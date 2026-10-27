package ai.msrv.gaterook.e2e.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.GatewayApplication;
import ai.msrv.gaterook.core.connections.ConnectionService;
import ai.msrv.gaterook.core.connections.ConnectionTester;
import ai.msrv.gaterook.core.connections.SecretInput;
import ai.msrv.gaterook.core.platform.ProblemException;
import ai.msrv.gaterook.core.platform.boot.GatewayLauncher;
import ai.msrv.gaterook.core.secrets.SecretInfo;
import ai.msrv.gaterook.core.secrets.SecretService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Rotate at the source through the core (ARCHITECTURE.md §4 secret versions, C1 {@code CredentialRotator}): the
 * gateway makes a new password, PostgreSQL takes it for the connection's own user, it becomes version 2 and the old
 * one stops working; when the source refuses, version 2 stays in use and no pending version is left behind.
 */
class SecretRotationIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String LAUNCH_SECRET = Base64.getUrlEncoder().withoutPadding()
      .encodeToString("secret-rotation-it-launch-secret".getBytes(StandardCharsets.US_ASCII));

  private static LocalPg pg;
  private static ConfigurableApplicationContext core;

  @BeforeAll
  static void start() throws Exception {
    pg = LocalPg.startIfAvailable();
    Assumptions.assumeTrue(pg != null, "No PostgreSQL: set GW_PG_BIN, install postgresql-16 or use -Pservices");
    Path home = Files.createTempDirectory(Files.createDirectories(Path.of("target", "core-homes")
        .toAbsolutePath()), "rotation-");
    core = new SpringApplicationBuilder(GatewayApplication.class)
        .contextFactory(GatewayLauncher.contexts(true))
        .run("--gateway.home=" + home, "--gateway.dev=true", "--gateway.dev.launch-secret=" + LAUNCH_SECRET,
            "--server.port=0", "--gateway.connections.health-interval=0s");
    core.getBean(ConnectionService.class).create("dev-db", "postgres", Env.DEV, JSON.readTree(
        "{\"connection\":{\"host\":\"127.0.0.1\",\"port\":" + pg.port + ",\"database\":\"app\",\"sslMode\":"
            + "\"disable\"},\"credentials\":{\"user\":\"gw_rw\"}}"), Map.of("/credentials/password",
        SecretInput.local(LocalPg.password("gw_rw").toCharArray())), null);
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

  private static SecretInfo password() {
    return core.getBean(SecretService.class).list().stream()
        .filter(s -> "dev-db".equals(s.ownerLabel()) && s.field().equals("/credentials/password")).findFirst()
        .orElseThrow();
  }

  @Test
  void theSourceTakesANewPasswordThatOnlyTheGatewayKnows() throws Exception {
    SecretInfo before = password();
    assertThat(before.canRotate()).as(String.valueOf(before.rotateReason())).isTrue();

    SecretService.ReplaceResult rotated = core.getBean(SecretService.class).rotate(before.id(), null);

    assertThat(rotated.version()).isEqualTo(before.version() + 1);
    assertThat(password().state()).isEqualTo("stored");
    assertThat(password().rotatedAt()).isNotNull();
    assertThatThrownBy(() -> pg.connect("app", "gw_rw", LocalPg.password("gw_rw")).close())
        .as("the old password no longer signs in").isInstanceOf(SQLException.class);
    ConnectionTester.Outcome tested = core.getBean(ConnectionTester.class).testStored("dev-db");
    assertThat(tested.result().ok()).as(String.valueOf(tested.result().error())).isTrue();
    JdbcTemplate jdbc = new JdbcTemplate(core.getBean(javax.sql.DataSource.class));
    assertThat(jdbc.queryForList("select change_to from setup_change where what = 'secret'", String.class))
        .contains("v" + rotated.version() + " (rotated at the source)");

    // Someone changed the password at the source: the gateway can't sign in, so it can't rotate, and nothing moves.
    try (var c = pg.connect("app", "postgres", LocalPg.SUPERUSER_PASSWORD); var st = c.createStatement()) {
      st.execute("ALTER ROLE gw_rw PASSWORD 'changed-elsewhere-0001'");
    }
    assertThatThrownBy(() -> core.getBean(SecretService.class).rotate(before.id(), null))
        .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.title()).isEqualTo("The source refused"));
    assertThat(password().version()).isEqualTo(rotated.version());
    assertThat(password().state()).as("no pending version is left").isEqualTo("stored");
  }
}
