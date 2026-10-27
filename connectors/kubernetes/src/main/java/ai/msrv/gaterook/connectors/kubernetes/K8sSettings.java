package ai.msrv.gaterook.connectors.kubernetes;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The connection form of Kubernetes, typed (config.schema.json; report 08 §4). Secrets are only named here: the token
 * and the pasted kubeconfig come from the call's {@code SecretResolver}.
 *
 * @param pinnedServer the API server a kubeconfig file pointed to when the person trusted it; calls refuse a file
 *     that now points elsewhere
 * @param pinnedCaSha256 the SHA-256 of that cluster's CA data (empty: the OS trust store)
 * @param trustedExec the SHA-256 of the credential command the person allowed ({@link ExecCredentials#fingerprint})
 */
record K8sSettings(Auth auth, URI server, String caCert, boolean insecureSkipTlsVerify, Path kubeconfigPath,
    String context, boolean allowExecPlugin, String namespace, int requestTimeoutMs, URI proxy, String pinnedServer,
    String pinnedCaSha256, String trustedExec) {

  static final String TOKEN = "/token";
  static final String KUBECONFIG = "/kubeconfig";

  private static final Pattern NAMESPACE = Pattern.compile("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$");

  enum Auth {
    TOKEN("token"), KUBECONFIG("kubeconfig"), KUBECONFIG_FILE("kubeconfig-file");

    final String id;

    Auth(String id) {
      this.id = id;
    }

    static Auth of(String id) throws ConnectorException {
      for (Auth a : values()) {
        if (a.id.equals(id)) {
          return a;
        }
      }
      throw ConnectorException.badInput("Sign in with must be token, kubeconfig or kubeconfig-file.");
    }
  }

  static K8sSettings of(ConnectionConfig cfg) throws ConnectorException {
    Auth auth = Auth.of(cfg.string("/auth") == null ? "token" : cfg.string("/auth"));
    URI server = null;
    String serverText = cfg.string("/server");
    if (auth == Auth.TOKEN) {
      if (serverText == null || serverText.isBlank()) {
        throw ConnectorException.badInput("Enter the API server, like https://k8s.dev.internal:6443.");
      }
      server = apiServer(serverText);
    }
    String namespace = cfg.string("/namespace") == null ? "default" : cfg.string("/namespace");
    if (namespace.length() > 63 || !NAMESPACE.matcher(namespace).matches()) {
      throw ConnectorException.badInput("The default namespace must be a DNS label, like app-dev.");
    }
    Path file = null;
    if (auth == Auth.KUBECONFIG_FILE) {
      String p = cfg.string("/kubeconfigPath");
      file = expand(p == null || p.isBlank() ? "~/.kube/config" : p);
      String context = cfg.string("/context");
      if (context == null || context.isBlank()) {
        throw ConnectorException.badInput("Pick the context of the kubeconfig file to use.");
      }
    }
    URI proxy = null;
    String proxyText = cfg.string("/proxy");
    if (proxyText != null && !proxyText.isBlank()) {
      try {
        proxy = new URI(proxyText.strip());
      } catch (URISyntaxException e) {
        throw ConnectorException.badInput("The HTTPS proxy must be a URL like http://proxy.internal:3128.");
      }
      if (!"http".equalsIgnoreCase(proxy.getScheme()) || proxy.getHost() == null) {
        throw ConnectorException.badInput("The HTTPS proxy must be an http:// URL with a host, like "
            + "http://proxy.internal:3128: the connection to the API server is tunnelled through it.");
      }
    }
    String caCert = cfg.string("/caCert");
    return new K8sSettings(auth, server, caCert == null || caCert.isBlank() ? null : caCert,
        cfg.bool("/insecureSkipTlsVerify", false), file, blankToNull(cfg.string("/context")),
        cfg.bool("/allowExecPlugin", false), namespace,
        Math.clamp(cfg.integer("/requestTimeoutMs", 10_000), 1_000, 60_000), proxy,
        blankToNull(cfg.string("/pinnedCluster/serverUrl")), blankToNull(cfg.string("/pinnedCluster/caSha256")),
        blankToNull(cfg.string("/trustedExec")));
  }

  /** An API server URL: https, a host, no user info, query or fragment; a path prefix (Rancher) is kept. */
  static URI apiServer(String text) throws ConnectorException {
    URI uri;
    try {
      uri = new URI(text.strip());
    } catch (URISyntaxException e) {
      throw ConnectorException.badInput("The API server must be a URL like https://k8s.dev.internal:6443.");
    }
    if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
        || uri.getRawQuery() != null || uri.getRawFragment() != null) {
      throw ConnectorException.badInput("The API server must be an https:// URL with a host and nothing after the "
          + "path, like https://k8s.dev.internal:6443.");
    }
    return uri;
  }

  /** {@code host:port} of the API server, for targets and messages. */
  static String hostPort(URI server) {
    if (server == null) {
      return "";
    }
    int port = server.getPort();
    return server.getHost() + (port < 0 ? "" : ":" + port);
  }

  private static Path expand(String path) {
    String p = path.strip();
    if (p.equals("~") || p.startsWith("~/")) {
      p = System.getProperty("user.home") + p.substring(1);
    }
    return Path.of(p).toAbsolutePath().normalize();
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }

  String authText() {
    return auth.id.toLowerCase(Locale.ROOT);
  }
}
