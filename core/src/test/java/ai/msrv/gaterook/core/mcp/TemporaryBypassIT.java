package ai.msrv.gaterook.core.mcp;

import static ai.msrv.gaterook.core.mcp.McpClient.args;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.core.approvals.ApprovalService;
import ai.msrv.gaterook.core.connections.ConnectionService;
import ai.msrv.gaterook.core.endpoints.EndpointConnections;
import ai.msrv.gaterook.core.endpoints.EndpointService;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshot;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshotService;
import ai.msrv.gaterook.core.endpoints.EndpointTools;
import ai.msrv.gaterook.core.endpoints.TokenService;
import ai.msrv.gaterook.core.endpoints.internal.EndpointRows;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointToolRepository;
import ai.msrv.gaterook.core.invocation.ConnectorInvoker;
import ai.msrv.gaterook.core.mcp.internal.ToolCallHandler;
import ai.msrv.gaterook.core.platform.LocalListener;
import ai.msrv.gaterook.core.platform.ProblemException;
import ai.msrv.gaterook.testfixtures.FakeConnectors;
import ai.msrv.gaterook.testfixtures.TestCore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Timed grants remain scoped, signed, persistent, and subject to policy and exact expiry. */
class TemporaryBypassIT {

  private static final Instant NOW = Instant.now();
  private static final MutableClock CLOCK = new MutableClock(NOW);
  private static TestCore core;
  private static EndpointService endpoints;
  private static EndpointTools tools;
  private static EndpointSnapshotService snapshots;

  @BeforeAll
  static void start() {
    core = TestCore.start("temporary-bypass");
    endpoints = core.bean(EndpointService.class);
    tools = core.bean(EndpointTools.class);
    snapshots = core.bean(EndpointSnapshotService.class);
    ReflectionTestUtils.setField(tools, "clock", CLOCK);
    ReflectionTestUtils.setField(snapshots, "clock", CLOCK);
  }

  @BeforeEach
  void resetClock() {
    CLOCK.now = NOW;
    snapshots.invalidateAll();
  }

  @AfterAll
  static void stop() {
    if (core != null) core.close();
  }

  @Test
  void allSixDurationsWorkOnDevAndStageAndKeepTheOriginalMode() {
    for (Env env : List.of(Env.DEV, Env.STAGE)) {
      String slug = "durations-" + env.id();
      create(slug, env);
      for (int minutes : List.of(15, 30, 60, 180, 720, 1440)) {
        grant(slug, minutes);
        var pair = pair(slug);
        assertThat(pair.mode()).isEqualTo(Mode.BYPASS);
        assertThat(pair.storedMode()).isEqualTo(Mode.APPROVAL);
        assertThat(pair.bypassUntil()).isEqualTo(NOW.plus(Duration.ofMinutes(minutes)));
        assertThat(row(slug).bypassUntil()).isEqualTo(pair.bypassUntil());
        assertThat(row(slug).temporaryBypassAllowed()).isTrue();
        tools.revokeTemporaryBypass(slug, "sql_execute", connection(slug), pair.rowVersion(), "End test grant");
        assertThat(pair(slug).mode()).isEqualTo(Mode.APPROVAL);
        assertThat(pair(slug).bypassUntil()).isNull();
      }
    }
  }

  @Test
  void expiryInvalidatesBothSnapshotLookupPathsAtTheExactDeadline() {
    create("expire-slug", Env.DEV);
    create("expire-id", Env.STAGE);
    grant("expire-slug", 15);
    grant("expire-id", 15);
    String id = endpoints.snapshot("expire-id").id();
    CLOCK.now = NOW.plus(Duration.ofMinutes(15)).minusNanos(1);
    assertThat(pair("expire-slug").mode()).isEqualTo(Mode.BYPASS);
    assertThat(snapshots.get(id).orElseThrow().pairs()).anyMatch(p -> p.mode() == Mode.BYPASS);

    CLOCK.now = NOW.plus(Duration.ofMinutes(15));
    assertThat(pair("expire-slug").mode()).isEqualTo(Mode.APPROVAL);
    assertThat(pair("expire-slug").bypassUntil()).isNull();
    assertThat(snapshots.get(id).orElseThrow().pairs()).noneMatch(p -> p.mode() == Mode.BYPASS);
    assertThat(row("expire-id").bypassUntil()).isNull();
  }

  @Test
  void grantsApplyOnlyToTheChosenEndpointConnectionAndTool() {
    create("scope-one", Env.DEV);
    core.connection("scope-other-db", "pgfake", Env.DEV, "db.test");
    core.bean(EndpointConnections.class).add("scope-one", "scope-other-db", null);
    endpoints.create("scope-two", null, EndpointService.StartFrom.EMPTY, null);
    core.bean(EndpointConnections.class).add("scope-two", connection("scope-one"), null);
    grant("scope-one", 30);

    assertThat(endpoints.snapshot("scope-one").pairs().stream().filter(p -> p.mode() == Mode.BYPASS))
        .singleElement().satisfies(p -> {
          assertThat(p.connectionName()).isEqualTo(connection("scope-one"));
          assertThat(p.toolKey()).isEqualTo("sql_execute");
        });
    assertThat(endpoints.snapshot("scope-two").pairs()).noneMatch(p -> p.mode() == Mode.BYPASS);
    endpoints.duplicate("scope-one", "scope-copy", "Copy settings");
    assertThat(endpoints.snapshot("scope-copy").pairs()).noneMatch(p -> p.mode() == Mode.BYPASS);
  }

  @Test
  void invalidDurationsProductionReadOnlyAndCoreToolsAreRefused() {
    create("invalid-time", Env.DEV);
    for (int minutes : List.of(-1, 0, 1, 14, 16, 45, 1441, Integer.MAX_VALUE)) {
      assertThatThrownBy(() -> grant("invalid-time", minutes)).isInstanceOf(ProblemException.class);
    }
    assertThat(pair("invalid-time").mode()).isEqualTo(Mode.APPROVAL);
    create("invalid-prod", Env.PROD);
    assertThat(row("invalid-prod").temporaryBypassAllowed()).isFalse();
    assertThatThrownBy(() -> grant("invalid-prod", 15)).isInstanceOf(ProblemException.class);
    create("invalid-readonly", Env.DEV);
    endpoints.updateSettings("invalid-readonly", new EndpointService.SettingsUpdate(null, "read-only", null,
        null, null, null, null), endpoints.get("invalid-readonly").version(), "Make read only");
    assertThat(row("invalid-readonly").temporaryBypassAllowed()).isFalse();
    assertThatThrownBy(() -> grant("invalid-readonly", 15)).isInstanceOf(ProblemException.class);
    var coreTool = endpoints.snapshot("invalid-time").coreTool("list_connections").orElseThrow();
    assertThatThrownBy(() -> tools.setTemporaryBypass("invalid-time", "list_connections", null, 15,
        coreTool.rowVersion(), "Invalid scope")).isInstanceOf(ProblemException.class);
  }

  @Test
  void explicitModeChangesAndBulkResetClearGrantsEvenWhenTheBaseModeIsUnchanged() {
    create("mode-reset", Env.DEV);
    grant("mode-reset", 60);
    tools.setMode("mode-reset", "sql_execute", connection("mode-reset"), Mode.APPROVAL, null,
        pair("mode-reset").rowVersion(), "Keep approval");
    assertThat(pair("mode-reset").mode()).isEqualTo(Mode.APPROVAL);
    assertThat(pair("mode-reset").bypassUntil()).isNull();
    grant("mode-reset", 60);
    tools.bulk("mode-reset", connection("mode-reset"), "APPROVAL", "Require approval for all tools");
    assertThat(pair("mode-reset").mode()).isEqualTo(Mode.APPROVAL);
    assertThat(pair("mode-reset").bypassUntil()).isNull();
    grant("mode-reset", 60);
    tools.setMode("mode-reset", "sql_execute", connection("mode-reset"), Mode.OFF, null,
        pair("mode-reset").rowVersion(), "Turn off");
    assertThat(pair("mode-reset").mode()).isEqualTo(Mode.OFF);
    assertThat(pair("mode-reset").bypassUntil()).isNull();
  }

  @Test
  void staleWritesCannotReplaceOrRevokeAChangedGrant() {
    create("stale-grant", Env.DEV);
    long stale = pair("stale-grant").rowVersion();
    grant("stale-grant", 15);
    assertThatThrownBy(() -> tools.setTemporaryBypass("stale-grant", "sql_execute", connection("stale-grant"),
        1440, stale, "Stale extension")).isInstanceOf(OptimisticLockingFailureException.class);
    assertThatThrownBy(() -> tools.revokeTemporaryBypass("stale-grant", "sql_execute", connection("stale-grant"),
        stale, "Stale revocation")).isInstanceOf(OptimisticLockingFailureException.class);
    assertThat(pair("stale-grant").bypassUntil()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
  }

  @Test
  void changingConnectionOrEndpointSettingsInvalidatesTheReviewedGrant() {
    create("config-change", Env.DEV);
    grant("config-change", 60);
    ConnectionService connections = core.bean(ConnectionService.class);
    var config = TestCore.config("db.test");
    config.withObject("/connection").put("note", "Updated configuration");
    connections.update(connection("config-change"), Env.DEV, config,
        connections.summary(connection("config-change")).version(), "Change connection", null);
    assertThat(pair("config-change").mode()).isEqualTo(Mode.APPROVAL);
    assertThat(pair("config-change").bypassUntil()).isNull();

    grant("config-change", 60);
    endpoints.updateSettings("config-change", new EndpointService.SettingsUpdate(null, "read-only", null,
        null, null, null, null), endpoints.get("config-change").version(), "Restrict endpoint");
    assertThat(pair("config-change").mode()).isEqualTo(Mode.OFF);
    endpoints.updateSettings("config-change", new EndpointService.SettingsUpdate(null, "standard", null,
        null, null, null, null), endpoints.get("config-change").version(), "Restore endpoint");
    assertThat(pair("config-change").mode()).isEqualTo(Mode.OFF);
    assertThat(pair("config-change").bypassUntil()).isNull();
  }

  @Test
  void bypassRunsAndAuditsWithoutAnApprovalButKeepsPolicyDenialsAndExpiry() {
    create("execute-stage", Env.STAGE);
    endpoints.updateSettings("execute-stage", new EndpointService.SettingsUpdate(null, null, null, null, null,
        JsonNodeFactory.instance.objectNode().put("firstWaitSeconds", 0), null),
        endpoints.get("execute-stage").version(), "Return pending approvals immediately");
    grant("execute-stage", 15);
    McpClient client = client("execute-stage");
    String sql = "UPDATE orders SET status = 'ready' WHERE id = 7";
    FakeConnectors.EXECUTIONS.clear();
    var result = client.legacyCall("sql_execute", args("connection", connection("execute-stage"), "sql", sql));
    assertThat(result.result().path("isError").asBoolean()).isFalse();
    assertThat(FakeConnectors.EXECUTIONS).singleElement().satisfies(e -> {
      assertThat(e.sql()).isEqualTo(sql);
      assertThat(e.approvalId()).isNull();
    });
    assertThat(core.jdbc().queryForObject("select count(*) from approval where endpoint_slug = 'execute-stage'",
        Integer.class)).isZero();
    assertThat(core.jdbc().queryForList("select decision, approval_id, facets from tool_call "
        + "where endpoint_slug = 'execute-stage' and phase = 'received'")).singleElement().satisfies(row -> {
          assertThat(row.get("decision")).isEqualTo("allowed");
          assertThat(row.get("approval_id")).isNull();
          assertThat(row.get("facets").toString()).contains("BYPASS", "core.bypass");
        });
    assertThat(client.legacyCall("sql_execute", args("connection", connection("execute-stage"), "sql",
        "DROP TABLE orders")).result().path("isError").asBoolean()).isTrue();
    assertThat(client.legacyCall("sql_execute", args("connection", connection("execute-stage"), "sql",
        "UPDATE leak_payload")).result().path("isError").asBoolean()).isTrue();
    assertThat(FakeConnectors.EXECUTIONS).hasSize(1);

    CLOCK.now = NOW.plus(Duration.ofMinutes(15));
    assertThat(client.legacyCall("sql_execute", args("connection", connection("execute-stage"), "sql", sql))
        .result().at("/structuredContent/status").asString()).isEqualTo("pending");
    assertThat(FakeConnectors.EXECUTIONS).hasSize(1);
    assertThat(core.jdbc().queryForObject("select count(*) from approval where endpoint_slug = 'execute-stage' "
        + "and status = 'pending'", Integer.class)).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void uiClientsGetTheBypassDeadlineAsTextWithoutAnApprovalCard(boolean modern) {
    String slug = "bypass-text-" + modern;
    create(slug, Env.DEV);
    McpClient client = client(slug);
    initializeUi(client);
    var query = endpoints.snapshot(slug).pairs().stream().filter(p -> p.toolKey().equals("sql_query"))
        .findFirst().orElseThrow();
    var queryArgs = args("connection", connection(slug), "sql", "SELECT 1");
    var before = uiCall(client, "sql_query", queryArgs, modern).result();
    tools.setTemporaryBypass(slug, "sql_query", connection(slug), 30, query.rowVersion(), "Temporary reads");
    grant(slug, 30);

    for (String name : List.of("sql_query", "sql_execute")) {
      var arguments = name.equals("sql_query") ? queryArgs
          : args("connection", connection(slug), "sql", "UPDATE orders SET status = 'ready' WHERE id = 9");
      var result = uiCall(client, name, arguments, modern).result();
      SpecSchemas.assertValid(modern ? McpClient.MODERN : "2025-11-25", "CallToolResult", result);
      assertThat(result.path("isError").asBoolean()).isFalse();
      assertThat(result.path("content").toString()).contains("Temporary bypass is enabled until "
          + deadline(30), "for " + name + " on " + connection(slug), "do not show an approval card");
      assertThat(result.toString()).doesNotContain("Show approval_card", "gaterook.approval-card", "approvalUrl",
          "input_required", "resourceUri");
      assertThat(result.at("/structuredContent/handle").isMissingNode()).isTrue();
      if (name.equals("sql_query")) {
        assertThat(result.path("structuredContent")).isEqualTo(before.path("structuredContent"));
      }
    }
    assertThat(core.jdbc().queryForObject("select count(*) from approval where endpoint_slug = ?",
        Integer.class, slug)).isZero();

    var unchanged = uiCall(client, "sql_execute", args("connection", connection(slug), "sql", "UPDATE no_change"),
        modern).result();
    assertThat(unchanged.path("content").toString()).contains("nothing to change", deadline(30));
    var refused = uiCall(client, "sql_execute", args("connection", connection(slug), "sql", "UPDATE leak_payload"),
        modern).result();
    assertThat(refused.path("isError").asBoolean()).isTrue();
    assertThat(refused.toString()).doesNotContain("Temporary bypass is enabled until");
  }

  @Test
  void completingAnExistingApprovalReportsOnlyTheCurrentScopedBypass() {
    String slug = "bypass-after-approval";
    create(slug, Env.DEV);
    McpClient client = client(slug);
    initializeUi(client);
    String handle = client.legacyCall("sql_execute", args("connection", connection(slug), "sql",
        "UPDATE orders SET status = 'ready' WHERE id = 10")).result().at("/structuredContent/handle").asString();
    assertThat(handle).matches("ap_[a-z0-9]{12}");
    grant(slug, 15);
    var pending = client.legacyCall("approval_status", args("handle", handle)).result();
    assertThat(pending.at("/structuredContent/status").asString()).isEqualTo("pending");
    assertThat(pending.toString()).doesNotContain("Temporary bypass is enabled until");

    ApprovalService approvals = core.bean(ApprovalService.class);
    approvals.decide(handle, ApprovalService.Decision.APPROVE, "app", null,
        approvals.find(handle).orElseThrow().digest());
    TestCore.await(() -> approvals.find(handle).orElseThrow().terminal(), "the original approval to complete");
    var completed = client.legacyCall("approval_status", args("handle", handle)).result();
    assertThat(completed.at("/structuredContent/status").asString()).isEqualTo("completed");
    assertThat(completed.path("content").toString()).contains("Temporary bypass is enabled until " + deadline(15),
        "do not show an approval card");
    assertThat(completed.toString()).doesNotContain("Show approval_card", "resourceUri");

    CLOCK.now = NOW.plus(Duration.ofMinutes(15));
    assertThat(client.legacyCall("approval_status", args("handle", handle)).result().toString())
        .doesNotContain("Temporary bypass is enabled until");
    grant(slug, 30);
    assertThat(client.legacyCall("approval_status", args("handle", handle)).result().path("content").toString())
        .contains(deadline(45));
    tools.revokeTemporaryBypass(slug, "sql_execute", connection(slug), pair(slug).rowVersion(), "End bypass");

    // A grant on another connection or endpoint must not describe this completed request as bypassed.
    create("bypass-elsewhere", Env.DEV);
    grant("bypass-elsewhere", 30);
    core.bean(EndpointConnections.class).add(slug, connection("bypass-elsewhere"), null);
    var other = endpoints.snapshot(slug).pairs().stream().filter(p -> p.toolKey().equals("sql_execute")
        && p.connectionName().equals(connection("bypass-elsewhere"))).findFirst().orElseThrow();
    tools.setTemporaryBypass(slug, other.toolKey(), other.connectionName(), 30, other.rowVersion(), "Other scope");
    assertThat(client.legacyCall("approval_status", args("handle", handle)).result().toString())
        .doesNotContain("Temporary bypass is enabled until");
    assertThat(FakeConnectors.EXECUTIONS.stream().filter(e -> handle.equals(e.approvalId())).count()).isEqualTo(1);
  }

  @Test
  void changingAnUnsignedGrantBlocksTheEndpointAndCannotBeResignedByRevocation() {
    create("tampered-grant", Env.DEV);
    grant("tampered-grant", 15);
    var before = pair("tampered-grant");
    core.jdbc().update("update endpoint_tool set temporary_bypass = json_set(temporary_bypass, '$.until', ?) "
        + "where id = ?", NOW.plus(Duration.ofDays(100)).toString(), before.rowId());
    snapshots.invalidateAll();
    assertThat(endpoints.snapshot("tampered-grant").blocked()).isTrue();
    assertThatThrownBy(() -> tools.revokeTemporaryBypass("tampered-grant", "sql_execute",
        connection("tampered-grant"), before.rowVersion(), "Revoke modified grant"))
        .isInstanceOf(ProblemException.class).hasMessageContaining("changed outside GateRook");

    // Reviewing it doesn't sign the grant again: it is removed, and the tool keeps its own mode.
    assertThatThrownBy(() -> endpoints.confirmIntegrity("tampered-grant", "looked"))
        .isInstanceOf(ProblemException.class).hasMessageContaining("temporary bypass that was set outside GateRook");
    endpoints.confirmIntegrity("tampered-grant", "looked", "reset-invalid");
    snapshots.invalidateAll();
    assertThat(endpoints.snapshot("tampered-grant").blocked()).isFalse();
    assertThat(pair("tampered-grant").mode()).isEqualTo(Mode.APPROVAL);
    assertThat(pair("tampered-grant").bypassUntil()).isNull();
    assertThat(core.jdbc().queryForObject("select temporary_bypass from endpoint_tool where id = ?", String.class,
        before.rowId())).isNull();
  }

  @Test
  void aSignedGrantBeyondTheLongestDurationNeverApplies() {
    create("far-grant", Env.DEV);
    grant("far-grant", 15);
    var before = pair("far-grant");
    core.jdbc().update("update endpoint_tool set temporary_bypass = json_set(temporary_bypass, '$.until', ?) "
        + "where id = ?", NOW.plus(Duration.ofDays(100)).toString(), before.rowId());
    // Signed as it is, as a deadline the admin API never grants would be after a review in an older version.
    core.bean(EndpointRows.class).resign(core.bean(EndpointToolRepository.class).findById(before.rowId())
        .orElseThrow());
    snapshots.invalidateAll();
    assertThat(endpoints.snapshot("far-grant").blocked()).isFalse();
    assertThat(pair("far-grant").bypassUntil()).isNull();
    assertThat(pair("far-grant").mode()).isEqualTo(Mode.APPROVAL);
  }

  @Test
  void bypassingOneMethodDoesNotExposeUnrelatedConnectorResources() {
    String slug = "method-only";
    core.connection(connection(slug), "pgfake", Env.DEV, "res.test");
    endpoints.create(slug, null, EndpointService.StartFrom.EMPTY, null);
    core.bean(EndpointConnections.class).add(slug, connection(slug), null);
    var query = endpoints.snapshot(slug).pairs().stream().filter(p -> p.toolKey().equals("sql_query"))
        .findFirst().orElseThrow();
    tools.setMode(slug, "sql_query", connection(slug), Mode.OFF, null, query.rowVersion(), "Disable reads");
    grant(slug, 15);
    McpClient client = client(slug);
    FakeConnectors.RESOURCE_READS.clear();
    assertThat(client.legacy("resources/list", null).result().path("resources")).isEmpty();
    assertThat(client.legacy("resources/read", args("uri", "mcpgw://connections/" + connection(slug) + "/schema"))
        .error().path("code").asInt()).isEqualTo(-32002);
    assertThat(FakeConnectors.RESOURCE_READS).isEmpty();
  }

  @Test
  void expiryAndRevocationDuringPreparationPreventExecution() {
    ToolCallHandler handler = core.bean(ToolCallHandler.class);
    ConnectorInvoker original = core.bean(ConnectorInvoker.class);
    for (boolean revoke : List.of(false, true)) {
      CLOCK.now = NOW;
      String slug = revoke ? "revoke-preparing" : "expire-preparing";
      create(slug, Env.DEV);
      grant(slug, 15);
      ConnectorInvoker intercepted = spy(original);
      doAnswer(invocation -> {
        ConnectorInvoker.Prepared prepared = (ConnectorInvoker.Prepared) invocation.callRealMethod();
        if (revoke) {
          tools.revokeTemporaryBypass(slug, "sql_execute", connection(slug), pair(slug).rowVersion(),
              "Stop while preparing");
        } else {
          CLOCK.now = NOW.plus(Duration.ofMinutes(15));
        }
        return prepared;
      }).when(intercepted).prepare(any());
      ReflectionTestUtils.setField(handler, "invoker", intercepted);
      try {
        FakeConnectors.EXECUTIONS.clear();
        var result = client(slug).legacyCall("sql_execute", args("connection", connection(slug), "sql",
            "UPDATE orders SET status = 'ready' WHERE id = 8"));
        assertThat(result.result().path("isError").asBoolean()).isTrue();
        assertThat(FakeConnectors.EXECUTIONS).isEmpty();
        assertThat(core.jdbc().queryForObject("select count(*) from approval where endpoint_slug = ?",
            Integer.class, slug)).isZero();
      } finally {
        ReflectionTestUtils.setField(handler, "invoker", original);
      }
    }
  }

  @Test
  void grantsSurviveRestartAndRemainBoundToTheirOriginalExpiry() {
    Path home;
    Instant until;
    try (TestCore first = TestCore.start("temporary-bypass-restart")) {
      home = first.home();
      first.connection("restart-db", "pgfake", Env.DEV, "db.test");
      var service = first.bean(EndpointService.class);
      service.create("restart", null, EndpointService.StartFrom.EMPTY, null);
      first.bean(EndpointConnections.class).add("restart", "restart-db", null);
      var pair = service.snapshot("restart").pairs().stream().filter(p -> p.toolKey().equals("sql_execute"))
          .findFirst().orElseThrow();
      first.bean(EndpointTools.class).setTemporaryBypass("restart", "sql_execute", "restart-db", 30,
          pair.rowVersion(), "Temporary access");
      until = service.snapshot("restart").pairs().stream().filter(p -> p.toolKey().equals("sql_execute"))
          .findFirst().orElseThrow().bypassUntil();
    }
    try (TestCore reopened = TestCore.reopen(home)) {
      var snapshot = reopened.bean(EndpointService.class).snapshot("restart");
      assertThat(snapshot.blocked()).isFalse();
      assertThat(snapshot.pairs().stream().filter(p -> p.toolKey().equals("sql_execute"))).singleElement()
          .satisfies(p -> {
            assertThat(p.mode()).isEqualTo(Mode.BYPASS);
            assertThat(p.storedMode()).isEqualTo(Mode.APPROVAL);
            assertThat(p.bypassUntil()).isEqualTo(until);
          });
    }
  }

  private static void create(String slug, Env env) {
    core.connection(connection(slug), "pgfake", env, "db.test");
    endpoints.create(slug, null, EndpointService.StartFrom.EMPTY, null);
    core.bean(EndpointConnections.class).add(slug, connection(slug), null);
  }

  private static String connection(String slug) {
    return slug + "-db";
  }

  private static EndpointSnapshot.Pair pair(String slug) {
    return endpoints.snapshot(slug).pairs().stream().filter(p -> p.toolKey().equals("sql_execute")
        && p.connectionName().equals(connection(slug))).findFirst().orElseThrow();
  }

  private static EndpointTools.ToolRow row(String slug) {
    return tools.list(slug).stream().filter(g -> connection(slug).equals(g.connection()))
        .flatMap(g -> g.tools().stream()).filter(t -> t.toolKey().equals("sql_execute")).findFirst().orElseThrow();
  }

  private static void grant(String slug, int minutes) {
    tools.setTemporaryBypass(slug, "sql_execute", connection(slug), minutes, pair(slug).rowVersion(),
        "Temporary development access");
  }

  private static McpClient client(String slug) {
    return new McpClient(core.bean(LocalListener.class).port(), slug,
        core.bean(TokenService.class).issue(slug, "temporary-test", 7, null).token());
  }

  private static String deadline(int minutes) {
    return NOW.plus(Duration.ofMinutes(minutes)).truncatedTo(ChronoUnit.SECONDS).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
  }

  private static ObjectNode uiCapabilities() {
    ObjectNode capabilities = JsonNodeFactory.instance.objectNode();
    capabilities.putObject("extensions").putObject("io.modelcontextprotocol/ui").putArray("mimeTypes")
        .add("text/html;profile=mcp-app");
    return capabilities;
  }

  private static void initializeUi(McpClient client) {
    ObjectNode params = args("protocolVersion", "2025-11-25");
    params.set("capabilities", uiCapabilities());
    params.putObject("clientInfo").put("name", "bypass-text-test").put("version", "1.0");
    var result = client.legacy("initialize", params).result();
    assertThat(result.path("instructions").asString()).contains("Only when a tool returns a pending approval",
        "Do not show a card for completed requests", "report its expiry as text");
  }

  private static McpClient.Answer uiCall(McpClient client, String tool, ObjectNode arguments, boolean modern) {
    if (!modern) return client.legacyCall(tool, arguments);
    ObjectNode params = args("name", tool);
    params.set("arguments", arguments);
    ObjectNode meta = params.putObject("_meta");
    meta.put("io.modelcontextprotocol/protocolVersion", McpClient.MODERN);
    meta.set("io.modelcontextprotocol/clientCapabilities", uiCapabilities());
    return client.post(client.request("tools/call", params).toString(), Map.of(
        "MCP-Protocol-Version", McpClient.MODERN, "Mcp-Method", "tools/call", "Mcp-Name", tool));
  }

  private static final class MutableClock extends Clock {
    private volatile Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return Clock.fixed(now, zone);
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
