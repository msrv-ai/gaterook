package ai.msrv.gaterook.core.endpoints;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.core.connections.ConnectionService;
import ai.msrv.gaterook.core.endpoints.internal.EndpointRows;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointToolEntity;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointToolRepository;
import ai.msrv.gaterook.core.platform.ProblemException;
import ai.msrv.gaterook.testfixtures.TestCore;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.ObjectNode;

/**
 * The definition of an external MCP server's tool is part of what a person decided (ARCHITECTURE.md §7.5): a tool
 * whose name, description, schemas, annotations or app differ from the ones its mode was set for does not keep Read
 * only or Bypass, however the server got there; unlisting a tool and listing it again changes no choice. The server
 * is the fixture {@code defs.test}, which lists what the connection's note says.
 */
class McpToolReviewIT {

  private static TestCore core;
  private static EndpointService endpoints;
  private static EndpointConnections wiring;
  private static EndpointTools tools;
  private static EndpointSnapshotService snapshots;
  private static ConnectionService connections;

  @BeforeAll
  static void start() {
    core = TestCore.start("mcp-tool-review");
    endpoints = core.bean(EndpointService.class);
    wiring = core.bean(EndpointConnections.class);
    tools = core.bean(EndpointTools.class);
    snapshots = core.bean(EndpointSnapshotService.class);
    connections = core.bean(ConnectionService.class);
  }

  @AfterAll
  static void stop() {
    if (core != null) {
      core.close();
    }
  }

  // ── What the server changes ─────────────────────────────────────────────────────────────────────────────────

  @ParameterizedTest
  @CsvSource({"desc,gh_search", "schema,gh_search", "hint,gh_search", "ui,gh_chart"})
  void aChangedDefinitionTakesReadOnlyAndBypassBackToApprovalAndSaysSo(String note, String changed) {
    String server = "rev-" + note;
    core.connection(server, "mcpfake", Env.DEV, "defs.test");
    for (String suffix : List.of("-ro", "-bp")) {
      endpoints.create(server + suffix, null, EndpointService.StartFrom.EMPTY, null);
      wiring.add(server + suffix, server, null);
    }
    set(server + "-bp", changed, Mode.BYPASS);
    set(server + "-bp", "gh_list", Mode.BYPASS);
    assertThat(pair(server + "-ro", changed).mode()).isEqualTo(Mode.READ_ONLY);
    assertThat(pair(server + "-bp", changed).mode()).isEqualTo(Mode.BYPASS);

    list(server, note);

    for (String suffix : List.of("-ro", "-bp")) {
      EndpointSnapshot.Pair pair = pair(server + suffix, changed);
      assertThat(pair.storedMode()).as(server + suffix).isEqualTo(Mode.APPROVAL);
      assertThat(pair.mode()).as(server + suffix).isEqualTo(Mode.APPROVAL);
    }
    // What the server left alone keeps what the person chose.
    assertThat(pair(server + "-bp", "gh_list").storedMode()).isEqualTo(Mode.BYPASS);
    assertThat(pair(server + "-ro", "gh_list").storedMode()).isEqualTo(Mode.READ_ONLY);
    assertThat(pair(server + "-ro", "gh_write").storedMode()).isEqualTo(Mode.APPROVAL);
    // The Setup changes name the tool, the endpoint and the new mode.
    assertThat(core.jdbc().queryForList("select endpoint_slug || ': ' || change_from || ' > ' || change_to from "
        + "setup_change where what = 'mode' and change_to = 'Approval' and subject like ? order by endpoint_slug",
        String.class, changed + " on " + server + ",%")).containsExactly(server + "-bp: Bypass > Approval",
        server + "-ro: Read only > Approval");
  }

  @Test
  void aServerVersionWithTheSameDefinitionsKeepsEveryMode() {
    String server = "rev-same";
    core.connection(server, "mcpfake", Env.DEV, "defs.test");
    endpoints.create(server, null, EndpointService.StartFrom.EMPTY, null);
    wiring.add(server, server, null);
    set(server, "gh_search", Mode.OFF);
    set(server, "gh_list", Mode.BYPASS);
    set(server, "gh_write", Mode.OFF);

    list(server, "1.0.1");

    // The new read shows that the list was taken in.
    assertThat(pair(server, "gh_extra").storedMode()).isEqualTo(Mode.READ_ONLY);
    assertThat(modes(server)).containsExactly("gh_chart=READ_ONLY", "gh_extra=READ_ONLY", "gh_list=BYPASS",
        "gh_search=OFF", "gh_write=OFF");
    assertThat(core.jdbc().queryForObject("select count(*) from setup_change where what = 'mode' and endpoint_slug "
        + "= ?", Integer.class, server)).isEqualTo(3);
  }

  @Test
  void unlistingAToolAndListingItAgainChangesNoChoice() {
    String server = "rev-gone";
    core.connection(server, "mcpfake", Env.DEV, "defs.test");
    for (String endpoint : List.of(server + "-a", server + "-b")) {
      endpoints.create(endpoint, null, EndpointService.StartFrom.EMPTY, null);
      wiring.add(endpoint, server, null);
    }
    // A declared read the person turned Off, and a tool they put in Approval.
    set(server + "-a", "gh_search", Mode.OFF);
    set(server + "-a", "gh_chart", Mode.APPROVAL);
    set(server + "-b", "gh_chart", Mode.BYPASS);

    list(server, "gone");
    // The tools the server doesn't list aren't published, and their rows stay as they were.
    assertThat(modes(server + "-a")).containsExactly("gh_list=READ_ONLY", "gh_write=APPROVAL");
    assertThat(storedRows(server + "-a")).containsExactly("gh_chart=APPROVAL", "gh_list=READ_ONLY", "gh_search=OFF",
        "gh_write=APPROVAL");
    assertThat(storedRows(server + "-b")).containsExactly("gh_chart=BYPASS", "gh_list=READ_ONLY",
        "gh_search=READ_ONLY", "gh_write=APPROVAL");

    // Back with the definitions it had: nothing is reset or upgraded.
    list(server, "1.0.0");
    assertThat(modes(server + "-a")).containsExactly("gh_chart=APPROVAL", "gh_list=READ_ONLY", "gh_search=OFF",
        "gh_write=APPROVAL");
    assertThat(modes(server + "-b")).containsExactly("gh_chart=BYPASS", "gh_list=READ_ONLY", "gh_search=READ_ONLY",
        "gh_write=APPROVAL");

    // Back with another definition: what let calls run without a person goes to Approval, the rest stays.
    list(server, "gone");
    list(server, "ui");
    assertThat(modes(server + "-a")).containsExactly("gh_chart=APPROVAL", "gh_list=READ_ONLY", "gh_search=OFF",
        "gh_write=APPROVAL");
    assertThat(modes(server + "-b")).containsExactly("gh_chart=APPROVAL", "gh_list=READ_ONLY",
        "gh_search=READ_ONLY", "gh_write=APPROVAL");
  }

  @Test
  void aReadOnlyEndpointHoldsAChangedToolOff() {
    String server = "rev-ro-endpoint";
    core.connection(server, "mcpfake", Env.DEV, "defs.test");
    endpoints.create(server, null, EndpointService.StartFrom.EMPTY, null);
    endpoints.updateSettings(server, new EndpointService.SettingsUpdate(null, "read-only", null, null, null, null,
        null), endpoints.get(server).version(), null);
    wiring.add(server, server, null);
    assertThat(pair(server, "gh_search").storedMode()).isEqualTo(Mode.READ_ONLY);

    list(server, "desc");

    assertThat(pair(server, "gh_search").storedMode()).isEqualTo(Mode.OFF);
    assertThat(pair(server, "gh_search").mode()).isEqualTo(Mode.OFF);
    assertThat(pair(server, "gh_list").storedMode()).isEqualTo(Mode.READ_ONLY);
    assertThat(core.jdbc().queryForList("select change_from || ' > ' || change_to from setup_change where what = "
        + "'mode' and change_to = 'Off' and subject like ?", String.class, "gh_search on " + server + ",%"))
        .containsExactly("Read only > Off");
  }

  @Test
  void aTemporaryBypassIsClearedWhenTheDefinitionChanges() {
    String server = "rev-temporary";
    core.connection(server, "mcpfake", Env.DEV, "defs.test");
    endpoints.create(server, null, EndpointService.StartFrom.EMPTY, null);
    wiring.add(server, server, null);
    for (String tool : List.of("gh_search", "gh_write")) {
      EndpointSnapshot.Pair p = pair(server, tool);
      tools.setTemporaryBypass(server, p.toolKey(), server, 30, p.rowVersion(), null);
      assertThat(pair(server, tool).mode()).isEqualTo(Mode.BYPASS);
    }

    list(server, "desc");
    assertThat(pair(server, "gh_search").storedMode()).isEqualTo(Mode.APPROVAL);
    assertThat(pair(server, "gh_search").bypassUntil()).isNull();
    assertThat(temporaryBypasses(server)).containsExactly("gh_write");

    list(server, "write-desc");
    assertThat(pair(server, "gh_write").storedMode()).isEqualTo(Mode.APPROVAL);
    assertThat(pair(server, "gh_write").mode()).isEqualTo(Mode.APPROVAL);
    assertThat(temporaryBypasses(server)).isEmpty();
    assertThat(core.jdbc().queryForList("select change_from || ' > ' || change_to from setup_change where what = "
        + "'mode' and change_to = 'Approval' and subject like ?", String.class, "gh_write on " + server + ",%"))
        .singleElement().satisfies(c -> assertThat(c).startsWith("Temporary bypass until ")
            .endsWith(" > Approval"));
  }

  @Test
  void thePersonCanSetTheModeAgainAndItHoldsUntilTheNextChange() {
    String server = "rev-again";
    core.connection(server, "mcpfake", Env.DEV, "defs.test");
    endpoints.create(server, null, EndpointService.StartFrom.EMPTY, null);
    wiring.add(server, server, null);

    list(server, "desc");
    assertThat(pair(server, "gh_search").mode()).isEqualTo(Mode.APPROVAL);
    set(server, "gh_search", Mode.READ_ONLY);
    assertThat(pair(server, "gh_search").mode()).isEqualTo(Mode.READ_ONLY);

    // The same list again, and a new version of the server that changes nothing.
    list(server, "desc");
    assertThat(pair(server, "gh_search").storedMode()).isEqualTo(Mode.READ_ONLY);

    // Changed back: it is another definition than the one the person looked at, whatever it was before.
    list(server, "1.0.0");
    assertThat(pair(server, "gh_search").storedMode()).isEqualTo(Mode.APPROVAL);
  }

  // ── What the endpoint holds a tool to ───────────────────────────────────────────────────────────────────────

  @Test
  void aServerToolJoinsWithTheDefinitionItHasAndABuiltInOneWithNone() {
    String server = "rev-join";
    core.connection(server, "mcpfake", Env.DEV, "defs.test");
    core.connection("rev-join-db", "pgfake", Env.DEV, "db.test");
    endpoints.create(server, null, EndpointService.StartFrom.EMPTY, null);
    wiring.add(server, server, null);
    wiring.add(server, "rev-join-db", null);

    assertThat(core.jdbc().queryForObject("select count(*) from endpoint_tool where tool_key like 'mcp:%' and "
        + "reviewed_definition is not null and endpoint_id = (select id from endpoint where slug = ?)", Integer.class,
        server)).isEqualTo(4);
    assertThat(core.jdbc().queryForObject("select count(*) from endpoint_tool where tool_key like 'mcp:%' and "
        + "reviewed_definition is null and endpoint_id = (select id from endpoint where slug = ?)", Integer.class,
        server)).isZero();
    assertThat(core.jdbc().queryForObject("select count(*) from endpoint_tool where tool_key not like 'mcp:%' and "
        + "reviewed_definition is not null", Integer.class)).as("nothing else records one").isZero();
  }

  @Test
  void untilTheCoreHasReconciledAChangedToolRunsOnlyWithApproval() {
    String server = "rev-guard";
    core.connection(server, "mcpfake", Env.DEV, "defs.test");
    endpoints.create(server, null, EndpointService.StartFrom.EMPTY, null);
    wiring.add(server, server, null);
    set(server, "gh_list", Mode.BYPASS);

    // What the row was set for isn't what the endpoint publishes (the server changed it a moment ago).
    record(server, "gh_search", "0".repeat(64));
    record(server, "gh_list", "0".repeat(64));

    assertThat(pair(server, "gh_search").storedMode()).isEqualTo(Mode.READ_ONLY);
    assertThat(pair(server, "gh_search").mode()).isEqualTo(Mode.APPROVAL);
    assertThat(pair(server, "gh_list").storedMode()).isEqualTo(Mode.BYPASS);
    assertThat(pair(server, "gh_list").mode()).isEqualTo(Mode.APPROVAL);
    assertThat(pair(server, "gh_chart").mode()).isEqualTo(Mode.READ_ONLY);

    // A person who sets the mode for what it is now holds it again.
    set(server, "gh_search", Mode.APPROVAL);
    set(server, "gh_search", Mode.READ_ONLY);
    assertThat(pair(server, "gh_search").mode()).isEqualTo(Mode.READ_ONLY);
    // Setting the mode it already has is enough when it is the definition that changed.
    set(server, "gh_list", Mode.BYPASS);
    assertThat(pair(server, "gh_list").mode()).isEqualTo(Mode.BYPASS);
    assertThat(core.jdbc().queryForList("select change_from || ' > ' || change_to from setup_change where what = "
        + "'mode' and change_from like '%earlier definition%' and subject like ?", String.class,
        "gh_list on " + server + ",%")).containsExactly("Bypass (earlier definition) > Bypass");
  }

  @Test
  void setAllToOffOrApprovalHoldsAToolTheServerHidesForAWhileToo() {
    String server = "rev-hidden";
    core.connection(server, "mcpfake", Env.DEV, "defs.test");
    for (String suffix : List.of("-off", "-approval")) {
      endpoints.create(server + suffix, null, EndpointService.StartFrom.EMPTY, null);
      wiring.add(server + suffix, server, null);
      set(server + suffix, "gh_chart", Mode.BYPASS);
    }
    set(server + "-approval", "gh_search", Mode.OFF);
    list(server, "gone");

    String chart = "mcp:" + server + ":gh_chart";
    String search = "mcp:" + server + ":gh_search";
    assertThat(tools.bulk(server + "-off", server, "OFF", null).changed()).contains(chart, search);
    assertThat(tools.bulk(server + "-approval", server, "APPROVAL", null).changed()).contains(chart)
        .doesNotContain(search);

    // Listed again with the definitions they had: nothing slipped through, and what was Off was not raised.
    list(server, "1.0.0");
    assertThat(modes(server + "-off")).containsExactly("gh_chart=OFF", "gh_list=OFF", "gh_search=OFF",
        "gh_write=OFF");
    assertThat(modes(server + "-approval")).containsExactly("gh_chart=APPROVAL", "gh_list=APPROVAL",
        "gh_search=OFF", "gh_write=APPROVAL");
  }

  @Test
  void setAllRecordsTheDefinitionsOfWhatItSetsToo() {
    String server = "rev-bulk";
    core.connection(server, "mcpfake", Env.DEV, "defs.test");
    endpoints.create(server, null, EndpointService.StartFrom.EMPTY, null);
    wiring.add(server, server, null);
    record(server, "gh_chart", "0".repeat(64));
    assertThat(pair(server, "gh_chart").mode()).isEqualTo(Mode.APPROVAL);

    EndpointTools.BulkResult result = tools.bulk(server, server, "READ_ONLY", null);

    assertThat(result.changed()).contains("mcp:" + server + ":gh_chart");
    assertThat(pair(server, "gh_chart").mode()).isEqualTo(Mode.READ_ONLY);
    assertThat(pair(server, "gh_search").mode()).isEqualTo(Mode.READ_ONLY);
    assertThat(tools.bulk(server, server, "READ_ONLY", null).changed()).as("nothing left to do").isEmpty();
  }

  @Test
  void theUpgradeRecordsTheDefinitionsOfRowsAnOlderVersionLeftWithoutOne() {
    String server = "rev-legacy-kept";
    legacyServer(server);
    assertThat(pair(server, "gh_search").mode()).as("nothing recorded: Approval").isEqualTo(Mode.APPROVAL);
    assertThat(pair(server, "gh_list").mode()).isEqualTo(Mode.APPROVAL);

    wiring.recordReviewedDefinitions();

    // The modes stand again, and from now on a change is a change.
    assertThat(modes(server)).containsExactly("gh_chart=READ_ONLY", "gh_list=BYPASS", "gh_search=READ_ONLY",
        "gh_write=APPROVAL");
    assertThat(core.jdbc().queryForObject("select count(*) from endpoint_tool where reviewed_definition is null and "
        + "tool_key like 'mcp:" + server + ":%'", Integer.class)).isZero();
    list(server, "desc");
    assertThat(pair(server, "gh_search").storedMode()).isEqualTo(Mode.APPROVAL);
    assertThat(pair(server, "gh_list").storedMode()).isEqualTo(Mode.BYPASS);
  }

  /**
   * The start that applies V89 records the definitions the endpoints publish for the tools that already had a row, and
   * says so in a Setup change, before any server starts; a start after it records nothing. The schema is rolled back to
   * before V89 and the rows are signed without a definition here, as an older version left them.
   */
  @Test
  void theStartThatAppliesTheMigrationRecordsTheDefinitionsOnceAndKeepsEveryMode() throws Exception {
    Path home;
    try (TestCore older = TestCore.start("mcp-review-upgrade")) {
      older.connection("up-mcp", "mcpfake", Env.DEV, "defs.test");
      older.bean(EndpointService.class).create("up", null, EndpointService.StartFrom.EMPTY, null);
      older.bean(EndpointConnections.class).add("up", "up-mcp", null);
      EndpointSnapshot.Pair list = older.bean(EndpointSnapshotService.class).bySlug("up").orElseThrow().pairs()
          .stream().filter(p -> p.tool().name().equals("gh_list")).findFirst().orElseThrow();
      older.bean(EndpointTools.class).setMode("up", list.toolKey(), "up-mcp", Mode.BYPASS, null, list.rowVersion(),
          null);
      EndpointRows rows = older.bean(EndpointRows.class);
      EndpointToolRepository repository = older.bean(EndpointToolRepository.class);
      new TransactionTemplate(older.bean(PlatformTransactionManager.class)).executeWithoutResult(s -> {
        for (EndpointToolEntity row : repository.findAll()) {
          assertThat(rows.verify(row)).isTrue();
          row.setReviewedDefinition(null, Instant.now());
          rows.save(row);
        }
      });
      home = older.home();
    }
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + home.resolve("gateway.db"));
        Statement s = c.createStatement()) {
      s.execute("ALTER TABLE endpoint_tool DROP COLUMN reviewed_definition");
      s.execute("DELETE FROM flyway_schema_history WHERE version = '89'");
    }
    String recorded = "select count(*) from setup_change where subject = 'Rows from an older version' and "
        + "change_to like 'recorded for 4 MCP server tools'";
    try (TestCore upgraded = TestCore.reopen(home)) {
      EndpointSnapshot s = upgraded.bean(EndpointSnapshotService.class).bySlug("up").orElseThrow();
      assertThat(s.blocked()).isFalse();
      assertThat(s.pairs().stream().map(p -> p.tool().name() + "=" + p.mode()).sorted().toList())
          .containsExactly("gh_chart=READ_ONLY", "gh_list=BYPASS", "gh_search=READ_ONLY", "gh_write=APPROVAL");
      assertThat(upgraded.jdbc().queryForObject("select count(*) from endpoint_tool where connection_id is not null "
          + "and reviewed_definition is null", Integer.class)).isZero();
      assertThat(upgraded.jdbc().queryForObject(recorded, Integer.class)).isEqualTo(1);
    }
    // Only that start records: the next one finds nothing to do.
    try (TestCore next = TestCore.reopen(home)) {
      assertThat(next.jdbc().queryForObject(recorded, Integer.class)).isEqualTo(1);
      assertThat(next.bean(EndpointSnapshotService.class).bySlug("up").orElseThrow().blocked()).isFalse();
    }
  }

  @Test
  void aRowStillWithoutARecordedDefinitionGoesToApprovalWhenTheEndpointIsReconciled() {
    String server = "rev-legacy-refused";
    legacyServer(server);

    // The upgrade couldn't record it (the master key was locked): no definition is taken for reviewed.
    wiring.reconcile(connections.get(server).id(), "test");

    assertThat(modes(server)).containsExactly("gh_chart=APPROVAL", "gh_list=APPROVAL", "gh_search=APPROVAL",
        "gh_write=APPROVAL");
    assertThat(storedRows(server)).containsExactly("gh_chart=APPROVAL", "gh_list=APPROVAL", "gh_search=APPROVAL",
        "gh_write=APPROVAL");
    assertThat(core.jdbc().queryForList("select change_from || ' > ' || change_to from setup_change where what = "
        + "'mode' and change_to = 'Approval' and endpoint_slug = ? order by id", String.class, server))
        .containsExactlyInAnyOrder("Read only > Approval", "Read only > Approval", "Bypass > Approval");
  }

  /** The connection's name is in what an endpoint publishes (the URI of a tool's app): a rename is no new definition. */
  @Test
  void renamingAServerChangesNoDefinitionItsToolsAreHeldTo() {
    String before = "rev-name-before";
    core.connection(before, "mcpfake", Env.DEV, "defs.test");
    endpoints.create(before, null, EndpointService.StartFrom.EMPTY, null);
    wiring.add(before, before, null);
    set(before, "gh_list", Mode.BYPASS);
    String published = pair(before, "gh_chart").tool().meta().toString();
    assertThat(published).contains("connections/" + before + "/");

    ConnectionService.Detail d = connections.get(before);
    connections.update(before, "rev-name-after", d.env(), d.config(), d.version(), "clearer name", null, Map.of());
    wiring.reconcile(d.id(), null);

    assertThat(pair(before, "gh_chart").tool().meta().toString()).contains("connections/rev-name-after/");
    assertThat(modes(before)).containsExactly("gh_chart=READ_ONLY", "gh_list=BYPASS", "gh_search=READ_ONLY",
        "gh_write=APPROVAL");
    assertThat(storedRows(before)).containsExactly("gh_chart=READ_ONLY", "gh_list=BYPASS", "gh_search=READ_ONLY",
        "gh_write=APPROVAL");
    assertThat(core.jdbc().queryForObject("select count(*) from setup_change where what = 'mode' and change_to = "
        + "'Approval' and subject like 'gh_%on rev-name-after,%'", Integer.class)).isZero();
    // The renamed server's tool still changes with its definition.
    list("rev-name-after", "ui");
    assertThat(pair(before, "gh_chart").storedMode()).isEqualTo(Mode.APPROVAL);
  }

  @Test
  void aDuplicateKeepsTheDefinitionItsModesWereSetForAndNotTheOneOfToday() {
    String server = "rev-copy";
    core.connection(server, "mcpfake", Env.DEV, "defs.test");
    endpoints.create(server, null, EndpointService.StartFrom.EMPTY, null);
    wiring.add(server, server, null);
    set(server, "gh_list", Mode.BYPASS);

    endpoints.duplicate(server, server + "-copy", null);
    assertThat(modes(server + "-copy")).isEqualTo(modes(server));
    list(server, "desc");
    assertThat(pair(server + "-copy", "gh_search").storedMode()).isEqualTo(Mode.APPROVAL);
    assertThat(pair(server + "-copy", "gh_list").storedMode()).isEqualTo(Mode.BYPASS);

    // A row that isn't up to date is no more so in the copy.
    record(server, "gh_list", "0".repeat(64));
    endpoints.duplicate(server, server + "-again", null);
    assertThat(pair(server + "-again", "gh_list").storedMode()).isEqualTo(Mode.BYPASS);
    assertThat(pair(server + "-again", "gh_list").mode()).isEqualTo(Mode.APPROVAL);
  }

  @Test
  void aDuplicateKeepsTheChoicesForToolsTheServerDoesntListNow() {
    String server = "rev-copy-gone";
    core.connection(server, "mcpfake", Env.DEV, "defs.test");
    endpoints.create(server, null, EndpointService.StartFrom.EMPTY, null);
    wiring.add(server, server, null);
    set(server, "gh_search", Mode.OFF);
    set(server, "gh_chart", Mode.BYPASS);
    list(server, "gone");

    endpoints.duplicate(server, server + "-copy", null);
    assertThat(storedRows(server + "-copy")).containsExactly("gh_chart=BYPASS", "gh_list=READ_ONLY", "gh_search=OFF",
        "gh_write=APPROVAL");

    // Listed again: the copy has what the original has, not the defaults of tools it never saw.
    list(server, "1.0.0");
    assertThat(modes(server + "-copy")).isEqualTo(modes(server)).containsExactly("gh_chart=BYPASS",
        "gh_list=READ_ONLY", "gh_search=OFF", "gh_write=APPROVAL");
  }

  @Test
  void keepingAnEditedEndpointAsStoredDropsTheDefinitionARowWasEditedWith() {
    String server = "rev-edited";
    core.connection(server, "mcpfake", Env.DEV, "defs.test");
    endpoints.create(server, null, EndpointService.StartFrom.EMPTY, null);
    wiring.add(server, server, null);
    // A definition that makes Read only valid, written outside GateRook.
    core.jdbc().update("update endpoint_tool set reviewed_definition = ? where tool_key = ? and endpoint_id = "
        + "(select id from endpoint where slug = ?)", "f".repeat(64), "mcp:" + server + ":gh_search", server);
    snapshots.invalidateAll();
    assertThat(snapshots.bySlug(server).orElseThrow().blocked()).isTrue();

    assertThatThrownBy(() -> endpoints.confirmIntegrity(server, "looked")).isInstanceOf(ProblemException.class)
        .hasMessageContaining("reviewed definition");
    endpoints.confirmIntegrity(server, "looked", "reset-invalid");

    assertThat(snapshots.bySlug(server).orElseThrow().blocked()).isFalse();
    assertThat(pair(server, "gh_search").storedMode()).isEqualTo(Mode.READ_ONLY);
    assertThat(pair(server, "gh_search").mode()).as("needs a person again").isEqualTo(Mode.APPROVAL);
    assertThat(pair(server, "gh_list").mode()).as("a row nobody edited").isEqualTo(Mode.READ_ONLY);
    assertThat(core.jdbc().queryForObject("select change_to from setup_change where subject = 'Endpoint " + server
        + " changed outside GateRook'", String.class)).contains("gh_search reviewed definition");
  }

  @Test
  void aServerThatListsMoreNamesThanARowCanBeGivenGetsNoRowsForTheRestAndLosesNone() {
    String server = "rev-flood";
    core.connection(server, "mcpfake", Env.DEV, "flood.test");
    endpoints.create(server, null, EndpointService.StartFrom.EMPTY, null);
    wiring.add(server, server, null);
    set(server, "gh_t0", Mode.OFF);
    String id = connections.get(server).id();

    flood(server, EndpointConnections.MAX_SERVER_ROWS + 300);
    assertThat(rowCount(id)).isEqualTo(EndpointConnections.MAX_SERVER_ROWS);
    assertThat(pair(server, "gh_t0").storedMode()).isEqualTo(Mode.OFF);
    assertThat(pair(server, "gh_t" + (EndpointConnections.MAX_SERVER_ROWS + 299)).storedMode()).isEqualTo(Mode.OFF);

    // Nearly all of them are gone now, and what a person decided for any name is still there.
    flood(server, 2);
    assertThat(rowCount(id)).isEqualTo(EndpointConnections.MAX_SERVER_ROWS);
    assertThat(pair(server, "gh_t0").storedMode()).isEqualTo(Mode.OFF);
    assertThat(pair(server, "gh_t1").storedMode()).isEqualTo(Mode.READ_ONLY);
  }

  @Test
  void aToolTheServerNoLongerListsDoesntShowAsUsedByAnEndpoint() {
    String server = "rev-used";
    core.connection(server, "mcpfake", Env.DEV, "defs.test");
    endpoints.create(server, null, EndpointService.StartFrom.EMPTY, null);
    wiring.add(server, server, null);
    assertThat(connections.get(server).usedBy()).extracting(u -> u.tool()).contains("gh_search", "gh_chart");

    list(server, "gone");

    assertThat(connections.get(server).usedBy()).extracting(u -> u.tool()).containsExactlyInAnyOrder("gh_list",
        "gh_write");
    assertThat(snapshots.bySlug(server).orElseThrow().pairs()).extracting(p -> p.tool().name())
        .containsExactlyInAnyOrder("gh_list", "gh_write");
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

  /** The server lists as {@code note} says from now on: the connection is saved with it, and the endpoints follow. */
  private static void list(String connection, String note) {
    list(connection, "defs.test", note);
  }

  private static void list(String connection, String host, String note) {
    ObjectNode config = (ObjectNode) TestCore.config(host);
    ((ObjectNode) config.get("connection")).put("note", note);
    ConnectionService.Detail current = connections.get(connection);
    connections.update(connection, Env.DEV, config, current.version(), null, null);
    wiring.reconcile(current.id(), "test");
  }

  /** The server of {@code flood.test} lists {@code count} declared reads from now on. */
  private static void flood(String connection, int count) {
    list(connection, "flood.test", Integer.toString(count));
  }

  private static int rowCount(String connectionId) {
    return core.jdbc().queryForObject("select count(*) from endpoint_tool where connection_id = ?", Integer.class,
        connectionId);
  }

  /**
   * A server on its own endpoint as an older version left it: the tools in their default modes ({@code gh_list} in
   * Bypass), the rows signed, none with a definition.
   */
  private static void legacyServer(String server) {
    core.connection(server, "mcpfake", Env.DEV, "defs.test");
    endpoints.create(server, null, EndpointService.StartFrom.EMPTY, null);
    wiring.add(server, server, null);
    set(server, "gh_list", Mode.BYPASS);
    for (String tool : List.of("gh_search", "gh_list", "gh_chart", "gh_write")) {
      record(server, tool, null);
    }
  }

  /**
   * Stores {@code definition} in the row of a tool, signed as the core signs a row it wrote; {@code null} as a row
   * written before the column was signed (no definition in its MAC).
   */
  private static void record(String endpoint, String tool, String definition) {
    EndpointRows rows = core.bean(EndpointRows.class);
    EndpointToolRepository repository = core.bean(EndpointToolRepository.class);
    String id = pair(endpoint, tool).rowId();
    new TransactionTemplate(core.bean(PlatformTransactionManager.class)).executeWithoutResult(s -> {
      EndpointToolEntity row = repository.findById(id).orElseThrow();
      assertThat(rows.verify(row)).isTrue();
      row.setReviewedDefinition(definition, Instant.now());
      rows.save(row);
    });
    snapshots.invalidateAll();
  }

  private static EndpointSnapshot.Pair pair(String endpoint, String tool) {
    return snapshots.bySlug(endpoint).orElseThrow().pairs().stream().filter(p -> p.tool().name().equals(tool))
        .findFirst().orElseThrow();
  }

  private static void set(String endpoint, String tool, Mode mode) {
    EndpointSnapshot.Pair p = pair(endpoint, tool);
    tools.setMode(endpoint, p.toolKey(), p.connectionName(), mode, null, p.rowVersion(), null);
  }

  /** The tools an endpoint publishes now and the modes they run in, by name. */
  private static List<String> modes(String endpoint) {
    return snapshots.bySlug(endpoint).orElseThrow().pairs().stream().map(p -> p.tool().name() + "=" + p.mode())
        .sorted().toList();
  }

  /** The modes stored in the rows of an endpoint's server tools, those the server doesn't list now included. */
  private static List<String> storedRows(String endpoint) {
    return core.jdbc().queryForList("select t.tool_key || '=' || t.mode from endpoint_tool t join endpoint e on e.id "
        + "= t.endpoint_id where e.slug = ? and t.connection_id is not null order by t.tool_key", String.class,
        endpoint).stream().map(row -> row.substring(row.lastIndexOf(':') + 1)).toList();
  }

  /** The tools of an endpoint that hold a temporary bypass in their rows. */
  private static List<String> temporaryBypasses(String endpoint) {
    return core.jdbc().queryForList("select t.tool_key from endpoint_tool t join endpoint e on e.id = t.endpoint_id "
        + "where e.slug = ? and t.temporary_bypass is not null order by t.tool_key", String.class, endpoint).stream()
        .map(key -> key.substring(key.lastIndexOf(':') + 1)).toList();
  }
}
