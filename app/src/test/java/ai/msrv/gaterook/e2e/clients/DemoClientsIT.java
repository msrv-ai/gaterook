package ai.msrv.gaterook.e2e.clients;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.core.GatewayApplication;
import ai.msrv.gaterook.core.clients.ClientService;
import ai.msrv.gaterook.core.clients.ClientViews;
import ai.msrv.gaterook.core.platform.boot.GatewayLauncher;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The demo world's clients (design/BLUEPRINT.md §9 "Clients"): Claude Desktop connected to {@code dev} and
 * {@code incident-prod} through extensions, Codex to {@code dev} through the helper as {@code codex-laptop}, Cursor
 * detected, Antigravity not installed — all in {@code <home>/demo-user-home}, and the demo's calls and requests come
 * from those clients' real grants.
 */
class DemoClientsIT {

  private static final String LAUNCH_SECRET = Base64.getUrlEncoder().withoutPadding()
      .encodeToString("demo-clients-it-launch-secret-01".getBytes(StandardCharsets.US_ASCII));

  @Test
  void theDemoHasTheKitsClientsInItsOwnUserHome() throws IOException {
    Path home = newHome();
    try (ConfigurableApplicationContext core = new SpringApplicationBuilder(GatewayApplication.class)
        .contextFactory(GatewayLauncher.contexts(true))
        .run("--gateway.home=" + home, "--gateway.dev=true", "--gateway.dev.launch-secret=" + LAUNCH_SECRET,
            "--server.port=0", "--gateway.connections.health-interval=0s", "--gateway.demo=true")) {
      ClientService clients = core.getBean(ClientService.class);
      Path demoHome = home.resolve("demo-user-home");
      assertThat(clients.environment().userHome()).isEqualTo(demoHome);
      List<ClientViews.Card> cards = clients.scan().clients();

      ClientViews.Card claude = card(cards, "claude-desktop");
      assertThat(claude.status()).isEqualTo("connected");
      assertThat(claude.links()).extracting(ClientViews.Link::endpoint).containsExactly("dev", "incident-prod");
      assertThat(claude.links()).allSatisfy(l -> {
        assertThat(l.method()).isEqualTo("mcpb");
        assertThat(l.status()).isEqualTo("connected");
        assertThat(Path.of(l.mcpbPath())).exists().startsWith(home.resolve("clients/claude-desktop"));
      });
      ClientViews.Card codex = card(cards, "codex");
      assertThat(codex.status()).isEqualTo("connected");
      assertThat(codex.links()).singleElement().satisfies(l -> {
        assertThat(l.endpoint()).isEqualTo("dev");
        assertThat(l.method()).isEqualTo("helper");
        assertThat(l.grantLabel()).isEqualTo("codex-laptop");
      });
      assertThat(Files.readString(demoHome.resolve(".codex/config.toml"))).contains("[mcp_servers.github]")
          .contains("[mcp_servers.gateway-dev]").contains("headers --endpoint dev --grant gr_");
      ClientViews.Card cursor = card(cards, "cursor");
      assertThat(cursor.status()).isEqualTo("detected");
      assertThat(cursor.links()).isEmpty();
      assertThat(card(cards, "antigravity").status()).isEqualTo("not-installed");

      // The demo's requests and calls come from these grants, not from made-up ids.
      JdbcTemplate jdbc = new JdbcTemplate(core.getBean(javax.sql.DataSource.class));
      List<String> principals = jdbc.queryForList("select distinct principal_id from approval", String.class);
      assertThat(principals).isNotEmpty().allSatisfy(p -> assertThat(jdbc.queryForObject("select count(*) from "
          + "client_grant where id = ?", Integer.class, p)).as(p).isEqualTo(1));
      assertThat(jdbc.queryForObject("select count(*) from client_grant where label = 'codex-laptop' and client = "
          + "'codex' and revoked_at is null", Integer.class)).isEqualTo(1);
    }
  }

  private static ClientViews.Card card(List<ClientViews.Card> cards, String client) {
    return cards.stream().filter(c -> c.client().equals(client)).findFirst().orElseThrow();
  }

  private static Path newHome() {
    try {
      Path parent = Files.createDirectories(Path.of("target", "gateway-homes").toAbsolutePath());
      return Files.createTempDirectory(parent, "demo-clients-");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
