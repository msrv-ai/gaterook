package ai.msrv.gaterook.connectors.sql;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import java.io.InputStream;
import java.nio.file.Path;
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
    assertThat(checked).isEqualTo(4);
  }
}
