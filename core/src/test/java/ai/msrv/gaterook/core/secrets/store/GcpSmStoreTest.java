package ai.msrv.gaterook.core.secrets.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudEndpoints;
import ai.msrv.gaterook.connectors.cloudauth.GcpSpec;
import ai.msrv.gaterook.core.secrets.SecretStores;
import ai.msrv.gaterook.core.secrets.store.gcp.GcpSmStore;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.CRC32C;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * GCP Secret Manager through impersonation from this computer's Google sign-in (report 12 §4.3, §7.6), against a fake
 * token server, IAM and Secret Manager: the access token in the call's taint, CRC32C, the texts of Google's errors.
 */
class GcpSmStoreTest {

  private static final String SA = "gw-secrets@acme-stage.iam.gserviceaccount.com";
  private static final String TOKEN = "ya29.cnry-impersonated-token";

  private MockWebServer server;
  private CloudCredentials cloud;
  private GcpSmStore store;
  private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
  private final Map<String, MockResponse> secrets = new ConcurrentHashMap<>();
  private final List<CharSequence> taint = new ArrayList<>();
  private volatile String permissions = "[\"secretmanager.versions.access\"]";

  @BeforeEach
  void start() throws Exception {
    server = new MockWebServer();
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        requests.add(request);
        String path = request.getUrl().encodedPath();
        if (path.equals("/token")) {
          return json(200, "{\"access_token\":\"ya29.cnry-source-token\",\"expires_in\":3600,\"token_type\":"
              + "\"Bearer\"}");
        }
        if (path.endsWith(":generateAccessToken")) {
          return json(200, "{\"accessToken\":\"" + TOKEN + "\",\"expireTime\":\"" + Instant.now().plusSeconds(900)
              + "\"}");
        }
        if (path.endsWith(":testIamPermissions")) {
          return json(200, "{\"permissions\":" + permissions + "}");
        }
        if (path.equals("/v1/projects/acme-stage/secrets")) {
          return json(403, "{\"error\":{\"code\":403,\"status\":\"PERMISSION_DENIED\"}}");
        }
        MockResponse r = secrets.get(path);
        return r != null ? r : json(404, "{\"error\":{\"code\":404,\"message\":\"Secret [x] not found\",\"status\":"
            + "\"NOT_FOUND\"}}");
      }
    });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
    Path dir = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "gcp-sm-");
    Path adc = Files.writeString(dir.resolve("adc.json"), "{\"type\":\"authorized_user\",\"client_id\":\"c\","
        + "\"client_secret\":\"cnry-client-secret\",\"refresh_token\":\"1//cnry-refresh\"}");
    cloud = new CloudCredentials(new CloudEndpoints(server.url("/token").uri(), server.url("/").toString()
        + "v1/projects/-/serviceAccounts/%s:generateAccessToken", adc, null, null, null), Clock.systemUTC());
    GcpSpec spec = new GcpSpec(GcpSpec.Source.ADC, null, SA, List.of(), 900, null);
    store = new GcpSmStore(new GcpSmStore.Settings("acme-stage", spec, 60, server.url("/").uri()), cloud,
        new CloudCredentials.Owner("store-gcp", 1, "gcp-sm"), MapSecrets::new);
  }

  @AfterEach
  void stop() {
    store.close();
    cloud.close();
    server.close();
  }

  private StoreCall call() {
    return StoreCall.within(v -> taint.add(v.toString()), Duration.ofSeconds(10), Clock.systemUTC());
  }

  private void put(String secret, String version, byte[] payload, Long crc) {
    String body = "{\"name\":\"projects/412398765012/secrets/" + secret + "/versions/" + version + "\",\"payload\":"
        + "{\"data\":\"" + Base64.getEncoder().encodeToString(payload) + "\"" + (crc == null ? "" : ",\"dataCrc32c\":\""
        + crc + "\"") + "}}";
    secrets.put("/v1/projects/acme-stage/secrets/" + secret + "/versions/latest:access", json(200, body));
  }

  private static long crc(byte[] data) {
    CRC32C c = new CRC32C();
    c.update(data);
    return c.getValue();
  }

  @Test
  void readsTheLatestVersionWithTheImpersonatedToken() {
    byte[] value = "cnry-billing-token-81fd".getBytes(StandardCharsets.UTF_8);
    put("billing-api-token", "4", value, crc(value));

    try (RemoteValue v = store.read(SecretRef.parse("gcp-sm://projects/acme-stage/secrets/billing-api-token/versions/"
        + "latest"), call())) {
      assertThat(v.value()).isEqualTo(value);
      assertThat(v.version()).isEqualTo("4");
      assertThat(v.type()).isEqualTo("string");
    }
    RecordedRequest access = requests.stream().filter(r -> r.getUrl().encodedPath().endsWith(":access")).findFirst()
        .orElseThrow();
    assertThat(access.getMethod()).isEqualTo("GET");
    assertThat(access.getHeaders().get("Authorization")).isEqualTo("Bearer " + TOKEN);
    assertThat(taint).extracting(CharSequence::toString).contains(TOKEN);
  }

  @Test
  void readsOneKeyOfAJsonSecret() {
    byte[] json = "{\"username\":\"billing\",\"password\":\"cnry-json-password\"}".getBytes(StandardCharsets.UTF_8);
    put("billing-db", "2", json, null);

    try (RemoteValue v = store.read(SecretRef.parse("gcp-sm://projects/acme-stage/secrets/billing-db#password"),
        call())) {
      assertThat(new String(v.value(), StandardCharsets.UTF_8)).isEqualTo("cnry-json-password");
      assertThat(v.keys()).containsExactlyInAnyOrder("username", "password");
    }
    assertThatThrownBy(() -> store.read(SecretRef.parse("gcp-sm://projects/acme-stage/secrets/billing-db#pass"),
        call())).hasMessageStartingWith("billing-db has no key \"pass\". Keys: ")
        .hasMessageNotContaining("cnry-json-password");

    byte[] plain = "not json".getBytes(StandardCharsets.UTF_8);
    put("plain", "1", plain, null);
    assertThatThrownBy(() -> store.read(SecretRef.parse("gcp-sm://projects/acme-stage/secrets/plain#k"), call()))
        .hasMessage("plain isn't JSON, so it has no key \"k\". Remove #k from the reference.");
  }

  @Test
  void aDamagedPayloadIsRefused() {
    byte[] value = "cnry-damaged".getBytes(StandardCharsets.UTF_8);
    put("damaged", "1", value, crc(value) + 1);
    assertThatThrownBy(() -> store.read(SecretRef.parse("gcp-sm://projects/acme-stage/secrets/damaged"), call()))
        .isInstanceOfSatisfying(StoreException.class,
            e -> assertThat(e.reason()).isEqualTo(StoreException.Reason.UNAVAILABLE))
        .hasMessageContaining("CRC32C mismatch");
  }

  @Test
  void googlesErrorsBecomeTextsToActOn() {
    assertThatThrownBy(() -> store.read(SecretRef.parse("gcp-sm://projects/acme-stage/secrets/missing"), call()))
        .isInstanceOfSatisfying(StoreException.class,
            e -> assertThat(e.reason()).isEqualTo(StoreException.Reason.NOT_FOUND))
        .hasMessage("No secret missing in Secret Manager of project acme-stage.");

    secrets.put("/v1/projects/acme-stage/secrets/old/versions/3:access", json(400, "{\"error\":{\"code\":400,"
        + "\"message\":\"Secret Version [projects/1/secrets/old/versions/3] is in DESTROYED state.\",\"status\":"
        + "\"FAILED_PRECONDITION\"}}"));
    assertThatThrownBy(() -> store.read(SecretRef.parse("gcp-sm://projects/acme-stage/secrets/old/versions/3"),
        call())).isInstanceOfSatisfying(StoreException.class,
            e -> assertThat(e.reason()).isEqualTo(StoreException.Reason.GONE))
        .hasMessage("Version 3 of old is disabled or destroyed.");

    secrets.put("/v1/projects/acme-stage/secrets/locked/versions/latest:access", json(403, "{\"error\":{\"code\":403,"
        + "\"message\":\"Permission 'secretmanager.versions.access' denied\",\"status\":\"PERMISSION_DENIED\"}}"));
    assertThatThrownBy(() -> store.read(SecretRef.parse("gcp-sm://projects/acme-stage/secrets/locked"), call()))
        .hasMessage("The gateway's identity can't read locked: grant it roles/secretmanager.secretAccessor on the "
            + "secret.");
  }

  @Test
  void testWarnsWhenTheIdentityCanAddVersions() {
    permissions = "[\"secretmanager.versions.access\",\"secretmanager.versions.add\"]";
    SecretStores.TestResult result = store.test(List.of(SecretRef.parse(
        "gcp-sm://projects/acme-stage/secrets/billing-api-token/versions/latest")), call());

    assertThat(result.ok()).isTrue();
    assertThat(result.identity().path("identity").asString()).isEqualTo(SA);
    assertThat(result.checks()).extracting(SecretStores.Check::detail).contains("Impersonates " + SA,
        "Can't list the secrets of acme-stage (expected with roles/secretmanager.secretAccessor)");
    assertThat(result.hints()).extracting(SecretStores.Hint::text).containsExactly(
        "This identity can add versions to billing-api-token. Grant only roles/secretmanager.secretAccessor.");
  }

  private static MockResponse json(int code, String body) {
    return new MockResponse.Builder().code(code).addHeader("Content-Type", "application/json; charset=utf-8")
        .body(body).build();
  }
}
