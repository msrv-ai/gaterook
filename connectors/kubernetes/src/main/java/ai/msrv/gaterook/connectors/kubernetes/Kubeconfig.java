package ai.msrv.gaterook.connectors.kubernetes;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connectors.kubernetes.YamlTrees.YamlException;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * A kubeconfig read by our own mapper (report 08 §4), not Fabric8's {@code KubeConfigUtils}: that one sits in an
 * internal package, merges files and runs exec plugins while it does. Only what one context names is used, and every
 * way of signing in that GateRook doesn't run itself is refused with a reason: auth providers (OIDC refresh),
 * basic auth, impersonation.
 *
 * <p>A pasted kubeconfig is a secret and has no folder, so it must carry its certificates and keys inline
 * ({@code *-data}); a kubeconfig file may name files next to it, as kubectl allows.
 */
final class Kubeconfig {

  static final int MAX_BYTES = 1 << 20;

  /** One context of the file, for the form's "Context" list: names and servers are not secret. */
  record Context(String name, String server) {}

  /** What one context says, resolved; secrets are plain strings here and tainted by the caller. */
  record Resolved(String context, URI server, String caPem, boolean insecure, String tlsServerName, URI proxy,
      String namespace, String token, String clientCertPem, String clientKeyPem, ObjectNode exec, Path dir) {}

  private final Map<String, JsonNode> clusters;
  private final Map<String, JsonNode> users;
  private final Map<String, JsonNode> contexts;
  private final String currentContext;
  private final Path dir;

  private Kubeconfig(Map<String, JsonNode> clusters, Map<String, JsonNode> users, Map<String, JsonNode> contexts,
      String currentContext, Path dir) {
    this.clusters = clusters;
    this.users = users;
    this.contexts = contexts;
    this.currentContext = currentContext;
    this.dir = dir;
  }

  /** A pasted kubeconfig (the secret {@code /kubeconfig}). */
  static Kubeconfig pasted(String text) throws ConnectorException {
    return parse(text, null);
  }

  /** The kubeconfig file on this computer, read now (the gateway doesn't copy it). */
  static Kubeconfig file(Path path) throws ConnectorException {
    try {
      if (!Files.isRegularFile(path)) {
        throw ConnectorException.unavailable("The kubeconfig file " + path + " doesn't exist on this computer.", null);
      }
      if (Files.size(path) > MAX_BYTES) {
        throw ConnectorException.badInput("The kubeconfig file " + path + " is larger than 1 MB.");
      }
      return parse(Files.readString(path, StandardCharsets.UTF_8), path.getParent());
    } catch (IOException e) {
      throw ConnectorException.unavailable("The kubeconfig file " + path + " can't be read: " + e.getMessage(), e);
    }
  }

  private static Kubeconfig parse(String text, Path dir) throws ConnectorException {
    List<JsonNode> docs;
    try {
      docs = YamlTrees.documents(text, MAX_BYTES);
    } catch (YamlException e) {
      throw ConnectorException.badInput("The kubeconfig isn't valid YAML. " + e.getMessage());
    }
    if (docs.size() != 1 || !docs.get(0).isObject()) {
      throw ConnectorException.badInput("The kubeconfig must be one YAML document of kind Config.");
    }
    JsonNode root = docs.get(0);
    return new Kubeconfig(named(root, "clusters", "cluster"), named(root, "users", "user"),
        named(root, "contexts", "context"), root.path("current-context").asString(null), dir);
  }

  private static Map<String, JsonNode> named(JsonNode root, String list, String item) throws ConnectorException {
    Map<String, JsonNode> out = new LinkedHashMap<>();
    for (JsonNode n : root.path(list)) {
      String name = n.path("name").asString(null);
      if (name == null) {
        throw ConnectorException.badInput("An entry of " + list + " in the kubeconfig has no name.");
      }
      out.put(name, n.path(item));
    }
    return out;
  }

  /** Every context with the server of its cluster, in file order. */
  List<Context> contexts() {
    List<Context> out = new ArrayList<>();
    for (Map.Entry<String, JsonNode> c : contexts.entrySet()) {
      JsonNode cluster = clusters.get(c.getValue().path("cluster").asString(""));
      out.add(new Context(c.getKey(), cluster == null ? null : cluster.path("server").asString(null)));
    }
    return out;
  }

  static ArrayNode contextsTree(List<Context> contexts) {
    ArrayNode out = JsonNodeFactory.instance.arrayNode();
    for (Context c : contexts) {
      ObjectNode n = out.addObject().put("name", c.name());
      if (c.server() != null) {
        n.put("server", c.server());
      }
    }
    return out;
  }

  /** The context {@code name}, or the file's current context when {@code name} is null. */
  Resolved resolve(String name) throws ConnectorException {
    String ctxName = name != null ? name : currentContext;
    if (ctxName == null) {
      throw ConnectorException.badInput("The kubeconfig has no current-context: pick a context. Contexts: "
          + String.join(", ", contexts.keySet()) + ".");
    }
    JsonNode ctx = contexts.get(ctxName);
    if (ctx == null) {
      throw ConnectorException.badInput("The kubeconfig has no context " + ctxName + ". Contexts: "
          + String.join(", ", contexts.keySet()) + ".");
    }
    String clusterName = ctx.path("cluster").asString("");
    JsonNode cluster = clusters.get(clusterName);
    if (cluster == null) {
      throw ConnectorException.badInput("Context " + ctxName + " names the cluster " + clusterName
          + ", which the kubeconfig doesn't define.");
    }
    String userName = ctx.path("user").asString("");
    JsonNode user = userName.isEmpty() ? JsonNodeFactory.instance.objectNode() : users.get(userName);
    if (user == null) {
      throw ConnectorException.badInput("Context " + ctxName + " names the user " + userName
          + ", which the kubeconfig doesn't define.");
    }
    URI server = K8sSettings.apiServer(cluster.path("server").asString(""));
    String ca = data(cluster, "certificate-authority-data", "certificate-authority", "the CA of " + clusterName);
    URI proxy = null;
    String proxyUrl = cluster.path("proxy-url").asString(null);
    if (proxyUrl != null && !proxyUrl.isBlank()) {
      try {
        proxy = URI.create(proxyUrl.strip());
      } catch (IllegalArgumentException e) {
        proxy = null;
      }
      if (proxy == null || !"http".equalsIgnoreCase(proxy.getScheme()) || proxy.getHost() == null) {
        throw ConnectorException.badInput("The kubeconfig's proxy-url must be an http:// proxy with a host.");
      }
    }
    for (String refused : new String[] {"username", "password"}) {
      if (user.has(refused)) {
        throw ConnectorException.badInput("User " + userName + " signs in with a user name and password, which "
            + "Kubernetes no longer accepts. Use a token or a credential command.");
      }
    }
    if (user.has("auth-provider")) {
      throw ConnectorException.badInput("User " + userName + " signs in through the auth provider "
          + user.path("auth-provider").path("name").asString("?") + ", which GateRook doesn't run. Use a token "
          + "or a credential command (exec).");
    }
    if (user.has("as") || user.has("as-groups") || user.has("as-uid") || user.has("as-user-extra")) {
      throw ConnectorException.badInput("User " + userName + " impersonates another user; GateRook connects as "
          + "the identity itself. Use a kubeconfig without as/as-groups.");
    }
    String token = user.path("token").asString(null);
    if (token == null && user.has("tokenFile")) {
      token = file(user.path("tokenFile").asString(), "the token file of " + userName).strip();
    }
    String cert = data(user, "client-certificate-data", "client-certificate", "the client certificate of "
        + userName);
    String key = data(user, "client-key-data", "client-key", "the client key of " + userName);
    if ((cert == null) != (key == null)) {
      throw ConnectorException.badInput("User " + userName + " has a client certificate without its key, or a key "
          + "without its certificate.");
    }
    ObjectNode exec = user.get("exec") instanceof ObjectNode e ? e : null;
    String ns = ctx.path("namespace").asString(null);
    return new Resolved(ctxName, server, ca, cluster.path("insecure-skip-tls-verify").asBoolean(false),
        cluster.path("tls-server-name").asString(null), proxy, ns, token == null || token.isBlank() ? null : token,
        cert, key, exec, dir);
  }

  /** {@code field-data} (base64 of PEM) or, in a file only, {@code field} (a path next to the kubeconfig). */
  private String data(JsonNode node, String dataField, String pathField, String what) throws ConnectorException {
    String b64 = node.path(dataField).asString(null);
    if (b64 != null && !b64.isBlank()) {
      try {
        return new String(Base64.getMimeDecoder().decode(b64.strip()), StandardCharsets.UTF_8);
      } catch (IllegalArgumentException e) {
        throw ConnectorException.badInput(what + " (" + dataField + ") isn't valid base64.");
      }
    }
    String path = node.path(pathField).asString(null);
    if (path == null || path.isBlank()) {
      return null;
    }
    return file(path, what);
  }

  private String file(String path, String what) throws ConnectorException {
    if (dir == null) {
      throw ConnectorException.badInput("The pasted kubeconfig refers to a file for " + what + ". Paste the -data "
          + "form instead: kubectl config view --raw --minify --flatten.");
    }
    Path p = dir.resolve(path).normalize();
    try {
      if (Files.size(p) > MAX_BYTES) {
        throw ConnectorException.badInput(what + " (" + p + ") is larger than 1 MB.");
      }
      return Files.readString(p, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw ConnectorException.unavailable("The kubeconfig names " + p + " for " + what + ", which can't be read.",
          e);
    }
  }
}
