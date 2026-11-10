package ai.msrv.gaterook.core.endpoints;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.core.admin.AdminMvc;
import ai.msrv.gaterook.core.connections.ConnectionConfigs;
import ai.msrv.gaterook.core.connections.ConnectionService;
import ai.msrv.gaterook.core.connections.ConnectionTester;
import ai.msrv.gaterook.core.platform.LocalListener;
import ai.msrv.gaterook.core.platform.MaskPolicy;
import ai.msrv.gaterook.core.platform.Masker;
import ai.msrv.gaterook.core.platform.ProblemException;
import ai.msrv.gaterook.core.platform.Taint;
import ai.msrv.gaterook.core.secrets.SecretService;
import ai.msrv.gaterook.core.templates.TemplateService;
import ai.msrv.gaterook.testfixtures.FakeConnectors;
import ai.msrv.gaterook.testfixtures.TestCore;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Real storage, encryption, endpoint row MACs and transaction rollback without external services. */
class EndpointShareIT {
  private static TestCore core;
  private static EndpointShareService shares;
  private static EndpointService endpoints;
  private static EndpointConnections wiring;

  @BeforeAll static void start() {
    core = TestCore.start("endpoint-share");
    shares = core.bean(EndpointShareService.class);
    endpoints = core.bean(EndpointService.class);
    wiring = core.bean(EndpointConnections.class);
  }
  @AfterAll static void stop() { if (core != null) core.close(); }

  private static ObjectNode source(String prefix, boolean secrets) {
    core.connection(prefix + "-one", "pgfake", Env.DEV, "db.example.test");
    core.connection(prefix + "-two", "pgfake", Env.DEV, "other.example.test");
    endpoints.create(prefix, "Shared endpoint", EndpointService.StartFrom.EMPTY, "test");
    wiring.add(prefix, prefix + "-one", "test");
    wiring.add(prefix, prefix + "-two", "test");
    return shares.export(prefix, List.of(prefix + "-one", prefix + "-two"), secrets);
  }

  /** What the person trusted for {@code hostkey.test} on this computer, as Test connection's Trust records it. */
  private static final String TRUSTED_KEY = "SHA256:Q2xMjfU8pNt8fNl3a9vrZ0rDcoAPeExDQ4l0Tf3bAvo";
  private static final String TRUST_ITEM = "/connection/hostKey: Not imported. Confirm this trust setting on this "
      + "computer with Test connection, or set it yourself.";

  /**
   * The fake's host key ({@code /connection/hostKey}) is a trust setting: Test connection asks, the person trusts, and
   * the key is the source's own decision. A share leaves it out, and one that carries it (an older GateRook wrote it,
   * or anyone who edits the file) creates a disabled draft without it, through the window's admin API.
   */
  @Test void aTrustedHostKeyStaysOnThisComputerAndAShareCarryingOneImportsAsADraftThatTrustsNothing() {
    core.connection("trust-share-one", "pgfake", Env.DEV, "hostkey.test");
    ConnectionTester tester = core.bean(ConnectionTester.class);
    ConnectionTester.Outcome asked = tester.testStored("trust-share-one");
    assertThat(asked.confirmationId()).as("Test connection asks to trust the host key").isNotNull();
    tester.confirm(asked.confirmationId(), "Trusted on the source computer");
    assertThat(core.bean(ConnectionConfigs.class).byName("trust-share-one").orElseThrow().config()
        .string("/connection/hostKey")).isEqualTo(TRUSTED_KEY);
    endpoints.create("trust-share", null, EndpointService.StartFrom.EMPTY, "test");
    wiring.add("trust-share", "trust-share-one", "test");
    AdminMvc admin = new AdminMvc(core.context(), core.bean(LocalListener.class).port());

    ObjectNode export = TestCore.JSON.createObjectNode().put("endpointSlug", "trust-share").put("includeSecrets", true);
    export.putArray("connectionNames").add("trust-share-one");
    JsonNode bundle = admin.expect(200, HttpMethod.POST, "/internal/endpoint-shares/export", export);

    JsonNode entry = bundle.path("connections").get(0);
    assertThat(bundle.toString()).doesNotContain(TRUSTED_KEY);
    assertThat(entry.at("/config/connection/host").asString()).isEqualTo("hostkey.test");
    assertThat(entry.at("/config/connection").has("hostKey")).isFalse();
    assertThat(entry.path("secrets").has("/credentials/password")).isTrue();
    assertThat(entry.path("warnings").toString()).contains("Trust settings were omitted (/connection/hostKey); "
        + "confirm them on the destination computer with Test connection.");

    // An older GateRook exported the key; whoever edits the file can write one. Its own warnings say nothing.
    ObjectNode older = (ObjectNode) bundle.deepCopy();
    ObjectNode olderEntry = (ObjectNode) older.path("connections").get(0);
    ((ObjectNode) olderEntry.path("config").path("connection")).put("hostKey", TRUSTED_KEY);
    olderEntry.putArray("warnings");
    ObjectNode inspect = TestCore.JSON.createObjectNode();
    inspect.set("bundle", older);
    JsonNode preview = admin.expect(200, HttpMethod.POST, "/internal/endpoint-shares/preview", inspect);
    JsonNode row = preview.path("connections").get(0);
    assertThat(preview.toString()).doesNotContain(TRUSTED_KEY);
    assertThat(row.at("/config/connection/host").asString()).isEqualTo("hostkey.test");
    assertThat(row.at("/config/connection").has("hostKey")).isFalse();
    assertThat(row.path("warnings").toString()).contains("Trust settings in this file are not imported: "
        + "/connection/hostKey. The connection is imported off, with Setup required, until you confirm them on this "
        + "computer with Test connection.");

    ObjectNode request = inspect.deepCopy().put("includeEndpoint", true).put("includeSecrets", true)
        .put("endpointSlug", "trust-share-copy");
    request.putArray("connectionIds").add("trust-share-one");
    JsonNode imported = admin.expect(200, HttpMethod.POST, "/internal/endpoint-shares/import", request);
    assertThat(imported.path("connections").get(0).path("needsSetup").asBoolean()).isTrue();
    String name = imported.path("connections").get(0).path("name").asString();
    JsonNode copy = admin.ok("/api/connections/" + name);
    assertThat(copy.path("setupRequired").asBoolean()).isTrue();
    assertThat(copy.path("enabled").asBoolean()).isFalse();
    assertThat(copy.at("/config/connection/host").asString()).isEqualTo("hostkey.test");
    assertThat(copy.at("/config/connection").has("hostKey")).isFalse();
    assertThat(copy.path("setupMissingFields")).anyMatch(item -> item.asString().equals(TRUST_ITEM));
    // The included credential is kept for the person's own Test connection.
    assertThat(copy.path("secrets"))
        .anyMatch(secret -> secret.path("field").asString().equals("/credentials/password"));
    assertThat(admin.answers()).noneMatch(answer -> answer.body() != null && answer.body().contains(TRUSTED_KEY));
    assertThat(endpoints.snapshot("trust-share-copy").pairs()).noneMatch(pair -> pair.config().name().equals(name));

    // The file as GateRook exports it now carries no key: the copy is complete, and trusts nothing of the source.
    ObjectNode current = TestCore.JSON.createObjectNode();
    current.set("bundle", bundle);
    ObjectNode plain = current.deepCopy().put("includeEndpoint", false).put("includeSecrets", true);
    plain.putArray("connectionIds").add("trust-share-one");
    JsonNode complete = admin.expect(200, HttpMethod.POST, "/internal/endpoint-shares/import", plain);
    assertThat(complete.path("connections").get(0).path("needsSetup").asBoolean()).isFalse();
    JsonNode fresh = admin.ok("/api/connections/" + complete.path("connections").get(0).path("name").asString());
    assertThat(fresh.at("/config/connection").has("hostKey")).isFalse();
    assertThat(fresh.path("setupRequired").asBoolean()).isFalse();
  }

  /**
   * A trust setting counts by being there, whatever it holds, and a share entry that carries one never reaches a test:
   * a production entry is not checked for dangerous rights before it is a draft, so nothing contacts its resource.
   */
  @Test void anEntryCarryingATrustSettingOfAnyValueBecomesADraftWithoutAnyResourceCheck() {
    ObjectNode bundle = source("trust-value-share", true);
    ObjectNode template = (ObjectNode) bundle.path("connections").get(0);
    ArrayNode entries = bundle.putArray("connections");
    List<String> values = List.of("null", "false", "\"\"", "{}", "[]", "\"" + TRUSTED_KEY + "\"");
    for (int i = 0; i < values.size(); i++) {
      ObjectNode entry = template.deepCopy().put("id", "value-" + i).put("name", "trust-value-" + i);
      ((ObjectNode) entry.path("config").path("connection")).set("hostKey", TestCore.json(values.get(i)));
      entries.add(entry);
    }
    ObjectNode prod = template.deepCopy().put("id", "prod").put("name", "trust-value-prod").put("env", "prod");
    ((ObjectNode) prod.path("config").path("connection")).put("host", "danger-one.test").put("hostKey", TRUSTED_KEY);
    entries.add(prod);
    int probes = FakeConnectors.DANGER_TESTS.size();
    List<String> ids = List.of("value-0", "value-1", "value-2", "value-3", "value-4", "value-5", "prod");

    JsonNode imported = shares.importBundle(bundle, ids, false, true, null, "test");

    assertThat(imported.path("connections")).hasSize(ids.size())
        .allMatch(connection -> connection.path("needsSetup").asBoolean());
    for (JsonNode connection : imported.path("connections")) {
      var stored = core.bean(ConnectionConfigs.class).byId(connection.path("id").asString()).orElseThrow();
      assertThat(stored.config().config().at("/connection").has("hostKey")).as(stored.name()).isFalse();
      assertThat(stored.enabled()).as(stored.name()).isFalse();
      assertThat(stored.config().metadata().path("setupMissingFields").toString()).as(stored.name())
          .contains(TRUST_ITEM);
    }
    assertThat(FakeConnectors.DANGER_TESTS).as("no production rights test ran").hasSize(probes);
  }

  @Test void exportOnlyIncludesSelectedConnectionsAndSecretsAreOptIn() {
    source("select-share", false);
    JsonNode bundle = shares.export("select-share", List.of("select-share-one"), false);
    assertThat(bundle.path("connections")).hasSize(1);
    assertThat(bundle.toString()).doesNotContain("pw-select-share", "select-share-two", "$secret", "masterKey", "clientGrants");
    assertThat(bundle.path("connections").get(0).path("secrets")).isEmpty();
    assertThat(bundle.path("connections").get(0).path("omittedSecretFields").toString()).contains("/credentials/password");
    assertThatThrownBy(() -> shares.export("select-share", List.of("not-on-endpoint"), true)).isInstanceOf(ProblemException.class);
  }

  @Test void previewHidesValuesAndImportCreatesReadyEnabledCopiesWithFreshSecretOwners() throws Exception {
    ObjectNode bundle = source("copy-share", true);
    endpoints.disable("copy-share", "Keep the existing endpoint paused");
    assertThat(bundle.path("connections").get(0).path("omittedSecretFields")).isEmpty();
    JsonNode preview = shares.preview(bundle);
    assertThat(preview.toString()).doesNotContain("pw-copy-share", "$secret");
    assertThat(preview.path("connections").get(0).path("secretCount").asInt()).isEqualTo(1);
    assertThat(preview.path("suggestedSlug").asString()).isEqualTo("copy-share-2");
    int probes = FakeConnectors.DANGER_TESTS.size();
    JsonNode imported = shares.importBundle(bundle, List.of("copy-share-one"), true, true, null, "test");
    assertThat(imported.path("endpointSlug").asString()).isEqualTo("copy-share-2");
    assertThat(imported.path("connections")).hasSize(1);
    String name = imported.path("connections").get(0).path("name").asString();
    assertThat(name).isEqualTo("copy-share-one-2");
    var destination = endpoints.snapshot("copy-share-2");
    assertThat(destination.enabled()).isTrue();
    assertThat(endpoints.snapshot("copy-share").enabled()).isFalse();
    assertThat(destination.oauthEnabled()).isFalse();
    assertThat(destination.published()).isFalse();
    assertThat(destination.blocked()).isFalse();
    assertThat(destination.connections()).hasSize(1);
    var copied = core.bean(ConnectionConfigs.class).byName(name).orElseThrow();
    assertThat(copied.enabled()).isTrue();
    assertThat(copied.verified()).isTrue();
    assertThat(ConnectionService.needsSetup(copied.config())).isFalse();
    assertThat(imported.path("connections").get(0).path("needsSetup").asBoolean()).isFalse();
    assertThat(destination.pairs()).anyMatch(pair -> pair.config().name().equals(name)
        && pair.tool().name().equals("sql_query") && pair.mode() == Mode.READ_ONLY);
    assertThat(copied.id()).isNotEqualTo(core.bean(ConnectionConfigs.class).byName("copy-share-one").orElseThrow().id());
    Taint taint = new Taint();
    try (var resolver = core.bean(SecretService.class).resolver(copied.config(), taint); var secret = resolver.secret("/credentials/password")) {
      assertThat(secret.unsafeString()).isEqualTo("pw-copy-share-one-0123");
    } finally { taint.clear(); }
    assertThat(FakeConnectors.DANGER_TESTS).hasSize(probes);
    assertThat(core.jdbc().queryForObject("select count(*) from api_token where endpoint_id = ?", Integer.class, destination.id())).isZero();
  }

  @Test void recipientCanSkipEndpointAndAllSecrets() {
    ObjectNode bundle = source("skip-share", true);
    JsonNode imported = shares.importBundle(bundle, List.of("skip-share-two"), false, false, null, "test");
    assertThat(imported.path("endpointSlug").isNull()).isTrue();
    assertThat(imported.path("connections")).hasSize(1);
    var copied = core.bean(ConnectionConfigs.class).byName("skip-share-two-2").orElseThrow();
    assertThat(copied.secretIds()).isEmpty();
    assertThat(copied.enabled()).isFalse();
    assertThat(ConnectionService.needsSetup(copied.config())).isTrue();
    assertThat(imported.path("connections").get(0).path("needsSetup").asBoolean()).isTrue();
    assertThat(core.bean(ConnectionConfigs.class).byName("skip-share-one-2")).isEmpty();
    assertThat(endpoints.list()).noneMatch(e -> e.slug().equals("skip-share-2"));
  }

  @Test void completeAndIncompleteConnectionsImportTogetherWithoutBlockingReadyTools() {
    ObjectNode bundle = source("mixed-setup-share", true);
    ((ObjectNode) bundle.path("connections").get(1).path("config").path("credentials")).remove("user");
    JsonNode imported = shares.importBundle(bundle, List.of("mixed-setup-share-one", "mixed-setup-share-two"),
        true, true, "mixed-setup-share-copy", "test");
    assertThat(imported.path("connections").get(0).path("needsSetup").asBoolean()).isFalse();
    assertThat(imported.path("connections").get(1).path("needsSetup").asBoolean()).isTrue();
    var destination = endpoints.snapshot("mixed-setup-share-copy");
    assertThat(destination.enabled()).isTrue();
    assertThat(destination.pairs()).anyMatch(pair -> pair.config().name().equals("mixed-setup-share-one-2"))
        .noneMatch(pair -> pair.config().name().equals("mixed-setup-share-two-2"));
    var incomplete = core.bean(ConnectionConfigs.class).byName("mixed-setup-share-two-2").orElseThrow();
    assertThat(incomplete.enabled()).isFalse();
    assertThat(incomplete.secretIds()).containsKey("/credentials/password");
    assertThat(incomplete.config().metadata().path("setupMissingFields").toString()).contains("user")
        .doesNotContain("Review imported settings and credentials");
  }

  @Test void productionWarningsAreReviewedTogetherBeforeAnyCopiesAreWritten() {
    ObjectNode bundle = source("prod-review-share", true);
    int index = 0;
    for (JsonNode entry : bundle.path("connections")) {
      ((ObjectNode) entry).put("env", "prod");
      ((ObjectNode) entry.path("config").path("connection"))
          .put("host", index++ == 0 ? "danger-one.test" : "danger-two.test");
    }
    int count = core.bean(ConnectionConfigs.class).all().size();
    assertThatThrownBy(() -> shares.importBundle(bundle, List.of("prod-review-share-one", "prod-review-share-two"),
        true, true, "prod-review-share-copy", "test"))
        .isInstanceOfSatisfying(ProblemException.class, problem -> {
          assertThat(problem.confirm()).isEqualTo(ConnectionService.CONFIRM_DANGER);
          assertThat(problem.errors()).hasSize(2);
          assertThat(problem.getMessage()).contains("prod-review-share-one-2", "prod-review-share-two-2")
              .doesNotContain("pw-prod-review");
        });
    assertThat(core.bean(ConnectionConfigs.class).all()).hasSize(count);
    assertThat(endpoints.list()).noneMatch(endpoint -> endpoint.slug().equals("prod-review-share-copy"));
    JsonNode imported = shares.importBundle(bundle, List.of("prod-review-share-one", "prod-review-share-two"),
        true, true, "prod-review-share-copy", "test", ConnectionService.CONFIRM_DANGER);
    assertThat(imported.path("connections")).allMatch(entry -> !entry.path("needsSetup").asBoolean());
    assertThat(endpoints.snapshot("prod-review-share-copy").enabled()).isTrue();
    assertThat(endpoints.snapshot("prod-review-share-copy").connections()).allMatch(connection ->
        core.bean(ConnectionConfigs.class).byId(connection.config().id()).orElseThrow().enabled());
  }

  @Test void bypassAndUntrustedReadModesNeverBecomeImplicitApprovalGrants() {
    ObjectNode bundle = source("mode-share", false);
    JsonNode tools = bundle.path("connections").get(0).path("tools");
    for (JsonNode tool : tools) if (tool.path("toolKey").asString().equals("sql_execute")) ((ObjectNode) tool).put("mode", "BYPASS");
    shares.importBundle(bundle, List.of("mode-share-one"), true, false, "mode-share-copy", "test");
    assertThat(core.jdbc().queryForObject("select mode from endpoint_tool where connection_id = ? and tool_key = 'sql_execute'", String.class,
        core.bean(ConnectionConfigs.class).byName("mode-share-one-2").orElseThrow().id())).isEqualTo("APPROVAL");
    for (JsonNode tool : tools) if (tool.path("toolKey").asString().equals("sql_execute")) ((ObjectNode) tool).put("mode", "READ_ONLY");
    shares.importBundle(bundle, List.of("mode-share-one"), true, false, "mode-share-copy-two", "test");
    assertThat(core.jdbc().queryForObject("select mode from endpoint_tool where connection_id = ? and tool_key = 'sql_execute'", String.class,
        core.bean(ConnectionConfigs.class).byName("mode-share-one-3").orElseThrow().id())).isEqualTo("APPROVAL");
  }

  @Test void malformedSelectionsSettingsAndSecretPlaceholdersAreRejectedBeforeChanges() {
    ObjectNode bundle = source("invalid-share", false);
    int count = core.bean(ConnectionConfigs.class).all().size();
    assertThatThrownBy(() -> shares.importBundle(bundle, List.of("missing"), true, false, null, "test")).isInstanceOf(ProblemException.class);
    assertThatThrownBy(() -> shares.importBundle(bundle, List.of(), false, false, null, "test")).isInstanceOf(ProblemException.class);
    ObjectNode config = (ObjectNode) bundle.path("connections").get(0).path("config");
    config.putObject("unexpected").put("$secret", "secret-from-another-user");
    assertThatThrownBy(() -> shares.preview(bundle)).isInstanceOf(ProblemException.class);
    assertThat(core.bean(ConnectionConfigs.class).all()).hasSize(count);
    config.remove("unexpected");
    config.put("privateKeyPath", "/private/not-importable");
    assertThatThrownBy(() -> shares.importBundle(bundle, List.of("invalid-share-one"), true, true, null, "test")).isInstanceOf(ProblemException.class);
    assertThat(core.bean(ConnectionConfigs.class).all()).hasSize(count);
  }

  @Test void productionMixingRollsBackEveryNewConnectionAndEndpoint() {
    ObjectNode bundle = source("atomic-share", true);
    ((ObjectNode) bundle.path("connections").get(1)).put("env", "prod");
    int count = core.bean(ConnectionConfigs.class).all().size();
    assertThatThrownBy(() -> shares.importBundle(bundle, List.of("atomic-share-one", "atomic-share-two"), true, true,
        "atomic-share-copy", "test")).isInstanceOf(ProblemException.class);
    assertThat(core.bean(ConnectionConfigs.class).all()).hasSize(count);
    assertThat(endpoints.list()).noneMatch(e -> e.slug().equals("atomic-share-copy"));
  }

  @Test void aTemplateCompilationFailureRollsBackReadyConnectionsAndTheEnabledEndpoint() {
    ObjectNode bundle = source("compile-failure-share", true);
    ((ObjectNode) bundle.path("connections").get(1)).putArray("templates").addObject()
        .put("id", "broken-template").put("name", "broken_query").put("kind", "sql-query")
        .putObject("definition").put("name", "broken_query");
    int count = core.bean(ConnectionConfigs.class).all().size();
    assertThatThrownBy(() -> shares.importBundle(bundle, List.of("compile-failure-share-one", "compile-failure-share-two"),
        true, true, "compile-failure-share-copy", "test")).isInstanceOf(ProblemException.class);
    assertThat(core.bean(ConnectionConfigs.class).all()).hasSize(count);
    assertThat(endpoints.list()).noneMatch(endpoint -> endpoint.slug().equals("compile-failure-share-copy"));
    assertThat(core.bean(ConnectionConfigs.class).byName("compile-failure-share-one-2")).isEmpty();
  }

  @Test void sqlTemplatesAreCopiedAndToolKeysUseNewIds() {
    source("template-share", false);
    var templates = core.bean(TemplateService.class);
    var original = templates.create("sql-query", "template-share-one", null,
        TestCore.json("{\"name\":\"shared_query\",\"sql\":\"select 1\"}"), null, "test").template();
    TestCore.await(() -> endpoints.snapshot("template-share").pairs().stream()
        .anyMatch(p -> p.tool().name().equals("shared_query") && p.rowId() != null), "template row");
    ObjectNode bundle = shares.export("template-share", List.of("template-share-one"), false);
    assertThat(bundle.path("connections").get(0).path("templates")).hasSize(1);
    JsonNode imported = shares.importBundle(bundle, List.of("template-share-one"), true, false, "template-share-copy", "test");
    String connectionId = imported.path("connections").get(0).path("id").asString();
    var copied = templates.list(null).stream().filter(t -> connectionId.equals(t.connectionId())).findFirst().orElseThrow();
    assertThat(copied.id()).isNotEqualTo(original.id());
    assertThat(templates.definition(copied.id()).orElseThrow().path("sql").asString()).isEqualTo("select 1");
    var keys = core.jdbc().queryForList("select tool_key from endpoint_tool where connection_id = ?", String.class, connectionId);
    assertThat(keys).anyMatch(k -> k.startsWith("tpl:" + copied.id() + ":")).noneMatch(k -> k.contains(original.id()));
    assertThat(core.jdbc().queryForList("select mode from endpoint_tool where connection_id = ? and tool_key like 'tpl:%'", String.class, connectionId))
        .containsOnly("APPROVAL");
  }

  @Test void sourceIntegrityCannotBeLaunderedThroughExport() {
    source("integrity-share", false);
    core.jdbc().update("update connection set name = 'integrity-share-tampered' where name = 'integrity-share-one'");
    assertThatThrownBy(() -> shares.export("integrity-share", List.of("integrity-share-tampered"), true)).isInstanceOf(ProblemException.class);
  }

  @Test void installedPluginSettingsTransferWithoutItsCodeOrReadOnlyConfirmation() {
    core.connection("plugin-share-source", "jirafake", Env.DEV, "jira.example.test");
    endpoints.create("plugin-share", null, EndpointService.StartFrom.EMPTY, "test");
    wiring.add("plugin-share", "plugin-share-source", "test");
    ObjectNode bundle = shares.export("plugin-share", List.of("plugin-share-source"), true);
    assertThat(shares.preview(bundle).path("connections").get(0).path("supported").asBoolean()).isTrue();
    var imported = shares.importBundle(bundle, List.of("plugin-share-source"), true, true, "plugin-share-copy", "test");
    String id = imported.path("connections").get(0).path("id").asString();
    assertThat(core.jdbc().queryForList("select tool_key from endpoint_tool where connection_id = ?", String.class, id))
        .anyMatch(k -> k.startsWith("plugin:jirafake:"));
    assertThat(core.jdbc().queryForList("select mode from endpoint_tool where connection_id = ?", String.class, id)).doesNotContain("READ_ONLY", "BYPASS");
    assertThat(core.bean(ConnectionConfigs.class).byId(id).orElseThrow().enabled()).isTrue();
  }

  @Tag("isolated-secret-fingerprints")
  @Test void secretFingerprintsCannotRenamePreviewProtocolKeysOrSelectionIdentifiers() {
    assertThat(Masker.standard().text("connections", new Taint(), MaskPolicy.SECRETS_ONLY).text())
        .as("the isolated process has not learned the protocol word").isEqualTo("connections");
    core.bean(ConnectionService.class).create("protocol-key-share", "pgfake", Env.DEV,
        TestCore.config("db.example.test"), java.util.Map.of("/credentials/password",
            ai.msrv.gaterook.core.connections.SecretInput.local("connections".toCharArray())), "test");
    endpoints.create("protocol-key-endpoint", null, EndpointService.StartFrom.EMPTY, "test");
    wiring.add("protocol-key-endpoint", "protocol-key-share", "test");
    ObjectNode bundle = shares.export("protocol-key-endpoint", List.of("protocol-key-share"), true);
    assertThat(Masker.standard().text("connections", new Taint(), MaskPolicy.SECRETS_ONLY).text())
        .as("resolved secrets remain masked by the process for other calls").isEqualTo(Masker.MASK);
    JsonNode preview = shares.preview(bundle);
    assertThat(preview.path("connections")).hasSize(1);
    assertThat(preview.path("connections").get(0).path("id").asString()).isEqualTo("protocol-key-share");
    assertThat(preview.path("connections").get(0).path("supported").asBoolean()).isTrue();
    assertThat(preview.path("endpoint").path("slug").asString()).isEqualTo("protocol-key-endpoint");
  }

  @Test void anUnavailablePluginWithTemplatesCanBeSkippedWithoutBlockingOtherConnections() {
    ObjectNode bundle = source("unavailable-share", false);
    ObjectNode unavailable = (ObjectNode) bundle.path("connections").get(1);
    unavailable.put("type", "uninstalled-plugin");
    unavailable.putArray("templates").addObject().put("id", "foreign-template").put("name", "foreign-query")
        .put("kind", "sql-query").putObject("definition").put("name", "foreign-query").put("sql", "select 1");
    JsonNode preview = shares.preview(bundle);
    assertThat(preview.path("connections").get(1).path("supported").asBoolean()).isFalse();
    assertThatThrownBy(() -> shares.importBundle(bundle, List.of("unavailable-share-two"), false, false, null, "test"))
        .isInstanceOf(ProblemException.class);
    JsonNode imported = shares.importBundle(bundle, List.of("unavailable-share-one"), true, false, "unavailable-share-copy", "test");
    assertThat(imported.path("connections")).hasSize(1);
    assertThat(core.bean(ConnectionConfigs.class).byName("unavailable-share-two-2")).isEmpty();
  }
}
