package gaterook.plugins.clickhouse;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.plugin.protocol.PluginManifest;
import ai.msrv.gaterook.plugin.protocol.WireCodec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The release as the gateway reads it (catalog entry {@code clickhouse} 0.6.1): the manifest passes the parser the
 * core and the host share, the code serves exactly its six tools (the host's "tools ⊆ manifest" check by definition
 * hash), every tool is a declared read, and the connection form is the kit's "Host, port, database, user and
 * password" with the limits behind a collapsed section.
 */
class ManifestTest {

  @Test
  void theManifestParsesAndTheCodeServesExactlyItsTools() throws Exception {
    PluginManifest manifest = PluginManifest.parse(TestCalls.manifest());
    assertThat(manifest.id()).isEqualTo("clickhouse");
    assertThat(manifest.version()).isEqualTo("0.6.1");
    assertThat(manifest.name()).isEqualTo("ClickHouse");
    assertThat(manifest.icon()).isEqualTo("database");
    assertThat(manifest.group()).isEqualTo("Databases");
    assertThat(manifest.connectorClass()).isEqualTo(ClickHouseConnector.class.getName());

    Map<String, String> served = new LinkedHashMap<>();
    for (ToolDefinition t : new ClickHouseConnector(TestCalls.manifest()).manifest().tools()) {
      served.put(t.name(), WireCodec.definitionHash(t));
    }
    assertThat(served).isEqualTo(manifest.definitionHashes());
  }

  @Test
  void sixToolsAllDeclaredReadsThatStartInApproval() throws Exception {
    PluginManifest manifest = PluginManifest.parse(TestCalls.manifest());
    assertThat(manifest.tools()).extracting(ToolDefinition::name).containsExactly("clickhouse_query",
        "clickhouse_list_databases", "clickhouse_list_tables", "clickhouse_describe_table", "clickhouse_explain",
        "clickhouse_running_queries");
    for (ToolDefinition t : manifest.connectorManifest().tools()) {
      assertThat(t.nature()).as(t.name()).isEqualTo(ToolDefinition.Nature.READ);
      assertThat(t.annotations()).as(t.name()).isEqualTo(new ToolDefinition.Annotations(true, false, true, false));
      assertThat(t.defaultMode()).as(t.name()).isEqualTo(ToolDefinition.Mode.APPROVAL);
    }
    // The HTTP interface only: 8443 with TLS, 8123 without.
    assertThat(manifest.access().hosts()).containsExactly("{connection.host}:8443", "{connection.host}:8123");
    assertThat(manifest.access().commands()).isEmpty();
  }

  @Test
  void theConnectionFormIsHostPortDatabaseUserAndPassword() throws Exception {
    PluginManifest manifest = PluginManifest.parse(TestCalls.manifest());
    var properties = manifest.configSchema().path("properties");
    List<String> shown = new java.util.ArrayList<>();
    for (var e : properties.properties()) {
      if (!e.getValue().path("x-collapsed").asBoolean(false)) {
        shown.add(e.getValue().path("title").asString());
      }
    }
    assertThat(shown).containsExactly("Host", "Port", "Database", "Databases", "User", "Password");
    assertThat(manifest.configSchema().path("x-database-selection").path("path").asString()).isEqualTo("/databases");
    assertThat(properties.path("password").path("writeOnly").asBoolean()).isTrue();
    assertThat(properties.path("options").path("properties").path("tls").path("default").asBoolean()).isTrue();
  }
}
