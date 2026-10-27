package ai.msrv.gaterook.connectors.cloudauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.auth.oauth2.GoogleCredentials;
import ai.msrv.gaterook.connector.api.ConnectorException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** ADC, a service account key and impersonation against a fake token server and IAM (report 09 §8). */
class GcpCredentialsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String SA = "gw-reader@acme-stage.iam.gserviceaccount.com";
  private static final CloudCredentials.Owner OWNER = new CloudCredentials.Owner("conn-1", 1, "prod-logs");

  private MockWebServer server;
  private Path dir;
  private CloudCredentials cloud;
  private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
  private volatile int iamStatus = 200;

  @BeforeEach
  void start() throws IOException {
    server = new MockWebServer();
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        requests.add(request);
        String path = request.getUrl().encodedPath();
        if (path.equals("/token")) {
          return json(200, "{\"access_token\":\"ya29.source-token\",\"expires_in\":3600,\"token_type\":\"Bearer\"}");
        }
        if (path.endsWith(":generateAccessToken")) {
          if (iamStatus != 200) {
            return json(iamStatus, "{\"error\":{\"code\":403,\"message\":\"Permission 'iam.serviceAccounts."
                + "getAccessToken' denied\",\"status\":\"PERMISSION_DENIED\"}}");
          }
          return json(200, "{\"accessToken\":\"ya29.impersonated-token\",\"expireTime\":\""
              + Instant.now().plusSeconds(900) + "\"}");
        }
        return new MockResponse.Builder().code(404).build();
      }
    });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
    dir = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "gcp-");
  }

  @AfterEach
  void stop() {
    if (cloud != null) {
      cloud.close();
    }
    server.close();
  }

  @Test
  void impersonatesFromAdcAndReusesTheTokenFor900Seconds() throws Exception {
    Path adc = write("adc.json", "{\"type\":\"authorized_user\",\"client_id\":\"c.apps.googleusercontent.com\","
        + "\"client_secret\":\"d-FL95Q19q7MQmFpd7hHD0Ty\",\"refresh_token\":\"1//0refresh\"}");
    cloud = new CloudCredentials(endpoints(adc), Clock.systemUTC());
    GcpSpec spec = new GcpSpec(GcpSpec.Source.ADC, null, SA, List.of(), 900, null);

    GoogleCredentials credentials = cloud.gcp(OWNER, spec, List.of(CloudCredentials.CLOUD_PLATFORM), new FakeSecrets());
    List<CharSequence> taint = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      Map<String, List<String>> headers = CloudCredentials.requestMetadata(credentials,
          URI.create("https://logging.googleapis.com/v2/entries:list"), taint::add);
      assertThat(headers.get("Authorization")).containsExactly("Bearer ya29.impersonated-token");
    }

    assertThat(taint).extracting(CharSequence::toString).containsOnly("ya29.impersonated-token");
    List<RecordedRequest> iam = requests.stream()
        .filter(r -> r.getUrl().encodedPath().endsWith(":generateAccessToken")).toList();
    assertThat(iam).hasSize(1);
    assertThat(iam.getFirst().getUrl().encodedPath()).contains(SA);
    assertThat(iam.getFirst().getHeaders().get("Authorization")).isEqualTo("Bearer ya29.source-token");
    JsonNode body = JSON.readTree(iam.getFirst().getBody().utf8());
    assertThat(body.path("lifetime").asString()).isEqualTo("900s");
    assertThat(body.path("scope").get(0).asString()).isEqualTo(CloudCredentials.CLOUD_PLATFORM);
    assertThat(cloud.gcp(OWNER, spec, List.of(CloudCredentials.CLOUD_PLATFORM), new FakeSecrets()))
        .as("cached per owner and version").isSameAs(credentials);
    assertThat(cloud.issued()).singleElement().satisfies(issued -> {
      assertThat(issued.owner()).isEqualTo("prod-logs");
      assertThat(issued.mechanism()).isEqualTo("GCP impersonation");
      assertThat(issued.identity()).isEqualTo(SA);
      assertThat(issued.lastIssuedAt()).isNotNull();
      assertThat(issued.lifetime()).hasSeconds(900);
    });
  }

  @Test
  void tellsWhichRoleIsMissingWhenImpersonationIsDenied() throws Exception {
    iamStatus = 403;
    Path adc = write("adc.json", "{\"type\":\"authorized_user\",\"client_id\":\"c\",\"client_secret\":\"s\","
        + "\"refresh_token\":\"r\"}");
    cloud = new CloudCredentials(endpoints(adc), Clock.systemUTC());
    GoogleCredentials credentials = cloud.gcp(OWNER, new GcpSpec(GcpSpec.Source.ADC, null, SA, List.of(), 900, null),
        List.of(CloudCredentials.CLOUD_PLATFORM), new FakeSecrets());

    assertThatThrownBy(() -> CloudCredentials.requestMetadata(credentials, URI.create("https://x.googleapis.com/"),
        t -> { })).isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_UNAVAILABLE);
          assertThat(e.getMessage()).contains("roles/iam.serviceAccountTokenCreator").contains(SA);
        });
  }

  @Test
  void signsInWithAServiceAccountKeyFromTheResolver() throws Exception {
    cloud = new CloudCredentials(endpoints(dir.resolve("none.json")), Clock.systemUTC());
    FakeSecrets secrets = new FakeSecrets().with("/credentials/serviceAccountKey",
        serviceAccountKey("https://oauth2.googleapis.com/token"));
    GcpSpec spec = new GcpSpec(GcpSpec.Source.SERVICE_ACCOUNT_KEY, "/credentials/serviceAccountKey", null, List.of(),
        900, "acme-billing");

    GoogleCredentials credentials = cloud.gcp(OWNER, spec, List.of(CloudCredentials.CLOUD_PLATFORM), secrets);
    Map<String, List<String>> headers = CloudCredentials.requestMetadata(credentials,
        URI.create("https://monitoring.googleapis.com/v3/projects/x/timeSeries"), t -> { });

    assertThat(headers.get("Authorization")).containsExactly("Bearer ya29.source-token");
    assertThat(headers.get("x-goog-user-project")).containsExactly("acme-billing");
    RecordedRequest token = server.takeRequest(5, TimeUnit.SECONDS);
    assertThat(token.getBody().utf8()).contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer");
  }

  @Test
  void refusesAKeyWhoseTokenRequestsGoElsewhere() throws Exception {
    cloud = new CloudCredentials(endpoints(dir.resolve("none.json")), Clock.systemUTC());
    FakeSecrets secrets = new FakeSecrets().with("/k", serviceAccountKey("https://evil.example/token"));

    assertThatThrownBy(() -> cloud.gcp(OWNER, new GcpSpec(GcpSpec.Source.SERVICE_ACCOUNT_KEY, "/k", null, List.of(),
        900, null), List.of(CloudCredentials.CLOUD_PLATFORM), secrets))
        .isInstanceOf(ConnectorException.class).hasMessageContaining("evil.example");
    assertThat(server.getRequestCount()).isZero();
  }

  @Test
  void refusesAKeyOrSignInOfAnotherUniverse() throws Exception {
    // google-auth would build the impersonation's IAM address and the self-signed token's audience from it.
    cloud = new CloudCredentials(endpoints(dir.resolve("none.json")), Clock.systemUTC());
    ObjectNode key = (ObjectNode) JSON.readTree(serviceAccountKey("https://oauth2.googleapis.com/token"));
    key.put("universe_domain", "attacker.example");
    FakeSecrets secrets = new FakeSecrets().with("/k", JSON.writeValueAsString(key));
    GcpSpec impersonating = new GcpSpec(GcpSpec.Source.SERVICE_ACCOUNT_KEY, "/k", SA, List.of(), 900, null);

    assertThatThrownBy(() -> cloud.gcp(OWNER, impersonating, List.of(CloudCredentials.CLOUD_PLATFORM), secrets))
        .isInstanceOf(ConnectorException.class).hasMessageContaining("attacker.example");
    CloudCredentials adc = new CloudCredentials(endpoints(write("adc-universe.json", "{\"type\":\"authorized_user\","
        + "\"client_id\":\"c\",\"client_secret\":\"s\",\"refresh_token\":\"r\",\"universe_domain\":\"evil.example\"}")),
        Clock.systemUTC());
    assertThatThrownBy(() -> adc.gcp(OWNER, new GcpSpec(GcpSpec.Source.ADC, null, SA, List.of(), 900, null),
        List.of(CloudCredentials.CLOUD_PLATFORM), new FakeSecrets()))
        .isInstanceOf(ConnectorException.class).hasMessageContaining("evil.example");
    adc.close();
    assertThat(server.getRequestCount()).isZero();

    key.put("universe_domain", "googleapis.com");
    FakeSecrets google = new FakeSecrets().with("/k", JSON.writeValueAsString(key));
    assertThat(cloud.gcp(OWNER, new GcpSpec(GcpSpec.Source.SERVICE_ACCOUNT_KEY, "/k", null, List.of(), 900, null),
        List.of(CloudCredentials.CLOUD_PLATFORM), google)).isNotNull();
  }

  @Test
  void refusesSignInsThatRunProgramsOrAskTheMetadataServer() throws Exception {
    Path external = write("ext.json", "{\"type\":\"external_account\",\"audience\":\"x\",\"subject_token_type\":\"y\","
        + "\"credential_source\":{\"executable\":{\"command\":\"/bin/evil\"}}}");
    cloud = new CloudCredentials(endpoints(external), Clock.systemUTC());
    GcpSpec adc = new GcpSpec(GcpSpec.Source.ADC, null, null, List.of(), 900, null);

    assertThatThrownBy(() -> cloud.gcp(OWNER, adc, List.of(CloudCredentials.CLOUD_PLATFORM), new FakeSecrets()))
        .isInstanceOf(ConnectorException.class).hasMessageContaining("external_account");

    CloudCredentials none = new CloudCredentials(endpoints(dir.resolve("missing.json")), Clock.systemUTC());
    assertThatThrownBy(() -> none.gcp(OWNER, adc, List.of(CloudCredentials.CLOUD_PLATFORM), new FakeSecrets()))
        .isInstanceOf(ConnectorException.class).hasMessageContaining("gcloud auth application-default login");
    none.close();
  }

  @Test
  void usesAnImpersonatedSignInOfThisComputer() throws Exception {
    Path adc = write("adc.json", impersonatedSignIn("https://iamcredentials.googleapis.com/v1/projects/-/"
        + "serviceAccounts/" + SA + ":generateAccessToken", "{\"type\":\"authorized_user\",\"client_id\":\"c\","
        + "\"client_secret\":\"s\",\"refresh_token\":\"1//0refresh\"}"));
    cloud = new CloudCredentials(endpoints(adc), Clock.systemUTC());

    GoogleCredentials credentials = cloud.gcp(OWNER, new GcpSpec(GcpSpec.Source.ADC, null, null, List.of(), 900, null),
        List.of(CloudCredentials.CLOUD_PLATFORM), new FakeSecrets());
    Map<String, List<String>> headers = CloudCredentials.requestMetadata(credentials,
        URI.create("https://logging.googleapis.com/v2/entries:list"), t -> { });

    assertThat(headers.get("Authorization")).containsExactly("Bearer ya29.impersonated-token");
    assertThat(headers.get("x-goog-user-project")).containsExactly("acme-billing");
    RecordedRequest iam = requests.stream().filter(r -> r.getUrl().encodedPath().endsWith(":generateAccessToken"))
        .findFirst().orElseThrow();
    assertThat(iam.getUrl().encodedPath()).contains(SA);
    assertThat(iam.getHeaders().get("Authorization")).isEqualTo("Bearer ya29.source-token");
    assertThat(JSON.readTree(iam.getBody().utf8()).path("delegates").get(0).asString())
        .isEqualTo("projects/-/serviceAccounts/hop@acme-stage.iam.gserviceaccount.com");
  }

  @Test
  void anImpersonatedSignInGetsTheChecksOfItsParts() throws Exception {
    String google = "https://iamcredentials.googleapis.com/v1/projects/-/serviceAccounts/" + SA
        + ":generateAccessToken";
    String user = "{\"type\":\"authorized_user\",\"client_id\":\"c\",\"client_secret\":\"s\",\"refresh_token\":\"r\"}";
    Map<String, String> refused = new java.util.LinkedHashMap<>();
    refused.put(impersonatedSignIn("https://iam.evil.example/v1/projects/-/serviceAccounts/" + SA
        + ":generateAccessToken", user), "iam.evil.example");
    refused.put(impersonatedSignIn("http://iamcredentials.googleapis.com/v1/projects/-/serviceAccounts/" + SA
        + ":generateAccessToken", user), "iamcredentials.googleapis.com");
    refused.put(impersonatedSignIn(google, serviceAccountKey("https://evil.example/token")), "evil.example");
    refused.put(impersonatedSignIn(google, "{\"type\":\"authorized_user\",\"client_id\":\"c\",\"client_secret\":\"s\","
        + "\"refresh_token\":\"r\",\"token_uri\":\"https://tokens.evil.example/token\"}"), "tokens.evil.example");
    refused.put(impersonatedSignIn(google, "{\"type\":\"external_account\",\"audience\":\"x\"}"), "external_account");
    refused.put("{\"type\":\"authorized_user\",\"client_id\":\"c\",\"client_secret\":\"s\",\"refresh_token\":\"r\","
        + "\"token_uri\":\"https://tokens.evil.example/token\"}", "tokens.evil.example");
    GcpSpec adc = new GcpSpec(GcpSpec.Source.ADC, null, null, List.of(), 900, null);

    int n = 0;
    for (Map.Entry<String, String> file : refused.entrySet()) {
      CloudCredentials credentials = new CloudCredentials(endpoints(write("adc-" + n++ + ".json", file.getKey())),
          Clock.systemUTC());
      assertThatThrownBy(() -> credentials.gcp(OWNER, adc, List.of(CloudCredentials.CLOUD_PLATFORM),
          new FakeSecrets())).as(file.getKey()).isInstanceOfSatisfying(ConnectorException.class, e -> {
            assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_UNAVAILABLE);
            assertThat(e.getMessage()).contains(file.getValue());
          });
      credentials.close();
    }
    assertThat(server.getRequestCount()).isZero();
  }

  @Test
  void readsTheFormsCredentialsObject() {
    ObjectNode config = JSON.createObjectNode();
    config.putObject("credentials").put("source", "adc").put("impersonate", SA).put("lifetimeSeconds", 600)
        .putArray("delegates").add("a@b.iam.gserviceaccount.com");
    GcpSpec spec = GcpSpec.fromConfig(config, "/credentials");
    assertThat(spec.source()).isEqualTo(GcpSpec.Source.ADC);
    assertThat(spec.impersonate()).isEqualTo(SA);
    assertThat(spec.lifetimeSeconds()).isEqualTo(600);
    assertThat(spec.delegates()).containsExactly("a@b.iam.gserviceaccount.com");
    config.putObject("key").put("source", "serviceAccountKey");
    assertThat(GcpSpec.fromConfig(config, "/key").keyField()).isEqualTo("/key/serviceAccountKey");
  }

  private CloudEndpoints endpoints(Path adc) {
    return new CloudEndpoints(server.url("/token").uri(),
        server.url("/").toString() + "v1/projects/-/serviceAccounts/%s:generateAccessToken", adc, null, null, null);
  }

  private Path write(String name, String content) throws IOException {
    return Files.writeString(dir.resolve(name), content);
  }

  /** What {@code gcloud auth application-default login --impersonate-service-account} writes. */
  private static String impersonatedSignIn(String impersonationUrl, String source) {
    ObjectNode file = JSON.createObjectNode();
    file.put("type", "impersonated_service_account").put("service_account_impersonation_url", impersonationUrl)
        .put("quota_project_id", "acme-billing").set("source_credentials", JSON.readTree(source));
    file.putArray("delegates").add("projects/-/serviceAccounts/hop@acme-stage.iam.gserviceaccount.com");
    return JSON.writeValueAsString(file);
  }

  private static String serviceAccountKey(String tokenUri) throws Exception {
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    String pem = "-----BEGIN PRIVATE KEY-----\n"
        + Base64.getMimeEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
        + "\n-----END PRIVATE KEY-----\n";
    ObjectNode key = JSON.createObjectNode();
    key.put("type", "service_account").put("project_id", "acme-stage").put("private_key_id", "abc123")
        .put("private_key", pem).put("client_email", SA).put("client_id", "1234567890")
        .put("token_uri", tokenUri);
    return JSON.writeValueAsString(key);
  }

  private static MockResponse json(int code, String body) {
    return new MockResponse.Builder().code(code).addHeader("Content-Type", "application/json; charset=utf-8")
        .body(body).build();
  }
}
