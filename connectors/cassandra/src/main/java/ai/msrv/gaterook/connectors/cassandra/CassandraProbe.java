package ai.msrv.gaterook.connectors.cassandra;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.TestResult.Check;
import ai.msrv.gaterook.connector.api.TestResult.Hint;
import ai.msrv.gaterook.connector.api.TestResult.Level;
import ai.msrv.gaterook.connector.api.TestResult.Severity;
import ai.msrv.gaterook.connectors.support.OutputSanitizer;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.metadata.Node;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Test connection: a session of its own (nothing cached), the cluster as {@code system.local} and
 * {@code system.peers} describe it — its name, release and data centers, and whether the local data center has
 * nodes — the keyspaces agents may use, and what the role may do: {@code LIST ROLES OF} for a superuser and
 * {@code LIST ALL PERMISSIONS OF} for its grants, where the cluster lets the role list them. When it can't, the test
 * says so and passes. Cassandra has no read-only session, so the hints always show the CQL of a role that may only
 * read.
 */
final class CassandraProbe {

  private static final JsonNodeFactory F = JsonNodeFactory.instance;
  /** Grants that let a role change data or schema. */
  private static final Set<String> WRITES = Set.of("MODIFY", "CREATE", "ALTER", "DROP");
  /** Grants that let a role change who may do what. */
  private static final Set<String> ADMIN = Set.of("AUTHORIZE");

  private CassandraProbe() {}

  static TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    CassandraSettings s;
    try {
      s = CassandraSettings.of(cfg);
    } catch (ConnectorException e) {
      return TestResult.failure("cassandra.settings", e.getMessage(), null);
    }
    CassandraSessions.Login login;
    try {
      login = CassandraSessions.login(s, secrets, ctx);
    } catch (ConnectorException e) {
      return TestResult.failure("cassandra.credentials", e.getMessage(), null);
    }
    try (login) {
      List<Check> checks = new ArrayList<>();
      long started = System.nanoTime();
      List<InetSocketAddress> points;
      try {
        points = CassandraSessions.contactPoints(s);
      } catch (ConnectorException e) {
        return TestResult.failure(e.kind() == ConnectorException.Kind.DENIED ? "cassandra.address"
            : "cassandra.unreachable", e.getMessage(), null);
      }
      CompletableFuture<CqlSession> opening = CassandraSessions.open("test-" + cfg.id(), s, login, points);
      CqlSession session;
      try {
        session = opening.get(Math.max(1, ctx.remaining().minus(Duration.ofMillis(500)).toMillis()),
            TimeUnit.MILLISECONDS);
      } catch (TimeoutException e) {
        close(opening);
        return TestResult.failure("cassandra.unreachable", "The cluster didn't let GateRook connect within the "
            + "test's time. Check the contact points, the port and TLS.", null);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        close(opening);
        return TestResult.failure("cassandra.unreachable", "The test was stopped while it connected.", null);
      } catch (ExecutionException e) {
        ConnectorException ce = CassandraSessions.opening(e.getCause());
        return TestResult.failure(code(ce), ce.getMessage(), technical(e.getCause()));
      }
      try {
        long connected = ms(started);
        checks.add(new Check("connect", true, connected, (login.user() == null ? "Connected without signing in"
            : "Signed in as " + login.user()) + (s.tls() ? " over TLS" + (s.verifyHostname() ? "" : " (CA only)")
            : " without TLS") + " to " + s.contactPointsText()));
        return probe(cfg, s, login, session, ctx, checks, connected);
      } finally {
        session.closeAsync();
      }
    }
  }

  private static void close(CompletableFuture<CqlSession> opening) {
    opening.whenComplete((session, error) -> {
      if (session != null) {
        session.closeAsync();
      }
    });
  }

  private static String code(ConnectorException e) {
    String m = e.getMessage();
    if (m.startsWith("Cassandra refused the sign-in")) {
      return "cassandra.auth";
    }
    if (m.startsWith("TLS")) {
      return "cassandra.tls";
    }
    if (m.startsWith("The connection's keyspace")) {
      return "cassandra.keyspace";
    }
    if (m.startsWith("No node of the local data center")) {
      return "cassandra.local-dc";
    }
    return "cassandra.unreachable";
  }

  private static String technical(Throwable t) {
    Throwable root = CassandraErrors.rootOf(CassandraErrors.unwrap(t));
    return OutputSanitizer.sanitize(CassandraErrors.cut(root.getClass().getSimpleName() + ": "
        + CassandraErrors.firstLine(String.valueOf(root.getMessage())), 300));
  }

  private static TestResult probe(ConnectionConfig cfg, CassandraSettings s, CassandraSessions.Login login,
      CqlSession session, ExecContext ctx, List<Check> checks, long latency) {
    // Data centers as the control connection read them from system.local and system.peers.
    Map<String, Integer> centers = new TreeMap<>();
    for (Node node : session.getMetadata().getNodes().values()) {
      centers.merge(node.getDatacenter() == null ? "?" : node.getDatacenter(), 1, Integer::sum);
    }
    if (!centers.containsKey(s.localDataCenter())) {
      return TestResult.failure("cassandra.local-dc", "The cluster has no data center " + s.localDataCenter()
          + ": its data centers are " + String.join(", ", centers.keySet()) + ". Set Local data center to one of "
          + "them.", null).withChecks(checks);
    }
    long t0 = System.nanoTime();
    String cluster;
    String release;
    String dse = null;
    try {
      Row local = CassandraConnector.rows(session, statement("SELECT * FROM system.local", ctx), 1, ctx,
          "Reading system.local").getFirst();
      cluster = local.getColumnDefinitions().contains("cluster_name") ? local.getString("cluster_name") : null;
      release = local.getColumnDefinitions().contains("release_version") ? local.getString("release_version") : null;
      if (local.getColumnDefinitions().contains("dse_version")) {
        dse = local.getString("dse_version");
      }
      for (Row peer : CassandraConnector.rows(session, statement("SELECT data_center FROM system.peers", ctx), 1_000,
          ctx, "Reading system.peers")) {
        // The driver's view already counts the peers; this read checks the role may read system tables.
        peer.getString("data_center");
      }
    } catch (ConnectorException | RuntimeException e) {
      boolean unauthorized = CassandraErrors.causeOf(e, com.datastax.oss.driver.api.core.servererrors
          .UnauthorizedException.class) != null;
      return TestResult.failure(unauthorized ? "cassandra.permissions" : "cassandra.query", "Connected, but reading "
          + "the cluster's system tables failed: " + e.getMessage(), null).withChecks(checks);
    }
    String product = product(s, session, ctx, dse);
    String version = product + (release == null ? "" : " " + release);
    List<String> dcText = new ArrayList<>();
    centers.forEach((dc, n) -> dcText.add(dc + " (" + n + (n == 1 ? " node" : " nodes") + ")"));
    checks.add(new Check("cluster", true, ms(t0), (cluster == null ? "" : clean(cluster) + " · ") + version
        + " · data centers " + String.join(", ", dcText)));
    checks.add(new Check("local data center", true, 0, centers.get(s.localDataCenter()) + " node"
        + (centers.get(s.localDataCenter()) == 1 ? "" : "s") + " in " + s.localDataCenter() + " serve requests"));
    // Keyspaces agents may use.
    long t1 = System.nanoTime();
    List<String> visible = new ArrayList<>();
    try {
      for (Row r : CassandraConnector.rows(session, statement("SELECT keyspace_name FROM system_schema.keyspaces",
          ctx), 10_000, ctx, "Listing keyspaces")) {
        if (s.allows(r.getString("keyspace_name"))) {
          visible.add(r.getString("keyspace_name"));
        }
      }
      checks.add(new Check("keyspaces", true, ms(t1), visible.size() + (visible.size() == 1 ? " keyspace" :
          " keyspaces") + " agents may use" + (visible.isEmpty() ? "" : ": " + String.join(", ",
          visible.subList(0, Math.min(5, visible.size()))) + (visible.size() > 5 ? ", …" : ""))));
    } catch (ConnectorException e) {
      checks.add(new Check("keyspaces", false, ms(t1), e.getMessage()));
    }
    // What the role may do, where the cluster lets it say so.
    Grants grants = login.user() == null ? null : grants(session, login.user(), ctx);
    TestResult.Permissions permissions = permissions(grants);
    if (grants != null) {
      checks.add(new Check("permissions", grants.problem() == null, 0, grants.problem() == null ? permissions.read()
          + (permissions.write() == null ? "" : " · writes: " + permissions.write()) : grants.problem()));
    }
    ObjectNode details = F.objectNode();
    if (cluster != null) {
      details.put("cluster", clean(cluster));
    }
    ObjectNode dcs = details.putObject("dataCenters");
    centers.forEach(dcs::put);
    details.putObject("tls").put("enabled", s.tls()).put("verify", s.tls() ? (s.verifyHostname() ? "full" : "ca")
        : "none");
    details.put("readConsistency", s.readConsistency());
    ObjectNode metadata = F.objectNode().put("serverVersion", version);
    if (cluster != null) {
      metadata.put("cluster", clean(cluster));
    }
    ArrayNode dcList = metadata.putArray("dataCenters");
    centers.keySet().forEach(dcList::add);
    TestResult result = TestResult.success(latency, version, login.user() == null ? "anonymous" : login.user(),
        permissions).withChecks(checks).withDetails(details).withMetadata(metadata);
    for (Hint h : hints(cfg.env(), s, login.user(), grants, permissions, centers, visible)) {
      result = result.withHint(h);
    }
    return result;
  }

  private static SimpleStatement statement(String cql, ExecContext ctx) {
    Duration left = ctx.remaining().minus(CassandraConnector.MARGIN);
    Duration timeout = left.compareTo(Duration.ofSeconds(5)) > 0 ? Duration.ofSeconds(5) : left;
    return SimpleStatement.newInstance(cql).setTimeout(timeout.compareTo(Duration.ofMillis(100)) < 0
        ? Duration.ofMillis(100) : timeout);
  }

  /** The service behind the contact points: Amazon Keyspaces and Azure Cosmos DB by their names, else the release. */
  private static String product(CassandraSettings s, CqlSession session, ExecContext ctx, String dse) {
    String host = s.contactPoints().getFirst().host().toLowerCase(Locale.ROOT);
    if (host.endsWith(".amazonaws.com") || host.endsWith(".amazonaws.com.cn")) {
      return "Amazon Keyspaces";
    }
    if (host.endsWith(".cosmos.azure.com")) {
      return "Azure Cosmos DB for Apache Cassandra";
    }
    if (dse != null) {
      return "DataStax Enterprise " + clean(dse) + " · Cassandra";
    }
    try {
      List<Row> table = CassandraConnector.rows(session, statement("SELECT table_name FROM system_schema.tables "
          + "WHERE keyspace_name = 'system' AND table_name = 'versions'", ctx), 1, ctx, "Looking for ScyllaDB");
      if (!table.isEmpty()) {
        List<Row> v = CassandraConnector.rows(session, statement("SELECT version FROM system.versions WHERE "
            + "key = 'local'", ctx), 1, ctx, "Reading ScyllaDB's version");
        return "ScyllaDB" + (v.isEmpty() || v.getFirst().isNull("version") ? "" : " " + clean(
            v.getFirst().getString("version"))) + " · CQL";
      }
    } catch (ConnectorException | RuntimeException e) {
      // Not ScyllaDB, or it doesn't say: the release is the answer.
    }
    return "Apache Cassandra";
  }

  /**
   * What {@code LIST ROLES OF} and {@code LIST ALL PERMISSIONS OF} said about the role.
   *
   * @param grants  {@code [permission, resource]} pairs
   * @param problem why the cluster didn't say, or {@code null}
   */
  record Grants(boolean superuser, List<String[]> grants, String problem) {}

  static Grants grants(CqlSession session, String user, ExecContext ctx) {
    String role = "'" + user.replace("'", "''") + "'";
    boolean superuser = false;
    try {
      for (Row r : CassandraConnector.rows(session, statement("LIST ROLES OF " + role, ctx), 100, ctx,
          "Listing the role")) {
        if (r.getColumnDefinitions().contains("super") && !r.isNull("super") && r.getBoolean("super")) {
          superuser = true;
        }
      }
    } catch (ConnectorException | RuntimeException e) {
      return new Grants(false, List.of(), "Cassandra didn't list the role: " + reason(e));
    }
    List<String[]> grants = new ArrayList<>();
    try {
      for (Row r : CassandraConnector.rows(session, statement("LIST ALL PERMISSIONS OF " + role, ctx), 1_000, ctx,
          "Listing the role's permissions")) {
        if (r.getColumnDefinitions().contains("permission") && r.getColumnDefinitions().contains("resource")) {
          grants.add(new String[] {String.valueOf(r.getString("permission")).toUpperCase(Locale.ROOT),
              clean(String.valueOf(r.getString("resource")))});
        }
      }
    } catch (ConnectorException | RuntimeException e) {
      return new Grants(superuser, List.of(), "Cassandra didn't list the role's permissions: " + reason(e));
    }
    return new Grants(superuser, List.copyOf(grants), null);
  }

  private static String reason(Exception e) {
    String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    return OutputSanitizer.sanitize(CassandraErrors.cut(CassandraErrors.firstLine(m), 300));
  }

  static TestResult.Permissions permissions(Grants g) {
    if (g == null) {
      return new TestResult.Permissions(Level.UNKNOWN, "Whatever the cluster lets a client without sign-in do",
          null, List.of(), null);
    }
    ObjectNode details = F.objectNode().put("superuser", g.superuser());
    ArrayNode list = details.putArray("grants");
    g.grants().stream().limit(50).forEach(p -> list.addObject().put("permission", p[0]).put("resource", p[1]));
    if (g.superuser()) {
      return new TestResult.Permissions(Level.ADMIN, "Everything", "Everything", List.of("superuser"), details);
    }
    if (g.problem() != null) {
      details.put("problem", g.problem());
      return new TestResult.Permissions(Level.UNKNOWN, "Unknown", null, List.of(), details);
    }
    Set<String> reads = new TreeSet<>();
    Set<String> writes = new TreeSet<>();
    List<String> admin = new ArrayList<>();
    for (String[] p : g.grants()) {
      String grant = p[0] + " on " + p[1];
      if (ADMIN.contains(p[0]) || p[1].startsWith("<all roles") || p[1].startsWith("<role ")) {
        admin.add(grant);
      } else if (WRITES.contains(p[0])) {
        writes.add(grant);
      } else {
        reads.add(grant);
      }
    }
    Level level = !admin.isEmpty() ? Level.ADMIN : !writes.isEmpty() ? Level.WRITE : Level.READ;
    String read = reads.isEmpty() ? "No SELECT grants" : join(reads);
    return new TestResult.Permissions(level, read, writes.isEmpty() ? null : join(writes), List.copyOf(admin),
        details);
  }

  private static String join(Set<String> grants) {
    List<String> shown = new ArrayList<>(grants);
    return String.join(", ", shown.subList(0, Math.min(6, shown.size()))) + (shown.size() > 6 ? ", …" : "");
  }

  static List<Hint> hints(Env env, CassandraSettings s, String user, Grants grants,
      TestResult.Permissions permissions, Map<String, Integer> centers, List<String> visible) {
    List<Hint> out = new ArrayList<>();
    boolean dev = env == Env.DEV;
    String role = readerRole(s);
    if (user == null) {
      out.add(new Hint(dev ? Severity.INFO : Severity.WARNING, "cassandra.no-sign-in", "/credentials/user",
          "GateRook connects without signing in, so the cluster checks no permissions. Enable "
              + "PasswordAuthenticator and CassandraAuthorizer, and sign in with a role that may only read:\n"
              + role));
    } else if (permissions.level() == Level.ADMIN) {
      out.add(new Hint(dev ? Severity.WARNING : Severity.DANGER, "cassandra.admin-role", "/credentials/user",
          user + (grants != null && grants.superuser() ? " is a superuser" : " may grant permissions")
              + ": it may do everything, and Cassandra has no read-only session. Sign in with a role that may only "
              + "read:\n" + role));
    } else if (permissions.level() == Level.WRITE) {
      out.add(new Hint(dev ? Severity.INFO : Severity.WARNING, "cassandra.write-role", "/credentials/user",
          user + " may change data or schema (" + permissions.write() + "). GateRook runs reads only as SELECT, "
              + "but for reads alone a role with SELECT only is the safer boundary:\n" + role));
    } else if (permissions.level() == Level.UNKNOWN) {
      out.add(new Hint(Severity.INFO, "cassandra.unknown-permissions", "/credentials/user",
          (grants != null && grants.problem() != null ? grants.problem() + " " : "")
              + "Check that " + user + " may only read where agents only read:\n" + role));
    }
    out.add(new Hint(Severity.INFO, "cassandra.read-only-boundary", null, "Cassandra has no read-only session: "
        + "cassandra_query runs only what GateRook reads as one SELECT, and the role's grants are the boundary. "
        + "cassandra_execute needs MODIFY (and CREATE, ALTER or DROP for schema) on the keyspaces it changes."));
    if (!s.tls()) {
      out.add(new Hint(dev ? Severity.INFO : Severity.WARNING, "cassandra.no-tls", "/tls", "Traffic to the "
          + "cluster, the password included, isn't encrypted. Turn on TLS where the cluster offers it."));
    } else if (!s.verifyHostname()) {
      out.add(new Hint(dev ? Severity.INFO : Severity.WARNING, "cassandra.tls-ca-only", "/tlsVerify", "Certificates "
          + "are checked against the CA, not the node's name: any node certificate of that CA is accepted."));
    }
    if (centers.size() > 1) {
      out.add(new Hint(Severity.INFO, "cassandra.other-data-centers", "/localDataCenter", "Requests go only to the "
          + centers.get(s.localDataCenter()) + " node" + (centers.get(s.localDataCenter()) == 1 ? "" : "s") + " of "
          + s.localDataCenter() + "; the cluster also has " + (centers.size() - 1) + " other data center"
          + (centers.size() == 2 ? "" : "s") + "."));
    }
    if (visible.isEmpty()) {
      out.add(new Hint(Severity.WARNING, "cassandra.no-keyspaces", "/keyspaces", s.keyspaces().isEmpty()
          ? "The cluster has no keyspace of its own yet: agents see only what you create."
          : "No keyspace of the cluster matches the connection's list."));
    }
    if (s.keyspace() != null && !s.allows(s.keyspace())) {
      out.add(new Hint(Severity.WARNING, "cassandra.keyspace-not-allowed", "/keyspace", "The connection's keyspace "
          + s.keyspace() + " isn't one agents may use: statements that don't name a keyspace are refused."));
    }
    if (s.allowFiltering()) {
      out.add(new Hint(Severity.INFO, "cassandra.allow-filtering", "/advanced/allowFiltering", "Reads may use "
          + "ALLOW FILTERING on this dev connection; it scans partitions and stays refused on stage and prod."));
    }
    return out;
  }

  /** The CQL of a role that may only read the connection's keyspaces. */
  static String readerRole(CassandraSettings s) {
    List<String> keyspaces = new ArrayList<>();
    if (s.keyspace() != null) {
      keyspaces.add(s.keyspace());
    }
    for (String k : s.keyspaces()) {
      if (!k.contains("*") && !k.contains("?") && !keyspaces.contains(k)) {
        keyspaces.add(k);
      }
    }
    if (keyspaces.isEmpty()) {
      keyspaces.add("app");
    }
    StringBuilder cql = new StringBuilder("CREATE ROLE gw_reader WITH PASSWORD = '…' AND LOGIN = true;");
    for (String k : keyspaces.subList(0, Math.min(5, keyspaces.size()))) {
      cql.append("\nGRANT SELECT ON KEYSPACE ").append(Cql.quote(k)).append(" TO gw_reader;");
    }
    return cql.toString();
  }

  /** Text of the cluster's own (a name, a version): one line, sanitized and cut. */
  private static String clean(String text) {
    return OutputSanitizer.sanitize(CassandraErrors.cut(CassandraErrors.firstLine(text), 120));
  }

  private static long ms(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }
}
