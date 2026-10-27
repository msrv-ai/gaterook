package ai.msrv.gaterook.e2e.mongodb;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCredential;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import de.flapdoodle.commons.reverse.StateID;
import de.flapdoodle.embed.mongo.distribution.Version;
import de.flapdoodle.embed.mongo.transitions.Mongod;
import de.flapdoodle.embed.process.archives.ExtractedFileSet;
import ai.msrv.gaterook.connectors.support.Pem;
import ai.msrv.gaterook.connectors.support.TrustStores;
import ai.msrv.gaterook.e2e.TestServices;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.bson.BsonDocument;
import org.bson.Document;

/**
 * A mongod with access control and TLS only (a CA of its own, {@code openssl}) for the e2e of the core as a process:
 * {@code GW_MONGOD_BIN}, else 8.0.23 extracted by flapdoodle; data under {@code target/}, a free port. The users are
 * those of report 05 §8; {@code orders.notes} holds the password of {@code gw_rw}, the masking canary. With the
 * Compose services ({@link TestServices}) the server is their MongoDB with TLS (the CA of {@code run/certs}, their
 * root), and a start drops what a previous run left before making the users and the seed.
 */
final class LocalMongod implements AutoCloseable {

  static final String ROOT = "root";

  final int port;
  final Path dir;
  private final Process process;

  private LocalMongod(int port, Path dir, Process process) {
    this.port = port;
    this.dir = dir;
    this.process = process;
  }

  static String password(String user) {
    if (user.equals(ROOT) && TestServices.enabled()) {
      return TestServices.value("GW_MONGO_ROOT_PASSWORD");
    }
    return user + "-e2e-pw-5d3b91f0";
  }

  static LocalMongod start() throws Exception {
    if (TestServices.enabled()) {
      LocalMongod m = new LocalMongod(TestServices.port("GW_MONGO_TLS_PORT"), TestServices.file("run/certs"), null);
      m.awaitReady();
      try (MongoClient c = m.client(true)) {
        c.getDatabase("orders").runCommand(new Document("dropAllUsersFromDatabase", 1));
        c.getDatabase("orders").drop();
        for (String user : List.of("gw_rw", "gw_ro")) {
          if (!c.getDatabase("admin").runCommand(new Document("usersInfo", user)).getList("users", Document.class)
              .isEmpty()) {
            c.getDatabase("admin").runCommand(new Document("dropUser", user));
          }
        }
      }
      m.createUsersAndSeed();
      return m;
    }
    Path dir = Files.createTempDirectory(Files.createDirectories(Path.of("target").toAbsolutePath()), "mongod-");
    Path data = Files.createDirectories(dir.resolve("data"));
    certificates(dir);
    int port;
    try (ServerSocket s = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
      port = s.getLocalPort();
    }
    Process p = new ProcessBuilder(binary().toString(), "--dbpath", data.toString(), "--port", Integer.toString(port),
        "--bind_ip", "127.0.0.1", "--logpath", dir.resolve("mongod.log").toString(), "--quiet", "--auth",
        "--tlsMode", "requireTLS", "--tlsCertificateKeyFile", dir.resolve("server.pem").toString(),
        "--tlsCAFile", dir.resolve("ca.pem").toString(), "--tlsAllowConnectionsWithoutCertificates")
        .redirectErrorStream(true).redirectOutput(dir.resolve("stdout.log").toFile()).start();
    Runtime.getRuntime().addShutdownHook(new Thread(p::destroyForcibly));
    LocalMongod m = new LocalMongod(port, dir, p);
    m.awaitReady();
    m.createUsersAndSeed();
    return m;
  }

  private static Path binary() throws IOException {
    String env = System.getenv("GW_MONGOD_BIN");
    if (env != null && !env.isBlank()) {
      return Path.of(env);
    }
    Path copy = Path.of("target", "mongod-bin", "mongod-8.0.23").toAbsolutePath();
    if (Files.isExecutable(copy)) {
      return copy;
    }
    try (var state = Mongod.instance().transitions(Version.Main.V8_0).walker()
        .initState(StateID.of(ExtractedFileSet.class))) {
      Files.createDirectories(copy.getParent());
      Files.copy(state.current().executable(), copy, StandardCopyOption.REPLACE_EXISTING);
      copy.toFile().setExecutable(true);
      return copy;
    }
  }

  /** The server's version ({@code buildInfo}): 8.0.23 of its own, 8.x of the Compose services. */
  String version() {
    try (MongoClient c = client(true)) {
      return c.getDatabase("admin").runCommand(new Document("buildInfo", 1)).getString("version");
    }
  }

  /** {@code 8.0} of {@code 8.0.23}: what tool descriptions name. */
  String majorMinor() {
    String v = version();
    return v.substring(0, v.indexOf('.', v.indexOf('.') + 1));
  }

  String caCertificate() throws IOException {
    return Files.readString(dir.resolve(process == null ? "ca.crt" : "ca.pem"));
  }

  /** A client of the test itself, as root unless {@code root} is false (the localhost exception). */
  MongoClient client(boolean root) {
    MongoClientSettings.Builder b = MongoClientSettings.builder()
        .applyConnectionString(new ConnectionString("mongodb://127.0.0.1:" + port + "/?directConnection=true"))
        .applyToClusterSettings(c -> c.serverSelectionTimeout(1, TimeUnit.SECONDS));
    if (root) {
      b.credential(MongoCredential.createCredential(ROOT, "admin", password(ROOT).toCharArray()));
    }
    try {
      var context = TrustStores.sslContext(TrustStores.of(Pem.certificates(caCertificate()), false));
      b.applyToSslSettings(s -> s.enabled(true).context(context));
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
    return MongoClients.create(b.build());
  }

  private void awaitReady() throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    Exception last = null;
    while (System.nanoTime() < deadline) {
      if (process != null && !process.isAlive()) {
        throw new IllegalStateException("mongod exited: " + Files.readString(dir.resolve("stdout.log")));
      }
      try (MongoClient c = client(false)) {
        c.getDatabase("admin").runCommand(new Document("ping", 1));
        return;
      } catch (Exception e) {
        last = e;
        Thread.sleep(200);
      }
    }
    throw new IllegalStateException("mongod didn't start within 60 s", last);
  }

  private void createUsersAndSeed() {
    if (process != null) {
      try (MongoClient c = client(false)) {
        c.getDatabase("admin").runCommand(user(ROOT, "root"));
      }
    }
    try (MongoClient c = client(true)) {
      c.getDatabase("admin").runCommand(user("gw_rw", "readWrite"));
      c.getDatabase("admin").runCommand(user("gw_ro", "read"));
      var orders = c.getDatabase("orders");
      orders.getCollection("orders", BsonDocument.class).insertMany(List.of(
          BsonDocument.parse("{_id: 1, id: 48213, status: 'pending', total: NumberDecimal('120.50'),"
              + " customer: {id: NumberLong('9007199254740993')}}"),
          BsonDocument.parse("{_id: 2, id: 48240, status: 'pending', total: NumberDecimal('9.99')}"),
          BsonDocument.parse("{_id: 3, id: 48241, status: 'shipped', total: NumberDecimal('42.00')}")));
      // Someone pasted the connection's password into a note: the core must mask it in the answer.
      orders.getCollection("notes").insertOne(new Document("_id", 1).append("text", "db password is "
          + password("gw_rw") + ", don't share"));
    }
  }

  private static Document user(String name, String role) {
    return new Document("createUser", name).append("pwd", password(name))
        .append("roles", List.of(new Document("role", role).append("db", name.equals(ROOT) ? "admin" : "orders")));
  }

  long count(String collection) {
    try (MongoClient c = client(true)) {
      return c.getDatabase("orders").getCollection(collection).countDocuments();
    }
  }

  List<String> collections() {
    try (MongoClient c = client(true)) {
      return c.getDatabase("orders").listCollectionNames().into(new java.util.ArrayList<>());
    }
  }

  @Override
  public void close() {
    if (process == null) {
      return; // the Compose service keeps running
    }
    process.destroy();
    try {
      if (!process.waitFor(10, TimeUnit.SECONDS)) {
        process.destroyForcibly();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
    }
  }

  /** A CA and a server certificate for 127.0.0.1 and localhost. */
  private static void certificates(Path dir) throws Exception {
    Files.writeString(dir.resolve("ext.cnf"), "subjectAltName=DNS:localhost,IP:127.0.0.1\n");
    run(dir, "openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", "ca.key", "-out", "ca.pem",
        "-days", "2", "-subj", "/CN=gw-e2e-ca");
    run(dir, "openssl", "req", "-newkey", "rsa:2048", "-nodes", "-keyout", "server.key", "-out", "server.csr",
        "-subj", "/CN=localhost");
    run(dir, "openssl", "x509", "-req", "-in", "server.csr", "-CA", "ca.pem", "-CAkey", "ca.key",
        "-CAcreateserial", "-out", "server.crt", "-days", "2", "-extfile", "ext.cnf");
    Files.writeString(dir.resolve("server.pem"), Files.readString(dir.resolve("server.crt"))
        + Files.readString(dir.resolve("server.key")));
  }

  private static void run(Path dir, String... cmd) throws IOException, InterruptedException {
    Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
    String out = new String(p.getInputStream().readAllBytes());
    if (p.waitFor() != 0) {
      throw new IOException(String.join(" ", cmd) + " failed: " + out);
    }
  }
}
