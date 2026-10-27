package ai.msrv.gaterook.connector.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Accessors connectors use to read their inputs: call arguments, connection config, policy section, env. */
class InputAccessorsTest {

  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  @Test
  void toolCallArguments() {
    ObjectNode args = F.objectNode().put("sql", "SELECT 1").put("limit", 20).putNull("note");
    ToolCall call = new ToolCall("tc_1", "sql_query", "dev-db", args, null);

    assertThat(call.string("sql")).isEqualTo("SELECT 1");
    assertThat(call.string("note")).as("JSON null").isNull();
    assertThat(call.string("absent")).isNull();
    assertThat(call.integer("limit", 50)).isEqualTo(20);
    assertThat(call.integer("absent", 50)).isEqualTo(50);
    assertThat(call.integer("note", 50)).isEqualTo(50);
    assertThat(call.integer("sql", 7)).as("not a number").isEqualTo(7);
  }

  @Test
  void connectionConfigByJsonPointer() {
    ObjectNode config = F.objectNode();
    config.putObject("connection").put("host", "db.dev.internal").put("port", 5432).put("tls", true)
        .putNull("caCertificate");
    config.putObject("advanced").putArray("schemaAllowlist").add("public").add("reporting");
    ConnectionConfig cfg = new ConnectionConfig("c1", "dev-db", "postgres", Env.DEV, 3, config,
        Set.of("/credentials/password"), null);

    assertThat(cfg.string("/connection/host")).isEqualTo("db.dev.internal");
    assertThat(cfg.string("/connection/caCertificate")).isNull();
    assertThat(cfg.string("/connection/absent")).isNull();
    assertThat(cfg.integer("/connection/port", 1)).isEqualTo(5432);
    assertThat(cfg.integer("/connection/absent", 5)).isEqualTo(5);
    assertThat(cfg.bool("/connection/tls", false)).isTrue();
    assertThat(cfg.bool("/connection/absent", true)).isTrue();
    assertThat(cfg.strings("/advanced/schemaAllowlist")).containsExactly("public", "reporting");
    assertThat(cfg.strings("/advanced/absent")).isEmpty();
    assertThat(cfg.secretFields()).containsExactly("/credentials/password");
  }

  @Test
  void policyViewSection() {
    ObjectNode section = F.objectNode().put("denyDdl", true).put("rowLimit", 100).putNull("statementTimeoutMs");
    section.putArray("allowlist").add("public.orders");
    PolicyView policy = new PolicyView(Env.PROD, "prod.sql", section, null);

    assertThat(policy.rule("block_ddl")).isEqualTo("prod.sql.block_ddl");
    assertThat(policy.flag("denyDdl", false)).isTrue();
    assertThat(policy.flag("absent", true)).isTrue();
    assertThat(policy.integer("rowLimit", 1000)).isEqualTo(100);
    assertThat(policy.integer("statementTimeoutMs", 3000)).isEqualTo(3000);
    assertThat(policy.strings("allowlist")).containsExactly("public.orders");
    assertThat(policy.strings("absent")).isEmpty();
  }

  @Test
  void policyViewWithoutSectionIsEmpty() {
    PolicyView policy = new PolicyView(Env.DEV, "dev.redis", null, null);

    assertThat(policy.section().isObject()).isTrue();
    assertThat(policy.section().isEmpty()).isTrue();
    assertThat(policy.flag("x", false)).isFalse();
  }

  @Test
  void envIds() {
    assertThat(Env.DEV.id()).isEqualTo("dev");
    assertThat(Env.STAGE.id()).isEqualTo("stage");
    assertThat(Env.PROD.id()).isEqualTo("prod");
    assertThat(Env.of("prod")).isEqualTo(Env.PROD);
    assertThat(Env.of("Stage")).isEqualTo(Env.STAGE);
    assertThatThrownBy(() -> Env.of("qa")).isInstanceOf(IllegalArgumentException.class);
  }
}
