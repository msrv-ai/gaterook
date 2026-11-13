package com.gaterook.core.endpoints;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.ToolDefinition.Mode;
import com.gaterook.core.connections.ConnectionService;
import com.gaterook.core.endpoints.internal.EndpointRows;
import com.gaterook.core.endpoints.internal.ToolPairs;
import com.gaterook.core.endpoints.internal.persistence.EndpointToolEntity;
import com.gaterook.core.endpoints.internal.persistence.EndpointToolRepository;
import com.gaterook.core.platform.ProblemException;
import com.gaterook.testfixtures.TestCore;
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
    // gh_write keeps its definition, but its grant is bound to the connection and the endpoint as they were: the
    // server's new list and the reconcile changed both, so it no longer applies.
    assertThat(pair(server, "gh_write").mode()).isEqualTo(Mode.APPROVAL);
    assertThat(pair(server, "gh_write").bypassUntil()).isNull();

    list(server, "write-desc");
    assertThat(pair(server, "gh_write").storedMode()).isEqualTo(Mode.APPROVAL);
    assertThat(pair(server, "gh_write").mode()).isEqualTo(Mode.APPROVAL);
    assertThat(temporaryBypasses(server)).isEmpty();
    // The grant that had stopped applying is not shown as in force.
    assertThat(core.jdbc().queryForList("select change_from || ' > ' || change_to from setup_change where what = "
        + "'mode' and change_to = 'Approval' and subject like ?", String.class, "gh_write on " + server + ",%"))
        .singleElement().satisfies(c -> assertThat(c).startsWith("Approval, temporary bypass until ")
            .endsWith(", no longer in force > Approval"));
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

    EndpointTools.BulkResult result = tools.bulk(server, server, "READ_ONLY", shown(server), null);

    assertThat(result.changed()).contains("mcp:" + server + ":gh_chart");
    assertThat(pair(server, "gh_chart").mode()).isEqualTo(Mode.READ_ONLY);
    assertThat(pair(server, "gh_search").mode()).isEqualTo(Mode.READ_ONLY);
    assertThat(tools.bulk(server, server, "READ_ONLY", shown(server), null).changed()).as("nothing left to do")
        .isEmpty();
  }

  @Test
  void setAllSetsOnlyTheToolsThePersonSaw() {
    String server = "rev-bulk-shown";
    core.connection(server, "mcpfake", Env.DEV, "defs.test");
    endpoints.create(server, null, EndpointService.StartFrom.EMPTY, null);
    wiring.add(server, server, null);
    List<String> before = modes(server);

    // A search showed gh_chart and gh_write only: the other tools keep their modes.
    EndpointTools.BulkResult result = tools.bulk(server, server, "BYPASS", shown(server, "gh_chart", "gh_write"),
        null);

    assertThat(result.changed()).containsExactlyInAnyOrder("mcp:" + server + ":gh_chart",
        "mcp:" + server + ":gh_write");
    assertThat(modes(server)).contains("gh_chart=BYPASS", "gh_write=BYPASS");
    assertThat(modes(server).stream().filter(m -> m.startsWith("gh_list") || m.startsWith("gh_search")))
        .containsExactlyElementsOf(before.stream().filter(m -> m.startsWith("gh_list") || m.startsWith("gh_search"))
            .toList());
  }

  @Test
  void setAllRefusesToolsThatChangedWhileThePersonLookedAndChangesNothing() {
    String server = "rev-bulk-stale";
    core.connection(server, "mcpfake", Env.DEV, "defs.test");
    endpoints.create(server, null, EndpointService.StartFrom.EMPTY, null);
    wiring.add(server, server, null);
    // The list was loaded, then the server changed gh_search before the person chose Set all.
    List<EndpointTools.Shown> seen = shown(server);
    list(server, "desc");
    List<String> now = modes(server);

    assertThatThrownBy(() -> tools.bulk(server, server, "BYPASS", seen, null)).isInstanceOf(ProblemException.class)
        .satisfies(e -> assertThat(((ProblemException) e).kind()).isEqualTo(ProblemException.Kind.CONFLICT))
        .hasMessageContaining("gh_search");
    assertThat(modes(server)).as("not one of them was set").isEqualTo(now);
    assertThat(storedRows(server)).doesNotContain("gh_chart=BYPASS", "gh_list=BYPASS");
    // Lowering needs no review: Off and Approval are stored as asked.
    assertThat(tools.bulk(server, server, "OFF", seen, null).changed()).isNotEmpty();
    assertThat(modes(server)).allSatisfy(m -> assertThat(m).endsWith("=OFF"));
  }

  @Test
  void setAllWithoutTheToolsAPersonSawRecordsNoDefinition() {
    String server = "rev-bulk-blind";
    core.connection(server, "mcpfake", Env.DEV, "defs.test");
    endpoints.create(server, null, EndpointService.StartFrom.EMPTY, null);
    wiring.add(server, server, null);
    record(server, "gh_chart", "0".repeat(64));
    assertThat(pair(server, "gh_chart").mode()).isEqualTo(Mode.APPROVAL);

    // An API caller that names no tools: the mode is stored, but a held tool stays held until a person looks.
    tools.bulk(server, server, "READ_ONLY", null);

    assertThat(pair(server, "gh_chart").storedMode()).isEqualTo(Mode.READ_ONLY);
    assertThat(pair(server, "gh_chart").mode()).isEqualTo(Mode.APPROVAL);
    assertThat(reviewedDefinition(server, "gh_chart")).isEqualTo("0".repeat(64));
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
      older.bean(EndpointTools.class).setMode("up", list.toolKey(), "up-mcp", Mode.BYPASS,
          ToolPairs.definitionHash(list.tool()), null, list.rowVersion(), null);
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
    // Nothing was recorded, so nothing changed since: the reason says what is missing.
    assertThat(modeChanges(server, "gh_list")).last().isEqualTo("Bypass > Approval: No definition of gh_list is "
        + "recorded for the mode it was set to");
    assertThat(modeChanges(server, "gh_search")).last().isEqualTo("Read only > Approval: No definition of gh_search "
        + "is recorded for the mode it was set to");
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

  // ── A temporary bypass has its own binding ──────────────────────────────────────────────────────────────────

  @Test
  void aTemporaryBypassGrantedAfterADefinitionChangeSurvivesAReconcileThatChangesNothing() {
    String server = "rev-grant-demoted";
    core.connection(server, "mcpfake", Env.DEV, "defs.test");
    endpoints.create(server, null, EndpointService.StartFrom.EMPTY, null);
    wiring.add(server, server, null);
    list(server, "desc");
    assertThat(pair(server, "gh_search").storedMode()).isEqualTo(Mode.APPROVAL);

    grant(server, "gh_search");
    reconcile(server);

    assertThat(pair(server, "gh_search").mode()).isEqualTo(Mode.BYPASS);
    assertThat(pair(server, "gh_search").bypassUntil()).isNotNull();
    assertThat(temporaryBypasses(server)).containsExactly("gh_search");
    assertThat(modeChanges(server, "gh_search")).containsExactly(
        "Read only > Approval: The definition of gh_search changed since its mode was set",
        "Approval > Temporary bypass until " + pair(server, "gh_search").bypassUntil());
  }

  @Test
  void aTemporaryBypassOfAToolInApprovalWhoseDefinitionChangedSurvivesAReconcileThatChangesNothing() {
    String server = "rev-grant-approval";
    core.connection(server, "mcpfake", Env.DEV, "defs.test");
    endpoints.create(server, null, EndpointService.StartFrom.EMPTY, null);
    wiring.add(server, server, null);
    // gh_write waits in Approval: its row keeps the definition it came in with.
    list(server, "write-desc");
    grant(server, "gh_write");

    reconcile(server);
    reconcile(server);

    assertThat(pair(server, "gh_write").storedMode()).isEqualTo(Mode.APPROVAL);
    assertThat(pair(server, "gh_write").mode()).isEqualTo(Mode.BYPASS);
    assertThat(temporaryBypasses(server)).containsExactly("gh_write");
  }

  @Test
  void aTemporaryBypassEndsWhenTheDefinitionItWasGrantedForChangesAndSaysSo() {
    String server = "rev-grant-ends";
    core.connection(server, "mcpfake", Env.DEV, "defs.test");
    endpoints.create(server, null, EndpointService.StartFrom.EMPTY, null);
    wiring.add(server, server, null);
    list(server, "desc");
    grant(server, "gh_search");
    Instant until = pair(server, "gh_search").bypassUntil();

    list(server, "1.0.0");

    assertThat(pair(server, "gh_search").mode()).isEqualTo(Mode.APPROVAL);
    assertThat(temporaryBypasses(server)).isEmpty();
    // The server's new list saved the connection, so the grant had stopped applying before the reconcile dropped it.
    assertThat(modeChanges(server, "gh_search")).containsExactly(
        "Read only > Approval: The definition of gh_search changed since its mode was set",
        "Approval > Temporary bypass until " + until,
        "Approval, temporary bypass until " + until + ", no longer in force > Approval: The definition of gh_search "
            + "changed since its temporary bypass was granted");
  }

  @Test
  void aTemporaryBypassOnARowWithoutARecordedDefinitionIsJudgedByItsOwnBinding() {
    String server = "rev-grant-unrecorded";
    core.connection(server, "mcpfake", Env.DEV, "defs.test");
    endpoints.create(server, null, EndpointService.StartFrom.EMPTY, null);
    wiring.add(server, server, null);
    record(server, "gh_write", null);
    grant(server, "gh_write");

    reconcile(server);

    assertThat(pair(server, "gh_write").mode()).isEqualTo(Mode.BYPASS);
    assertThat(temporaryBypasses(server)).containsExactly("gh_write");
    assertThat(modeChanges(server, "gh_write")).singleElement().satisfies(c -> assertThat(c)
        .startsWith("Approval > Temporary bypass until "));

    // A stored Read only held in Approval (its row records an earlier definition) runs with the grant it is given.
    record(server, "gh_search", "0".repeat(64));
    grant(server, "gh_search");
    String until = "temporary bypass until " + pair(server, "gh_search").bypassUntil();
    assertThat(pair(server, "gh_search").mode()).isEqualTo(Mode.BYPASS);
    assertThat(pair(server, "gh_search").held()).isNull();

    // The reconcile lowers its stored mode and saves the endpoint, whose new version no grant is bound to: the grant
    // ends with the mode change and the Setup change says so, instead of staying in the row without applying.
    reconcile(server);
    assertThat(pair(server, "gh_search").storedMode()).isEqualTo(Mode.APPROVAL);
    assertThat(pair(server, "gh_search").mode()).isEqualTo(Mode.APPROVAL);
    assertThat(pair(server, "gh_search").bypassUntil()).isNull();
    assertThat(modeChanges(server, "gh_search")).last().isEqualTo("Read only, " + until + " > Approval: The "
        + "definition of gh_search changed since its mode was set, and its temporary bypass ended with the mode change");
    // gh_write's grant, bound to the endpoint as it was, stops applying with the same save.
    assertThat(temporaryBypasses(server)).containsExactly("gh_write");
    assertThat(pair(server, "gh_write").mode()).isEqualTo(Mode.APPROVAL);
  }

  // ── A definition is recorded when a person chooses the mode ─────────────────────────────────────────────────

  @Test
  void savingTheLimitsOfAHeldToolRecordsNoDefinitionAndKeepsItInApproval() {
    String server = "rev-limits";
    core.connection(server, "mcpfake", Env.DEV, "defs.test");
    endpoints.create(server, null, EndpointService.StartFrom.EMPTY, null);
    wiring.add(server, server, null);
    set(server, "gh_list", Mode.BYPASS);
    record(server, "gh_list", "0".repeat(64));
    record(server, "gh_search", null);
    assertThat(pair(server, "gh_list").mode()).isEqualTo(Mode.APPROVAL);

    saveLimits(server, "gh_list", "{\"rowLimit\":5}");
    saveLimits(server, "gh_search", "{\"rowLimit\":5}");

    for (String tool : List.of("gh_list", "gh_search")) {
      assertThat(pair(server, tool).overrides().path("rowLimit").asInt()).isEqualTo(5);
      assertThat(pair(server, tool).mode()).as(tool).isEqualTo(Mode.APPROVAL);
    }
    assertThat(pair(server, "gh_list").storedMode()).isEqualTo(Mode.BYPASS);
    assertThat(reviewedDefinition(server, "gh_list")).isEqualTo("0".repeat(64));
    assertThat(reviewedDefinition(server, "gh_search")).isNull();
    // The tool list says why.
    EndpointTools.ToolRow row = tools.list(server).getFirst().tools().stream()
        .filter(t -> t.name().equals("gh_list")).findFirst().orElseThrow();
    assertThat(row.mode()).isEqualTo("Approval");
    assertThat(row.reason()).isEqualTo("The definition of gh_list changed since it was set to Bypass, so it doesn't "
        + "run without a person until you set the mode again for the definition it has now.");
    assertThat(tools.list(server).getFirst().tools().stream().filter(t -> t.name().equals("gh_search")).findFirst()
        .orElseThrow().reason()).startsWith("No definition of gh_search is recorded for Read only");

    // Chosen by a person for what it is now, it holds again.
    set(server, "gh_list", Mode.BYPASS);
    assertThat(pair(server, "gh_list").mode()).isEqualTo(Mode.BYPASS);
    assertThat(tools.list(server).getFirst().tools().stream().filter(t -> t.name().equals("gh_list")).findFirst()
        .orElseThrow().reason()).isNull();
  }

  @Test
  void aModeChosenForADefinitionTheToolNoLongerHasIsRefusedAndChangesNothing() {
    String server = "rev-stale";
    core.connection(server, "mcpfake", Env.DEV, "defs.test");
    endpoints.create(server, null, EndpointService.StartFrom.EMPTY, null);
    wiring.add(server, server, null);
    EndpointSnapshot.Pair shown = pair(server, "gh_search");
    String seen = ToolPairs.definitionHash(shown.tool());
    record(server, "gh_search", "0".repeat(64));
    // The server changed the tool after the page was loaded.
    list(server, "desc");
    EndpointSnapshot.Pair now = pair(server, "gh_search");

    for (Mode mode : List.of(Mode.READ_ONLY, Mode.BYPASS)) {
      assertThatThrownBy(() -> tools.setMode(server, now.toolKey(), server, mode, seen, null, now.rowVersion(), null))
          .isInstanceOfSatisfying(ProblemException.class, e -> {
            assertThat(e.kind()).isEqualTo(ProblemException.Kind.CONFLICT);
            assertThat(e.getMessage()).contains("gh_search changed while you were looking at it");
          });
    }
    assertThat(pair(server, "gh_search").rowVersion()).isEqualTo(now.rowVersion());
    assertThat(pair(server, "gh_search").storedMode()).isEqualTo(Mode.APPROVAL);
    // Stricter modes need no review: what the person saw doesn't matter for them.
    tools.setMode(server, now.toolKey(), server, Mode.OFF, seen, null, now.rowVersion(), null);
    assertThat(pair(server, "gh_search").storedMode()).isEqualTo(Mode.OFF);
    // The definition the person was shown now records it.
    set(server, "gh_search", Mode.READ_ONLY);
    assertThat(pair(server, "gh_search").mode()).isEqualTo(Mode.READ_ONLY);
    assertThat(reviewedDefinition(server, "gh_search")).isNotEqualTo("0".repeat(64)).isNotNull();
  }

  @Test
  void aModeSetWithoutTheDefinitionItWasChosenForIsStoredButRecordsNone() {
    String server = "rev-no-hash";
    core.connection(server, "mcpfake", Env.DEV, "defs.test");
    endpoints.create(server, null, EndpointService.StartFrom.EMPTY, null);
    wiring.add(server, server, null);
    record(server, "gh_write", "0".repeat(64));
    EndpointSnapshot.Pair p = pair(server, "gh_write");

    tools.setMode(server, p.toolKey(), server, Mode.BYPASS, null, null, p.rowVersion(), null);

    assertThat(pair(server, "gh_write").storedMode()).isEqualTo(Mode.BYPASS);
    assertThat(pair(server, "gh_write").mode()).isEqualTo(Mode.APPROVAL);
    assertThat(reviewedDefinition(server, "gh_write")).isEqualTo("0".repeat(64));
  }

  // ── Rows left without a definition ──────────────────────────────────────────────────────────────────────────

  /**
   * A row that keeps a stored Read only or Bypass without any recorded definition (the V89 start couldn't verify the
   * rows) goes to Approval at the next start whose rows verify, Off on a read-only endpoint, with one Setup change; a
   * row with a definition, a temporary bypass, a built-in tool and a row changed outside GateRook are left alone.
   */
  @Test
  void aStartStoresApprovalForRowsThatStillHaveNoDefinitionOnce() throws Exception {
    Path home;
    try (TestCore older = TestCore.start("mcp-review-unrecorded")) {
      older.connection("un-mcp", "mcpfake", Env.DEV, "defs.test");
      older.connection("un-db", "pgfake", Env.DEV, "db.test");
      EndpointService service = older.bean(EndpointService.class);
      EndpointConnections wired = older.bean(EndpointConnections.class);
      service.create("un", null, EndpointService.StartFrom.EMPTY, null);
      service.create("un-ro", null, EndpointService.StartFrom.EMPTY, null);
      service.updateSettings("un-ro", new EndpointService.SettingsUpdate(null, "read-only", null, null, null, null,
          null), service.get("un-ro").version(), null);
      for (String endpoint : List.of("un", "un-ro")) {
        wired.add(endpoint, "un-mcp", null);
      }
      wired.add("un", "un-db", null);
      EndpointSnapshotService s = older.bean(EndpointSnapshotService.class);
      EndpointTools t = older.bean(EndpointTools.class);
      EndpointSnapshot.Pair list = s.bySlug("un").orElseThrow().pairs().stream()
          .filter(p -> p.tool().name().equals("gh_list")).findFirst().orElseThrow();
      t.setMode("un", list.toolKey(), "un-mcp", Mode.BYPASS, ToolPairs.definitionHash(list.tool()), null,
          list.rowVersion(), null);
      EndpointSnapshot.Pair chart = s.bySlug("un").orElseThrow().pairs().stream()
          .filter(p -> p.tool().name().equals("gh_chart")).findFirst().orElseThrow();
      t.setTemporaryBypass("un", chart.toolKey(), "un-mcp", 30, chart.rowVersion(), null);
      EndpointRows rows = older.bean(EndpointRows.class);
      EndpointToolRepository repository = older.bean(EndpointToolRepository.class);
      new TransactionTemplate(older.bean(PlatformTransactionManager.class)).executeWithoutResult(x -> {
        for (EndpointToolEntity row : repository.findAll()) {
          // gh_search on "un" keeps its record.
          if (row.getToolKey().startsWith("mcp:") && (!row.getToolKey().endsWith(":gh_search")
              || !row.getEndpoint().getSlug().equals("un"))) {
            assertThat(rows.verify(row)).as(row.getToolKey()).isTrue();
            row.setReviewedDefinition(null, Instant.now());
            rows.save(row);
          }
        }
      });
      home = older.home();
    }
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + home.resolve("gateway.db"));
        Statement s = c.createStatement()) {
      // Changed outside GateRook: never signed again by the start.
      s.execute("UPDATE endpoint_tool SET overrides = '{\"rowLimit\":3}' WHERE tool_key = 'mcp:un-mcp:gh_search' AND "
          + "endpoint_id = (SELECT id FROM endpoint WHERE slug = 'un-ro')");
    }
    String held = "select change_from || ' > ' || change_to from setup_change where subject = 'MCP server tools "
        + "without a recorded definition'";
    try (TestCore next = TestCore.reopen(home)) {
      assertThat(stored(next, "un")).containsExactly("gh_chart=APPROVAL", "gh_list=APPROVAL", "gh_search=READ_ONLY",
          "gh_write=APPROVAL", "sql_execute=APPROVAL", "sql_query=READ_ONLY");
      assertThat(stored(next, "un-ro")).containsExactly("gh_chart=OFF", "gh_list=OFF", "gh_search=READ_ONLY",
          "gh_write=OFF");
      assertThat(next.jdbc().queryForList(held, String.class))
          .containsExactly("Read only or Bypass > Approval for 2 MCP server tools, Off for 2 MCP server tools on "
              + "read-only endpoints");
      // The temporary bypass is the grant's own: it stays, and still applies.
      EndpointSnapshot.Pair chart = next.bean(EndpointSnapshotService.class).bySlug("un").orElseThrow().pairs()
          .stream().filter(p -> p.tool().name().equals("gh_chart")).findFirst().orElseThrow();
      assertThat(chart.mode()).isEqualTo(Mode.BYPASS);
    }
    // Once: the next start finds nothing to do.
    try (TestCore again = TestCore.reopen(home)) {
      assertThat(again.jdbc().queryForList(held, String.class)).hasSize(1);
    }
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

  /**
   * The tools of the endpoint's only server as the tool list shows them now, with their definitions: what "Set all
   * to…" sends; {@code names} keeps only those (a search or filter showed only them).
   */
  private static List<EndpointTools.Shown> shown(String endpoint, String... names) {
    return snapshots.bySlug(endpoint).orElseThrow().pairs().stream()
        .filter(p -> names.length == 0 || List.of(names).contains(p.tool().name()))
        .map(p -> new EndpointTools.Shown(p.toolKey(), p.connectionName(), ToolPairs.definitionHash(p.tool())))
        .toList();
  }

  /** A person picks {@code mode} for the tool as the tool list shows it now. */
  private static void set(String endpoint, String tool, Mode mode) {
    EndpointSnapshot.Pair p = pair(endpoint, tool);
    tools.setMode(endpoint, p.toolKey(), p.connectionName(), mode, ToolPairs.definitionHash(p.tool()), null,
        p.rowVersion(), null);
  }

  /** The endpoint page saves the limits of a tool: its stored mode, no definition. */
  private static void saveLimits(String endpoint, String tool, String limits) {
    EndpointSnapshot.Pair p = pair(endpoint, tool);
    tools.setMode(endpoint, p.toolKey(), p.connectionName(), p.storedMode(), null, TestCore.json(limits),
        p.rowVersion(), null);
  }

  /** Grants the tool a temporary bypass of 30 minutes. */
  private static void grant(String endpoint, String tool) {
    EndpointSnapshot.Pair p = pair(endpoint, tool);
    tools.setTemporaryBypass(endpoint, p.toolKey(), p.connectionName(), 30, p.rowVersion(), null);
  }

  /** The endpoints reconcile the server's tools with nothing changed (a template change, a refresh). */
  private static void reconcile(String connection) {
    wiring.reconcile(connections.get(connection).id(), "test");
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

  /** The mode Setup changes of one tool on its own endpoint, oldest first: "from > to: reason". */
  private static List<String> modeChanges(String endpoint, String tool) {
    return core.jdbc().queryForList("select change_from || ' > ' || change_to || coalesce(': ' || reason, '') from "
        + "setup_change where what = 'mode' and endpoint_slug = ? and subject like ? order by id", String.class,
        endpoint, tool + " on %");
  }

  private static String reviewedDefinition(String endpoint, String tool) {
    return core.jdbc().queryForObject("select reviewed_definition from endpoint_tool where id = ?", String.class,
        pair(endpoint, tool).rowId());
  }

  /** The modes stored in the rows of an endpoint's connections in {@code c}, by tool name. */
  private static List<String> stored(TestCore c, String endpoint) {
    return c.jdbc().queryForList("select t.tool_key || '=' || t.mode from endpoint_tool t join endpoint e on e.id = "
        + "t.endpoint_id where e.slug = ? and t.connection_id is not null order by t.tool_key", String.class, endpoint)
        .stream().map(row -> row.substring(row.lastIndexOf(':') + 1)).toList();
  }

  /** The tools of an endpoint that hold a temporary bypass in their rows. */
  private static List<String> temporaryBypasses(String endpoint) {
    return core.jdbc().queryForList("select t.tool_key from endpoint_tool t join endpoint e on e.id = t.endpoint_id "
        + "where e.slug = ? and t.temporary_bypass is not null order by t.tool_key", String.class, endpoint).stream()
        .map(key -> key.substring(key.lastIndexOf(':') + 1)).toList();
  }
}
