package ai.msrv.gaterook.connectors.cloudflare;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.TestResult;
import org.junit.jupiter.api.Test;

/** Test connection and health: the token, the account, the zones, a read per family, the token's permissions. */
class ConnectionTestTest extends CloudflareTestBase {

  private static final String TOKEN_ID = "ed17574386854bf78a67040be0a770b0";

  private void everythingReadable() {
    cf.ok("GET", "/accounts", "[{\"id\":\"" + ACCOUNT + "\",\"name\":\"Acme\"},{\"id\":\"" + OTHER_ACCOUNT
        + "\",\"name\":\"Other\"}]");
    cf.zones(ACCOUNT, ZONE, "example.com", ZONE2, "example.org");
    cf.ok("GET", "/zones/" + ZONE + "/dns_records", "[]");
    cf.ok("GET", "/accounts/" + ACCOUNT + "/workers/scripts", "[]");
    cf.ok("GET", "/accounts/" + ACCOUNT + "/storage/kv/namespaces", "[]");
    cf.ok("GET", "/accounts/" + ACCOUNT + "/pages/projects", "[]");
    cf.ok("GET", "/accounts/" + ACCOUNT + "/cfd_tunnel", "[]");
    cf.fail("GET", "/accounts/" + ACCOUNT + "/logpush/jobs", 403, 10000, "Authentication error");
    cf.on("POST", "/graphql", r -> FakeCloudflare.body(200, "{\"data\":{\"viewer\":{\"zones\":[{"
        + "\"httpRequestsAdaptiveGroups\":[]}]}}}"));
  }

  @Test
  void aUserTokenThatReadsAndCanChangeDns() {
    cf.ok("GET", "/user/tokens/verify", "{\"id\":\"" + TOKEN_ID + "\",\"status\":\"active\",\"expires_on\":"
        + "\"2026-11-20T00:00:00Z\"}");
    everythingReadable();
    cf.ok("GET", "/user/tokens/" + TOKEN_ID, "{\"id\":\"" + TOKEN_ID + "\",\"policies\":[{\"effect\":\"allow\","
        + "\"permission_groups\":[{\"name\":\"Zone Read\"},{\"name\":\"DNS Write\"},{\"name\":\"Workers Scripts "
        + "Read\"}]}]}");
    TestResult r = connector.test(cfg(Env.PROD, "\"zones\":[\"example.com\",\"missing.example\"]", null), secrets,
        TestCtx.of());
    assertThat(r.ok()).as(String.valueOf(r.error())).isTrue();
    assertThat(r.identity()).isEqualTo("user token " + TOKEN_ID + " · Acme");
    assertThat(r.serverVersion()).isEqualTo("Cloudflare API v4");
    assertThat(r.permissions().level()).isEqualTo(TestResult.Level.WRITE);
    assertThat(r.permissions().admin()).containsExactly("DNS Write");
    assertThat(r.checks()).extracting(TestResult.Check::name).containsExactly("token", "accounts", "zones", "DNS",
        "Workers", "Workers KV", "Pages", "tunnels", "Logpush", "analytics", "permissions");
    assertThat(r.checks().stream().filter(c -> c.name().equals("Logpush")).findFirst().orElseThrow().ok()).isFalse();
    assertThat(r.hints()).extracting(TestResult.Hint::code).contains("cloudflare.expires-soon",
        "cloudflare.zone-hidden", "cloudflare.families-denied", "cloudflare.can-write");
    assertThat(r.hints().stream().filter(h -> h.code().equals("cloudflare.can-write")).findFirst().orElseThrow()
        .severity()).isEqualTo(TestResult.Severity.WARNING);
    assertThat(r.metadataUpdate().path("zones")).hasSize(1);
    assertThat(r.metadataUpdate().at("/zones/0/name").asString()).isEqualTo("example.com");
    assertThat(r.metadataUpdate().path("tokenKind").asString()).isEqualTo("user");
    assertThat(r.details().path("accounts")).hasSize(2);
    assertThat(r.details().path("readable").toString()).contains("DNS", "Workers", "analytics")
        .doesNotContain("Logpush");
    assertThat(r.toString()).doesNotContain(FakeCloudflare.TOKEN);
    assertThat(cf.changes()).as("the test changes nothing").isEmpty();
  }

  @Test
  void anAccountTokenIsVerifiedThroughTheAccount() {
    cf.fail("GET", "/user/tokens/verify", 401, 1000, "Invalid API Token");
    cf.ok("GET", "/accounts/" + ACCOUNT + "/tokens/verify", "{\"id\":\"" + TOKEN_ID + "\",\"status\":\"active\"}");
    everythingReadable();
    cf.fail("GET", "/accounts/" + ACCOUNT + "/tokens/" + TOKEN_ID, 403, 9109, "Unauthorized to access requested "
        + "resource");
    TestResult r = connector.test(cfg(null), secrets, TestCtx.of());
    assertThat(r.ok()).isTrue();
    assertThat(r.identity()).startsWith("account token " + TOKEN_ID);
    assertThat(r.permissions().level()).isEqualTo(TestResult.Level.UNKNOWN);
    assertThat(r.hints()).extracting(TestResult.Hint::code).contains("cloudflare.permissions-unknown");
    assertThat(r.metadataUpdate().path("tokenKind").asString()).isEqualTo("account");
  }

  @Test
  void aTokenThatCreatesTokensIsADanger() {
    cf.ok("GET", "/user/tokens/verify", "{\"id\":\"" + TOKEN_ID + "\",\"status\":\"active\"}");
    everythingReadable();
    cf.ok("GET", "/user/tokens/" + TOKEN_ID, "{\"policies\":[{\"effect\":\"allow\",\"permission_groups\":[{\"name\":"
        + "\"API Tokens Write\"}]}]}");
    TestResult r = connector.test(cfg(null), secrets, TestCtx.of());
    assertThat(r.hints()).anySatisfy(h -> {
      assertThat(h.code()).isEqualTo("cloudflare.creates-tokens");
      assertThat(h.severity()).isEqualTo(TestResult.Severity.DANGER);
    });
  }

  @Test
  void aTokenCloudflareDoesntKnowFailsTheTest() {
    cf.fail("GET", "/user/tokens/verify", 401, 1000, "Invalid API Token");
    cf.fail("GET", "/accounts/" + ACCOUNT + "/tokens/verify", 401, 1000, "Invalid API Token");
    TestResult r = connector.test(cfg(null), secrets, TestCtx.of());
    assertThat(r.ok()).isFalse();
    assertThat(r.error().code()).isEqualTo("cloudflare.token");
    assertThat(r.error().message()).contains("doesn't accept this API token").contains("the Account ID");
    assertThat(r.checks()).singleElement().satisfies(c -> assertThat(c.ok()).isFalse());
  }

  @Test
  void aDisabledTokenFailsTheTest() {
    cf.ok("GET", "/user/tokens/verify", "{\"id\":\"" + TOKEN_ID + "\",\"status\":\"disabled\"}");
    TestResult r = connector.test(cfg(null), secrets, TestCtx.of());
    assertThat(r.ok()).isFalse();
    assertThat(r.error().message()).contains("The API token is disabled");
  }

  @Test
  void anAccountTheTokenDoesntSeeIsAWarning() {
    cf.ok("GET", "/user/tokens/verify", "{\"id\":\"" + TOKEN_ID + "\",\"status\":\"active\"}");
    cf.ok("GET", "/accounts", "[]");
    cf.zones(ACCOUNT, ZONE, "example.com");
    cf.ok("GET", "/zones/" + ZONE + "/dns_records", "[]");
    cf.on("POST", "/graphql", r -> FakeCloudflare.body(200, "{\"data\":{\"viewer\":{\"zones\":[{}]}}}"));
    TestResult r = connector.test(cfg(null), secrets, TestCtx.of());
    assertThat(r.ok()).isTrue();
    assertThat(r.hints()).extracting(TestResult.Hint::code).contains("cloudflare.account-hidden");
    assertThat(r.checks()).extracting(TestResult.Check::name).doesNotContain("Workers", "Pages");
  }

  @Test
  void healthIsTheTokenStillActive() {
    cf.ok("GET", "/user/tokens/verify", "{\"id\":\"" + TOKEN_ID + "\",\"status\":\"active\"}");
    HealthResult h = connector.health(cfg(null), secrets, TestCtx.of());
    assertThat(h.state()).isIn(HealthResult.State.HEALTHY, HealthResult.State.DEGRADED);
    assertThat(cf.requests).hasSize(1);
    TestSecrets none = new TestSecrets();
    assertThat(connector.health(cfg(null), none, TestCtx.of()).state()).isEqualTo(HealthResult.State.UNREACHABLE);
  }
}
