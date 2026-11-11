package ai.msrv.gaterook.e2e.builtin;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connectors.support.PersonOnly;
import ai.msrv.gaterook.core.connections.ConnectionForms;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The connectors' own tests ask {@link PersonOnly#covers} whether their Trust patch writes a marked setting; the import
 * refuses what the core lists in {@code personOnlyFields}. Both must name the same settings of every built-in schema.
 */
class PersonOnlyTemplatesTest {

  @Test
  void theConnectorsWalkAndTheImportsListNameTheSameSettings() {
    Map<String, Integer> marked = new TreeMap<>();
    for (ConnectorManifest manifest : BuiltInManifests.load().values()) {
      var templates = PersonOnly.templates(manifest.configSchema());
      assertThat(templates).as(manifest.id())
          .isEqualTo(ConnectionForms.personOnlyFields(manifest.configSchema()));
      templates.forEach(template -> assertThat(PersonOnly.covers(manifest.configSchema(),
          template.replace("*", "0"))).as(manifest.id() + " " + template).isTrue());
      if (!templates.isEmpty()) marked.put(manifest.id(), templates.size());
    }
    // The comparison found marks (schemas without any would otherwise make it pass for nothing).
    assertThat(marked).containsOnlyKeys("aws", "azure", "azure-monitor", "http", "kubernetes", "logs", "sqlite", "ssh",
        "ssh-logs");
  }
}
