package ai.msrv.gaterook.e2e.endpoints;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.core.GatewayApplication;
import ai.msrv.gaterook.core.connections.ConnectionService;
import ai.msrv.gaterook.core.connections.SecretInput;
import ai.msrv.gaterook.core.endpoints.EndpointConnections;
import ai.msrv.gaterook.core.endpoints.EndpointService;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshot;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshotService;
import ai.msrv.gaterook.core.endpoints.EndpointTools;
import ai.msrv.gaterook.core.endpoints.RiskAnalyzer;
import ai.msrv.gaterook.core.endpoints.TokenService;
import ai.msrv.gaterook.core.endpoints.ToolCatalog;
import ai.msrv.gaterook.core.platform.ProblemException;
import ai.msrv.gaterook.core.platform.boot.GatewayLauncher;
import ai.msrv.gaterook.core.policy.PolicyService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The kit's demo world (design/BLUEPRINT.md §9) on the assembled core with the real built-in connectors: the twelve
 * connections, the endpoints {@code dev}, {@code stage-readonly} and {@code incident-prod} with the kit's modes, the
 * risk callouts (dev 3/3, stage-readonly 3/3, incident-prod contained), an endpoint with PostgreSQL and MySQL behind
 * one {@code sql_query}, and finally the deletion of {@code dev-db} and {@code dev}, after which the history still
 * names them (ARCHITECTURE.md §10, M1.4).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DemoWorldIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String LAUNCH_SECRET = Base64.getUrlEncoder().withoutPadding()
      .encodeToString("demo-world-it-launch-secret-0123".getBytes(StandardCharsets.US_ASCII));

  private static ConfigurableApplicationContext core;
  private static ConnectionService connections;
  private static EndpointService endpoints;
  private static EndpointConnections wiring;
  private static EndpointTools tools;
  private static EndpointSnapshotService snapshots;
  private static ToolCatalog catalog;
  private static JdbcTemplate jdbc;
  private static final Map<String, String> pending = new LinkedHashMap<>();

  @BeforeAll
  static void seedTheDemoWorld() {
    core = new SpringApplicationBuilder(GatewayApplication.class)
        .contextFactory(GatewayLauncher.contexts(true))
        .run("--gateway.home=" + newHome(), "--gateway.dev=true", "--gateway.dev.launch-secret=" + LAUNCH_SECRET,
            "--server.port=0", "--gateway.connections.health-interval=0s");
    connections = core.getBean(ConnectionService.class);
    endpoints = core.getBean(EndpointService.class);
    wiring = core.getBean(EndpointConnections.class);
    tools = core.getBean(EndpointTools.class);
    snapshots = core.getBean(EndpointSnapshotService.class);
    catalog = core.getBean(ToolCatalog.class);
    jdbc = new JdbcTemplate(core.getBean(javax.sql.DataSource.class));

    // Connections (BLUEPRINT §9 "Connections" and "Hosts"). Secret references and short-lived credentials arrive
    // with S1; here every secret is encrypted locally.
    connection("dev-db", "postgres", Env.DEV, """
        {"connection":{"host":"db.dev.internal","port":5432,"database":"app"},"credentials":{"user":"gw_reader"}}""",
        "/credentials/password");
    connection("stage-db", "postgres", Env.STAGE, """
        {"connection":{"host":"db.stage.internal","port":5432,"database":"app"},"credentials":{"user":"gw_reader"}}""",
        "/credentials/password");
    connection("prod-replica", "postgres", Env.PROD, """
        {"connection":{"host":"replica.prod.internal","port":5432,"database":"app"},
         "credentials":{"user":"gw_reader"}}""", "/credentials/password");
    connection("orders-mongo", "mongodb", Env.DEV, """
        {"connection":{"uri":"mongodb://mongo.dev.internal:27017/orders"},"credentials":{"user":"gw_reader"}}""",
        "/credentials/password");
    connection("cache-redis", "redis", Env.DEV, """
        {"connection":{"host":"cache.dev.internal","port":6379}}""", "/credentials/password");
    connection("dev-vm", "ssh", Env.DEV, """
        {"connection":{"host":"10.20.0.14","username":"gw"},"credentials":{"method":"password"}}""",
        "/credentials/password");
    connection("app-logs", "logs", Env.DEV, """
        {"backend":"loki","url":"http://loki.dev.internal:3100","auth":{"mode":"bearer"}}""", "/auth/secret");
    connection("prod-logs", "logs", Env.PROD, """
        {"backend":"gcp-logging","resourceNames":["projects/acme-prod"],
         "credentials":{"source":"adc","impersonate":"gw-reader@acme-prod.iam.gserviceaccount.com"}}""", null);
    connection("dev-cluster", "kubernetes", Env.DEV, """
        {"auth":"token","server":"https://k8s.dev.internal:6443","namespace":"app-dev"}""", "/token");
    connection("aws-stage", "aws", Env.STAGE, """
        {"regions":["eu-central-1"],"accountAlias":"acme-stage","credentials":{"source":"accessKey",
         "accessKeyId":"AKIAZ7GWSTAGEDEMO000","roleArn":"arn:aws:iam::123456789012:role/gw-readonly"}}""",
        "/credentials/secretAccessKey");
    connection("billing-api", "http", Env.STAGE, """
        {"baseUrls":["https://billing.stage.acme.dev"],"auth":{"type":"bearer"}}""", "/auth/token");
    connection("prod-vm", "ssh", Env.PROD, """
        {"connection":{"host":"10.40.0.21","username":"gw"},"credentials":{"method":"password"}}""",
        "/credentials/password");

    // dev: eight connections, then the two Setup changes of the day (ssh_exec was Approval, http_get Read only).
    endpoints.create("dev", "Day-to-day development", EndpointService.StartFrom.EMPTY, null);
    for (String c : List.of("dev-db", "orders-mongo", "cache-redis", "dev-vm", "app-logs", "dev-cluster", "aws-stage",
        "billing-api")) {
      wiring.add("dev", c, null);
    }
    for (String coreTool : List.of("list_connections", "describe_connection")) {
      EndpointSnapshot s = snapshots.bySlug("dev").orElseThrow();
      tools.setMode("dev", coreTool, null, Mode.READ_ONLY, null, s.coreTool(coreTool).orElseThrow().rowVersion(),
          null);
    }
    EndpointSnapshot.Pair httpGet = pair(snapshots.bySlug("dev").orElseThrow(), "http_get", "billing-api");
    tools.setMode("dev", httpGet.toolKey(), "billing-api", Mode.OFF, null, httpGet.rowVersion(),
        "Responses carried customer data");

    // stage-readonly: the three stage connections, read only.
    endpoints.create("stage-readonly", "Try stage from claude.ai", EndpointService.StartFrom.EMPTY, null);
    for (String c : List.of("stage-db", "aws-stage", "billing-api")) {
      wiring.add("stage-readonly", c, null);
    }
    endpoints.updateSettings("stage-readonly", new EndpointService.SettingsUpdate(null, "read-only", null, null, null,
        null, null), endpoints.get("stage-readonly").version(), "claude.ai only reads");

    // incident-prod: Start from "Incident response" picks the prod connections.
    endpoints.create("incident-prod", "On-call", EndpointService.StartFrom.INCIDENT_RESPONSE, null);

    // Tokens of dev and the three pending requests of 14:30.
    TokenService tokens = core.getBean(TokenService.class);
    tokens.issue("dev", "inspector-debug", 3, null);
    tokens.issue("dev", "ci-smoke", 1, null);
    tokens.revoke(tokens.issue("dev", "old-claude", 7, null).id(), "replaced by the bridge");
    pending.put("k8s_rollout_restart", pendingApproval("dev", "dev-cluster", "k8s_rollout_restart", "dev",
        "dev.kubernetes.write-approval"));
    pending.put("sql_execute", pendingApproval("dev", "dev-db", "sql_execute", "dev", "dev.sql.write-approval"));
    pending.put("http_request", pendingApproval("dev", "billing-api", "http_request", "stage",
        "stage.http.write-approval"));
    toolCall("dev", "sql_query", "dev-db", "dev", "allowed");
    toolCall("dev", "logs_search", "app-logs", "dev", "allowed");
    toolCall("incident-prod", "sql_query", "prod-replica", "prod", "denied");
  }

  @AfterAll
  static void stop() {
    if (core != null) {
      core.close();
    }
  }

  // ── The world as the kit shows it ───────────────────────────────────────────────────────────────────────────

  @Test
  @Order(1)
  void devHasTheKitsTwentyTwoTools() {
    EndpointService.Summary dev = endpoints.get("dev");
    assertThat(dev.url()).endsWith("/mcp/dev");
    assertThat(dev.connections()).isEqualTo(8);
    assertThat(dev.tools()).as("14 read only · 3 approval · 5 off").isEqualTo(new EndpointService.ToolCounts(14, 3,
        5));
    assertThat(dev.tokensActive()).isEqualTo(2);
    Map<String, Mode> modes = snapshots.bySlug("dev").orElseThrow().pairs().stream()
        .collect(Collectors.toMap(p -> p.connectionName() + " " + p.tool().name(), EndpointSnapshot.Pair::mode));
    assertThat(modes).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
        Map.entry("dev-db sql_list_schemas", Mode.READ_ONLY), Map.entry("dev-db sql_describe_table", Mode.READ_ONLY),
        Map.entry("dev-db sql_query", Mode.READ_ONLY), Map.entry("dev-db sql_execute", Mode.APPROVAL),
        Map.entry("orders-mongo mongo_find", Mode.READ_ONLY), Map.entry("orders-mongo mongo_aggregate",
            Mode.READ_ONLY),
        Map.entry("cache-redis redis_get", Mode.READ_ONLY), Map.entry("cache-redis redis_scan", Mode.READ_ONLY),
        Map.entry("dev-vm ssh_run", Mode.READ_ONLY), Map.entry("dev-vm ssh_exec", Mode.OFF),
        Map.entry("app-logs logs_search", Mode.READ_ONLY), Map.entry("app-logs logs_tail", Mode.OFF),
        Map.entry("dev-cluster k8s_get", Mode.READ_ONLY), Map.entry("dev-cluster k8s_logs", Mode.OFF),
        Map.entry("dev-cluster k8s_rollout_restart", Mode.APPROVAL), Map.entry("dev-cluster k8s_apply", Mode.OFF),
        Map.entry("aws-stage aws_describe", Mode.READ_ONLY), Map.entry("aws-stage aws_metrics", Mode.READ_ONLY),
        Map.entry("billing-api http_get", Mode.OFF), Map.entry("billing-api http_request", Mode.APPROVAL)));
    assertThat(jdbc.queryForObject("select count(*) from setup_change where what = 'mode' and endpoint_slug = 'dev' "
        + "and subject = 'http_get on billing-api, endpoint dev'", Integer.class)).isEqualTo(1);

    ToolCatalog.Catalog published = catalog.forEndpoint(snapshots.bySlug("dev").orElseThrow());
    assertThat(published.tools()).extracting(ToolCatalog.PublishedTool::name).contains("approval_status",
        "list_connections", "describe_connection", "sql_query", "k8s_rollout_restart").doesNotContain("http_get",
        "ssh_exec", "k8s_apply");
  }

  @Test
  @Order(2)
  void theRiskCalloutsSayWhatTheKitSays() {
    RiskAnalyzer.Report dev = endpoints.risk("dev");
    assertThat(dev.state()).isEqualTo("present");
    assertThat(dev.ingredients().count()).as("3/3").isEqualTo(3);
    assertThat(dev.sources()).extracting(RiskAnalyzer.Source::text).contains("sql_query reads dev-db",
        "http_request can send data to billing-api");

    RiskAnalyzer.Report stage = endpoints.risk("stage-readonly");
    assertThat(stage.state()).isEqualTo("present");
    assertThat(stage.ingredients().count()).as("3/3").isEqualTo(3);
    assertThat(stage.sources()).extracting(RiskAnalyzer.Source::text).contains("http_get can send data to "
        + "billing-api").doesNotContain("http_request can send data to billing-api");

    RiskAnalyzer.Report incident = endpoints.risk("incident-prod");
    assertThat(incident.state()).as("contained: the only way out is fixed runbooks").isEqualTo("limited");
    assertThat(incident.ingredients().externalChannel()).isEqualTo("limited");
    assertThat(incident.sources()).extracting(RiskAnalyzer.Source::text)
        .contains("ssh_run runs fixed runbooks on prod-vm");
  }

  @Test
  @Order(3)
  void stageReadonlyOnlyReads() {
    EndpointService.Summary stage = endpoints.get("stage-readonly");
    assertThat(stage.access()).isEqualTo("read-only");
    assertThat(stage.env()).isEqualTo(Env.STAGE);
    assertThat(stage.connectionsByEnv()).containsEntry(Env.STAGE, 3);
    EndpointSnapshot s = snapshots.bySlug("stage-readonly").orElseThrow();
    assertThat(mode(s, "sql_execute", "stage-db")).isEqualTo(Mode.OFF);
    assertThat(mode(s, "http_request", "billing-api")).isEqualTo(Mode.OFF);
    assertThat(mode(s, "http_get", "billing-api")).isEqualTo(Mode.READ_ONLY);
    assertThat(mode(s, "sql_query", "stage-db")).isEqualTo(Mode.READ_ONLY);
    EndpointSnapshot.Pair execute = pair(s, "sql_execute", "stage-db");
    assertThatThrownBy(() -> tools.setMode("stage-readonly", "sql_execute", "stage-db", Mode.APPROVAL, null,
        execute.rowVersion(), null)).isInstanceOf(ProblemException.class)
        .hasMessageContaining("stage-readonly is read only");
  }

  @Test
  @Order(4)
  void incidentProdIsProdOnlyAndKeepsMutationsOff() {
    EndpointService.Summary incident = endpoints.get("incident-prod");
    assertThat(incident.env()).isEqualTo(Env.PROD);
    assertThat(incident.connectionsByEnv()).containsExactly(Map.entry(Env.PROD, 3));
    EndpointSnapshot s = snapshots.bySlug("incident-prod").orElseThrow();
    assertThat(s.connections()).extracting(w -> w.config().name())
        .containsExactlyInAnyOrder("prod-replica", "prod-logs", "prod-vm");
    assertThat(mode(s, "sql_query", "prod-replica")).isEqualTo(Mode.READ_ONLY);
    assertThat(mode(s, "sql_execute", "prod-replica")).isEqualTo(Mode.OFF);
    assertThat(mode(s, "ssh_run", "prod-vm")).isEqualTo(Mode.READ_ONLY);
    assertThat(mode(s, "ssh_exec", "prod-vm")).isEqualTo(Mode.OFF);
    assertThat(wiring.preview("incident-prod", "dev-db").refusal()).isEqualTo("incident-prod accepts prod "
        + "connections only");
    assertThat(wiring.preview("dev", "prod-replica").refusal()).contains("goes only on a prod endpoint");

    // The prod policy of the kit: 100 rows, 3 s, no DDL, 10 min to approve, a comment to approve.
    PolicyService policies = core.getBean(PolicyService.class);
    JsonNode prod = policies.effective(Env.PROD);
    assertThat(prod.at("/sql/rowLimit").asInt()).isEqualTo(100);
    assertThat(prod.at("/sql/statementTimeoutMs").asInt()).isEqualTo(3000);
    assertThat(prod.at("/approvals/timeoutMinutes").asInt()).isEqualTo(10);
    assertThat(prod.at("/approvals/comment").asString()).isEqualTo("required");
    JsonNode dev = policies.effective(Env.DEV);
    assertThat(dev.at("/sql/rowLimit").asInt()).isEqualTo(1000);
    assertThat(dev.at("/approvals/timeoutMinutes").asInt()).isEqualTo(5);
    assertThat(policies.effective(Env.STAGE).at("/approvals/timeoutMinutes").asInt()).isEqualTo(10);
  }

  @Test
  @Order(5)
  void postgresAndMysqlAnswerOneSqlQuery() {
    connection("dev-mysql", "mysql", Env.DEV, """
        {"connection":{"host":"mysql.dev.internal","port":3306,"database":"shop"},
         "credentials":{"user":"gw_reader"}}""", "/credentials/password");
    endpoints.create("sql-mix", null, EndpointService.StartFrom.EMPTY, null);
    wiring.add("sql-mix", "dev-db", null);
    EndpointSnapshot s = wiring.add("sql-mix", "dev-mysql", null);
    ToolCatalog.Catalog list = catalog.forEndpoint(s);
    ToolCatalog.PublishedTool query = list.tool("sql_query").orElseThrow();
    assertThat(query.inputSchema().at("/properties/connection/enum")).extracting(JsonNode::asString)
        .containsExactly("dev-db", "dev-mysql");
    assertThat(query.description()).contains("dev-db (PostgreSQL, dev)").contains("dev-mysql (MySQL, dev)");
    assertThat(query.outputSchema()).isNotNull();
    assertThat(list.tool("sql_execute").orElseThrow().outputSchema()).as("Approval: no outputSchema").isNull();
    assertThat(catalog.resolve(list, "sql_query", "dev-mysql")).map(p -> p.manifest().id()).contains("mysql");
    assertThat(catalog.resolve(list, "sql_query", "dev-db")).map(p -> p.manifest().id()).contains("postgres");
    assertThat(list.tools().stream().filter(t -> t.name().equals("sql_query")).count()).isEqualTo(1);
  }

  @Test
  @Order(6)
  void prodRefusesWhatTheConnectorsForbidThere() {
    // Each built-in schema's "Prod is stricter" lock, on the real manifests: refused on prod, accepted elsewhere.
    assertForbiddenOnProd("aws", """
        {"regions":["eu-central-1"],"credentials":{"source":"accessKey","accessKeyId":"AKIAZ7GWPRODDEMO0000",
         "roleArn":"arn:aws:iam::123456789012:role/gw-readonly","sessionPolicy":"none"}}""",
        "/credentials/secretAccessKey", "/config/credentials/sessionPolicy", "sessionPolicy can't be none on prod.");
    assertForbiddenOnProd("http", """
        {"baseUrls":["https://billing.acme.dev"],"auth":{"type":"apiKeyQuery"}}""", "/auth/apiKey",
        "/config/auth/type", "type can't be apiKeyQuery on prod.");
    assertForbiddenOnProd("http", """
        {"baseUrls":["https://billing.acme.dev"],"auth":{"type":"bearer"},"advanced":{"followRedirects":"same-origin"}}""",
        "/auth/token", "/config/advanced/followRedirects", "followRedirects can't be same-origin on prod.");
    assertForbiddenOnProd("redis", """
        {"connection":{"host":"cache.prod.internal","port":6379,"tlsVerify":"ca"}}""", "/credentials/password",
        "/config/connection/tlsVerify", "TLS verification can't be ca on prod.");
    assertForbiddenOnProd("kubernetes", """
        {"auth":"token","server":"https://k8s.prod.internal:6443","namespace":"app","insecureSkipTlsVerify":true}""",
        "/token", "/config/insecureSkipTlsVerify", "Skip TLS verification isn't allowed on prod.");

    // Moving a connection to prod checks it against prod's locks.
    connection("aws-sandbox", "aws", Env.DEV, """
        {"regions":["eu-central-1"],"credentials":{"source":"accessKey","accessKeyId":"AKIAZ7GWSANDBOX00000",
         "roleArn":"arn:aws:iam::123456789012:role/gw-readonly","sessionPolicy":"none"}}""",
        "/credentials/secretAccessKey");
    ConnectionService.Detail sandbox = connections.get("aws-sandbox");
    assertThatThrownBy(() -> connections.update("aws-sandbox", Env.PROD, sandbox.config(), sandbox.version(),
        "promote", null)).isInstanceOfSatisfying(ProblemException.class, e -> {
          assertThat(e.kind()).isEqualTo(ProblemException.Kind.UNPROCESSABLE);
          assertThat(e.field()).isEqualTo("/config/credentials/sessionPolicy");
        });
    assertThat(connections.get("aws-sandbox").env()).isEqualTo(Env.DEV);
  }

  @Test
  @Order(7)
  void theBastionKeepsItsCredentialsAsSecrets() {
    // The bastion's credentials are the host's schema behind a $ref: their secret fields are secret fields too.
    connections.create("bastion-vm", "ssh", Env.DEV, JSON.readTree("""
        {"connection":{"host":"10.20.0.15","username":"gw"},"credentials":{"method":"password"},
         "jump":{"host":"bastion.dev.internal","username":"gw","credentials":{"method":"password"}}}"""),
        Map.of("/credentials/password", SecretInput.local("demo-host-Xq7".toCharArray()),
            "/jump/credentials/password", SecretInput.local("demo-bastion-Xq7".toCharArray())), null);
    ConnectionService.Detail vm = connections.get("bastion-vm");
    assertThat(vm.secrets()).extracting(ConnectionService.SecretField::field)
        .containsExactlyInAnyOrder("/credentials/password", "/jump/credentials/password");
    assertThat(vm.config().toString()).doesNotContain("demo-bastion-Xq7");
  }

  private static int locked;

  private static void assertForbiddenOnProd(String type, String config, String secretField, String field,
      String message) {
    String name = "locked-" + type + "-" + ++locked;
    assertThatThrownBy(() -> connection(name, type, Env.PROD, config, secretField))
        .as(type + " " + field).isInstanceOfSatisfying(ProblemException.class, e -> {
          assertThat(e.kind()).isEqualTo(ProblemException.Kind.UNPROCESSABLE);
          assertThat(e.field()).isEqualTo(field);
          assertThat(e.getMessage()).contains(message);
        });
    assertThat(connections.list()).extracting(ConnectionService.Summary::name).doesNotContain(name);
    connection(name, type, Env.STAGE, config, secretField);
  }

  // ── Deletion keeps the history ──────────────────────────────────────────────────────────────────────────────

  @Test
  @Order(10)
  void deletingDevDbKeepsItsNameInTheHistory() {
    ConnectionService.DeleteSummary deleted = connections.delete("dev-db", "dev-db", "retired");
    assertThat(deleted.stoppedTools()).contains("sql_query on dev", "sql_execute on dev", "sql_query on sql-mix");
    assertThat(deleted.rejectedApprovals()).isEqualTo(1);
    assertThat(deleted.secretsDestroyed()).containsExactly("dev-db/password");

    Map<String, Object> request = jdbc.queryForMap("select status, connection_id, connection_name, endpoint_slug, "
        + "decided_via, comment, error from approval where id = ?", pending.get("sql_execute"));
    assertThat(request).containsEntry("status", "rejected").containsEntry("connection_name", "dev-db")
        .containsEntry("endpoint_slug", "dev").containsEntry("decided_via", "core");
    assertThat(request.get("connection_id")).isNull();
    assertThat(request.get("comment")).as("the core is no person: no decision comment").isNull();
    assertThat(request.get("error")).asString().contains("dev-db");
    // The seeded call keeps its row; the core's rejection adds the resolved row of its request (M1.5).
    assertThat(jdbc.queryForList("select phase || ':' || decision || ':' || coalesce(decided_via, '-') from tool_call "
        + "where connection_name = 'dev-db' order by id", String.class))
        .containsExactly("completed:allowed:-", "resolved:rejected:core");
    assertThat(jdbc.queryForObject("select count(*) from setup_change where subject = 'Connection dev-db deleted'",
        Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from secret where name = 'dev-db/password'", Integer.class))
        .isZero();

    EndpointService.Summary dev = endpoints.get("dev");
    assertThat(dev.connections()).isEqualTo(7);
    assertThat(dev.tools()).as("dev-db's 3 read only and 1 approval are gone").isEqualTo(
        new EndpointService.ToolCounts(11, 2, 5));
    assertThat(endpoints.get("sql-mix").connections()).isEqualTo(1);
  }

  @Test
  @Order(11)
  void deletingDevKeepsItsSlugInTheHistory() {
    EndpointService.DeleteSummary deleted = endpoints.delete("dev", "dev", "rebuilt as dev-2");
    assertThat(deleted.tokensRevoked()).containsExactlyInAnyOrder("inspector-debug", "ci-smoke");
    assertThat(deleted.rejectedApprovals()).as("k8s_rollout_restart and http_request").isEqualTo(2);
    for (String tool : List.of("k8s_rollout_restart", "http_request")) {
      Map<String, Object> request = jdbc.queryForMap("select status, endpoint_id, endpoint_slug, connection_name "
          + "from approval where id = ?", pending.get(tool));
      assertThat(request).containsEntry("status", "rejected").containsEntry("endpoint_slug", "dev");
      assertThat(request.get("endpoint_id")).isNull();
    }
    // Two seeded calls, and a resolved row for each request the core rejected: dev-db's sql_execute before, these two.
    assertThat(jdbc.queryForObject("select count(*) from tool_call where endpoint_slug = 'dev'", Integer.class))
        .isEqualTo(5);
    assertThat(jdbc.queryForObject("select count(*) from tool_call where endpoint_slug = 'dev' and phase = 'resolved' "
        + "and decision = 'rejected' and decided_via = 'core'", Integer.class)).isEqualTo(3);
    assertThat(jdbc.queryForObject("select count(*) from setup_change where endpoint_slug = 'dev'", Integer.class))
        .as("created, two core tools, http_get, three tokens issued, one revoked, deleted").isGreaterThanOrEqualTo(4);
    assertThat(jdbc.queryForObject("select count(*) from api_token where name in ('inspector-debug', 'ci-smoke', "
        + "'old-claude')", Integer.class)).as("token rows go with the endpoint").isZero();
    assertThat(snapshots.bySlug("dev")).isEmpty();

    // The other endpoints and the connections keep working.
    assertThat(endpoints.get("stage-readonly").connections()).isEqualTo(3);
    assertThat(endpoints.risk("incident-prod").state()).isEqualTo("limited");
    assertThat(connections.list()).extracting(ConnectionService.Summary::name).contains("billing-api", "dev-cluster")
        .doesNotContain("dev-db");
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

  private static void connection(String name, String type, Env env, String config, String secretField) {
    Map<String, SecretInput> secrets = secretField == null ? Map.of()
        : Map.of(secretField, SecretInput.local(("demo-" + name + "-Xq7").toCharArray()));
    connections.create(name, type, env, JSON.readTree(config), secrets, null);
  }

  private static EndpointSnapshot.Pair pair(EndpointSnapshot s, String tool, String connection) {
    return s.pairs().stream().filter(p -> p.tool().name().equals(tool) && p.connectionName().equals(connection))
        .findFirst().orElseThrow(() -> new AssertionError("No " + tool + " on " + connection));
  }

  private static Mode mode(EndpointSnapshot s, String tool, String connection) {
    return pair(s, tool, connection).mode();
  }

  /** A request waiting for a person; the approvals module writes these from M1.5 on. */
  private static String pendingApproval(String slug, String connection, String tool, String env, String rule) {
    String id = "ap_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    long now = Instant.now().toEpochMilli();
    String endpointId = endpoints.get(slug).id();
    String connectionId = connections.get(connection).id();
    jdbc.update("insert into approval (id, call_id, endpoint_id, endpoint_slug, connection_id, connection_name, "
        + "tool, tool_key, env, principal_kind, principal_id, call_canonical, digest, action, action_sha256, rule_id, "
        + "comment_policy, status, expires_at, created_at, version) values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'BRIDGE', "
        + "'grant_codex', '{}', 'd', '{}', 'a', ?, 'optional', 'pending', ?, ?, 0)", id, "tc_" + id.substring(3),
        endpointId, slug, connectionId, connection, tool, tool, env, rule, now + 300_000, now);
    return id;
  }

  /** One logged call of an earlier day, as a row (the history the deletions must keep). */
  private static void toolCall(String slug, String tool, String connection, String env, String decision) {
    long now = Instant.now().toEpochMilli();
    jdbc.update("insert into tool_call (call_id, phase, created_at, hour_bucket, endpoint_slug, tool_name, tool_key, "
        + "connection_name, env, decision, prev_sha256, row_sha256) values (?, 'completed', ?, ?, ?, ?, ?, ?, ?, ?, ?, "
        + "?)", "tc_" + UUID.randomUUID(), now, now / 3_600_000, slug, tool, tool, connection, env, decision,
        "0".repeat(64), "0".repeat(64));
  }

  private static String newHome() {
    try {
      Path parent = Path.of("target", "gateway-homes").toAbsolutePath();
      Files.createDirectories(parent);
      return Files.createTempDirectory(parent, "demo-world-").toString();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
