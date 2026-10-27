package ai.msrv.gaterook.connectors.mongodb;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The parts of a connection string the gateway checks before the driver sees it (report 05 §4): user information,
 * hosts, the default database and the option names. The string is split exactly the way the driver's
 * {@code ConnectionString} splits it (first {@code /} or {@code ?} ends the hosts, the last {@code @} before them ends
 * the user information, options are separated by {@code &} or {@code ;}), so what is checked here is what the
 * driver reads: a string can't hide user information or an option from this parser and still reach the driver with
 * it. No DNS: the TXT records of {@code mongodb+srv://} may add only {@code authSource}, {@code replicaSet} and
 * {@code loadBalanced}, which the driver enforces itself.
 *
 * @param srv      {@code mongodb+srv://}
 * @param userInfo whether the string carries a user name or a password
 * @param hosts    the host list as written ({@code host[:port]}, one for {@code +srv})
 * @param database the default database, or {@code null}
 * @param options  option names in lower case → decoded values (the last one when an option repeats)
 */
record MongoUri(boolean srv, boolean userInfo, List<String> hosts, String database, Map<String, String> options) {

  static final String PREFIX = "mongodb://";
  static final String SRV_PREFIX = "mongodb+srv://";

  /** Parses {@code uri}; a string the driver would refuse to parse is an {@link IllegalArgumentException}. */
  static MongoUri parse(String uri) {
    if (uri == null || uri.isBlank()) {
      throw new IllegalArgumentException("Enter the connection string: mongodb://host:27017/database.");
    }
    boolean srv = uri.startsWith(SRV_PREFIX);
    if (!srv && !uri.startsWith(PREFIX)) {
      throw new IllegalArgumentException("The connection string must start with mongodb:// or mongodb+srv://.");
    }
    String rest = uri.substring(srv ? SRV_PREFIX.length() : PREFIX.length());
    int slash = rest.indexOf('/');
    int question = rest.indexOf('?');
    String userAndHosts;
    if (question == -1 && slash == -1) {
      userAndHosts = rest;
      rest = "";
    } else if (question != -1 && (slash == -1 || question < slash)) {
      userAndHosts = rest.substring(0, question);
      rest = rest.substring(question);
    } else {
      userAndHosts = rest.substring(0, slash);
      rest = rest.substring(slash + 1);
    }
    int at = userAndHosts.lastIndexOf('@');
    // The driver takes any '@' at an index > 0 as the end of user information and refuses one at index 0.
    boolean userInfo = at >= 0;
    String hostPart = at >= 0 ? userAndHosts.substring(at + 1) : userAndHosts;
    List<String> hosts = new ArrayList<>();
    for (String h : hostPart.split(",", -1)) {
      if (h.isBlank()) {
        throw new IllegalArgumentException("The connection string has an empty host.");
      }
      checkPort(h);
      hosts.add(h);
    }
    if (srv && hosts.size() != 1) {
      throw new IllegalArgumentException("mongodb+srv:// takes exactly one host name.");
    }
    if (srv && hosts.getFirst().contains(":")) {
      throw new IllegalArgumentException("mongodb+srv:// takes a host name without a port.");
    }
    String database = null;
    String query = "";
    int q = rest.indexOf('?');
    String ns = q == -1 ? rest : rest.substring(0, q);
    if (q != -1) {
      query = rest.substring(q + 1);
    }
    if (!ns.isEmpty()) {
      String decoded = decode(ns);
      int dot = decoded.indexOf('.');
      database = dot < 0 ? decoded : decoded.substring(0, dot);
      if (database.isEmpty() || database.chars().anyMatch(c -> "/\\ \"$\0".indexOf(c) >= 0)) {
        throw new IllegalArgumentException("The connection string names an invalid database: " + database + ".");
      }
    }
    Map<String, String> options = new LinkedHashMap<>();
    if (!query.isEmpty()) {
      for (String part : query.split("[&;]")) {
        if (part.isEmpty()) {
          continue;
        }
        int eq = part.indexOf('=');
        if (eq < 0) {
          throw new IllegalArgumentException("The connection string option " + part + " has no value.");
        }
        options.put(part.substring(0, eq).toLowerCase(Locale.ROOT), decode(part.substring(eq + 1)));
      }
    }
    return new MongoUri(srv, userInfo, List.copyOf(hosts), database, Map.copyOf(options));
  }

  /** {@code host:port[,…]/database} for lists and texts: never user information, never options. */
  String display() {
    String h = String.join(",", hosts);
    return database == null ? h : h + "/" + database;
  }

  /** The first host as written, for error texts ("Can't resolve mongo.dev.internal"). */
  String firstHost() {
    String h = hosts.getFirst();
    if (h.startsWith("[")) {
      int end = h.indexOf(']');
      return end > 0 ? h.substring(1, end) : h;
    }
    int colon = h.lastIndexOf(':');
    return colon > 0 && h.indexOf(':') == colon ? h.substring(0, colon) : h;
  }

  /** The port of the first host, 27017 when none is written. */
  int firstPort() {
    String h = hosts.getFirst();
    int colon = h.lastIndexOf(':');
    int close = h.lastIndexOf(']');
    if (colon > close && (h.startsWith("[") || h.indexOf(':') == colon)) {
      try {
        return Integer.parseInt(h.substring(colon + 1));
      } catch (NumberFormatException e) {
        return 27017;
      }
    }
    return 27017;
  }

  /** {@code host:port} or {@code [v6]:port}: the port, when there is one, is a number the driver accepts. */
  private static void checkPort(String host) {
    String port = null;
    if (host.startsWith("[")) {
      int end = host.indexOf(']');
      if (end < 0) {
        throw new IllegalArgumentException("The connection string has an unclosed [ in " + host + ".");
      }
      if (end + 1 < host.length()) {
        if (host.charAt(end + 1) != ':') {
          throw new IllegalArgumentException("The connection string has text after ] in " + host + ".");
        }
        port = host.substring(end + 2);
      }
    } else if (host.indexOf(':') >= 0) {
      if (host.indexOf(':') != host.lastIndexOf(':')) {
        throw new IllegalArgumentException("Put an IPv6 address in brackets: [" + host + "].");
      }
      port = host.substring(host.indexOf(':') + 1);
    }
    if (port != null) {
      int p;
      try {
        p = Integer.parseInt(port);
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("The port of " + host + " isn't a number.");
      }
      if (p < 1 || p > 65_535) {
        throw new IllegalArgumentException("The port of " + host + " isn't between 1 and 65535.");
      }
    }
  }

  String option(String name) {
    return options.get(name.toLowerCase(Locale.ROOT));
  }

  private static String decode(String s) {
    try {
      return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("The connection string has a broken %-escape.");
    }
  }
}
