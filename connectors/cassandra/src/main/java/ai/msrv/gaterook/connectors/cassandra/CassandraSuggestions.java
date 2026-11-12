package ai.msrv.gaterook.connectors.cassandra;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.Suggestions;
import ai.msrv.gaterook.connector.api.Suggestions.Option;
import ai.msrv.gaterook.connectors.support.OutputSanitizer;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.config.DriverExecutionProfile;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.loadbalancing.LoadBalancingPolicy;
import com.datastax.oss.driver.api.core.metadata.Node;
import com.datastax.oss.driver.internal.core.loadbalancing.BasicLoadBalancingPolicy;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.IntPredicate;

/**
 * Help for the Cassandra form ({@link CassandraConnector#suggest}). On this computer, while the fields are empty: the
 * host and port of cqlsh's {@code ~/.cassandra/cqlshrc} and its user (never its password), and {@code localhost:9042}
 * when a node answers there; offered, never filled in. Signed in on a session of its own, as Test connection does: the
 * transport Auto chose, the cluster's data centers with the contact points' one filled into an empty Local data center
 * ({@code x-suggest}: asked while it is still empty), and the keyspaces for Keyspace and Keyspaces.
 */
final class CassandraSuggestions {

  static final int PORT = 9042;
  static final String CQLSHRC = "~/.cassandra/cqlshrc";
  private static final long MAX_CQLSHRC = 64 * 1024;

  private CassandraSuggestions() {}

  /**
   * @param listening whether something accepts a TCP connection on a loopback port ({@code LocalPorts::listening})
   * @param cqlshrc   cqlsh's settings file ({@link #CQLSHRC} in the home folder)
   */
  static Suggestions suggest(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, Suggestions.Scope scope,
      IntPredicate listening, Path cqlshrc) {
    Suggestions.Builder out = Suggestions.builder();
    local(cfg, listening, cqlshrc, out);
    if (scope == Suggestions.Scope.SERVICE) {
      service(cfg, secrets, ctx, out);
    }
    return out.build();
  }

  // ── This computer ───────────────────────────────────────────────────────────────────────────────────────────

  /** What cqlsh connects to and as whom, from {@code [connection]} and {@code [authentication]}. */
  record Cqlsh(String hostname, Integer port, String username) {}

  private static void local(ConnectionConfig cfg, IntPredicate listening, Path cqlshrc, Suggestions.Builder out) {
    Cqlsh rc = cqlsh(cqlshrc);
    if (cfg.strings("/contactPoints").stream().allMatch(String::isBlank)) {
      List<Option> options = new ArrayList<>();
      if (rc != null && rc.hostname() != null) {
        String host = rc.hostname().indexOf(':') >= 0 ? "[" + rc.hostname() + "]" : rc.hostname();
        options.add(new Option(host + ":" + (rc.port() == null ? PORT : rc.port()), null, "cqlsh connects here ("
            + CQLSHRC + ")"));
      }
      if (listening.test(PORT) && options.stream().noneMatch(o -> o.value().startsWith("localhost:")
          || o.value().startsWith("127.0.0.1:"))) {
        options.add(new Option("localhost:" + PORT, null, "A Cassandra node answers on port " + PORT));
      }
      out.options("/contactPoints", "this computer", options);
    }
    String user = cfg.string(CassandraSettings.USER);
    if ((user == null || user.isBlank()) && rc != null && rc.username() != null) {
      out.options(CassandraSettings.USER, CQLSHRC, List.of(Option.of(rc.username(), "cqlsh signs in as this role")));
    }
  }

  /** cqlsh's host, port and user from its settings file, or {@code null}; the password is never read. */
  static Cqlsh cqlsh(Path file) {
    try {
      if (file == null || !Files.isRegularFile(file) || Files.size(file) > MAX_CQLSHRC) {
        return null;
      }
      String section = "";
      String host = null;
      Integer port = null;
      String user = null;
      for (String raw : Files.readAllLines(file)) {
        String line = raw.strip();
        if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
          continue;
        }
        if (line.startsWith("[") && line.endsWith("]")) {
          section = line.substring(1, line.length() - 1).strip().toLowerCase(Locale.ROOT);
          continue;
        }
        int eq = firstOf(line, '=', ':');
        if (eq <= 0) {
          continue;
        }
        String key = line.substring(0, eq).strip().toLowerCase(Locale.ROOT);
        String value = line.substring(eq + 1).strip();
        if (section.equals("connection") && key.equals("hostname") && plainName(value)) {
          host = value;
        } else if (section.equals("connection") && key.equals("port") && value.matches("\\d{1,5}")
            && Integer.parseInt(value) >= 1 && Integer.parseInt(value) <= 65_535) {
          port = Integer.valueOf(value);
        } else if (section.equals("authentication") && key.equals("username") && !value.isEmpty()
            && value.length() <= 256 && OutputSanitizer.sanitize(value).equals(value)) {
          user = value;
        }
      }
      return host == null && port == null && user == null ? null : new Cqlsh(host, port, user);
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }

  private static int firstOf(String line, char a, char b) {
    int i = line.indexOf(a);
    int j = line.indexOf(b);
    return i < 0 ? j : j < 0 ? i : Math.min(i, j);
  }

  private static boolean plainName(String host) {
    return host.matches("[A-Za-z0-9.-]{1,253}|[0-9A-Fa-f:.]{2,45}");
  }

  // ── The cluster ─────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * What a session of the draft saw.
   *
   * @param localDataCenter the configured data center, or the one the driver inferred from the contact points
   * @param keyspaces       the cluster's keyspaces but Cassandra's own, or {@code null} with {@link #problem}
   */
  record Facts(String cluster, Map<String, Integer> dataCenters, String localDataCenter, List<String> keyspaces,
      String problem) {}

  private static void service(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx,
      Suggestions.Builder out) {
    CassandraSettings chosen;
    try {
      chosen = CassandraSettings.draft(cfg);
    } catch (ConnectorException e) {
      out.note(e.getMessage());
      return;
    }
    try (CassandraSessions.Login login = CassandraSessions.login(chosen, secrets, ctx)) {
      List<InetSocketAddress> points = CassandraSessions.contactPoints(chosen);
      CassandraSettings s = AutoTls.resolve(chosen, points, CassandraSessions.autoBudget(chosen, ctx));
      CompletableFuture<CqlSession> opening = CassandraSessions.open("suggest-" + cfg.id(), s, login, points);
      CqlSession session;
      try {
        session = opening.get(Math.max(1, ctx.remaining().minus(Duration.ofMillis(500)).toMillis()),
            TimeUnit.MILLISECONDS);
      } catch (TimeoutException e) {
        close(opening);
        out.note("The cluster didn't let GateRook connect in time. Check the contact points and the port.");
        return;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        close(opening);
        return;
      } catch (ExecutionException e) {
        out.note(opening(e.getCause()));
        return;
      }
      try {
        apply(facts(session, s, ctx), chosen, s, login.user(), out);
      } finally {
        session.closeAsync();
      }
    } catch (ConnectorException e) {
      out.note(e.getMessage());
    }
  }

  /** Why a draft's session didn't open; contact points of several data centers can't infer one. */
  private static String opening(Throwable error) {
    Throwable t = CassandraErrors.unwrap(error);
    if (t instanceof IllegalStateException && String.valueOf(t.getMessage()).contains("different DCs")) {
      return "The contact points are in different data centers: enter Local data center.";
    }
    return CassandraSessions.opening(error).getMessage();
  }

  static Facts facts(CqlSession session, CassandraSettings s, ExecContext ctx) {
    Map<String, Integer> centers = new TreeMap<>();
    for (Node node : session.getMetadata().getNodes().values()) {
      if (node.getDatacenter() != null) {
        centers.merge(node.getDatacenter(), 1, Integer::sum);
      }
    }
    String local = s.localDataCenter();
    if (local == null) {
      LoadBalancingPolicy policy = session.getContext().getLoadBalancingPolicy(DriverExecutionProfile.DEFAULT_NAME);
      local = policy instanceof BasicLoadBalancingPolicy basic ? basic.getLocalDatacenter() : null;
    }
    List<String> keyspaces = new ArrayList<>();
    String problem = null;
    try {
      for (Row r : CassandraConnector.rows(session, SimpleStatement.newInstance(
          "SELECT keyspace_name FROM system_schema.keyspaces").setTimeout(Duration.ofSeconds(5)), 10_000, ctx,
          "Listing keyspaces")) {
        String name = r.getString("keyspace_name");
        if (name != null && !CassandraSettings.systemKeyspace(name) && !CassandraSettings.authTable(name, null)) {
          keyspaces.add(name);
        }
      }
    } catch (ConnectorException | RuntimeException e) {
      keyspaces = null;
      problem = e.getMessage();
    }
    return new Facts(session.getMetadata().getClusterName().orElse(null), centers, local,
        keyspaces == null ? null : keyspaces.stream().sorted().toList(), problem);
  }

  /** The suggestions of what a session saw. */
  static void apply(Facts f, CassandraSettings chosen, CassandraSettings used, String user, Suggestions.Builder out) {
    List<Option> centers = new ArrayList<>();
    f.dataCenters().forEach((dc, n) -> centers.add(new Option(dc, null, n + (n == 1 ? " node" : " nodes"))));
    String typed = chosen.localDataCenter();
    out.value("/localDataCenter", "the cluster", typed == null ? f.localDataCenter() : null, centers);
    if (chosen.autoTls()) {
      out.note(used.tls() ? "The nodes speak TLS: Auto connects with TLS."
          : "The nodes don't speak TLS: Auto connects without it, so the traffic isn't encrypted.");
    }
    if (typed != null && !f.dataCenters().containsKey(typed)) {
      out.note("The cluster has no data center " + typed + ": its data centers are " + String.join(", ",
          f.dataCenters().keySet()) + ".");
    }
    String dc = typed != null ? typed : f.localDataCenter();
    StringBuilder found = new StringBuilder(user == null ? "Connected without signing in" : "Signed in as " + user);
    if (f.cluster() != null) {
      found.append(" to ").append(OutputSanitizer.sanitize(CassandraErrors.cut(f.cluster(), 80)));
    }
    if (dc != null && f.dataCenters().containsKey(dc)) {
      int n = f.dataCenters().get(dc);
      found.append(": data center ").append(dc).append(" (").append(n).append(n == 1 ? " node)" : " nodes)");
    }
    if (f.keyspaces() == null) {
      out.note(found + ". The keyspaces weren't listed: " + f.problem());
      return;
    }
    List<Option> all = f.keyspaces().stream().map(Option::of).toList();
    out.options("/keyspaces", "the cluster", all);
    out.options("/keyspace", "the cluster", f.keyspaces().stream().filter(chosen::allows).map(Option::of).toList());
    int n = f.keyspaces().size();
    out.note(found + ". " + (n == 0 ? "The cluster has no keyspace of its own yet."
        : n + (n == 1 ? " keyspace" : " keyspaces") + ": choose the ones agents may use."));
  }

  private static void close(CompletableFuture<CqlSession> opening) {
    opening.whenComplete((session, error) -> {
      if (session != null) {
        session.closeAsync();
      }
    });
  }
}
