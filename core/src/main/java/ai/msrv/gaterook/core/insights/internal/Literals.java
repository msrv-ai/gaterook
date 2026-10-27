package ai.msrv.gaterook.core.insights.internal;

import java.net.URI;
import java.util.Locale;
import java.util.Map;

/**
 * The short name of what a call asked for, from its masked arguments: the statement of a SQL call ({@code DROP TABLE},
 * {@code TRUNCATE}, {@code DELETE}), the method and host of a request ({@code GET 169.254.169.254}), the namespace of
 * a Kubernetes call ({@code kube-system}), else the tool. Denied by rule's example and the prod-ddl finding's
 * statements: a keyword, a host or a path without its query — the arguments themselves stay in Activity, masked.
 */
public final class Literals {

  /** The second word that belongs to the statement's name: {@code DROP TABLE}, {@code CREATE INDEX}. */
  private static final java.util.Set<String> OBJECTS = java.util.Set.of("TABLE", "INDEX", "VIEW", "SCHEMA",
      "DATABASE", "SEQUENCE", "FUNCTION", "PROCEDURE", "TRIGGER", "TYPE", "EXTENSION", "ROLE", "USER", "COLUMN",
      "MATERIALIZED", "TABLESPACE", "DOMAIN", "POLICY");
  private static final java.util.Set<String> DDL = java.util.Set.of("CREATE", "ALTER", "DROP", "TRUNCATE", "RENAME",
      "COMMENT", "GRANT", "REVOKE");

  private Literals() {}

  /**
   * {@code DROP TABLE} of {@code DROP TABLE orders_archive}, {@code TRUNCATE} of {@code TRUNCATE orders}: the first
   * word after leading space and block comments, with the second when it names what DDL acts on. The comments are
   * skipped in a loop, not a regular expression: the agent writes the SQL, and a pattern recurses once per comment —
   * a few thousand empty comments in front of a denied statement would end every Insights read with a
   * {@code StackOverflowError}.
   */
  public static String statement(String sql) {
    if (sql == null) {
      return null;
    }
    int at = spaces(sql, 0);
    while (sql.startsWith("/*", at)) {
      int close = sql.indexOf("*/", at + 2);
      if (close < 0) {
        return null;
      }
      at = spaces(sql, close + 2);
    }
    int end = letters(sql, at);
    if (end == at) {
      return null;
    }
    String first = sql.substring(at, end).toUpperCase(Locale.ROOT);
    int next = spaces(sql, end);
    int nextEnd = letters(sql, next);
    String second = next > end && nextEnd > next ? sql.substring(next, nextEnd).toUpperCase(Locale.ROOT) : null;
    if (DDL.contains(first) && second != null && OBJECTS.contains(second)) {
      return first + " " + second;
    }
    return first;
  }

  /** The first index at or after {@code from} that isn't space ({@code \s} of a regular expression). */
  private static int spaces(String s, int from) {
    int i = from;
    while (i < s.length() && " \t\n\u000B\f\r".indexOf(s.charAt(i)) >= 0) {
      i++;
    }
    return i;
  }

  /** The end of the ASCII letters starting at {@code from}. */
  private static int letters(String s, int from) {
    int i = from;
    while (i < s.length() && ((s.charAt(i) >= 'A' && s.charAt(i) <= 'Z') || (s.charAt(i) >= 'a'
        && s.charAt(i) <= 'z'))) {
      i++;
    }
    return i;
  }

  /** The literal of a denied call: see the class comment. */
  public static String of(String rule, String tool, Map<String, Object> args) {
    if (rule != null && (rule.equals("mode.off") || rule.endsWith(".mode.off"))) {
      return tool;
    }
    if (args != null) {
      Object sql = args.get("sql");
      if (sql instanceof String s && statement(s) != null) {
        return statement(s);
      }
      Object method = args.get("method");
      Object target = args.containsKey("url") ? args.get("url") : args.get("path");
      if (method instanceof String m && target instanceof String t) {
        return m.toUpperCase(Locale.ROOT) + " " + host(t);
      }
      Object namespace = args.get("namespace");
      if (namespace instanceof String ns && !ns.isBlank()) {
        return ns;
      }
      Object runbook = args.get("runbook");
      if (runbook instanceof String r && !r.isBlank()) {
        return r;
      }
    }
    return tool;
  }

  /** The host of an absolute URL, else the path as given ({@code /v1/refunds}). */
  private static String host(String target) {
    try {
      URI uri = URI.create(target.strip());
      if (uri.getHost() != null) {
        return uri.getHost();
      }
    } catch (IllegalArgumentException e) {
      // Not a URL: shown as given.
    }
    String path = target.strip();
    int query = path.indexOf('?');
    return query < 0 ? path : path.substring(0, query);
  }
}
