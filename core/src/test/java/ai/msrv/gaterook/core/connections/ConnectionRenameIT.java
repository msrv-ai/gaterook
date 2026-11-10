package ai.msrv.gaterook.core.connections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.core.endpoints.EndpointConnections;
import ai.msrv.gaterook.core.endpoints.EndpointService;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshot;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshotService;
import ai.msrv.gaterook.core.endpoints.EndpointTools;
import ai.msrv.gaterook.core.platform.ProblemException;
import ai.msrv.gaterook.testfixtures.TestCore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * Renaming a connection on a real core with the fake connectors (ARCHITECTURE.md §8.6 contract 13): the row, its
 * secrets' names and an MCP server's pairs with their modes follow the new name in one transaction, a Setup change
 * records it, the old name is free for a new connection, and a refused rename changes nothing.
 */
class ConnectionRenameIT {

  private static TestCore core;
  private static ConnectionService connections;
  private static EndpointService endpoints;
  private static EndpointConnections wiring;
  private static EndpointTools tools;
  private static EndpointSnapshotService snapshots;

  @BeforeAll
  static void start() {
    core = TestCore.start("connection-rename");
    connections = core.bean(ConnectionService.class);
    endpoints = core.bean(EndpointService.class);
    wiring = core.bean(EndpointConnections.class);
    tools = core.bean(EndpointTools.class);
    snapshots = core.bean(EndpointSnapshotService.class);
  }

  @AfterAll
  static void stop() {
    if (core != null) {
      core.close();
    }
  }

  private static ConnectionService.Summary rename(String from, String to) {
    ConnectionService.Detail d = connections.get(from);
    return connections.update(from, to, d.env(), d.config(), d.version(), "clearer name", null, Map.of());
  }

  private static List<String> pairs(String connectionId) {
    return core.jdbc().queryForList("select tool_key || '=' || mode from endpoint_tool where connection_id = ? "
        + "order by tool_key", String.class, connectionId);
  }

  private static EndpointSnapshot.Pair pair(String slug, String tool) {
    return snapshots.bySlug(slug).orElseThrow().pairs().stream().filter(p -> p.tool().name().equals(tool))
        .findFirst().orElseThrow();
  }

  @Test
  void anMcpServerKeepsItsModesSecretsAndEndpointsUnderItsNewNameAndTheOldNameIsFree() {
    ConnectionService.Summary created = core.connection("ren-mcp", "mcpfake", Env.DEV, "modes.test");
    endpoints.create("ren-ep", null, EndpointService.StartFrom.EMPTY, null);
    wiring.add("ren-ep", "ren-mcp", null);
    EndpointSnapshot.Pair write = pair("ren-ep", "gh_write");
    tools.setMode("ren-ep", write.toolKey(), "ren-mcp", Mode.BYPASS, null, null, write.rowVersion(), null);
    assertThat(pairs(created.id())).containsExactly("mcp:ren-mcp:gh_search=READ_ONLY",
        "mcp:ren-mcp:gh_write=BYPASS");

    ConnectionService.Summary renamed = rename("ren-mcp", "ren-tracker");

    assertThat(renamed.id()).isEqualTo(created.id());
    assertThat(renamed.name()).isEqualTo("ren-tracker");
    assertThat(renamed.verified()).isTrue();
    assertThat(renamed.usedBy()).containsExactly("ren-ep");
    assertThat(connections.get("ren-tracker").secrets()).extracting(ConnectionService.SecretField::name)
        .containsExactly("ren-tracker/password");
    assertThatThrownBy(() -> connections.get("ren-mcp")).isInstanceOfSatisfying(ProblemException.class,
        e -> assertThat(e.kind()).isEqualTo(ProblemException.Kind.NOT_FOUND));
    assertThat(pairs(created.id())).containsExactly("mcp:ren-tracker:gh_search=READ_ONLY",
        "mcp:ren-tracker:gh_write=BYPASS");
    assertThat(pair("ren-ep", "gh_write").connectionName()).isEqualTo("ren-tracker");
    assertThat(pair("ren-ep", "gh_write").mode()).isEqualTo(Mode.BYPASS);
    assertThat(pair("ren-ep", "gh_search").mode()).isEqualTo(Mode.READ_ONLY);
    // What the change event brings the pairs in line with finds nothing left to add, drop or turn Off.
    wiring.reconcile(created.id(), null);
    assertThat(pairs(created.id())).containsExactly("mcp:ren-tracker:gh_search=READ_ONLY",
        "mcp:ren-tracker:gh_write=BYPASS");
    assertThat(core.jdbc().queryForList("select change_from || ' > ' || change_to || ' @ ' || connection_name "
        + "from setup_change where subject = 'Connection ren-mcp renamed to ren-tracker'", String.class))
        .containsExactly("ren-mcp > ren-tracker @ ren-tracker");
    assertThat(core.jdbc().queryForObject("select count(*) from setup_change where subject = "
        + "'Connection ren-tracker edited'", Integer.class)).as("nothing else changed").isZero();

    ConnectionService.Summary reused = core.connection("ren-mcp", "pgfake", Env.DEV, "db.test");
    assertThat(reused.id()).isNotEqualTo(created.id());
    assertThat(connections.get("ren-mcp").secrets()).extracting(ConnectionService.SecretField::name)
        .containsExactly("ren-mcp/password");
    assertThat(connections.get("ren-tracker").version()).isEqualTo(renamed.version());
  }

  @Test
  void aRenameWithOtherChangesRecordsBothAndTheNewSettingsApply() {
    core.connection("ren-both", "pgfake", Env.DEV, "db.test");
    ConnectionService.Detail d = connections.get("ren-both");
    ObjectNode edited = (ObjectNode) d.config().deepCopy();
    ((ObjectNode) edited.path("credentials")).put("user", "reader");

    ConnectionService.Summary s = connections.update("ren-both", "ren-reader", Env.STAGE, edited, d.version(),
        "reader account", null, Map.of());

    assertThat(s.env()).isEqualTo(Env.STAGE);
    assertThat(connections.get("ren-reader").config().at("/credentials/user").asString()).isEqualTo("reader");
    assertThat(core.jdbc().queryForList("select subject from setup_change where connection_name = 'ren-reader' "
        + "order by id", String.class)).containsExactly("Connection ren-both renamed to ren-reader",
            "Connection ren-reader edited");
  }

  @Test
  void aNameInUseOrNotASlugIsRefusedAndNothingChanges() {
    core.connection("ren-a", "pgfake", Env.DEV, "db.test");
    core.connection("ren-b", "pgfake", Env.DEV, "db.test");
    long version = connections.get("ren-a").version();

    assertThatThrownBy(() -> rename("ren-a", "ren-b")).isInstanceOfSatisfying(ProblemException.class, e -> {
      assertThat(e.kind()).isEqualTo(ProblemException.Kind.CONFLICT);
      assertThat(e.getMessage()).isEqualTo("A connection named ren-b already exists.");
    });
    for (String bad : List.of("Ren A", "ren--a", "-ren", "r".repeat(65), "")) {
      assertThatThrownBy(() -> rename("ren-a", bad)).as(bad).isInstanceOfSatisfying(ProblemException.class,
          e -> assertThat(e.field()).isEqualTo("/name"));
    }

    assertThat(connections.get("ren-a").version()).isEqualTo(version);
    assertThat(connections.get("ren-a").secrets()).extracting(ConnectionService.SecretField::name)
        .containsExactly("ren-a/password");
    assertThat(rename("ren-a", "ren-a").version()).as("its own name is no rename").isGreaterThan(version);
    assertThat(core.jdbc().queryForObject("select count(*) from setup_change where subject like "
        + "'Connection ren-a renamed%'", Integer.class)).isZero();
  }

  @Test
  void aPairChangedOutsideGateRookVetoesTheRenameAndEverythingRollsBack() {
    ConnectionService.Summary created = core.connection("ren-veto", "mcpfake", Env.DEV, "modes.test");
    endpoints.create("ren-veto-ep", null, EndpointService.StartFrom.EMPTY, null);
    wiring.add("ren-veto-ep", "ren-veto", null);
    core.jdbc().update("update endpoint_tool set mode = 'BYPASS' where connection_id = ? and tool_key = ?",
        created.id(), "mcp:ren-veto:gh_write");

    assertThatThrownBy(() -> rename("ren-veto", "ren-vetoed")).isInstanceOfSatisfying(ProblemException.class, e -> {
      assertThat(e.kind()).isEqualTo(ProblemException.Kind.CONFLICT);
      assertThat(e.getMessage()).contains("Endpoint ren-veto-ep was changed outside GateRook");
    });

    assertThat(connections.get("ren-veto").version()).isEqualTo(created.version());
    assertThat(connections.get("ren-veto").secrets()).extracting(ConnectionService.SecretField::name)
        .containsExactly("ren-veto/password");
    assertThat(pairs(created.id())).containsExactly("mcp:ren-veto:gh_search=READ_ONLY",
        "mcp:ren-veto:gh_write=BYPASS");
    assertThat(core.jdbc().queryForObject("select count(*) from connection where name = 'ren-vetoed'",
        Integer.class)).isZero();
    assertThat(core.jdbc().queryForObject("select count(*) from setup_change where subject like "
        + "'Connection ren-veto renamed%'", Integer.class)).isZero();
  }
}
