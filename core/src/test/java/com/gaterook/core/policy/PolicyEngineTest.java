package com.gaterook.core.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.connector.api.Classification;
import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorManifest;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.ToolDefinition;
import com.gaterook.connector.api.ToolDefinition.Mode;
import com.gaterook.connector.api.ToolDefinition.Nature;
import com.gaterook.core.platform.MaskPolicy;
import com.gaterook.core.policy.Decision.Outcome;
import com.gaterook.testfixtures.FakeConnectors;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The truth table of ARCHITECTURE.md §5 step 7 over every combination the engine can meet, and the "only stricter
 * wins" merge of limits. The policy service is a stub that serves the schema defaults: what is under test is the
 * engine, not the storage.
 */
class PolicyEngineTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final ConnectorManifest SQL = FakeConnectors.sql("pgfake", "FakePG");

  /** Schema defaults per environment, with environments that "didn't verify" on demand. */
  static final class StubPolicies extends PolicyService {

    final PolicySchemas schemas;
    final ObjectNode explicit = JSON.createObjectNode();
    final Set<Env> tampered = EnumSet.noneOf(Env.class);

    StubPolicies() {
      this(SQL);
    }

    StubPolicies(ConnectorManifest... manifests) {
      super(null, null, null, null, null, null, null, null, JSON, null, null);
      schemas = PolicySchemas.forManifests(List.of(manifests), JSON);
    }

    @Override
    public PolicySchemas schemas() {
      return schemas;
    }

    @Override
    public ObjectNode effective(Env env) {
      ObjectNode out = JSON.createObjectNode();
      for (String section : schemas.names()) {
        out.set(section, schemas.defaults(section, env));
        for (var field : explicit.path(section).properties()) {
          ((ObjectNode) out.path(section)).set(field.getKey(), field.getValue());
        }
      }
      return out;
    }

    @Override
    public boolean verified(Env env) {
      return !tampered.contains(env);
    }
  }

  private final StubPolicies policies = new StubPolicies();
  private final PolicyEngine engine = new PolicyEngine(policies);

  private static ToolDefinition tool(Nature nature) {
    return ToolDefinition.builder(nature == Nature.MUTATION ? "sql_execute" : "sql_query")
        .nature(nature).defaultMode(Mode.APPROVAL, Mode.APPROVAL).build();
  }

  private static PairView pair(Env endpointEnv, boolean readOnly, Env connectionEnv, Nature nature, Mode mode,
      JsonNode endpointLimits, JsonNode overrides) {
    ToolDefinition t = tool(nature);
    return new PairView("ep_1", "ep", endpointEnv, readOnly, "cn_1", "db", connectionEnv, SQL, t, t.name(), mode,
        endpointLimits, overrides);
  }

  private static ConnectionConfig cfg(Env env, JsonNode config) {
    return new ConnectionConfig("cn_1", "db", "pgfake", env, 1, config == null ? JSON.createObjectNode() : config,
        Set.of(), JSON.createObjectNode());
  }

  private record Row(boolean verified, Env endpointEnv, boolean readOnly, Env connectionEnv, Nature nature, Mode mode,
      Classification classification) {

    /** The table as ARCHITECTURE.md states it, written out independently of the engine. */
    Outcome expected() {
      if (!verified) {
        return Outcome.DENY;
      }
      if (mode == Mode.OFF) {
        return Outcome.UNKNOWN_TOOL;
      }
      if (classification == null || classification.kind() == Classification.Kind.DENY) {
        return Outcome.DENY;
      }
      if ((endpointEnv == Env.PROD) != (connectionEnv == Env.PROD)) {
        return Outcome.DENY;
      }
      boolean mutation = classification.kind() == Classification.Kind.MUTATION || nature == Nature.MUTATION;
      if (readOnly && (mutation || mode == Mode.BYPASS)) {
        return Outcome.DENY;
      }
      if (mode == Mode.BYPASS) {
        return Outcome.ALLOW;
      }
      if (mutation) {
        return Outcome.APPROVAL;
      }
      return mode == Mode.APPROVAL ? Outcome.APPROVAL : Outcome.ALLOW;
    }

    String expectedRule() {
      if (!verified) {
        return "core.integrity";
      }
      if (mode == Mode.OFF) {
        return null;
      }
      if (classification == null) {
        return "core.classify-error";
      }
      if (classification.kind() == Classification.Kind.DENY) {
        return classification.ruleId();
      }
      if ((endpointEnv == Env.PROD) != (connectionEnv == Env.PROD)) {
        return "core.prod-isolation";
      }
      boolean mutation = classification.kind() == Classification.Kind.MUTATION || nature == Nature.MUTATION;
      if (readOnly && (mutation || mode == Mode.BYPASS)) {
        return endpointEnv.id() + ".endpoint.read-only";
      }
      if (mode == Mode.BYPASS) {
        return "core.bypass";
      }
      if (mutation) {
        return connectionEnv.id() + ".sql.write-approval";
      }
      return mode == Mode.APPROVAL ? connectionEnv.id() + ".sql.approval-mode" : null;
    }
  }

  private static List<Row> everyRow() {
    List<Classification> classifications = new ArrayList<>();
    classifications.add(Classification.read());
    classifications.add(Classification.mutation("UPDATE"));
    classifications.add(Classification.deny("x.sql.block_ddl", "DDL is blocked", "Use a migration"));
    classifications.add(null);
    List<Row> rows = new ArrayList<>();
    for (boolean verified : new boolean[] {true, false}) {
      for (Env endpointEnv : List.of(Env.DEV, Env.STAGE, Env.PROD)) {
        for (Env connectionEnv : List.of(Env.DEV, Env.STAGE, Env.PROD)) {
          for (boolean readOnly : new boolean[] {false, true}) {
            for (Nature nature : Nature.values()) {
              for (Mode mode : Mode.values()) {
                for (Classification c : classifications) {
                  rows.add(new Row(verified, endpointEnv, readOnly, connectionEnv, nature, mode, c));
                }
              }
            }
          }
        }
      }
    }
    return rows;
  }

  @Test
  void everyCombinationDecidesAsTheTableSays() {
    List<Row> rows = everyRow();
    assertThat(rows).hasSize(2 * 3 * 3 * 2 * 3 * 4 * 4);
    for (Row r : rows) {
      policies.tampered.clear();
      if (!r.verified()) {
        policies.tampered.add(r.connectionEnv());
      }
      Decision d = engine.decide(pair(r.endpointEnv(), r.readOnly(), r.connectionEnv(), r.nature(), r.mode(), null,
          null), cfg(r.connectionEnv(), null), r.classification());
      assertThat(d.outcome()).as("%s", r).isEqualTo(r.expected());
      assertThat(d.ruleId()).as("rule of %s", r).isEqualTo(r.expectedRule());
      assertThat(d.limits()).as("limits of %s", r).isNotNull();
    }
  }

  @Test
  void theInvariantsHoldWithoutReadingTheTable() {
    for (Row r : everyRow()) {
      policies.tampered.clear();
      if (!r.verified()) {
        policies.tampered.add(r.connectionEnv());
      }
      Decision d = engine.decide(pair(r.endpointEnv(), r.readOnly(), r.connectionEnv(), r.nature(), r.mode(), null,
          null), cfg(r.connectionEnv(), null), r.classification());
      boolean mutation = r.nature() == Nature.MUTATION || r.classification() != null
          && r.classification().kind() == Classification.Kind.MUTATION;
      if (mutation && r.mode() != Mode.BYPASS) {
        assertThat(d.outcome()).as("a mutation needs approval unless Bypass was explicitly enabled: %s", r)
            .isNotEqualTo(Outcome.ALLOW);
      }
      if (r.readOnly()) {
        assertThat(d.outcome() == Outcome.APPROVAL && mutation).as("a read-only endpoint never asks: %s", r)
            .isFalse();
      }
      if (d.outcome() == Outcome.ALLOW) {
        assertThat(r.mode()).isIn(Mode.READ_ONLY, Mode.BYPASS);
        assertThat(r.verified()).isTrue();
      }
    }
  }

  @Test
  void bypassKeepsTheSameNarrowedLimitsAndRateBudgets() {
    JsonNode limits = JSON.readTree("{\"rowLimit\":12,\"statementTimeoutMs\":500}");
    ConnectionConfig connection = cfg(Env.DEV, null);
    Decision approval = engine.decide(pair(Env.DEV, false, Env.DEV, Nature.MUTATION, Mode.APPROVAL, limits,
        null), connection, Classification.mutation("UPDATE"));
    Decision bypass = engine.decide(pair(Env.DEV, false, Env.DEV, Nature.MUTATION, Mode.BYPASS, limits, null),
        connection, Classification.mutation("UPDATE"));
    assertThat(approval.outcome()).isEqualTo(Outcome.APPROVAL);
    assertThat(bypass.outcome()).isEqualTo(Outcome.ALLOW);
    assertThat(bypass.limits()).isEqualTo(approval.limits());
    assertThat(bypass.limits().maxRows()).isEqualTo(12);
    assertThat(bypass.ratePerToken()).isEqualTo(approval.ratePerToken());
    assertThat(bypass.ratePerTool()).isEqualTo(approval.ratePerTool());
  }

  @Test
  void anEnvironmentThatDidNotVerifyDeniesItsEndpointsToo() {
    policies.tampered.add(Env.PROD);
    Decision d = engine.decide(pair(Env.PROD, false, Env.PROD, Nature.READ, Mode.READ_ONLY, null, null),
        cfg(Env.PROD, null), Classification.read());
    assertThat(d.outcome()).isEqualTo(Outcome.DENY);
    assertThat(d.reason()).contains("changed outside GateRook");
    policies.tampered.clear();
    policies.tampered.add(Env.STAGE);
    // The endpoint's environment matters as well: stage endpoint, stage policy tampered, dev connection.
    assertThat(engine.decide(pair(Env.STAGE, false, Env.DEV, Nature.READ, Mode.READ_ONLY, null, null),
        cfg(Env.DEV, null), Classification.read()).ruleId()).isEqualTo("core.integrity");
  }

  @Test
  void limitsComeFromTheConnectionsEnvironment() {
    Decision dev = engine.decide(pair(Env.DEV, false, Env.DEV, Nature.READ, Mode.READ_ONLY, null, null),
        cfg(Env.DEV, null), Classification.read());
    assertThat(dev.limits().maxRows()).isEqualTo(1_000);
    assertThat(dev.limits().statementTimeout()).isEqualTo(Duration.ofSeconds(5));
    assertThat(dev.limits().toolTimeout()).isEqualTo(Duration.ofSeconds(30));
    assertThat(dev.limits().maxLines()).isEqualTo(1_000);
    assertThat(dev.limits().maxResponseBytes()).isEqualTo(512 * 1024);
    assertThat(dev.ratePerToken()).isEqualTo(120);
    assertThat(dev.ratePerTool()).isEqualTo(30);

    Decision prod = engine.decide(pair(Env.PROD, false, Env.PROD, Nature.READ, Mode.READ_ONLY, null, null),
        cfg(Env.PROD, null), Classification.read());
    assertThat(prod.limits().maxRows()).isEqualTo(100);
    assertThat(prod.limits().statementTimeout()).isEqualTo(Duration.ofSeconds(3));
    assertThat(prod.limits().toolTimeout()).isEqualTo(Duration.ofSeconds(5));
    assertThat(prod.limits().maxLines()).isEqualTo(200);
    assertThat(prod.limits().maxResponseBytes()).isEqualTo(256 * 1024);
    assertThat(prod.ratePerToken()).isEqualTo(60);
    assertThat(prod.ratePerTool()).isEqualTo(20);
    assertThat(prod.view().section().path("denyDdl").asBoolean()).isTrue();
    assertThat(prod.view().rulePrefix()).isEqualTo("prod.sql");
  }

  @Test
  void httpRequestTimeoutOnlyCapsConnectorsUsingTheHttpPolicySection() {
    ConnectorManifest http = timeoutManifest("http", "http");
    ConnectorManifest httpPlugin = timeoutManifest("custom-http", "http");
    ConnectorManifest sshLogs = timeoutManifest("ssh-logs", "ssh");
    StubPolicies configured = new StubPolicies(SQL, http, httpPlugin, sshLogs);
    configured.explicit.putObject("limits").put("toolTimeoutSeconds", 30);
    PolicyEngine scoped = new PolicyEngine(configured);

    assertThat(configured.limits(Env.DEV).toolTimeout()).isEqualTo(Duration.ofSeconds(30));
    for (ConnectorManifest manifest : List.of(SQL, http, httpPlugin, sshLogs)) {
      PairView pair = timeoutPair(manifest, null, null);
      Duration expected = Duration.ofSeconds(manifest == http || manifest == httpPlugin ? 10 : 30);
      assertThat(configured.limits(Env.DEV, manifest.id()).toolTimeout()).as(manifest.id()).isEqualTo(expected);
      assertThat(scoped.view(pair, null).limits().toolTimeout()).as(manifest.id()).isEqualTo(expected);
      assertThat(scoped.decide(pair, null, Classification.read()).limits().toolTimeout())
          .as(manifest.id()).isEqualTo(expected);
    }
  }

  @Test
  void scopedTimeoutsStillRespectEndpointAndToolCapsWithoutRelaxingHttp() {
    ConnectorManifest http = timeoutManifest("http", "http");
    ConnectorManifest sshLogs = timeoutManifest("ssh-logs", "ssh");
    StubPolicies configured = new StubPolicies(http, sshLogs);
    configured.explicit.putObject("limits").put("toolTimeoutSeconds", 30);
    PolicyEngine scoped = new PolicyEngine(configured);
    JsonNode endpoint = JSON.createObjectNode().put("toolTimeoutSeconds", 25);
    JsonNode pairLimit = JSON.createObjectNode().put("toolTimeoutSeconds", 20);
    assertThat(scoped.view(timeoutPair(sshLogs, endpoint, pairLimit), null).limits().toolTimeout())
        .isEqualTo(Duration.ofSeconds(20));
    assertThat(scoped.view(timeoutPair(http, endpoint, pairLimit), null).limits().toolTimeout())
        .isEqualTo(Duration.ofSeconds(10));
    JsonNode stricter = JSON.createObjectNode().put("toolTimeoutSeconds", 4);
    for (ConnectorManifest manifest : List.of(http, sshLogs)) {
      assertThat(scoped.view(timeoutPair(manifest, endpoint, stricter), null).limits().toolTimeout())
          .as(manifest.id()).isEqualTo(Duration.ofSeconds(4));
    }
  }

  /**
   * A person who raised Limits › Max response size for OpenSearch on prod still had its results cut by the SQL and
   * HTTP sections' sizes. Each section now caps only its own connectors; endpoint caps still narrow every one.
   */
  @Test
  void sqlAndHttpResponseSizesOnlyCapTheirOwnConnectors() {
    ConnectorManifest sql = responseManifest("pgfake", "sql");
    ConnectorManifest http = responseManifest("http", "http");
    ConnectorManifest openSearch = responseManifest("opensearch", "logs");
    StubPolicies configured = new StubPolicies(sql, http, openSearch);
    PolicyEngine scoped = new PolicyEngine(configured);

    // The sections' 1 MB defaults don't widen anything: Limits (prod 256 KB, dev 512 KB) is the stricter one.
    for (ConnectorManifest m : List.of(sql, http, openSearch)) {
      assertThat(scoped.view(responsePair(m, Env.PROD, null), null).limits().maxResponseBytes()).as(m.id())
          .isEqualTo(256 * 1024);
      assertThat(scoped.view(responsePair(m, Env.DEV, null), null).limits().maxResponseBytes()).as(m.id())
          .isEqualTo(512 * 1024);
    }

    configured.explicit.putObject("limits").put("maxResponseKb", 2_048);
    configured.explicit.putObject("http").put("maxResponseKb", 256);
    assertThat(scoped.view(responsePair(openSearch, Env.PROD, null), null).limits().maxResponseBytes())
        .isEqualTo(2_048 * 1024);
    assertThat(configured.limits(Env.PROD, openSearch.id()).maxResponseBytes()).isEqualTo(2_048 * 1024);
    assertThat(configured.limits(Env.PROD).maxResponseBytes()).isEqualTo(2_048 * 1024);
    assertThat(scoped.view(responsePair(sql, Env.PROD, null), null).limits().maxResponseBytes())
        .isEqualTo(1_024 * 1024);
    assertThat(scoped.view(responsePair(http, Env.PROD, null), null).limits().maxResponseBytes())
        .isEqualTo(256 * 1024);

    JsonNode endpoint = JSON.createObjectNode().put("maxResponseKb", 128);
    for (ConnectorManifest m : List.of(sql, http, openSearch)) {
      assertThat(scoped.view(responsePair(m, Env.PROD, endpoint), null).limits().maxResponseBytes()).as(m.id())
          .isEqualTo(128 * 1024);
    }
  }

  @Test
  void optionalLogRangesCanBeNarrowedByConnectionEndpointAndTool() {
    ObjectNode schema = JSON.createObjectNode().put("type", "object").put("x-policy-section", "logs");
    schema.putObject("properties").putObject("maxRangeHours").put("type", "integer").put("minimum", 0)
        .put("default", 0).put("x-stricter", "min-positive");
    ObjectNode configSchema = JSON.createObjectNode().put("type", "object");
    configSchema.putObject("properties").putObject("maxRangeHours").put("type", "integer")
        .put("x-stricter", "min-positive");
    ConnectorManifest logs = ConnectorManifest.builder("logs", "1.0.0", ConnectorManifest.Kind.BUILT_IN)
        .policySchema(schema).configSchema(configSchema).build();
    StubPolicies configured = new StubPolicies(logs);
    PolicyEngine scoped = new PolicyEngine(configured);
    for (Env env : Env.values()) {
      assertThat(configured.limits(env).maxRange()).as(env.id()).isNull();
    }
    assertThat(scoped.view(timeoutPair(logs, null, null), null).limits().maxRange()).isNull();

    JsonNode unlimited = JSON.createObjectNode().put("maxRangeHours", 0);
    JsonNode day = JSON.createObjectNode().put("maxRangeHours", 24);
    JsonNode halfDay = JSON.createObjectNode().put("maxRangeHours", 12);
    ConnectionConfig connection = new ConnectionConfig("cn_1", "resource", "logs", Env.DEV, 1,
        JSON.createObjectNode().put("maxRangeHours", 72), Set.of(), null);
    assertThat(scoped.view(timeoutPair(logs, null, unlimited), connection).limits().maxRange())
        .isEqualTo(Duration.ofHours(72));
    assertThat(scoped.view(timeoutPair(logs, day, unlimited), connection).limits().maxRange())
        .isEqualTo(Duration.ofHours(24));
    assertThat(scoped.view(timeoutPair(logs, day, halfDay), connection).limits().maxRange())
        .isEqualTo(Duration.ofHours(12));

    configured.explicit.putObject("logs").put("maxRangeHours", 6);
    assertThat(scoped.view(timeoutPair(logs, unlimited, unlimited), connection).limits().maxRange())
        .isEqualTo(Duration.ofHours(6));
  }

  private static ConnectorManifest timeoutManifest(String id, String section) {
    ObjectNode schema = JSON.createObjectNode().put("type", "object").put("x-policy-section", section);
    ObjectNode properties = schema.putObject("properties");
    if (section.equals("http")) {
      properties.putObject("toolTimeoutSeconds").put("type", "integer").put("default", 10)
          .put("x-stricter", "min");
    }
    return ConnectorManifest.builder(id, "1.0.0", ConnectorManifest.Kind.BUILT_IN).policySchema(schema).build();
  }

  private static PairView timeoutPair(ConnectorManifest manifest, JsonNode endpointLimits, JsonNode overrides) {
    ToolDefinition tool = ToolDefinition.builder("read").nature(Nature.READ).build();
    return new PairView("ep_1", "ep", Env.DEV, false, "cn_1", "resource", Env.DEV, manifest, tool,
        tool.name(), Mode.READ_ONLY, endpointLimits, overrides);
  }

  /** A section like the SQL and HTTP ones (their own 1 MB response size), or one without a size (logs). */
  private static ConnectorManifest responseManifest(String id, String section) {
    ObjectNode schema = JSON.createObjectNode().put("type", "object").put("x-policy-section", section);
    ObjectNode properties = schema.putObject("properties");
    if (!section.equals("logs")) {
      properties.putObject("maxResponseKb").put("type", "integer").put("default", 1_024).put("x-stricter", "min");
    }
    return ConnectorManifest.builder(id, "1.0.0", ConnectorManifest.Kind.BUILT_IN).policySchema(schema).build();
  }

  private static PairView responsePair(ConnectorManifest manifest, Env env, JsonNode endpointLimits) {
    ToolDefinition tool = ToolDefinition.builder("read").nature(Nature.READ).build();
    return new PairView("ep_1", "ep", env, false, "cn_1", "resource", env, manifest, tool, tool.name(),
        Mode.READ_ONLY, endpointLimits, null);
  }

  @Test
  void onlyStricterWinsFromConnectionEndpointAndPair() {
    JsonNode connection = JSON.readTree("{\"advanced\":{\"rowLimit\":50,\"statementTimeoutMs\":60000}}");
    JsonNode endpoint = JSON.readTree("{\"rowLimit\":2000,\"toolTimeoutSeconds\":4,\"denyDdl\":false,"
        + "\"allowlist\":[\"orders\",\"customers\"],\"readOnlyTransactions\":false}");
    JsonNode pairOverrides = JSON.readTree("{\"rowLimit\":20,\"allowlist\":[\"orders\",\"invoices\"],"
        + "\"deniedFunctions\":[\"pg_sleep\"]}");
    PairView p = pair(Env.PROD, false, Env.PROD, Nature.READ, Mode.READ_ONLY, endpoint, pairOverrides);
    ObjectNode merged = engine.merged(p, cfg(Env.PROD, connection));
    JsonNode sql = merged.path("sql");
    assertThat(sql.path("rowLimit").asInt()).as("min(prod 100, connection 50, endpoint 2000, pair 20)").isEqualTo(20);
    assertThat(sql.path("statementTimeoutMs").asInt()).as("60 s is looser than prod's 3 s").isEqualTo(3000);
    assertThat(sql.path("denyDdl").asBoolean()).as("or: the endpoint can't switch the protection off").isTrue();
    assertThat(sql.path("readOnlyTransactions").asBoolean()).as("no x-stricter: can't be narrowed").isTrue();
    assertThat(sql.path("allowlist")).extracting(JsonNode::asString).containsExactly("orders");
    assertThat(sql.path("deniedFunctions")).extracting(JsonNode::asString).containsExactly("pg_sleep");
    assertThat(merged.path("limits").path("toolTimeoutSeconds").asInt()).isEqualTo(4);

    Decision d = engine.decide(p, cfg(Env.PROD, connection), Classification.read());
    assertThat(d.limits().maxRows()).isEqualTo(20);
    assertThat(d.limits().toolTimeout()).isEqualTo(Duration.ofSeconds(4));
    assertThat(engine.view(p, cfg(Env.PROD, connection)).section().path("rowLimit").asInt()).isEqualTo(20);
  }

  @Test
  void looserValuesNeverWidenAnything() {
    JsonNode loose = JSON.readTree("{\"rowLimit\":100000,\"statementTimeoutMs\":600000,\"denyDdl\":false,"
        + "\"rateLimitPerToken\":100000,\"maxResponseKb\":100000}");
    PairView p = pair(Env.STAGE, false, Env.STAGE, Nature.READ, Mode.READ_ONLY, loose, loose);
    Decision d = engine.decide(p, cfg(Env.STAGE, JSON.readTree("{\"advanced\":{\"rowLimit\":100000}}")),
        Classification.read());
    assertThat(d.limits().maxRows()).isEqualTo(1_000);
    assertThat(d.limits().statementTimeout()).isEqualTo(Duration.ofSeconds(5));
    assertThat(d.limits().maxResponseBytes()).isEqualTo(512 * 1024);
    assertThat(d.ratePerToken()).isEqualTo(120);
    assertThat(d.view().section().path("denyDdl").asBoolean()).as("stage default").isTrue();
  }

  @Test
  void endpointAndPairMaskingNarrowsTheResultsOfTheCall() {
    JsonNode endpoint = JSON.readTree("{\"pii\":[\"email\"],\"fields\":[\"users.ssn\"],\"rowLimit\":10}");
    JsonNode pairOverrides = JSON.readTree("{\"pii\":[\"phone\"],\"fields\":[\"*.token\"],\"entropy\":false}");
    PairView p = pair(Env.DEV, false, Env.DEV, Nature.READ, Mode.READ_ONLY, endpoint, pairOverrides);

    Decision d = engine.decide(p, cfg(Env.DEV, null), Classification.read());
    assertThat(d.mask().pii()).containsExactlyInAnyOrder(MaskPolicy.Pii.EMAIL, MaskPolicy.Pii.PHONE);
    assertThat(d.mask().fields()).containsExactlyInAnyOrder("users.ssn", "*.token");
    assertThat(d.mask().entropy()).as("or: an override can't switch entropy masking off").isTrue();
    assertThat(engine.maskPolicy(p, cfg(Env.DEV, null))).isEqualTo(d.mask());
    assertThat(engine.decide(p.withMode(Mode.OFF), cfg(Env.DEV, null), Classification.read()).mask())
        .as("every outcome carries the masking").isEqualTo(d.mask());

    PairView plain = pair(Env.DEV, false, Env.DEV, Nature.READ, Mode.READ_ONLY, null, null);
    assertThat(engine.decide(plain, cfg(Env.DEV, null), Classification.read()).mask())
        .as("without overrides: the environment's").isEqualTo(MaskPolicy.SECRETS_ONLY);
    PairView prod = pair(Env.PROD, false, Env.PROD, Nature.READ, Mode.READ_ONLY,
        JSON.readTree("{\"pii\":[]}"), null);
    assertThat(engine.decide(prod, cfg(Env.PROD, null), Classification.read()).mask().pii())
        .as("union: an empty list never removes prod's kinds").containsExactlyInAnyOrder(MaskPolicy.Pii.values());
  }

  /**
   * Three list keys, one per meaning of an empty list (x-empty), as the built-in HTTP, Kubernetes and SQL sections
   * declare them. Endpoint limits and tool overrides may narrow each of them, never reach past what the environment's
   * value allows under that meaning.
   */
  @Test
  void listOverridesNeverAllowMoreThanTheEnvironmentsListAllows() {
    ObjectNode schema = JSON.createObjectNode().put("type", "object").put("x-policy-section", "lists");
    ObjectNode properties = schema.putObject("properties");
    properties.putObject("baseUrls").put("type", "array").put("x-stricter", "intersect").put("x-empty", "none")
        .putArray("default");
    properties.putObject("namespaces").put("type", "array").put("x-stricter", "intersect")
        .put("x-empty", "connector-default").putArray("default");
    properties.putObject("tables").put("type", "array").put("x-stricter", "intersect").putArray("default");
    ConnectorManifest lists = ConnectorManifest.builder("lists", "1.0.0", ConnectorManifest.Kind.BUILT_IN)
        .policySchema(schema).build();
    StubPolicies configured = new StubPolicies(lists);
    PolicyEngine scoped = new PolicyEngine(configured);
    JsonNode endpoint = JSON.readTree("{\"baseUrls\":[\"https://api.example.com/\"],\"namespaces\":[\"kube-system\"],"
        + "\"tables\":[\"orders\",\"invoices\"]}");
    JsonNode tool = JSON.readTree("{\"tables\":[\"orders\"],\"namespaces\":[\"kube-system\",\"web\"]}");

    JsonNode section = scoped.view(timeoutPair(lists, endpoint, tool), null).section();
    assertThat(section.path("baseUrls")).as("empty allows nothing, and no override changes that").isEmpty();
    assertThat(section.path("namespaces")).extracting(JsonNode::asString).as("bounded by the connector's default")
        .containsExactly("kube-system", "\u0000within-default");
    assertThat(section.path("tables")).extracting(JsonNode::asString).as("empty restricts nothing")
        .containsExactly("orders");

    configured.explicit.putObject("lists").putArray("baseUrls").add("https://api.example.com/")
        .add("https://other.example.com/");
    ((ObjectNode) configured.explicit.path("lists")).putArray("namespaces").add("web").add("kube-system");
    section = scoped.view(timeoutPair(lists, endpoint, tool), null).section();
    assertThat(section.path("baseUrls")).extracting(JsonNode::asString).containsExactly("https://api.example.com/");
    assertThat(section.path("namespaces")).extracting(JsonNode::asString).as("an explicit list narrows as before")
        .containsExactly("kube-system");
    assertThat(scoped.view(timeoutPair(lists, null, JSON.readTree("{\"baseUrls\":[]}")), null).section()
        .path("baseUrls")).as("an empty override closes what the environment opened").isEmpty();
  }

  @Test
  void theConfigFieldsThatNarrowAreFoundByTheirSchema() {
    assertThat(PolicyEngine.stricterFields(SQL.configSchema())).extracting(f -> f[0] + "=" + f[1])
        .containsExactlyInAnyOrder("/advanced/statementTimeoutMs=statementTimeoutMs", "/advanced/rowLimit=rowLimit");
  }
}
