package ai.msrv.gaterook.connectors.s3;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.support.OutputSanitizer;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.w3c.dom.Element;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4FamilyHttpSigner;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.SignedRequest;
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity;

/**
 * S3's REST API through the core's guarded egress ({@code ctx.egress()}): the environment's address policy applies to
 * every endpoint, so a MinIO on a private network needs its range allowed, and no redirect is ever followed. Each
 * request is signed with SigV4 for {@code s3} in the connection's region, with {@code x-amz-content-sha256} of its
 * body, no double URL encoding and no path normalization: the path S3 checks is the path that is sent.
 *
 * <p>Amazon S3 takes its credentials from connectors/cloud-auth (a role assumed under the call's session policy);
 * another endpoint its access key, both tainted so that the core masks any echo of them. On Amazon S3 without a
 * bucket list, every request carries {@code x-amz-expected-bucket-owner} with the credentials' account: S3 then
 * refuses buckets of other accounts. Nothing is retried; a change whose answer is lost is reported as unknown.
 */
final class S3Api {

  static final Duration MARGIN = Duration.ofMillis(150);
  static final Duration MAX_REQUEST = Duration.ofSeconds(30);
  /** Error answers are small; this much of one is read. */
  static final long MAX_ERROR = 64 * 1024;
  private static final Pattern ACCOUNT = Pattern.compile("^\\d{12}$");
  private static final AwsV4HttpSigner SIGNER = AwsV4HttpSigner.create();
  /** Account ids of connections without a bucket list, by connection id and version; an hour each. */
  private static final Duration ACCOUNT_TTL = Duration.ofHours(1);

  private final CloudCredentials cloud;
  private final Map<String, Account> accounts = new ConcurrentHashMap<>();

  private record Account(String id, long atNanos) {}

  S3Api(CloudCredentials cloud) {
    this.cloud = cloud;
  }

  /** One call's way to S3: the connection, its settings and secrets, the call's context. */
  record Call(ConnectionConfig cfg, S3Settings settings, SecretResolver secrets, ExecContext ctx) {}

  /** S3's answer: status, headers by lower-case name, the body as far as it was read. */
  record Answer(int status, Map<String, List<String>> headers, byte[] body, boolean truncated) {
    String header(String name) {
      List<String> v = headers.get(name);
      return v == null || v.isEmpty() ? null : v.getFirst();
    }
  }

  URI base(S3Settings s) {
    return s.base(cloud.endpoints());
  }

  /** The URL of {@code r} on the connection's endpoint, path or virtual-hosted style. */
  URI uri(S3Settings s, S3Request r) {
    URI base = base(s);
    return r.uri(base, r.virtualHosted(base, s.pathStyle()));
  }

  /**
   * Sends {@code r} under {@code sessionPolicy} (Amazon S3 with a role) and returns any answer of S3, an error status
   * too: callers decide what a 404 means. A failed connection, a timeout or a guard's refusal is an exception; a
   * change ({@code PUT}, {@code DELETE}) whose answer is lost after it was sent is {@code OUTCOME_UNKNOWN}.
   *
   * @param maxBytes how much of the body is read
   */
  Answer send(Call c, S3Request r, String sessionPolicy, long maxBytes) throws ConnectorException {
    S3Request request = r;
    String owner = r.bucket() == null ? null : expectedOwner(c, sessionPolicy);
    if (owner != null) {
      request = request.header("x-amz-expected-bucket-owner", owner);
      if (r.headers().containsKey("x-amz-copy-source")) {
        request = request.header("x-amz-source-expected-bucket-owner", owner);
      }
    }
    URI uri = uri(c.settings(), request);
    Duration left = c.ctx().remaining().minus(MARGIN);
    if (left.isNegative() || left.isZero()) {
      throw ConnectorException.timeout("S3 didn't answer " + r.operation() + " in time.");
    }
    Map<String, List<String>> headers = sign(c, request, uri, credentials(c, sessionPolicy));
    ExecContext.EgressResponse response;
    try {
      response = c.ctx().egress().send(new ExecContext.EgressRequest(request.method(), uri, headers, request.body(),
          left.compareTo(MAX_REQUEST) > 0 ? MAX_REQUEST : left), Math.max(maxBytes, MAX_ERROR));
    } catch (ConnectorException e) {
      // The guard reports a request whose headers went out and whose answer was lost as unknown; a timeout or a
      // refusal before that sent nothing.
      if (e.kind() == ConnectorException.Kind.OUTCOME_UNKNOWN) {
        throw ConnectorException.outcomeUnknown("The connection to " + c.settings().target() + " ended after the "
            + r.operation() + " was sent, so whether it ran is not known. Check the object with s3_head_object "
            + "before retrying.", e);
      }
      throw e;
    }
    return new Answer(response.status(), response.headers(), response.body(), response.truncated());
  }

  /** The credentials of a call: Amazon S3's through cloud-auth, or the endpoint's access key. */
  AwsCredentialsIdentity credentials(Call c, String sessionPolicy) throws ConnectorException {
    S3Settings s = c.settings();
    if (s.aws()) {
      try {
        return provider(c, sessionPolicy).resolveCredentials();
      } catch (SdkException e) {
        throw ConnectorException.unavailable("The AWS credentials of " + c.cfg().name() + " couldn't be loaded: "
            + firstLine(e.getMessage()) + ".", e);
      }
    }
    try (SecretResolver.Secret secret = c.secrets().secret(S3Settings.SECRET_KEY)) {
      String value = secret.unsafeString();
      c.ctx().taint(value);
      return AwsBasicCredentials.create(s.accessKeyId(), value);
    }
  }

  /** Amazon S3's credentials provider for {@code sessionPolicy}, its every resolution tainted. */
  AwsCredentialsProvider provider(Call c, String sessionPolicy) throws ConnectorException {
    return CloudCredentials.tainting(cloud.aws(new CloudCredentials.Owner(c.cfg().id(), c.cfg().version(),
        c.cfg().name()), c.settings().credentials(), sessionPolicy, c.secrets()), c.ctx()::taint);
  }

  /** The caller's identity on Amazon S3 ({@code sts:GetCallerIdentity}). */
  CloudCredentials.CallerIdentity identity(Call c, String sessionPolicy) throws ConnectorException {
    try {
      return cloud.callerIdentity(provider(c, sessionPolicy), c.settings().region());
    } catch (SdkException e) {
      throw ConnectorException.unavailable("The AWS credentials of " + c.cfg().name() + " couldn't be loaded: "
          + firstLine(e.getMessage()) + ".", e);
    }
  }

  /**
   * The account S3 must find as the bucket's owner: on Amazon S3 without a bucket list, the credentials' own
   * ({@code sts:GetCallerIdentity}, kept for an hour per connection version); otherwise {@code null}.
   */
  String expectedOwner(Call c, String sessionPolicy) throws ConnectorException {
    if (!c.settings().aws() || c.settings().listed()) {
      return null;
    }
    String key = c.cfg().id() + "|" + c.cfg().version();
    Account known = accounts.get(key);
    if (known != null && System.nanoTime() - known.atNanos() < ACCOUNT_TTL.toNanos()) {
      return known.id();
    }
    String account = identity(c, sessionPolicy).account();
    if (account == null || !ACCOUNT.matcher(account).matches()) {
      throw ConnectorException.unavailable("AWS didn't say which account the credentials of " + c.cfg().name()
          + " belong to, so GateRook can't keep calls to its buckets.", null);
    }
    if (accounts.size() > 1_000) {
      accounts.clear();
    }
    accounts.put(key, new Account(account, System.nanoTime()));
    return account;
  }

  /** The account of an earlier lookup or the last test, for previews without I/O; {@code null} when unknown. */
  String knownOwner(ConnectionConfig cfg, S3Settings s) {
    if (!s.aws() || s.listed()) {
      return null;
    }
    Account known = accounts.get(cfg.id() + "|" + cfg.version());
    if (known != null) {
      return known.id();
    }
    String tested = cfg.metadata() == null ? null : cfg.metadata().path("account").asString(null);
    return tested != null && ACCOUNT.matcher(tested).matches() ? tested : null;
  }

  void forget(String connectionId) {
    accounts.keySet().removeIf(k -> k.startsWith(connectionId + "|"));
  }

  /** SigV4 for {@code s3}: the request's headers with the signer's, as the egress sends them. */
  static Map<String, List<String>> sign(Call c, S3Request r, URI uri, AwsCredentialsIdentity credentials) {
    SdkHttpRequest.Builder unsigned = SdkHttpRequest.builder()
        .method(SdkHttpMethod.fromValue(r.method()))
        .protocol(uri.getScheme())
        .host(uri.getHost())
        .encodedPath(uri.getRawPath());
    if (uri.getPort() >= 0) {
      unsigned.port(uri.getPort());
    }
    r.query().forEach(unsigned::putRawQueryParameter);
    r.headers().forEach(unsigned::putHeader);
    SignedRequest signed = SIGNER.sign(s -> s.identity(credentials)
        .request(unsigned.build())
        .payload(r.body() == null || r.body().length == 0 ? null : ContentStreamProvider.fromByteArrayUnsafe(r.body()))
        .putProperty(AwsV4FamilyHttpSigner.SERVICE_SIGNING_NAME, "s3")
        .putProperty(AwsV4HttpSigner.REGION_NAME, c.settings().region())
        .putProperty(AwsV4FamilyHttpSigner.DOUBLE_URL_ENCODE, false)
        .putProperty(AwsV4FamilyHttpSigner.NORMALIZE_PATH, false)
        .putProperty(AwsV4FamilyHttpSigner.PAYLOAD_SIGNING_ENABLED, true)
        .putProperty(HttpSigner.SIGNING_CLOCK, c.ctx().clock()));
    Map<String, List<String>> out = new LinkedHashMap<>();
    signed.request().forEachHeader((name, values) -> out.put(name, List.copyOf(values)));
    return out;
  }

  // ── Errors ──────────────────────────────────────────────────────────────────────────────────────────────────

  /** S3's {@code <Error>} answer: its code, message and the region it names, if any. */
  record S3Error(String code, String message, String region) {}

  static S3Error parseError(Answer a) {
    String code = "";
    String message = "";
    String region = a.header("x-amz-bucket-region");
    if (a.body() != null && a.body().length > 0 && !a.truncated()) {
      try {
        Element e = S3Xml.parse(a.body());
        if (S3Xml.name(e).equals("Error")) {
          code = nonNull(S3Xml.text(e, "Code"));
          message = nonNull(S3Xml.text(e, "Message"));
          if (region == null) {
            region = S3Xml.text(e, "Region");
          }
        }
      } catch (IOException notXml) {
        // An answer that isn't S3's XML: the status says what is known.
      }
    }
    return new S3Error(code, OutputSanitizer.sanitize(firstLine(message)), region);
  }

  /**
   * S3's error as what the agent can do about it. A change that failed with a server error (other than
   * {@code SlowDown}, which S3 sends before it starts) may still have been applied: its outcome is unknown.
   */
  static ConnectorException error(Answer a, S3Request r, Call c, boolean change) {
    S3Error e = parseError(a);
    String what = r.bucket() == null ? "" : r.key() == null ? Names.shown(r.bucket())
        : Names.shown(r.bucket()) + "/" + Names.shown(r.key());
    String said = e.message().isBlank() ? (e.code().isBlank() ? "HTTP " + a.status() : e.code()) : e.message();
    String region = c.settings().region();
    if (a.status() == 301 || a.status() == 307 || "PermanentRedirect".equals(e.code())
        || "AuthorizationHeaderMalformed".equals(e.code()) && e.region() != null && !e.region().equals(region)) {
      return ConnectorException.badInput((r.bucket() == null ? "The bucket" : Names.shown(r.bucket())) + " is in "
          + (e.region() == null ? "another region" : e.region()) + ", not " + region + ". Add a connection for "
          + "that region.");
    }
    return switch (e.code()) {
      case "NoSuchBucket" -> ConnectorException.badInput("There is no bucket " + Names.shown(r.bucket())
          + ". Call s3_list_buckets to see the buckets.");
      case "NoSuchKey" -> ConnectorException.badInput("There is no object " + Names.shown(r.key()) + " in "
          + Names.shown(r.bucket()) + ". Call s3_list_objects to see its keys.");
      case "NoSuchVersion" -> ConnectorException.badInput("That version of " + what + " doesn't exist.");
      case "AccessDenied", "AllAccessDisabled", "AccountProblem" -> denied(c, r, said);
      case "InvalidAccessKeyId", "SignatureDoesNotMatch", "ExpiredToken", "InvalidToken", "TokenRefreshRequired",
          "InvalidSecurity", "AuthorizationHeaderMalformed", "RequestTimeTooSkewed" ->
          ConnectorException.unavailable(c.settings().target() + " refused the credentials of " + c.cfg().name()
              + ": " + said + ".", null);
      case "PreconditionFailed", "ConditionalRequestConflict" -> ConnectorException.resourceError(
          r.method().equals("PUT") ? "The object at " + what + " isn't what the request expected, so nothing "
              + "changed: " + said + "." : "The object " + what + " changed while it was read. Read it again.", null);
      case "InvalidRange" -> ConnectorException.badInput("The offset is past the end of " + what + ".");
      case "InvalidObjectState" -> ConnectorException.badInput(what + " is archived (" + said + "): restore it "
          + "before reading it.");
      case "SlowDown", "ServiceUnavailable" -> ConnectorException.resourceError(c.settings().target()
          + " is throttling requests" + (change ? ", so nothing changed" : "") + ". Try again in a moment.", null);
      case "EntityTooLarge", "InvalidArgument", "InvalidRequest", "InvalidBucketName", "KeyTooLongError",
          "MetadataTooLarge", "InvalidStorageClass", "MalformedXML" ->
          ConnectorException.badInput(c.settings().target() + " refused " + r.operation() + ": " + said + ".");
      default -> {
        if (a.status() == 404) {
          yield ConnectorException.badInput(r.key() != null ? "There is no object " + Names.shown(r.key()) + " in "
              + Names.shown(r.bucket()) + "." : "There is no bucket " + Names.shown(r.bucket()) + ".");
        }
        if (a.status() == 403) {
          yield denied(c, r, said);
        }
        if (a.status() == 412) {
          yield ConnectorException.resourceError("The object at " + what + " isn't what the request expected, so "
              + "nothing changed.", null);
        }
        if (a.status() >= 500) {
          yield change ? ConnectorException.outcomeUnknown(c.settings().target() + " failed with " + a.status()
              + " during the " + r.operation() + ", so whether it ran is not known. Check the object with "
              + "s3_head_object before retrying.", null)
              : ConnectorException.unavailable(c.settings().target() + " failed with " + a.status() + ": " + said
                  + ".", null);
        }
        yield ConnectorException.resourceError(c.settings().target() + " refused " + r.operation() + " ("
            + a.status() + "): " + said + ".", null);
      }
    };
  }

  private static ConnectorException denied(Call c, S3Request r, String said) {
    StringBuilder text = new StringBuilder("The credentials of ").append(c.cfg().name()).append(" may not ")
        .append(r.operation()).append(r.bucket() == null ? "" : " on " + Names.shown(r.bucket()));
    if (r.method().equals("HEAD") && r.key() != null) {
      text.append(", or the object doesn't exist");
    }
    text.append(": ").append(said).append('.');
    if (c.settings().aws() && !c.settings().listed()) {
      text.append(" Without a bucket list, GateRook uses only buckets of the credentials' own account.");
    }
    return ConnectorException.resourceError(text.toString(), null);
  }

  private static String nonNull(String s) {
    return s == null ? "" : s.strip();
  }

  static String firstLine(String s) {
    if (s == null) {
      return "";
    }
    int nl = s.indexOf('\n');
    String line = (nl < 0 ? s : s.substring(0, nl)).strip();
    return line.endsWith(".") ? line.substring(0, line.length() - 1) : line;
  }

  /** The values of a header that starts with {@code prefix}, by the rest of its name. */
  static Map<String, String> withPrefix(Answer a, String prefix) {
    Map<String, String> out = new LinkedHashMap<>();
    List<String> names = new ArrayList<>(a.headers().keySet());
    names.sort(null);
    for (String name : names) {
      String lower = name.toLowerCase(Locale.ROOT);
      if (lower.startsWith(prefix) && lower.length() > prefix.length()) {
        out.put(lower.substring(prefix.length()), String.join(", ", a.headers().get(name)));
      }
    }
    return out;
  }
}
