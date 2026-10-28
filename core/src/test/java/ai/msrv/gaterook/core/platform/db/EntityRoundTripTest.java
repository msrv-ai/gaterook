package ai.msrv.gaterook.core.platform.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.core.approvals.internal.persistence.ApprovalEntity;
import ai.msrv.gaterook.core.approvals.internal.persistence.ApprovalEventEntity;
import ai.msrv.gaterook.core.approvals.internal.persistence.ApprovalEventKind;
import ai.msrv.gaterook.core.approvals.internal.persistence.ApprovalEventRepository;
import ai.msrv.gaterook.core.approvals.internal.persistence.ApprovalRepository;
import ai.msrv.gaterook.core.approvals.internal.persistence.ApprovalStatus;
import ai.msrv.gaterook.core.approvals.internal.persistence.CommentPolicy;
import ai.msrv.gaterook.core.audit.internal.persistence.AuditExportEntity;
import ai.msrv.gaterook.core.audit.internal.persistence.AuditExportRepository;
import ai.msrv.gaterook.core.audit.internal.persistence.CallDecision;
import ai.msrv.gaterook.core.audit.internal.persistence.CallPhase;
import ai.msrv.gaterook.core.audit.internal.persistence.ExportKind;
import ai.msrv.gaterook.core.audit.internal.persistence.SetupChangeEntity;
import ai.msrv.gaterook.core.audit.internal.persistence.SetupChangeRepository;
import ai.msrv.gaterook.core.audit.internal.persistence.ToolCallEntity;
import ai.msrv.gaterook.core.audit.internal.persistence.ToolCallRepository;
import ai.msrv.gaterook.core.audit.internal.persistence.TraceEntity;
import ai.msrv.gaterook.core.audit.internal.persistence.TraceRepository;
import ai.msrv.gaterook.core.audit.internal.persistence.TraceTitleSource;
import ai.msrv.gaterook.core.clients.internal.persistence.ClientKind;
import ai.msrv.gaterook.core.clients.internal.persistence.ClientLinkEntity;
import ai.msrv.gaterook.core.clients.internal.persistence.ClientLinkRepository;
import ai.msrv.gaterook.core.clients.internal.persistence.LinkMethod;
import ai.msrv.gaterook.core.connections.internal.persistence.ConnectionEntity;
import ai.msrv.gaterook.core.connections.internal.persistence.ConnectionRepository;
import ai.msrv.gaterook.core.connectors.internal.persistence.ConnectorTypeEntity;
import ai.msrv.gaterook.core.connectors.internal.persistence.ConnectorTypeRepository;
import ai.msrv.gaterook.core.connectors.internal.persistence.PluginEntity;
import ai.msrv.gaterook.core.connectors.internal.persistence.PluginRepository;
import ai.msrv.gaterook.core.connectors.internal.persistence.PluginSource;
import ai.msrv.gaterook.core.endpoints.internal.persistence.ApiTokenEntity;
import ai.msrv.gaterook.core.endpoints.internal.persistence.ApiTokenRepository;
import ai.msrv.gaterook.core.endpoints.internal.persistence.ClientGrantEntity;
import ai.msrv.gaterook.core.endpoints.internal.persistence.ClientGrantRepository;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointAccess;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointConnectionEntity;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointConnectionRepository;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointEntity;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointRepository;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointToolEntity;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointToolRepository;
import ai.msrv.gaterook.core.endpoints.internal.persistence.TokenKind;
import ai.msrv.gaterook.core.mcpservers.internal.persistence.McpServerEntity;
import ai.msrv.gaterook.core.mcpservers.internal.persistence.McpServerRepository;
import ai.msrv.gaterook.core.notifications.internal.persistence.NotificationChannelEntity;
import ai.msrv.gaterook.core.notifications.internal.persistence.NotificationChannelRepository;
import ai.msrv.gaterook.core.notifications.internal.persistence.NotifyChannel;
import ai.msrv.gaterook.core.notifications.internal.persistence.NotifyDeliveryEntity;
import ai.msrv.gaterook.core.notifications.internal.persistence.NotifyDeliveryRepository;
import ai.msrv.gaterook.core.notifications.internal.persistence.NotifyLinkEntity;
import ai.msrv.gaterook.core.notifications.internal.persistence.NotifyLinkRepository;
import ai.msrv.gaterook.core.notifications.internal.persistence.NotifyPairingEntity;
import ai.msrv.gaterook.core.notifications.internal.persistence.NotifyPairingRepository;
import ai.msrv.gaterook.core.oauth.internal.persistence.OAuthClientEntity;
import ai.msrv.gaterook.core.oauth.internal.persistence.OAuthClientKind;
import ai.msrv.gaterook.core.oauth.internal.persistence.OAuthClientRepository;
import ai.msrv.gaterook.core.oauth.internal.persistence.OAuthGrantEntity;
import ai.msrv.gaterook.core.oauth.internal.persistence.OAuthGrantRepository;
import ai.msrv.gaterook.core.oauth.internal.persistence.OAuthTokenEntity;
import ai.msrv.gaterook.core.oauth.internal.persistence.OAuthTokenKind;
import ai.msrv.gaterook.core.oauth.internal.persistence.OAuthTokenRepository;
import ai.msrv.gaterook.core.platform.Caller;
import ai.msrv.gaterook.core.platform.Ingress;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder;
import ai.msrv.gaterook.core.platform.TestGateway;
import ai.msrv.gaterook.core.policy.internal.persistence.PolicyEntity;
import ai.msrv.gaterook.core.policy.internal.persistence.PolicyRepository;
import ai.msrv.gaterook.core.secrets.internal.persistence.ExternalStoreEntity;
import ai.msrv.gaterook.core.secrets.internal.persistence.ExternalStoreRepository;
import ai.msrv.gaterook.core.secrets.internal.persistence.MasterKeyStateEntity;
import ai.msrv.gaterook.core.secrets.internal.persistence.MasterKeyStateRepository;
import ai.msrv.gaterook.core.secrets.internal.persistence.SecretEntity;
import ai.msrv.gaterook.core.secrets.internal.persistence.SecretKind;
import ai.msrv.gaterook.core.secrets.internal.persistence.SecretLeaseEntity;
import ai.msrv.gaterook.core.secrets.internal.persistence.SecretLeaseRepository;
import ai.msrv.gaterook.core.secrets.internal.persistence.SecretOwnerKind;
import ai.msrv.gaterook.core.secrets.internal.persistence.SecretRepository;
import ai.msrv.gaterook.core.secrets.internal.persistence.SecretVersionEntity;
import ai.msrv.gaterook.core.secrets.internal.persistence.SecretVersionRepository;
import ai.msrv.gaterook.core.secrets.internal.persistence.SecretVersionState;
import ai.msrv.gaterook.core.templates.internal.persistence.TemplateBlobEntity;
import ai.msrv.gaterook.core.templates.internal.persistence.TemplateBlobRepository;
import ai.msrv.gaterook.core.templates.internal.persistence.TemplateEntity;
import ai.msrv.gaterook.core.templates.internal.persistence.TemplateRepository;
import ai.msrv.gaterook.core.templates.internal.persistence.TemplateType;
import ai.msrv.gaterook.core.templates.internal.persistence.TemplateVersionEntity;
import ai.msrv.gaterook.core.templates.internal.persistence.TemplateVersionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every entity written and read back through its repository (what {@code validate} can't check: nullability, CHECK
 * constraints, converters, JSON columns, the epoch-millisecond timestamps the audit triggers compare), the lazy
 * associations of enhanced entities, the approval CAS and the cascades and SET NULLs of deleting a connection and an
 * endpoint (ARCHITECTURE.md §4).
 */
@SpringBootTest
class EntityRoundTripTest {

  @DynamicPropertySource
  static void gateway(DynamicPropertyRegistry registry) {
    TestGateway.register(registry, "entities");
  }

  @Autowired PlatformTransactionManager transactions;
  JdbcTemplate jdbc;

  /** The core has no JdbcTemplate bean (JPA only, decision 3): the test makes its own over the primary pool. */
  @Autowired
  void jdbc(javax.sql.DataSource dataSource) {
    jdbc = new JdbcTemplate(dataSource);
  }

  @Autowired Clock clock;
  @Autowired PolicyRepository policies;
  @Autowired ExternalStoreRepository stores;
  @Autowired SecretRepository secrets;
  @Autowired SecretVersionRepository secretVersions;
  @Autowired SecretLeaseRepository leases;
  @Autowired MasterKeyStateRepository masterKeyState;
  @Autowired PluginRepository plugins;
  @Autowired ConnectorTypeRepository connectorTypes;
  @Autowired ConnectionRepository connections;
  @Autowired McpServerRepository mcpServers;
  @Autowired TemplateRepository templates;
  @Autowired TemplateBlobRepository templateBlobs;
  @Autowired TemplateVersionRepository templateVersions;
  @Autowired EndpointRepository endpoints;
  @Autowired EndpointConnectionRepository endpointConnections;
  @Autowired EndpointToolRepository endpointTools;
  @Autowired ClientGrantRepository grants;
  @Autowired ApiTokenRepository tokens;
  @Autowired ClientLinkRepository clientLinks;
  @Autowired OAuthClientRepository oauthClients;
  @Autowired OAuthGrantRepository oauthGrants;
  @Autowired OAuthTokenRepository oauthTokens;
  @Autowired ApprovalRepository approvals;
  @Autowired ApprovalEventRepository approvalEvents;
  @Autowired ToolCallRepository toolCalls;
  @Autowired SetupChangeRepository setupChanges;
  @Autowired TraceRepository traces;
  @Autowired AuditExportRepository exports;
  @Autowired NotificationChannelRepository channels;
  @Autowired NotifyLinkRepository links;
  @Autowired NotifyDeliveryRepository deliveries;
  @Autowired NotifyPairingRepository pairings;

  private <T> T tx(java.util.function.Supplier<T> work) {
    return new TransactionTemplate(transactions).execute(s -> work.get());
  }

  private <T> T readOnly(java.util.function.Supplier<T> work) {
    TransactionTemplate template = new TransactionTemplate(transactions);
    template.setReadOnly(true);
    return template.execute(s -> work.get());
  }

  @Test
  void theWholeModelRoundTripsAndDeletesCascadeAsDesigned() {
    Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
    List<String> existingEndpointToolIds = readOnly(() -> endpointTools.findAll().stream()
        .map(EndpointToolEntity::getId).toList());

    // Settings, policies, secrets ────────────────────────────────────────────────────────────────────────────
    tx(() -> policies.save(new PolicyEntity(Env.PROD, Env.STAGE, Map.of("sql", Map.of("maxRows", 100),
        "approvals", Map.of("comment", "required")), now)));
    ExternalStoreEntity vault = tx(() -> stores.save(new ExternalStoreEntity(
        ai.msrv.gaterook.core.secrets.internal.persistence.StoreKind.VAULT, "vault", Map.of("address",
            "https://vault.internal:8200"), "connected", now)));
    // The core wrote the row when it made the key at this start (its creation date): the test continues that row.
    tx(() -> {
      MasterKeyStateEntity keyState = masterKeyState.findById(MasterKeyStateEntity.ID)
          .orElseGet(MasterKeyStateEntity::new);
      keyState.backedUp(now, List.of(1_234_567_890_123L, 42L));
      return masterKeyState.save(keyState);
    });

    // Connectors, connections ────────────────────────────────────────────────────────────────────────────────
    PluginEntity jira = tx(() -> plugins.save(new PluginEntity("jira-service-desk", "1.2.0", true,
        PluginSource.CATALOG, "a".repeat(64), 1_024, Map.of("id", "jira-service-desk"), now)));
    tx(() -> connectorTypes.save(new ConnectorTypeEntity("postgres", ConnectorManifest.Kind.BUILT_IN, "0.4.0",
        Map.of("id", "postgres"), "b".repeat(64), null, now)));
    tx(() -> connectorTypes.save(new ConnectorTypeEntity("jira-service-desk", ConnectorManifest.Kind.PLUGIN, "1.2.0",
        Map.of("id", "jira-service-desk"), "c".repeat(64), jira, now)));
    tx(() -> connectorTypes.save(new ConnectorTypeEntity("mcp-server", ConnectorManifest.Kind.MCP_SERVER, "0.4.0",
        Map.of("id", "mcp-server"), "d".repeat(64), null, now)));
    ConnectionEntity devDb = new ConnectionEntity("dev-db", "postgres", Env.DEV,
        Map.of("connection", Map.of("host", "db.dev.internal", "port", 5432),
            "credentials", Map.of("password", Map.of("$secret", "s1"))), now);
    devDb.recordHealth(HealthResult.State.HEALTHY, 42L, null, now);
    tx(() -> connections.save(devDb));
    ConnectionEntity github = tx(() -> connections.save(new ConnectionEntity("github-mcp", "mcp-server", Env.DEV,
        Map.of("command", List.of("node", "server.js")), now)));
    tx(() -> mcpServers.save(new McpServerEntity(github.getId(), "github_", List.of(Map.of("exposed",
        "github_list_issues", "definitionHash", "e".repeat(64))), "stopped", now)));

    SecretEntity password = tx(() -> secrets.save(new SecretEntity("dev-db/password", SecretKind.LOCAL,
        SecretOwnerKind.CONNECTION, devDb.getId(), "/credentials/password", now)));
    SecretVersionEntity v1 = new SecretVersionEntity(password, 1, SecretVersionState.CURRENT, 1, now);
    v1.seal(new byte[] {1, 2, 3}, new byte[144], 99L);
    tx(() -> secretVersions.save(v1));
    SecretEntity dynamic = tx(() -> {
      SecretEntity s = new SecretEntity("prod-replica/login", SecretKind.DYNAMIC, SecretOwnerKind.CONNECTION,
          devDb.getId(), "/credentials/login", now);
      s.pointTo(SecretKind.DYNAMIC, stores.findById(vault.getId()).orElseThrow(), "vault://database/creds/ro");
      return secrets.save(s);
    });
    tx(() -> leases.save(new SecretLeaseEntity("database/creds/ro/abc", stores.findById(vault.getId()).orElseThrow(),
        dynamic, now, now.plusSeconds(600))));

    // Templates ──────────────────────────────────────────────────────────────────────────────────────────────
    TemplateEntity template = tx(() -> templates.save(new TemplateEntity(TemplateType.SQL_QUERY, "orders_by_status",
        devDb.getId(), now)));
    tx(() -> templateBlobs.save(new TemplateBlobEntity("f".repeat(64), "application/json", 10, new byte[] {31, 8})));
    tx(() -> templateVersions.save(new TemplateVersionEntity(templates.findById(template.getId()).orElseThrow(), 1,
        Map.of("sql", "select 1"), "f".repeat(64), "9".repeat(64), Map.of("read", 1, "mutation", 0), "first", (byte[]) null,
        now)));
    tx(() -> {
      templates.findById(template.getId()).orElseThrow().advanceTo(1, now);
      return null;
    });

    // Endpoints, tokens, clients ─────────────────────────────────────────────────────────────────────────────
    EndpointEntity dev = new EndpointEntity("dev", Env.DEV, EndpointAccess.STANDARD, Map.of("protocol",
        Map.of("revisions", List.of("2026-07-28", "2025-11-25"))), now);
    dev.setInstructions("You are connected to GateRook, endpoint \"dev\".");
    tx(() -> endpoints.save(dev));
    tx(() -> endpointConnections.save(new EndpointConnectionEntity(endpoints.findBySlug("dev").orElseThrow(),
        devDb.getId(), now)));
    tx(() -> endpointTools.save(new EndpointToolEntity(endpoints.findBySlug("dev").orElseThrow(), devDb.getId(),
        "sql_execute", ToolDefinition.Mode.APPROVAL, now)));
    tx(() -> endpointTools.save(new EndpointToolEntity(endpoints.findBySlug("dev").orElseThrow(), null,
        "approval_status", ToolDefinition.Mode.READ_ONLY, now)));
    ClientGrantEntity grant = tx(() -> grants.save(new ClientGrantEntity("codex", endpoints.findBySlug("dev")
        .orElseThrow(), "codex-laptop", now)));
    tx(() -> tokens.save(ApiTokenEntity.bridge(grants.findById(grant.getId()).orElseThrow(), "h".repeat(43),
        now, now.plus(Duration.ofMinutes(15)))));
    ApiTokenEntity named = tx(() -> tokens.save(ApiTokenEntity.named(endpoints.findBySlug("dev").orElseThrow(),
        "inspector-debug", "gwt_K9xP", "n".repeat(43), now, now.plus(Duration.ofDays(7)))));
    ClientLinkEntity link = new ClientLinkEntity(ClientKind.CLAUDE_DESKTOP, dev.getId(), LinkMethod.MCPB,
        "connected", now);
    link.setGrantId(grant.getId());
    link.setTokenId(named.getId());
    tx(() -> clientLinks.save(link));

    // OAuth ──────────────────────────────────────────────────────────────────────────────────────────────────
    OAuthClientEntity claude = tx(() -> oauthClients.save(new OAuthClientEntity(OAuthClientKind.CIMD, Ingress.TUNNEL,
        "https://claude.ai/oauth/mcp-oauth-client-metadata", "Claude", "claude.ai",
        List.of("https://claude.ai/api/mcp/auth_callback"), now)));
    OAuthGrantEntity consent = tx(() -> oauthGrants.save(new OAuthGrantEntity(oauthClients.findById(claude.getId())
        .orElseThrow(), dev.getId(), "https://abc.trycloudflare.com/mcp/dev", "mcp", now, now.plus(Duration.ofDays(7)))));
    tx(() -> oauthTokens.save(new OAuthTokenEntity("o".repeat(43), oauthGrants.findById(consent.getId()).orElseThrow(),
        OAuthTokenKind.ACCESS, now, now.plus(Duration.ofHours(1)))));

    // Approvals, audit, notifications ────────────────────────────────────────────────────────────────────────
    ApprovalEntity approval = tx(() -> approvals.save(ApprovalEntity.builder()
        .call("tc_aaaaaaaaaaaa", "sql_execute", "sql_execute").endpoint(dev.getId(), "dev")
        .connection(devDb.getId(), "dev-db", Env.DEV).principal(Caller.Kind.BRIDGE, grant.getId(), "codex-laptop")
        .canonical(Map.of("tool", "sql_execute", "arguments", Map.of("sql", "UPDATE orders SET status = 'x'")),
            "1".repeat(64))
        .action(Map.of("kind", "SQL", "text", "UPDATE orders SET status = 'x'"), "2".repeat(64), "[1,2]")
        .rule("dev.sql.write-approval", CommentPolicy.OPTIONAL).window(now, now.plus(Duration.ofMinutes(5)))
        .traceContext("00-4f2a9c71e8d04b5a9c3e2b1f0a6d8e81-7a1c2e9b4d3f6a10-01").build()));
    assertThat(approval.getId()).matches("ap_[a-z0-9]{12}");
    tx(() -> approvalEvents.save(new ApprovalEventEntity(approvals.findById(approval.getId()).orElseThrow(),
        ApprovalEventKind.REQUESTED, Map.of("via", "mcp"), now)));
    ToolCallEntity received = tx(() -> toolCalls.save(ToolCallEntity.builder("tc_aaaaaaaaaaaa", CallPhase.RECEIVED,
            now, "dev", "sql_execute", CallDecision.PENDING)
        .tool("sql_execute", "dev-db", Env.DEV).caller(Caller.Kind.BRIDGE, Ingress.LOCAL, "codex-laptop", "127.0.0.1")
        .client("codex", Map.of("name", "codex-mcp-client"), "2026-07-28").policy(Classification.Kind.MUTATION,
            "dev.sql.write-approval")
        .arguments(Map.of("sql", "UPDATE orders SET status = 'x'"), "3".repeat(64))
        .approval(approval.getId(), null, null, null, "2".repeat(64), null).chain("0".repeat(64), "4".repeat(64))
        .build()));
    tx(() -> setupChanges.save(new SetupChangeEntity(new SetupChangeRecorder.SetupChange(
        SetupChangeRecorder.What.RISK_WARNING, "dev", "shown", "dismissed", "accepted for the demo", Env.DEV, "dev",
        null, null), Map.of("ingredients", List.of("privateData")), "you", now, "0".repeat(64), "5".repeat(64))));
    TraceEntity trace = new TraceEntity("4f2a9c71e8d04b5a9c3e2b1f0a6d8e81", now);
    trace.setTitle("Cancel stuck orders", TraceTitleSource.META);
    trace.summarize(now, 1, "codex", "dev", Map.of("phases", List.of("Explore")));
    tx(() -> traces.save(trace));
    tx(() -> exports.save(new AuditExportEntity(ExportKind.SIEM, "soc-syslog", Map.of("host", "siem.internal"),
        "stream")));
    tx(() -> channels.save(new NotificationChannelEntity(NotifyChannel.SLACK, true, Map.of("deliverTo", "dm"), now)));
    tx(() -> links.save(new NotifyLinkEntity(NotifyChannel.TELEGRAM, null, "123456", "123456", "@you", now)));
    tx(() -> deliveries.save(new NotifyDeliveryEntity(approval.getId(), NotifyChannel.SLACK, "U123", true)));
    tx(() -> pairings.save(new NotifyPairingEntity("p".repeat(43), NotifyChannel.TELEGRAM, now.plusSeconds(600))));

    // Read back: converters, JSON, timestamps, lazy associations ─────────────────────────────────────────────
    readOnly(() -> {
      PolicyEntity prod = policies.findById("prod").orElseThrow();
      assertThat(prod.getEnv()).isEqualTo(Env.PROD);
      assertThat(prod.getInherits()).isEqualTo(Env.STAGE);
      assertThat(prod.getSections()).containsEntry("approvals", Map.of("comment", "required"));
      assertThat(masterKeyState.findById(1).orElseThrow().getLastBackupKeyIds()).containsExactly(1_234_567_890_123L,
          42L);
      assertThat(masterKeyState.findById(1).orElseThrow().getKeyCreatedAt()).as("the key made at this start")
          .isNotNull();
      assertThat(masterKeyState.findById(1).orElseThrow().getKeyRotatedAt()).isNull();

      ConnectionEntity loaded = connections.findByName("dev-db").orElseThrow();
      assertThat(loaded.getEnv()).isEqualTo(Env.DEV);
      assertThat(loaded.getHealthState()).isEqualTo(HealthResult.State.HEALTHY);
      assertThat(loaded.getConfig()).containsKey("credentials");
      assertThat(loaded.getCreatedAt()).isEqualTo(now);

      SecretVersionEntity version = secretVersions.findBySecretIdOrderBySecretVersionDesc(password.getId()).getFirst();
      assertThat(Hibernate.isInitialized(version.getSecret())).as("enhanced lazy to-one").isFalse();
      assertThat(version.getSecret().getName()).isEqualTo("dev-db/password");
      assertThat(version.getWrappedDek()).hasSize(144);
      assertThat(secretVersions.findKeyIdsInUse()).containsExactly(99L);

      ConnectorTypeEntity jiraType = connectorTypes.findById("jira-service-desk").orElseThrow();
      assertThat(jiraType.getKind()).isEqualTo(ConnectorManifest.Kind.PLUGIN);
      assertThat(jiraType.getPlugin().getCurrentVersion()).isEqualTo("1.2.0");

      assertThat(templateVersions.findByTemplateIdAndTemplateVersion(template.getId(), 1).orElseThrow()
          .getTemplate().getKind()).isEqualTo(TemplateType.SQL_QUERY);
      assertThat(endpointConnections.findEndpointSlugsByConnectionId(devDb.getId())).containsExactly("dev");
      assertThat(endpointTools.findByEndpointIdAndConnectionIdIsNullAndToolKey(dev.getId(), "approval_status"))
          .isPresent();
      ApiTokenEntity bridge = tokens.findByHash("h".repeat(43)).orElseThrow();
      assertThat(bridge.getKind()).isEqualTo(TokenKind.BRIDGE);
      assertThat(bridge.getGrant().getLabel()).isEqualTo("codex-laptop");
      assertThat(bridge.getEndpoint().getSlug()).isEqualTo("dev");
      assertThat(endpoints.findBySlug("dev").orElseThrow().getInstructions()).startsWith("You are connected");
      assertThat(clientLinks.findByClientAndRemovedAtIsNull(ClientKind.CLAUDE_DESKTOP)).hasSize(1);
      assertThat(oauthGrants.findByClient_IdAndEndpointIdAndState(claude.getId(), dev.getId(),
          ai.msrv.gaterook.core.oauth.internal.persistence.OAuthGrantState.ACTIVE)).hasSize(1);
      assertThat(oauthClients.findByClientId("https://claude.ai/oauth/mcp-oauth-client-metadata").orElseThrow()
          .getRedirectUris()).containsExactly("https://claude.ai/api/mcp/auth_callback");

      ApprovalEntity pending = approvals.findFirstByEndpointIdAndPrincipalIdAndDigestAndStatus(dev.getId(),
          grant.getId(), "1".repeat(64), ApprovalStatus.PENDING).orElseThrow();
      assertThat(pending.getPreparedState()).isEqualTo("[1,2]");
      assertThat(pending.getCommentPolicy()).isEqualTo(CommentPolicy.OPTIONAL);
      assertThat(approvalEvents.findByApprovalIdOrderByIdAsc(approval.getId())).singleElement()
          .satisfies(e -> assertThat(e.getKind()).isEqualTo(ApprovalEventKind.REQUESTED));
      ToolCallEntity call = toolCalls.findById(received.getId()).orElseThrow();
      assertThat(call.getHourBucket()).isEqualTo(now.toEpochMilli() / 3_600_000);
      assertThat(call.getClassification()).isEqualTo(Classification.Kind.MUTATION);
      assertThat(toolCalls.findTopByOrderByIdDesc()).hasValueSatisfying(
          head -> assertThat(head.getRowSha256()).isEqualTo("4".repeat(64)));
      assertThat(setupChanges.findTopByOrderByIdDesc().orElseThrow().getWhat())
          .isEqualTo(SetupChangeRecorder.What.RISK_WARNING);
      assertThat(traces.findById(trace.getTraceId()).orElseThrow().getTitleSource()).isEqualTo(TraceTitleSource.META);
      assertThat(exports.findRetentionBound()).contains(0L);
      assertThat(channels.findById("slack").orElseThrow().getChannel()).isEqualTo(NotifyChannel.SLACK);
      assertThat(deliveries.findByApprovalId(approval.getId())).hasSize(1);
      return null;
    });

    // The stored forms the schema's CHECKs and the audit triggers rely on.
    assertThat(jdbc.queryForObject("select typeof(created_at) from tool_call", String.class)).isEqualTo("integer");
    assertThat(jdbc.queryForObject("select created_at from tool_call", Long.class)).isEqualTo(now.toEpochMilli());
    assertThat(jdbc.queryForObject("select env || '/' || status || '/' || comment_policy from approval",
        String.class)).isEqualTo("dev/pending/optional");
    assertThat(jdbc.queryForObject("select what from setup_change", String.class)).isEqualTo("risk-warning");
    assertThat(jdbc.queryForObject("select access from endpoint where id = ?", String.class, dev.getId()))
        .isEqualTo("standard");
    assertThat(jdbc.queryForObject("select typeof(enabled) || ':' || enabled from endpoint where id = ?",
        String.class, dev.getId()))
        .isEqualTo("integer:1");

    // The approval CAS: exactly one winner, versions bump, expiry respected ─────────────────────────────────
    int first = tx(() -> approvals.transitionBeforeExpiry(approval.getId(), ApprovalStatus.PENDING,
        ApprovalStatus.RUNNING, now));
    int second = tx(() -> approvals.transitionBeforeExpiry(approval.getId(), ApprovalStatus.PENDING,
        ApprovalStatus.REJECTED, now));
    assertThat(first).isEqualTo(1);
    assertThat(second).isZero();
    ApprovalEntity running = readOnly(() -> approvals.findById(approval.getId()).orElseThrow());
    assertThat(running.getStatus()).isEqualTo(ApprovalStatus.RUNNING);
    assertThat(running.getVersion()).isEqualTo(approval.getVersion() + 1);
    assertThat(tx(() -> approvals.transition(approval.getId(), ApprovalStatus.RUNNING, ApprovalStatus.COMPLETED)))
        .isEqualTo(1);
    ApprovalEntity late = tx(() -> approvals.save(ApprovalEntity.builder().call("tc_bbbbbbbbbbbb", "sql_execute",
            "sql_execute").endpoint(dev.getId(), "dev").connection(devDb.getId(), "dev-db", Env.DEV)
        .principal(Caller.Kind.BRIDGE, grant.getId(), "codex-laptop").canonical(Map.of(), "6".repeat(64))
        .action(Map.of(), "7".repeat(64), null).rule("dev.sql.write-approval", CommentPolicy.OPTIONAL)
        .window(now.minusSeconds(600), now.minusSeconds(300)).build()));
    assertThat(tx(() -> approvals.transitionBeforeExpiry(late.getId(), ApprovalStatus.PENDING,
        ApprovalStatus.RUNNING, now))).as("an expired request can't be approved").isZero();

    // CHECK constraints are the last line behind the services ─────────────────────────────────────────────────
    assertThatThrownBy(() -> tx(() -> endpoints.saveAndFlush(new EndpointEntity("Bad_Slug", Env.DEV,
        EndpointAccess.STANDARD, Map.of(), now)))).isInstanceOf(DataIntegrityViolationException.class);

    // Deleting dev-db: endpoint wiring, tools, the MCP server row of its type go; templates and approvals keep
    // their history with names (ARCHITECTURE.md §4, M1.4 deletion test).
    tx(() -> {
      secretVersions.deleteAll(secretVersions.findBySecretIdOrderBySecretVersionDesc(password.getId()));
      leases.deleteAll(leases.findBySecretIdAndRevokedAtIsNull(dynamic.getId()));
      secrets.deleteAll(List.of(secrets.findById(password.getId()).orElseThrow(),
          secrets.findById(dynamic.getId()).orElseThrow()));
      connections.delete(connections.findByName("dev-db").orElseThrow());
      return null;
    });
    assertThat(jdbc.queryForObject("select count(*) from endpoint_connection", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from endpoint_tool where connection_id is not null",
        Integer.class)).isZero();
    assertThat(jdbc.queryForObject("select connection_id is null from template", Boolean.class)).isTrue();
    assertThat(jdbc.queryForObject("select count(*) from approval where connection_id is null "
        + "and connection_name = 'dev-db'", Integer.class)).isEqualTo(2);

    // Deleting the endpoint: grants, tokens, links, OAuth grants cascade; approvals keep the slug.
    tx(() -> {
      endpoints.delete(endpoints.findBySlug("dev").orElseThrow());
      return null;
    });
    assertThat(jdbc.queryForList("select id from endpoint_tool where endpoint_id = ?", String.class, dev.getId()))
        .isEmpty();
    assertThat(readOnly(() -> endpointTools.findAll().stream().map(EndpointToolEntity::getId).toList()))
        .as("the startup endpoint's tools are preserved")
        .containsExactlyInAnyOrderElementsOf(existingEndpointToolIds);
    for (String table : List.of("client_grant", "api_token", "client_link", "oauth_grant",
        "oauth_token")) {
      assertThat(jdbc.queryForObject("select count(*) from " + table, Integer.class)).as(table).isZero();
    }
    assertThat(jdbc.queryForObject("select count(*) from approval where endpoint_id is null and endpoint_slug = 'dev'",
        Integer.class)).isEqualTo(2);
    assertThat(jdbc.queryForObject("select count(*) from tool_call", Integer.class)).as("the audit stays").isEqualTo(1);
  }
}
