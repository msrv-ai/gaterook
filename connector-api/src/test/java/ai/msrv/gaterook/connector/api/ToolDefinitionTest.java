package ai.msrv.gaterook.connector.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ToolDefinition.Annotations;
import ai.msrv.gaterook.connector.api.ToolDefinition.Egress;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.connector.api.ToolDefinition.Nature;
import ai.msrv.gaterook.connector.api.ToolDefinition.ResultShape;
import ai.msrv.gaterook.connector.api.ToolDefinition.Risk;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

class ToolDefinitionTest {

  private static final JsonNode SCHEMA = JsonNodeFactory.instance.objectNode().put("type", "object");

  @Test
  void builderDefaultsToASafeReadTool() {
    ToolDefinition t = ToolDefinition.builder("sql_query").build();

    assertThat(t.name()).isEqualTo("sql_query");
    assertThat(t.title()).as("title defaults to the name").isEqualTo("sql_query");
    assertThat(t.description()).isEmpty();
    assertThat(t.nature()).isEqualTo(Nature.READ);
    assertThat(t.annotations()).isEqualTo(Annotations.read());
    assertThat(t.defaultMode()).isEqualTo(Mode.READ_ONLY);
    assertThat(t.prodDefaultMode()).isEqualTo(Mode.READ_ONLY);
    assertThat(t.resultShape()).isEqualTo(ResultShape.JSON);
    assertThat(t.risk()).isEqualTo(new Risk(true, true, Egress.NONE));
    assertThat(t.mutating()).isFalse();
    assertThat(t.readOnlyModeAllowed()).isTrue();
  }

  @Test
  void builderSetsEveryField() {
    Risk risk = new Risk(false, true, Egress.CHANNEL);
    ToolDefinition t = ToolDefinition.builder("http_request")
        .title("HTTP request")
        .description("Any method")
        .inputSchema(SCHEMA)
        .annotations(new Annotations(false, true, false, true))
        .nature(Nature.MIXED)
        .defaultMode(Mode.APPROVAL, Mode.OFF)
        .resultShape(ResultShape.TEXT)
        .risk(risk)
        .build();

    assertThat(t).isEqualTo(new ToolDefinition("http_request", "HTTP request", "Any method", SCHEMA, null,
        new Annotations(false, true, false, true), Nature.MIXED, Mode.APPROVAL, Mode.OFF, ResultShape.TEXT, risk));
    assertThat(t.mutating()).isTrue();
  }

  @Test
  void outputSchemaOnlyForReadTools() {
    assertThat(ToolDefinition.builder("q").outputSchema(SCHEMA).build().outputSchema()).isEqualTo(SCHEMA);
    for (Nature nature : new Nature[] {Nature.MUTATION, Nature.MIXED}) {
      assertThatThrownBy(() -> ToolDefinition.builder("x").nature(nature).outputSchema(SCHEMA)
              .defaultMode(Mode.APPROVAL, Mode.APPROVAL).build())
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("outputSchema only for READ tools");
    }
  }

  @Test
  void mutatingToolCanNotDefaultToReadOnly() {
    assertThatThrownBy(() -> ToolDefinition.builder("sql_execute").nature(Nature.MUTATION).build())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("can't default to Read only");
    assertThatThrownBy(() -> ToolDefinition.builder("sql_execute").nature(Nature.MUTATION)
            .defaultMode(Mode.APPROVAL, Mode.READ_ONLY).build())
        .isInstanceOf(IllegalStateException.class);
    assertThat(ToolDefinition.builder("sql_execute").nature(Nature.MUTATION).defaultMode(Mode.APPROVAL, Mode.OFF)
        .build().prodDefaultMode()).isEqualTo(Mode.OFF);
    // A MIXED tool may start in Read only: classify() sends its mutating calls to approval anyway.
    assertThat(ToolDefinition.builder("ssh_run").nature(Nature.MIXED).build().defaultMode()).isEqualTo(Mode.READ_ONLY);
  }

  @Test
  void readOnlyModeIsRefusedForMutationsAndDestructiveTools() {
    assertThat(ToolDefinition.builder("a").nature(Nature.MUTATION).defaultMode(Mode.APPROVAL, Mode.APPROVAL)
        .annotations(Annotations.write(false)).build().readOnlyModeAllowed()).isFalse();
    assertThat(ToolDefinition.builder("b").nature(Nature.MIXED)
        .annotations(Annotations.write(true)).build().readOnlyModeAllowed()).isFalse();
    assertThat(ToolDefinition.builder("c").nature(Nature.MIXED)
        .annotations(Annotations.write(false)).build().readOnlyModeAllowed()).isTrue();
    assertThat(ToolDefinition.builder("d").annotations(new Annotations(null, null, null, null)).build()
        .readOnlyModeAllowed()).as("undeclared destructiveHint").isTrue();
  }

  @Test
  void annotationFactoriesAreExplicit() {
    assertThat(Annotations.read()).isEqualTo(new Annotations(true, false, true, false));
    assertThat(Annotations.write(true)).isEqualTo(new Annotations(false, true, false, false));
    assertThat(Annotations.write(false)).isEqualTo(new Annotations(false, false, false, false));
  }
}
