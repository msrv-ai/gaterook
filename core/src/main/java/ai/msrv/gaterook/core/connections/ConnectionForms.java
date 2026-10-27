package ai.msrv.gaterook.core.connections;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.connections.internal.SchemaPointers;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * What a connection form needs to know about a connector's {@code configSchema} beyond the schema itself
 * (ARCHITECTURE.md §6 {@code GET /api/connector-types/{type}/form}, §8.3): where the secret fields are and what kind of
 * secret each takes ({@code x-gateway-secret}), and which fields an environment starts with another value
 * ({@code x-env-default}, "what changes on prod"). The walk is the one the core uses when it saves a connection, so the
 * form and the server agree on every field, including those behind {@code oneOf} branches and local {@code $ref}s.
 */
public final class ConnectionForms {

  /**
   * One secret field.
   *
   * @param pointer JSON Pointer template ({@code *} for an array index)
   * @param kind    {@code value}, {@code login}, {@code pem} or {@code binary}
   */
  public record SecretField(String pointer, String kind, String title) {}

  /**
   * A field whose value on {@code env} differs from its default elsewhere.
   *
   * @param from the field's {@code default}, or {@code null} without one
   * @param to   its {@code x-env-default} for the environment
   */
  public record EnvDefault(String pointer, String title, JsonNode from, JsonNode to) {}

  private ConnectionForms() {}

  public static List<SecretField> secretFields(JsonNode configSchema) {
    if (configSchema == null) {
      return List.of();
    }
    Map<String, SecretField> out = new LinkedHashMap<>();
    for (SchemaPointers.Located l : SchemaPointers.collect(configSchema, s -> s.path("writeOnly").asBoolean(false))) {
      out.putIfAbsent(l.template(), new SecretField(l.template(), l.schema().path("x-gateway-secret").asString("value"),
          l.schema().path("title").asString(null)));
    }
    return List.copyOf(out.values());
  }

  /**
   * The {@code x-variants} entry of a form a config is made from (Logs: Loki, GCP Cloud Logging, …): the first whose
   * {@code presetConfig} the config holds value by value.
   *
   * @param id   {@code loki}
   * @param name {@code Loki}, as the wizard's card and the list's Type column say it
   */
  public record Variant(String id, String name) {}

  public static java.util.Optional<Variant> variant(JsonNode configSchema, JsonNode config) {
    if (configSchema == null || config == null) {
      return java.util.Optional.empty();
    }
    for (JsonNode v : configSchema.path("x-variants")) {
      if (v.path("id").isString() && v.path("presetConfig").isObject() && holds(config, v.get("presetConfig"))) {
        return java.util.Optional.of(new Variant(v.get("id").asString(), v.path("name").asString(v.get("id")
            .asString())));
      }
    }
    return java.util.Optional.empty();
  }

  /** Whether {@code config} has every value of {@code preset} (objects compared key by key). */
  public static boolean holds(JsonNode config, JsonNode preset) {
    for (Map.Entry<String, JsonNode> e : preset.properties()) {
      JsonNode value = config.path(e.getKey());
      if (e.getValue().isObject() ? !holds(value, e.getValue()) : !e.getValue().equals(value)) {
        return false;
      }
    }
    return true;
  }

  /**
   * The {@code metadata} paths a form lists its options from ({@code x-options-from: metadata.kubeconfigContexts} →
   * {@code kubeconfigContexts}): the only part of a connection's metadata a form needs.
   */
  public static List<String> optionsFrom(JsonNode configSchema) {
    if (configSchema == null) {
      return List.of();
    }
    java.util.Set<String> out = new java.util.LinkedHashSet<>();
    for (SchemaPointers.Located l : SchemaPointers.collect(configSchema,
        s -> s.path("x-options-from").asString("").startsWith("metadata."))) {
      String path = l.schema().path("x-options-from").asString().substring("metadata.".length());
      if (!path.isBlank()) {
        out.add(path);
      }
    }
    return List.copyOf(out);
  }

  public static List<EnvDefault> envDefaults(JsonNode configSchema, Env env) {
    if (configSchema == null) {
      return List.of();
    }
    List<EnvDefault> out = new ArrayList<>();
    for (SchemaPointers.Located l : SchemaPointers.collect(configSchema,
        s -> s.path("x-env-default").has(env.id()))) {
      JsonNode to = l.schema().path("x-env-default").get(env.id());
      JsonNode from = l.schema().get("default");
      if (from == null || !from.equals(to)) {
        out.add(new EnvDefault(l.template(), l.schema().path("title").asString(null), from, to));
      }
    }
    return List.copyOf(out);
  }
}
