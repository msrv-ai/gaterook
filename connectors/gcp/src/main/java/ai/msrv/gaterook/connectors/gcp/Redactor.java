package ai.msrv.gaterook.connectors.gcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Removes values that GCP returns as plain configuration but that are secrets more often than not (report 09 §6
 * "Redaction"), before the answer leaves the connector; the core's masker runs after it. Counted, so the answer can
 * say "3 fields masked".
 *
 * <ul>
 *   <li>Compute metadata ({@code metadata.items[*].value}: startup scripts, keys), the project's too
 *       ({@code commonInstanceMetadata.items[*].value}), and the metadata maps of GKE and Dataproc nodes
 *       ({@code nodeConfig.metadata}, a node pool's {@code config.metadata}, {@code gceClusterConfig.metadata});</li>
 *   <li>container environments ({@code env[*].value} of Cloud Run and Kubernetes containers, {@code env} as a map),
 *       environment maps ({@code environmentVariables}, {@code buildEnvironmentVariables} of functions,
 *       {@code envVariables} of App Engine and Composer, {@code userEnvVars} of Workflows,
 *       {@code environment.variables} of Batch);</li>
 *   <li>GKE {@code masterAuth.password} and {@code masterAuth.clientKey};</li>
 *   <li>the data of a Kubernetes Secret;</li>
 *   <li>everywhere, a text value whose key names a secret
 *       ({@code password, passwd, secret, token, private_key, client_key, credential, connection_string, user_data,
 *       auth_string}).</li>
 * </ul>
 * References to secrets ({@code valueSource.secretKeyRef}, {@code secretEnvironmentVariables}) carry names only and
 * stay readable, except where the key itself says "secret".
 */
final class Redactor {

  static final String MASK = "‹masked›";

  private static final Pattern SENSITIVE_KEY = Pattern.compile(
      "(?i).*(password|passwd|secret|token|private_?key|client_?key|credential|connection_?string|user_?data"
          + "|auth_?string).*");
  /** Maps (or {@code [{name, value}]} lists) whose every value is an environment variable. */
  private static final Set<String> ENV_MAPS = Set.of("environmentVariables", "buildEnvironmentVariables",
      "envVariables", "userEnvVars", "envVars", "env", "envs");
  /** Keys that end in a sensitive word but hold counts or settings, not values. */
  private static final Pattern NOT_A_VALUE = Pattern.compile("(?i).*(count|ttl|seconds|enabled|type|policy|"
      + "version|expir\\w*|time|duration|rotation\\w*|name|id|uri|url|ref|source|lifetime)$");

  private Redactor() {}

  /** Masks in place; returns the number of values masked. */
  static int redact(JsonNode node) {
    return walk(node, null);
  }

  private static int walk(JsonNode node, String key) {
    int masked = 0;
    if (node instanceof ObjectNode o) {
      boolean secretObject = "Secret".equals(o.path("kind").asString(""));
      List<Map.Entry<String, JsonNode>> fields = new ArrayList<>(o.properties());
      for (Map.Entry<String, JsonNode> f : fields) {
        String k = f.getKey();
        JsonNode v = f.getValue();
        if (secretObject && (k.equals("data") || k.equals("stringData")) && v.isObject()) {
          masked += maskAll((ObjectNode) v);
        } else if (ENV_MAPS.contains(k) && v.isObject()) {
          masked += maskAll((ObjectNode) v);
        } else if (k.equals("variables") && "environment".equals(key) && v.isObject()) {
          masked += maskAll((ObjectNode) v);
        } else if ((k.equals("metadata") || k.equals("commonInstanceMetadata")) && v.path("items").isArray()) {
          masked += maskItemValues((ArrayNode) v.path("items"));
          masked += walk(v, k);
        } else if (k.equals("metadata") && v.isObject() && key != null
            && key.toLowerCase(Locale.ROOT).endsWith("config")) {
          // A node's metadata map (GKE nodeConfig, a node pool's config, Dataproc's gceClusterConfig): the same
          // startup scripts and keys as an instance's items. A Kubernetes object's metadata (names, labels) is never
          // under a *config key.
          masked += maskAll((ObjectNode) v);
        } else if (ENV_MAPS.contains(k) && v.isArray()) {
          masked += maskItemValues((ArrayNode) v);
          masked += walk(v, k);
        } else if (k.equals("masterAuth") && v.isObject()) {
          masked += maskField((ObjectNode) v, "password");
          masked += maskField((ObjectNode) v, "clientKey");
          masked += walk(v, k);
        } else if (v.isString() && sensitiveKey(k)) {
          masked += maskField(o, k);
        } else {
          masked += walk(v, k);
        }
      }
    } else if (node instanceof ArrayNode a) {
      for (JsonNode item : a) {
        masked += walk(item, key);
      }
    }
    return masked;
  }

  static boolean sensitiveKey(String key) {
    return SENSITIVE_KEY.matcher(key).matches() && !NOT_A_VALUE.matcher(key).matches();
  }

  /** {@code [{key|name, value}]}: every {@code value} that is text goes. */
  private static int maskItemValues(ArrayNode items) {
    int masked = 0;
    for (JsonNode item : items) {
      if (item instanceof ObjectNode o) {
        masked += maskField(o, "value");
      }
    }
    return masked;
  }

  private static int maskAll(ObjectNode o) {
    int masked = 0;
    for (String name : new ArrayList<>(o.propertyNames())) {
      masked += maskField(o, name);
    }
    return masked;
  }

  private static int maskField(ObjectNode o, String name) {
    JsonNode v = o.get(name);
    if (v == null || v.isNull() || v.isContainer() || (v.isString() && (v.asString().isEmpty()
        || v.asString().equals(MASK)))) {
      return 0;
    }
    o.put(name, MASK);
    return 1;
  }
}
