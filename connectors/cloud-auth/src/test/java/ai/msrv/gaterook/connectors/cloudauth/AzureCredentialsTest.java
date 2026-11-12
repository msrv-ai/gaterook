package ai.msrv.gaterook.connectors.cloudauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connectors.cloudauth.AzureCloud.Audience;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.tls.HeldCertificate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Entra ID tokens against a fake authority: the client credentials grant with a secret and with a PS256 certificate
 * assertion, the device sign-in and its refresh tokens, and the Azure CLI as a command the person allowed.
 */
class AzureCredentialsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String TENANT = "6f1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d";
  private static final String CLIENT = "0a1b2c3d-1111-4222-8333-444455556666";
  private static final CloudCredentials.Owner OWNER = new CloudCredentials.Owner("conn-az", 1, "stage-azure");
  private static final Duration BUDGET = Duration.ofSeconds(10);

  private final MockWebServer server = new MockWebServer();
  private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
  private final AtomicReference<Function<RecordedRequest, MockResponse>> token = new AtomicReference<>();
  private final AtomicReference<Function<RecordedRequest, MockResponse>> deviceCode = new AtomicReference<>();
  private CloudCredentials cloud;

  @BeforeEach
  void start() throws IOException {
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        requests.add(request);
        String path = request.getUrl().encodedPath();
        if (path.endsWith("/oauth2/v2.0/token")) {
          return token.get().apply(request);
        }
        if (path.endsWith("/oauth2/v2.0/devicecode")) {
          return deviceCode.get().apply(request);
        }
        return new MockResponse.Builder().code(404).build();
      }
    });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
    cloud = credentials(null);
  }

  @AfterEach
  void stop() throws IOException {
    cloud.close();
    server.close();
  }

  private CloudCredentials credentials(Path az) {
    return new CloudCredentials(new CloudEndpoints(null, null, null, null, null, null,
        Map.of("azure.login", server.url("/").uri()), az), Clock.systemUTC());
  }

  // ── Client secret ───────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void aClientSecretGetsOneTokenPerAudienceAndKeepsIt() throws Exception {
    AtomicInteger issued = new AtomicInteger();
    token.set(r -> json(200, "{\"token_type\":\"Bearer\",\"expires_in\":3599,\"access_token\":\""
        + jwt(Map.of("tid", TENANT, "appid", CLIENT, "idtyp", "app", "oid", "sp-oid")) + "-" + issued
        .incrementAndGet() + "\"}"));
    AzureSpec spec = spec(AzureSpec.Source.CLIENT_SECRET);
    FakeSecrets secrets = new FakeSecrets().with("/credentials/clientSecret", "s3cr3t~value");

    AzureCredentials.AccessToken arm = cloud.azure().token(OWNER, spec, Audience.ARM, secrets, BUDGET);
    AzureCredentials.AccessToken again = cloud.azure().token(OWNER, spec, Audience.ARM, secrets, BUDGET);
    AzureCredentials.AccessToken logs = cloud.azure().token(OWNER, spec, Audience.LOG_ANALYTICS, secrets, BUDGET);

    assertThat(again.value()).isEqualTo(arm.value());
    assertThat(logs.value()).isNotEqualTo(arm.value());
    assertThat(arm.tenantId()).isEqualTo(TENANT);
    assertThat(arm.application()).isTrue();
    assertThat(arm.account()).isEqualTo(CLIENT);
    assertThat(arm.toString()).doesNotContain(arm.value());
    assertThat(requests).hasSize(2);
    Map<String, String> form = form(requests.getFirst());
    assertThat(requests.getFirst().getUrl().encodedPath()).isEqualTo("/" + TENANT + "/oauth2/v2.0/token");
    assertThat(form).containsEntry("grant_type", "client_credentials").containsEntry("client_id", CLIENT)
        .containsEntry("client_secret", "s3cr3t~value")
        .containsEntry("scope", "https://management.azure.com/.default");
    assertThat(form(requests.get(1))).containsEntry("scope", "https://api.loganalytics.io/.default");
    assertThat(cloud.issued()).extracting(IssuedCredential::mechanism)
        .contains("Azure service principal · Azure Resource Manager", "Azure service principal · Log Analytics");
  }

  @Test
  void aSearchTokenAsksForTheSearchAudienceOfItsCloud() throws Exception {
    token.set(r -> json(200, "{\"expires_in\":3600,\"access_token\":\"" + jwt(Map.of("tid", TENANT, "appid", CLIENT,
        "idtyp", "app")) + "\"}"));
    FakeSecrets secrets = new FakeSecrets().with("/credentials/clientSecret", "x");
    AzureSpec gov = new AzureSpec(AzureCloud.AZURE_US_GOVERNMENT, AzureSpec.Source.CLIENT_SECRET, TENANT, CLIENT,
        "/credentials/clientSecret", null);
    AzureSpec china = new AzureSpec(AzureCloud.AZURE_CHINA, AzureSpec.Source.CLIENT_SECRET, TENANT, CLIENT,
        "/credentials/clientSecret", null);

    cloud.azure().token(OWNER, spec(AzureSpec.Source.CLIENT_SECRET), Audience.SEARCH, secrets, BUDGET);
    cloud.azure().token(OWNER, gov, Audience.SEARCH, secrets, BUDGET);
    cloud.azure().token(OWNER, china, Audience.SEARCH, secrets, BUDGET);

    assertThat(requests).extracting(r -> form(r).get("scope")).containsExactly("https://search.azure.com/.default",
        "https://search.azure.us/.default", "https://search.azure.cn/.default");
    assertThat(cloud.issued()).extracting(IssuedCredential::mechanism)
        .contains("Azure service principal · Azure AI Search");
  }

  @Test
  void aTokenCloseToItsExpiryIsReplaced() throws Exception {
    AtomicInteger issued = new AtomicInteger();
    token.set(r -> json(200, "{\"expires_in\":120,\"access_token\":\"" + jwt(Map.of("tid", TENANT)) + "-"
        + issued.incrementAndGet() + "\"}"));
    AzureSpec spec = spec(AzureSpec.Source.CLIENT_SECRET);
    FakeSecrets secrets = new FakeSecrets().with("/credentials/clientSecret", "x");

    String first = cloud.azure().token(OWNER, spec, Audience.ARM, secrets, BUDGET).value();
    String second = cloud.azure().token(OWNER, spec, Audience.ARM, secrets, BUDGET).value();

    assertThat(second).isNotEqualTo(first);
    assertThat(issued.get()).isEqualTo(2);
  }

  @Test
  void entraErrorsSayWhatToDo() {
    token.set(r -> json(401, "{\"error\":\"invalid_client\",\"error_description\":\"AADSTS7000215: Invalid client "
        + "secret provided. Trace ID: 1\\r\\nCorrelation ID: 2\",\"error_codes\":[7000215]}"));
    AzureSpec spec = spec(AzureSpec.Source.CLIENT_SECRET);
    FakeSecrets secrets = new FakeSecrets().with("/credentials/clientSecret", "cnry-7f3a-secret");

    assertThatThrownBy(() -> cloud.azure().token(OWNER, spec, Audience.ARM, secrets, BUDGET))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_UNAVAILABLE);
          assertThat(e.getMessage()).contains("refused the client secret").contains("Replace it under Credentials")
              .doesNotContain("cnry-7f3a-secret");
        });
  }

  // ── Certificate ─────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void aCertificateSignsAPs256AssertionForTheTokenEndpoint() throws Exception {
    HeldCertificate held = new HeldCertificate.Builder().rsa2048().commonName("gaterook-stage").build();
    String pem = held.privateKeyPkcs8Pem() + held.certificatePem();
    token.set(r -> json(200, "{\"expires_in\":3600,\"access_token\":\"" + jwt(Map.of("tid", TENANT, "appid", CLIENT,
        "idtyp", "app")) + "\"}"));
    AzureSpec spec = spec(AzureSpec.Source.CLIENT_CERTIFICATE);

    cloud.azure().token(OWNER, spec, Audience.ARM, new FakeSecrets().with("/credentials/certificate", pem), BUDGET);

    Map<String, String> form = form(requests.getFirst());
    assertThat(form).containsEntry("client_assertion_type",
        "urn:ietf:params:oauth:client-assertion-type:jwt-bearer").doesNotContainKey("client_secret");
    String[] jwt = form.get("client_assertion").split("\\.");
    JsonNode header = JSON.readTree(Base64.getUrlDecoder().decode(jwt[0]));
    JsonNode claims = JSON.readTree(Base64.getUrlDecoder().decode(jwt[1]));
    assertThat(header.path("alg").asString()).isEqualTo("PS256");
    assertThat(header.path("x5t#S256").asString()).isEqualTo(Base64.getUrlEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(held.certificate().getEncoded())));
    assertThat(claims.path("aud").asString()).isEqualTo(server.url("/" + TENANT + "/oauth2/v2.0/token").toString());
    assertThat(claims.path("iss").asString()).isEqualTo(CLIENT);
    assertThat(claims.path("sub").asString()).isEqualTo(CLIENT);
    assertThat(claims.path("exp").asLong() - claims.path("iat").asLong()).isEqualTo(600);
    Signature verify = Signature.getInstance("RSASSA-PSS");
    verify.setParameter(new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1));
    verify.initVerify(held.certificate().getPublicKey());
    verify.update((jwt[0] + "." + jwt[1]).getBytes(StandardCharsets.US_ASCII));
    assertThat(verify.verify(Base64.getUrlDecoder().decode(jwt[2]))).isTrue();
  }

  @Test
  void aCertificateOfAnotherKeyIsRefusedBeforeAnythingIsSent() {
    HeldCertificate one = new HeldCertificate.Builder().rsa2048().build();
    HeldCertificate other = new HeldCertificate.Builder().rsa2048().build();
    AzureSpec spec = spec(AzureSpec.Source.CLIENT_CERTIFICATE);
    FakeSecrets secrets = new FakeSecrets().with("/credentials/certificate", one.privateKeyPkcs8Pem()
        + other.certificatePem());

    assertThatThrownBy(() -> cloud.azure().token(OWNER, spec, Audience.ARM, secrets, BUDGET))
        .hasMessageContaining("doesn't belong to its private key");
    assertThat(requests).isEmpty();
  }

  @Test
  void anEcCertificateIsRefused() {
    HeldCertificate ec = new HeldCertificate.Builder().ecdsa256().build();
    AzureSpec spec = spec(AzureSpec.Source.CLIENT_CERTIFICATE);
    FakeSecrets secrets = new FakeSecrets().with("/credentials/certificate", ec.privateKeyPkcs8Pem()
        + ec.certificatePem());

    assertThatThrownBy(() -> cloud.azure().token(OWNER, spec, Audience.ARM, secrets, BUDGET))
        .hasMessageContaining("must be RSA");
  }

  // ── Device sign-in ──────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void aDeviceSignInShowsACodeThenKeepsTheRefreshTokenForEveryAudience() throws Exception {
    deviceCode.set(r -> json(200, "{\"device_code\":\"dev-code-1\",\"user_code\":\"ABCD-EFGH\","
        + "\"verification_uri\":\"https://microsoft.com/devicelogin\",\"expires_in\":900,\"interval\":5}"));
    AtomicInteger polls = new AtomicInteger();
    token.set(r -> {
      Map<String, String> f = form(r);
      if (f.get("grant_type").equals("urn:ietf:params:oauth:grant-type:device_code")) {
        if (polls.incrementAndGet() == 1) {
          return json(400, "{\"error\":\"authorization_pending\",\"error_description\":\"AADSTS70016: pending\"}");
        }
        return json(200, "{\"access_token\":\"" + jwt(Map.of("tid", TENANT, "upn", "dev@contoso.example")) + "\","
            + "\"refresh_token\":\"rt-1\",\"expires_in\":3600,\"id_token\":\""
            + jwt(Map.of("preferred_username", "dev@contoso.example")) + "\"}");
      }
      assertThat(f.get("grant_type")).isEqualTo("refresh_token");
      String next = f.get("refresh_token").equals("rt-1") ? "rt-2" : "rt-3";
      return json(200, "{\"access_token\":\"" + jwt(Map.of("tid", TENANT, "upn", "dev@contoso.example",
          "aud", f.get("scope"))) + "\",\"refresh_token\":\"" + next + "\",\"expires_in\":3600}");
    });
    AzureSpec spec = spec(AzureSpec.Source.DEVICE_CODE);

    assertThatThrownBy(() -> cloud.azure().token(OWNER, spec, Audience.ARM, new FakeSecrets(), BUDGET))
        .hasMessageContaining("Test connection to sign in with a code");
    AzureCredentials.DeviceSignIn first = cloud.azure().deviceSignIn(spec, BUDGET);
    assertThat(first).isInstanceOfSatisfying(AzureCredentials.DeviceSignIn.Pending.class, p -> {
      assertThat(p.userCode()).isEqualTo("ABCD-EFGH");
      assertThat(p.verificationUri()).hasToString("https://microsoft.com/devicelogin");
    });
    Map<String, String> start = form(requests.getFirst());
    assertThat(start).containsEntry("client_id", AzureSpec.AZURE_CLI_CLIENT_ID)
        .containsEntry("scope", "https://management.azure.com/.default offline_access openid profile");
    assertThat(cloud.azure().deviceSignIn(spec, BUDGET)).isInstanceOf(AzureCredentials.DeviceSignIn.Pending.class);
    assertThat(cloud.azure().deviceSignIn(spec, BUDGET)).isEqualTo(
        new AzureCredentials.DeviceSignIn.SignedIn("dev@contoso.example", TENANT));

    AzureCredentials.AccessToken arm = cloud.azure().token(OWNER, spec, Audience.ARM, new FakeSecrets(), BUDGET);
    AzureCredentials.AccessToken logs = cloud.azure().token(OWNER, spec, Audience.LOG_ANALYTICS, new FakeSecrets(),
        BUDGET);

    assertThat(arm.application()).isFalse();
    assertThat(arm.account()).isEqualTo("dev@contoso.example");
    List<Map<String, String>> refreshes = requests.stream().map(AzureCredentialsTest::form)
        .filter(f -> "refresh_token".equals(f.get("grant_type"))).toList();
    assertThat(refreshes).extracting(f -> f.get("refresh_token")).containsExactly("rt-1", "rt-2");
    assertThat(refreshes).extracting(f -> f.get("scope")).containsExactly(
        "https://management.azure.com/.default offline_access", "https://api.loganalytics.io/.default offline_access");
    assertThat(logs.value()).isNotEqualTo(arm.value());
    // Another connection of the same tenant and client uses the same sign-in.
    cloud.azure().token(new CloudCredentials.Owner("conn-other", 1, "other"), spec, Audience.ARM, new FakeSecrets(),
        BUDGET);
  }

  @Test
  void anExpiredRefreshTokenEndsTheSignIn() throws Exception {
    deviceCode.set(r -> json(200, "{\"device_code\":\"d\",\"user_code\":\"CODE\",\"verification_uri\":"
        + "\"https://microsoft.com/devicelogin\",\"expires_in\":900}"));
    token.set(r -> form(r).get("grant_type").startsWith("urn:")
        ? json(200, "{\"access_token\":\"" + jwt(Map.of("tid", TENANT)) + "\",\"refresh_token\":\"rt\","
            + "\"expires_in\":3600}")
        : json(400, "{\"error\":\"invalid_grant\",\"error_description\":\"AADSTS700082: The refresh token has "
            + "expired due to inactivity.\"}"));
    AzureSpec spec = spec(AzureSpec.Source.DEVICE_CODE);
    cloud.azure().deviceSignIn(spec, BUDGET);
    assertThat(cloud.azure().deviceSignIn(spec, BUDGET)).isInstanceOf(AzureCredentials.DeviceSignIn.SignedIn.class);

    assertThatThrownBy(() -> cloud.azure().token(OWNER, spec, Audience.ARM, new FakeSecrets(), BUDGET))
        .hasMessageContaining("has expired");
    assertThat(cloud.azure().deviceSignIn(spec, BUDGET)).isInstanceOf(AzureCredentials.DeviceSignIn.Pending.class);
  }

  // ── Azure CLI ───────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void theCliRunsOnlyTheCommandThePersonAllowed() throws Exception {
    Path dir = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "az-");
    Path args = dir.resolve("args.txt");
    Path env = dir.resolve("env.txt");
    long expires = Instant.now().plusSeconds(3000).getEpochSecond();
    Path az = script(dir, "#!/bin/sh\nprintf '%s\\n' \"$@\" > '" + args + "'\nenv > '" + env + "'\n"
        + "printf '{\"accessToken\":\"" + jwt(Map.of("tid", TENANT, "upn", "me@contoso.example")) + "\","
        + "\"expiresOn\":\"2099-01-01 00:00:00.000000\",\"expires_on\":" + expires + ",\"subscription\":\"s\","
        + "\"tenant\":\"" + TENANT + "\",\"tokenType\":\"Bearer\"}'\n");
    try (CloudCredentials local = credentials(az)) {
      AzureSpec untrusted = new AzureSpec(AzureCloud.AZURE_PUBLIC, AzureSpec.Source.CLI, TENANT, null, null, null);
      AzureCredentials.CliCommand command = local.azure().cliCommand(untrusted);

      assertThatThrownBy(() -> local.azure().token(OWNER, untrusted, Audience.ARM, new FakeSecrets(), BUDGET))
          .isInstanceOfSatisfying(ConnectorException.class, e ->
              assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
      assertThat(args).doesNotExist();
      assertThat(command.commandLine()).isEqualTo(az.toAbsolutePath().normalize() + " account get-access-token --output json "
          + "--resource <resource> --tenant " + TENANT);
      assertThat(command.environment()).contains("HOME", "PATH").doesNotContain("GATEROOK_TEST_SECRET");

      AzureSpec trusted = untrusted.withTrustedCli(command.fingerprint());
      AzureCredentials.AccessToken t = local.azure().token(OWNER, trusted, Audience.ARM, new FakeSecrets(), BUDGET);

      assertThat(t.tenantId()).isEqualTo(TENANT);
      assertThat(t.account()).isEqualTo("me@contoso.example");
      assertThat(t.expiresAt()).isEqualTo(Instant.ofEpochSecond(expires));
      assertThat(Files.readAllLines(args)).containsExactly("account", "get-access-token", "--output", "json",
          "--resource", "https://management.azure.com/", "--tenant", TENANT);
      assertThat(Files.readString(env)).doesNotContain("JAVA_HOME=").contains("HOME=");

      AzureSpec otherTenant = new AzureSpec(AzureCloud.AZURE_PUBLIC, AzureSpec.Source.CLI,
          "11111111-2222-4333-8444-555555555555", null, null, command.fingerprint());
      assertThatThrownBy(() -> local.azure().token(OWNER, otherTenant, Audience.ARM, new FakeSecrets(), BUDGET))
          .hasMessageContaining("command changed");
    }
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void aCliWithoutSignInSaysToRunAzLogin() throws Exception {
    Path dir = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "az-");
    Path az = script(dir, "#!/bin/sh\necho \"ERROR: Please run 'az login' to setup account.\" >&2\nexit 1\n");
    try (CloudCredentials local = credentials(az)) {
      AzureSpec spec = new AzureSpec(AzureCloud.AZURE_PUBLIC, AzureSpec.Source.CLI, null, null, null, null);
      AzureSpec trusted = spec.withTrustedCli(local.azure().cliCommand(spec).fingerprint());

      assertThatThrownBy(() -> local.azure().token(OWNER, trusted, Audience.ARM, new FakeSecrets(), BUDGET))
          .hasMessageContaining("Run az login in a terminal");
    }
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void anOlderCliTellsTheExpiryInLocalTime() throws Exception {
    Path dir = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "az-");
    String local = java.time.LocalDateTime.now().plusMinutes(40).format(
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS"));
    Path az = script(dir, "#!/bin/sh\nprintf '{\"accessToken\":\"opaque-token\",\"expiresOn\":\"" + local
        + "\",\"tenant\":\"" + TENANT + "\"}'\n");
    try (CloudCredentials creds = credentials(az)) {
      AzureSpec spec = new AzureSpec(AzureCloud.AZURE_PUBLIC, AzureSpec.Source.CLI, null, null, null, null);
      AzureSpec trusted = spec.withTrustedCli(creds.azure().cliCommand(spec).fingerprint());

      AzureCredentials.AccessToken t = creds.azure().token(OWNER, trusted, Audience.ARM, new FakeSecrets(), BUDGET);

      assertThat(t.tenantId()).as("from the CLI's own answer").isEqualTo(TENANT);
      assertThat(Duration.between(Instant.now(), t.expiresAt())).isBetween(Duration.ofMinutes(38),
          Duration.ofMinutes(41));
    }
  }

  // ── Where tokens go ─────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void tokensGoOnlyToTheirAudienceInTheirCloud() {
    AzureCloud pub = AzureCloud.AZURE_PUBLIC;
    assertThat(pub.accepts(Audience.ARM, URI.create("https://management.azure.com/subscriptions"))).isTrue();
    assertThat(pub.accepts(Audience.ARM, URI.create("https://management.azure.com:443/x"))).isTrue();
    assertThat(pub.accepts(Audience.ARM, URI.create("http://management.azure.com/x"))).isFalse();
    assertThat(pub.accepts(Audience.ARM, URI.create("https://management.azure.com:8443/x"))).isFalse();
    assertThat(pub.accepts(Audience.ARM, URI.create("https://u@management.azure.com/x"))).isFalse();
    assertThat(pub.accepts(Audience.ARM, URI.create("https://management.azure.com.evil.example/x"))).isFalse();
    assertThat(pub.accepts(Audience.ARM, URI.create("https://api.loganalytics.io/v1"))).isFalse();
    assertThat(pub.accepts(Audience.LOG_ANALYTICS, URI.create("https://api.loganalytics.io/v1/workspaces"))).isTrue();
    assertThat(pub.accepts(Audience.KEY_VAULT, URI.create("https://kv-stage.vault.azure.net/secrets"))).isTrue();
    assertThat(pub.accepts(Audience.KEY_VAULT, URI.create("https://a.b.vault.azure.net/"))).isFalse();
    assertThat(pub.accepts(Audience.STORAGE, URI.create("https://acmestage.blob.core.windows.net/c"))).isTrue();
    assertThat(pub.accepts(Audience.STORAGE, URI.create("https://acme.web.core.windows.net/"))).isFalse();
    assertThat(pub.accepts(Audience.STORAGE, URI.create("https://acme.azurewebsites.net/"))).isFalse();
    AzureCloud gov = AzureCloud.AZURE_US_GOVERNMENT;
    assertThat(gov.accepts(Audience.ARM, URI.create("https://management.usgovcloudapi.net/x"))).isTrue();
    assertThat(gov.accepts(Audience.ARM, URI.create("https://management.azure.com/x"))).isFalse();
    assertThat(gov.authority()).hasToString("https://login.microsoftonline.us");
    assertThat(gov.scope(Audience.LOG_ANALYTICS)).isEqualTo("https://api.loganalytics.us/.default");
    AzureCloud china = AzureCloud.of("AzureChinaCloud");
    assertThat(china.authority()).hasToString("https://login.chinacloudapi.cn");
    assertThat(china.scope(Audience.ARM)).isEqualTo("https://management.chinacloudapi.cn/.default");
    assertThat(china.accepts(Audience.STORAGE, URI.create("https://acme.blob.core.chinacloudapi.cn/"))).isTrue();
    assertThat(AzureCloud.of(null)).isEqualTo(AzureCloud.AZURE_PUBLIC);
    assertThatThrownBy(() -> AzureCloud.of("AzureGermanCloud")).hasMessageContaining("Unknown Azure cloud");
  }

  @Test
  void searchTokensGoOnlyToSearchServicesOfTheirCloud() {
    AzureCloud pub = AzureCloud.AZURE_PUBLIC;
    assertThat(pub.accepts(Audience.SEARCH, URI.create("https://contoso.search.windows.net/indexes"))).isTrue();
    assertThat(pub.accepts(Audience.SEARCH, URI.create("https://search.windows.net/indexes"))).isFalse();
    assertThat(pub.accepts(Audience.SEARCH, URI.create("https://a.b.search.windows.net/"))).isFalse();
    assertThat(pub.accepts(Audience.SEARCH, URI.create("https://contoso.search.windows.net.evil.example/"))).isFalse();
    assertThat(pub.accepts(Audience.SEARCH, URI.create("http://contoso.search.windows.net/"))).isFalse();
    assertThat(pub.accepts(Audience.SEARCH, URI.create("https://contoso.search.azure.us/"))).isFalse();
    assertThat(pub.accepts(Audience.ARM, URI.create("https://contoso.search.windows.net/"))).isFalse();
    assertThat(pub.scope(Audience.SEARCH)).isEqualTo("https://search.azure.com/.default");
    AzureCloud gov = AzureCloud.AZURE_US_GOVERNMENT;
    assertThat(gov.accepts(Audience.SEARCH, URI.create("https://contoso.search.azure.us/indexes"))).isTrue();
    assertThat(gov.accepts(Audience.SEARCH, URI.create("https://contoso.search.windows.net/"))).isFalse();
    assertThat(gov.scope(Audience.SEARCH)).isEqualTo("https://search.azure.us/.default");
    AzureCloud china = AzureCloud.AZURE_CHINA;
    assertThat(china.accepts(Audience.SEARCH, URI.create("https://contoso.search.azure.cn/indexes"))).isTrue();
    assertThat(china.scope(Audience.SEARCH)).isEqualTo("https://search.azure.cn/.default");

    assertThat(AzureCloud.ofSearchHost("Contoso.Search.Windows.Net")).isEqualTo(pub);
    assertThat(AzureCloud.ofSearchHost("contoso.search.azure.us")).isEqualTo(gov);
    assertThat(AzureCloud.ofSearchHost("contoso.search.azure.cn")).isEqualTo(china);
    assertThat(AzureCloud.ofSearchHost("search.windows.net")).isNull();
    assertThat(AzureCloud.ofSearchHost("contoso.search.example.com")).isNull();
    assertThat(AzureCloud.ofSearchHost(null)).isNull();
  }

  @Test
  void aTestAuthoritysTokensGoOnlyToTestApis() {
    try (CloudCredentials test = new CloudCredentials(new CloudEndpoints(null, null, null, null, null, null,
        Map.of("azure.login", URI.create("http://127.0.0.1:1/"), "azure.arm", URI.create("http://127.0.0.1:2/"),
            "azure.search", URI.create("http://127.0.0.1:4/"))), Clock.systemUTC());
        CloudCredentials real = new CloudCredentials(CloudEndpoints.PRODUCTION, Clock.systemUTC())) {
      AzureCloud c = AzureCloud.AZURE_PUBLIC;
      assertThat(test.azure().sendsTokensTo(c, Audience.SEARCH, URI.create("http://127.0.0.1:4/indexes"))).isTrue();
      assertThat(test.azure().sendsTokensTo(c, Audience.SEARCH, URI.create("https://contoso.search.windows.net/")))
          .isFalse();
      assertThat(real.azure().sendsTokensTo(c, Audience.SEARCH, URI.create("https://contoso.search.windows.net/")))
          .isTrue();
      assertThat(real.azure().sendsTokensTo(c, Audience.SEARCH, URI.create("http://127.0.0.1:4/indexes"))).isFalse();
      assertThat(test.azure().sendsTokensTo(c, Audience.ARM, URI.create("http://127.0.0.1:2/subscriptions"))).isTrue();
      assertThat(test.azure().sendsTokensTo(c, Audience.ARM, URI.create("https://management.azure.com/x"))).isFalse();
      assertThat(test.azure().sendsTokensTo(c, Audience.ARM, URI.create("http://127.0.0.1:3/x"))).isFalse();
      assertThat(real.azure().sendsTokensTo(c, Audience.ARM, URI.create("https://management.azure.com/x"))).isTrue();
      assertThat(real.azure().sendsTokensTo(c, Audience.ARM, URI.create("http://127.0.0.1:2/x"))).isFalse();
      assertThat(test.azure().base(c, Audience.ARM)).hasToString("http://127.0.0.1:2/");
      assertThat(real.azure().base(c, Audience.LOG_ANALYTICS)).hasToString("https://api.loganalytics.io");
    }
  }

  @Test
  void specsAreChecked() {
    ObjectNode config = JSON.createObjectNode();
    config.put("tenantId", "Contoso.OnMicrosoft.com");
    config.putObject("credentials").put("source", "deviceCode");
    AzureSpec device = AzureSpec.fromConfig(config, "/credentials");
    assertThat(device.tenantId()).isEqualTo("contoso.onmicrosoft.com");
    assertThat(device.publicClientId()).isEqualTo(AzureSpec.AZURE_CLI_CLIENT_ID);
    assertThat(device.tenantIsGuid()).isFalse();

    ObjectNode sp = JSON.createObjectNode().put("tenantId", TENANT).put("cloud", "AzureUSGovernment");
    sp.putObject("credentials").put("source", "clientCertificate").put("clientId", CLIENT);
    AzureSpec certificate = AzureSpec.fromConfig(sp, "/credentials");
    assertThat(certificate.secretField()).isEqualTo("/credentials/certificate");
    assertThat(certificate.cloud()).isEqualTo(AzureCloud.AZURE_US_GOVERNMENT);

    assertThatThrownBy(() -> new AzureSpec(AzureCloud.AZURE_PUBLIC, AzureSpec.Source.CLIENT_SECRET, TENANT, null,
        "/credentials/clientSecret", null)).hasMessageContaining("client) ID");
    assertThatThrownBy(() -> new AzureSpec(AzureCloud.AZURE_PUBLIC, AzureSpec.Source.DEVICE_CODE, null, null, null,
        null)).hasMessageContaining("tenant");
    assertThatThrownBy(() -> new AzureSpec(AzureCloud.AZURE_PUBLIC, AzureSpec.Source.DEVICE_CODE, "organizations",
        null, null, null)).hasMessageContaining("Name one tenant");
    assertThatThrownBy(() -> new AzureSpec(AzureCloud.AZURE_PUBLIC, AzureSpec.Source.CLI, "not a tenant!", null,
        null, null)).hasMessageContaining("directory ID");
    assertThatThrownBy(() -> new AzureSpec(AzureCloud.AZURE_PUBLIC, AzureSpec.Source.CLI, null, null, null,
        "abc")).hasMessageContaining("fingerprint");
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

  private static AzureSpec spec(AzureSpec.Source source) {
    return new AzureSpec(AzureCloud.AZURE_PUBLIC, source, TENANT, source == AzureSpec.Source.DEVICE_CODE ? null
        : CLIENT, switch (source) {
          case CLIENT_SECRET -> "/credentials/clientSecret";
          case CLIENT_CERTIFICATE -> "/credentials/certificate";
          default -> null;
        }, null);
  }

  private static Path script(Path dir, String text) throws IOException {
    Path az = dir.resolve("az");
    Files.writeString(az, text);
    Files.setPosixFilePermissions(az, PosixFilePermissions.fromString("rwx------"));
    return az;
  }

  /** An unsigned JWT with {@code claims}: Entra ID's tokens are read, never checked, by the gateway. */
  static String jwt(Map<String, String> claims) {
    ObjectNode c = JSON.createObjectNode();
    claims.forEach(c::put);
    c.put("exp", Instant.now().plusSeconds(3600).getEpochSecond());
    Base64.Encoder e = Base64.getUrlEncoder().withoutPadding();
    return e.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8)) + "."
        + e.encodeToString(JSON.writeValueAsBytes(c)) + ".c2lnbmF0dXJl";
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

  private static MockResponse json(int code, String body) {
    return new MockResponse.Builder().code(code).addHeader("Content-Type", "application/json; charset=utf-8")
        .body(body).build();
  }

  @SuppressWarnings("unused")
  private static String utc(Instant i) {
    return i.atOffset(ZoneOffset.UTC).toString();
  }
}
