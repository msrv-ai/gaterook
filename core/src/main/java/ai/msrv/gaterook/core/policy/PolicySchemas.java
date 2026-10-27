package ai.msrv.gaterook.core.policy;

import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.connectors.ConnectorRegistry;
import ai.msrv.gaterook.core.connectors.ConnectorType;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The sections of an environment policy and their JSON Schemas: one per {@code x-policy-section} of the connector
 * manifests (SQL, SSH, Kubernetes, Cloud, HTTP egress, Logs) plus the core's own Masking, Limits and Approvals. Two
 * connectors may share a section (PostgreSQL and MySQL: {@code sql}; GCP and AWS: {@code cloud}); the first one
 * registered defines it.
 *
 * <p>Per key: {@code default}, {@code x-env-default} (the environment's own default), {@code const} (a lock),
 * {@code x-stricter} (how overrides may narrow it), {@code x-inherit: false} (not inherited from the parent
 * environment), {@code x-confirm: danger} and {@code x-forbidden-env-values}.
 */
public final class PolicySchemas {

  /** The core's sections, in the order the kit shows them after the connectors'. */
  public static final String[] CORE_SECTIONS = {"masking", "limits", "approvals"};

  private static final Logger log = LoggerFactory.getLogger(PolicySchemas.class);

  private final Map<String, ObjectNode> sections;
  /** Connector type → its section, or none. */
  private final Map<String, String> sectionOfType;

  private PolicySchemas(Map<String, ObjectNode> sections, Map<String, String> sectionOfType) {
    this.sections = sections;
    this.sectionOfType = sectionOfType;
  }

  /** Reads the core's sections from the classpath and the connector sections from {@code registry}. */
  public static PolicySchemas load(ConnectorRegistry registry, JsonMapper json) {
    return forManifests(registry.types().stream().map(ConnectorType::manifest).toList(), json);
  }

  /** The sections of {@code manifests} plus the core's. */
  public static PolicySchemas forManifests(java.util.List<ConnectorManifest> manifests, JsonMapper json) {
    Map<String, ObjectNode> sections = new LinkedHashMap<>();
    Map<String, String> byType = new LinkedHashMap<>();
    for (ConnectorManifest m : manifests) {
      JsonNode schema = m.policySchema();
      if (schema == null || !schema.isObject()) {
        continue;
      }
      String section = schema.path("x-policy-section").asString(m.id());
      byType.put(m.id(), section);
      ObjectNode earlier = sections.putIfAbsent(section, (ObjectNode) schema.deepCopy());
      if (earlier != null && !earlier.path("properties").equals(schema.path("properties"))) {
        log.warn("Connector {} declares policy section {} differently from an earlier connector; the first wins",
            m.id(), section);
      }
    }
    for (String core : CORE_SECTIONS) {
      sections.put(core, read(json, core));
    }
    return new PolicySchemas(Map.copyOf(sections), Map.copyOf(byType));
  }

  private static ObjectNode read(JsonMapper json, String section) {
    String path = "/ai/msrv/gaterook/core/policy/" + section + ".schema.json";
    try (InputStream in = PolicySchemas.class.getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalStateException("Missing policy schema " + path);
      }
      return (ObjectNode) json.readTree(in);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Section names: the connectors' first, then the core's. */
  public Iterable<String> names() {
    java.util.List<String> names = new java.util.ArrayList<>();
    sections.keySet().stream().filter(s -> !isCore(s)).sorted().forEach(names::add);
    for (String core : CORE_SECTIONS) {
      names.add(core);
    }
    return names;
  }

  public boolean has(String section) {
    return sections.containsKey(section);
  }

  public Optional<ObjectNode> schema(String section) {
    return Optional.ofNullable(sections.get(section));
  }

  /** The section of a connector type, if its manifest declares a policy. */
  public Optional<String> sectionOf(String connectorType) {
    return Optional.ofNullable(sectionOfType.get(connectorType));
  }

  public static boolean isCore(String section) {
    for (String core : CORE_SECTIONS) {
      if (core.equals(section)) {
        return true;
      }
    }
    return false;
  }

  /** The schema of one key, or a missing node. */
  public JsonNode key(String section, String key) {
    ObjectNode schema = sections.get(section);
    return schema == null ? JsonNodeFactory.instance.missingNode() : schema.path("properties").path(key);
  }

  /** The defaults of a section on {@code env}: {@code x-env-default}, else {@code default}, else {@code const}. */
  public ObjectNode defaults(String section, Env env) {
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    ObjectNode schema = sections.get(section);
    if (schema == null) {
      return out;
    }
    for (Map.Entry<String, JsonNode> p : schema.path("properties").properties()) {
      JsonNode envDefault = p.getValue().path("x-env-default").path(env.id());
      if (!envDefault.isMissingNode()) {
        out.set(p.getKey(), envDefault.deepCopy());
      } else if (p.getValue().has("default")) {
        out.set(p.getKey(), p.getValue().get("default").deepCopy());
      } else if (p.getValue().has("const")) {
        out.set(p.getKey(), p.getValue().get("const").deepCopy());
      }
    }
    return out;
  }

  /** Whether a child environment inherits {@code key} from its parent. */
  public boolean inheritable(String section, String key) {
    return key(section, key).path("x-inherit").asBoolean(true);
  }

  /** {@code x-stricter} of a key, or {@code null} when overrides can't narrow it. */
  public String stricter(String section, String key) {
    JsonNode op = key(section, key).get("x-stricter");
    return op == null || !op.isString() ? null : op.asString();
  }
}
