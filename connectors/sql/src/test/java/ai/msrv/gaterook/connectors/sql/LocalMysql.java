package ai.msrv.gaterook.connectors.sql;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * MySQL 8.4.10 or MariaDB 11.4.5 for the {@code mysql-it} ITs (report 04 §8), without Docker: the MySQL generic
 * tarball from cdn.mysql.com with {@code libaio.so.1} taken from Ubuntu's {@code libaio1t64} package (24.04 renamed
 * it), and MariaDB's binaries from the {@code mariaDB4j-db-linux64} jar on Maven Central. Downloads go to
 * {@code ~/.cache/mcp-gateway-test/sql} once, checked against pinned SHA-256 sums; each run gets a fresh data
 * directory under {@code target/} and an ephemeral port on 127.0.0.1. One server per flavor serves the whole run.
 *
 * <p>The seed matches {@link LocalPostgres}: database {@code app} with {@code customers}, {@code orders},
 * {@code order_items}, {@code audit_log} and the function {@code f_write()} that inserts; users {@code ro},
 * {@code rw}, {@code filer} ({@code FILE}), {@code admin} ({@code ALL PRIVILEGES ON *.*}) and {@code rotator}.
 * With the Compose services ({@link TestServices}) the server is theirs ({@code mysql:8.4.10}, {@code mariadb:11.4.5},
 * root without a password); a run starts by dropping every database and account an earlier run made, then makes the
 * seed.
 */
final class LocalMysql implements BeforeAllCallback {

  enum Flavor { MYSQL, MARIADB }

  static final Path CACHE = Path.of(System.getProperty("user.home"), ".cache", "mcp-gateway-test", "sql");

  static final String MYSQL_TARBALL = "mysql-8.4.10-linux-glibc2.28-x86_64-minimal.tar.xz";
  static final String MYSQL_URL = "https://cdn.mysql.com/Downloads/MySQL-8.4/" + MYSQL_TARBALL;
  static final String MYSQL_SHA256 = "88ac9cc85bed076c4c8c6e816c3369a6febfbb5db13e6e174f1d8971161e0b77";
  static final String LIBAIO_DEB = "libaio1t64_0.3.113-6build1.1_amd64.deb";
  static final String LIBAIO_URL = "http://archive.ubuntu.com/ubuntu/pool/main/liba/libaio/" + LIBAIO_DEB;
  static final String LIBAIO_SHA256 = "433212b33e26d424605c01cec1de33b63f2cc12870987b4cb7b3625baba66a19";
  static final String MARIADB_JAR = "mariaDB4j-db-linux64-11.4.5.jar";
  static final String MARIADB_URL = "https://repo1.maven.org/maven2/ch/vorburger/mariaDB4j/mariaDB4j-db-linux64/"
      + "11.4.5/" + MARIADB_JAR;
  static final String MARIADB_SHA256 = "5c6dcab86a79b6cd6b8957ce48a66a97312150ee86fab8ae658495ab09841854";

  private static final ExtensionContext.Namespace NS = ExtensionContext.Namespace.create(LocalMysql.class);

  private final Flavor flavor;
  private Server server;

  private LocalMysql(Flavor flavor) {
    this.flavor = flavor;
  }

  static LocalMysql mysql() {
    return new LocalMysql(Flavor.MYSQL);
  }

  static LocalMysql mariaDb() {
    return new LocalMysql(Flavor.MARIADB);
  }

  static String password(String user) {
    return user + "-pw-0001";
  }

  @Override
  public void beforeAll(ExtensionContext context) {
    server = context.getRoot().getStore(NS).computeIfAbsent(flavor,
        k -> TestServices.enabled() ? Server.compose(flavor) : Server.start(flavor), Server.class);
  }

  Flavor flavor() {
    return flavor;
  }

  int port() {
    return server.port;
  }

  Connection connect(String user, String password) throws SQLException {
    return DriverManager.getConnection("jdbc:mysql://127.0.0.1:" + server.port + "/app?sslMode=PREFERRED"
        + "&allowPublicKeyRetrieval=true", user, password);
  }

  Connection root() throws SQLException {
    return connect("root", "");
  }

  void exec(String... statements) throws SQLException {
    try (Connection c = root(); Statement st = c.createStatement()) {
      for (String s : statements) {
        st.execute(s);
      }
    }
  }

  /** A running server; closed by JUnit with the root context. */
  static final class Server implements AutoCloseable {
    final Flavor flavor;
    final Path dir;
    final int port;
    Process process;

    private Server(Flavor flavor, Path dir, int port) {
      this.flavor = flavor;
      this.dir = dir;
      this.port = port;
    }

    static Server start(Flavor flavor) {
      try {
        Path dir = Path.of("target", flavor.name().toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8))
            .toAbsolutePath();
        Files.createDirectories(dir);
        Server s = new Server(flavor, dir, freePort());
        if (flavor == Flavor.MYSQL) {
          s.startMysql();
        } else {
          s.startMariaDb();
        }
        s.seed();
        return s;
      } catch (IOException | InterruptedException | SQLException e) {
        throw new IllegalStateException(flavor + " didn't start", e);
      }
    }

    /** The Compose service of the flavor, its databases and users of the seed dropped and made again. */
    static Server compose(Flavor flavor) {
      Server s = new Server(flavor, null, TestServices.port(flavor == Flavor.MYSQL ? "GW_MYSQL_PORT"
          : "GW_MARIADB_PORT"));
      try (Connection c = DriverManager.getConnection("jdbc:mysql://127.0.0.1:" + s.port
          + "/?sslMode=PREFERRED&allowPublicKeyRetrieval=true", "root", ""); Statement st = c.createStatement()) {
        // Every database and account a run made goes, as on a fresh server.
        List<String> databases = new ArrayList<>();
        try (var rs = st.executeQuery("SELECT schema_name FROM information_schema.schemata WHERE schema_name NOT IN "
            + "('mysql', 'information_schema', 'performance_schema', 'sys')")) {
          while (rs.next()) {
            databases.add(rs.getString(1));
          }
        }
        for (String db : databases) {
          st.execute("DROP DATABASE `" + db.replace("`", "``") + "`");
        }
        List<String> users = new ArrayList<>();
        try (var rs = st.executeQuery("SELECT user, host FROM mysql.user WHERE user NOT IN ('root', 'mysql.sys', "
            + "'mysql.session', 'mysql.infoschema', 'mariadb.sys', 'healthcheck')")) {
          while (rs.next()) {
            users.add("'" + rs.getString(1).replace("'", "''") + "'@'" + rs.getString(2).replace("'", "''") + "'");
          }
        }
        for (String user : users) {
          st.execute("DROP USER " + user);
        }
      } catch (SQLException e) {
        throw new IllegalStateException(flavor + " of the test services (scripts/test-services.sh up sql) isn't "
            + "reachable on 127.0.0.1:" + s.port, e);
      }
      try {
        s.seed();
      } catch (SQLException e) {
        throw new IllegalStateException(flavor + " of the test services didn't take the seed", e);
      }
      return s;
    }

    private boolean root() {
      return "root".equals(System.getProperty("user.name"));
    }

    private void startMysql() throws IOException, InterruptedException, SQLException {
      Path tarball = fetch(MYSQL_URL, MYSQL_TARBALL, MYSQL_SHA256);
      Path base = CACHE.resolve(MYSQL_TARBALL.replace(".tar.xz", ""));
      if (!Files.isExecutable(base.resolve("bin/mysqld"))) {
        Path tmp = Files.createTempDirectory(CACHE, "extract-");
        run(List.of("tar", "-xJf", tarball.toString(), "-C", tmp.toString()), Map.of(), Duration.ofMinutes(5));
        Files.move(tmp.resolve(base.getFileName()), base, StandardCopyOption.ATOMIC_MOVE);
        delete(tmp);
      }
      Path deb = fetch(LIBAIO_URL, LIBAIO_DEB, LIBAIO_SHA256);
      Path aio = CACHE.resolve("libaio1t64-0.3.113");
      Path libs = aio.resolve("usr/lib/x86_64-linux-gnu");
      if (!Files.exists(libs.resolve("libaio.so.1"))) {
        Path tmp = Files.createTempDirectory(CACHE, "extract-");
        run(List.of("dpkg-deb", "-x", deb.toString(), tmp.toString()), Map.of(), Duration.ofMinutes(1));
        Files.createSymbolicLink(tmp.resolve("usr/lib/x86_64-linux-gnu/libaio.so.1"), Path.of("libaio.so.1t64"));
        Files.move(tmp, aio, StandardCopyOption.ATOMIC_MOVE);
      }
      Map<String, String> env = Map.of("LD_LIBRARY_PATH", libs.toString());
      Path data = dir.resolve("data");
      List<String> init = new ArrayList<>(List.of(base.resolve("bin/mysqld").toString(), "--no-defaults",
          "--initialize-insecure", "--basedir=" + base, "--datadir=" + data));
      List<String> serve = new ArrayList<>(List.of(base.resolve("bin/mysqld").toString(), "--no-defaults",
          "--basedir=" + base, "--datadir=" + data, "--port=" + port, "--bind-address=127.0.0.1",
          "--socket=" + dir.resolve("s"), "--mysqlx=OFF", "--log-error=" + dir.resolve("err.log"),
          "--pid-file=" + dir.resolve("pid"), "--disable-log-bin", "--innodb-buffer-pool-size=64M",
          "--performance-schema=OFF"));
      if (root()) {
        init.add(2, "--user=root");
        serve.add(2, "--user=root");
      }
      run(init, env, Duration.ofMinutes(2));
      ProcessBuilder pb = new ProcessBuilder(serve).redirectErrorStream(true)
          .redirectOutput(dir.resolve("out.log").toFile());
      pb.environment().putAll(env);
      process = pb.start();
      awaitReady();
    }

    private void startMariaDb() throws IOException, InterruptedException, SQLException {
      Path jar = fetch(MARIADB_URL, MARIADB_JAR, MARIADB_SHA256);
      Path base = CACHE.resolve("mariadb-11.4.5");
      if (!Files.isExecutable(base.resolve("bin/mariadbd"))) {
        Path tmp = Files.createTempDirectory(CACHE, "extract-");
        run(List.of("unzip", "-q", jar.toString(), "ch/vorburger/mariadb4j/mariadb-11.4.5/linux/*", "-d",
            tmp.toString()), Map.of(), Duration.ofMinutes(5));
        Path linux = tmp.resolve("ch/vorburger/mariadb4j/mariadb-11.4.5/linux");
        try (Stream<Path> files = Stream.concat(Files.list(linux.resolve("bin")), Files.list(linux.resolve(
            "scripts")))) {
          for (Path f : files.toList()) {
            f.toFile().setExecutable(true);
          }
        }
        Files.move(linux, base, StandardCopyOption.ATOMIC_MOVE);
        delete(tmp);
      }
      Map<String, String> env = Map.of("LD_LIBRARY_PATH", base.resolve("libs").toString());
      Path data = dir.resolve("data");
      List<String> init = new ArrayList<>(List.of(base.resolve("scripts/mariadb-install-db").toString(),
          "--no-defaults", "--basedir=" + base, "--datadir=" + data, "--auth-root-authentication-method=normal",
          "--skip-test-db"));
      List<String> serve = new ArrayList<>(List.of(base.resolve("bin/mariadbd").toString(), "--no-defaults",
          "--basedir=" + base, "--datadir=" + data, "--port=" + port, "--bind-address=127.0.0.1",
          "--socket=" + dir.resolve("s"), "--lc-messages-dir=" + base.resolve("share"),
          "--log-error=" + dir.resolve("err.log"), "--pid-file=" + dir.resolve("pid"),
          "--innodb-buffer-pool-size=64M"));
      if (root()) {
        init.add(2, "--user=root");
        serve.add(2, "--user=root");
      }
      run(init, env, Duration.ofMinutes(2));
      ProcessBuilder pb = new ProcessBuilder(serve).redirectErrorStream(true)
          .redirectOutput(dir.resolve("out.log").toFile());
      pb.environment().putAll(env);
      process = pb.start();
      awaitReady();
    }

    private void awaitReady() throws InterruptedException, IOException, SQLException {
      long until = System.nanoTime() + Duration.ofSeconds(90).toNanos();
      SQLException last = null;
      while (System.nanoTime() < until) {
        if (!process.isAlive()) {
          throw new IOException(flavor + " exited: " + log());
        }
        try (Connection c = DriverManager.getConnection("jdbc:mysql://127.0.0.1:" + port
            + "/?sslMode=PREFERRED&allowPublicKeyRetrieval=true", "root", "")) {
          return;
        } catch (SQLException e) {
          last = e;
          Thread.sleep(250);
        }
      }
      throw new IOException(flavor + " didn't accept connections: " + log(), last);
    }

    private String log() throws IOException {
      Path err = dir.resolve("err.log");
      return Files.exists(err) ? Files.readString(err) : "";
    }

    private void seed() throws SQLException {
      List<String> seed = new ArrayList<>(List.of(
          "CREATE DATABASE app",
          "CREATE TABLE app.customers (id BIGINT PRIMARY KEY, email VARCHAR(200) NOT NULL UNIQUE, name VARCHAR(100), "
              + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP) COMMENT 'People who order'",
          "CREATE TABLE app.orders (id BIGINT PRIMARY KEY AUTO_INCREMENT, customer_id BIGINT, status VARCHAR(20) "
              + "NOT NULL, total DECIMAL(12, 2) NOT NULL DEFAULT 0, note TEXT, flags BIT(1) NULL, "
              + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, "
              + "CONSTRAINT fk_customer FOREIGN KEY (customer_id) REFERENCES app.customers (id), "
              + "CONSTRAINT status_ok CHECK (status IN ('pending', 'paid', 'shipped', 'cancelled')), "
              + "INDEX orders_status_idx (status, created_at)) COMMENT 'One row per order'",
          "CREATE TABLE app.order_items (id BIGINT PRIMARY KEY AUTO_INCREMENT, order_id BIGINT NOT NULL, "
              + "sku VARCHAR(20) NOT NULL, qty INT NOT NULL, "
              + "FOREIGN KEY (order_id) REFERENCES app.orders (id) ON DELETE CASCADE)",
          "CREATE TABLE app.audit_log (id BIGINT PRIMARY KEY AUTO_INCREMENT, what VARCHAR(50) NOT NULL)",
          "CREATE FUNCTION app.f_write() RETURNS INT MODIFIES SQL DATA BEGIN INSERT INTO app.audit_log (what) "
              + "VALUES ('f_write'); RETURN 1; END",
          "INSERT INTO app.customers (id, email, name) VALUES (1, 'ada@example.com', 'Ada'), "
              + "(2, 'bob@example.com', 'Bob')",
          "INSERT INTO app.orders (id, customer_id, status, total, note, created_at) VALUES "
              + "(48213, 1, 'pending', 120.50, 'call first', NOW() - INTERVAL 2 HOUR), "
              + "(48240, 2, 'pending', 9.99, NULL, NOW() - INTERVAL 3 DAY), "
              + "(48241, 1, 'shipped', 42.00, NULL, NOW() - INTERVAL 1 HOUR)",
          "INSERT INTO app.order_items (order_id, sku, qty) VALUES (48213, 'A-1', 2), (48213, 'B-7', 1), "
              + "(48240, 'C-3', 5)",
          "CREATE DATABASE secret_stuff",
          "CREATE TABLE secret_stuff.`keys` (id INT PRIMARY KEY, value TEXT)",
          "CREATE USER 'ro'@'%' IDENTIFIED BY '" + password("ro") + "'",
          "CREATE USER 'rw'@'%' IDENTIFIED BY '" + password("rw") + "'",
          "CREATE USER 'filer'@'%' IDENTIFIED BY '" + password("filer") + "'",
          "CREATE USER 'admin'@'%' IDENTIFIED BY '" + password("admin") + "'",
          "CREATE USER 'rotator'@'%' IDENTIFIED BY '" + password("rotator") + "'",
          "GRANT SELECT ON app.* TO 'ro'@'%'",
          "GRANT SELECT, INSERT, UPDATE, DELETE, EXECUTE ON app.* TO 'rw'@'%'",
          "GRANT SELECT ON app.* TO 'filer'@'%'",
          "GRANT FILE ON *.* TO 'filer'@'%'",
          "GRANT ALL PRIVILEGES ON *.* TO 'admin'@'%' WITH GRANT OPTION",
          "GRANT SELECT ON app.* TO 'rotator'@'%'"));
      if (flavor == Flavor.MARIADB) {
        seed.add("CREATE SEQUENCE app.ticket_seq");
        seed.add("GRANT INSERT ON app.ticket_seq TO 'rw'@'%'");
      }
      try (Connection c = DriverManager.getConnection("jdbc:mysql://127.0.0.1:" + port
          + "/?sslMode=PREFERRED&allowPublicKeyRetrieval=true", "root", ""); Statement st = c.createStatement()) {
        for (String sql : seed) {
          st.execute(sql);
        }
      }
    }

    @Override
    public void close() throws Exception {
      if (dir == null) {
        return; // the Compose service keeps running
      }
      if (process != null) {
        process.destroy();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
          process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        }
      }
      delete(dir);
    }

    private void run(List<String> command, Map<String, String> env, Duration timeout)
        throws IOException, InterruptedException {
      ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
      pb.environment().putAll(env);
      Process p = pb.start();
      String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (!p.waitFor(timeout.toSeconds(), TimeUnit.SECONDS) || p.exitValue() != 0) {
        throw new IOException(String.join(" ", command) + " failed:\n" + out);
      }
    }
  }

  /** A pinned download in the shared cache, fetched once and checked every time. */
  static Path fetch(String url, String name, String sha256) throws IOException, InterruptedException {
    Files.createDirectories(CACHE);
    Path target = CACHE.resolve(name);
    if (!Files.exists(target)) {
      Path part = CACHE.resolve(name + ".part");
      HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
          .connectTimeout(Duration.ofSeconds(30)).build();
      HttpResponse<Path> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10))
          .build(), HttpResponse.BodyHandlers.ofFile(part));
      if (r.statusCode() != 200) {
        Files.deleteIfExists(part);
        throw new IOException(url + " answered " + r.statusCode());
      }
      Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
    }
    String actual = sha256(target);
    if (!actual.equals(sha256)) {
      throw new IOException(target + " has SHA-256 " + actual + ", expected " + sha256 + ": delete it to download "
          + "again");
    }
    return target;
  }

  private static String sha256(Path file) throws IOException {
    try (InputStream in = Files.newInputStream(file)) {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[1 << 16];
      for (int n = in.read(buffer); n > 0; n = in.read(buffer)) {
        md.update(buffer, 0, n);
      }
      return HexFormat.of().formatHex(md.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void delete(Path dir) throws IOException {
    if (!Files.exists(dir)) {
      return;
    }
    try (Stream<Path> files = Files.walk(dir)) {
      files.sorted(Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
    }
  }

  private static int freePort() throws IOException {
    try (ServerSocket s = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
      return s.getLocalPort();
    }
  }
}
