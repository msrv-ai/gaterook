package ai.msrv.gaterook.connectors.redshift;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ConnectorException.Kind;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.support.OutputSanitizer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.SSLException;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4FamilyHttpSigner;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.SignedRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Redshift Data API (version 2019-12-20) on {@code java.net.http}: {@code POST /} to the region's endpoint with
 * {@code X-Amz-Target: RedshiftData.<Operation>}, JSON 1.1, signed with SigV4 for {@code redshift-data}, every header
 * the request carries included. The host is fixed by the region (a development build's test endpoint
 * {@code aws.redshift-data} replaces it, with the test credentials that go with it); redirects are never followed and
 * nothing is retried. Each request runs against the call's deadline, and an answer is read up to {@link #MAX_BODY}.
 */
final class RedshiftApi {

  static final String TARGET = "RedshiftData.";
  static final String CONTENT_TYPE = "application/x-amz-json-1.1";
  static final String SIGNING_NAME = "redshift-data";
  /** A page of GetStatementResult; its typed JSON is a few times the rows' bytes. */
  static final long MAX_BODY = 16L * 1024 * 1024;
  static final Duration MARGIN = Duration.ofMillis(150);
  static final Duration MAX_REQUEST = Duration.ofSeconds(35);
  private static final Set<String> RESTRICTED = Set.of("host", "content-length", "connection", "expect", "upgrade");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final CloudCredentials cloud;
  private final HttpClient http = HttpClient.newBuilder()
      .version(HttpClient.Version.HTTP_1_1)
      .followRedirects(HttpClient.Redirect.NEVER)
      .proxy(HttpClient.Builder.NO_PROXY)
      .connectTimeout(Duration.ofSeconds(5))
      .build();

  RedshiftApi(CloudCredentials cloud) {
    this.cloud = cloud;
  }

  /** One call's way to the Data API: the connection, its settings and secrets, the call's context. */
  record Call(ConnectionConfig cfg, RedshiftSettings settings, SecretResolver secrets, ExecContext ctx) {}

  /** The endpoint of the connection's region, or the test endpoint of a development build. */
  URI endpoint(RedshiftSettings s) {
    URI test = cloud.endpoints().service("aws.redshift-data");
    return test != null ? test : URI.create(s.endpoint() + "/");
  }

  /**
   * Runs {@code operation} with {@code body} under {@code sessionPolicy} (with a role) and returns the answer.
   *
   * @param change whether the request starts a change: a connection lost after sending it leaves the outcome unknown
   */
  JsonNode call(Call call, String operation, ObjectNode body, String sessionPolicy, boolean change)
      throws ConnectorException {
    String json = JSON.writeValueAsString(body);
    URI uri = endpoint(call.settings());
    Duration left = call.ctx().remaining().minus(MARGIN);
    if (left.isNegative() || left.isZero()) {
      throw ConnectorException.timeout("Redshift didn't answer " + operation + " in time.");
    }
    HttpRequest.Builder request = HttpRequest.newBuilder(uri)
        .timeout(left.compareTo(MAX_REQUEST) > 0 ? MAX_REQUEST : left)
        .header("Content-Type", CONTENT_TYPE)
        .header("X-Amz-Target", TARGET + operation)
        .POST(HttpRequest.BodyPublishers.ofString(json));
    sign(call, request, uri, operation, json, sessionPolicy);
    int status;
    byte[] answer;
    try {
      HttpResponse<InputStream> response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
      status = response.statusCode();
      try (InputStream in = response.body()) {
        answer = read(in);
      }
    } catch (HttpConnectTimeoutException e) {
      throw ConnectorException.unavailable("Can't connect to the Redshift Data API in " + call.settings().region()
          + " (timed out). Check that this computer can reach AWS.", e);
    } catch (HttpTimeoutException e) {
      if (change) {
        throw ConnectorException.outcomeUnknown("Redshift didn't answer the change in time, so whether it ran is not "
            + "known. Check the table before retrying.", e);
      }
      throw ConnectorException.timeout("Redshift didn't answer " + operation + " in time.");
    } catch (ConnectException | SSLException e) {
      throw ConnectorException.unavailable("Can't connect to the Redshift Data API in " + call.settings().region()
          + ": " + reason(e) + ".", e);
    } catch (IOException e) {
      if (change) {
        throw ConnectorException.outcomeUnknown("The connection to Redshift failed during the change, so whether it "
            + "ran is not known. Check the table before retrying.", e);
      }
      throw ConnectorException.unavailable("Redshift " + operation + " failed: " + reason(e) + ".", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw ConnectorException.timeout("Redshift " + operation + " was cancelled.");
    }
    JsonNode tree;
    try {
      tree = JSON.readTree(answer.length == 0 ? "{}" : new String(answer, StandardCharsets.UTF_8));
    } catch (JacksonException e) {
      throw ConnectorException.resourceError("Redshift answered " + status + " to " + operation + " with something "
          + "that isn't JSON.", null);
    }
    if (status / 100 != 2) {
      throw error(status, tree, operation, call, change);
    }
    return tree;
  }

  /** SigV4 for {@code redshift-data}: the signer's headers onto the request {@code java.net.http} sends. */
  private void sign(Call call, HttpRequest.Builder b, URI uri, String operation, String body, String sessionPolicy)
      throws ConnectorException {
    AwsCredentials credentials;
    try {
      credentials = CloudCredentials.tainting(cloud.aws(new CloudCredentials.Owner(call.cfg().id(),
          call.cfg().version(), call.cfg().name()), call.settings().credentials(), sessionPolicy, call.secrets()),
          call.ctx()::taint).resolveCredentials();
    } catch (SdkException e) {
      throw ConnectorException.unavailable("The AWS credentials of " + call.cfg().name() + " couldn't be loaded: "
          + firstLine(e.getMessage()), e);
    }
    SdkHttpRequest unsigned = SdkHttpRequest.builder().method(SdkHttpMethod.POST).uri(uri)
        .putHeader("Content-Type", CONTENT_TYPE)
        .putHeader("X-Amz-Target", TARGET + operation)
        .build();
    SignedRequest signed = AwsV4HttpSigner.create().sign(r -> r.identity(credentials).request(unsigned)
        .payload(ContentStreamProvider.fromUtf8String(body))
        .putProperty(AwsV4FamilyHttpSigner.SERVICE_SIGNING_NAME, SIGNING_NAME)
        .putProperty(AwsV4HttpSigner.REGION_NAME, call.settings().region()));
    for (Map.Entry<String, List<String>> h : signed.request().headers().entrySet()) {
      String name = h.getKey().toLowerCase(Locale.ROOT);
      if (RESTRICTED.contains(name) || name.equals("content-type") || name.equals("x-amz-target")) {
        continue;
      }
      for (String v : h.getValue()) {
        b.header(h.getKey(), v);
      }
    }
  }

  /** A refusal of the Data API, with its error type ({@code ValidationException}) for the callers that act on it. */
  static final class ApiError extends ConnectorException {
    private static final long serialVersionUID = 1L;
    private final String type;

    ApiError(Kind kind, String message, String type) {
      super(kind, message, null);
      this.type = type;
    }

    String type() {
      return type;
    }
  }

  /** The Data API's {@code __type} and message as what the agent can do about it. */
  static ApiError error(int status, JsonNode tree, String operation, Call call, boolean change) {
    String type = tree.path("__type").asString("");
    type = type.substring(type.lastIndexOf('#') + 1);
    String message = OutputSanitizer.sanitize(firstLine(tree.path("message").asString(
        tree.path("Message").asString(""))));
    String said = message.isBlank() ? type : message;
    String who = call.cfg().name();
    RedshiftSettings s = call.settings();
    return switch (type) {
      case "ValidationException" -> new ApiError(Kind.BAD_INPUT, "Redshift refused " + operation + ": " + said + ".",
          type);
      case "ResourceNotFoundException" -> new ApiError(Kind.BAD_INPUT, said + ". Check the " + s.warehouse()
          + " and the database " + s.database() + "; Redshift keeps a query's result for 24 hours.", type);
      case "ActiveStatementsExceededException", "ActiveSessionsExceededException",
          "ActiveWaitingRequestsExceededException" -> new ApiError(Kind.RESOURCE_ERROR, "Redshift runs too many "
          + "statements now: " + said + ". Try again in a moment.", type);
      case "DatabaseConnectionException" -> new ApiError(Kind.RESOURCE_UNAVAILABLE, "The Data API couldn't connect "
          + "to the database " + s.database() + " on " + s.warehouse() + ": " + said + ". Check that it is available "
          + "and that " + s.signIn() + " may sign in.", type);
      case "QueryTimeoutException" -> new ApiError(Kind.TIMEOUT, "Redshift didn't answer " + operation + " in time: "
          + said + ".", type);
      case "AccessDeniedException" -> new ApiError(Kind.RESOURCE_ERROR, "The AWS credentials of " + who + " may not "
          + "do this: " + said + ".", type);
      case "UnrecognizedClientException", "InvalidSignatureException", "ExpiredTokenException",
          "IncompleteSignatureException", "MissingAuthenticationTokenException", "InvalidClientTokenId" ->
          new ApiError(Kind.RESOURCE_UNAVAILABLE, "AWS refused the credentials of " + who + ": " + said + ".", type);
      case "ThrottlingException" -> new ApiError(Kind.RESOURCE_ERROR, "The Data API throttled the request: " + said
          + ". Try again in a moment.", type);
      default -> status >= 500
          ? (change ? new ApiError(Kind.OUTCOME_UNKNOWN, "Redshift failed with " + status + " (" + type + ") during "
              + "the change, so whether it ran is not known. Check the table before retrying.", type)
              : new ApiError(Kind.RESOURCE_UNAVAILABLE, "Redshift " + operation + " failed with " + status + ": "
                  + said + ".", type))
          : new ApiError(Kind.RESOURCE_ERROR, "Redshift refused " + operation + " (" + status + "): " + said + ".",
              type);
    };
  }

  private static byte[] read(InputStream in) throws IOException, ConnectorException {
    ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
    byte[] buf = new byte[16384];
    long total = 0;
    int n;
    while ((n = in.read(buf)) > 0) {
      total += n;
      if (total > MAX_BODY) {
        throw ConnectorException.resourceError("Redshift's answer is larger than " + (MAX_BODY >> 20) + " MiB.", null);
      }
      out.write(buf, 0, n);
    }
    return out.toByteArray();
  }

  private static String reason(Throwable e) {
    String m = e.getMessage();
    return m == null || m.isBlank() ? e.getClass().getSimpleName() : firstLine(m);
  }

  static String firstLine(String s) {
    if (s == null) {
      return "";
    }
    int nl = s.indexOf('\n');
    String line = (nl < 0 ? s : s.substring(0, nl)).strip();
    return line.endsWith(".") ? line.substring(0, line.length() - 1) : line;
  }
}
