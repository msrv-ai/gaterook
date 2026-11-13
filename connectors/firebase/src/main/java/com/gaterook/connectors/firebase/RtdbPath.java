package com.gaterook.connectors.firebase;

import java.util.ArrayList;
import java.util.List;

/**
 * A path of a Realtime Database, checked: keys joined by slashes, {@code /users/alice}; the root has none. A key is
 * what the database allows (at most 768 bytes, none of {@code . $ [ ]}) and what {@link Names} lets into a URL — so no
 * {@code ..}, {@code #}, {@code ?} or {@code %} either. One leading slash is optional; an empty key or a trailing
 * slash is refused, and a path is at most 32 keys deep, as the database nests.
 */
record RtdbPath(List<String> keys) {

  static final int MAX_KEY_BYTES = 768;
  static final int MAX_DEPTH = 32;
  /** What a database key may not hold, besides {@link Names#FORBIDDEN}. */
  static final String FORBIDDEN = ".$[]";

  static final RtdbPath ROOT = new RtdbPath(List.of());

  RtdbPath {
    keys = List.copyOf(keys);
  }

  /** The path {@code text}, checked; blank or {@code /} is the root. */
  static RtdbPath parse(String text) throws Refusal {
    if (text == null || text.isEmpty() || text.equals("/")) {
      return ROOT;
    }
    String t = text.startsWith("/") ? text.substring(1) : text;
    if (t.endsWith("/")) {
      throw Refusal.bad("The path " + Names.shown(text) + " ends with a slash.", "Write it as /users/alice.");
    }
    return new RtdbPath(keys(t, text));
  }

  /** {@code a/b/c} relative to another path: an update's key. */
  static RtdbPath relative(String text) throws Refusal {
    if (text.startsWith("/") || text.endsWith("/")) {
      throw Refusal.bad("The key " + Names.shown(text) + " of the update starts or ends with a slash.",
          "Name a child such as name, or a path below the written one such as profile/name.");
    }
    return new RtdbPath(keys(text, text));
  }

  private static List<String> keys(String t, String text) throws Refusal {
    List<String> out = new ArrayList<>();
    for (String key : t.split("/", -1)) {
      String problem = problem(key);
      if (problem != null) {
        throw Refusal.bad("The path " + Names.shown(text) + " has a key that " + problem + ".",
            "A key may not hold . $ # [ ] / ? % \\ or control characters.");
      }
      out.add(key);
    }
    if (out.size() > MAX_DEPTH) {
      throw Refusal.bad("The path " + Names.shown(text) + " is deeper than the database's " + MAX_DEPTH + " levels.",
          null);
    }
    return out;
  }

  /** Why {@code key} can't be a key of the database, or {@code null}. */
  static String problem(String key) {
    return Names.problem(key, FORBIDDEN, MAX_KEY_BYTES);
  }

  boolean root() {
    return keys.isEmpty();
  }

  /** The first key: what the connection's list of paths names. */
  String top() {
    return keys.getFirst();
  }

  int depth() {
    return keys.size();
  }

  RtdbPath resolve(RtdbPath relative) {
    List<String> out = new ArrayList<>(keys);
    out.addAll(relative.keys());
    return new RtdbPath(out);
  }

  /** {@code /users/alice}; the root is {@code /}. */
  String text() {
    return "/" + String.join("/", keys);
  }

  /** {@code /users/alice.json}, each key percent-encoded; the root is {@code /.json}. */
  String url() {
    List<String> out = new ArrayList<>(keys.size());
    keys.forEach(k -> out.add(Names.encode(k)));
    return "/" + String.join("/", out) + ".json";
  }

  @Override
  public String toString() {
    return text();
  }
}
