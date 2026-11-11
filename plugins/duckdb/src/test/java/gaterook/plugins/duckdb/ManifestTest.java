package gaterook.plugins.duckdb;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.plugin.protocol.PluginManifest;
import ai.msrv.gaterook.plugin.protocol.WireCodec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The release as the gateway reads it (catalog entry {@code duckdb} 1.0.0): the manifest passes the parser the core
 * and the host share, the code serves exactly its four tools (the host's "tools ⊆ manifest" check by definition hash),
 * three declared reads and one change without an output schema, and the connection form is the person's file and data
 * folders with the limits behind a collapsed section.
 */
class ManifestTest {

  @Test
  void theManifestParsesAndTheCodeServesExactlyItsTools() throws Exception {
    PluginManifest manifest = PluginManifest.parse(TestCalls.manifest());
    assertThat(manifest.id()).isEqualTo("duckdb");
    assertThat(manifest.version()).isEqualTo("1.0.0");
    assertThat(manifest.name()).isEqualTo("DuckDB");
    assertThat(manifest.icon()).isEqualTo("database");
    assertThat(manifest.group()).isEqualTo("Databases");
    assertThat(manifest.toolNamespace()).isEqualTo("duckdb_");
    assertThat(manifest.connectorClass()).isEqualTo(DuckDbConnector.class.getName());
    assertThat(manifest.requires().javaModules()).containsExactly("java.sql");

    Map<String, String> served = new LinkedHashMap<>();
    for (ToolDefinition t : new DuckDbConnector(TestCalls.manifest()).manifest().tools()) {
      served.put(t.name(), WireCodec.definitionHash(t));
    }
    assertThat(served).isEqualTo(manifest.definitionHashes());
  }

  @Test
  void threeReadsAndOneApprovedChangeThatAllStartInApproval() throws Exception {
    PluginManifest manifest = PluginManifest.parse(TestCalls.manifest());
    assertThat(manifest.tools()).extracting(ToolDefinition::name).containsExactly("duckdb_query",
        "duckdb_list_tables", "duckdb_describe_table", "duckdb_execute");
    for (ToolDefinition t : manifest.connectorManifest().tools()) {
      assertThat(t.defaultMode()).as(t.name()).isEqualTo(ToolDefinition.Mode.APPROVAL);
      assertThat(t.prodDefaultMode()).as(t.name()).isEqualTo(ToolDefinition.Mode.APPROVAL);
      assertThat(t.risk().egress()).as(t.name()).isEqualTo(ToolDefinition.Egress.NONE);
      if (t.name().equals("duckdb_execute")) {
        assertThat(t.nature()).isEqualTo(ToolDefinition.Nature.MUTATION);
        assertThat(t.annotations()).isEqualTo(ToolDefinition.Annotations.write(true));
        assertThat(t.outputSchema()).isNull();
        assertThat(t.readOnlyModeAllowed()).isFalse();
      } else {
        assertThat(t.nature()).as(t.name()).isEqualTo(ToolDefinition.Nature.READ);
        assertThat(t.annotations()).as(t.name()).isEqualTo(ToolDefinition.Annotations.read());
        assertThat(t.readOnlyModeAllowed()).as(t.name()).isTrue();
      }
    }
    // The agent says why it wants a change the way sql_execute takes it: the core shows args.note to the approver.
    JsonNode note = manifest.tool("duckdb_execute").orElseThrow().inputSchema().path("properties").path("note");
    assertThat(note.path("type").asString()).isEqualTo("string");
    assertThat(note.path("maxLength").asInt()).isEqualTo(500);
    assertThat(note.path("description").asString())
        .isEqualTo("Why you need this change. Shown to the approver as unverified.");
    assertThat(manifest.tool("duckdb_execute").orElseThrow().inputSchema().path("required").toString())
        .isEqualTo("[\"sql\"]");
    // A local file: no host, no command; the files are the ones each connection names.
    assertThat(manifest.access().hosts()).isEmpty();
    assertThat(manifest.access().commands()).isEmpty();
    assertThat(manifest.access().files()).containsExactly("chosen in each connection");
  }

  @Test
  void theFileAndTheDataFoldersAreThePersonsAndTheLimitsAreCollapsed() throws Exception {
    PluginManifest manifest = PluginManifest.parse(TestCalls.manifest());
    JsonNode properties = manifest.configSchema().path("properties");
    List<String> shown = new ArrayList<>();
    for (var e : properties.properties()) {
      if (!e.getValue().path("x-collapsed").asBoolean(false)) {
        shown.add(e.getValue().path("title").asString());
      }
    }
    assertThat(shown).containsExactly("Database file", "Data folders");
    assertThat(manifest.configSchema().path("required").toString()).isEqualTo("[\"file\"]");
    JsonNode file = properties.path("file");
    assertThat(file.path("x-person-only").asBoolean()).isTrue();
    assertThat(file.path("x-file-picker").asString()).isEqualTo("duckdb-database");
    assertThat(file.path("pattern").asString()).isEqualTo("^[^?;\\u0000-\\u001f]+$");
    assertThat(properties.path("dataFolders").path("x-person-only").asBoolean()).isTrue();
    assertThat(properties.path("dataFolders").has("default")).isFalse();
    JsonNode options = properties.path("options");
    assertThat(options.path("x-collapsed").asBoolean()).isTrue();
    assertThat(options.path("properties").path("memoryLimitMb").path("default").asInt()).isEqualTo(512);
    assertThat(options.path("properties").path("threads").path("default").asInt()).isEqualTo(2);
    // Nothing is a secret: a DuckDB file has no password.
    assertThat(manifest.configSchema().toString()).doesNotContain("writeOnly");
  }
}
