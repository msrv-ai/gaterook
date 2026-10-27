package ai.msrv.gaterook.e2e.sql;

import ai.msrv.gaterook.e2e.TestServices;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * A PostgreSQL of the machine's own binaries for the end-to-end tests ({@code $GW_PG_BIN} or the newest
 * {@code /usr/lib/postgresql/<n>/bin}), run through {@code runuser -u postgres} as root, data under {@code target/},
 * on an ephemeral port. The connector module's ITs have the full fixture; this one seeds what the core's pipeline
 * needs: {@code customers} with e-mail addresses, {@code orders}, {@code audit_log}, the writing function
 * {@code f_write()}, and the users {@code gw_rw} (may write) and {@code gw_ro}. With the Compose services
 * ({@link TestServices}) the server is theirs and the seed is made afresh: database {@code app} and both users.
 */
final class LocalPg implements AutoCloseable {

  static final String SUPERUSER_PASSWORD = TestServices.enabled() ? TestServices.value("GW_PG_PASSWORD")
      : "postgres-e2e-0001";

  final Path dir;
  final Path bin;
  final int port;
  private final boolean asRoot = "root".equals(System.getProperty("user.name"));

  private LocalPg(Path dir, Path bin, int port) {
    this.dir = dir;
    this.bin = bin;
    this.port = port;
  }

  /** A started server, or {@code null} without binaries. */
  static LocalPg startIfAvailable() throws IOException, InterruptedException, SQLException {
    if (TestServices.enabled()) {
      LocalPg pg = new LocalPg(null, null, TestServices.port("GW_PG_PORT"));
      try (Connection c = pg.connect("postgres", "postgres", SUPERUSER_PASSWORD); Statement st = c.createStatement()) {
        st.execute("DROP DATABASE IF EXISTS app WITH (FORCE)");
        st.execute("DROP ROLE IF EXISTS gw_rw");
        st.execute("DROP ROLE IF EXISTS gw_ro");
        st.execute("CREATE DATABASE app");
      }
      pg.seed();
      return pg;
    }
    Path bin = binaries();
    if (bin == null) {
      return null;
    }
    Path dir = Path.of("target", "e2e-pg-" + UUID.randomUUID().toString().substring(0, 8)).toAbsolutePath();
    Files.createDirectories(dir);
    int port;
    try (ServerSocket s = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
      port = s.getLocalPort();
    }
    LocalPg pg = new LocalPg(dir, bin, port);
    pg.init();
    return pg;
  }

  static String password(String user) {
    return user + "-e2e-pw-0001";
  }

  private void init() throws IOException, InterruptedException, SQLException {
    Path pwfile = dir.resolve("pwfile");
    Files.writeString(pwfile, SUPERUSER_PASSWORD + "\n");
    if (asRoot) {
      run(List.of("chown", "-R", "postgres:postgres", dir.toString()), false);
    }
    run(List.of(bin.resolve("initdb").toString(), "-D", dir.resolve("data").toString(), "-U", "postgres",
        "--auth-local=trust", "--auth-host=scram-sha-256", "--pwfile=" + pwfile, "--no-sync", "-E", "UTF8",
        "--locale=C.UTF-8"), true);
    run(List.of(bin.resolve("pg_ctl").toString(), "-D", dir.resolve("data").toString(), "-o", "-p " + port + " -k "
        + dir + " -c listen_addresses=127.0.0.1 -c fsync=off -c full_page_writes=off", "-l",
        dir.resolve("log").toString(), "-w", "-t", "60", "start"), true);
    try (Connection c = connect("postgres", "postgres", SUPERUSER_PASSWORD); Statement st = c.createStatement()) {
      st.execute("CREATE DATABASE app");
    }
    seed();
  }

  private void seed() throws SQLException {
    try (Connection c = connect("app", "postgres", SUPERUSER_PASSWORD); Statement st = c.createStatement()) {
      for (String sql : List.of(
          "CREATE ROLE gw_rw LOGIN PASSWORD '" + password("gw_rw") + "'",
          "CREATE ROLE gw_ro LOGIN PASSWORD '" + password("gw_ro") + "'",
          "CREATE TABLE customers (id bigint PRIMARY KEY, email text NOT NULL, name text)",
          "CREATE TABLE orders (id bigint PRIMARY KEY, customer_id bigint REFERENCES customers (id), status text "
              + "NOT NULL, total numeric(12, 2) NOT NULL DEFAULT 0, created_at timestamptz NOT NULL DEFAULT now())",
          "CREATE TABLE audit_log (id bigserial PRIMARY KEY, what text NOT NULL)",
          "CREATE FUNCTION f_write() RETURNS int LANGUAGE plpgsql AS $$ BEGIN INSERT INTO audit_log (what) "
              + "VALUES ('f_write'); RETURN 1; END $$",
          "INSERT INTO customers VALUES (1, 'ada@example.com', 'Ada'), (2, 'bob@example.com', 'Bob')",
          "INSERT INTO orders (id, customer_id, status, total, created_at) VALUES "
              + "(48213, 1, 'pending', 120.50, now() - interval '2 hours'), "
              + "(48240, 2, 'pending', 9.99, now() - interval '3 days'), "
              + "(48241, 1, 'shipped', 42.00, now() - interval '1 hour')",
          "GRANT USAGE ON SCHEMA public TO gw_rw, gw_ro",
          "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO gw_rw",
          "GRANT SELECT ON ALL TABLES IN SCHEMA public TO gw_ro",
          "GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO gw_rw")) {
        st.execute(sql);
      }
    }
  }

  Connection connect(String database, String user, String password) throws SQLException {
    return DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + port + "/" + database, user, password);
  }

  long count(String sql) throws SQLException {
    try (Connection c = connect("app", "postgres", SUPERUSER_PASSWORD); Statement st = c.createStatement();
         ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private void run(List<String> command, boolean asPostgres) throws IOException, InterruptedException {
    List<String> full = new ArrayList<>();
    if (asPostgres && asRoot) {
      full.addAll(List.of("runuser", "-u", "postgres", "--"));
    }
    full.addAll(command);
    Process p = new ProcessBuilder(full).redirectErrorStream(true).start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (!p.waitFor(90, TimeUnit.SECONDS) || p.exitValue() != 0) {
      throw new IOException(String.join(" ", full) + " failed:\n" + out);
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
          .max(Comparator.comparing(b -> b.getParent().getFileName().toString(), Comparator.comparingInt(n -> {
            try {
              return Integer.parseInt(n);
            } catch (NumberFormatException e) {
              return 0;
            }
          }))).orElse(null);
    } catch (IOException e) {
      return null;
    }
  }
}
