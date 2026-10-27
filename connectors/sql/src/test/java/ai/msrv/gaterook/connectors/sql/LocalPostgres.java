package ai.msrv.gaterook.connectors.sql;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * A PostgreSQL server of the machine's own binaries for the ITs (report 04 §8): {@code $GW_PG_BIN} or the newest
 * {@code /usr/lib/postgresql/<n>/bin}, a fresh cluster under {@code target/}, an ephemeral port on 127.0.0.1, no
 * fsync. PostgreSQL refuses to run as root, so as root every command goes through {@code runuser -u postgres}. One
 * server serves every IT class of the run; JUnit stops it at the end. Without binaries the ITs are skipped. With the
 * Compose services ({@link TestServices}) it is their PostgreSQL instead: the same superuser password and a
 * self-signed certificate for {@code localhost}; a run starts by dropping every database and role an earlier run made,
 * then makes the seed.
 *
 * <p>The seed: database {@code app} with {@code orders}, {@code customers}, {@code order_items}, schema
 * {@code reporting} with a view, the function {@code f_write()} that inserts, a sequence; roles {@code ro} (reads
 * {@code public}), {@code rw} (writes it), {@code signal} ({@code pg_signal_backend}) and the superuser
 * {@code postgres}. Passwords are the role names followed by {@code -pw-0001}, so they are long enough to rotate.
 */
final class LocalPostgres implements BeforeAllCallback {

  static final String SUPERUSER_PASSWORD = "postgres-pw-0001";

  private static final ExtensionContext.Namespace NS = ExtensionContext.Namespace.create(LocalPostgres.class);

  private Server server;

  static String password(String role) {
    return role + "-pw-0001";
  }

  @Override
  public void beforeAll(ExtensionContext context) {
    server = context.getRoot().getStore(NS).computeIfAbsent(Server.class,
        k -> TestServices.enabled() ? Server.compose() : Server.start(), Server.class);
  }

  int port() {
    return server.port;
  }

  String version() {
    return server.version;
  }

  /** The server's self-signed certificate (PEM), for {@code caCertificate}. */
  String certificatePem() throws IOException {
    return Files.readString(server.certificate);
  }

  /** A JDBC connection as {@code user} to {@code database}, for setup and checks outside the connector. */
  Connection connect(String database, String user, String password) throws SQLException {
    Properties p = new Properties();
    p.setProperty("user", user);
    p.setProperty("password", password);
    return DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + server.port + "/" + database, p);
  }

  Connection superuser() throws SQLException {
    return connect("app", "postgres", SUPERUSER_PASSWORD);
  }

  void exec(String... statements) throws SQLException {
    try (Connection c = superuser(); Statement st = c.createStatement()) {
      for (String s : statements) {
        st.execute(s);
      }
    }
  }

  /** The running server; closed by JUnit with the root context. */
  static final class Server implements AutoCloseable {
    final Path dir;
    final Path bin;
    final int port;
    final boolean asRoot;
    Path certificate;
    String version;

    private Server(Path dir, Path bin, int port, boolean asRoot) {
      this.dir = dir;
      this.bin = bin;
      this.port = port;
      this.asRoot = asRoot;
    }

    static Server start() {
      Path bin = binaries();
      Assumptions.assumeTrue(bin != null, "No PostgreSQL binaries: set GW_PG_BIN or install postgresql-16");
      try {
        boolean root = "root".equals(System.getProperty("user.name"));
        Path dir = Path.of("target", "pg-" + UUID.randomUUID().toString().substring(0, 8)).toAbsolutePath();
        Files.createDirectories(dir);
        Server s = new Server(dir, bin, freePort(), root);
        s.init();
        return s;
      } catch (IOException | InterruptedException | SQLException e) {
        throw new IllegalStateException("Local PostgreSQL didn't start", e);
      }
    }

    /** The Compose service's server ({@code run/certs/postgres.crt} is its certificate), seeded afresh. */
    static Server compose() {
      if (!SUPERUSER_PASSWORD.equals(TestServices.value("GW_PG_PASSWORD"))) {
        throw new IllegalStateException("GW_PG_PASSWORD of test-services/.env must be " + SUPERUSER_PASSWORD);
      }
      Server s = new Server(null, null, TestServices.port("GW_PG_PORT"), false);
      s.certificate = TestServices.file("run/certs/postgres.crt");
      try {
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + s.port + "/postgres",
            "postgres", SUPERUSER_PASSWORD); Statement st = c.createStatement()) {
          wipe(st);
          st.execute("CREATE DATABASE app");
          try (var rs = st.executeQuery("SHOW server_version")) {
            rs.next();
            s.version = rs.getString(1);
          }
        }
        s.seed();
        return s;
      } catch (SQLException e) {
        throw new IllegalStateException("PostgreSQL of the test services (scripts/test-services.sh up sql) isn't "
            + "reachable on 127.0.0.1:" + s.port, e);
      }
    }

    /**
     * Everything a run made on a shared server — databases other than {@code postgres} and the templates, roles other
     * than {@code postgres} and the built-in {@code pg_*} — goes, as on a fresh cluster.
     */
    static void wipe(Statement st) throws SQLException {
      List<String> databases = new ArrayList<>();
      try (var rs = st.executeQuery("SELECT datname FROM pg_database WHERE datname NOT IN ('postgres', 'template0', "
          + "'template1')")) {
        while (rs.next()) {
          databases.add(rs.getString(1));
        }
      }
      for (String db : databases) {
        st.execute("DROP DATABASE " + quote(db) + " WITH (FORCE)");
      }
      List<String> roles = new ArrayList<>();
      try (var rs = st.executeQuery("SELECT rolname FROM pg_roles WHERE rolname <> 'postgres' AND rolname NOT LIKE "
          + "'pg\\_%'")) {
        while (rs.next()) {
          roles.add(rs.getString(1));
        }
      }
      for (String role : roles) {
        st.execute("DROP OWNED BY " + quote(role));
        st.execute("DROP ROLE " + quote(role));
      }
    }

    private static String quote(String identifier) {
      return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private void init() throws IOException, InterruptedException, SQLException {
      Path pwfile = dir.resolve("pwfile");
      Files.writeString(pwfile, SUPERUSER_PASSWORD + "\n");
      // A self-signed certificate for localhost only: verify-full passes for "localhost" and fails for 127.0.0.1.
      Path tls = dir.resolve("tls");
      Files.createDirectories(tls);
      run(List.of("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", tls.resolve("server.key")
          .toString(), "-out", tls.resolve("server.crt").toString(), "-days", "2", "-subj", "/CN=localhost",
          "-addext", "subjectAltName=DNS:localhost"), false);
      run(List.of("chmod", "600", tls.resolve("server.key").toString()), false);
      if (asRoot) {
        run(List.of("chown", "-R", "postgres:postgres", dir.toString()), false);
      }
      run(List.of(bin.resolve("initdb").toString(), "-D", dir.resolve("data").toString(), "-U", "postgres",
          "--auth-local=trust", "--auth-host=scram-sha-256", "--pwfile=" + pwfile, "--no-sync", "-E", "UTF8",
          "--locale=C.UTF-8"), true);
      run(List.of(bin.resolve("pg_ctl").toString(), "-D", dir.resolve("data").toString(), "-o",
          "-p " + port + " -k " + dir + " -c listen_addresses=127.0.0.1 -c fsync=off -c full_page_writes=off "
              + "-c synchronous_commit=off -c max_connections=100 -c ssl=on -c ssl_cert_file=" + tls.resolve(
              "server.crt") + " -c ssl_key_file=" + tls.resolve("server.key"), "-l", dir.resolve("log").toString(),
          "-w",
          "-t", "60", "start"), true);
      try (Connection c = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + port + "/postgres",
          "postgres", SUPERUSER_PASSWORD); Statement st = c.createStatement()) {
        st.execute("CREATE DATABASE app");
        try (var rs = st.executeQuery("SHOW server_version")) {
          rs.next();
          version = rs.getString(1);
        }
      }
      certificate = tls.resolve("server.crt");
      seed();
    }

    private void seed() throws SQLException {
      try (Connection c = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + port + "/app", "postgres",
          SUPERUSER_PASSWORD); Statement st = c.createStatement()) {
        for (String sql : SEED) {
          st.execute(sql);
        }
      }
    }

    static final List<String> SEED = List.of(
        "CREATE ROLE ro LOGIN PASSWORD '" + password("ro") + "'",
        "CREATE ROLE rw LOGIN PASSWORD '" + password("rw") + "'",
        "CREATE ROLE signal LOGIN PASSWORD '" + password("signal") + "'",
        "CREATE ROLE rotator LOGIN PASSWORD '" + password("rotator") + "'",
        "GRANT pg_signal_backend TO signal",
        "REVOKE CREATE ON SCHEMA public FROM PUBLIC",
        "CREATE TABLE customers (id bigint PRIMARY KEY, email text NOT NULL UNIQUE, name text, "
            + "created_at timestamptz NOT NULL DEFAULT now())",
        "COMMENT ON TABLE customers IS 'People who order'",
        "COMMENT ON COLUMN customers.email IS 'Login and receipts'",
        "CREATE TABLE orders (id bigserial PRIMARY KEY, customer_id bigint REFERENCES customers (id), "
            + "status text NOT NULL CHECK (status IN ('pending', 'paid', 'shipped', 'cancelled')), "
            + "total numeric(12, 2) NOT NULL DEFAULT 0, note text, meta jsonb, tags text[], "
            + "created_at timestamptz NOT NULL DEFAULT now())",
        "CREATE INDEX orders_status_idx ON orders (status, created_at DESC)",
        "CREATE TABLE order_items (id bigserial PRIMARY KEY, order_id bigint NOT NULL REFERENCES orders (id) "
            + "ON DELETE CASCADE, sku text NOT NULL, qty int NOT NULL)",
        "CREATE TABLE audit_log (id bigserial PRIMARY KEY, what text NOT NULL)",
        "CREATE SEQUENCE ticket_seq",
        "CREATE FUNCTION f_write() RETURNS int LANGUAGE plpgsql AS $$ BEGIN INSERT INTO audit_log (what) "
            + "VALUES ('f_write'); RETURN 1; END $$",
        "CREATE FUNCTION f_read(x int) RETURNS int LANGUAGE sql IMMUTABLE AS $$ SELECT x * 2 $$",
        "CREATE PROCEDURE p_write() LANGUAGE sql AS $$ INSERT INTO audit_log (what) VALUES ('p_write') $$",
        "INSERT INTO customers (id, email, name) VALUES (1, 'ada@example.com', 'Ada'), (2, 'bob@example.com', 'Bob')",
        "INSERT INTO orders (id, customer_id, status, total, note, meta, tags, created_at) VALUES "
            + "(48213, 1, 'pending', 120.50, 'call first', '{\"vip\": true}', '{a,b}', now() - interval '2 hours'), "
            + "(48240, 2, 'pending', 9.99, NULL, '{}', '{}', now() - interval '3 days'), "
            + "(48241, 1, 'shipped', 42.00, NULL, NULL, NULL, now() - interval '1 hour')",
        "INSERT INTO order_items (order_id, sku, qty) VALUES (48213, 'A-1', 2), (48213, 'B-7', 1), (48240, 'C-3', 5)",
        "CREATE SCHEMA reporting",
        "CREATE VIEW reporting.daily_totals AS SELECT date_trunc('day', created_at) AS day, sum(total) AS total "
            + "FROM orders GROUP BY 1",
        "CREATE SCHEMA secret_stuff",
        "CREATE TABLE secret_stuff.keys (id int PRIMARY KEY, value text)",
        "GRANT USAGE ON SCHEMA public, reporting TO ro, rw, signal",
        "GRANT SELECT ON ALL TABLES IN SCHEMA public, reporting TO ro, signal",
        "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO rw",
        "GRANT SELECT ON ALL TABLES IN SCHEMA reporting TO rw",
        "GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO ro, rw",
        "GRANT EXECUTE ON FUNCTION f_write() TO ro, rw");

    private void run(List<String> command, boolean asPostgres) throws IOException, InterruptedException {
      List<String> full = new ArrayList<>();
      if (asPostgres && asRoot) {
        full.addAll(List.of("runuser", "-u", "postgres", "--"));
      }
      full.addAll(command);
      Process p = new ProcessBuilder(full).redirectErrorStream(true).start();
      String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (!p.waitFor(90, TimeUnit.SECONDS) || p.exitValue() != 0) {
        String log = Files.exists(dir.resolve("log")) ? Files.readString(dir.resolve("log")) : "";
        throw new IOException(String.join(" ", full) + " failed:\n" + out + "\n" + log);
      }
    }

    @Override
    public void close() throws Exception {
      if (dir == null) {
        return; // the Compose service keeps running
      }
      try {
        run(List.of(bin.resolve("pg_ctl").toString(), "-D", dir.resolve("data").toString(), "-m", "immediate", "-w",
            "stop"), true);
      } finally {
        try (Stream<Path> files = Files.walk(dir)) {
          files.sorted(Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
        }
      }
    }

    private static Path binaries() {
      String env = System.getenv("GW_PG_BIN");
      if (env != null && Files.isExecutable(Path.of(env, "initdb"))) {
        return Path.of(env);
      }
      Path root = Path.of("/usr/lib/postgresql");
      if (!Files.isDirectory(root)) {
        return null;
      }
      try (Stream<Path> versions = Files.list(root)) {
        return versions.map(v -> v.resolve("bin")).filter(b -> Files.isExecutable(b.resolve("initdb")))
            .max(Comparator.comparingInt(b -> {
              try {
                return Integer.parseInt(b.getParent().getFileName().toString());
              } catch (NumberFormatException e) {
                return 0;
              }
            })).orElse(null);
      } catch (IOException e) {
        return null;
      }
    }

    private static int freePort() throws IOException {
      try (ServerSocket s = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
        return s.getLocalPort();
      }
    }
  }
}
