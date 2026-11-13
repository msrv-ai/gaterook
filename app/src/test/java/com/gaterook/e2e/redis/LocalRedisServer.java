package com.gaterook.e2e.redis;

import com.gaterook.e2e.TestServices;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.RedisProtocol;

/**
 * Redis for the end-to-end test, in a container: the image the Compose services run ({@code GW_REDIS_TAG} of
 * {@code test-services/.env}, 7.0.15), on ephemeral ports of 127.0.0.1 only, no persistence, TLS on a second port with
 * a certificate for {@code localhost} from a private CA made with openssl. The connector module's ITs have the full
 * fixture; this one has what the core's pipeline needs: the users {@code default} (everything) and {@code ro} (the
 * read-only line of Test connection's hint), with passwords the test chooses. With the Compose services
 * ({@link TestServices}) the server is their primary (the CA of {@code run/certs}): {@code ro} gets the chosen password
 * for the run, the default user keeps its own, and closing reloads the users of the ACL file.
 */
final class LocalRedisServer implements AutoCloseable {

  /** The ports inside the container, as in the Compose services. */
  private static final int PORT = 6379;
  private static final int TLS_PORT = 6380;

  final int port;
  final int tlsPort;
  final String caPem;
  private final String adminPassword;
  private final GenericContainer<?> container;

  private LocalRedisServer(int port, int tlsPort, String caPem, String adminPassword, GenericContainer<?> container) {
    this.port = port;
    this.tlsPort = tlsPort;
    this.caPem = caPem;
    this.adminPassword = adminPassword;
    this.container = container;
  }

  /** Whether there is a server to have: the Compose services, or Docker to run one in (else the test is skipped). */
  static boolean available() {
    return TestServices.enabled() || DockerClientFactory.instance().isDockerAvailable();
  }

  static LocalRedisServer start(String adminPassword, String roPassword) throws Exception {
    if (TestServices.enabled()) {
      LocalRedisServer r = new LocalRedisServer(TestServices.port("GW_REDIS_PORT"),
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
    String image = "redis:" + TestServices.tag("GW_REDIS_TAG");
    List<PortBinding> loopback = List.of(PORT, TLS_PORT).stream()
        .map(p -> new PortBinding(Ports.Binding.bindIp("127.0.0.1"), ExposedPort.tcp(p))).toList();
    GenericContainer<?> c = new GenericContainer<>(DockerImageName.parse(image))
        .withCopyFileToContainer(MountableFile.forHostPath(dir.resolve("users.acl"), 0644), "/conf/users.acl")
        .withCopyFileToContainer(MountableFile.forHostPath(dir.resolve("server.crt"), 0644), "/certs/server.crt")
        .withCopyFileToContainer(MountableFile.forHostPath(dir.resolve("server.key"), 0644), "/certs/server.key")
        .withCopyFileToContainer(MountableFile.forHostPath(dir.resolve("ca.crt"), 0644), "/certs/ca.crt")
        .withExposedPorts(PORT, TLS_PORT)
        // On 127.0.0.1 only, as the Compose services publish them.
        .withCreateContainerCmdModifier(create -> create.getHostConfig().withPublishAllPorts(false)
            .withPortBindings(loopback))
        .withCommand("redis-server", "--port", Integer.toString(PORT), "--tls-port", Integer.toString(TLS_PORT),
            "--tls-cert-file", "/certs/server.crt", "--tls-key-file", "/certs/server.key", "--tls-ca-cert-file",
            "/certs/ca.crt", "--tls-auth-clients", "no", "--save", "", "--appendonly", "no", "--aclfile",
            "/conf/users.acl", "--protected-mode", "no")
        .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1)
            .withStartupTimeout(Duration.ofSeconds(60)));
    c.start();
    LocalRedisServer r = new LocalRedisServer(c.getMappedPort(PORT), c.getMappedPort(TLS_PORT),
        Files.readString(dir.resolve("ca.crt")), adminPassword, c);
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

  private static void run(Path dir, String... cmd) throws IOException, InterruptedException {
    Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
      throw new IOException(String.join(" ", cmd) + " failed: " + out);
    }
  }

  @Override
  public void close() {
    if (container == null) {
      try (Jedis j = admin()) {
        j.aclLoad(); // the Compose service keeps running, with the users of its ACL file
      }
      return;
    }
    container.stop();
  }
}
