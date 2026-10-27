package ai.msrv.gaterook.core.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The era table of report 01 §3.1, row by row, and the JSON-RPC shape checked before it. */
class EraResolverTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  // ── Modern ──────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void aVersionInMetaMakesTheRequestModern() {
    Envelope env = EraResolver.resolve(modern("tools/list", "{}"), headers("MCP-Protocol-Version", "2026-07-28",
        "Mcp-Method", "tools/list"));
    assertThat(env.era()).isEqualTo(Era.MODERN);
    assertThat(env.version()).isEqualTo("2026-07-28");
    assertThat(env.clientCapabilities().path("elicitation").path("url").isObject()).isTrue();
    assertThat(env.elicitationUrl()).isTrue();
    assertThat(env.clientInfo().path("name").asString()).isEqualTo("it");
    assertThat(env.notification()).isFalse();
  }

  @Test
  void headerNamesAreMatchedWithoutRegardToCase() {
    // The controller hands over a case-insensitive lookup (Servlet headers); the resolver asks in canonical case.
    Envelope env = EraResolver.resolve(modern("tools/list", "{}"), headers("mcp-protocol-version", "2026-07-28",
        "MCP-METHOD", "tools/list"));
    assertThat(env.era()).isEqualTo(Era.MODERN);
  }

  @Test
  void aModernVersionTheGatewayDoesNotSpeakIsRefused() {
    ObjectNode req = modern("tools/list", "{}");
    ((ObjectNode) req.at("/params/_meta")).put(EraResolver.META_VERSION, "2027-01-01");
    EraResolver.Rejected e = rejected(req, headers("MCP-Protocol-Version", "2027-01-01", "Mcp-Method", "tools/list"));
    assertThat(e.code()).isEqualTo(JsonRpc.UNSUPPORTED_VERSION);
    assertThat(e.data().path("supported").toString()).isEqualTo("[\"2026-07-28\",\"2025-11-25\"]");
    assertThat(e.data().path("requested").asString()).isEqualTo("2027-01-01");
    assertThat(e.id().asInt()).isEqualTo(1);
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(delimiter = '|', value = {
      "version header differs   | 2025-11-25 | tools/call | sql_query",
      "version header missing   |            | tools/call | sql_query",
      "method header differs    | 2026-07-28 | tools/list | sql_query",
      "method header missing    | 2026-07-28 |            | sql_query",
      "name header differs      | 2026-07-28 | tools/call | sql_execute",
      "name header missing      | 2026-07-28 | tools/call | ",
      "name header bad base64   | 2026-07-28 | tools/call | =?base64?***?=",
  })
  void headersMustRepeatTheBody(String row, String version, String method, String name) {
    ObjectNode req = modern("tools/call", "{\"name\":\"sql_query\",\"arguments\":{}}");
    Map<String, String> h = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    if (version != null) {
      h.put("MCP-Protocol-Version", version);
    }
    if (method != null) {
      h.put("Mcp-Method", method);
    }
    if (name != null) {
      h.put("Mcp-Name", name);
    }
    assertThat(rejected(req, h::get).code()).as(row).isEqualTo(JsonRpc.HEADER_MISMATCH);
  }

  @Test
  void theBase64SentinelOfMcpNameIsDecodedFirst() {
    String name = "запрос_данных";
    ObjectNode req = modern("tools/call", "{\"name\":\"" + name + "\",\"arguments\":{}}");
    String sentinel = "=?base64?" + Base64.getEncoder().encodeToString(name.getBytes(StandardCharsets.UTF_8)) + "?=";
    Envelope env = EraResolver.resolve(req, headers("MCP-Protocol-Version", "2026-07-28", "Mcp-Method", "tools/call",
        "Mcp-Name", sentinel));
    assertThat(env.params().path("name").asString()).isEqualTo(name);
    assertThat(EraResolver.decodeName("plain")).isEqualTo("plain");
    assertThat(EraResolver.decodeName(null)).isNull();
  }

  @Test
  void resourcesReadNamesItsUriAndPromptsGetItsName() {
    ObjectNode read = modern("resources/read", "{\"uri\":\"mcpgw://connections/dev-db/schema\"}");
    assertThat(EraResolver.resolve(read, headers("MCP-Protocol-Version", "2026-07-28", "Mcp-Method",
        "resources/read", "Mcp-Name", "mcpgw://connections/dev-db/schema")).method()).isEqualTo("resources/read");
    ObjectNode prompt = modern("prompts/get", "{\"name\":\"verify_deploy\"}");
    assertThat(rejected(prompt, headers("MCP-Protocol-Version", "2026-07-28", "Mcp-Method", "prompts/get"))
        .code()).isEqualTo(JsonRpc.HEADER_MISMATCH);
    // tools/list names nothing: no Mcp-Name needed.
    assertThat(EraResolver.resolve(modern("tools/list", "{}"), headers("MCP-Protocol-Version", "2026-07-28",
        "Mcp-Method", "tools/list")).era()).isEqualTo(Era.MODERN);
  }

  @Test
  void aModernRequestWithoutClientCapabilitiesIsRefused() {
    ObjectNode req = modern("tools/list", "{}");
    ((ObjectNode) req.at("/params/_meta")).remove(EraResolver.META_CAPABILITIES);
    EraResolver.Rejected e = rejected(req, headers("MCP-Protocol-Version", "2026-07-28", "Mcp-Method", "tools/list"));
    assertThat(e.code()).isEqualTo(JsonRpc.INVALID_PARAMS);
    assertThat(e.getMessage()).contains("clientCapabilities");
  }

  @Test
  void theModernHeaderWithoutTheMetaVersionIsAModernError() {
    EraResolver.Rejected e = rejected(request("tools/list", "{}"), headers("MCP-Protocol-Version", "2026-07-28"));
    assertThat(e.code()).isEqualTo(JsonRpc.INVALID_PARAMS);
    assertThat(e.getMessage()).isEqualTo("Missing _meta.io.modelcontextprotocol/protocolVersion");
  }

  // ── Legacy ──────────────────────────────────────────────────────────────────────────────────────────────────

  @ParameterizedTest(name = "initialize {0} → {1}")
  @CsvSource({"2025-11-25, 2025-11-25", "2025-06-18, 2025-06-18", "2025-03-26, 2025-03-26",
      "2024-11-05, 2025-11-25", "2026-07-28, 2025-11-25"})
  void initializeNegotiatesALegacyVersion(String asked, String answered) {
    ObjectNode req = request("initialize", "{\"protocolVersion\":\"" + asked + "\",\"capabilities\":{\"elicitation\""
        + ":{}},\"clientInfo\":{\"name\":\"codex-mcp-client\",\"version\":\"0.164.0\"}}");
    Envelope env = EraResolver.resolve(req, headers());
    assertThat(env.era()).isEqualTo(Era.LEGACY);
    assertThat(env.version()).isEqualTo(answered);
    assertThat(env.clientInfo().path("name").asString()).isEqualTo("codex-mcp-client");
    assertThat(env.clientCapabilities().has("elicitation")).isTrue();
    // URL elicitation is a modern-only path (MRTR).
    assertThat(env.elicitationUrl()).isFalse();
  }

  @ParameterizedTest(name = "initialize with the header {0}")
  @CsvSource({"2026-07-28", "2025-11-25", "2024-11-05"})
  void initializeIsLegacyWhateverHeaderItCarries(String header) {
    // The order of report 01 §3.1 matters: initialize is decided before the modern header is refused, so a client
    // that sends MCP-Protocol-Version: 2026-07-28 on it still negotiates instead of getting -32602.
    ObjectNode req = request("initialize", "{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{}}");
    Envelope env = EraResolver.resolve(req, headers("MCP-Protocol-Version", header));
    assertThat(env.era()).isEqualTo(Era.LEGACY);
    assertThat(env.version()).isEqualTo("2025-11-25");
  }

  @ParameterizedTest(name = "header {0} → {1}")
  @CsvSource(nullValues = "none", value = {"2025-11-25, 2025-11-25", "2025-06-18, 2025-06-18",
      "2025-03-26, 2025-03-26", "none, 2025-03-26"})
  void otherRequestsAreLegacyByTheirHeader(String header, String version) {
    Envelope env = EraResolver.resolve(request("tools/list", "{}"), header == null ? headers()
        : headers("MCP-Protocol-Version", header));
    assertThat(env.era()).isEqualTo(Era.LEGACY);
    assertThat(env.version()).isEqualTo(version);
    assertThat(env.clientInfo()).isNull();
  }

  @Test
  void anUnknownLegacyHeaderIsRefused() {
    assertThat(rejected(request("tools/list", "{}"), headers("MCP-Protocol-Version", "2024-11-05")).code())
        .isEqualTo(JsonRpc.INVALID_REQUEST);
  }

  @Test
  void aNotificationHasNoId() {
    ObjectNode n = json("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
    Envelope env = EraResolver.resolve(n, headers("MCP-Protocol-Version", "2025-11-25"));
    assertThat(env.notification()).isTrue();
  }

  @Test
  void theTraceparentOfMetaIsKeptAsSent() {
    ObjectNode req = request("tools/list", "{\"_meta\":{\"traceparent\":\"00-4f2a9c00000000000000000000000000-"
        + "00f067aa0ba902b7-01\"}}");
    assertThat(EraResolver.resolve(req, headers()).traceparent()).startsWith("00-4f2a9c");
  }

  // ── JSON-RPC shape ──────────────────────────────────────────────────────────────────────────────────────────

  @ParameterizedTest(name = "{0}")
  @CsvSource(delimiter = '|', value = {
      "no jsonrpc        | {\"id\":1,\"method\":\"ping\"}",
      "jsonrpc 1.0       | {\"jsonrpc\":\"1.0\",\"id\":1,\"method\":\"ping\"}",
      "a response        | {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}",
      "an error response | {\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":1,\"message\":\"x\"}}",
      "no method         | {\"jsonrpc\":\"2.0\",\"id\":1}",
      "empty method      | {\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"\"}",
      "object id         | {\"jsonrpc\":\"2.0\",\"id\":{},\"method\":\"ping\"}",
      "fractional id     | {\"jsonrpc\":\"2.0\",\"id\":1.5,\"method\":\"ping\"}",
      "array params      | {\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":[]}",
  })
  void malformedRequestsAreInvalidRequests(String row, String body) {
    assertThat(rejected(json(body), headers()).code()).as(row).isEqualTo(JsonRpc.INVALID_REQUEST);
  }

  @Test
  void aRejectionKeepsAUsableIdOnly() {
    assertThat(rejected(json("{\"jsonrpc\":\"2.0\",\"id\":\"a-1\"}"), headers()).id().asString()).isEqualTo("a-1");
    assertThat(rejected(json("{\"jsonrpc\":\"2.0\",\"id\":[1],\"method\":\"ping\"}"), headers()).id()).isNull();
  }

  @Test
  void everyRejectionIsHttp400() {
    for (int code : new int[] {JsonRpc.INVALID_REQUEST, JsonRpc.INVALID_PARAMS, JsonRpc.HEADER_MISMATCH,
        JsonRpc.UNSUPPORTED_VERSION}) {
      // What the controller answers for a resolver rejection does not depend on the era.
      assertThat(new EraResolver.Rejected(null, code, "x", null)).isInstanceOf(McpException.class);
    }
    assertThat(McpException.status(Era.MODERN, JsonRpc.METHOD_NOT_FOUND)).isEqualTo(404);
    assertThat(McpException.status(Era.LEGACY, JsonRpc.METHOD_NOT_FOUND)).isEqualTo(200);
    assertThat(McpException.status(Era.MODERN, JsonRpc.INVALID_PARAMS)).isEqualTo(400);
    assertThat(McpException.status(Era.LEGACY, JsonRpc.INVALID_PARAMS)).isEqualTo(200);
    assertThat(McpException.status(Era.MODERN, JsonRpc.INTERNAL_ERROR)).isEqualTo(200);
    assertThat(McpException.status(Era.LEGACY, JsonRpc.HEADER_MISMATCH)).isEqualTo(400);
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

  private static EraResolver.Rejected rejected(ObjectNode req, Function<String, String> h) {
    return (EraResolver.Rejected) org.assertj.core.api.Assertions.catchThrowable(() -> EraResolver.resolve(req, h));
  }

  private static ObjectNode modern(String method, String params) {
    ObjectNode req = request(method, params);
    ObjectNode meta = ((ObjectNode) req.get("params")).putObject("_meta");
    meta.put(EraResolver.META_VERSION, "2026-07-28");
    meta.putObject(EraResolver.META_CAPABILITIES).putObject("elicitation").putObject("url");
    meta.putObject(EraResolver.META_CLIENT).put("name", "it").put("version", "1");
    return req;
  }

  private static ObjectNode request(String method, String params) {
    return json("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":" + params + "}");
  }

  private static ObjectNode json(String s) {
    return (ObjectNode) JSON.readTree(s);
  }

  private static Function<String, String> headers(String... pairs) {
    Map<String, String> h = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    for (int i = 0; i < pairs.length; i += 2) {
      h.put(pairs[i], pairs[i + 1]);
    }
    return h::get;
  }

  @Test
  void rejectedIsThrownNotReturned() {
    assertThatThrownBy(() -> EraResolver.resolve(json("{}"), headers())).isInstanceOf(EraResolver.Rejected.class);
  }
}
