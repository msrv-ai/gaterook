package ai.msrv.gaterook.core.mcp;

import static ai.msrv.gaterook.core.mcp.McpClient.args;
import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.core.approvals.ApprovalService;
import ai.msrv.gaterook.core.approvals.ApprovalView;
import ai.msrv.gaterook.core.endpoints.EndpointConnections;
import ai.msrv.gaterook.core.endpoints.EndpointService;
import ai.msrv.gaterook.core.endpoints.EndpointTools;
import ai.msrv.gaterook.core.endpoints.TokenService;
import ai.msrv.gaterook.core.platform.LocalListener;
import ai.msrv.gaterook.testfixtures.FakeConnectors;
import ai.msrv.gaterook.testfixtures.TestCore;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Calls that run as tasks (ARCHITECTURE.md §5.6): with {@code params.task} of 2025-11-25 the answer is the task at
 * once, and a client of 2026-07-28 that takes tasks of the Tasks extension gets one when the call takes longer than a
 * moment. A task waits for a person's decision itself and then carries what ran; cancelling it declines the request
 * nobody decided yet, or stops the call that runs. A task is its client's only.
 */
class McpTasksIT {

  static final String SQL = "UPDATE orders SET status = 'cancelled' WHERE id = 48213;";
  static final Pattern REQUEST = Pattern.compile("request (ap_[A-Za-z0-9]+)");

  static TestCore core;
  static McpClient dev;
  static ApprovalService approvals;

  @BeforeAll
  static void start() {
    core = TestCore.start("tasks");
    core.connection("dev-db", "pgfake", Env.DEV, "db.dev.internal");
    core.connection("steps-mcp", "mcpfake", Env.DEV, "share.internal");
    EndpointService endpoints = core.bean(EndpointService.class);
    endpoints.create("dev", null, EndpointService.StartFrom.EMPTY, null);
    EndpointConnections wiring = core.bean(EndpointConnections.class);
    wiring.add("dev", "dev-db", null);
    wiring.add("dev", "steps-mcp", null);
    EndpointTools tools = core.bean(EndpointTools.class);
    for (String tool : List.of("gh_wait", "gh_search")) {
      var pair = endpoints.snapshot("dev").pairs().stream().filter(p -> p.tool().name().equals(tool)).findFirst()
          .orElseThrow();
      tools.setMode("dev", pair.toolKey(), "steps-mcp", Mode.BYPASS, null, pair.rowVersion(), "Test");
    }
    // An ordinary call waits a second for a quick decision here (15 s by default), so the tests stay short.
    endpoints.updateSettings("dev", new EndpointService.SettingsUpdate(null, null, null, null, null,
        JsonNodeFactory.instance.objectNode().put("firstWaitSeconds", 1), null), endpoints.get("dev").version(), null);
    dev = new McpClient(core.bean(LocalListener.class).port(), "dev",
        core.bean(TokenService.class).issue("dev", "tasks", 7, null).token());
    approvals = core.bean(ApprovalService.class);
  }

  @AfterAll
  static void stop() {
    if (core != null) {
      core.close();
    }
  }

  private static ObjectNode sqlCall() {
    ObjectNode p = JsonNodeFactory.instance.objectNode().put("name", "sql_execute");
    p.set("arguments", args("connection", "dev-db", "sql", SQL));
    return p;
  }

  private static ObjectNode taskId(String id) {
    return JsonNodeFactory.instance.objectNode().put("taskId", id);
  }

  private static ObjectNode takesTasks() {
    ObjectNode caps = JsonNodeFactory.instance.objectNode();
    caps.putObject("extensions").putObject("io.modelcontextprotocol/tasks");
    return caps;
  }

  /** Asks {@code ask} until its answer passes {@code done}, for up to 10 s. */
  private static JsonNode until(java.util.function.Supplier<McpClient.Answer> ask, Predicate<JsonNode> done)
      throws InterruptedException {
    long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (true) {
      JsonNode result = ask.get().result();
      if (done.test(result)) {
        return result;
      }
      assertThat(System.nanoTime()).as("an answer within 10 s, last " + result).isLessThan(end);
      Thread.sleep(100);
    }
  }

  private static String approvalOf(JsonNode task) {
    Matcher m = REQUEST.matcher(task.path("statusMessage").asString(""));
    assertThat(m.find()).as("the request the task waits for: " + task).isTrue();
    return m.group(1);
  }

  @Test
  void aTaskOf2025WaitsForTheDecisionAndCarriesWhatRan() throws Exception {
    FakeConnectors.EXECUTIONS.clear();
    JsonNode init = dev.initialize("2025-11-25").result();
    SpecSchemas.assertValid("2025-11-25", "InitializeResult", init);
    JsonNode tasks = init.path("capabilities").path("tasks");
    assertThat(tasks.path("requests").path("tools").path("call").isObject()).isTrue();
    assertThat(tasks.path("list").isObject()).isTrue();
    assertThat(tasks.path("cancel").isObject()).isTrue();
    assertThat(dev.initialize("2025-06-18").result().path("capabilities").has("tasks")).isFalse();
    JsonNode listed = dev.legacy("tools/list", JsonNodeFactory.instance.objectNode()).result();
    SpecSchemas.assertValid("2025-11-25", "ListToolsResult", listed);
    for (JsonNode t : listed.path("tools")) {
      String expected = t.path("name").asString().equals("sql_execute") ? "optional" : null;
      if (expected != null) {
        assertThat(t.path("execution").path("taskSupport").asString()).isEqualTo(expected);
      }
      if (t.path("name").asString().equals("approval_status")) {
        assertThat(t.has("execution")).as("the gateway's own tools answer at once").isFalse();
      }
    }

    ObjectNode params = sqlCall();
    params.putObject("task").put("ttl", 120_000);
    McpClient.Answer created = dev.legacy("tools/call", params);
    SpecSchemas.assertValid("2025-11-25", "CreateTaskResult", created.result());
    JsonNode task = created.result().path("task");
    String id = task.path("taskId").asString();
    assertThat(task.path("status").asString()).isEqualTo("working");
    assertThat(task.path("ttl").asLong()).isEqualTo(120_000);
    assertThat(task.path("pollInterval").asLong()).isPositive();

    JsonNode waiting = until(() -> dev.legacy("tasks/get", taskId(id)),
        t -> t.path("statusMessage").asString("").startsWith("Waiting for a person to approve it in GateRook"));
    SpecSchemas.assertValid("2025-11-25", "GetTaskResult", waiting);
    assertThat(waiting.path("status").asString()).isEqualTo("working");
    String handle = approvalOf(waiting);
    assertThat(FakeConnectors.EXECUTIONS).isEmpty();
    JsonNode list = dev.legacy("tasks/list", JsonNodeFactory.instance.objectNode()).result();
    SpecSchemas.assertValid("2025-11-25", "ListTasksResult", list);
    assertThat(list.path("tasks")).extracting(t -> t.path("taskId").asString()).contains(id);
    // Another client knows nothing of it.
    McpClient other = dev.as(core.bean(TokenService.class).issue("dev", "someone-else", 7, null).token());
    assertThat(other.legacy("tasks/get", taskId(id)).error().path("code").asInt()).isEqualTo(-32602);
    assertThat(other.legacy("tasks/list", JsonNodeFactory.instance.objectNode()).result().path("tasks")).isEmpty();

    ApprovalView view = approvals.find(handle).orElseThrow();
    approvals.decide(handle, ApprovalService.Decision.APPROVE, "app", null, view.digest());
    JsonNode done = until(() -> dev.legacy("tasks/get", taskId(id)), t -> t.path("status").asString().equals(
        "completed"));
    SpecSchemas.assertValid("2025-11-25", "GetTaskResult", done);
    McpClient.Answer result = dev.legacy("tasks/result", taskId(id));
    SpecSchemas.assertValid("2025-11-25", "CallToolResult", result.result());
    assertThat(result.result().at("/content/0/text").asString())
        .isEqualTo("Approved by you and executed on dev-db (dev): 1 row updated");
    assertThat(result.result().path("_meta").path("io.modelcontextprotocol/related-task").path("taskId").asString())
        .isEqualTo(id);
    assertThat(FakeConnectors.EXECUTIONS).singleElement().satisfies(e -> assertThat(e.approved()).isTrue());
    assertThat(dev.legacy("tasks/cancel", taskId(id)).error().path("code").asInt()).isEqualTo(-32602);
  }

  @Test
  void cancellingATaskDeclinesItsUndecidedRequestOrStopsItsCall() throws Exception {
    ObjectNode params = sqlCall();
    params.putObject("task");
    String id = dev.legacy("tools/call", params).result().path("task").path("taskId").asString();
    String handle = approvalOf(until(() -> dev.legacy("tasks/get", taskId(id)),
        t -> REQUEST.matcher(t.path("statusMessage").asString("")).find()));

    McpClient.Answer cancelled = dev.legacy("tasks/cancel", taskId(id));
    SpecSchemas.assertValid("2025-11-25", "CancelTaskResult", cancelled.result());
    assertThat(cancelled.result().path("status").asString()).isEqualTo("cancelled");
    assertThat(approvals.find(handle).orElseThrow().status()).isEqualTo("rejected");
    assertThat(dev.legacy("tasks/result", taskId(id)).error().path("code").asInt()).isEqualTo(-32602);

    FakeConnectors.CANCELLED.clear();
    ObjectNode wait = JsonNodeFactory.instance.objectNode().put("name", "gh_wait");
    wait.set("arguments", args("connection", "steps-mcp", "query", "x"));
    wait.putObject("task");
    String waiting = dev.legacy("tools/call", wait).result().path("task").path("taskId").asString();
    Thread.sleep(500);
    assertThat(dev.legacy("tasks/cancel", taskId(waiting)).result().path("status").asString()).isEqualTo("cancelled");
    TestCore.await(() -> FakeConnectors.CANCELLED.size() == 1, "the call stopped");

    // The gateway's own tools answer at once.
    ObjectNode own = JsonNodeFactory.instance.objectNode().put("name", "approval_status");
    own.set("arguments", args("handle", handle));
    own.putObject("task");
    assertThat(dev.legacy("tools/call", own).error().path("code").asInt()).isEqualTo(-32601);
  }

  @Test
  void aClientOf2026GetsATaskWhenTheCallTakesLonger() throws Exception {
    JsonNode discovered = dev.modern("server/discover", JsonNodeFactory.instance.objectNode(), false).result();
    assertThat(discovered.path("capabilities").path("extensions").has("io.modelcontextprotocol/tasks")).isTrue();

    // A call that ends within a moment is answered as usual.
    ObjectNode quick = JsonNodeFactory.instance.objectNode().put("name", "gh_search");
    quick.set("arguments", args("connection", "steps-mcp", "query", "x"));
    McpClient.Answer at = dev.modern("tools/call", quick, takesTasks());
    SpecSchemas.assertValid("2026-07-28", "CallToolResultResponse", at.body());
    assertThat(at.result().path("resultType").asString()).isEqualTo("complete");

    McpClient.Answer created = dev.modern("tools/call", sqlCall(), takesTasks());
    assertThat(created.status()).isEqualTo(200);
    assertThat(created.result().path("resultType").asString()).isEqualTo("task");
    String id = created.result().path("taskId").asString();
    assertThat(created.result().path("status").asString()).isEqualTo("working");
    assertThat(created.result().path("ttlMs").asLong()).isPositive();
    assertThat(created.result().path("pollIntervalMs").asLong()).isPositive();
    String handle = approvalOf(created.result().has("statusMessage") ? created.result()
        : until(() -> dev.modern("tasks/get", taskId(id), takesTasks()), t -> t.has("statusMessage")));

    // Mcp-Name names the task.
    McpClient.Answer misnamed = dev.post(dev.request("tasks/get", modernParams(taskId(id))).toString(), Map.of(
        "MCP-Protocol-Version", "2026-07-28", "Mcp-Method", "tasks/get", "Mcp-Name", "tsk_other"));
    assertThat(misnamed.error().path("code").asInt()).isEqualTo(-32020);
    assertThat(dev.modern("tasks/update", taskId(id), takesTasks()).error().path("code").asInt()).isEqualTo(-32602);

    approvals.decide(handle, ApprovalService.Decision.APPROVE, "app", null, approvals.find(handle).orElseThrow()
        .digest());
    JsonNode done = until(() -> dev.modern("tasks/get", taskId(id), takesTasks()),
        t -> t.path("status").asString().equals("completed"));
    assertThat(done.path("resultType").asString()).isEqualTo("complete");
    assertThat(done.path("result").at("/content/0/text").asString())
        .isEqualTo("Approved by you and executed on dev-db (dev): 1 row updated");
    assertThat(done.path("result").has("resultType")).isFalse();
    assertThat(dev.modern("tasks/cancel", taskId(id), takesTasks()).error().path("code").asInt()).isEqualTo(-32602);
    assertThat(dev.modern("tasks/get", taskId("tsk_nobody"), takesTasks()).error().path("code").asInt())
        .isEqualTo(-32602);

    // Without the extension the same call is an ordinary one: the handle answers.
    McpClient.Answer plain = dev.modern("tools/call", sqlCall(), false);
    assertThat(plain.result().path("resultType").asString()).isEqualTo("complete");
    String again = plain.result().path("structuredContent").path("handle").asString();
    approvals.decide(again, ApprovalService.Decision.DENY, "app", null, null);
  }

  private static ObjectNode modernParams(ObjectNode params) {
    ObjectNode meta = params.putObject("_meta");
    meta.put("io.modelcontextprotocol/protocolVersion", "2026-07-28");
    meta.set("io.modelcontextprotocol/clientCapabilities", takesTasks());
    meta.putObject("io.modelcontextprotocol/clientInfo").put("name", "codex-mcp-client").put("version", "0.164.0");
    return params;
  }
}
