package ai.msrv.gaterook.core.mcp;

import static ai.msrv.gaterook.core.mcp.McpClient.args;
import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.approvals.ApprovalService;
import ai.msrv.gaterook.core.endpoints.EndpointConnections;
import ai.msrv.gaterook.core.endpoints.EndpointService;
import ai.msrv.gaterook.core.endpoints.TokenService;
import ai.msrv.gaterook.core.platform.EventBus;
import ai.msrv.gaterook.core.platform.LocalListener;
import ai.msrv.gaterook.testfixtures.FakeConnectors;
import ai.msrv.gaterook.testfixtures.TestCore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Authenticated MCP clients can render their own requests, decline them, or open the protected review UI. */
class McpApprovalCardsIT {

  private static final String RESOURCE = "ui://gaterook/approval-card/v1.html";
  private static final String MIME = "text/html;profile=mcp-app";
  private static TestCore core;
  private static ApprovalService approvals;
  private static TokenService tokens;
  private static int port;

  @BeforeAll
  static void start() {
    core = TestCore.start("mcp-approval-cards");
    core.connection("dev-db", "pgfake", Env.DEV, "db.dev.internal");
    EndpointService endpoints = core.bean(EndpointService.class);
    for (String slug : List.of("dev", "other-dev", "ui-auto")) {
      endpoints.create(slug, null, EndpointService.StartFrom.EMPTY, null);
      core.bean(EndpointConnections.class).add(slug, "dev-db", null);
      endpoints.updateSettings(slug, new EndpointService.SettingsUpdate(null, null, null, null, null,
          JsonNodeFactory.instance.objectNode().put("firstWaitSeconds", slug.equals("ui-auto") ? 25 : 0), null),
          endpoints.get(slug).version(), null);
    }
    approvals = core.bean(ApprovalService.class);
    tokens = core.bean(TokenService.class);
    port = core.bean(LocalListener.class).port();
  }

  @AfterAll
  static void stop() {
    if (core != null) {
      core.close();
    }
  }

  @Test
  void legacyUiNegotiationPersistsAndTheCardResourceHasNoExternalCapabilities() {
    McpClient agent = client("legacy-card", "dev");
    initializeUi(agent);
    JsonNode listed = agent.legacy("tools/list", null).result();
    assertThat(tool(listed, "approval_card").at("/_meta/ui/resourceUri").asString()).isEqualTo(RESOURCE);
    assertThat(tool(listed, "approval_card_action").at("/_meta/ui/visibility").toString())
        .isEqualTo("[\"app\"]");
    assertThat(tool(listed, "approval_card_action").at("/inputSchema/properties/action/enum").toString())
        .doesNotContain("approve").contains("status", "open", "deny");

    JsonNode resources = agent.legacy("resources/list", null).result().path("resources");
    List<JsonNode> descriptors = new ArrayList<>();
    resources.forEach(descriptors::add);
    assertThat(descriptors).anySatisfy(r -> {
      assertThat(r.path("uri").asString()).isEqualTo(RESOURCE);
      assertThat(r.path("mimeType").asString()).isEqualTo(MIME);
    });
    McpClient.Answer read = agent.legacy("resources/read", args("uri", RESOURCE));
    SpecSchemas.assertValid("2025-11-25", "ReadResourceResult", read.result());
    JsonNode resource = read.result().path("contents").get(0);
    assertThat(resource.path("uri").asString()).isEqualTo(RESOURCE);
    assertThat(resource.path("mimeType").asString()).isEqualTo(MIME);
    assertThat(resource.at("/_meta/ui/csp/connectDomains").toString()).isEqualTo("[]");
    assertThat(resource.at("/_meta/ui/csp/resourceDomains").toString()).isEqualTo("[]");
    assertThat(resource.at("/_meta/ui/csp/frameDomains").toString()).isEqualTo("[]");
    assertThat(resource.path("text").asString()).contains("Approve in GateRook", "approval_card_action")
        .doesNotContain("{{KIT_CSS}}", "{{APP_JS}}", "pw-dev-db-0123");
  }

  @Test
  void modernUiMetadataIsPerRequestAndPlainClientsKeepTheHandleFallback() {
    McpClient agent = client("modern-card", "dev");
    JsonNode withUi = modernUi(agent, "tools/list", JsonNodeFactory.instance.objectNode()).result();
    SpecSchemas.assertValid("2026-07-28", "ListToolsResult", withUi);
    assertThat(tool(withUi, "approval_card").at("/_meta/ui/resourceUri").asString()).isEqualTo(RESOURCE);
    assertThat(names(withUi.path("tools"))).contains("approval_card_action");
    JsonNode plain = agent.modern("tools/list", JsonNodeFactory.instance.objectNode(), false).result();
    assertThat(names(plain.path("tools"))).doesNotContain("approval_card_action");
    assertThat(tool(plain, "approval_card").at("/_meta/ui/resourceUri").isMissingNode()).isTrue();

    String handle = pending(agent, "UPDATE orders SET status = 'plain-card' WHERE id = 811;");
    JsonNode status = agent.legacyCall("approval_status", args("handle", handle)).result();
    assertThat(status.at("/structuredContent/status").asString()).isEqualTo("pending");
    assertThat(status.at("/content/0/text").asString()).contains("GateRook");
    JsonNode rendered = agent.legacyCall("approval_card", args("handle", handle)).result();
    assertThat(rendered.at("/content/0/text").asString()).isNotBlank();
    assertThat(rendered.at("/structuredContent/handle").asString()).isEqualTo(handle);
    assertNotExecuted(handle);
    approvals.decide(handle, ApprovalService.Decision.DENY, "app", null, null);
  }

  @Test
  void modernUiGetsTheHandleImmediatelyEvenWhenUrlElicitationIsAvailable() throws Exception {
    McpClient agent = client("ui-and-url", "ui-auto");
    ObjectNode params = args("name", "sql_execute");
    params.set("arguments", args("connection", "dev-db", "sql",
        "UPDATE orders SET status = 'ui-before-url' WHERE id = 819;"));
    // This endpoint normally holds the first response for 25 seconds; a card needs its handle immediately.
    McpClient.Answer answer = CompletableFuture.supplyAsync(() -> modernUi(agent, "tools/call", params, true))
        .get(10, TimeUnit.SECONDS);
    assertThat(answer.status()).isEqualTo(200);
    SpecSchemas.assertValid("2026-07-28", "CallToolResult", answer.result());
    assertThat(answer.result().path("resultType").asString()).isEqualTo("complete");
    assertThat(answer.result().path("inputRequests").isMissingNode()).isTrue();
    assertThat(answer.result().at("/structuredContent/status").asString()).isEqualTo("pending");
    String handle = answer.result().at("/structuredContent/handle").asString();
    assertThat(handle).matches("ap_[a-z0-9]{12}");
    assertThat(approvals.find(handle).orElseThrow().status()).isEqualTo("pending");
    assertNotExecuted(handle);
    approvals.decide(handle, ApprovalService.Decision.DENY, "app", null, null);
  }

  @Test
  void aCardContainsOnlyCallerVisibleRequestDataAndNeverThePreparedActionOrDigest() {
    McpClient agent = client("safe-card-data", "dev");
    String sql = "UPDATE leak_details SET x = 1 WHERE id = 812;";
    String handle = pending(agent, sql);
    JsonNode result = card(agent, handle);
    assertThat(result.path("isError").asBoolean()).isFalse();
    JsonNode data = result.path("structuredContent");
    assertThat(data.path("kind").asString()).isEqualTo("gaterook.approval-card");
    assertThat(data.path("version").asInt()).isEqualTo(1);
    assertThat(data.path("handle").asString()).isEqualTo(handle);
    assertThat(data.path("tool").asString()).isEqualTo("sql_execute");
    assertThat(data.path("connection").asString()).isEqualTo("dev-db");
    assertThat(data.path("environment").asString()).isEqualTo("dev");
    assertThat(data.at("/request/text").asString()).isEqualTo(sql);
    assertThat(data.path("approvalInApp").asBoolean()).isTrue();
    assertThat(Instant.parse(data.path("expiresAt").asString())).isAfter(Instant.parse(data.path("serverTime")
        .asString()));
    assertThat(result.toString()).doesNotContain("pw-dev-db-0123", "Runs as", "checked with", "agentNote",
        "actionSha256", "digest", approvals.find(handle).orElseThrow().digest());
    approvals.decide(handle, ApprovalService.Decision.DENY, "app", null, null);

    String diffHandle = pending(agent, "DIFF deployment/api replicas=5");
    JsonNode diff = card(agent, diffHandle);
    assertThat(diff.at("/structuredContent/request/text").asString()).contains("DIFF deployment/api replicas=5");
    assertThat(diff.toString()).doesNotContain("live-state", "replicas: 3", "replicas: 5");
    assertThat(approvals.find(diffHandle).orElseThrow().action().text()).contains("live-state");
    approvals.decide(diffHandle, ApprovalService.Decision.DENY, "app", null, null);
  }

  @Test
  void anotherPrincipalOrEndpointCannotReadOpenOrDenyTheRequest() throws Exception {
    McpClient owner = client("card-owner", "dev");
    McpClient stranger = client("card-stranger", "dev");
    McpClient otherEndpoint = client("card-other-endpoint", "other-dev");
    String handle = pending(owner, "UPDATE orders SET status = 'scoped-card' WHERE id = 813;");
    String absent = "ap_000000000000";
    EventBus events = core.bean(EventBus.class);
    long before = events.lastId();
    for (McpClient untrusted : List.of(stranger, otherEndpoint)) {
      for (String action : List.of("status", "open", "deny")) {
        JsonNode refused = interaction(untrusted, handle, action);
        JsonNode unknown = interaction(untrusted, absent, action);
        assertThat(refused.path("isError").asBoolean()).isTrue();
        assertThat(refused.at("/content/0/text").asString().replace(handle, "<handle>"))
            .isEqualTo(unknown.at("/content/0/text").asString().replace(absent, "<handle>"));
        assertThat(refused.path("structuredContent").isMissingNode()).isTrue();
      }
      assertThat(card(untrusted, handle).path("isError").asBoolean()).isTrue();
    }
    assertThat(eventsAfter(before)).noneMatch(e -> e.type().equals("ui.open"));
    assertThat(approvals.find(handle).orElseThrow().status()).isEqualTo("pending");
    assertNotExecuted(handle);
    approvals.decide(handle, ApprovalService.Decision.DENY, "app", null, null);
  }

  @Test
  void rawCallsCannotForgeApprovalOrReplaceTheReviewRoute() {
    McpClient agent = client("forged-card-actions", "dev");
    String handle = pending(agent, "UPDATE orders SET status = 'forged-card' WHERE id = 814;");
    List<ObjectNode> forged = List.of(
        args("handle", handle, "action", "approve"),
        args("handle", handle, "action", "open", "path", "/settings"),
        args("handle", handle, "action", "deny", "via", "app"),
        args("handle", handle, "action", "status", "digest", approvals.find(handle).orElseThrow().digest()));
    for (ObjectNode arguments : forged) {
      JsonNode result = agent.legacyCall("approval_card_action", arguments).result();
      assertThat(result.path("isError").asBoolean()).isTrue();
      assertThat(approvals.find(handle).orElseThrow().status()).isEqualTo("pending");
    }
    assertNotExecuted(handle);
    approvals.decide(handle, ApprovalService.Decision.DENY, "app", null, null);
  }

  @Test
  void openingFromTheCardOnlyEmitsTheFixedProtectedReviewRoute() throws Exception {
    McpClient agent = client("open-card", "dev");
    String handle = pending(agent, "UPDATE orders SET status = 'open-card' WHERE id = 815;");
    EventBus events = core.bean(EventBus.class);
    long before = events.lastId();
    JsonNode result = interaction(agent, handle, "open");
    assertThat(result.path("isError").asBoolean()).isFalse();
    assertThat(result.at("/structuredContent/status").asString()).isEqualTo("pending");
    List<EventBus.GatewayEvent> opened = eventsAfter(before).stream().filter(e -> e.type().equals("ui.open")).toList();
    assertThat(opened).hasSize(1);
    assertThat(opened.getFirst().data()).isEqualTo(args("path", "/approvals/" + handle));
    assertThat(approvals.find(handle).orElseThrow().status()).isEqualTo("pending");
    assertNotExecuted(handle);
    approvals.decide(handle, ApprovalService.Decision.DENY, "app", null, null);
  }

  @Test
  void denyIsIdempotentAuditedAsClientAndNeverRunsTheAction() {
    McpClient agent = client("deny-card", "dev");
    String handle = pending(agent, "UPDATE orders SET status = 'deny-card' WHERE id = 816;");
    for (int retry = 0; retry < 2; retry++) {
      JsonNode result = interaction(agent, handle, "deny");
      assertThat(result.path("isError").asBoolean()).isFalse();
      assertThat(result.at("/structuredContent/status").asString()).isEqualTo("rejected");
      assertThat(result.at("/structuredContent/canDeny").asBoolean()).isFalse();
    }
    assertThat(approvals.find(handle).orElseThrow().decidedVia()).isEqualTo("client");
    assertThat(core.jdbc().queryForList("select decision || ':' || decided_via from tool_call where approval_id = ? "
        + "and phase = 'resolved'", String.class, handle)).containsExactly("rejected:client");
    assertNotExecuted(handle);
  }

  @Test
  void expiredAndCompletedCardsCannotResurrectOrRepeatAnAction() {
    McpClient agent = client("terminal-cards", "dev");
    String expired = pending(agent, "UPDATE orders SET status = 'expired-card' WHERE id = 817;");
    core.jdbc().update("update approval set expires_at = ? where id = ?", System.currentTimeMillis() - 1_000,
        expired);
    approvals.expireDue();
    assertThat(card(agent, expired).at("/structuredContent/status").asString()).isEqualTo("expired");
    interaction(agent, expired, "deny");
    assertThat(approvals.find(expired).orElseThrow().status()).isEqualTo("expired");
    assertNotExecuted(expired);

    String completed = pending(agent, "UPDATE orders SET status = 'completed-card' WHERE id = 818;");
    approvals.decide(completed, ApprovalService.Decision.APPROVE, "app", null,
        approvals.find(completed).orElseThrow().digest());
    TestCore.await(() -> approvals.find(completed).orElseThrow().terminal(), "the approved card action");
    JsonNode data = card(agent, completed).path("structuredContent");
    assertThat(data.path("status").asString()).isEqualTo("completed");
    assertThat(data.path("canDeny").asBoolean()).isFalse();
    interaction(agent, completed, "deny");
    assertThat(approvals.find(completed).orElseThrow().status()).isEqualTo("completed");
    assertThat(FakeConnectors.EXECUTIONS.stream().filter(e -> completed.equals(e.approvalId())).count()).isEqualTo(1);
  }

  private static McpClient client(String label, String slug) {
    return new McpClient(port, slug, tokens.issue(slug, label, 7, null).token());
  }

  private static String pending(McpClient client, String sql) {
    McpClient.Answer answer = client.legacyCall("sql_execute", args("connection", "dev-db", "sql", sql));
    assertThat(answer.result().path("isError").asBoolean()).as(answer.body().toString()).isFalse();
    String handle = answer.result().at("/structuredContent/handle").asString();
    assertThat(handle).matches("ap_[a-z0-9]{12}");
    return handle;
  }

  private static JsonNode card(McpClient client, String handle) {
    return client.legacyCall("approval_card", args("handle", handle)).result();
  }

  private static JsonNode interaction(McpClient client, String handle, String action) {
    return client.legacyCall("approval_card_action", args("handle", handle, "action", action)).result();
  }

  private static void assertNotExecuted(String handle) {
    assertThat(FakeConnectors.EXECUTIONS).noneMatch(e -> handle.equals(e.approvalId()));
  }

  private static void initializeUi(McpClient client) {
    ObjectNode params = args("protocolVersion", "2025-11-25");
    params.set("capabilities", uiCapabilities());
    params.putObject("clientInfo").put("name", "card-test").put("version", "1.0");
    assertThat(client.legacy("initialize", params).status()).isEqualTo(200);
  }

  private static ObjectNode uiCapabilities() {
    ObjectNode capabilities = JsonNodeFactory.instance.objectNode();
    capabilities.putObject("extensions").putObject("io.modelcontextprotocol/ui").putArray("mimeTypes").add(MIME);
    return capabilities;
  }

  private static McpClient.Answer modernUi(McpClient client, String method, ObjectNode params) {
    return modernUi(client, method, params, false);
  }

  private static McpClient.Answer modernUi(McpClient client, String method, ObjectNode params,
      boolean elicitationUrl) {
    ObjectNode meta = params.putObject("_meta");
    meta.put("io.modelcontextprotocol/protocolVersion", McpClient.MODERN);
    ObjectNode capabilities = uiCapabilities();
    if (elicitationUrl) {
      capabilities.putObject("elicitation").putObject("url");
    }
    meta.set("io.modelcontextprotocol/clientCapabilities", capabilities);
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("MCP-Protocol-Version", McpClient.MODERN);
    headers.put("Mcp-Method", method);
    if (method.equals("resources/read")) {
      headers.put("Mcp-Name", params.path("uri").asString());
    } else if (method.equals("tools/call")) {
      headers.put("Mcp-Name", params.path("name").asString());
    }
    return client.post(client.request(method, params).toString(), headers);
  }

  private static JsonNode tool(JsonNode list, String name) {
    for (JsonNode tool : list.path("tools")) {
      if (name.equals(tool.path("name").asString())) {
        return tool;
      }
    }
    throw new AssertionError("Tool not listed: " + name);
  }

  private static List<String> names(JsonNode tools) {
    List<String> names = new ArrayList<>();
    tools.forEach(tool -> names.add(tool.path("name").asString()));
    return names;
  }

  private static List<EventBus.GatewayEvent> eventsAfter(long id) throws InterruptedException {
    List<EventBus.GatewayEvent> seen = new ArrayList<>();
    try (var subscription = core.bean(EventBus.class).subscribe(OptionalLong.of(id))) {
      for (var event = subscription.next(Duration.ofMillis(25)); event.isPresent(); event = subscription.next(
          Duration.ofMillis(25))) {
        seen.add(event.get());
      }
    }
    return seen;
  }
}
