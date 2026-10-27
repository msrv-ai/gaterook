package ai.msrv.gaterook.core.connectors.plugins;

import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.core.connectors.ManifestCodec;
import ai.msrv.gaterook.core.connectors.internal.persistence.PluginEntity;
import ai.msrv.gaterook.core.connectors.internal.persistence.PluginRepository;
import ai.msrv.gaterook.core.connectors.internal.persistence.PluginState;
import ai.msrv.gaterook.core.platform.IntegrityAlerts;
import ai.msrv.gaterook.core.platform.RowMac;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The {@code plugin} table behind a MAC (ARCHITECTURE.md §4): the row carries the installed version, where the JAR came
 * from, its SHA-256 and its stored manifest, signed with {@link RowMac} over the fields that matter for safety. A row
 * that doesn't verify ("Changed outside GateRook") is reported and treated as absent until a person reviews it, so
 * a JAR swapped on disk or a state flipped in the file can't make the core load it.
 */
@Component
public class PluginStore {

  static final String TABLE = "plugin";

  private final PluginRepository repository;
  private final RowMac rowMac;
  private final IntegrityAlerts alerts;
  private final JsonMapper json;

  PluginStore(PluginRepository repository, RowMac rowMac, IntegrityAlerts alerts, JsonMapper json) {
    this.repository = repository;
    this.rowMac = rowMac;
    this.alerts = alerts;
    this.json = json;
  }

  PluginRepository repository() {
    return repository;
  }

  /** Every installed row that verifies; a row that doesn't is reported and left out. */
  List<PluginEntity> verifiedAll() {
    List<PluginEntity> out = new java.util.ArrayList<>();
    for (PluginEntity row : repository.findAll()) {
      if (verify(row)) {
        out.add(row);
      } else {
        alerts.report("plugin", row.getId(), row.getId(), "plugin row");
      }
    }
    return List.copyOf(out);
  }

  Optional<PluginEntity> find(String id) {
    return repository.findById(id).filter(row -> {
      if (verify(row)) {
        return true;
      }
      alerts.report("plugin", id, id, "plugin row");
      return false;
    });
  }

  /** The stored manifest of a plugin, as the registry and {@code tools/list} use it (no host started). */
  ConnectorManifest manifest(PluginEntity row) {
    JsonNode tree = rowMac.tree(row.getManifest());
    return ManifestCodec.read(tree);
  }

  void sign(PluginEntity row) {
    row.setMac(rowMac.sign(TABLE, row.getId(), row.getVersion(), macFields(row)));
    repository.saveAndFlush(row);
  }

  boolean verify(PluginEntity row) {
    return rowMac.verify(TABLE, row.getId(), row.getVersion(), macFields(row), row.getMac());
  }

  private JsonNode macFields(PluginEntity row) {
    ObjectNode f = rowMac.fields();
    f.put("currentVersion", row.getCurrentVersion());
    f.put("previousVersion", row.getPreviousVersion());
    f.put("state", row.getState().name());
    f.put("signed", row.isSigned());
    f.put("source", row.getSource().name());
    f.put("sha256", row.getSha256());
    f.put("sizeBytes", row.getSizeBytes());
    f.put("catalogEntry", row.getCatalogEntry());
    // Since V50: absent from rows signed before it, so they still verify.
    if (row.getPreviousSha256() != null) {
      f.put("previousSha256", row.getPreviousSha256());
    }
    return f;
  }

  @SuppressWarnings("unchecked")
  java.util.Map<String, Object> map(ConnectorManifest manifest) {
    return json.treeToValue(ManifestCodec.write(manifest), java.util.Map.class);
  }

  boolean isState(PluginEntity row, PluginState state) {
    return row.getState() == state;
  }
}
