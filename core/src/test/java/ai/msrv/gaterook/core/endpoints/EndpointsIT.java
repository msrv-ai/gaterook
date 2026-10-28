package ai.msrv.gaterook.core.endpoints;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.EndpointView;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.core.connections.ConnectionService;
import ai.msrv.gaterook.core.connectors.ConnectorRegistry;
import ai.msrv.gaterook.core.endpoints.internal.EndpointRows;
import ai.msrv.gaterook.core.endpoints.internal.ToolPairs;
import ai.msrv.gaterook.core.endpoints.internal.persistence.ApiTokenEntity;
import ai.msrv.gaterook.core.endpoints.internal.persistence.ApiTokenRepository;
import ai.msrv.gaterook.core.endpoints.internal.persistence.ClientGrantEntity;
import ai.msrv.gaterook.core.endpoints.internal.persistence.ClientGrantRepository;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointRepository;
import ai.msrv.gaterook.core.platform.Authenticator;
import ai.msrv.gaterook.core.platform.Caller;
import ai.msrv.gaterook.core.platform.CallerAuthenticator;
import ai.msrv.gaterook.core.invocation.ToolGuard;
import ai.msrv.gaterook.core.platform.Ingress;
import ai.msrv.gaterook.core.platform.IntegrityAlerts;
import ai.msrv.gaterook.core.platform.ProblemException;
import ai.msrv.gaterook.core.policy.Decision;
import ai.msrv.gaterook.core.policy.PolicyEngine;
import ai.msrv.gaterook.core.policy.PolicyService;
import ai.msrv.gaterook.core.templates.TemplateService;
import ai.msrv.gaterook.testfixtures.FakeConnectors;
import ai.msrv.gaterook.testfixtures.TestCore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Endpoints on a real core with the fake connectors (ARCHITECTURE.md M1.4): Start from, derived env and prod
 * isolation, tool modes and their locks, the published tool list (same-named tools of two types merge), risk, tokens,
 * split, deletion with history, rows edited outside the core.
 */
class EndpointsIT {

  private static TestCore core;
  private static EndpointService endpoints;
  private static EndpointConnections wiring;
  private static EndpointTools tools;
  private static EndpointSnapshotService snapshots;
  private static ToolCatalog catalog;

  @BeforeAll
  static void start() {
    core = TestCore.start("endpoints");
    endpoints = core.bean(EndpointService.class);
    wiring = core.bean(EndpointConnections.class);
    tools = core.bean(EndpointTools.class);
    snapshots = core.bean(EndpointSnapshotService.class);
    catalog = core.bean(ToolCatalog.class);
  }

  @AfterAll
  static void stop() {
    if (core != null) {
      core.close();
    }
  }

  private static Mode mode(EndpointSnapshot s, String tool, String connection) {
    return s.pairs().stream().filter(p -> p.tool().name().equals(tool) && p.connectionName().equals(connection))
        .findFirst().orElseThrow().mode();
  }

  @Test
  void anEmptyEndpointTakesTheEnvOfItsFirstConnectionAndProdNeverMixes() {
    EndpointService.Summary created = endpoints.create("empty-ep", "for tests", EndpointService.StartFrom.EMPTY, null);
    assertThat(created.env()).isEqualTo(Env.DEV);
    assertThat(created.connections()).isZero();
    assertThat(created.tools()).isEqualTo(new EndpointService.ToolCounts(0, 0, 2));
    assertThat(created.url()).endsWith("/mcp/empty-ep");

    core.connection("e1-prod", "pgfake", Env.PROD, "db.test");
    core.connection("e1-dev", "pgfake", Env.DEV, "db.test");
    EndpointSnapshot s = wiring.add("empty-ep", "e1-prod", null);
    assertThat(s.env()).isEqualTo(Env.PROD);
    assertThat(mode(s, "sql_query", "e1-prod")).isEqualTo(Mode.READ_ONLY);
    EndpointConnections.Preview preview = wiring.preview("empty-ep", "e1-dev");
    assertThat(preview.refusal()).isEqualTo("empty-ep accepts prod connections only");
    assertThatThrownBy(() -> wiring.add("empty-ep", "e1-dev", null)).isInstanceOf(ProblemException.class)
        .hasMessageContaining("prod connections only");

    endpoints.create("dev-only", null, EndpointService.StartFrom.EMPTY, null);
    wiring.add("dev-only", "e1-dev", null);
    assertThat(wiring.preview("dev-only", "e1-prod").refusal()).contains("goes only on a prod endpoint");

    assertThatThrownBy(() -> endpoints.create("Bad_Slug", null, EndpointService.StartFrom.EMPTY, null))
        .isInstanceOf(ProblemException.class);
    assertThatThrownBy(() -> endpoints.create("dev-only", null, EndpointService.StartFrom.EMPTY, null))
        .isInstanceOfSatisfying(ProblemException.class,
            e -> assertThat(e.kind()).isEqualTo(ProblemException.Kind.CONFLICT));
  }

  @Test
  void postgresAndMysqlShareOneSqlQuery() {
    core.connection("m-pg", "pgfake", Env.DEV, "db.test");
    core.connection("m-my", "myfake", Env.DEV, "db.test");
    endpoints.create("mixed", null, EndpointService.StartFrom.EMPTY, null);
    wiring.add("mixed", "m-pg", null);
    EndpointSnapshot s = wiring.add("mixed", "m-my", null);
    ToolCatalog.Catalog list = catalog.forEndpoint(s);
    assertThat(list.tools()).extracting(ToolCatalog.PublishedTool::name)
        .containsExactly("approval_card", "approval_card_action", "approval_status", "sql_execute", "sql_query");
    ToolCatalog.PublishedTool query = list.tool("sql_query").orElseThrow();
    assertThat(query.inputSchema().at("/properties/connection/enum")).extracting(n -> n.asString())
        .containsExactly("m-my", "m-pg");
    assertThat(query.outputSchema()).as("both pairs Read only").isNotNull();
    assertThat(query.description()).contains("Connections: m-my (FakeMy, dev), m-pg (FakePG, dev).")
        .contains("Max 1,000 rows, 5 s.");
    assertThat(list.tool("sql_execute").orElseThrow().outputSchema()).isNull();
    assertThat(catalog.resolve(list, "sql_query", "m-my")).map(p -> p.manifest().id()).contains("myfake");
    assertThat(catalog.resolve(list, "sql_query", "m-pg")).map(p -> p.manifest().id()).contains("pgfake");
    assertThat(catalog.resolve(list, "sql_query", "other")).isEmpty();

    EndpointSnapshot.Pair myQuery = s.pairs().stream().filter(p -> p.toolKey().equals("sql_query")
        && p.connectionName().equals("m-my")).findFirst().orElseThrow();
    tools.setMode("mixed", "sql_query", "m-my", Mode.APPROVAL, null, myQuery.rowVersion(), "check queries");
    ToolCatalog.Catalog after = catalog.forEndpoint(snapshots.bySlug("mixed").orElseThrow());
    assertThat(after.tool("sql_query").orElseThrow().outputSchema()).as("a pair in Approval drops it").isNull();
    assertThat(after.listHash()).isNotEqualTo(list.listHash());
    assertThat(core.jdbc().queryForObject("select count(*) from setup_change where what = 'mode' and subject = "
        + "'sql_query on m-my, endpoint mixed'", Integer.class)).isEqualTo(1);
  }

  @Test
  void modesKeepTheirLocks() {
    core.connection("l-pg", "pgfake", Env.DEV, "db.test");
    core.connection("l-jira", "jirafake", Env.DEV, "jira.test");
    endpoints.create("locks", null, EndpointService.StartFrom.EMPTY, null);
    wiring.add("locks", "l-pg", null);
    EndpointSnapshot s = wiring.add("locks", "l-jira", null);
    assertThat(mode(s, "sql_execute", "l-pg")).isEqualTo(Mode.APPROVAL);
    assertThat(mode(s, "jira_search", "l-jira")).as("a plugin's READ starts in Approval").isEqualTo(Mode.APPROVAL);

    assertThatThrownBy(() -> tools.setMode("locks", "sql_execute", "l-pg", Mode.READ_ONLY, null, 0, null))
        .isInstanceOf(ProblemException.class).hasMessageContaining("changes data");
    EndpointSnapshot.Pair jira = s.pairs().stream().filter(p -> p.tool().name().equals("jira_search")).findFirst()
        .orElseThrow();
    assertThat(jira.declaredBy()).isEqualTo("FakeJira");
    assertThatThrownBy(() -> tools.setMode("locks", jira.toolKey(), "l-jira", Mode.READ_ONLY, null,
        jira.rowVersion(), null)).isInstanceOf(ProblemException.class).hasMessageContaining("Confirm");

    EndpointTools.BulkResult bulk = tools.bulk("locks", null, "READ_ONLY", "everything read only");
    assertThat(bulk.skipped()).extracting(EndpointTools.Skipped::reason)
        .anyMatch(r -> r.contains("sql_execute stays as it is")).anyMatch(r -> r.contains("Declared by FakeJira"));

    String hash = tools.list("locks").stream().flatMap(g -> g.tools().stream())
        .filter(t -> t.name().equals("jira_search")).findFirst().orElseThrow().definitionHash();
    tools.confirmReadOnly("locks", jira.toolKey(), "l-jira", hash, "it only searches");
    EndpointSnapshot confirmed = snapshots.bySlug("locks").orElseThrow();
    EndpointSnapshot.Pair now = confirmed.pairs().stream().filter(p -> p.tool().name().equals("jira_search"))
        .findFirst().orElseThrow();
    assertThat(now.readOnlyConfirmed()).isTrue();
    tools.setMode("locks", jira.toolKey(), "l-jira", Mode.READ_ONLY, null, now.rowVersion(), null);
    assertThat(mode(snapshots.bySlug("locks").orElseThrow(), "jira_search", "l-jira")).isEqualTo(Mode.READ_ONLY);
  }

  @Test
  void readOnlyDevAndIncidentResponseStartWhereTheKitSays() {
    core.connection("s-dev-db", "pgfake", Env.DEV, "db.test");
    core.connection("s-dev-vm", "sshfake", Env.DEV, "vm.test");
    core.connection("s-dev-api", "httpfake", Env.DEV, "api.test");
    core.connection("s-prod-db", "pgfake", Env.PROD, "db.test");
    core.connection("s-prod-vm", "sshfake", Env.PROD, "vm.test");
    // An external MCP server joins an endpoint only when the person adds it: no start wires it.
    core.connection("s-dev-mcp", "mcpfake", Env.DEV, "mcp.test");

    EndpointService.StartPreview preview = endpoints.previewStart(EndpointService.StartFrom.READ_ONLY_DEV);
    assertThat(preview.connections()).contains("s-dev-db", "s-dev-vm", "s-dev-api").doesNotContain("s-dev-mcp",
        "s-prod-db");
    EndpointService.Summary ro = endpoints.create("s-readonly", null, EndpointService.StartFrom.READ_ONLY_DEV, null);
    assertThat(ro.access()).isEqualTo("read-only");
    assertThat(ro.tools()).as("the card's count is the list's after Create").isEqualTo(preview.tools());
    assertThat(ro.connections()).isEqualTo(preview.connections().size());
    EndpointSnapshot s = snapshots.bySlug("s-readonly").orElseThrow();
    assertThat(s.connections()).extracting(w -> w.config().env()).containsOnly(Env.DEV);
    assertThat(s.connections()).extracting(w -> w.config().name()).doesNotContain("s-dev-mcp");
    assertThat(mode(s, "sql_query", "s-dev-db")).isEqualTo(Mode.READ_ONLY);
    assertThat(mode(s, "sql_execute", "s-dev-db")).isEqualTo(Mode.OFF);
    assertThat(mode(s, "ssh_run", "s-dev-vm")).as("runbooks that only read").isEqualTo(Mode.READ_ONLY);
    assertThat(mode(s, "http_request", "s-dev-api")).isEqualTo(Mode.OFF);
    assertThatThrownBy(() -> tools.setMode("s-readonly", "sql_execute", "s-dev-db", Mode.APPROVAL, null, 0, null))
        .isInstanceOf(ProblemException.class).hasMessageContaining("s-readonly is read only");

    EndpointService.StartPreview incidentPreview = endpoints.previewStart(EndpointService.StartFrom.INCIDENT_RESPONSE);
    EndpointService.Summary incident = endpoints.create("s-incident", null,
        EndpointService.StartFrom.INCIDENT_RESPONSE, null);
    assertThat(incident.env()).isEqualTo(Env.PROD);
    assertThat(incident.tools()).isEqualTo(incidentPreview.tools());
    EndpointSnapshot p = snapshots.bySlug("s-incident").orElseThrow();
    assertThat(p.connections()).extracting(w -> w.config().env()).containsOnly(Env.PROD);
    assertThat(mode(p, "sql_execute", "s-prod-db")).isEqualTo(Mode.OFF);
    assertThat(mode(p, "ssh_run", "s-prod-vm")).isEqualTo(Mode.READ_ONLY);
    assertThat(mode(p, "ssh_exec", "s-prod-vm")).isEqualTo(Mode.OFF);

    // A standard endpoint that becomes read only loses everything that could change data.
    endpoints.create("s-standard", null, EndpointService.StartFrom.EMPTY, null);
    wiring.add("s-standard", "s-dev-db", null);
    long version = endpoints.get("s-standard").version();
    endpoints.updateSettings("s-standard", new EndpointService.SettingsUpdate(null, "read-only", null, null, null,
        null, null), version, "freeze");
    assertThat(mode(snapshots.bySlug("s-standard").orElseThrow(), "sql_execute", "s-dev-db")).isEqualTo(Mode.OFF);
  }

  @Test
  void riskFollowsTheIngredients() {
    core.connection("r-db", "pgfake", Env.DEV, "db.test");
    core.connection("r-api", "httpfake", Env.DEV, "api.test");
    core.connection("r-vm", "sshfake", Env.DEV, "vm.test");
    endpoints.create("r-full", null, EndpointService.StartFrom.EMPTY, null);
    wiring.add("r-full", "r-db", null);
    wiring.add("r-full", "r-api", null);
    RiskAnalyzer.Report full = endpoints.risk("r-full");
    assertThat(full.state()).isEqualTo("present");
    assertThat(full.ingredients()).isEqualTo(new RiskAnalyzer.Ingredients(true, true, "present"));

    endpoints.create("r-contained", null, EndpointService.StartFrom.EMPTY, null);
    wiring.add("r-contained", "r-db", null);
    wiring.add("r-contained", "r-vm", null);
    assertThat(endpoints.risk("r-contained").state()).isEqualTo("limited");

    // A runbook with a free-text parameter turns ssh_run into a full channel.
    core.bean(TemplateService.class).create("ssh-runbook", "r-vm", "grep-logs",
        TestCore.json("{\"name\":\"grep-logs\",\"freeText\":true}"), null, null);
    TestCore.await(() -> "present".equals(endpoints.risk("r-contained").state()), "the runbook to count");

    RiskAnalyzer.Report dismissed = endpoints.dismissRisk("r-full", "sandbox data only");
    assertThat(dismissed.state()).isEqualTo("dismissed");
    assertThat(dismissed.dismissed().path("reason").asString()).isEqualTo("sandbox data only");
    assertThatThrownBy(() -> endpoints.dismissRisk("r-full", " ")).isInstanceOf(ProblemException.class);

    EndpointService.SplitPreview preview = endpoints.previewSplit("r-full", List.of("r-api"), "r-full-billing");
    assertThat(preview.remaining().state()).isEqualTo("none");
    assertThat(preview.moved().state()).isEqualTo("present");
    String pending = core.pendingApproval(endpoints.get("r-full").id(), "r-full",
        core.bean(ai.msrv.gaterook.core.connections.ConnectionConfigs.class).byName("r-api").orElseThrow().id(), "r-api",
        "http_request");
    EndpointService.Summary split = endpoints.split("r-full", List.of("r-api"), "r-full-billing", "split the risk");
    assertThat(split.slug()).isEqualTo("r-full-billing");
    EndpointSnapshot moved = snapshots.bySlug("r-full-billing").orElseThrow();
    assertThat(moved.connections()).extracting(w -> w.config().name()).containsExactly("r-api");
    assertThat(mode(moved, "http_request", "r-api")).isEqualTo(Mode.APPROVAL);
    assertThat(snapshots.bySlug("r-full").orElseThrow().connections()).extracting(w -> w.config().name())
        .containsExactly("r-db");
    assertThat(core.jdbc().queryForObject("select status from approval where id = ?", String.class, pending))
        .isEqualTo("rejected");
  }

  @Test
  void namedTokensAuthenticateOnTheirEndpointOnly() {
    endpoints.create("tok-ep", null, EndpointService.StartFrom.EMPTY, null);
    endpoints.create("tok-other", null, EndpointService.StartFrom.EMPTY, null);
    TokenService tokens = core.bean(TokenService.class);
    TokenService.Issued issued = tokens.issue("tok-ep", "inspector-debug", null, "debugging");
    assertThat(issued.token()).startsWith("gwt_").hasSize(47);
    assertThat(issued.prefix()).hasSize(8).isEqualTo(issued.token().substring(0, 8));
    assertThat(issued.toString()).doesNotContain(issued.token());
    assertThat(issued.expiresAt()).isBetween(Instant.now().plus(Duration.ofDays(183)),
        Instant.now().plus(Duration.ofDays(184)));
    String stored = core.jdbc().queryForObject("select hash from api_token where id = ?", String.class, issued.id());
    assertThat(stored).isEqualTo(TokenService.hash(issued.token())).doesNotContain(issued.token());

    CallerAuthenticator auth = core.bean(CallerAuthenticator.class);
    Optional<Caller> caller = auth.authenticate(new Authenticator.Request(Ingress.LOCAL, "tok-ep", issued.token(),
        "127.0.0.1"));
    assertThat(caller).hasValueSatisfying(c -> {
      assertThat(c.kind()).isEqualTo(Caller.Kind.LOCAL_TOKEN);
      assertThat(c.principalId()).isEqualTo(issued.id());
      assertThat(c.label()).isEqualTo("inspector-debug");
    });
    assertThat(auth.authenticate(new Authenticator.Request(Ingress.LOCAL, "tok-other", issued.token(), null)))
        .as("another endpoint").isEmpty();
    assertThat(auth.authenticate(new Authenticator.Request(Ingress.TUNNEL, "tok-ep", issued.token(), null)))
        .as("another ingress").isEmpty();
    assertThat(auth.authenticate(new Authenticator.Request(Ingress.LOCAL, "tok-ep", issued.token() + "x", null)))
        .isEmpty();
    assertThat(tokens.list("tok-ep")).singleElement().satisfies(t -> {
      assertThat(t.state()).isEqualTo("active");
      assertThat(t.prefix()).isEqualTo(issued.prefix());
    });

    // A bridge token is the grant's: revoking the grant stops it on the next call.
    String bridge = TokenService.BRIDGE_PREFIX + "bridge-token-for-the-test-0123456789abcdef";
    // Grants are signed rows: the clients module creates them through ClientGrants (K1).
    String signedGrant = core.bean(ClientGrants.class).create(null, "codex", "tok-ep", "codex-laptop", null).id();
    String grantId = new TransactionTemplate(core.bean(PlatformTransactionManager.class)).execute(s -> {
      ClientGrantEntity g = core.bean(ClientGrantRepository.class).findById(signedGrant).orElseThrow();
      core.bean(ApiTokenRepository.class).save(ApiTokenEntity.bridge(g, TokenService.hash(bridge), Instant.now(),
          Instant.now().plusSeconds(900)));
      return g.getId();
    });
    assertThat(auth.authenticate(new Authenticator.Request(Ingress.LOCAL, "tok-ep", bridge, null)))
        .hasValueSatisfying(c -> {
          assertThat(c.kind()).isEqualTo(Caller.Kind.BRIDGE);
          assertThat(c.principalId()).isEqualTo(grantId);
          assertThat(c.clientHint()).isEqualTo("codex");
        });
    core.jdbc().update("update client_grant set revoked_at = ? where id = ?", Instant.now().toEpochMilli(), grantId);
    assertThat(auth.authenticate(new Authenticator.Request(Ingress.LOCAL, "tok-ep", bridge, null))).isEmpty();

    tokens.revoke(issued.id(), "leaked");
    assertThat(auth.authenticate(new Authenticator.Request(Ingress.LOCAL, "tok-ep", issued.token(), null)))
        .as("revoked").isEmpty();
  }

  @Test
  void thePolicyDecidesOnTheSnapshotsPairs() {
    core.connection("p-dev", "pgfake", Env.DEV, "db.test");
    core.connection("p-prod", "pgfake", Env.PROD, "db.test");
    endpoints.create("p-devep", null, EndpointService.StartFrom.EMPTY, null);
    EndpointSnapshot dev = wiring.add("p-devep", "p-dev", null);
    endpoints.create("p-prodep", null, EndpointService.StartFrom.EMPTY, null);
    EndpointSnapshot prod = wiring.add("p-prodep", "p-prod", null);
    PolicyEngine engine = core.bean(PolicyEngine.class);

    EndpointSnapshot.Pair execute = pair(dev, "sql_execute");
    Decision write = engine.decide(execute.view(), execute.config(), Classification.mutation("UPDATE"));
    assertThat(write.outcome()).isEqualTo(Decision.Outcome.APPROVAL);
    assertThat(write.ruleId()).isEqualTo("dev.sql.write-approval");

    EndpointSnapshot.Pair query = pair(prod, "sql_query");
    ai.msrv.gaterook.connector.api.ToolCall drop = new ai.msrv.gaterook.connector.api.ToolCall("tc_1", "sql_query",
        "p-prod", JsonNodeFactory.instance.objectNode().put("sql", "DROP TABLE orders"), null);
    var connector = core.bean(ai.msrv.gaterook.core.connectors.ConnectorRegistry.class).connectorFor(query.config())
        .orElseThrow();
    Decision denied = engine.decide(query.view(), query.config(),
        connector.classify(drop, query.config(), engine.view(query.view(), query.config())));
    assertThat(denied.outcome()).isEqualTo(Decision.Outcome.DENY);
    assertThat(denied.ruleId()).isEqualTo("prod.sql.block_ddl");
    assertThat(denied.limits().maxRows()).isEqualTo(100);
    assertThat(denied.ratePerToken()).isEqualTo(60);

    // Limits narrow: endpoint 20 rows beats prod's 100.
    long version = endpoints.get("p-prodep").version();
    endpoints.updateSettings("p-prodep", new EndpointService.SettingsUpdate(null, null, null,
        TestCore.json("{\"rowLimit\":20,\"toolTimeoutSeconds\":60}"), null, null, null), version, null);
    EndpointSnapshot.Pair narrowed = pair(snapshots.bySlug("p-prodep").orElseThrow(), "sql_query");
    Decision read = engine.decide(narrowed.view(), narrowed.config(), Classification.read());
    assertThat(read.outcome()).isEqualTo(Decision.Outcome.ALLOW);
    assertThat(read.limits().maxRows()).isEqualTo(20);
    assertThat(read.limits().toolTimeout().toSeconds()).as("60 s is not stricter than prod's 5 s").isEqualTo(5);
    assertThatThrownBy(() -> endpoints.updateSettings("p-prodep", new EndpointService.SettingsUpdate(null, null,
        null, TestCore.json("{\"readOnlyTransactions\":false}"), null, null, null), version + 1, null))
        .isInstanceOf(ProblemException.class);

    // A policy row edited outside the core denies every call of its environment.
    core.jdbc().update("update policy set sections = '{\"sql\":{\"rowLimit\":100000}}' where env = 'prod'");
    core.bean(PolicyService.class).onConnectorTypeChanged(null);
    core.bean(EndpointSnapshotService.class).invalidateAll();
    clearPolicyCache();
    Decision tampered = engine.decide(narrowed.view(), narrowed.config(), Classification.read());
    assertThat(tampered.outcome()).isEqualTo(Decision.Outcome.DENY);
    assertThat(tampered.ruleId()).isEqualTo("core.integrity");
    core.bean(PolicyService.class).confirmIntegrity(Env.PROD, "I set it");
    assertThat(engine.decide(narrowed.view(), narrowed.config(), Classification.read()).outcome())
        .isEqualTo(Decision.Outcome.ALLOW);
    core.bean(PolicyService.class).reset(Env.PROD, "back to defaults");
  }

  @Test
  void deletingAConnectionAndAnEndpointKeepsTheirNamesInTheHistory() {
    ConnectionService.Summary db = core.connection("d-db", "pgfake", Env.DEV, "db.test");
    core.connection("d-api", "httpfake", Env.DEV, "api.test");
    endpoints.create("d-ep", null, EndpointService.StartFrom.EMPTY, null);
    wiring.add("d-ep", "d-db", null);
    wiring.add("d-ep", "d-api", null);
    String endpointId = endpoints.get("d-ep").id();
    String pendingOnDb = core.pendingApproval(endpointId, "d-ep", db.id(), "d-db", "sql_execute");
    String apiId = core.bean(ai.msrv.gaterook.core.connections.ConnectionConfigs.class).byName("d-api").orElseThrow().id();
    String pendingOnApi = core.pendingApproval(endpointId, "d-ep", apiId, "d-api", "http_request");
    TokenService.Issued token = core.bean(TokenService.class).issue("d-ep", "ci-smoke", 7, null);

    ConnectionService.DeleteSummary deleted = core.bean(ConnectionService.class).delete("d-db", "d-db", "retired");
    assertThat(deleted.stoppedTools()).contains("sql_query on d-ep", "sql_execute on d-ep");
    assertThat(deleted.rejectedApprovals()).isEqualTo(1);
    assertThat(deleted.secretsDestroyed()).containsExactly("d-db/password");
    Map<String, Object> row = core.jdbc().queryForMap("select status, connection_id, connection_name, endpoint_slug, "
        + "decided_via, comment, error from approval where id = ?", pendingOnDb);
    assertThat(row).containsEntry("status", "rejected").containsEntry("connection_name", "d-db")
        .containsEntry("endpoint_slug", "d-ep").containsEntry("decided_via", "core")
        .containsEntry("error", "d-db was removed from d-ep. Not executed.");
    assertThat(row.get("connection_id")).isNull();
    assertThat(row.get("comment")).isNull();
    assertThat(snapshots.bySlug("d-ep").orElseThrow().connections()).extracting(w -> w.config().name())
        .containsExactly("d-api");

    EndpointService.DeleteSummary gone = endpoints.delete("d-ep", "d-ep", "done");
    assertThat(gone.tokensRevoked()).containsExactly("ci-smoke");
    assertThat(gone.rejectedApprovals()).isEqualTo(1);
    Map<String, Object> api = core.jdbc().queryForMap("select status, endpoint_id, endpoint_slug from approval where "
        + "id = ?", pendingOnApi);
    assertThat(api).containsEntry("status", "rejected").containsEntry("endpoint_slug", "d-ep");
    assertThat(api.get("endpoint_id")).isNull();
    assertThat(core.bean(CallerAuthenticator.class).authenticate(new Authenticator.Request(Ingress.LOCAL, "d-ep",
        token.token(), null))).isEmpty();
    assertThat(core.jdbc().queryForObject("select count(*) from setup_change where subject in ('Connection d-db "
        + "deleted', 'Endpoint d-ep deleted')", Integer.class)).isEqualTo(2);
    assertThatThrownBy(() -> endpoints.delete("d-api-ep", "other", null)).isInstanceOf(ProblemException.class);
  }

  @Test
  void anEndpointEditedOutsideTheCorePublishesNothingUntilReviewed() {
    core.connection("t-db", "pgfake", Env.DEV, "db.test");
    endpoints.create("t-ep", null, EndpointService.StartFrom.EMPTY, null);
    wiring.add("t-ep", "t-db", null);
    core.jdbc().update("update endpoint_tool set mode = 'READ_ONLY' where tool_key = 'sql_execute' and endpoint_id = "
        + "(select id from endpoint where slug = 't-ep')");
    snapshots.invalidateAll();
    EndpointSnapshot s = snapshots.bySlug("t-ep").orElseThrow();
    assertThat(s.blocked()).isTrue();
    assertThat(catalog.forEndpoint(s).tools()).extracting(ToolCatalog.PublishedTool::name)
        .containsExactly("approval_status");
    assertThatThrownBy(() -> tools.list("t-ep")).isInstanceOf(ProblemException.class);

    endpoints.confirmIntegrity("t-ep", "reviewed");
    EndpointSnapshot ok = snapshots.bySlug("t-ep").orElseThrow();
    assertThat(ok.blocked()).isFalse();
    assertThat(mode(ok, "sql_execute", "t-db")).as("Read only of a mutation still counts as Approval")
        .isEqualTo(Mode.APPROVAL);
  }

  @Test
  void newTemplateToolsJoinTheEndpointsOfTheirConnection() {
    core.connection("n-db", "pgfake", Env.DEV, "db.test");
    endpoints.create("n-ep", null, EndpointService.StartFrom.EMPTY, null);
    wiring.add("n-ep", "n-db", null);
    core.bean(TemplateService.class).create("sql-query", "n-db", null,
        TestCore.json("{\"name\":\"orders_by_status\",\"sql\":\"select 1\"}"), null, null);
    // The pair is offered as soon as the template compiles (Off, no row yet); the row with its first mode is
    // written by the asynchronous reconcile.
    TestCore.await(() -> snapshots.bySlug("n-ep").orElseThrow().pairs().stream()
        .anyMatch(p -> p.tool().name().equals("orders_by_status") && p.rowId() != null), "the template tool");
    EndpointSnapshot s = snapshots.bySlug("n-ep").orElseThrow();
    assertThat(mode(s, "orders_by_status", "n-db")).as("a new template's defaults").isEqualTo(Mode.READ_ONLY);
    ToolCatalog.PublishedTool t = catalog.forEndpoint(s).tool("orders_by_status").orElseThrow();
    assertThat(t.description()).contains("Runs on n-db (dev).");
    assertThat(catalog.resolve(catalog.forEndpoint(s), "orders_by_status", null)).isPresent()
        .hasValueSatisfying(p -> assertThat(p.template()).isNotNull());
  }

  @Test
  void disablingKeepsWhatWaitsAndEnablingUndoesIt() {
    core.connection("o-db", "pgfake", Env.DEV, "db.test");
    endpoints.create("o-ep", null, EndpointService.StartFrom.EMPTY, null);
    wiring.add("o-ep", "o-db", null);
    String endpointId = endpoints.get("o-ep").id();
    String dbId = core.bean(ai.msrv.gaterook.core.connections.ConnectionConfigs.class).byName("o-db").orElseThrow().id();
    String waiting = core.pendingApproval(endpointId, "o-ep", dbId, "o-db", "sql_execute");

    EndpointService.Summary off = endpoints.disable("o-ep", "maintenance");
    assertThat(off.status()).isEqualTo("disabled");
    assertThat(snapshots.bySlug("o-ep").orElseThrow().enabled()).as("what M1.5's 404 reads").isFalse();
    assertThat(core.jdbc().queryForObject("select status from approval where id = ?", String.class, waiting))
        .as("its requests stay pending").isEqualTo("pending");
    assertThat(core.jdbc().queryForObject("select disabled_at from endpoint where slug = 'o-ep'", Long.class))
        .isNotNull();
    endpoints.disable("o-ep", "again");
    assertThat(endpointChanges("o-ep")).containsExactly("Running→Disabled maintenance");

    EndpointService.Summary on = endpoints.enable("o-ep", "Undo");
    assertThat(on.status()).isEqualTo("running");
    assertThat(snapshots.bySlug("o-ep").orElseThrow().enabled()).isTrue();
    assertThat(core.jdbc().queryForObject("select disabled_at from endpoint where slug = 'o-ep'", Long.class))
        .isNull();
    assertThat(endpointChanges("o-ep")).containsExactly("Running→Disabled maintenance", "Disabled→Running Undo");

    // An endpoint row edited outside the core is never signed again by a switch.
    core.jdbc().update("update endpoint set instructions = 'ATTACKER TEXT' where slug = 'o-ep'");
    assertThatThrownBy(() -> endpoints.disable("o-ep", null)).isInstanceOfSatisfying(ProblemException.class,
        e -> assertThat(e.kind()).isEqualTo(ProblemException.Kind.CONFLICT));
    assertThat(core.jdbc().queryForObject("select enabled from endpoint where slug = 'o-ep'", Boolean.class))
        .isTrue();
    endpoints.confirmIntegrity("o-ep", "my own note");
    assertThat(endpoints.disable("o-ep", null).status()).isEqualTo("disabled");
  }

  @Test
  void aDismissedRiskWarningCanBeShownAgain() {
    core.connection("q-db", "pgfake", Env.DEV, "db.test");
    core.connection("q-api", "httpfake", Env.DEV, "api.test");
    endpoints.create("q-ep", null, EndpointService.StartFrom.EMPTY, null);
    wiring.add("q-ep", "q-db", null);
    wiring.add("q-ep", "q-api", null);
    assertThat(endpoints.dismissRisk("q-ep", "sandbox data only").state()).isEqualTo("dismissed");
    RiskAnalyzer.Report shown = endpoints.restoreRisk("q-ep", "real data now");
    assertThat(shown.state()).isEqualTo("present");
    assertThat(shown.dismissed()).isNull();
    assertThat(core.jdbc().queryForList("select change_from, change_to, reason from setup_change where "
        + "what = 'risk-warning' and endpoint_slug = 'q-ep' order by id")).extracting(r -> r.get("change_from") + "→"
        + r.get("change_to") + " " + r.get("reason")).containsExactly("present→Dismissed sandbox data only",
        "Dismissed→Shown real data now");
    long count = core.jdbc().queryForObject("select count(*) from setup_change", Long.class);
    endpoints.restoreRisk("q-ep", null);
    assertThat(core.jdbc().queryForObject("select count(*) from setup_change", Long.class)).as("nothing to restore")
        .isEqualTo(count);
  }

  @Test
  void settingsAndOverridesAreCheckedAgainstTheirSchemas() {
    core.connection("g-db", "pgfake", Env.DEV, "db.test");
    endpoints.create("g-ep", null, EndpointService.StartFrom.EMPTY, null);
    EndpointSnapshot s = wiring.add("g-ep", "g-db", null);
    long version = endpoints.get("g-ep").version();
    // A row limit of -5 or a tool timeout of 0 would reach ToolGuard as they are; a string would be ignored.
    for (String[] bad : new String[][] {{"{\"rowLimit\":-5}", "/limits/rowLimit"},
        {"{\"toolTimeoutSeconds\":0}", "/limits/toolTimeoutSeconds"}, {"{\"rowLimit\":\"20\"}", "/limits/rowLimit"},
        {"{\"nope\":1}", "/limits/nope"}, {"{\"readOnlyTransactions\":false}", "/limits/readOnlyTransactions"}}) {
      assertThatThrownBy(() -> endpoints.updateSettings("g-ep", new EndpointService.SettingsUpdate(null, null, null,
          TestCore.json(bad[0]), null, null, null), version, null)).as(bad[0])
          .isInstanceOfSatisfying(ProblemException.class, e -> {
            assertThat(e.kind()).isEqualTo(ProblemException.Kind.UNPROCESSABLE);
            assertThat(e.field()).isEqualTo(bad[1]);
          });
    }
    for (String[] bad : new String[][] {{"{\"revisions\":[\"1999-01-01\"]}", null},
        {"{\"revisions\":[]}", null}, {"{\"approvalDelivery\":\"sometimes\"}", null},
        {"{\"revisions\":null}", null}}) {
      assertThatThrownBy(() -> endpoints.updateSettings("g-ep", new EndpointService.SettingsUpdate(null, null, null,
          null, TestCore.json(bad[0]), null, null), version, null)).as(bad[0])
          .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.field()).startsWith("/protocol"));
    }
    assertThatThrownBy(() -> endpoints.updateSettings("g-ep", new EndpointService.SettingsUpdate(null, null, null,
        null, null, TestCore.json("{\"firstWaitSeconds\":60}"), null), version, null))
        .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.field())
            .isEqualTo("/approvalWait/firstWaitSeconds"));
    assertThat(endpoints.get("g-ep").version()).as("nothing was stored").isEqualTo(version);

    // A patch changes what it names and keeps the rest.
    endpoints.updateSettings("g-ep", new EndpointService.SettingsUpdate(null, null, null,
        TestCore.json("{\"rowLimit\":20}"), TestCore.json("{\"approvalDelivery\":\"handle-only\"}"),
        TestCore.json("{\"firstWaitSeconds\":0}"), null), version, "handles only");
    EndpointSnapshot after = snapshots.bySlug("g-ep").orElseThrow();
    assertThat(after.settings().at("/protocol/revisions")).extracting(n -> n.asString())
        .containsExactly("2026-07-28", "2025-11-25");
    assertThat(after.settings().at("/protocol/approvalDelivery").asString()).isEqualTo("handle-only");
    assertThat(after.settings().at("/approvalWait/firstWaitSeconds").asInt()).isZero();
    long changes = core.jdbc().queryForObject("select count(*) from setup_change where endpoint_slug = 'g-ep' and "
        + "subject like 'Endpoint g-ep · %'", Long.class);
    assertThat(changes).as("limits, protocol, approvalWait").isEqualTo(3);
    endpoints.updateSettings("g-ep", new EndpointService.SettingsUpdate(null, null, null, null,
        TestCore.json("{\"approvalDelivery\":\"handle-only\"}"), null, null), after.version(), null);
    assertThat(core.jdbc().queryForObject("select count(*) from setup_change where endpoint_slug = 'g-ep' and "
        + "subject like 'Endpoint g-ep · %'", Long.class)).as("the same value is no change").isEqualTo(changes);

    // A pair's override is checked the same way.
    EndpointSnapshot.Pair query = pair(s, "sql_query");
    assertThatThrownBy(() -> tools.setMode("g-ep", "sql_query", "g-db", Mode.READ_ONLY,
        TestCore.json("{\"rowLimit\":0}"), query.rowVersion(), null)).isInstanceOfSatisfying(ProblemException.class,
        e -> assertThat(e.field()).isEqualTo("/overrides/rowLimit"));
    tools.setMode("g-ep", "sql_query", "g-db", Mode.READ_ONLY, TestCore.json("{\"rowLimit\":5}"), query.rowVersion(),
        null);
    EndpointSnapshot.Pair narrowed = pair(snapshots.bySlug("g-ep").orElseThrow(), "sql_query");
    assertThat(core.bean(PolicyEngine.class).decide(narrowed.view(), narrowed.config(), Classification.read())
        .limits().maxRows()).isEqualTo(5);
  }

  /** {@code from→to reason} of an endpoint's enable/disable Setup changes. */
  private static List<String> endpointChanges(String slug) {
    return core.jdbc().queryForList("select change_from, change_to, reason from setup_change where what = 'settings' "
        + "and subject = ? order by id", "Endpoint " + slug).stream().map(r -> r.get("change_from") + "→"
        + r.get("change_to") + " " + r.get("reason")).toList();
  }

  @Test
  void sameNamedTemplatesOfTwoConnectionsPublishNeither() {
    TemplateService templates = core.bean(TemplateService.class);
    core.connection("c-a", "pgfake", Env.DEV, "db.test");
    core.connection("c-b", "pgfake", Env.DEV, "db.test");
    core.connection("c-jira", "jirafake", Env.DEV, "jira.test");
    endpoints.create("c-ep", null, EndpointService.StartFrom.EMPTY, null);
    wiring.add("c-ep", "c-a", null);
    wiring.add("c-ep", "c-b", null);
    templates.create("sql-query", "c-a", null, TestCore.json("{\"name\":\"orders_by_status\",\"sql\":\"select 1\"}"),
        null, null);
    // A template that happens to share its name with the plugin's tool, on the connection wired first.
    templates.create("sql-query", "c-a", null, TestCore.json("{\"name\":\"jira_search\",\"sql\":\"select 2\"}"),
        null, null);
    TemplateService.SaveResult second = templates.create("sql-query", "c-b", null,
        TestCore.json("{\"name\":\"orders_by_status\",\"sql\":\"select 3\"}"), null, null);
    assertThat(second.template()).as("allowed: the connections may never meet").isNotNull();
    assertThat(second.compile().warnings()).containsExactly("orders_by_status is also a template tool on c-a: an "
        + "endpoint with both connections publishes neither.");
    wiring.add("c-ep", "c-jira", null);
    TestCore.await(() -> snapshots.bySlug("c-ep").orElseThrow().pairs().stream()
        .filter(p -> p.template() != null && p.rowId() != null).count() == 3, "the template pairs");

    // tools/list of a TABLE-shaped clash used to fail with a NullPointerException.
    ToolCatalog.Catalog list = catalog.forEndpoint(snapshots.bySlug("c-ep").orElseThrow());
    assertThat(list.tool("orders_by_status")).isEmpty();
    assertThat(list.problems()).contains(
        "orders_by_status of template on c-a clashes with another tool of the same name",
        "orders_by_status of template on c-b clashes with another tool of the same name",
        "jira_search of template on c-a clashes with another tool of the same name");
    // The plugin's own tool still publishes, although the template pair of its name came first.
    ToolCatalog.PublishedTool jira = list.tool("jira_search").orElseThrow();
    assertThat(jira.pairs()).singleElement().satisfies(p -> {
      assertThat(p.connectionName()).isEqualTo("c-jira");
      assertThat(p.template()).isNull();
    });
    assertThat(jira.inputSchema().at("/properties/connection/enum")).extracting(n -> n.asString())
        .containsExactly("c-jira");
    assertThat(catalog.resolve(list, "orders_by_status", "c-a")).isEmpty();
  }

  @Test
  void rowsEditedOutsideTheCoreAreNeverSignedAgainByOrdinaryChanges() {
    IntegrityAlerts alerts = core.bean(IntegrityAlerts.class);
    ConnectionService connections = core.bean(ConnectionService.class);

    // An edited endpoint row stays edited, unsigned, when another connection leaves it by deletion.
    core.connection("w-dev", "pgfake", Env.DEV, "db.test");
    core.connection("w-stage", "pgfake", Env.STAGE, "db.test");
    endpoints.create("w-ep", null, EndpointService.StartFrom.EMPTY, null);
    wiring.add("w-ep", "w-dev", null);
    wiring.add("w-ep", "w-stage", null);
    endpoints.updateSettings("w-ep", new EndpointService.SettingsUpdate(null, "read-only", null, null, null, null,
        null), endpoints.get("w-ep").version(), "freeze");
    String id = endpoints.get("w-ep").id();
    assertThat(endpoints.get("w-ep").env()).isEqualTo(Env.STAGE);
    core.jdbc().update("update endpoint set access = 'standard', instructions = 'ATTACKER TEXT' where slug = 'w-ep'");
    // The cache still holds the reviewed view on purpose: changes must check the rows, not the cache.
    connections.delete("w-stage", "w-stage", "retired");
    snapshots.invalidateAll();
    EndpointSnapshot afterDelete = snapshots.bySlug("w-ep").orElseThrow();
    assertThat(afterDelete.blocked()).isTrue();
    assertThat(afterDelete.connections()).extracting(w -> w.config().name()).containsExactly("w-dev");
    assertThat(alerts.has("endpoint", id)).isTrue();
    assertThat(catalog.forEndpoint(afterDelete).tools()).extracting(ToolCatalog.PublishedTool::name)
        .containsExactly("approval_status");

    // Removing a connection from an edited endpoint is refused: its row would be signed again.
    core.connection("w2-a", "pgfake", Env.DEV, "db.test");
    core.connection("w2-b", "pgfake", Env.DEV, "db.test");
    endpoints.create("w2-ep", null, EndpointService.StartFrom.EMPTY, null);
    wiring.add("w2-ep", "w2-a", null);
    wiring.add("w2-ep", "w2-b", null);
    snapshots.bySlug("w2-ep").orElseThrow();
    core.jdbc().update("update endpoint set instructions = 'ATTACKER TEXT' where slug = 'w2-ep'");
    assertThatThrownBy(() -> wiring.remove("w2-ep", "w2-b", null)).isInstanceOfSatisfying(ProblemException.class,
        e -> assertThat(e.kind()).isEqualTo(ProblemException.Kind.CONFLICT));
    snapshots.invalidateAll();
    assertThat(snapshots.bySlug("w2-ep").orElseThrow().blocked()).isTrue();
    assertThat(snapshots.bySlug("w2-ep").orElseThrow().connections()).hasSize(2);

    // An edited tool row: duplicate, split, a mode, a read-only switch all refuse, even with the cache warm.
    core.connection("w3-a", "pgfake", Env.DEV, "db.test");
    core.connection("w3-b", "httpfake", Env.DEV, "api.test");
    endpoints.create("w3-ep", null, EndpointService.StartFrom.EMPTY, null);
    wiring.add("w3-ep", "w3-a", null);
    EndpointSnapshot warm = wiring.add("w3-ep", "w3-b", null);
    core.jdbc().update("update endpoint_tool set mode = 'READ_ONLY' where tool_key = 'list_connections' and "
        + "endpoint_id = (select id from endpoint where slug = 'w3-ep')");
    assertThatThrownBy(() -> endpoints.duplicate("w3-ep", "w3-copy", null))
        .isInstanceOfSatisfying(ProblemException.class, e -> {
          assertThat(e.kind()).isEqualTo(ProblemException.Kind.CONFLICT);
          assertThat(e.getMessage()).contains("tool list_connections");
        });
    assertThat(core.bean(EndpointRepository.class).existsBySlug("w3-copy")).isFalse();
    assertThatThrownBy(() -> endpoints.split("w3-ep", List.of("w3-b"), "w3-split", null))
        .isInstanceOf(ProblemException.class).hasMessageContaining("Changed outside");
    assertThat(core.bean(EndpointRepository.class).existsBySlug("w3-split")).isFalse();
    EndpointSnapshot.Pair query = warm.pairs().stream().filter(p -> p.toolKey().equals("sql_query")).findFirst()
        .orElseThrow();
    assertThatThrownBy(() -> tools.setMode("w3-ep", "sql_query", "w3-a", Mode.APPROVAL, null, query.rowVersion(),
        null)).isInstanceOf(ProblemException.class).hasMessageContaining("changed outside");
    assertThatThrownBy(() -> tools.bulk("w3-ep", null, "OFF", null)).isInstanceOf(ProblemException.class);
    long version = endpoints.get("w3-ep").version();
    assertThatThrownBy(() -> endpoints.updateSettings("w3-ep", new EndpointService.SettingsUpdate(null, "read-only",
        null, null, null, null, null), version, null)).isInstanceOf(ProblemException.class);
    snapshots.invalidateAll();
    EndpointSnapshot still = snapshots.bySlug("w3-ep").orElseThrow();
    assertThat(still.blocked()).isTrue();
    assertThat(still.readOnly()).isFalse();
    assertThat(still.coreTool("list_connections").orElseThrow().storedMode()).as("as edited, still unsigned")
        .isEqualTo(Mode.READ_ONLY);

    // The person reviews it: kept as stored, its env derived again from what is left.
    endpoints.confirmIntegrity("w-ep", "I made it standard");
    EndpointSnapshot reviewed = snapshots.bySlug("w-ep").orElseThrow();
    assertThat(reviewed.blocked()).isFalse();
    assertThat(reviewed.env()).as("w-stage left while it was unverified").isEqualTo(Env.DEV);
    assertThat(reviewed.readOnly()).isFalse();
    assertThat(alerts.has("endpoint", id)).isFalse();
  }

  @Test
  void becomingReadOnlyRaisesNoFalseAlertAndTurnsCoreToolsInApprovalOff() {
    core.connection("ro-db", "pgfake", Env.DEV, "db.test");
    endpoints.create("ro-ep", null, EndpointService.StartFrom.EMPTY, null);
    EndpointSnapshot s = wiring.add("ro-ep", "ro-db", null);
    tools.setMode("ro-ep", "list_connections", null, Mode.APPROVAL, null,
        s.coreTool("list_connections").orElseThrow().rowVersion(), "ask before listing");
    String id = endpoints.get("ro-ep").id();
    long version = endpoints.get("ro-ep").version();
    // A cold cache: the switch builds the view it checks itself, before it changes anything.
    snapshots.invalidateAll();
    endpoints.updateSettings("ro-ep", new EndpointService.SettingsUpdate(null, "read-only", null, null, null, null,
        null), version, "freeze");
    assertThat(core.bean(IntegrityAlerts.class).has("endpoint", id)).as("nothing was edited outside").isFalse();
    EndpointSnapshot ro = snapshots.bySlug("ro-ep").orElseThrow();
    assertThat(ro.blocked()).isFalse();
    assertThat(ro.readOnly()).isTrue();
    assertThat(mode(ro, "sql_execute", "ro-db")).isEqualTo(Mode.OFF);
    assertThat(ro.coreTool("list_connections").orElseThrow().storedMode()).isEqualTo(Mode.OFF);
    assertThat(core.jdbc().queryForList("select change_from, change_to from setup_change where what = 'mode' and "
        + "subject = 'list_connections, endpoint ro-ep' order by id")).extracting(r -> r.get("change_from") + "→"
        + r.get("change_to")).containsExactly("Off→Approval", "Approval→Off");

    // A read-only endpoint holds its core tools Off or Read only, like every pair.
    long coreVersion = ro.coreTool("list_connections").orElseThrow().rowVersion();
    assertThatThrownBy(() -> tools.setMode("ro-ep", "list_connections", null, Mode.APPROVAL, null, coreVersion, null))
        .isInstanceOfSatisfying(ProblemException.class, e -> {
          assertThat(e.kind()).isEqualTo(ProblemException.Kind.UNPROCESSABLE);
          assertThat(e.getMessage()).contains("ro-ep is read only");
        });
    tools.setMode("ro-ep", "list_connections", null, Mode.READ_ONLY, null, coreVersion, null);
    assertThat(snapshots.bySlug("ro-ep").orElseThrow().coreTool("list_connections").orElseThrow().mode())
        .isEqualTo(Mode.READ_ONLY);
  }

  @Test
  void aWiredConnectionNeverCrossesTheProdLine() {
    ConnectionService connections = core.bean(ConnectionService.class);
    core.connection("x-dev", "pgfake", Env.DEV, "db.test");
    endpoints.create("x-dev-ep", null, EndpointService.StartFrom.EMPTY, null);
    wiring.add("x-dev-ep", "x-dev", null);
    long v = connections.get("x-dev").version();
    // Its only connection: the dev endpoint would turn into a prod one with its dev-era modes and tokens.
    assertThatThrownBy(() -> connections.update("x-dev", Env.PROD, TestCore.config("db.test"), v, "promote", null))
        .isInstanceOfSatisfying(ProblemException.class, e -> {
          assertThat(e.kind()).isEqualTo(ProblemException.Kind.UNPROCESSABLE);
          assertThat(e.field()).isEqualTo("/env");
          assertThat(e.getMessage()).contains("x-dev-ep (dev)");
        });
    assertThat(connections.get("x-dev").env()).isEqualTo(Env.DEV);
    connections.update("x-dev", Env.STAGE, TestCore.config("db.test"), v, "now on stage", null);
    TestCore.await(() -> endpoints.get("x-dev-ep").env() == Env.STAGE, "the endpoint to follow dev → stage");

    core.connection("x-prod", "pgfake", Env.PROD, "db.test");
    endpoints.create("x-prod-ep", null, EndpointService.StartFrom.EMPTY, null);
    wiring.add("x-prod-ep", "x-prod", null);
    assertThatThrownBy(() -> connections.update("x-prod", Env.DEV, TestCore.config("db.test"),
        connections.get("x-prod").version(), null, null)).isInstanceOf(ProblemException.class)
        .hasMessageContaining("x-prod-ep (prod)");
    // Once off the endpoint it may move.
    wiring.remove("x-prod-ep", "x-prod", null);
    assertThat(connections.update("x-prod", Env.DEV, TestCore.config("db.test"), connections.get("x-prod").version(),
        null, null).env()).isEqualTo(Env.DEV);
  }

  @Test
  void anEnvChangeRacingAnAddNeverMixesProdWithDev() throws Exception {
    ConnectionService connections = core.bean(ConnectionService.class);
    java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      for (int round = 0; round < 12; round++) {
        String conn = "race-" + round;
        String slug = "race-ep-" + round;
        core.connection(conn, "pgfake", Env.DEV, "db.test");
        core.connection(conn + "-other", "pgfake", Env.DEV, "db.test");
        endpoints.create(slug, null, EndpointService.StartFrom.EMPTY, null);
        wiring.add(slug, conn + "-other", null);
        long version = connections.get(conn).version();
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        var add = pool.submit(() -> {
          go.await();
          return wiring.add(slug, conn, null);
        });
        var promote = pool.submit(() -> {
          go.await();
          return connections.update(conn, Env.PROD, TestCore.config("db.test"), version, null, null);
        });
        go.countDown();
        boolean added = succeeded(add);
        boolean promoted = succeeded(promote);
        assertThat(added && promoted).as("round " + round + ": both passed the prod line").isFalse();
        assertThat(added || promoted).as("round " + round + ": one of them goes through").isTrue();
        EndpointSnapshot s = snapshots.uncached(endpoints.get(slug).id()).orElseThrow();
        assertThat(s.connections()).extracting(w -> w.config().env()).as("round " + round).containsOnly(Env.DEV);
      }
    } finally {
      pool.shutdownNow();
    }
  }

  private static boolean succeeded(java.util.concurrent.Future<?> f) throws InterruptedException {
    try {
      f.get(30, java.util.concurrent.TimeUnit.SECONDS);
      return true;
    } catch (java.util.concurrent.ExecutionException e) {
      assertThat(e.getCause()).isInstanceOfSatisfying(ProblemException.class,
          p -> assertThat(p.kind()).isEqualTo(ProblemException.Kind.UNPROCESSABLE));
      return false;
    } catch (java.util.concurrent.TimeoutException e) {
      throw new AssertionError("hung", e);
    }
  }

  @Test
  void anEditedParentPolicyDeniesTheEnvironmentsThatInheritIt() {
    PolicyService policies = core.bean(PolicyService.class);
    PolicyEngine engine = core.bean(PolicyEngine.class);
    IntegrityAlerts alerts = core.bean(IntegrityAlerts.class);
    try {
      policies.update(Env.DEV, TestCore.json("{\"sql\":{\"rowLimit\":50}}"), policies.get(Env.DEV).version(),
          "tighten dev", null);
      core.connection("i-stage", "pgfake", Env.STAGE, "db.test");
      endpoints.create("i-ep", null, EndpointService.StartFrom.EMPTY, null);
      EndpointSnapshot.Pair query = pair(wiring.add("i-ep", "i-stage", null), "sql_query");
      assertThat(engine.decide(query.view(), query.config(), Classification.read()).limits().maxRows())
          .as("stage inherits dev's 50").isEqualTo(50);

      core.jdbc().update("update policy set sections = '{\"sql\":{\"rowLimit\":51}}' where env = 'dev'");
      clearPolicyCache();
      Decision denied = engine.decide(query.view(), query.config(), Classification.read());
      assertThat(denied.outcome()).as("not served on defaults (1,000 rows)").isEqualTo(Decision.Outcome.DENY);
      assertThat(denied.ruleId()).isEqualTo("core.integrity");
      PolicyService.EnvPolicy stage = policies.get(Env.STAGE);
      assertThat(stage.verified()).as("its own row is fine").isTrue();
      assertThat(stage.serving()).isFalse();
      assertThat(alerts.has("policy", "stage")).isTrue();

      policies.confirmIntegrity(Env.DEV, "I set 51");
      Decision allowed = engine.decide(query.view(), query.config(), Classification.read());
      assertThat(allowed.outcome()).isEqualTo(Decision.Outcome.ALLOW);
      assertThat(allowed.limits().maxRows()).isEqualTo(51);
      assertThat(alerts.has("policy", "dev")).isFalse();
      assertThat(alerts.has("policy", "stage")).isFalse();
    } finally {
      policies.reset(Env.DEV, "back to defaults");
    }
  }

  @Test
  void aParentChangeIsRecordedForItsChildrenAndSavingDefaultsPinsNothing() {
    PolicyService policies = core.bean(PolicyService.class);
    try {
      long lastId = core.jdbc().queryForObject("select coalesce(max(id), 0) from setup_change", Long.class);
      PolicyService.EnvPolicy dev = policies.get(Env.DEV);
      // A whole form saved as it is: dev's own defaults, nothing stored, stage keeps its stricter default.
      PolicyService.EnvPolicy same = policies.update(Env.DEV, TestCore.json(
          "{\"sql\":{\"denyDdl\":false,\"rowLimit\":1000,\"statementTimeoutMs\":5000}}"), dev.version(), "save", null);
      assertThat(same.version()).isEqualTo(dev.version());
      assertThat(same.explicit().has("sql")).isFalse();
      assertThat(policies.effective(Env.STAGE).at("/sql/denyDdl").asBoolean()).isTrue();
      assertThat(policyChanges(lastId)).isEmpty();

      PolicyService.EnvPolicy slower = policies.update(Env.DEV,
          TestCore.json("{\"approvals\":{\"timeoutMinutes\":15}}"), dev.version(), "slower reviews", null);
      assertThat(policies.effective(Env.STAGE).at("/approvals/timeoutMinutes").asInt()).isEqualTo(15);
      assertThat(policyChanges(lastId)).containsExactly(
          "dev policy · approvals.timeoutMinutes 5→15 dev ",
          "stage policy · approvals.timeoutMinutes 10→15 stage dev");

      // Back to dev's default: not stored, so stage has its own default again, and says so.
      long secondId = core.jdbc().queryForObject("select max(id) from setup_change where what = 'policy'",
          Long.class);
      policies.update(Env.DEV, TestCore.json("{\"approvals\":{\"timeoutMinutes\":5}}"), slower.version(), null, null);
      assertThat(policies.get(Env.DEV).explicit().has("approvals")).isFalse();
      assertThat(policies.effective(Env.STAGE).at("/approvals/timeoutMinutes").asInt()).isEqualTo(10);
      assertThat(policyChanges(secondId)).containsExactly(
          "dev policy · approvals.timeoutMinutes 15→5 dev ",
          "stage policy · approvals.timeoutMinutes 15→10 stage dev");
    } finally {
      policies.reset(Env.DEV, "back to defaults");
    }
  }

  /** Policy Setup changes after {@code id}: subject, from→to, env, the environment it was inherited from. */
  private static List<String> policyChanges(long id) {
    return core.jdbc().queryForList("select subject, change_from, change_to, env, details from setup_change where "
        + "what = 'policy' and id > ? order by id", id).stream().map(r -> r.get("subject") + " " + r.get("change_from")
        + "→" + r.get("change_to") + " " + r.get("env") + " " + (r.get("details") == null ? ""
        : TestCore.json(r.get("details").toString()).path("inheritedFrom").asString())).toList();
  }

  @Test
  void revokingAPrincipalRejectsWhatItStillWaitsFor() {
    core.connection("v-db", "pgfake", Env.DEV, "db.test");
    endpoints.create("v-ep", null, EndpointService.StartFrom.EMPTY, null);
    wiring.add("v-ep", "v-db", null);
    String endpointId = endpoints.get("v-ep").id();
    String dbId = core.bean(ai.msrv.gaterook.core.connections.ConnectionConfigs.class).byName("v-db").orElseThrow().id();
    TokenService tokens = core.bean(TokenService.class);
    ToolGuard guard = core.bean(ToolGuard.class);

    TokenService.Issued token = tokens.issue("v-ep", "ci-smoke", null, null);
    String mine = core.pendingApproval(endpointId, "v-ep", dbId, "v-db", "sql_execute", token.id());
    String someoneElses = core.pendingApproval(endpointId, "v-ep", dbId, "v-db", "sql_execute", "tok_other");
    assertThat(guard.admit(token.id(), "sql_query", 1, 1).allowed()).isTrue();
    assertThat(guard.admit(token.id(), "sql_query", 1, 1).allowed()).isFalse();

    assertThat(tokens.revoke(token.id(), "leaked")).isEqualTo(1);
    Map<String, Object> rejected = core.jdbc().queryForMap("select status, decided_via, comment, error from approval "
        + "where id = ?", mine);
    assertThat(rejected).containsEntry("status", "rejected").containsEntry("decided_via", "core")
        .containsEntry("error", "Access of ci-smoke to v-ep was revoked. Not executed.");
    assertThat(rejected.get("comment")).as("no person commented").isNull();
    assertThat(core.jdbc().queryForObject("select count(*) from approval_event where approval_id = ? and kind = "
        + "'decided'", Integer.class, mine)).isEqualTo(1);
    assertThat(core.jdbc().queryForObject("select status from approval where id = ?", String.class, someoneElses))
        .isEqualTo("pending");
    TestCore.await(() -> guard.admit(token.id(), "sql_query", 1, 1).allowed(), "its limiters to be forgotten");
    assertThat(tokens.revoke(token.id(), null)).as("once").isZero();

    // A grant: its bridge tokens stop and its requests end with it.
    String bridge = TokenService.BRIDGE_PREFIX + "bridge-token-of-the-revoked-grant-0123456789";
    // Grants are signed rows: the clients module creates them through ClientGrants (K1).
    String signedGrant = core.bean(ClientGrants.class).create(null, "codex", "v-ep", "codex-laptop", null).id();
    String grantId = new TransactionTemplate(core.bean(PlatformTransactionManager.class)).execute(s -> {
      ClientGrantEntity g = core.bean(ClientGrantRepository.class).findById(signedGrant).orElseThrow();
      core.bean(ApiTokenRepository.class).save(ApiTokenEntity.bridge(g, TokenService.hash(bridge), Instant.now(),
          Instant.now().plusSeconds(900)));
      return g.getId();
    });
    String grants = core.pendingApproval(endpointId, "v-ep", dbId, "v-db", "sql_execute", grantId);
    CallerAuthenticator auth = core.bean(CallerAuthenticator.class);
    assertThat(auth.authenticate(new Authenticator.Request(Ingress.LOCAL, "v-ep", bridge, null))).isPresent();
    assertThat(tokens.revokeGrant(grantId, "laptop lost")).isEqualTo(1);
    assertThat(auth.authenticate(new Authenticator.Request(Ingress.LOCAL, "v-ep", bridge, null))).isEmpty();
    assertThat(core.jdbc().queryForObject("select status from approval where id = ?", String.class, grants))
        .isEqualTo("rejected");
    assertThat(core.jdbc().queryForObject("select count(*) from api_token where grant_id = ? and revoked_at is null",
        Integer.class, grantId)).isZero();
    // Created, then revoked: each a Setup change of the grant.
    assertThat(core.jdbc().queryForList("select coalesce(change_from, '-') || ' → ' || change_to from setup_change "
        + "where what = 'grant' and subject = 'Grant codex-laptop (codex) for v-ep' order by id", String.class))
        .containsExactly("- → Active", "Active → Revoked");
  }

  @Test
  void theConnectionsOfOneTypeShareOneViewAndPublishAsOneTool() {
    core.connection("v-vm-a", "sshfake", Env.DEV, "vm.test");
    core.connection("v-vm-b", "sshfake", Env.DEV, "vm.test");
    TemplateService templates = core.bean(TemplateService.class);
    templates.create("ssh-runbook", "v-vm-a", "compose-ps", TestCore.json("{\"name\":\"compose-ps\"}"), null,
        null);
    templates.create("ssh-runbook", "v-vm-b", "restart-api", TestCore.json("{\"name\":\"restart-api\"}"), null,
        null);
    endpoints.create("view-ep", null, EndpointService.StartFrom.EMPTY, null);
    wiring.add("view-ep", "v-vm-a", null);
    EndpointSnapshot s = wiring.add("view-ep", "v-vm-b", null);

    ToolCatalog.Catalog list = catalog.forEndpoint(s);
    assertThat(list.problems()).as("one definition for both pairs: nothing differs").isEmpty();
    ToolCatalog.PublishedTool run = list.tool("ssh_run").orElseThrow();
    assertThat(run.pairs()).extracting(EndpointSnapshot.Pair::connectionName).containsExactly("v-vm-a", "v-vm-b");
    assertThat(run.inputSchema().at("/properties/connection/enum")).extracting(n -> n.asString())
        .containsExactly("v-vm-a", "v-vm-b");
    assertThat(run.inputSchema().at("/properties/runbook/enum")).as("the runbooks of both hosts can be called")
        .extracting(n -> n.asString()).containsExactly("compose-ps", "restart-api");
    assertThat(run.inputSchema().at("/allOf")).hasSize(2);
    assertThat(run.inputSchema().at("/allOf/1/then/properties/runbook/enum/0").asString()).isEqualTo("restart-api");
    assertThat(run.description()).contains("v-vm-a: compose-ps.").contains("v-vm-b: restart-api.");

    // The connector was asked once with both connections, their limits and the contributions of both.
    EndpointView view = FakeConnectors.SSH_VIEWS.stream()
        .filter(v -> v.slug().equals("view-ep")).reduce((a, b) -> b).orElseThrow();
    assertThat(view.connections()).extracting(c -> c.name()).containsExactly("v-vm-a", "v-vm-b");
    assertThat(view.limitsByConnection()).hasSize(2);
    assertThat(view.contributions()).extracting(c -> c.value().path("name").asString())
        .containsExactlyInAnyOrder("compose-ps", "restart-api");
    // What adding a third one would publish is asked the same way.
    core.connection("v-vm-c", "sshfake", Env.DEV, "vm.test");
    wiring.preview("view-ep", "v-vm-c");
    assertThat(FakeConnectors.SSH_VIEWS.stream().filter(v -> v.slug().equals("view-ep"))
        .reduce((a, b) -> b).orElseThrow().connections()).extracting(c -> c.name())
        .containsExactly("v-vm-a", "v-vm-b", "v-vm-c");
  }

  @Test
  void anEndpointWithoutConnectionsCrossesTheProdLineOnlyWithoutAccess() {
    core.connection("pl-prod", "pgfake", Env.PROD, "db.test");
    core.connection("pl-dev", "pgfake", Env.DEV, "db.test");
    TokenService tokens = core.bean(TokenService.class);

    // An empty dev endpoint with a live token would turn into a prod endpoint that keeps it.
    endpoints.create("pl-empty", null, EndpointService.StartFrom.EMPTY, null);
    TokenService.Issued token = tokens.issue("pl-empty", "agent", null, null);
    assertThat(wiring.preview("pl-empty", "pl-prod").refusal())
        .contains("pl-empty is a dev endpoint with 1 live token").contains("create a prod endpoint for pl-prod");
    assertThatThrownBy(() -> wiring.add("pl-empty", "pl-prod", null)).isInstanceOfSatisfying(ProblemException.class,
        e -> {
          assertThat(e.kind()).isEqualTo(ProblemException.Kind.UNPROCESSABLE);
          assertThat(e.field()).isEqualTo("/connection");
        });
    assertThat(endpoints.get("pl-empty").env()).isEqualTo(Env.DEV);
    assertThat(wiring.preview("pl-empty", "pl-dev").refusal()).as("dev stays dev").isNull();
    tokens.revoke(token.id(), "rotated");
    assertThat(wiring.add("pl-empty", "pl-prod", null).env()).as("nothing left to carry across").isEqualTo(Env.PROD);

    // Emptied, it stays a prod endpoint; with a client grant it takes no dev connection.
    wiring.remove("pl-empty", "pl-prod", null);
    assertThat(endpoints.get("pl-empty").env()).isEqualTo(Env.PROD);
    new TransactionTemplate(core.bean(PlatformTransactionManager.class)).executeWithoutResult(st -> core
        .bean(ClientGrantRepository.class).save(new ClientGrantEntity("codex", core.bean(EndpointRepository.class)
            .findBySlug("pl-empty").orElseThrow(), "codex-laptop", Instant.now())));
    assertThat(wiring.preview("pl-empty", "pl-dev").refusal()).contains("pl-empty is a prod endpoint with 1 client "
        + "grant").contains("create a dev or stage endpoint for pl-dev");
    assertThatThrownBy(() -> wiring.add("pl-empty", "pl-dev", null)).isInstanceOf(ProblemException.class)
        .hasMessageContaining("Revoke them first");
    assertThat(wiring.add("pl-empty", "pl-prod", null).env()).as("its own side still takes it").isEqualTo(Env.PROD);

    // A published endpoint takes no prod connection: a prod endpoint is never published.
    endpoints.create("pl-pub", null, EndpointService.StartFrom.EMPTY, null);
    new TransactionTemplate(core.bean(PlatformTransactionManager.class)).executeWithoutResult(st -> {
      var rows = core.bean(EndpointRows.class);
      var e = core.bean(EndpointRepository.class).findBySlug("pl-pub").orElseThrow();
      assertThat(rows.verify(e)).isTrue();
      e.setPublished(true);
      rows.save(e);
    });
    snapshots.invalidateAll();
    assertThat(wiring.preview("pl-pub", "pl-prod").refusal()).contains("pl-pub is published through the tunnel");
    assertThatThrownBy(() -> wiring.add("pl-pub", "pl-prod", null)).isInstanceOf(ProblemException.class)
        .hasMessageContaining("Unpublish pl-pub first");
    EndpointConnections.Preview dev = wiring.preview("pl-pub", "pl-dev");
    assertThat(dev.refusal()).isNull();
    assertThat(dev.warnings()).anyMatch(w -> w.startsWith("Dev data on a public endpoint"));
  }

  @Test
  void aTemplateEditedOutsideTheCoreServesNothingUntilReviewed() {
    core.connection("tm-a", "pgfake", Env.DEV, "db.test");
    core.connection("tm-b", "pgfake", Env.DEV, "db.test");
    String a = core.bean(ConnectionService.class).summary("tm-a").id();
    String b = core.bean(ConnectionService.class).summary("tm-b").id();
    TemplateService templates = core.bean(TemplateService.class);
    IntegrityAlerts alerts = core.bean(IntegrityAlerts.class);
    String id = templates.create("sql-query", "tm-a", null,
        TestCore.json("{\"name\":\"orders_tm\",\"sql\":\"select 1\"}"), null, null).template().id();
    templates.update(id, TestCore.json("{\"name\":\"orders_tm\",\"sql\":\"select 2\"}"), 1, null, null);
    endpoints.create("tm-ep", null, EndpointService.StartFrom.EMPTY, null);
    wiring.add("tm-ep", "tm-b", null);
    assertThat(templates.compiledFor(a)).singleElement().satisfies(c -> assertThat(c.version()).isEqualTo(2));

    // (a) Repointed at another connection of the same type: neither runs it, no pair joins tm-ep.
    core.jdbc().update("update template set connection_id = ? where id = ?", b, id);
    assertThat(templates.compiledFor(b)).isEmpty();
    assertThat(templates.compiledFor(a)).isEmpty();
    wiring.reconcile(b, null);
    snapshots.invalidateAll();
    EndpointSnapshot s = snapshots.bySlug("tm-ep").orElseThrow();
    assertThat(s.pairs()).noneMatch(p -> p.toolKey().startsWith("tpl:" + id));
    assertThat(core.jdbc().queryForObject("select count(*) from endpoint_tool where tool_key like ?", Integer.class,
        "tpl:" + id + ":%")).as("no row signed for it").isZero();
    assertThat(alerts.current()).filteredOn(al -> al.kind().equals("template") && al.id().equals(id)).hasSize(1);
    assertThat(templates.get(id).verified()).isFalse();
    core.jdbc().update("update template set connection_id = ? where id = ?", a, id);
    assertThat(templates.compiledFor(a)).as("as signed again").hasSize(1);

    // (b) Rolled back to an older, still signed version.
    core.jdbc().update("update template set current_version = 1 where id = ?", id);
    assertThat(templates.compiledFor(a)).isEmpty();
    core.jdbc().update("update template set current_version = 2 where id = ?", id);
    assertThat(templates.compiledFor(a)).hasSize(1);

    // (c) Switched on again outside the core.
    templates.setStatus(id, false, "paused");
    assertThat(templates.compiledFor(a)).isEmpty();
    core.jdbc().update("update template set status = 'active' where id = ?", id);
    assertThat(templates.compiledFor(a)).isEmpty();
    assertThatThrownBy(() -> templates.setStatus(id, true, null)).isInstanceOfSatisfying(ProblemException.class,
        e -> assertThat(e.kind()).isEqualTo(ProblemException.Kind.CONFLICT));

    // The review keeps only what an ordinary change could have made: not a rollback.
    core.jdbc().update("update template set current_version = 1 where id = ?", id);
    assertThatThrownBy(() -> templates.confirmIntegrity(id, "looked")).isInstanceOfSatisfying(ProblemException.class,
        e -> {
          assertThat(e.kind()).isEqualTo(ProblemException.Kind.UNPROCESSABLE);
          assertThat(e.getMessage()).contains("points at v1, but its newest version is v2");
        });
    core.jdbc().update("update template set current_version = 2 where id = ?", id);
    TemplateService.TemplateInfo kept = templates.confirmIntegrity(id, "I switched it on");
    assertThat(kept.verified()).isTrue();
    assertThat(kept.status()).isEqualTo("active");
    assertThat(templates.compiledFor(a)).hasSize(1);
    assertThat(alerts.has("template", id)).isFalse();
    assertThat(core.jdbc().queryForObject("select change_to from setup_change where subject = 'Template orders_tm "
        + "changed outside GateRook'", String.class)).isEqualTo("Kept as stored: v2, active, runs on tm-a");
  }

  @Test
  void aReadOnlyConfirmationOfAnMcpServerToolIsBoundToTheServersVersion() {
    ConnectionService connections = core.bean(ConnectionService.class);
    core.connection("srv-mcp", "mcpfake", Env.DEV, "mcp.test");
    endpoints.create("srv-ep", null, EndpointService.StartFrom.EMPTY, null);
    EndpointSnapshot s = wiring.add("srv-ep", "srv-mcp", null);
    EndpointSnapshot.Pair search = pair(s, "gh_search");
    assertThat(search.toolKey()).isEqualTo("mcp:srv-mcp:gh_search");
    assertThat(search.mode()).as("MCP server tools start in Approval").isEqualTo(Mode.APPROVAL);
    assertThat(search.provenanceVersion()).as("the server's version, not the type card's 0.4.0").isEqualTo("1.0.0");
    tools.confirmReadOnly("srv-ep", search.toolKey(), "srv-mcp",
        ToolPairs.definitionHash(search.tool()), "only reads");
    assertThat(core.jdbc().queryForObject("select json_extract(ro_confirmation, '$.version') from endpoint_tool "
        + "where tool_key = 'mcp:srv-mcp:gh_search'", String.class)).isEqualTo("1.0.0");
    EndpointSnapshot.Pair confirmed = pair(snapshots.bySlug("srv-ep").orElseThrow(), "gh_search");
    tools.setMode("srv-ep", confirmed.toolKey(), "srv-mcp", Mode.READ_ONLY, null, confirmed.rowVersion(), null);
    assertThat(pair(snapshots.bySlug("srv-ep").orElseThrow(), "gh_search").mode()).isEqualTo(Mode.READ_ONLY);

    // The upstream server reports a new version: the confirmation no longer counts.
    var config = TestCore.config("mcp.test");
    ((ObjectNode) config.get("connection")).put("note", "2.0.0");
    connections.update("srv-mcp", Env.DEV, config, connections.get("srv-mcp").version(), "server upgraded", null);
    TestCore.await(() -> {
      EndpointSnapshot.Pair p = pair(snapshots.bySlug("srv-ep").orElseThrow(), "gh_search");
      return !p.readOnlyConfirmed() && p.mode() == Mode.APPROVAL;
    }, "the confirmation of the old server version to lapse");
    assertThat(pair(snapshots.bySlug("srv-ep").orElseThrow(), "gh_search").provenanceVersion()).isEqualTo("2.0.0");
  }

  @Test
  void aReadOnlyPairThatStartsChangingDataGoesToApprovalOrOffOnAReadOnlyEndpoint() {
    core.connection("rc-db", "pgfake", Env.DEV, "db.test");
    endpoints.create("rc-std", null, EndpointService.StartFrom.EMPTY, null);
    endpoints.create("rc-ro", null, EndpointService.StartFrom.EMPTY, null);
    endpoints.updateSettings("rc-ro", new EndpointService.SettingsUpdate(null, "read-only", null, null, null, null,
        null), endpoints.get("rc-ro").version(), null);
    wiring.add("rc-std", "rc-db", null);
    wiring.add("rc-ro", "rc-db", null);
    TemplateService templates = core.bean(TemplateService.class);
    String id = templates.create("sql-query", "rc-db", null,
        TestCore.json("{\"name\":\"rc_orders\",\"sql\":\"select 1\"}"), null, null).template().id();
    String key = "tpl:" + id + ":q";
    String modes = "select e.slug || '=' || t.mode from endpoint_tool t join endpoint e on e.id = t.endpoint_id "
        + "where t.tool_key = ? order by e.slug";
    TestCore.await(() -> core.jdbc().queryForList(modes, String.class, key).size() == 2, "the template's rows");
    assertThat(core.jdbc().queryForList(modes, String.class, key)).containsExactly("rc-ro=READ_ONLY",
        "rc-std=READ_ONLY");

    templates.update(id, TestCore.json("{\"name\":\"rc_orders\",\"sql\":\"delete\",\"mutates\":true}"), 1, null,
        null);
    TestCore.await(() -> !core.jdbc().queryForList(modes, String.class, key).contains("rc-std=READ_ONLY")
        && !core.jdbc().queryForList(modes, String.class, key).contains("rc-ro=READ_ONLY"), "the modes to follow");
    // A read-only endpoint holds what changes data Off: the row and the Setup change say what it really does.
    assertThat(core.jdbc().queryForList(modes, String.class, key)).containsExactly("rc-ro=OFF", "rc-std=APPROVAL");
    assertThat(core.jdbc().queryForList("select endpoint_slug || ':' || change_to from setup_change where what = "
        + "'mode' and subject like 'rc_orders on rc-db%' order by id", String.class))
        .containsExactlyInAnyOrder("rc-std:Approval", "rc-ro:Off");
    assertThat(mode(snapshots.bySlug("rc-ro").orElseThrow(), "rc_orders", "rc-db")).isEqualTo(Mode.OFF);
  }

  @Test
  void aReviewKeepsOnlyWhatAnOrdinaryChangeCouldHaveStored() {
    PolicyService policies = core.bean(PolicyService.class);
    String original = core.jdbc().queryForObject("select sections from policy where env = 'dev'", String.class);
    try {
      // Values the API refuses don't get in through "I reviewed it" either.
      core.jdbc().update("update policy set sections = '{\"sql\":{\"rowLimit\":-5}}' where env = 'dev'");
      clearPolicyCache();
      assertThatThrownBy(() -> policies.confirmIntegrity(Env.DEV, "looked")).isInstanceOfSatisfying(
          ProblemException.class, e -> {
            assertThat(e.kind()).isEqualTo(ProblemException.Kind.UNPROCESSABLE);
            assertThat(e.field()).isEqualTo("/sections/sql/rowLimit");
          });
      core.jdbc().update("update policy set sections = '{\"sql\":{\"readOnlyTransactions\":false}}' where env = 'dev'");
      assertThatThrownBy(() -> policies.confirmIntegrity(Env.DEV, "looked")).isInstanceOf(ProblemException.class)
          .hasMessageContaining("always on");
      core.jdbc().update("update policy set sections = '{\"approvals\":{\"approvers\":2}}' where env = 'dev'");
      assertThatThrownBy(() -> policies.confirmIntegrity(Env.DEV, "looked")).isInstanceOf(ProblemException.class);
      core.jdbc().update("update policy set sections = '{\"limits\":{\"toolTimeoutSeconds\":0}}' where env = 'dev'");
      assertThatThrownBy(() -> policies.confirmIntegrity(Env.DEV, "looked")).isInstanceOfSatisfying(
          ProblemException.class, e -> assertThat(e.field()).isEqualTo("/sections/limits/toolTimeoutSeconds"));
      assertThat(policies.get(Env.DEV).verified()).as("nothing was signed").isFalse();
    } finally {
      // Back to the signed content: its MAC verifies again.
      core.jdbc().update("update policy set sections = ? where env = 'dev'", original);
      clearPolicyCache();
    }
    assertThat(policies.get(Env.DEV).verified()).isTrue();

    // Keeping a danger value on prod needs the danger confirmation, as setting it does.
    String prod = core.jdbc().queryForObject("select sections from policy where env = 'prod'", String.class);
    ObjectNode withExec = (ObjectNode) TestCore.json(prod);
    (withExec.has("ssh") ? (ObjectNode) withExec.get("ssh") : withExec.putObject("ssh")).put("allowExec", true);
    core.jdbc().update("update policy set sections = ? where env = 'prod'", withExec.toString());
    clearPolicyCache();
    assertThatThrownBy(() -> policies.confirmIntegrity(Env.PROD, "looked")).isInstanceOfSatisfying(
        ProblemException.class, e -> assertThat(e.confirm()).isEqualTo("danger"));
    policies.confirmIntegrity(Env.PROD, "I enabled exec", "danger");
    assertThat(policies.get(Env.PROD).verified()).isTrue();
    policies.update(Env.PROD, TestCore.json("{\"ssh\":{\"allowExec\":null}}"), policies.get(Env.PROD).version(),
        "back", "danger");

    // An endpoint has no reset of its own: what can't be kept is reset on the person's confirmation.
    core.connection("rv-db", "pgfake", Env.DEV, "db.test");
    endpoints.create("rv-ep", null, EndpointService.StartFrom.EMPTY, null);
    wiring.add("rv-ep", "rv-db", null);
    core.jdbc().update("update endpoint set settings = json_set(settings, '$.limits.rowLimit', -5, "
        + "'$.approvalWait.firstWaitSeconds', 90) where slug = 'rv-ep'");
    core.jdbc().update("update endpoint_tool set overrides = '{\"toolTimeoutSeconds\":0,\"rowLimit\":10}' where "
        + "tool_key = 'sql_query' and endpoint_id = (select id from endpoint where slug = 'rv-ep')");
    assertThatThrownBy(() -> endpoints.confirmIntegrity("rv-ep", "looked")).isInstanceOfSatisfying(
        ProblemException.class, e -> {
          assertThat(e.kind()).isEqualTo(ProblemException.Kind.UNPROCESSABLE);
          assertThat(e.field()).isEqualTo("/limits/rowLimit");
          assertThat(e.confirm()).isEqualTo("reset-invalid");
        });
    snapshots.invalidateAll();
    assertThat(snapshots.bySlug("rv-ep").orElseThrow().blocked()).isTrue();
    endpoints.confirmIntegrity("rv-ep", "reset what is wrong", "reset-invalid");
    EndpointSnapshot reviewed = snapshots.bySlug("rv-ep").orElseThrow();
    assertThat(reviewed.blocked()).isFalse();
    assertThat(reviewed.settings().path("limits").has("rowLimit")).isFalse();
    assertThat(reviewed.settings().at("/approvalWait/firstWaitSeconds").asInt()).isEqualTo(15);
    assertThat(pair(reviewed, "sql_query").overrides()).as("the valid override stays")
        .isEqualTo(TestCore.json("{\"rowLimit\":10}"));
    assertThat(core.jdbc().queryForObject("select change_to from setup_change where subject = 'Endpoint rv-ep "
        + "changed outside GateRook'", String.class))
        .isEqualTo("Kept as stored, reset: limits.rowLimit, approvalWait, sql_query overrides");
  }

  @Test
  void connectorTypeChangesAnswerAsProblems() {
    ConnectorRegistry registry = core.bean(ConnectorRegistry.class);
    assertThatThrownBy(() -> registry.setEnabled("nosuchtype", false, null)).isInstanceOfSatisfying(
        ProblemException.class, e -> assertThat(e.kind()).isEqualTo(ProblemException.Kind.NOT_FOUND));
    assertThatThrownBy(() -> registry.confirmIntegrity("nosuchtype", null)).isInstanceOfSatisfying(
        ProblemException.class, e -> assertThat(e.kind()).isEqualTo(ProblemException.Kind.NOT_FOUND));
    core.jdbc().update("update connector_type set impl_version = 'edited' where id = 'httpfake'");
    try {
      assertThatThrownBy(() -> registry.setEnabled("httpfake", false, null)).isInstanceOfSatisfying(
          ProblemException.class, e -> {
            assertThat(e.kind()).isEqualTo(ProblemException.Kind.CONFLICT);
            assertThat(e.getMessage()).contains("FakeHTTP was changed outside GateRook");
          });
    } finally {
      registry.confirmIntegrity("httpfake", "test edit");
    }
    assertThat(registry.usable("httpfake")).isTrue();
  }

  /**
   * {@code synchronize()} reads the rows outside the writer's transaction; a switch committed between that read and its
   * update of the switches in memory would be overwritten with the value it read, and a type turned off would keep
   * serving. The switch therefore waits for a synchronization in progress (the registry's monitor) and wins after it.
   */
  @Test
  void aSwitchWaitsForASynchronizationInProgressAndWins() throws Exception {
    ConnectorRegistry registry = core.bean(ConnectorRegistry.class);
    Thread off = new Thread(() -> registry.setEnabled("httpfake", false, "test"), "switch-off");
    // The bean is a proxy (@Async listener): the monitor is the target's.
    Object monitor = AopTestUtils.getUltimateTargetObject(registry);
    try {
      synchronized (monitor) {
        off.start();
        long until = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (off.getState() != Thread.State.BLOCKED && System.nanoTime() < until) {
          Thread.sleep(5);
        }
        assertThat(off.getState()).as("the switch waits for the synchronization").isEqualTo(Thread.State.BLOCKED);
        registry.synchronize();
        assertThat(registry.usable("httpfake")).isTrue();
      }
      off.join(10_000);
      assertThat(registry.usable("httpfake")).as("the switch, not the synchronization's read, is what stays")
          .isFalse();
      registry.synchronize();
      assertThat(registry.usable("httpfake")).isFalse();
    } finally {
      registry.setEnabled("httpfake", true, "test");
    }
    assertThat(registry.usable("httpfake")).isTrue();
  }

  private static EndpointSnapshot.Pair pair(EndpointSnapshot s, String tool) {
    return s.pairs().stream().filter(p -> p.tool().name().equals(tool)).findFirst().orElseThrow();
  }

  /**
   * The policy cache is refreshed by its own events; the test edits the table behind its back. Synchronously: its
   * listeners are {@code @Async}, so calling one through the bean would race the next assertion.
   */
  private static void clearPolicyCache() {
    core.bean(PolicyService.class).invalidateAll();
  }
}
