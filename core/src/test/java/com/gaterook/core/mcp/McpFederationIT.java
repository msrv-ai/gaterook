package com.gaterook.core.mcp;

import static com.gaterook.core.mcp.McpClient.args;
import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.ToolDefinition.Mode;
import com.gaterook.core.endpoints.EndpointConnections;
import com.gaterook.core.endpoints.EndpointService;
import com.gaterook.core.endpoints.EndpointTools;
import com.gaterook.core.endpoints.TokenService;
import com.gaterook.core.platform.LocalListener;
import com.gaterook.testfixtures.FakeConnectors;
import com.gaterook.testfixtures.TestCore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * What external MCP servers share besides tools, through a real core (ARCHITECTURE.md §5.4): resources, templates,
 * prompts and completions of a sharing server under its connection's namespace, masked and audited like reads; the
 * apps of its tools for clients that render them, and app-only tools only for them; images, blobs and links of
 * results as blocks, withheld when their bytes carry a secret.
 */
class McpFederationIT {

  static final String CONNECTION = "notes-mcp";
  static final String PASSWORD = "pw-notes-mcp-0123";
  static final String WELCOME = "mcpgw://connections/notes-mcp/mcp/notes://welcome";
  static final String APP = "ui://gaterook/connections/notes-mcp/chart.html";

  static TestCore core;
  static McpClient dev;

  @BeforeAll
  static void start() {
    core = TestCore.start("federation");
    core.connection(CONNECTION, "mcpfake", Env.DEV, "share.internal");
    EndpointService endpoints = core.bean(EndpointService.class);
    endpoints.create("dev", null, EndpointService.StartFrom.EMPTY, null);
    core.bean(EndpointConnections.class).add("dev", CONNECTION, null);
    EndpointTools tools = core.bean(EndpointTools.class);
    for (String[] t : List.of(new String[] {"gh_chart", "APPROVAL"}, new String[] {"gh_widget", "APPROVAL"},
        new String[] {"gh_picture", "BYPASS"})) {
      var pair = endpoints.snapshot("dev").pairs().stream().filter(p -> p.tool().name().equals(t[0])).findFirst()
          .orElseThrow();
      tools.setMode("dev", pair.toolKey(), CONNECTION, Mode.valueOf(t[1]), null, null, pair.rowVersion(), "Test");
    }
    dev = new McpClient(core.bean(LocalListener.class).port(), "dev",
        core.bean(TokenService.class).issue("dev", "federation", 7, null).token());
  }

  @AfterAll
  static void stop() {
    if (core != null) {
      core.close();
    }
  }

  /** A 2026-07-28 request of a client that renders MCP Apps. */
  private static McpClient.Answer withApps(String method, ObjectNode params) {
    ObjectNode meta = params.putObject("_meta");
    meta.put("io.modelcontextprotocol/protocolVersion", "2026-07-28");
    meta.putObject("io.modelcontextprotocol/clientCapabilities").putObject("extensions")
        .putObject("io.modelcontextprotocol/ui").putArray("mimeTypes").add("text/html;profile=mcp-app");
    meta.putObject("io.modelcontextprotocol/clientInfo").put("name", "app-host").put("version", "1.0");
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("MCP-Protocol-Version", "2026-07-28");
    headers.put("Mcp-Method", method);
    if (method.equals("resources/read")) {
      headers.put("Mcp-Name", params.path("uri").asString());
    } else if (method.equals("tools/call")) {
      headers.put("Mcp-Name", params.path("name").asString());
    }
    return dev.post(dev.request(method, params).toString(), headers);
  }

  private static List<String> strings(JsonNode array, String field) {
    List<String> out = new ArrayList<>();
    array.forEach(n -> out.add(n.path(field).asString()));
    return out;
  }

  @Test
  void sharedResourcesAreListedUnderTheirConnectionAndReadMaskedAndAudited() {
    JsonNode list = dev.legacy("resources/list", null).result();
    assertThat(strings(list.path("resources"), "uri")).contains(WELCOME,
        "mcpgw://connections/notes-mcp/mcp/bin://key").noneMatch(u -> u.contains("someone-else"));
    JsonNode templates = dev.legacy("resources/templates/list", null).result();
    assertThat(strings(templates.path("resourceTemplates"), "uriTemplate"))
        .contains("mcpgw://connections/notes-mcp/mcp/notes://{name}");

    JsonNode read = dev.legacy("resources/read", JsonNodeFactory.instance.objectNode().put("uri", WELCOME)).result();
    JsonNode content = read.path("contents").get(0);
    assertThat(content.path("uri").asString()).isEqualTo(WELCOME);
    assertThat(content.path("text").asString()).startsWith("Hello, the password is ").doesNotContain(PASSWORD)
        .contains("‹masked›");
    assertThat(core.jdbc().queryForObject("select count(*) from tool_call where tool_name = 'resources/read'"
        + " and connection_name = ?", Integer.class, CONNECTION)).isPositive();

    JsonNode key = dev.legacy("resources/read", JsonNodeFactory.instance.objectNode()
        .put("uri", "mcpgw://connections/notes-mcp/mcp/bin://key")).result();
    assertThat(key.path("contents").get(0).has("blob")).isFalse();
    assertThat(key.path("contents").get(0).path("text").asString()).contains("withheld by GateRook");

    JsonNode fromTemplate = dev.legacy("resources/read", JsonNodeFactory.instance.objectNode()
        .put("uri", "mcpgw://connections/notes-mcp/mcp/notes://todo")).result();
    assertThat(fromTemplate.path("contents").get(0).path("text").asString()).isEqualTo("Note todo");

    McpClient.Answer foreign = dev.legacy("resources/read", JsonNodeFactory.instance.objectNode()
        .put("uri", "mcpgw://connections/someone-else/mcp/x"));
    assertThat(foreign.error().path("code").asInt()).isEqualTo(-32002);
  }

  @Test
  void sharedPromptsJoinTheGatewaysOwnAndComplete() {
    JsonNode list = dev.legacy("prompts/list", null).result();
    List<String> names = strings(list.path("prompts"), "name");
    assertThat(names).contains("investigate_incident", "verify_deploy", "gh_summarize");
    assertThat(names.stream().filter("investigate_incident"::equals)).hasSize(1);

    ObjectNode get = JsonNodeFactory.instance.objectNode().put("name", "gh_summarize");
    get.putObject("arguments").put("note", "welcome");
    JsonNode prompt = dev.legacy("prompts/get", get).result();
    assertThat(prompt.path("description").asString()).isEqualTo("Summary of welcome");
    assertThat(prompt.path("messages").get(0).path("content").path("text").asString()).doesNotContain(PASSWORD);
    assertThat(prompt.path("messages").get(1).path("role").asString()).isEqualTo("assistant");
    assertThat(prompt.path("messages").get(1).path("content").path("data").asString())
        .isEqualTo(FakeConnectors.PNG);

    ObjectNode complete = JsonNodeFactory.instance.objectNode();
    complete.putObject("ref").put("type", "ref/prompt").put("name", "gh_summarize");
    complete.putObject("argument").put("name", "note").put("value", "we");
    JsonNode values = dev.legacy("completion/complete", complete).result().path("completion");
    assertThat(values.path("values").toString()).isEqualTo("[\"welcome\",\"weekly\"]");
    assertThat(values.path("total").asInt()).isEqualTo(2);

    ObjectNode own = JsonNodeFactory.instance.objectNode();
    own.putObject("ref").put("type", "ref/prompt").put("name", "investigate_incident");
    own.putObject("argument").put("name", "service").put("value", "or");
    assertThat(dev.legacy("completion/complete", own).result().path("completion").path("values").isEmpty())
        .isTrue();

    ObjectNode modern = JsonNodeFactory.instance.objectNode();
    modern.putObject("ref").put("type", "ref/resource").put("uri",
        "mcpgw://connections/notes-mcp/mcp/notes://{name}");
    modern.putObject("argument").put("name", "name").put("value", "w");
    McpClient.Answer answer = dev.modern("completion/complete", modern, false);
    SpecSchemas.assertValid("2026-07-28", "CompleteResultResponse", answer.body());
  }

  @Test
  void appsReachOnlyClientsThatRenderThem() {
    JsonNode plain = dev.legacy("tools/list", null).result();
    assertThat(strings(plain.path("tools"), "name")).contains("gh_chart").doesNotContain("gh_widget");
    JsonNode chart = find(plain.path("tools"), "gh_chart");
    assertThat(chart.path("_meta").path("ui").isMissingNode()).isTrue();
    assertThat(strings(dev.legacy("resources/list", null).result().path("resources"), "uri")).doesNotContain(APP);

    JsonNode tools = withApps("tools/list", JsonNodeFactory.instance.objectNode()).result();
    assertThat(find(tools.path("tools"), "gh_chart").path("_meta").path("ui").path("resourceUri").asString())
        .isEqualTo(APP);
    assertThat(find(tools.path("tools"), "gh_widget").path("_meta").path("ui").path("visibility").toString())
        .isEqualTo("[\"app\"]");
    // The apps of the server may call its tools as it declares them; the gateway's own tools are the model's.
    assertThat(find(tools.path("tools"), "gh_search").path("_meta").path("ui").has("visibility")).isFalse();
    assertThat(find(tools.path("tools"), "approval_status").path("_meta").path("ui").path("visibility").toString())
        .isEqualTo("[\"model\"]");
    JsonNode resources = withApps("resources/list", JsonNodeFactory.instance.objectNode()).result();
    assertThat(strings(resources.path("resources"), "uri").stream().filter(APP::equals)).hasSize(1);

    McpClient.Answer html = withApps("resources/read", JsonNodeFactory.instance.objectNode().put("uri", APP));
    SpecSchemas.assertValid("2026-07-28", "ReadResourceResultResponse", html.body());
    JsonNode content = html.result().path("contents").get(0);
    assertThat(content.path("mimeType").asString()).isEqualTo("text/html;profile=mcp-app");
    // An app is code: no entropy masking that would break it, but the call's secrets are masked.
    assertThat(content.path("text").asString()).contains("a8f3c2d1e9b74f6a0c5d2e8b1a7f4c3d9e6b0a2f5c8d1e4b7a0c3f6d9e2b5a8")
        .doesNotContain(PASSWORD);
    assertThat(content.path("_meta").path("ui").path("csp").path("connectDomains").get(0).asString())
        .isEqualTo("https://charts.example");
  }

  @Test
  void imagesAndLinksOfAResultAreBlocksAndOneWithASecretIsWithheld() {
    JsonNode r = dev.legacyCall("gh_picture", args("connection", CONNECTION, "query", "x")).result();

    JsonNode content = r.path("content");
    assertThat(content.get(0).path("type").asString()).isEqualTo("image");
    assertThat(content.get(0).path("data").asString()).isEqualTo(FakeConnectors.PNG);
    assertThat(content.get(1).path("type").asString()).isEqualTo("text");
    assertThat(content.get(1).path("text").asString()).contains("withheld by GateRook");
    assertThat(content.get(2).path("type").asString()).isEqualTo("resource_link");
    assertThat(content.get(2).path("uri").asString()).isEqualTo(WELCOME);
    assertThat(r.path("_meta").path("ai.msrv.gaterook/masked").path("fields").asInt()).isPositive();

    McpClient.Answer modern = withApps("tools/call", JsonNodeFactory.instance.objectNode().put("name", "gh_picture")
        .set("arguments", args("connection", CONNECTION, "query", "x")));
    SpecSchemas.assertValid("2026-07-28", "CallToolResultResponse", modern.body());
  }

  private static JsonNode find(JsonNode tools, String name) {
    for (JsonNode t : tools) {
      if (t.path("name").asString().equals(name)) {
        return t;
      }
    }
    throw new AssertionError("No tool " + name);
  }
}
