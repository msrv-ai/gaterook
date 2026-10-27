package ai.msrv.gaterook.e2e.redis;

import ai.msrv.gaterook.e2e.TestServices;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.RedisProtocol;

/**
 * The machine's {@code redis-server} ({@code $GW_REDIS_BIN} or {@code PATH}; 7.0.15 here) for the end-to-end test:
 * loopback, ephemeral ports, no persistence, data under {@code target/}, TLS on a second port with a certificate for
 * {@code localhost} from a private CA made with openssl. The connector module's ITs have the full fixture; this one
 * has what the core's pipeline needs: the users {@code default} (everything) and {@code ro} (the read-only line of
 * Test connection's hint), with passwords the test chooses. With the Compose services ({@link TestServices}) the
 * server is their primary (the CA of {@code run/certs}): {@code ro} gets the chosen password for the run, the default
 * user keeps its own, and closing reloads the users of the ACL file.
 */
final class LocalRedisServer implements AutoCloseable {

  final Path dir;
  final int port;
  final int tlsPort;
  final String caPem;
  private final String adminPassword;
  private final Process process;

  private LocalRedisServer(Path dir, int port, int tlsPort, String caPem, String adminPassword, Process process) {
    this.dir = dir;
    this.port = port;
    this.tlsPort = tlsPort;
    this.caPem = caPem;
    this.adminPassword = adminPassword;
    this.process = process;
  }

  /** The binary, or {@code null} when this machine has none (the test is skipped); the Compose services need none. */
  static Path binary() {
    if (TestServices.enabled()) {
      return Path.of("compose");
    }
    String env = System.getenv("GW_REDIS_BIN");
    if (env != null && !env.isBlank()) {
      return Path.of(env);
    }
    for (String d : System.getenv().getOrDefault("PATH", "/usr/bin").split(":")) {
      Path p = Path.of(d, "redis-server");
      if (Files.isExecutable(p)) {
        return p;
      }
    }
    return null;
  }

  static LocalRedisServer start(Path binary, String adminPassword, String roPassword) throws Exception {
    if (TestServices.enabled()) {
      LocalRedisServer r = new LocalRedisServer(null, TestServices.port("GW_REDIS_PORT"),
          TestServices.port("GW_REDIS_TLS_PORT"), Files.readString(TestServices.file("run/certs/ca.crt")),
          "default-pass", null);
      try (Jedis j = r.admin()) {
        j.aclLoad();
        j.aclSetUser("ro", "resetpass", ">" + roPassword);
        j.flushAll();
      }
      return r;
    }
    Path dir = Path.of("target", "e2e-redis-" + UUID.randomUUID().toString().substring(0, 8)).toAbsolutePath();
    Files.createDirectories(dir);
    run(dir, "openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", "ca.key", "-out", "ca.crt",
        "-days", "2", "-subj", "/CN=gw-e2e-redis-ca");
    run(dir, "openssl", "req", "-newkey", "rsa:2048", "-nodes", "-keyout", "server.key", "-out", "server.csr",
        "-subj", "/CN=localhost");
    Files.writeString(dir.resolve("ext.cnf"), "subjectAltName=DNS:localhost\n");
    run(dir, "openssl", "x509", "-req", "-in", "server.csr", "-CA", "ca.crt", "-CAkey", "ca.key", "-CAcreateserial",
        "-out", "server.crt", "-days", "2", "-extfile", "ext.cnf");
    Files.writeString(dir.resolve("users.acl"), String.join("\n",
        "user default on >" + adminPassword + " ~* &* +@all",
        "user ro on >" + roPassword + " ~* resetchannels -@all +@read +@connection -@dangerous -dump -xread -lolwut",
        ""));
    int port = freePort();
    int tlsPort = freePort();
    Process p = new ProcessBuilder(List.of(binary.toString(), "--bind", "127.0.0.1", "--port", Integer.toString(port),
        "--tls-port", Integer.toString(tlsPort), "--tls-cert-file", dir.resolve("server.crt").toString(),
        "--tls-key-file", dir.resolve("server.key").toString(), "--tls-ca-cert-file", dir.resolve("ca.crt").toString(),
        "--tls-auth-clients", "no", "--save", "", "--appendonly", "no", "--dir", dir.toString(), "--logfile",
        dir.resolve("redis.log").toString(), "--aclfile", dir.resolve("users.acl").toString(), "--protected-mode",
        "no")).redirectErrorStream(true).redirectOutput(dir.resolve("stdout.log").toFile()).start();
    LocalRedisServer r = new LocalRedisServer(dir, port, tlsPort, Files.readString(dir.resolve("ca.crt")),
        adminPassword, p);
    long until = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (System.nanoTime() < until) {
      if (!p.isAlive()) {
        throw new IllegalStateException("redis-server exited: " + Files.readString(dir.resolve("stdout.log")));
      }
      if (r.answers()) {
        return r;
      }
      Thread.sleep(25);
    }
    r.close();
    throw new IllegalStateException("redis-server didn't start in 15 s");
  }

  /** A client of the test itself (seeding, checks), as the default user. */
  Jedis admin() {
    Jedis j = new Jedis(new HostAndPort("127.0.0.1", port), DefaultJedisClientConfig.builder()
        .password(adminPassword).socketTimeoutMillis(10_000).protocol(RedisProtocol.RESP2).build());
    j.connect();
    return j;
  }

  private boolean answers() {
    try (Socket s = new Socket()) {
      s.connect(new InetSocketAddress("127.0.0.1", port), 200);
      s.setSoTimeout(500);
      s.getOutputStream().write("PING\r\n".getBytes(StandardCharsets.US_ASCII));
      byte[] buf = new byte[16];
      return s.getInputStream().read(buf) > 0;
    } catch (IOException e) {
      return false;
    }
  }

  private static int freePort() throws IOException {
    try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return s.getLocalPort();
    }
  }

  private static void run(Path dir, String... cmd) throws IOException, InterruptedException {
    Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
      throw new IOException(String.join(" ", cmd) + " failed: " + out);
    }
  }

  @Override
  public void close() {
    if (process == null) {
      try (Jedis j = admin()) {
        j.aclLoad(); // the Compose service keeps running, with the users of its ACL file
      }
      return;
    }
    process.destroy();
    try {
      if (!process.waitFor(5, TimeUnit.SECONDS)) {
        process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
