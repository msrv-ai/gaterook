package com.gaterook.connectors.redis;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.RedisProtocol;

/**
 * A real Redis for one test class (report 05 §8), in a container: the image the Compose services run, by its tag in
 * {@code test-services/.env} ({@code GW_REDIS_TAG}, 7.0.15; {@code GW_REDIS62_TAG}, 6.2.11, with
 * {@link Options#withV62()}), on ephemeral ports of 127.0.0.1 only, no persistence, users from an ACL file. Nothing of
 * Redis needs to be installed; Docker needs to run.
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
  /** The ports inside the container, as in the Compose services. */
  private static final int PORT = 6379;
  private static final int TLS_PORT = 6380;

  final int port;
  final Integer tlsPort;
  final Certificates certs;
  /** The name another container (a replica) reaches this server by on {@link Network#SHARED}. */
  private final String alias;
  private final GenericContainer<?> container;

  /** What to start; {@code openDefault}: the default user needs no password (any password works). */
  record Options(boolean v62, Certificates tls, LocalRedis replicaOf, boolean cluster, boolean openDefault) {
    static Options defaults() {
      return new Options(false, null, null, false, false);
    }

    Options withTls(Certificates c) {
      return new Options(v62, c, replicaOf, cluster, openDefault);
    }

    Options withReplicaOf(LocalRedis primary) {
      return new Options(v62, tls, primary, cluster, openDefault);
    }

    Options withCluster() {
      return new Options(v62, tls, replicaOf, true, openDefault);
    }

    /** Redis 6.2 and the 6.2 form of the read-only user. */
    Options withV62() {
      return new Options(true, tls, replicaOf, cluster, openDefault);
    }

    Options withOpenDefault() {
      return new Options(v62, tls, replicaOf, cluster, true);
    }
  }

  private LocalRedis(int port, Integer tlsPort, Certificates certs, String alias, GenericContainer<?> container) {
    this.port = port;
    this.tlsPort = tlsPort;
    this.certs = certs;
    this.alias = alias;
    this.container = container;
  }

  static LocalRedis start(String name, Options o) {
    return TestServices.enabled() ? compose(o) : container(name, o);
  }

  private static LocalRedis container(String name, Options o) {
    String image = "redis:" + TestServices.tag(o.v62() ? "GW_REDIS62_TAG" : "GW_REDIS_TAG");
    if (!DockerClientFactory.instance().isDockerAvailable()) {
      throw new IllegalStateException("The Redis ITs run " + image + " in a container and need Docker; or use the "
          + "Compose services (test-services/README.md)");
    }
    try {
      Path dir = Path.of("target", "redis-it", name + "-" + ProcessHandle.current().pid() + "-"
          + SEQUENCE.incrementAndGet()).toAbsolutePath();
      Files.createDirectories(dir);
      Path acl = dir.resolve("users.acl");
      Files.writeString(acl, acl(o.v62(), o.openDefault()));
      String alias = "redis-" + dir.getFileName();
      List<Integer> ports = new ArrayList<>(List.of(PORT));
      List<String> cmd = new ArrayList<>(List.of("redis-server", "--port", Integer.toString(PORT), "--save", "",
          "--appendonly", "no", "--aclfile", "/conf/users.acl", "--protected-mode", "no"));
      GenericContainer<?> c = new GenericContainer<>(DockerImageName.parse(image))
          .withNetwork(Network.SHARED)
          .withNetworkAliases(alias)
          .withCopyFileToContainer(MountableFile.forHostPath(acl, 0644), "/conf/users.acl");
      if (o.tls() != null) {
        ports.add(TLS_PORT);
        c.withCopyFileToContainer(MountableFile.forHostPath(o.tls().serverCert(), 0644), "/certs/server.crt")
            .withCopyFileToContainer(MountableFile.forHostPath(o.tls().serverKey(), 0644), "/certs/server.key")
            .withCopyFileToContainer(MountableFile.forHostPath(o.tls().ca(), 0644), "/certs/ca.crt");
        cmd.addAll(List.of("--tls-port", Integer.toString(TLS_PORT), "--tls-cert-file", "/certs/server.crt",
            "--tls-key-file", "/certs/server.key", "--tls-ca-cert-file", "/certs/ca.crt", "--tls-auth-clients", "no"));
      }
      if (o.replicaOf() != null) {
        cmd.addAll(List.of("--replicaof", o.replicaOf().alias, Integer.toString(PORT), "--masteruser", "default",
            "--masterauth", DEFAULT_PASSWORD, "--replica-read-only", "yes"));
      }
      if (o.cluster()) {
        cmd.addAll(List.of("--cluster-enabled", "yes", "--cluster-config-file", "/data/nodes.conf"));
      }
      List<PortBinding> loopback = ports.stream()
          .map(p -> new PortBinding(Ports.Binding.bindIp("127.0.0.1"), ExposedPort.tcp(p))).toList();
      c.withExposedPorts(ports.toArray(Integer[]::new))
          // On 127.0.0.1 only, as the Compose services publish them: the users' passwords are no secret.
          .withCreateContainerCmdModifier(create -> create.getHostConfig().withPublishAllPorts(false)
              .withPortBindings(loopback))
          .withCommand(cmd.toArray(String[]::new))
          .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1)
              .withStartupTimeout(Duration.ofSeconds(60)));
      c.start();
      LocalRedis r = new LocalRedis(c.getMappedPort(PORT), o.tls() == null ? null : c.getMappedPort(TLS_PORT), o.tls(),
          alias, c);
      long until = System.nanoTime() + Duration.ofSeconds(15).toNanos();
      while (!r.answers()) {
        if (System.nanoTime() > until) {
          String log = c.getLogs();
          r.close();
          throw new IllegalStateException(image + " doesn't answer on 127.0.0.1:" + r.port + ": " + log);
        }
        Thread.sleep(25);
      }
      return r;
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
    LocalRedis r = new LocalRedis(TestServices.port(port), o.tls() == null ? null : TestServices.port(tls), o.tls(),
        null, null);
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

  static int freePort() throws IOException {
    try (ServerSocket s = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
      return s.getLocalPort();
    }
  }

  @Override
  public void close() {
    if (container != null) {
      container.stop(); // the Compose service keeps running
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
