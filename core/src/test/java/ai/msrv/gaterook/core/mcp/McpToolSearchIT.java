package ai.msrv.gaterook.core.mcp;

import static ai.msrv.gaterook.core.mcp.McpClient.args;
import static ai.msrv.gaterook.core.mcp.McpIT.names;
import static ai.msrv.gaterook.core.mcp.McpIT.tool;
import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.approvals.ApprovalService;
import ai.msrv.gaterook.core.approvals.ApprovalView;
import ai.msrv.gaterook.core.endpoints.CoreTools;
import ai.msrv.gaterook.core.endpoints.EndpointConnections;
import ai.msrv.gaterook.core.endpoints.EndpointService;
import ai.msrv.gaterook.core.endpoints.EndpointTools;
import ai.msrv.gaterook.core.endpoints.TokenService;
import ai.msrv.gaterook.core.platform.LocalListener;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.testfixtures.FakeConnectors;
import ai.msrv.gaterook.testfixtures.TestCore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * An endpoint that searches its tools (ARCHITECTURE.md §5.7) on a real core: tools/list shows the gateway's own tools,
 * {@code find_tools} finds the others, and {@code call_tool} and {@code call_read_tool} run them as the tools
 * themselves would run: their validation, modes, approvals and audit.
 */
class McpToolSearchIT {

  static TestCore core;
  static McpClient dev;
  static EndpointService endpoints;

  @BeforeAll
  static void start() {
    core = TestCore.start("mcp-tool-search");
    core.connection("dev-db", "pgfake", Env.DEV, "db.dev.internal");
    endpoints = core.bean(EndpointService.class);
    endpoints.create("dev", null, EndpointService.StartFrom.EMPTY, null);
    core.bean(EndpointConnections.class).add("dev", "dev-db", null);
    EndpointTools modes = core.bean(EndpointTools.class);
    for (String tool : CoreTools.WITH_MODE) {
      var pair = endpoints.snapshot("dev").coreTool(tool).orElseThrow();
      modes.setMode("dev", tool, null, Mode.READ_ONLY, null, null, pair.rowVersion(), null);
    }
    endpoints.updateSettings("dev", new EndpointService.SettingsUpdate(null, null, null, null, null,
        JsonNodeFactory.instance.objectNode().put("firstWaitSeconds", 0), null,
        JsonNodeFactory.instance.objectNode().put("mode", "search").put("mostUsed", 0)), endpoints.get("dev").version(),
        null);
    dev = new McpClient(core.bean(LocalListener.class).port(), "dev",
        core.bean(TokenService.class).issue("dev", "search-agent", 7, null).token());
  }

  @AfterAll
  static void stop() {
    if (core != null) {
      core.close();
    }
  }

  @Test
  void toolsListShowsTheGatewaysOwnToolsAndTheInstructionsSayHowToFindTheRest() {
    JsonNode list = dev.legacy("tools/list", null).result();
    assertThat(names(list.path("tools"))).containsExactly("approval_card", "approval_status", "call_read_tool",
        "call_tool", "connection_import_progress", "connection_import_schema", "describe_connection", "find_tools",
        "list_connections", "submit_connection_import");
    assertThat(tool(list, "call_tool").at("/annotations/readOnlyHint").asBoolean()).isFalse();
    assertThat(tool(list, "call_tool").at("/annotations/destructiveHint").asBoolean()).isTrue();
    assertThat(tool(list, "call_read_tool").at("/annotations/readOnlyHint").asBoolean()).isTrue();
    assertThat(tool(list, "call_tool").at("/execution/taskSupport").asString()).as("its call is the tool's")
        .isEqualTo("optional");
    assertThat(tool(list, "find_tools").has("execution")).isFalse();

    String instructions = dev.initialize("2025-11-25").result().path("instructions").asString();
    assertThat(instructions.substring(0, 512).replaceAll("\\s+", " "))
        .contains("find_tools finds the other 2 by what they do or by name");
    assertThat(instructions).doesNotContain("sql_execute changes data")
        .contains("- find_tools says which of the tools it finds wait for a person.")
        .endsWith("More tools, through find_tools\n- dev-db (FakePG): 2 tools");
  }

  @Test
  void findToolsFindsAToolByWhatItDoesWithWhatItNeeds() {
    JsonNode found = dev.legacyCall("find_tools", args("query", "change rows in the database")).result();
    assertThat(found.path("isError").asBoolean()).as(found.toString()).isFalse();
    JsonNode first = found.at("/structuredContent/tools/0");
    assertThat(first.path("name").asString()).isEqualTo("sql_execute");
    assertThat(first.path("readOnly").asBoolean()).isFalse();
    assertThat(first.path("approval").asString()).isEqualTo("required");
    assertThat(first.path("listed").asBoolean()).isFalse();
    assertThat(first.at("/inputSchema/properties/sql").isObject()).isTrue();
    assertThat(found.at("/content/0/text").asString()).contains("1. sql_execute")
        .contains("can change data · waits for a person's approval · dev-db").contains("inputSchema: {");

    JsonNode named = dev.legacyCall("find_tools", namesArgs("sql_query", "no_such_tool")).result();
    assertThat(named.at("/structuredContent/tools/0/name").asString()).isEqualTo("sql_query");
    assertThat(named.at("/structuredContent/tools/0/readOnly").asBoolean()).isTrue();
    assertThat(named.at("/structuredContent/tools/0/approval").asString()).isEqualTo("none");
    assertThat(named.at("/structuredContent/unknown/0").asString()).isEqualTo("no_such_tool");

    JsonNode neither = dev.legacyCall("find_tools", JsonNodeFactory.instance.objectNode()).result();
    assertThat(neither.path("isError").asBoolean()).isTrue();
    assertThat(neither.at("/content/0/text").asString()).startsWith("Give a query");

    // Audited like any call of the gateway's own tools.
    assertThat(core.jdbc().queryForList("select tool_name from tool_call where endpoint_slug = 'dev' and phase = "
        + "'completed'", String.class)).contains("find_tools");
  }

  @Test
  void callReadToolRunsAReadAsTheToolItselfWouldAndRefusesAChange() {
    JsonNode read = dev.legacyCall("call_read_tool", run("sql_query", args("connection", "dev-db", "sql",
        "SELECT 1"))).result();
    JsonNode direct = dev.legacyCall("sql_query", args("connection", "dev-db", "sql", "SELECT 1")).result();
    assertThat(read.path("isError").asBoolean()).as(read.toString()).isFalse();
    assertThat(read.at("/structuredContent/rows")).isEqualTo(direct.at("/structuredContent/rows"));
    String callId = read.at("/_meta/ai.msrv.gaterook~1callId").asString();
    assertThat(core.jdbc().queryForObject("select tool_name from tool_call where call_id = ? and phase = "
        + "'completed'", String.class, callId)).as("Activity shows the tool that ran").isEqualTo("sql_query");

    // Its schema checks the arguments, as for a direct call.
    JsonNode invalid = dev.legacyCall("call_read_tool", run("sql_query", args("connection", "dev-db", "sql",
        "SELECT 1", "bogus", "x"))).result();
    assertThat(invalid.path("isError").asBoolean()).isTrue();
    assertThat(invalid.at("/content/0/text").asString()).startsWith("Invalid arguments: ");

    JsonNode change = dev.legacyCall("call_read_tool", run("sql_execute", args("connection", "dev-db", "sql",
        "DELETE FROM orders"))).result();
    assertThat(change.path("isError").asBoolean()).isTrue();
    assertThat(change.at("/content/0/text").asString())
        .isEqualTo("sql_execute can change data, so call_read_tool doesn't run it: run it with call_tool.");

    JsonNode unknown = dev.legacyCall("call_tool", run("no_such_tool", null)).result();
    assertThat(unknown.path("isError").asBoolean()).isTrue();
    assertThat(unknown.at("/content/0/text").asString()).startsWith("There is no tool no_such_tool on dev.");
    JsonNode own = dev.legacyCall("call_tool", run("list_connections", null)).result();
    assertThat(own.at("/content/0/text").asString()).isEqualTo("list_connections is listed: call it directly.");
    JsonNode stray = dev.legacyCall("call_tool", args("tool", "sql_query")).result();
    assertThat(stray.at("/content/0/text").asString()).startsWith("call_tool takes name and arguments");

    JsonNode modern = dev.modernCall("call_read_tool", run("sql_query", args("connection", "dev-db", "sql",
        "SELECT 1")), false).result();
    assertThat(modern.path("isError").asBoolean()).as(modern.toString()).isFalse();
  }

  @Test
  void callToolWaitsForAPersonLikeTheToolItself() {
    FakeConnectors.EXECUTIONS.clear();
    String sql = "UPDATE orders SET status = 'cancelled' WHERE id = 7;";
    JsonNode pending = dev.legacyCall("call_tool", run("sql_execute", args("connection", "dev-db", "sql", sql)))
        .result();
    assertThat(pending.at("/content/0/text").asString()).startsWith("NOT EXECUTED YET.")
        .contains("Don't repeat sql_execute.");
    String handle = pending.at("/structuredContent/handle").asString();
    ApprovalService approvals = core.bean(ApprovalService.class);
    ApprovalView view = approvals.find(handle).orElseThrow();
    assertThat(view.tool()).isEqualTo("sql_execute");
    assertThat(FakeConnectors.EXECUTIONS).isEmpty();

    approvals.decide(handle, ApprovalService.Decision.APPROVE, "app", null, view.digest());
    ObjectNode poll = args("handle", handle);
    poll.put("wait_seconds", 10);
    JsonNode done = dev.legacyCall("approval_status", poll).result();
    assertThat(done.at("/structuredContent/status").asString()).isEqualTo("completed");
    assertThat(FakeConnectors.EXECUTIONS).hasSize(1);
  }

  @Test
  void allListsEveryToolAgainAndPinnedConnectionsStayListed() {
    try {
      setToolList(JsonNodeFactory.instance.objectNode().put("mode", "search").set("pinned",
          JsonNodeFactory.instance.arrayNode().add("dev-db")));
      JsonNode pinned = dev.legacy("tools/list", null).result();
      assertThat(names(pinned.path("tools"))).contains("find_tools", "sql_execute", "sql_query");

      setToolList(JsonNodeFactory.instance.objectNode().put("mode", "all").putNull("pinned"));
      JsonNode all = dev.legacy("tools/list", null).result();
      assertThat(names(all.path("tools"))).contains("sql_execute", "sql_query").doesNotContain("find_tools",
          "call_tool", "call_read_tool");
      // The short list of this endpoint needs no search in auto either.
      setToolList(JsonNodeFactory.instance.objectNode().put("mode", "auto"));
      assertThat(names(dev.legacy("tools/list", null).result().path("tools"))).contains("sql_query")
          .doesNotContain("find_tools");
      assertThat(dev.legacyCall("call_tool", run("sql_query", args("connection", "dev-db", "sql", "SELECT 1")))
          .error().path("message").asString()).isEqualTo("Unknown tool: call_tool");
    } finally {
      setToolList(JsonNodeFactory.instance.objectNode().put("mode", "search").putNull("pinned"));
    }
  }

  /** The tools agents call most are listed; the count holds for a day, so this test recounts at once. */
  @Test
  void theToolsAgentsCallMostAreListedAndTheOthersAreFoundWhereTheyAre() {
    endpoints.create("pop", null, EndpointService.StartFrom.EMPTY, null);
    core.bean(EndpointConnections.class).add("pop", "dev-db", null);
    var listing = endpoints.snapshot("pop").coreTool(CoreTools.LIST_CONNECTIONS).orElseThrow();
    core.bean(EndpointTools.class).setMode("pop", CoreTools.LIST_CONNECTIONS, null, Mode.READ_ONLY, null, null,
        listing.rowVersion(), null);
    endpoints.updateSettings("pop", new EndpointService.SettingsUpdate(null, null, null, null, null, null, null,
        JsonNodeFactory.instance.objectNode().put("mode", "search")), endpoints.get("pop").version(), null);
    McpClient pop = new McpClient(core.bean(LocalListener.class).port(), "pop",
        core.bean(TokenService.class).issue("pop", "popular-agent", 7, null).token());
    JsonNode before = pop.legacy("tools/list", null).result();
    assertThat(names(before.path("tools"))).doesNotContain("sql_query", "sql_execute");
    assertThat(tool(before, "find_tools").path("description").asString())
        .startsWith("Finds the tools of this endpoint that tools/list doesn't show: 2 on dev-db (FakePG).");

    for (int i = 0; i < 3; i++) {
      JsonNode ran = pop.legacyCall("call_read_tool", run("sql_query", args("connection", "dev-db", "sql",
          "SELECT " + i))).result();
      assertThat(ran.path("isError").asBoolean()).as(ran.toString()).isFalse();
    }
    core.bean(ai.msrv.gaterook.core.insights.PopularTools.class).recountNow();

    JsonNode after = pop.legacy("tools/list", null).result();
    assertThat(names(after.path("tools"))).contains("sql_query").doesNotContain("sql_execute");
    assertThat(tool(after, "find_tools").path("description").asString())
        .startsWith("Finds the tools of this endpoint that tools/list doesn't show: 1 on dev-db (FakePG).");
    String instructions = pop.initialize("2025-11-25").result().path("instructions").asString();
    assertThat(instructions.replaceAll("\\s+", " "))
        .contains("tools/list shows GateRook's own tools and the one agents call most here. find_tools finds the "
            + "other 1 by what they do or by name");
    JsonNode connections = pop.legacyCall("list_connections", JsonNodeFactory.instance.objectNode()).result();
    assertThat(connections.at("/content/0/text").asString()).contains("sql_execute (approval)*")
        .contains("* not in tools/list: find_tools gives a tool's definition");
  }

  private static void setToolList(JsonNode toolList) {
    endpoints.updateSettings("dev", new EndpointService.SettingsUpdate(null, null, null, null, null, null, null,
        toolList), endpoints.get("dev").version(), null);
  }

  private static ObjectNode run(String name, ObjectNode arguments) {
    ObjectNode out = JsonNodeFactory.instance.objectNode().put("name", name);
    if (arguments != null) {
      out.set("arguments", arguments);
    }
    return out;
  }

  private static ObjectNode namesArgs(String... names) {
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    for (String n : names) {
      out.withArray("names").add(n);
    }
    return out;
  }
}
