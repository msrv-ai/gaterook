package ai.msrv.gaterook.core.mcp;

import static ai.msrv.gaterook.core.mcp.McpClient.args;
import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.endpoints.EndpointConnections;
import ai.msrv.gaterook.core.endpoints.EndpointService;
import ai.msrv.gaterook.core.endpoints.TokenService;
import ai.msrv.gaterook.core.platform.LocalListener;
import ai.msrv.gaterook.core.policy.PolicyService;
import ai.msrv.gaterook.testfixtures.TestCore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The gateway's own time per call (report 01 §8 step 8, Requirements: p95 ≤ 50 ms): 1,000 {@code sql_query} calls in each era
 * against the in-memory fake, measured at the client, minus the time the audit says the call spent in the resource.
 * What remains is HTTP, authentication, the snapshot, policy, two audit rows with their fsync, masking, the limiter
 * and the JSON — everything the gateway adds.
 */
class McpOverheadIT {

  static final int CALLS = 1_000;
  static final int WARM_UP = 200;
  static final long P95_BUDGET_MS = 50;

  static TestCore core;
  static int port;

  @BeforeAll
  static void start() {
    core = TestCore.start("mcp-overhead");
    core.connection("dev-db", "pgfake", Env.DEV, "db.dev.internal");
    EndpointService endpoints = core.bean(EndpointService.class);
    endpoints.create("dev", null, EndpointService.StartFrom.EMPTY, null);
    core.bean(EndpointConnections.class).add("dev", "dev-db", null);
    // The rate limits of dev (120 per token, 30 per tool a minute) are what this test would measure otherwise.
    PolicyService policies = core.bean(PolicyService.class);
    policies.update(Env.DEV, TestCore.json("{\"limits\":{\"rateLimitPerToken\":10000,\"rateLimitPerTool\":10000}}"),
        policies.get(Env.DEV).version(), "load test", "danger");
    port = core.bean(LocalListener.class).port();
  }

  @AfterAll
  static void stop() {
    if (core != null) {
      core.close();
    }
  }

  @Test
  void legacyCallsAddAtMost50MsAtP95() {
    measure("legacy", false);
  }

  @Test
  void modernCallsAddAtMost50MsAtP95() {
    measure("modern", true);
  }

  private static void measure(String era, boolean modern) {
    McpClient client = new McpClient(port, "dev", core.bean(TokenService.class).issue("dev", "load-" + era, 7, null)
        .token());
    for (int i = 0; i < WARM_UP; i++) {
      call(client, modern, i);
    }
    Map<String, Long> latencyByCall = new HashMap<>();
    for (int i = 0; i < CALLS; i++) {
      long t0 = System.nanoTime();
      McpClient.Answer a = call(client, modern, i);
      long micros = (System.nanoTime() - t0) / 1_000;
      assertThat(a.status()).isEqualTo(200);
      assertThat(a.result().path("isError").asBoolean(true)).as(a.body().toString()).isFalse();
      latencyByCall.put(a.result().at("/_meta/ai.msrv.gaterook~1callId").asString(), micros);
    }
    Map<String, Long> resourceMs = new HashMap<>();
    core.jdbc().query("select call_id, resource_ms from tool_call where phase = 'completed' and token_label = ?",
        rs -> {
          resourceMs.put(rs.getString(1), rs.getLong(2));
        }, "load-" + era);
    List<Long> overheadMicros = new ArrayList<>();
    latencyByCall.forEach((callId, micros) -> {
      assertThat(resourceMs).as("audited " + callId).containsKey(callId);
      overheadMicros.add(Math.max(0, micros - resourceMs.get(callId) * 1_000));
    });
    long[] sorted = overheadMicros.stream().mapToLong(Long::longValue).sorted().toArray();
    double p50 = percentile(sorted, 0.50);
    double p95 = percentile(sorted, 0.95);
    double p99 = percentile(sorted, 0.99);
    System.out.printf("MCP overhead (%s, %d calls): p50 %.1f ms, p95 %.1f ms, p99 %.1f ms, max %.1f ms%n", era,
        sorted.length, p50, p95, p99, sorted[sorted.length - 1] / 1_000.0);
    assertThat(sorted).hasSize(CALLS);
    assertThat(p95).as("p95 overhead of %s calls, ms (all: %s)", era, Arrays.toString(Arrays.copyOfRange(sorted,
        sorted.length - 10, sorted.length))).isLessThanOrEqualTo(P95_BUDGET_MS);
  }

  private static McpClient.Answer call(McpClient client, boolean modern, int i) {
    var a = args("connection", "dev-db", "sql", "SELECT id, status FROM orders WHERE id = " + i);
    return modern ? client.modernCall("sql_query", a, false) : client.legacyCall("sql_query", a);
  }

  /** Nearest rank, in milliseconds. */
  private static double percentile(long[] sortedMicros, double p) {
    int rank = (int) Math.ceil(p * sortedMicros.length) - 1;
    return sortedMicros[Math.max(0, rank)] / 1_000.0;
  }
}
