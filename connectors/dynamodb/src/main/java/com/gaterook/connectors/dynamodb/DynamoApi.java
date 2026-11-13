package com.gaterook.connectors.dynamodb;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.ExecContext;
import com.gaterook.connector.api.SecretResolver;
import com.gaterook.connectors.cloudauth.CloudCredentials;
import com.gaterook.connectors.support.OutputSanitizer;
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
 * DynamoDB's JSON API (version 2012-08-10) on {@code java.net.http}: {@code POST /} to the region's endpoint with
 * {@code X-Amz-Target: DynamoDB_20120810.<Operation>}, signed with SigV4 for {@code dynamodb}, every header the
 * request carries included. The host is fixed by the region (a development build's test endpoint
 * {@code aws.dynamodb} replaces it, with the test credentials that go with it); redirects are never followed and
 * nothing is retried. Each request runs against the call's deadline, and an answer is read up to {@link #MAX_BODY}.
 */
final class DynamoApi {

  static final String TARGET = "DynamoDB_20120810.";
  static final String CONTENT_TYPE = "application/x-amz-json-1.0";
  /** A Query or Scan page holds at most 1 MB of items; their JSON with types is a few times that. */
  static final long MAX_BODY = 16L * 1024 * 1024;
  static final Duration MARGIN = Duration.ofMillis(150);
  static final Duration MAX_REQUEST = Duration.ofSeconds(30);
  private static final Set<String> RESTRICTED = Set.of("host", "content-length", "connection", "expect", "upgrade");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final CloudCredentials cloud;
  private final HttpClient http = HttpClient.newBuilder()
      .version(HttpClient.Version.HTTP_1_1)
      .followRedirects(HttpClient.Redirect.NEVER)
      .proxy(HttpClient.Builder.NO_PROXY)
      .connectTimeout(Duration.ofSeconds(5))
      .build();

  DynamoApi(CloudCredentials cloud) {
    this.cloud = cloud;
  }

  /** One call's way to DynamoDB: the connection, its settings and secrets, the call's context. */
  record Call(ConnectionConfig cfg, DynamoSettings settings, SecretResolver secrets, ExecContext ctx) {}

  /** The endpoint of the connection's region, or the test endpoint of a development build. */
  URI endpoint(DynamoSettings s) {
    URI test = cloud.endpoints().service("aws.dynamodb");
    return test != null ? test : URI.create(s.endpoint() + "/");
  }

  /**
   * Runs {@code operation} with {@code body} under {@code sessionPolicy} (with a role) and returns DynamoDB's answer.
   *
   * @param change whether the request changes data: a connection lost after sending it leaves the outcome unknown
   */
  JsonNode call(Call call, String operation, ObjectNode body, String sessionPolicy, boolean change)
      throws ConnectorException {
    String json = JSON.writeValueAsString(body);
    URI uri = endpoint(call.settings());
    Duration left = call.ctx().remaining().minus(MARGIN);
    if (left.isNegative() || left.isZero()) {
      throw ConnectorException.timeout("DynamoDB didn't answer " + operation + " in time.");
    }
    HttpRequest.Builder request = HttpRequest.newBuilder(uri)
        .timeout(left.compareTo(MAX_REQUEST) > 0 ? MAX_REQUEST : left)
        .header("Content-Type", CONTENT_TYPE)
        .header("X-Amz-Target", TARGET + operation)
        .POST(HttpRequest.BodyPublishers.ofString(json));
    sign(call, request, uri, operation, json, sessionPolicy);
    HttpResponse<InputStream> response;
    byte[] answer;
    try {
      response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
      try (InputStream in = response.body()) {
        answer = read(in);
      }
    } catch (HttpConnectTimeoutException e) {
      throw ConnectorException.unavailable("Can't connect to DynamoDB in " + call.settings().region()
          + " (timed out). Check that this computer can reach AWS.", e);
    } catch (HttpTimeoutException e) {
      if (change) {
        throw ConnectorException.outcomeUnknown("DynamoDB didn't answer the change in time, so whether it ran is "
            + "not known. Check the item before retrying.", e);
      }
      throw ConnectorException.timeout("DynamoDB didn't answer " + operation + " in time.");
    } catch (ConnectException | SSLException e) {
      throw ConnectorException.unavailable("Can't connect to DynamoDB in " + call.settings().region() + ": "
          + reason(e) + ".", e);
    } catch (IOException e) {
      if (change) {
        throw ConnectorException.outcomeUnknown("The connection to DynamoDB failed during the change, so whether "
            + "it ran is not known. Check the item before retrying.", e);
      }
      throw ConnectorException.unavailable("DynamoDB " + operation + " failed: " + reason(e) + ".", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw ConnectorException.timeout("DynamoDB " + operation + " was cancelled.");
    }
    JsonNode tree;
    try {
      tree = JSON.readTree(answer.length == 0 ? "{}" : new String(answer, StandardCharsets.UTF_8));
    } catch (JacksonException e) {
      throw ConnectorException.resourceError("DynamoDB answered " + response.statusCode() + " to " + operation
          + " with something that isn't JSON.", null);
    }
    if (response.statusCode() / 100 != 2) {
      throw error(response.statusCode(), tree, operation, call, change);
    }
    return tree;
  }

  /** SigV4 for {@code dynamodb}: the signer's headers onto the request {@code java.net.http} sends. */
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
        .putProperty(AwsV4FamilyHttpSigner.SERVICE_SIGNING_NAME, "dynamodb")
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

  /** DynamoDB's {@code __type} and message as what the agent can do about it. */
  static ConnectorException error(int status, JsonNode tree, String operation, Call call, boolean change) {
    String type = tree.path("__type").asString("");
    type = type.substring(type.lastIndexOf('#') + 1);
    String message = OutputSanitizer.sanitize(firstLine(tree.path("message").asString(
        tree.path("Message").asString(""))));
    String said = message.isBlank() ? type : message;
    return switch (type) {
      case "ResourceNotFoundException" -> ConnectorException.badInput(said + ". Call dynamodb_list_tables to see the "
          + "tables.");
      case "ValidationException", "SerializationException" -> ConnectorException.badInput("DynamoDB refused "
          + operation + ": " + said + ".");
      case "ConditionalCheckFailedException" -> ConnectorException.resourceError("The statement's condition "
          + "failed, so nothing changed: " + said + ".", null);
      case "DuplicateItemException" -> ConnectorException.resourceError("An item with this key already exists, so "
          + "nothing changed.", null);
      case "AccessDeniedException" -> ConnectorException.resourceError("The AWS credentials of " + call.cfg().name()
          + " may not do this: " + said + ".", null);
      case "UnrecognizedClientException", "InvalidSignatureException", "ExpiredTokenException",
          "IncompleteSignatureException", "MissingAuthenticationTokenException" ->
          ConnectorException.unavailable("AWS refused the credentials of " + call.cfg().name() + ": " + said + ".",
              null);
      case "ProvisionedThroughputExceededException", "ThrottlingException", "RequestLimitExceeded",
          "LimitExceededException" -> ConnectorException.resourceError("DynamoDB throttled the request: " + said
          + ". Try again in a moment, or read fewer items.", null);
      case "TransactionConflictException" -> ConnectorException.resourceError("Another transaction changes this item "
          + "now, so nothing changed. Try again.", null);
      default -> status >= 500
          ? (change ? ConnectorException.outcomeUnknown("DynamoDB failed with " + status + " during the change, so "
              + "whether it ran is not known. Check the item before retrying.", null)
              : ConnectorException.unavailable("DynamoDB failed with " + status + ": " + said + ".", null))
          : ConnectorException.resourceError("DynamoDB refused " + operation + " (" + status + "): " + said + ".",
              null);
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
        throw ConnectorException.resourceError("DynamoDB's answer is larger than " + (MAX_BODY >> 20) + " MiB.", null);
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
