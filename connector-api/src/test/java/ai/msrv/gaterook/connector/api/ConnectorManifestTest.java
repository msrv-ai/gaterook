package ai.msrv.gaterook.connector.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ConnectorManifest.Kind;
import ai.msrv.gaterook.connector.api.ConnectorManifest.RequiredAccess;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

class ConnectorManifestTest {

  @Test
  void builderDefaults() {
    ConnectorManifest m = ConnectorManifest.builder("jira-service-desk", "1.3.0", Kind.PLUGIN).build();

    assertThat(m.id()).isEqualTo("jira-service-desk");
    assertThat(m.version()).isEqualTo("1.3.0");
    assertThat(m.kind()).isEqualTo(Kind.PLUGIN);
    assertThat(m.name()).as("name defaults to the id").isEqualTo("jira-service-desk");
    assertThat(m.icon()).isEqualTo("puzzle");
    assertThat(m.group()).isEqualTo("HTTP and MCP");
    assertThat(m.description()).isEmpty();
    assertThat(m.configSchema()).isNull();
    assertThat(m.policySchema()).isNull();
    assertThat(m.tools()).isEmpty();
    assertThat(m.access()).isEqualTo(RequiredAccess.none());
    assertThat(m.dynamicTools()).isFalse();
    assertThat(m.connectorApi()).isEqualTo(ApiVersion.CURRENT);
  }

  @Test
  void builderSetsEveryFieldAndStampsTheApiVersion() {
    JsonNode config = JsonNodeFactory.instance.objectNode().put("type", "object");
    JsonNode policy = JsonNodeFactory.instance.objectNode().put("title", "SQL");
    ToolDefinition tool = ToolDefinition.builder("sql_query").build();
    RequiredAccess access = new RequiredAccess(List.of("db.dev.internal:5432"), List.of(), List.of("~/.pgpass"));

    ConnectorManifest m = ConnectorManifest.builder("postgres", "0.4.0", Kind.BUILT_IN)
        .name("PostgreSQL").icon("database").group("Databases").description("Schemas, tables and queries.")
        .configSchema(config).policySchema(policy).tools(List.of(tool)).access(access).dynamicTools(true)
        .build();

    assertThat(m).isEqualTo(new ConnectorManifest("postgres", "0.4.0", Kind.BUILT_IN, "PostgreSQL", "database",
        "Databases", "Schemas, tables and queries.", config, policy, List.of(tool), access, true, "1.0.0"));
  }

  @Test
  void toolsAreCopiedDefensively() {
    List<ToolDefinition> tools = new ArrayList<>(List.of(ToolDefinition.builder("a").build()));
    ConnectorManifest m = ConnectorManifest.builder("x", "1", Kind.BUILT_IN).tools(tools).build();

    tools.add(ToolDefinition.builder("b").build());

    assertThat(m.tools()).hasSize(1);
    assertThatThrownBy(() -> m.tools().add(ToolDefinition.builder("c").build()))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void requiredAccessNoneIsEmpty() {
    assertThat(RequiredAccess.none().hosts()).isEmpty();
    assertThat(RequiredAccess.none().commands()).isEmpty();
    assertThat(RequiredAccess.none().files()).isEmpty();
  }
}
