package ai.msrv.gaterook.connectors.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class SchemaResourcesTest {

  private final SchemaResources schemas = SchemaResources.of(SchemaResourcesTest.class, "test");

  @Test
  void loadsAnObjectSchema() {
    JsonNode schema = schemas.load("ok");

    assertThat(schema.path("type").asString()).isEqualTo("object");
    assertThat(schema.at("/properties/connection/type").asString()).isEqualTo("string");
  }

  @Test
  void everyLoadReturnsAFreshTree() {
    assertThat(schemas.load("ok")).isNotSameAs(schemas.load("ok")).isEqualTo(schemas.load("ok"));
  }

  @Test
  void reportsTheNativeResourcePattern() {
    assertThat(schemas.resourcePattern()).isEqualTo("connectors/test/*.schema.json");
  }

  @Test
  void missingResourceIsAPackagingError() {
    assertThatThrownBy(() -> schemas.load("absent"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("connectors/test/absent.schema.json")
        .hasMessageContaining("missing");
  }

  @Test
  void schemaMustBeAnObject() {
    assertThatThrownBy(() -> schemas.load("array"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not a JSON object");
  }

  @Test
  void brokenJsonNamesTheResource() {
    assertThatThrownBy(() -> schemas.load("broken"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("connectors/test/broken.schema.json");
  }

  @Test
  void directoryIsASingleSafeSegment() {
    assertThatThrownBy(() -> SchemaResources.of(SchemaResourcesTest.class, "../etc"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SchemaResources.of(SchemaResourcesTest.class, "a/b"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
