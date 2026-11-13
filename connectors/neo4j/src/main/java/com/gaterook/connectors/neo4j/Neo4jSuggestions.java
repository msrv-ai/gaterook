package com.gaterook.connectors.neo4j;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.ExecContext;
import com.gaterook.connector.api.SecretResolver;
import com.gaterook.connector.api.Suggestions;
import com.gaterook.connector.api.Suggestions.Option;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.IntPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.neo4j.driver.AccessMode;
import org.neo4j.driver.Record;
import org.neo4j.driver.Value;
import org.neo4j.driver.exceptions.ServiceUnavailableException;

/**
 * Help for the Neo4j form ({@link Neo4jConnector#suggest}): which URI scheme the server needs, and its databases.
 *
 * <p>On this computer. A URI typed without a scheme gets one when the field is left: {@code neo4j+s://} for another
 * computer, which GateRook reaches only with TLS, and for this computer the scheme its port speaks, found with a TLS
 * hello and, when that meets no TLS, a Bolt handshake ({@link LoopbackBolt}); nothing that signs in is sent. An empty URI
 * is offered {@code neo4j://localhost:7687} (or {@code neo4j+s://}) when a server answers there, never filled in. A
 * scheme that doesn't fit gets a note and the one that does: no TLS to another computer, or TLS to a local port that
 * doesn't speak it, and back.
 *
 * <p>Signed in on a driver of its own, as Test connection does: the databases the user may use, with the home
 * database filled into Database, and for {@code bolt://} to a member of a cluster, that {@code neo4j://} routes.
 */
final class Neo4jSuggestions {

  static final String THIS_COMPUTER = "this computer";
  /** {@code host}, {@code host:port} or {@code [v6]:port}, as people type a server without a scheme. */
  private static final Pattern ADDRESS = Pattern.compile(
      "^(\\[[0-9A-Fa-f:.]+]|[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?)(?::(\\d{1,5}))?/?$");
  private static final Pattern WITH_SCHEME = Pattern.compile(
      "^(neo4j|bolt)(\\+s)?://(\\[[0-9A-Fa-f:.]+]|[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?)(?::(\\d{1,5}))?/?$",
      Pattern.CASE_INSENSITIVE);
  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  private Neo4jSuggestions() {}

  /**
   * @param listening whether something accepts a TCP connection on a loopback port ({@code LocalPorts::listening})
   * @param probe     what a port of this computer speaks ({@link LoopbackBolt#probe}); never asked for another host
   */
  static Suggestions suggest(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, Suggestions.Scope scope,
      Bolt.Source bolts, IntPredicate listening, BiFunction<String, Integer, LoopbackBolt.Transport> probe) {
    Suggestions.Builder out = Suggestions.builder();
    local(cfg, listening, probe, out);
    if (scope == Suggestions.Scope.SERVICE) {
      service(cfg, secrets, ctx, bolts, out);
    }
    return out.build();
  }

  // ── The scheme ──────────────────────────────────────────────────────────────────────────────────────────────

  private static void local(ConnectionConfig cfg, IntPredicate listening,
      BiFunction<String, Integer, LoopbackBolt.Transport> probe, Suggestions.Builder out) {
    String typed = cfg.string("/uri");
    typed = typed == null ? "" : typed.strip();
    if (typed.isEmpty()) {
      if (listening.test(Neo4jSettings.DEFAULT_PORT)) {
        LoopbackBolt.Transport t = probe.apply("localhost", Neo4jSettings.DEFAULT_PORT);
        boolean tls = t == LoopbackBolt.Transport.TLS || t == LoopbackBolt.Transport.UNTRUSTED;
        String address = "localhost:" + Neo4jSettings.DEFAULT_PORT;
        String detail = "A Neo4j server answers on port " + Neo4jSettings.DEFAULT_PORT
            + (tls ? " with TLS" : t == LoopbackBolt.Transport.PLAIN ? " without TLS" : "");
        out.options("/uri", THIS_COMPUTER, List.of(new Option((tls ? "neo4j+s://" : "neo4j://") + address, null, detail),
            new Option((tls ? "bolt+s://" : "bolt://") + address, null, detail + "; one server, no routing")));
      }
      return;
    }
    if (!typed.contains("://")) {
      Matcher m = ADDRESS.matcher(typed);
      if (!m.matches()) {
        return;
      }
      String host = m.group(1);
      int port = m.group(2) == null ? Neo4jSettings.DEFAULT_PORT : Integer.parseInt(m.group(2));
      String address = host + ":" + port;
      if (!Neo4jSettings.loopback(host)) {
        out.value("/uri", "the URI you typed", "neo4j+s://" + typed, List.of(Option.of("neo4j+s://" + typed,
            "a cluster or Aura: the cluster routes each call"), Option.of("bolt+s://" + typed, "one server")));
        out.note("GateRook reaches " + host + " only with TLS: neo4j+s:// is added when you leave the field.");
        return;
      }
      LoopbackBolt.Transport t = port < 1 || port > 65_535 ? LoopbackBolt.Transport.NONE : probe.apply(unbracket(host),
          port);
      String source = "a check of " + address;
      switch (t) {
        case TLS -> {
          out.value("/uri", source, "neo4j+s://" + typed, List.of(Option.of("neo4j+s://" + typed),
              Option.of("bolt+s://" + typed)));
          out.note(address + " speaks TLS: neo4j+s:// is added when you leave the field.");
        }
        case UNTRUSTED -> {
          out.value("/uri", source, "neo4j+s://" + typed, List.of(Option.of("neo4j+s://" + typed),
              Option.of("bolt+s://" + typed)));
          out.note(address + " speaks TLS, but its certificate isn't trusted on this computer: neo4j+s:// is added. "
              + "Paste the server's CA certificate under Advanced settings.");
        }
        case PLAIN -> {
          out.value("/uri", source, "neo4j://" + typed, List.of(Option.of("neo4j://" + typed),
              Option.of("bolt://" + typed)));
          out.note(address + " answers without TLS: neo4j:// is added when you leave the field.");
        }
        default -> out.note("Nothing answers as Neo4j on " + address + ". Check the port, or type the URI with "
            + "neo4j:// or neo4j+s://.");
      }
      return;
    }
    Matcher m = WITH_SCHEME.matcher(typed);
    if (!m.matches()) {
      return;
    }
    String scheme = m.group(1).toLowerCase(Locale.ROOT);
    boolean tls = m.group(2) != null;
    String host = m.group(3);
    String rest = typed.substring(typed.indexOf("://") + 3);
    if (!tls && !Neo4jSettings.loopback(host)) {
      out.options("/uri", "the URI you typed", List.of(Option.of(scheme + "+s://" + rest)));
      out.note("Without TLS GateRook connects only to this computer: use " + scheme + "+s://" + rest + ".");
      return;
    }
    if (!Neo4jSettings.loopback(host)) {
      return;
    }
    int port = m.group(4) == null ? Neo4jSettings.DEFAULT_PORT : Integer.parseInt(m.group(4));
    if (port < 1 || port > 65_535) {
      return;
    }
    LoopbackBolt.Transport t = probe.apply(unbracket(host), port);
    boolean speaksTls = t == LoopbackBolt.Transport.TLS || t == LoopbackBolt.Transport.UNTRUSTED;
    String address = host + ":" + port;
    if (speaksTls && !tls) {
      out.options("/uri", "a check of " + address, List.of(Option.of(scheme + "+s://" + rest)));
      out.note(address + " speaks TLS: use " + scheme + "+s://" + rest + ".");
    } else if (t == LoopbackBolt.Transport.PLAIN && tls) {
      out.options("/uri", "a check of " + address, List.of(Option.of(scheme + "://" + rest)));
      out.note(address + " doesn't speak TLS: use " + scheme + "://" + rest + ".");
    } else if (t == LoopbackBolt.Transport.UNTRUSTED) {
      out.note(address + " speaks TLS, but its certificate isn't trusted on this computer. Paste the server's CA "
          + "certificate under Advanced settings.");
    }
  }

  private static String unbracket(String host) {
    return host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
  }

  // ── The databases ───────────────────────────────────────────────────────────────────────────────────────────

  /** One database as {@code SHOW DATABASES} lists it. */
  record Database(String name, String status, String access, boolean home, boolean isDefault) {}

  private static void service(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, Bolt.Source bolts,
      Suggestions.Builder out) {
    Neo4jSettings s;
    Neo4jAuth auth;
    try {
      s = Neo4jSettings.of(cfg);
      auth = Neo4jAuth.resolve(s, secrets, ctx);
    } catch (ConnectorException e) {
      out.note(e.getMessage());
      return;
    }
    try (Bolt.Lease lease = bolts.fresh(s, auth, ctx)) {
      Bolt.Client client = lease.client();
      Map<String, Database> found = new LinkedHashMap<>();
      String agent;
      try (Bolt.Tx tx = client.begin(AccessMode.READ, "system", TIMEOUT, Neo4jConnector.metadata(ctx, "suggest"),
          100)) {
        Bolt.Run run = tx.run("SHOW DATABASES", Map.of());
        while (run.hasNext() && found.size() < Suggestions.MAX_OPTIONS) {
          Record r = run.next();
          String name = text(r, "name");
          if (name == null || name.equalsIgnoreCase("system") || !Neo4jSettings.DATABASE.matcher(name).matches()) {
            continue;
          }
          // A cluster lists a database once per server: one is enough, its home and default flags are the same.
          found.putIfAbsent(name, new Database(name, text(r, "currentStatus"), text(r, "access"), flag(r, "home"),
              flag(r, "default")));
        }
        agent = run.consume().agent();
      }
      List<Option> options = new ArrayList<>();
      String home = null;
      String fallback = null;
      for (Database d : found.values()) {
        List<String> facts = new ArrayList<>();
        if (d.status() != null) {
          facts.add(d.status());
        }
        if (d.access() != null) {
          facts.add(d.access());
        }
        if (d.home()) {
          facts.add("your home database");
          home = d.name();
        }
        if (d.isDefault() && fallback == null) {
          fallback = d.name();
        }
        options.add(new Option(d.name(), null, facts.isEmpty() ? null : String.join(" · ", facts)));
      }
      out.value("/database", "the server", home != null ? home : fallback, options);
      String user = auth.user() == null ? "the bearer token's user" : auth.user();
      out.note("Signed in as " + user + (agent == null ? "" : " to " + agent.replace('/', ' ')) + ": " + found.size()
          + (found.size() == 1 ? " database" : " databases") + ". Choose the one every call runs in.");
      if (!s.routing()) {
        cluster(client, s, ctx, out);
      }
    } catch (ConnectorException e) {
      out.note(e.getMessage());
    } catch (RuntimeException e) {
      out.note(Neo4jErrors.read(e, s, TIMEOUT).getMessage() + (s.routing() && e instanceof ServiceUnavailableException
          ? " With " + s.scheme() + "://, the driver goes on to the addresses the server announces; if this computer "
              + "can't reach them (a port mapped by Docker), use " + (s.tls() ? "bolt+s" : "bolt") + "://"
              + s.address() + "." : ""));
    }
  }

  /** For {@code bolt://}: a server that is one of several in its routing table belongs to a cluster. */
  private static void cluster(Bolt.Client client, Neo4jSettings s, ExecContext ctx, Suggestions.Builder out) {
    Set<String> servers = new LinkedHashSet<>();
    try (Bolt.Tx tx = client.begin(AccessMode.READ, s.database(), TIMEOUT, Neo4jConnector.metadata(ctx, "suggest"),
        10)) {
      Bolt.Run run = tx.run("CALL dbms.routing.getRoutingTable({}, $database) YIELD servers RETURN servers",
          Map.of("database", s.database()));
      while (run.hasNext()) {
        for (Value server : run.next().get("servers").values()) {
          for (Value address : server.get("addresses").values()) {
            servers.add(address.asString());
          }
        }
      }
      run.consume();
    } catch (RuntimeException e) {
      // No routing table for this user or server: nothing to say.
      return;
    }
    if (servers.size() > 1) {
      String uri = "neo4j" + (s.tls() ? "+s" : "") + "://" + s.address();
      out.options("/uri", "the server", List.of(Option.of(uri, "the cluster routes each call")));
      out.note(s.address() + " is one of " + servers.size() + " servers of a cluster: " + uri + " lets the cluster "
          + "route each call, reads to its secondaries.");
    }
  }

  private static String text(Record r, String key) {
    if (!r.containsKey(key)) {
      return null;
    }
    Value v = r.get(key);
    return v == null || v.isNull() ? null : v.asString();
  }

  private static boolean flag(Record r, String key) {
    if (!r.containsKey(key)) {
      return false;
    }
    Value v = r.get(key);
    return v != null && !v.isNull() && v.asBoolean(false);
  }
}
