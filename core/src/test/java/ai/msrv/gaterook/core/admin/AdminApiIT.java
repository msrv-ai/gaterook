package ai.msrv.gaterook.core.admin;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.core.platform.EventBus;
import ai.msrv.gaterook.core.platform.LocalListener;
import ai.msrv.gaterook.testfixtures.TestCore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

/**
 * Contract tests of the admin API (ARCHITECTURE.md §6, M1.6) on a whole core with the fake connectors, through MockMvc
 * and the core's own filters: every request is signed as Electron's main process signs it. The tests walk the screens
 * in the order a person sets the gateway up — health, the connection wizard, an endpoint with its tools, tokens and
 * settings, the Playground, an approval, Activity, policies, templates, Settings and Backup, Overview — and in the end
 * scan every answer: no property that could carry a secret holds a value, and the secrets typed into the forms appear
 * in no answer (§9 "Write-only secrets").
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdminApiIT {

  /** The password every form of this test types: it must never come back. */
  private static final String CANARY = "cnry-admin-" + UUID.randomUUID().toString().substring(0, 13);

  private static TestCore core;
  private static AdminMvc api;
  private static String approvalId;
  private static long connectionVersion;

  @BeforeAll
  static void start() {
    core = TestCore.start("admin-api", "--gateway.backup.catch-up-delay=1h");
    api = new AdminMvc(core.context(), core.bean(LocalListener.class).port());
  }

  @AfterAll
  static void stop() {
    if (core != null) {
      core.close();
    }
  }

  private static String password() {
    return CANARY;
  }

  private static String draft(String host) {
    return """
        {"connection":{"host":"%s","port":5432,"note":"orders"},"credentials":{"user":"app"}}""".formatted(host);
  }

  // ── The frame ───────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  @Order(1)
  void onlySignedRequestsReachTheApi() {
    assertThat(api.sendUnsigned(HttpMethod.GET, "/api/health", null).status()).isEqualTo(401);
    assertThat(api.sendUnsigned(HttpMethod.POST, "/api/connections", "{}").status()).isEqualTo(401);
    assertThat(api.get("/api/health").status()).isEqualTo(200);
    // Not JSON: refused by the guard before any controller.
    assertThat(api.send(HttpMethod.GET, "/api/nope", null).status()).isEqualTo(404);
  }

  @Test
  @Order(2)
  void healthCoreInfoCountersAndSearchDescribeTheCore() {
    JsonNode health = api.ok("/api/health");
    assertThat(health.path("status").asString()).isEqualTo("ok");
    assertThat(health.path("locked").isNull()).isTrue();
    assertThat(health.path("version").asString()).isNotBlank();
    assertThat(health.path("runtime").asString()).isEqualTo("jvm");
    assertThat(health.path("ports").path("local").asInt()).isEqualTo(api.port());
    assertThat(health.path("ports").path("public").asInt()).isPositive();
    // The newest applied migration; steps add their own (K2 from V30 on), so only its shape is fixed here.
    assertThat(Integer.parseInt(health.path("db").path("schema").asString())).isGreaterThanOrEqualTo(3);
    assertThat(health.path("db").path("journal").asString()).isEqualTo("wal");
    assertThat(health.path("db").path("sqlite").asString()).startsWith("3.");
    assertThat(health.path("db").path("sizeBytes").asLong()).isPositive();
    assertThat(health.path("db").path("path").asString()).endsWith("gateway.db");
    assertThat(health.path("masterKey").path("protection").asString()).isNotBlank();
    assertThat(health.path("tunnel").path("state").asString()).isEqualTo("off");
    assertThat(health.path("uptimeMs").asLong()).isNotNegative();

    JsonNode info = api.ok("/api/core/info");
    assertThat(info.path("runtime").asString()).isEqualTo("jvm");
    assertThat(info.path("port").asInt()).isEqualTo(api.port());
    assertThat(info.path("logFile").asString()).endsWith("core.log");
    assertThat(info.path("pluginRuntime").path("present").asBoolean()).as("a JVM build lends its JDK").isTrue();
    assertThat(info.path("pluginRuntime").path("bundledJre").asBoolean()).isFalse();

    JsonNode counters = api.ok("/api/nav/counters");
    assertThat(counters.path("pendingApprovals").asLong()).isZero();
    assertThat(counters.path("tunnel").asString()).isEqualTo("off");

    JsonNode search = api.ok("/api/search?q=sett");
    assertThat(search.path("goTo").findValuesAsString("id")).contains("settings");
  }

  // ── Connector catalog and the connection wizard ─────────────────────────────────────────────────────────────

  @Test
  @Order(10)
  void theCatalogGroupsCardsAndListsInstalledTypes() {
    JsonNode catalog = api.ok("/api/connector-types");
    List<String> groups = each(catalog.path("groups"), "name");
    assertThat(groups).containsSubsequence("Databases", "HTTP and MCP");
    JsonNode databases = group(catalog, "Databases");
    JsonNode pg = card(databases, "pgfake");
    assertThat(pg.path("kind").asString()).isEqualTo("built-in");
    assertThat(pg.path("tools").asInt()).isEqualTo(2);
    assertThat(pg.path("available").asBoolean()).isTrue();
    JsonNode http = group(catalog, "HTTP and MCP");
    JsonNode mcp = card(http, "mcpfake");
    assertThat(mcp.path("kind").asString()).isEqualTo("mcp-server");
    assertThat(mcp.path("tools").isNull()).isTrue();
    assertThat(mcp.path("toolsFrom").asString()).isEqualTo("server");
    // The generic MCP server type is the group's last card, plugins come before it.
    List<String> httpTypes = each(http.path("cards"), "type");
    assertThat(httpTypes.getLast()).isEqualTo("mcpfake");
    assertThat(httpTypes.indexOf("jirafake")).isLessThan(httpTypes.indexOf("mcpfake"));
    List<String> installed = each(catalog.path("installed"), "id");
    assertThat(installed).contains("pgfake", "jirafake").doesNotContain("mcpfake");
    assertThat(installedType(catalog, "jirafake").path("kind").asString()).isEqualTo("plugin");

    JsonNode only = api.ok("/api/connector-types?group=Databases");
    assertThat(only.path("groups").size()).isEqualTo(1);

    JsonNode type = api.ok("/api/connector-types/pgfake");
    assertThat(each(type.path("tools"), "name")).containsExactly("sql_query", "sql_execute");
    assertThat(type.path("templateKinds").get(0).asString()).isEqualTo("sql-query");
    assertThat(api.get("/api/connector-types/nope").status()).isEqualTo(404);

    JsonNode form = api.ok("/api/connector-types/pgfake/form?env=prod");
    assertThat(form.path("schema").path("properties").has("connection")).isTrue();
    assertThat(each(form.path("secretFields"), "pointer")).containsExactly("/credentials/password");
    assertThat(each(form.path("secretSources"), "id")).containsExactly("local", "vault", "gcp-sm",
        "aws-sm", "vault-dynamic");
    assertThat(form.path("common").path("env").path("enum").size()).isEqualTo(3);
  }

  @Test
  @Order(11)
  void aDraftIsTestedWithTheTypedSecretAndNothingIsSaved() {
    JsonNode ok = api.expect(200, HttpMethod.POST, "/api/connections/test", """
        {"type":"pgfake","env":"dev","config":%s,"secrets":{"/credentials/password":{"source":"local","value":"%s"}}}"""
        .formatted(draft("db.test"), password()));
    assertThat(ok.path("ok").asBoolean()).isTrue();
    assertThat(ok.path("latencyMs").asLong()).isEqualTo(12);
    assertThat(ok.path("permissions").path("level").asString()).isEqualTo("read");

    // The fake echoes the password into its error: the tester masks it before anyone sees it.
    JsonNode failed = api.expect(200, HttpMethod.POST, "/api/connections/test", """
        {"type":"pgfake","env":"dev","config":%s,"secrets":{"/credentials/password":{"source":"local","value":"%s"}}}"""
        .formatted(draft("fail.test"), password()));
    assertThat(failed.path("ok").asBoolean()).isFalse();
    assertThat(failed.path("error").path("message").asString()).contains("‹masked›").doesNotContain(CANARY);

    JsonNode trust = api.expect(200, HttpMethod.POST, "/api/connections/test", """
        {"type":"pgfake","env":"dev","config":%s,"secrets":{"/credentials/password":{"source":"local","value":"%s"}}}"""
        .formatted(draft("hostkey.test"), password()));
    assertThat(trust.path("needsConfirmation").path("kind").asString()).isEqualTo("host-key");
    String confirmation = trust.path("confirmationId").asString();
    JsonNode trusted = api.expect(200, HttpMethod.POST, "/api/connections/test/confirm",
        "{\"confirmationId\":\"" + confirmation + "\",\"reason\":\"checked with ops\"}");
    assertThat(trusted.path("config").path("connection").path("hostKey").asString()).startsWith("SHA256:");
    assertThat(api.get("/api/connections").json().path("total").asInt()).as("a draft saves nothing").isZero();

    JsonNode wrongField = api.expect(422, HttpMethod.POST, "/api/connections/test", """
        {"type":"pgfake","env":"dev","config":%s,"secrets":{"/credentials/user":{"source":"local","value":"x"}}}"""
        .formatted(draft("db.test")));
    assertThat(wrongField.path("title").asString()).isEqualTo("Not a secret field");

    JsonNode files = api.expect(200, HttpMethod.POST, "/api/connections/browse-files", """
        {"type":"pgfake","env":"dev","config":%s,"path":"/var/log",
         "secrets":{"/credentials/password":{"source":"local","value":"%s"}}}"""
        .formatted(draft("db.test"), password()));
    assertThat(files.path("ok").asBoolean()).isTrue();
    assertThat(files.path("details").path("path").asString()).isEqualTo("/var/log");
    assertThat(files.path("details").path("entries").get(0).path("name").asString()).isEqualTo("app.log");
    assertThat(api.get("/api/connections").json().path("total").asInt()).as("browsing saves nothing").isZero();
    JsonNode badPath = api.expect(422, HttpMethod.POST, "/api/connections/browse-files", """
        {"type":"pgfake","env":"dev","config":%s,"path":"relative",
         "secrets":{"/credentials/password":{"source":"local","value":"%s"}}}"""
        .formatted(draft("db.test"), password()));
    assertThat(badPath.path("title").asString()).isEqualTo("Invalid folder");
  }

  @Test
  @Order(12)
  void aConnectionIsCreatedListedEditedAndDuplicatedWithoutItsSecret() {
    JsonNode created = api.expect(201, HttpMethod.POST, "/api/connections", """
        {"name":"dev-db","type":"pgfake","env":"dev","config":%s,
         "secrets":{"/credentials/password":{"source":"local","value":"%s"}},"reason":"first database"}"""
        .formatted(draft("db.test"), password()));
    assertThat(created.path("name").asString()).isEqualTo("dev-db");
    assertThat(created.path("secretSource").asString()).isEqualTo("encrypted");
    api.expect(201, HttpMethod.POST, "/api/connections", """
        {"name":"cache-down","type":"pgfake","env":"dev","config":%s,
         "secrets":{"/credentials/password":{"source":"local","value":"%s"}}}""".formatted(draft("fail.test"),
        password()));
    JsonNode taken = api.expect(409, HttpMethod.POST, "/api/connections", """
        {"name":"dev-db","type":"pgfake","env":"dev","config":%s,
         "secrets":{"/credentials/password":{"source":"local","value":"%s"}}}""".formatted(draft("db.test"),
        password()));
    assertThat(taken.path("title").asString()).isEqualTo("Name in use");
    assertThat(api.answers().getLast().contentType()).startsWith("application/problem+json");

    JsonNode list = api.ok("/api/connections");
    assertThat(list.path("total").asInt()).isEqualTo(2);
    assertThat(list.path("facets").path("health").path("unknown").asInt()).isEqualTo(2);
    JsonNode filtered = api.ok("/api/connections?q=dev-db&env=dev");
    assertThat(filtered.path("items").size()).isEqualTo(1);
    assertThat(filtered.path("items").get(0).path("host").asString()).contains("db.test");
    assertThat(api.ok("/api/connections?env=prod").path("items").isEmpty()).isTrue();

    JsonNode detail = api.ok("/api/connections/dev-db");
    assertThat(detail.path("config").path("credentials").has("password")).isFalse();
    JsonNode secret = detail.path("secrets").get(0);
    assertThat(secret.path("field").asString()).isEqualTo("/credentials/password");
    assertThat(secret.path("state").asString()).isEqualTo("stored");
    assertThat(secret.path("version").asInt()).isEqualTo(1);
    connectionVersion = detail.path("version").asLong();

    JsonNode copy = api.ok("/api/connections/dev-db/duplicate");
    assertThat(copy.path("config").path("connection").path("host").asString()).isEqualTo("db.test");
    assertThat(copy.path("emptySecretFields").get(0).asString()).isEqualTo("/credentials/password");
    assertThat(copy.path("notice").asString()).contains("never copied");

    JsonNode stale = api.expect(409, HttpMethod.PUT, "/api/connections/dev-db", """
        {"env":"dev","version":%d,"config":%s}""".formatted(connectionVersion - 1, draft("db.test")));
    assertThat(stale.path("title").asString()).isEqualTo("Changed meanwhile");
    JsonNode newHost = api.expect(409, HttpMethod.PUT, "/api/connections/dev-db", """
        {"env":"dev","version":%d,"config":%s}""".formatted(connectionVersion, draft("db2.test")));
    assertThat(newHost.path("confirm").asString()).isEqualTo("send-secret-to-new-host");
    JsonNode edited = api.expect(200, HttpMethod.PUT, "/api/connections/dev-db", """
        {"env":"dev","version":%d,"config":%s,"reason":"note changed"}""".formatted(connectionVersion,
        draft("db.test").replace("orders", "orders and refunds")));
    assertThat(edited.path("version").asLong()).isGreaterThan(connectionVersion);
    connectionVersion = edited.path("version").asLong();

    JsonNode untested = api.ok("/api/connections?q=dev-db").path("items").get(0);
    assertThat(untested.path("typeLabel").asString()).as("no test yet: the connector's name").isEqualTo("FakePG");
    assertThat(untested.path("serverVersion").isNull()).isTrue();
    assertThat(untested.path("health").path("since").isNull()).isTrue();

    JsonNode tested = api.expect(200, HttpMethod.POST, "/api/connections/dev-db/test", null);
    assertThat(tested.path("ok").asBoolean()).isTrue();
    TestCore.await(() -> "healthy".equals(api.ok("/api/connections/dev-db").path("health").path("state")
        .asString()), "the stored test to record health");
    // What the last successful test saw (TestResult.metadataUpdate.serverVersion): the kit's "PostgreSQL 16".
    JsonNode row = api.ok("/api/connections?q=dev-db").path("items").get(0);
    assertThat(row.path("serverVersion").asString()).isEqualTo("FakePG 16");
    assertThat(row.path("typeLabel").asString()).isEqualTo("FakePG 16");
    assertThat(row.path("host").asString()).isEqualTo("db.test:5432");
    assertThat(row.path("health").path("since").asString()).isEqualTo(row.path("health").path("checkedAt")
        .asString());
    assertThat(api.ok("/api/connections").path("healthIntervalSeconds").asLong()).as("health checks off in tests")
        .isZero();
    assertThat(api.ok("/api/connections?kind=connection").path("total").asInt()).isEqualTo(2);
    assertThat(api.ok("/api/connections?kind=mcp-server").path("total").asInt()).isZero();
    assertThat(api.expect(422, HttpMethod.GET, "/api/connections?kind=servers", null).path("field").asString())
        .isEqualTo("/kind");
    JsonNode edit = api.ok("/api/connections/dev-db");
    assertThat(edit.path("typeLabel").asString()).isEqualTo("FakePG 16");
    assertThat(edit.path("host").asString()).isEqualTo("db.test:5432");
    assertThat(edit.path("metadata").isObject()).as("only what a form lists options from").isTrue();
    assertThat(edit.path("metadata").has("serverVersion")).isFalse();
    assertThat(edit.path("healthIntervalSeconds").asLong()).isZero();

    JsonNode described = api.ok("/api/connections/dev-db/describe");
    assertThat(described.path("settings").path("connection.note").asString()).isEqualTo("orders and refunds");
    assertThat(described.path("credentials").path("/credentials/password").asString()).isEqualTo("stored");
    assertThat(described.path("limits").path("maxRows").asInt()).isPositive();

    assertThat(api.expect(202, HttpMethod.POST, "/api/connections/test-all", null).path("connections").asInt())
        .isEqualTo(2);
    TestCore.await(() -> "unreachable".equals(api.ok("/api/connections/cache-down").path("health").path("state")
        .asString()), "test all to reach cache-down");
    assertThat(api.ok("/api/connections?health=unreachable").path("items").size()).isEqualTo(1);
    // The refusal echoed the stored password; the health line keeps only its mask.
    assertThat(api.ok("/api/connections/cache-down").path("health").path("message").asString()).contains("‹masked›");
    // "Connection refused since 11:42": a second check that says the same keeps when it started.
    String since = api.ok("/api/connections/cache-down").path("health").path("since").asString();
    assertThat(since).isNotBlank();
    api.expect(200, HttpMethod.POST, "/api/connections/cache-down/test", null);
    JsonNode again = api.ok("/api/connections/cache-down").path("health");
    assertThat(again.path("since").asString()).isEqualTo(since);
    assertThat(again.path("checkedAt").asString()).isNotEqualTo(since);
  }

  // ── Endpoints, tools, tokens ────────────────────────────────────────────────────────────────────────────────

  @Test
  @Order(20)
  void anEndpointIsCreatedWiredAndItsToolsSet() {
    JsonNode created = api.expect(201, HttpMethod.POST, "/api/endpoints",
        "{\"slug\":\"dev\",\"description\":\"Day-to-day development\",\"startFrom\":\"empty\"}");
    assertThat(created.path("url").asString()).endsWith("/mcp/dev");
    assertThat(api.expect(409, HttpMethod.POST, "/api/endpoints", "{\"slug\":\"dev\"}").path("title").asString())
        .isEqualTo("Slug in use");
    assertThat(api.expect(422, HttpMethod.POST, "/api/endpoints", "{\"slug\":\"Bad_Slug\"}").path("field")
        .asString()).isEqualTo("/slug");

    JsonNode preview = api.expect(200, HttpMethod.POST, "/api/endpoints/dev/connections/preview",
        "{\"connection\":\"dev-db\"}");
    assertThat(each(preview.path("tools"), "tool")).containsExactly("sql_query", "sql_execute");
    assertThat(preview.path("refusal").isNull()).isTrue();
    JsonNode wired = api.expect(200, HttpMethod.POST, "/api/endpoints/dev/connections/dev-db", "{\"reason\":\"db\"}");
    assertThat(wired.path("connections").get(0).path("name").asString()).isEqualTo("dev-db");
    assertThat(wired.path("endpoint").path("connections").path("total").asInt()).isEqualTo(1);

    JsonNode list = api.ok("/api/endpoints");
    assertThat(each(list, "slug")).containsExactly("default", "dev");
    JsonNode dev = endpoint(list, "dev");
    assertThat(dev.path("slug").asString()).isEqualTo("dev");
    assertThat(dev.path("status").asString()).isEqualTo("running");
    assertThat(dev.path("connections").path("byEnv").path("dev").asInt()).isEqualTo(1);
    assertThat(dev.path("risk").path("state").asString()).isNotBlank();

    JsonNode tools = api.ok("/api/endpoints/dev/tools");
    JsonNode db = tools.get(0);
    assertThat(db.path("connection").asString()).isEqualTo("dev-db");
    JsonNode execute = tool(db, "sql_execute");
    assertThat(execute.path("mode").asString()).isEqualTo("APPROVAL");
    assertThat(execute.path("readOnlyAllowed").asBoolean()).isFalse();
    assertThat(execute.path("reason").asString()).isNotBlank();
    JsonNode query = tool(db, "sql_query");
    assertThat(query.path("mode").asString()).isEqualTo("READ_ONLY");

    // Read only is natively refused for a tool that changes data.
    JsonNode refused = api.expect(422, HttpMethod.PUT, "/api/endpoints/dev/tools", """
        {"toolKey":"sql_execute","connection":"dev-db","mode":"READ_ONLY","version":%d}"""
        .formatted(execute.path("version").asLong()));
    assertThat(refused.path("detail").asString()).isNotBlank();
    JsonNode changed = api.expect(200, HttpMethod.PUT, "/api/endpoints/dev/tools", """
        {"toolKey":"sql_query","connection":"dev-db","mode":"APPROVAL","version":%d,"reason":"try"}"""
        .formatted(query.path("version").asLong()));
    assertThat(tool(changed.get(0), "sql_query").path("mode").asString()).isEqualTo("APPROVAL");
    JsonNode bulk = api.expect(200, HttpMethod.POST, "/api/endpoints/dev/tools/bulk",
        "{\"group\":\"dev-db\",\"mode\":\"DEFAULT\",\"reason\":\"back\"}");
    assertThat(bulk.path("changed").size()).isEqualTo(1);
    assertThat(api.expect(422, HttpMethod.POST, "/api/endpoints/dev/tools/bulk", "{\"mode\":\"WRITE\"}")
        .path("field").asString()).isEqualTo("/mode");
    JsonNode core = tools.get(tools.size() - 1);
    assertThat(core.path("group").asString()).isEqualTo("Core");
    assertThat(core.path("tools").findValuesAsString("name")).contains("list_connections", "describe_connection");
  }

  @Test
  @Order(21)
  void riskSettingsCheckDisableAndSplitAnswerAsTheKitAsks() {
    JsonNode risk = api.ok("/api/endpoints/dev/risk");
    assertThat(risk.path("ingredients").path("privateData").asString()).isIn("present", "absent");
    if ("none".equals(risk.path("state").asString())) {
      assertThat(api.expect(422, HttpMethod.POST, "/api/endpoints/dev/risk/dismiss", "{\"reason\":\"test data\"}")
          .path("title").asString()).isEqualTo("Nothing to dismiss");
    } else {
      assertThat(api.expect(200, HttpMethod.POST, "/api/endpoints/dev/risk/dismiss",
          "{\"reason\":\"only test data\"}").path("state").asString()).isEqualTo("dismissed");
      api.expect(200, HttpMethod.POST, "/api/endpoints/dev/risk/restore", "{}");
    }

    JsonNode settings = api.ok("/api/endpoints/dev/settings");
    assertThat(settings.path("protocol").path("revisions").size()).isEqualTo(2);
    assertThat(settings.path("approvalWait").path("firstWaitSeconds").asInt()).isEqualTo(15);
    assertThat(settings.path("instructionsMax").asInt()).isEqualTo(4000);
    assertThat(each(settings.path("revisions"), "rev")).containsExactly("2026-07-28", "2025-11-25");
    long version = settings.path("version").asLong();
    JsonNode badLimit = api.expect(422, HttpMethod.PUT, "/api/endpoints/dev/settings", """
        {"limits":{"rowLimit":-5},"version":%d}""".formatted(version));
    assertThat(badLimit.path("field").asString()).isEqualTo("/limits/rowLimit");
    // The Playground's approval answers come back at once: no first wait for a person.
    JsonNode saved = api.expect(200, HttpMethod.PUT, "/api/endpoints/dev/settings", """
        {"approvalWait":{"firstWaitSeconds":0},"limits":{"rowLimit":200},"version":%d,"reason":"tests"}"""
        .formatted(version));
    assertThat(saved.path("approvalWait").path("firstWaitSeconds").asInt()).isZero();
    assertThat(saved.path("limits").path("rowLimit").asInt()).isEqualTo(200);

    JsonNode check = api.expect(200, HttpMethod.POST, "/api/endpoints/dev/check", null);
    assertThat(check.path("text").asString()).contains("tools · 2 revisions · instructions").contains("/ 4,000");
    assertThat(check.path("instructionsChars").asInt()).isPositive();
    // Settings › Instructions shows the text the gateway writes while the person wrote none: what clients get.
    assertThat(saved.path("instructions").isNull()).isTrue();
    String defaults = saved.path("defaultInstructions").asString();
    assertThat(defaults).startsWith("You are connected to GateRook, endpoint \"dev\" (local development).")
        .contains("‹masked› marks a secret the gateway removed").contains("sql_execute");
    assertThat(defaults.length()).isEqualTo(check.path("instructionsChars").asInt());

    // New endpoint › Start from: what each start would wire now, counted as the list counts it after Create.
    JsonNode starts = api.ok("/api/endpoint-starts");
    assertThat(each(starts, "startFrom")).containsExactly("empty", "read-only-dev", "incident-response");
    assertThat(starts.get(0).path("tools").path("off").asInt()).as("Empty: the core tools, off").isEqualTo(2);
    JsonNode readOnlyDev = starts.get(1);
    assertThat(readOnlyDev.path("access").asString()).isEqualTo("read-only");
    assertThat(readOnlyDev.path("connections").toString()).contains("dev-db");
    assertThat(readOnlyDev.path("tools").path("approval").asInt()).as("a read-only endpoint asks nobody").isZero();
    assertThat(starts.get(2).path("env").asString()).isEqualTo("prod");

    assertThat(api.expect(200, HttpMethod.POST, "/api/endpoints/dev/disable", "{\"reason\":\"pause\"}")
        .path("status").asString()).isEqualTo("disabled");
    assertThat(api.expect(200, HttpMethod.POST, "/api/endpoints/dev/enable", null).path("status").asString())
        .isEqualTo("running");

    JsonNode copy = api.expect(201, HttpMethod.POST, "/api/endpoints/dev/duplicate", "{\"slug\":\"dev-copy\"}");
    assertThat(copy.path("connections").path("total").asInt()).isEqualTo(1);
    api.expect(200, HttpMethod.POST, "/api/endpoints/dev-copy/connections/cache-down", null);
    assertThat(api.expect(422, HttpMethod.POST, "/api/endpoints/dev-copy/split/preview",
        "{\"move\":[\"dev-db\",\"cache-down\"],\"newSlug\":\"x\"}").path("title").asString())
        .isEqualTo("Nothing would stay");
    JsonNode split = api.expect(200, HttpMethod.POST, "/api/endpoints/dev-copy/split/preview",
        "{\"move\":[\"cache-down\"],\"newSlug\":\"cache-only\"}");
    assertThat(split.path("movedTools").size()).isPositive();
    assertThat(api.expect(422, HttpMethod.POST, "/api/endpoints/dev-copy/split/preview", "{\"newSlug\":\"x\"}")
        .path("field").asString()).isEqualTo("/move");
    JsonNode wrongSlug = api.expect(422, HttpMethod.DELETE, "/api/endpoints/dev-copy", "{\"confirmSlug\":\"dev\"}");
    assertThat(wrongSlug.path("field").asString()).isEqualTo("/confirmSlug");
    JsonNode deleted = api.expect(200, HttpMethod.DELETE, "/api/endpoints/dev-copy",
        "{\"confirmSlug\":\"dev-copy\",\"reason\":\"not needed\"}");
    assertThat(deleted.path("rejectedApprovals").asInt()).isZero();
    assertThat(api.get("/api/endpoints/dev-copy").status()).isEqualTo(404);
  }

  @Test
  @Order(22)
  void aTokenIsShownOnceAndOnlyItsPrefixAfterwards() {
    JsonNode issued = api.expect(201, HttpMethod.POST, "/api/endpoints/dev/tokens",
        "{\"name\":\"inspector-debug\",\"ttlDays\":3,\"reason\":\"inspector\"}");
    String token = issued.path("token").asString();
    assertThat(token).startsWith("gwt_");
    assertThat(issued.path("prefix").asString()).isEqualTo(token.substring(0, 8));
    JsonNode rows = api.ok("/api/endpoints/dev/tokens");
    assertThat(rows.size()).isEqualTo(1);
    assertThat(rows.get(0).has("token")).isFalse();
    assertThat(rows.get(0).path("state").asString()).isEqualTo("active");
    assertThat(endpoint(api.ok("/api/endpoints"), "dev").path("tokens").path("active").asInt()).isEqualTo(1);
    assertThat(api.expect(422, HttpMethod.POST, "/api/endpoints/dev/tokens", "{\"name\":\"x\",\"ttlDays\":400}")
        .path("field").asString()).isEqualTo("/ttlDays");
    JsonNode revoked = api.expect(200, HttpMethod.DELETE, "/api/tokens/" + rows.get(0).path("id").asString(),
        "{\"reason\":\"done\"}");
    assertThat(revoked.path("rejectedApprovals").asInt()).isZero();
    assertThat(api.ok("/api/endpoints/dev/tokens").get(0).path("state").asString()).isEqualTo("revoked");
    // The value appears in exactly one answer: the one that issued it.
    long withToken = api.answers().stream().filter(a -> a.body() != null && a.body().contains(token)).count();
    assertThat(withToken).isEqualTo(1);
  }

  // ── Playground, approvals, Activity ─────────────────────────────────────────────────────────────────────────

  @Test
  @Order(30)
  void thePlaygroundCallsThroughTheSamePipelineInBothRevisions() {
    JsonNode tools = api.ok("/api/playground/tools?endpoint=dev");
    assertThat(tools.path("era").asString()).isEqualTo("2026-07-28");
    JsonNode query = playgroundTool(tools, "sql_query");
    assertThat(query.path("mode").asString()).isEqualTo("READ_ONLY");
    assertThat(query.path("runnable").asBoolean()).isTrue();
    assertThat(query.path("connections").get(0).asString()).isEqualTo("dev-db");
    assertThat(playgroundTool(tools, "sql_execute").path("needsApproval").asBoolean()).isTrue();
    assertThat(tools.path("wirePreview").path("result").path("tools").findValuesAsString("name"))
        .contains("sql_query", "approval_status");

    JsonNode modern = api.expect(200, HttpMethod.POST, "/api/playground/call", """
        {"endpoint":"dev","tool":"sql_query","arguments":{"connection":"dev-db","sql":"SELECT 1"}}""");
    assertThat(modern.path("isError").asBoolean()).as(modern.toString()).isFalse();
    assertThat(modern.path("callId").asString()).startsWith("tc_");
    assertThat(modern.path("wire").path("request").path("params").path("_meta")
        .path("io.modelcontextprotocol/protocolVersion").asString()).isEqualTo("2026-07-28");
    assertThat(modern.path("structured").path("rows").size()).isEqualTo(3);
    assertThat(modern.path("meta").path("bytes").asLong()).isPositive();

    JsonNode legacy = api.expect(200, HttpMethod.POST, "/api/playground/call", """
        {"endpoint":"dev","tool":"sql_query","arguments":{"connection":"dev-db","sql":"SELECT 2"},
         "era":"2025-11-25"}""");
    assertThat(legacy.path("isError").asBoolean()).isFalse();
    assertThat(legacy.path("wire").path("request").path("params").has("_meta")).isFalse();

    JsonNode history = api.ok("/api/playground/history?endpoint=dev");
    assertThat(history.path("events").size()).isEqualTo(2);
    assertThat(history.path("events").get(0).path("client").asString()).isEqualTo("playground");
    assertThat(api.expect(422, HttpMethod.POST, "/api/playground/call",
        "{\"endpoint\":\"dev\",\"tool\":\"sql_query\",\"era\":\"2024-01-01\"}").path("field").asString())
        .isEqualTo("/era");
  }

  @Test
  @Order(31)
  void aMutationWaitsInApprovalsUntilThePersonApprovesTheDigestTheySaw() {
    JsonNode call = api.expect(200, HttpMethod.POST, "/api/playground/call", """
        {"endpoint":"dev","tool":"sql_execute","arguments":{"connection":"dev-db",
         "sql":"UPDATE orders SET status = 'cancelled' WHERE id = 48213;"}}""");
    assertThat(call.path("approval").path("status").asString()).isEqualTo("pending");
    approvalId = call.path("approval").path("handle").asString();
    assertThat(api.ok("/api/nav/counters").path("pendingApprovals").asLong()).isEqualTo(1);

    JsonNode pending = api.ok("/api/approvals");
    assertThat(pending.path("pending").asLong()).isEqualTo(1);
    JsonNode item = pending.path("items").get(0);
    assertThat(item.path("id").asString()).isEqualTo(approvalId);
    assertThat(item.path("tool").asString()).isEqualTo("sql_execute");
    assertThat(item.path("client").asString()).isEqualTo("playground");
    assertThat(item.path("tokenLabel").asString()).isEqualTo("playground");

    JsonNode detail = api.ok("/api/approvals/" + approvalId);
    assertThat(detail.path("action").path("segments").get(0).path("t").asString()).isEqualTo("text");
    assertThat(detail.path("action").path("text").asString()).contains("UPDATE orders");
    assertThat(detail.path("rule").path("text").asString()).isNotBlank();
    assertThat(detail.path("timeline").findValuesAsString("kind")).contains("requested");
    String digest = detail.path("digest").asString();
    assertThat(api.get("/api/approvals/ap_000000000000").status()).isEqualTo(404);

    assertThat(api.expect(422, HttpMethod.POST, "/api/approvals/" + approvalId + "/decision",
        "{\"decision\":\"approve\"}").path("field").asString()).isEqualTo("/digest");
    assertThat(api.expect(422, HttpMethod.POST, "/api/approvals/" + approvalId + "/decision", """
        {"decision":"approve","digest":"%s","via":"desktop-notification"}""".formatted(digest)).path("field")
        .asString()).isEqualTo("/decision");
    JsonNode decided = api.expect(200, HttpMethod.POST, "/api/approvals/" + approvalId + "/decision", """
        {"decision":"approve","digest":"%s","comment":"customer asked"}""".formatted(digest.substring(0, 12)));
    assertThat(decided.path("status").asString()).isIn("running", "completed");
    TestCore.await(() -> "completed".equals(api.ok("/api/approvals/" + approvalId).path("status").asString()),
        "the approved call to run");
    JsonNode done = api.ok("/api/approvals/" + approvalId);
    assertThat(done.path("decidedVia").asString()).isEqualTo("app");
    assertThat(done.path("comment").asString()).isEqualTo("customer asked");
    assertThat(done.path("result").path("isError").asBoolean()).isFalse();
    // Where the call is in Activity: its event (the latest row, as the events table names it) and its trace.
    JsonNode event = api.ok("/api/activity/events?q=tool:sql_execute").path("events").findParents("approvalId")
        .stream().filter(e -> approvalId.equals(e.path("approvalId").asString(null))).findFirst().orElseThrow();
    assertThat(done.path("eventId").asLong()).isEqualTo(event.path("id").asLong());
    assertThat(done.path("traceId").asString()).hasSize(32).isEqualTo(event.path("traceId").asString());
    assertThat(done.path("traceIsTask").asBoolean()).as("a Playground call's own trace isn't a task of Traces")
        .isFalse();
    assertThat(api.ok("/api/activity/events/" + done.path("eventId").asLong()).path("event").path("approvalId")
        .asString()).isEqualTo(approvalId);
    assertThat(api.expect(409, HttpMethod.POST, "/api/approvals/" + approvalId + "/decision",
        "{\"decision\":\"deny\"}").path("title").asString()).isEqualTo("Already decided");

    // An OS notification can deny without the digest.
    JsonNode second = api.expect(200, HttpMethod.POST, "/api/playground/call", """
        {"endpoint":"dev","tool":"sql_execute","arguments":{"connection":"dev-db","sql":"DELETE FROM carts;"}}""");
    String other = second.path("approval").path("handle").asString();
    assertThat(api.expect(200, HttpMethod.POST, "/api/approvals/" + other + "/decision",
        "{\"decision\":\"deny\",\"via\":\"desktop-notification\"}").path("status").asString()).isEqualTo("rejected");

    JsonNode history = api.ok("/api/approvals?status=history&limit=1");
    assertThat(history.path("items").size()).isEqualTo(1);
    assertThat(history.path("nextCursor").isString()).isTrue();
    JsonNode older = api.ok("/api/approvals?status=history&limit=1&cursor=" + history.path("nextCursor").asString());
    assertThat(older.path("items").get(0).path("id").asString()).isNotEqualTo(history.path("items").get(0)
        .path("id").asString());
    assertThat(history.path("counts").path("completed").asLong()).isEqualTo(1);
    assertThat(history.path("counts").path("rejected").asLong()).isEqualTo(1);
    assertThat(api.get("/api/approvals?status=everything").status()).isEqualTo(422);
  }

  @Test
  @Order(32)
  void activityFindsTheCallsAndExplainsEachOne() throws Exception {
    JsonNode events = api.ok("/api/activity/events?q=tool:sql_query");
    assertThat(events.path("events").size()).isEqualTo(2);
    JsonNode first = events.path("events").get(0);
    assertThat(first.path("decision").asString()).isEqualTo("allowed");
    assertThat(first.path("tokenLabel").asString()).isEqualTo("playground");
    assertThat(first.has("token")).isFalse();
    assertThat(api.get("/api/activity/events?q=nonsense:1").status()).isEqualTo(422);

    JsonNode histogram = api.ok("/api/activity/histogram");
    assertThat(histogram.path("stepHours").asInt()).isEqualTo(1);
    long total = 0;
    for (JsonNode b : histogram.path("buckets")) {
      total += b.path("total").asLong();
    }
    assertThat(total).isEqualTo(api.ok("/api/activity/events").path("events").size());

    JsonNode facets = api.ok("/api/activity/facets");
    assertThat(facets.path("tool").findValuesAsString("name")).contains("sql_query", "sql_execute");
    assertThat(facets.path("decision").findValuesAsString("name")).contains("allowed", "approved", "rejected");

    JsonNode detail = api.ok("/api/activity/events/" + first.path("id").asLong());
    assertThat(each(detail.path("rows"), "phase")).containsExactly("received", "completed");
    assertThat(detail.path("rows").get(0).path("args").path("sql").asString()).startsWith("SELECT");
    assertThat(detail.path("rows").get(1).path("resultBytes").asLong()).isPositive();
    assertThat(api.get("/api/activity/events/999999").status()).isEqualTo(404);

    JsonNode traces = api.ok("/api/activity/traces");
    assertThat(traces.path("traces").isArray()).isTrue();

    JsonNode changes = api.ok("/api/activity/changes?q=dev-db");
    assertThat(changes.path("changes").findValuesAsString("subject")).contains("Connection dev-db added");
    assertThat(changes.path("changes").get(0).path("by").asString()).isEqualTo("You");

    JsonNode views = api.ok("/api/activity/views");
    assertThat(views.path("version").asLong()).as("never saved").isZero();
    JsonNode saved = api.expect(200, HttpMethod.PUT, "/api/activity/views", """
        {"views":[{"name":"Approvals","q":"decision:approved decision:rejected","view":"events"}],"version":0}""");
    assertThat(saved.path("views").get(0).path("name").asString()).isEqualTo("Approvals");
    assertThat(api.expect(409, HttpMethod.PUT, "/api/activity/views", "{\"views\":[],\"version\":0}")
        .path("title").asString()).isEqualTo("Changed meanwhile");

    Path target = core.home().resolve("tmp").resolve("activity.csv");
    String grant = api.expect(200, HttpMethod.POST, "/internal/files/grant", """
        {"path":"%s","purpose":"audit-export"}""".formatted(target.toString().replace("\\", "\\\\")))
        .path("token").asString();
    JsonNode exported = api.expect(200, HttpMethod.POST, "/api/activity/export", """
        {"q":"tool:sql_query","format":"csv","target":"file","saveToken":"%s"}""".formatted(grant));
    assertThat(exported.path("rows").asLong()).isEqualTo(2);
    List<String> lines = Files.readAllLines(target);
    assertThat(lines.get(0)).startsWith("id,callId,at,decision");
    assertThat(lines).hasSize(3);
    assertThat(Files.getPosixFilePermissions(target)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
  }

  // ── Policies and templates ──────────────────────────────────────────────────────────────────────────────────

  @Test
  @Order(40)
  void policiesShowTheirLocksAndChangeWithTheirVersion() {
    JsonNode all = api.ok("/api/policies");
    JsonNode dev = all.path("dev");
    assertThat(all.path("stage").path("inherits").asString()).isEqualTo("dev");
    assertThat(dev.path("serving").asBoolean()).isTrue();
    assertThat(dev.path("locks").findValuesAsString("key")).contains("readOnlyTransactions", "approvers", "secrets");
    assertThat(dev.path("sections").path("sql").path("rowLimit").asInt()).isEqualTo(1000);
    assertThat(dev.path("sections").path("limits").path("toolTimeoutSeconds").asInt()).isEqualTo(30);
    assertThat(all.path("stage").path("sections").path("limits").path("toolTimeoutSeconds").asInt()).isEqualTo(30);
    assertThat(all.path("prod").path("sections").path("limits").path("toolTimeoutSeconds").asInt()).isEqualTo(5);
    assertThat(all.path("prod").path("sections").path("approvals").path("comment").asString()).isEqualTo("required");
    assertThat(all.path("usage").path("busiestTool").path("name").asString()).isNotBlank();
    assertThat(api.ok("/api/policies/schema").path("sections").has("approvals")).isTrue();

    long version = dev.path("version").asLong();
    JsonNode locked = api.expect(422, HttpMethod.PUT, "/api/policies/dev", """
        {"sections":{"sql":{"readOnlyTransactions":false}},"version":%d}""".formatted(version));
    assertThat(locked.path("field").asString()).contains("readOnlyTransactions");
    JsonNode changed = api.expect(200, HttpMethod.PUT, "/api/policies/dev", """
        {"sections":{"sql":{"rowLimit":500},"limits":{"toolTimeoutSeconds":10}},"version":%d,"reason":"smaller"}""".formatted(version));
    assertThat(changed.path("sections").path("sql").path("rowLimit").asInt()).isEqualTo(500);
    assertThat(changed.path("explicit").path("sql").path("rowLimit").asInt()).isEqualTo(500);
    assertThat(api.ok("/api/policies/dev").path("sections").path("limits").path("toolTimeoutSeconds").asInt()).isEqualTo(10);
    assertThat(api.ok("/api/policies/stage").path("sections").path("limits").path("toolTimeoutSeconds").asInt()).isEqualTo(10);
    api.expect(409, HttpMethod.PUT, "/api/policies/dev", """
        {"sections":{"sql":{"rowLimit":400}},"version":%d}""".formatted(version));
    JsonNode reset = api.expect(200, HttpMethod.POST, "/api/policies/dev/reset", "{\"reason\":\"back\"}");
    assertThat(reset.path("explicit").isEmpty()).isTrue();
    assertThat(reset.path("sections").path("limits").path("toolTimeoutSeconds").asInt()).isEqualTo(30);
    assertThat(api.get("/api/policies/qa").status()).isEqualTo(404);
  }

  @Test
  @Order(41)
  void aTemplateCompilesPreviewsVersionsAndGoes() {
    JsonNode notCompiled = api.expect(200, HttpMethod.POST, "/api/templates", """
        {"kind":"sql-query","connection":"dev-db","name":"orders_by_status","definition":{"name":"orders_by_status"}}""");
    assertThat(notCompiled.path("template").isNull()).isTrue();
    assertThat(notCompiled.path("compile").path("ok").asBoolean()).isFalse();
    assertThat(notCompiled.path("compile").path("errors").get(0).asString()).isEqualTo("sql is missing");

    JsonNode saved = api.expect(201, HttpMethod.POST, "/api/templates", """
        {"kind":"sql-query","connection":"dev-db","name":"orders_by_status",
         "definition":{"name":"orders_by_status","sql":"SELECT * FROM orders WHERE status = :status"}}""");
    String id = saved.path("template").path("id").asString();
    assertThat(saved.path("compile").path("tools").get(0).path("name").asString()).isEqualTo("orders_by_status");
    assertThat(api.ok("/api/templates?kind=sql-query").size()).isEqualTo(1);
    JsonNode preview = api.expect(200, HttpMethod.POST, "/api/templates/" + id + "/preview",
        "{\"arguments\":{\"status\":\"pending\"}}");
    assertThat(preview.path("action").path("language").asString()).isEqualTo("sql");
    assertThat(preview.path("action").path("segments").size()).isPositive();
    JsonNode v2 = api.expect(200, HttpMethod.PUT, "/api/templates/" + id, """
        {"baseVersion":1,"definition":{"name":"orders_by_status","sql":"SELECT id FROM orders WHERE status = :status"},
         "note":"fewer columns"}""");
    assertThat(v2.path("template").path("version").asInt()).isEqualTo(2);
    assertThat(api.ok("/api/templates/" + id + "/versions").size()).isEqualTo(2);
    // The editor opens the current version; the list carries the signature, never the definition.
    JsonNode opened = api.ok("/api/templates/" + id);
    assertThat(opened.path("definition").path("sql").asString())
        .isEqualTo("SELECT id FROM orders WHERE status = :status");
    assertThat(opened.path("parameters").isArray()).isTrue();
    JsonNode row = api.ok("/api/templates?kind=sql-query").get(0);
    assertThat(row.path("definition").isNull()).isTrue();
    assertThat(row.path("parameters").isArray()).isTrue();
    api.expect(409, HttpMethod.PUT, "/api/templates/" + id, "{\"baseVersion\":1,\"definition\":{\"sql\":\"x\"}}");
    assertThat(api.expect(200, HttpMethod.POST, "/api/templates/" + id + "/disable", null).path("status").asString())
        .isEqualTo("disabled");
    assertThat(api.expect(200, HttpMethod.POST, "/api/templates/" + id + "/enable", null).path("status").asString())
        .isEqualTo("active");
    assertThat(api.expect(422, HttpMethod.POST, "/api/templates/openapi/parse", "{}").path("title").asString())
        .isEqualTo("OpenAPI import unavailable");
    assertThat(api.delete("/api/templates/" + id, null).status()).isEqualTo(204);
    assertThat(api.get("/api/templates/" + id).status()).isEqualTo(404);
  }

  // ── Settings and backup ─────────────────────────────────────────────────────────────────────────────────────

  @Test
  @Order(50)
  void settingsSectionsValidateAndKeepTheirVersion() {
    JsonNode all = api.ok("/api/settings");
    assertThat(List.copyOf(all.propertyNames())).containsExactly("general", "appearance", "authorization",
        "approval-rules", "local-api", "audit", "backup");
    assertThat(all.path("general").path("values").path("autoUpdate").asBoolean()).isTrue();
    assertThat(all.path("authorization").path("values").path("tokenTtlDays").asInt()).isEqualTo(184);
    assertThat(all.path("audit").path("values").path("retentionYears").asInt()).isEqualTo(1);
    assertThat(all.path("backup").path("values").path("keep").asInt()).isEqualTo(7);
    assertThat(all.path("local-api").path("values").path("address").asString()).isEqualTo("127.0.0.1");
    assertThat(all.path("approval-rules").path("values").path("requireCommentOnProd").asBoolean()).isTrue();
    assertThat(all.path("general").path("version").asLong()).isZero();

    JsonNode general = api.expect(200, HttpMethod.PUT, "/api/settings/general",
        "{\"autoUpdate\":false,\"version\":0,\"reason\":\"metered connection\"}");
    assertThat(general.path("values").path("autoUpdate").asBoolean()).isFalse();
    assertThat(general.path("version").asLong()).isPositive();
    assertThat(api.expect(409, HttpMethod.PUT, "/api/settings/general", "{\"autoUpdate\":true,\"version\":0}")
        .path("title").asString()).isEqualTo("Changed meanwhile");
    assertThat(api.expect(422, HttpMethod.PUT, "/api/settings/audit", "{\"retentionYears\":4,\"version\":0}")
        .path("field").asString()).isEqualTo("/retentionYears");
    assertThat(api.expect(422, HttpMethod.PUT, "/api/settings/appearance", "{\"theme\":\"pink\",\"version\":0}")
        .path("field").asString()).isEqualTo("/theme");
    assertThat(api.expect(422, HttpMethod.PUT, "/api/settings/general", "{\"autoUpdate\":true}").path("field")
        .asString()).isEqualTo("/version");
    assertThat(api.get("/api/settings/users").status()).isEqualTo(404);
    JsonNode defaultToken = api.expect(201, HttpMethod.POST, "/api/endpoints/dev/tokens",
        "{\"name\":\"six-month-default\"}");
    assertThat(Duration.between(Instant.now(), Instant.parse(defaultToken.path("expiresAt").asString())).toDays())
        .isBetween(183L, 184L);
    JsonNode preset = api.expect(200, HttpMethod.PUT, "/api/settings/authorization",
        "{\"tokenTtlDays\":184,\"version\":0}");
    assertThat(preset.path("values").path("tokenTtlDays").asInt()).isEqualTo(184);
    api.expect(422, HttpMethod.PUT, "/api/settings/authorization",
        "{\"tokenTtlDays\":185,\"version\":%d}".formatted(preset.path("version").asLong()));
    api.expect(201, HttpMethod.POST, "/api/endpoints/dev/tokens", "{\"name\":\"six-month-explicit\",\"ttlDays\":184}");
    for (int invalid : List.of(0, 185)) {
      assertThat(api.expect(422, HttpMethod.POST, "/api/endpoints/dev/tokens",
          "{\"name\":\"invalid-lifetime\",\"ttlDays\":%d}".formatted(invalid)).path("field").asString())
          .isEqualTo("/ttlDays");
    }
    // TokenService still honors a shorter default selected in Settings.
    api.expect(200, HttpMethod.PUT, "/api/settings/authorization",
        "{\"tokenTtlDays\":30,\"version\":%d}".formatted(preset.path("version").asLong()));
    JsonNode token = api.expect(201, HttpMethod.POST, "/api/endpoints/dev/tokens", "{\"name\":\"ttl-default\"}");
    long days = Duration.between(java.time.Instant.now(), java.time.Instant.parse(token.path("expiresAt").asString()))
        .toDays();
    assertThat(days).isBetween(29L, 30L);
    assertThat(api.ok("/api/activity/changes?q=autoUpdate").path("changes").get(0).path("reason").asString())
        .isEqualTo("metered connection");
  }

  @Test
  @Order(51)
  void approvalRulesAndLocalApiWriteWhereTheirValuesLive() {
    long rulesVersion = api.ok("/api/settings/approval-rules").path("version").asLong();
    JsonNode off = api.expect(200, HttpMethod.PUT, "/api/settings/approval-rules",
        "{\"requireCommentOnProd\":false,\"version\":%d,\"reason\":\"trial\"}".formatted(rulesVersion));
    assertThat(off.path("values").path("requireCommentOnProd").asBoolean()).isFalse();
    assertThat(off.path("values").path("perEnvironment").path("prod").path("comment").asString())
        .isEqualTo("optional");
    assertThat(api.ok("/api/policies/prod").path("sections").path("approvals").path("comment").asString())
        .isEqualTo("optional");
    JsonNode on = api.expect(200, HttpMethod.PUT, "/api/settings/approval-rules",
        "{\"requireCommentOnProd\":true,\"decisionsFrom\":\"app\",\"version\":%d}".formatted(off.path("version")
            .asLong()));
    assertThat(on.path("values").path("requireCommentOnProd").asBoolean()).isTrue();
    assertThat(on.path("values").path("decisionsFrom").asString()).isEqualTo("app");

    EventBus events = core.bean(EventBus.class);
    try (EventBus.Subscription sub = events.subscribe(java.util.OptionalLong.empty())) {
      JsonNode local = api.ok("/api/settings/local-api");
      assertThat(local.path("values").path("port").asInt()).isEqualTo(api.port());
      int publicPort = local.path("values").path("publicPort").asInt();
      assertThat(api.expect(422, HttpMethod.PUT, "/api/settings/local-api",
          "{\"port\":%d,\"version\":0}".formatted(publicPort)).path("field").asString()).isEqualTo("/port");
      assertThat(api.expect(422, HttpMethod.PUT, "/api/settings/local-api",
          "{\"allowedOrigins\":[\"http://localhost:6274/path\"],\"version\":0}").path("field").asString())
          .isEqualTo("/allowedOrigins/0");
      api.expect(200, HttpMethod.PUT, "/api/settings/local-api",
          "{\"allowedOrigins\":[\"http://localhost:6274\"],\"version\":0}");
      Optional<EventBus.GatewayEvent> restart = next(sub, "core.restart-required");
      assertThat(restart).isPresent();
      assertThat(restart.get().data().path("reason").asString()).isEqualTo("allowed-origins");
    }
  }

  @Test
  @Order(52)
  void theDatabaseIsBackedUpNowIntoTheFolderOrTheChosenFile() throws Exception {
    JsonNode status = api.ok("/api/backup");
    assertThat(status.path("daily").asBoolean()).isTrue();
    assertThat(status.path("at").asString()).isEqualTo("03:00");
    assertThat(status.path("keep").asInt()).isEqualTo(7);
    assertThat(status.path("folder").asString()).endsWith("backups");

    JsonNode copy = api.expect(200, HttpMethod.POST, "/api/backup/db", null);
    Path file = Path.of(copy.path("path").asString());
    assertThat(copy.path("file").asString()).matches("gateway-\\d{4}-\\d{2}-\\d{2}-\\d{4}\\.db");
    assertThat(Files.size(file)).isPositive();
    assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
    assertThat(api.ok("/api/backup").path("lastFile").asString()).isEqualTo(copy.path("file").asString());

    Path chosen = core.home().resolve("tmp").resolve("my-copy.db");
    String grant = api.expect(200, HttpMethod.POST, "/internal/files/grant", """
        {"path":"%s","purpose":"db-backup"}""".formatted(chosen)).path("token").asString();
    JsonNode chosenCopy = api.expect(200, HttpMethod.POST, "/api/backup/db", "{\"saveToken\":\"" + grant + "\"}");
    assertThat(Path.of(chosenCopy.path("path").asString())).isEqualTo(chosen);
    assertThat(Files.size(chosen)).isPositive();
    assertThat(api.expect(422, HttpMethod.POST, "/api/backup/db", "{\"saveToken\":\"" + grant + "\"}")
        .path("field").asString()).as("a save token works once").isEqualTo("/saveToken");
  }

  // ── Overview, onboarding, secrets ───────────────────────────────────────────────────────────────────────────

  @Test
  @Order(60)
  void overviewSumsUpTheSetupAndTheDay() {
    JsonNode overview = api.ok("/api/overview");
    JsonNode steps = overview.path("gettingStarted").path("steps");
    assertThat(each(steps, "id")).containsExactly("master-key", "connection", "endpoint", "client",
        "playground", "notifications");
    assertThat(step(steps, "connection").path("state").asString()).isEqualTo("done");
    assertThat(step(steps, "endpoint").path("state").asString()).isEqualTo("done");
    assertThat(step(steps, "playground").path("state").asString()).isEqualTo("done");
    assertThat(step(steps, "master-key").path("state").asString()).as("never backed up").isEqualTo("current");
    assertThat(overview.path("gettingStarted").path("total").asInt()).isEqualTo(6);
    List<String> nodes = each(overview.path("setupMap").path("nodes"), "id");
    assertThat(nodes).containsExactly("secrets", "connections", "policies", "endpoints", "clients");
    assertThat(node(overview, "connections").path("state").asString()).as("cache-down is unreachable")
        .isEqualTo("attention");
    // The kit's words (design/pages/overview.html: "12 connections · billing-api unreachable", …).
    assertThat(node(overview, "connections").path("status").asString())
        .isEqualTo("2 connections · cache-down unreachable");
    assertThat(node(overview, "policies").path("status").asString())
        .isEqualTo("3 environments · mutations need approval");
    assertThat(node(overview, "endpoints").path("status").asString()).matches("2 endpoints · (\\d+ active tokens?|no "
        + "active tokens)");
    assertThat(node(overview, "secrets").path("status").asString()).as("never backed up, with a secret stored here")
        .startsWith("Master key: ").contains("not backed up").endsWith(" secrets");
    assertThat(node(overview, "clients").path("status").asString()).as("no client on this test's own home")
        .doesNotContain("connected ·");
    assertThat(node(overview, "policies").path("count").asInt()).isEqualTo(3);
    assertThat(overview.path("stats").path("calls24h").asLong()).isPositive();
    assertThat(overview.path("stats").path("spark").size()).isEqualTo(24);
    // The overhead tile's sparkline: the gateway's p95 of each hour, null where nothing completed.
    JsonNode p95 = overview.path("stats").path("gatewayP95Spark");
    assertThat(p95.size()).isEqualTo(24);
    assertThat(p95.valueStream().anyMatch(JsonNode::isNumber)).as("this test's calls completed").isTrue();
    assertThat(p95.get(0).isNull()).as("nothing ran a day ago").isTrue();
    assertThat(overview.path("healthIntervalSeconds").asLong()).isZero();
    JsonNode down = overview.path("health").findParents("name").stream()
        .filter(h -> h.path("name").asString().equals("cache-down")).findFirst().orElseThrow();
    assertThat(down.path("since").asString()).isNotBlank();
    assertThat(down.path("typeLabel").asString()).isEqualTo("FakePG");
    assertThat(overview.path("recent").size()).isPositive();
    assertThat(overview.path("health").findValuesAsString("name")).contains("dev-db", "cache-down");

    assertThat(api.expect(200, HttpMethod.POST, "/api/overview/getting-started/dismiss", null).path("dismissed")
        .asBoolean()).isTrue();
    assertThat(api.expect(200, HttpMethod.POST, "/api/overview/getting-started/restore", null).path("dismissed")
        .asBoolean()).isFalse();

    JsonNode onboarding = api.ok("/api/onboarding");
    assertThat(onboarding.path("firstRun").asBoolean()).as("this home was empty at start").isTrue();
    assertThat(onboarding.path("skipped").asBoolean()).isFalse();
    assertThat(api.expect(200, HttpMethod.POST, "/api/onboarding/skip", null).path("skipped").asBoolean()).isTrue();
    assertThat(api.ok("/api/onboarding").path("skipped").asBoolean()).isTrue();

    JsonNode secrets = api.ok("/api/secrets");
    assertThat(secrets.findValuesAsString("name")).contains("dev-db/password");
  }

  @Test
  @Order(70)
  void deletingAConnectionNeedsItsNameAndSaysWhatItStopped() {
    JsonNode wrong = api.expect(422, HttpMethod.DELETE, "/api/connections/cache-down", "{\"confirmName\":\"dev-db\"}");
    assertThat(wrong.path("field").asString()).isEqualTo("/confirmName");
    JsonNode deleted = api.expect(200, HttpMethod.DELETE, "/api/connections/cache-down",
        "{\"confirmName\":\"cache-down\",\"reason\":\"gone\"}");
    assertThat(deleted.path("secretsDestroyed").size()).isEqualTo(1);
    assertThat(api.get("/api/connections/cache-down").status()).isEqualTo(404);
  }

  // ── The scan ────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  @Order(100)
  void noAnswerCarriesASecretValue() {
    List<String> problems = new ArrayList<>();
    int json = 0;
    for (AdminMvc.Answer a : api.answers()) {
      if (a.body() != null && a.body().contains(CANARY)) {
        problems.add(a.method() + " " + a.target() + " echoes the password typed into a form");
      }
      if (a.contentType() == null || !a.contentType().contains("json") || a.body().isEmpty()) {
        continue;
      }
      json++;
      List<String> found = AdminMvc.secretLookingValues(a.json());
      boolean issuing = a.method().equals("POST") && a.target().matches("/api/endpoints/[^/]+/tokens")
          && a.status() == 201;
      // Electron main's one-time file grant is a capability for one save, not a stored secret.
      boolean fileGrant = a.method().equals("POST") && a.target().equals("/internal/files/grant");
      if (issuing || fileGrant) {
        // The answers that show a token: its value, nothing else.
        assertThat(found).as(a.target()).containsExactly("/token");
      } else if (!found.isEmpty()) {
        problems.add(a.method() + " " + a.target() + " has " + found);
      }
    }
    assertThat(json).as("the scan saw the answers").isGreaterThan(100);
    assertThat(problems).isEmpty();
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

  /** {@code field} of each element of {@code array}, not of what they hold (unlike {@code findValuesAsString}). */
  private static List<String> each(JsonNode array, String field) {
    List<String> out = new ArrayList<>();
    array.forEach(e -> out.add(e.path(field).asString()));
    return out;
  }

  private static JsonNode group(JsonNode catalog, String name) {
    for (JsonNode g : catalog.path("groups")) {
      if (g.path("name").asString().equals(name)) {
        return g;
      }
    }
    throw new AssertionError("no group " + name + " in " + catalog);
  }

  private static JsonNode card(JsonNode group, String type) {
    for (JsonNode c : group.path("cards")) {
      if (c.path("type").asString().equals(type)) {
        return c;
      }
    }
    throw new AssertionError("no card " + type + " in " + group);
  }

  private static JsonNode installedType(JsonNode catalog, String id) {
    for (JsonNode t : catalog.path("installed")) {
      if (t.path("id").asString().equals(id)) {
        return t;
      }
    }
    throw new AssertionError("no installed type " + id);
  }

  private static JsonNode tool(JsonNode group, String name) {
    for (JsonNode t : group.path("tools")) {
      if (t.path("name").asString().equals(name)) {
        return t;
      }
    }
    throw new AssertionError("no tool " + name + " in " + group);
  }

  private static JsonNode endpoint(JsonNode endpoints, String slug) {
    for (JsonNode endpoint : endpoints) {
      if (endpoint.path("slug").asString().equals(slug)) {
        return endpoint;
      }
    }
    throw new AssertionError("no endpoint " + slug);
  }

  private static JsonNode playgroundTool(JsonNode tools, String name) {
    for (JsonNode t : tools.path("tools")) {
      if (t.path("name").asString().equals(name)) {
        return t;
      }
    }
    throw new AssertionError("no tool " + name + " in " + tools);
  }

  private static JsonNode step(JsonNode steps, String id) {
    for (JsonNode s : steps) {
      if (s.path("id").asString().equals(id)) {
        return s;
      }
    }
    throw new AssertionError("no step " + id);
  }

  private static JsonNode node(JsonNode overview, String id) {
    for (JsonNode n : overview.path("setupMap").path("nodes")) {
      if (n.path("id").asString().equals(id)) {
        return n;
      }
    }
    throw new AssertionError("no node " + id);
  }

  private static Optional<EventBus.GatewayEvent> next(EventBus.Subscription sub, String type) {
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (System.nanoTime() < deadline) {
      try {
        Optional<EventBus.GatewayEvent> e = sub.next(Duration.ofMillis(200));
        if (e.isPresent() && e.get().type().equals(type)) {
          return e;
        }
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        return Optional.empty();
      }
    }
    return Optional.empty();
  }
}
