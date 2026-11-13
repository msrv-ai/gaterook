package com.gaterook.connectors.cassandra;

import com.gaterook.connector.api.Env;
import tools.jackson.databind.JsonNode;

/**
 * What the connection lets a statement do, without I/O: the rules {@code classify} turns into denials and
 * {@code execute} checks again, as it gets no policy. Each refusal names its rule ({@code <env>.cassandra.<rule>}).
 */
final class CassandraRules {

  static final int MAX_VALUES = 100;

  /**
   * A refusal.
   *
   * @param rule   the rule id after the policy's prefix
   * @param reason what happened, for the agent
   * @param hint   what to do instead, or {@code null}
   */
  record Refusal(String rule, String reason, String hint) {}

  private CassandraRules() {}

  /** Why {@code st} can't run through the tool ({@code read}: {@code cassandra_query}), or {@code null}. */
  static Refusal statement(Cql.Statement st, CassandraSettings s, boolean read, Env env) {
    switch (st.kind()) {
      case UNREADABLE -> {
        return new Refusal("unparsed", st.problem() + " The gateway doesn't run what it can't read.",
            "Write one plain CQL statement.");
      }
      case BATCH -> {
        return new Refusal("batch", st.problem(), null);
      }
      case ACCESS -> {
        return new Refusal("access-control", st.problem(), null);
      }
      case KEYSPACE -> {
        return new Refusal("keyspace-change", st.problem(), null);
      }
      case CODE -> {
        return new Refusal("server-code", st.problem(), null);
      }
      case USE -> {
        return new Refusal("use-keyspace", st.problem(), null);
      }
      case OTHER -> {
        return new Refusal("not-supported", st.problem(), null);
      }
      default -> {
        // A read or a change: checked below.
      }
    }
    if (read && st.kind() != Cql.Kind.SELECT) {
      return new Refusal("query-writes", "cassandra_query only reads; this is " + st.verb() + ".",
          "Use cassandra_execute: a person approves the exact statement.");
    }
    if (!read && st.kind() == Cql.Kind.SELECT) {
      return new Refusal("execute-read", "This is a read.", "Use cassandra_query; it doesn't need approval.");
    }
    if (st.allowFiltering() && !(s.allowFiltering() && env == Env.DEV)) {
      return new Refusal("allow-filtering", "ALLOW FILTERING makes Cassandra read through partitions, possibly "
          + "every one in the cluster, to find the rows.", "Query by the partition key, or by a column with a "
          + "secondary index (cassandra_describe_table shows them).");
    }
    boolean change = st.kind().change();
    Refusal r = keyspace(st.target(), s, change, env);
    if (r == null && st.base() != null) {
      r = keyspace(st.base(), s, change, env);
    }
    if (r == null) {
      for (String ks : st.functionKeyspaces()) {
        r = keyspace(new Cql.Name(ks, null), s, false, env);
        if (r != null) {
          break;
        }
      }
    }
    return r;
  }

  /**
   * Why a statement may not use what {@code name} names (a function's keyspace: {@code name.name()} is
   * {@code null}), or {@code null}.
   */
  static Refusal keyspace(Cql.Name name, CassandraSettings s, boolean change, Env env) {
    String ks = name.keyspace() != null ? name.keyspace() : s.keyspace();
    if (ks == null) {
      return new Refusal("no-keyspace", "The statement doesn't name the keyspace of " + Cql.quote(name.name())
          + ", and the connection has no keyspace.", "Name it as keyspace.table.");
    }
    if (CassandraSettings.authTable(ks, name.name())) {
      return new Refusal("auth-tables", Cql.quote(ks) + (ks.startsWith("system_") || ks.startsWith("dse_") ? ""
          : "." + Cql.quote(name.name())) + " holds roles, password hashes and permissions: agents never read "
          + "or change it.", null);
    }
    if (change && CassandraSettings.systemKeyspace(ks)) {
      return new Refusal("system-keyspace", Cql.quote(ks) + " is one of Cassandra's own keyspaces: changes there "
          + "stay with people.", null);
    }
    if (!s.allows(ks)) {
      String where = env == null ? "this environment" : env.id();
      String reason = Cql.quote(ks) + " isn't one of the keyspaces agents may use on " + where + "."
          + (CassandraSettings.systemKeyspace(ks) ? " Cassandra's own keyspaces are open only when the connection "
          + "lists them by name." : "");
      return new Refusal("keyspace-not-allowed", reason, "Call cassandra_list_keyspaces to see the keyspaces you "
          + "can use.");
    }
    return null;
  }

  /** Why {@code values} aren't an array (for {@code ?}) or an object (for {@code :name}) of values, or {@code null}. */
  static Refusal values(JsonNode values) {
    if (values == null || values.isMissingNode() || values.isNull()) {
      return null;
    }
    if (values.isArray()) {
      return values.size() > MAX_VALUES ? new Refusal("bad-argument", "At most " + MAX_VALUES + " values per "
          + "statement.", null) : null;
    }
    if (values.isObject()) {
      return values.size() > MAX_VALUES ? new Refusal("bad-argument", "At most " + MAX_VALUES + " values per "
          + "statement.", null) : null;
    }
    return new Refusal("bad-argument", "values is an array for ? markers, in order, or an object for :name markers.",
        null);
  }
}
