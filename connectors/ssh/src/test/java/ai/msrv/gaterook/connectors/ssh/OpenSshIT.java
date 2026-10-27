package ai.msrv.gaterook.connectors.ssh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The connector against the real OpenSSH 9.6p1 of Ubuntu 24.04 ({@code -Popenssh-it} of this module, which sets
 * {@code gateway.it.openssh}; report 06 §8): the packages
 * are downloaded once to {@code ~/.cache/mcp-gateway-test/openssh-9.6p1} and checked against their SHA-256, unpacked
 * without installing, and sshd runs unprivileged as {@code ubuntu} in a mount namespace that binds the test folder
 * at {@code /mnt} (it needs absolute paths it can read): {@code unshare -m} + {@code runuser}. Needs root, the user
 * {@code ubuntu}, {@code dpkg-deb}, {@code unshare} and {@code runuser}; skipped otherwise.
 *
 * <p>What only a real sshd shows: its banner and probe answers, a certificate checked by {@code TrustedUserCAKeys},
 * no PTY, stdin at EOF, and {@code signal TERM} reaching the process group of the command.
 *
 * <p>With the Compose services ({@link TestServices}) the sshd is theirs ({@code test-services/openssh}: the same
 * OpenSSH of Ubuntu 24.04 and user, its host key, user key, user CA and log in {@code run/openssh}); it needs neither
 * root nor Linux here.
 */
@EnabledIfSystemProperty(named = "gateway.it.openssh", matches = "true",
    disabledReason = "real sshd: mvn -pl connectors/ssh -am verify -Popenssh-it")
class OpenSshIT {

  private static final String USER = "ubuntu";
  private static final String MIRROR = "http://archive.ubuntu.com/ubuntu/pool/main/";
  /** Name, path in the pool, SHA-256 as published in the noble(-updates) Packages index. */
  private static final List<String[]> PACKAGES = List.of(
      new String[] {"openssh-server_9.6p1-3ubuntu13.20_amd64.deb", "o/openssh/",
          "5d0d5eb672ddc27601e2da4e8465c5736dc73375a21bc1cd1834fcb08fb155bf"},
      new String[] {"libwrap0_7.6.q-33_amd64.deb", "t/tcp-wrappers/",
          "36118b862539868408ee056411d6e470068d14c46e5296b29bfb744770f5d2ce"});

  private static Path dir;
  private static Process sshd;
  private static int port;
  private static KeyPair hostKey;
  private static KeyPair userKey;
  private static KeyPair ca;

  private SshConnector connector;

  @BeforeAll
  static void startSshd() throws Exception {
    if (TestServices.enabled()) {
      useComposeSshd();
      return;
    }
    assumeTrue("root".equals(System.getProperty("user.name")), "needs root for unshare -m");
    for (String tool : List.of("/usr/bin/dpkg-deb", "/usr/bin/unshare", "/usr/sbin/runuser")) {
      assumeTrue(Files.isExecutable(Path.of(tool)), "needs " + tool);
    }
    assumeTrue(run(List.of("id", "-u", USER)) == 0, "needs the user " + USER);
    MinaSecurity.install();

    Path cache = Path.of(System.getProperty("user.home"), ".cache", "mcp-gateway-test", "openssh-9.6p1");
    Files.createDirectories(cache);
    dir = Path.of("target", "openssh-it").toAbsolutePath();
    if (Files.exists(dir)) {
      try (Stream<Path> all = Files.walk(dir)) {
        all.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
      }
    }
    Path runDir = Files.createDirectories(dir.resolve("run"));
    for (String[] pkg : PACKAGES) {
      Path deb = download(cache.resolve(pkg[0]), MIRROR + pkg[1] + pkg[0], pkg[2]);
      assertThat(run(List.of("/usr/bin/dpkg-deb", "-x", deb.toString(), dir.resolve("root").toString()))).isZero();
    }

    hostKey = KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, 256);
    userKey = KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, 256);
    ca = KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, 256);
    ByteArrayOutputStream hk = new ByteArrayOutputStream();
    OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(hostKey, "host", null, hk);
    Files.write(runDir.resolve("host_ed25519"), hk.toByteArray());
    Files.writeString(runDir.resolve("authorized_keys"), HostKeys.line(userKey.getPublic()) + "\n");
    Files.writeString(runDir.resolve("user_ca.pub"), HostKeys.line(ca.getPublic()) + "\n");
    port = freePort();
    Files.writeString(runDir.resolve("sshd_config"), """
        Port %d
        ListenAddress 127.0.0.1
        HostKey /mnt/run/host_ed25519
        PidFile none
        AuthorizedKeysFile /mnt/run/authorized_keys
        TrustedUserCAKeys /mnt/run/user_ca.pub
        PasswordAuthentication no
        KbdInteractiveAuthentication no
        UsePAM no
        StrictModes no
        AllowTcpForwarding yes
        MaxSessions 10
        LogLevel VERBOSE
        """.formatted(port));
    UserPrincipal ubuntu = dir.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(USER);
    try (Stream<Path> files = Files.list(runDir)) {
      for (Path f : files.toList()) {
        Files.setOwner(f, ubuntu);
      }
    }
    Files.setOwner(runDir, ubuntu);
    Files.setPosixFilePermissions(runDir.resolve("host_ed25519"), PosixFilePermissions.fromString("rw-------"));

    sshd = new ProcessBuilder("/usr/bin/unshare", "-m", "--propagation", "private", "sh", "-c",
        "mount --bind '" + dir + "' /mnt && exec /usr/sbin/runuser -u " + USER + " -- /usr/bin/env "
            + "LD_LIBRARY_PATH=/mnt/root/usr/lib/x86_64-linux-gnu:/mnt/root/lib/x86_64-linux-gnu "
            + "/mnt/root/usr/sbin/sshd -D -e -f /mnt/run/sshd_config")
        .redirectErrorStream(true).redirectOutput(dir.resolve("sshd.log").toFile()).start();
    long until = System.nanoTime() + 15_000_000_000L;
    while (!listening(port)) {
      assertThat(sshd.isAlive()).as(Files.readString(dir.resolve("sshd.log"))).isTrue();
      assertThat(System.nanoTime()).as("sshd listens").isLessThan(until);
      Thread.sleep(100);
    }
  }

  /** The sshd of the Compose services: keys from {@code run/openssh}, its log emptied for this run. */
  private static void useComposeSshd() throws Exception {
    MinaSecurity.install();
    dir = TestServices.file("run/openssh");
    port = TestServices.port("GW_OPENSSH_PORT");
    assertThat(listening(port)).as("sshd of the test services: scripts/test-services.sh up ssh").isTrue();
    hostKey = new KeyPair(HostKeys.parse(Files.readString(dir.resolve("host_ed25519.pub")).strip()), null);
    userKey = PrivateKeys.parse(Files.readString(dir.resolve("user_ed25519")).toCharArray(), null, "privateKey");
    ca = PrivateKeys.parse(Files.readString(dir.resolve("user_ca")).toCharArray(), null, "privateKey");
    Files.write(dir.resolve("sshd.log"), new byte[0]);
  }

  @AfterAll
  static void stopSshd() {
    if (sshd != null) {
      sshd.descendants().forEach(ProcessHandle::destroy);
      sshd.destroy();
    }
  }

  @BeforeEach
  void connector() {
    connector = new SshConnector();
  }

  @AfterEach
  void close() {
    connector.close();
  }

  private static ObjectNode keyConfig(boolean pinned) {
    ObjectNode c = JsonNodeFactory.instance.objectNode();
    c.putObject("connection").put("host", "127.0.0.1").put("port", port).put("username", USER);
    c.putObject("credentials").put("method", "privateKey");
    if (pinned) {
      c.putObject("hostKeys").putArray("pinned").add(HostKeys.line(hostKey.getPublic()));
    }
    return c;
  }

  private static TestSecrets keySecrets() throws Exception {
    ByteArrayOutputStream pem = new ByteArrayOutputStream();
    OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(userKey, "gw", null, pem);
    return new TestSecrets().with("/credentials/privateKey", pem.toString(StandardCharsets.US_ASCII));
  }

  private ConnectionConfig offer(ObjectNode config, ObjectNode... runbooks) {
    ConnectionConfig cfg = TestConfigs.connection("c1", "dev-vm", Env.DEV, 1, config);
    TestConfigs.offer(connector, TestCtx.devLimits(), List.of(cfg), TestConfigs.contributions("c1", runbooks));
    return cfg;
  }

  private static ToolCall run(String runbook) {
    return new ToolCall("tc_1", SshTools.RUN, "dev-vm",
        JsonNodeFactory.instance.objectNode().put("runbook", runbook), null);
  }

  @Test
  void testConnectionTrustsTheKeyThenReadsWhatTheAccountMayDo() throws Exception {
    TestResult first = connector.test(TestConfigs.connection("c1", "dev-vm", Env.DEV, 1, keyConfig(false)),
        keySecrets(), TestCtx.read());
    TestResult second = connector.test(TestConfigs.connection("c1", "dev-vm", Env.DEV, 2, keyConfig(true)),
        keySecrets(), TestCtx.read());

    assertThat(first.needsConfirmation().data().path("fingerprint").asString())
        .isEqualTo(KeyUtils.getFingerPrint(hostKey.getPublic()));
    assertThat(second.error()).isNull();
    assertThat(second.serverVersion()).isEqualTo("OpenSSH 9.6p1");
    assertThat(second.identity()).isEqualTo("ubuntu@127.0.0.1 · uid 1000");
    assertThat(second.metadataUpdate().path("banner").asString()).startsWith("SSH-2.0-OpenSSH_9.6p1 Ubuntu");
    assertThat(second.metadataUpdate().path("shell").asString()).isIn("bash", "sh", "dash");
  }

  @Test
  void aRunbookRunsWithoutAPtyAndWithStdinClosed() throws Exception {
    ConnectionConfig cfg = offer(keyConfig(true), TestConfigs.runbook("tty", "tty; cat; echo after-cat", true, null));

    ToolResult r = connector.execute(run("tty"), cfg, keySecrets(), TestCtx.read());

    assertThat(r.structuredContent().path("stdout").asString()).isEqualTo("not a tty\nafter-cat\n");
    assertThat(r.structuredContent().path("exitCode").asInt()).isZero();
  }

  @Test
  void aVaultCertificateIsAcceptedByTrustedUserCaKeys() throws Exception {
    ObjectNode config = keyConfig(true);
    config.putObject("credentials").put("method", "vaultSshCa").putObject("vault").put("store", "vault-dev");
    ConnectionConfig cfg = offer(config, TestConfigs.runbook("who", "id -un", true, null));

    ToolResult r = connector.execute(run("who"), cfg, new TestSecrets().ca(ca), TestCtx.read());

    assertThat(r.structuredContent().path("stdout").asString()).isEqualTo("ubuntu\n");
    assertThat(Files.readString(dir.resolve("sshd.log"))).contains("Accepted certificate ID \"gw:dev-vm:tc_test\"");
  }

  @Test
  void theDeadlineTermReachesTheProcessGroupOnTheHost() throws Exception {
    ConnectionConfig cfg = offer(keyConfig(true), TestConfigs.runbook("hang", "sleep 31 & sleep 32; wait", true,
        null));
    Limits limits = TestCtx.limits(Duration.ofSeconds(2), Duration.ofSeconds(10), 256 * 1024, 64 * 1024);

    ToolResult r = connector.execute(run("hang"), cfg, keySecrets(), TestCtx.of(limits));

    assertThat(r.structuredContent().path("timedOut").asBoolean()).isTrue();
    long until = System.nanoTime() + 3_000_000_000L;
    while (sleeping() && System.nanoTime() < until) {
      Thread.sleep(50);
    }
    assertThat(sleeping()).as("sleep 31 and sleep 32 were stopped by TERM").isFalse();
  }

  @Test
  void eightParallelReadsShareOneSession() throws Exception {
    ConnectionConfig cfg = offer(keyConfig(true), TestConfigs.runbook("nap", "sleep 0.5; echo ok", true, null));
    TestSecrets secrets = keySecrets();
    TestConfigs.allowParallel(connector, cfg, 8);

    List<ToolResult> results = new ArrayList<>();
    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<ToolResult>> futures = new ArrayList<>();
      for (int i = 0; i < 8; i++) {
        futures.add(pool.submit(() -> connector.execute(run("nap"), cfg, secrets, TestCtx.read())));
      }
      for (Future<ToolResult> f : futures) {
        results.add(f.get());
      }
    }

    assertThat(results).allSatisfy(r -> assertThat(r.structuredContent().path("stdout").asString())
        .isEqualTo("ok\n"));
  }

  private static boolean sleeping() {
    if (TestServices.enabled()) {
      try {
        TestServices.exec("gw-test-openssh", "pgrep", "-f", "sleep 3[12]");
        return true;
      } catch (IllegalStateException noneLeft) {
        return false; // pgrep exits 1 when nothing matches
      }
    }
    return ProcessHandle.allProcesses().anyMatch(p -> p.info().commandLine()
        .map(c -> c.contains("sleep 31") || c.contains("sleep 32")).orElse(false));
  }

  private static Path download(Path target, String url, String sha256) throws Exception {
    if (Files.exists(target) && sha256(target).equals(sha256)) {
      return target;
    }
    HttpClient http = HttpClient.newBuilder().proxy(proxy()).followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(20)).build();
    Path part = target.resolveSibling(target.getFileName() + ".part");
    HttpResponse<Path> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).build(),
        HttpResponse.BodyHandlers.ofFile(part));
    assertThat(r.statusCode()).as(url).isEqualTo(200);
    assertThat(sha256(part)).as("SHA-256 of " + url).isEqualTo(sha256);
    Files.move(part, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    return target;
  }

  /** {@code http_proxy} of the environment, as curl would use it. */
  private static ProxySelector proxy() {
    String p = System.getenv().getOrDefault("http_proxy", System.getenv("HTTP_PROXY"));
    if (p == null || p.isBlank()) {
      return ProxySelector.getDefault();
    }
    URI u = URI.create(p);
    return ProxySelector.of(new InetSocketAddress(u.getHost(), u.getPort() < 0 ? 80 : u.getPort()));
  }

  private static String sha256(Path file) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
  }

  private static int run(List<String> command) throws IOException, InterruptedException {
    Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
    p.getInputStream().readAllBytes();
    return p.waitFor(60, TimeUnit.SECONDS) ? p.exitValue() : -1;
  }

  private static int freePort() throws IOException {
    try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
      return s.getLocalPort();
    }
  }

  private static boolean listening(int port) {
    try (Socket s = new Socket()) {
      s.connect(new InetSocketAddress("127.0.0.1", port), 200);
      return true;
    } catch (IOException e) {
      return false;
    }
  }
}
