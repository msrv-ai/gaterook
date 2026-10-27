package ai.msrv.gaterook.core.secrets.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.core.connections.ConnectionConfigs;
import ai.msrv.gaterook.core.connections.ConnectionService;
import ai.msrv.gaterook.core.connections.SecretInput;
import ai.msrv.gaterook.core.platform.Taint;
import ai.msrv.gaterook.core.secrets.ScopedSecretResolver;
import ai.msrv.gaterook.core.secrets.SecretService;
import ai.msrv.gaterook.core.secrets.SecretStores;
import ai.msrv.gaterook.core.secrets.store.vault.VaultSettings;
import ai.msrv.gaterook.core.secrets.store.vault.VaultStore;
import ai.msrv.gaterook.testfixtures.TestCore;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Vault client against a real Vault 2.2.1 dev server and PostgreSQL 16 (report 12 §6, §8; {@code -Pvault-it}):
 * KV v2 versions (destroyed, deleted, current), KV v1, the 403 of a path the policy leaves out, re-sign-in after the
 * gateway's token is revoked, database credentials a real PostgreSQL accepts and drops when the lease is revoked, an
 * SSH certificate from Vault's CA. Vault is the release zip cached under {@code ~/.cache/mcp-gateway-test/vault-2.2.1}
 * (SHA-256 checked, downloaded when missing); PostgreSQL is the machine's {@code /usr/lib/postgresql/16/bin}, run as
 * {@code postgres} when the tests run as root. Data lives under {@code target/}. With the Compose services
 * ({@link TestServices}) both are theirs: Vault in dev mode with the root token of {@code .env}, emptied of the mounts
 * and the AppRole an earlier run made, and their PostgreSQL, which Vault reaches as {@code postgres:5432}.
 */
@Tag("vault-it")
class VaultIT {

  private static final String VERSION = "2.2.1";
  private static final String ZIP_SHA256 = "079435de8ff146bdec4cdbe3be71d672ef4e59e4df6af0f2dba672e71be21a68";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final boolean COMPOSE = TestServices.enabled();
  private static final String PG_PASSWORD = COMPOSE ? TestServices.value("GW_PG_PASSWORD")
      : "cnry-pg-super-" + UUID.randomUUID().toString().substring(0, 8);
  private static final String ROOT_TOKEN = COMPOSE ? TestServices.value("GW_VAULT_ROOT_TOKEN")
      : "cnry-root-" + UUID.randomUUID();
  private static final String KV_PASSWORD = "cnry-kv-v3-" + UUID.randomUUID().toString().substring(0, 8);

  private static Path dir;
  private static Process vaultProcess;
  private static Path pgData;
  private static Path pgBin;
  private static int pgPort;
  /** PostgreSQL as Vault's database engine dials it. */
  private static String pgFromVault;
  private static String address;
  private static String roleId;
  private static String secretId;
  private static final HttpClient HTTP = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

  @BeforeAll
  static void start() throws Exception {
    if (COMPOSE) {
      address = "http://127.0.0.1:" + TestServices.port("GW_VAULT_PORT");
      pgPort = TestServices.port("GW_PG_PORT");
      pgFromVault = "postgres:5432";
      awaitVault();
      // Unmounting revokes the engine's leases: the database users of an earlier run go with it.
      for (String mount : List.of("kv", "kv1", "database", "ssh-client-signer")) {
        root("DELETE", "sys/mounts/" + mount, null);
      }
      root("DELETE", "sys/auth/approle", null);
      configure();
      return;
    }
    pgBin = Path.of(System.getenv().getOrDefault("GW_PG_BIN", "/usr/lib/postgresql/16/bin"));
    Assumptions.assumeTrue(Files.isExecutable(pgBin.resolve("initdb")), "PostgreSQL 16 binaries are needed");
    Path vault = vaultBinary();
    dir = Files.createDirectories(Path.of("target", "vault-it-" + UUID.randomUUID().toString().substring(0, 8))
        .toAbsolutePath());
    startPostgres();
    pgFromVault = "127.0.0.1:" + pgPort;

    int port = freePort();
    address = "http://127.0.0.1:" + port;
    ProcessBuilder pb = new ProcessBuilder(vault.toString(), "server", "-dev", "-dev-root-token-id=" + ROOT_TOKEN,
        "-dev-listen-address=127.0.0.1:" + port, "-dev-no-store-token")
        .redirectErrorStream(true).redirectOutput(dir.resolve("vault.log").toFile());
    pb.environment().put("HOME", dir.toString());
    pb.environment().remove("VAULT_ADDR");
    pb.environment().remove("VAULT_TOKEN");
    vaultProcess = pb.start();
    awaitVault();
    configure();
  }

  @AfterAll
  static void stop() throws Exception {
    if (vaultProcess != null) {
      vaultProcess.destroy();
      vaultProcess.waitFor(10, TimeUnit.SECONDS);
    }
    if (pgData != null) {
      run(List.of(pgBin.resolve("pg_ctl").toString(), "-D", pgData.toString(), "-m", "immediate", "stop"), true);
    }
  }

  // ── Setup ───────────────────────────────────────────────────────────────────────────────────────────────────

  /** The release binary, from the cache or downloaded into it; the zip's SHA-256 is checked either way. */
  private static Path vaultBinary() throws Exception {
    Path cache = Path.of(System.getProperty("user.home"), ".cache", "mcp-gateway-test", "vault-" + VERSION);
    Path zip = cache.resolve("vault_" + VERSION + "_linux_amd64.zip");
    Path binary = cache.resolve("vault");
    Files.createDirectories(cache);
    if (!Files.exists(zip)) {
      HttpClient web = HttpClient.newBuilder().proxy(ProxySelector.getDefault())
          .followRedirects(HttpClient.Redirect.NORMAL).build();
      Path part = cache.resolve(zip.getFileName() + ".part");
      HttpResponse<Path> r = web.send(HttpRequest.newBuilder(URI.create("https://releases.hashicorp.com/vault/"
          + VERSION + "/vault_" + VERSION + "_linux_amd64.zip")).build(), HttpResponse.BodyHandlers.ofFile(part));
      Assumptions.assumeTrue(r.statusCode() == 200, "Vault " + VERSION + " can't be downloaded");
      Files.move(part, zip, StandardCopyOption.REPLACE_EXISTING);
    }
    String sha;
    try (InputStream in = Files.newInputStream(zip)) {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] buf = new byte[1 << 16];
      for (int n; (n = in.read(buf)) > 0; ) {
        md.update(buf, 0, n);
      }
      sha = HexFormat.of().formatHex(md.digest());
    }
    if (!sha.equals(ZIP_SHA256)) {
      throw new IllegalStateException(zip + " has SHA-256 " + sha + ", expected " + ZIP_SHA256 + ": delete it");
    }
    if (!Files.isExecutable(binary)) {
      try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
        for (var e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
          if (e.getName().equals("vault")) {
            Path part = cache.resolve("vault.part");
            Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
            part.toFile().setExecutable(true);
            Files.move(part, binary, StandardCopyOption.REPLACE_EXISTING);
          }
        }
      }
    }
    return binary;
  }

  private static void startPostgres() throws Exception {
    boolean root = "root".equals(System.getProperty("user.name"));
    Path pg = Files.createDirectories(dir.resolve("pg"));
    Files.writeString(pg.resolve("pwfile"), PG_PASSWORD + "\n");
    if (root) {
      run(List.of("chown", "-R", "postgres:postgres", pg.toString()), false);
    }
    pgData = pg.resolve("data");
    pgPort = freePort();
    run(List.of(pgBin.resolve("initdb").toString(), "-D", pgData.toString(), "-U", "postgres", "--auth-local=trust",
        "--auth-host=scram-sha-256", "--pwfile=" + pg.resolve("pwfile"), "--no-sync", "-E", "UTF8",
        "--locale=C.UTF-8"), true);
    run(List.of(pgBin.resolve("pg_ctl").toString(), "-D", pgData.toString(), "-o", "-p " + pgPort + " -k " + pg
        + " -c listen_addresses=127.0.0.1 -c fsync=off", "-l", pg.resolve("log").toString(), "-w", "-t", "60",
        "start"), true);
  }

  /** Runs a command (as {@code postgres} when asked and running as root); fails on a non-zero exit. */
  private static void run(List<String> command, boolean asPostgres) throws IOException, InterruptedException {
    List<String> full = new ArrayList<>();
    if (asPostgres && "root".equals(System.getProperty("user.name"))) {
      full.addAll(List.of("runuser", "-u", "postgres", "--"));
    }
    full.addAll(command);
    Process p = new ProcessBuilder(full).redirectErrorStream(true).start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (!p.waitFor(90, TimeUnit.SECONDS) || p.exitValue() != 0) {
      throw new IllegalStateException(String.join(" ", command) + " failed: " + out);
    }
  }

  /** {@code psql -c select current_user} with a login; the exit status says whether PostgreSQL let it in. */
  private static boolean canLogIn(String user, char[] password) throws Exception {
    if (COMPOSE) {
      try {
        return TestServices.exec("gw-test-postgres", "env", "PGPASSWORD=" + new String(password), "PGCONNECT_TIMEOUT=5",
            "psql", "-h", "127.0.0.1", "-U", user, "-d", "postgres", "-tAc", "select current_user").strip()
            .equals(user);
      } catch (IllegalStateException refused) {
        return false;
      }
    }
    ProcessBuilder pb = new ProcessBuilder("psql", "-h", "127.0.0.1", "-p", String.valueOf(pgPort), "-U", user,
        "-d", "postgres", "-tAc", "select current_user").redirectErrorStream(true);
    pb.environment().put("PGPASSWORD", new String(password));
    pb.environment().put("PGCONNECT_TIMEOUT", "5");
    Process p = pb.start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    p.waitFor(20, TimeUnit.SECONDS);
    return p.exitValue() == 0 && out.strip().equals(user);
  }

  private static int freePort() throws IOException {
    try (ServerSocket s = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
      return s.getLocalPort();
    }
  }

  private static void awaitVault() throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    while (true) {
      try {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(address + "/v1/sys/health")).build(),
            HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 200) {
          return;
        }
      } catch (IOException e) {
        // Not listening yet.
      }
      if (System.nanoTime() > deadline || vaultProcess != null && !vaultProcess.isAlive()) {
        throw new IllegalStateException("Vault didn't start: " + Files.readString(dir.resolve("vault.log")));
      }
      Thread.sleep(200);
    }
  }

  private static JsonNode root(String method, String path, String body) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(address + "/v1/" + path))
        .header("X-Vault-Token", ROOT_TOKEN)
        .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    if (r.statusCode() >= 300) {
      throw new IllegalStateException(method + " " + path + ": " + r.statusCode() + " " + r.body());
    }
    return r.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(r.body());
  }

  private static void configure() throws Exception {
    root("POST", "sys/mounts/kv", "{\"type\":\"kv\",\"options\":{\"version\":\"2\"}}");
    for (String v : List.of("cnry-kv-v1-pw", "cnry-kv-v2-pw", KV_PASSWORD)) {
      root("POST", "kv/data/stage/db", "{\"data\":{\"user\":\"app\",\"password\":\"" + v + "\"}}");
    }
    root("POST", "kv/destroy/stage/db", "{\"versions\":[1]}");
    root("POST", "kv/delete/stage/db", "{\"versions\":[2]}");
    root("POST", "kv/data/prod/db", "{\"data\":{\"password\":\"cnry-prod-never\"}}");
    root("POST", "sys/mounts/kv1", "{\"type\":\"kv\",\"options\":{\"version\":\"1\"}}");
    root("POST", "kv1/app", "{\"api_key\":\"cnry-kv1-api-key\"}");

    root("POST", "sys/mounts/database", "{\"type\":\"database\"}");
    root("POST", "database/config/pg", JSON.writeValueAsString(Map.of("plugin_name", "postgresql-database-plugin",
        "connection_url", "postgresql://{{username}}:{{password}}@" + pgFromVault + "/postgres?sslmode=disable",
        "allowed_roles", "prod-replica-readonly", "username", "postgres", "password", PG_PASSWORD)));
    root("POST", "database/roles/prod-replica-readonly", JSON.writeValueAsString(Map.of("db_name", "pg",
        "creation_statements", List.of("CREATE ROLE \"{{name}}\" WITH LOGIN PASSWORD '{{password}}' VALID UNTIL "
            + "'{{expiration}}';", "GRANT pg_read_all_data TO \"{{name}}\";"), "default_ttl", "1h", "max_ttl",
        "24h")));

    root("POST", "sys/mounts/ssh-client-signer", "{\"type\":\"ssh\"}");
    root("POST", "ssh-client-signer/config/ca", "{\"generate_signing_key\":true,\"key_type\":\"ed25519\"}");
    root("POST", "ssh-client-signer/roles/gw-runbooks", "{\"key_type\":\"ca\",\"allow_user_certificates\":true,"
        + "\"allowed_users\":\"deploy\",\"default_user\":\"deploy\",\"ttl\":\"5m\",\"max_ttl\":\"30m\","
        + "\"allowed_extensions\":\"permit-pty\",\"default_extensions\":{\"permit-pty\":\"\"}}");

    // The gateway's own policy: read the references, issue and sign, renew and revoke its leases by path.
    String policy = """
        path "kv/data/stage/*" { capabilities = ["read"] }
        path "kv1/*" { capabilities = ["read"] }
        path "database/creds/prod-replica-readonly" { capabilities = ["read"] }
        path "ssh-client-signer/sign/gw-runbooks" { capabilities = ["update"] }
        path "sys/leases/renew" { capabilities = ["update"] }
        path "sys/leases/revoke/database/creds/prod-replica-readonly/*" { capabilities = ["update"] }
        """;
    root("PUT", "sys/policies/acl/gaterook", JSON.writeValueAsString(Map.of("policy", policy)));
    root("POST", "sys/auth/approle", "{\"type\":\"approle\"}");
    root("POST", "auth/approle/role/gaterook", "{\"token_policies\":[\"gaterook\"],\"token_ttl\":\"1h\","
        + "\"token_max_ttl\":\"4h\"}");
    roleId = root("GET", "auth/approle/role/gaterook/role-id", null).path("data").path("role_id").asString();
    secretId = root("POST", "auth/approle/role/gaterook/secret-id", null).path("data").path("secret_id")
        .asString();
  }

  private static VaultStore store() {
    return new VaultStore(new VaultSettings(URI.create(address), null, null, VaultSettings.Auth.APPROLE, "approle",
        roleId, null, Duration.ofHours(1), 60, false), f -> secretId.toCharArray(), Clock.systemUTC());
  }

  private static StoreCall call(List<String> taint) {
    return StoreCall.within(v -> taint.add(v.toString()), Duration.ofSeconds(10), Clock.systemUTC());
  }

  // ── Tests ───────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void readsKvVersionsAndExplainsDestroyedDeletedAndDenied() throws Exception {
    List<String> taint = new ArrayList<>();
    try (VaultStore vault = store()) {
      try (RemoteValue v = vault.read(SecretRef.parse("vault://kv/stage/db#password"), call(taint))) {
        assertThat(new String(v.value(), StandardCharsets.UTF_8)).isEqualTo(KV_PASSWORD);
        assertThat(v.version()).isEqualTo("3");
        assertThat(v.keys()).containsExactlyInAnyOrder("user", "password");
        assertThat(v.created()).isNotNull();
      }
      assertThatThrownBy(() -> vault.read(SecretRef.parse("vault://kv/stage/db?version=1#password"), call(taint)))
          .hasMessage("Version 1 of kv/stage/db is destroyed.");
      assertThatThrownBy(() -> vault.read(SecretRef.parse("vault://kv/stage/db?version=2#password"), call(taint)))
          .hasMessage("Version 2 of kv/stage/db is deleted.");
      assertThatThrownBy(() -> vault.read(SecretRef.parse("vault://kv/stage/nothing#password"), call(taint)))
          .hasMessage("No secret at kv/stage/nothing.");
      assertThatThrownBy(() -> vault.read(SecretRef.parse("vault://kv/prod/db#password"), call(taint)))
          .isInstanceOfSatisfying(StoreException.class,
              e -> assertThat(e.reason()).isEqualTo(StoreException.Reason.DENIED))
          .hasMessage("The gateway's token can't read kv/data/prod/db.");
      try (RemoteValue v = vault.read(SecretRef.parse("vault://kv1/app#api_key"), call(taint))) {
        assertThat(new String(v.value(), StandardCharsets.UTF_8)).isEqualTo("cnry-kv1-api-key");
        assertThat(v.version()).isNull();
      }
      SecretStores.TestResult test = vault.test(List.of(SecretRef.parse("vault://kv/stage/db#password")),
          call(taint));
      assertThat(test.ok()).as(test.toString()).isTrue();
      assertThat(test.server().path("version").asString()).isEqualTo(VERSION);
      assertThat(test.identity().path("policies").toString()).contains("gaterook");
      assertThat(test.permissions().getFirst().capabilities()).containsExactly("read");
      assertThat(test.hints()).isEmpty();
    }
    assertThat(taint).anyMatch(t -> t.startsWith("hvs."));
  }

  @Test
  void aRevokedTokenIsReplacedBySigningInAgain() throws Exception {
    List<String> taint = new ArrayList<>();
    try (VaultStore vault = store()) {
      vault.read(SecretRef.parse("vault://kv/stage/db#password"), call(taint)).close();
      String token = taint.stream().filter(t -> t.startsWith("hvs.")).findFirst().orElseThrow();
      root("POST", "auth/token/revoke", JSON.writeValueAsString(Map.of("token", token)));
      try (RemoteValue v = vault.read(SecretRef.parse("vault://kv/stage/db#user"), call(taint))) {
        assertThat(new String(v.value(), StandardCharsets.UTF_8)).isEqualTo("app");
      }
      assertThat(taint.stream().filter(t -> t.startsWith("hvs.")).distinct().count()).isEqualTo(2);
    }
  }

  @Test
  void dynamicCredentialsLogInToPostgresAndAreDroppedWhenRevoked() throws Exception {
    try (TestCore core = TestCore.start("vault-it")) {
      SecretStores stores = core.bean(SecretStores.class);
      ObjectNode config = JSON.createObjectNode().put("address", address);
      config.putObject("auth").put("method", "approle").put("roleId", roleId);
      stores.create("vault", "vault", config, Map.of(VaultSettings.SECRET_ID, secretId.toCharArray()), "vault-it");
      core.bean(ConnectionService.class).create("prod-replica", "pgfake", Env.PROD, TestCore.config("db.test"),
          Map.of("/credentials/password", SecretInput.dynamic("vault://database/creds/prod-replica-readonly", null)),
          "vault-it");
      ConnectionConfig cfg = core.bean(ConnectionConfigs.class).byName("prod-replica").orElseThrow().config();

      String user;
      char[] password;
      try (ScopedSecretResolver r = core.bean(SecretService.class).resolver(cfg, new Taint());
          SecretResolver.Login login = r.login("/credentials/password")) {
        user = login.username();
        password = login.password().chars().clone();
        assertThat(user).startsWith("v-approle-prod-rep");
        assertThat(login.notAfter()).isAfter(Instant.now().plus(Duration.ofMinutes(50)));
      }
      assertThat(canLogIn(user, password)).as("PostgreSQL accepts the leased user").isTrue();
      assertThat(stores.issued()).anySatisfy(i -> assertThat(i.mechanism()).isEqualTo("Vault dynamic database user"));

      core.bean(ConnectionService.class).delete("prod-replica", "prod-replica", "vault-it");
      TestCore.await(() -> {
        try {
          return !canLogIn(user, password);
        } catch (Exception e) {
          return false;
        }
      }, "the leased user dropped by Vault");
      java.util.Arrays.fill(password, '\0');
    }
  }

  @Test
  void vaultsCaSignsAKeyForThePrincipal() throws Exception {
    PublicKey key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic();
    try (VaultStore vault = store()) {
      VaultStore.Signed cert = vault.sign("ssh-client-signer", "gw-runbooks", key, "deploy", Duration.ofMinutes(5),
          "gw-call-7", Map.of("permit-pty", ""), call(new ArrayList<>()));
      String[] parts = cert.openSsh().split(" ");
      assertThat(parts[0]).isEqualTo("ssh-ed25519-cert-v01@openssh.com");
      ByteBuffer b = ByteBuffer.wrap(Base64.getDecoder().decode(parts[1]));
      assertThat(string(b)).isEqualTo("ssh-ed25519-cert-v01@openssh.com");
      bytes(b); // nonce
      byte[] signed = bytes(b);
      byte[] spki = key.getEncoded();
      assertThat(signed).isEqualTo(java.util.Arrays.copyOfRange(spki, spki.length - 32, spki.length));
      b.getLong(); // serial
      assertThat(b.getInt()).as("user certificate").isEqualTo(1);
      bytes(b); // key id: the role decides whether ours is kept
      ByteBuffer principals = ByteBuffer.wrap(bytes(b));
      assertThat(string(principals)).isEqualTo("deploy");
      long after = b.getLong();
      long before = b.getLong();
      assertThat(before - after).isBetween(240L, 330L);
    }
  }

  private static byte[] bytes(ByteBuffer b) {
    byte[] out = new byte[b.getInt()];
    b.get(out);
    return out;
  }

  private static String string(ByteBuffer b) {
    return new String(bytes(b), StandardCharsets.UTF_8);
  }
}
