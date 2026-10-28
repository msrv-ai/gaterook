package ai.msrv.gaterook.core.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.core.approvals.ApprovalAnswers;
import ai.msrv.gaterook.core.approvals.ApprovalService;
import ai.msrv.gaterook.core.approvals.ApprovalView;
import ai.msrv.gaterook.core.audit.AuditUnavailableException;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshot;
import ai.msrv.gaterook.core.platform.Caller;
import ai.msrv.gaterook.core.platform.EventBus;
import ai.msrv.gaterook.core.platform.Ingress;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

class ApprovalCardsTest {

  static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");
  static final String HANDLE = "ap_123456abcdef";
  final RequestStateCodecTest.MutableClock clock = new RequestStateCodecTest.MutableClock(NOW);
  final ApprovalService approvals = mock(ApprovalService.class);
  final ApprovalAnswers answers = mock(ApprovalAnswers.class);
  final EventBus events = mock(EventBus.class);
  final ApprovalCards cards = new ApprovalCards(approvals, answers, events, clock);

  @BeforeEach
  void projectionIsTheExistingMaskedAgentView() {
    when(answers.agentAction(any())).thenReturn(new ApprovalAnswers.AgentAction("arguments",
        "{\"collection\":\"orders\",\"password\":\"‹masked›\"}"));
  }

  @AfterEach
  void noCardCanApprove() {
    verify(approvals, never()).decide(any(), any(), any(), any(), any());
    verify(approvals, never()).decide(any(), any(), any(), any(), any(), any());
  }

  @Test
  void aCardContainsOnlyTheMaskedAgentProjectionAndNoDecisionCapability() {
    ApprovalView view = view("pending");
    owned(view);
    ToolResult result = cards.call(request(), args(), false);
    assertThat(result.isError()).isFalse();
    JsonNode data = result.structuredContent();
    assertThat(data.path("kind").asString()).isEqualTo("gaterook.approval-card");
    assertThat(data.path("version").asInt()).isEqualTo(1);
    assertThat(data.path("handle").asString()).isEqualTo(HANDLE);
    assertThat(data.path("environment").asString()).isEqualTo("dev");
    assertThat(data.path("status").asString()).isEqualTo("pending");
    assertThat(data.path("serverTime").asString()).isEqualTo(NOW.toString());
    assertThat(data.at("/request/language").asString()).isEqualTo("json");
    assertThat(data.at("/request/text").asString()).contains("‹masked›");
    assertThat(data.path("approvalInApp").asBoolean()).isTrue();
    assertThat(data.path("commentRequired").asBoolean()).isTrue();
    assertThat(data.path("canDeny").asBoolean()).isTrue();
    assertThat(data.path("canOpen").asBoolean()).isTrue();
    assertThat(data.toString()).doesNotContain("private-", "digest", "agentNote", "actionSha256", "token");
    assertThat(result.meta()).isNull();
    verify(answers).agentAction(view);
    verifyNoInteractions(events);
  }

  @Test
  void anotherPrincipalOrEndpointGetsTheSameUnknownAnswerWithoutSideEffects() {
    for (String action : List.of("status", "open", "deny")) {
      ToolResult unknown = cards.call(request(), args().put("action", action), true);
      ToolResult otherPrincipal = cards.call(request("ep-owned", "pr-other"), args().put("action", action), true);
      ToolResult otherEndpoint = cards.call(request("ep-other", "pr-owned"), args().put("action", action), true);
      assertThat(otherPrincipal).isEqualTo(unknown);
      assertThat(otherEndpoint).isEqualTo(unknown);
      assertThat(unknown.isError()).isTrue();
    }
    verify(approvals, times(3)).forCaller(HANDLE, "ep-owned", "pr-other");
    verify(approvals, times(3)).forCaller(HANDLE, "ep-other", "pr-owned");
    verify(approvals, never()).declineInClient(anyString());
    verifyNoInteractions(answers, events);
  }

  @Test
  void forgedDecisionsCapabilitiesRoutesAndMalformedHandlesAreRejectedBeforeLookup() {
    List<ObjectNode> invalid = List.of(
        args().put("action", "approve"),
        args().put("action", "deny").put("digest", "private-digest"),
        args().put("action", "open").put("path", "/settings"),
        args().put("action", "deny").put("via", "app"),
        args().put("action", 1),
        args(),
        args().put("action", "open").put("handle", HANDLE + "/../../settings"));
    for (ObjectNode input : invalid) {
      assertThat(cards.call(request(), input, true).isError()).isTrue();
    }
    assertThat(cards.call(request(), args().put("action", "status"), false).isError()).isTrue();
    verifyNoInteractions(approvals, answers, events);
  }

  @Test
  void openingUsesOnlyTheOwnedHandleAndThrottlesRepeatedFocusRequests() {
    owned(view("completed"));
    ObjectNode open = args().put("action", "open");
    assertThat(cards.call(request(), open, true).isError()).isFalse();
    assertThat(cards.call(request(), open, true).isError()).isTrue();
    ArgumentCaptor<JsonNode> payload = ArgumentCaptor.forClass(JsonNode.class);
    verify(events).publish(eq("ui.open"), payload.capture());
    assertThat(payload.getValue()).isEqualTo(JsonNodeFactory.instance.objectNode()
        .put("path", "/approvals/" + HANDLE));
    clock.advance(ApprovalCards.OPEN_INTERVAL);
    assertThat(cards.call(request(), open, true).isError()).isFalse();
    verify(events, times(2)).publish(eq("ui.open"), any());
    verify(approvals, never()).declineInClient(anyString());
  }

  @Test
  void theFocusThrottleIsBoundedAndIndependentOfUntrustedRequestHandles() {
    when(approvals.forCaller(eq(HANDLE), eq("ep-owned"), anyString())).thenReturn(Optional.of(view("pending")));
    for (int i = 0; i < ApprovalCards.MAX_OPEN_CALLERS; i++) {
      assertThat(cards.call(request("ep-owned", "pr-" + i), args().put("action", "open"), true).isError())
          .isFalse();
    }
    assertThat(cards.call(request("ep-owned", "pr-next"), args().put("action", "open"), true).isError()).isTrue();
    clock.advance(ApprovalCards.OPEN_INTERVAL);
    assertThat(cards.call(request("ep-owned", "pr-next"), args().put("action", "open"), true).isError()).isFalse();
  }

  @Test
  void denyingUsesTheClientPathAndReturnsTheAuthoritativeResultIdempotently() {
    ApprovalView rejected = view("rejected");
    when(approvals.forCaller(HANDLE, "ep-owned", "pr-owned"))
        .thenReturn(Optional.of(view("pending")), Optional.of(rejected));
    when(approvals.declineInClient(HANDLE)).thenReturn(rejected);
    for (int attempt = 0; attempt < 2; attempt++) {
      JsonNode result = cards.call(request(), args().put("action", "deny"), true).structuredContent();
      assertThat(result.path("status").asString()).isEqualTo("rejected");
      assertThat(result.path("canDeny").asBoolean()).isFalse();
    }
    verify(approvals).declineInClient(HANDLE);
    verifyNoInteractions(events);
  }

  @Test
  void aConcurrentApprovalRemainsRunningAndItsExecutionResultIsNotDisclosed() {
    owned(view("pending"));
    when(approvals.declineInClient(HANDLE)).thenReturn(view("running"));
    JsonNode result = cards.call(request(), args().put("action", "deny"), true).structuredContent();
    assertThat(result.path("status").asString()).isEqualTo("running");
    assertThat(result.path("canDeny").asBoolean()).isFalse();
    assertThat(result.has("result")).isFalse();
  }

  @Test
  void expiredAndTerminalRequestsCannotBeDeniedOrResurrected() {
    owned(view("pending"));
    clock.set(NOW.plusSeconds(300));
    JsonNode waitingForTimer = cards.call(request(), args().put("action", "deny"), true).structuredContent();
    // Only the expiration transaction can claim a terminal status. The deadline still disables the card action.
    assertThat(waitingForTimer.path("status").asString()).isEqualTo("pending");
    assertThat(waitingForTimer.path("canDeny").asBoolean()).isFalse();
    for (String status : List.of("running", "completed", "rejected", "expired", "failed", "cancelled")) {
      owned(view(status));
      JsonNode result = cards.call(request(), args().put("action", "deny"), true).structuredContent();
      assertThat(result.path("status").asString()).isEqualTo(status);
      assertThat(result.path("canDeny").asBoolean()).isFalse();
    }
    verify(approvals, never()).declineInClient(anyString());
  }

  @Test
  void suspiciousCharactersAreVisibleAndTheExpandedPreviewIsBounded() {
    owned(view("pending"));
    when(answers.agentAction(any())).thenReturn(new ApprovalAnswers.AgentAction("sql", "UPDATE оrders\u202E\t "));
    JsonNode data = cards.call(request(), args(), false).structuredContent();
    assertThat(data.at("/request/text").asString()).isEqualTo("UPDATE ‹U+043E›rders‹U+202E›‹U+0009›‹U+0020›");
    assertThat(data.at("/request/truncated").asBoolean()).isFalse();
    when(answers.agentAction(any())).thenReturn(new ApprovalAnswers.AgentAction("sql", "\u202E".repeat(13_000)));
    data = cards.call(request(), args(), false).structuredContent();
    String preview = data.at("/request/text").asString();
    assertThat(preview.length()).isLessThanOrEqualTo(ApprovalCards.PREVIEW_LIMIT);
    assertThat(preview).endsWith("›").doesNotContain("\u202E");
    assertThat(data.at("/request/truncated").asBoolean()).isTrue();
  }

  @Test
  void jsonIsReadableWithoutChangingNumberLexemesOrStringEscapes() {
    owned(view("pending"));
    String number = "123456789012345678901234567890.1234567890123456789";
    String encoded = "\"comma, colon: brace{} bracket[] quote\\\" backslash\\\\ newline\\n literal\\\\u202e\"";
    String source = "{\"array\":[{},[],{\"number\":" + number + "}],\"text\":" + encoded + "}";
    when(answers.agentAction(any())).thenReturn(new ApprovalAnswers.AgentAction("arguments", source));
    JsonNode data = cards.call(request(), args(), false).structuredContent();
    String expected = String.join("\n", "{", "  \"array\": [", "    {},", "    [],", "    {",
        "      \"number\": " + number, "    }", "  ],", "  \"text\": " + encoded, "}");
    assertThat(data.at("/request/text").asString()).isEqualTo(expected);
    assertThat(data.at("/request/truncated").asBoolean()).isFalse();
  }

  @Test
  void indentationAndDepthBudgetsFallBackToTheCompleteCompactValue() {
    owned(view("pending"));
    for (String source : List.of("{\"entries\":[" + "{},".repeat(2_000) + "{}]}",
        "[".repeat(65) + "0" + "]".repeat(65))) {
      when(answers.agentAction(any())).thenReturn(new ApprovalAnswers.AgentAction("arguments", source));
      JsonNode data = cards.call(request(), args(), false).structuredContent();
      assertThat(data.at("/request/text").asString()).isEqualTo(source);
      assertThat(data.at("/request/truncated").asBoolean()).isFalse();
    }
  }

  @Test
  void oversizedJsonStaysAnExplicitlyTruncatedCompactPreview() {
    owned(view("pending"));
    String source = "{\"value\":\"" + "x".repeat(ApprovalCards.PREVIEW_LIMIT) + "\"}";
    when(answers.agentAction(any())).thenReturn(new ApprovalAnswers.AgentAction("arguments", source));
    JsonNode data = cards.call(request(), args(), false).structuredContent();
    assertThat(data.at("/request/text").asString()).isEqualTo(source.substring(0, ApprovalCards.PREVIEW_LIMIT));
    assertThat(data.at("/request/truncated").asBoolean()).isTrue();
  }

  @Test
  void truncatingDoesNotSplitASupplementaryUnicodeCharacter() {
    owned(view("pending"));
    when(answers.agentAction(any())).thenReturn(new ApprovalAnswers.AgentAction("arguments",
        "a".repeat(ApprovalCards.PREVIEW_LIMIT - 1) + "😀suffix"));
    JsonNode data = cards.call(request(), args(), false).structuredContent();
    assertThat(data.at("/request/text").asString()).isEqualTo("a".repeat(ApprovalCards.PREVIEW_LIMIT - 1));
    assertThat(data.at("/request/truncated").asBoolean()).isTrue();
  }

  @Test
  void anAuditFailureCannotProduceASuccessfulDenialReceipt() {
    owned(view("pending"));
    when(approvals.declineInClient(HANDLE)).thenThrow(new AuditUnavailableException("unavailable", null));
    assertThatThrownBy(() -> cards.call(request(), args().put("action", "deny"), true))
        .isInstanceOf(AuditUnavailableException.class);
    verifyNoInteractions(answers, events);
  }

  private void owned(ApprovalView view) {
    when(approvals.forCaller(HANDLE, "ep-owned", "pr-owned")).thenReturn(Optional.of(view));
  }

  private static ObjectNode args() {
    return JsonNodeFactory.instance.objectNode().put("handle", HANDLE);
  }

  private static McpRequest request() {
    return request("ep-owned", "pr-owned");
  }

  private static McpRequest request(String endpoint, String principal) {
    EndpointSnapshot snapshot = new EndpointSnapshot(endpoint, "dev", null, Env.DEV, false, true, null,
        JsonNodeFactory.instance.objectNode(), false, false, null, 1, List.of(), List.of(), List.of(), null);
    Caller caller = new Caller(Caller.Kind.LOCAL_TOKEN, principal, "test-client", null, Ingress.LOCAL, "127.0.0.1");
    return new McpRequest(null, caller, snapshot, null, null);
  }

  private static ApprovalView view(String status) {
    return new ApprovalView(HANDLE, "tc_private-call", "ep-owned", "dev", "cn-private", "dev-mongo", "mongo_update",
        "private-tool-key", Env.DEV, Caller.Kind.LOCAL_TOKEN, "pr-owned", "private-client-label", status,
        "private-digest", ActionPreview.of(ActionPreview.Kind.JSON, "json", "private-prepared-action",
            "private-target", "private-summary"),
        "private-action-sha", "private-agent-note", "private-rule-id", "required", "private-comment", null, null,
        NOW, NOW.plusSeconds(300), null, ToolResult.text("private-execution-result"), null, "private-error", null,
        JsonNodeFactory.instance.objectNode().put("private-call", "private-arguments"));
  }
}
