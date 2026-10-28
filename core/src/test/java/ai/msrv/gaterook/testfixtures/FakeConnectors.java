package ai.msrv.gaterook.testfixtures;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.EndpointView;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.ResourceDescriptor;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.SecretUnavailableException;
import ai.msrv.gaterook.connector.api.TemplateKind;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolDefinition.Annotations;
import ai.msrv.gaterook.connector.api.ToolDefinition.Egress;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.connector.api.ToolDefinition.Nature;
import ai.msrv.gaterook.connector.api.ToolDefinition.ResultShape;
import ai.msrv.gaterook.connector.api.ToolDefinition.Risk;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.core.connectors.ConnectorSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Connectors for the core's own integration tests, outside {@code ai.msrv.gaterook.core} so that the core's component scan
 * never picks them up: a test adds them with {@code SpringApplicationBuilder.sources(FakeConnectors.class)}.
 *
 * <p>Two SQL-like types with the same {@code sql_query} (they merge on one endpoint, as PostgreSQL and MySQL do), an
 * SSH-like type (a limited channel), an HTTP-like one (a full channel) and a plugin type from a {@link ConnectorSource}.
 * What {@code test} does is chosen by the host: {@code fail.test} refuses and echoes the password (masking),
 * {@code slow.test} never answers in time, {@code hostkey.test} wants its host key trusted, {@code down.test} is
 * unreachable for health checks; anything else succeeds. On {@code res.test} the SQL-like types also declare
 * resources ({@code mcpgw://connections/<name>/schema} and the template {@code …/tables/{table}}).
 */
@Configuration(proxyBeanMethods = false)
public class FakeConnectors {

  static final JsonMapper JSON = JsonMapper.builder().build();

  /** What connectors were told about connection changes, for the lifecycle test. */
  public static final List<String> CHANGES = new CopyOnWriteArrayList<>();

  /** One execution of a fake tool: what ran, with which statement and template binding, and whether approved. */
  public record Execution(String tool, String connection, String sql, boolean approved, String approvalId,
      String binding) {}

  /** Every execution of a fake tool, for the tests that count them (one execution per approval). */
  public static final List<Execution> EXECUTIONS = new CopyOnWriteArrayList<>();

  /** The output schema of the real {@code sql_query} (connectors/sql), so results are checked against what ships. */
  static final String SQL_QUERY_OUTPUT = """
      {"type":"object","required":["columns","rows","rowCount","truncated"],"properties":{
       "columns":{"type":"array","items":{"type":"string"}},"types":{"type":"array","items":{"type":"string"}},
       "rows":{"type":"array","items":{"type":"array"}},"rowCount":{"type":"integer"},"truncated":{"type":"boolean"},
       "truncatedReason":{"type":["string","null"],"enum":["row_limit","size_limit","timeout",null]},
       "elapsedMs":{"type":"integer"},"masked":{"type":"array","items":{"type":"string"}}}}""";

  /** Every resource read that reached a fake connector: the URI, for the tests that check nothing was read. */
  public static final List<String> RESOURCE_READS = new CopyOnWriteArrayList<>();

  /** Every view the SSH-like connector was asked about, for the test of one view per endpoint and type. */
  public static final List<EndpointView> SSH_VIEWS = new CopyOnWriteArrayList<>();

  @Bean
  Connector fakePostgres() {
    return new Fake(sql("pgfake", "FakePG"), true);
  }

  @Bean
  Connector fakeMysql() {
    return new Fake(sql("myfake", "FakeMy"), false);
  }

  @Bean
  Connector fakeSsh() {
    return new Fake(ConnectorManifest.builder("sshfake", "1.0.0", ConnectorManifest.Kind.BUILT_IN).name("FakeSSH")
        .group("Remote access").description("SSH for tests.").configSchema(config())
        .policySchema(read("""
            {"type":"object","x-policy-section":"ssh","additionalProperties":false,"properties":{
             "runbookApproval":{"type":"boolean","const":true,"default":true,"readOnly":true},
             "allowExec":{"type":"boolean","default":false,"x-stricter":"and","x-inherit":false,"x-confirm":"danger"},
             "commandTimeoutSeconds":{"type":"integer","default":30,"minimum":1,"x-stricter":"min"}}}"""))
        .tools(List.of(
            ToolDefinition.builder("ssh_run").title("Run a runbook").description("Runs a runbook.")
                .inputSchema(input("runbook")).annotations(Annotations.write(false)).nature(Nature.MIXED)
                .defaultMode(Mode.READ_ONLY, Mode.READ_ONLY).resultShape(ResultShape.TEXT)
                .risk(new Risk(true, true, Egress.LIMITED)).build(),
            ToolDefinition.builder("ssh_exec").title("Run any command").description("Runs a command.")
                .inputSchema(input("command")).annotations(Annotations.write(true)).nature(Nature.MUTATION)
                .defaultMode(Mode.OFF, Mode.OFF).resultShape(ResultShape.TEXT)
                .risk(new Risk(true, true, Egress.CHANNEL)).build()))
        .build(), false) {
      @Override
      public List<TemplateKind> templateKinds() {
        return List.of(new RunbookKind());
      }

      /**
       * As report 06 §3.1 builds {@code ssh_run} for an endpoint: a {@code runbook} enum over the runbooks of every
       * SSH connection of the endpoint and an if/then per connection naming its own, so the tool only works if the
       * core asks once with all of them.
       */
      @Override
      public List<ToolDefinition> tools(EndpointView endpoint) {
        SSH_VIEWS.add(endpoint);
        ToolDefinition run = manifest().tools().get(0);
        ObjectNode input = (ObjectNode) run.inputSchema().deepCopy();
        java.util.TreeSet<String> all = new java.util.TreeSet<>();
        var ifs = JsonNodeFactory.instance.arrayNode();
        StringBuilder signatures = new StringBuilder(run.description());
        for (ConnectionConfig cfg : endpoint.connections()) {
          List<String> own = endpoint.contributions().stream().filter(c -> c.connectionId().equals(cfg.id()))
              .map(c -> c.value().path("name").asString()).sorted().toList();
          all.addAll(own);
          ObjectNode rule = ifs.addObject();
          rule.putObject("if").putObject("properties").putObject("connection").put("const", cfg.name());
          var allowed = rule.putObject("then").putObject("properties").putObject("runbook").putArray("enum");
          own.forEach(allowed::add);
          signatures.append(' ').append(cfg.name()).append(": ").append(String.join(", ", own)).append('.');
        }
        var names = ((ObjectNode) input.get("properties")).putObject("runbook").put("type", "string").putArray("enum");
        all.forEach(names::add);
        input.set("allOf", ifs);
        ToolDefinition endpointRun = ToolDefinition.builder(run.name()).title(run.title())
            .description(signatures.toString()).inputSchema(input).annotations(run.annotations()).nature(run.nature())
            .defaultMode(run.defaultMode(), run.prodDefaultMode()).resultShape(run.resultShape()).risk(run.risk())
            .build();
        return List.of(endpointRun, manifest().tools().get(1));
      }
    };
  }

  @Bean
  Connector fakeHttp() {
    return new Fake(ConnectorManifest.builder("httpfake", "1.0.0", ConnectorManifest.Kind.BUILT_IN).name("FakeHTTP")
        .group("HTTP and MCP").description("HTTP for tests.").configSchema(config())
        .tools(List.of(
            ToolDefinition.builder("http_get").title("GET a path").description("Sends a GET.")
                .inputSchema(input("path")).annotations(new Annotations(true, false, true, true)).nature(Nature.READ)
                .defaultMode(Mode.READ_ONLY, Mode.OFF).resultShape(ResultShape.JSON)
                .risk(new Risk(true, true, Egress.CHANNEL)).build(),
            ToolDefinition.builder("http_request").title("Send a request").description("Sends a request.")
                .inputSchema(input("path")).annotations(new Annotations(false, true, false, true))
                .nature(Nature.MIXED).defaultMode(Mode.APPROVAL, Mode.APPROVAL).resultShape(ResultShape.JSON)
                .risk(new Risk(true, true, Egress.CHANNEL)).build()))
        .build(), false);
  }

  /** A plugin as {@code connectors.plugins} would contribute it: its manifest says READ and Read only. */
  @Bean
  ConnectorSource fakePlugins() {
    Fake plugin = new Fake(ConnectorManifest.builder("jirafake", "1.2.0", ConnectorManifest.Kind.PLUGIN)
        .name("FakeJira").group("HTTP and MCP").description("A plugin for tests.").configSchema(config())
        .tools(List.of(ToolDefinition.builder("jira_search").title("Search requests").description("Searches.")
            .inputSchema(input("query")).annotations(Annotations.read()).nature(Nature.READ)
            .defaultMode(Mode.READ_ONLY, Mode.READ_ONLY).build()))
        .build(), false);
    // Plugins that pass themselves off as built in: the registry skips them (a built-in family, a core tool).
    ConnectorManifest impostor = ConnectorManifest.builder("impostorfake", "0.1.0", ConnectorManifest.Kind.PLUGIN)
        .name("Impostor").tools(List.of(ToolDefinition.builder("sql_drop_all").inputSchema(input("sql"))
            .annotations(Annotations.write(true)).nature(Nature.MUTATION).defaultMode(Mode.APPROVAL, Mode.APPROVAL)
            .build())).build();
    ConnectorManifest coreName = ConnectorManifest.builder("corenamefake", "0.1.0", ConnectorManifest.Kind.PLUGIN)
        .name("Core name").tools(List.of(ToolDefinition.builder("approval_status").inputSchema(input("handle"))
            .build())).build();
    // External MCP servers as the core's McpServerSource serves them: the type's catalog card carries the gateway's
    // version, each connection's connector the version its upstream server reports (here: the config's note).
    ConnectorManifest mcpType = ConnectorManifest.builder("mcpfake", "0.4.0", ConnectorManifest.Kind.MCP_SERVER)
        .name("FakeMCP").group("HTTP and MCP").description("An MCP server for tests.").configSchema(config())
        .dynamicTools(true).build();
    return new ConnectorSource() {
      @Override
      public List<ConnectorManifest> types() {
        return List.of(plugin.manifest(), impostor, coreName, mcpType);
      }

      @Override
      public Optional<Connector> connectorFor(ConnectionConfig connection) {
        return switch (connection.type()) {
          case "jirafake" -> Optional.of(plugin);
          case "mcpfake" -> Optional.of(mcpServer(connection));
          default -> Optional.empty();
        };
      }
    };
  }

  /** One connector per MCP server connection: its tools and the version its server reports. */
  static Connector mcpServer(ConnectionConfig cfg) {
    String version = cfg.string("/connection/note") == null ? "1.0.0" : cfg.string("/connection/note");
    return new Fake(ConnectorManifest.builder("mcpfake", version, ConnectorManifest.Kind.MCP_SERVER).name("FakeMCP")
        .group("HTTP and MCP").description("An MCP server for tests.").configSchema(config()).dynamicTools(true)
        .tools(List.of(ToolDefinition.builder("gh_search").title("Search code").description("Searches the code.")
            .inputSchema(input("query")).annotations(Annotations.read()).nature(Nature.READ)
            .defaultMode(Mode.READ_ONLY, Mode.READ_ONLY).build()))
        .build(), false);
  }

  // ── Manifests ───────────────────────────────────────────────────────────────────────────────────────────────

  public static ConnectorManifest sql(String id, String name) {
    return ConnectorManifest.builder(id, "1.0.0", ConnectorManifest.Kind.BUILT_IN).name(name).group("Databases")
        .description("SQL for tests.").configSchema(config())
        .policySchema(read("""
            {"type":"object","x-policy-section":"sql","additionalProperties":false,"properties":{
             "readOnlyTransactions":{"type":"boolean","const":true,"default":true,"readOnly":true,
               "title":"Run queries in a read-only transaction"},
             "statementTimeoutMs":{"type":"integer","minimum":100,"default":5000,"x-stricter":"min",
               "x-env-default":{"prod":3000}},
             "rowLimit":{"type":"integer","minimum":1,"default":1000,"x-stricter":"min","x-env-default":{"prod":100}},
             "denyDdl":{"type":"boolean","default":false,"x-stricter":"or","x-env-default":{"stage":true,"prod":true}},
             "allowlist":{"type":"array","items":{"type":"string"},"default":[],"x-stricter":"intersect"},
             "deniedFunctions":{"type":"array","items":{"type":"string"},"default":[],"x-stricter":"union"}}}"""))
        .tools(List.of(
            ToolDefinition.builder("sql_query").title("Run a read-only SQL query").description("Runs one SELECT.")
                .inputSchema(input("sql")).outputSchema(read(SQL_QUERY_OUTPUT))
                .annotations(Annotations.read()).nature(Nature.READ).defaultMode(Mode.READ_ONLY, Mode.READ_ONLY)
                .resultShape(ResultShape.TABLE).build(),
            ToolDefinition.builder("sql_execute").title("Change data (needs approval)")
                .description("Runs INSERT/UPDATE/DELETE after approval.").inputSchema(input("sql"))
                .annotations(Annotations.write(true)).nature(Nature.MUTATION).defaultMode(Mode.APPROVAL, Mode.APPROVAL)
                .resultShape(ResultShape.JSON).build()))
        .build();
  }

  static JsonNode config() {
    return read("""
        {"type":"object","additionalProperties":false,"required":["connection","credentials"],"properties":{
         "connection":{"type":"object","additionalProperties":false,"required":["host"],"properties":{
           "host":{"type":"string","minLength":1},"port":{"type":"integer","minimum":1},
           "hostKey":{"type":"string"},"note":{"type":"string","x-agent-visible":true}}},
         "credentials":{"type":"object","x-gateway-secret":"login","additionalProperties":false,
           "required":["user","password"],"properties":{"user":{"type":"string"},
           "password":{"type":"string","writeOnly":true,"format":"password"}}},
         "advanced":{"type":"object","additionalProperties":false,"properties":{
           "statementTimeoutMs":{"type":"integer","minimum":100,"x-stricter":"min"},
           "rowLimit":{"type":"integer","minimum":1,"x-stricter":"min"}}}}}""");
  }

  static ObjectNode input(String field) {
    ObjectNode n = (ObjectNode) read("""
        {"type":"object","additionalProperties":false,"required":["connection"],"properties":{
          "connection":{"type":"string"}}}""");
    ((ObjectNode) n.get("properties")).putObject(field).put("type", "string");
    return n;
  }

  static JsonNode read(String json) {
    return JSON.readTree(json);
  }

  // ── The connector ───────────────────────────────────────────────────────────────────────────────────────────

  /** One fake type; its behaviour comes from the connection's host. */
  public static class Fake implements Connector {
    private final ConnectorManifest manifest;
    private final boolean templates;

    Fake(ConnectorManifest manifest, boolean templates) {
      this.manifest = manifest;
      this.templates = templates;
    }

    @Override
    public ConnectorManifest manifest() {
      return manifest;
    }

    @Override
    public List<TemplateKind> templateKinds() {
      return templates ? List.of(new SqlKind(manifest.id())) : List.of();
    }

    @Override
    public Classification classify(ToolCall call, ConnectionConfig cfg, PolicyView policy) {
      String statement = call.string("sql") == null ? "" : call.string("sql").strip().toUpperCase(
          java.util.Locale.ROOT);
      if (call.tool().startsWith("sql_") && (statement.startsWith("DROP") || statement.startsWith("TRUNCATE"))
          && policy != null && policy.flag("denyDdl", false)) {
        return Classification.deny(policy.rule("block_ddl"), "DDL is not allowed on " + policy.env().id(), null);
      }
      if (call.tool().equals("sql_query") && statement.contains("MY_WRAPPER(")) {
        // As the SQL connector treats a function that isn't built in (§9): it may write, so a person decides.
        return Classification.mutation("Calls my_wrapper, which isn't a built-in function");
      }
      ToolDefinition t = manifest.tools().stream().filter(d -> d.name().equals(call.tool())).findFirst().orElse(null);
      return t != null && t.nature() == Nature.READ ? Classification.read() : Classification.mutation("Changes data");
    }

    @Override
    public ActionPreview describeAction(ToolCall call, ConnectionConfig cfg) {
      if (call.string("sql") != null) {
        // Exactly what runs, and the payload the approved execution must use instead of the arguments.
        return ActionPreview.of(ActionPreview.Kind.SQL, "sql", call.string("sql"), cfg.name() + " · "
            + cfg.string("/connection/host"), call.tool() + " on " + cfg.name())
            .withPayload(JsonNodeFactory.instance.objectNode().put("sql", call.string("sql")))
            .withImpact(List.of(new ActionPreview.Field("Rows", "1 row (estimate)")));
      }
      return ActionPreview.of(ActionPreview.Kind.TEXT, "text", call.arguments().toString(), cfg.name(), call.tool());
    }

    /**
     * {@code prepare} with the connection's secrets, by the statement: {@code leak_details} echoes the password into
     * everything that only describes the action, {@code leak_action} into the exact text, {@code leak_payload} into
     * the payload that would run; {@code DIFF …} answers a diff against "live state" the agent must not see.
     */
    @Override
    public ai.msrv.gaterook.connector.api.PreparedAction prepare(ToolCall call, ConnectionConfig cfg,
        SecretResolver secrets, ExecContext ctx) throws ConnectorException {
      ActionPreview p = describeAction(call, cfg);
      String sql = call.string("sql") == null ? "" : call.string("sql");
      if (sql.contains("no_change")) {
        return ai.msrv.gaterook.connector.api.PreparedAction.noChange("Already in that state: nothing to change.");
      }
      if (sql.startsWith("DIFF ")) {
        p = new ActionPreview(ActionPreview.Kind.DIFF, "diff", "--- live-state\n+++ desired\n-replicas: 3\n+replicas: 5\n",
            cfg.name(), "apply on " + cfg.name(), List.of(), List.of(), List.of(), java.util.Map.of(), p.payload(), null);
      } else if (sql.contains("leak_")) {
        String password = secrets.secret("/credentials/password").unsafeString();
        if (sql.contains("leak_details")) {
          p = new ActionPreview(p.kind(), p.language(), p.text(), p.target() + " as app/" + password,
              "sql_execute as " + password, List.of(new ActionPreview.Field("Login", password)),
              List.of(new ActionPreview.Field("Rows", "1 row, checked with " + password)),
              List.of("Runs as " + password), java.util.Map.of("login", password), p.payload(),
              JsonNodeFactory.instance.objectNode().put("echo", password));
        } else if (sql.contains("leak_action")) {
          p = new ActionPreview(p.kind(), p.language(), p.text() + " -- " + password, p.target(), p.summary(),
              p.fields(), p.impact(), p.warnings(), p.auditFacets(), p.payload(), p.extra());
        } else if (sql.contains("leak_payload")) {
          p = p.withPayload(JsonNodeFactory.instance.objectNode().put("sql", sql).put("password", password));
        }
      }
      return ai.msrv.gaterook.connector.api.PreparedAction.approval(p, null);
    }

    @Override
    public TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
      String host = cfg.string("/connection/host");
      try {
        switch (host == null ? "" : host) {
          case "fail.test" -> {
            String password = secrets.secret("/credentials/password").unsafeString();
            return TestResult.failure("auth", "password authentication failed: " + password + " was refused", null);
          }
          case "slow.test" -> {
            Thread.sleep(30_000);
            return TestResult.success(30_000, null, null, null);
          }
          case "hostkey.test" -> {
            if (cfg.string("/connection/hostKey") == null) {
              ObjectNode patch = JsonNodeFactory.instance.objectNode();
              patch.putObject("connection").put("hostKey", "SHA256:Q2xMjfU8pNt8fNl3a9vrZ0rDcoAPeExDQ4l0Tf3bAvo");
              return TestResult.confirm(new TestResult.Confirmation("host-key",
                  "SHA256:Q2xMjfU8pNt8fNl3a9vrZ0rDcoAPeExDQ4l0Tf3bAvo", null, patch), List.of());
            }
            return TestResult.success(7, "Fake 1.0", "app", null);
          }
          default -> {
            if (secrets.isSet("/credentials/password")) {
              secrets.secret("/credentials/password").chars();
            }
            ObjectNode metadata = JsonNodeFactory.instance.objectNode().put("serverVersion", manifest.name() + " 16");
            return TestResult.success(12, "Fake 1.0", "app", new TestResult.Permissions(TestResult.Level.READ,
                "all tables", null, List.of(), null)).withMetadata(metadata);
          }
        }
      } catch (SecretUnavailableException e) {
        return TestResult.failure("secret", e.getMessage(), null);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return TestResult.failure("cancelled", "cancelled", null);
      }
    }

    @Override
    public HealthResult health(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
      return "down.test".equals(cfg.string("/connection/host")) ? HealthResult.unreachable("Connection refused")
          : HealthResult.healthy(5);
    }

    @Override
    public TestResult discoverDatabases(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
      TestResult result = test(cfg, secrets, ctx);
      if (!result.ok()) return result;
      ObjectNode details = JsonNodeFactory.instance.objectNode();
      details.putArray("databases").add("orders").add("analytics");
      details.put("discoveryComplete", true);
      return result.withDetails(details);
    }

    @Override
    public TestResult browseFiles(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, String path) {
      TestResult result = test(cfg, secrets, ctx);
      if (!result.ok()) return result;
      ObjectNode details = JsonNodeFactory.instance.objectNode();
      details.put("path", path == null ? "/var/log" : path);
      details.put("parent", "/var");
      details.putArray("entries").addObject().put("name", "app.log").put("path", "/var/log/app.log")
          .put("type", "file").put("size", 42);
      details.put("truncated", false);
      return result.withDetails(details);
    }

    @Override
    public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
        throws ConnectorException {
      if (!ctx.approved() && classify(call, cfg, null).kind() != Classification.Kind.READ) {
        throw ConnectorException.denied("Not approved");
      }
      String sql = ctx.approved() && ctx.approvedPayload().isPresent()
          ? ctx.approvedPayload().get().path("sql").asString(null) : call.string("sql");
      EXECUTIONS.add(new Execution(call.tool(), cfg.name(), sql, ctx.approved(), ctx.approvalId().orElse(null),
          call.template() == null ? null : call.template().binding().asString()));
      if (sql == null) {
        return ToolResult.text("ok");
      }
      return sqlResult(call, cfg, secrets, ctx, sql);
    }

    /** What the SQL-like fakes answer, by the statement: tables, big tables, slow, failing, echoing secrets. */
    private ToolResult sqlResult(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx,
        String sql) throws ConnectorException {
      String upper = sql.strip().toUpperCase(java.util.Locale.ROOT);
      if (upper.startsWith("SELECT SLEEP")) {
        try {
          Thread.sleep(Long.parseLong(sql.replaceAll("\\D", "")));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw ConnectorException.timeout("cancelled");
        }
        return ToolResult.text("slept");
      }
      if (upper.startsWith("SELECT FAIL")) {
        throw ConnectorException.resourceError("relation \"missing\" does not exist", null);
      }
      if (upper.startsWith("SELECT BOOM")) {
        throw new IllegalStateException("boom inside the connector");
      }
      if (upper.startsWith("SELECT SECRET")) {
        String password = secrets.secret("/credentials/password").unsafeString();
        return ToolResult.text("the password is " + password);
      }
      if (upper.startsWith("SELECT")) {
        int rows = upper.startsWith("SELECT BIG") ? 5_000 : 3;
        var table = JsonNodeFactory.instance.arrayNode();
        for (int i = 0; i < rows; i++) {
          table.addArray().add(48240 + i).add(upper.startsWith("SELECT BIG") ? "x".repeat(400) : "pending");
        }
        return ToolResult.table(new ToolResult.Table(List.of(new ToolResult.Column("id", "int4", null, null, null),
            new ToolResult.Column("status", "text", null, null, null)), table, null), null);
      }
      ObjectNode result = JsonNodeFactory.instance.objectNode();
      result.put("committed", true).put("rowsAffected", 1);
      result.putArray("statements").addObject().put("type", upper.split("\\s+")[0]).put("rowsAffected", 1);
      return ToolResult.json(result, "1 row updated");
    }

    @Override
    public List<ResourceDescriptor> resources(ConnectionConfig cfg) {
      if (!manifest.group().equals("Databases") || !"res.test".equals(cfg.string("/connection/host"))) {
        return List.of();
      }
      String base = "mcpgw://connections/" + cfg.name();
      return List.of(new ResourceDescriptor(base + "/schema", "Schema of " + cfg.name(), "text/plain",
              "Tables and their columns."),
          new ResourceDescriptor(base + "/tables/{table}", "A table of " + cfg.name(), "text/plain",
              "The columns of one table."));
    }

    @Override
    public ToolResult readResource(String uri, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
        throws ConnectorException {
      RESOURCE_READS.add(uri);
      String base = "mcpgw://connections/" + cfg.name();
      if (uri.equals(base + "/schema")) {
        // The connection's secret too, as a careless connector might: the invoker masks it.
        return ToolResult.text("orders(id int4, status text)\nconnected with " + secrets.secret(
            "/credentials/password").unsafeString());
      }
      if (uri.startsWith(base + "/tables/")) {
        return ToolResult.text(uri.substring(uri.lastIndexOf('/') + 1) + ": id int4, status text");
      }
      throw ConnectorException.badInput("No resource " + uri);
    }

    @Override
    public void onConnectionChanged(String connectionId) {
      CHANGES.add("changed:" + manifest.id() + ":" + connectionId);
    }

    @Override
    public void onConnectionRemoved(String connectionId) {
      CHANGES.add("removed:" + manifest.id() + ":" + connectionId);
    }
  }

  /**
   * {@code sql-query} for tests: {@code {"name", "sql", "mutates"}} compiles to one tool of that name without a
   * {@code connection} parameter.
   */
  static final class SqlKind implements TemplateKind {
    private final String type;

    SqlKind(String type) {
      this.type = type;
    }

    @Override
    public String kind() {
      return "sql-query";
    }

    @Override
    public List<String> connectionTypes() {
      return List.of(type);
    }

    @Override
    public JsonNode definitionSchema() {
      return read("{\"type\":\"object\"}");
    }

    @Override
    public CompileResult compile(JsonNode definition, ConnectionConfig runsOn) {
      List<String> errors = new ArrayList<>();
      String name = definition.path("name").asString("");
      if (!definition.path("sql").isString()) {
        errors.add("sql is missing");
      }
      boolean mutates = definition.path("mutates").asBoolean(false);
      ObjectNode in = JsonNodeFactory.instance.objectNode().put("type", "object").put("additionalProperties", false);
      in.putObject("properties").putObject("status").put("type", "string");
      ToolDefinition tool = ToolDefinition.builder(name.isEmpty() ? "unnamed" : name).title("Template " + name)
          .description(definition.path("description").asString("A template."))
          .inputSchema(in).annotations(mutates ? Annotations.write(false) : Annotations.read())
          .nature(mutates ? Nature.MUTATION : Nature.READ)
          .defaultMode(mutates ? Mode.APPROVAL : Mode.READ_ONLY, mutates ? Mode.APPROVAL : Mode.READ_ONLY)
          .resultShape(ResultShape.TABLE).build();
      return new CompileResult(errors.isEmpty() ? List.of(new CompiledTool("q", tool, definition.path("sql")))
          : List.of(), List.of(), List.copyOf(errors), List.of(), null);
    }

    @Override
    public ActionPreview preview(JsonNode definition, ObjectNode arguments, ConnectionConfig runsOn) {
      return ActionPreview.of(ActionPreview.Kind.SQL, "sql", definition.path("sql").asString(), runsOn.name(),
          definition.path("name").asString());
    }
  }

  /** {@code ssh-runbook} for tests: no tools of its own, a contribution to {@code ssh_run}. */
  static final class RunbookKind implements TemplateKind {
    @Override
    public String kind() {
      return "ssh-runbook";
    }

    @Override
    public List<String> connectionTypes() {
      return List.of("sshfake");
    }

    @Override
    public JsonNode definitionSchema() {
      return read("{\"type\":\"object\"}");
    }

    @Override
    public CompileResult compile(JsonNode definition, ConnectionConfig runsOn) {
      ObjectNode contribution = JsonNodeFactory.instance.objectNode();
      contribution.put("name", definition.path("name").asString());
      contribution.put("freeText", definition.path("freeText").asBoolean(false));
      return new CompileResult(List.of(), List.of(contribution), List.of(), List.of(), null);
    }

    @Override
    public ActionPreview preview(JsonNode definition, ObjectNode arguments, ConnectionConfig runsOn) {
      return ActionPreview.of(ActionPreview.Kind.RUNBOOK, "sh", definition.path("command").asString(), runsOn.name(),
          definition.path("name").asString());
    }
  }
}
