package ai.msrv.gaterook.core.connections.internal;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.core.connections.internal.persistence.ConnectionEntity;
import ai.msrv.gaterook.core.connections.internal.persistence.ConnectionRepository;
import ai.msrv.gaterook.core.platform.RowMac;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Rows of {@code connection}: their MAC (name, type, env, config with its secret placeholders, metadata; health is
 * not part of it and is written without a new version) and the {@link ConnectionConfig} connectors get.
 */
@Component
public class ConnectionRows {

  private static final String TABLE = "connection";

  private final RowMac rowMac;
  private final ConnectionRepository repository;
  private final JsonMapper json;

  public ConnectionRows(RowMac rowMac, ConnectionRepository repository, JsonMapper json) {
    this.rowMac = rowMac;
    this.repository = repository;
    this.json = json;
  }

  /** Signs a row after the flush that settled its version, and writes the MAC (no new version: excluded). */
  public void sign(ConnectionEntity row) {
    row.setMac(rowMac.sign(TABLE, row.getId(), row.getVersion(), fields(row)));
    repository.saveAndFlush(row);
  }

  public boolean verify(ConnectionEntity row) {
    return rowMac.verify(TABLE, row.getId(), row.getVersion(), fields(row), row.getMac());
  }

  private JsonNode fields(ConnectionEntity row) {
    ObjectNode f = rowMac.fields();
    f.put("name", row.getName());
    f.put("type", row.getType());
    f.put("env", row.getEnv().id());
    f.set("config", rowMac.tree(row.getConfig()));
    f.set("metadata", rowMac.tree(row.getMetadata()));
    return f;
  }

  /** The stored config as a tree, placeholders included. */
  public ObjectNode storedConfig(ConnectionEntity row) {
    return (ObjectNode) rowMac.tree(row.getConfig());
  }

  /** What connectors get: the config without placeholders, the names of its secret fields, the metadata. */
  public ConnectionConfig toConfig(ConnectionEntity row, ConnectorManifest manifest) {
    ObjectNode stored = storedConfig(row);
    Set<String> secretFields = new LinkedHashSet<>();
    if (manifest != null && manifest.configSchema() != null) {
      for (String template : SecretFields.templates(manifest.configSchema())) {
        if (!template.contains("*")) {
          secretFields.add(template);
        }
      }
    }
    secretFields.addAll(SecretFields.placeholders(stored).keySet());
    JsonNode metadata = row.getMetadata() == null ? null : rowMac.tree(row.getMetadata());
    return new ConnectionConfig(row.getId(), row.getName(), row.getType(), row.getEnv(), row.getVersion(),
        SecretFields.strip(stored), Set.copyOf(secretFields), metadata);
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> map(JsonNode tree) {
    return tree == null || tree.isNull() ? null : json.treeToValue(tree, Map.class);
  }
}
