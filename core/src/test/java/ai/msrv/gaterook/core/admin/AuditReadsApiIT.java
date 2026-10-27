package ai.msrv.gaterook.core.admin;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.audit.AuditBackfill;
import ai.msrv.gaterook.core.audit.AuditWriter;
import ai.msrv.gaterook.core.platform.Caller;
import ai.msrv.gaterook.core.platform.CanonicalJson;
import ai.msrv.gaterook.core.platform.Ids;
import ai.msrv.gaterook.core.platform.Ingress;
import ai.msrv.gaterook.core.platform.LocalListener;
import ai.msrv.gaterook.core.templates.TemplateService;
import ai.msrv.gaterook.testfixtures.TestCore;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * What the pages read back from the audit through the admin API: when a runbook last ran (Policies › Runbooks, "Last
 * run 5 h ago"), the {@code runbook} filter of Activity — a fact of the request that a call keeps after it completed —
 * and the tasks of Activity › Traces by query and range over the whole audit, not the newest 500 matching calls.
 */
class AuditReadsApiIT {

  private static final Instant T0 = Instant.now().minus(Duration.ofHours(8)).truncatedTo(ChronoUnit.MILLIS);
  private static final String OLD_TASK = "a1".repeat(16);
  private static final String NEW_TASK = "b2".repeat(16);
  /** More calls of the newer task than the old reading of Traces looked at. */
  private static final int NEWER_CALLS = 501;

  private static TestCore core;
  private static AdminMvc api;

  @BeforeAll
  static void start() {
    core = TestCore.start("audit-reads", "--gateway.backup.catch-up-delay=1h");
    api = new AdminMvc(core.context(), core.bean(LocalListener.class).port());
    core.connection("vm-a", "sshfake", Env.DEV, "vm.test");
    TemplateService templates = core.bean(TemplateService.class);
    templates.create("ssh-runbook", "vm-a", "compose-ps", TestCore.json("{\"name\":\"compose-ps\"}"), null, null);
    templates.create("ssh-runbook", "vm-a", "restart-api", TestCore.json("{\"name\":\"restart-api\"}"), null, null);

    List<AuditBackfill.PastCall> past = new ArrayList<>();
    // The old task: compose-ps ran (allowed, completed 120 ms later), restart-api was denied and never ran.
    past.add(sshRun(OLD_TASK, "compose-ps", T0.plus(Duration.ofHours(1)), true));
    past.add(sshRun(OLD_TASK, "restart-api", T0.plus(Duration.ofHours(2)), false));
    // The newer task: many calls on the same connection, none of them a runbook.
    for (int i = 0; i < NEWER_CALLS; i++) {
      past.add(exec(NEW_TASK, T0.plus(Duration.ofHours(4)).plusMillis(i)));
    }
    core.bean(AuditBackfill.class).write(past);
    TestCore.await(() -> api.ok("/api/activity/traces").path("traces").size() == 2, "both tasks summarized");
  }

  @AfterAll
  static void stop() {
    if (core != null) {
      core.close();
    }
  }

  @Test
  void aRunbookSaysWhenItLastRanAndOnlyARunCounts() {
    JsonNode runbooks = api.ok("/api/policies/dev").path("runbooks");
    assertThat(runbooks.findValuesAsString("name")).containsExactlyInAnyOrder("compose-ps", "restart-api");
    for (JsonNode r : runbooks) {
      if (r.path("name").asString().equals("compose-ps")) {
        assertThat(Instant.parse(r.path("lastRunAt").asString())).as("its completed row")
            .isEqualTo(T0.plus(Duration.ofHours(1)).plusMillis(120));
      } else {
        assertThat(r.path("lastRunAt").isNull()).as("a denied request never ran").isTrue();
      }
    }
    assertThat(api.ok("/api/policies").path("dev").path("runbooks").size()).isEqualTo(2);
  }

  @Test
  void theRunbookFilterFindsACallThatCompleted() {
    JsonNode ran = api.ok("/api/activity/events?q=" + enc("runbook:compose-ps")).path("events");
    assertThat(ran.size()).isEqualTo(1);
    assertThat(ran.get(0).path("decision").asString()).isEqualTo("allowed");
    assertThat(ran.get(0).path("phase").asString()).isEqualTo("completed");
    assertThat(api.ok("/api/activity/events?q=" + enc("runbook:restart-*")).path("events").size()).isEqualTo(1);
    assertThat(api.ok("/api/activity/events?q=" + enc("tool:ssh_run -runbook:compose-ps")).path("events")
        .findValuesAsString("decision")).containsExactly("denied");
  }

  @Test
  void tracesMatchTheWholeAuditAndTheRange() {
    assertThat(tasks("q=" + enc("connection:vm-a"))).as("older than the newest 500 matching calls too")
        .containsExactly(NEW_TASK, OLD_TASK);
    assertThat(tasks("q=" + enc("runbook:compose-ps") + "&limit=1")).as("a page is full while older tasks match")
        .containsExactly(OLD_TASK);
    String middle = enc(T0.plus(Duration.ofHours(3)).toString());
    assertThat(tasks("from=" + middle)).containsExactly(NEW_TASK);
    assertThat(tasks("to=" + middle)).containsExactly(OLD_TASK);
    assertThat(tasks("q=" + enc("tool:ssh_exec") + "&to=" + middle)).isEmpty();
    assertThat(api.get("/api/activity/traces?from=yesterday").json().path("field").asString()).isEqualTo("/from");

    JsonNode first = api.ok("/api/activity/traces?limit=1&q=" + enc("connection:vm-a"));
    assertThat(first.path("traces").findValuesAsString("traceId")).containsExactly(NEW_TASK);
    assertThat(api.ok("/api/activity/traces?limit=1&q=" + enc("connection:vm-a") + "&cursor="
        + first.path("nextCursor").asString()).path("traces").findValuesAsString("traceId"))
        .containsExactly(OLD_TASK);
  }

  private static List<String> tasks(String query) {
    return api.ok("/api/activity/traces?" + query).path("traces").findValuesAsString("traceId");
  }

  private static AuditBackfill.PastCall sshRun(String trace, String runbook, Instant at, boolean ran) {
    ObjectNode args = JsonNodeFactory.instance.objectNode().put("connection", "vm-a").put("runbook", runbook);
    ObjectNode facets = JsonNodeFactory.instance.objectNode().put("runbook", runbook);
    AuditWriter.Call call = call("ssh_run", trace, args, facets);
    if (!ran) {
      return new AuditBackfill.PastCall(call, List.of(AuditBackfill.PastRow.received(at, AuditWriter.Received.of(
          AuditWriter.Verdict.DENIED, Classification.Kind.DENY, "dev.ssh.no_restart"))));
    }
    return new AuditBackfill.PastCall(call, List.of(
        AuditBackfill.PastRow.received(at, AuditWriter.Received.of(AuditWriter.Verdict.ALLOWED,
            Classification.Kind.READ, null)),
        AuditBackfill.PastRow.completed(at.plusMillis(120), completion())));
  }

  private static AuditBackfill.PastCall exec(String trace, Instant at) {
    ObjectNode args = JsonNodeFactory.instance.objectNode().put("connection", "vm-a").put("command", "uptime");
    return new AuditBackfill.PastCall(call("ssh_exec", trace, args, null), List.of(
        AuditBackfill.PastRow.received(at, AuditWriter.Received.of(AuditWriter.Verdict.ALLOWED,
            Classification.Kind.READ, null)),
        AuditBackfill.PastRow.completed(at.plusMillis(1), completion())));
  }

  private static AuditWriter.Call call(String tool, String trace, ObjectNode args, ObjectNode facets) {
    Caller caller = new Caller(Caller.Kind.LOCAL_TOKEN, "tok_codex", "codex-token", "codex", Ingress.LOCAL,
        "127.0.0.1");
    return new AuditWriter.Call(Ids.next("tc_"), "dev", tool, tool, "vm-a", Env.DEV, caller, "codex", null,
        "2025-11-25", trace, "00f067aa0ba902b7", true, args, CanonicalJson.sha256Hex(args), facets);
  }

  private static AuditWriter.Completion completion() {
    return new AuditWriter.Completion(AuditWriter.Verdict.ALLOWED, null, null, null, null, 120L, 4L, 116L, null, 2L,
        "0".repeat(64), "ok", 0, false, null, null, null, null, null);
  }

  private static String enc(String text) {
    return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
