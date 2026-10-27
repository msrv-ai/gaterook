package ai.msrv.gaterook.connectors.aws;

import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudEndpoints;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okio.Buffer;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.cbor.CBORGenerator;
import tools.jackson.dataformat.cbor.CBORMapper;

/**
 * AWS on this computer for the tests (report 09 §8): one MockWebServer answers STS (Query/XML), Cloud Control (JSON
 * 1.0, {@code X-Amz-Target: CloudApiService.<Op>}) and CloudWatch (Smithy RPCv2 CBOR on
 * {@code /service/GraniteServiceVersion20100801/operation/<Op>}, timestamps as CBOR tag 1). AssumeRole hands out an
 * {@code ASIA…} session whose secret and token are canaries; GetCallerIdentity answers by the key that signed it.
 */
final class FakeAws implements AutoCloseable {

  static final String ACCOUNT = "412398765012";
  static final String BASE_SECRET = "wJalrXUtnFEMI/K7MDENG/cnryBASEKEY0000001";
  static final String SESSION_KEY_ID = "ASIATESTSESSION00001";
  static final String SESSION_SECRET = "cnry-session-secret-" + Long.toHexString(System.nanoTime());
  static final String SESSION_TOKEN = "cnry-session-token-" + Long.toHexString(System.nanoTime());
  static final String USER_ARN = "arn:aws:iam::" + ACCOUNT + ":user/gw";
  static final String SESSION_ARN = "arn:aws:sts::" + ACCOUNT + ":assumed-role/gw-readonly/mcp-gateway-aws-stage";
  static final CBORMapper CBOR = CBORMapper.builder().build();
  /** Keys whose values (or items) are timestamps: written as CBOR tag 1, as the Smithy protocol says. */
  private static final Set<String> TIMESTAMPS = Set.of("Timestamps", "StateUpdatedTimestamp",
      "AlarmConfigurationUpdatedTimestamp", "StateTransitionedTimestamp");

  final MockWebServer server = new MockWebServer();
  final CloudCredentials cloud;
  final URI base;
  final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
  private final Map<String, Function<RecordedRequest, MockResponse>> handlers = new ConcurrentHashMap<>();

  FakeAws() throws IOException {
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
          case "AssumeRole" -> assumeRole();
          case "GetCallerIdentity" -> callerIdentity(request);
          default -> new MockResponse.Builder().code(404).body("no fake for " + op).build();
        };
      }
    });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
    base = URI.create("http://127.0.0.1:" + server.getPort());
    cloud = new CloudCredentials(new CloudEndpoints(null, null, null, base, null, null,
        Map.of("aws.cloudcontrol", base, "aws.monitoring", base)), Clock.systemUTC());
  }

  void on(String operation, Function<RecordedRequest, MockResponse> handler) {
    handlers.put(operation, handler);
  }

  List<RecordedRequest> to(String operation) {
    return requests.stream().filter(r -> operation(r).equals(operation)).toList();
  }

  /** The operation of a request, by protocol: the JSON target, the RPCv2 path, the Query action. */
  static String operation(RecordedRequest r) {
    String target = r.getHeaders().get("X-Amz-Target");
    if (target != null) {
      return target.substring(target.indexOf('.') + 1);
    }
    String path = r.getUrl().encodedPath();
    if (path.contains("/operation/")) {
      return path.substring(path.lastIndexOf('/') + 1);
    }
    String body = r.getBody() == null ? "" : r.getBody().utf8();
    int at = body.indexOf("Action=");
    if (at >= 0) {
      int end = body.indexOf('&', at);
      return body.substring(at + 7, end < 0 ? body.length() : end);
    }
    return path;
  }

  // ── STS ──────────────────────────────────────────────────────────────────────────────────────────────────────

  static MockResponse assumeRole() {
    return xml("<AssumeRoleResponse xmlns=\"https://sts.amazonaws.com/doc/2011-06-15/\"><AssumeRoleResult>"
        + "<Credentials><AccessKeyId>" + SESSION_KEY_ID + "</AccessKeyId><SecretAccessKey>" + SESSION_SECRET
        + "</SecretAccessKey><SessionToken>" + SESSION_TOKEN + "</SessionToken><Expiration>"
        + Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS) + "</Expiration></Credentials>"
        + "<AssumedRoleUser><AssumedRoleId>AROATEST:mcp-gateway-aws-stage</AssumedRoleId><Arn>" + SESSION_ARN
        + "</Arn></AssumedRoleUser></AssumeRoleResult><ResponseMetadata><RequestId>1</RequestId>"
        + "</ResponseMetadata></AssumeRoleResponse>");
  }

  static MockResponse callerIdentity(RecordedRequest r) {
    String auth = String.valueOf(r.getHeaders().get("Authorization"));
    String arn = auth.contains("Credential=ASIA") ? SESSION_ARN : USER_ARN;
    return xml("<GetCallerIdentityResponse xmlns=\"https://sts.amazonaws.com/doc/2011-06-15/\">"
        + "<GetCallerIdentityResult><Arn>" + arn + "</Arn><UserId>AIDATEST</UserId><Account>" + ACCOUNT
        + "</Account></GetCallerIdentityResult><ResponseMetadata><RequestId>2</RequestId></ResponseMetadata>"
        + "</GetCallerIdentityResponse>");
  }

  static MockResponse stsError(int code, String error, String message) {
    return new MockResponse.Builder().code(code).addHeader("Content-Type", "text/xml").body("<ErrorResponse "
        + "xmlns=\"https://sts.amazonaws.com/doc/2011-06-15/\"><Error><Type>Sender</Type><Code>" + error + "</Code>"
        + "<Message>" + message + "</Message></Error><RequestId>3</RequestId></ErrorResponse>").build();
  }

  static MockResponse xml(String body) {
    return new MockResponse.Builder().code(200).addHeader("Content-Type", "text/xml").body(body).build();
  }

  // ── Cloud Control (JSON 1.0) ─────────────────────────────────────────────────────────────────────────────────

  static MockResponse json(int code, String body) {
    return new MockResponse.Builder().code(code).addHeader("Content-Type", "application/x-amz-json-1.0")
        .body(body).build();
  }

  static MockResponse cloudControlError(int code, String type, String message) {
    return new MockResponse.Builder().code(code).addHeader("Content-Type", "application/x-amz-json-1.0")
        .addHeader("x-amzn-ErrorType", type).body("{\"__type\":\"com.amazon.cloudapiservice#" + type
            + "\",\"Message\":\"" + message + "\"}").build();
  }

  /** {@code {Identifier, Properties}} where Properties is the JSON string Cloud Control sends. */
  static String resource(String identifier, String propertiesJson) {
    return "{\"Identifier\":" + quote(identifier) + ",\"Properties\":" + quote(propertiesJson) + "}";
  }

  static String quote(String s) {
    return CBOR_JSON.writeValueAsString(s);
  }

  private static final tools.jackson.databind.json.JsonMapper CBOR_JSON = tools.jackson.databind.json.JsonMapper
      .builder().build();

  static JsonNode jsonBody(RecordedRequest r) {
    return CBOR_JSON.readTree(r.getBody().utf8());
  }

  // ── CloudWatch (Smithy RPCv2 CBOR) ───────────────────────────────────────────────────────────────────────────

  static MockResponse cbor(int code, JsonNode body) {
    Buffer b = new Buffer();
    b.write(cborOf(body));
    return new MockResponse.Builder().code(code).addHeader("Content-Type", "application/cbor")
        .addHeader("smithy-protocol", "rpc-v2-cbor").body(b).build();
  }

  static MockResponse cloudWatchError(int code, String type, String message) {
    return cbor(code, CBOR_JSON.readTree("{\"__type\":\"com.amazonaws.cloudwatch#" + type + "\",\"message\":"
        + quote(message) + "}"));
  }

  /** The tree as CBOR, timestamps (by key, seconds as numbers in the tree) as tag 1. */
  static byte[] cborOf(JsonNode tree) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (JsonGenerator g = CBOR.createGenerator(out)) {
      write((CBORGenerator) g, tree, false);
    }
    return out.toByteArray();
  }

  private static void write(CBORGenerator g, JsonNode n, boolean timestamp) {
    if (n.isObject()) {
      g.writeStartObject();
      for (Map.Entry<String, JsonNode> e : n.properties()) {
        g.writeName(e.getKey());
        write(g, e.getValue(), TIMESTAMPS.contains(e.getKey()));
      }
      g.writeEndObject();
    } else if (n.isArray()) {
      g.writeStartArray();
      for (JsonNode item : n) {
        write(g, item, timestamp);
      }
      g.writeEndArray();
    } else if (n.isNumber()) {
      if (timestamp) {
        g.writeTag(1);
      }
      if (n.isIntegralNumber()) {
        g.writeNumber(n.asLong());
      } else {
        g.writeNumber(n.asDouble());
      }
    } else if (n.isString()) {
      g.writeString(n.asString());
    } else if (n.isBoolean()) {
      g.writeBoolean(n.asBoolean());
    } else {
      g.writeNull();
    }
  }

  /** The CBOR body of a CloudWatch request as a tree (tag 1 values read as their numbers). */
  static JsonNode cborBody(RecordedRequest r) {
    return CBOR.readTree(r.getBody().toByteArray());
  }

  static String utf8(byte[] b) {
    return new String(b, StandardCharsets.UTF_8);
  }

  @Override
  public void close() {
    cloud.close();
    server.close();
  }
}
