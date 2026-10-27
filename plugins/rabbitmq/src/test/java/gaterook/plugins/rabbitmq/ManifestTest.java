package gaterook.plugins.rabbitmq;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.plugin.protocol.PluginManifest;
import ai.msrv.gaterook.plugin.protocol.WireCodec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The release as the gateway reads it (catalog entry {@code rabbitmq} 0.3.0, unsigned): the manifest passes the
 * parser the core and the host share, the code serves exactly its four tools, three reads and one mutation that waits
 * for approval, and it asks for port 15672 of the connection's host only — the kit's review of the unsigned plugin.
 */
class ManifestTest {

  @Test
  void theManifestParsesAndTheCodeServesExactlyItsTools() throws Exception {
    PluginManifest manifest = PluginManifest.parse(TestCalls.manifest());
    assertThat(manifest.id()).isEqualTo("rabbitmq");
    assertThat(manifest.version()).isEqualTo("0.3.0");
    assertThat(manifest.name()).isEqualTo("RabbitMQ");
    assertThat(manifest.connectorClass()).isEqualTo(RabbitMqConnector.class.getName());
    Map<String, String> served = new LinkedHashMap<>();
    for (ToolDefinition t : new RabbitMqConnector(TestCalls.manifest()).manifest().tools()) {
      served.put(t.name(), WireCodec.definitionHash(t));
    }
    assertThat(served).isEqualTo(manifest.definitionHashes());
  }

  @Test
  void threeReadsAndAPublishThatWaitsForApproval() throws Exception {
    PluginManifest manifest = PluginManifest.parse(TestCalls.manifest());
    assertThat(manifest.tools()).extracting(ToolDefinition::name).containsExactly("rabbitmq_queues",
        "rabbitmq_consumers", "rabbitmq_dead_letters", "rabbitmq_publish");
    for (ToolDefinition t : manifest.connectorManifest().tools()) {
      assertThat(t.defaultMode()).as(t.name()).isEqualTo(ToolDefinition.Mode.APPROVAL);
      assertThat(t.prodDefaultMode()).as(t.name()).isEqualTo(ToolDefinition.Mode.APPROVAL);
      boolean publish = t.name().equals("rabbitmq_publish");
      assertThat(t.nature()).as(t.name()).isEqualTo(publish ? ToolDefinition.Nature.MUTATION
          : ToolDefinition.Nature.READ);
      assertThat(t.annotations().readOnlyHint()).as(t.name()).isEqualTo(!publish);
    }
    assertThat(manifest.access().hosts()).containsExactly("{connection.host}:15672");
  }

  @Test
  void theConnectionFormIsHostPortVirtualHostUserAndPassword() throws Exception {
    PluginManifest manifest = PluginManifest.parse(TestCalls.manifest());
    List<String> shown = new ArrayList<>();
    for (var e : manifest.configSchema().path("properties").properties()) {
      if (!e.getValue().path("x-collapsed").asBoolean(false)) {
        shown.add(e.getValue().path("title").asString());
      }
    }
    assertThat(shown).containsExactly("Host", "Port", "Virtual host", "User", "Password");
  }
}
