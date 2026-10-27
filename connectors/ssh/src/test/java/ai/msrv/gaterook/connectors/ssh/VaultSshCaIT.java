package ai.msrv.gaterook.connectors.ssh;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.SecretUnavailableException;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.sshd.common.config.keys.OpenSshCertificate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Certificates of a real Vault 2.2.1 SSH secrets engine ({@code -Pvault-it}, report 06 §7.3): Vault in dev mode
 * (downloaded once to {@code ~/.cache/mcp-gateway-test/vault-2.2.1}, checked against HashiCorp's SHA-256), a signing
 * role as the connection form describes it, and the in-process SSH server trusting Vault's CA. The core's Vault store
 * (step S1) answers {@code SecretResolver.sshCertificate} in the product; here the same request
 * ({@code POST /v1/<mount>/sign/<role>}) is made by the test resolver, so what is tested is the connector's side: the
 * request (principal, TTL, key id, extensions), Vault's certificate text and sign-in with it.
 */
@Tag("vault-it")
class VaultSshCaIT {

  private static final String ZIP = "vault_2.2.1_linux_amd64.zip";
  private static final String ZIP_SHA256 = "079435de8ff146bdec4cdbe3be71d672ef4e59e4df6af0f2dba672e71be21a68";
  private static final String ROOT_TOKEN = TestServices.enabled() ? TestServices.value("GW_VAULT_ROOT_TOKEN")
      : "gw-c4-root-" + Long.toHexString(System.nanoTime());
  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  private static Process vault;
  private static String address;
  private static PublicKey vaultCa;

  private final List<AutoCloseable> closeLater = new CopyOnWriteArrayList<>();
  private SshConnector connector;

  @BeforeAll
  static void startVault() throws Exception {
    // Before HostKeys.parse below: MINA picks its key decoders when it first loads, and the other ITs of this JVM
    // need its providers as MinaSecurity sets them.
    MinaSecurity.install();
    if (TestServices.enabled()) {
      // The Vault of the Compose services: the mount an earlier run (or VaultIT of the core) made goes first.
      address = "http://127.0.0.1:" + TestServices.port("GW_VAULT_PORT");
      HttpResponse<String> unmount = vault("DELETE", "/v1/sys/mounts/ssh-client-signer", null);
      assertThat(unmount.statusCode()).as(unmount.body()).isIn(200, 204);
    } else {
      startOwnVault();
    }
    assertThat(vault("POST", "/v1/sys/mounts/ssh-client-signer", "{\"type\":\"ssh\"}").statusCode()).isEqualTo(204);
    HttpResponse<String> ca = vault("POST", "/v1/ssh-client-signer/config/ca",
        "{\"generate_signing_key\":true,\"key_type\":\"ed25519\"}");
    assertThat(ca.statusCode()).as(ca.body()).isEqualTo(200);
    vaultCa = HostKeys.parse(TestConfigs.json(ca.body()).at("/data/public_key").asString().strip());
    // The role of the setup guide: user certificates for this principal only, our key ids, port forwarding for a bastion.
    HttpResponse<String> role = vault("POST", "/v1/ssh-client-signer/roles/gw-runbooks", """
        {"key_type":"ca","allow_user_certificates":true,"allowed_users":"deploy","default_user":"deploy",
         "ttl":"5m","max_ttl":"10m","allow_user_key_ids":true,"allowed_extensions":"permit-port-forwarding",
         "default_extensions":{}}""");
    assertThat(role.statusCode()).as(role.body()).isIn(200, 204);
  }

  /** Vault 2.2.1 of its own in dev mode, on a free loopback port. */
  private static void startOwnVault() throws Exception {
    Path binary = binary();
    Path home = Files.createDirectories(Path.of("target", "vault-it").toAbsolutePath());
    int port;
    try (ServerSocket s = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
      port = s.getLocalPort();
    }
    address = "http://127.0.0.1:" + port;
    ProcessBuilder pb = new ProcessBuilder(binary.toString(), "server", "-dev", "-dev-root-token-id=" + ROOT_TOKEN,
        "-dev-listen-address=127.0.0.1:" + port).redirectErrorStream(true)
        .redirectOutput(home.resolve("vault.log").toFile());
    pb.environment().put("HOME", home.toString()); // the dev server writes ~/.vault-token
    vault = pb.start();
    long until = System.nanoTime() + 30_000_000_000L;
    while (true) {
      try {
        if (vault("GET", "/v1/sys/health", null).statusCode() == 200) {
          break;
        }
      } catch (IOException notYet) {
        // Not listening yet.
      }
      assertThat(vault.isAlive()).as(Files.readString(home.resolve("vault.log"))).isTrue();
      assertThat(System.nanoTime()).as("Vault answers").isLessThan(until);
      Thread.sleep(200);
    }
  }

  @AfterAll
  static void stopVault() {
    if (vault != null) {
      vault.destroy();
    }
  }

  @BeforeEach
  void setUp() {
    MinaSecurity.install();
    connector = new SshConnector();
  }

  @AfterEach
  void tearDown() throws Exception {
    connector.close();
    for (AutoCloseable c : closeLater) {
      c.close();
    }
  }

  private TestSshd server(boolean forwarding) throws Exception {
    TestSshd s = TestSshd.start(forwarding);
    s.userCa = vaultCa;
    closeLater.add(s);
    return s;
  }

  @Test
  void aVaultCertificateSignsInForOneCall() throws Exception {
    TestSshd sshd = server(false);
    ObjectNode config = TestConfigs.password(sshd.port(), sshd.hostKeyLine());
    config.putObject("credentials").put("method", "vaultSshCa").putObject("vault").put("store", "vault-dev")
        .put("ttl", "2m");
    ConnectionConfig cfg = TestConfigs.connection("c1", "dev-vm", Env.DEV, 1, config);
    TestConfigs.offer(connector, TestCtx.devLimits(), List.of(cfg), TestConfigs.contributions("c1",
        TestConfigs.runbook("uptime", "echo up", true, null)));
    VaultSigner signer = new VaultSigner();

    ToolResult r = connector.execute(run(), cfg, signer, TestCtx.read());

    assertThat(r.structuredContent().path("stdout").asString()).isEqualTo("up\n");
    OpenSshCertificate cert = sshd.certificates.getFirst();
    assertThat(cert.getId()).isEqualTo("gw:dev-vm:tc_test");
    assertThat(cert.getPrincipals()).containsExactly("deploy");
    assertThat(cert.getExtensions()).isEmpty();
    long ttl = cert.getValidBefore() - Instant.now().getEpochSecond();
    assertThat(ttl).isBetween(60L, 125L);
    assertThat(signer.serials).hasSize(1);
  }

  @Test
  void theBastionsCertificateMayForwardAndTheHostsMayNot() throws Exception {
    TestSshd sshd = server(false);
    TestSshd bastion = server(true);
    ObjectNode config = TestConfigs.password(sshd.port(), sshd.hostKeyLine());
    config.putObject("credentials").put("method", "vaultSshCa").putObject("vault").put("store", "vault-dev");
    config.putObject("jump").put("host", "127.0.0.1").put("port", bastion.port()).putObject("hostKeys")
        .putArray("pinned").add(bastion.hostKeyLine());
    ConnectionConfig cfg = TestConfigs.connection("c1", "dev-vm", Env.DEV, 1, config);
    TestConfigs.offer(connector, TestCtx.devLimits(), List.of(cfg), TestConfigs.contributions("c1",
        TestConfigs.runbook("uptime", "echo up", true, null)));

    ToolResult r = connector.execute(run(), cfg, new VaultSigner(), TestCtx.read());

    assertThat(r.structuredContent().path("stdout").asString()).isEqualTo("up\n");
    assertThat(bastion.certificates.getFirst().getExtensions()).extracting(OpenSshCertificate.CertificateOption::getName)
        .containsExactly("permit-port-forwarding");
    assertThat(sshd.certificates.getFirst().getExtensions()).isEmpty();
  }

  private static ToolCall run() {
    return new ToolCall("tc_1", SshTools.RUN, "dev-vm", JsonNodeFactory.instance.objectNode().put("runbook", "uptime"),
        null);
  }

  /** What the core's Vault store does for {@code sshCertificate}: one {@code sign} request of the role. */
  private static final class VaultSigner implements SecretResolver {
    final List<String> serials = new CopyOnWriteArrayList<>();

    @Override
    public SshCertificate sshCertificate(String field, PublicKey key, SshCertRequest request)
        throws SecretUnavailableException {
      ObjectNode body = JsonNodeFactory.instance.objectNode();
      body.put("public_key", HostKeys.line(key));
      body.put("valid_principals", request.principal());
      body.put("ttl", request.ttl().toSeconds() + "s");
      body.put("key_id", request.keyId());
      body.put("cert_type", "user");
      ObjectNode extensions = body.putObject("extensions");
      request.extensions().forEach(extensions::put);
      try {
        HttpResponse<String> r = vault("POST", "/v1/ssh-client-signer/sign/gw-runbooks", body.toString());
        if (r.statusCode() != 200) {
          throw new SecretUnavailableException("Vault refused to sign: " + r.body(), null);
        }
        JsonNode data = TestConfigs.json(r.body()).path("data");
        serials.add(data.path("serial_number").asString());
        return new SshCertificate(data.path("signed_key").asString(), data.path("serial_number").asString(), null);
      } catch (IOException | InterruptedException e) {
        throw new SecretUnavailableException("Vault is unreachable: " + e, e);
      }
    }

    @Override
    public Secret secret(String field) throws SecretUnavailableException {
      throw new SecretUnavailableException("only certificates here", null);
    }

    @Override
    public Login login(String field) throws SecretUnavailableException {
      throw new SecretUnavailableException("only certificates here", null);
    }

    @Override
    public boolean isSet(String field) {
      return false;
    }

    @Override
    public Meta meta(String field) {
      return new Meta("reference", null, null);
    }
  }

  private static HttpResponse<String> vault(String method, String path, String body)
      throws IOException, InterruptedException {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(address + path)).timeout(Duration.ofSeconds(10))
        .header("X-Vault-Token", ROOT_TOKEN)
        .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  /** The {@code vault} binary from the cached, checked release zip. */
  private static Path binary() throws Exception {
    Path cache = Files.createDirectories(Path.of(System.getProperty("user.home"), ".cache", "mcp-gateway-test",
        "vault-2.2.1"));
    Path zip = cache.resolve(ZIP);
    if (!Files.exists(zip) || !sha256(zip).equals(ZIP_SHA256)) {
      HttpClient http = HttpClient.newBuilder().proxy(ProxySelector.getDefault())
          .followRedirects(HttpClient.Redirect.NORMAL).build();
      Path part = cache.resolve(ZIP + ".part");
      HttpResponse<Path> r = http.send(HttpRequest.newBuilder(URI.create(
          "https://releases.hashicorp.com/vault/2.2.1/" + ZIP)).timeout(Duration.ofMinutes(10)).build(),
          HttpResponse.BodyHandlers.ofFile(part));
      assertThat(r.statusCode()).isEqualTo(200);
      assertThat(sha256(part)).as("SHA-256 of " + ZIP).isEqualTo(ZIP_SHA256);
      Files.move(part, zip, StandardCopyOption.REPLACE_EXISTING);
    }
    Path binary = cache.resolve("vault");
    if (!Files.isExecutable(binary)) {
      try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
        Optional<Path> written = Optional.empty();
        for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
          if (e.getName().equals("vault")) {
            Path part = cache.resolve("vault.part");
            Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
            Files.setPosixFilePermissions(part, PosixFilePermissions.fromString("rwxr-xr-x"));
            Files.move(part, binary, StandardCopyOption.REPLACE_EXISTING);
            written = Optional.of(binary);
          }
        }
        assertThat(written).as("vault in " + ZIP).isPresent();
      }
    }
    return binary;
  }

  private static String sha256(Path file) throws Exception {
    MessageDigest d = MessageDigest.getInstance("SHA-256");
    try (InputStream in = Files.newInputStream(file)) {
      byte[] buf = new byte[1 << 16];
      for (int n; (n = in.read(buf)) > 0; ) {
        d.update(buf, 0, n);
      }
    }
    return HexFormat.of().formatHex(d.digest());
  }
}
