package com.gaterook.connectors.trino;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What one Trino statement is, without I/O: the gate before Trino's own answer. The text is read by Trino's tokens —
 * {@code '…'} strings with {@code ''}, {@code "…"} names with {@code ""}, {@code --} line comments that end at a line
 * break, block comments that don't nest — so a semicolon or a keyword inside a string or a comment is neither.
 * Whatever this can't read with certainty (an open quote or comment, a comment opened inside a comment or closed
 * outside one, a backquoted name, a control character, a character outside ASCII in the code, more than one
 * statement) is {@link Kind#UNREADABLE} or {@link Kind#FORBIDDEN}, and a call refuses it.
 *
 * <p>This only picks the route. A read runs only after Trino's own IO plan of it names no table it writes
 * ({@link IoPlan}). Sessions, roles, grants, transactions, prepared statements, procedures and catalogs are never run
 * through GateRook; table functions other than Trino's built-in {@code sequence} and {@code exclude_columns} are
 * refused, as a pass-through query of a JDBC catalog can change data that no plan shows.
 *
 * <p>Trino plans a write through its catalog's write hooks: planning {@code CREATE TABLE AS}, {@code INSERT},
 * {@code DELETE}, {@code UPDATE}, {@code MERGE}, {@code ANALYZE}, {@code REFRESH MATERIALIZED VIEW} or
 * {@code ALTER … EXECUTE} begins the write in the catalog, and a memory catalog creates the table of a
 * {@code CREATE TABLE AS} at that point, even for an {@code EXPLAIN}. So a change Trino plans as a write is never
 * planned before a person approves it: it carries a read-only query of what it reads instead
 * ({@link Statement#planned}), built from its own tokens, and a change whose read can't be built with certainty is
 * {@link Kind#UNREADABLE}. Trino doesn't plan a data definition statement: it answers its {@code EXPLAIN} with the
 * statement's own text without running it.
 */
final class TrinoSql {

  /** The route of a statement. */
  enum Kind { READ, CHANGE, FORBIDDEN, UNREADABLE }

  /** What a name in a statement stands for: which of its parts is the catalog. */
  enum Role { CATALOG, SCHEMA, TABLE, COLUMN, FUNCTION }

  /**
   * A name as written: its parts, quoted ones without their quotes.
   *
   * @param parts at least one part
   */
  record Name(List<String> parts) {

    Name {
      parts = List.copyOf(parts);
    }

    /** The name as Trino resolves it: case-insensitive, so lower case. */
    String text() {
      return String.join(".", parts).toLowerCase(Locale.ROOT);
    }

    /** The catalog this name is in, for its role, or {@code null} when it is the session's (unqualified). */
    String catalog(Role role) {
      int qualified = switch (role) {
        case CATALOG -> 1;
        case SCHEMA -> 2;
        case TABLE, FUNCTION -> 3;
        case COLUMN -> 4;
      };
      return parts.size() >= qualified ? parts.getFirst().toLowerCase(Locale.ROOT) : null;
    }
  }

  /** A name the statement uses, and what it stands for. */
  record Ref(Name name, Role role) {}

  /**
   * One call's text.
   *
   * @param type      what it is, upper case: {@code SELECT}, {@code SHOW TABLES}, {@code INSERT}, {@code CREATE TABLE AS}
   * @param sql       the text that is sent: the statement without a final semicolon
   * @param planned   the query whose IO plan decides it, which is always a read: for a read, its own text or the query
   *                  an {@code EXPLAIN} explains; for a change Trino plans as a write, the read-only query of what it
   *                  reads (the query of an {@code INSERT}, {@code SELECT 1 FROM t WHERE c} of a {@code DELETE}, …),
   *                  as Trino would begin the write while planning the change itself; {@code null} for a data
   *                  definition statement, which Trino doesn't plan
   * @param target    for a change, the object it changes; else {@code null}
   * @param targetAt  the token where {@link #target}'s name starts, or {@code -1}
   * @param refs      names whose catalog the connection checks (show and describe objects, qualified functions)
   * @param planless  a read whose IO plan names no table, so only {@link #refs} tell what it reads
   * @param catalogs  {@code SHOW CATALOGS}: every catalog's name
   * @param problem   why it is FORBIDDEN or UNREADABLE, for the agent; {@code null} otherwise
   */
  record Statement(Kind kind, String type, String sql, String planned, Ref target, int targetAt, List<Ref> refs,
      boolean planless, boolean catalogs, String problem) {

    static Statement refused(Kind kind, String type, String problem) {
      return new Statement(kind, type, null, null, null, -1, List.of(), false, false, problem);
    }

    Statement withRefs(List<Ref> more) {
      return new Statement(kind, type, sql, planned, target, targetAt, List.copyOf(more), planless, catalogs, problem);
    }

    /** This change, planned by {@code read}: the read-only query of what it reads. */
    Statement plannedBy(String read) {
      return new Statement(kind, type, sql, read, target, targetAt, refs, planless, catalogs, problem);
    }

    boolean read() {
      return kind == Kind.READ;
    }

    boolean change() {
      return kind == Kind.CHANGE;
    }
  }

  static final int MAX_LENGTH = 100_000;

  /** Table functions of Trino's own: they read what their arguments name and nothing else. */
  private static final Set<String> SAFE_TABLE_FUNCTIONS = Set.of("sequence", "exclude_columns");
  private static final Set<String> QUERIES = Set.of("SELECT", "WITH", "VALUES");
  /** Changes Trino plans as a write, besides {@code ALTER TABLE … EXECUTE} and its materialized view form. */
  private static final Set<String> PLANNED_WRITES = Set.of("INSERT", "CREATE TABLE AS", "DELETE", "UPDATE", "MERGE",
      "ANALYZE", "REFRESH MATERIALIZED VIEW");
  private static final String SESSIONS = "sessions belong to the gateway, which sets them for each call";

  private TrinoSql() {}

  // ── Tokens ──────────────────────────────────────────────────────────────────────────────────────────────────

  enum T { WORD, QUOTED, STRING, NUMBER, PUNCT }

  /**
   * One token of the code.
   *
   * @param text  {@code WORD}: upper case; {@code QUOTED}: the name without quotes; {@code PUNCT}: the character
   * @param start where it starts in the text
   * @param end   where it ends in the text (exclusive): a closing quote included
   */
  record Token(T type, String text, int start, int end) {

    boolean is(String word) {
      return type == T.WORD && text.equals(word);
    }

    boolean punct(char c) {
      return type == T.PUNCT && text.charAt(0) == c;
    }

    boolean name() {
      return type == T.WORD || type == T.QUOTED;
    }
  }

  /** The tokens of a text, where the text to send ends, or why it can't be read. */
  record Scan(List<Token> tokens, int end, String problem) {}

  /** Reads {@code sql} by Trino's tokens: comments and whitespace dropped, a final semicolon cut off. */
  static Scan scan(String sql) {
    List<Token> out = new ArrayList<>();
    int n = sql.length();
    int i = 0;
    while (i < n) {
      char c = sql.charAt(i);
      if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
        i++;
        continue;
      }
      if (c < 0x20 || c == 0x7F) {
        return new Scan(out, n, "The statement has a control character, which GateRook doesn't analyze.");
      }
      if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
        while (i < n && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') {
          if (control(sql.charAt(i))) {
            return new Scan(out, n, "A comment has a control character, which GateRook doesn't analyze.");
          }
          i++;
        }
        continue;
      }
      if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
        int close = sql.indexOf("*/", i + 2);
        if (close < 0) {
          return new Scan(out, n, "A comment isn't closed.");
        }
        String body = sql.substring(i + 2, close);
        if (body.contains("/*")) {
          return new Scan(out, n, "A comment opens inside a comment, which GateRook doesn't analyze.");
        }
        if (body.chars().anyMatch(ch -> control((char) ch))) {
          return new Scan(out, n, "A comment has a control character, which GateRook doesn't analyze.");
        }
        i = close + 2;
        continue;
      }
      if (c == '*' && i + 1 < n && sql.charAt(i + 1) == '/') {
        return new Scan(out, n, "A comment closes that never opened, which GateRook doesn't analyze.");
      }
      if (c == '\'' || c == '"') {
        int j = i + 1;
        StringBuilder value = new StringBuilder();
        boolean closed = false;
        while (j < n) {
          char d = sql.charAt(j);
          if (d == c) {
            if (j + 1 < n && sql.charAt(j + 1) == c) {
              value.append(c);
              j += 2;
              continue;
            }
            closed = true;
            break;
          }
          if (control(d)) {
            return new Scan(out, n, (c == '"' ? "A quoted name" : "A string") + " has a control character, which "
                + "GateRook doesn't analyze.");
          }
          value.append(d);
          j++;
        }
        if (!closed) {
          return new Scan(out, n, c == '"' ? "A quoted name isn't closed." : "A string isn't closed.");
        }
        if (c == '"' && value.isEmpty()) {
          return new Scan(out, n, "A quoted name is empty.");
        }
        out.add(new Token(c == '"' ? T.QUOTED : T.STRING, value.toString(), i, j + 1));
        i = j + 1;
        continue;
      }
      if (c == '`') {
        return new Scan(out, n, "Backquoted names aren't Trino SQL: quote names with double quotes.");
      }
      if (c > 0x7F) {
        return new Scan(out, n, "The code has a character outside ASCII: Trino reads such names only in double "
            + "quotes.");
      }
      if (Character.isLetter(c) || c == '_') {
        int j = i;
        while (j < n && (isAsciiLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_')) {
          j++;
        }
        out.add(new Token(T.WORD, sql.substring(i, j).toUpperCase(Locale.ROOT), i, j));
        i = j;
        continue;
      }
      if (c >= '0' && c <= '9') {
        int j = i;
        while (j < n && (isAsciiLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_' || sql.charAt(j) == '.'
            || ((sql.charAt(j) == '+' || sql.charAt(j) == '-') && (sql.charAt(j - 1) == 'e'
            || sql.charAt(j - 1) == 'E')))) {
          j++;
        }
        out.add(new Token(T.NUMBER, sql.substring(i, j), i, j));
        i = j;
        continue;
      }
      out.add(new Token(T.PUNCT, String.valueOf(c), i, i + 1));
      i++;
    }
    // One final semicolon is cut off: Trino refuses it. Any other ends a statement before another.
    int end = n;
    for (int k = 0; k < out.size(); k++) {
      if (out.get(k).punct(';')) {
        if (k != out.size() - 1) {
          return new Scan(out, n, "One statement per call: this text has more than one. Send them one by one.");
        }
        end = out.get(k).start();
        out.remove(k);
        break;
      }
    }
    return new Scan(out, end, null);
  }

  private static boolean control(char c) {
    return (c < 0x20 && c != '\t' && c != '\n' && c != '\r') || c == 0x7F || c == 0x2028 || c == 0x2029;
  }

  private static boolean isAsciiLetterOrDigit(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
  }

  // ── Statements ──────────────────────────────────────────────────────────────────────────────────────────────

  static Statement read(String sql) {
    if (sql == null || sql.isBlank()) {
      return Statement.refused(Kind.UNREADABLE, "statement", "The statement is empty.");
    }
    if (sql.length() > MAX_LENGTH) {
      return Statement.refused(Kind.UNREADABLE, "statement", "The statement is longer than " + MAX_LENGTH
          + " characters.");
    }
    Scan scan = scan(sql);
    if (scan.problem() != null) {
      return Statement.refused(scan.problem().startsWith("One statement") ? Kind.FORBIDDEN : Kind.UNREADABLE,
          "statement", scan.problem());
    }
    if (scan.tokens().isEmpty()) {
      return Statement.refused(Kind.UNREADABLE, "statement", "The statement has no keyword.");
    }
    String text = sql.substring(0, scan.end()).strip();
    // The tokens of the text that is sent, so that where they start and end is where they are in it.
    Scan sent = scan(text);
    if (sent.problem() != null || sent.tokens().size() != scan.tokens().size()) {
      return Statement.refused(Kind.UNREADABLE, "statement", "The statement can't be read with certainty.");
    }
    List<Token> t = sent.tokens();
    Statement st = statement(t, text);
    if (st.kind() == Kind.READ || st.kind() == Kind.CHANGE) {
      Functions f = functions(t, st);
      if (f.problem() != null) {
        return Statement.refused(Kind.FORBIDDEN, st.type(), f.problem());
      }
      st = st.withRefs(f.refs());
      return st.change() ? reads(t, text, st) : st;
    }
    return st;
  }

  private static Statement statement(List<Token> t, String text) {
    int i = 0;
    while (i < t.size() && t.get(i).punct('(')) {
      i++;
    }
    if (i >= t.size() || t.get(i).type() != T.WORD) {
      return Statement.refused(Kind.UNREADABLE, "statement", "The statement doesn't start with a keyword.");
    }
    String first = t.get(i).text();
    if (i > 0 && !QUERIES.contains(first)) {
      return Statement.refused(Kind.UNREADABLE, first, "Only a query can start with a parenthesis.");
    }
    return switch (first) {
      case "SELECT", "VALUES" -> query(first, text, text);
      case "WITH" -> word(t, i + 1, "SESSION")
          ? Statement.refused(Kind.FORBIDDEN, "WITH SESSION", "WITH SESSION sets session properties for the query: "
              + SESSIONS + ".")
          : query("SELECT", text, text);
      case "SHOW" -> show(t, i, text);
      case "DESCRIBE" -> describe(t, i, text);
      case "EXPLAIN" -> explain(t, i, text);
      case "INSERT" -> change(t, i, text, "INSERT", "INTO", Role.TABLE);
      case "DELETE" -> change(t, i, text, "DELETE", "FROM", Role.TABLE);
      case "MERGE" -> change(t, i, text, "MERGE", "INTO", Role.TABLE);
      case "UPDATE" -> change(t, i, text, "UPDATE", null, Role.TABLE);
      case "TRUNCATE" -> change(t, i, text, "TRUNCATE TABLE", "TABLE", Role.TABLE);
      case "ANALYZE" -> change(t, i, text, "ANALYZE", null, Role.TABLE);
      case "CREATE" -> create(t, i, text);
      case "ALTER" -> alter(t, i, text);
      case "DROP" -> drop(t, i, text);
      case "COMMENT" -> comment(t, i, text);
      case "REFRESH" -> word(t, i + 1, "MATERIALIZED") && word(t, i + 2, "VIEW")
          ? changeAt(t, i + 3, text, "REFRESH MATERIALIZED VIEW", Role.TABLE)
          : unknown(first);
      case "GRANT", "REVOKE", "DENY" -> Statement.refused(Kind.FORBIDDEN, first, "Access control stays with "
          + "people: GateRook doesn't run GRANT, REVOKE or DENY.");
      case "SET", "RESET" -> Statement.refused(Kind.FORBIDDEN, first + " " + wordAt(t, i + 1), first + " "
          + wordAt(t, i + 1) + " changes the session: " + SESSIONS + ". Roles and the session user stay with "
          + "people.");
      case "USE" -> Statement.refused(Kind.FORBIDDEN, "USE", "USE changes the session's catalog: the connection "
          + "sets the catalog and schema. Name tables as catalog.schema.table.");
      case "START", "COMMIT", "ROLLBACK" -> Statement.refused(Kind.FORBIDDEN, first, "Transactions aren't run "
          + "through GateRook: each call is one statement.");
      case "PREPARE", "EXECUTE", "DEALLOCATE" -> Statement.refused(Kind.FORBIDDEN, first, "Prepared statements "
          + "aren't run through GateRook: send the statement itself.");
      case "CALL" -> Statement.refused(Kind.FORBIDDEN, "CALL", "Procedures reach past what Trino's plan shows: "
          + "GateRook doesn't run CALL.");
      default -> unknown(first);
    };
  }

  private static Statement unknown(String first) {
    return Statement.refused(Kind.UNREADABLE, first, first + " isn't a statement GateRook runs on Trino.");
  }

  private static Statement query(String type, String text, String planned) {
    return new Statement(Kind.READ, type, text, planned, null, -1, List.of(), false, false, null);
  }

  private static Statement read(String type, String text, List<Ref> refs, boolean planless, boolean catalogs) {
    return new Statement(Kind.READ, type, text, text, null, -1, List.copyOf(refs), planless, catalogs, null);
  }

  /** {@code EXPLAIN [(options)] query}; the query's IO plan decides it. {@code EXPLAIN ANALYZE} runs the query. */
  private static Statement explain(List<Token> t, int i, String text) {
    int j = i + 1;
    if (word(t, j, "ANALYZE")) {
      return Statement.refused(Kind.FORBIDDEN, "EXPLAIN ANALYZE", "EXPLAIN ANALYZE runs the statement it explains: "
          + "GateRook doesn't run it. Use EXPLAIN without ANALYZE.");
    }
    if (j < t.size() && t.get(j).punct('(')) {
      int depth = 0;
      for (; j < t.size(); j++) {
        if (t.get(j).punct('(')) {
          depth++;
        } else if (t.get(j).punct(')') && --depth == 0) {
          j++;
          break;
        }
      }
    }
    int inner = j;
    while (inner < t.size() && t.get(inner).punct('(')) {
      inner++;
    }
    if (inner >= t.size() || !QUERIES.contains(t.get(inner).text())
        || (t.get(inner).is("WITH") && word(t, inner + 1, "SESSION"))) {
      return Statement.refused(Kind.FORBIDDEN, "EXPLAIN", "trino_query explains a query (SELECT, WITH or VALUES) "
          + "only.");
    }
    return query("EXPLAIN", text, text.substring(t.get(j).start()));
  }

  private static Statement describe(List<Token> t, int i, String text) {
    if (word(t, i + 1, "INPUT") || word(t, i + 1, "OUTPUT")) {
      return Statement.refused(Kind.FORBIDDEN, "DESCRIBE " + wordAt(t, i + 1), "DESCRIBE INPUT and OUTPUT describe "
          + "prepared statements, which GateRook doesn't keep.");
    }
    Name name = name(t, i + 1);
    if (name == null) {
      return Statement.refused(Kind.UNREADABLE, "DESCRIBE", "Name the table to describe.");
    }
    return read("DESCRIBE", text, List.of(new Ref(name, Role.TABLE)), false, false);
  }

  /** The {@code SHOW} forms GateRook knows, each with the name whose catalog it shows. */
  private static Statement show(List<Token> t, int i, String text) {
    String what = wordAt(t, i + 1);
    List<Ref> refs = new ArrayList<>();
    switch (what) {
      case "CATALOGS" -> {
        return read("SHOW CATALOGS", text, refs, false, true);
      }
      case "SCHEMAS" -> {
        from(t, i + 2, Role.CATALOG, refs);
        return read("SHOW SCHEMAS", text, refs, false, false);
      }
      case "TABLES" -> {
        from(t, i + 2, Role.SCHEMA, refs);
        return read("SHOW TABLES", text, refs, false, false);
      }
      case "COLUMNS" -> {
        if (!from(t, i + 2, Role.TABLE, refs)) {
          return Statement.refused(Kind.UNREADABLE, "SHOW COLUMNS", "Name the table: SHOW COLUMNS FROM table.");
        }
        return read("SHOW COLUMNS", text, refs, false, false);
      }
      case "FUNCTIONS" -> {
        from(t, i + 2, Role.SCHEMA, refs);
        return read("SHOW FUNCTIONS", text, refs, true, false);
      }
      case "ROLES", "CURRENT", "ROLE" -> {
        int j = i + 2;
        while (word(t, j, "ROLES") || word(t, j, "GRANTS")) {
          j++;
        }
        from(t, j, Role.CATALOG, refs);
        return read("SHOW " + what, text, refs, true, false);
      }
      case "GRANTS" -> {
        int j = i + 2;
        if (word(t, j, "ON")) {
          j += word(t, j + 1, "TABLE") ? 2 : 1;
          Name name = name(t, j);
          if (name == null) {
            return Statement.refused(Kind.UNREADABLE, "SHOW GRANTS", "Name the table: SHOW GRANTS ON table.");
          }
          refs.add(new Ref(name, Role.TABLE));
        }
        return read("SHOW GRANTS", text, refs, false, false);
      }
      case "CREATE" -> {
        int j = i + 2;
        Role role = Role.TABLE;
        String object = wordAt(t, j);
        if (object.equals("MATERIALIZED") && word(t, j + 1, "VIEW")) {
          j++;
        } else if (object.equals("SCHEMA")) {
          role = Role.SCHEMA;
        } else if (object.equals("FUNCTION")) {
          role = Role.FUNCTION;
        } else if (!object.equals("TABLE") && !object.equals("VIEW")) {
          return Statement.refused(Kind.UNREADABLE, "SHOW CREATE", "SHOW CREATE " + object + " isn't a form "
              + "GateRook knows.");
        }
        Name name = name(t, j + 1);
        if (name == null) {
          return Statement.refused(Kind.UNREADABLE, "SHOW CREATE", "Name what to show.");
        }
        refs.add(new Ref(name, role));
        return read("SHOW CREATE " + object, text, refs, true, false);
      }
      case "STATS" -> {
        if (!word(t, i + 2, "FOR")) {
          return Statement.refused(Kind.UNREADABLE, "SHOW STATS", "Write SHOW STATS FOR table.");
        }
        if (i + 3 < t.size() && t.get(i + 3).punct('(')) {
          // The statistics of a query: the plan of SHOW STATS names no table, so the query's own plan decides.
          int close = closing(t, i + 3);
          if (close < 0 || close != t.size() - 1 || i + 4 >= close || !QUERIES.contains(wordAt(t, i + 4))) {
            return Statement.refused(Kind.UNREADABLE, "SHOW STATS", "Write SHOW STATS FOR table or SHOW STATS FOR "
                + "(SELECT …).");
          }
          return new Statement(Kind.READ, "SHOW STATS", text, text.substring(t.get(i + 4).start(),
              t.get(close).start()), null, -1, List.of(), false, false, null);
        }
        Name name = name(t, i + 3);
        if (name == null) {
          return Statement.refused(Kind.UNREADABLE, "SHOW STATS", "Write SHOW STATS FOR table.");
        }
        refs.add(new Ref(name, Role.TABLE));
        return read("SHOW STATS", text, refs, true, false);
      }
      case "BRANCHES" -> {
        int j = i + 2;
        if (word(t, j, "FROM") || word(t, j, "IN")) {
          j++;
        }
        if (word(t, j, "TABLE")) {
          j++;
        }
        Name name = name(t, j);
        if (name == null) {
          return Statement.refused(Kind.UNREADABLE, "SHOW BRANCHES", "Name the table.");
        }
        refs.add(new Ref(name, Role.TABLE));
        return read("SHOW BRANCHES", text, refs, true, false);
      }
      case "SESSION" -> {
        return Statement.refused(Kind.FORBIDDEN, "SHOW SESSION", "SHOW SESSION shows GateRook's session of this one "
            + "call: " + SESSIONS + ".");
      }
      default -> {
        return Statement.refused(Kind.UNREADABLE, "SHOW", "SHOW " + what + " isn't a form GateRook knows.");
      }
    }
  }

  /** The token that closes the parenthesis opened at {@code open}, or {@code -1}. */
  private static int closing(List<Token> t, int open) {
    int depth = 0;
    for (int k = open; k < t.size(); k++) {
      if (t.get(k).punct('(')) {
        depth++;
      } else if (t.get(k).punct(')') && --depth == 0) {
        return k;
      }
    }
    return -1;
  }

  /** {@code FROM name} or {@code IN name} at {@code j}: its ref, whether there was one. */
  private static boolean from(List<Token> t, int j, Role role, List<Ref> refs) {
    if (word(t, j, "FROM") || word(t, j, "IN")) {
      Name name = name(t, j + 1);
      if (name != null) {
        refs.add(new Ref(name, role));
        return true;
      }
    }
    return false;
  }

  private static Statement create(List<Token> t, int i, String text) {
    int j = i + 1;
    if (word(t, j, "OR") && word(t, j + 1, "REPLACE")) {
      j += 2;
    }
    String object = wordAt(t, j);
    switch (object) {
      case "ROLE" -> {
        return Statement.refused(Kind.FORBIDDEN, "CREATE ROLE", "Roles stay with people: GateRook doesn't run "
            + "CREATE ROLE.");
      }
      case "CATALOG" -> {
        return Statement.refused(Kind.FORBIDDEN, "CREATE CATALOG", "Catalogs connect Trino to other systems with "
            + "their own credentials: they stay with people.");
      }
      case "TABLE" -> {
        int at = skipIf(t, j + 1);
        Name name = name(t, at);
        if (name == null) {
          return Statement.refused(Kind.UNREADABLE, "CREATE TABLE", "Name the table to create.");
        }
        int after = at + tokens(name);
        boolean as = false;
        List<Ref> refs = new ArrayList<>();
        int depth = 0;
        for (int k = after; k < t.size(); k++) {
          Token tok = t.get(k);
          if (tok.punct('(')) {
            depth++;
          } else if (tok.punct(')')) {
            depth--;
          } else if (depth == 0 && tok.is("AS")) {
            as = true;
            break;
          } else if (depth == 1 && tok.is("LIKE")) {
            Name like = name(t, k + 1);
            if (like != null) {
              refs.add(new Ref(like, Role.TABLE));
            }
          }
        }
        return changed(as ? "CREATE TABLE AS" : "CREATE TABLE", text, new Ref(name, Role.TABLE), at, refs);
      }
      case "VIEW" -> {
        return changeAt(t, j + 1, text, "CREATE VIEW", Role.TABLE);
      }
      case "MATERIALIZED" -> {
        if (!word(t, j + 1, "VIEW")) {
          return unknown("CREATE " + object);
        }
        return changeAt(t, skipIf(t, j + 2), text, "CREATE MATERIALIZED VIEW", Role.TABLE);
      }
      case "SCHEMA" -> {
        if (anyWord(t, j, "AUTHORIZATION")) {
          return Statement.refused(Kind.FORBIDDEN, "CREATE SCHEMA", "A schema's owner (AUTHORIZATION) stays with "
              + "people: create it without one.");
        }
        return changeAt(t, skipIf(t, j + 1), text, "CREATE SCHEMA", Role.SCHEMA);
      }
      case "FUNCTION" -> {
        return changeAt(t, j + 1, text, "CREATE FUNCTION", Role.FUNCTION);
      }
      default -> {
        return Statement.refused(Kind.UNREADABLE, "CREATE " + object, "CREATE " + object + " isn't a statement "
            + "GateRook runs on Trino.");
      }
    }
  }

  private static Statement alter(List<Token> t, int i, String text) {
    if (anyWord(t, i, "AUTHORIZATION")) {
      return Statement.refused(Kind.FORBIDDEN, "ALTER", "Changing an owner (SET AUTHORIZATION) stays with people.");
    }
    int j = i + 1;
    String object = wordAt(t, j);
    Role role = Role.TABLE;
    switch (object) {
      case "TABLE", "VIEW" -> j++;
      case "MATERIALIZED" -> {
        if (!word(t, j + 1, "VIEW")) {
          return unknown("ALTER " + object);
        }
        j += 2;
        object = "MATERIALIZED VIEW";
      }
      case "SCHEMA" -> {
        role = Role.SCHEMA;
        j++;
      }
      default -> {
        return Statement.refused(Kind.UNREADABLE, "ALTER " + object, "ALTER " + object + " isn't a statement "
            + "GateRook runs on Trino.");
      }
    }
    j = skipIf(t, j);
    Name name = name(t, j);
    if (name == null) {
      return Statement.refused(Kind.UNREADABLE, "ALTER " + object, "Name what to alter.");
    }
    List<Ref> refs = new ArrayList<>();
    for (int k = j + tokens(name); k + 1 < t.size(); k++) {
      if (t.get(k).is("RENAME") && word(t, k + 1, "TO")) {
        Name to = name(t, k + 2);
        if (to != null) {
          refs.add(new Ref(to, role));
        }
      }
    }
    return changed("ALTER " + object, text, new Ref(name, role), j, refs);
  }

  private static Statement drop(List<Token> t, int i, String text) {
    int j = i + 1;
    String object = wordAt(t, j);
    return switch (object) {
      case "ROLE" -> Statement.refused(Kind.FORBIDDEN, "DROP ROLE", "Roles stay with people: GateRook doesn't run "
          + "DROP ROLE.");
      case "CATALOG" -> Statement.refused(Kind.FORBIDDEN, "DROP CATALOG", "Catalogs stay with people: GateRook "
          + "doesn't run DROP CATALOG.");
      case "TABLE", "VIEW" -> changeAt(t, skipIf(t, j + 1), text, "DROP " + object, Role.TABLE);
      case "MATERIALIZED" -> word(t, j + 1, "VIEW")
          ? changeAt(t, skipIf(t, j + 2), text, "DROP MATERIALIZED VIEW", Role.TABLE) : unknown("DROP " + object);
      case "SCHEMA" -> changeAt(t, skipIf(t, j + 1), text, "DROP SCHEMA", Role.SCHEMA);
      case "FUNCTION" -> changeAt(t, skipIf(t, j + 1), text, "DROP FUNCTION", Role.FUNCTION);
      default -> Statement.refused(Kind.UNREADABLE, "DROP " + object, "DROP " + object + " isn't a statement "
          + "GateRook runs on Trino.");
    };
  }

  private static Statement comment(List<Token> t, int i, String text) {
    if (!word(t, i + 1, "ON")) {
      return unknown("COMMENT");
    }
    String object = wordAt(t, i + 2);
    return switch (object) {
      case "TABLE", "VIEW" -> changeAt(t, i + 3, text, "COMMENT ON " + object, Role.TABLE);
      case "COLUMN" -> changeAt(t, i + 3, text, "COMMENT ON COLUMN", Role.COLUMN);
      default -> unknown("COMMENT ON " + object);
    };
  }

  /** A change whose keyword is followed by {@code expect} (or nothing) and then the name it changes. */
  private static Statement change(List<Token> t, int i, String text, String type, String expect, Role role) {
    int j = i + 1;
    if (expect != null) {
      if (!word(t, j, expect)) {
        return Statement.refused(Kind.UNREADABLE, type, "Write " + type.split(" ")[0] + " " + expect + " table.");
      }
      j++;
    }
    return changeAt(t, j, text, type, role);
  }

  private static Statement changeAt(List<Token> t, int j, String text, String type, Role role) {
    Name name = name(t, j);
    if (name == null) {
      return Statement.refused(Kind.UNREADABLE, type, "Name what " + type + " changes.");
    }
    return changed(type, text, new Ref(name, role), j, List.of());
  }

  private static Statement changed(String type, String text, Ref target, int at, List<Ref> refs) {
    return new Statement(Kind.CHANGE, type, text, null, target, at, List.copyOf(refs), false, false, null);
  }

  /** {@code IF EXISTS} or {@code IF NOT EXISTS} at {@code j}: where the name starts. */
  private static int skipIf(List<Token> t, int j) {
    if (word(t, j, "IF")) {
      j++;
      if (word(t, j, "NOT")) {
        j++;
      }
      if (word(t, j, "EXISTS")) {
        j++;
      }
    }
    return j;
  }

  // ── What a change reads ─────────────────────────────────────────────────────────────────────────────────────

  /** The read-only query of what a change reads, or why GateRook can't build it with certainty. */
  private record Read(String query, String problem) {

    static Read of(String query) {
      return new Read(query, null);
    }

    static Read refused(String problem) {
      return new Read(null, problem);
    }
  }

  /**
   * A change Trino plans as a write, planned by the read-only query of what it reads; a data definition statement as
   * it is; or the change refused when that query can't be built with certainty. The query is cut from the statement's
   * own text at its tokens, so a string, a quoted name or a comment stays whole and no comment hides what follows it;
   * GateRook reads the query once more, and it must be a query (SELECT, WITH or VALUES) that GateRook reads as a read.
   */
  private static Statement reads(List<Token> t, String text, Statement st) {
    boolean execute = (st.type().equals("ALTER TABLE") || st.type().equals("ALTER MATERIALIZED VIEW"))
        && word(t, after(st), "EXECUTE");
    if (!execute && !PLANNED_WRITES.contains(st.type())) {
      return st;
    }
    int[] depth = nesting(t);
    if (depth == null) {
      return Statement.refused(Kind.UNREADABLE, st.type(), "The parentheses, brackets or CASE … END of " + st.type()
          + " don't pair up, so GateRook can't tell what it reads.");
    }
    Read derived = execute ? execute(t, text, st, depth) : switch (st.type()) {
      case "INSERT" -> insert(t, text, st);
      case "CREATE TABLE AS" -> createAs(t, text, st, depth);
      case "DELETE" -> delete(t, text, st);
      case "UPDATE" -> update(t, text, st, depth);
      case "MERGE" -> merge(t, text, st, depth);
      case "ANALYZE" -> analyze(t, text, st);
      case "REFRESH MATERIALIZED VIEW" -> after(st) == t.size() ? Read.of("SELECT 1 FROM " + target(t, text, st))
          : Read.refused("Write REFRESH MATERIALIZED VIEW name.");
      default -> Read.refused(st.type() + " isn't a change GateRook plans.");
    };
    if (derived.problem() != null) {
      return Statement.refused(Kind.UNREADABLE, st.type(), derived.problem());
    }
    Statement query = read(derived.query());
    if (query.kind() != Kind.READ || !(query.type().equals("SELECT") || query.type().equals("VALUES"))) {
      String why = query.problem() != null ? query.problem() : query.type() + " isn't a query.";
      return Statement.refused(query.kind() == Kind.FORBIDDEN ? Kind.FORBIDDEN : Kind.UNREADABLE, st.type(),
          "GateRook plans " + st.type() + " by a read of what it reads, and can't read that as a query: " + why);
    }
    return st.plannedBy(derived.query());
  }

  /** {@code INSERT INTO t [@ branch] [(column, …)] query}: the query. */
  private static Read insert(List<Token> t, String text, Statement st) {
    int j = branch(t, after(st));
    if (j < 0) {
      return Read.refused("Write INSERT INTO table [@ branch] [(columns)] SELECT ….");
    }
    if (punct(t, j, '(') && !startsQuery(t, j + 1)) {
      int close = closing(t, j);
      if (close < 0 || !names(t, j + 1, close)) {
        return Read.refused("The column list of INSERT is names separated by commas: INSERT INTO table (a, b) "
            + "SELECT ….");
      }
      j = close + 1;
    }
    if (!startsQuery(t, j)) {
      return Read.refused("INSERT inserts a query: write INSERT INTO table [(columns)] followed by SELECT …, WITH … "
          + "or VALUES ….");
    }
    if (t.get(j).is("TABLE")) {
      return Read.refused("GateRook plans INSERT by the query it inserts and reads SELECT, WITH or VALUES there: "
          + "write SELECT * FROM table instead of TABLE table.");
    }
    return Read.of(query(t, text, j, t.size() - 1));
  }

  /** {@code CREATE TABLE t [(column, …)] [COMMENT …] [WITH (…)] AS query [WITH [NO] DATA]}: the query. */
  private static Read createAs(List<Token> t, String text, Statement st, int[] depth) {
    int as = -1;
    for (int k = after(st); k < t.size(); k++) {
      if (depth[k] == 0 && t.get(k).is("AS")) {
        as = k;
        break;
      }
    }
    int end = t.size() - 1;
    if (word(t, end, "DATA") && word(t, end - 1, "WITH")) {
      end -= 2;
    } else if (word(t, end, "DATA") && word(t, end - 1, "NO") && word(t, end - 2, "WITH")) {
      end -= 3;
    }
    if (as < 0 || as + 1 > end || !startsQuery(t, as + 1) || t.get(as + 1).is("TABLE")) {
      return Read.refused("CREATE TABLE AS creates a table from a query: write CREATE TABLE table AS SELECT …, WITH "
          + "… or VALUES …, optionally followed by WITH [NO] DATA.");
    }
    return Read.of(query(t, text, as + 1, end));
  }

  /** {@code DELETE FROM t [@ branch] [WHERE condition]}: {@code SELECT 1 FROM t [WHERE condition]}. */
  private static Read delete(List<Token> t, String text, Statement st) {
    int j = branch(t, after(st));
    String from = "SELECT 1 FROM " + target(t, text, st);
    if (j == t.size()) {
      return Read.of(from);
    }
    if (j < 0 || !word(t, j, "WHERE") || j + 1 >= t.size()) {
      return Read.refused("Write DELETE FROM table [WHERE condition]: GateRook plans it by the rows it reads.");
    }
    return Read.of(from + " " + slice(t, text, j, t.size() - 1));
  }

  /**
   * {@code UPDATE t [@ branch] SET a = v1, b = v2 [WHERE condition]}:
   * {@code SELECT v1, v2 FROM t [WHERE condition]}.
   */
  private static Read update(List<Token> t, String text, Statement st, int[] depth) {
    int j = branch(t, after(st));
    if (j < 0 || !word(t, j, "SET")) {
      return Read.refused("Write UPDATE table SET column = value, … [WHERE condition]: GateRook plans it by the "
          + "values it reads.");
    }
    int where = j + 1;
    while (where < t.size() && !(depth[where] == 0 && t.get(where).is("WHERE"))) {
      where++;
    }
    List<String> values = new ArrayList<>();
    if (!assignments(t, text, depth, j + 1, where, values) || (where < t.size() && where + 1 == t.size())) {
      return Read.refused("Write UPDATE table SET column = value, … [WHERE condition]: GateRook plans it by the "
          + "values it reads.");
    }
    return Read.of("SELECT " + String.join(", ", values) + " FROM " + target(t, text, st)
        + (where < t.size() ? " " + slice(t, text, where, t.size() - 1) : ""));
  }

  /**
   * {@code MERGE INTO t [@ branch] [[AS] a] USING s [[AS] b] ON c WHEN …}: {@code SELECT <each WHEN … AND condition,
   * each UPDATE SET value and each INSERT value, or 1> FROM t [a] FULL JOIN s [b] ON c}. A source that is itself a join
   * is refused: its own ON can't be told from the MERGE's.
   */
  private static Read merge(List<Token> t, String text, Statement st, int[] depth) {
    String shape = "Write MERGE INTO table [alias] USING source [alias] ON condition WHEN …: GateRook plans it by what "
        + "it reads.";
    int j = branch(t, after(st));
    if (j < 0) {
      return Read.refused(shape);
    }
    String alias = null;
    if (word(t, j, "AS")) {
      if (j + 1 >= t.size() || !t.get(j + 1).name()) {
        return Read.refused(shape);
      }
      alias = slice(t, text, j + 1, j + 1);
      j += 2;
    } else if (j < t.size() && t.get(j).name() && !t.get(j).is("USING")) {
      alias = slice(t, text, j, j);
      j++;
    }
    if (!word(t, j, "USING")) {
      return Read.refused(shape);
    }
    int source = j + 1;
    int on = -1;
    for (int k = source; k < t.size(); k++) {
      if (depth[k] == 0 && t.get(k).is("JOIN")) {
        return Read.refused("The source of MERGE is a join, whose ON GateRook can't tell from the MERGE's own: put "
            + "it in a subquery, USING (SELECT … FROM a JOIN b ON …) s.");
      }
      if (depth[k] == 0 && t.get(k).is("ON")) {
        on = k;
        break;
      }
    }
    int when = on < 0 ? -1 : nextWhen(t, depth, on + 1);
    if (on <= source || when <= on + 1 || when == t.size()) {
      return Read.refused(shape);
    }
    String cases = "Each WHEN of MERGE is WHEN [NOT] MATCHED [AND condition] THEN followed by UPDATE SET column = "
        + "value, …, DELETE or INSERT [(columns)] VALUES (…).";
    List<String> values = new ArrayList<>();
    for (int k = when; k < t.size(); ) {
      int next = nextWhen(t, depth, k + 1);
      int m = k + 1 + (word(t, k + 1, "NOT") ? 1 : 0);
      if (!word(t, m, "MATCHED")) {
        return Read.refused(cases);
      }
      int then = m + 1;
      while (then < next && !(depth[then] == 0 && t.get(then).is("THEN"))) {
        then++;
      }
      if (then >= next) {
        return Read.refused(cases);
      }
      if (then > m + 1) {
        if (!word(t, m + 1, "AND") || then == m + 2) {
          return Read.refused(cases);
        }
        values.add(slice(t, text, m + 2, then - 1));
      }
      int action = then + 1;
      if (word(t, action, "DELETE")) {
        if (action + 1 != next) {
          return Read.refused(cases);
        }
      } else if (word(t, action, "UPDATE") && word(t, action + 1, "SET")) {
        if (action + 2 >= next || !assignments(t, text, depth, action + 2, next, values)) {
          return Read.refused(cases);
        }
      } else if (word(t, action, "INSERT")) {
        int v = action + 1;
        if (punct(t, v, '(')) {
          int close = closing(t, v);
          if (close < 0 || !names(t, v + 1, close)) {
            return Read.refused(cases);
          }
          v = close + 1;
        }
        int close = word(t, v, "VALUES") && punct(t, v + 1, '(') ? closing(t, v + 1) : -1;
        if (close != next - 1 || close == v + 2) {
          return Read.refused(cases);
        }
        List<String> inserted = list(t, text, depth, v + 2, close);
        if (inserted == null) {
          return Read.refused(cases);
        }
        values.addAll(inserted);
      } else {
        return Read.refused(cases);
      }
      k = next;
    }
    return Read.of("SELECT " + (values.isEmpty() ? "1" : String.join(", ", values)) + " FROM " + target(t, text, st)
        + (alias == null ? "" : " " + alias) + " FULL JOIN " + slice(t, text, source, on - 1) + " ON "
        + slice(t, text, on + 1, when - 1));
  }

  /** {@code ANALYZE t [WITH (property = value, …)]}: {@code SELECT 1 FROM t}; the properties are constants. */
  private static Read analyze(List<Token> t, String text, Statement st) {
    int j = after(st);
    if (j != t.size() && !(word(t, j, "WITH") && punct(t, j + 1, '(') && closing(t, j + 1) == t.size() - 1)) {
      return Read.refused("Write ANALYZE table [WITH (property = value, …)].");
    }
    return Read.of("SELECT 1 FROM " + target(t, text, st));
  }

  /**
   * {@code ALTER TABLE t EXECUTE procedure[(argument, …)] [WHERE condition]}, and the same of a materialized view:
   * {@code SELECT 1 FROM t [WHERE condition]}; the arguments are constants.
   */
  private static Read execute(List<Token> t, String text, Statement st, int[] depth) {
    int j = after(st) + 1;
    String shape = "Write " + st.type() + " name EXECUTE procedure[(arguments)] [WHERE condition].";
    if (j >= t.size() || !t.get(j).name()) {
      return Read.refused(shape);
    }
    j++;
    if (punct(t, j, '(')) {
      int close = closing(t, j);
      if (close < 0) {
        return Read.refused(shape);
      }
      j = close + 1;
    }
    String from = "SELECT 1 FROM " + target(t, text, st);
    if (j == t.size()) {
      return Read.of(from);
    }
    if (!word(t, j, "WHERE") || depth[j] != 0 || j + 1 >= t.size()) {
      return Read.refused(shape);
    }
    return Read.of(from + " " + slice(t, text, j, t.size() - 1));
  }

  /**
   * {@code column = value, …} from {@code from} up to {@code to} (exclusive) at the statement's top level: each value
   * into {@code values}; {@code false} when it isn't such a list.
   */
  private static boolean assignments(List<Token> t, String text, int[] depth, int from, int to, List<String> values) {
    if (from >= to) {
      return false;
    }
    int k = from;
    while (k < to) {
      if (!t.get(k).name() || !punct(t, k + 1, '=') || depth[k] != 0) {
        return false;
      }
      int value = k + 2;
      int end = value;
      while (end < to && !(depth[end] == 0 && t.get(end).punct(','))) {
        end++;
      }
      if (end == value || end == to - 1) {
        return false;
      }
      values.add(slice(t, text, value, end - 1));
      k = end + 1;
    }
    return true;
  }

  /** The items of a list from {@code from} up to {@code to} (exclusive), split at its own commas; or {@code null}. */
  private static List<String> list(List<Token> t, String text, int[] depth, int from, int to) {
    List<String> out = new ArrayList<>();
    int level = depth[from];
    int start = from;
    for (int k = from; k <= to; k++) {
      if (k == to || (depth[k] == level && t.get(k).punct(','))) {
        if (k == start) {
          return null;
        }
        out.add(slice(t, text, start, k - 1));
        start = k + 1;
      }
    }
    return out;
  }

  /** The next {@code WHEN} of a MERGE at the top level from {@code k}, or the end. */
  private static int nextWhen(List<Token> t, int[] depth, int k) {
    while (k < t.size() && !(depth[k] == 0 && t.get(k).is("WHEN"))) {
      k++;
    }
    return k;
  }

  /**
   * How deep each token is in parentheses, brackets, braces and {@code CASE … END}, a closing one as deep as its
   * opening one; {@code null} when they don't pair up.
   */
  static int[] nesting(List<Token> t) {
    int[] depth = new int[t.size()];
    Deque<String> open = new ArrayDeque<>();
    for (int k = 0; k < t.size(); k++) {
      Token tok = t.get(k);
      String closes = tok.punct(')') ? "(" : tok.punct(']') ? "[" : tok.punct('}') ? "{" : tok.is("END") ? "CASE"
          : null;
      if (closes != null) {
        if (open.isEmpty() || !open.peek().equals(closes)) {
          return null;
        }
        open.pop();
        depth[k] = open.size();
        continue;
      }
      depth[k] = open.size();
      if (tok.punct('(') || tok.punct('[') || tok.punct('{')) {
        open.push(tok.text());
      } else if (tok.is("CASE")) {
        open.push("CASE");
      }
    }
    return open.isEmpty() ? depth : null;
  }

  /** The token after a change's target. */
  private static int after(Statement st) {
    return st.targetAt() + tokens(st.target().name());
  }

  /** The target's name as written. */
  private static String target(List<Token> t, String text, Statement st) {
    return slice(t, text, st.targetAt(), after(st) - 1);
  }

  /** After an optional {@code @ branch} at {@code j}; {@code -1} when {@code @} names no branch. */
  private static int branch(List<Token> t, int j) {
    if (!punct(t, j, '@')) {
      return j;
    }
    return j + 1 < t.size() && t.get(j + 1).name() ? j + 2 : -1;
  }

  /** Whether a query starts at {@code j}: {@code SELECT}, {@code WITH}, {@code VALUES}, {@code TABLE} or {@code (}. */
  private static boolean startsQuery(List<Token> t, int j) {
    return j < t.size() && (t.get(j).punct('(') || QUERIES.contains(wordAt(t, j)) || t.get(j).is("TABLE"));
  }

  /** The query from {@code from} to {@code to}, without parentheses around all of it (a statement can't be one). */
  private static String query(List<Token> t, String text, int from, int to) {
    while (from < to && t.get(from).punct('(') && closing(t, from) == to) {
      from++;
      to--;
    }
    return slice(t, text, from, to);
  }

  /** Whether {@code from} up to {@code to} (exclusive) is one or more names separated by commas. */
  private static boolean names(List<Token> t, int from, int to) {
    if (from >= to) {
      return false;
    }
    for (int k = from; k < to; k++) {
      boolean name = (k - from) % 2 == 0;
      if (name ? !t.get(k).name() : !t.get(k).punct(',')) {
        return false;
      }
    }
    return (to - from) % 2 == 1;
  }

  /** The text from token {@code from} to token {@code to}, both included, as written. */
  private static String slice(List<Token> t, String text, int from, int to) {
    return text.substring(t.get(from).start(), t.get(to).end());
  }

  private static boolean punct(List<Token> t, int j, char c) {
    return j >= 0 && j < t.size() && t.get(j).punct(c);
  }

  // ── Functions ───────────────────────────────────────────────────────────────────────────────────────────────

  /** What {@link #functions} found: why the statement is refused, or the refs it adds to the statement's own. */
  private record Functions(String problem, List<Ref> refs) {}

  /**
   * Table functions and qualified functions of the statement: a table function other than Trino's own built-in ones
   * and an AI function ({@code ai_…}, which calls a model provider) are refused; a function of a catalog becomes a
   * ref the connection checks. The statement's own target, with an {@code INSERT}'s column list or a function's
   * parameters right after it, isn't a call.
   */
  private static Functions functions(List<Token> t, Statement st) {
    List<Ref> refs = new ArrayList<>(st.refs());
    int skipFrom = st.targetAt();
    int skipTo = st.target() == null ? -1 : st.targetAt() + tokens(st.target().name());
    for (int k = 0; k < t.size(); k++) {
      if (k >= skipFrom && k < skipTo) {
        continue;
      }
      Token tok = t.get(k);
      if (tok.is("TABLE") && k + 1 < t.size() && t.get(k + 1).punct('(')) {
        Name fn = name(t, k + 2);
        if (fn != null && k + 2 + tokens(fn) < t.size() && t.get(k + 2 + tokens(fn)).punct('(')) {
          List<String> p = fn.parts().stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
          boolean safe = SAFE_TABLE_FUNCTIONS.contains(p.getLast()) && (p.size() == 1 || p.size() == 3
              && p.get(0).equals("system") && p.get(1).equals("builtin"));
          if (!safe) {
            return new Functions("The table function " + fn.text() + " reaches past what Trino's plan shows (a "
                + "pass-through query of a JDBC catalog runs any SQL there): GateRook runs no table function but "
                + "sequence and exclude_columns.", refs);
          }
        }
      }
      if (!tok.name() || (k > 0 && t.get(k - 1).punct('.'))) {
        continue;
      }
      Name fn = name(t, k);
      int after = k + tokens(fn);
      if (after >= t.size() || !t.get(after).punct('(')) {
        continue;
      }
      String last = fn.parts().getLast().toLowerCase(Locale.ROOT);
      if (last.startsWith("ai_")) {
        return new Functions("AI functions (" + fn.text() + ") send data to a model provider from Trino: GateRook "
            + "doesn't run them, as no plan shows what they send or cost.", refs);
      }
      if (fn.parts().size() > 3) {
        return new Functions(fn.text() + " has more parts than a Trino function name.", refs);
      }
      if (fn.parts().size() == 3 && !(fn.parts().get(0).equalsIgnoreCase("system")
          && fn.parts().get(1).equalsIgnoreCase("builtin"))) {
        refs.add(new Ref(fn, Role.FUNCTION));
      }
      k = after - 1;
    }
    return new Functions(null, refs);
  }

  // ── Names ───────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * A name an agent gave as a tool argument ({@code catalog.schema.table}, {@code "My Table"}), read by the same
   * tokens: one dotted name of at most three parts and nothing else, or {@code null}.
   */
  static Name qualified(String written) {
    if (written == null || written.isBlank() || written.length() > 1_000) {
      return null;
    }
    boolean quoted = false;
    for (int i = 0; i + 1 < written.length(); i++) {
      char c = written.charAt(i);
      if (c == '"') {
        quoted = !quoted;
      } else if (!quoted && ((c == '-' && written.charAt(i + 1) == '-') || (c == '/' && written.charAt(i + 1) == '*'))) {
        return null;
      }
    }
    Scan scan = scan(written);
    if (scan.problem() != null || scan.end() != written.length()) {
      return null;
    }
    Name name = name(scan.tokens(), 0);
    if (name == null || tokens(name) != scan.tokens().size() || name.parts().size() > 3) {
      return null;
    }
    return name;
  }

  /** The dotted name starting at {@code j}, or {@code null}. */
  static Name name(List<Token> t, int j) {
    if (j >= t.size() || !t.get(j).name()) {
      return null;
    }
    List<String> parts = new ArrayList<>();
    parts.add(part(t.get(j)));
    int k = j + 1;
    while (k + 1 < t.size() && t.get(k).punct('.') && t.get(k + 1).name()) {
      parts.add(part(t.get(k + 1)));
      k += 2;
    }
    return new Name(parts);
  }

  /** How many tokens {@code name} took: its parts and the dots between them. */
  static int tokens(Name name) {
    return name.parts().size() * 2 - 1;
  }

  private static String part(Token tok) {
    return tok.type() == T.WORD ? tok.text().toLowerCase(Locale.ROOT) : tok.text();
  }

  private static boolean word(List<Token> t, int j, String word) {
    return j >= 0 && j < t.size() && t.get(j).is(word);
  }

  private static String wordAt(List<Token> t, int j) {
    return j < t.size() && t.get(j).type() == T.WORD ? t.get(j).text() : "";
  }

  private static boolean anyWord(List<Token> t, int from, String word) {
    for (int k = from; k < t.size(); k++) {
      if (t.get(k).is(word)) {
        return true;
      }
    }
    return false;
  }
}
