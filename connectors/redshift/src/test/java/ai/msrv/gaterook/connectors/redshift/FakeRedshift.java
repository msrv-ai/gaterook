package ai.msrv.gaterook.connectors.redshift;

import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudEndpoints;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * AWS on this computer: STS ({@code AssumeRole}, {@code GetCallerIdentity}) and the Redshift Data API by operation
 * ({@code X-Amz-Target: RedshiftData.<Operation>}). The test endpoint {@code aws.redshift-data} points here.
 */
final class FakeRedshift implements AutoCloseable {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final String SESSION_SECRET = "session-secret-cnry";
  static final String SESSION_TOKEN = "session-token-cnry";

  final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
  private final Map<String, Function<RecordedRequest, MockResponse>> handlers = new ConcurrentHashMap<>();
  private final MockWebServer server = new MockWebServer();
  final CloudCredentials cloud;

  FakeRedshift() throws IOException {
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        requests.add(request);
        String op = operation(request);
        Function<RecordedRequest, MockResponse> h = handlers.get(op);
        if (h != null) {
          return h.apply(request);
        }
        return switch (op) {
          case "AssumeRole" -> xml("<AssumeRoleResponse xmlns=\"https://sts.amazonaws.com/doc/2011-06-15/\">"
              + "<AssumeRoleResult><Credentials><AccessKeyId>ASIATESTSESSION00001</AccessKeyId><SecretAccessKey>"
              + SESSION_SECRET + "</SecretAccessKey><SessionToken>" + SESSION_TOKEN + "</SessionToken><Expiration>"
              + Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS) + "</Expiration>"
              + "</Credentials><AssumedRoleUser><AssumedRoleId>AROATEST:gaterook</AssumedRoleId><Arn>"
              + "arn:aws:sts::412398765012:assumed-role/gw-redshift/gaterook</Arn></AssumedRoleUser>"
              + "</AssumeRoleResult><ResponseMetadata><RequestId>1</RequestId></ResponseMetadata>"
              + "</AssumeRoleResponse>");
          case "GetCallerIdentity" -> xml("<GetCallerIdentityResponse xmlns=\"https://sts.amazonaws.com/doc/"
              + "2011-06-15/\"><GetCallerIdentityResult><Arn>" + (String.valueOf(request.getHeaders()
              .get("Authorization")).contains("Credential=ASIA")
              ? "arn:aws:sts::412398765012:assumed-role/gw-redshift/gaterook" : "arn:aws:iam::412398765012:user/reader")
              + "</Arn><UserId>AIDATEST</UserId><Account>412398765012</Account></GetCallerIdentityResult>"
              + "<ResponseMetadata><RequestId>2</RequestId></ResponseMetadata></GetCallerIdentityResponse>");
          default -> json(400, "{\"__type\": \"ValidationException\", \"message\": \"no fake for " + op + "\"}");
        };
      }
    });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
    URI base = URI.create("http://127.0.0.1:" + server.getPort());
    cloud = new CloudCredentials(new CloudEndpoints(null, null, null, base, null, null,
        Map.of("aws.redshift-data", base)), Clock.systemUTC());
  }

  /** The Data API operation of a request, or the STS action. */
  static String operation(RecordedRequest r) {
    String target = r.getHeaders().get("X-Amz-Target");
    if (target != null) {
      return target.substring(target.indexOf('.') + 1);
    }
    String body = r.getBody() == null ? "" : r.getBody().utf8();
    int at = body.indexOf("Action=");
    if (at < 0) {
      return r.getUrl().encodedPath();
    }
    int end = body.indexOf('&', at);
    return body.substring(at + 7, end < 0 ? body.length() : end);
  }

  FakeRedshift on(String operation, Function<RecordedRequest, MockResponse> answer) {
    handlers.put(operation, answer);
    return this;
  }

  FakeRedshift on(String operation, String body) {
    return on(operation, r -> json(200, body));
  }

  List<RecordedRequest> to(String operation) {
    return requests.stream().filter(r -> operation(r).equals(operation)).toList();
  }

  static JsonNode body(RecordedRequest r) {
    return JSON.readTree(r.getBody().utf8());
  }

  static MockResponse json(int code, String body) {
    return new MockResponse.Builder().code(code).addHeader("Content-Type", RedshiftApi.CONTENT_TYPE).body(body)
        .build();
  }

  static MockResponse xml(String body) {
    return new MockResponse.Builder().code(200).addHeader("Content-Type", "text/xml").body(body).build();
  }

  /** The session policy an {@code AssumeRole} asked for. */
  static String policyOf(RecordedRequest assumeRole) {
    for (String pair : assumeRole.getBody().utf8().split("&")) {
      if (pair.startsWith("Policy=")) {
        return URLDecoder.decode(pair.substring(7), StandardCharsets.UTF_8);
      }
    }
    return "";
  }

  /**
   * {@code DescribeStatement} of a batch of four statements with these states; the third, the agent's, has a result
   * of {@code rows} rows.
   */
  static String batch(String id, long rows, String... states) {
    StringBuilder subs = new StringBuilder();
    for (int i = 0; i < states.length; i++) {
      String[] state = states[i].split(":", 2);
      subs.append(i == 0 ? "" : ",").append("{\"Id\":\"").append(id).append(':').append(i + 1)
          .append("\",\"Status\":\"").append(state[0]).append('"');
      if (state.length > 1) {
        subs.append(",\"Error\":").append(JSON.writeValueAsString(state[1]));
      }
      if (i == 2) {
        subs.append(",\"HasResultSet\":true,\"ResultRows\":").append(rows).append(",\"ResultSize\":")
            .append(rows * 40).append(",\"RedshiftQueryId\":4711");
      } else {
        subs.append(",\"HasResultSet\":false,\"ResultRows\":-1,\"ResultSize\":-1");
      }
      subs.append('}');
    }
    boolean failed = String.join(",", states).contains("FAILED");
    return "{\"Id\":\"" + id + "\",\"Status\":\"" + (failed ? "FAILED" : "FINISHED") + "\",\"ExecutionMode\":"
        + "\"AUTO_COMMIT\",\"HasResultSet\":true,\"SubStatements\":[" + subs + "]}";
  }

  /** A page of {@code GetStatementResult}. */
  static String page(String columns, String records, String nextToken, long total) {
    return "{\"ColumnMetadata\":" + columns + ",\"Records\":" + records + ",\"TotalNumRows\":" + total
        + (nextToken == null ? "" : ",\"NextToken\":\"" + nextToken + "\"") + "}";
  }

  @Override
  public void close() {
    cloud.close();
    server.close();
  }
}
