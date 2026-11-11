package ai.msrv.gaterook.connectors.snowflake;

import ai.msrv.gaterook.connectors.snowflake.SnowflakeLexer.Scan;
import ai.msrv.gaterook.connectors.snowflake.SnowflakeLexer.Token;
import ai.msrv.gaterook.connectors.snowflake.SnowflakeLexer.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What one Snowflake statement does, without I/O, and whether a tool may run it. The text is read by
 * {@link SnowflakeLexer}'s tokens, so a word inside a string, a quoted name or a comment is never a keyword; what it
 * can't read is refused.
 *
 * <ul>
 *   <li>{@code snowflake_query} runs one SELECT, WITH … SELECT, SHOW or DESCRIBE. It refuses session and transaction
 *       control, CALL and EXECUTE, file commands, every statement that changes data or objects, the flow operator
 *       {@code ->>}, sequences ({@code NEXTVAL}), {@code FOR UPDATE}, and every {@code SYSTEM$} function except the
 *       few that only read ({@link #READ_SYSTEM_FUNCTIONS}). Before it runs a SELECT, Snowflake plans it
 *       ({@link Plans}).</li>
 *   <li>{@code snowflake_execute} runs one INSERT, UPDATE, DELETE, MERGE, TRUNCATE or DDL statement after approval.
 *       It refuses the same control and file statements, and also GRANT, REVOKE and the objects of access and the
 *       account: users, roles, integrations, secrets, network, authentication, password and session policies,
 *       shares, connections, replication and failover groups, the account itself, and DROP DATABASE.</li>
 *   <li>With a table list, every table a call names must be on it: the plan's tables for a SELECT, the named table for
 *       DESCRIBE and for a change, and a change that reads another object (a query, a stage, CLONE, LIKE) is refused,
 *       as is SHOW, INFORMATION_SCHEMA and every function that reads an object it is given as text.</li>
 * </ul>
 * Snowflake has no read-only transaction: these checks are defence in depth, and the role is the boundary.
 */
final class SnowflakeClassifier {

  /** What a statement does. */
  enum Kind { READ, CHANGE, FORBIDDEN, UNKNOWN }

  /**
   * One statement read.
   *
   * @param type    what it is, for the agent and the approver: SELECT, SHOW, INSERT, CREATE TABLE, ALTER SESSION…
   * @param ddl     whether it changes objects rather than rows (Snowflake commits it at once)
   * @param target  for a change, the object it changes as written; {@code null} when GateRook can't name one
   * @param object  for a change, its kind of object ({@code TABLE}, {@code VIEW}, {@code COLUMN}); for DESCRIBE, what
   *                it describes; {@code null} otherwise
   * @param problem why the text can't be read; {@code null} when it can
   */
  record Statement(Scan scan, Kind kind, String type, boolean ddl, ObjectName target, String object,
      String problem) {

    boolean readable() {
      return problem == null;
    }

    int count() {
      return scan.statements();
    }
  }

  /** Why a call is refused: the rule's id (after the policy's prefix), the reason and a hint, for the agent. */
  record Refusal(String rule, String reason, String hint) {}

  /**
   * {@code SYSTEM$} functions a read may call, and whether they name a table in a string argument (which a table list
   * can't check, so they are refused with one): {@code SYSTEM$TYPEOF} only says the type of an expression; the
   * clustering functions report the micro-partition statistics of a table and change nothing.
   */
  static final Map<String, Boolean> READ_SYSTEM_FUNCTIONS = Map.of("SYSTEM$TYPEOF", false,
      "SYSTEM$CLUSTERING_INFORMATION", true, "SYSTEM$CLUSTERING_DEPTH", true);

  /** Functions that read an object, a query's result or a definition they are given as text. */
  static final Set<String> NAME_READERS = Set.of("RESULT_SCAN", "GET_DDL", "GET_OBJECT_REFERENCES",
      "SYSTEM$CLUSTERING_INFORMATION", "SYSTEM$CLUSTERING_DEPTH", "INFER_SCHEMA", "GET_PRESIGNED_URL",
      "BUILD_SCOPED_FILE_URL", "BUILD_STAGE_FILE_URL", "GET_STAGE_LOCATION", "GET_RELATIVE_PATH",
      "GET_ABSOLUTE_PATH");

  private static final Set<String> SESSION = Set.of("USE", "SET", "UNSET");
  private static final Set<String> TRANSACTION = Set.of("BEGIN", "START", "COMMIT", "ROLLBACK");
  private static final Set<String> CODE = Set.of("CALL", "EXECUTE", "DECLARE", "LET", "RETURN", "FOR", "WHILE",
      "REPEAT", "LOOP", "IF", "CASE", "EXCEPTION", "RAISE", "AWAIT", "CANCEL");
  private static final Set<String> FILES = Set.of("PUT", "GET", "COPY", "LIST", "LS", "REMOVE", "RM");
  private static final Set<String> ACCESS = Set.of("GRANT", "REVOKE");
  private static final Set<String> DDL_VERBS = Set.of("CREATE", "ALTER", "DROP", "UNDROP");
  /** Words before an object's kind in DDL. */
  private static final Set<String> MODIFIERS = Set.of("OR", "REPLACE", "ALTER", "IF", "NOT", "EXISTS", "SECURE",
      "RECURSIVE", "LOCAL", "GLOBAL", "TEMP", "TEMPORARY", "VOLATILE", "TRANSIENT");
  /** Kinds of objects that take more than one word, longest first. */
  private static final List<String> KIND_PHRASES = List.of("ROW ACCESS POLICY", "EXTERNAL ACCESS INTEGRATION",
      "CORTEX SEARCH SERVICE", "SECURITY INTEGRATION", "API INTEGRATION", "STORAGE INTEGRATION",
      "NOTIFICATION INTEGRATION", "CATALOG INTEGRATION", "NETWORK POLICY", "NETWORK RULE", "AUTHENTICATION POLICY",
      "PASSWORD POLICY", "SESSION POLICY", "PACKAGES POLICY", "MASKING POLICY", "AGGREGATION POLICY",
      "PROJECTION POLICY", "JOIN POLICY", "DATABASE ROLE", "APPLICATION ROLE", "APPLICATION PACKAGE",
      "MANAGED ACCOUNT", "FAILOVER GROUP", "REPLICATION GROUP", "RESOURCE MONITOR", "COMPUTE POOL",
      "IMAGE REPOSITORY", "GIT REPOSITORY", "FILE FORMAT", "MATERIALIZED VIEW", "SEMANTIC VIEW", "DYNAMIC TABLE",
      "EXTERNAL TABLE", "ICEBERG TABLE", "HYBRID TABLE", "EVENT TABLE", "EXTERNAL FUNCTION", "EXTERNAL VOLUME");
  /** Kinds of access and account objects, which a person changes in Snowflake itself. */
  private static final Set<String> ACCESS_KINDS = Set.of("USER", "ROLE", "DATABASE ROLE", "APPLICATION ROLE",
      "SECRET", "ACCOUNT", "MANAGED ACCOUNT", "CONNECTION", "SHARE", "FAILOVER GROUP", "REPLICATION GROUP",
      "NETWORK POLICY", "NETWORK RULE", "AUTHENTICATION POLICY", "PASSWORD POLICY", "SESSION POLICY",
      "PACKAGES POLICY", "INTEGRATION", "SECURITY INTEGRATION", "API INTEGRATION", "STORAGE INTEGRATION",
      "NOTIFICATION INTEGRATION", "CATALOG INTEGRATION", "EXTERNAL ACCESS INTEGRATION", "ORGANIZATION");
  /** Words of DDL that make it read or name another object: {@code CLONE src}, {@code LIKE src}, renames, swaps. */
  private static final Set<String> DDL_SOURCES = Set.of("CLONE", "LIKE", "RENAME", "SWAP");
  /** Words that end a table's alias after FROM or USING. */
  private static final Set<String> AFTER_SOURCE = Set.of("ON", "WHERE", "SET", "WHEN", "JOIN", "INNER", "LEFT",
      "RIGHT", "FULL", "CROSS", "NATURAL", "USING", "AT", "BEFORE", "CHANGES", "SAMPLE", "TABLESAMPLE", "PIVOT",
      "UNPIVOT", "MATCH_RECOGNIZE", "LATERAL");
  /** Kinds a DESCRIBE or a change of a listed table may name. */
  private static final Set<String> TABLE_KINDS = Set.of("TABLE", "VIEW", "MATERIALIZED VIEW", "DYNAMIC TABLE",
      "EXTERNAL TABLE", "ICEBERG TABLE", "HYBRID TABLE", "EVENT TABLE");

  private SnowflakeClassifier() {}

  // ── Reading ─────────────────────────────────────────────────────────────────────────────────────────────────

  static Statement analyze(String sql) {
    Scan scan = SnowflakeLexer.scan(sql);
    if (!scan.readable()) {
      return new Statement(scan, Kind.UNKNOWN, "statement", false, null, null, scan.problem());
    }
    String first = scan.firstWord();
    int at = firstIndex(scan);
    return switch (first) {
      case "SELECT" -> read(scan, "SELECT", null);
      case "SHOW" -> read(scan, "SHOW", null);
      case "DESCRIBE", "DESC" -> describe(scan, at);
      case "WITH" -> with(scan);
      case "INSERT" -> insert(scan, at);
      case "UPDATE" -> change(scan, "UPDATE", false, name(scan, at + 1), "TABLE");
      case "DELETE" -> change(scan, "DELETE", false, scan.at(at + 1) != null && scan.at(at + 1).word("FROM")
          ? name(scan, at + 2) : null, "TABLE");
      case "MERGE" -> change(scan, "MERGE", false, scan.at(at + 1) != null && scan.at(at + 1).word("INTO")
          ? name(scan, at + 2) : null, "TABLE");
      case "TRUNCATE" -> truncate(scan, at);
      case "CREATE", "ALTER", "DROP", "UNDROP" -> ddl(scan, at, first);
      case "COMMENT" -> comment(scan, at);
      default -> other(scan, at, first);
    };
  }

  private static Statement read(Scan scan, String type, String object) {
    return new Statement(scan, Kind.READ, type, false, null, object, null);
  }

  private static Statement change(Scan scan, String type, boolean ddl, ObjectName target, String object) {
    return new Statement(scan, Kind.CHANGE, type, ddl, target, object, null);
  }

  private static Statement forbidden(Scan scan, String type) {
    return new Statement(scan, Kind.FORBIDDEN, type, false, null, null, null);
  }

  private static int firstIndex(Scan scan) {
    int i = 0;
    while (scan.at(i) != null && scan.at(i).symbol("(")) {
      i++;
    }
    return i;
  }

  private static ObjectName name(Scan scan, int i) {
    ObjectName.Read r = ObjectName.read(scan, i);
    return r == null ? null : r.name();
  }

  /** {@code DESCRIBE [kind] name}: the kind (TABLE when none is said) and the name. */
  private static Statement describe(Scan scan, int at) {
    int i = at + 1;
    String kind = kind(scan, i);
    if (kind != null) {
      i += kind.split(" ").length;
    }
    ObjectName target = name(scan, i);
    return new Statement(scan, Kind.READ, "DESCRIBE", false, target, kind == null ? "TABLE" : kind, null);
  }

  /**
   * {@code WITH} names queries before the statement that uses them: a SELECT is a read. {@code WITH … AS PROCEDURE …
   * CALL} runs an anonymous procedure, which can do anything; any other statement after WITH isn't run.
   */
  private static Statement with(Scan scan) {
    boolean select = false;
    for (int i = 0; i < scan.tokens().size(); i++) {
      Token t = scan.at(i);
      if (t.type() != Type.WORD || t.depth() != 0 || scan.qualified(i)) {
        continue;
      }
      switch (t.text()) {
        case "CALL", "PROCEDURE" -> {
          return forbidden(scan, "WITH … CALL");
        }
        case "INSERT", "UPDATE", "DELETE", "MERGE" -> {
          return new Statement(scan, Kind.UNKNOWN, "WITH … " + t.text(), false, null, null, null);
        }
        case "SELECT" -> select = true;
        default -> {
          // A CTE's name or AS.
        }
      }
    }
    return select ? read(scan, "SELECT", null)
        : new Statement(scan, Kind.UNKNOWN, "WITH without SELECT", false, null, null, null);
  }

  /** {@code INSERT [OVERWRITE] INTO name}; INSERT ALL and INSERT FIRST write several tables. */
  private static Statement insert(Scan scan, int at) {
    int i = at + 1;
    if (scan.at(i) != null && scan.at(i).word("OVERWRITE")) {
      i++;
    }
    Token t = scan.at(i);
    if (t != null && (t.word("ALL") || t.word("FIRST"))) {
      return change(scan, "INSERT " + t.text(), false, null, "TABLE");
    }
    return change(scan, "INSERT", false, t != null && t.word("INTO") ? name(scan, i + 1) : null, "TABLE");
  }

  /** {@code TRUNCATE [TABLE] [IF EXISTS] name}. */
  private static Statement truncate(Scan scan, int at) {
    int i = at + 1;
    if (scan.at(i) != null && scan.at(i).word("TABLE")) {
      i++;
    }
    i = ifExists(scan, i);
    return change(scan, "TRUNCATE", false, name(scan, i), "TABLE");
  }

  /** CREATE, ALTER, DROP or UNDROP of a kind of object, its modifiers and {@code IF [NOT] EXISTS} skipped. */
  private static Statement ddl(Scan scan, int at, String verb) {
    int i = at + 1;
    while (scan.at(i) != null && scan.at(i).type() == Type.WORD && MODIFIERS.contains(scan.at(i).text())) {
      i++;
    }
    String kind = kind(scan, i);
    if (kind == null) {
      Token t = scan.at(i);
      return new Statement(scan, Kind.UNKNOWN, verb + (t != null && t.type() == Type.WORD ? " " + t.text() : ""),
          true, null, null, null);
    }
    String type = verb + " " + kind;
    if (verb.equals("ALTER") && kind.equals("SESSION")) {
      return forbidden(scan, "ALTER SESSION");
    }
    if (ACCESS_KINDS.contains(kind)) {
      return forbidden(scan, type);
    }
    if (verb.equals("DROP") && kind.equals("DATABASE")) {
      return forbidden(scan, type);
    }
    i += kind.split(" ").length;
    i = ifExists(scan, i);
    return change(scan, type, true, name(scan, i), kind);
  }

  /** {@code COMMENT [IF EXISTS] ON kind name IS '…'}. */
  private static Statement comment(Scan scan, int at) {
    int i = ifExists(scan, at + 1);
    if (scan.at(i) == null || !scan.at(i).word("ON")) {
      return new Statement(scan, Kind.UNKNOWN, "COMMENT", true, null, null, null);
    }
    String kind = kind(scan, i + 1);
    if (kind == null) {
      return new Statement(scan, Kind.UNKNOWN, "COMMENT", true, null, null, null);
    }
    if (ACCESS_KINDS.contains(kind)) {
      return forbidden(scan, "COMMENT ON " + kind);
    }
    return change(scan, "COMMENT", true, name(scan, i + 1 + kind.split(" ").length), kind);
  }

  private static Statement other(Scan scan, int at, String first) {
    Token next = scan.at(at + 1);
    if (SESSION.contains(first)) {
      return forbidden(scan, first);
    }
    if (TRANSACTION.contains(first)) {
      return forbidden(scan, first.equals("START") ? "START TRANSACTION" : first);
    }
    if (CODE.contains(first)) {
      return forbidden(scan, first.equals("EXECUTE") && next != null && next.type() == Type.WORD
          ? "EXECUTE " + next.text() : first);
    }
    if (FILES.contains(first) || ACCESS.contains(first)) {
      return forbidden(scan, first);
    }
    return new Statement(scan, Kind.UNKNOWN, first.isEmpty() ? "statement" : first, false, null, null, null);
  }

  /** The kind of object at token {@code i}: a known phrase, or the one word there; {@code null} when none. */
  private static String kind(Scan scan, int i) {
    for (String phrase : KIND_PHRASES) {
      String[] words = phrase.split(" ");
      boolean match = true;
      for (int k = 0; k < words.length; k++) {
        Token t = scan.at(i + k);
        if (t == null || !t.word(words[k])) {
          match = false;
          break;
        }
      }
      if (match) {
        return phrase;
      }
    }
    Token t = scan.at(i);
    if (t == null || t.type() != Type.WORD || t.text().startsWith("$")) {
      return null;
    }
    return switch (t.text()) {
      case "TABLE", "VIEW", "SCHEMA", "DATABASE", "STAGE", "SEQUENCE", "STREAM", "TASK", "PIPE", "FUNCTION",
          "PROCEDURE", "WAREHOUSE", "USER", "ROLE", "INTEGRATION", "SECRET", "ACCOUNT", "CONNECTION", "SHARE",
          "TAG", "ALERT", "COLUMN", "SESSION", "NOTEBOOK", "STREAMLIT", "SERVICE", "MODEL", "SNAPSHOT", "LISTING",
          "ORGANIZATION", "APPLICATION", "DATASET" -> t.text();
      default -> null;
    };
  }

  private static int ifExists(Scan scan, int i) {
    if (scan.at(i) != null && scan.at(i).word("IF")) {
      int j = i + 1;
      if (scan.at(j) != null && scan.at(j).word("NOT")) {
        j++;
      }
      if (scan.at(j) != null && scan.at(j).word("EXISTS")) {
        return j + 1;
      }
    }
    return i;
  }

  // ── Rules ───────────────────────────────────────────────────────────────────────────────────────────────────

  /** Whether {@code snowflake_query} may run the statement; {@code null} when it may. */
  static Refusal query(Statement st, SnowflakeSettings s, String env) {
    Refusal common = common(st, "snowflake_query");
    if (common != null) {
      return common;
    }
    if (st.kind() == Kind.CHANGE) {
      return new Refusal("query-writes", "snowflake_query only reads; this is " + st.type() + ".",
          "Use snowflake_execute: a person approves the exact statement.");
    }
    if (st.kind() == Kind.UNKNOWN) {
      return new Refusal("unknown-statement", "snowflake_query runs SELECT, WITH … SELECT, SHOW or DESCRIBE; this is "
          + st.type() + ".", null);
    }
    Scan scan = st.scan();
    boolean restricted = s.tables().restricted();
    for (int i = 0; i < scan.tokens().size(); i++) {
      Token t = scan.at(i);
      String system = systemFunction(t);
      if (system != null) {
        Boolean namesTable = READ_SYSTEM_FUNCTIONS.get(system);
        if (namesTable == null) {
          return new Refusal("function-denied", system + " isn't allowed in a read: system functions can cancel "
              + "queries, change state or send messages.", "A read may call SYSTEM$TYPEOF"
              + (restricted ? "." : ", SYSTEM$CLUSTERING_INFORMATION and SYSTEM$CLUSTERING_DEPTH."));
        }
      }
      if (t.word("NEXTVAL")) {
        return new Refusal("forbidden", "NEXTVAL advances a sequence, and snowflake_query only reads.", null);
      }
      if (t.word("FOR") && !scan.qualified(i) && scan.at(i + 1) != null && scan.at(i + 1).word("UPDATE")) {
        return new Refusal("forbidden", "SELECT … FOR UPDATE locks rows, and snowflake_query only reads.", null);
      }
    }
    if (dynamicSystemName(scan)) {
      return new Refusal("function-denied", "IDENTIFIER() with a SYSTEM$ name isn't allowed in a read.", null);
    }
    if (restricted) {
      return tablesOfRead(st, s, env);
    }
    return null;
  }

  /** With a table list: what a read names beyond the tables Snowflake's plan will show. */
  private static Refusal tablesOfRead(Statement st, SnowflakeSettings s, String env) {
    Scan scan = st.scan();
    if (st.type().equals("SHOW")) {
      return notOnList("SHOW lists objects beyond the tables agents may use on " + env + ".",
          "Call snowflake_list_schemas to see the tables you can use.");
    }
    if (st.type().equals("DESCRIBE")) {
      if (!TABLE_KINDS.contains(st.object())) {
        return notOnList("DESCRIBE " + st.object() + " names an object other than a table, and on " + env + " only "
            + "some tables are open to agents.", null);
      }
      return listed(st.target(), s, env, "DESCRIBE");
    }
    for (int i = 0; i < scan.tokens().size(); i++) {
      Token t = scan.at(i);
      if ((t.name() && t.text().toUpperCase(Locale.ROOT).equals("INFORMATION_SCHEMA"))) {
        return notOnList("INFORMATION_SCHEMA describes tables beyond those agents may use on " + env + ".",
            "Call snowflake_list_schemas or snowflake_describe_table instead.");
      }
      if (t.type() == Type.WORD && NAME_READERS.contains(t.text()) && scan.called(i)) {
        return notOnList(t.text() + " reads an object it is given as text, which GateRook can't check against the "
            + "tables agents may use on " + env + ".", null);
      }
      if (t.symbol("@")) {
        return notOnList("The statement reads a stage, and on " + env + " only some tables are open to agents.",
            null);
      }
    }
    return null;
  }

  /** Whether {@code snowflake_execute} may run the statement; {@code null} when it may. */
  static Refusal execute(Statement st, SnowflakeSettings s, String env) {
    Refusal common = common(st, "snowflake_execute");
    if (common != null) {
      return common;
    }
    if (st.kind() == Kind.READ) {
      return new Refusal("execute-read", "This is a read.", "Use snowflake_query; it doesn't need approval.");
    }
    if (st.kind() == Kind.UNKNOWN) {
      return new Refusal("unknown-statement", "snowflake_execute runs INSERT, UPDATE, DELETE, MERGE, TRUNCATE or DDL "
          + "(CREATE, ALTER, DROP, UNDROP, COMMENT); this is " + st.type() + ".", null);
    }
    if (s.tables().restricted()) {
      return tablesOfChange(st, s, env);
    }
    return null;
  }

  /**
   * With a table list, a change changes one listed table and reads nothing but listed tables: what it reads must be
   * named in it (the source of UPDATE … FROM, DELETE … USING, MERGE … USING), not a query, a stage or a clone.
   */
  private static Refusal tablesOfChange(Statement st, SnowflakeSettings s, String env) {
    if (st.target() == null) {
      return notOnList("GateRook can't tell which table this " + st.type() + " changes, and on " + env + " only "
          + "some tables are open to agents.", "Name the table directly, as DATABASE.SCHEMA.TABLE.");
    }
    ObjectName table = "COLUMN".equals(st.object()) ? st.target().parent() : st.target();
    if (!TABLE_KINDS.contains(st.object()) && !"COLUMN".equals(st.object())) {
      return notOnList(st.type() + " changes an object other than a table, and on " + env + " only some tables are "
          + "open to agents.", null);
    }
    Refusal target = listed(table, s, env, st.type());
    if (target != null) {
      return target;
    }
    Scan scan = st.scan();
    // FROM and USING name sources only in the statement's own clauses: EXTRACT(YEAR FROM d) is no source.
    int top = scan.at(firstIndex(scan)).depth();
    for (int i = 0; i < scan.tokens().size(); i++) {
      Token t = scan.at(i);
      boolean word = t.type() == Type.WORD && !scan.qualified(i);
      boolean reads = t.symbol("@")
          || (t.name() && t.text().toUpperCase(Locale.ROOT).equals("INFORMATION_SCHEMA"))
          || (word && t.text().equals("SELECT"))
          || (word && st.ddl() && DDL_SOURCES.contains(t.text()))
          || (word && (t.text().equals("TABLE") || t.text().equals("IDENTIFIER")
              || NAME_READERS.contains(t.text())) && scan.called(i));
      if (reads) {
        return notOnList(st.type() + " reads or names objects beyond its table, which GateRook can't check against "
            + "the tables agents may use on " + env + ".", "Change one listed table with values written in the "
            + "statement.");
      }
      boolean source = (t.word("FROM") && st.type().equals("UPDATE")) || (t.word("USING")
          && (st.type().equals("DELETE") || st.type().equals("MERGE")));
      if (source && !scan.qualified(i) && t.depth() == top) {
        Refusal sources = sources(scan, i + 1, s, env, st.type());
        if (sources != null) {
          return sources;
        }
      }
    }
    return null;
  }

  /** The tables after FROM or USING: {@code name [[AS] alias] [, …]}; anything else isn't checked, so refused. */
  private static Refusal sources(Scan scan, int i, SnowflakeSettings s, String env, String type) {
    while (true) {
      ObjectName.Read read = ObjectName.read(scan, i);
      if (read == null) {
        return notOnList(type + " reads from something GateRook can't check against the tables agents may use on "
            + env + ".", "Read from listed tables named directly, as DATABASE.SCHEMA.TABLE.");
      }
      Refusal r = listed(read.name(), s, env, type);
      if (r != null) {
        return r;
      }
      i = read.next();
      if (scan.at(i) != null && scan.at(i).word("AS")) {
        i++;
      }
      Token alias = scan.at(i);
      if (alias != null && (alias.type() == Type.QUOTED_NAME
          || (alias.type() == Type.WORD && !AFTER_SOURCE.contains(alias.text())))) {
        i++;
      }
      if (scan.at(i) != null && scan.at(i).symbol(",")) {
        i++;
        continue;
      }
      Token after = scan.at(i);
      if (after != null && after.type() == Type.WORD && Set.of("JOIN", "INNER", "LEFT", "RIGHT", "FULL", "CROSS",
          "NATURAL", "LATERAL").contains(after.text())) {
        return notOnList(type + " joins tables in a way GateRook doesn't check, and on " + env + " only some tables "
            + "are open to agents.", "List the tables after FROM or USING, separated by commas.");
      }
      return null;
    }
  }

  private static Refusal listed(ObjectName name, SnowflakeSettings s, String env, String type) {
    if (name == null) {
      return notOnList("GateRook can't tell which table " + type + " uses, and on " + env + " only some tables are "
          + "open to agents.", "Name the table directly, as DATABASE.SCHEMA.TABLE.");
    }
    ObjectName full = name.resolve(s.database(), s.schema());
    if (full == null) {
      return notOnList(name.display() + " isn't qualified, and the connection has no default " + (s.database() == null
          ? "database" : "schema") + ".", "Write it as DATABASE.SCHEMA.TABLE.");
    }
    if (!s.tables().allows(full)) {
      return notOnList(full.display() + " isn't one of the tables agents may use on " + env + ".",
          "Call snowflake_list_schemas to see the tables you can use.");
    }
    return null;
  }

  private static Refusal notOnList(String reason, String hint) {
    return new Refusal("table-not-allowed", reason, hint);
  }

  /** What both tools refuse: text that can't be read, several statements, control and file statements, pipes. */
  private static Refusal common(Statement st, String tool) {
    if (!st.readable()) {
      return new Refusal("unparsed", st.problem() + " GateRook doesn't run what it can't read.",
          "Write one plain statement.");
    }
    if (st.count() > 1) {
      return new Refusal("multi-statement", tool + " runs one statement at a time; this has " + st.count() + ".",
          tool.equals("snowflake_execute") ? "Send them one by one: each waits for approval."
              : "Send them one by one.");
    }
    if (st.kind() == Kind.FORBIDDEN) {
      return new Refusal("forbidden", forbiddenReason(st.type()), forbiddenHint(st.type()));
    }
    for (Token t : st.scan().tokens()) {
      if (t.symbol("->>")) {
        return new Refusal("forbidden", "The flow operator ->> runs more than one statement.", "Send them one by one.");
      }
    }
    return null;
  }

  static String forbiddenReason(String type) {
    if (SESSION.contains(type) || type.equals("ALTER SESSION")) {
      return type + " isn't allowed: GateRook sets the warehouse, role, database and schema of every statement from "
          + "the connection.";
    }
    if (TRANSACTION.contains(type) || type.equals("START TRANSACTION")) {
      return type + " isn't allowed: Snowflake commits every statement GateRook runs on its own.";
    }
    String word = type.split(" ")[0];
    if (CODE.contains(word) || type.equals("WITH … CALL")) {
      return type + " isn't allowed: it runs code whose statements GateRook can't see.";
    }
    if (FILES.contains(word)) {
      return type + " isn't allowed: it moves files between stages and storage, outside the tables GateRook checks.";
    }
    if (type.equals("DROP DATABASE")) {
      return "DROP DATABASE isn't allowed through GateRook.";
    }
    return type + " isn't allowed: a person changes access and account settings in Snowflake itself.";
  }

  private static String forbiddenHint(String type) {
    if (SESSION.contains(type) || type.equals("ALTER SESSION")) {
      return "Name objects in full (DATABASE.SCHEMA.TABLE), or ask a person to change the connection.";
    }
    if (TRANSACTION.contains(type) || type.equals("START TRANSACTION")) {
      return "Send one statement per call.";
    }
    return null;
  }

  /** The {@code SYSTEM$} function a token names, upper case, or {@code null}. */
  private static String systemFunction(Token t) {
    if (t.type() == Type.WORD && t.text().startsWith("SYSTEM$")) {
      return t.text();
    }
    if (t.type() == Type.QUOTED_NAME && t.text().toUpperCase(Locale.ROOT).startsWith("SYSTEM$")) {
      return t.text().toUpperCase(Locale.ROOT);
    }
    return null;
  }

  /** {@code IDENTIFIER('SYSTEM$…')}: a function named by a string. */
  private static boolean dynamicSystemName(Scan scan) {
    boolean identifier = false;
    for (int i = 0; i < scan.tokens().size(); i++) {
      if (scan.at(i).word("IDENTIFIER") && scan.called(i)) {
        identifier = true;
        break;
      }
    }
    if (!identifier) {
      return false;
    }
    for (Token t : scan.tokens()) {
      if (t.type() == Type.STRING && t.text().toUpperCase(Locale.ROOT).contains("SYSTEM$")) {
        return true;
      }
    }
    return false;
  }

  /** The targets' display of a statement for an approval: {@code ANALYTICS.PUBLIC.ORDERS}, resolved where possible. */
  static String targetDisplay(Statement st, SnowflakeSettings s) {
    if (st.target() == null) {
      return null;
    }
    ObjectName full = st.target().resolve(s.database(), s.schema());
    return (full == null ? st.target() : full).display();
  }

  /** Every word of the statement's code, for tests and facts. */
  static List<String> words(Statement st) {
    List<String> out = new ArrayList<>();
    for (Token t : st.scan().tokens()) {
      if (t.type() == Type.WORD) {
        out.add(t.text());
      }
    }
    return out;
  }
}
