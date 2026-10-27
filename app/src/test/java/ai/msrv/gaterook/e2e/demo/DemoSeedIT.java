package ai.msrv.gaterook.e2e.demo;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.core.GatewayApplication;
import ai.msrv.gaterook.core.approvals.ApprovalService;
import ai.msrv.gaterook.core.approvals.ApprovalService.Decision;
import ai.msrv.gaterook.core.approvals.ApprovalView;
import ai.msrv.gaterook.core.connections.ConnectionService;
import ai.msrv.gaterook.core.endpoints.EndpointService;
import ai.msrv.gaterook.core.endpoints.TokenService;
import ai.msrv.gaterook.core.platform.IntegrityAlerts;
import ai.msrv.gaterook.core.platform.boot.GatewayLauncher;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code GW_DEMO=1} (ARCHITECTURE.md §2.4, design/BLUEPRINT.md §9): an empty home gets the kit's world through the
 * core's own services, on the assembled core with the real built-in connectors; a home with data is left alone.
 */
class DemoSeedIT {

  private static final String LAUNCH_SECRET = Base64.getUrlEncoder().withoutPadding()
      .encodeToString("demo-seed-it-launch-secret-01234".getBytes(StandardCharsets.US_ASCII));

  @Test
  void anEmptyHomeGetsTheKitsWorldAndAFilledOneIsLeftAlone() {
    String home = newHome();
    try (ConfigurableApplicationContext core = start(home)) {
      ConnectionService connections = core.getBean(ConnectionService.class);
      EndpointService endpoints = core.getBean(EndpointService.class);
      // The kit's 12 connections and its external MCP server, a connection of the mcp-server type (§2.3).
      assertThat(connections.list()).extracting(ConnectionService.Summary::name).containsExactlyInAnyOrder("dev-db",
          "stage-db", "prod-replica", "orders-mongo", "cache-redis", "dev-vm", "app-logs", "prod-logs", "dev-cluster",
          "aws-stage", "billing-api", "prod-vm", "github-mcp");
      Map<String, ConnectionService.Summary> rows = connections.list().stream()
          .collect(Collectors.toMap(ConnectionService.Summary::name, s -> s));
      // The Connections rows of connections.html: type, host, health as the kit has them.
      assertThat(rows.get("dev-db").typeLabel()).isEqualTo("PostgreSQL 16");
      assertThat(rows.get("orders-mongo").typeLabel()).isEqualTo("MongoDB 7");
      assertThat(rows.get("cache-redis").typeLabel()).isEqualTo("Redis 7");
      assertThat(rows.get("app-logs").typeLabel()).isEqualTo("Loki");
      assertThat(rows.get("prod-logs").typeLabel()).isEqualTo("GCP Cloud Logging");
      assertThat(rows.get("dev-cluster").target()).isEqualTo("k8s.dev.internal:6443 · ns app-dev");
      assertThat(rows.get("aws-stage").target()).isEqualTo("eu-central-1 · acme-stage");
      assertThat(rows.get("dev-vm").target()).isEqualTo("10.20.0.14:22");
      assertThat(rows.get("app-logs").target()).isEqualTo("loki.dev.internal:3100");
      assertThat(rows.get("billing-api").target()).isEqualTo("https://billing.stage.acme.dev");
      assertThat(rows.get("dev-db").health().latencyMs()).isEqualTo(12L);
      assertThat(rows.get("cache-redis").health().state().name()).isEqualTo("DEGRADED");
      ConnectionService.Summary billing = rows.get("billing-api");
      assertThat(billing.health().state().name()).isEqualTo("UNREACHABLE");
      assertThat(Duration.between(billing.health().since(), billing.health().checkedAt()))
          .as("unreachable since 11:42, checked a minute ago").isEqualTo(Duration.ofMinutes(167));

      EndpointService.Summary dev = endpoints.get("dev");
      assertThat(dev.connections()).isEqualTo(8);
      assertThat(dev.tools()).as("14 read only · 3 approval · 5 off").isEqualTo(new EndpointService.ToolCounts(14, 3,
          5));
      assertThat(dev.description()).startsWith("Local development. Agents read freely;");
      assertThat(endpoints.get("stage-readonly").access()).isEqualTo("read-only");
      assertThat(endpoints.get("stage-readonly").connections()).isEqualTo(3);
      assertThat(endpoints.get("incident-prod").connections()).as("prod-replica, prod-logs, prod-vm").isEqualTo(3);
      assertThat(endpoints.get("incident-prod").tools()).as("10 tools: 7 read only · 3 off")
          .isEqualTo(new EndpointService.ToolCounts(7, 0, 3));
      assertThat(endpoints.get("incident-prod").description()).contains("run approved runbooks on prod-vm");

      // github-mcp v0.6.2 from the catalog: 26 tools, on no endpoint, its token encrypted here; nothing ran npm.
      ai.msrv.gaterook.core.mcpservers.McpServerService servers = core.getBean(
          ai.msrv.gaterook.core.mcpservers.McpServerService.class);
      assertThat(servers.tools("github-mcp")).hasSize(26).allSatisfy(t -> assertThat(t.name()).startsWith("github_"));
      assertThat(rows.get("github-mcp").usedBy()).isEmpty();

      // Secrets: 11, 8 of them encrypted here (6 connections, the github-mcp token, the Vault sign-in), 3 references.
      List<ai.msrv.gaterook.core.secrets.SecretInfo> secrets = core.getBean(ai.msrv.gaterook.core.secrets.SecretService.class)
          .list();
      assertThat(secrets).hasSize(11);
      assertThat(secrets).filteredOn(s -> s.source().equals("local")).hasSize(8);
      ai.msrv.gaterook.core.secrets.MasterKeyStatus key = core.getBean(ai.msrv.gaterook.core.secrets.MasterKeyService.class)
          .status();
      assertThat(key.protects()).isEqualTo(8);
      assertThat(key.problems()).as("backed up 12 days ago").doesNotContain("backup-needed");
      assertThat(Duration.between(key.lastBackupAt(), Instant.now()).toDays()).isEqualTo(12);
      assertThat(Duration.between(key.createdAt(), Instant.now()).toDays()).as("created on Jul 14").isEqualTo(100);
      assertThat(key.rotatedAt()).as("never rotated").isNull();
      // Review API #7: the backup is a date, not a copy of the key without a passphrase left in a home that is a
      // normal home afterwards.
      try (Stream<Path> files = Files.walk(Path.of(home))) {
        assertThat(files.filter(Files::isRegularFile).map(p -> p.getFileName().toString()))
            .noneMatch(n -> n.startsWith("gaterook-master-key"));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }

      // The runbooks of dev-vm and prod-vm.
      assertThat(core.getBean(ai.msrv.gaterook.core.templates.TemplateService.class).list("ssh-runbook"))
          .extracting(t -> t.name() + "@" + t.connectionName()).containsExactlyInAnyOrder("compose-logs@dev-vm",
              "compose-ps@dev-vm", "compose-restart@dev-vm", "restart-api@prod-vm", "service-status@prod-vm");

      Map<String, String> tokens = core.getBean(TokenService.class).list("dev").stream()
          .filter(t -> t.name() != null).collect(Collectors.toMap(TokenService.TokenRow::name,
              TokenService.TokenRow::state));
      assertThat(tokens).as("the kit: inspector-debug expires in 3 days, ci-smoke expired, old-claude revoked")
          .containsEntry("inspector-debug", "active").containsEntry("ci-smoke", "expired")
          .containsEntry("old-claude", "revoked");

      ApprovalService approvals = core.getBean(ApprovalService.class);
      assertThat(approvals.pending()).extracting(ApprovalView::tool).containsExactlyInAnyOrder("k8s_rollout_restart",
          "sql_execute", "http_request");
      assertThat(approvals.pending()).extracting(ApprovalView::clientLabel).contains("codex-laptop", "claude-desktop");
      // Each comes from an agent's task: its call carries the client's trace, so the request's Trace row opens a task
      // of Traces from the start (the admin API's traceIsTask reads this flag, not the summary written a moment later).
      assertThat(approvals.pending()).allSatisfy(a -> assertThat(a.call().path("origin").path("traceFromClient")
          .asBoolean()).as(a.tool()).isTrue());
      ApprovalView update = approvals.pending().stream().filter(a -> a.tool().equals("sql_execute")).findFirst()
          .orElseThrow();
      assertThat(update.action().text()).isEqualTo("UPDATE orders SET status = 'cancelled' WHERE id = 48213;");
      assertThat(update.env().id()).isEqualTo("dev");
      assertThat(approvals.history(null, 10).items()).extracting(a -> a.tool() + ":" + a.status())
          .contains("k8s_apply:rejected");

      JdbcTemplate jdbc = new JdbcTemplate(core.getBean(javax.sql.DataSource.class));
      List<String> calls = jdbc.queryForList("select tool_name || ':' || phase || ':' || decision from tool_call "
          + "order by id", String.class);
      assertThat(calls).contains("k8s_rollout_restart:received:pending", "k8s_apply:resolved:rejected",
          "sql_query:completed:allowed", "ssh_run:completed:allowed", "logs_search:completed:allowed",
          "redis_get:completed:error", "sql_execute:received:denied");
      assertThat(jdbc.queryForObject("select count(*) from setup_change where what = 'mode' and endpoint_slug = 'dev' "
          + "and subject = 'ssh_exec on dev-vm, endpoint dev' and change_to like '%Off%'", Integer.class))
          .isEqualTo(1);
      assertThat(jdbc.queryForObject("select count(*) from setup_change where what = 'mode' and endpoint_slug = 'dev' "
          + "and subject = 'http_get on billing-api, endpoint dev'", Integer.class)).isEqualTo(1);

      // The tunnel (K3): stage-readonly is its endpoint, this morning's session is in the history, the tunnel is off,
      // and claude.ai's sign-in of that session is active for 7 days.
      ai.msrv.gaterook.core.tunnel.TunnelState tunnel = core.getBean(ai.msrv.gaterook.core.tunnel.TunnelService.class)
          .state();
      assertThat(tunnel.state()).isEqualTo("off");
      assertThat(tunnel.endpoint()).isEqualTo("stage-readonly");
      assertThat(tunnel.history()).hasSize(1);
      assertThat(tunnel.history().getFirst().calls()).isEqualTo(19);
      assertThat(tunnel.history().getFirst().host()).isEqualTo("gw-acme-7f3k.trycloudflare.com");
      assertThat(endpoints.get("stage-readonly").published()).isTrue();
      assertThat(core.getBean(ai.msrv.gaterook.core.oauth.OAuthService.class).grants())
          .extracting(g -> g.client() + ":" + g.endpoint() + ":" + g.state()).containsExactly(
              "claude.ai:stage-readonly:active");
      assertThat(jdbc.queryForObject("select count(*) from setup_change where what = 'tunnel'", Integer.class))
          .as("Off → On and On → Off").isEqualTo(2);

      // The seeding's calls belong to the agents' tasks of the kit (activity.html, approvals.html "Trace c7e19d").
      ai.msrv.gaterook.core.audit.AuditQueries audit = core.getBean(ai.msrv.gaterook.core.audit.AuditQueries.class);
      await(() -> audit.trace("c7e19d").isPresent() && audit.trace("7d19b0").isPresent(), "the tasks to be summed up");
      assertThat(audit.trace("c7e19d").orElseThrow().title()).isEqualTo("Cancel duplicate order 48213");
      assertThat(audit.trace("7d19b0").orElseThrow().title()).isEqualTo("Restart api after config change");
      assertThat(jdbc.queryForObject("select count(*) from tool_call where trace_id like 'c7e19d%' and phase = "
          + "'received'", Integer.class)).as("sql_query, logs_search, sql_execute, http_request").isEqualTo(4);

      // The seeded history is one verified chain: anchored while empty, so the start check walks it as a tail.
      assertThat(jdbc.queryForObject("select count(*) from setting where key = 'audit.head-mac'", Integer.class))
          .isEqualTo(1);
      assertThat(jdbc.queryForList("select subject from setup_change", String.class))
          .doesNotContain("Audit history");
      assertThat(core.getBean(IntegrityAlerts.class).current()).as("no integrity warning in /api/health").isEmpty();

      // Approve runs (U2 saw every Approve refused as revoked access while the seed's callers had no real grant):
      // Codex's call comes from its client link's grant, so the gateway runs it — against a dev-db that isn't there.
      ApprovalView approved = approvals.decide(update.id(), Decision.APPROVE, "app", null, update.digest());
      ApprovalView outcome = approvals.await(approved.id(), Duration.ofSeconds(30));
      assertThat(outcome.terminal()).as(outcome.status()).isTrue();
      assertThat(outcome.status()).as(String.valueOf(outcome.error())).isNotEqualTo("rejected");
      assertThat(String.valueOf(outcome.error())).doesNotContain("revoked");
    }

    // A second start on the same home: the switch is still on, the world is not seeded again, and the history the
    // first run anchored at its stop still verifies.
    try (ConfigurableApplicationContext again = start(home)) {
      assertThat(again.getBean(ConnectionService.class).list()).as("12 connections and github-mcp").hasSize(13);
      assertThat(again.getBean(ApprovalService.class).pending()).as("the seed's three, less the one approved above")
          .hasSize(2);
      JdbcTemplate jdbc = new JdbcTemplate(again.getBean(javax.sql.DataSource.class));
      assertThat(jdbc.queryForList("select subject from setup_change", String.class))
          .doesNotContain("Audit history");
      assertThat(again.getBean(IntegrityAlerts.class).current()).isEmpty();
    }
  }

  private static ConfigurableApplicationContext start(String home) {
    return new SpringApplicationBuilder(GatewayApplication.class)
        .contextFactory(GatewayLauncher.contexts(true))
        .run("--gateway.home=" + home, "--gateway.dev=true", "--gateway.dev.launch-secret=" + LAUNCH_SECRET,
            "--server.port=0", "--gateway.connections.health-interval=0s", "--gateway.demo=true");
  }

  private static void await(java.util.function.BooleanSupplier condition, String what) {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("Timed out waiting for " + what);
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError(e);
      }
    }
  }

  private static String newHome() {
    try {
      Path parent = Path.of("target", "gateway-homes").toAbsolutePath();
      Files.createDirectories(parent);
      return Files.createTempDirectory(parent, "demo-seed-").toString();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
