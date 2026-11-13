package com.gaterook.connectors.firebase;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * The arguments of {@code rtdb_get}, checked without I/O, as the database's REST query: {@code shallow=true}, or
 * {@code orderBy} with {@code limitToFirst}/{@code limitToLast} and {@code startAt}/{@code endAt}/{@code equalTo}, each
 * value JSON-encoded and percent-encoded. {@code shallow} defaults to true at the root without a query, as a database's
 * root may hold all of it. On a connection limited to some paths, the root may only be read shallow: its keys, those
 * on the list.
 *
 * @param orderBy the JSON text of {@code orderBy} ({@code "$key"}, {@code "score"}), or {@code null}
 */
record RtdbRead(RtdbPath path, boolean shallow, String orderBy, Integer limitToFirst, Integer limitToLast,
    JsonNode startAt, JsonNode endAt, JsonNode equalTo) {

  static final Set<String> ORDERS = Set.of("$key", "$value", "$priority");
  /** The longest string a query bound may be. */
  static final int MAX_BOUND = 1_024;

  static RtdbRead of(JsonNode args, RtdbSettings s, int maxRows) throws Refusal {
    JsonNode p = args.path("path");
    if (!p.isMissingNode() && !p.isNull() && !p.isString()) {
      throw Refusal.bad("path must be a path such as /users/alice.", null);
    }
    RtdbPath path = RtdbPath.parse(p.isString() ? p.asString() : null);
    String orderBy = null;
    JsonNode o = args.path("orderBy");
    if (!o.isMissingNode() && !o.isNull()) {
      if (!o.isString()) {
        throw Refusal.bad("orderBy must be $key, $value, $priority or a child such as score.", null);
      }
      String child = o.asString();
      if (!ORDERS.contains(child)) {
        RtdbPath.relative(child);
      }
      orderBy = GoogleHttp.JSON.writeValueAsString(JsonNodeFactory.instance.stringNode(child));
    }
    Integer first = limit(args, "limitToFirst", maxRows);
    Integer last = limit(args, "limitToLast", maxRows);
    JsonNode startAt = bound(args, "startAt");
    JsonNode endAt = bound(args, "endAt");
    JsonNode equalTo = bound(args, "equalTo");
    boolean filtered = first != null || last != null || startAt != null || endAt != null || equalTo != null;
    if (filtered && orderBy == null) {
      throw Refusal.bad("limitToFirst, limitToLast, startAt, endAt and equalTo need orderBy.",
          "Add orderBy: $key, $value, $priority or a child such as score.");
    }
    if (first != null && last != null) {
      throw Refusal.bad("Use limitToFirst or limitToLast, not both.", null);
    }
    if (equalTo != null && (startAt != null || endAt != null)) {
      throw Refusal.bad("equalTo can't be combined with startAt or endAt.", null);
    }
    boolean query = orderBy != null;
    JsonNode sh = args.path("shallow");
    if (!sh.isMissingNode() && !sh.isNull() && !sh.isBoolean()) {
      throw Refusal.bad("shallow must be true or false.", null);
    }
    boolean shallow = sh.isBoolean() ? sh.booleanValue() : path.root() && !query;
    if (shallow && query) {
      throw Refusal.bad("shallow can't be combined with orderBy, limitToFirst, limitToLast, startAt, endAt or "
          + "equalTo.", null);
    }
    if (s.restricted()) {
      if (path.root() && !shallow) {
        throw new Refusal(Refusal.NOT_ALLOWED_PATH, "This connection allows only some paths, so the root is read "
            + "shallow: its keys on the list.", "Call rtdb_get with path / to see them, then read one.");
      }
      if (!path.root() && !s.allows(path.top())) {
        throw new Refusal(Refusal.NOT_ALLOWED_PATH, "/" + Names.shown(path.top()) + " isn't one of the paths agents "
            + "may use on this connection.", "Call rtdb_get with path / to see the paths you can use.");
      }
    }
    return new RtdbRead(path, shallow, orderBy, first, last, startAt, endAt, equalTo);
  }

  private static Integer limit(JsonNode args, String name, int maxRows) throws Refusal {
    JsonNode n = args.path(name);
    if (n.isMissingNode() || n.isNull()) {
      return null;
    }
    if (!n.isIntegralNumber() || !n.canConvertToInt() || n.asInt() < 1) {
      throw Refusal.bad(name + " must be a whole number of at least 1.", null);
    }
    return Math.min(n.asInt(), Math.max(1, maxRows));
  }

  /** A query bound: a string, number, boolean or null, as its JSON text goes into the URL. */
  private static JsonNode bound(JsonNode args, String name) throws Refusal {
    if (!args.has(name)) {
      return null;
    }
    JsonNode v = args.get(name);
    if (v.isString() && v.asString().length() <= MAX_BOUND) {
      return v;
    }
    if ((v.isNumber() && Double.isFinite(v.doubleValue())) || v.isBoolean() || v.isNull()) {
      return v;
    }
    throw Refusal.bad(name + " must be a string of at most " + MAX_BOUND + " characters, a number, true, false or "
        + "null.", null);
  }

  /** The URL's query: {@code shallow=true}, or the query's parameters; empty for a plain read. */
  String query() {
    List<String> out = new ArrayList<>();
    if (shallow) {
      out.add("shallow=true");
    }
    if (orderBy != null) {
      out.add("orderBy=" + Names.encode(orderBy));
    }
    if (limitToFirst != null) {
      out.add("limitToFirst=" + limitToFirst);
    }
    if (limitToLast != null) {
      out.add("limitToLast=" + limitToLast);
    }
    if (startAt != null) {
      out.add("startAt=" + Names.encode(startAt.toString()));
    }
    if (endAt != null) {
      out.add("endAt=" + Names.encode(endAt.toString()));
    }
    if (equalTo != null) {
      out.add("equalTo=" + Names.encode(equalTo.toString()));
    }
    return String.join("&", out);
  }
}
