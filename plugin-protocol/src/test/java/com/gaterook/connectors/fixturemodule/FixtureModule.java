package com.gaterook.connectors.fixturemodule;

import com.gaterook.connector.api.ActionPreview;
import com.gaterook.connector.api.Classification;
import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.Connector;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.ConnectorManifest;
import com.gaterook.connector.api.ConnectorModule;
import com.gaterook.connector.api.EndpointView;
import com.gaterook.connector.api.ExecContext;
import com.gaterook.connector.api.ModuleContext;
import com.gaterook.connector.api.PolicyView;
import com.gaterook.connector.api.PreparedAction;
import com.gaterook.connector.api.SecretResolver;
import com.gaterook.connector.api.TemplateKind;
import com.gaterook.connector.api.TestResult;
import com.gaterook.connector.api.ToolCall;
import com.gaterook.connector.api.ToolDefinition;
import com.gaterook.connector.api.ToolResult;
import com.gaterook.plugin.protocol.GppJson;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A connector module for tests, in a package a module's code may live in ({@code com.gaterook.connectors.*}):
 * two connector types written as compiled-in connectors are. {@code alpha} uses every
 * part of the SPI a module's host forwards (a tool that classifies per call, prepared actions with state, its part of
 * describe_connection, a tool some connections don't support, a template kind, shared quotas, the masked view,
 * progress); {@code beta} is the plainest connector there is. Both declare the {@code connection} parameter and their
 * own default modes, as built-in tools do.
 */
public final class FixtureModule implements ConnectorModule {

  public static final String ID = "fixture-module";
  public static final String VERSION = "1.0.0";

  /** The module's descriptor, as a {@code module.json} would hold it. */
  public static final String DESCRIPTOR = """
      {"id":"fixture-module","version":"1.0.0","name":"Fixture module","provider":"GateRook","license":"Apache-2.0",
       "description":"Two connector types for tests.",
       "moduleClass":"com.gaterook.connectors.fixturemodule.FixtureModule",
       "requires":{"gateway":">=0.1.0 & <99.0.0","connectorApi":">=2.0.0 & <3.0.0"},
       "access":{"hosts":["{connection.host}:443"],"commands":[],"files":[]},
       "resources":{"maxHeapMb":128,"maxMetaspaceMb":96,"maxInFlight":4},
       "typeCapabilities":{"alpha":["quota","masked"]}}""";

  /** What the connectors were asked, in order: {@code alpha.execute alpha_run}, {@code beta.test}. */
  public final List<String> asked = new CopyOnWriteArrayList<>();
  private volatile boolean closed;

  @Override
  public List<Connector> connectors(ModuleContext context) {
    return List.of(new Alpha(), new Beta());
  }

  @Override
  public void close() {
    closed = true;
  }

  public boolean closed() {
    return closed;
  }

  static JsonNode json(String text) {
    return GppJson.MAPPER.readTree(text);
  }

  private static JsonNode input(String extra) {
    return json("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"connection\"],\"properties\":{"
        + "\"connection\":{\"type\":\"string\",\"description\":\"The connection to run on\"}" + extra + "}}");
  }

  /** A connector type that uses everything a module's host forwards. */
  public final class Alpha implements Connector {

    private final ConnectorManifest manifest = ConnectorManifest.builder("alpha", VERSION,
        ConnectorManifest.Kind.BUILT_IN).name("Alpha").icon("database").group("Databases")
        .description("The first fixture type.")
        .configSchema(json("""
            {"type":"object","required":["host"],"properties":{"host":{"type":"string"},
             "readOnly":{"type":"boolean","default":false},
             "password":{"type":"string","writeOnly":true}}}"""))
        .policySchema(json("{\"type\":\"object\",\"properties\":{\"blockDrop\":{\"type\":\"boolean\"}}}"))
        .tools(List.of(
            ToolDefinition.builder("alpha_read").title("Read alpha").description("Reads rows.")
                .inputSchema(input(",\"table\":{\"type\":\"string\"}"))
                .outputSchema(json("{\"type\":\"object\"}")).build(),
            ToolDefinition.builder("alpha_run").title("Run on alpha").description("Runs a statement.")
                .inputSchema(input(",\"sql\":{\"type\":\"string\"}")).nature(ToolDefinition.Nature.MIXED)
                .annotations(new ToolDefinition.Annotations(false, true, false, false))
                .defaultMode(ToolDefinition.Mode.READ_ONLY, ToolDefinition.Mode.APPROVAL).build(),
            ToolDefinition.builder("alpha_write").title("Write to alpha").description("Changes a row.")
                .inputSchema(input(",\"id\":{\"type\":\"integer\"}")).nature(ToolDefinition.Nature.MUTATION)
                .annotations(ToolDefinition.Annotations.write(false))
                .defaultMode(ToolDefinition.Mode.APPROVAL, ToolDefinition.Mode.APPROVAL).build()))
        .access(new ConnectorManifest.RequiredAccess(List.of("{connection.host}:443"), List.of(), List.of()))
        .build();

    @Override
    public ConnectorManifest manifest() {
      return manifest;
    }

    @Override
    public List<ToolDefinition> tools(EndpointView endpoint) {
      asked.add("alpha.tools " + endpoint.slug());
      return manifest.tools();
    }

    @Override
    public boolean supportsTool(String tool, ConnectionConfig cfg) {
      return !("alpha_write".equals(tool) && cfg.bool("/readOnly", false));
    }

    @Override
    public int maxConcurrency() {
      return 2;
    }

    @Override
    public boolean runsCommand(String tool) {
      return "alpha_run".equals(tool);
    }

    @Override
    public Classification classify(ToolCall call, ConnectionConfig cfg, PolicyView policy) {
      asked.add("alpha.classify " + call.tool());
      String sql = call.string("sql");
      if ("alpha_run".equals(call.tool())) {
        if (sql != null && sql.toLowerCase(java.util.Locale.ROOT).startsWith("drop") && policy.flag("blockDrop", false)) {
          return Classification.deny(policy.rule("block_drop"), "DROP is blocked on " + cfg.name() + ".", null);
        }
        return sql != null && sql.toLowerCase(java.util.Locale.ROOT).startsWith("select") ? Classification.read()
            : Classification.mutation("The statement changes data.");
      }
      return "alpha_read".equals(call.tool()) ? Classification.read() : Classification.mutation("Changes a row.");
    }

    @Override
    public ActionPreview describeAction(ToolCall call, ConnectionConfig cfg) {
      String text = call.string("sql") == null ? "UPDATE t SET x = 1 WHERE id = " + call.integer("id", 0)
          : call.string("sql");
      ObjectNode payload = GppJson.object();
      payload.put("sql", text);
      return ActionPreview.of(ActionPreview.Kind.SQL, "sql", text, cfg.name() + " · " + cfg.string("/host"),
          call.tool() + " on " + cfg.name()).withPayload(payload);
    }

    @Override
    public PreparedAction prepare(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
        throws ConnectorException {
      asked.add("alpha.prepare " + call.tool() + (call.template() == null ? "" : " template "
          + call.template().templateId()));
      if (call.integer("id", 0) < 0) {
        return PreparedAction.reject("There is no row " + call.integer("id", 0) + ".");
      }
      if (call.integer("id", 0) == 404) {
        throw ConnectorException.unavailable("alpha is unreachable", null);
      }
      try (SecretResolver.Secret password = secrets.secret("/password")) {
        ObjectNode state = GppJson.object();
        state.put("rowVersion", 7);
        state.put("passwordLength", password.chars().length);
        return PreparedAction.approval(describeAction(call, cfg).withImpact(List.of(
            new ActionPreview.Field("Rows", "1"))), state);
      }
    }

    @Override
    public TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
      asked.add("alpha.test");
      return TestResult.success(3, "alpha 1.0", "tester", null);
    }

    @Override
    public Optional<JsonNode> describe(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, String object) {
      asked.add("alpha.describe " + object);
      if ("nothing".equals(object)) {
        return Optional.empty();
      }
      ObjectNode out = GppJson.object();
      out.put("object", object == null ? "(all)" : object);
      out.putArray("tables").add("orders").add("customers");
      return Optional.of(out);
    }

    @Override
    public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
        throws ConnectorException {
      asked.add("alpha.execute " + call.tool());
      ObjectNode out = GppJson.object();
      out.put("tool", call.tool());
      out.put("approved", ctx.approved());
      out.put("template", call.template() == null ? null : call.template().templateId() + "@"
          + call.template().version());
      ctx.preparedState().ifPresent(state -> out.set("preparedState", state));
      ctx.approvedPayload().ifPresent(payload -> out.set("approvedPayload", payload));
      String table = call.string("table");
      if ("quota".equals(table)) {
        out.put("first", ctx.acquire("alpha:account-1:api", 1));
        out.put("second", ctx.acquire("alpha:account-1:api", 1));
      }
      if ("masked".equals(table)) {
        ObjectNode tree = GppJson.object();
        tree.put("email", "ann@example.com");
        out.set("masked", ctx.masked(tree));
      }
      if ("progress".equals(table)) {
        out.put("wantsProgress", ctx.wantsProgress());
        ctx.progress(1, 2.0, "Half way");
        out.put("clientLogLevel", ctx.clientLogLevel());
        ctx.clientLog("info", "alpha", GppJson.object().put("message", "reading"));
      }
      return ToolResult.json(out, null);
    }

    @Override
    public List<TemplateKind> templateKinds() {
      return List.of(new TemplateKind() {
        @Override
        public String kind() {
          return "alpha-query";
        }

        @Override
        public List<String> connectionTypes() {
          return List.of("alpha");
        }

        @Override
        public JsonNode definitionSchema() {
          return json("{\"type\":\"object\",\"required\":[\"name\",\"sql\"],\"properties\":{"
              + "\"name\":{\"type\":\"string\"},\"sql\":{\"type\":\"string\"}}}");
        }

        @Override
        public CompileResult compile(JsonNode definition, ConnectionConfig runsOn) {
          asked.add("alpha.compile " + definition.path("name").asString());
          String name = definition.path("name").asString("");
          if (name.isEmpty()) {
            return new CompileResult(List.of(), List.of(), List.of("A template needs a name."), List.of(), null);
          }
          ToolDefinition tool = ToolDefinition.builder(name).title(name).description("A stored query.")
              .inputSchema(input("")).build();
          return new CompileResult(List.of(new CompiledTool("q", tool, definition.path("sql"))), List.of(),
              List.of(), List.of("Runs on " + runsOn.name() + "."), "sha256:fixture");
        }

        @Override
        public ActionPreview preview(JsonNode definition, ObjectNode arguments, ConnectionConfig runsOn) {
          return ActionPreview.of(ActionPreview.Kind.SQL, "sql", definition.path("sql").asString(), runsOn.name(),
              "A stored query with " + arguments.size() + " arguments");
        }
      });
    }

    @Override
    public void onConnectionChanged(String connectionId) {
      asked.add("alpha.changed " + connectionId);
    }
  }

  /** The plainest connector type: one read. */
  public final class Beta implements Connector {

    private final ConnectorManifest manifest = ConnectorManifest.builder("beta", VERSION,
        ConnectorManifest.Kind.BUILT_IN).name("Beta").icon("activity").group("Logs")
        .description("The second fixture type.")
        .configSchema(json("{\"type\":\"object\",\"properties\":{\"host\":{\"type\":\"string\"}}}"))
        .tools(List.of(ToolDefinition.builder("beta_read").title("Read beta").description("Reads lines.")
            .inputSchema(input("")).resultShape(ToolDefinition.ResultShape.LOGS).build()))
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
      asked.add("beta.test");
      return TestResult.success(1, "beta 2.0", null, null);
    }

    @Override
    public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
      asked.add("beta.execute " + call.tool());
      return ToolResult.json(GppJson.object().put("type", "beta").put("template", call.template() != null), null);
    }
  }

  /** The manifest map the fixture's types are known by, for tests that need one without a host. */
  public static Map<String, ConnectorManifest> manifests() {
    FixtureModule module = new FixtureModule();
    return Map.of("alpha", module.new Alpha().manifest(), "beta", module.new Beta().manifest());
  }
}
