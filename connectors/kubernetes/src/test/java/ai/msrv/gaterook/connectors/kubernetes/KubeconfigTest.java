package ai.msrv.gaterook.connectors.kubernetes;

import static ai.msrv.gaterook.connectors.kubernetes.K8sTestKit.ctx;
import static ai.msrv.gaterook.connectors.kubernetes.K8sTestKit.secrets;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Kubeconfigs are read by our own mapper: one context, every credential tainted, the ways of signing in MCP Gateway
 * doesn't run refused with a reason (report 08 §4, §6).
 */
class KubeconfigTest {

  private static final String CA = "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n";

  @TempDir
  Path dir;

  private static String b64(String s) {
    return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
  }

  private static String kubeconfig(String user) {
    return """
        apiVersion: v1
        kind: Config
        current-context: dev
        clusters:
        - name: dev-cluster
          cluster:
            server: https://k8s.dev.internal:6443
            certificate-authority-data: %s
        - name: prod-cluster
          cluster:
            server: https://k8s.prod.internal:6443
        contexts:
        - name: dev
          context: {cluster: dev-cluster, user: dev-user, namespace: app-dev}
        - name: prod
          context: {cluster: prod-cluster, user: dev-user}
        users:
        - name: dev-user
          user:
        %s
        """.formatted(b64(CA), user.indent(4).stripTrailing());
  }

  @Test
  void aPastedKubeconfigResolvesOneContext() throws Exception {
    Kubeconfig kc = Kubeconfig.pasted(kubeconfig("token: cnry-kubeconfig-token"));

    Kubeconfig.Resolved dev = kc.resolve(null);
    Kubeconfig.Resolved prod = kc.resolve("prod");

    assertThat(kc.contexts()).containsExactly(new Kubeconfig.Context("dev", "https://k8s.dev.internal:6443"),
        new Kubeconfig.Context("prod", "https://k8s.prod.internal:6443"));
    assertThat(dev.context()).isEqualTo("dev");
    assertThat(dev.server().toString()).isEqualTo("https://k8s.dev.internal:6443");
    assertThat(dev.caPem()).isEqualTo(CA);
    assertThat(dev.token()).isEqualTo("cnry-kubeconfig-token");
    assertThat(prod.caPem()).isNull();
    assertThatThrownBy(() -> kc.resolve("stage")).hasMessageContaining("no context stage")
        .hasMessageContaining("dev, prod");
  }

  @Test
  void waysOfSigningInThatTheGatewayDoesNotRunAreRefused() {
    assertThatThrownBy(() -> Kubeconfig.pasted(kubeconfig("auth-provider: {name: oidc}")).resolve(null))
        .hasMessageContaining("auth provider oidc");
    assertThatThrownBy(() -> Kubeconfig.pasted(kubeconfig("username: a\npassword: cnry-b")).resolve(null))
        .hasMessageContaining("user name and password").hasMessageNotContaining("cnry-");
    assertThatThrownBy(() -> Kubeconfig.pasted(kubeconfig("token: t\nas: admin")).resolve(null))
        .hasMessageContaining("impersonates");
    assertThatThrownBy(() -> Kubeconfig.pasted(kubeconfig("client-certificate-data: " + b64("x"))).resolve(null))
        .hasMessageContaining("without its key");
  }

  @Test
  void aPastedKubeconfigCantNameFilesOnThisComputer() {
    assertThatThrownBy(() -> Kubeconfig.pasted(kubeconfig("tokenFile: /etc/passwd")).resolve(null))
        .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.getMessage())
            .contains("Paste the -data form").doesNotContain("root:"));
  }

  @Test
  void aKubeconfigFileMayNameFilesNextToIt() throws Exception {
    Files.writeString(dir.resolve("ca.crt"), CA);
    Files.writeString(dir.resolve("token"), "cnry-file-token\n");
    String text = kubeconfig("tokenFile: token").replace("certificate-authority-data: " + b64(CA),
        "certificate-authority: ca.crt");
    Path file = dir.resolve("config");
    Files.writeString(file, text);

    Kubeconfig.Resolved r = Kubeconfig.file(file).resolve("dev");

    assertThat(r.caPem()).isEqualTo(CA);
    assertThat(r.token()).isEqualTo("cnry-file-token");
    assertThatThrownBy(() -> Kubeconfig.file(dir.resolve("missing"))).hasMessageContaining("doesn't exist");
  }

  @Test
  void hostileYamlInAKubeconfigIsRefused() {
    assertThatThrownBy(() -> Kubeconfig.pasted("a: &x [1]\nb: *x\n")).hasMessageContaining("alias");
    assertThatThrownBy(() -> Kubeconfig.pasted("---\na: 1\n---\nb: 2\n")).hasMessageContaining("one YAML document");
  }

  @Test
  void everyCredentialOfAKubeconfigIsTainted() throws Exception {
    String key = "-----BEGIN EC PRIVATE KEY-----\ncnryKEY\n-----END EC PRIVATE KEY-----\n";
    String cert = "-----BEGIN CERTIFICATE-----\nCERT\n-----END CERTIFICATE-----\n";
    String user = "client-certificate-data: " + b64(cert) + "\nclient-key-data: " + b64(key);
    ConnectionConfig cfg = K8sTestKit.cfg(Env.DEV, "{\"auth\":\"kubeconfig\",\"namespace\":\"app-dev\"}");
    List<String> tainted = new ArrayList<>();

    ClusterAccess a = ClusterAccess.resolve(K8sSettings.of(cfg), secrets(Map.of(K8sSettings.KUBECONFIG,
        kubeconfig(user))), Env.DEV, s -> tainted.add(s.toString()));

    assertThat(a.clientKeyPem()).isEqualTo(key);
    assertThat(tainted).contains(key.strip(), b64(key));
    assertThat(a.contexts()).hasSize(2);
    assertThat(K8sClients.keyAlgorithm(key)).isEqualTo("EC");

    List<String> tokens = new ArrayList<>();
    ClusterAccess.resolve(K8sSettings.of(cfg), secrets(Map.of(K8sSettings.KUBECONFIG, kubeconfig("token: cnry-t"))),
        Env.DEV, s -> tokens.add(s.toString()));
    assertThat(tokens).containsExactly("cnry-t");
  }

  @Test
  void skippingTlsVerificationIsRefusedOnProd() {
    String insecure = kubeconfig("token: t").replace("server: https://k8s.dev.internal:6443",
        "server: https://k8s.dev.internal:6443\n    insecure-skip-tls-verify: true");
    ConnectionConfig cfg = K8sTestKit.cfg(Env.PROD, "{\"auth\":\"kubeconfig\",\"namespace\":\"app-dev\"}");

    assertThatThrownBy(() -> ClusterAccess.resolve(K8sSettings.of(cfg), secrets(Map.of(K8sSettings.KUBECONFIG,
        insecure)), Env.PROD, s -> { })).isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
  }

  // ── a kubeconfig file is pinned to the cluster the person trusted ───────────────────────────────────────────

  @Test
  void aKubeconfigFileIsTrustedOnceAndThenPinned() throws Exception {
    Path file = dir.resolve("config");
    Files.writeString(file, kubeconfig("token: t"));
    KubernetesConnector connector = new KubernetesConnector();
    ConnectionConfig unpinned = K8sTestKit.cfg(Env.DEV, "{\"auth\":\"kubeconfig-file\",\"kubeconfigPath\":\""
        + file + "\",\"context\":\"dev\",\"namespace\":\"app-dev\"}");

    TestResult first = connector.test(unpinned, secrets(Map.of()), ctx(Env.DEV));

    assertThat(first.needsConfirmation()).isNotNull();
    assertThat(first.needsConfirmation().kind()).isEqualTo("kubeconfig-cluster");
    assertThat(first.needsConfirmation().text()).contains("points to https://k8s.dev.internal:6443")
        .contains("CA SHA-256");
    assertThat(first.needsConfirmation().configPatch().at("/pinnedCluster/serverUrl").asString())
        .isEqualTo("https://k8s.dev.internal:6443");
    String ca = first.needsConfirmation().configPatch().at("/pinnedCluster/caSha256").asString();
    assertThat(ca).hasSize(64);

    // The file now points elsewhere: calls stop until the person trusts the new cluster.
    Files.writeString(file, kubeconfig("token: t").replace("k8s.dev.internal", "evil.example"));
    ConnectionConfig pinned = K8sTestKit.cfg(Env.DEV, "{\"auth\":\"kubeconfig-file\",\"kubeconfigPath\":\"" + file
        + "\",\"context\":\"dev\",\"namespace\":\"app-dev\",\"pinnedCluster\":{\"serverUrl\":"
        + "\"https://k8s.dev.internal:6443\",\"caSha256\":\"" + ca + "\"}}");
    TestResult changed = connector.test(pinned, secrets(Map.of()), ctx(Env.DEV));
    assertThat(changed.needsConfirmation().text()).startsWith("Kubeconfig changed")
        .contains("https://evil.example:6443");
    assertThat(changed.needsConfirmation().data().path("previousServer").asString())
        .isEqualTo("https://k8s.dev.internal:6443");
    ToolCall get = K8sTestKit.call("k8s_get", "{\"kind\":\"pods\"}");
    assertThatThrownBy(() -> connector.execute(get, pinned, secrets(Map.of()), ctx(Env.DEV)))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED);
          assertThat(e.getMessage()).contains("Kubeconfig changed").contains("evil.example");
        });
  }

  // ── credential commands ─────────────────────────────────────────────────────────────────────────────────────

  @Test
  void aCredentialCommandRunsOnlyWhenAllowedAndTrusted() throws Exception {
    Path plugin = dir.resolve("fake-auth-plugin");
    Files.writeString(plugin, "#!/bin/sh\necho '{}'\n");
    plugin.toFile().setExecutable(true);
    String user = "exec:\n  apiVersion: client.authentication.k8s.io/v1\n  command: " + plugin
        + "\n  args: [get-token, --cluster, dev]\n  env: [{name: AWS_PROFILE, value: dev}]\n  interactiveMode: Never";
    KubernetesConnector connector = new KubernetesConnector();
    Map<String, String> secret = Map.of(K8sSettings.KUBECONFIG, kubeconfig(user));

    ConnectionConfig off = K8sTestKit.cfg(Env.DEV, "{\"auth\":\"kubeconfig\",\"namespace\":\"app-dev\"}");
    TestResult refused = connector.test(off, secrets(secret), ctx(Env.DEV));
    assertThat(refused.ok()).isFalse();
    assertThat(refused.error().code()).isEqualTo("k8s.exec-not-allowed");
    assertThat(refused.metadataUpdate().path("kubeconfigContexts").size()).isEqualTo(2);

    ConnectionConfig on = K8sTestKit.cfg(Env.DEV, "{\"auth\":\"kubeconfig\",\"namespace\":\"app-dev\","
        + "\"allowExecPlugin\":true}");
    TestResult confirm = connector.test(on, secrets(secret), ctx(Env.DEV));
    assertThat(confirm.needsConfirmation().kind()).isEqualTo("exec-plugin");
    assertThat(confirm.needsConfirmation().text()).endsWith(plugin + " get-token --cluster dev");
    assertThat(confirm.needsConfirmation().data().path("env").toString()).isEqualTo("[\"AWS_PROFILE\"]");
    String fingerprint = confirm.needsConfirmation().configPatch().path("trustedExec").asString();
    assertThat(fingerprint).hasSize(64);

    // Another command than the one allowed doesn't run.
    String other = kubeconfig(user.replace("get-token", "get-other-token"));
    ConnectionConfig trusted = K8sTestKit.cfg(Env.DEV, "{\"auth\":\"kubeconfig\",\"namespace\":\"app-dev\","
        + "\"allowExecPlugin\":true,\"trustedExec\":\"" + fingerprint + "\"}");
    ToolCall get = K8sTestKit.call("k8s_get", "{\"kind\":\"pods\"}");
    assertThatThrownBy(() -> connector.execute(get, trusted, secrets(Map.of(K8sSettings.KUBECONFIG, other)),
        ctx(Env.DEV))).hasMessageContaining("isn't the one that was allowed");
  }

  @Test
  void theFormIsCheckedBeforeAnyRequest() {
    assertThatThrownBy(() -> K8sSettings.of(K8sTestKit.cfg(Env.DEV, "{\"auth\":\"token\"}")))
        .hasMessageContaining("Enter the API server");
    assertThatThrownBy(() -> K8sSettings.of(K8sTestKit.cfg(Env.DEV,
        "{\"auth\":\"token\",\"server\":\"https://user:pw@k8s:6443\"}"))).hasMessageContaining("https:// URL");
    assertThatThrownBy(() -> K8sSettings.of(K8sTestKit.cfg(Env.DEV,
        "{\"auth\":\"token\",\"server\":\"https://k8s:6443\",\"proxy\":\"socks5://p:1080\"}")))
        .hasMessageContaining("http:// URL");
    assertThatThrownBy(() -> K8sSettings.of(K8sTestKit.cfg(Env.DEV,
        "{\"auth\":\"token\",\"server\":\"https://k8s:6443\",\"namespace\":\"Bad\"}")))
        .hasMessageContaining("DNS label");
    assertThatThrownBy(() -> K8sSettings.of(K8sTestKit.cfg(Env.DEV, "{\"auth\":\"oidc\"}")))
        .hasMessageContaining("Sign in with");
  }
}
