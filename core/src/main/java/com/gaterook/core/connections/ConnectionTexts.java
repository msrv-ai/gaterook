package com.gaterook.core.connections;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorManifest;
import com.gaterook.connector.api.SecretUnavailableException;
import com.gaterook.core.connections.internal.SecretFields;
import com.gaterook.core.secrets.ScopedSecretResolver;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

/**
 * Text fields read from a store ({@code vault://kv/prod/db#username} in the user or database field): one resolver for
 * every text field of every connector. The stored config keeps a placeholder there, like a secret field's reference;
 * the config a connector gets for one call has the value, read through that call's resolver, so a connector reads the
 * field as it reads a typed one. Values of text fields are not secret by their schema and aren't tainted; the fields
 * that say where credentials go ({@code host}, {@code url}) are never read from a store.
 */
public final class ConnectionTexts {

  private ConnectionTexts() {}

  /** The text fields of {@code cfg} read from a store, by pointer. */
  public static List<String> fromStore(ConnectionConfig cfg, ConnectorManifest manifest) {
    JsonNode schema = manifest == null ? null : manifest.configSchema();
    if (schema == null || cfg.secretFields().isEmpty()) {
      return List.of();
    }
    List<String> secrets = SecretFields.templates(schema);
    List<String> texts = SecretFields.textTemplates(schema);
    return cfg.secretFields().stream().filter(f -> !SecretFields.matches(secrets, f) && SecretFields.matches(texts, f))
        .sorted().toList();
  }

  /**
   * The config of one call with each text field read from its store: {@code cfg} itself when none is.
   *
   * @param secrets the call's resolver: the values are read for this call (cached by the store's TTL)
   * @throws SecretUnavailableException when a store can't answer: the call fails as it would for a secret
   */
  public static ConnectionConfig fill(ConnectionConfig cfg, ConnectorManifest manifest, ScopedSecretResolver secrets)
      throws SecretUnavailableException {
    List<String> fields = secrets == null ? List.of() : fromStore(cfg, manifest).stream().filter(secrets::isSet)
        .toList();
    if (fields.isEmpty()) {
      return cfg;
    }
    ObjectNode config = cfg.config() instanceof ObjectNode o ? o.deepCopy() : JsonNodeFactory.instance.objectNode();
    for (String field : fields) {
      SecretFields.set(config, field, StringNode.valueOf(secrets.text(field)));
    }
    return new ConnectionConfig(cfg.id(), cfg.name(), cfg.type(), cfg.env(), cfg.version(), config,
        cfg.secretFields(), cfg.metadata());
  }
}
