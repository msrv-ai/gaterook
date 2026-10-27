package ai.msrv.gaterook.e2e.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.GatewayApplication;
import ai.msrv.gaterook.core.connections.ConnectionService;
import ai.msrv.gaterook.core.connections.SecretInput;
import ai.msrv.gaterook.core.platform.ProblemException;
import ai.msrv.gaterook.core.platform.boot.GatewayLauncher;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A prod connection whose user can do harm beyond the agent's tools (ARCHITECTURE.md §9 "SQL reads cannot write",
 * {@code confirm:"danger"}): saving it as the superuser asks first and names the rights; with the confirmation it is
 * saved; a user with ordinary rights saves without a question, and dev never asks.
 */
class ProdRightsIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String LAUNCH_SECRET = Base64.getUrlEncoder().withoutPadding()
      .encodeToString("prod-rights-it-launch-secret-012".getBytes(StandardCharsets.US_ASCII));

  private static LocalPg pg;
  private static ConfigurableApplicationContext core;
  private static ConnectionService connections;

  @BeforeAll
  static void start() throws Exception {
    pg = LocalPg.startIfAvailable();
    Assumptions.assumeTrue(pg != null, "No PostgreSQL: set GW_PG_BIN, install postgresql-16 or use -Pservices");
    Path home = Files.createTempDirectory(Files.createDirectories(Path.of("target", "core-homes")
        .toAbsolutePath()), "prod-rights-");
    core = new SpringApplicationBuilder(GatewayApplication.class)
        .contextFactory(GatewayLauncher.contexts(true))
        .run("--gateway.home=" + home, "--gateway.dev=true", "--gateway.dev.launch-secret=" + LAUNCH_SECRET,
            "--server.port=0", "--gateway.connections.health-interval=0s");
    connections = core.getBean(ConnectionService.class);
  }

  @AfterAll
  static void stop() throws Exception {
    if (core != null) {
      core.close();
    }
    if (pg != null) {
      pg.close();
    }
  }

  private static JsonNode config(String user) {
    return JSON.readTree("{\"connection\":{\"host\":\"127.0.0.1\",\"port\":" + pg.port + ",\"database\":\"app\","
        + "\"sslMode\":\"disable\"},\"credentials\":{\"user\":\"" + user + "\"}}");
  }

  private static Map<String, SecretInput> password(String value) {
    return Map.of("/credentials/password", SecretInput.local(value.toCharArray()));
  }

  @Test
  void theSuperuserOnProdIsSavedOnlyOnceThePersonConfirms() {
    assertThatThrownBy(() -> connections.create("prod-admin", "postgres", Env.PROD, config("postgres"),
        password(LocalPg.SUPERUSER_PASSWORD), "e2e"))
        .isInstanceOfSatisfying(ProblemException.class, e -> {
          assertThat(e.confirm()).isEqualTo(ConnectionService.CONFIRM_DANGER);
          assertThat(e.title()).isEqualTo("Keep these rights on prod?");
        });
    assertThat(connections.list()).extracting(ConnectionService.Summary::name).doesNotContain("prod-admin");

    connections.create("prod-admin", "postgres", Env.PROD, config("postgres"), password(LocalPg.SUPERUSER_PASSWORD),
        "e2e", ConnectionService.CONFIRM_DANGER);
    assertThat(connections.list()).extracting(ConnectionService.Summary::name).contains("prod-admin");
  }

  @Test
  void anOrdinaryUserOnProdAndTheSuperuserOnDevAreSavedWithoutAQuestion() {
    connections.create("prod-reader", "postgres", Env.PROD, config("gw_ro"), password(LocalPg.password("gw_ro")),
        "e2e");
    connections.create("dev-admin", "postgres", Env.DEV, config("postgres"), password(LocalPg.SUPERUSER_PASSWORD),
        null);
    assertThat(connections.list()).extracting(ConnectionService.Summary::name).contains("prod-reader", "dev-admin");
  }
}
