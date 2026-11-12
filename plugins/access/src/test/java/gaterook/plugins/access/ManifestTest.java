package gaterook.plugins.access;

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
 * The release as the gateway reads it (catalog entry {@code msaccess} 1.0.0): the manifest passes the parser the core
 * and the host share, the code serves exactly its four tools, three reads and one change, and the connection form is
 * the person's file with the limits collapsed.
 */
class ManifestTest {

  @Test
  void theManifestParsesAndTheCodeServesExactlyItsTools() throws Exception {
    PluginManifest manifest = PluginManifest.parse(TestCalls.manifest());
    assertThat(manifest.id()).isEqualTo("msaccess");
    assertThat(manifest.version()).isEqualTo("1.0.0");
    assertThat(manifest.name()).isEqualTo("Microsoft Access");
    assertThat(manifest.icon()).isEqualTo("database");
    assertThat(manifest.group()).isEqualTo("Databases");
    assertThat(manifest.toolNamespace()).isEqualTo("access_");
    assertThat(manifest.connectorClass()).isEqualTo(AccessConnector.class.getName());
    assertThat(manifest.requires().javaModules()).containsExactly("java.sql", "java.logging");

    Map<String, String> served = new LinkedHashMap<>();
    for (ToolDefinition t : new AccessConnector(TestCalls.manifest()).manifest().tools()) {
      served.put(t.name(), WireCodec.definitionHash(t));
    }
    assertThat(served).isEqualTo(manifest.definitionHashes());
  }

  @Test
  void threeReadsAndOneApprovedChangeThatAllStartInApproval() throws Exception {
    PluginManifest manifest = PluginManifest.parse(TestCalls.manifest());
    assertThat(manifest.tools()).extracting(ToolDefinition::name).containsExactly("access_list_tables",
        "access_describe_table", "access_query", "access_execute");
    for (ToolDefinition t : manifest.connectorManifest().tools()) {
      assertThat(t.defaultMode()).as(t.name()).isEqualTo(ToolDefinition.Mode.APPROVAL);
      assertThat(t.prodDefaultMode()).as(t.name()).isEqualTo(ToolDefinition.Mode.APPROVAL);
      assertThat(t.risk().egress()).as(t.name()).isEqualTo(ToolDefinition.Egress.NONE);
      if (t.name().equals("access_execute")) {
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
    JsonNode note = manifest.tool("access_execute").orElseThrow().inputSchema().path("properties").path("note");
    assertThat(note.path("description").asString())
        .isEqualTo("Why you need this change. Shown to the approver as unverified.");
    // A file of this computer: no host, no TCP destination, no command.
    assertThat(manifest.access().hosts()).isEmpty();
    assertThat(manifest.access().tcp()).isEmpty();
    assertThat(manifest.access().commands()).isEmpty();
    assertThat(manifest.access().files()).containsExactly("chosen in each connection");
  }

  @Test
  void theFileIsThePersonsAndTheLimitsAreCollapsed() throws Exception {
    PluginManifest manifest = PluginManifest.parse(TestCalls.manifest());
    JsonNode properties = manifest.configSchema().path("properties");
    List<String> shown = new ArrayList<>();
    for (var e : properties.properties()) {
      if (!e.getValue().path("x-collapsed").asBoolean(false)) {
        shown.add(e.getValue().path("title").asString());
      }
    }
    assertThat(shown).containsExactly("Database file");
    JsonNode file = properties.path("file");
    assertThat(file.path("x-person-only").asBoolean()).isTrue();
    assertThat(file.path("x-file-picker").asString()).isEqualTo("access-database");
    assertThat(file.path("pattern").asString()).isEqualTo("^[^?;\\u0000-\\u001f]+$");
    assertThat(properties.path("options").path("x-collapsed").asBoolean()).isTrue();
    assertThat(manifest.configSchema().toString()).doesNotContain("writeOnly");
  }
}
