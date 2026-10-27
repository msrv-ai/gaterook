package ai.msrv.gaterook.connectors.mongodb;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCredential;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import de.flapdoodle.commons.reverse.StateID;
import de.flapdoodle.embed.mongo.distribution.Version;
import de.flapdoodle.embed.mongo.transitions.Mongod;
import de.flapdoodle.embed.process.archives.ExtractedFileSet;
import ai.msrv.gaterook.connectors.support.Pem;
import ai.msrv.gaterook.connectors.support.TrustStores;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.bson.Document;

/**
 * A real {@code mongod} for tests, without Docker (ARCHITECTURE.md §10 C2): the binary of {@code GW_MONGOD_BIN}, else
 * MongoDB 8.0.23 extracted by flapdoodle 5.0.0 (from its cache {@code ~/.embedmongo}), or a release tarball of the
 * {@code -Pmongo-it} matrix downloaded once into {@code ~/.cache/mcp-gateway-test/mongodb/<version>/} and checked
 * against its SHA-256. Started by the test itself on a free port with its data under {@code target/}, with access
 * control (users made through the localhost exception), optionally TLS with a CA of its own ({@code openssl}), and
 * {@code enableTestCommands} for the time-limit fail point.
 *
 * <p>With the Compose services ({@link TestServices}) the server is one of theirs instead — 8.x with access control,
 * without it or with TLS ({@code run/certs/ca.crt}), 7.0 or 9.0 of the matrix — and a start drops what a previous run
 * left (databases, the test users, the fail point) before the users and the seed are made again.
 */
final class LocalMongod implements AutoCloseable {

  /** Release tarballs of the matrix: sha256 from downloads.mongodb.org/full.json. */
  static final Map<String, String[]> TARBALLS = Map.of(
      "7.0.45", new String[] {"mongodb-linux-x86_64-ubuntu2204-7.0.45.tgz",
          "c2eec1e4aaee967838430ac57fc7d46c22f566c718eda6033e32add80d506f8b"},
      "9.0.4", new String[] {"mongodb-linux-x86_64-ubuntu2404-9.0.4.tgz",
          "5e85bf685450287a1e7e073da3ae39677ffe7f00c01a86dec31283ccc2cf2dcc"});

  static final String ROOT = "root";

  /** The "binary" of a Compose server: {@code compose/<version>}. */
  private static final Path COMPOSE = Path.of("compose");

  String version;
  final int port;
  final Path dir;
  final Path caPem;
  final boolean auth;
  /** Whether the server also listens on 127.0.0.2, a name its certificate doesn't have (Linux; not macOS, not Compose). */
  final boolean secondLoopback;
  private final Process process;

  private LocalMongod(String version, int port, Path dir, Path caPem, boolean auth, boolean secondLoopback,
      Process process) {
    this.version = version;
    this.port = port;
    this.dir = dir;
    this.caPem = caPem;
    this.auth = auth;
    this.secondLoopback = secondLoopback;
    this.process = process;
  }

  /** The password of a test user: deterministic, never a real one. */
  static String password(String user) {
    return user + "-pw-7a1c2e9b";
  }

  /** {@code GW_MONGOD_BIN}, else 8.0.23 from flapdoodle; with the Compose services, their 8.x server. */
  static Path defaultBinary() {
    if (TestServices.enabled()) {
      return COMPOSE.resolve(TestServices.value("GW_MONGO_TAG"));
    }
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
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The {@code mongod} of a release tarball, downloaded and checked once. */
  static Path tarball(String version) throws IOException, InterruptedException {
    if (TestServices.enabled()) {
      return COMPOSE.resolve(version);
    }
    String[] t = TARBALLS.get(version);
    Path cache = Path.of(System.getProperty("user.home"), ".cache", "mcp-gateway-test", "mongodb", version);
    Path mongod = cache.resolve("mongod");
    if (Files.isExecutable(mongod)) {
      return mongod;
    }
    Files.createDirectories(cache);
    Path archive = cache.resolve(t[0]);
    if (!Files.exists(archive) || !sha256(archive).equals(t[1])) {
      HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
      Path part = cache.resolve(t[0] + ".part");
      http.send(HttpRequest.newBuilder(URI.create("https://fastdl.mongodb.org/linux/" + t[0])).build(),
          HttpResponse.BodyHandlers.ofFile(part));
      Files.move(part, archive, StandardCopyOption.REPLACE_EXISTING);
    }
    if (!sha256(archive).equals(t[1])) {
      throw new IOException(archive + " doesn't match its SHA-256");
    }
    run(cache, List.of("tar", "-xzf", archive.toString(), "--strip-components=2", "--wildcards", "*/bin/mongod"));
    return mongod;
  }

  static LocalMongod start(Path binary, boolean auth, boolean tls) throws Exception {
    if (binary.startsWith(COMPOSE)) {
      return compose(binary.getFileName().toString(), auth, tls);
    }
    Path dir = Files.createTempDirectory(Files.createDirectories(Path.of("target").toAbsolutePath()), "mongod-");
    Path data = Files.createDirectories(dir.resolve("data"));
    int port = freePort();
    boolean second = canBind("127.0.0.2");
    List<String> cmd = new ArrayList<>(List.of(binary.toString(), "--dbpath", data.toString(), "--port",
        Integer.toString(port), "--bind_ip", second ? "127.0.0.1,127.0.0.2" : "127.0.0.1", "--logpath",
        dir.resolve("mongod.log").toString(), "--setParameter", "enableTestCommands=1", "--quiet"));
    if (auth) {
      cmd.add("--auth");
    }
    Path ca = null;
    if (tls) {
      ca = certificates(dir);
      cmd.addAll(List.of("--tlsMode", "requireTLS", "--tlsCertificateKeyFile", dir.resolve("server.pem").toString(),
          "--tlsCAFile", ca.toString(), "--tlsAllowConnectionsWithoutCertificates"));
    }
    Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(dir.resolve("stdout.log").toFile())
        .start();
    LocalMongod m = new LocalMongod(versionOf(binary), port, dir, ca, auth, second, p);
    Runtime.getRuntime().addShutdownHook(new Thread(p::destroyForcibly));
    m.awaitReady();
    if (auth) {
      m.createUsers();
    }
    m.seed();
    return m;
  }

  /** The Compose server of the version and kind, cleaned and seeded like a fresh one. */
  private static LocalMongod compose(String version, boolean auth, boolean tls) throws Exception {
    String key;
    if (version.equals(TestServices.value("GW_MONGO7_TAG"))) {
      key = "GW_MONGO7_PORT";
    } else if (version.equals(TestServices.value("GW_MONGO9_TAG"))) {
      key = "GW_MONGO9_PORT";
    } else {
      key = tls ? "GW_MONGO_TLS_PORT" : auth ? "GW_MONGO_PORT" : "GW_MONGO_OPEN_PORT";
    }
    if (!password(ROOT).equals(TestServices.value("GW_MONGO_ROOT_PASSWORD"))) {
      throw new IllegalStateException("GW_MONGO_ROOT_PASSWORD of test-services/.env must be " + password(ROOT));
    }
    if (auth == key.equals("GW_MONGO_OPEN_PORT") || tls != key.equals("GW_MONGO_TLS_PORT")) {
      throw new IllegalArgumentException("The test services have no MongoDB " + version + (auth ? " with" : " without")
          + " access control" + (tls ? " and with TLS" : ""));
    }
    Path ca = tls ? TestServices.file("run/certs/ca.crt") : null;
    LocalMongod m = new LocalMongod(version, TestServices.port(key), null, ca, auth, false, null);
    m.awaitReady();
    try (MongoClient c = m.root()) {
      m.version = c.getDatabase("admin").runCommand(new Document("buildInfo", 1)).getString("version");
    }
    m.reset();
    if (auth) {
      m.createUsers();
    }
    m.seed();
    return m;
  }

  /** What a previous run left on a Compose server: its databases, the test users, the time-limit fail point. */
  private void reset() {
    try (MongoClient c = root()) {
      MongoDatabase admin = c.getDatabase("admin");
      for (String name : c.listDatabaseNames()) {
        if (!List.of("admin", "config", "local").contains(name)) {
          c.getDatabase(name).runCommand(new Document("dropAllUsersFromDatabase", 1));
          c.getDatabase(name).drop();
        }
      }
      if (auth) {
        for (String user : List.of("gw_ro", "gw_rw")) {
          if (admin.runCommand(new Document("usersInfo", user)).getList("users", Document.class).size() > 0) {
            admin.runCommand(new Document("dropUser", user));
          }
        }
      }
      admin.runCommand(new Document("configureFailPoint", "maxTimeAlwaysTimeOut").append("mode", "off"));
    }
  }

  String uri(String database) {
    return "mongodb://127.0.0.1:" + port + "/" + database;
  }

  String caCertificate() throws IOException {
    return caPem == null ? null : Files.readString(caPem);
  }

  /** A client as root (or without credentials when access control is off). */
  MongoClient root() {
    MongoClientSettings.Builder b = MongoClientSettings.builder()
        .applyConnectionString(new ConnectionString(uri("admin") + "?directConnection=true"))
        .applyToClusterSettings(c -> c.serverSelectionTimeout(5, TimeUnit.SECONDS));
    if (auth) {
      b.credential(MongoCredential.createCredential(ROOT, "admin", password(ROOT).toCharArray()));
    }
    tls(b);
    return MongoClients.create(b.build());
  }

  private void tls(MongoClientSettings.Builder b) {
    if (caPem != null) {
      try {
        var context = TrustStores.sslContext(TrustStores.of(Pem.certificates(Files.readString(caPem)), false));
        b.applyToSslSettings(s -> s.enabled(true).context(context));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  private void awaitReady() throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    Exception last = null;
    while (System.nanoTime() < deadline) {
      if (process != null && !process.isAlive()) {
        throw new IllegalStateException("mongod exited: " + Files.readString(dir.resolve("stdout.log")));
      }
      MongoClientSettings.Builder b = MongoClientSettings.builder()
          .applyConnectionString(new ConnectionString(uri("admin") + "?directConnection=true"))
          .applyToClusterSettings(c -> c.serverSelectionTimeout(500, TimeUnit.MILLISECONDS));
      tls(b);
      try (MongoClient c = MongoClients.create(b.build())) {
        c.getDatabase("admin").runCommand(new Document("ping", 1));
        return;
      } catch (Exception e) {
        last = e;
        Thread.sleep(200);
      }
    }
    throw new IllegalStateException(process == null ? "MongoDB of the test services (scripts/test-services.sh up "
        + "mongo) doesn't answer on 127.0.0.1:" + port : "mongod didn't start within 60 s", last);
  }

  /** Through the localhost exception: root first (a Compose server has it), then the users of report 05 §8. */
  private void createUsers() {
    if (process != null) {
      MongoClientSettings.Builder b = MongoClientSettings.builder()
          .applyConnectionString(new ConnectionString(uri("admin") + "?directConnection=true"));
      tls(b);
      try (MongoClient c = MongoClients.create(b.build())) {
        c.getDatabase("admin").runCommand(createUser(ROOT, "root", "admin"));
      }
    }
    try (MongoClient c = root()) {
      MongoDatabase admin = c.getDatabase("admin");
      admin.runCommand(createUser("gw_ro", "read", "orders"));
      admin.runCommand(createUser("gw_rw", "readWrite", "orders"));
      c.getDatabase("orders").runCommand(createUser("gw_reader", "read", "orders"));
    }
  }

  private static Document createUser(String user, String role, String db) {
    return new Document("createUser", user).append("pwd", password(user))
        .append("roles", List.of(new Document("role", role).append("db", db)));
  }

  /** The orders world of the tests: types of every kind, a view, a time series, a second database. */
  private void seed() {
    try (MongoClient c = root()) {
      MongoDatabase orders = c.getDatabase("orders");
      orders.getCollection("orders", BsonDocument.class).insertMany(List.of(
          BsonDocument.parse("{_id: 1, id: 48213, status: 'pending', total: NumberDecimal('120.50'),"
              + " created_at: ISODate('2026-10-22T12:29:41Z'), customer: {id: NumberLong('9007199254740993'),"
              + " email: 'ann@example.com'}}"),
          BsonDocument.parse("{_id: 2, id: 48240, status: 'pending', total: NumberDecimal('9.99'),"
              + " created_at: ISODate('1960-01-01T00:00:00Z'), customer: {id: NumberLong('42')}}"),
          BsonDocument.parse("{_id: 3, id: 48241, status: 'shipped', total: NumberDecimal('42.00'),"
              + " created_at: ISODate('2026-10-21T08:00:00Z'), ratio: NaN,"
              + " tag: UUID('0f8fad5b-d9cb-469f-a165-70867728950e')}")));
      orders.getCollection("customers").insertOne(new Document("_id", 42).append("name", "Ann"));
      orders.runCommand(new Document("create", "paid_orders").append("viewOn", "orders")
          .append("pipeline", List.of(new Document("$match", new Document("status", "paid")))));
      orders.runCommand(new Document("create", "metrics").append("timeseries", new Document("timeField", "t")));
      List<BsonDocument> events = new ArrayList<>();
      for (int i = 0; i < 3_000; i++) {
        events.add(new BsonDocument("_id", new BsonInt32(i)).append("pad", new BsonString("e".repeat(200)))
            .append("tags", new BsonArray(List.of(new BsonString("a"), new BsonString("b")))));
      }
      orders.getCollection("events", BsonDocument.class).insertMany(events);
      c.getDatabase("inventory").getCollection("items").insertOne(new Document("_id", "sku-1"));
    }
  }

  /** Makes the next {@code times} operations with {@code maxTimeMS} time out (the server's test fail point). */
  void failNextWithTimeLimit(int times) {
    try (MongoClient c = root()) {
      c.getDatabase("admin").runCommand(new Document("configureFailPoint", "maxTimeAlwaysTimeOut")
          .append("mode", new Document("times", times)));
    }
  }

  void clearFailPoint() {
    try (MongoClient c = root()) {
      c.getDatabase("admin").runCommand(new Document("configureFailPoint", "maxTimeAlwaysTimeOut")
          .append("mode", "off"));
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

  private static Path certificates(Path dir) throws Exception {
    Files.writeString(dir.resolve("ext.cnf"), "subjectAltName=DNS:localhost,IP:127.0.0.1\n");
    run(dir, List.of("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", "ca.key", "-out",
        "ca.pem", "-days", "2", "-subj", "/CN=gw-test-ca"));
    run(dir, List.of("openssl", "req", "-newkey", "rsa:2048", "-nodes", "-keyout", "server.key", "-out",
        "server.csr", "-subj", "/CN=localhost"));
    run(dir, List.of("openssl", "x509", "-req", "-in", "server.csr", "-CA", "ca.pem", "-CAkey", "ca.key",
        "-CAcreateserial", "-out", "server.crt", "-days", "2", "-extfile", "ext.cnf"));
    Files.writeString(dir.resolve("server.pem"), Files.readString(dir.resolve("server.crt"))
        + Files.readString(dir.resolve("server.key")));
    return dir.resolve("ca.pem");
  }

  private static void run(Path dir, List<String> cmd) throws IOException, InterruptedException {
    Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
    String out = new String(p.getInputStream().readAllBytes());
    if (p.waitFor() != 0) {
      throw new IOException(String.join(" ", cmd) + " failed: " + out);
    }
  }

  private static String versionOf(Path binary) throws IOException, InterruptedException {
    Process p = new ProcessBuilder(binary.toString(), "--version").redirectErrorStream(true).start();
    String out = new String(p.getInputStream().readAllBytes());
    p.waitFor();
    int at = out.indexOf("db version v");
    return at < 0 ? "?" : out.substring(at + 12, out.indexOf('\n', at)).strip();
  }

  /** Whether this machine has the loopback address (macOS has only 127.0.0.1 unless aliased). */
  private static boolean canBind(String address) {
    try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getByName(address))) {
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  static int freePort() throws IOException {
    try (ServerSocket s = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
      return s.getLocalPort();
    }
  }

  private static String sha256(Path file) throws IOException {
    try (InputStream in = Files.newInputStream(file)) {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] buf = new byte[1 << 16];
      int n;
      while ((n = in.read(buf)) > 0) {
        md.update(buf, 0, n);
      }
      return HexFormat.of().formatHex(md.digest());
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
