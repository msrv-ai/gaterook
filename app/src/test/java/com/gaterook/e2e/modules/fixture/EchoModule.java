package com.gaterook.e2e.modules.fixture;

import com.gaterook.connector.api.ActionPreview;
import com.gaterook.connector.api.Classification;
import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.Connector;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.ConnectorManifest;
import com.gaterook.connector.api.ConnectorModule;
import com.gaterook.connector.api.ExecContext;
import com.gaterook.connector.api.ModuleContext;
import com.gaterook.connector.api.PolicyView;
import com.gaterook.connector.api.PreparedAction;
import com.gaterook.connector.api.SecretResolver;
import com.gaterook.connector.api.TestResult;
import com.gaterook.connector.api.ToolCall;
import com.gaterook.connector.api.ToolDefinition;
import com.gaterook.connector.api.ToolResult;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * A connector module for the end-to-end tests, written as a compiled-in connector is and using nothing but the
 * connector API: two connector types in one package. {@code echo-db} reads its connection's secret, classifies per
 * call, describes its exact action and prepares it with state; {@code echo-log} is the plainest type there is. The
 * test packs these classes with the manifest {@code ModuleManifests} writes from them.
 */
public final class EchoModule implements ConnectorModule {

  /** The module's descriptor, as a {@code module.json} holds it. */
  public static final String DESCRIPTOR = """
      {"id":"echo","version":"1.0.0","name":"Echo","provider":"GateRook","license":"Apache-2.0",
       "description":"A connector module for tests.",
       "moduleClass":"com.gaterook.e2e.modules.fixture.EchoModule",
       "requires":{"gateway":">=0.1.0 & <99.0.0","connectorApi":">=1.2.0 & <2.0.0"},
       "access":{"hosts":[],"commands":[],"files":[]},
       "resources":{"maxHeapMb":96,"maxMetaspaceMb":64,"maxInFlight":4}}""";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Override
  public List<Connector> connectors(ModuleContext context) {
    return List.of(new EchoDb(), new EchoLog());
  }

  static JsonNode json(String text) {
    return JSON.readTree(text);
  }

  /** The build of the package this code was loaded from: the test puts it beside the classes. */
  static String build() {
    try (java.io.InputStream in = EchoModule.class.getResourceAsStream("/META-INF/echo-build.txt")) {
      return in == null ? "unpackaged" : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
    } catch (java.io.IOException e) {
      return "unreadable";
    }
  }

  private static JsonNode input(String extra) {
    return json("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"connection\"],\"properties\":{"
        + "\"connection\":{\"type\":\"string\",\"description\":\"The connection to run on\"}" + extra + "}}");
  }

  /** A type with a read, a change that is prepared and approved, and a secret. */
  static final class EchoDb implements Connector {

    private final ConnectorManifest manifest = ConnectorManifest.builder("echo-db", "1.0.0",
        ConnectorManifest.Kind.BUILT_IN).name("Echo DB").icon("database").group("Databases")
        .description("Answers what it is asked.")
        .configSchema(json("""
            {"type":"object","required":["host"],"properties":{"host":{"type":"string","title":"Host"},
             "password":{"type":"string","title":"Password","writeOnly":true}}}"""))
        .tools(List.of(
            ToolDefinition.builder("echo_read").title("Read").description("Echoes a text.")
                .inputSchema(input(",\"text\":{\"type\":\"string\"}")).build(),
            ToolDefinition.builder("echo_write").title("Write").description("Writes a text.")
                .inputSchema(input(",\"text\":{\"type\":\"string\"}")).nature(ToolDefinition.Nature.MUTATION)
                .annotations(ToolDefinition.Annotations.write(false))
                .defaultMode(ToolDefinition.Mode.APPROVAL, ToolDefinition.Mode.APPROVAL).build()))
        .build();

    @Override
    public ConnectorManifest manifest() {
      return manifest;
    }

    @Override
    public Classification classify(ToolCall call, ConnectionConfig cfg, PolicyView policy) {
      if ("echo_read".equals(call.tool())) {
        String text = call.string("text");
        return text != null && text.startsWith("forbidden") ? Classification.deny(policy.rule("forbidden-text"),
            "This text isn't echoed on " + cfg.name() + ".", null) : Classification.read();
      }
      return Classification.mutation("Writes to " + cfg.name() + ".");
    }

    @Override
    public ActionPreview describeAction(ToolCall call, ConnectionConfig cfg) {
      return ActionPreview.of(ActionPreview.Kind.TEXT, "text", "WRITE '" + call.string("text") + "' TO "
          + cfg.string("/host"), cfg.name() + " · " + cfg.string("/host"), "Writes one text");
    }

    @Override
    public PreparedAction prepare(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
      ObjectNode state = JsonNodeFactory.instance.objectNode().put("preparedFor", call.string("text"));
      return PreparedAction.approval(describeAction(call, cfg).withImpact(List.of(
          new ActionPreview.Field("Texts", "1"))), state);
    }

    @Override
    public TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
      return TestResult.success(1, "echo 1.0", "tester", null);
    }

    @Override
    public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
        throws ConnectorException {
      ObjectNode out = JsonNodeFactory.instance.objectNode();
      out.put("echo", call.string("text"));
      out.put("host", cfg.string("/host"));
      if ("echo_write".equals(call.tool())) {
        if (!ctx.approved()) {
          throw ConnectorException.denied("echo_write runs only after a person approved it.");
        }
        out.put("written", true);
        ctx.preparedState().ifPresent(state -> out.set("preparedState", state));
        return ToolResult.json(out, "Wrote " + call.string("text"));
      }
      try (SecretResolver.Secret password = secrets.secret("/password")) {
        out.put("passwordLength", password.chars().length);
      }
      out.put("pid", ProcessHandle.current().pid());
      out.put("build", build());
      return ToolResult.json(out, null);
    }
  }

  /** The plainest type: one read. */
  static final class EchoLog implements Connector {

    private final ConnectorManifest manifest = ConnectorManifest.builder("echo-log", "1.0.0",
        ConnectorManifest.Kind.BUILT_IN).name("Echo Log").icon("activity").group("Logs")
        .description("Reads lines that aren't there.")
        .configSchema(json("{\"type\":\"object\",\"properties\":{\"host\":{\"type\":\"string\"}}}"))
        .tools(List.of(ToolDefinition.builder("echolog_tail").title("Tail").description("Reads the last lines.")
            .inputSchema(input("")).build()))
        .build();

    @Override
    public ConnectorManifest manifest() {
      return manifest;
    }

    @Override
    public Classification classify(ToolCall call, ConnectionConfig cfg, PolicyView policy) {
      return Classification.read();
    }

    @Override
    public ActionPreview describeAction(ToolCall call, ConnectionConfig cfg) {
      return ActionPreview.of(ActionPreview.Kind.TEXT, "text", call.tool(), cfg.name(), call.tool());
    }

    @Override
    public TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
      return TestResult.success(1, "echo 1.0", null, null);
    }

    @Override
    public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
      return ToolResult.json(JsonNodeFactory.instance.objectNode().put("lines", 0), null);
    }
  }
}
