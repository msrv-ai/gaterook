package ai.msrv.gaterook.core.admin;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.audit.AuditWriter;
import ai.msrv.gaterook.core.platform.Caller;
import ai.msrv.gaterook.core.platform.CanonicalJson;
import ai.msrv.gaterook.core.platform.Ids;
import ai.msrv.gaterook.core.platform.Ingress;
import ai.msrv.gaterook.core.platform.LocalListener;
import ai.msrv.gaterook.testfixtures.TestCore;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The contract of {@code GET /api/insights} (ARCHITECTURE.md §6 insights; the UI's {@code insights.api.ts}) through
 * MockMvc: the first-run answer before any call, every field the page reads afterwards, the query language narrowing
 * every number but {@code unfilteredCalls}, and 422s at the field for a bad query or range.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InsightsApiIT {

  private static final List<String> TOP = List.of("from", "to", "firstCallAt", "unfilteredCalls", "kpis",
      "callsOverTime", "topTools", "byClient", "decisions", "approvals", "deniedByRule", "latency",
      "errorsByConnection", "unusual");
  private static final List<String> TOTALS = List.of("calls", "pending", "approved", "denied", "errors", "tasks",
      "gatewayP50Ms", "gatewayP95Ms");

  private static TestCore core;
  private static AdminMvc api;

  @BeforeAll
  static void start() {
    core = TestCore.start("insights-api", "--gateway.audit.export.tick=1h", "--gateway.backup.catch-up-delay=1h");
    api = new AdminMvc(core.context(), core.bean(LocalListener.class).port());
  }

  @AfterAll
  static void stop() {
    if (core != null) {
      core.close();
    }
  }

  @Test
  @Order(1)
  void beforeTheFirstCallTheAnswerSaysSo() {
    JsonNode i = api.ok("/api/insights");
    assertThat(i.properties()).extracting(java.util.Map.Entry::getKey).containsExactlyInAnyOrderElementsOf(TOP);
    assertThat(i.path("firstCallAt").isNull()).as("first run").isTrue();
    assertThat(i.path("unfilteredCalls").asLong()).isZero();
    assertThat(i.path("kpis").path("previous").isNull()).isTrue();
    JsonNode now = i.path("kpis").path("current");
    assertThat(now.properties()).extracting(java.util.Map.Entry::getKey).containsExactlyInAnyOrderElementsOf(TOTALS);
    assertThat(now.path("gatewayP95Ms").isNull()).isTrue();
    assertThat(i.path("approvals").path("approvedShare").isNull()).isTrue();
    assertThat(i.path("approvals").path("waitHistogram").isEmpty()).isTrue();
    assertThat(i.path("latency").path("byConnection").isArray()).isTrue();
    assertThat(i.path("unusual").isEmpty()).isTrue();
    assertThat(Duration.between(Instant.parse(i.path("from").asString()), Instant.parse(i.path("to").asString())))
        .as("the last 24 hours by default").isEqualTo(Duration.ofDays(1));
  }

  @Test
  @Order(2)
  void theNumbersOfTheRangeAndTheFilter() {
    call("sql_query", "dev-db", "allowed", null, "codex");
    call("sql_query", "dev-db", "allowed", null, "claude-desktop");
    call("sql_execute", "prod-db", "denied", "prod.sql.block_ddl", "claude-desktop");
    call("redis_get", "cache", "error", null, "codex");
    Instant to = Instant.now().plusSeconds(1);
    String range = "from=" + enc(to.minus(Duration.ofHours(1)).toString()) + "&to=" + enc(to.toString());

    JsonNode i = api.ok("/api/insights?" + range);
    assertThat(i.path("firstCallAt").isNull()).isFalse();
    JsonNode now = i.path("kpis").path("current");
    assertThat(now.path("calls").asLong()).isEqualTo(4);
    assertThat(now.path("denied").asLong()).isEqualTo(1);
    assertThat(now.path("errors").asLong()).isEqualTo(1);
    assertThat(now.path("gatewayP95Ms").asLong()).isEqualTo(5);
    assertThat(i.path("kpis").path("previous").isNull()).as("the audit doesn't reach back an hour").isTrue();
    assertThat(i.path("decisions").path("allowed").asLong()).isEqualTo(2);
    assertThat(i.path("topTools").get(0).path("name").asString()).isEqualTo("sql_query");
    assertThat(i.path("topTools").get(0).path("count").asLong()).isEqualTo(2);
    JsonNode client = i.path("byClient").get(0);
    for (String field : List.of("client", "count", "decisions", "endpoints", "connections", "firstAt", "lastAt")) {
      assertThat(client.has(field)).as(field).isTrue();
    }
    JsonNode rule = i.path("deniedByRule").get(0);
    assertThat(rule.path("rule").asString()).isEqualTo("prod.sql.block_ddl");
    assertThat(rule.path("example").path("literal").asString()).isEqualTo("DROP TABLE");
    assertThat(rule.path("example").path("env").asString()).isEqualTo("prod");
    JsonNode errors = i.path("errorsByConnection").get(0);
    assertThat(errors.path("connection").asString()).isEqualTo("cache");
    assertThat(errors.path("kind").asString()).isEqualTo("TIMEOUT");
    JsonNode latency = i.path("latency").path("byConnection").get(0);
    for (String field : List.of("connection", "env", "calls", "gatewayP50", "gatewayP95", "resourceP50",
        "resourceP95")) {
      assertThat(latency.has(field)).as(field).isTrue();
    }
    for (String field : List.of("approved", "rejected", "expired", "medianWaitS", "p90WaitS", "approvedShare",
        "waitHistogram", "slowest")) {
      assertThat(i.path("approvals").has(field)).as(field).isTrue();
    }
    JsonNode histogram = i.path("callsOverTime");
    assertThat(histogram.path("stepHours").asInt()).isEqualTo(1);
    long total = 0;
    for (JsonNode b : histogram.path("buckets")) {
      total += b.path("total").asLong();
    }
    assertThat(total).isEqualTo(4);

    JsonNode filtered = api.ok("/api/insights?q=" + enc("client:codex") + "&" + range);
    assertThat(filtered.path("kpis").path("current").path("calls").asLong()).isEqualTo(2);
    assertThat(filtered.path("unfilteredCalls").asLong()).as("312 of 1,284").isEqualTo(4);
  }

  @Test
  @Order(3)
  void aBadQueryOrRangeIsA422AtItsField() {
    assertThat(api.get("/api/insights?q=" + enc("status:denied")).json().path("field").asString()).isEqualTo("/q");
    Instant now = Instant.now();
    assertThat(api.get("/api/insights?from=" + enc(now.toString()) + "&to=" + enc(now.minusSeconds(60).toString()))
        .json().path("field").asString()).isEqualTo("/to");
    assertThat(api.get("/api/insights?from=yesterday").json().path("field").asString()).isEqualTo("/from");
    assertThat(api.get("/api/insights?from=" + enc(now.minus(Duration.ofDays(120)).toString())).status())
        .isEqualTo(422);
  }

  private static void call(String tool, String connection, String decision, String rule, String client) {
    AuditWriter writer = core.bean(AuditWriter.class);
    Caller caller = new Caller(Caller.Kind.LOCAL_TOKEN, "tok_" + client, client + "-token", client, Ingress.LOCAL,
        "127.0.0.1");
    ObjectNode args = JsonNodeFactory.instance.objectNode();
    if (rule != null) {
      args.put("sql", "DROP TABLE orders_archive");
    }
    Env env = connection.startsWith("prod") ? Env.PROD : Env.DEV;
    AuditWriter.Call call = new AuditWriter.Call(Ids.next("tc_"), env == Env.PROD ? "incident-prod" : "dev", tool,
        tool, connection, env, caller, client, null, "2025-11-25", "4bf92f3577b34da6a3ce929d0e0e4736",
        "00f067aa0ba902b7", false, args, CanonicalJson.sha256Hex(args), null);
    switch (decision) {
      case "denied" -> writer.received(call, AuditWriter.Received.of(AuditWriter.Verdict.DENIED,
          Classification.Kind.DENY, rule));
      default -> {
        writer.received(call, AuditWriter.Received.of(AuditWriter.Verdict.ALLOWED, Classification.Kind.READ, null));
        boolean error = decision.equals("error");
        writer.completed(call, new AuditWriter.Completion(error ? AuditWriter.Verdict.ERROR
            : AuditWriter.Verdict.ALLOWED, null, null, null, null, 2005L, 5L, 2000L, null, 4L, "0".repeat(64),
            error ? null : "1 row", 0, false, error ? "TIMEOUT" : null, error ? "ETIMEDOUT after 2.0 s" : null, null,
            null, null));
      }
    }
  }

  private static String enc(String text) {
    return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
