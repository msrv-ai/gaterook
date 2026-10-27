package ai.msrv.gaterook.plugin.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.ApiVersion;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.EndpointView;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Every GPP/1 message of report 16 §4.4 against its golden file in {@code src/test/resources/gpp/}: the JSON-RPC
 * envelope as it travels, with the params or result the codec writes. A change of the wire shows up here as a diff
 * of a reviewed file; each message also reads back into the same values. Regenerate after a deliberate change with
 * {@code -Dgpp.golden.update=true} and review the diff.
 */
class GoldenMessagesTest {

  static final ConnectionConfig JSM = new ConnectionConfig("c7b1", "acme-jsm", "jira-service-desk", Env.DEV, 4,
      json("""
          {"baseUrl":"https://acme.atlassian.net","deployment":"cloud","email":"svc-gateway@acme.com",
           "serviceDeskIds":[],"timeoutMs":10000}"""), Set.of("/apiToken"), null);

  static final Limits DEV = new Limits(1000, 1000, 524_288, 1_048_576, 262_144, 65_536, 8192, Duration.ofSeconds(5),
      Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofHours(168));

  static final ToolDefinition SLA = ToolDefinition.builder("jira_request_sla").title("Request SLA")
      .description("Time to first response and resolution of one request.")
      .inputSchema(json("""
          {"type":"object","additionalProperties":false,"required":["issueKey"],"properties":{
           "issueKey":{"type":"string","pattern":"^[A-Z][A-Z0-9_]{0,9}-[0-9]{1,9}$"}}}"""))
      .outputSchema(json("""
          {"type":"object","required":["issueKey","slas"],"properties":{"issueKey":{"type":"string"},
           "slas":{"type":"array"},"truncated":{"type":"boolean"}}}"""))
      .annotations(new ToolDefinition.Annotations(true, false, true, false)).nature(ToolDefinition.Nature.READ)
      .defaultMode(ToolDefinition.Mode.APPROVAL, ToolDefinition.Mode.APPROVAL).build();

  static final ToolCall SLA_CALL = new ToolCall("tc_01JBA7Q2", "jira_request_sla", "acme-jsm",
      (ObjectNode) json("{\"issueKey\":\"SD-123\"}"), null);

  static final ToolCall COMMENT_CALL = new ToolCall("tc_01JBA7R5", "jira_add_comment", "acme-jsm",
      (ObjectNode) json("{\"issueKey\":\"SD-123\",\"body\":\"Restarted the payment worker\",\"public\":false}"), null);

  static final Messages.EgressGrant EGRESS = new Messages.EgressGrant(List.of("acme.atlassian.net:443"), List.of(),
      List.of(7465, 7466));

  static JsonNode json(String text) {
    return GppJson.MAPPER.readTree(text);
  }

  static ObjectNode request(Object id, String method, JsonNode params) {
    ObjectNode o = GppJson.object();
    o.put("jsonrpc", "2.0");
    putId(o, id);
    o.put("method", method);
    if (params != null) {
      o.set("params", params);
    }
    return o;
  }

  static ObjectNode notification(String method, JsonNode params) {
    ObjectNode o = GppJson.object();
    o.put("jsonrpc", "2.0");
    o.put("method", method);
    o.set("params", params);
    return o;
  }

  static ObjectNode result(Object id, JsonNode result) {
    ObjectNode o = GppJson.object();
    o.put("jsonrpc", "2.0");
    putId(o, id);
    o.set("result", result);
    return o;
  }

  static ObjectNode error(Object id, int code, String message, JsonNode data) {
    ObjectNode o = GppJson.object();
    o.put("jsonrpc", "2.0");
    putId(o, id);
    ObjectNode e = o.putObject("error");
    e.put("code", code);
    e.put("message", message);
    if (data != null) {
      e.set("data", data);
    }
    return o;
  }

  private static void putId(ObjectNode o, Object id) {
    if (id instanceof Long l) {
      o.put("id", l);
    } else if (id instanceof Integer i) {
      o.put("id", (long) i);
    } else {
      o.put("id", String.valueOf(id));
    }
  }

  /** Compares with the golden file (or writes it when asked to). */
  static void golden(String name, JsonNode actual) throws IOException {
    Path file = Path.of("src/test/resources/gpp", name + ".json");
    String pretty = GppJson.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(actual) + "\n";
    if (Boolean.getBoolean("gpp.golden.update") || !Files.exists(file)) {
      Files.createDirectories(file.getParent());
      Files.writeString(file, pretty);
    }
    try (InputStream in = GoldenMessagesTest.class.getResourceAsStream("/gpp/" + name + ".json")) {
      JsonNode expected = in == null ? GppJson.MAPPER.readTree(Files.readString(file)) : GppJson.MAPPER.readTree(in);
      // As canonical text: a tree built in code holds longs where the parsed file holds ints.
      assertThat(Canonical.text(actual)).as(name).isEqualTo(Canonical.text(expected));
    }
  }

  // ── host/* and plugin/* ─────────────────────────────────────────────────────────────────────────────────────

  @Test
  void hello() throws IOException {
    Messages.Hello hello = new Messages.Hello(1, "0.4.0", ApiVersion.CURRENT, true, Gpp.MAX_FRAME_BYTES, 8, "info");
    golden("host-hello.request", request(1L, Gpp.HOST_HELLO, hello.write()));
    assertThat(Messages.Hello.read(hello.write())).isEqualTo(hello);
    Messages.HelloResult r = new Messages.HelloResult(1, "0.4.0", ApiVersion.CURRENT, 21691, "25.0.5+8-LTS", true,
        List.of("cancel", "secrets.callback"), 31);
    golden("host-hello.result", result(1L, r.write()));
    assertThat(Messages.HelloResult.read(r.write())).isEqualTo(r);
  }

  @Test
  void load() throws IOException {
    Messages.Load load = new Messages.Load("jira-service-desk@1.3.0", 1,
        "/home/ann/.gaterook/plugins/jira-service-desk/1.3.0/plugin.jar",
        "c4e19a07b3d25f8e61a0c9d7e42b58f13a6d09e7c25b81f4d3a96e0b7c18f52d",
        "/home/ann/.gaterook/plugin-data/jira-service-desk", new Messages.Sandbox(true,
            List.of("/home/ann/.gaterook/plugins/jira-service-desk/1.3.0"),
            List.of("/home/ann/.gaterook/plugin-data/jira-service-desk")));
    golden("plugin-load.request", request(2L, Gpp.PLUGIN_LOAD, load.write()));
    assertThat(Messages.Load.read(load.write())).isEqualTo(load);
    Messages.Loaded loaded = new Messages.Loaded("jira-service-desk@1.3.0", "jira-service-desk", ApiVersion.CURRENT,
        47, List.of(SLA), "landlock");
    golden("plugin-load.result", result(2L, loaded.write()));
    Messages.Loaded back = Messages.Loaded.read(loaded.write());
    assertThat(back.tools()).hasSize(1);
    assertThat(WireCodec.definitionHash(back.tools().getFirst())).isEqualTo(WireCodec.definitionHash(SLA));
    ObjectNode failed = GppJson.object();
    failed.put("phase", "verify");
    golden("plugin-load.failed", error(2L, Gpp.PLUGIN_LOAD_FAILED, "Plugin file changed on disk", failed));
    golden("host-ping.request", request(3L, Gpp.HOST_PING, null));
    golden("host-shutdown.request", request(4L, Gpp.HOST_SHUTDOWN, null));
    golden("connection-invalidate.notification", notification(Gpp.CONNECTION_INVALIDATE,
        Messages.invalidate("c7b1")));
  }

  // ── connector/* ─────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void execute() throws IOException {
    Messages.Execute e = new Messages.Execute("jira-service-desk@1.3.0", "ct_9Qm2xLkP0aZs4vRt7yUb1w", SLA_CALL, JSM,
        10_000, false, null, null, null, DEV, "4f2a9c71e8d04b5a9c3e2b1f0a6d8e81",
        "00-4f2a9c71e8d04b5a9c3e2b1f0a6d8e81-7a1c2e9b4d3f6a10-01", EGRESS);
    golden("connector-execute.request", request(41L, Gpp.CONNECTOR_EXECUTE, e.write()));
    Messages.Execute back = Messages.Execute.read(e.write());
    assertThat(back.call().arguments()).isEqualTo(SLA_CALL.arguments());
    assertThat(back.connection().config()).isEqualTo(JSM.config());
    assertThat(back.connection().secretFields()).isEqualTo(JSM.secretFields());
    assertThat(back.limits()).isEqualTo(DEV);
    assertThat(back.egress()).isEqualTo(EGRESS);

    ToolResult r = ToolResult.json(json("""
        {"issueKey":"SD-123","slas":[{"name":"Time to first response","state":"ongoing","breached":false,
         "goalMillis":14400000,"elapsedMillis":5400000,"remainingMillis":9000000,
         "breachTime":"2026-10-22T18:30:00+02:00"}],"truncated":false}"""),
        "SD-123 · Time to first response: ongoing, 2h 30m left (goal 4h)");
    ObjectNode out = WireCodec.result(r);
    out.putObject("stats").put("durationMs", 214);
    golden("connector-execute.result", result(41L, out));
    ToolResult again = WireCodec.result(out);
    assertThat(again.content()).isEqualTo(r.content());
    assertThat(again.structuredContent()).isEqualTo(r.structuredContent());

    ToolResult table = ToolResult.table(new ToolResult.Table(List.of(new ToolResult.Column("key", "string", null, null,
        null), new ToolResult.Column("summary", "string", null, null, null)), (tools.jackson.databind.node.ArrayNode)
        json("[[\"SD-123\",\"Payments are slow\"]]"), "start=50"), null).truncated("row_limit");
    golden("connector-execute.table-result", result(42L, WireCodec.result(table)));
    assertThat(WireCodec.result(WireCodec.result(table))).isEqualTo(table);

    Messages.Execute approved = new Messages.Execute("jira-service-desk@1.3.0", "ct_Zx81bR4wQ0mN2pL7sK3tAa",
        COMMENT_CALL, JSM, 9_400, true, "ap_3kq7m2x9vt4p", null, json("""
            {"tool":"jira_add_comment","arguments":{"issueKey":"SD-123","body":"Restarted the payment worker",
             "public":false},"plugin":"jira-service-desk","version":"1.3.0"}"""), DEV, null, null, EGRESS);
    golden("connector-execute.approved-request", request(58L, Gpp.CONNECTOR_EXECUTE, approved.write()));
    assertThat(Messages.Execute.read(approved.write()).approvedPayload()).isEqualTo(approved.approvedPayload());

    golden("cancel.notification", notification(Gpp.CANCEL, Messages.cancel(41, "deadline")));
    ObjectNode after = GppJson.object();
    after.put("afterMs", 10_003);
    golden("connector-execute.cancelled", error(41L, Gpp.CANCELLED, "Cancelled", after));
    ObjectNode kind = GppJson.object();
    kind.put("kind", "RESOURCE_ERROR");
    golden("connector-execute.connector-error", error(43L, Gpp.CONNECTOR_ERROR,
        "SD-999 not found or not visible to this account", kind));
  }

  @Test
  void describeAndClassify() throws IOException {
    Messages.Describe d = new Messages.Describe("jira-service-desk@1.3.0", COMMENT_CALL, JSM);
    golden("connector-describeAction.request", request(57L, Gpp.CONNECTOR_DESCRIBE_ACTION, d.write()));
    assertThat(Messages.Describe.read(d.write()).call().arguments()).isEqualTo(COMMENT_CALL.arguments());
    ActionPreview a = ActionPreview.of(ActionPreview.Kind.HTTP, "http",
        "POST https://acme.atlassian.net/rest/servicedeskapi/request/SD-123/comment\n"
            + "{\"body\":\"Restarted the payment worker\",\"public\":false}",
        "acme-jsm · acme.atlassian.net", "Internal comment on SD-123")
        .withFields(List.of(new ActionPreview.Field("Request", "SD-123")));
    golden("connector-describeAction.result", result(57L, WireCodec.action(a)));
    assertThat(WireCodec.action(WireCodec.action(a))).isEqualTo(a);

    PolicyView policy = new PolicyView(Env.DEV, "dev.plugin", json("{}"), DEV);
    Messages.Classify c = new Messages.Classify("jira-service-desk@1.3.0", COMMENT_CALL, JSM, policy);
    golden("connector-classify.request", request(60L, Gpp.CONNECTOR_CLASSIFY, c.write()));
    assertThat(Messages.Classify.read(c.write()).policy()).isEqualTo(policy);
    Classification mutation = Classification.mutation("Comments on SD-123");
    golden("connector-classify.result", result(60L, WireCodec.classification(mutation)));
    assertThat(WireCodec.classification(WireCodec.classification(mutation))).isEqualTo(mutation);
  }

  @Test
  void testConnection() throws IOException {
    Messages.Test t = new Messages.Test("jira-service-desk@1.3.0", "tt_01JBA9", "ct_Zx81bR4wQ0mN2pL7sK3tAb", JSM,
        10_000, DEV, EGRESS);
    golden("connector-test.request", request(63L, Gpp.CONNECTOR_TEST, t.write()));
    assertThat(Messages.Test.read(t.write())).isEqualTo(t);
    TestResult r = TestResult.success(182, "5.25.0", "svc-gateway@acme.com", new TestResult.Permissions(
        TestResult.Level.WRITE, "Browse projects", "Add comments", List.of(), null))
        .withHint(new TestResult.Hint(TestResult.Severity.WARNING, "can-write", null,
            "This account can comment on requests. Use a read-only account for prod."))
        .withChecks(List.of(new TestResult.Check("servicedeskapi/info", true, 40, "5.25.0")));
    golden("connector-test.result", result(63L, WireCodec.test(r)));
    assertThat(WireCodec.test(WireCodec.test(r))).isEqualTo(r);
  }

  @Test
  void tools() throws IOException {
    EndpointView view = new EndpointView("dev", Env.DEV, List.of(JSM), Map.of("c7b1", DEV), List.of());
    Messages.Tools t = new Messages.Tools("jira-service-desk@1.3.0", "ct_Ab12", view);
    golden("connector-tools.request", request(70L, Gpp.CONNECTOR_TOOLS, t.write()));
    assertThat(Messages.Tools.read(t.write()).endpoint().limitsByConnection()).isEqualTo(view.limitsByConnection());
    ObjectNode tools = GppJson.object();
    tools.set("tools", WireCodec.tools(List.of(SLA)));
    golden("connector-tools.result", result(70L, tools));
    golden("connector-toolsChanged.notification", notification(Gpp.TOOLS_CHANGED, GppJson.object()));
  }

  // ── host → core ─────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void secrets() throws IOException {
    golden("secrets-get.request", request("h17", Gpp.SECRETS_GET, Messages.secretField("ct_9Qm2xLkP0aZs4vRt7yUb1w",
        "/apiToken")));
    golden("secrets-get.result", result("h17", raw(SecretWire.text("ATATT3xFfGF0-test-only".toCharArray(), "v3",
        null))));
    golden("secrets-get.binary-result", result("h19", raw(SecretWire.binary(new byte[] {1, 2, 3, (byte) 0xff}, "v1",
        Instant.parse("2026-10-22T15:00:00Z")))));
    golden("secrets-login.result", result("h20", raw(SecretWire.login("gw_reader",
        "pw-test-only".toCharArray(), "lease 8f2c1a9e", Instant.parse("2026-10-22T15:00:00Z")))));
    ObjectNode meta = GppJson.object();
    meta.put("source", "local");
    meta.put("version", "v3");
    meta.put("set", true);
    golden("secrets-meta.result", result("h21", meta));
    golden("secrets-get.denied", error("h18", Gpp.SECRET_DENIED, "Secret access outside a call", null));
    ObjectNode ssh = Messages.secretField("ct_Ab12", "/credentials/vault");
    ssh.put("publicKey", "MCowBQYDK2VwAyEA");
    ssh.put("algorithm", "Ed25519");
    ssh.put("principal", "deploy");
    ssh.put("ttlSeconds", 300);
    ssh.putObject("extensions");
    golden("secrets-sshCertificate.request", request("h22", Gpp.SECRETS_SSH_CERTIFICATE, ssh));
    golden("taint-add.notification", notification(Gpp.TAINT_ADD, Messages.taint("ct_9Qm2xLkP0aZs4vRt7yUb1w",
        "eyJhbGciOiJSUzI1NiJ9.derived-token")));
    golden("log.notification", notification(Gpp.LOG, Messages.log("debug", "io.acme.jsm.Http", "tc_01JBA7Q2",
        "GET /rest/servicedeskapi/request/SD-123/sla 200 212 ms")));
    ObjectNode seal = GppJson.object();
    seal.put("callToken", "ct_Ab12");
    seal.put("purpose", "jira.search");
    seal.set("payload", json("{\"start\":50}"));
    seal.put("ttlSeconds", 86_400);
    golden("handles-seal.request", request("h23", Gpp.HANDLES_SEAL, seal));
  }

  private static JsonNode raw(JsonRpcPeer.RawResult r) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (JsonGenerator g = GppJson.MAPPER.createGenerator(out)) {
      r.write(g);
    } finally {
      r.dispose();
    }
    return GppJson.MAPPER.readTree(out.toString(StandardCharsets.UTF_8));
  }
}
