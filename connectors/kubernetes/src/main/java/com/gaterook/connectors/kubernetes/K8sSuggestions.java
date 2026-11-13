package com.gaterook.connectors.kubernetes;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.ExecContext;
import com.gaterook.connector.api.SecretResolver;
import com.gaterook.connector.api.Suggestions;
import com.gaterook.connector.api.Suggestions.Option;
import com.gaterook.connectors.kubernetes.K8sApi.StatusException;
import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Help for the Kubernetes form ({@link com.gaterook.connector.api.Connector#suggest}). On this computer: the
 * kubeconfig files of {@code ~/.kube} and {@code KUBECONFIG}, the contexts of the chosen file or of a pasted kubeconfig
 * (the file's current context filled in), the namespace a context sets, and for a token the API servers the local
 * kubeconfig knows. Signed in: the cluster's namespaces. A credential command is never run for a suggestion: until
 * Test connection has asked the person to allow it, the namespaces wait.
 */
final class K8sSuggestions {

  static final String KUBECONFIG_SOURCE = "your kubeconfig";
  private static final int MAX_FILES = 20;

  private K8sSuggestions() {}

  static Suggestions suggest(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx,
      Suggestions.Scope scope, K8sClients clients) {
    return suggest(cfg, secrets, ctx, scope, clients, defaultFile());
  }

  /** {@code homeConfig}: the kubeconfig this computer uses by default ({@code ~/.kube/config}). */
  static Suggestions suggest(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx,
      Suggestions.Scope scope, K8sClients clients, Path homeConfig) {
    Suggestions.Builder out = Suggestions.builder();
    String auth = text(cfg, "/auth");
    String chosen = text(cfg, "/context");
    Kubeconfig kc = null;
    String source = KUBECONFIG_SOURCE;
    switch (auth == null ? "token" : auth) {
      case "kubeconfig-file" -> {
        String typed = text(cfg, "/kubeconfigPath");
        Path path = expand(typed == null ? "~/.kube/config" : typed);
        out.options("/kubeconfigPath", "this computer", files(path));
        try {
          kc = Kubeconfig.file(path);
          source = shown(path);
        } catch (ConnectorException e) {
          out.note(e.getMessage());
        }
      }
      case "kubeconfig" -> {
        if (secrets != null && secrets.isSet(K8sSettings.KUBECONFIG)) {
          try (SecretResolver.Secret secret = secrets.secret(K8sSettings.KUBECONFIG)) {
            kc = Kubeconfig.pasted(secret.unsafeString());
            source = "the pasted kubeconfig";
          } catch (ConnectorException e) {
            out.note(e.getMessage());
          }
        }
      }
      default -> {
        servers(homeConfig, out);
        signInWithFile(cfg, secrets, homeConfig, out);
      }
    }
    if (kc != null) {
      contexts(cfg, kc, "kubeconfig-file".equals(auth), chosen, source, out);
    }
    if (scope == Suggestions.Scope.SERVICE) {
      namespaces(cfg, secrets, ctx, clients, kc, chosen, out);
    }
    return out.build();
  }

  /** The contexts of a kubeconfig; a file's current context is filled in, as it is required for a file. */
  private static void contexts(ConnectionConfig cfg, Kubeconfig kc, boolean file, String chosen, String source,
      Suggestions.Builder out) {
    List<Kubeconfig.Context> contexts = kc.contexts();
    if (contexts.isEmpty()) {
      out.note("The kubeconfig has no contexts.");
      return;
    }
    String current = kc.currentContext();
    List<Option> options = new ArrayList<>();
    for (Kubeconfig.Context c : contexts) {
      String detail = c.server() == null ? null : hostOf(c.server());
      options.add(new Option(c.name(), c.name().equals(current) ? c.name() + " (current)" : null, detail));
    }
    // A pasted kubeconfig uses its current context when Context is empty; a file needs one named.
    out.value("/context", source, file ? current : null, options);
    String effective = chosen != null && contexts.stream().anyMatch(c -> c.name().equals(chosen)) ? chosen : current;
    if (effective == null) {
      out.note("The kubeconfig has no current context: choose one of its " + contexts.size() + " contexts.");
      return;
    }
    String ns = kc.namespaceOf(effective);
    if (ns != null) {
      out.value("/namespace", "context " + effective, ns, List.of(Option.of(ns)));
    }
    String command = kc.execCommandOf(effective);
    if (command != null && !cfg.bool("/allowExecPlugin", false)) {
      out.note("Context " + effective + " signs in with " + command + ": Test connection shows the command for you "
          + "to allow.");
    }
  }

  /** For a token: the API servers the kubeconfig of this computer knows, with the contexts that use them. */
  private static void servers(Path path, Suggestions.Builder out) {
    if (!Files.isRegularFile(path)) {
      return;
    }
    try {
      Map<String, List<String>> servers = Kubeconfig.file(path).servers();
      List<Option> options = new ArrayList<>();
      servers.forEach((server, contexts) -> options.add(Option.of(server, String.join(", ", contexts))));
      out.options("/server", KUBECONFIG_SOURCE, options);
    } catch (ConnectorException e) {
      // No suggestion from a kubeconfig that doesn't parse: the person types the server.
    }
  }

  /**
   * A draft with nothing of a token yet (no API server, no token typed) signs in with the kubeconfig of this computer
   * when it has contexts: Sign in with takes the kubeconfig file, as the form fills in a field still at its default
   * and keeps a mode the person chose, and the next suggestion fills in the file's current context.
   */
  private static void signInWithFile(ConnectionConfig cfg, SecretResolver secrets, Path path,
      Suggestions.Builder out) {
    if (text(cfg, "/server") != null || (secrets != null && secrets.isSet(K8sSettings.TOKEN))
        || !Files.isRegularFile(path)) {
      return;
    }
    try {
      if (!Kubeconfig.file(path).contexts().isEmpty()) {
        out.value("/auth", KUBECONFIG_SOURCE, "kubeconfig-file", List.of());
      }
    } catch (ConnectorException e) {
      // A kubeconfig that doesn't parse: the person chooses how to sign in.
    }
  }

  /**
   * The namespaces the credentials may list. The cluster is the one the person picked; nothing is pinned or trusted
   * by a suggestion, and a credential command runs only once Test connection had the person allow it.
   */
  private static void namespaces(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, K8sClients clients,
      Kubeconfig kc, String chosen, Suggestions.Builder out) {
    K8sSettings s;
    ClusterAccess access;
    try {
      s = K8sSettings.of(cfg);
      access = ClusterAccess.resolve(s, secrets, cfg.env(), ctx::taint);
    } catch (ConnectorException e) {
      out.note(e.getMessage());
      return;
    }
    if (access.exec() != null && (!s.allowExecPlugin()
        || !ExecCredentials.fingerprint(access.exec()).equals(s.trustedExec()))) {
      out.note("Namespaces appear after Test connection: it asks you to allow the credential command first.");
      return;
    }
    K8sClients.Client client;
    try {
      client = clients.client(cfg.id(), cfg.version(), access, s.requestTimeoutMs());
    } catch (ConnectorException e) {
      out.note(e.getMessage());
      return;
    }
    try {
      JsonNode list = client.api().get("api/v1/namespaces", K8sApi.query().put("limit", 500), K8sApi.ACCEPT_JSON,
          ctx);
      List<Option> options = new ArrayList<>();
      for (JsonNode item : list.path("items")) {
        String name = item.path("metadata").path("name").asString(null);
        String phase = item.path("status").path("phase").asString(null);
        if (name != null && !"Terminating".equals(phase)) {
          options.add(Option.of(name));
        }
      }
      String preferred = kc == null ? null : kc.namespaceOf(chosen != null ? chosen : kc.currentContext());
      boolean complete = list.path("metadata").path("continue").asString("").isEmpty();
      out.field("/namespace", new Suggestions.Field(options, preferred, "the cluster", complete));
      out.note("Signed in to " + access.target() + ": " + options.size() + (complete ? "" : "+") + " namespace"
          + (options.size() == 1 ? "" : "s") + ". Choose the default namespace.");
    } catch (StatusException e) {
      out.note(e.code == 403 ? "The account can't list namespaces: type one it may use." : e.code == 401
          ? "The API server rejected the credentials (401)." : "The API server answered " + e.code + ": "
              + K8sText.cut(e.getMessage(), 160));
    } catch (ConnectorException e) {
      out.note(e.getMessage());
    } finally {
      if (client.exec() != null && client.exec().current() != null) {
        ctx.taint(client.exec().current());
      }
      if (!K8sClients.cached(cfg.id())) {
        client.close();
      }
    }
  }

  /** The kubeconfig files of this computer: {@code KUBECONFIG}'s, {@code ~/.kube/config} and its neighbours. */
  static List<Option> files(Path typed) {
    Set<Path> found = new LinkedHashSet<>();
    String env = System.getenv("KUBECONFIG");
    if (env != null) {
      for (String part : env.split(File.pathSeparator)) {
        if (!part.isBlank()) {
          found.add(expand(part));
        }
      }
    }
    Path home = defaultFile();
    found.add(home);
    try (DirectoryStream<Path> dir = Files.newDirectoryStream(home.getParent())) {
      for (Path p : dir) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".kubeconfig")
            || name.startsWith("config")) {
          found.add(p.toAbsolutePath().normalize());
        }
      }
    } catch (IOException | RuntimeException e) {
      // No ~/.kube folder: only what KUBECONFIG names.
    }
    if (typed != null) {
      // The file typed in the form is offered too, wherever it is, so that it shows as the one chosen.
      found.add(typed);
    }
    List<Option> out = new ArrayList<>();
    for (Path p : found) {
      if (out.size() >= MAX_FILES) {
        break;
      }
      try {
        if (Files.isRegularFile(p) && Files.size(p) <= Kubeconfig.MAX_BYTES) {
          out.add(new Option(shown(p), null, p.equals(typed) ? "chosen" : null));
        }
      } catch (IOException e) {
        // Unreadable: not offered.
      }
    }
    return out;
  }

  private static Path defaultFile() {
    return expand("~/.kube/config");
  }

  static Path expand(String path) {
    String p = path.strip();
    if (p.equals("~") || p.startsWith("~/") || p.startsWith("~\\")) {
      p = System.getProperty("user.home") + p.substring(1);
    }
    return Path.of(p).toAbsolutePath().normalize();
  }

  /** {@code ~/.kube/config} rather than the home folder's full path, as people type it. */
  static String shown(Path p) {
    Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
    return p.startsWith(home) ? "~/" + home.relativize(p).toString().replace('\\', '/') : p.toString();
  }

  private static String hostOf(String server) {
    try {
      return K8sSettings.hostPort(java.net.URI.create(server));
    } catch (IllegalArgumentException e) {
      return server;
    }
  }

  private static String text(ConnectionConfig cfg, String pointer) {
    String v = cfg.string(pointer);
    return v == null || v.isBlank() ? null : v.strip();
  }
}
