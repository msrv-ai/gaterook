package ai.msrv.gaterook.connectors.kubernetes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * What of a Kubernetes object the agent and the approver see (report 08 §3.1, §3.4, §6).
 *
 * <p>For the agent: no {@code managedFields} and no {@code kubectl.kubernetes.io/last-applied-configuration} — on a
 * Secret applied with kubectl that annotation holds the plain {@code stringData} — and the values of a Secret's
 * {@code data} and {@code stringData} replaced by {@code ‹masked›} (keys stay, lengths don't show). The core doesn't
 * know that {@code data.*} of a Secret is a secret; this does.
 *
 * <p>For a diff: what {@code kubectl diff --server-side} prints (the server's own fields — resourceVersion,
 * generation, uid, creationTimestamp, status — included, so the hunks line up with kubectl's), without
 * {@code managedFields} (kubectl hides them too) and without the {@code last-applied-configuration} (kubectl would
 * show it; here it may carry a Secret's plain text, so it is never shown).
 *
 * <p>For the hash that holds an approved apply to its dry run: the diff's view without what the server changes on
 * its own (the version, the generation, timestamps, uid, status), so a controller writing the status between approval
 * and execution doesn't count as a change of the approved object.
 */
final class ObjectNormalizer {

  static final String LAST_APPLIED = "kubectl.kubernetes.io/last-applied-configuration";
  static final String MASK = "‹masked›";

  private ObjectNormalizer() {}

  /** A copy for the agent. */
  static ObjectNode forAgent(JsonNode object) {
    ObjectNode o = ((ObjectNode) object).deepCopy();
    stripMetadata(o);
    if (isSecret(o)) {
      maskValues(o, "data");
      maskValues(o, "stringData");
    }
    return o;
  }

  /** A copy as the diff shows it: the agent's view, as {@code kubectl diff} lays the object out. */
  static ObjectNode forDiff(JsonNode object) {
    return forAgent(object);
  }

  /** A copy for the hash of an approved object: no field the server sets by itself. */
  static ObjectNode forHash(JsonNode object) {
    ObjectNode o = forAgent(object);
    if (o.get("metadata") instanceof ObjectNode m) {
      m.remove(List.of("resourceVersion", "generation", "uid", "creationTimestamp", "selfLink"));
      if (m.isEmpty()) {
        o.remove("metadata");
      }
    }
    o.remove("status");
    return o;
  }

  static boolean isSecret(JsonNode o) {
    return "Secret".equals(o.path("kind").asString()) && "v1".equals(o.path("apiVersion").asString());
  }

  private static void stripMetadata(ObjectNode o) {
    if (!(o.get("metadata") instanceof ObjectNode m)) {
      return;
    }
    m.remove("managedFields");
    if (m.get("annotations") instanceof ObjectNode a) {
      a.remove(LAST_APPLIED);
      if (a.isEmpty()) {
        m.remove("annotations");
      }
    }
  }

  private static void maskValues(ObjectNode o, String field) {
    if (o.get(field) instanceof ObjectNode values) {
      List<String> keys = new ArrayList<>();
      for (Map.Entry<String, JsonNode> e : values.properties()) {
        keys.add(e.getKey());
      }
      for (String k : keys) {
        values.put(k, MASK);
      }
    }
  }
}
