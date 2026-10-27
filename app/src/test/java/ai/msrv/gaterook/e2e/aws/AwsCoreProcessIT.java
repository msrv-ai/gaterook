package ai.msrv.gaterook.e2e.aws;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.e2e.platform.CoreProcess;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okio.Buffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The AWS connector through the assembled core (step C8, ARCHITECTURE.md §10): the window's admin API tests and saves
 * an AWS connection (an access key assuming a role with an ExternalId), an endpoint and a token; an agent then lists
 * and reads resources through Cloud Control, reads metrics through CloudWatch and tries what the gateway never sends.
 * AWS is a fake on this computer: STS (Query/XML), Cloud Control (JSON 1.0) and CloudWatch (Smithy RPCv2 CBOR with
 * tag-1 timestamps), wired through {@code gateway.cloud.aws.sts-endpoint} and {@code gateway.cloud.endpoints}.
 *
 * <p>An instance's UserData holds a password and a tag echoes the session token: the agent sees {@code ‹masked›} for
 * both (§9 "the agent never sees a secret"). No canary — the base secret key, the session's secret key and token, the
 * gateway's token — appears in an answer, the admin API, stdout or the log.
 *
 * <p>On the JVM this runs {@code app.jar}; with {@code -Dgateway.core.binary=<path>} it is the native touch of the
 * connector (C8 "Completion criteria"): the SDK's signing, JSON 1.0 and RPCv2 CBOR protocols, the URL-connection client and
 * the guard interceptor in the image.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AwsCoreProcessIT {

  private static final Duration READY = Duration.ofSeconds(90);
  private static final String ACCOUNT = "412398765012";
  private static final String BASE_SECRET = "wJalrXUtnFEMI/K7MDENG/cnryAWSBASE" + Long.toHexString(System.nanoTime());
  private static final String SESSION_SECRET = "cnry-aws-session-secret-" + Long.toHexString(System.nanoTime());
  private static final String SESSION_TOKEN = "cnry-aws-session-token-" + Long.toHexString(System.nanoTime());
  private static final String USER_DATA_SECRET = "cnry-userdata-" + Long.toHexString(System.nanoTime());
  private static final String SESSION_ARN = "arn:aws:sts::" + ACCOUNT + ":assumed-role/gw-readonly/gaterook-aws-dev";
  private static final HttpClient HTTP = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY)
      .connectTimeout(Duration.ofSeconds(5)).build();

  private static final List<RecordedRequest> REQUESTS = new CopyOnWriteArrayList<>();
  private static MockWebServer fake;
  private static CoreProcess core;
  private static String token;

  @BeforeAll
  static void start() throws Exception {
    fake = new MockWebServer();
    fake.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        REQUESTS.add(request);
        return answer(request);
      }
    });
    fake.start(InetAddress.getByName("127.0.0.1"), 0);
    String base = "http://127.0.0.1:" + fake.getPort();
    core = CoreProcess.start(CoreProcess.newHome("aws"), CoreProcess.freePort(), List.of(
        "-Dgateway.cloud.aws.sts-endpoint=" + base,
        "-Dgateway.cloud.endpoints=aws.cloudcontrol=" + base + ",aws.monitoring=" + base)).bootstrap();
  }

  @AfterAll
  static void stop() {
    if (core != null) {
      core.close();
    }
    if (fake != null) {
      fake.close();
    }
  }

  @Test
  @Order(1)
  void theConnectionIsTestedAndSavedAndTheEndpointServesIt() throws Exception {
    assertThat(core.untilReady(READY).getLast().path("ready").asBoolean()).as(core.stderr()).isTrue();
    String config = """
        {"regions":["eu-central-1"],"accountAlias":"acme-dev","credentials":{"source":"accessKey",
         "accessKeyId":"AKIAZ7GWSTAGEDEMO000","roleArn":"arn:aws:iam::%s:role/gw-readonly",
         "externalId":"gw-ext-3f9a1c2b7d4e"}}""".formatted(ACCOUNT);
    String secrets = "{\"/credentials/secretAccessKey\":{\"source\":\"local\",\"value\":\"" + BASE_SECRET + "\"}}";

    JsonNode tested = json(admin("POST", "/api/connections/test", "{\"type\":\"aws\",\"env\":\"dev\",\"config\":"
        + config + ",\"secrets\":" + secrets + "}"), 200);
    assertThat(tested.path("ok").asBoolean()).as(tested.toString()).isTrue();
    assertThat(tested.path("identity").asString()).startsWith("arn:aws:sts::" + ACCOUNT
        + ":assumed-role/gw-readonly/gaterook-");
    assertThat(tested.path("serverVersion").asString()).isEqualTo("Cloud Control API · CloudWatch · STS eu-central-1");
    assertThat(tested.path("permissions").path("level").asString()).isEqualToIgnoringCase("read");
    json(admin("POST", "/api/connections", "{\"name\":\"aws-dev\",\"type\":\"aws\",\"env\":\"dev\",\"config\":"
        + config + ",\"secrets\":" + secrets + "}"), 201);
    // The stored connection's test writes its metadata: the account and the line the tools' description shows.
    JsonNode stored = json(admin("POST", "/api/connections/aws-dev/test", "{}"), 200);
    assertThat(stored.path("ok").asBoolean()).as(stored.toString()).isTrue();
    assertThat(stored.path("identity").asString()).isEqualTo(SESSION_ARN);

    json(admin("POST", "/api/endpoints", "{\"slug\":\"cloud\",\"startFrom\":\"empty\"}"), 201);
    json(admin("POST", "/api/endpoints/cloud/connections/aws-dev", "{}"), 200);
    token = json(admin("POST", "/api/endpoints/cloud/tokens", "{\"name\":\"aws-e2e\",\"ttlDays\":1}"), 201)
        .path("token").asString();
    assertThat(token).startsWith("gwt_");

    mcp("""
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},
         "clientInfo":{"name":"aws-e2e","version":"1"}}}""");
    JsonNode describe = null;
    JsonNode metrics = null;
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while ((describe == null || metrics == null) && System.nanoTime() < deadline) {
      for (JsonNode t : mcp("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}")
          .at("/result/tools")) {
        if (t.path("name").asString().equals("aws_describe")) {
          describe = t;
        } else if (t.path("name").asString().equals("aws_metrics")) {
          metrics = t;
        }
      }
      if (describe == null || metrics == null) {
        Thread.sleep(200);
      }
    }
    assertThat(describe).as("aws_describe on the endpoint").isNotNull();
    assertThat(metrics).as("aws_metrics on the endpoint").isNotNull();
    assertThat(describe.path("description").asString()).contains("Regions: aws-dev: eu-central-1 · " + ACCOUNT
        + " acme-dev").contains("Connections: aws-dev (AWS · eu-central-1 · " + ACCOUNT + " acme-dev, dev)");
    assertThat(describe.at("/inputSchema/properties/region/enum").toString()).isEqualTo("[\"eu-central-1\"]");
    assertThat(describe.at("/annotations/readOnlyHint").asBoolean()).isTrue();
    assertThat(describe.has("outputSchema")).as("Read only: the schema is published").isTrue();
  }

  @Test
  @Order(2)
  void anAgentListsPagesAndReadsOneResourceWithItsSecretsMasked() throws Exception {
    JsonNode list = call(3, "aws_describe", "{\"connection\":\"aws-dev\",\"type\":\"AWS::RDS::DBInstance\","
        + "\"limit\":1}");
    assertThat(list.at("/result/isError").asBoolean(true)).as(list.toString()).isFalse();
    assertThat(list.at("/result/structuredContent/resources/0/identifier").asString()).isEqualTo("dev-orders");
    String cursor = list.at("/result/structuredContent/nextCursor").asString();
    assertThat(cursor).startsWith("ac1.");
    assertThat(list.at("/result/content/0/text").asString()).contains("cursor " + cursor);
    JsonNode next = call(4, "aws_describe", "{\"connection\":\"aws-dev\",\"type\":\"AWS::RDS::DBInstance\","
        + "\"limit\":1,\"cursor\":\"" + cursor + "\"}");
    assertThat(next.at("/result/isError").asBoolean(true)).as(next.toString()).isFalse();
    assertThat(next.at("/result/structuredContent/resources/0/identifier").asString()).isEqualTo("dev-billing");

    RecordedRequest cc = REQUESTS.stream().filter(r -> "CloudApiService.ListResources".equals(
        r.getHeaders().get("X-Amz-Target"))).findFirst().orElseThrow();
    assertThat(cc.getHeaders().get("Authorization")).contains("Credential=ASIAAWSE2ESESSION001/");
    assertThat(cc.getHeaders().get("X-Amz-Security-Token")).isEqualTo(SESSION_TOKEN);

    JsonNode get = call(5, "aws_describe", "{\"connection\":\"aws-dev\",\"type\":\"AWS::EC2::Instance\","
        + "\"identifier\":\"i-0abc\"}");
    assertThat(get.at("/result/isError").asBoolean(true)).as(get.toString()).isFalse();
    JsonNode res = get.at("/result/structuredContent/resource");
    assertThat(res.path("status").asString()).isEqualTo("running");
    assertThat(res.path("arn").asString()).isEqualTo("arn:aws:ec2:eu-central-1:" + ACCOUNT + ":instance/i-0abc");
    assertThat(res.at("/properties/UserData").asString()).isEqualTo("‹masked›");
    assertThat(res.at("/tags/deployed-by").asString()).as("the session token, echoed by AWS, is masked by the core")
        .contains("‹masked›");
    assertThat(get.toString()).doesNotContain(USER_DATA_SECRET).doesNotContain(SESSION_TOKEN);

    // where sees what the core will show (C8 review, finding 1): a glob on a value only the core's masker hides would
    // keep the queue whose key starts with the guess, one character at a time.
    JsonNode guess = call(30, "aws_describe", "{\"connection\":\"aws-dev\",\"type\":\"AWS::SQS::Queue\","
        + "\"where\":{\"tags.api_key\":\"k*\"}}");
    assertThat(guess.at("/result/isError").asBoolean(true)).as(guess.toString()).isFalse();
    assertThat(guess.at("/result/structuredContent/count").asInt()).as(guess.toString()).isZero();
    JsonNode team = call(31, "aws_describe", "{\"connection\":\"aws-dev\",\"type\":\"AWS::SQS::Queue\","
        + "\"where\":{\"tags.team\":\"pay*\"}}");
    assertThat(team.at("/result/structuredContent/resources/0/arn").asString()).endsWith(":dev-payments");
    assertThat(team.at("/result/structuredContent/resources/0/tags/api_key").asString()).isEqualTo("‹masked›");
    assertThat(team.toString()).doesNotContain("kE2EAPIKEY0001");
  }

  @Test
  @Order(3)
  void anAgentReadsMetricsThroughCbor() throws Exception {
    JsonNode m = call(6, "aws_metrics", "{\"connection\":\"aws-dev\",\"metrics\":[{\"namespace\":\"AWS/RDS\","
        + "\"metric\":\"CPUUtilization\",\"dimensions\":{\"DBInstanceIdentifier\":\"dev-orders\"}}],"
        + "\"expressions\":[{\"id\":\"e1\",\"expression\":\"m1 * 2\"}],\"from\":\"-1h\"}");
    assertThat(m.at("/result/isError").asBoolean(true)).as(m.toString()).isFalse();
    assertThat(m.at("/result/structuredContent/series/0/points/0/v").asDouble()).isEqualTo(12.5);
    assertThat(m.at("/result/structuredContent/series/0/points/0/t").asString()).isEqualTo("2026-10-22T12:00:00Z");
    assertThat(m.at("/result/structuredContent/series/0/status").asString()).isEqualTo("Complete");
    RecordedRequest cw = REQUESTS.stream().filter(r -> r.getUrl().encodedPath().equals(
        "/service/GraniteServiceVersion20100801/operation/GetMetricData")).findFirst().orElseThrow();
    assertThat(cw.getHeaders().get("Content-Type")).isEqualTo("application/cbor");
    assertThat(cw.getHeaders().get("smithy-protocol")).isEqualTo("rpc-v2-cbor");
    String body = new String(cw.getBody().toByteArray(), StandardCharsets.ISO_8859_1);
    assertThat(body).contains("MetricDataQueries", "AWS/RDS", "CPUUtilization", "m1 * 2", "TimestampAscending")
        .doesNotContain("AccountId");

    JsonNode alarms = call(7, "aws_metrics", "{\"connection\":\"aws-dev\",\"action\":\"alarms\"}");
    assertThat(alarms.at("/result/isError").asBoolean(true)).as(alarms.toString()).isFalse();
    assertThat(alarms.at("/result/structuredContent/alarms/0/name").asString()).isEqualTo("dev-orders-cpu");
  }

  @Test
  @Order(4)
  void whatTheGatewayNeverSendsIsDeniedBeforeAws() throws Exception {
    int before = REQUESTS.size();
    JsonNode secret = call(8, "aws_describe", "{\"connection\":\"aws-dev\",\"type\":\"AWS::SecretsManager::Secret\"}");
    assertThat(secret.at("/result/isError").asBoolean()).isTrue();
    assertThat(secret.at("/result/content/0/text").asString()).startsWith("Denied by policy dev.cloud.sensitive_type");
    JsonNode lambda = call(9, "aws_metrics", "{\"connection\":\"aws-dev\",\"metrics\":[{\"namespace\":\"AWS/RDS\","
        + "\"metric\":\"CPUUtilization\"}],\"expressions\":[{\"id\":\"e1\",\"expression\":\"LAMBDA(\\\"f\\\", m1)\"}]}");
    assertThat(lambda.at("/result/content/0/text").asString())
        .startsWith("Denied by policy dev.cloud.metric_expression");
    JsonNode search = call(10, "aws_metrics", "{\"connection\":\"aws-dev\",\"metrics\":[{\"namespace\":\"AWS/RDS\","
        + "\"metric\":\"CPUUtilization\"}],\"expressions\":[{\"id\":\"e1\",\"expression\":"
        + "\"SEARCH(\\\"{AWS/EC2} CPUUtilization\\\", \\\"Average\\\", 300)\"}]}");
    assertThat(search.at("/result/content/0/text").asString())
        .startsWith("Denied by policy dev.cloud.metric_expression");
    // Nothing of the three reached AWS; the core's health check (DescribeAlarms) may have run meanwhile.
    assertThat(REQUESTS.subList(before, REQUESTS.size())).extracting(AwsCoreProcessIT::operation)
        .allMatch(op -> List.of("AssumeRole", "GetCallerIdentity", "DescribeAlarms").contains(op));
    // Only reads ever went out.
    assertThat(REQUESTS).extracting(AwsCoreProcessIT::operation).allMatch(op -> List.of("AssumeRole",
        "GetCallerIdentity", "ListResources", "GetResource", "GetMetricData", "ListMetrics", "DescribeAlarms")
        .contains(op));
  }

  @Test
  @Order(5)
  void noCredentialReachesAnAnswerOrTheLog() throws Exception {
    List<String> seen = new ArrayList<>(core.stdoutSoFar());
    seen.add(core.coreLog());
    seen.add(core.stderr());
    seen.add(json(admin("GET", "/api/connections", null), 200).toString());
    seen.add(json(admin("GET", "/api/activity/events", null), 200).toString());
    String everything = String.join("\n", seen);
    for (String canary : List.of(BASE_SECRET, SESSION_SECRET, SESSION_TOKEN, USER_DATA_SECRET, token)) {
      assertThat(everything).doesNotContain(canary);
    }
    RecordedRequest assume = REQUESTS.stream().filter(r -> "AssumeRole".equals(operation(r))).findFirst()
        .orElseThrow();
    String form = URLDecoder.decode(assume.getBody().utf8(), StandardCharsets.UTF_8);
    assertThat(form).contains("ExternalId=gw-ext-3f9a1c2b7d4e", "DurationSeconds=900", "\"Effect\":\"Deny\"",
        "secretsmanager:GetSecretValue", "cloudformation:ListResources");
    assertThat(core.coreLog()).doesNotContainPattern("Missing[A-Za-z]*Registration")
        .doesNotContain("UnsupportedFeatureError");
  }

  // ── The fake AWS ────────────────────────────────────────────────────────────────────────────────────────────

  private static String operation(RecordedRequest r) {
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
    if (at < 0) {
      return path;
    }
    int end = body.indexOf('&', at);
    return body.substring(at + 7, end < 0 ? body.length() : end);
  }

  private static MockResponse answer(RecordedRequest r) {
    String op = operation(r);
    switch (op) {
      case "AssumeRole" -> {
        String session = URLDecoder.decode(r.getBody().utf8(), StandardCharsets.UTF_8)
            .replaceAll("(?s).*RoleSessionName=([^&]+).*", "$1");
        return xml("<AssumeRoleResponse xmlns=\"https://sts.amazonaws.com/doc/2011-06-15/\"><AssumeRoleResult>"
            + "<Credentials><AccessKeyId>ASIAAWSE2ESESSION001</AccessKeyId><SecretAccessKey>" + SESSION_SECRET
            + "</SecretAccessKey><SessionToken>" + SESSION_TOKEN + "</SessionToken><Expiration>"
            + Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS) + "</Expiration>"
            + "</Credentials><AssumedRoleUser><AssumedRoleId>AROAE2E:" + session + "</AssumedRoleId><Arn>"
            + "arn:aws:sts::" + ACCOUNT + ":assumed-role/gw-readonly/" + session + "</Arn></AssumedRoleUser>"
            + "</AssumeRoleResult><ResponseMetadata><RequestId>1</RequestId></ResponseMetadata>"
            + "</AssumeRoleResponse>");
      }
      case "GetCallerIdentity" -> {
        String auth = String.valueOf(r.getHeaders().get("Authorization"));
        String arn = auth.contains("Credential=ASIA") ? "arn:aws:sts::" + ACCOUNT + ":assumed-role/gw-readonly/"
            + latestSession() : "arn:aws:iam::" + ACCOUNT + ":user/gw";
        return xml("<GetCallerIdentityResponse xmlns=\"https://sts.amazonaws.com/doc/2011-06-15/\">"
            + "<GetCallerIdentityResult><Arn>" + arn + "</Arn><UserId>AIDAE2E</UserId><Account>" + ACCOUNT
            + "</Account></GetCallerIdentityResult><ResponseMetadata><RequestId>2</RequestId></ResponseMetadata>"
            + "</GetCallerIdentityResponse>");
      }
      case "ListResources" -> {
        JsonNode body = CoreProcess.JSON.readTree(r.getBody().utf8());
        if (body.path("TypeName").asString().equals("AWS::RDS::DBInstance")) {
          return json("{\"ResourceDescriptions\":[" + db("dev-orders") + "," + db("dev-billing") + "]}");
        }
        if (body.path("TypeName").asString().equals("AWS::SQS::Queue")) {
          return json("{\"ResourceDescriptions\":[" + queue("dev-payments", "kE2EAPIKEY0001", "payments") + ","
              + queue("dev-billing", "zE2EAPIKEY0002", "billing") + "]}");
        }
        return json("{\"ResourceDescriptions\":[]}");
      }
      case "GetResource" -> {
        String props = "{\"InstanceId\":\"i-0abc\",\"State\":{\"Code\":16,\"Name\":\"running\"},\"UserData\":\""
            + Base64.getEncoder().encodeToString(("#!/bin/sh\nexport DB_PASSWORD=" + USER_DATA_SECRET)
                .getBytes(StandardCharsets.UTF_8)) + "\",\"Tags\":[{\"Key\":\"deployed-by\",\"Value\":\"session "
            + SESSION_TOKEN + "\"}]}";
        return json("{\"TypeName\":\"AWS::EC2::Instance\",\"ResourceDescription\":{\"Identifier\":\"i-0abc\","
            + "\"Properties\":" + JsonNodeFactory.instance.textNode(props) + "}}");
      }
      case "GetMetricData" -> {
        Map<String, Object> m1 = new LinkedHashMap<>();
        m1.put("Id", "m1");
        m1.put("Label", "CPUUtilization");
        m1.put("Timestamps", List.of(new Epoch(Instant.parse("2026-10-22T12:00:00Z").getEpochSecond())));
        m1.put("Values", List.of(12.5));
        m1.put("StatusCode", "Complete");
        Map<String, Object> e1 = new LinkedHashMap<>();
        e1.put("Id", "e1");
        e1.put("Timestamps", List.of(new Epoch(Instant.parse("2026-10-22T12:00:00Z").getEpochSecond())));
        e1.put("Values", List.of(25.0));
        e1.put("StatusCode", "Complete");
        return cbor(Map.of("MetricDataResults", List.of(m1, e1)));
      }
      case "ListMetrics" -> {
        return cbor(Map.of("Metrics", List.of(Map.of("Namespace", "AWS/EC2", "MetricName", "CPUUtilization",
            "Dimensions", List.of()))));
      }
      case "DescribeAlarms" -> {
        Map<String, Object> alarm = new LinkedHashMap<>();
        alarm.put("AlarmName", "dev-orders-cpu");
        alarm.put("StateValue", "ALARM");
        alarm.put("StateReason", "Threshold Crossed");
        alarm.put("StateUpdatedTimestamp", new Epoch(Instant.parse("2026-10-22T12:05:00Z").getEpochSecond()));
        alarm.put("Namespace", "AWS/RDS");
        alarm.put("MetricName", "CPUUtilization");
        alarm.put("Dimensions", List.of(Map.of("Name", "DBInstanceIdentifier", "Value", "dev-orders")));
        alarm.put("Threshold", 80.0);
        alarm.put("ComparisonOperator", "GreaterThanThreshold");
        return cbor(Map.of("MetricAlarms", List.of(alarm)));
      }
      default -> {
        return new MockResponse.Builder().code(404).body("no fake for " + op).build();
      }
    }
  }

  /**
   * The role session name of the latest AssumeRole: every session of the fake shares one key, and a test connection
   * asks who it is right after assuming the role.
   */
  private static String latestSession() {
    for (int i = REQUESTS.size() - 1; i >= 0; i--) {
      RecordedRequest earlier = REQUESTS.get(i);
      if ("AssumeRole".equals(operation(earlier))) {
        return URLDecoder.decode(earlier.getBody().utf8(), StandardCharsets.UTF_8)
            .replaceAll("(?s).*RoleSessionName=([^&]+).*", "$1");
      }
    }
    return "none";
  }

  /** A queue tagged with an API key the core masks (the key name says so) and a team it shows. */
  private static String queue(String name, String apiKey, String team) {
    String arn = "arn:aws:sqs:eu-central-1:" + ACCOUNT + ":" + name;
    return "{\"Identifier\":\"" + arn + "\",\"Properties\":" + JsonNodeFactory.instance.textNode("{\"Arn\":\"" + arn
        + "\",\"QueueName\":\"" + name + "\",\"Tags\":[{\"Key\":\"api_key\",\"Value\":\"" + apiKey + "\"},"
        + "{\"Key\":\"team\",\"Value\":\"" + team + "\"}]}") + "}";
  }

  private static String db(String id) {
    return "{\"Identifier\":\"" + id + "\",\"Properties\":" + JsonNodeFactory.instance.textNode(
        "{\"DBInstanceIdentifier\":\"" + id + "\",\"DBInstanceStatus\":\"available\",\"DBInstanceArn\":\"arn:aws:rds:"
            + "eu-central-1:" + ACCOUNT + ":db:" + id + "\"}") + "}";
  }

  private static MockResponse xml(String body) {
    return new MockResponse.Builder().code(200).addHeader("Content-Type", "text/xml").body(body).build();
  }

  private static MockResponse json(String body) {
    return new MockResponse.Builder().code(200).addHeader("Content-Type", "application/x-amz-json-1.0").body(body)
        .build();
  }

  private static MockResponse cbor(Object body) {
    Buffer b = new Buffer();
    b.write(Cbor.encode(body));
    return new MockResponse.Builder().code(200).addHeader("Content-Type", "application/cbor")
        .addHeader("smithy-protocol", "rpc-v2-cbor").body(b).build();
  }

  /** A timestamp in seconds, written as CBOR tag 1 (epoch-based date/time) as Smithy RPCv2 CBOR does. */
  private record Epoch(long seconds) {}

  /** Just enough CBOR (RFC 8949) for the fake's answers: maps, arrays, text, integers, doubles, tag 1. */
  private static final class Cbor {
    static byte[] encode(Object v) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      write(out, v);
      return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, Object v) {
      switch (v) {
        case Map<?, ?> m -> {
          head(out, 5, m.size());
          m.forEach((k, value) -> {
            write(out, k);
            write(out, value);
          });
        }
        case List<?> l -> {
          head(out, 4, l.size());
          l.forEach(item -> write(out, item));
        }
        case String s -> {
          byte[] b = s.getBytes(StandardCharsets.UTF_8);
          head(out, 3, b.length);
          out.writeBytes(b);
        }
        case Epoch e -> {
          out.write(0xc1);
          write(out, e.seconds());
        }
        case Double d -> {
          out.write(0xfb);
          long bits = Double.doubleToLongBits(d);
          for (int i = 7; i >= 0; i--) {
            out.write((int) (bits >>> (8 * i)) & 0xff);
          }
        }
        case Long n -> head(out, n >= 0 ? 0 : 1, n >= 0 ? n : -1 - n);
        case Integer n -> write(out, (long) n);
        default -> throw new IllegalArgumentException("No CBOR for " + v.getClass());
      }
    }

    private static void head(ByteArrayOutputStream out, int major, long n) {
      int m = major << 5;
      if (n < 24) {
        out.write(m | (int) n);
      } else if (n < 0x100) {
        out.write(m | 24);
        out.write((int) n);
      } else if (n < 0x10000) {
        out.write(m | 25);
        out.write((int) (n >>> 8));
        out.write((int) n & 0xff);
      } else if (n < 0x100000000L) {
        out.write(m | 26);
        for (int i = 3; i >= 0; i--) {
          out.write((int) (n >>> (8 * i)) & 0xff);
        }
      } else {
        out.write(m | 27);
        for (int i = 7; i >= 0; i--) {
          out.write((int) (n >>> (8 * i)) & 0xff);
        }
      }
    }
  }

  // ── The window's admin API and the agent's MCP ──────────────────────────────────────────────────────────────

  private record Answer(int status, String body) {}

  private static URI uri(String target) {
    return URI.create("http://127.0.0.1:" + core.port + target);
  }

  private static Answer admin(String method, String target, String body) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(uri(target)).timeout(Duration.ofSeconds(30))
        .header("X-Gateway-Auth", core.secret().sign(method, target, System.currentTimeMillis(), nonce()))
        .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    if (body != null) {
      b.header("Content-Type", "application/json");
    }
    HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    return new Answer(r.statusCode(), r.body());
  }

  private static JsonNode json(Answer answer, int status) {
    assertThat(answer.status()).as(answer.body()).isEqualTo(status);
    return CoreProcess.JSON.readTree(answer.body());
  }

  private static JsonNode mcp(String message) throws Exception {
    HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(uri("/mcp/cloud")).timeout(Duration.ofSeconds(40))
        .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
        .header("MCP-Protocol-Version", "2025-11-25").header("Authorization", "Bearer " + token)
        .POST(HttpRequest.BodyPublishers.ofString(message)).build(), HttpResponse.BodyHandlers.ofString());
    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    return CoreProcess.JSON.readTree(r.body());
  }

  private static JsonNode call(int id, String tool, String arguments) throws Exception {
    ObjectNode params = JsonNodeFactory.instance.objectNode().put("name", tool);
    params.set("arguments", CoreProcess.JSON.readTree(arguments));
    return mcp("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":" + params + "}");
  }

  private static String nonce() {
    byte[] bytes = new byte[16];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
