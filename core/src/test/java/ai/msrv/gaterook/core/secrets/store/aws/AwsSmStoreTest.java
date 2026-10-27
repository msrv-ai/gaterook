package ai.msrv.gaterook.core.secrets.store.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connectors.cloudauth.AwsSpec;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudEndpoints;
import ai.msrv.gaterook.core.secrets.SecretStores;
import ai.msrv.gaterook.core.secrets.store.MapSecrets;
import ai.msrv.gaterook.core.secrets.store.RemoteValue;
import ai.msrv.gaterook.core.secrets.store.SecretRef;
import ai.msrv.gaterook.core.secrets.store.StoreCall;
import ai.msrv.gaterook.core.secrets.store.StoreException;
import java.net.InetAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

/**
 * AWS Secrets Manager through AssumeRole with ExternalId and a session policy naming exactly the references (report 12
 * §4.3, §6), against a fake STS and Secrets Manager: the session keys in the call's taint, only GetSecretValue leaves.
 */
class AwsSmStoreTest {

  private static final String ROLE = "arn:aws:iam::412398765012:role/gw-secrets-read";
  private static final String SESSION_SECRET = "cnry-aws-session-secret";
  private static final String SESSION_TOKEN = "cnry-aws-session-token";

  private MockWebServer server;
  private CloudCredentials cloud;
  private AwsSmStore store;
  private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
  private final Map<String, MockResponse> secrets = new ConcurrentHashMap<>();
  private final List<CharSequence> taint = new ArrayList<>();
  private final List<SecretRef> bound = new CopyOnWriteArrayList<>();

  @BeforeEach
  void start() throws Exception {
    server = new MockWebServer();
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        requests.add(request);
        String body = request.getBody().utf8();
        if (body.contains("Action=AssumeRole")) {
          return xml("<AssumeRoleResponse xmlns=\"https://sts.amazonaws.com/doc/2011-06-15/\"><AssumeRoleResult>"
              + "<Credentials><AccessKeyId>ASIACNRYSESSION00001</AccessKeyId><SecretAccessKey>" + SESSION_SECRET
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
        String target = request.getHeaders().get("X-Amz-Target");
        if ("secretsmanager.GetSecretValue".equals(target)) {
          String id = body.replaceAll(".*\"SecretId\":\"([^\"]+)\".*", "$1");
          MockResponse r = secrets.get(id);
          return r != null ? r : error(400, "ResourceNotFoundException",
              "Secrets Manager can't find the specified secret.");
        }
        return error(400, "InvalidAction", "no");
      }
    });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
    cloud = new CloudCredentials(new CloudEndpoints(null, null, null, server.url("/").uri(), null, null),
        Clock.systemUTC());
    AwsSpec spec = new AwsSpec(AwsSpec.Source.ACCESS_KEY, "AKIACNRYBASEKEY00001", AwsSmStore.SECRET_KEY, null, ROLE,
        "gw-ext-5d1e9a0c4b2f", 900, "eu-central-1");
    store = new AwsSmStore(new AwsSmStore.Settings("eu-central-1", spec, 60, server.url("/").uri()), cloud,
        new CloudCredentials.Owner("store-aws", 1, "aws-sm"),
        () -> new MapSecrets().with(AwsSmStore.SECRET_KEY, "cnry-aws-base-secret-key"), () -> List.copyOf(bound));
  }

  @AfterEach
  void stop() {
    if (store != null) {
      store.close();
    }
    cloud.close();
    server.close();
  }

  private StoreCall call() {
    return StoreCall.within(v -> taint.add(v.toString()), Duration.ofSeconds(10), Clock.systemUTC());
  }

  private void put(String name, String secretString) {
    secrets.put(name, json("{\"ARN\":\"arn:aws:secretsmanager:eu-central-1:412398765012:secret:" + name + "-AbCdEf\","
        + "\"Name\":\"" + name + "\",\"VersionId\":\"0f6c2a4e-8b1d-4c3e-9a7f-2d5b6c8e1f30\",\"SecretString\":\""
        + secretString.replace("\"", "\\\"") + "\",\"VersionStages\":[\"AWSCURRENT\"],\"CreatedDate\":1.7608e9}"));
  }

  @Test
  void readsAKeyWithTheAssumedRoleNarrowedToTheReferences() {
    put("stage/billing-api", "{\"webhook_secret\":\"cnry-webhook-secret\",\"other\":\"x\"}");
    SecretRef ref = SecretRef.parse("aws-sm://eu-central-1/stage/billing-api#webhook_secret");
    bound.add(ref);

    try (RemoteValue v = store.read(ref, call())) {
      assertThat(new String(v.value(), StandardCharsets.UTF_8)).isEqualTo("cnry-webhook-secret");
      assertThat(v.version()).isEqualTo("0f6c2a4e-8b1d-4c3e-9a7f-2d5b6c8e1f30");
      assertThat(v.keys()).containsExactlyInAnyOrder("webhook_secret", "other");
      assertThat(v.created()).isEqualTo(Instant.ofEpochSecond(1_760_800_000L));
    }

    RecordedRequest assume = requests.stream().filter(r -> r.getBody().utf8().contains("Action=AssumeRole"))
        .findFirst().orElseThrow();
    String form = URLDecoder.decode(assume.getBody().utf8(), StandardCharsets.UTF_8);
    assertThat(form).contains("RoleArn=" + ROLE, "ExternalId=gw-ext-5d1e9a0c4b2f",
        "arn:aws:secretsmanager:eu-central-1:412398765012:secret:stage/billing-api-??????",
        "secretsmanager:GetSecretValue", "kms:Decrypt");
    assertThat(form).doesNotContain("secretsmanager:*", "\"Resource\":\"*\"");
    RecordedRequest get = requests.stream().filter(r -> "secretsmanager.GetSecretValue".equals(r.getHeaders()
        .get("X-Amz-Target"))).findFirst().orElseThrow();
    assertThat(get.getHeaders().get("Authorization")).contains("Credential=ASIACNRYSESSION00001/");
    assertThat(get.getHeaders().get("X-Amz-Security-Token")).isEqualTo(SESSION_TOKEN);
    assertThat(taint).extracting(CharSequence::toString).contains(SESSION_SECRET, SESSION_TOKEN);
    assertThat(requests).noneMatch(r -> r.getBody().utf8().contains("cnry-aws-base-secret-key"));
  }

  @Test
  void awsErrorsBecomeTextsToActOn() {
    assertThatThrownBy(() -> store.read(SecretRef.parse("aws-sm://eu-central-1/stage/nothing"), call()))
        .isInstanceOfSatisfying(StoreException.class,
            e -> assertThat(e.reason()).isEqualTo(StoreException.Reason.NOT_FOUND))
        .hasMessage("No secret stage/nothing in AWS Secrets Manager (eu-central-1).");

    secrets.put("stage/locked", error(400, "AccessDeniedException", "User is not authorized"));
    assertThatThrownBy(() -> store.read(SecretRef.parse("aws-sm://eu-central-1/stage/locked"), call()))
        .isInstanceOfSatisfying(StoreException.class,
            e -> assertThat(e.reason()).isEqualTo(StoreException.Reason.DENIED))
        .hasMessage("The gateway's role can't read stage/locked: allow secretsmanager:GetSecretValue on it.");

    secrets.put("stage/kms", error(400, "DecryptionFailure", "no key"));
    assertThatThrownBy(() -> store.read(SecretRef.parse("aws-sm://eu-central-1/stage/kms"), call()))
        .hasMessage("stage/kms can't be decrypted: the role needs kms:Decrypt on the secret's key.");

    put("stage/plain", "plain text");
    assertThatThrownBy(() -> store.read(SecretRef.parse("aws-sm://eu-central-1/stage/plain#k"), call()))
        .hasMessage("stage/plain isn't JSON, so it has no key \"k\". Remove #k from the reference.");
  }

  @Test
  void testNamesTheAssumedRoleAndReadsTheFirstReference() {
    put("stage/billing-api", "{\"webhook_secret\":\"cnry-webhook-secret\"}");
    SecretStores.TestResult result = store.test(List.of(SecretRef.parse(
        "aws-sm://eu-central-1/stage/billing-api#webhook_secret")), call());

    assertThat(result.ok()).isTrue();
    assertThat(result.identity().path("account").asString()).isEqualTo("412398765012");
    assertThat(result.identity().path("externalId").asString()).isEqualTo("gw-ext-5d1e9a0c4b2f");
    assertThat(result.checks()).extracting(SecretStores.Check::name).contains("identity", "read");
    assertThat(result.toString()).doesNotContain("cnry-webhook-secret", SESSION_SECRET, SESSION_TOKEN);
  }

  @Test
  void onlyGetSecretValueLeavesTheCore() {
    try (SecretsManagerClient sm = SecretsManagerClient.builder().region(Region.EU_CENTRAL_1)
        .httpClient(UrlConnectionHttpClient.create())
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIACNRY", "x")))
        .endpointOverride(server.url("/").uri())
        .overrideConfiguration(o -> o.addExecutionInterceptor(new AwsSmStore.OperationGuard())).build()) {
      assertThatThrownBy(() -> sm.listSecrets(b -> { })).isInstanceOf(SecurityException.class)
          .hasMessage("Blocked secretsmanager.ListSecrets: GateRook only reads secrets");
      assertThatThrownBy(() -> sm.putSecretValue(b -> b.secretId("x").secretString("y")))
          .isInstanceOf(SecurityException.class);
    }
    assertThat(requests).isEmpty();
  }

  @Test
  void theSessionPolicyNamesEachReferenceOrNothing() {
    String none = store.sessionPolicy(List.of());
    assertThat(none).contains("secret:mcp-gateway-none").doesNotContain("secret:*");
    String two = store.sessionPolicy(List.of(SecretRef.parse("aws-sm://eu-central-1/a/b"),
        SecretRef.parse("aws-sm://arn:aws:secretsmanager:us-east-1:111122223333:secret:x-AbCdEf")));
    assertThat(two).contains("arn:aws:secretsmanager:eu-central-1:412398765012:secret:a/b-??????",
        "arn:aws:secretsmanager:us-east-1:111122223333:secret:x-AbCdEf*");
  }

  private static MockResponse json(String body) {
    return new MockResponse.Builder().code(200).addHeader("Content-Type", "application/x-amz-json-1.1").body(body)
        .build();
  }

  private static MockResponse error(int code, String type, String message) {
    return new MockResponse.Builder().code(code).addHeader("Content-Type", "application/x-amz-json-1.1")
        .addHeader("x-amzn-ErrorType", type).body("{\"__type\":\"" + type + "\",\"message\":\"" + message + "\"}")
        .build();
  }

  private static MockResponse xml(String body) {
    return new MockResponse.Builder().code(200).addHeader("Content-Type", "text/xml").body(body).build();
  }
}
