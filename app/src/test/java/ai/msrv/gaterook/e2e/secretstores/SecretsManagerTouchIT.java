package ai.msrv.gaterook.e2e.secretstores;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.e2e.platform.CoreProcess;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The external stores' touch of the assembled core (ARCHITECTURE.md §10 S1): AWS Secrets Manager through AssumeRole
 * with ExternalId and a session policy, against a fake STS and Secrets Manager on this computer. On the JVM this is
 * {@code app.jar}; with {@code -Dgateway.core.binary=<path>} it is the native image, where the SDK's request path
 * (signing, the JSON protocol, the URL-connection client, STS) must work without reflection surprises. The fake-sql
 * connector reads its password from the store; no answer, log line or stdout carries the value or a session key.
 */
class SecretsManagerTouchIT {

  private static final Duration READY = Duration.ofSeconds(90);
  private static final String WEBHOOK = "cnry-webhook-" + Long.toHexString(System.nanoTime());
  private static final String DB_PASSWORD = "cnry-orders-db-" + Long.toHexString(System.nanoTime());
  private static final String BASE_SECRET = "cnry-base-secret-key-" + Long.toHexString(System.nanoTime());
  private static final String SESSION_SECRET = "cnry-session-secret-" + Long.toHexString(System.nanoTime());
  private static final String SESSION_TOKEN = "cnry-session-token-" + Long.toHexString(System.nanoTime());
  private static final HttpClient HTTP = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY)
      .connectTimeout(Duration.ofSeconds(5)).build();

  private static MockWebServer aws;
  private static final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
  private static CoreProcess core;

  @BeforeAll
  static void start() throws Exception {
    aws = new MockWebServer();
    aws.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        requests.add(request);
        String body = request.getBody().utf8();
        if (body.contains("Action=AssumeRole")) {
          return xml("<AssumeRoleResponse xmlns=\"https://sts.amazonaws.com/doc/2011-06-15/\"><AssumeRoleResult>"
              + "<Credentials><AccessKeyId>ASIATOUCHSESSION0001</AccessKeyId><SecretAccessKey>" + SESSION_SECRET
              + "</SecretAccessKey><SessionToken>" + SESSION_TOKEN + "</SessionToken><Expiration>"
              + Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS) + "</Expiration>"
              + "</Credentials><AssumedRoleUser><AssumedRoleId>AROA:mcp-gateway-aws-sm</AssumedRoleId><Arn>"
              + "arn:aws:sts::412398765012:assumed-role/gw-secrets-read/mcp-gateway-aws-sm</Arn></AssumedRoleUser>"
              + "</AssumeRoleResult><ResponseMetadata><RequestId>1</RequestId></ResponseMetadata>"
              + "</AssumeRoleResponse>");
        }
        if (body.contains("Action=GetCallerIdentity")) {
          return xml("<GetCallerIdentityResponse xmlns=\"https://sts.amazonaws.com/doc/2011-06-15/\">"
              + "<GetCallerIdentityResult><Arn>arn:aws:sts::412398765012:assumed-role/gw-secrets-read/mcp-gateway-"
              + "aws-sm</Arn><UserId>AROA:mcp-gateway-aws-sm</UserId><Account>412398765012</Account>"
              + "</GetCallerIdentityResult><ResponseMetadata><RequestId>2</RequestId></ResponseMetadata>"
              + "</GetCallerIdentityResponse>");
        }
        if ("secretsmanager.GetSecretValue".equals(request.getHeaders().get("X-Amz-Target"))) {
          if (body.contains("\"SecretId\":\"stage/billing-api\"")) {
            return secret("stage/billing-api", "{\\\"webhook_secret\\\":\\\"" + WEBHOOK + "\\\",\\\"note\\\":\\\"x\\\"}");
          }
          if (body.contains("\"SecretId\":\"stage/orders-db\"")) {
            return secret("stage/orders-db", "{\\\"password\\\":\\\"" + DB_PASSWORD + "\\\"}");
          }
          return new MockResponse.Builder().code(400).addHeader("Content-Type", "application/x-amz-json-1.1")
              .addHeader("x-amzn-ErrorType", "ResourceNotFoundException").body("{\"__type\":\"ResourceNotFoundException"
                  + "\",\"message\":\"Secrets Manager can't find the specified secret.\"}").build();
        }
        return new MockResponse.Builder().code(404).build();
      }
    });
    aws.start(InetAddress.getByName("127.0.0.1"), 0);
    String endpoint = "http://127.0.0.1:" + aws.getPort();
    Path home = CoreProcess.newHome("secrets-manager");
    core = CoreProcess.start(home, CoreProcess.freePort(), List.of("-Dgateway.test.fake-connector=true",
        "-Dgateway.cloud.aws.sts-endpoint=" + endpoint, "-Dgateway.cloud.aws.secretsmanager-endpoint=" + endpoint))
        .bootstrap();
    List<JsonNode> lines = core.untilReady(READY);
    assertThat(lines.getLast().path("ready").asBoolean()).as(core.stderr()).isTrue();
  }

  @AfterAll
  static void stop() {
    if (core != null) {
      core.close();
    }
    if (aws != null) {
      aws.close();
    }
  }

  @Test
  void referencesResolveThroughAssumeRoleAndNothingLeaks() throws Exception {
    JsonNode store = json(admin("POST", "/api/secret-stores", """
        {"kind":"aws-sm","name":"aws-sm","config":{"region":"eu-central-1","credentials":{"source":"accessKey",
         "accessKeyId":"AKIATOUCHBASEKEY0001","roleArn":"arn:aws:iam::412398765012:role/gw-secrets-read"}},
         "secrets":{"/credentials/secretAccessKey":"%s"},"reason":"touch"}""".formatted(BASE_SECRET)), 201);
    String externalId = store.path("config").path("credentials").path("externalId").asString();
    assertThat(externalId).startsWith("gw-ext-");

    JsonNode ref = json(admin("POST", "/api/secret-refs/test",
        "{\"uri\":\"aws-sm://eu-central-1/stage/billing-api#webhook_secret\"}"), 200);
    assertThat(ref.path("found").asBoolean()).as(ref.toString()).isTrue();
    assertThat(ref.path("resolvedVersion").asString()).isEqualTo("6f2c9a4e-8b1d-4c3e-9a7f-2d5b6c8e1f30");
    assertThat(ref.path("keys").toString()).contains("webhook_secret", "note");

    json(admin("POST", "/api/connections", """
        {"name":"orders-db","type":"fake-sql","env":"stage","config":{"connection":{"host":"db.touch.internal",
         "port":5432,"database":"app"},"credentials":{"user":"gw_reader"}},"secrets":{"/credentials/password":
         {"source":"reference","ref":"aws-sm://eu-central-1/stage/orders-db#password"}}}"""), 201);
    JsonNode tested = json(admin("POST", "/api/connections/orders-db/test", null), 200);
    assertThat(tested.path("ok").asBoolean()).as(tested.toString()).isTrue();
    JsonNode storeTest = json(admin("POST", "/api/secret-stores/" + store.path("id").asString() + "/test", null),
        200);
    assertThat(storeTest.path("ok").asBoolean()).as(storeTest.toString()).isTrue();
    assertThat(storeTest.path("identity").path("account").asString()).isEqualTo("412398765012");
    JsonNode secrets = json(admin("GET", "/api/secrets", null), 200);
    assertThat(secrets.toString()).contains("orders-db/password", "aws-sm://eu-central-1/stage/orders-db#password");

    // One session per set of references: the connection's names exactly its own (and the test's, its own).
    List<String> sessions = requests.stream().map(r -> URLDecoder.decode(r.getBody().utf8(), StandardCharsets.UTF_8))
        .filter(f -> f.contains("Action=AssumeRole")).toList();
    assertThat(sessions).allSatisfy(f -> assertThat(f).contains("ExternalId=" + externalId,
        "\"Action\":[\"secretsmanager:GetSecretValue\"]").doesNotContain("secretsmanager:*"));
    assertThat(sessions).anySatisfy(f -> assertThat(f).contains(":secret:stage/orders-db-??????")
        .doesNotContain("stage/billing-api"));
    RecordedRequest assume = requests.stream().filter(r -> r.getBody().utf8().contains("Action=AssumeRole"))
        .findFirst().orElseThrow();
    assertThat(assume.getHeaders().get("Authorization")).contains("Credential=AKIATOUCHBASEKEY0001/");
    List<RecordedRequest> reads = requests.stream().filter(r -> "secretsmanager.GetSecretValue".equals(r.getHeaders()
        .get("X-Amz-Target"))).toList();
    assertThat(reads).hasSizeGreaterThanOrEqualTo(2).allSatisfy(r -> {
      assertThat(r.getHeaders().get("Authorization")).contains("Credential=ASIATOUCHSESSION0001/");
      assertThat(r.getHeaders().get("X-Amz-Security-Token")).isEqualTo(SESSION_TOKEN);
    });

    String everything = String.join("\n", store.toString(), ref.toString(), tested.toString(), storeTest.toString(),
        secrets.toString(), String.join("\n", core.stdoutSoFar()), core.coreLog(), core.stderr());
    for (String canary : List.of(WEBHOOK, DB_PASSWORD, BASE_SECRET, SESSION_SECRET, SESSION_TOKEN)) {
      assertThat(everything).doesNotContain(canary);
    }
    assertThat(core.coreLog()).doesNotContain("MissingReflectionRegistration", "MissingResourceRegistration",
        "MissingJNIRegistration", "UnsupportedFeatureError");
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

  private record Answer(int status, String body) {}

  private static Answer admin(String method, String target, String body) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + core.port + target))
        .timeout(Duration.ofSeconds(30))
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

  private static String nonce() {
    byte[] bytes = new byte[16];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static MockResponse secret(String name, String secretString) {
    return new MockResponse.Builder().code(200).addHeader("Content-Type", "application/x-amz-json-1.1")
        .body("{\"ARN\":\"arn:aws:secretsmanager:eu-central-1:412398765012:secret:" + name + "-AbCdEf\",\"Name\":\""
            + name + "\",\"VersionId\":\"6f2c9a4e-8b1d-4c3e-9a7f-2d5b6c8e1f30\",\"SecretString\":\"" + secretString
            + "\",\"VersionStages\":[\"AWSCURRENT\"],\"CreatedDate\":1.7608e9}").build();
  }

  private static MockResponse xml(String body) {
    return new MockResponse.Builder().code(200).addHeader("Content-Type", "text/xml").body(body).build();
  }
}
