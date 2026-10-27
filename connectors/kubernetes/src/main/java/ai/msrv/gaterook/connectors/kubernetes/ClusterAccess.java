package ai.msrv.gaterook.connectors.kubernetes;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connectors.kubernetes.K8sSettings.Auth;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Where one connection's calls go and how they sign in, resolved from the form and the call's secrets: the server URL
 * and token of the form, or one context of a pasted kubeconfig or of the kubeconfig file on this computer.
 *
 * <p>Nothing comes from the environment of the process ({@code ~/.kube/config}, {@code KUBERNETES_*},
 * {@code HTTPS_PROXY}, the service account of a pod): the form decides. Every credential found here is handed to
 * {@code taint} at once, so the core masks its echoes (a kubeconfig's token is a part of the secret, not the secret).
 *
 * @param caPem the cluster's CA certificates; {@code null}: the platform's trust store
 * @param exec the credential command, when the kubeconfig signs in with one
 * @param contexts every context of the kubeconfig, for the form's list (empty with a token)
 * @param fingerprint a SHA-256 over everything that makes a client: the key of the client cache
 */
record ClusterAccess(URI server, String caPem, boolean insecure, String token, String clientCertPem,
    String clientKeyPem, ExecCredentials.Spec exec, URI proxy, String contextName, String tlsServerName,
    List<Kubeconfig.Context> contexts, String fingerprint) {

  /** The SHA-256 of the CA text a kubeconfig file pointed to when it was trusted ("" without one). */
  String caSha256() {
    return caPem == null ? "" : sha256(caPem.strip());
  }

  static ClusterAccess resolve(K8sSettings s, SecretResolver secrets, Env env, Consumer<CharSequence> taint)
      throws ConnectorException {
    ClusterAccess access = switch (s.auth()) {
      case TOKEN -> token(s, secrets, taint);
      case KUBECONFIG -> {
        if (!secrets.isSet(K8sSettings.KUBECONFIG)) {
          throw ConnectorException.badInput("Paste the kubeconfig: it is stored as a secret of the connection.");
        }
        String text;
        try (SecretResolver.Secret secret = secrets.secret(K8sSettings.KUBECONFIG)) {
          text = secret.unsafeString();
        }
        Kubeconfig kc = Kubeconfig.pasted(text);
        yield fromKubeconfig(s, kc, kc.resolve(s.context()), taint);
      }
      case KUBECONFIG_FILE -> {
        Kubeconfig kc = Kubeconfig.file(s.kubeconfigPath());
        yield fromKubeconfig(s, kc, kc.resolve(s.context()), taint);
      }
    };
    if (access.insecure() && env == Env.PROD) {
      throw ConnectorException.denied("Skipping TLS verification isn't allowed on prod: give the cluster's CA "
          + "instead.");
    }
    return access;
  }

  private static ClusterAccess token(K8sSettings s, SecretResolver secrets, Consumer<CharSequence> taint)
      throws ConnectorException {
    if (!secrets.isSet(K8sSettings.TOKEN)) {
      throw ConnectorException.badInput("Enter the bearer token of the service account: it is stored as a secret of "
          + "the connection.");
    }
    String token;
    try (SecretResolver.Secret secret = secrets.secret(K8sSettings.TOKEN)) {
      token = secret.unsafeString().strip();
    }
    return build(s.server(), s.caCert(), s.insecureSkipTlsVerify(), token, null, null, null, s.proxy(), null, null,
        List.of(), taint);
  }

  private static ClusterAccess fromKubeconfig(K8sSettings s, Kubeconfig kc, Kubeconfig.Resolved r,
      Consumer<CharSequence> taint) throws ConnectorException {
    ExecCredentials.Spec exec = null;
    if (r.exec() != null) {
      ObjectNode cluster = JsonNodeFactory.instance.objectNode();
      cluster.put("server", r.server().toString());
      if (r.caPem() != null) {
        cluster.put("certificate-authority-data", java.util.Base64.getEncoder()
            .encodeToString(r.caPem().getBytes(StandardCharsets.UTF_8)));
      }
      cluster.put("insecure-skip-tls-verify", r.insecure());
      exec = ExecCredentials.spec(r.exec(), r.dir(), cluster);
    }
    URI proxy = s.proxy() != null ? s.proxy() : r.proxy();
    String ca = s.caCert() != null ? s.caCert() : r.caPem();
    boolean insecure = r.insecure() || s.insecureSkipTlsVerify();
    return build(r.server(), ca, insecure, exec == null ? r.token() : null, r.clientCertPem(), r.clientKeyPem(), exec,
        proxy, r.context(), r.tlsServerName(), kc.contexts(), taint);
  }

  private static ClusterAccess build(URI server, String ca, boolean insecure, String token, String cert, String key,
      ExecCredentials.Spec exec, URI proxy, String context, String tlsServerName, List<Kubeconfig.Context> contexts,
      Consumer<CharSequence> taint) {
    if (token != null) {
      taint.accept(token);
    }
    if (key != null) {
      taint.accept(key.strip());
      // The kubeconfig carries it base64-encoded: the encoded form is an echo of the same secret.
      taint.accept(java.util.Base64.getEncoder().encodeToString(key.getBytes(StandardCharsets.UTF_8)));
    }
    List<String> parts = new ArrayList<>();
    parts.add(server.toString());
    parts.add(ca == null ? "" : ca);
    parts.add(Boolean.toString(insecure));
    parts.add(token == null ? "" : token);
    parts.add(cert == null ? "" : cert);
    parts.add(key == null ? "" : key);
    parts.add(exec == null ? "" : ExecCredentials.fingerprint(exec));
    parts.add(proxy == null ? "" : proxy.toString());
    return new ClusterAccess(server, ca, insecure, token, cert, key, exec, proxy, context, tlsServerName,
        List.copyOf(contexts), sha256(String.join("\u0000", parts)));
  }

  /** What a person compares: {@code k8s.dev.internal:6443}. */
  String target() {
    return K8sSettings.hostPort(server);
  }

  static String sha256(String text) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Whether this context of a kubeconfig file is the cluster the person trusted. */
  boolean matchesPin(K8sSettings s) {
    return server.toString().equals(s.pinnedServer()) && caSha256().equals(s.pinnedCaSha256() == null ? ""
        : s.pinnedCaSha256());
  }

  boolean fileMode(K8sSettings s) {
    return s.auth() == Auth.KUBECONFIG_FILE;
  }
}
