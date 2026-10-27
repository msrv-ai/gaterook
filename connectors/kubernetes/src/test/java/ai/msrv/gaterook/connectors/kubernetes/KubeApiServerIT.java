package ai.msrv.gaterook.connectors.kubernetes;

import static ai.msrv.gaterook.connectors.kubernetes.K8sTestKit.call;
import static ai.msrv.gaterook.connectors.kubernetes.K8sTestKit.ctx;
import static ai.msrv.gaterook.connectors.kubernetes.K8sTestKit.json;
import static ai.msrv.gaterook.connectors.kubernetes.K8sTestKit.policy;
import static ai.msrv.gaterook.connectors.kubernetes.K8sTestKit.secrets;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.fabric8.kubeapitest.KubeAPIServer;
import io.fabric8.kubeapitest.KubeAPIServerConfigBuilder;
import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.PreparedAction;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * The connector against a real kube-apiserver 1.37 and etcd ({@code io.fabric8:kube-api-test}, no Docker), with the
 * envtest binaries of controller-tools — kubectl among them, so the approver's diff is compared with
 * {@code kubectl diff --server-side} line by line. {@code mvn -Pkube-api}.
 *
 * <p>The archive is downloaded once to {@code ~/.cache/mcp-gateway-test/envtest} and checked against its SHA-256;
 * kube-api-test then runs offline from {@code ~/.cache/mcp-gateway-test/kube-api-test}. There is no kubelet and no
 * controller manager: pods stay Pending, workloads have no status, logs can't be read (the stand-in server covers
 * them).
 */
@Tag("kube-api")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KubeApiServerIT {

  static final String VERSION = "1.37.0";
  /** envtest-v1.37.0-linux-amd64.tar.gz; its SHA-512 in controller-tools' envtest-releases.yaml was checked once. */
  static final String SHA256_LINUX_AMD64 = "f03faedfc68d2a78cee5cb02618bb44e7f67044f4c5fc957f72d3307fda61a9c";
  static final String URL = "https://github.com/kubernetes-sigs/controller-tools/releases/download/envtest-v"
      + VERSION + "/envtest-v" + VERSION + "-linux-amd64.tar.gz";

  private static final Path CACHE = Path.of(System.getProperty("user.home"), ".cache", "mcp-gateway-test");

  private KubeAPIServer server;
  private Path kubectl;
  private Path adminKubeconfig;
  private String adminYaml;
  private String serverUrl;
  private String caPem;
  private KubernetesConnector connector;

  @TempDir
  Path work;

  @BeforeAll
  void startApiServer() throws Exception {
    Path testDir = binaries();
    kubectl = testDir.resolve("k8s").resolve(VERSION + "-linux-amd64").resolve("kubectl");
    server = new KubeAPIServer(KubeAPIServerConfigBuilder.anAPIServerConfig()
        .withTestDir(testDir.toString())
        .withApiServerVersion(VERSION)
        .withOfflineMode(true)
        .withUpdateKubeConfig(false)
        .withStartupTimeout(120_000)
        .build());
    server.start();
    Path dir = Files.createTempDirectory("kube-api-it");
    adminKubeconfig = dir.resolve("admin.kubeconfig");
    Files.writeString(adminKubeconfig, server.getKubeConfigYaml());
    // kube-api-test names certificate files; a pasted kubeconfig carries them inline, as a person pastes it.
    adminYaml = kubectl("config", "view", "--raw", "--minify", "--flatten");
    Files.writeString(adminKubeconfig, adminYaml);
    Kubeconfig.Resolved admin = Kubeconfig.pasted(adminYaml).resolve(null);
    serverUrl = admin.server().toString();
    caPem = admin.caPem();
    connector = new KubernetesConnector();
    kubectl("create", "namespace", "app-dev");
    kubectl("create", "namespace", "web");
  }

  @AfterAll
  void stopApiServer() {
    if (connector != null) {
      connector.close();
    }
    if (server != null) {
      server.stop();
    }
  }

  // ── the binaries ────────────────────────────────────────────────────────────────────────────────────────────

  /** The kube-api-test directory with {@code k8s/1.37.0-linux-amd64/{etcd,kube-apiserver,kubectl}}. */
  private static Path binaries() throws Exception {
    String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
    String arch = System.getProperty("os.arch");
    org.junit.jupiter.api.Assumptions.assumeTrue(os.contains("linux") && (arch.equals("amd64")
        || arch.equals("x86_64")), "the pinned envtest archive is linux-amd64");
    Path archive = CACHE.resolve("envtest").resolve("envtest-v" + VERSION + "-linux-amd64.tar.gz");
    if (!Files.exists(archive) || !sha256(archive).equals(SHA256_LINUX_AMD64)) {
      Files.createDirectories(archive.getParent());
      Path part = archive.resolveSibling(archive.getFileName() + ".part");
      HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
      HttpResponse<Path> r = http.send(HttpRequest.newBuilder(URI.create(URL)).build(),
          HttpResponse.BodyHandlers.ofFile(part));
      if (r.statusCode() != 200 || !sha256(part).equals(SHA256_LINUX_AMD64)) {
        Files.deleteIfExists(part);
        throw new IllegalStateException("The envtest archive from " + URL + " doesn't match its SHA-256.");
      }
      Files.move(part, archive, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
    Path testDir = CACHE.resolve("kube-api-test");
    Path bin = testDir.resolve("k8s").resolve(VERSION + "-linux-amd64");
    Path stamp = bin.resolve(".sha256");
    if (!Files.exists(stamp) || !Files.readString(stamp).equals(SHA256_LINUX_AMD64)) {
      Files.createDirectories(bin);
      try (InputStream in = new GZIPInputStream(Files.newInputStream(archive), 1 << 16)) {
        untar(in, bin, List.of("etcd", "kube-apiserver", "kubectl"));
      }
      Files.writeString(stamp, SHA256_LINUX_AMD64);
    }
    return testDir;
  }

  /** The regular files named {@code wanted} of a ustar stream, flattened into {@code dir}. */
  private static void untar(InputStream in, Path dir, List<String> wanted) throws IOException {
    byte[] header = new byte[512];
    while (in.readNBytes(header, 0, 512) == 512) {
      String name = field(header, 0, 100);
      if (name.isEmpty()) {
        return;
      }
      String prefix = field(header, 345, 155);
      long size = Long.parseLong(field(header, 124, 12).strip(), 8);
      char type = (char) header[156];
      String path = prefix.isEmpty() ? name : prefix + "/" + name;
      String file = path.substring(path.lastIndexOf('/') + 1);
      long padded = (size + 511) / 512 * 512;
      if ((type == '0' || type == 0) && wanted.contains(file)) {
        Path target = dir.resolve(file);
        try (var out = Files.newOutputStream(target)) {
          copyExactly(in, out, size);
        }
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwxr-xr-x"));
        in.skipNBytes(padded - size);
      } else {
        in.skipNBytes(padded);
      }
    }
  }

  private static String field(byte[] header, int offset, int length) {
    int end = offset;
    while (end < offset + length && header[end] != 0) {
      end++;
    }
    return new String(header, offset, end - offset, StandardCharsets.US_ASCII);
  }

  private static void copyExactly(InputStream in, java.io.OutputStream out, long size) throws IOException {
    byte[] buf = new byte[1 << 16];
    long left = size;
    while (left > 0) {
      int n = in.read(buf, 0, (int) Math.min(buf.length, left));
      if (n < 0) {
        throw new IOException("The archive ends inside a file.");
      }
      out.write(buf, 0, n);
      left -= n;
    }
  }

  private static String sha256(Path file) throws Exception {
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    try (InputStream in = Files.newInputStream(file)) {
      byte[] buf = new byte[1 << 16];
      for (int n; (n = in.read(buf)) > 0; ) {
        md.update(buf, 0, n);
      }
    }
    return HexFormat.of().formatHex(md.digest());
  }

  // ── kubectl as the person's own tool ────────────────────────────────────────────────────────────────────────

  record Run(int exit, String out, String err) {}

  private Run kubectlRun(String stdin, String... args) throws Exception {
    List<String> argv = new ArrayList<>(List.of(kubectl.toString(), "--kubeconfig", adminKubeconfig.toString()));
    argv.addAll(List.of(args));
    ProcessBuilder pb = new ProcessBuilder(argv);
    pb.environment().put("KUBECTL_EXTERNAL_DIFF", "diff -u -N");
    Process p = pb.start();
    if (stdin != null) {
      p.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
    }
    p.getOutputStream().close();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    Thread t1 = Thread.ofVirtual().start(() -> copy(p.getInputStream(), out));
    Thread t2 = Thread.ofVirtual().start(() -> copy(p.getErrorStream(), err));
    if (!p.waitFor(60, TimeUnit.SECONDS)) {
      p.destroyForcibly();
      throw new IllegalStateException("kubectl " + String.join(" ", args) + " hung");
    }
    t1.join();
    t2.join();
    return new Run(p.exitValue(), out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
  }

  private String kubectl(String... args) throws Exception {
    return kubectlIn(null, args);
  }

  private String kubectlIn(String stdin, String... args) throws Exception {
    Run r = kubectlRun(stdin, args);
    if (r.exit() != 0) {
      throw new IllegalStateException("kubectl " + String.join(" ", args) + " failed: " + r.err());
    }
    return r.out();
  }

  private static void copy(InputStream in, ByteArrayOutputStream out) {
    try (in) {
      in.transferTo(out);
    } catch (IOException ignored) {
      // The process ended.
    }
  }

  // ── connections ─────────────────────────────────────────────────────────────────────────────────────────────

  private ConnectionConfig adminCfg(String id, Env env, JsonNode metadata) {
    return K8sTestKit.cfg(id, env, "{\"auth\":\"kubeconfig\",\"namespace\":\"app-dev\"}", metadata);
  }

  private Map<String, String> adminSecret() {
    return Map.of(K8sSettings.KUBECONFIG, adminYaml);
  }

  private ConnectionConfig tokenCfg(String id, String namespace) {
    return K8sTestKit.cfg(id, Env.DEV, "{\"auth\":\"token\",\"server\":\"" + serverUrl + "\",\"namespace\":\""
        + namespace + "\",\"caCert\":" + K8sApi.JSON.writeValueAsString(caPem) + "}", null);
  }

  private ToolResult execute(ToolCall call, ConnectionConfig cfg, Map<String, String> secret)
      throws ConnectorException {
    return connector.execute(call, cfg, secrets(secret), ctx(Env.DEV));
  }

  /** prepare → approval → execute, as the core runs a change a person approved. */
  private ToolResult approveAndRun(ToolCall call, ConnectionConfig cfg, PreparedAction prepared)
      throws ConnectorException {
    assertThat(prepared.outcome()).as(prepared.message()).isEqualTo(PreparedAction.Outcome.APPROVAL);
    return connector.execute(call, cfg, secrets(adminSecret()), ctx(Env.DEV)
        .approved(prepared.preview().payload(), prepared.preparedState()));
  }

  // ── Test connection ─────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void testConnectionReadsTheClusterAndStoresItsDiscovery() {
    TestResult t = connector.test(adminCfg("draft-admin", Env.DEV, null), secrets(adminSecret()), ctx(Env.DEV));

    assertThat(t.ok()).as(String.valueOf(t.error())).isTrue();
    assertThat(t.serverVersion()).startsWith("v1.37.0");
    assertThat(t.permissions().level()).isEqualTo(TestResult.Level.ADMIN);
    assertThat(t.hints()).extracting(TestResult.Hint::code).contains("k8s.admin");
    assertThat(t.metadataUpdate().at("/discovery/resources").size()).isGreaterThan(40);
    Kinds kinds = Kinds.of(t.metadataUpdate());
    assertThat(kinds.resolve("deploy").orElseThrow().rbacName()).isEqualTo("deployments.apps");
    assertThat(kinds.resolve("pods").orElseThrow().group()).isEmpty();
  }

  @Test
  void aServiceAccountTokenSeesWhatItsRoleAllows() throws Exception {
    kubectlIn("""
        apiVersion: v1
        kind: ServiceAccount
        metadata: {name: gw-reader, namespace: app-dev}
        ---
        apiVersion: rbac.authorization.k8s.io/v1
        kind: Role
        metadata: {name: gw-reader, namespace: app-dev}
        rules:
        - apiGroups: [""]
          resources: [pods, pods/log, configmaps, events]
          verbs: [get, list, watch]
        - apiGroups: [apps]
          resources: [deployments]
          verbs: [get, list, watch, patch]
        ---
        apiVersion: rbac.authorization.k8s.io/v1
        kind: RoleBinding
        metadata: {name: gw-reader, namespace: app-dev}
        roleRef: {apiGroup: rbac.authorization.k8s.io, kind: Role, name: gw-reader}
        subjects: [{kind: ServiceAccount, name: gw-reader, namespace: app-dev}]
        """, "apply", "-f", "-");
    String token = kubectl("create", "token", "gw-reader", "-n", "app-dev", "--duration=1h").strip();
    ConnectionConfig cfg = tokenCfg("c-reader", "app-dev");
    Map<String, String> secret = Map.of(K8sSettings.TOKEN, token);

    TestResult t = connector.test(cfg, secrets(secret), ctx(Env.DEV));

    assertThat(t.ok()).as(String.valueOf(t.error())).isTrue();
    assertThat(t.identity()).isEqualTo("system:serviceaccount:app-dev:gw-reader");
    assertThat(t.permissions().details().path("list pods").asBoolean()).isTrue();
    assertThat(t.permissions().details().path("patch deployments").asBoolean()).isTrue();
    assertThat(t.permissions().details().path("read Secrets").asBoolean()).isFalse();
    assertThat(t.permissions().details().path("do anything").asBoolean()).isFalse();
    assertThat(t.hints()).extracting(TestResult.Hint::code).contains("k8s.token-expiry")
        .doesNotContain("k8s.admin", "k8s.broad-rights");

    // What the Role doesn't grant, the API server refuses in its own words.
    ToolCall secrets = call("k8s_get", "{\"kind\":\"secrets\"}");
    assertThat(connector.classify(secrets, cfg, policy(Env.DEV, "{\"resources\":[\"secrets\"]}")).kind())
        .isEqualTo(Classification.Kind.READ);
    assertThatThrownBy(() -> execute(secrets, cfg, secret)).hasMessageStartingWith("Forbidden by the cluster's RBAC")
        .hasMessageContaining("cannot list resource \"secrets\"");
    assertThatThrownBy(() -> execute(call("k8s_get", "{\"kind\":\"pods\",\"namespace\":\"web\"}"), cfg, secret))
        .hasMessageContaining("Forbidden");
  }

  // ── the approver's diff is kubectl's ────────────────────────────────────────────────────────────────────────

  private static final String WEB_V1 = """
      apiVersion: apps/v1
      kind: Deployment
      metadata:
        name: web
        namespace: app-dev
        labels: {app: web, tier: "frontend"}
        annotations:
          description: The web front end of the shop; it serves the storefront, the cart and the checkout pages to
            every visitor, behind the ingress.
      spec:
        replicas: 2
        selector: {matchLabels: {app: web}}
        template:
          metadata: {labels: {app: web}}
          spec:
            containers:
            - name: web
              image: registry.example.com/web:1.4.2
              args: ["--port=8080", "--log-format", "json"]
              env:
              - {name: FEATURE_CART, value: "true"}
              - {name: TIMEOUT, value: "30"}
              ports: [{containerPort: 8080, name: http}]
              resources: {requests: {cpu: 100m, memory: 128Mi}}
      """;

  private static final String WEB_V2 = WEB_V1.replace("replicas: 2", "replicas: 3")
      .replace("web:1.4.2", "web:1.4.3")
      .replace("behind the ingress.", "behind the ingress and the CDN, which caches the static assets for an hour.")
      .replace("- {name: TIMEOUT, value: \"30\"}", "- {name: TIMEOUT, value: \"45\"}\n        - {name: "
          + "MODE, value: \"on\"}")
      .replace("tier: \"frontend\"", "tier: \"frontend\", version: \"1.4\"");

  private ToolCall apply(String manifest) {
    return call("k8s_apply", K8sApi.JSON.createObjectNode().put("manifest", manifest).toString());
  }

  private ToolCall apply(String manifest, boolean force) {
    return call("k8s_apply", K8sApi.JSON.createObjectNode().put("manifest", manifest).put("force", force)
        .toString());
  }

  private PreparedAction prepare(ToolCall call) throws ConnectorException {
    return connector.prepare(call, adminCfg("c-admin", Env.DEV, null), secrets(adminSecret()), ctx(Env.DEV));
  }

  /** The hunks of {@code kubectl diff --server-side} as the gateway's field manager (exit 1: there are changes). */
  private List<String> kubectlDiff(String manifest) throws Exception {
    Run r = kubectlRun(manifest, "diff", "--server-side", "--field-manager=gaterook", "-f", "-");
    assertThat(r.exit()).as(r.err()).isEqualTo(1);
    return hunks(r.out().lines().toList());
  }

  private static List<String> fresh(List<String> lines) {
    return lines.stream().map(l -> l.replaceFirst("^\\+  (uid|creationTimestamp): .*", "+  $1: …")).toList();
  }

  /** From the first {@code @@}: the headers name temporary files on one side and the object on the other. */
  private static List<String> hunks(List<String> lines) {
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).startsWith("@@")) {
        return lines.subList(i, lines.size());
      }
    }
    return List.of();
  }

  @Test
  void theApproversDiffIsKubectlDiffServerSide() throws Exception {
    // A new object: everything is added, as against kubectl's empty LIVE file.
    ToolCall create = apply(WEB_V1);
    List<String> kubectlCreate = kubectlDiff(WEB_V1);
    PreparedAction created = prepare(create);
    // Every dry run of a create makes up its own uid and timestamp: the rest is the same.
    assertThat(fresh(hunks(created.preview().text().lines().toList()))).isEqualTo(fresh(kubectlCreate));
    assertThat(approveAndRun(create, adminCfg("c-admin", Env.DEV, null), created).isError()).isFalse();

    // A change of the object the gateway manages: replicas, image, env, a label and a long annotation.
    ToolCall change = apply(WEB_V2);
    List<String> kubectlChange = kubectlDiff(WEB_V2);
    PreparedAction changed = prepare(change);

    List<String> ours = hunks(changed.preview().text().lines().toList());
    assertThat(String.join("\n", ours)).isEqualTo(String.join("\n", kubectlChange));
    assertThat(ours).contains("-  replicas: 2", "+  replicas: 3");
    assertThat(changed.preview().warnings()).contains("deployment/web: image of web registry.example.com/web:1.4.2 "
        + "→ registry.example.com/web:1.4.3", "deployment/web: replicas 2 → 3");
    assertThat(approveAndRun(change, adminCfg("c-admin", Env.DEV, null), changed).isError()).isFalse();
    assertThat(kubectl("get", "deployment", "web", "-n", "app-dev", "-o", "jsonpath={.spec.replicas}"))
        .isEqualTo("3");
    // Applied again, nothing changes and nothing waits for a person.
    assertThat(prepare(apply(WEB_V2)).outcome()).isEqualTo(PreparedAction.Outcome.NO_CHANGE);
  }

  // ── Secrets and last-applied ────────────────────────────────────────────────────────────────────────────────

  @Test
  void aSecretAppliedWithKubectlNeverShowsItsValues() throws Exception {
    kubectlIn("""
        apiVersion: v1
        kind: Secret
        metadata: {name: db, namespace: app-dev}
        stringData: {password: cnry-kubectl-secret-9q, user: admin}
        """, "apply", "-f", "-");
    // Client-side apply copies the plain stringData into the last-applied annotation.
    assertThat(kubectl("get", "secret", "db", "-n", "app-dev", "-o", "yaml")).contains("cnry-kubectl-secret-9q");
    ConnectionConfig cfg = adminCfg("c-admin", Env.DEV, null);
    ToolCall one = call("k8s_get", "{\"kind\":\"secrets\",\"name\":\"db\",\"events\":false}");
    ToolCall list = call("k8s_get", "{\"kind\":\"secrets\"}");
    assertThat(connector.classify(one, cfg, policy(Env.DEV, "{\"resources\":[\"secrets\"]}")).kind())
        .isEqualTo(Classification.Kind.READ);

    ToolResult r = execute(one, cfg, adminSecret());
    ToolResult rows = execute(list, cfg, adminSecret());

    String base64 = java.util.Base64.getEncoder().encodeToString("cnry-kubectl-secret-9q".getBytes());
    for (ToolResult t : List.of(r, rows)) {
      String all = t.structuredContent() + "\n" + t.content().get(0).text();
      assertThat(all).doesNotContain("cnry-kubectl-secret", base64, "last-applied-configuration");
    }
    assertThat(r.structuredContent().at("/object/data/password").asString()).isEqualTo("‹masked›");
    assertThat(r.structuredContent().at("/object/data/user").asString()).isEqualTo("‹masked›");
    assertThat(rows.structuredContent().path("columns").toString()).isEqualTo("[\"Name\",\"Type\",\"Data\","
        + "\"Age\"]");
  }

  @Test
  void lastAppliedNeverReachesTheAgentOrTheApprover() throws Exception {
    kubectlIn("""
        apiVersion: v1
        kind: ConfigMap
        metadata: {name: legacy, namespace: app-dev}
        data: {url: "https://user:cnry-in-a-configmap@db.internal"}
        """, "apply", "-f", "-");
    ConnectionConfig cfg = adminCfg("c-admin", Env.DEV, null);

    ToolResult r = execute(call("k8s_get", "{\"kind\":\"cm\",\"name\":\"legacy\",\"events\":false}"), cfg,
        adminSecret());
    PreparedAction p = prepare(apply("""
        apiVersion: v1
        kind: ConfigMap
        metadata: {name: legacy, namespace: app-dev}
        data: {url: "https://db.internal"}
        """, true));

    assertThat(r.structuredContent().toString()).doesNotContain("last-applied-configuration");
    assertThat(p.preview().text()).doesNotContain("last-applied-configuration")
        .contains("+  url: https://db.internal");
  }

  // ── what runs after approval is what was approved ───────────────────────────────────────────────────────────

  private static final String SETTINGS = """
      apiVersion: apps/v1
      kind: Deployment
      metadata: {name: settings, namespace: app-dev}
      spec:
        replicas: %d
        selector: {matchLabels: {app: settings}}
        template:
          metadata: {labels: {app: settings}}
          spec: {containers: [{name: s, image: "settings:1"}]}
      """;

  @Test
  void applyHoldsToTheResourceVersionItWasBuiltOn() throws Exception {
    ConnectionConfig cfg = adminCfg("c-admin", Env.DEV, null);
    ToolCall create = apply(SETTINGS.formatted(1));
    approveAndRun(create, cfg, prepare(create));

    // A status write between approval and execution (controllers do it all the time) doesn't stop the apply.
    ToolCall two = apply(SETTINGS.formatted(2));
    PreparedAction approved = prepare(two);
    String rvBefore = kubectl("get", "deploy", "settings", "-n", "app-dev", "-o",
        "jsonpath={.metadata.resourceVersion}");
    kubectl("patch", "deploy", "settings", "-n", "app-dev", "--subresource=status", "--type=merge", "-p",
        "{\"status\":{\"observedGeneration\":1,\"replicas\":1}}");
    assertThat(kubectl("get", "deploy", "settings", "-n", "app-dev", "-o", "jsonpath={.metadata.resourceVersion}"))
        .isNotEqualTo(rvBefore);
    ToolResult ok = approveAndRun(two, cfg, approved);
    assertThat(ok.structuredContent().path("status").asString()).isEqualTo("completed");
    assertThat(kubectl("get", "deploy", "settings", "-n", "app-dev", "-o", "jsonpath={.spec.replicas}")).isEqualTo("2");

    // A change of the object itself after approval stops it: the dry run is no longer the approved object.
    ToolCall three = apply(SETTINGS.formatted(3));
    PreparedAction approvedThree = prepare(three);
    kubectl("annotate", "deploy", "settings", "-n", "app-dev", "owner=someone-else");
    ToolResult stopped = approveAndRun(three, cfg, approvedThree);
    assertThat(stopped.isError()).isTrue();
    assertThat(stopped.content().get(0).text()).startsWith("deployment/settings changed in the cluster after "
        + "approval.");
    assertThat(kubectl("get", "deploy", "settings", "-n", "app-dev", "-o", "jsonpath={.spec.replicas}")).isEqualTo("2");
  }

  @Test
  void anotherManagersFieldsAreTakenOnlyWithForceAndTheApproverSeesWhich() throws Exception {
    kubectlIn("""
        apiVersion: v1
        kind: ConfigMap
        metadata: {name: shared, namespace: app-dev}
        data: {owner: kubectl}
        """, "apply", "--server-side", "-f", "-");
    String manifest = """
        apiVersion: v1
        kind: ConfigMap
        metadata: {name: shared, namespace: app-dev}
        data: {owner: gaterook}
        """;

    PreparedAction refused = prepare(apply(manifest, false));
    assertThat(refused.outcome()).isEqualTo(PreparedAction.Outcome.REJECT);
    assertThat(refused.message()).contains(".data.owner (owned by kubectl)").contains("force=true");

    ToolCall forced = apply(manifest, true);
    PreparedAction p = prepare(forced);
    assertThat(p.preview().fields()).extracting(ActionPreview.Field::value)
        .contains("takes over configmap/shared .data.owner (owned by kubectl)");
    approveAndRun(forced, adminCfg("c-admin", Env.DEV, null), p);
    assertThat(kubectl("get", "cm", "shared", "-n", "app-dev", "-o", "jsonpath={.data.owner}"))
        .isEqualTo("gaterook");
    assertThat(kubectl("get", "cm", "shared", "-n", "app-dev", "-o", "jsonpath={.metadata.managedFields[*].manager}",
        "--show-managed-fields")).contains("gaterook");
  }

  @Test
  void aRestartChangesOnlyTheObjectThatWasApproved() throws Exception {
    ConnectionConfig cfg = adminCfg("c-admin", Env.DEV, null);
    String manifest = SETTINGS.replace("name: settings", "name: worker").replace("app: settings", "app: worker")
        .formatted(2);
    kubectlIn(manifest, "apply", "-f", "-");
    ToolCall restart = call("k8s_rollout_restart", "{\"kind\":\"deployment\",\"name\":\"worker\"}");

    PreparedAction p = prepare(restart);
    ToolResult r = approveAndRun(restart, cfg, p);

    assertThat(r.content().get(0).text()).startsWith("Restarted deployment/worker in app-dev");
    assertThat(kubectl("get", "deploy", "worker", "-n", "app-dev", "-o",
        "jsonpath={.spec.template.metadata.annotations.kubectl\\.kubernetes\\.io/restartedAt}")).isNotBlank();
    assertThat(kubectl("get", "deploy", "worker", "-n", "app-dev", "-o", "jsonpath={.metadata.generation}"))
        .isEqualTo("2");

    // Deleted and created again after approval: a new uid, and nothing restarts.
    PreparedAction again = prepare(restart);
    kubectl("delete", "deploy", "worker", "-n", "app-dev", "--wait=true");
    kubectlIn(manifest, "apply", "-f", "-");
    assertThatThrownBy(() -> approveAndRun(restart, cfg, again)).hasMessageContaining("deleted or replaced after "
        + "approval");
    assertThat(kubectl("get", "deploy", "worker", "-n", "app-dev", "-o", "jsonpath={.metadata.generation}"))
        .isEqualTo("1");

    // The API server itself refuses a merge patch whose uid isn't the object's: the precondition holds even if
    // the object is replaced between the gateway's read and its patch.
    Run wrongUid = kubectlRun(null, "patch", "deploy", "worker", "-n", "app-dev", "--type=merge", "-p",
        "{\"metadata\":{\"uid\":\"" + p.preparedState().path("uid").asString() + "\"},\"spec\":{\"template\":"
            + "{\"metadata\":{\"annotations\":{\"x\":\"y\"}}}}}");
    assertThat(wrongUid.exit()).isNotZero();
    assertThat(wrongUid.err()).contains("metadata.uid").contains("field is immutable");

    kubectl("patch", "deploy", "worker", "-n", "app-dev", "--type=merge", "-p", "{\"spec\":{\"paused\":true}}");
    assertThat(prepare(restart).message()).contains("is paused");
  }

  // ── lists, custom resources, pods ───────────────────────────────────────────────────────────────────────────

  @Test
  void listsPageWithTheServersContinueTokenBehindASealedCursor() throws Exception {
    kubectl("create", "namespace", "paging");
    for (int i = 0; i < 5; i++) {
      kubectl("create", "configmap", "page-" + i, "-n", "paging", "--from-literal=k=v");
    }
    ConnectionConfig cfg = K8sTestKit.cfg("c-paging", Env.DEV, "{\"auth\":\"kubeconfig\",\"namespace\":"
        + "\"paging\"}", null);
    List<String> names = new ArrayList<>();
    String cursor = null;
    int pages = 0;
    K8sTestKit.Ctx ctx = ctx(Env.DEV);
    do {
      String args = "{\"kind\":\"configmaps\",\"limit\":2" + (cursor == null ? "" : ",\"cursor\":\"" + cursor
          + "\"") + "}";
      ToolResult page = connector.execute(call("k8s_get", args), cfg, secrets(adminSecret()), ctx);
      page.structuredContent().path("rows").forEach(row -> names.add(row.path("name").asString()));
      cursor = page.structuredContent().path("cursor").asString(null);
      pages++;
    } while (cursor != null && pages < 10);

    assertThat(names).contains("page-0", "page-1", "page-2", "page-3", "page-4");
    assertThat(pages).isGreaterThanOrEqualTo(3);
  }

  @Test
  void customResourcesBecomeKnownWithTestConnection() throws Exception {
    kubectlIn("""
        apiVersion: apiextensions.k8s.io/v1
        kind: CustomResourceDefinition
        metadata: {name: widgets.example.com}
        spec:
          group: example.com
          scope: Namespaced
          names: {plural: widgets, singular: widget, kind: Widget, shortNames: [wd]}
          versions:
          - name: v1
            served: true
            storage: true
            schema:
              openAPIV3Schema:
                type: object
                properties:
                  spec: {type: object, properties: {size: {type: integer}}}
            additionalPrinterColumns:
            - {name: Size, type: integer, jsonPath: .spec.size}
        """, "apply", "-f", "-");
    kubectl("wait", "--for=condition=Established", "crd/widgets.example.com", "--timeout=30s");
    String widget = """
        apiVersion: example.com/v1
        kind: Widget
        metadata: {name: blue, namespace: app-dev}
        spec: {size: 3}
        """;
    ConnectionConfig before = adminCfg("c-crd", Env.DEV, null);
    assertThat(connector.classify(call("k8s_get", "{\"kind\":\"wd\"}"), before, policy(Env.DEV, "{}")).ruleId())
        .isEqualTo("dev.k8s.kind-unknown");

    TestResult t = connector.test(before, secrets(adminSecret()), ctx(Env.DEV));
    ConnectionConfig after = adminCfg("c-crd", Env.DEV, t.metadataUpdate());
    assertThat(connector.classify(call("k8s_get", "{\"kind\":\"wd\"}"), after, policy(Env.DEV, "{}")).kind())
        .isEqualTo(Classification.Kind.READ);
    ToolCall create = apply(widget);
    PreparedAction p = connector.prepare(create, after, secrets(adminSecret()), ctx(Env.DEV));
    assertThat(fresh(hunks(p.preview().text().lines().toList()))).isEqualTo(fresh(kubectlDiff(widget)));
    connector.execute(create, after, secrets(adminSecret()), ctx(Env.DEV).approved(p.preview().payload(),
        p.preparedState()));

    ToolResult rows = connector.execute(call("k8s_get", "{\"kind\":\"widgets.example.com\"}"), after,
        secrets(adminSecret()), ctx(Env.DEV));
    // The CRD's printer columns replace the default ones, as kubectl get shows them.
    assertThat(rows.structuredContent().path("columns").toString()).isEqualTo("[\"Name\",\"Size\"]");
    assertThat(rows.structuredContent().at("/rows/0/cells/1").asInt()).isEqualTo(3);
  }

  @Test
  void aPodWithoutANodeSaysSoAndHasNoLogs() throws Exception {
    kubectlIn("""
        apiVersion: v1
        kind: Pod
        metadata: {name: lonely, namespace: app-dev}
        spec: {containers: [{name: app, image: "app:1"}]}
        """, "apply", "-f", "-");
    ConnectionConfig cfg = adminCfg("c-admin", Env.DEV, null);

    ToolResult pod = execute(call("k8s_get", "{\"kind\":\"po\",\"name\":\"lonely\"}"), cfg, adminSecret());

    assertThat(pod.content().get(0).text()).startsWith("pod/lonely in app-dev on dev-cluster (dev) · Pending · 0/1 "
        + "ready · 0 restarts");
    assertThat(pod.structuredContent().at("/containers/0/image").asString()).isEqualTo("app:1");
    // The API server answers an empty log for a pod no node runs (there is no kubelet to ask).
    ToolResult logs = execute(call("k8s_logs", "{\"pod\":\"lonely\"}"), cfg, adminSecret());
    assertThat(logs.content().get(0).text()).endsWith("(no log lines)");
    assertThat(logs.structuredContent().path("container").asString()).isEqualTo("app");
  }

  // ── the native smoke's fixture touch ────────────────────────────────────────────────────────────────────────

  @Test
  void theSmokeTouchWithAnApiServerHolds() throws Exception {
    kubectlIn("""
        apiVersion: v1
        kind: ServiceAccount
        metadata: {name: gw-smoke, namespace: app-dev}
        ---
        apiVersion: rbac.authorization.k8s.io/v1
        kind: Role
        metadata: {name: gw-smoke, namespace: app-dev}
        rules: [{apiGroups: [""], resources: [pods], verbs: [get, list]}]
        ---
        apiVersion: rbac.authorization.k8s.io/v1
        kind: RoleBinding
        metadata: {name: gw-smoke, namespace: app-dev}
        roleRef: {apiGroup: rbac.authorization.k8s.io, kind: Role, name: gw-smoke}
        subjects: [{kind: ServiceAccount, name: gw-smoke, namespace: app-dev}]
        """, "apply", "-f", "-");
    Map<String, String> fixture = Map.of("server", serverUrl, "caCertificate", caPem, "token",
        kubectl("create", "token", "gw-smoke", "-n", "app-dev").strip());
    JsonNode touch = null;
    for (JsonNode t : SmokeDescriptorTest.descriptor().path("touches")) {
      if ("kube-apiserver".equals(t.path("fixture").asString(null))) {
        touch = t;
      }
    }
    assertThat(touch).isNotNull();
    ConnectionConfig cfg = SmokeDescriptorTest.connection(touch, fixture);

    ToolResult r = connector.execute(SmokeDescriptorTest.call(touch, cfg), cfg,
        SmokeDescriptorTest.secrets(touch, fixture), ctx(cfg.env()));

    JsonNode expect = touch.path("expect");
    assertThat(r.isError()).isEqualTo(expect.path("isError").asBoolean());
    expect.path("structuredContent").properties().forEach(e -> assertThat(r.structuredContent().path(e.getKey()))
        .as(e.getKey()).isEqualTo(e.getValue()));
  }
}
