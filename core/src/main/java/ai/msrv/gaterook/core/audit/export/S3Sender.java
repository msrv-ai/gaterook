package ai.msrv.gaterook.core.audit.export;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity;
import software.amazon.awssdk.utils.http.SdkHttpUtils;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * An S3 bucket ({@code PutObject} signed with SigV4, sent with {@code java.net.http}): one object per batch,
 * {@code <prefix><yyyy>/<mm>/<dd>/tool-calls-<from>-<to>.jsonl} (or {@code .csv}) after the date of its first row. The
 * name comes from the cursor range, so the retry of a batch writes the same object again rather than a second copy.
 * Virtual-hosted addresses on AWS, path-style for a bucket with dots and for an S3-compatible endpoint.
 * {@code Content-MD5} goes with every object: buckets with Object Lock require it.
 */
public final class S3Sender implements ExportSender {

  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneOffset.UTC);
  private static final Pattern XML_CODE = Pattern.compile("<Code>([^<]*)</Code>");
  private static final Pattern XML_MESSAGE = Pattern.compile("<Message>([^<]*)</Message>");

  private final ExportSettings.S3 settings;
  private final AwsCredentialsProvider credentials;
  private final HttpClient http;
  private final Clock clock;
  private final Duration timeout;

  public S3Sender(ExportSettings.S3 settings, AwsCredentialsProvider credentials, HttpClient http, Clock clock,
      Duration timeout) {
    this.settings = settings;
    this.credentials = credentials;
    this.http = http;
    this.clock = clock;
    this.timeout = timeout;
  }

  @Override
  public long send(Batch batch) throws ExportException {
    boolean csv = settings.format().equals("csv");
    byte[] body = csv ? ExportRecords.csv(batch.records()) : ExportRecords.jsonl(batch.records());
    put(objectKey(batch), body, csv ? "text/csv; charset=utf-8" : "application/x-ndjson");
    return body.length;
  }

  @Override
  public String test() throws ExportException {
    ObjectNode probe = JsonNodeFactory.instance.objectNode();
    probe.put("type", "connection-test");
    probe.put("at", clock.instant().toString());
    String key = settings.prefix() + "mcp-gateway-connection-test.json";
    put(key, (probe + "\n").getBytes(StandardCharsets.UTF_8), "application/json");
    return "Wrote s3://" + settings.bucket() + "/" + key;
  }

  /** The object of a batch: its date folder and its range, the same on every attempt. */
  String objectKey(Batch batch) {
    String ext = settings.format().equals("csv") ? ".csv" : ".jsonl";
    return settings.prefix() + DAY.format(batch.firstAt()) + "/tool-calls-" + batch.key() + ext;
  }

  /**
   * Where object {@code key} lives. The key goes out percent-encoded as S3 encodes it for the signature (all but
   * {@code A-Z a-z 0-9 - _ . ~} and {@code /}): the path is signed as sent, and a prefix may hold {@code !*'()}, which
   * a URI carries raw but S3's canonical request doesn't — raw, every PUT would end in SignatureDoesNotMatch.
   */
  URI objectUri(String key) {
    String path = SdkHttpUtils.urlEncodeIgnoreSlashes(key);
    if (settings.endpoint() != null) {
      String base = settings.endpoint().toString();
      if (settings.pathStyle()) {
        return URI.create(base + "/" + settings.bucket() + "/" + path);
      }
      URI e = settings.endpoint();
      return URI.create(e.getScheme() + "://" + settings.bucket() + "." + e.getRawAuthority() + "/" + path);
    }
    String host = "s3." + settings.region() + ".amazonaws.com";
    return settings.pathStyle() ? URI.create("https://" + host + "/" + settings.bucket() + "/" + path)
        : URI.create("https://" + settings.bucket() + "." + host + "/" + path);
  }

  private void put(String key, byte[] body, String contentType) throws ExportException {
    URI uri = objectUri(key);
    AwsCredentialsIdentity identity;
    try {
      identity = credentials.resolveCredentials();
    } catch (SdkException e) {
      throw new ExportException("No AWS credentials for " + settings.describe() + ": " + e.getMessage(), e);
    }
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Content-Type", contentType);
    headers.put("Content-MD5", Base64.getEncoder().encodeToString(md5(body)));
    Map<String, List<String>> signed = S3Signing.sign(SdkHttpMethod.PUT, uri, headers, body, identity,
        settings.region(), clock);
    HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(timeout)
        .PUT(HttpRequest.BodyPublishers.ofByteArray(body));
    signed.forEach((name, values) -> {
      if (!name.equalsIgnoreCase("Host") && !name.equalsIgnoreCase("Content-Length")) {
        values.forEach(v -> request.header(name, v));
      }
    });
    HttpResponse<String> response = ExportHttp.exchange(http, request.build(), timeout);
    if (response.statusCode() / 100 != 2) {
      throw new ExportException(error(response));
    }
  }

  /** S3's own words: {@code 403 AccessDenied: Access Denied}. */
  static String error(HttpResponse<String> response) {
    String body = response.body() == null ? "" : response.body();
    Matcher code = XML_CODE.matcher(body);
    Matcher message = XML_MESSAGE.matcher(body);
    StringBuilder out = new StringBuilder().append(response.statusCode());
    if (code.find()) {
      out.append(' ').append(code.group(1));
    }
    if (message.find()) {
      out.append(": ").append(message.group(1));
    } else if (body.isBlank()) {
      out.append(switch (response.statusCode()) {
        case 403 -> ": access denied";
        case 404 -> ": no such bucket";
        default -> "";
      });
    }
    return out.toString();
  }

  private static byte[] md5(byte[] body) {
    try {
      return MessageDigest.getInstance("MD5").digest(body);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
