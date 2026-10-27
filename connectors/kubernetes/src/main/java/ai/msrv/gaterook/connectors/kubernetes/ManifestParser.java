package ai.msrv.gaterook.connectors.kubernetes;

import ai.msrv.gaterook.connectors.kubernetes.YamlTrees.YamlException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The {@code manifest} of {@code k8s_apply}: YAML or JSON, one or more documents, each one Kubernetes object with
 * {@code apiVersion}, {@code kind} and {@code metadata.name} (report 08 §3.4). Parsing has no I/O, so
 * {@code classify} runs it; {@code prepare} and the approved execution read the same objects again.
 */
final class ManifestParser {

  /** At most this many objects per call, whatever the policy says (each one is a dry run before the request). */
  static final int MAX_OBJECTS = 20;

  /** The schema caps the manifest at 256 KiB; the YAML reader gets the same bound in code points. */
  static final int MAX_CODE_POINTS = 262_144;

  private static final Pattern NAME = Pattern.compile("^[a-z0-9]([-a-z0-9.]*[a-z0-9])?$");

  private ManifestParser() {}

  /** Why a manifest can't be applied, in words for the agent. */
  static final class ManifestException extends Exception {
    private static final long serialVersionUID = 1L;

    ManifestException(String message) {
      super(message);
    }
  }

  static List<ObjectNode> parse(String manifest) throws ManifestException {
    if (manifest == null || manifest.isBlank()) {
      throw new ManifestException("The manifest is empty.");
    }
    List<JsonNode> docs;
    try {
      docs = YamlTrees.documents(manifest, MAX_CODE_POINTS + 1024);
    } catch (YamlException e) {
      throw new ManifestException(e.getMessage());
    }
    if (docs.isEmpty()) {
      throw new ManifestException("The manifest has no object.");
    }
    if (docs.size() > MAX_OBJECTS) {
      throw new ManifestException("The manifest has " + docs.size() + " objects; at most " + MAX_OBJECTS
          + " are applied per call. Split it.");
    }
    List<ObjectNode> out = new ArrayList<>();
    int i = 0;
    for (JsonNode doc : docs) {
      i++;
      String where = docs.size() == 1 ? "The manifest" : "Document " + i;
      if (!doc.isObject()) {
        throw new ManifestException(where + " isn't a Kubernetes object (a mapping with apiVersion and kind).");
      }
      ObjectNode o = (ObjectNode) doc;
      String apiVersion = text(o.get("apiVersion"));
      String kind = text(o.get("kind"));
      if (apiVersion == null || kind == null) {
        throw new ManifestException(where + " needs apiVersion and kind.");
      }
      if (kind.endsWith("List")) {
        throw new ManifestException(where + " is a " + kind + ": write its items as separate documents.");
      }
      JsonNode metadata = o.get("metadata");
      if (metadata == null || !metadata.isObject()) {
        throw new ManifestException(where + " (" + kind + ") needs metadata.name.");
      }
      if (metadata.has("generateName")) {
        throw new ManifestException(where + " (" + kind + ") uses generateName: apply needs a fixed metadata.name.");
      }
      String name = text(metadata.get("name"));
      if (name == null || name.length() > 253 || !NAME.matcher(name).matches()) {
        throw new ManifestException(where + " (" + kind + ") needs a metadata.name of lowercase letters, digits, "
            + "'-' and '.'.");
      }
      JsonNode ns = metadata.get("namespace");
      if (ns != null && !ns.isNull() && (!ns.isString() || !K8sNames.namespace(ns.asString()))) {
        throw new ManifestException(where + " (" + kind + "/" + name + ") has an invalid metadata.namespace.");
      }
      if (metadata.has("resourceVersion") || metadata.has("uid")) {
        throw new ManifestException(where + " (" + kind + "/" + name + ") carries resourceVersion or uid: apply a "
            + "manifest, not an object read from the cluster.");
      }
      // The diff a person approves never shows this annotation (on a Secret applied with kubectl it holds the plain
      // values), so a manifest setting it would change the object where nobody looks — and the next client-side
      // kubectl apply merges against it.
      if (metadata.path("annotations").has(ObjectNormalizer.LAST_APPLIED)) {
        throw new ManifestException(where + " (" + kind + "/" + name + ") sets the annotation "
            + ObjectNormalizer.LAST_APPLIED + ", which kubectl keeps for itself. Leave it out.");
      }
      out.add(o);
    }
    for (int a = 0; a < out.size(); a++) {
      for (int b = a + 1; b < out.size(); b++) {
        if (sameObject(out.get(a), out.get(b))) {
          throw new ManifestException("Documents " + (a + 1) + " and " + (b + 1) + " are the same object.");
        }
      }
    }
    return out;
  }

  private static boolean sameObject(ObjectNode a, ObjectNode b) {
    return a.path("apiVersion").equals(b.path("apiVersion")) && a.path("kind").equals(b.path("kind"))
        && a.path("metadata").path("name").equals(b.path("metadata").path("name"))
        && a.path("metadata").path("namespace").equals(b.path("metadata").path("namespace"));
  }

  private static String text(JsonNode n) {
    return n == null || !n.isString() || n.asString().isBlank() ? null : n.asString();
  }
}
