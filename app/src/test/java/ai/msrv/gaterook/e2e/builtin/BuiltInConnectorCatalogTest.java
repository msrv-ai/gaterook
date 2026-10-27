package ai.msrv.gaterook.e2e.builtin;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import ai.msrv.gaterook.connector.api.ApiVersion;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.ConnectorManifest.Kind;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.connectors.support.BuiltIn;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * The built-in connector catalog against the kit's demo world (design/BLUEPRINT.md §9): every connector module
 * registers through its own {@code AutoConfiguration.imports}, exactly as the assembled core loads them, and the
 * numbers every screen shows hold.
 */
class BuiltInConnectorCatalogTest {

  /** BLUEPRINT §9 "Connectors": the one tool count every screen shows, "Built-in · 4 tools". */
  private static final Map<String, Integer> TOOLS_PER_CONNECTOR = orderedMap(
      "PostgreSQL", 4, "MySQL", 4, "MongoDB", 2, "Redis", 2, "SSH", 2, "Logs", 2, "Kubernetes", 4, "GCP", 2, "AWS", 2,
      "HTTP API", 2);

  /** BLUEPRINT §9 "Tools of endpoint dev": the tools of each connection type on {@code dev}, by mode. */
  private static final Map<String, Map<String, Mode>> DEV_ENDPOINT = Map.of(
      "postgres", Map.of("sql_list_schemas", Mode.READ_ONLY, "sql_describe_table", Mode.READ_ONLY,
          "sql_query", Mode.READ_ONLY, "sql_execute", Mode.APPROVAL),
      "mongodb", Map.of("mongo_find", Mode.READ_ONLY, "mongo_aggregate", Mode.READ_ONLY),
      "redis", Map.of("redis_get", Mode.READ_ONLY, "redis_scan", Mode.READ_ONLY),
      "ssh", Map.of("ssh_run", Mode.READ_ONLY, "ssh_exec", Mode.OFF),
      "logs", Map.of("logs_search", Mode.READ_ONLY, "logs_tail", Mode.OFF),
      "kubernetes", Map.of("k8s_get", Mode.READ_ONLY, "k8s_logs", Mode.OFF, "k8s_rollout_restart", Mode.APPROVAL,
          "k8s_apply", Mode.OFF),
      "aws", Map.of("aws_describe", Mode.READ_ONLY, "aws_metrics", Mode.READ_ONLY),
      "http", Map.of("http_get", Mode.OFF, "http_request", Mode.APPROVAL));

  /** Names a tool of each built-in family must start with; reserved for them against plugins and MCP servers. */
  private static final Map<String, String> TOOL_PREFIX = Map.of("postgres", "sql_", "mysql", "sql_",
      "mongodb", "mongo_", "redis", "redis_", "ssh", "ssh_", "logs", "logs_", "kubernetes", "k8s_", "gcp", "gcp_",
      "aws", "aws_", "http", "http_");

  /** Property names that hold credentials: a connection form must never show them back (ARCHITECTURE.md §9). */
  private static final Set<String> SECRET_PROPERTIES = Set.of("password", "passphrase", "privateKey", "token",
      "secret", "apiKey", "secretAccessKey", "serviceAccountKey", "kubeconfig", "secretRef");

  private static List<Connector> connectors;
  private static Map<String, ConnectorManifest> byId;

  @BeforeAll
  static void loadConnectorModules() {
    ClassLoader classLoader = BuiltInConnectorCatalogTest.class.getClassLoader();
    List<String> imports = ImportCandidates.load(AutoConfiguration.class, classLoader).getCandidates().stream()
        .filter(name -> name.startsWith("ai.msrv.gaterook.connectors."))
        .toList();
    List<Class<?>> configurations = new ArrayList<>();
    for (String name : imports) {
      try {
        configurations.add(Class.forName(name));
      } catch (ClassNotFoundException e) {
        throw new AssertionError("AutoConfiguration.imports names a missing class " + name, e);
      }
    }
    List<Connector> found = new ArrayList<>();
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(configurations.toArray(Class<?>[]::new)))
        .run(ctx -> found.addAll(ctx.getBeansOfType(Connector.class).values()));
    connectors = List.copyOf(found);
    byId = connectors.stream().map(Connector::manifest)
        .collect(Collectors.toMap(ConnectorManifest::id, Function.identity()));
  }

  @Test
  void everyConnectorModuleRegistersItsConnectorTypes() {
    assertThat(byId.keySet()).containsExactlyInAnyOrder("postgres", "mysql", "mongodb", "redis", "ssh", "logs",
        "kubernetes", "gcp", "aws", "http", "mcp-server");
    assertThat(connectors).hasSize(byId.size());
  }

  @Test
  void builtInToolCountsAreTheKits() {
    Map<String, Integer> counts = new LinkedHashMap<>();
    byId.values().stream()
        .filter(m -> m.kind() == Kind.BUILT_IN)
        .forEach(m -> counts.put(m.name(), m.tools().size()));

    assertThat(counts).containsExactlyInAnyOrderEntriesOf(TOOLS_PER_CONNECTOR);
  }

  @Test
  void manifestsCarryTheBuildVersionAndTheApiVersion() {
    for (ConnectorManifest m : byId.values()) {
      assertThat(m.version()).as(m.id()).isEqualTo(BuiltIn.VERSION);
      assertThat(m.connectorApi()).as(m.id()).isEqualTo(ApiVersion.CURRENT);
      assertThat(m.group()).as(m.id())
          .isIn("Databases", "Remote access", "Logs", "Kubernetes", "Cloud", "HTTP and MCP");
      assertThat(m.description()).as(m.id()).isNotBlank().endsWith(".");
    }
  }

  @Test
  void externalMcpServersBringTheirOwnTools() {
    ConnectorManifest m = byId.get("mcp-server");

    assertThat(m.kind()).isEqualTo(Kind.MCP_SERVER);
    assertThat(m.dynamicTools()).isTrue();
    assertThat(m.tools()).isEmpty();
  }

  @Test
  void devEndpointHasTheKitsModes() {
    // BLUEPRINT §9: dev has 22 tools, 14 read only · 3 approval · 5 off. Defaults of its 8 connection types, then the
    // Setup change of 10:40 (http_get on billing-api: Read only → Off), plus the core tools list_connections and
    // describe_connection, both Read only.
    Map<Mode, Integer> modes = new EnumMap<>(Mode.class);
    DEV_ENDPOINT.forEach((type, expected) -> {
      Map<String, Mode> defaults = byId.get(type).tools().stream()
          .collect(Collectors.toMap(ToolDefinition::name, ToolDefinition::defaultMode));
      assertThat(defaults.keySet()).as(type).containsExactlyInAnyOrderElementsOf(expected.keySet());
      expected.forEach((tool, mode) -> {
        Mode initial = defaults.get(tool);
        if (tool.equals("http_get")) {
          assertThat(initial).as("http_get before the Setup change of 10:40").isEqualTo(Mode.READ_ONLY);
          initial = Mode.OFF;
        }
        assertThat(initial).as(type + " " + tool).isEqualTo(mode);
        modes.merge(initial, 1, Integer::sum);
      });
    });
    modes.merge(Mode.READ_ONLY, 2, Integer::sum);

    assertThat(modes).containsExactlyInAnyOrderEntriesOf(
        Map.of(Mode.READ_ONLY, 14, Mode.APPROVAL, 3, Mode.OFF, 5));
  }

  @Test
  void toolNamesAreReservedPerFamilyAndOnlySqlIsShared() {
    Map<String, ToolDefinition> seen = new HashMap<>();
    for (ConnectorManifest m : byId.values()) {
      for (ToolDefinition t : m.tools()) {
        assertThat(t.name()).as(m.id()).matches("^[a-z][a-z0-9_]{0,63}$").startsWith(TOOL_PREFIX.get(m.id()));
        ToolDefinition earlier = seen.putIfAbsent(t.name(), t);
        if (earlier != null) {
          // The core merges same-named tools of PostgreSQL and MySQL only if they are equal (§5 step 4).
          assertThat(t.name()).startsWith("sql_");
          assertThat(t).isEqualTo(earlier);
        }
      }
    }
    assertThat(seen).hasSize(22);
  }

  @Test
  void toolDefinitionsKeepTheSafetyRules() {
    for (ConnectorManifest m : byId.values()) {
      for (ToolDefinition t : m.tools()) {
        String name = m.id() + " " + t.name();
        assertThat(t.annotations().readOnlyHint()).as(name).isNotNull();
        assertThat(t.annotations().destructiveHint()).as(name).isNotNull();
        assertThat(t.annotations().idempotentHint()).as(name).isNotNull();
        assertThat(t.annotations().openWorldHint()).as(name).isNotNull();
        assertThat(t.title()).as(name).isNotBlank().isNotEqualTo(t.name());
        assertThat(t.description()).as(name).isNotBlank();
        if (t.mutating()) {
          assertThat(t.outputSchema()).as(name + ": may answer with an approval handle").isNull();
          assertThat(t.annotations().readOnlyHint()).as(name).isFalse();
        }
        if (Boolean.TRUE.equals(t.annotations().destructiveHint())) {
          assertThat(t.readOnlyModeAllowed()).as(name).isFalse();
          assertThat(List.of(t.defaultMode(), t.prodDefaultMode())).as(name).doesNotContain(Mode.READ_ONLY);
        }
        JsonNode input = t.inputSchema();
        assertThat(input.path("type").asString()).as(name).isEqualTo("object");
        assertThat(input.path("additionalProperties").asBoolean(true)).as(name + " closed input").isFalse();
        assertThat(input.at("/properties/connection")).as(name + " connection placeholder")
            .isEqualTo(JsonNodeFactory.instance.objectNode().put("type", "string"));
      }
    }
  }

  @Test
  void connectionFormsKeepSecretsWriteOnly() {
    for (ConnectorManifest m : byId.values()) {
      List<String> secrets = new ArrayList<>();
      collectSecretProperties(m.configSchema(), "", secrets, m.id());
      assertThat(secrets).as(m.id() + " has secret fields").isNotEmpty();
    }
  }

  @Test
  void everySchemaIsValidJsonSchema2020() {
    SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    Schema metaSchema = registry.getSchema(SchemaLocation.of("https://json-schema.org/draft/2020-12/schema"));
    Map<String, JsonNode> schemas = new LinkedHashMap<>();
    for (ConnectorManifest m : byId.values()) {
      schemas.put(m.id() + " config", m.configSchema());
      if (m.policySchema() != null) {
        schemas.put(m.id() + " policy", m.policySchema());
      }
      for (ToolDefinition t : m.tools()) {
        schemas.put(m.id() + " " + t.name() + " input", t.inputSchema());
        if (t.outputSchema() != null) {
          schemas.put(m.id() + " " + t.name() + " output", t.outputSchema());
        }
      }
    }
    schemas.forEach((name, schema) -> {
      assertThat(metaSchema.validate(schema)).as(name).isEmpty();
      // Compiling resolves every $ref; a dangling one throws here rather than on a user's first call.
      SchemaLocation location = SchemaLocation.of("urn:mcp-gateway:test:" + name.replace(' ', ':'));
      Schema compiled = registry.getSchema(location, schema);
      compiled.initializeValidators();
      compiled.validate(JsonNodeFactory.instance.objectNode());
    });
    assertThat(schemas).hasSizeGreaterThan(40);
  }

  /**
   * The SSH form declares each sign-in method's fields in its own branch (the renderer shows them only for it): the
   * branch still requires them, a field of another method that an older form stored stays valid, and an unknown key
   * is still refused.
   */
  @Test
  void sshCredentialsAreCheckedByTheirMethod() {
    SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    Schema ssh = registry.getSchema(SchemaLocation.of("urn:mcp-gateway:test:ssh"), byId.get("ssh").configSchema());
    Function<String, Integer> errors = credentials -> ssh.validate(tools.jackson.databind.json.JsonMapper.builder()
        .build().readTree("{\"connection\":{\"host\":\"10.20.0.14\",\"username\":\"gw\"},\"credentials\":"
            + credentials + "}")).size();
    assertThat(errors.apply("{\"method\":\"vaultSshCa\",\"vault\":{\"store\":\"vault\"}}")).isZero();
    assertThat(errors.apply("{\"method\":\"vaultSshCa\"}")).as("the Vault is required").isPositive();
    assertThat(errors.apply("{\"method\":\"vaultSshCa\",\"vault\":{}}")).as("and its store").isPositive();
    assertThat(errors.apply("{\"method\":\"privateKey\",\"privateKey\":\"pem\",\"vault\":{\"mount\":"
        + "\"ssh-client-signer\"}}")).as("vault's defaults an older form stored").isZero();
    assertThat(errors.apply("{\"method\":\"password\"}")).as("the password is required").isPositive();
    assertThat(errors.apply("{\"method\":\"password\",\"password\":\"pw\",\"token\":\"x\"}"))
        .as("unknown keys are refused").isPositive();
  }

  /** Walks a schema; every property named like a credential must be writeOnly. Collects the secret paths found. */
  private static void collectSecretProperties(JsonNode schema, String path, List<String> found, String connector) {
    if (schema == null || !schema.isContainer()) {
      return;
    }
    JsonNode properties = schema.get("properties");
    if (properties != null && properties.isObject()) {
      for (var entry : properties.properties()) {
        String child = path + "/" + entry.getKey();
        if (SECRET_PROPERTIES.contains(entry.getKey()) && entry.getValue().isObject()
            && !entry.getValue().has("$ref") && !entry.getValue().has("const")) {
          assertThat(entry.getValue().path("writeOnly").asBoolean(false))
              .as(connector + " " + child + " must be writeOnly").isTrue();
          // x-gateway-secret on the field (value | pem | binary) or on its object (login: user + password).
          boolean kindNamed = entry.getValue().path("x-gateway-secret").isString()
              || schema.path("x-gateway-secret").isString();
          assertThat(kindNamed).as(connector + " " + child + " names its secret kind").isTrue();
          found.add(child);
        }
      }
    }
    for (JsonNode value : schema) {
      collectSecretProperties(value, path, found, connector);
    }
  }

  @SuppressWarnings("unchecked")
  private static <K, V> Map<K, V> orderedMap(Object... keysAndValues) {
    Map<K, V> map = new LinkedHashMap<>();
    for (int i = 0; i < keysAndValues.length; i += 2) {
      map.put((K) keysAndValues[i], (V) keysAndValues[i + 1]);
    }
    return map;
  }
}
