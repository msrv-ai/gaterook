package ai.msrv.gaterook.core.mcp;

import static ai.msrv.gaterook.core.mcp.McpClient.args;
import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.core.endpoints.EndpointConnections;
import ai.msrv.gaterook.core.endpoints.EndpointService;
import ai.msrv.gaterook.core.endpoints.EndpointTools;
import ai.msrv.gaterook.core.endpoints.TokenService;
import ai.msrv.gaterook.core.platform.LocalListener;
import ai.msrv.gaterook.testfixtures.FakeConnectors;
import ai.msrv.gaterook.testfixtures.TestCore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * What a client hears while a call runs (ARCHITECTURE.md §5.4): its progress for its {@code progressToken} and the
 * resource's log messages at the level it asked for, on an event stream the POST is answered with once there is
 * something to say, masked, then the answer; a call that says nothing is answered as JSON; and a client's
 * {@code notifications/cancelled} stops its own call.
 */
class McpCallStreamIT {

  static final String CONNECTION = "steps-mcp";
  static final String PASSWORD = "pw-steps-mcp-0123";
  static final JsonMapper JSON = JsonMapper.builder().build();

  static TestCore core;
  static McpClient dev;

  @BeforeAll
  static void start() {
    core = TestCore.start("call-stream");
    core.connection(CONNECTION, "mcpfake", Env.DEV, "share.internal");
    EndpointService endpoints = core.bean(EndpointService.class);
    endpoints.create("dev", null, EndpointService.StartFrom.EMPTY, null);
    core.bean(EndpointConnections.class).add("dev", CONNECTION, null);
    EndpointTools tools = core.bean(EndpointTools.class);
    for (String tool : List.of("gh_steps", "gh_wait", "gh_search")) {
      var pair = endpoints.snapshot("dev").pairs().stream().filter(p -> p.tool().name().equals(tool)).findFirst()
          .orElseThrow();
      tools.setMode("dev", pair.toolKey(), CONNECTION, Mode.BYPASS, null, pair.rowVersion(), "Test");
    }
    dev = new McpClient(core.bean(LocalListener.class).port(), "dev",
        core.bean(TokenService.class).issue("dev", "streaming", 7, null).token());
  }

  @AfterAll
  static void stop() {
    if (core != null) {
      core.close();
    }
  }

  /** The messages of an event stream, in order. */
  static List<JsonNode> events(McpClient.Answer a) {
    List<JsonNode> out = new ArrayList<>();
    for (String block : a.body().asString().split("\n\n")) {
      for (String line : block.split("\n")) {
        if (line.startsWith("data: ")) {
          out.add(JSON.readTree(line.substring(6)));
        }
      }
    }
    return out;
  }

  private static ObjectNode call(String tool, String token) {
    ObjectNode p = JsonNodeFactory.instance.objectNode().put("name", tool);
    p.set("arguments", args("connection", CONNECTION, "query", "x"));
    if (token != null) {
      p.putObject("_meta").put("progressToken", token);
    }
    return p;
  }

  @Test
  void progressComesOnTheStreamOfTheCallMaskedThenTheAnswer() {
    McpClient.Answer a = dev.legacy("tools/call", call("gh_steps", "p-1"));

    assertThat(a.header("content-type")).startsWith("text/event-stream");
    List<JsonNode> events = events(a);
    List<JsonNode> progress = events.stream().filter(e -> "notifications/progress".equals(e.path("method")
        .asString(null))).toList();
    assertThat(progress).hasSizeBetween(1, 3);
    assertThat(progress).allSatisfy(e -> {
      assertThat(e.path("params").path("progressToken").asString()).isEqualTo("p-1");
      assertThat(e.path("params").path("total").asDouble()).isEqualTo(3.0);
      assertThat(e.path("params").path("message").asString()).doesNotContain(PASSWORD).contains("‹masked›");
    });
    assertThat(progress.getLast().path("params").path("progress").asDouble()).isEqualTo(3.0);
    // No level asked for: no log messages.
    assertThat(events).noneMatch(e -> "notifications/message".equals(e.path("method").asString(null)));
    JsonNode answer = events.getLast();
    assertThat(answer.path("result").path("content").get(0).path("text").asString()).isEqualTo("three steps");
  }

  @Test
  void aCallThatSaysNothingIsAnsweredAsJson() {
    McpClient.Answer a = dev.legacy("tools/call", call("gh_search", null));

    assertThat(a.header("content-type")).startsWith("application/json");
    assertThat(a.result().path("isError").asBoolean()).isFalse();
  }

  @Test
  void logMessagesComeFromTheLevelTheClientAskedFor() {
    // 2025 revisions: logging/setLevel, once, for the client's later calls.
    McpClient other = dev.as(core.bean(TokenService.class).issue("dev", "logs", 7, null).token());
    assertThat(other.legacy("logging/setLevel", JsonNodeFactory.instance.objectNode().put("level", "warning"))
        .result().isEmpty()).isTrue();
    List<JsonNode> legacy = events(other.legacy("tools/call", call("gh_steps", null)));
    List<JsonNode> logs = legacy.stream().filter(e -> "notifications/message".equals(e.path("method")
        .asString(null))).toList();
    assertThat(logs).singleElement().satisfies(e -> {
      assertThat(e.path("params").path("level").asString()).isEqualTo("warning");
      assertThat(e.path("params").path("logger").asString()).isEqualTo("steps");
      assertThat(e.path("params").path("data").asString()).doesNotContain(PASSWORD).contains("‹masked›");
    });
    other.legacy("logging/setLevel", JsonNodeFactory.instance.objectNode().put("level", "emergency"));
    assertThat(other.legacy("logging/setLevel", JsonNodeFactory.instance.objectNode().put("level", "loud"))
        .error().path("code").asInt()).isEqualTo(-32602);

    // 2026-07-28: each request says it in its _meta.
    ObjectNode p = call("gh_steps", "p-2");
    ((ObjectNode) p.get("_meta")).put("io.modelcontextprotocol/logLevel", "info");
    McpClient.Answer modern = dev.modern("tools/call", p, false);
    List<JsonNode> events = events(modern);
    for (JsonNode e : events.subList(0, events.size() - 1)) {
      SpecSchemas.assertValid("2026-07-28", e.path("method").asString().equals("notifications/progress")
          ? "ProgressNotification" : "LoggingMessageNotification", e);
    }
    SpecSchemas.assertValid("2026-07-28", "CallToolResultResponse", events.getLast());
    assertThat(events).anyMatch(e -> "notifications/message".equals(e.path("method").asString(null)));
  }

  @Test
  void aClientsCancelStopsItsOwnCall() throws Exception {
    FakeConnectors.CANCELLED.clear();
    ObjectNode body = dev.request("tools/call", call("gh_wait", null));
    long id = body.path("id").asLong();
    long started = System.nanoTime();
    CompletableFuture<McpClient.Answer> running = CompletableFuture.supplyAsync(() -> dev.post(body.toString(),
        Map.of("MCP-Protocol-Version", "2025-11-25")));
    Thread.sleep(700);
    // An id of another token is nobody's call here.
    McpClient stranger = dev.as(core.bean(TokenService.class).issue("dev", "stranger", 7, null).token());
    stranger.post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":" + id
        + "}}", Map.of("MCP-Protocol-Version", "2025-11-25"));
    Thread.sleep(300);
    assertThat(running).isNotDone();
    McpClient.Answer cancel = dev.post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":"
        + "{\"requestId\":" + id + ",\"reason\":\"The user stopped it\"}}", Map.of("MCP-Protocol-Version",
            "2025-11-25"));
    assertThat(cancel.status()).isEqualTo(202);

    McpClient.Answer answer = running.get(15, TimeUnit.SECONDS);
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
    assertThat(FakeConnectors.CANCELLED).hasSize(1);
    assertThat(answer.status()).isEqualTo(200);
  }
}
