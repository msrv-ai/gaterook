package ai.msrv.gaterook.connectors.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class ToolNamesAndSchemasTest {

  private static final Function<String, Optional<String>> NONE = n -> Optional.empty();

  @Test
  void upstreamNamesBecomeGatewayToolNames() {
    assertThat(ToolNameMapper.map("github_", "create_issue", Set.of(), NONE).exposed())
        .isEqualTo("github_create_issue");
    assertThat(ToolNameMapper.map("fs_", "Read-File.v2", Set.of(), NONE).exposed()).isEqualTo("fs_read_file_v2");
    String long64 = ToolNameMapper.map("github_", "x".repeat(80), Set.of(), NONE).exposed();
    assertThat(long64).hasSize(64).matches("^github_x+_[0-9a-f]{4}$");
  }

  @Test
  void twoNamesThatMapAlikeKeepApartByHash() {
    String first = ToolNameMapper.map("fs_", "read-file", Set.of(), NONE).exposed();
    String second = ToolNameMapper.map("fs_", "read.file", Set.of(first), NONE).exposed();

    assertThat(first).isEqualTo("fs_read_file");
    assertThat(second).startsWith("fs_read_file_").hasSize("fs_read_file_".length() + 4);
  }

  @Test
  void reservedAndUnwritableNamesAreRefusedNotRenamed() {
    ToolNameMapper.Name reserved = ToolNameMapper.map("sql_", "query", Set.of(),
        n -> n.equals("sql_query") ? Optional.of("sql_query is a built-in tool") : Optional.empty());
    assertThat(reserved.exposed()).isNull();
    assertThat(reserved.problem()).isEqualTo("sql_query is a built-in tool");
    assertThat(ToolNameMapper.map("x_", "", Set.of(), NONE).problem()).isEqualTo("it has no name");
    assertThat(ToolNameMapper.map("x_", "y".repeat(129), Set.of(), NONE).problem()).contains("128");
  }

  @Test
  void schemasAreNormalizedTo2020_12() {
    ObjectNode declared = Fixtures.object("""
        {"$schema":"http://json-schema.org/draft-07/schema#",
         "properties":{"repo":{"$ref":"#/definitions/name"},"definitions":{"type":"string"}},
         "definitions":{"name":{"type":"string"}},
         "dependencies":{"repo":["owner"]}}""");

    ToolSchemas.Result r = ToolSchemas.normalize(declared, true);

    assertThat(r.problem()).isNull();
    assertThat(r.schema().has("$schema")).isFalse();
    assertThat(r.schema().path("type").asString()).isEqualTo("object");
    assertThat(r.schema().path("$defs").has("name")).isTrue();
    assertThat(r.schema().path("properties").path("repo").path("$ref").asString()).isEqualTo("#/$defs/name");
    // A property that happens to be called "definitions" is a property, not a keyword.
    assertThat(r.schema().path("properties").path("definitions").path("type").asString()).isEqualTo("string");
    assertThat(r.schema().path("dependentRequired").path("repo").get(0).asString()).isEqualTo("owner");
  }

  @Test
  void schemasThatCantBePublishedSayWhy() {
    assertThat(ToolSchemas.normalize(Fixtures.object("{\"type\":\"array\"}"), true).problem())
        .contains("doesn't describe an object");
    assertThat(ToolSchemas.normalize(Fixtures.object("{\"properties\":{\"connection\":{\"type\":\"string\"}}}"),
        true).problem()).contains("connection");
    assertThat(ToolSchemas.normalize(Fixtures.object("{\"properties\":{\"a\":{\"type\":5}}}"), true).problem())
        .contains("isn't valid JSON Schema 2020-12");
    ObjectNode big = Fixtures.object("{\"properties\":{}}");
    for (int i = 0; i < 2_000; i++) {
      ((ObjectNode) big.get("properties")).putObject("p" + i).put("description", "x".repeat(40));
    }
    assertThat(ToolSchemas.normalize(big, true).problem()).contains("64 KB");
  }
}
