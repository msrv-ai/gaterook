package ai.msrv.gaterook.connectors.redis;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.RedisProtocol;

/**
 * A real {@code redis-server} for one test class (report 05 §8): on a free loopback port, data and log under
 * {@code target/redis-it/}, no persistence, users from an ACL file. The binary is {@code $GW_REDIS_BIN}, else
 * {@code redis-server} from {@code PATH} (7.0.15 on this machine); {@link #redis62()} is Redis 6.2.11, the Linux binary
 * inside {@code com.github.codemonstur:embedded-redis} 1.4.4, downloaded once into the shared test cache and checked
 * by SHA-256.
 *
 * <p>Users — passwords are {@code <name>-pass}: {@code default} (everything), {@code ro} (the read-only line of
 * Test connection's hint), {@code rokeys} (the same, keys {@code cache:*} only), {@code rw} (everything but
 * {@code @dangerous}).
 *
 * <p>With the Compose services ({@link TestServices}) a start takes one of their servers instead — the primary with
 * TLS, its replica, the open default user, the cluster node or 6.2 — with the same users ({@code test-services/redis})
 * and certificates ({@code run/certs}), and makes it look fresh: {@code ACL LOAD}, an empty {@code ACL LOG},
 * {@code FLUSHALL}.
 */
final class LocalRedis implements AutoCloseable {

  static final String DEFAULT_PASSWORD = "default-pass";
  private static final AtomicInteger SEQUENCE = new AtomicInteger();
  private static final Path CACHE = Path.of(System.getProperty("user.home"), ".cache", "mcp-gateway-test", "redis");
  private static final String JAR = "embedded-redis-1.4.4.jar";
  private static final String JAR_URL = "https://repo1.maven.org/maven2/com/github/codemonstur/embedded-redis/1.4.4/"
      + JAR;
  private static final String JAR_SHA256 = "b56845f0a81aa0b6a0c32a309e69081ee7bdeadba9efff6374b0fe9af230db63";
  private static final String BIN_62 = "redis-server-6.2.6-v5-linux-amd64";
  private static final String BIN_62_SHA256 = "1f716094e08a25faee2bc7626feb9e13464ed89018ac5c376c5e9cff06bdcf52";
  /** The "binary" of a Compose server: {@code compose/<version>}. */
  private static final Path COMPOSE = Path.of("compose");

  final Path binary;
  final Path dir;
  final int port;
  final Integer tlsPort;
  final Certificates certs;
  private final Process process;

  /** What to start; {@code openDefault}: the default user needs no password (any password works). */
  record Options(Path binary, boolean v62, Certificates tls, LocalRedis replicaOf, boolean cluster,
      boolean openDefault) {
    static Options of(Path binary) {
      return new Options(binary, false, null, null, false, false);
    }

    Options withTls(Certificates c) {
      return new Options(binary, v62, c, replicaOf, cluster, openDefault);
    }

    Options withReplicaOf(LocalRedis primary) {
      return new Options(binary, v62, tls, primary, cluster, openDefault);
    }

    Options withCluster() {
      return new Options(binary, v62, tls, replicaOf, true, openDefault);
    }

    Options withV62() {
      return new Options(binary, true, tls, replicaOf, cluster, openDefault);
    }

    Options withOpenDefault() {
      return new Options(binary, v62, tls, replicaOf, cluster, true);
    }
  }

  private LocalRedis(Path binary, Path dir, int port, Integer tlsPort, Certificates certs, Process process) {
    this.binary = binary;
    this.dir = dir;
    this.port = port;
    this.tlsPort = tlsPort;
    this.certs = certs;
    this.process = process;
  }

  /** The redis-server this machine has (7.0.15 here), or {@code $GW_REDIS_BIN}; with the Compose services, theirs. */
  static Path localBinary() {
    if (TestServices.enabled()) {
      return COMPOSE.resolve(TestServices.value("GW_REDIS_TAG"));
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
    throw new IllegalStateException("redis-server isn't on PATH; set GW_REDIS_BIN to a redis-server binary");
  }

  /** Redis 6.2.11 from embedded-redis 1.4.4 (Linux x86-64), from the test cache or downloaded and checked once. */
  static synchronized Path redis62() {
    if (TestServices.enabled()) {
      return COMPOSE.resolve(TestServices.value("GW_REDIS62_TAG"));
    }
    try {
      Path bin = CACHE.resolve("6.2").resolve(BIN_62);
      if (Files.isExecutable(bin) && sha256(bin).equals(BIN_62_SHA256)) {
        return bin;
      }
      Files.createDirectories(bin.getParent());
      Path jar = CACHE.resolve(JAR);
      if (!Files.exists(jar) || !sha256(jar).equals(JAR_SHA256)) {
        Path part = CACHE.resolve(JAR + ".part");
        // curl knows this machine's proxy settings (HTTPS_PROXY), which java.net.http would not read by itself.
        Process curl = new ProcessBuilder("curl", "-sSfL", "-o", part.toString(), JAR_URL).inheritIO().start();
        if (!curl.waitFor(5, TimeUnit.MINUTES) || curl.exitValue() != 0) {
          throw new IOException("Downloading " + JAR_URL + " failed");
        }
        if (!sha256(part).equals(JAR_SHA256)) {
          Files.delete(part);
          throw new IOException(JAR + " doesn't have the expected SHA-256");
        }
        Files.move(part, jar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      }
      try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(jar))) {
        for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
          if (e.getName().equals(BIN_62)) {
            Path part = bin.resolveSibling(BIN_62 + ".part");
            Files.copy(zip, part, StandardCopyOption.REPLACE_EXISTING);
            Files.move(part, bin, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            break;
          }
        }
      }
      if (!sha256(bin).equals(BIN_62_SHA256)) {
        throw new IOException(BIN_62 + " doesn't have the expected SHA-256");
      }
      bin.toFile().setExecutable(true);
      return bin;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  static LocalRedis start(String name, Options o) {
    if (o.binary().startsWith(COMPOSE)) {
      return compose(o);
    }
    IllegalStateException last = null;
    for (int attempt = 0; attempt < 3; attempt++) {
      try {
        return startOnce(name, o);
      } catch (IllegalStateException e) {
        // A port taken between choosing and binding it: try another.
        last = e;
      }
    }
    throw last;
  }

  private static LocalRedis startOnce(String name, Options o) {
    try {
      Path dir = Path.of("target", "redis-it", name + "-" + ProcessHandle.current().pid() + "-"
          + SEQUENCE.incrementAndGet()).toAbsolutePath();
      Files.createDirectories(dir);
      Path acl = dir.resolve("users.acl");
      Files.writeString(acl, acl(o.v62(), o.openDefault()));
      int port = freePort();
      List<String> cmd = new ArrayList<>(List.of(o.binary().toString(),
          "--bind", "127.0.0.1", "--port", Integer.toString(port), "--save", "", "--appendonly", "no",
          "--dir", dir.toString(), "--logfile", dir.resolve("redis.log").toString(), "--daemonize", "no",
          "--aclfile", acl.toString(), "--protected-mode", "no"));
      Integer tlsPort = null;
      if (o.tls() != null) {
        tlsPort = freePort();
        cmd.addAll(List.of("--tls-port", Integer.toString(tlsPort), "--tls-cert-file", o.tls().serverCert().toString(),
            "--tls-key-file", o.tls().serverKey().toString(), "--tls-ca-cert-file", o.tls().ca().toString(),
            "--tls-auth-clients", "no"));
      }
      if (o.replicaOf() != null) {
        cmd.addAll(List.of("--replicaof", "127.0.0.1", Integer.toString(o.replicaOf().port), "--masteruser", "default",
            "--masterauth", DEFAULT_PASSWORD, "--replica-read-only", "yes"));
      }
      if (o.cluster()) {
        cmd.addAll(List.of("--cluster-enabled", "yes", "--cluster-config-file", dir.resolve("nodes.conf").toString()));
      }
      Process p = new ProcessBuilder(cmd).redirectErrorStream(true)
          .redirectOutput(dir.resolve("stdout.log").toFile()).start();
      LocalRedis r = new LocalRedis(o.binary(), dir, port, tlsPort, o.tls(), p);
      long until = System.nanoTime() + Duration.ofSeconds(15).toNanos();
      while (System.nanoTime() < until) {
        if (!p.isAlive()) {
          throw new IllegalStateException("redis-server exited: " + r.log());
        }
        if (r.answers()) {
          return r;
        }
        Thread.sleep(25);
      }
      r.close();
      throw new IllegalStateException("redis-server didn't start in 15 s: " + r.log());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** The Compose server for the options, its users reloaded from the ACL file and its data flushed. */
  private static LocalRedis compose(Options o) {
    String port;
    String tls = null;
    if (o.v62()) {
      port = "GW_REDIS62_PORT";
      tls = "GW_REDIS62_TLS_PORT";
    } else if (o.replicaOf() != null) {
      port = "GW_REDIS_REPLICA_PORT";
    } else if (o.openDefault()) {
      port = "GW_REDIS_OPEN_PORT";
    } else if (o.cluster()) {
      port = "GW_REDIS_CLUSTER_PORT";
    } else {
      port = "GW_REDIS_PORT";
      tls = "GW_REDIS_TLS_PORT";
    }
    if (o.tls() != null && tls == null) {
      throw new IllegalArgumentException("The test services have TLS on the primary and on 6.2 only");
    }
    LocalRedis r = new LocalRedis(o.binary(), null, TestServices.port(port), o.tls() == null ? null
        : TestServices.port(tls), o.tls(), null);
    if (!r.answers()) {
      throw new IllegalStateException("Redis of the test services (scripts/test-services.sh up redis) doesn't answer "
          + "on 127.0.0.1:" + r.port);
    }
    try (Jedis j = r.admin()) {
      j.aclLoad();
      j.aclLogReset();
      j.configResetStat();
      if (o.replicaOf() == null) {
        j.flushAll();
      }
    }
    return r;
  }

  private static String acl(boolean v62, boolean openDefault) {
    String reader = "resetchannels -@all +@read +@connection -@dangerous " + (v62 ? "+client|setname +client|id " : "")
        + "-dump -xread -lolwut";
    return String.join("\n",
        "user default on " + (openDefault ? "nopass" : ">" + DEFAULT_PASSWORD) + " ~* &* +@all",
        "user ro on >ro-pass ~* " + reader,
        "user rokeys on >rokeys-pass ~cache:* " + reader,
        "user rw on >rw-pass ~* &* +@all -@dangerous",
        "");
  }

  /** A client for the test itself (seeding, checks): the default user, everything allowed. */
  Jedis admin() {
    Jedis j = new Jedis(new HostAndPort("127.0.0.1", port), DefaultJedisClientConfig.builder()
        .password(DEFAULT_PASSWORD).socketTimeoutMillis(10_000).protocol(RedisProtocol.RESP2).build());
    j.connect();
    return j;
  }

  private boolean answers() {
    try (Socket s = new Socket()) {
      s.connect(new InetSocketAddress("127.0.0.1", port), 200);
      s.setSoTimeout(500);
      s.getOutputStream().write("PING\r\n".getBytes(StandardCharsets.US_ASCII));
      byte[] buf = new byte[64];
      int n = s.getInputStream().read(buf);
      // Any RESP answer (+PONG, or -NOAUTH from an ACL'd default user) means it serves.
      return n > 0 && (buf[0] == '+' || buf[0] == '-');
    } catch (IOException e) {
      return false;
    }
  }

  String log() {
    if (dir == null) {
      return "(docker logs of the Compose service on port " + port + ")";
    }
    try {
      Path f = dir.resolve("redis.log");
      String log = Files.exists(f) ? Files.readString(f) : "";
      Path out = dir.resolve("stdout.log");
      return log + (Files.exists(out) ? Files.readString(out) : "");
    } catch (IOException e) {
      return e.toString();
    }
  }

  static int freePort() throws IOException {
    try (ServerSocket s = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
      return s.getLocalPort();
    }
  }

  @Override
  public void close() {
    if (process == null) {
      return; // the Compose service keeps running
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

  static String sha256(Path f) throws IOException {
    try (InputStream in = Files.newInputStream(f)) {
      MessageDigest d = MessageDigest.getInstance("SHA-256");
      byte[] buf = new byte[1 << 16];
      for (int n; (n = in.read(buf)) > 0; ) {
        d.update(buf, 0, n);
      }
      return HexFormat.of().formatHex(d.digest());
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * A CA and a server certificate for {@code localhost} only (no IP), made with openssl: connecting to 127.0.0.1
   * with full verification must fail on the name, and with CA-only verification pass.
   */
  record Certificates(Path ca, Path serverCert, Path serverKey, String caPem, String otherCaPem) {
    static Certificates create(Path dir) {
      if (TestServices.enabled()) {
        // The Compose servers already serve the certificates of run/certs: the same CA, names and "other" CA.
        try {
          return new Certificates(TestServices.file("run/certs/ca.crt"), TestServices.file("run/certs/localhost.crt"),
              TestServices.file("run/certs/localhost.key"), Files.readString(TestServices.file("run/certs/ca.crt")),
              Files.readString(TestServices.file("run/certs/other-ca.crt")));
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      }
      try {
        Files.createDirectories(dir);
        run(dir, "openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", "ca.key", "-out", "ca.crt",
            "-days", "2", "-subj", "/CN=gw-redis-test-ca");
        run(dir, "openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", "other.key", "-out",
            "other.crt", "-days", "2", "-subj", "/CN=gw-redis-other-ca");
        run(dir, "openssl", "req", "-newkey", "rsa:2048", "-nodes", "-keyout", "server.key", "-out", "server.csr",
            "-subj", "/CN=localhost");
        Files.writeString(dir.resolve("ext.cnf"), "subjectAltName=DNS:localhost\n");
        run(dir, "openssl", "x509", "-req", "-in", "server.csr", "-CA", "ca.crt", "-CAkey", "ca.key",
            "-CAcreateserial", "-out", "server.crt", "-days", "2", "-extfile", "ext.cnf");
        return new Certificates(dir.resolve("ca.crt"), dir.resolve("server.crt"), dir.resolve("server.key"),
            Files.readString(dir.resolve("ca.crt")), Files.readString(dir.resolve("other.crt")));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    private static void run(Path dir, String... cmd) throws IOException {
      try {
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
          throw new IOException(String.join(" ", cmd) + " failed: " + out);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException(e);
      }
    }
  }
}
