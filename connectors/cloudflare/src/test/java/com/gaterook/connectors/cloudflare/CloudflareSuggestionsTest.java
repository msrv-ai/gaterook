package com.gaterook.connectors.cloudflare;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.Suggestions;
import com.gaterook.connector.api.Suggestions.Option;
import com.gaterook.connectors.support.SchemaResources;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.util.Map;
import java.util.Set;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Help for the Cloudflare form: an account ID in what was pasted or in {@code CLOUDFLARE_ACCOUNT_ID}, what a typed
 * token is not, and once the draft holds a token, the accounts it can see (Account ID may still be empty: it is marked
 * {@code x-suggest}) and the account's zones.
 */
class CloudflareSuggestionsTest extends CloudflareTestBase {

  private static ConnectionConfig draft(String json) {
    return new ConnectionConfig("draft-1", "draft", "cloudflare", Env.DEV, 1, json(json),
        Set.of(CloudflareSettings.TOKEN), null);
  }

  private Suggestions service(String json) {
    return connector.suggest(draft(json), secrets, TestCtx.of(), Suggestions.Scope.SERVICE);
  }

  private void oneAccount() {
    cf.ok("GET", "/accounts", "[{\"id\":\"" + ACCOUNT + "\",\"name\":\"Acme\"}]");
  }

  // ── This computer ───────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void anAccountIdInAPastedDashboardLinkIsOffered() {
    Suggestions s = connector.suggest(draft("{\"accountId\":\"https://dash.cloudflare.com/" + ACCOUNT.toUpperCase()
        + "/example.com/dns\",\"credentials\":{}}"), new TestSecrets(), TestCtx.of(), Suggestions.Scope.LOCAL);

    Suggestions.Field account = s.fields().get("/accountId");
    assertThat(account.options()).extracting(Option::value).containsExactly(ACCOUNT);
    assertThat(account.source()).isEqualTo("what you pasted");
    assertThat(account.value()).isNull();
    assertThat(cf.requests).isEmpty();
  }

  @Test
  void cloudflareAccountIdFillsInAnEmptyAccountId() {
    try (CloudflareConnector withEnv = new CloudflareConnector(cf.base(), TestCtx.CLOCK,
        Map.of("CLOUDFLARE_ACCOUNT_ID", ACCOUNT)::get)) {
      Suggestions s = withEnv.suggest(draft("{\"credentials\":{}}"), new TestSecrets(), TestCtx.of(),
          Suggestions.Scope.LOCAL);

      assertThat(s.fields().get("/accountId").value()).isEqualTo(ACCOUNT);
      assertThat(s.fields().get("/accountId").source()).isEqualTo("CLOUDFLARE_ACCOUNT_ID");
    }
  }

  @Test
  void aTypedTokenThatIsntOneIsANote() {
    Suggestions bearer = connector.suggest(draft("{\"credentials\":{}}"), new TestSecrets()
        .with(CloudflareSettings.TOKEN, "Bearer " + FakeCloudflare.TOKEN), TestCtx.of(), Suggestions.Scope.LOCAL);
    Suggestions global = connector.suggest(draft("{\"credentials\":{}}"), new TestSecrets()
        .with(CloudflareSettings.TOKEN, "1234567893feefc5f0a5000bf00c38d90bbeb"), TestCtx.of(),
        Suggestions.Scope.LOCAL);
    Suggestions fine = connector.suggest(draft("{\"credentials\":{}}"), secrets, TestCtx.of(),
        Suggestions.Scope.LOCAL);

    assertThat(bearer.notes()).containsExactly("Paste the API token without the word Bearer.");
    assertThat(global.notes()).containsExactly("This looks like the Global API Key, which GateRook doesn't accept: "
        + "create an API token under My Profile → API Tokens.");
    assertThat(fine.isEmpty()).isTrue();
    assertThat(bearer.toString()).doesNotContain(FakeCloudflare.TOKEN);
    assertThat(cf.requests).isEmpty();
  }

  // ── Signed in ───────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void withAccountIdStillEmptyTheTokensOnlyAccountIsFilledInAndItsZonesOffered() {
    oneAccount();
    cf.zones(ACCOUNT, ZONE, "example.com", ZONE2, "example.org");

    Suggestions s = service("{\"zones\":[\"example.com\",\"gone.example\"],\"credentials\":{}}");

    Suggestions.Field account = s.fields().get("/accountId");
    assertThat(account.value()).isEqualTo(ACCOUNT);
    assertThat(account.options()).singleElement().satisfies(o -> assertThat(o.label()).isEqualTo("Acme"));
    assertThat(account.source()).isEqualTo(CloudflareSuggestions.TOKEN);
    assertThat(s.fields().get("/zones").options()).extracting(Option::value)
        .containsExactly("example.com", "example.org");
    assertThat(s.notes()).containsExactly(
        "The API token reads Acme (" + ACCOUNT + "): 2 zones. Choose the zones agents may use; empty means all of them.",
        "The API token doesn't see zone gone.example in this account: calls for it will fail.");
    RecordedRequest zones = cf.sent("GET", "/zones").getFirst();
    assertThat(zones.getUrl().queryParameter("account.id")).isEqualTo(ACCOUNT);
    assertThat(cf.requests).extracting(RecordedRequest::getMethod).containsOnly("GET");
  }

  @Test
  void aHalfTypedAccountIdIsNoAccountYet() {
    oneAccount();
    cf.zones(ACCOUNT, ZONE, "example.com");

    Suggestions s = service("{\"accountId\":\"0123456789\",\"credentials\":{}}");

    assertThat(s.fields().get("/accountId").value()).isEqualTo(ACCOUNT);
    assertThat(s.fields().get("/zones").options()).extracting(Option::value).containsExactly("example.com");
  }

  @Test
  void anAccountIdTheTokenDoesntSeeIsANote() {
    oneAccount();
    cf.zones(OTHER_ACCOUNT, ZONE2, "example.org");

    Suggestions s = service("{\"accountId\":\"" + OTHER_ACCOUNT + "\",\"credentials\":{}}");

    assertThat(s.notes()).first().isEqualTo("The API token doesn't see account " + OTHER_ACCOUNT + ": it sees Acme ("
        + ACCOUNT + "). Check the Account ID.");
    // Zones of an account can be visible without its account permissions: they are listed anyway.
    assertThat(s.fields().get("/zones").options()).extracting(Option::value).containsExactly("example.org");
  }

  @Test
  void severalAccountsWaitForTheChoice() {
    cf.ok("GET", "/accounts", "[{\"id\":\"" + ACCOUNT + "\",\"name\":\"Acme\"},{\"id\":\"" + OTHER_ACCOUNT
        + "\",\"name\":\"Other\"}]");

    Suggestions s = service("{\"credentials\":{}}");

    assertThat(s.fields().get("/accountId").value()).isNull();
    assertThat(s.fields().get("/accountId").options()).extracting(Option::value)
        .containsExactly(ACCOUNT, OTHER_ACCOUNT);
    assertThat(s.notes()).containsExactly("The API token sees 2 accounts: choose the one agents may use.");
    assertThat(cf.sent("GET", "/zones")).isEmpty();
  }

  @Test
  void aMissingPermissionIsANote() {
    oneAccount();
    cf.fail("GET", "/zones", 403, 10000, "Authentication error");

    Suggestions s = service("{\"accountId\":\"" + ACCOUNT + "\",\"credentials\":{}}");

    assertThat(s.notes()).containsExactly("The API token can't list zones: give it Zone · Zone · Read.");
    assertThat(s.fields()).containsOnlyKeys("/accountId");
  }

  @Test
  void aTokenCloudflareRefusesIsANote() {
    cf.fail("GET", "/accounts", 401, 1000, "Invalid API Token");

    Suggestions s = service("{\"credentials\":{}}");

    assertThat(s.fields()).isEmpty();
    assertThat(s.notes()).singleElement().asString().startsWith("Cloudflare refused the API token (401)");
  }

  // ── The form ────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void accountIdStaysRequiredAndIsListedBeforeItIsFilledIn() throws Exception {
    JsonNode schema = SchemaResources.of(CloudflareConnector.class, "cloudflare").load("config");
    var validator = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(schema);

    assertThat(schema.path("required").toString()).contains("\"accountId\"");
    assertThat(schema.at("/properties/accountId/x-suggest").asBoolean(false)).isTrue();
    assertThat(validator.validate(json("{\"credentials\":{\"token\":\"" + FakeCloudflare.TOKEN + "\"}}")))
        .isNotEmpty();
    // A connection's calls still need it: the suggestions' leniency stops at the form.
    assertThatThrownBy(() -> CloudflareSettings.parse(draft("{\"credentials\":{}}")))
        .isInstanceOf(CloudflareSettings.InvalidSettingsException.class);
    assertThat(CloudflareSuggestions.draft(draft("{\"accountId\":\" " + ACCOUNT + " \",\"zones\":[\"Example.COM\","
        + "\"not a zone\"],\"credentials\":{}}"))).satisfies(d -> {
          assertThat(d.accountId()).isEqualTo(ACCOUNT);
          assertThat(d.zones()).containsExactly("example.com");
        });
  }
}
