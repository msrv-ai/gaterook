package com.gaterook.connectors.sql;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.connector.api.Classification;
import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.TestResult;
import com.gaterook.connector.api.ToolCall;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The native smoke's touches of this module ({@code META-INF/mcp-gateway/smoke/sql.json}, ARCHITECTURE.md §10 N1)
 * hold on the JVM: the ones without a fixture run here as the smoke would run them in the image.
 */
class SmokeDescriptorTest {

  @Test
  void touchesWithoutAServerAnswerAsTheDescriptorSays() throws Exception {
    JsonNode smoke;
    try (InputStream in = getClass().getClassLoader().getResourceAsStream("META-INF/mcp-gateway/smoke/sql.json")) {
      assertThat(in).isNotNull();
      smoke = Fixtures.JSON.readTree(in);
    }
    assertThat(smoke.path("area").asString()).isEqualTo("sql");
    assertThat(check(smoke)).isEqualTo(5);
  }

  /**
   * The touches of Oracle Database and SQL Server live with the modules that register those types and bring their
   * drivers ({@code connectors/sql-oracle}, {@code connectors/sql-sqlserver}); the connector they touch is this one.
   */
  @Test
  void theTouchesOfTheDialectsThatAreTheirOwnUnitsAnswerAsTheirDescriptorsSay() throws Exception {
    for (String unit : List.of("sql-oracle", "sql-sqlserver")) {
      Path file = Path.of("..", unit, "src", "main", "resources", "META-INF", "mcp-gateway", "smoke", unit + ".json");
      JsonNode smoke = Fixtures.JSON.readTree(Files.readAllBytes(file));
      assertThat(smoke.path("area").asString()).isEqualTo(unit);
      assertThat(check(smoke)).as(unit).isEqualTo(1);
    }
  }

  /** Runs every touch of {@code smoke} that needs no server. @return how many it ran */
  private static int check(JsonNode smoke) throws Exception {
    int checked = 0;
    for (JsonNode touch : smoke.path("touches")) {
      if (touch.has("fixture")) {
        continue;
      }
      JsonNode c = touch.path("connection");
      String type = c.path("type").asString();
      Env env = Env.of(c.path("env").asString());
      JdbcSqlConnector connector = new JdbcSqlConnector(switch (type) {
        case "mysql" -> new MySqlDialect();
        case "oracle" -> new OracleDialect();
        case "sqlserver" -> new SqlServerDialect();
        case "sqlite" -> new SqliteDialect();
        default -> new PostgresDialect();
      });
      ConnectionConfig cfg = new ConnectionConfig("smoke-" + touch.path("name").asString(), "dev-db", type, env, 1,
          c.path("config"), Set.of(ConnectionSettings.PASSWORD), null);
      Fixtures.Secrets secrets = Fixtures.Secrets.password(c.at("/secrets/~1credentials~1password").asString());
      JsonNode expect = touch.path("expect");
      if (touch.path("test").asBoolean(false)) {
        TestResult r = connector.test(cfg, secrets, Fixtures.Context.of(env, Path.of("target", "smoke-work")));
        assertThat(r.ok()).isEqualTo(expect.path("ok").asBoolean());
        assertThat(r.error().code()).isEqualTo(expect.path("errorCode").asString());
      } else {
        ToolCall call = new ToolCall("tc_smoke", touch.at("/call/tool").asString(), "dev-db",
            (ObjectNode) touch.at("/call/arguments"), null);
        Classification cl = connector.classify(call, cfg, Fixtures.policy(env, null));
        assertThat(cl.kind()).isEqualTo(Classification.Kind.DENY);
        assertThat("Denied by policy " + cl.ruleId() + ": " + cl.reason())
            .startsWith(expect.path("textStartsWith").asString());
      }
      connector.close();
      checked++;
    }
    return checked;
  }
}
