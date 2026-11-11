package ai.msrv.gaterook.core.secrets.store.azure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudEndpoints;
import ai.msrv.gaterook.core.secrets.SecretStores;
import ai.msrv.gaterook.core.secrets.store.MapSecrets;
import ai.msrv.gaterook.core.secrets.store.RemoteValue;
import ai.msrv.gaterook.core.secrets.store.SecretRef;
import ai.msrv.gaterook.core.secrets.store.StoreCall;
import ai.msrv.gaterook.core.secrets.store.StoreException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.tls.HeldCertificate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Azure Key Vault as an external store (report 12 §4.3, §5), against a fake Entra ID token endpoint and a fake vault
 * (the vault's host is the first path segment, as {@code AzureCredentials.dataPlane} puts it under a test endpoint):
 * the Key Vault token asked for with the service principal's secret or certificate, handed to the call's taint and sent
 * to the vault only; values, JSON keys and pinned versions; and Key Vault's errors as texts to act on.
 */
class AzureKvStoreTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String TENANT = "6f1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d";
  private static final String CLIENT = "0a1b2c3d-1111-4222-8333-444455556666";
  private static final String CLIENT_SECRET = "cnry-azure-client-secret-5e1a";
  private static final String HOST = "acme-kv.vault.azure.net";
  private static final String CURRENT = "4387e9f3d6e14c459867679a90fd0f79";
  private static final String OLDER = "0f1e2d3c4b5a69788796a5b4c3d2e1f0";
  private static final long CREATED = 1_760_600_000L;

  private MockWebServer server;
  private CloudCredentials cloud;
  private AzureKvStore store;
  private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
  private final Map<String, MockResponse> vault = new ConcurrentHashMap<>();
  private final List<String> tokens = new CopyOnWriteArrayList<>();
  private final List<CharSequence> taint = new CopyOnWriteArrayList<>();
  private final AtomicReference<MockResponse> tokenAnswer = new AtomicReference<>();
  private final AtomicReference<MockResponse> listAnswer = new AtomicReference<>();
  private final AtomicInteger issued = new AtomicInteger();

  @BeforeEach
  void start() throws Exception {
    listAnswer.set(json(200, "{\"value\":[{\"id\":\"https://" + HOST + "/secrets/billing-db\",\"attributes\":"
        + "{\"enabled\":true}}],\"nextLink\":null}"));
    server = new MockWebServer();
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        requests.add(request);
        String path = request.getUrl().encodedPath();
        if (path.endsWith("/oauth2/v2.0/token")) {
          MockResponse custom = tokenAnswer.get();
          if (custom != null) {
            return custom;
          }
          String token = token(issued.incrementAndGet());
          tokens.add(token);
          return json(200, "{\"token_type\":\"Bearer\",\"expires_in\":3599,\"access_token\":\"" + token + "\"}");
        }
        if (path.endsWith(".vault.azure.net/secrets") || path.endsWith(".vault.usgovcloudapi.net/secrets")) {
          return listAnswer.get();
        }
        MockResponse r = vault.get(path);
        return r != null ? r : json(404, "{\"error\":{\"code\":\"SecretNotFound\",\"message\":\"A secret with "
            + "(name/id) x was not found in this key vault. If you recently deleted this secret you may be able to "
            + "recover it using the correct recovery command.\"}}");
      }
    });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
    cloud = credentials(Map.of("azure.login", server.url("/").uri(), "azure.keyvault", server.url("/").uri()));
    store = store(config("AzureCloud", "clientSecret"), () -> new MapSecrets().with(AzureKvStore.CLIENT_SECRET,
        CLIENT_SECRET));
  }

  @AfterEach
  void stop() {
    store.close();
    cloud.close();
    server.close();
  }

  private static CloudCredentials credentials(Map<String, URI> services) {
    return new CloudCredentials(new CloudEndpoints(null, null, null, null, null, null, services), Clock.systemUTC());
  }

  private static JsonNode config(String cloud, String source) {
    ObjectNode c = JSON.createObjectNode().put("vault", "acme-kv").put("cloud", cloud).put("tenantId", TENANT);
    c.putObject("credentials").put("source", source).put("clientId", CLIENT);
    return c;
  }

  private AzureKvStore store(JsonNode config, Supplier<MapSecrets> secrets) {
    return new AzureKvStore(AzureKvStore.Settings.of(config), cloud, new CloudCredentials.Owner("store-azure", 0,
        "azure-acme-kv"), secrets);
  }

  private StoreCall call() {
    return StoreCall.within(v -> taint.add(v.toString()), Duration.ofSeconds(10), Clock.systemUTC());
  }

  /** A token of the test tenant for the service principal: an unsigned JWT, read and never checked. */
  private static String token(int n) {
    ObjectNode claims = JSON.createObjectNode().put("tid", TENANT).put("appid", CLIENT).put("idtyp", "app")
        .put("oid", "sp-object-id").put("exp", Instant.now().plusSeconds(3600).getEpochSecond())
        .put("n", "cnry-kv-token-" + n);
    Base64.Encoder e = Base64.getUrlEncoder().withoutPadding();
    return e.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
        + e.encodeToString(JSON.writeValueAsBytes(claims)) + ".c2ln";
  }

  /** The secret {@code name} at {@code version}, as the current one and as that version. */
  private void put(String host, String name, String version, String value, boolean enabled, boolean current) {
    ObjectNode body = JSON.createObjectNode().put("value", value).put("id", "https://" + host + "/secrets/" + name
        + "/" + version).put("contentType", "text/plain");
    body.putObject("attributes").put("enabled", enabled).put("created", CREATED).put("updated", CREATED)
        .put("recoveryLevel", "Recoverable+Purgeable");
    body.putObject("tags");
    MockResponse answer = json(200, body.toString());
    vault.put("/" + host + "/secrets/" + name + "/" + version, answer);
    if (current) {
      vault.put("/" + host + "/secrets/" + name, answer);
    }
  }

  private void put(String name, String value) {
    put(HOST, name, CURRENT, value, true, true);
  }

  private List<RecordedRequest> tokenRequests() {
    return requests.stream().filter(r -> r.getUrl().encodedPath().endsWith("/oauth2/v2.0/token")).toList();
  }

  private List<RecordedRequest> vaultRequests() {
    return requests.stream().filter(r -> !r.getUrl().encodedPath().endsWith("/oauth2/v2.0/token")).toList();
  }

  private static Map<String, String> form(RecordedRequest r) {
    Map<String, String> out = new LinkedHashMap<>();
    for (String pair : r.getBody().utf8().split("&")) {
      int eq = pair.indexOf('=');
      out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
          URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
    }
    return out;
  }

  private static String error(String code, String inner, String message) {
    return "{\"error\":{\"code\":\"" + code + "\",\"message\":\"" + message + "\"" + (inner == null ? ""
        : ",\"innererror\":{\"code\":\"" + inner + "\"}") + "}}";
  }

  // ── Reads ───────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void readsTheCurrentVersionWithAKeyVaultTokenThatGoesToTheVaultOnly() {
    put("billing-api-token", "cnry-billing-token-81fd");

    try (RemoteValue v = store.read(SecretRef.parse("azure-kv://acme-kv/billing-api-token"), call())) {
      assertThat(new String(v.value(), StandardCharsets.UTF_8)).isEqualTo("cnry-billing-token-81fd");
      assertThat(v.version()).isEqualTo(CURRENT);
      assertThat(v.created()).isEqualTo(Instant.ofEpochSecond(CREATED));
      assertThat(v.type()).isEqualTo("string");
      assertThat(v.keys()).isEmpty();
    }

    RecordedRequest sign = tokenRequests().getFirst();
    assertThat(sign.getUrl().encodedPath()).isEqualTo("/" + TENANT + "/oauth2/v2.0/token");
    assertThat(sign.getHeaders().get("Authorization")).isNull();
    assertThat(form(sign)).containsEntry("grant_type", "client_credentials").containsEntry("client_id", CLIENT)
        .containsEntry("scope", "https://vault.azure.net/.default").containsEntry("client_secret", CLIENT_SECRET);

    RecordedRequest read = vaultRequests().getFirst();
    assertThat(read.getMethod()).isEqualTo("GET");
    assertThat(read.getUrl().encodedPath()).isEqualTo("/" + HOST + "/secrets/billing-api-token");
    assertThat(read.getUrl().queryParameter("api-version")).isEqualTo("7.5");
    assertThat(read.getHeaders().get("Authorization")).isEqualTo("Bearer " + tokens.getFirst());
    assertThat(read.getHeaders().get("Accept")).isEqualTo("application/json");
    assertThat(taint).extracting(CharSequence::toString).contains(tokens.getFirst());
    // The token goes to the vault only; the client secret goes to the sign-in only.
    for (RecordedRequest r : requests) {
      if (r.getHeaders().get("Authorization") != null) {
        assertThat(r.getUrl().encodedPath()).startsWith("/" + HOST + "/");
      }
    }
    for (RecordedRequest r : vaultRequests()) {
      assertThat(r.getHeaders().toString() + (r.getBody() == null ? "" : r.getBody().utf8()))
          .doesNotContain(CLIENT_SECRET);
    }

    // The next read reuses the token.
    try (RemoteValue v = store.read(SecretRef.parse("azure-kv://acme-kv/billing-api-token"), call())) {
      assertThat(v.version()).isEqualTo(CURRENT);
    }
    assertThat(tokenRequests()).hasSize(1);
  }

  @Test
  void readsOneKeyOfAJsonSecret() {
    put("billing-db", "{\"username\":\"billing\",\"password\":\"cnry-json-password\"}");

    try (RemoteValue v = store.read(SecretRef.parse("azure-kv://acme-kv/billing-db#password"), call())) {
      assertThat(new String(v.value(), StandardCharsets.UTF_8)).isEqualTo("cnry-json-password");
      assertThat(v.type()).isEqualTo("string");
      assertThat(v.keys()).containsExactly("username", "password");
    }
    try (RemoteValue v = store.read(SecretRef.parse("azure-kv://acme-kv/billing-db"), call())) {
      assertThat(v.type()).isEqualTo("json");
      assertThat(v.keys()).containsExactly("username", "password");
    }
    assertThatThrownBy(() -> store.read(SecretRef.parse("azure-kv://acme-kv/billing-db#pass"), call()))
        .hasMessage("Key \"pass\" was not found in this secret. Check the key after # in the reference.")
        .hasMessageNotContaining("cnry-json-password");

    put("plain", "not json");
    assertThatThrownBy(() -> store.read(SecretRef.parse("azure-kv://acme-kv/plain#k"), call()))
        .hasMessage("plain isn't JSON, so it has no key \"k\". Remove #k from the reference.");
  }

  @Test
  void aPinnedVersionIsReadAsNamed() {
    put(HOST, "billing-db", CURRENT, "cnry-new-password", true, true);
    put(HOST, "billing-db", OLDER, "cnry-old-password", true, false);

    try (RemoteValue v = store.read(SecretRef.parse("azure-kv://acme-kv/billing-db/" + OLDER), call())) {
      assertThat(new String(v.value(), StandardCharsets.UTF_8)).isEqualTo("cnry-old-password");
      assertThat(v.version()).isEqualTo(OLDER);
    }
    assertThat(vaultRequests().getLast().getUrl().encodedPath()).isEqualTo("/" + HOST + "/secrets/billing-db/" + OLDER);

    assertThatThrownBy(() -> store.read(SecretRef.parse("azure-kv://acme-kv/billing-db/"
        + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), call()))
        .isInstanceOfSatisfying(StoreException.class, e -> assertThat(e.reason())
            .isEqualTo(StoreException.Reason.NOT_FOUND))
        .hasMessage("No secret billing-db version aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa in key vault acme-kv.");
  }

  @Test
  void aReferenceOfAnotherVaultIsRefusedBeforeAnythingIsSent() {
    assertThatThrownBy(() -> store.read(SecretRef.parse("azure-kv://other-kv/billing-db"), call()))
        .isInstanceOfSatisfying(StoreException.class, e -> assertThat(e.reason())
            .isEqualTo(StoreException.Reason.INVALID))
        .hasMessage("azure-kv://other-kv/billing-db is in key vault other-kv, and this store reads acme-kv only. "
            + "Connect other-kv as a store of its own.");
    assertThat(requests).isEmpty();
  }

  // ── Errors ──────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void keyVaultsErrorsBecomeTextsToActOn() {
    assertThatThrownBy(() -> store.read(SecretRef.parse("azure-kv://acme-kv/missing"), call()))
        .isInstanceOfSatisfying(StoreException.class, e -> assertThat(e.reason())
            .isEqualTo(StoreException.Reason.NOT_FOUND))
        .hasMessage("No secret missing in key vault acme-kv.");

    vault.put("/" + HOST + "/secrets/locked", json(403, error("Forbidden", "ForbiddenByRbac", "Caller is not "
        + "authorized to perform action on resource. Caller: appid=" + CLIENT + ";oid=sp-object-id Vault: acme-kv;"
        + "location=westeurope")));
    assertThatThrownBy(() -> store.read(SecretRef.parse("azure-kv://acme-kv/locked"), call()))
        .isInstanceOfSatisfying(StoreException.class, e -> assertThat(e.reason())
            .isEqualTo(StoreException.Reason.DENIED))
        .hasMessage("The gateway's identity (service principal " + CLIENT + ") can't read locked: assign Key Vault "
            + "Secrets User on the secret or vault.")
        .hasMessageNotContaining("westeurope");

    vault.put("/" + HOST + "/secrets/policy", json(403, error("Forbidden", "AccessDenied", "The user, group or "
        + "application does not have secrets get permission on key vault.")));
    assertThatThrownBy(() -> store.read(SecretRef.parse("azure-kv://acme-kv/policy"), call()))
        .hasMessage("The gateway's identity (service principal " + CLIENT + ") can't read policy: assign Key Vault "
            + "Secrets User on the secret or vault, or allow Get on secrets in the vault's access policy.");

    vault.put("/" + HOST + "/secrets/walled", json(403, error("Forbidden", "ForbiddenByFirewall", "Client address "
        + "is not authorized and caller is not a trusted service. Client address: 203.0.113.7")));
    assertThatThrownBy(() -> store.read(SecretRef.parse("azure-kv://acme-kv/walled"), call()))
        .isInstanceOfSatisfying(StoreException.class, e -> assertThat(e.reason())
            .isEqualTo(StoreException.Reason.DENIED))
        .hasMessage("The firewall of key vault acme-kv blocks this computer: allow its address under Networking in "
            + "the vault, then test again.")
        .hasMessageNotContaining("203.0.113.7");

    vault.put("/" + HOST + "/secrets/old", json(403, error("Forbidden", "SecretDisabled", "Operation get is not "
        + "allowed on a disabled secret.")));
    assertThatThrownBy(() -> store.read(SecretRef.parse("azure-kv://acme-kv/old"), call()))
        .isInstanceOfSatisfying(StoreException.class, e -> assertThat(e.reason())
            .isEqualTo(StoreException.Reason.GONE))
        .hasMessage("old is disabled in key vault acme-kv. Enable it there, or point to another version.");
    put(HOST, "old", OLDER, "cnry-disabled", false, false);
    assertThatThrownBy(() -> store.read(SecretRef.parse("azure-kv://acme-kv/old/" + OLDER), call()))
        .isInstanceOfSatisfying(StoreException.class, e -> assertThat(e.reason())
            .isEqualTo(StoreException.Reason.GONE))
        .hasMessage("Version " + OLDER + " of old is disabled in key vault acme-kv. Enable it there, or point to "
            + "another version.");

    vault.put("/" + HOST + "/secrets/busy", json(429, error("Throttled", null, "Too many requests.")));
    assertThatThrownBy(() -> store.read(SecretRef.parse("azure-kv://acme-kv/busy"), call()))
        .isInstanceOfSatisfying(StoreException.class, e -> assertThat(e.reason())
            .isEqualTo(StoreException.Reason.UNAVAILABLE))
        .hasMessageContaining("throttling");
    vault.put("/" + HOST + "/secrets/down", json(503, "{}"));
    assertThatThrownBy(() -> store.read(SecretRef.parse("azure-kv://acme-kv/down"), call()))
        .isInstanceOfSatisfying(StoreException.class, e -> assertThat(e.reason())
            .isEqualTo(StoreException.Reason.UNAVAILABLE))
        .hasMessage("Key vault acme-kv is unavailable (HTTP 503). Test again.");
    vault.put("/" + HOST + "/secrets/odd", json(400, error("Bad<Parameter>", null, "x")));
    assertThatThrownBy(() -> store.read(SecretRef.parse("azure-kv://acme-kv/odd"), call()))
        .isInstanceOfSatisfying(StoreException.class, e -> assertThat(e.reason())
            .isEqualTo(StoreException.Reason.INVALID))
        .hasMessage("Key vault acme-kv refused the request (HTTP 400).");
  }

  @Test
  void aRefusedTokenIsDroppedSoTheNextReadSignsInAgain() {
    vault.put("/" + HOST + "/secrets/billing-db", json(401, error("Unauthorized", null, "AKV10032: Invalid issuer. "
        + "Expected one of https://sts.windows.net/other/.")));
    assertThatThrownBy(() -> store.read(SecretRef.parse("azure-kv://acme-kv/billing-db"), call()))
        .isInstanceOfSatisfying(StoreException.class, e -> assertThat(e.reason())
            .isEqualTo(StoreException.Reason.UNAVAILABLE))
        .hasMessage("Key vault acme-kv refused the gateway's token (AKV10032). Check that the vault belongs to tenant "
            + TENANT + ", then test again.");
    put("billing-db", "cnry-after-401");
    try (RemoteValue v = store.read(SecretRef.parse("azure-kv://acme-kv/billing-db"), call())) {
      assertThat(new String(v.value(), StandardCharsets.UTF_8)).isEqualTo("cnry-after-401");
    }
    assertThat(tokenRequests()).hasSize(2);
    assertThat(vaultRequests().getLast().getHeaders().get("Authorization")).isEqualTo("Bearer " + tokens.getLast());
  }

  @Test
  void anAnswerLargerThanAnySecretIsRefused() {
    vault.put("/" + HOST + "/secrets/huge", json(200, "{\"value\":\"" + "x".repeat((1 << 20) + 10) + "\"}"));
    assertThatThrownBy(() -> store.read(SecretRef.parse("azure-kv://acme-kv/huge"), call()))
        .isInstanceOfSatisfying(StoreException.class, e -> assertThat(e.reason())
            .isEqualTo(StoreException.Reason.UNAVAILABLE))
        .hasMessage("Key vault acme-kv answered with more than 1 MB.");
  }

  @Test
  void aFailedSignInSaysWhatEntraIdRefused() {
    tokenAnswer.set(json(401, "{\"error\":\"invalid_client\",\"error_description\":\"AADSTS7000215: Invalid client "
        + "secret provided.\"}"));
    SecretStores.TestResult result = store.test(List.of(), call());
    assertThat(result.ok()).isFalse();
    assertThat(result.reason()).isEqualTo("unavailable");
    assertThat(result.error()).isEqualTo("Entra ID refused the client secret: it is wrong (use the secret's value, "
        + "not its ID). Replace it under Credentials.");
    assertThat(vaultRequests()).isEmpty();
  }

  @Test
  void tokensGoNowhereButATestVaultWhenOnlyTheVaultIsFaked() {
    // A Key Vault test endpoint without the test sign-in: a real Entra ID token must never reach a fake vault.
    try (CloudCredentials real = credentials(Map.of("azure.keyvault", server.url("/").uri()));
        AzureKvStore misdirected = new AzureKvStore(AzureKvStore.Settings.of(config("AzureCloud", "clientSecret")),
            real, new CloudCredentials.Owner("store-azure-2", 0, "azure-acme-kv"),
            () -> new MapSecrets().with(AzureKvStore.CLIENT_SECRET, CLIENT_SECRET))) {
      assertThatThrownBy(() -> misdirected.read(SecretRef.parse("azure-kv://acme-kv/billing-db"), call()))
          .isInstanceOfSatisfying(StoreException.class, e -> assertThat(e.reason())
              .isEqualTo(StoreException.Reason.INVALID))
          .hasMessage("GateRook sends Key Vault tokens to acme-kv.vault.azure.net only.");
    }
    assertThat(requests).isEmpty();
  }

  // ── Sign-ins and clouds ─────────────────────────────────────────────────────────────────────────────────────

  @Test
  void aCertificateSignsInWithAnAssertionInsteadOfASecret() {
    HeldCertificate held = new HeldCertificate.Builder().rsa2048().commonName("gaterook-secrets").build();
    store.close();
    store = store(config("AzureCloud", "clientCertificate"), () -> new MapSecrets().with(AzureKvStore.CERTIFICATE,
        held.privateKeyPkcs8Pem() + held.certificatePem()));
    put("billing-db", "cnry-cert-read");

    try (RemoteValue v = store.read(SecretRef.parse("azure-kv://acme-kv/billing-db"), call())) {
      assertThat(new String(v.value(), StandardCharsets.UTF_8)).isEqualTo("cnry-cert-read");
    }
    Map<String, String> form = form(tokenRequests().getFirst());
    assertThat(form).containsEntry("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer")
        .containsEntry("scope", "https://vault.azure.net/.default").containsKey("client_assertion")
        .doesNotContainKey("client_secret");
  }

  @Test
  void azureGovernmentHasItsOwnVaultHostAndAudience() {
    store.close();
    store = store(config("AzureUSGovernment", "clientSecret"), () -> new MapSecrets().with(
        AzureKvStore.CLIENT_SECRET, CLIENT_SECRET));
    assertThat(store.settings().host()).isEqualTo("acme-kv.vault.usgovcloudapi.net");
    put("acme-kv.vault.usgovcloudapi.net", "billing-db", CURRENT, "cnry-gov-read", true, true);

    try (RemoteValue v = store.read(SecretRef.parse("azure-kv://acme-kv/billing-db"), call())) {
      assertThat(new String(v.value(), StandardCharsets.UTF_8)).isEqualTo("cnry-gov-read");
    }
    assertThat(form(tokenRequests().getFirst())).containsEntry("scope", "https://vault.usgovcloudapi.net/.default");
    assertThat(vaultRequests().getFirst().getUrl().encodedPath())
        .isEqualTo("/acme-kv.vault.usgovcloudapi.net/secrets/billing-db");
  }

  @Test
  void closingForgetsTheStoresTokens() {
    put("billing-db", "cnry-before-close");
    store.read(SecretRef.parse("azure-kv://acme-kv/billing-db"), call()).close();
    store.close();
    store = store(config("AzureCloud", "clientSecret"), () -> new MapSecrets().with(AzureKvStore.CLIENT_SECRET,
        CLIENT_SECRET));
    store.read(SecretRef.parse("azure-kv://acme-kv/billing-db"), call()).close();
    assertThat(tokenRequests()).as("the closed store's token went with it").hasSize(2);
  }

  @Test
  void settingsAreCheckedAndOnlyAServicePrincipalSignsIn() {
    for (String source : List.of("cli", "deviceCode")) {
      assertThatThrownBy(() -> AzureKvStore.Settings.of(config("AzureCloud", source)))
          .isInstanceOfSatisfying(StoreException.class, e -> assertThat(e.reason())
              .isEqualTo(StoreException.Reason.INVALID))
          .hasMessage("A Key Vault store signs in as a service principal, with a client secret or a certificate. "
              + "The Azure CLI and signing in with a code aren't available for stores yet.");
    }
    ObjectNode address = (ObjectNode) config("AzureCloud", "clientSecret");
    address.put("vault", "acme-kv.vault.azure.net");
    assertThatThrownBy(() -> AzureKvStore.Settings.of(address)).hasMessage("Enter the key vault's name, like "
        + "acme-kv, not its address.");
    ObjectNode shortName = (ObjectNode) config("AzureCloud", "clientSecret");
    shortName.put("vault", "kv");
    assertThatThrownBy(() -> AzureKvStore.Settings.of(shortName)).hasMessageContaining("3 to 24 lower-case letters");
    ObjectNode noTenant = (ObjectNode) config("AzureCloud", "clientSecret");
    noTenant.remove("tenantId");
    assertThatThrownBy(() -> AzureKvStore.Settings.of(noTenant)).hasMessageContaining("tenant");
    ObjectNode badCloud = (ObjectNode) config("AzureCloud", "clientSecret");
    badCloud.put("cloud", "AzureGermanCloud");
    assertThatThrownBy(() -> AzureKvStore.Settings.of(badCloud)).hasMessageContaining("Unknown Azure cloud");
    ObjectNode longCache = (ObjectNode) config("AzureCloud", "clientSecret");
    longCache.put("cacheTtlSeconds", 301);
    assertThatThrownBy(() -> AzureKvStore.Settings.of(longCache)).hasMessage("Keep a resolved value for 0 to 300 "
        + "seconds.");

    AzureKvStore.Settings ok = AzureKvStore.Settings.of(config("AzureChinaCloud", "clientCertificate"));
    assertThat(ok.host()).isEqualTo("acme-kv.vault.azure.cn");
    assertThat(ok.cacheTtlSeconds()).isEqualTo(60);
    assertThat(ok.credentials().secretField()).isEqualTo(AzureKvStore.CERTIFICATE);
  }

  // ── Test connection ─────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void testSignsInListsOneNameAndPointsOutReferencesOfAnotherVault() {
    SecretStores.TestResult result = store.test(List.of(SecretRef.parse("azure-kv://acme-kv/billing-db"),
        SecretRef.parse("azure-kv://other-kv/orders-db#password")), call());

    assertThat(result.ok()).as(result.toString()).isTrue();
    assertThat(result.identity().path("vault").asString()).isEqualTo("acme-kv");
    assertThat(result.identity().path("identity").asString()).isEqualTo("service principal " + CLIENT);
    assertThat(result.identity().path("account").asString()).isEqualTo(CLIENT);
    assertThat(result.checks()).extracting(SecretStores.Check::detail).containsExactly(
        "Signed in as service principal " + CLIENT + " in tenant " + TENANT, "Can list the secret names of acme-kv");
    assertThat(result.hints()).extracting(SecretStores.Hint::code).containsExactly("store.other-vault");
    assertThat(result.hints().getFirst().text()).isEqualTo("azure-kv://other-kv/orders-db#password is in key vault "
        + "other-kv, which this store doesn't read. Connect other-kv as a store of its own.");
    RecordedRequest list = vaultRequests().getFirst();
    assertThat(list.getUrl().encodedPath()).isEqualTo("/" + HOST + "/secrets");
    assertThat(list.getUrl().queryParameter("maxresults")).isEqualTo("1");
    assertThat(list.getUrl().queryParameter("api-version")).isEqualTo("7.5");
    assertThat(taint).extracting(CharSequence::toString).contains(tokens.getFirst());
  }

  @Test
  void anIdentityThatMayOnlyGetSecretsPassesTheTest() {
    listAnswer.set(json(403, error("Forbidden", "ForbiddenByRbac", "Caller is not authorized.")));
    SecretStores.TestResult result = store.test(List.of(), call());
    assertThat(result.ok()).isTrue();
    assertThat(result.checks()).extracting(SecretStores.Check::detail).contains("Can't list the secret names of "
        + "acme-kv (expected when the identity may only get secrets)");

    listAnswer.set(json(403, error("Forbidden", "ForbiddenByFirewall", "Client address is not authorized.")));
    SecretStores.TestResult walled = store.test(List.of(), call());
    assertThat(walled.ok()).isFalse();
    assertThat(walled.reason()).isEqualTo("denied");
    assertThat(walled.error()).contains("firewall of key vault acme-kv");
  }

  @Test
  void theVersionIsTheLastSegmentOfTheId() {
    assertThat(AzureKvStore.version("https://" + HOST + "/secrets/billing-db/" + CURRENT)).isEqualTo(CURRENT);
    assertThat(AzureKvStore.version("https://" + HOST + "/secrets/billing-db")).isNull();
    assertThat(AzureKvStore.version("https://" + HOST + "/secrets/billing-db/" + CURRENT + "/x")).isNull();
    assertThat(AzureKvStore.version("https://" + HOST + "/keys/billing-db/" + CURRENT)).isNull();
    assertThat(AzureKvStore.version("")).isNull();
  }

  private static MockResponse json(int code, String body) {
    return new MockResponse.Builder().code(code).addHeader("Content-Type", "application/json; charset=utf-8")
        .body(body).build();
  }
}
