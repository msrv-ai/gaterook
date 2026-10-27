package ai.msrv.gaterook.core.connections.internal;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.platform.CanonicalJson;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * The "Prod is stricter" locks of a connector's {@code configSchema} (ARCHITECTURE.md §8.3, §9), which networknt
 * ignores as unknown keywords and the form only shows: the server is where they hold.
 * <ul>
 *   <li>{@code "x-forbidden-env-values": {"prod": ["apiKeyQuery"]}} — values that can't be chosen on that
 *       environment (an API key in the URL, AWS without its read-only session policy, Redis TLS without the host name
 *       check);</li>
 *   <li>{@code "x-forbidden-env": ["prod"]} — an option that stays off there: the field may only be missing or hold
 *       its {@code default} ({@code insecureSkipTlsVerify}, {@code allowPlainHttp}).</li>
 * </ul>
 * Checked on every config that is saved or tested, with the environment it is saved for: moving a connection to prod
 * checks it again.
 */
public final class EnvLocks {

  /** What a config may not hold on its environment, and where. */
  public record Violation(String pointer, String message) {}

  public static final String FORBIDDEN_VALUES = "x-forbidden-env-values";
  public static final String FORBIDDEN = "x-forbidden-env";

  private EnvLocks() {}

  /** The first lock {@code config} breaks on {@code env}, if any. */
  public static Optional<Violation> check(JsonNode schema, JsonNode config, Env env) {
    for (SchemaPointers.Located locked : SchemaPointers.collect(schema, EnvLocks::locks)) {
      JsonNode keySchema = locked.schema();
      for (Map.Entry<String, JsonNode> at : SchemaPointers.values(config, locked.template()).entrySet()) {
        JsonNode value = at.getValue();
        if (value.isNull() || SecretFields.isPlaceholder(value)) {
          continue;
        }
        String label = label(keySchema, at.getKey());
        for (JsonNode forbidden : keySchema.path(FORBIDDEN_VALUES).path(env.id())) {
          if (same(forbidden, value)) {
            return Optional.of(new Violation(at.getKey(), label + " can't be " + text(value) + " on " + env.id()
                + "."));
          }
        }
        if (contains(keySchema.path(FORBIDDEN), env.id())) {
          JsonNode allowed = keySchema.get("default");
          if (allowed == null || !same(allowed, value)) {
            return Optional.of(new Violation(at.getKey(), label + " isn't allowed on " + env.id() + "."));
          }
        }
      }
    }
    return Optional.empty();
  }

  private static boolean locks(JsonNode schema) {
    return schema.path(FORBIDDEN_VALUES).isObject() || schema.path(FORBIDDEN).isArray();
  }

  private static boolean contains(JsonNode envs, String env) {
    for (JsonNode e : envs) {
      if (env.equals(e.asString(null))) {
        return true;
      }
    }
    return false;
  }

  /** The same JSON value however it was typed ({@code 5} and {@code 5.0}). */
  private static boolean same(JsonNode a, JsonNode b) {
    return CanonicalJson.text(a).equals(CanonicalJson.text(b));
  }

  private static String label(JsonNode keySchema, String pointer) {
    String title = keySchema.path("title").asString("");
    return title.isBlank() ? SecretFields.unescape(pointer.substring(pointer.lastIndexOf('/') + 1)) : title;
  }

  private static String text(JsonNode value) {
    return value.isString() ? value.asString() : CanonicalJson.text(value);
  }
}
