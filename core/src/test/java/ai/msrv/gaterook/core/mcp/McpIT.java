package ai.msrv.gaterook.core.mcp;

import static ai.msrv.gaterook.core.mcp.McpClient.args;
import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.approvals.ApprovalService;
import ai.msrv.gaterook.core.approvals.ApprovalView;
import ai.msrv.gaterook.core.connectors.PluginGenerationSwitched;
import ai.msrv.gaterook.core.endpoints.EndpointConnections;
import ai.msrv.gaterook.core.endpoints.EndpointService;
import ai.msrv.gaterook.core.endpoints.TokenService;
import ai.msrv.gaterook.core.platform.LocalListener;
import ai.msrv.gaterook.testfixtures.FakeConnectors;
import ai.msrv.gaterook.testfixtures.TestCore;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The MCP runtime on a real core with the fake connectors (ARCHITECTURE.md M1.5): both revisions on one URL, the
 * transport's refusals, reads, approvals through the handle and through MRTR, every answer checked against the
 * specification's schema of its revision and, for tools with one, against the published {@code outputSchema}.
 */
class McpIT {

  static TestCore core;
  static McpClient dev;
  static McpClient prod;
  static ApprovalService approvals;

  @BeforeAll
  static void start() {
    core = TestCore.start("mcp");
    core.connection("dev-db", "pgfake", Env.DEV, "db.dev.internal");
    core.connection("prod-db", "pgfake", Env.PROD, "db.prod.internal");
    EndpointService endpoints = core.bean(EndpointService.class);
    EndpointConnections wiring = core.bean(EndpointConnections.class);
    endpoints.create("dev", null, EndpointService.StartFrom.EMPTY, null);
    wiring.add("dev", "dev-db", null);
    endpoints.create("incident-prod", null, EndpointService.StartFrom.EMPTY, null);
    wiring.add("incident-prod", "prod-db", null);
    ai.msrv.gaterook.core.endpoints.EndpointTools modes = core.bean(ai.msrv.gaterook.core.endpoints.EndpointTools.class);
    for (String tool : ai.msrv.gaterook.core.endpoints.CoreTools.WITH_MODE) {
      var pair = endpoints.snapshot("dev").coreTool(tool).orElseThrow();
      modes.setMode("dev", tool, null, ai.msrv.gaterook.connector.api.ToolDefinition.Mode.READ_ONLY, null,
          pair.rowVersion(), null);
    }
    // The first answer waits 2 s for a quick decision here (15 s by default), so the tests stay short.
    for (String slug : List.of("dev", "incident-prod")) {
      endpoints.updateSettings(slug, new EndpointService.SettingsUpdate(null, null, null, null, null,
          JsonNodeFactory.instance.objectNode().put("firstWaitSeconds", 2), null), endpoints.get(slug).version(),
          null);
    }
    TokenService tokens = core.bean(TokenService.class);
    int port = core.bean(LocalListener.class).port();
    dev = new McpClient(port, "dev", tokens.issue("dev", "inspector-debug", 7, null).token());
    prod = new McpClient(port, "incident-prod", tokens.issue("incident-prod", "ops", 7, null).token());
    approvals = core.bean(ApprovalService.class);
  }

  @AfterAll
  static void stop() {
    if (core != null) {
      core.close();
    }
  }

  // ── Transport ───────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void theTransportRefusesWhatIsNotOneJsonRpcRequest() {
    assertThat(dev.send("GET", null, null, Map.of()).status()).isEqualTo(405);
    assertThat(dev.send("GET", null, null, Map.of()).header("Allow")).isEqualTo("POST");
    assertThat(dev.send("DELETE", null, null, Map.of()).status()).isEqualTo(405);
    assertThat(dev.send("POST", "{}", "text/plain", Map.of()).status()).isEqualTo(415);
    assertThat(dev.send("POST", "x".repeat((1 << 20) + 10), "application/json", Map.of()).status()).isEqualTo(413);
    McpClient.Answer noToken = dev.as(null).post("{}", Map.of());
    assertThat(noToken.status()).isEqualTo(401);
    assertThat(noToken.header("WWW-Authenticate")).isEqualTo("Bearer realm=\"mcp-gateway\"");
    assertThat(dev.as("gwt_not-a-token").post("{}", Map.of()).status()).isEqualTo(401);
    // A token of another endpoint is no token here.
    assertThat(dev.on("incident-prod", tokenOf(dev)).post("{}", Map.of()).status()).isEqualTo(401);

    McpClient.Answer batch = dev.post("[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}]", Map.of());
    assertThat(batch.status()).isEqualTo(400);
    assertThat(batch.error().path("code").asInt()).isEqualTo(-32600);
    assertThat(batch.error().path("message").asString()).isEqualTo("Batch requests are not supported");
    McpClient.Answer parse = dev.post("{not json", Map.of());
    assertThat(parse.status()).isEqualTo(400);
    assertThat(parse.error().path("code").asInt()).isEqualTo(-32700);
    McpClient.Answer response = dev.post("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}", Map.of());
    assertThat(response.error().path("code").asInt()).isEqualTo(-32600);
    McpClient.Answer notification = dev.post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
        Map.of("MCP-Protocol-Version", "2025-11-25"));
    assertThat(notification.status()).isEqualTo(202);
    assertThat(notification.header("Mcp-Session-Id")).isNull();
  }

  @Test
  void eachEraIsCheckedBeforeAnythingRuns() {
    // Unknown method: legacy 200, modern 404 (MUST).
    McpClient.Answer legacy = dev.legacy("completion/complete", null);
    assertThat(legacy.status()).isEqualTo(200);
    assertThat(legacy.error().path("code").asInt()).isEqualTo(-32601);
    McpClient.Answer modern = dev.modern("subscriptions/listen", JsonNodeFactory.instance.objectNode(), false);
    assertThat(modern.status()).isEqualTo(404);
    assertThat(modern.error().path("code").asInt()).isEqualTo(-32601);
    SpecSchemas.assertValid("2026-07-28", "JSONRPCErrorResponse", modern.body());

    // A modern version the gateway doesn't speak.
    ObjectNode future = JsonNodeFactory.instance.objectNode();
    future.putObject("_meta").put("io.modelcontextprotocol/protocolVersion", "2027-01-01")
        .putObject("io.modelcontextprotocol/clientCapabilities");
    McpClient.Answer unsupported = dev.post(dev.request("server/discover", future).toString(),
        Map.of("MCP-Protocol-Version", "2027-01-01", "Mcp-Method", "server/discover"));
    assertThat(unsupported.status()).isEqualTo(400);
    assertThat(unsupported.error().path("code").asInt()).isEqualTo(-32022);
    assertThat(unsupported.error().path("data").path("supported").toString()).contains("2026-07-28", "2025-11-25");

    // Headers that don't match the body.
    ObjectNode p = JsonNodeFactory.instance.objectNode();
    p.put("name", "sql_query");
    p.set("arguments", args("connection", "dev-db", "sql", "SELECT 1"));
    p.putObject("_meta").put("io.modelcontextprotocol/protocolVersion", "2026-07-28")
        .putObject("io.modelcontextprotocol/clientCapabilities");
    McpClient.Answer mismatch = dev.post(dev.request("tools/call", p).toString(), Map.of("MCP-Protocol-Version",
        "2026-07-28", "Mcp-Method", "tools/call", "Mcp-Name", "sql_execute"));
    assertThat(mismatch.status()).isEqualTo(400);
    assertThat(mismatch.error().path("code").asInt()).isEqualTo(-32020);
    // The base64 sentinel of Mcp-Name is decoded first.
    McpClient.Answer sentinel = dev.post(dev.request("tools/call", p.deepCopy()).toString(), Map.of(
        "MCP-Protocol-Version", "2026-07-28", "Mcp-Method", "tools/call", "Mcp-Name",
        "=?base64?" + java.util.Base64.getEncoder().encodeToString("sql_query".getBytes()) + "?="));
    assertThat(sentinel.status()).isEqualTo(200);
    // Header names in any case.
    McpClient.Answer lower = dev.post(dev.request("tools/call", p.deepCopy()).toString(), Map.of(
        "mcp-protocol-version", "2026-07-28", "mcp-method", "tools/call", "MCP-NAME", "sql_query"));
    assertThat(lower.status()).isEqualTo(200);
    assertThat(lower.result().path("resultType").asString()).isEqualTo("complete");

    // No client capabilities.
    ObjectNode bare = JsonNodeFactory.instance.objectNode();
    bare.putObject("_meta").put("io.modelcontextprotocol/protocolVersion", "2026-07-28");
    McpClient.Answer noCaps = dev.post(dev.request("tools/list", bare).toString(), Map.of("MCP-Protocol-Version",
        "2026-07-28", "Mcp-Method", "tools/list"));
    assertThat(noCaps.status()).isEqualTo(400);
    assertThat(noCaps.error().path("code").asInt()).isEqualTo(-32602);

    // The modern header without the _meta version: refused, so the client doesn't fall back silently.
    McpClient.Answer half = dev.post(dev.request("tools/list", null).toString(),
        Map.of("MCP-Protocol-Version", "2026-07-28"));
    assertThat(half.status()).isEqualTo(400);
    assertThat(half.error().path("code").asInt()).isEqualTo(-32602);

    // No header at all: 2025-03-26 is assumed.
    McpClient.Answer old = dev.post(dev.request("tools/list", null).toString(), Map.of());
    assertThat(old.status()).isEqualTo(200);
    assertThat(old.result().has("resultType")).isFalse();
  }

  @Test
  void legacyInitializeAndModernDiscoverDescribeTheEndpoint() {
    McpClient.Answer init = dev.initialize("2025-11-25");
    assertThat(init.status()).isEqualTo(200);
    assertThat(init.header("Mcp-Session-Id")).isNull();
    SpecSchemas.assertValid("2025-11-25", "InitializeResult", init.result());
    assertThat(init.result().path("protocolVersion").asString()).isEqualTo("2025-11-25");
    assertThat(init.result().path("serverInfo").path("title").asString()).isEqualTo("MCP Gateway · dev");
    assertThat(init.result().path("capabilities").path("tools").path("listChanged").asBoolean(true)).isFalse();
    String instructions = init.result().path("instructions").asString();
    assertThat(instructions).startsWith("You are connected to MCP Gateway, endpoint \"dev\" (local development).")
        .contains("- sql_execute changes data.\n  Every call waits until a person approves the exact action.")
        .contains("poll approval_status(handle). Don't repeat the call.")
        .contains("- You never receive passwords, keys or tokens. Don't ask for them.\n"
            + "- ‹masked› marks a secret the gateway removed. Don't try to recover it.\n\n");
    assertThat(init.result().has("resultType")).isFalse();
    // Codex asks for 2025-06-18; an unknown legacy version gets 2025-11-25.
    assertThat(dev.initialize("2025-06-18").result().path("protocolVersion").asString()).isEqualTo("2025-06-18");
    assertThat(dev.initialize("2024-01-01").result().path("protocolVersion").asString()).isEqualTo("2025-11-25");

    McpClient.Answer discover = dev.modern("server/discover", JsonNodeFactory.instance.objectNode(), false);
    assertThat(discover.status()).isEqualTo(200);
    SpecSchemas.assertValid("2026-07-28", "DiscoverResult", discover.result());
    assertThat(discover.result().path("resultType").asString()).isEqualTo("complete");
    assertThat(discover.result().path("supportedVersions").toString()).isEqualTo("[\"2026-07-28\",\"2025-11-25\"]");
    assertThat(discover.result().path("ttlMs").asLong()).isEqualTo(60_000);
    assertThat(discover.result().path("cacheScope").asString()).isEqualTo("private");
    assertThat(discover.result().path("_meta").path("io.modelcontextprotocol/serverInfo").path("name").asString())
        .isEqualTo("mcp-gateway");
    assertThat(discover.result().path("instructions").asString()).isEqualTo(instructions);
  }

  @Test
  void toolsListPublishesTheEndpointsToolsInBothEras() {
    McpClient.Answer legacy = dev.legacy("tools/list", null);
    SpecSchemas.assertValid("2025-11-25", "ListToolsResult", legacy.result());
    McpClient.Answer modern = dev.modern("tools/list", JsonNodeFactory.instance.objectNode(), false);
    SpecSchemas.assertValid("2026-07-28", "ListToolsResult", modern.result());
    assertThat(modern.result().path("ttlMs").asLong()).isEqualTo(30_000);
    assertThat(modern.result().path("cacheScope").asString()).isEqualTo("private");
    for (JsonNode list : List.of(legacy.result(), modern.result())) {
      assertThat(names(list.path("tools"))).containsExactly("approval_status", "describe_connection",
          "list_connections", "sql_execute", "sql_query");
      JsonNode query = tool(list, "sql_query");
      assertThat(query.path("outputSchema").isObject()).isTrue();
      assertThat(query.at("/inputSchema/properties/connection/enum").toString()).isEqualTo("[\"dev-db\"]");
      assertThat(query.at("/annotations/readOnlyHint").asBoolean()).isTrue();
      // A tool that waits for approval publishes no outputSchema: a pending handle wouldn't match it.
      assertThat(tool(list, "sql_execute").has("outputSchema")).isFalse();
      assertThat(tool(list, "sql_execute").at("/annotations/destructiveHint").asBoolean()).isTrue();
    }
    assertThat(legacy.result().has("ttlMs")).isFalse();
    // A cursor that names another list, or nothing, is refused.
    ObjectNode bad = JsonNodeFactory.instance.objectNode().put("cursor", "eyJvIjoxMDAsImgiOiJ4In0");
    assertThat(dev.legacy("tools/list", bad).error().path("code").asInt()).isEqualTo(-32602);
  }

  // ── Reads ───────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void aReadRunsAtOnceAndIsAuditedBeforeItsResultLeaves() {
    for (boolean modern : new boolean[] {false, true}) {
      ObjectNode a = args("connection", "dev-db", "sql", "SELECT id, status FROM orders");
      McpClient.Answer r = modern ? dev.modernCall("sql_query", a, false) : dev.legacyCall("sql_query", a);
      assertThat(r.status()).isEqualTo(200);
      String revision = modern ? "2026-07-28" : "2025-11-25";
      SpecSchemas.assertValid(revision, "CallToolResult", r.result());
      JsonNode result = r.result();
      assertThat(result.path("isError").asBoolean()).isFalse();
      assertThat(result.at("/content/0/text").asString()).startsWith("3 rows · ").contains("id     status")
          .contains("48240  pending");
      JsonNode s = result.path("structuredContent");
      assertThat(s.path("columns").toString()).isEqualTo("[\"id\",\"status\"]");
      assertThat(s.path("rowCount").asInt()).isEqualTo(3);
      assertThat(s.path("truncated").asBoolean()).isFalse();
      SpecSchemas.assertMatchesOutputSchema(tool(dev.legacy("tools/list", null).result(), "sql_query")
          .path("outputSchema"), s);
      String callId = result.at("/_meta/ai.msrv.gaterook~1callId").asString();
      assertThat(callId).startsWith("tc_");
      assertThat(core.jdbc().queryForList("select phase, decision from tool_call where call_id = ? order by id",
          callId)).extracting(m -> m.get("phase") + ":" + m.get("decision"))
          .containsExactly("received:allowed", "completed:allowed");
      Map<String, Object> completed = core.jdbc().queryForMap("select protocol_version, client, result_bytes, "
          + "result_sha256, token_label, args_masked from tool_call where call_id = ? and phase = 'completed'",
          callId);
      assertThat(completed.get("protocol_version")).isEqualTo(revision);
      assertThat(completed.get("client")).isEqualTo("codex");
      assertThat(completed.get("token_label")).isEqualTo("inspector-debug");
      assertThat(((Number) completed.get("result_bytes")).longValue()).isPositive();
    }
  }

  @Test
  void connectorErrorsSecretsAndBigResultsReachTheAgentSafely() {
    McpClient.Answer failed = dev.legacyCall("sql_query", args("connection", "dev-db", "sql", "SELECT fail"));
    assertThat(failed.result().path("isError").asBoolean()).isTrue();
    assertThat(failed.result().at("/content/0/text").asString()).isEqualTo("relation \"missing\" does not exist");
    McpClient.Answer boom = dev.legacyCall("sql_query", args("connection", "dev-db", "sql", "SELECT boom"));
    assertThat(boom.result().at("/content/0/text").asString()).startsWith("Internal error in FakePG. See Activity tc_")
        .doesNotContain("boom");
    McpClient.Answer secret = dev.legacyCall("sql_query", args("connection", "dev-db", "sql", "SELECT secret"));
    assertThat(secret.result().at("/content/0/text").asString()).isEqualTo("the password is ‹masked›");
    assertThat(secret.result().at("/_meta/ai.msrv.gaterook~1masked/fields").asInt()).isEqualTo(1);

    McpClient.Answer big = dev.modernCall("sql_query", args("connection", "dev-db", "sql", "SELECT big"), false);
    SpecSchemas.assertValid("2026-07-28", "CallToolResult", big.result());
    JsonNode s = big.result().path("structuredContent");
    assertThat(s.path("truncated").asBoolean()).isTrue();
    assertThat(s.path("rowCount").asInt()).isLessThan(1_000);
    assertThat(s.path("truncatedReason").asString()).isEqualTo("size_limit");
    assertThat(McpClient.JSON.writeValueAsString(big.result()).length()).isLessThanOrEqualTo(512 * 1024 + 4_096);
    SpecSchemas.assertMatchesOutputSchema(tool(dev.legacy("tools/list", null).result(), "sql_query")
        .path("outputSchema"), s);
    McpClient.Answer bigLegacy = dev.legacyCall("sql_query", args("connection", "dev-db", "sql", "SELECT big"));
    SpecSchemas.assertValid("2025-11-25", "CallToolResult", bigLegacy.result());
    assertThat(bigLegacy.result().at("/_meta/ai.msrv.gaterook~1truncated/reason").asString()).isEqualTo("size_limit");
    SpecSchemas.assertMatchesOutputSchema(tool(dev.legacy("tools/list", null).result(), "sql_query")
        .path("outputSchema"), bigLegacy.result().path("structuredContent"));

    McpClient.Answer badArgs = dev.legacyCall("sql_query", args("connection", "dev-db", "sql", "x", "nope", "1"));
    assertThat(badArgs.result().path("isError").asBoolean()).isTrue();
    assertThat(badArgs.result().at("/content/0/text").asString()).startsWith("Invalid arguments: ");
    McpClient.Answer other = dev.legacyCall("sql_query", args("connection", "prod-db", "sql", "SELECT 1"));
    assertThat(other.result().path("isError").asBoolean()).isTrue();
    McpClient.Answer unknown = dev.modernCall("drop_everything", args(), false);
    assertThat(unknown.status()).isEqualTo(400);
    assertThat(unknown.error().path("code").asInt()).isEqualTo(-32602);
    assertThat(dev.legacyCall("drop_everything", args()).status()).isEqualTo(200);
  }

  @Test
  void theCoreToolsAnswerThroughTheMaskerOfTheirEnvironment() {
    // A visible setting with personal data in it, on prod (where the policy masks personal data by default).
    ObjectNode config = TestCore.config("db.notes.internal");
    ((ObjectNode) config.get("connection")).put("note", "Owner ops.lead@example.com");
    core.bean(ai.msrv.gaterook.core.connections.ConnectionService.class).create("notes-db", "pgfake", Env.PROD, config,
        Map.of("/credentials/password", ai.msrv.gaterook.core.connections.SecretInput.local("pw-notes-db-0123"
            .toCharArray())), "test");
    EndpointService endpoints = core.bean(EndpointService.class);
    endpoints.create("notes-prod", null, EndpointService.StartFrom.EMPTY, null);
    core.bean(EndpointConnections.class).add("notes-prod", "notes-db", null);
    var modes = core.bean(ai.msrv.gaterook.core.endpoints.EndpointTools.class);
    for (String tool : ai.msrv.gaterook.core.endpoints.CoreTools.WITH_MODE) {
      modes.setMode("notes-prod", tool, null, ai.msrv.gaterook.connector.api.ToolDefinition.Mode.READ_ONLY, null,
          endpoints.snapshot("notes-prod").coreTool(tool).orElseThrow().rowVersion(), null);
    }
    McpClient agent = new McpClient(core.bean(LocalListener.class).port(), "notes-prod", core.bean(TokenService.class)
        .issue("notes-prod", "notes-agent", 7, null).token());

    McpClient.Answer described = agent.legacyCall("describe_connection", args("connection", "notes-db"));
    SpecSchemas.assertValid("2025-11-25", "CallToolResult", described.result());
    JsonNode s = described.result().path("structuredContent");
    assertThat(s.path("settings").path("connection.note").asString()).isEqualTo("Owner ‹masked›");
    // The core's own constant survives the pass: the agent learns the password is stored, not that it was masked.
    assertThat(s.path("credentials").path("/credentials/password").asString()).isEqualTo("stored");
    assertThat(described.result().at("/_meta/ai.msrv.gaterook~1masked/fields").asInt()).isPositive();
    assertThat(described.body().toString()).doesNotContain("ops.lead@example.com").doesNotContain("pw-notes-db");
    String callId = described.result().at("/_meta/ai.msrv.gaterook~1callId").asString();
    assertThat(core.jdbc().queryForObject("select result_preview from tool_call where call_id = ? and phase = "
        + "'completed'", String.class, callId)).doesNotContain("ops.lead@example.com");

    McpClient.Answer listed = agent.legacyCall("list_connections", args());
    SpecSchemas.assertValid("2025-11-25", "CallToolResult", listed.result());
    assertThat(listed.result().at("/structuredContent/connections/0/name").asString()).isEqualTo("notes-db");
    assertThat(listed.result().at("/content/0/text").asString()).startsWith("notes-db · FakePG · prod: ");
  }

  // ── Resources and prompts ───────────────────────────────────────────────────────────────────────────────────

  @Test
  void resourcesAreListedAndReadThroughTheSameGatesAsTools() {
    core.connection("res-db", "pgfake", Env.STAGE, "res.test");
    EndpointService endpoints = core.bean(EndpointService.class);
    endpoints.create("res", null, EndpointService.StartFrom.EMPTY, null);
    core.bean(EndpointConnections.class).add("res", "res-db", null);
    var modes = core.bean(ai.msrv.gaterook.core.endpoints.EndpointTools.class);
    for (String tool : ai.msrv.gaterook.core.endpoints.CoreTools.WITH_MODE) {
      modes.setMode("res", tool, null, ai.msrv.gaterook.connector.api.ToolDefinition.Mode.READ_ONLY, null,
          endpoints.snapshot("res").coreTool(tool).orElseThrow().rowVersion(), null);
    }
    McpClient agent = new McpClient(core.bean(LocalListener.class).port(), "res", core.bean(TokenService.class)
        .issue("res", "res-agent", 7, null).token());
    String schema = "mcpgw://connections/res-db/schema";
    ObjectNode readSchema = JsonNodeFactory.instance.objectNode().put("uri", schema);

    // Listed in both eras, as the specification's types say; templates apart.
    McpClient.Answer legacyList = agent.legacy("resources/list", null);
    SpecSchemas.assertValid("2025-11-25", "ListResourcesResult", legacyList.result());
    assertThat(legacyList.result().at("/resources/0/uri").asString()).isEqualTo(schema);
    assertThat(legacyList.result().path("resources")).hasSize(1);
    McpClient.Answer modernList = agent.modern("resources/list", JsonNodeFactory.instance.objectNode(), false);
    SpecSchemas.assertValid("2026-07-28", "ListResourcesResult", modernList.result());
    assertThat(modernList.result().path("ttlMs").asLong()).isEqualTo(30_000);
    McpClient.Answer templates = agent.legacy("resources/templates/list", null);
    SpecSchemas.assertValid("2025-11-25", "ListResourceTemplatesResult", templates.result());
    assertThat(templates.result().at("/resourceTemplates/0/uriTemplate").asString())
        .isEqualTo("mcpgw://connections/res-db/tables/{table}");
    SpecSchemas.assertValid("2026-07-28", "ListResourceTemplatesResult", agent.modern("resources/templates/list",
        JsonNodeFactory.instance.objectNode(), false).result());

    // Read like a READ call: audited, masked, in both eras; a template's URI too.
    FakeConnectors.RESOURCE_READS.clear();
    McpClient.Answer read = agent.legacy("resources/read", readSchema.deepCopy());
    SpecSchemas.assertValid("2025-11-25", "ReadResourceResult", read.result());
    assertThat(read.result().at("/contents/0/text").asString()).startsWith("orders(id int4, status text)")
        .endsWith("connected with ‹masked›");
    McpClient.Answer modernRead = agent.modern("resources/read", readSchema.deepCopy(), false);
    SpecSchemas.assertValid("2026-07-28", "ReadResourceResult", modernRead.result());
    assertThat(modernRead.body().toString()).doesNotContain("pw-res-db-0123");
    McpClient.Answer table = agent.legacy("resources/read", JsonNodeFactory.instance.objectNode().put("uri",
        "mcpgw://connections/res-db/tables/orders"));
    assertThat(table.result().at("/contents/0/text").asString()).isEqualTo("orders: id int4, status text");
    assertThat(FakeConnectors.RESOURCE_READS).hasSize(3);
    assertThat(core.jdbc().queryForList("select phase || ':' || decision from tool_call where token_label = "
        + "'res-agent' and tool_name = 'resources/read' order by id", String.class)).containsExactly(
            "received:allowed", "completed:allowed", "received:allowed", "completed:allowed", "received:allowed",
            "completed:allowed");
    McpClient.Answer missing = agent.legacy("resources/read", JsonNodeFactory.instance.objectNode().put("uri",
        "mcpgw://connections/res-db/nothing"));
    assertThat(missing.error().path("code").asInt()).isEqualTo(-32002);
    SpecSchemas.assertValid("2025-11-25", "JSONRPCErrorResponse", missing.body());

    // The stage policy edited outside the gateway: no resource is offered or read, and the core tools say why.
    String original = core.jdbc().queryForObject("select sections from policy where env = 'stage'", String.class);
    ai.msrv.gaterook.core.policy.PolicyService policies = core.bean(ai.msrv.gaterook.core.policy.PolicyService.class);
    core.jdbc().update("update policy set sections = '{\"sql\":{\"rowLimit\":7}}' where env = 'stage'");
    policies.invalidateAll();
    try {
      assertThat(agent.legacy("resources/list", null).result().path("resources")).isEmpty();
      assertThat(agent.legacy("resources/templates/list", null).result().path("resourceTemplates")).isEmpty();
      for (boolean modern : new boolean[] {false, true}) {
        McpClient.Answer refused = modern ? agent.modern("resources/read", readSchema.deepCopy(), false)
            : agent.legacy("resources/read", readSchema.deepCopy());
        assertThat(refused.status()).isEqualTo(modern ? 400 : 200);
        assertThat(refused.error().path("code").asInt()).isEqualTo(-32602);
        assertThat(refused.error().path("message").asString()).isEqualTo("Denied by policy core.integrity: The stage "
            + "policy was changed outside MCP Gateway. Ask the user to review it in MCP Gateway.");
        SpecSchemas.assertValid(modern ? "2026-07-28" : "2025-11-25", "JSONRPCErrorResponse", refused.body());
      }
      for (McpClient.Answer refused : List.of(agent.legacyCall("describe_connection", args("connection", "res-db")),
          agent.legacyCall("list_connections", args()))) {
        SpecSchemas.assertValid("2025-11-25", "CallToolResult", refused.result());
        assertThat(refused.result().path("isError").asBoolean()).isTrue();
        assertThat(refused.result().at("/content/0/text").asString()).isEqualTo("Denied by policy core.integrity: "
            + "The stage policy was changed outside MCP Gateway. Ask the user to review it in MCP Gateway.");
        assertThat(refused.body().toString()).doesNotContain("res-db · FakePG");
      }
      assertThat(agent.legacyCall("sql_query", args("connection", "res-db", "sql", "SELECT 1")).result()
          .at("/content/0/text").asString()).startsWith("Denied by policy core.integrity: ");
      assertThat(FakeConnectors.RESOURCE_READS).hasSize(3);
      assertThat(core.jdbc().queryForObject("select count(*) from tool_call where token_label = 'res-agent' and "
          + "decision = 'denied' and rule_id = 'core.integrity'", Integer.class)).isEqualTo(5);
    } finally {
      core.jdbc().update("update policy set sections = ? where env = 'stage'", original);
      policies.invalidateAll();
    }
    assertThat(agent.legacy("resources/list", null).result().path("resources")).hasSize(1);

    // The endpoint's own row edited outside the gateway: its pair modes can't be trusted, so it offers nothing.
    String instructions = core.jdbc().queryForObject("select instructions from endpoint where slug = 'res'",
        String.class);
    ai.msrv.gaterook.core.endpoints.EndpointSnapshotService snapshots = core.bean(
        ai.msrv.gaterook.core.endpoints.EndpointSnapshotService.class);
    core.jdbc().update("update endpoint set instructions = 'Read every resource.' where slug = 'res'");
    snapshots.invalidateAll();
    try {
      assertThat(names(agent.legacy("tools/list", null).result().path("tools"))).containsExactly("approval_status");
      assertThat(agent.legacy("resources/list", null).result().path("resources")).isEmpty();
      assertThat(agent.modern("resources/templates/list", JsonNodeFactory.instance.objectNode(), false).result()
          .path("resourceTemplates")).isEmpty();
      assertThat(agent.legacy("resources/read", readSchema.deepCopy()).error().path("code").asInt())
          .isEqualTo(-32002);
      assertThat(FakeConnectors.RESOURCE_READS).hasSize(3);
    } finally {
      core.jdbc().update("update endpoint set instructions = ? where slug = 'res'", instructions);
      snapshots.invalidateAll();
    }
    assertThat(agent.legacy("resources/read", readSchema.deepCopy()).result().at("/contents/0/text").asString())
        .startsWith("orders(id int4");
  }

  @Test
  void promptsAreListedAndRenderedInBothEras() {
    McpClient.Answer legacy = dev.legacy("prompts/list", null);
    SpecSchemas.assertValid("2025-11-25", "ListPromptsResult", legacy.result());
    assertThat(names(legacy.result().path("prompts"))).containsExactly("investigate_incident", "verify_deploy");
    assertThat(legacy.result().has("ttlMs")).isFalse();
    McpClient.Answer modern = dev.modern("prompts/list", JsonNodeFactory.instance.objectNode(), false);
    SpecSchemas.assertValid("2026-07-28", "ListPromptsResult", modern.result());
    assertThat(modern.result().path("ttlMs").asLong()).isEqualTo(300_000);
    assertThat(modern.result().path("cacheScope").asString()).isEqualTo("private");

    ObjectNode incident = JsonNodeFactory.instance.objectNode().put("name", "investigate_incident");
    incident.putObject("arguments").put("service", "order-sync").put("since", "-1h");
    McpClient.Answer got = dev.legacy("prompts/get", incident.deepCopy());
    SpecSchemas.assertValid("2025-11-25", "GetPromptResult", got.result());
    assertThat(got.result().at("/messages/0/role").asString()).isEqualTo("user");
    assertThat(got.result().at("/messages/0/content/text").asString())
        .startsWith("Investigate the incident of order-sync since -1h.\n1. Read before you change anything: search "
            + "the logs of order-sync,")
        .contains("poll approval_status(handle) and don't repeat the call.");
    McpClient.Answer gotModern = dev.modern("prompts/get", incident.deepCopy(), false);
    SpecSchemas.assertValid("2026-07-28", "GetPromptResult", gotModern.result());
    assertThat(gotModern.result().path("resultType").asString()).isEqualTo("complete");
    // A prompt's text is rendered for this call: nothing to cache.
    assertThat(gotModern.result().has("ttlMs")).isFalse();

    ObjectNode deploy = JsonNodeFactory.instance.objectNode().put("name", "verify_deploy");
    deploy.putObject("arguments").put("service", "api").put("version", "1.4.2");
    assertThat(dev.legacy("prompts/get", deploy).result().at("/messages/0/content/text").asString())
        .startsWith("Verify the deploy of api (version 1.4.2).\n");
    ObjectNode optional = JsonNodeFactory.instance.objectNode().put("name", "verify_deploy");
    optional.putObject("arguments").put("service", "api");
    assertThat(dev.legacy("prompts/get", optional).result().at("/messages/0/content/text").asString())
        .startsWith("Verify the deploy of api.\n");

    // A required argument that is missing or blank, or a prompt that doesn't exist: invalid params in both eras.
    for (ObjectNode bad : List.of(JsonNodeFactory.instance.objectNode().put("name", "investigate_incident"),
        (ObjectNode) TestCore.json("{\"name\":\"verify_deploy\",\"arguments\":{\"service\":\"  \"}}"),
        JsonNodeFactory.instance.objectNode().put("name", "drop_everything"))) {
      McpClient.Answer l = dev.legacy("prompts/get", bad.deepCopy());
      assertThat(l.status()).isEqualTo(200);
      assertThat(l.error().path("code").asInt()).isEqualTo(-32602);
      SpecSchemas.assertValid("2025-11-25", "JSONRPCErrorResponse", l.body());
      McpClient.Answer m = dev.modern("prompts/get", bad.deepCopy(), false);
      assertThat(m.status()).isEqualTo(400);
      assertThat(m.error().path("code").asInt()).isEqualTo(-32602);
      SpecSchemas.assertValid("2026-07-28", "JSONRPCErrorResponse", m.body());
    }
    assertThat(dev.legacy("prompts/get", JsonNodeFactory.instance.objectNode().put("name", "investigate_incident"))
        .error().path("message").asString()).isEqualTo("Prompt investigate_incident needs the argument service");
  }

  // ── Approvals through the handle ────────────────────────────────────────────────────────────────────────────

  @Test
  void aMutationWaitsForAPersonAndTheGatewayRunsTheStoredCall() {
    FakeConnectors.EXECUTIONS.clear();
    String sql = "UPDATE orders SET status = 'cancelled' WHERE id = 48213;";
    long t0 = System.nanoTime();
    McpClient.Answer first = dev.legacyCall("sql_execute", args("connection", "dev-db", "sql", sql));
    // firstWait: the first answer waited for a quick decision before it handed out the handle.
    assertThat(Duration.ofNanos(System.nanoTime() - t0)).isGreaterThanOrEqualTo(Duration.ofMillis(1_900));
    SpecSchemas.assertValid("2025-11-25", "CallToolResult", first.result());
    JsonNode pending = first.result();
    assertThat(pending.path("isError").asBoolean()).isFalse();
    String text = pending.at("/content/0/text").asString();
    assertThat(text).startsWith("NOT EXECUTED YET. A person must approve this exact action in MCP Gateway.\nHandle: ap_")
        .contains("\nAction (sql on dev-db, dev):\n" + sql + "\nApproval page: http://127.0.0.1:")
        .contains("Don't repeat sql_execute.");
    JsonNode s = pending.path("structuredContent");
    String handle = s.path("handle").asString();
    assertThat(s.path("status").asString()).isEqualTo("pending");
    assertThat(s.path("action").path("text").asString()).isEqualTo(sql);
    assertThat(s.path("approvalUrl").asString()).endsWith("/approvals/" + handle);
    assertThat(FakeConnectors.EXECUTIONS).isEmpty();
    // Impact and fields of prepare are the approver's, never the agent's.
    assertThat(pending.toString()).doesNotContain("1 row (estimate)");

    // The same call again while pending: the same handle.
    McpClient.Answer again = dev.modernCall("sql_execute", args("connection", "dev-db", "sql", sql), false);
    SpecSchemas.assertValid("2026-07-28", "CallToolResult", again.result());
    assertThat(again.result().path("structuredContent").path("handle").asString()).isEqualTo(handle);
    assertThat(again.result().path("resultType").asString()).isEqualTo("complete");

    McpClient.Answer waiting = dev.legacyCall("approval_status", args("handle", handle));
    assertThat(waiting.result().path("structuredContent").path("status").asString()).isEqualTo("pending");
    assertThat(waiting.result().at("/content/0/text").asString()).startsWith("Still waiting for a decision");
    // Another principal knows nothing of it.
    String other = core.bean(TokenService.class).issue("dev", "other-agent", 7, null).token();
    McpClient.Answer foreign = dev.as(other).legacyCall("approval_status", args("handle", handle));
    assertThat(foreign.result().path("isError").asBoolean()).isTrue();
    assertThat(foreign.result().at("/content/0/text").asString()).startsWith("Unknown handle " + handle);

    ApprovalView view = approvals.find(handle).orElseThrow();
    approvals.decide(handle, ApprovalService.Decision.APPROVE, "app", null, view.digest());
    ObjectNode poll = args("handle", handle);
    poll.put("wait_seconds", 10);
    McpClient.Answer done = dev.legacyCall("approval_status", poll);
    SpecSchemas.assertValid("2025-11-25", "CallToolResult", done.result());
    assertThat(done.result().path("structuredContent").path("status").asString()).isEqualTo("completed");
    assertThat(done.result().at("/content/0/text").asString())
        .isEqualTo("Approved by you and executed on dev-db (dev): 1 row updated");
    assertThat(done.result().at("/structuredContent/result/rowsAffected").asInt()).isEqualTo(1);
    assertThat(FakeConnectors.EXECUTIONS).singleElement().satisfies(e -> {
      assertThat(e.approved()).isTrue();
      assertThat(e.approvalId()).isEqualTo(handle);
      assertThat(e.sql()).isEqualTo(sql);
    });
    String callId = view.callId();
    List<Map<String, Object>> rows = core.jdbc().queryForList("select phase, decision, approval_id, action_sha256, "
        + "executed_sha256, decided_via, approval_delivery from tool_call where call_id = ? order by id", callId);
    assertThat(rows).extracting(m -> m.get("phase") + ":" + m.get("decision"))
        .containsExactly("received:pending", "resolved:approved", "completed:approved");
    assertThat(rows.get(0).get("approval_delivery")).isEqualTo("handle");
    assertThat(rows.get(1).get("decided_via")).isEqualTo("app");
    assertThat(rows.get(2).get("action_sha256")).isNotNull().isEqualTo(rows.get(2).get("executed_sha256"));
  }

  @Test
  void aReadToolThatGoesToAPersonKeepsToItsOutputSchemaOnEveryPath() throws Exception {
    // sql_query is READ and publishes an outputSchema; a statement with a function that isn't built in is classified
    // MUTATION (§9) and waits for a person. Every answer must then match the schema or be an error.
    JsonNode outputSchema = tool(dev.legacy("tools/list", null).result(), "sql_query").path("outputSchema");
    assertThat(outputSchema.isObject()).isTrue();
    McpClient agent = dev.as(core.bean(TokenService.class).issue("dev", "typed-agent", 7, null).token());
    FakeConnectors.EXECUTIONS.clear();

    // The handle: pending is an error that keeps its text and handle.
    String waitSql = "SELECT my_wrapper(1)";
    McpClient.Answer pending = agent.legacyCall("sql_query", args("connection", "dev-db", "sql", waitSql));
    assertConforms("2025-11-25", outputSchema, pending);
    assertThat(pending.result().path("isError").asBoolean()).isTrue();
    assertThat(pending.result().at("/content/0/text").asString()).startsWith("NOT EXECUTED YET. A person must approve")
        .contains("\nAction (sql on dev-db, dev):\n" + waitSql + "\n");
    String handle = handleOf(pending);
    assertThat(handle).startsWith("ap_");
    McpClient.Answer again = agent.modernCall("sql_query", args("connection", "dev-db", "sql", waitSql), false);
    assertConforms("2026-07-28", outputSchema, again);
    assertThat(handleOf(again)).isEqualTo(handle);
    approvals.decide(handle, ApprovalService.Decision.DENY, "app", null, null);
    assertThat(FakeConnectors.EXECUTIONS).isEmpty();

    // Approved within the first wait: the executed result itself, in the tool's own shape.
    String quickSql = "SELECT my_wrapper(2)";
    var quick = java.util.concurrent.CompletableFuture.supplyAsync(() -> agent.legacyCall("sql_query",
        args("connection", "dev-db", "sql", quickSql)));
    String quickId = awaitRequest(quickSql);
    approvals.decide(quickId, ApprovalService.Decision.APPROVE, "app", null, approvals.find(quickId).orElseThrow()
        .digest());
    McpClient.Answer done = quick.get(30, java.util.concurrent.TimeUnit.SECONDS);
    assertConforms("2025-11-25", outputSchema, done);
    assertThat(done.result().path("isError").asBoolean()).isFalse();
    assertThat(done.result().at("/structuredContent/rowCount").asInt()).isEqualTo(3);
    assertThat(done.result().at("/content/0/text").asString()).startsWith("Approved by you and executed on dev-db "
        + "(dev). Result:\n3 rows · ");
    assertThat(done.result().at("/_meta/ai.msrv.gaterook~1approval/handle").asString()).isEqualTo(quickId);
    assertThat(FakeConnectors.EXECUTIONS).singleElement().satisfies(e -> assertThat(e.approvalId()).isEqualTo(quickId));

    // MRTR: the round that sees the decision answers with the executed result; cancel and decline are errors.
    String mrtrSql = "SELECT my_wrapper(3)";
    McpClient.Answer first = agent.modernCall("sql_query", args("connection", "dev-db", "sql", mrtrSql), true);
    SpecSchemas.assertValid("2026-07-28", "InputRequiredResult", first.result());
    String mrtrId = handleOf(first);
    String state = first.result().path("requestState").asString();
    McpClient.Answer cancelled = agent.modern("tools/call", retry("sql_query", mrtrSql, state, "cancel"), true);
    assertConforms("2026-07-28", outputSchema, cancelled);
    assertThat(cancelled.result().path("isError").asBoolean()).isTrue();
    assertThat(cancelled.result().at("/structuredContent/status").asString()).isEqualTo("pending");
    var held = java.util.concurrent.CompletableFuture.supplyAsync(() -> agent.modern("tools/call",
        retry("sql_query", mrtrSql, state, "accept"), true));
    Thread.sleep(300);
    approvals.decide(mrtrId, ApprovalService.Decision.APPROVE, "app", null, approvals.find(mrtrId).orElseThrow()
        .digest());
    McpClient.Answer complete = held.get(30, java.util.concurrent.TimeUnit.SECONDS);
    assertConforms("2026-07-28", outputSchema, complete);
    assertThat(complete.result().path("resultType").asString()).isEqualTo("complete");
    assertThat(complete.result().path("isError").asBoolean()).isFalse();
    assertThat(complete.result().at("/structuredContent/columns").toString()).isEqualTo("[\"id\",\"status\"]");
    String declineSql = "SELECT my_wrapper(4)";
    McpClient.Answer toDecline = agent.modernCall("sql_query", args("connection", "dev-db", "sql", declineSql), true);
    McpClient.Answer declined = agent.modern("tools/call", retry("sql_query", declineSql, toDecline.result()
        .path("requestState").asString(), "decline"), true);
    assertConforms("2026-07-28", outputSchema, declined);
    assertThat(declined.result().at("/structuredContent/status").asString()).isEqualTo("rejected");

    // "Unchanged" is no table: an error for a tool with an outputSchema, a plain answer for one without.
    McpClient.Answer unchanged = agent.legacyCall("sql_query", args("connection", "dev-db", "sql",
        "SELECT my_wrapper(no_change)"));
    assertConforms("2025-11-25", outputSchema, unchanged);
    assertThat(unchanged.result().path("isError").asBoolean()).isTrue();
    assertThat(unchanged.result().at("/content/0/text").asString()).isEqualTo("Already in that state: nothing to "
        + "change.");
    McpClient.Answer unchangedWrite = agent.legacyCall("sql_execute", args("connection", "dev-db", "sql",
        "UPDATE no_change SET x = 1;"));
    SpecSchemas.assertValid("2025-11-25", "CallToolResult", unchangedWrite.result());
    assertThat(unchangedWrite.result().path("isError").asBoolean()).isFalse();
    assertThat(FakeConnectors.EXECUTIONS).hasSize(2);
  }

  @Test
  void aDeniedOrExpiredRequestNeverRuns() {
    FakeConnectors.EXECUTIONS.clear();
    ApprovalView denied = request("DELETE FROM orders WHERE id = 1;");
    approvals.decide(denied.id(), ApprovalService.Decision.DENY, "app", "use the refunds flow", null);
    McpClient.Answer rejected = dev.legacyCall("approval_status", args("handle", denied.id()));
    SpecSchemas.assertValid("2025-11-25", "CallToolResult", rejected.result());
    assertThat(rejected.result().path("isError").asBoolean()).isTrue();
    assertThat(rejected.result().at("/content/0/text").asString())
        .isEqualTo("Rejected by you: \"use the refunds flow\". Not executed. Ask the user before trying again.");
    assertThat(rejected.result().at("/structuredContent/status").asString()).isEqualTo("rejected");
    McpClient.Answer rejectedModern = dev.modernCall("approval_status", args("handle", denied.id()), false);
    SpecSchemas.assertValid("2026-07-28", "CallToolResult", rejectedModern.result());
    assertThat(rejectedModern.result().path("isError").asBoolean()).isTrue();

    ApprovalView late = request("DELETE FROM orders WHERE id = 2;");
    core.jdbc().update("update approval set expires_at = ? where id = ?", System.currentTimeMillis() - 1, late.id());
    TestCore.await(() -> approvals.find(late.id()).orElseThrow().status().equals("expired"), "expiry");
    McpClient.Answer expired = dev.modernCall("approval_status", args("handle", late.id()), false);
    SpecSchemas.assertValid("2026-07-28", "CallToolResult", expired.result());
    assertThat(expired.result().at("/structuredContent/status").asString()).isEqualTo("expired");
    assertThat(expired.result().path("isError").asBoolean()).isTrue();
    McpClient.Answer expiredLegacy = dev.legacyCall("approval_status", args("handle", late.id()));
    SpecSchemas.assertValid("2025-11-25", "CallToolResult", expiredLegacy.result());
    assertThat(expiredLegacy.result().at("/structuredContent/status").asString()).isEqualTo("expired");
    assertThat(FakeConnectors.EXECUTIONS).isEmpty();
    assertThat(core.jdbc().queryForObject("select decision from tool_call where approval_id = ? and phase = "
        + "'resolved'", String.class, late.id())).isEqualTo("expired");
  }

  @Test
  void anUpdateOfTheConnectionsPluginRejectsWhatWaitsForItsTools() {
    FakeConnectors.EXECUTIONS.clear();
    ApprovalView waiting = request("DELETE FROM orders WHERE id = 41;");
    // pgfake stands for a plugin here: the switch names the plugin by the id of its connector type, in its transaction.
    new TransactionTemplate(core.bean(PlatformTransactionManager.class)).executeWithoutResult(s -> core.context()
        .publishEvent(new PluginGenerationSwitched("pgfake", "1.2.0", "1.3.0")));
    ApprovalView after = approvals.find(waiting.id()).orElseThrow();
    assertThat(after.status()).isEqualTo("rejected");
    assertThat(after.decidedVia()).isEqualTo("core");
    assertThat(after.error()).isEqualTo("Plugin updated to 1.3.0, ask again");
    McpClient.Answer answer = dev.modernCall("approval_status", args("handle", waiting.id()), false);
    assertThat(answer.result().path("isError").asBoolean()).isTrue();
    assertThat(answer.result().at("/structuredContent/status").asString()).isEqualTo("rejected");
    assertThat(FakeConnectors.EXECUTIONS).isEmpty();
    assertThat(core.jdbc().queryForObject("select decision from tool_call where approval_id = ? and phase = "
        + "'resolved'", String.class, waiting.id())).isEqualTo("rejected");
  }

  @Test
  void prodNeedsACommentToApprove() {
    ObjectNode a = args("connection", "prod-db", "sql", "UPDATE t SET x = 1;");
    McpClient.Answer first = prod.modernCall("sql_execute", a, false);
    String handle = first.result().at("/structuredContent/handle").asString();
    assertThat(first.result().at("/content/0/text").asString()).contains("A comment is required to approve on prod.");
    String digest = approvals.find(handle).orElseThrow().digest();
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> approvals.decide(handle,
        ApprovalService.Decision.APPROVE, "app", " ", digest))
        .hasMessageContaining("Add a comment to approve on prod");
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> approvals.decide(handle,
        ApprovalService.Decision.APPROVE, "desktop-notification", "ok", digest))
        .hasMessageContaining("can only deny");
    // An Approve without what the person saw, or with another action's digest, approves nothing.
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> approvals.decide(handle,
        ApprovalService.Decision.APPROVE, "app", "ok", null))
        .isInstanceOfSatisfying(ai.msrv.gaterook.core.platform.ProblemException.class,
            e -> assertThat(e.getMessage()).contains("send the request's digest"));
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> approvals.decide(handle,
        ApprovalService.Decision.APPROVE, "app", "ok", "0123456789abcdef".equals(digest.substring(0, 16))
            ? "fedcba9876543210" : "0123456789abcdef"))
        .hasMessageContaining("This isn't the action you saw");
    assertThat(approvals.find(handle).orElseThrow().status()).isEqualTo("pending");
    approvals.decide(handle, ApprovalService.Decision.APPROVE, "app", "checked with the owner", digest);
    TestCore.await(() -> approvals.find(handle).orElseThrow().terminal(), "execution");
    assertThat(approvals.find(handle).orElseThrow().status()).isEqualTo("completed");
  }

  @Test
  void aPolicyDenialIsAToolErrorWithTheRule() {
    McpClient.Answer denied = prod.modernCall("sql_execute", args("connection", "prod-db", "sql",
        "TRUNCATE order_sync_queue;"), false);
    SpecSchemas.assertValid("2026-07-28", "CallToolResult", denied.result());
    assertThat(denied.result().path("isError").asBoolean()).isTrue();
    assertThat(denied.result().at("/content/0/text").asString()).startsWith("Denied by policy prod.sql.block_ddl: "
        + "DDL is not allowed on prod");
    String callId = denied.result().at("/_meta/ai.msrv.gaterook~1callId").asString();
    assertThat(core.jdbc().queryForList("select phase, decision, rule_id from tool_call where call_id = ?", callId))
        .extracting(m -> m.get("phase") + ":" + m.get("decision") + ":" + m.get("rule_id"))
        .containsExactly("received:denied:prod.sql.block_ddl");
    McpClient.Answer legacy = prod.legacyCall("sql_query", args("connection", "prod-db", "sql", "DROP TABLE t"));
    SpecSchemas.assertValid("2025-11-25", "CallToolResult", legacy.result());
    assertThat(legacy.result().path("isError").asBoolean()).isTrue();
  }

  @Test
  void identicalCallsInParallelMakeOneRequest() throws Exception {
    McpClient agent = dev.as(core.bean(TokenService.class).issue("dev", "dup-agent", 7, null).token());
    String sql = "UPDATE orders SET status = 'dup' WHERE id = 77;";
    List<java.util.concurrent.Future<McpClient.Answer>> calls = new java.util.ArrayList<>();
    try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < 6; i++) {
        boolean modern = i % 2 == 0;
        calls.add(pool.submit(() -> modern ? agent.modernCall("sql_execute", args("connection", "dev-db", "sql", sql),
            false) : agent.legacyCall("sql_execute", args("connection", "dev-db", "sql", sql))));
      }
      java.util.Set<String> handles = new java.util.HashSet<>();
      for (var f : calls) {
        handles.add(f.get(30, java.util.concurrent.TimeUnit.SECONDS).result().at("/structuredContent/handle")
            .asString());
      }
      assertThat(handles).hasSize(1);
      assertThat(core.jdbc().queryForObject("select count(*) from approval where action like ?", Integer.class,
          "%id = 77;%")).isEqualTo(1);
      String handle = handles.iterator().next();
      approvals.decide(handle, ApprovalService.Decision.DENY, "app", null, null);

      // Six calls, one request: each call ends with the request, none stays pending in Activity or in its trace.
      List<String> callIds = core.jdbc().queryForList("select call_id from tool_call where approval_id = ? and phase "
          + "= 'received'", String.class, handle);
      assertThat(callIds).hasSize(6).doesNotHaveDuplicates();
      for (String callId : callIds) {
        assertThat(core.jdbc().queryForList("select phase || ':' || decision from tool_call where call_id = ? order "
            + "by id", String.class, callId)).containsExactly("received:pending", "resolved:rejected");
      }
      var activity = core.bean(ai.msrv.gaterook.core.audit.AuditQueries.class);
      assertThat(activity.events("token:dup-agent", null, null, null, 50).events()).hasSize(6)
          .allSatisfy(e -> assertThat(e.decision()).isEqualTo("rejected"));
      assertThat(activity.events("token:dup-agent decision:pending", null, null, null, 50).events()).isEmpty();
      assertThat(core.jdbc().queryForObject("select count(*) from tool_call where approval_id = ? and phase = "
          + "'resolved' and approval_wait_ms is not null", Integer.class, handle)).as("the wait counted once")
          .isEqualTo(1);
    }
  }

  @Test
  void whatPrepareSaysWithTheSecretsNeverReachesTheAgentAndASecretInTheActionRefusesIt() throws Exception {
    String secret = "pw-dev-db-0123";
    ai.msrv.gaterook.core.platform.EventBus bus = core.bean(ai.msrv.gaterook.core.platform.EventBus.class);
    long eventsBefore = bus.lastId();
    McpClient agent = dev.as(core.bean(TokenService.class).issue("dev", "canary-agent", 7, null).token());

    // prepare echoes the password into everything that only describes the action: masked for the approver, and the
    // agent sees nothing of it — only its own statement.
    String sql = "UPDATE leak_details SET x = 1 WHERE id = 1;";
    McpClient.Answer details = agent.modernCall("sql_execute", args("connection", "dev-db", "sql", sql), false);
    String handle = handleOf(details);
    assertThat(details.result().at("/structuredContent/action/text").asString()).isEqualTo(sql);
    assertThat(details.body().toString()).doesNotContain(secret).doesNotContain("Runs as");
    String row = core.jdbc().queryForMap("select * from approval where id = ?", handle).toString();
    assertThat(row).doesNotContain(secret).contains("Runs as ‹masked›").contains("app/‹masked›");
    McpClient.Answer status = agent.legacyCall("approval_status", args("handle", handle));
    assertThat(status.body().toString()).doesNotContain(secret);
    McpClient.Answer mrtr = agent.modernCall("sql_execute", args("connection", "dev-db", "sql", sql), true);
    assertThat(mrtr.body().toString()).doesNotContain(secret).contains(sql);
    List<String> sse = new java.util.ArrayList<>();
    try (var subscription = bus.subscribe(java.util.OptionalLong.of(eventsBefore))) {
      for (var e = subscription.next(Duration.ofMillis(500)); e.isPresent(); e = subscription.next(Duration.ofMillis(
          500))) {
        sse.add(e.get().type() + " " + e.get().data());
      }
    }
    assertThat(sse).anySatisfy(e -> assertThat(e).startsWith("approval.requested").contains(handle)
        .contains("sql_execute as ‹masked›"));
    assertThat(sse).noneSatisfy(e -> assertThat(e).contains(secret));
    approvals.decide(handle, ApprovalService.Decision.DENY, "app", null, null);

    // A password in the exact action or in what would run can't be masked away: no request at all.
    for (String leak : List.of("UPDATE leak_action SET x = 1;", "UPDATE leak_payload SET x = 1;")) {
      McpClient.Answer refused = agent.legacyCall("sql_execute", args("connection", "dev-db", "sql", leak));
      SpecSchemas.assertValid("2025-11-25", "CallToolResult", refused.result());
      assertThat(refused.result().path("isError").asBoolean()).isTrue();
      assertThat(refused.result().at("/content/0/text").asString()).isEqualTo("The exact action of sql_execute on "
          + "dev-db would contain a stored secret, so MCP Gateway doesn't ask anyone to approve it. Nothing ran.");
      assertThat(core.jdbc().queryForObject("select count(*) from approval where call_canonical like ?",
          Integer.class, "%" + leak.substring(7, 19) + "%")).isZero();
    }
    assertThat(core.jdbc().queryForObject("select count(*) from approval where action like ? or call_canonical like ? "
        + "or prepared_state like ?", Integer.class, "%" + secret + "%", "%" + secret + "%", "%" + secret + "%"))
        .isZero();
    assertThat(core.jdbc().queryForObject("select count(*) from tool_call where args_masked like ? or "
        + "error_message like ? or result_preview like ?", Integer.class, "%" + secret + "%", "%" + secret + "%",
        "%" + secret + "%")).isZero();

    // A diff against live state is the approver's: the agent sees the arguments it sent.
    String diff = "DIFF deployment/api replicas=5";
    McpClient.Answer pending = agent.legacyCall("sql_execute", args("connection", "dev-db", "sql", diff));
    SpecSchemas.assertValid("2025-11-25", "CallToolResult", pending.result());
    assertThat(pending.result().at("/content/0/text").asString()).contains("\nAction (arguments on dev-db, dev):\n"
        + "{\"sql\":\"" + diff + "\"}\nIn MCP Gateway the person sees the exact action these arguments make.");
    assertThat(pending.result().at("/structuredContent/action/kind").asString()).isEqualTo("arguments");
    assertThat(pending.body().toString()).doesNotContain("live-state").doesNotContain("replicas: 3");
    McpClient.Answer elicited = agent.modernCall("sql_execute", args("connection", "dev-db", "sql", diff), true);
    assertThat(elicited.result().at("/inputRequests/gateway_approval/params/message").asString())
        .contains("{\"sql\":\"" + diff + "\"}").doesNotContain("live-state");
    assertThat(approvals.find(handleOf(pending)).orElseThrow().action().text()).contains("live-state");
    approvals.decide(handleOf(pending), ApprovalService.Decision.DENY, "app", null, null);
  }

  // ── Approvals through MRTR (2026-07-28 with URL elicitation) ────────────────────────────────────────────────

  @Test
  void mrtrShowsTheUrlAndTheRetryReturnsTheOutcome() throws Exception {
    FakeConnectors.EXECUTIONS.clear();
    String sql = "UPDATE orders SET status = 'shipped' WHERE id = 48300;";
    McpClient.Answer first = dev.modernCall("sql_execute", args("connection", "dev-db", "sql", sql), true);
    assertThat(first.status()).isEqualTo(200);
    SpecSchemas.assertValid("2026-07-28", "InputRequiredResult", first.result());
    JsonNode elicitation = first.result().path("inputRequests").path("gateway_approval");
    assertThat(elicitation.path("method").asString()).isEqualTo("elicitation/create");
    SpecSchemas.assertValid("2026-07-28", "ElicitRequestURLParams", elicitation.path("params"));
    String url = elicitation.at("/params/url").asString();
    String handle = url.substring(url.lastIndexOf('/') + 1);
    assertThat(url).isEqualTo(core.bean(LocalListener.class).origin() + "/approvals/" + handle);
    assertThat(elicitation.at("/params/message").asString()).contains(sql).endsWith("Decide in the MCP Gateway window.");
    assertThat(FakeConnectors.EXECUTIONS).isEmpty();
    assertThat(core.jdbc().queryForObject("select approval_delivery from tool_call where approval_id = ? and phase = "
        + "'received'", String.class, handle)).isEqualTo("mrtr");

    // The client shows the link and retries with the state; the person approves while the retry is held.
    ObjectNode retry = retry(sql, first.result().path("requestState").asString(), "accept");
    var held = java.util.concurrent.CompletableFuture.supplyAsync(() -> dev.modern("tools/call", retry, true));
    Thread.sleep(500);
    assertThat(held).isNotDone();
    approvals.decide(handle, ApprovalService.Decision.APPROVE, "app", null, approvals.find(handle).orElseThrow()
        .digest().substring(0, 12));
    McpClient.Answer done = held.get(30, java.util.concurrent.TimeUnit.SECONDS);
    SpecSchemas.assertValid("2026-07-28", "CallToolResult", done.result());
    assertThat(done.result().path("resultType").asString()).isEqualTo("complete");
    assertThat(done.result().path("isError").asBoolean()).isFalse();
    assertThat(done.result().at("/content/0/text").asString())
        .isEqualTo("Approved by you and executed on dev-db (dev): 1 row updated");
    assertThat(done.result().at("/structuredContent/status").asString()).isEqualTo("completed");
    assertThat(FakeConnectors.EXECUTIONS).singleElement().satisfies(e -> assertThat(e.approvalId()).isEqualTo(handle));

    // The same state again only reads: no second execution.
    McpClient.Answer again = dev.modern("tools/call", retry(sql, first.result().path("requestState").asString(),
        "accept"), true);
    assertThat(again.result().at("/structuredContent/status").asString()).isEqualTo("completed");
    assertThat(FakeConnectors.EXECUTIONS).hasSize(1);
  }

  @Test
  void mrtrDeclineRejectsCancelHandsOverAndAForgedStateFindsTheSameRequest() {
    McpClient agent = dev.as(core.bean(TokenService.class).issue("dev", "mrtr-agent", 7, null).token());
    FakeConnectors.EXECUTIONS.clear();
    String sql = "DELETE FROM orders WHERE id = 3;";
    McpClient.Answer first = agent.modernCall("sql_execute", args("connection", "dev-db", "sql", sql), true);
    String state = first.result().path("requestState").asString();
    String handle = handleOf(first);

    // A state with a changed MAC is no state: the call goes the ordinary way and finds the same pending request.
    String forged = state.substring(0, state.length() - 2) + (state.endsWith("AA") ? "BB" : "AA");
    McpClient.Answer viaForged = agent.modern("tools/call", retry(sql, forged, "accept"), true);
    SpecSchemas.assertValid("2026-07-28", "InputRequiredResult", viaForged.result());
    assertThat(handleOf(viaForged)).isEqualTo(handle);
    // A state of another principal is no state either.
    McpClient.Answer foreign = dev.modern("tools/call", retry(sql, state, "accept"), true);
    assertThat(handleOf(foreign)).isNotEqualTo(handle);
    approvals.decide(handleOf(foreign), ApprovalService.Decision.DENY, "app", null, null);
    // The state of other arguments doesn't open either: a new request for what was sent.
    McpClient.Answer otherArgs = agent.modern("tools/call", retry("DELETE FROM orders WHERE id = 4;", state, "accept"),
        true);
    assertThat(handleOf(otherArgs)).isNotEqualTo(handle);
    approvals.decide(handleOf(otherArgs), ApprovalService.Decision.DENY, "app", null, null);

    // cancel: the handle takes over.
    McpClient.Answer cancelled = agent.modern("tools/call", retry(sql, state, "cancel"), true);
    SpecSchemas.assertValid("2026-07-28", "CallToolResult", cancelled.result());
    assertThat(cancelled.result().path("resultType").asString()).isEqualTo("complete");
    assertThat(cancelled.result().at("/structuredContent/status").asString()).isEqualTo("pending");
    assertThat(cancelled.result().at("/structuredContent/handle").asString()).isEqualTo(handle);

    // decline: rejected in the client, never executed.
    McpClient.Answer declined = agent.modern("tools/call", retry(sql, state, "decline"), true);
    SpecSchemas.assertValid("2026-07-28", "CallToolResult", declined.result());
    assertThat(declined.result().path("isError").asBoolean()).isTrue();
    assertThat(declined.result().at("/structuredContent/status").asString()).isEqualTo("rejected");
    assertThat(approvals.find(handle).orElseThrow().decidedVia()).isEqualTo("client");
    assertThat(FakeConnectors.EXECUTIONS).isEmpty();
    // Every call that found this request (the forged retry did) is resolved with it.
    assertThat(core.jdbc().queryForList("select decided_via from tool_call where approval_id = ? and phase = "
        + "'resolved'", String.class, handle)).hasSize(2).containsOnly("client");
  }

  @Test
  void anEndpointWithHandleOnlyDeliveryNeverAsksForInput() {
    EndpointService endpoints = core.bean(EndpointService.class);
    endpoints.updateSettings("incident-prod", new EndpointService.SettingsUpdate(null, null, null, null,
        JsonNodeFactory.instance.objectNode().put("approvalDelivery", "handle-only"), null, null),
        endpoints.get("incident-prod").version(), null);
    try {
      McpClient.Answer r = prod.modernCall("sql_execute", args("connection", "prod-db", "sql", "UPDATE t SET y = 2;"),
          true);
      SpecSchemas.assertValid("2026-07-28", "CallToolResult", r.result());
      assertThat(r.result().path("resultType").asString()).isEqualTo("complete");
      assertThat(r.result().at("/structuredContent/status").asString()).isEqualTo("pending");
      approvals.decide(handleOf(r), ApprovalService.Decision.DENY, "app", null, null);
    } finally {
      endpoints.updateSettings("incident-prod", new EndpointService.SettingsUpdate(null, null, null, null,
          JsonNodeFactory.instance.objectNode().put("approvalDelivery", "auto"), null, null),
          endpoints.get("incident-prod").version(), null);
    }
  }

  // ── One decision, one execution ─────────────────────────────────────────────────────────────────────────────

  @Test
  void parallelDecisionsFromTheAppAndTheChannelsRunTheCallOnce() throws Exception {
    McpClient agent = dev.as(core.bean(TokenService.class).issue("dev", "race-agent", 7, null).token());
    FakeConnectors.EXECUTIONS.clear();
    List<String> vias = List.of("app", "slack", "telegram", "desktop-notification");
    int approvedWins = 0;
    List<String> ids = new java.util.ArrayList<>();
    for (int round = 0; round < 8; round++) {
      McpClient.Answer r = agent.modernCall("sql_execute", args("connection", "dev-db", "sql",
          "UPDATE orders SET status = 'race' WHERE id = " + (1000 + round) + ";"), false);
      String id = handleOf(r);
      String digest = approvals.find(id).orElseThrow().digest();
      ids.add(id);
      var start = new java.util.concurrent.CountDownLatch(1);
      List<java.util.concurrent.Future<String>> outcomes = new java.util.ArrayList<>();
      try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
        for (String via : vias) {
          outcomes.add(pool.submit(() -> {
            start.await();
            try {
              approvals.decide(id, via.equals("desktop-notification") ? ApprovalService.Decision.DENY
                  : ApprovalService.Decision.APPROVE, via, null, digest);
              return "won:" + via;
            } catch (RuntimeException e) {
              return "lost:" + via;
            }
          }));
        }
        start.countDown();
        List<String> won = new java.util.ArrayList<>();
        for (var f : outcomes) {
          String o = f.get(30, java.util.concurrent.TimeUnit.SECONDS);
          if (o.startsWith("won:")) {
            won.add(o);
          }
        }
        assertThat(won).as("round " + round).hasSize(1);
        if (!won.getFirst().equals("won:desktop-notification")) {
          approvedWins++;
        }
      }
      TestCore.await(() -> approvals.find(id).orElseThrow().terminal(), "decision " + id);
    }
    List<String> executed = FakeConnectors.EXECUTIONS.stream().map(FakeConnectors.Execution::approvalId).toList();
    assertThat(executed).doesNotHaveDuplicates().allMatch(ids::contains);
    assertThat(executed).hasSize(approvedWins);
    for (String id : ids) {
      assertThat(core.jdbc().queryForObject("select count(*) from tool_call where approval_id = ? and phase = "
          + "'resolved'", Integer.class, id)).as("one resolved row for " + id).isEqualTo(1);
    }
  }

  @Test
  void anApprovalChangedOutsideTheGatewayIsNotExecuted() {
    FakeConnectors.EXECUTIONS.clear();
    ApprovalView a = request("UPDATE orders SET status = 'paid' WHERE id = 31;");
    core.jdbc().update("update approval set action = replace(action, 'id = 31', 'id > 0') where id = ?", a.id());
    try {
      approvals.decide(a.id(), ApprovalService.Decision.APPROVE, "app", null, a.digest());
    } catch (RuntimeException refusedEarly) {
      // Refusing at the decision is as good as refusing at the execution.
    }
    TestCore.await(() -> approvals.find(a.id()).map(ApprovalView::terminal).orElse(true), "refusal");
    ApprovalView after = approvals.find(a.id()).orElseThrow();
    assertThat(after.status()).isEqualTo("rejected");
    assertThat(after.error()).isEqualTo("The request was changed outside MCP Gateway. Not executed.");
    assertThat(FakeConnectors.EXECUTIONS).isEmpty();
    McpClient.Answer status = dev.legacyCall("approval_status", args("handle", a.id()));
    assertThat(status.result().path("isError").asBoolean()).isTrue();
    assertThat(status.result().at("/content/0/text").asString()).startsWith("The request was changed outside MCP "
        + "Gateway. Not executed.");
    assertThat(core.jdbc().queryForObject("select decision from tool_call where approval_id = ? and phase = "
        + "'completed'", String.class, a.id())).isEqualTo("rejected");
  }

  @Test
  void anApprovedRequestTheGatewayStopsIsDecidedByTheCoreAndKeepsThePersonsDecisionInItsFeed() {
    FakeConnectors.EXECUTIONS.clear();
    ApprovalView a = request("UPDATE orders SET status = 'shipped' WHERE id = 32;");
    // A new version of dev-db between the request and the decision: the approved call no longer matches it.
    core.bean(ai.msrv.gaterook.core.connections.ConnectionService.class).touchEnv(Env.DEV);
    approvals.decide(a.id(), ApprovalService.Decision.APPROVE, "app", null, a.digest());
    TestCore.await(() -> approvals.find(a.id()).map(ApprovalView::terminal).orElse(true), "the refusal");
    ApprovalView after = approvals.find(a.id()).orElseThrow();
    assertThat(after.status()).isEqualTo("rejected");
    assertThat(after.error()).isEqualTo("dev-db changed after approval. Not executed.");
    assertThat(after.decidedVia()).as("as approval.resolved says: the gateway stopped it").isEqualTo("core");
    assertThat(after.decidedAt()).isNotNull();
    assertThat(FakeConnectors.EXECUTIONS).isEmpty();
    assertThat(approvals.timeline(a.id())).anySatisfy(e -> {
      assertThat(e.kind()).isEqualTo("decided");
      assertThat(e.detail().toString()).contains("approve");
    });
    assertThat(core.jdbc().queryForObject("select decided_via from tool_call where approval_id = ? and phase = "
        + "'resolved'", String.class, a.id())).as("the person's decision, in the audit").isEqualTo("app");

    // The decision's chain, for the event detail's Policy timeline: classification, rule, rate, limits.
    JsonNode policy = TestCore.json(core.jdbc().queryForObject("select facets from tool_call where approval_id = ? "
        + "and phase = 'received'", String.class, a.id())).path("policy");
    assertThat(policy.path("outcome").asString()).isEqualTo("APPROVAL");
    assertThat(policy.path("classification").path("kind").asString()).isEqualTo("MUTATION");
    assertThat(policy.path("rule").path("id").asString()).isEqualTo("dev.sql.write-approval");
    assertThat(policy.path("rate").path("perToken").asInt()).isPositive();
    assertThat(policy.path("rate").path("tokenCalls").asInt()).isBetween(1, policy.path("rate").path("perToken")
        .asInt());
    assertThat(policy.path("rate").path("toolCalls").asInt()).isPositive();
    assertThat(policy.path("limits").path("maxRows").asInt()).isPositive();
    assertThat(policy.path("limits").path("statementTimeoutMs").asLong()).isPositive();
  }

  @Test
  void anApprovedTemplateCallRunsTheBindingOfTheVerifiedTemplateNotTheStoredOne() {
    core.connection("tpl-db", "pgfake", Env.DEV, "db.tpl.internal");
    EndpointService endpoints = core.bean(EndpointService.class);
    endpoints.create("tpl", null, EndpointService.StartFrom.EMPTY, null);
    core.bean(EndpointConnections.class).add("tpl", "tpl-db", null);
    endpoints.updateSettings("tpl", new EndpointService.SettingsUpdate(null, null, null, null, null,
        JsonNodeFactory.instance.objectNode().put("firstWaitSeconds", 0), null), endpoints.get("tpl").version(), null);
    String statement = "UPDATE orders SET status = 'cancelled' WHERE id = 1";
    core.bean(ai.msrv.gaterook.core.templates.TemplateService.class).create("sql-query", "tpl-db", null,
        TestCore.json("{\"name\":\"cancel_order\",\"sql\":\"" + statement + "\",\"mutates\":true}"), null, null);
    TestCore.await(() -> endpoints.snapshot("tpl").pairs().stream().anyMatch(p -> p.tool().name().equals(
        "cancel_order") && p.rowId() != null), "the template tool");
    McpClient agent = new McpClient(core.bean(LocalListener.class).port(), "tpl", core.bean(TokenService.class)
        .issue("tpl", "tpl-agent", 7, null).token());
    FakeConnectors.EXECUTIONS.clear();
    McpClient.Answer pending = agent.modernCall("cancel_order", args("status", "x"), false);
    String handle = handleOf(pending);
    // A template's statement is the approver's: the agent sees the arguments it sent.
    assertThat(pending.result().at("/structuredContent/action/kind").asString()).isEqualTo("arguments");
    assertThat(pending.result().at("/structuredContent/action/text").asString()).isEqualTo("{\"status\":\"x\"}");
    // The binding in the row is covered by no MAC: an edit of it must not decide what runs.
    core.jdbc().update("update approval set call_canonical = replace(call_canonical, 'WHERE id = 1', 'WHERE id > 0') "
        + "where id = ?", handle);
    assertThat(core.jdbc().queryForObject("select call_canonical from approval where id = ?", String.class, handle))
        .contains("WHERE id > 0");
    approvals.decide(handle, ApprovalService.Decision.APPROVE, "app", null, approvals.find(handle).orElseThrow()
        .digest());
    TestCore.await(() -> approvals.find(handle).orElseThrow().terminal(), "the execution");
    assertThat(approvals.find(handle).orElseThrow().status()).isEqualTo("completed");
    assertThat(FakeConnectors.EXECUTIONS).singleElement().satisfies(e -> {
      assertThat(e.tool()).isEqualTo("cancel_order");
      assertThat(e.binding()).isEqualTo(statement);
    });
  }

  // ── The audit comes first ───────────────────────────────────────────────────────────────────────────────────

  @Test
  void nothingRunsWhileTheAuditCannotRecordIt() {
    McpClient agent = dev.as(core.bean(TokenService.class).issue("dev", "audit-agent", 7, null).token());
    FakeConnectors.EXECUTIONS.clear();
    int requestsBefore = core.jdbc().queryForObject("select count(*) from approval", Integer.class);
    core.jdbc().execute("CREATE TRIGGER it_audit_down BEFORE INSERT ON tool_call BEGIN SELECT RAISE(ABORT, "
        + "'disk full'); END");
    try {
      McpClient.Answer read = agent.legacyCall("sql_query", args("connection", "dev-db", "sql", "SELECT 1"));
      assertThat(read.status()).isEqualTo(200);
      assertThat(read.error().path("code").asInt()).isEqualTo(-32603);
      assertThat(read.error().path("message").asString()).isEqualTo("The audit is unavailable, so nothing ran. Try "
          + "again later.");
      SpecSchemas.assertValid("2025-11-25", "JSONRPCErrorResponse", read.body());
      McpClient.Answer write = agent.modernCall("sql_execute", args("connection", "dev-db", "sql",
          "UPDATE orders SET status = 'x' WHERE id = 404;"), false);
      assertThat(write.error().path("code").asInt()).isEqualTo(-32603);
      // The request and its received row commit together: the same answer as for the read, not an internal error.
      assertThat(write.error().path("message").asString()).isEqualTo("The audit is unavailable, so nothing ran. Try "
          + "again later.");
      SpecSchemas.assertValid("2026-07-28", "JSONRPCErrorResponse", write.body());
      assertThat(FakeConnectors.EXECUTIONS).isEmpty();
      assertThat(core.jdbc().queryForObject("select count(*) from approval", Integer.class)).isEqualTo(requestsBefore);
    } finally {
      core.jdbc().execute("DROP TRIGGER it_audit_down");
    }

    // The call ran but its result couldn't be recorded: it isn't shown, and nothing new runs until it is.
    core.jdbc().execute("CREATE TRIGGER it_audit_down BEFORE INSERT ON tool_call WHEN NEW.phase = 'completed' BEGIN "
        + "SELECT RAISE(ABORT, 'disk full'); END");
    try {
      McpClient.Answer ran = agent.legacyCall("sql_query", args("connection", "dev-db", "sql", "SELECT id FROM backlog"));
      assertThat(ran.error().path("code").asInt()).isEqualTo(-32603);
      assertThat(ran.error().path("message").asString()).startsWith("The audit couldn't record the result");
      assertThat(FakeConnectors.EXECUTIONS).hasSize(1);
      McpClient.Answer next = agent.legacyCall("sql_query", args("connection", "dev-db", "sql", "SELECT 2"));
      assertThat(next.error().path("message").asString()).startsWith("MCP Gateway is catching up with its audit");
      assertThat(FakeConnectors.EXECUTIONS).hasSize(1);
    } finally {
      core.jdbc().execute("DROP TRIGGER it_audit_down");
    }
    TestCore.await(() -> !agent.legacyCall("sql_query", args("connection", "dev-db", "sql", "SELECT 3")).result()
        .isMissingNode(), "the backlog is written");
    assertThat(core.jdbc().queryForObject("select count(*) from tool_call where phase = 'completed' and call_id in "
        + "(select call_id from tool_call where phase = 'received' and args_masked like '%FROM backlog%')",
        Integer.class)).isEqualTo(1);
  }

  /**
   * §5 step 11, "write failure after execute": the mutation ran, then the database refused both its completed row and
   * the request's status change. Nothing of the outcome is lost: new calls are refused until both are stored, and then
   * the request says completed, with exactly one completed row.
   */
  @Test
  void anExecutedMutationWhoseOutcomeCantBeStoredHoldsNewCallsUntilItIs() {
    McpClient agent = dev.as(core.bean(TokenService.class).issue("dev", "outcome-agent", 7, null).token());
    FakeConnectors.EXECUTIONS.clear();
    ApprovalView a = request("UPDATE orders SET status = 'held' WHERE id = 77;");
    core.jdbc().execute("CREATE TRIGGER it_outcome_down BEFORE INSERT ON tool_call WHEN NEW.phase = 'completed' BEGIN "
        + "SELECT RAISE(ABORT, 'disk full'); END");
    core.jdbc().execute("CREATE TRIGGER it_status_down BEFORE UPDATE OF status ON approval WHEN NEW.status IN "
        + "('completed', 'failed') BEGIN SELECT RAISE(ABORT, 'disk full'); END");
    ai.msrv.gaterook.core.audit.AuditWriter audit = core.bean(ai.msrv.gaterook.core.audit.AuditWriter.class);
    try {
      approvals.decide(a.id(), ApprovalService.Decision.APPROVE, "app", null, a.digest());
      TestCore.await(() -> FakeConnectors.EXECUTIONS.size() == 1, "the execution");
      TestCore.await(() -> !audit.available(), "the audit holding new calls");
      McpClient.Answer next = agent.legacyCall("sql_query", args("connection", "dev-db", "sql", "SELECT 5"));
      assertThat(next.error().path("code").asInt()).isEqualTo(-32603);
      assertThat(next.error().path("message").asString()).startsWith("MCP Gateway is catching up with its audit");
      assertThat(approvals.find(a.id()).orElseThrow().status()).isEqualTo("running");
      assertThat(FakeConnectors.EXECUTIONS).hasSize(1);
    } finally {
      core.jdbc().execute("DROP TRIGGER it_outcome_down");
      core.jdbc().execute("DROP TRIGGER it_status_down");
    }
    TestCore.await(() -> approvals.find(a.id()).orElseThrow().status().equals("completed"), "the stored outcome");
    TestCore.await(audit::available, "the audit catching up");
    assertThat(core.jdbc().queryForList("select decision from tool_call where call_id = ? and phase = 'completed'",
        String.class, a.callId())).containsExactly("approved");
    // The requester (the dev token of request()) asks how it went.
    McpClient.Answer done = dev.legacyCall("approval_status", args("handle", a.id()));
    SpecSchemas.assertValid("2025-11-25", "CallToolResult", done.result());
    assertThat(done.result().at("/structuredContent/status").asString()).isEqualTo("completed");
    assertThat(done.result().at("/content/0/text").asString())
        .isEqualTo("Approved by you and executed on dev-db (dev): 1 row updated");
    assertThat(FakeConnectors.EXECUTIONS).hasSize(1);
    assertThat(agent.legacyCall("sql_query", args("connection", "dev-db", "sql", "SELECT 6")).result()
        .path("isError").asBoolean()).isFalse();
  }

  /** §5 step 11, "write failure at the resolved step": the Approve can't be recorded, so nothing runs, and it says so. */
  @Test
  void anApprovalTheAuditCantRecordRunsNothingAndEndsTheCall() {
    FakeConnectors.EXECUTIONS.clear();
    ApprovalView a = request("UPDATE orders SET status = 'unrecorded' WHERE id = 78;");
    core.jdbc().execute("CREATE TRIGGER it_resolved_down BEFORE INSERT ON tool_call WHEN NEW.phase = 'resolved' BEGIN "
        + "SELECT RAISE(ABORT, 'disk full'); END");
    ApprovalView after;
    try {
      after = approvals.decide(a.id(), ApprovalService.Decision.APPROVE, "app", null, a.digest());
    } finally {
      core.jdbc().execute("DROP TRIGGER it_resolved_down");
    }
    assertThat(after.status()).isEqualTo("failed");
    assertThat(after.error()).isEqualTo("Audit unavailable: the decision couldn't be recorded, so nothing ran.");
    McpClient.Answer status = dev.legacyCall("approval_status", args("handle", a.id()));
    SpecSchemas.assertValid("2025-11-25", "CallToolResult", status.result());
    assertThat(status.result().path("isError").asBoolean()).isTrue();
    assertThat(status.result().at("/structuredContent/status").asString()).isEqualTo("failed");
    assertThat(status.result().at("/content/0/text").asString()).contains("Audit unavailable");
    // The call doesn't stay pending in Activity: its last row says how it ended.
    assertThat(core.jdbc().queryForList("select phase || ':' || decision from tool_call where call_id = ? order by id",
        String.class, a.callId())).containsExactly("received:pending", "completed:error");
    assertThat(FakeConnectors.EXECUTIONS).isEmpty();
  }

  @Test
  void argumentsNestedTooDeepAreInvalidArguments() {
    ObjectNode deep = args("connection", "dev-db", "sql", "SELECT 1");
    ObjectNode at = deep.putObject("note_detail");
    for (int i = 0; i < 40; i++) {
      at = at.putObject("n");
    }
    McpClient.Answer answer = dev.legacyCall("sql_query", deep);
    SpecSchemas.assertValid("2025-11-25", "CallToolResult", answer.result());
    assertThat(answer.result().path("isError").asBoolean()).isTrue();
    assertThat(answer.result().at("/content/0/text").asString())
        .isEqualTo("Invalid arguments: / nested deeper than 32 levels");
  }

  // ── The reference legacy client: MCP Java SDK 2.1.0 ─────────────────────────────────────────────────────────

  @Test
  void theJavaSdkLegacyClientRunsTheHandleFlow() {
    String token = core.bean(TokenService.class).issue("dev", "java-sdk", 7, null).token();
    var transport = io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
        .builder(core.bean(LocalListener.class).origin()).endpoint("/mcp/dev")
        .requestBuilder(java.net.http.HttpRequest.newBuilder().header("Authorization", "Bearer " + token)).build();
    try (var client = io.modelcontextprotocol.client.McpClient.sync(transport).requestTimeout(Duration.ofSeconds(20))
        .clientInfo(new io.modelcontextprotocol.spec.McpSchema.Implementation("java-sdk-it", "2.1.0")).build()) {
      var init = client.initialize();
      assertThat(init.protocolVersion()).isEqualTo("2025-11-25");
      assertThat(init.serverInfo().name()).isEqualTo("mcp-gateway");
      assertThat(client.getServerInstructions()).contains("approval_status");
      var tools = client.listTools().tools().stream().map(io.modelcontextprotocol.spec.McpSchema.Tool::name).toList();
      assertThat(tools).contains("sql_query", "sql_execute", "approval_status");

      var read = client.callTool(new io.modelcontextprotocol.spec.McpSchema.CallToolRequest("sql_query",
          Map.of("connection", "dev-db", "sql", "SELECT id, status FROM orders")));
      assertThat(read.isError()).isFalse();
      assertThat(((Map<?, ?>) read.structuredContent()).get("rowCount")).isEqualTo(3);

      var pending = client.callTool(new io.modelcontextprotocol.spec.McpSchema.CallToolRequest("sql_execute",
          Map.of("connection", "dev-db", "sql", "UPDATE orders SET status = 'sdk' WHERE id = 48220;")));
      Map<?, ?> s = (Map<?, ?>) pending.structuredContent();
      assertThat(s.get("status")).isEqualTo("pending");
      String handle = (String) s.get("handle");
      approvals.decide(handle, ApprovalService.Decision.APPROVE, "app", null, approvals.find(handle).orElseThrow()
          .digest());
      var done = client.callTool(new io.modelcontextprotocol.spec.McpSchema.CallToolRequest("approval_status",
          Map.of("handle", handle, "wait_seconds", 10)));
      assertThat(((Map<?, ?>) done.structuredContent()).get("status")).isEqualTo("completed");
      assertThat(((io.modelcontextprotocol.spec.McpSchema.TextContent) done.content().getFirst()).text())
          .isEqualTo("Approved by you and executed on dev-db (dev): 1 row updated");
    }
    assertThat(core.jdbc().queryForObject("select count(*) from tool_call where token_label = 'java-sdk' and "
        + "protocol_version = '2025-11-25' and client_info like '%java-sdk-it%'", Integer.class)).isPositive();
  }

  // ── The approval page ───────────────────────────────────────────────────────────────────────────────────────

  @Test
  void theApprovalPageOnlyOpensTheApp() throws Exception {
    ApprovalView a = request("UPDATE orders SET status = 'x' WHERE id = 9;");
    var http = java.net.http.HttpClient.newHttpClient();
    var response = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(
        core.bean(LocalListener.class).origin() + "/approvals/" + a.id())).build(),
        java.net.http.HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Security-Policy").orElseThrow())
        .startsWith("default-src 'none'; frame-ancestors 'none'");
    assertThat(response.body()).contains("mcp-gateway://approvals/" + a.id()).doesNotContain("UPDATE orders");
    approvals.decide(a.id(), ApprovalService.Decision.DENY, "app", null, null);
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

  /** A pending request made through the handle path (no wait: the endpoint's first wait is skipped here). */
  static ApprovalView request(String sql) {
    McpClient.Answer r = dev.modernCall("sql_execute", args("connection", "dev-db", "sql", sql), false);
    String handle = r.result().at("/structuredContent/handle").asString();
    return approvals.find(handle).orElseThrow();
  }

  /** The retry of an MRTR round: the same call with the state and the client's answer to the elicitation. */
  static ObjectNode retry(String sql, String state, String action) {
    return retry("sql_execute", sql, state, action);
  }

  static ObjectNode retry(String tool, String sql, String state, String action) {
    ObjectNode p = JsonNodeFactory.instance.objectNode();
    p.put("name", tool);
    p.set("arguments", args("connection", "dev-db", "sql", sql));
    p.put("requestState", state);
    p.putObject("inputResponses").putObject("gateway_approval").put("action", action);
    return p;
  }

  /**
   * A {@code CallToolResult} of a tool with an {@code outputSchema}, as the spec wants it and as the TS SDK checks it:
   * valid for its revision, and either an error or {@code structuredContent} that matches the schema.
   */
  static void assertConforms(String revision, JsonNode outputSchema, McpClient.Answer answer) {
    SpecSchemas.assertValid(revision, "CallToolResult", answer.result());
    if (!answer.result().path("isError").asBoolean()) {
      SpecSchemas.assertMatchesOutputSchema(outputSchema, answer.result().path("structuredContent"));
    }
  }

  /** The id of the pending request whose action holds {@code sql}, once a call in flight has made it. */
  static String awaitRequest(String sql) {
    String[] id = new String[1];
    TestCore.await(() -> {
      List<String> ids = core.jdbc().queryForList("select id from approval where status = 'pending' and action like ?",
          String.class, "%" + sql + "%");
      id[0] = ids.isEmpty() ? null : ids.getFirst();
      return id[0] != null;
    }, "the request of " + sql);
    return id[0];
  }

  /** The request a pending answer names: the handle, or the end of the approval URL of an elicitation. */
  static String handleOf(McpClient.Answer a) {
    JsonNode r = a.result();
    if ("input_required".equals(r.path("resultType").asString())) {
      String url = r.at("/inputRequests/gateway_approval/params/url").asString();
      return url.substring(url.lastIndexOf('/') + 1);
    }
    return r.at("/structuredContent/handle").asString();
  }

  static String tokenOf(McpClient c) {
    try {
      var f = McpClient.class.getDeclaredField("token");
      f.setAccessible(true);
      return (String) f.get(c);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  static List<String> names(JsonNode tools) {
    List<String> out = new java.util.ArrayList<>();
    tools.forEach(t -> out.add(t.path("name").asString()));
    return out;
  }

  static JsonNode tool(JsonNode list, String name) {
    for (JsonNode t : list.path("tools")) {
      if (t.path("name").asString().equals(name)) {
        return t;
      }
    }
    throw new AssertionError("No tool " + name);
  }
}
