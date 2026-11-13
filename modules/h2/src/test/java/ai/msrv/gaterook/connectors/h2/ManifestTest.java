package ai.msrv.gaterook.connectors.h2;

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
 * The module as the gateway reads it: the manifest passes the parser the core and
 * the host share, the code serves exactly its four tools, three reads and one change, its own sockets go only to the
 * connection's host and port, and it needs a gateway whose host gates them (connector-api 1.1).
 */
class ManifestTest {

  @Test
  void theManifestParsesAndTheCodeServesExactlyItsTools() throws Exception {
    PluginManifest manifest = PluginManifest.parse(TestCalls.manifest());
    assertThat(manifest.id()).isEqualTo("h2");
    assertThat(manifest.version()).isEqualTo("1.1.0");
    assertThat(manifest.name()).isEqualTo("H2");
    assertThat(manifest.icon()).isEqualTo("database");
    assertThat(manifest.group()).isEqualTo("Databases");
    assertThat(manifest.module()).isTrue();
    assertThat(manifest.moduleClass()).isEqualTo(H2Module.class.getName());
    assertThat(manifest.requires().javaModules()).containsExactly("java.sql");
    // A module needs the connector API that has modules (1.2), which is past the one that has the TCP gate (1.1).
    assertThat(manifest.requires().connectorApi()).isEqualTo(">=1.2.0 & <2.0.0");

    Map<String, String> served = new LinkedHashMap<>();
    for (ToolDefinition t : new H2Connector(TestCalls.definition(), false).manifest().tools()) {
      served.put(t.name(), WireCodec.definitionHash(t));
    }
    assertThat(manifest.definitionHashesByType()).containsOnlyKeys("h2");
    assertThat(served).isEqualTo(manifest.definitionHashesByType().get("h2"));
  }

  @Test
  void threeReadsThatRunAtOnceAndOneChangeThatWaitsForApproval() throws Exception {
    PluginManifest manifest = PluginManifest.parse(TestCalls.manifest());
    assertThat(manifest.tools()).extracting(ToolDefinition::name).containsExactly("h2_list_tables",
        "h2_describe_table", "h2_query", "h2_execute");
    assertThat(manifest.connectorManifests()).extracting(m -> m.kind())
        .containsExactly(ai.msrv.gaterook.connector.api.ConnectorManifest.Kind.BUILT_IN);
    for (ToolDefinition t : manifest.connectorManifests().getFirst().tools()) {
      // One of GateRook's own types: a read runs at once, a change waits for a person, on every environment.
      ToolDefinition.Mode expected = t.nature() == ToolDefinition.Nature.READ ? ToolDefinition.Mode.READ_ONLY
          : ToolDefinition.Mode.APPROVAL;
      assertThat(t.defaultMode()).as(t.name()).isEqualTo(expected);
      assertThat(t.prodDefaultMode()).as(t.name()).isEqualTo(expected);
      assertThat(t.risk().egress()).as(t.name()).isEqualTo(ToolDefinition.Egress.NONE);
      if (t.name().equals("h2_execute")) {
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
    JsonNode note = manifest.tool("h2_execute").orElseThrow().inputSchema().path("properties").path("note");
    assertThat(note.path("description").asString())
        .isEqualTo("Why you need this change. Shown to the approver as unverified.");
    // Its own TCP connections, to the connection's host and port only; no HTTP host, command or file.
    assertThat(manifest.access().tcp()).containsExactly("{connection.host}:{connection.port}");
    assertThat(manifest.access().hosts()).isEmpty();
    assertThat(manifest.access().commands()).isEmpty();
    assertThat(manifest.access().files()).isEmpty();
    assertThat(manifest.connectorManifest().access().tcp()).containsExactly("{connection.host}:{connection.port}");
  }

  @Test
  void theFormAsksForTheServerTheDatabaseAndTheLoginWithTheLimitsCollapsed() throws Exception {
    PluginManifest manifest = PluginManifest.parse(TestCalls.manifest());
    JsonNode properties = manifest.configSchema().path("properties");
    List<String> shown = new ArrayList<>();
    for (var e : properties.properties()) {
      if (!e.getValue().path("x-collapsed").asBoolean(false)) {
        shown.add(e.getValue().path("title").asString());
      }
    }
    assertThat(shown).containsExactly("Host", "Port", "Database", "User", "Password");
    assertThat(properties.path("port").path("default").asInt()).isEqualTo(9092);
    assertThat(properties.path("password").path("writeOnly").asBoolean()).isTrue();
    assertThat(properties.path("password").path("x-gateway-secret").asString()).isEqualTo("value");
    // The URL's settings are GateRook's: no ';' in the database's name.
    assertThat(properties.path("database").path("pattern").asString()).isEqualTo("^[^;\\u0000-\\u001f]+$");
    assertThat(properties.path("options").path("x-collapsed").asBoolean()).isTrue();
  }
}
