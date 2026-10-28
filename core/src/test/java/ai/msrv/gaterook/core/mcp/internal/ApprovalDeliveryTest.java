package ai.msrv.gaterook.core.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.core.approvals.ApprovalAnswers;
import ai.msrv.gaterook.core.approvals.ApprovalService;
import ai.msrv.gaterook.core.approvals.ApprovalView;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshot;
import ai.msrv.gaterook.core.platform.Caller;
import ai.msrv.gaterook.core.platform.Ingress;
import ai.msrv.gaterook.core.platform.LocalListener;
import ai.msrv.gaterook.core.platform.MaskPolicy;
import ai.msrv.gaterook.core.platform.Masker;
import ai.msrv.gaterook.core.policy.PolicyService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.IntNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * MRTR rounds with a fake clock and a fake approvals service (report 01 §8.1): the first round, holds of 20 s, the
 * 45 s budget, round 5, decline and cancel, and the handoff to the handle.
 */
class ApprovalDeliveryTest {

  static final Instant T0 = Instant.parse("2026-10-22T12:00:00Z");
  static final String SQL = "UPDATE orders SET status = 'cancelled' WHERE id = 48213;";

  final RequestStateCodecTest.MutableClock clock = new RequestStateCodecTest.MutableClock(T0);
  final ApprovalService approvals = mock(ApprovalService.class);
  final ApprovalAnswers answers = new ApprovalAnswers(new LocalListener(() -> 7465), Masker.standard(),
      policies());
  final RequestStateCodec codec = new RequestStateCodec(RequestStateCodecTest.keys("k"), JsonMapper.builder().build(),
      clock);
  final ApprovalDelivery delivery = new ApprovalDelivery(approvals, answers, codec, clock);
  final String digest = RequestStateCodec.argumentsDigest("sql_execute", JsonNodeFactory.instance.objectNode());

  // ── First round ─────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void aModernClientWithUrlElicitationGetsInputRequiredAtOnce() {
    McpRequest r = request(true, "auto", 15);
    ApprovalDelivery.Answer a = delivery.first(r, view("pending", null), digest);
    assertThat(a.result()).isNull();
    ObjectNode ir = a.inputRequired();
    assertThat(ir.path("resultType").asString()).isEqualTo("input_required");
    JsonNode e = ir.path("inputRequests").path(ApprovalDelivery.INPUT_KEY);
    assertThat(e.path("method").asString()).isEqualTo("elicitation/create");
    assertThat(e.at("/params/mode").asString()).isEqualTo("url");
    assertThat(e.at("/params/url").asString()).isEqualTo("http://127.0.0.1:7465/approvals/ap_1");
    assertThat(e.at("/params/message").asString()).startsWith("GateRook: approve sql_execute on dev-db (dev)? "
        + SQL + "  Request ap_1, auto-deny at ").endsWith("Decide in the GateRook window.");
    RequestStateCodec.State state = state(ir);
    assertThat(state.round()).isEqualTo(1);
    assertThat(state.t0()).isEqualTo(T0);
    // No wait in the first MRTR round: the client shows the link now.
    verify(approvals, never()).await(anyString(), any());
  }

  @Test
  void everyoneElseWaitsForAQuickDecisionThenGetsTheHandle() {
    when(approvals.await(eq("ap_1"), eq(Duration.ofSeconds(15)))).thenReturn(view("pending", null));
    ApprovalDelivery.Answer a = delivery.first(request(false, "auto", 15), view("pending", null), digest);
    assertThat(a.inputRequired()).isNull();
    assertThat(a.result().structuredContent().path("status").asString()).isEqualTo("pending");
    assertThat(a.result().content().getFirst().text()).startsWith("NOT EXECUTED YET.");

    // A decision within the first wait answers with the outcome.
    when(approvals.await(eq("ap_1"), eq(Duration.ofSeconds(15)))).thenReturn(view("completed", ToolResult.text(
        "1 row updated")));
    a = delivery.first(request(false, "auto", 15), view("pending", null), digest);
    assertThat(a.result().content().getFirst().text()).isEqualTo("Approved by you and executed on dev-db (dev): "
        + "1 row updated");
  }

  @Test
  void handleOnlyEndpointsAndAZeroWaitSkipBoth() {
    ApprovalDelivery.Answer a = delivery.first(request(true, "handle-only", 0), view("pending", null), digest);
    assertThat(a.inputRequired()).isNull();
    assertThat(a.result().structuredContent().path("status").asString()).isEqualTo("pending");
    verify(approvals, never()).await(anyString(), any());
  }

  @Test
  void appsGetTheHandleAtOnceWithoutMisreportingAnAlreadyRunningRequest() {
    McpRequest r = request(true, "auto", 25);
    ((ObjectNode) r.env().clientCapabilities()).putObject("extensions").putObject(McpAppResource.EXTENSION)
        .putArray("mimeTypes").add(McpAppResource.MIME_TYPE);
    assertThat(ApprovalDelivery.mrtr(r)).isFalse();
    for (String status : List.of("pending", "running", "rejected")) {
      ApprovalDelivery.Answer a = delivery.first(r, view(status, null), digest);
      assertThat(a.inputRequired()).isNull();
      assertThat(a.result().structuredContent().path("status").asString()).isEqualTo(status);
    }
    verify(approvals, never()).await(anyString(), any());
  }

  @Test
  void theHandleFlowAlsoKeepsTheRunningStateAfterItsFirstWait() {
    when(approvals.await("ap_1", Duration.ofSeconds(15))).thenReturn(view("running", null));
    ApprovalDelivery.Answer a = delivery.first(request(false, "auto", 15), view("pending", null), digest);
    assertThat(a.result().structuredContent().path("status").asString()).isEqualTo("running");
    assertThat(a.result().content().getFirst().text()).contains("is running").doesNotContain("NOT EXECUTED YET");
  }

  // ── Retries ─────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void aRetryHoldsUpTo20SecondsAndAsksAgainWithTheNextRound() {
    McpRequest r = request(true, "auto", 15);
    mine(view("pending", null));
    when(approvals.await("ap_1", ApprovalDelivery.HOLD_PER_ROUND)).thenAnswer(inv -> {
      clock.advance(ApprovalDelivery.HOLD_PER_ROUND);
      return view("pending", null);
    });
    ApprovalDelivery.Answer a = delivery.resume(r, new RequestStateCodec.State("ap_1", 1, T0), accept(), digest)
        .orElseThrow();
    assertThat(a.inputRequired().path("resultType").asString()).isEqualTo("input_required");
    // Later rounds carry only the state: the client already shows the link.
    assertThat(a.inputRequired().has("inputRequests")).isFalse();
    RequestStateCodec.State next = state(a.inputRequired());
    assertThat(next.round()).isEqualTo(2);
    assertThat(next.t0()).isEqualTo(T0);
  }

  @Test
  void theLastHoldIsCutToWhatIsLeftOfThe45SecondBudget() {
    clock.set(T0.plusSeconds(40));
    mine(view("pending", null));
    when(approvals.await("ap_1", Duration.ofSeconds(5))).thenReturn(view("pending", null));
    delivery.resume(request(true, "auto", 15), new RequestStateCodec.State("ap_1", 3, T0), accept(), digest);
    verify(approvals).await("ap_1", Duration.ofSeconds(5));
  }

  @Test
  void pastTheBudgetOrRoundFiveTheHandleTakesOver() {
    mine(view("pending", null));
    clock.set(T0.plus(ApprovalDelivery.MRTR_BUDGET));
    ApprovalDelivery.Answer late = delivery.resume(request(true, "auto", 15), new RequestStateCodec.State("ap_1", 3,
        T0), accept(), digest).orElseThrow();
    assertThat(late.inputRequired()).isNull();
    assertThat(late.result().structuredContent().path("status").asString()).isEqualTo("pending");
    assertThat(late.result().structuredContent().path("handle").asString()).isEqualTo("ap_1");

    clock.set(T0.plusSeconds(1));
    ApprovalDelivery.Answer many = delivery.resume(request(true, "auto", 15), new RequestStateCodec.State("ap_1",
        ApprovalDelivery.MAX_ROUNDS, T0), accept(), digest).orElseThrow();
    assertThat(many.inputRequired()).isNull();
    assertThat(many.result().structuredContent().path("status").asString()).isEqualTo("pending");
    verify(approvals, never()).await(anyString(), any());
  }

  @Test
  void aDecisionDuringTheHoldIsTheAnswer() {
    mine(view("pending", null));
    when(approvals.await("ap_1", ApprovalDelivery.HOLD_PER_ROUND)).thenReturn(view("completed",
        ToolResult.text("1 row updated")));
    ApprovalDelivery.Answer a = delivery.resume(request(true, "auto", 15), new RequestStateCodec.State("ap_1", 1, T0),
        accept(), digest).orElseThrow();
    assertThat(a.result().isError()).isFalse();
    assertThat(a.result().structuredContent().path("status").asString()).isEqualTo("completed");
  }

  @Test
  void aTerminalRequestAnswersAtOnce() {
    mine(view("rejected", null));
    ApprovalDelivery.Answer a = delivery.resume(request(true, "auto", 15), new RequestStateCodec.State("ap_1", 1, T0),
        accept(), digest).orElseThrow();
    assertThat(a.result().isError()).isTrue();
    assertThat(a.result().structuredContent().path("status").asString()).isEqualTo("rejected");
    verify(approvals, never()).await(anyString(), any());
  }

  @Test
  void declineRejectsTheRequestAndCancelHandsOverToTheHandle() {
    mine(view("pending", null));
    when(approvals.declineInClient("ap_1")).thenReturn(view("rejected", null, "client"));
    ApprovalDelivery.Answer declined = delivery.resume(request(true, "auto", 15), new RequestStateCodec.State("ap_1",
        1, T0), action("decline"), digest).orElseThrow();
    verify(approvals).declineInClient("ap_1");
    assertThat(declined.result().isError()).isTrue();
    assertThat(declined.result().structuredContent().path("status").asString()).isEqualTo("rejected");

    ApprovalDelivery.Answer cancelled = delivery.resume(request(true, "auto", 15), new RequestStateCodec.State(
        "ap_1", 1, T0), action("cancel"), digest).orElseThrow();
    assertThat(cancelled.inputRequired()).isNull();
    assertThat(cancelled.result().structuredContent().path("status").asString()).isEqualTo("pending");
    verify(approvals, never()).await(anyString(), any());
  }

  @Test
  void aStateOfARequestThatIsNotTheCallersIsIgnored() {
    when(approvals.forCaller("ap_1", "ep_1", "pr_1")).thenReturn(Optional.empty());
    assertThat(delivery.resume(request(true, "auto", 15), new RequestStateCodec.State("ap_1", 1, T0), accept(),
        digest)).isEmpty();
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

  static PolicyService policies() {
    PolicyService policies = mock(PolicyService.class);
    when(policies.maskPolicy(any())).thenReturn(MaskPolicy.SECRETS_ONLY);
    return policies;
  }

  private void mine(ApprovalView v) {
    when(approvals.forCaller("ap_1", "ep_1", "pr_1")).thenReturn(Optional.of(v));
  }

  private RequestStateCodec.State state(ObjectNode inputRequired) {
    return codec.open(inputRequired.path("requestState").asString(), "pr_1", "ep_1", "tools/call", "sql_execute",
        digest).orElseThrow();
  }

  private static JsonNode accept() {
    return action("accept");
  }

  private static JsonNode action(String a) {
    ObjectNode n = JsonNodeFactory.instance.objectNode();
    n.putObject(ApprovalDelivery.INPUT_KEY).put("action", a);
    return n;
  }

  static McpRequest request(boolean elicitationUrl, String approvalDelivery, int firstWait) {
    ObjectNode caps = JsonNodeFactory.instance.objectNode();
    if (elicitationUrl) {
      caps.putObject("elicitation").putObject("url");
    }
    Envelope env = new Envelope(Era.MODERN, Era.MODERN_VERSION, IntNode.valueOf(1), "tools/call",
        JsonNodeFactory.instance.objectNode().put("name", "sql_execute"), caps, null, null);
    ObjectNode settings = JsonNodeFactory.instance.objectNode();
    settings.putObject("protocol").put("approvalDelivery", approvalDelivery);
    settings.putObject("approvalWait").put("firstWaitSeconds", firstWait);
    EndpointSnapshot snapshot = new EndpointSnapshot("ep_1", "dev", null, Env.DEV, false, true, null, settings, false,
        false, null, 1, List.of(), List.of(), List.of(), null);
    Caller caller = new Caller(Caller.Kind.LOCAL_TOKEN, "pr_1", "it", null, Ingress.LOCAL, "127.0.0.1");
    return new McpRequest(env, caller, snapshot, null, null);
  }

  static ApprovalView view(String status, ToolResult result) {
    return view(status, result, "completed".equals(status) || "rejected".equals(status) ? "app" : null);
  }

  static ApprovalView view(String status, ToolResult result, String via) {
    return new ApprovalView("ap_1", "tc_1", "ep_1", "dev", "cn_1", "dev-db", "sql_execute", "pgfake/sql_execute",
        Env.DEV, Caller.Kind.LOCAL_TOKEN, "pr_1", "it", status, "d".repeat(64), ActionPreview.of(ActionPreview.Kind.SQL,
        "sql", SQL, "dev-db", "UPDATE orders"), "a".repeat(64), null, "dev.sql.mutations", "optional", null, via,
        via == null ? null : T0, T0, T0.plusSeconds(300), null, result, null, null, null,
        JsonNodeFactory.instance.objectNode());
  }
}
