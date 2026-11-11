package ai.msrv.gaterook.connectors.athena;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What one Athena statement is, without I/O: the gate before Athena's own answer. Athena engine version 3 runs queries
 * and DML with Trino and most DDL with Hive, so this reads both: Trino's tokens ({@code '…'} strings with {@code ''},
 * {@code "…"} names with {@code ""}, {@code --} and block comments that don't nest) and Hive's backquoted names
 * ({@code `…`} with {@code ``}). Where the two engines read a text differently, the statement is refused rather than
 * guessed at: a statement Athena hands to Hive may hold no backslash in a string or a name (an escape to Hive, a
 * character to Trino) and no block comment. Whatever can't be read with certainty is {@link Kind#UNREADABLE} or
 * {@link Kind#FORBIDDEN}, and a call refuses it.
 *
 * <p>This only picks the route; Athena's own plan ({@code EXPLAIN (TYPE IO, FORMAT JSON)}) decides a query before it
 * runs. {@code UNLOAD}, which writes query results to Amazon S3, external functions ({@code USING EXTERNAL FUNCTION},
 * which call Lambda or SageMaker), prepared statements, sessions and access control are never run through GateRook,
 * nor table functions other than Trino's built-in {@code sequence} and {@code exclude_columns} (a federated catalog's
 * pass-through query runs any SQL in its source).
 *
 * <p>Copied from the Trino connector's reader (connectors don't share internals) and adapted to Athena.
 */
final class AthenaSql {

  /** The route of a statement. */
  enum Kind { READ, CHANGE, FORBIDDEN, UNREADABLE }

  /** What a name in a statement stands for: which of its parts are the catalog and the database. */
  enum Role { CATALOG, DATABASE, TABLE, COLUMN, FUNCTION }

  /** A name as written: its parts, quoted ones without their quotes. */
  record Name(List<String> parts) {

    Name {
      parts = List.copyOf(parts);
    }

    /** The name as Athena resolves it: case-insensitive, so lower case. */
    String text() {
      return String.join(".", parts).toLowerCase(Locale.ROOT);
    }

    /** The catalog this name names, or {@code null} when it is the session's. */
    String catalog(Role role) {
      int qualified = switch (role) {
        case CATALOG -> 1;
        case DATABASE -> 2;
        case TABLE, FUNCTION -> 3;
        case COLUMN -> 4;
      };
      return parts.size() >= qualified ? parts.getFirst().toLowerCase(Locale.ROOT) : null;
    }

    /**
     * The database this name names, or {@code null} when it is the session's: the last part of a database, the one
     * before a table's (or a function's) name, the one before a column's table.
     */
    String database(Role role) {
      int fromEnd = switch (role) {
        case CATALOG -> 0;
        case DATABASE -> 1;
        case TABLE, FUNCTION -> 2;
        case COLUMN -> 3;
      };
      int index = parts.size() - fromEnd;
      return fromEnd == 0 || index < 0 ? null : parts.get(index).toLowerCase(Locale.ROOT);
    }
  }

  /** A name the statement uses, and what it stands for. */
  record Ref(Name name, Role role) {}

  /**
   * One call's text.
   *
   * @param type     what it is, upper case: {@code SELECT}, {@code SHOW TABLES}, {@code INSERT}, {@code CREATE TABLE AS}
   * @param sql      the text that is sent: the statement without a final semicolon
   * @param planned  for a read Athena plans with Trino, the text whose IO plan decides it; {@code null} for a read Hive
   *                 runs ({@code SHOW}, {@code DESCRIBE}), which no plan covers, and for a change Hive runs
   * @param target   for a change, the object it changes; else {@code null}
   * @param targetAt the token where {@link #target}'s name starts, or {@code -1}
   * @param refs     names whose database the connection checks
   * @param hive     whether Athena runs it with Hive (DDL, {@code SHOW}, {@code DESCRIBE}) rather than Trino
   * @param problem  why it is FORBIDDEN or UNREADABLE, for the agent; {@code null} otherwise
   */
  record Statement(Kind kind, String type, String sql, String planned, Ref target, int targetAt, List<Ref> refs,
      boolean hive, String problem) {

    static Statement refused(Kind kind, String type, String problem) {
      return new Statement(kind, type, null, null, null, -1, List.of(), false, problem);
    }

    Statement withRefs(List<Ref> more) {
      return new Statement(kind, type, sql, planned, target, targetAt, List.copyOf(more), hive, problem);
    }

    boolean read() {
      return kind == Kind.READ;
    }

    boolean change() {
      return kind == Kind.CHANGE;
    }
  }

  static final int MAX_LENGTH = 100_000;

  private static final Set<String> SAFE_TABLE_FUNCTIONS = Set.of("sequence", "exclude_columns");
  private static final Set<String> QUERIES = Set.of("SELECT", "WITH", "VALUES");
  /** Statements Athena runs with Trino; the rest of its DDL runs with Hive. */
  private static final Set<String> TRINO = Set.of("SELECT", "WITH", "VALUES", "EXPLAIN", "INSERT", "UPDATE", "DELETE",
      "MERGE", "CREATE TABLE AS", "CREATE VIEW", "CREATE OR REPLACE VIEW", "DROP VIEW", "OPTIMIZE", "VACUUM");

  private AthenaSql() {}

  // ── Tokens ──────────────────────────────────────────────────────────────────────────────────────────────────

  enum T { WORD, QUOTED, STRING, NUMBER, PUNCT }

  /**
   * One token of the code.
   *
   * @param text      {@code WORD}: upper case; {@code QUOTED}: the name without quotes; {@code PUNCT}: the character
   * @param start     where it starts in the text
   * @param backslash a string or a name with a backslash in it
   */
  record Token(T type, String text, int start, boolean backslash) {

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

  /** The tokens of a text, where the text to send ends, whether it has a block comment, or why it can't be read. */
  record Scan(List<Token> tokens, int end, boolean blockComment, String problem) {}

  static Scan scan(String sql) {
    List<Token> out = new ArrayList<>();
    int n = sql.length();
    boolean block = false;
    int i = 0;
    while (i < n) {
      char c = sql.charAt(i);
      if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
        i++;
        continue;
      }
      if (c < 0x20 || c == 0x7F) {
        return new Scan(out, n, block, "The statement has a control character, which GateRook doesn't analyze.");
      }
      if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
        while (i < n && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') {
          if (control(sql.charAt(i))) {
            return new Scan(out, n, block, "A comment has a control character, which GateRook doesn't analyze.");
          }
          i++;
        }
        continue;
      }
      if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
        int close = sql.indexOf("*/", i + 2);
        if (close < 0) {
          return new Scan(out, n, block, "A comment isn't closed.");
        }
        String body = sql.substring(i + 2, close);
        if (body.contains("/*")) {
          return new Scan(out, n, block, "A comment opens inside a comment, which GateRook doesn't analyze.");
        }
        if (body.chars().anyMatch(ch -> control((char) ch))) {
          return new Scan(out, n, block, "A comment has a control character, which GateRook doesn't analyze.");
        }
        block = true;
        i = close + 2;
        continue;
      }
      if (c == '*' && i + 1 < n && sql.charAt(i + 1) == '/') {
        return new Scan(out, n, block, "A comment closes that never opened, which GateRook doesn't analyze.");
      }
      if (c == '\'' || c == '"' || c == '`') {
        int j = i + 1;
        StringBuilder value = new StringBuilder();
        boolean closed = false;
        boolean backslash = false;
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
            return new Scan(out, n, block, (c == '\'' ? "A string" : "A quoted name") + " has a control character, "
                + "which GateRook doesn't analyze.");
          }
          if (d == '\\') {
            backslash = true;
          }
          value.append(d);
          j++;
        }
        if (!closed) {
          return new Scan(out, n, block, c == '\'' ? "A string isn't closed." : "A quoted name isn't closed.");
        }
        if (c != '\'' && value.isEmpty()) {
          return new Scan(out, n, block, "A quoted name is empty.");
        }
        out.add(new Token(c == '\'' ? T.STRING : T.QUOTED, value.toString(), i, backslash));
        i = j + 1;
        continue;
      }
      if (c > 0x7F) {
        return new Scan(out, n, block, "The code has a character outside ASCII: Athena reads such names only in "
            + "double quotes.");
      }
      if (Character.isLetter(c) || c == '_') {
        int j = i;
        while (j < n && (isAsciiLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_')) {
          j++;
        }
        out.add(new Token(T.WORD, sql.substring(i, j).toUpperCase(Locale.ROOT), i, false));
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
        out.add(new Token(T.NUMBER, sql.substring(i, j), i, false));
        i = j;
        continue;
      }
      out.add(new Token(T.PUNCT, String.valueOf(c), i, false));
      i++;
    }
    int end = n;
    for (int k = 0; k < out.size(); k++) {
      if (out.get(k).punct(';')) {
        if (k != out.size() - 1) {
          return new Scan(out, n, block, "One statement per call: this text has more than one. Send them one by one.");
        }
        end = out.get(k).start();
        out.remove(k);
        break;
      }
    }
    return new Scan(out, end, block, null);
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
    List<Token> t = scan.tokens();
    if (t.isEmpty()) {
      return Statement.refused(Kind.UNREADABLE, "statement", "The statement has no keyword.");
    }
    String text = sql.substring(0, scan.end()).strip();
    Statement st = statement(t, text);
    if (st.kind() != Kind.READ && st.kind() != Kind.CHANGE) {
      return st;
    }
    if (st.hive()) {
      // Hive reads a backslash as an escape and may not read block comments: the two readings could part.
      if (scan.blockComment()) {
        return Statement.refused(Kind.UNREADABLE, st.type(), "Athena runs " + st.type() + " with Hive, which reads "
            + "/* */ comments differently: use -- comments.");
      }
      if (t.stream().anyMatch(Token::backslash)) {
        return Statement.refused(Kind.UNREADABLE, st.type(), "Athena runs " + st.type() + " with Hive, which reads "
            + "a backslash in a string or a name as an escape: GateRook doesn't analyze it.");
      }
    } else if (t.stream().anyMatch(tok -> tok.type() == T.QUOTED && tok.backslash())) {
      return Statement.refused(Kind.UNREADABLE, st.type(), "A backslash in a quoted name isn't analyzed.");
    }
    Functions f = functions(t, st);
    if (f.problem() != null) {
      return Statement.refused(Kind.FORBIDDEN, st.type(), f.problem());
    }
    return st.withRefs(f.refs());
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
          ? Statement.refused(Kind.FORBIDDEN, "WITH SESSION", "WITH SESSION sets session properties: GateRook runs "
              + "each query in the workgroup's own session.")
          : query("SELECT", text, text);
      case "SHOW" -> show(t, i, text);
      case "DESCRIBE", "DESC" -> describe(t, i, text);
      case "EXPLAIN" -> explain(t, i, text);
      case "INSERT" -> change(t, i, text, "INSERT", "INTO", Role.TABLE);
      case "DELETE" -> change(t, i, text, "DELETE", "FROM", Role.TABLE);
      case "MERGE" -> change(t, i, text, "MERGE", "INTO", Role.TABLE);
      case "UPDATE" -> change(t, i, text, "UPDATE", null, Role.TABLE);
      case "OPTIMIZE" -> change(t, i, text, "OPTIMIZE", null, Role.TABLE);
      case "VACUUM" -> change(t, i, text, "VACUUM", null, Role.TABLE);
      case "MSCK" -> word(t, i + 1, "REPAIR") && word(t, i + 2, "TABLE")
          ? changeAt(t, i + 3, text, "MSCK REPAIR TABLE", Role.TABLE) : unknown(first);
      case "CREATE" -> create(t, i, text);
      case "ALTER" -> alter(t, i, text);
      case "DROP" -> drop(t, i, text);
      case "UNLOAD" -> Statement.refused(Kind.FORBIDDEN, "UNLOAD", "UNLOAD writes query results to files in Amazon "
          + "S3: GateRook doesn't run it. Read the rows with athena_query.");
      case "USING" -> Statement.refused(Kind.FORBIDDEN, "USING EXTERNAL FUNCTION", "External functions call Lambda "
          + "or SageMaker from Athena: GateRook doesn't run them, as no plan shows what they do or cost.");
      case "GRANT", "REVOKE", "DENY" -> Statement.refused(Kind.FORBIDDEN, first, "Access control stays with people: "
          + "GateRook doesn't run GRANT or REVOKE.");
      case "SET", "RESET", "USE" -> Statement.refused(Kind.FORBIDDEN, first, first + " changes the session: the "
          + "connection sets the catalog and database. Name tables as database.table.");
      case "START", "COMMIT", "ROLLBACK" -> Statement.refused(Kind.FORBIDDEN, first, "Transactions aren't run "
          + "through GateRook: each call is one statement.");
      case "PREPARE", "EXECUTE", "DEALLOCATE" -> Statement.refused(Kind.FORBIDDEN, first, "Prepared statements "
          + "aren't run through GateRook: send the statement itself.");
      case "CALL" -> Statement.refused(Kind.FORBIDDEN, "CALL", "Procedures reach past what Athena's plan shows: "
          + "GateRook doesn't run CALL.");
      default -> unknown(first);
    };
  }

  private static Statement unknown(String first) {
    return Statement.refused(Kind.UNREADABLE, first, first + " isn't a statement GateRook runs on Athena.");
  }

  private static Statement query(String type, String text, String planned) {
    return new Statement(Kind.READ, type, text, planned, null, -1, List.of(), false, null);
  }

  /** A read Athena runs with Hive: no plan, only its names tell what it reads. */
  private static Statement hiveRead(String type, String text, List<Ref> refs) {
    return new Statement(Kind.READ, type, text, null, null, -1, List.copyOf(refs), true, null);
  }

  private static Statement explain(List<Token> t, int i, String text) {
    int j = i + 1;
    if (word(t, j, "ANALYZE")) {
      return Statement.refused(Kind.FORBIDDEN, "EXPLAIN ANALYZE", "EXPLAIN ANALYZE runs the statement it explains, "
          + "and Athena charges for the data it scans: GateRook doesn't run it. Use EXPLAIN without ANALYZE.");
    }
    if (j < t.size() && t.get(j).punct('(')) {
      int close = closing(t, j);
      if (close < 0) {
        return Statement.refused(Kind.UNREADABLE, "EXPLAIN", "The options of EXPLAIN aren't closed.");
      }
      j = close + 1;
    }
    int inner = j;
    while (inner < t.size() && t.get(inner).punct('(')) {
      inner++;
    }
    if (inner >= t.size() || !QUERIES.contains(t.get(inner).text())
        || (t.get(inner).is("WITH") && word(t, inner + 1, "SESSION"))) {
      return Statement.refused(Kind.FORBIDDEN, "EXPLAIN", "athena_query explains a query (SELECT, WITH or VALUES) "
          + "only.");
    }
    return query("EXPLAIN", text, text.substring(t.get(j).start()));
  }

  /** {@code DESCRIBE [EXTENDED | FORMATTED] [db.]table [PARTITION (…)] [column]}, {@code DESCRIBE VIEW v}. */
  private static Statement describe(List<Token> t, int i, String text) {
    int j = i + 1;
    if (word(t, j, "INPUT") || word(t, j, "OUTPUT")) {
      return Statement.refused(Kind.FORBIDDEN, "DESCRIBE " + wordAt(t, j), "DESCRIBE INPUT and OUTPUT describe "
          + "prepared statements, which GateRook doesn't keep.");
    }
    while (word(t, j, "EXTENDED") || word(t, j, "FORMATTED") || word(t, j, "VIEW")) {
      j++;
    }
    Name name = name(t, j);
    if (name == null) {
      return Statement.refused(Kind.UNREADABLE, "DESCRIBE", "Name the table to describe.");
    }
    return hiveRead("DESCRIBE", text, List.of(new Ref(name, Role.TABLE)));
  }

  /** The {@code SHOW} forms of Athena, each with the database or table it shows. */
  private static Statement show(List<Token> t, int i, String text) {
    String what = wordAt(t, i + 1);
    List<Ref> refs = new ArrayList<>();
    switch (what) {
      case "DATABASES", "SCHEMAS" -> {
        // The databases of the session's catalog: names only, filtered by Athena's and Glue's permissions.
        return hiveRead("SHOW " + what, text, refs);
      }
      case "TABLES", "VIEWS" -> {
        int j = i + 2;
        if (word(t, j, "IN") || word(t, j, "FROM")) {
          Name db = name(t, j + 1);
          if (db == null) {
            return Statement.refused(Kind.UNREADABLE, "SHOW " + what, "Name the database: SHOW " + what + " IN db.");
          }
          refs.add(new Ref(db, Role.DATABASE));
        }
        return hiveRead("SHOW " + what, text, refs);
      }
      case "COLUMNS" -> {
        int j = i + 2;
        if (!(word(t, j, "IN") || word(t, j, "FROM"))) {
          return Statement.refused(Kind.UNREADABLE, "SHOW COLUMNS", "Write SHOW COLUMNS IN table.");
        }
        Name table = name(t, j + 1);
        if (table == null) {
          return Statement.refused(Kind.UNREADABLE, "SHOW COLUMNS", "Write SHOW COLUMNS IN table.");
        }
        int k = j + 1 + tokens(table);
        if (word(t, k, "IN") || word(t, k, "FROM")) {
          // SHOW COLUMNS IN t IN db: the table of that database.
          Name db = name(t, k + 1);
          if (db == null || table.parts().size() != 1) {
            return Statement.refused(Kind.UNREADABLE, "SHOW COLUMNS", "Write SHOW COLUMNS IN db.table.");
          }
          List<String> parts = new ArrayList<>(db.parts());
          parts.add(table.parts().getFirst());
          table = new Name(parts);
        }
        refs.add(new Ref(table, Role.TABLE));
        return hiveRead("SHOW COLUMNS", text, refs);
      }
      case "PARTITIONS", "TBLPROPERTIES" -> {
        Name table = name(t, i + 2);
        if (table == null) {
          return Statement.refused(Kind.UNREADABLE, "SHOW " + what, "Name the table.");
        }
        refs.add(new Ref(table, Role.TABLE));
        return hiveRead("SHOW " + what, text, refs);
      }
      case "CREATE" -> {
        String object = wordAt(t, i + 2);
        if (!object.equals("TABLE") && !object.equals("VIEW")) {
          return Statement.refused(Kind.UNREADABLE, "SHOW CREATE", "SHOW CREATE " + object + " isn't a form GateRook "
              + "knows.");
        }
        Name name = name(t, i + 3);
        if (name == null) {
          return Statement.refused(Kind.UNREADABLE, "SHOW CREATE", "Name what to show.");
        }
        refs.add(new Ref(name, Role.TABLE));
        return hiveRead("SHOW CREATE " + object, text, refs);
      }
      default -> {
        return Statement.refused(Kind.UNREADABLE, "SHOW", "SHOW " + what + " isn't a form GateRook knows.");
      }
    }
  }

  private static Statement create(List<Token> t, int i, String text) {
    int j = i + 1;
    boolean replace = false;
    if (word(t, j, "OR") && word(t, j + 1, "REPLACE")) {
      j += 2;
      replace = true;
    }
    if (word(t, j, "EXTERNAL")) {
      j++;
    }
    String object = wordAt(t, j);
    switch (object) {
      case "ROLE" -> {
        return Statement.refused(Kind.FORBIDDEN, "CREATE ROLE", "Roles stay with people.");
      }
      case "TABLE" -> {
        int at = skipIf(t, j + 1);
        Name name = name(t, at);
        if (name == null) {
          return Statement.refused(Kind.UNREADABLE, "CREATE TABLE", "Name the table to create.");
        }
        boolean as = false;
        int depth = 0;
        for (int k = at + tokens(name); k < t.size(); k++) {
          Token tok = t.get(k);
          if (tok.punct('(')) {
            depth++;
          } else if (tok.punct(')')) {
            depth--;
          } else if (depth == 0 && tok.is("AS")) {
            as = true;
            break;
          }
        }
        return changed(as ? "CREATE TABLE AS" : "CREATE TABLE", text, new Ref(name, Role.TABLE), at, List.of());
      }
      case "VIEW" -> {
        return changeAt(t, j + 1, text, replace ? "CREATE OR REPLACE VIEW" : "CREATE VIEW", Role.TABLE);
      }
      case "DATABASE", "SCHEMA" -> {
        return changeAt(t, skipIf(t, j + 1), text, "CREATE " + object, Role.DATABASE);
      }
      default -> {
        return Statement.refused(Kind.UNREADABLE, "CREATE " + object, "CREATE " + object + " isn't a statement "
            + "GateRook runs on Athena.");
      }
    }
  }

  private static Statement alter(List<Token> t, int i, String text) {
    int j = i + 1;
    String object = wordAt(t, j);
    Role role = switch (object) {
      case "TABLE", "VIEW" -> Role.TABLE;
      case "DATABASE", "SCHEMA" -> Role.DATABASE;
      default -> null;
    };
    if (role == null) {
      return Statement.refused(Kind.UNREADABLE, "ALTER " + object, "ALTER " + object + " isn't a statement GateRook "
          + "runs on Athena.");
    }
    int at = skipIf(t, j + 1);
    Name name = name(t, at);
    if (name == null) {
      return Statement.refused(Kind.UNREADABLE, "ALTER " + object, "Name what to alter.");
    }
    List<Ref> refs = new ArrayList<>();
    for (int k = at + tokens(name); k + 1 < t.size(); k++) {
      if (t.get(k).is("RENAME") && word(t, k + 1, "TO")) {
        Name to = name(t, k + 2);
        if (to != null) {
          refs.add(new Ref(to, role));
        }
      }
    }
    return changed("ALTER " + object, text, new Ref(name, role), at, refs);
  }

  private static Statement drop(List<Token> t, int i, String text) {
    String object = wordAt(t, i + 1);
    return switch (object) {
      case "ROLE" -> Statement.refused(Kind.FORBIDDEN, "DROP ROLE", "Roles stay with people.");
      case "TABLE" -> changeAt(t, skipIf(t, i + 2), text, "DROP TABLE", Role.TABLE);
      case "VIEW" -> changeAt(t, skipIf(t, i + 2), text, "DROP VIEW", Role.TABLE);
      case "DATABASE", "SCHEMA" -> changeAt(t, skipIf(t, i + 2), text, "DROP " + object, Role.DATABASE);
      default -> Statement.refused(Kind.UNREADABLE, "DROP " + object, "DROP " + object + " isn't a statement GateRook "
          + "runs on Athena.");
    };
  }

  private static Statement change(List<Token> t, int i, String text, String type, String expect, Role role) {
    int j = i + 1;
    if (expect != null) {
      if (!word(t, j, expect)) {
        return Statement.refused(Kind.UNREADABLE, type, "Write " + type + " " + expect + " table.");
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
    return new Statement(Kind.CHANGE, type, text, null, target, at, List.copyOf(refs), !TRINO.contains(type), null);
  }

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

  // ── Functions ───────────────────────────────────────────────────────────────────────────────────────────────

  private record Functions(String problem, List<Ref> refs) {}

  /**
   * Table functions and qualified functions of the statement: a table function other than Trino's built-in ones and
   * an AI function ({@code ai_…}) are refused; a function of a catalog becomes a ref the connection checks. The
   * statement's own target, with a column list or partition spec right after it, isn't a call.
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
            return new Functions("The table function " + fn.text() + " reaches past what Athena's plan shows (a "
                + "federated catalog's pass-through query runs any SQL in its source): GateRook runs no table "
                + "function but sequence and exclude_columns.", refs);
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
        return new Functions("AI functions (" + fn.text() + ") send data to a model provider: GateRook doesn't run "
            + "them.", refs);
      }
      if (fn.parts().size() > 3) {
        return new Functions(fn.text() + " has more parts than a function name.", refs);
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

  /** A name an agent gave as a tool argument: one dotted name of at most three parts and nothing else, or null. */
  static Name qualified(String written) {
    if (written == null || written.isBlank() || written.length() > 1_000) {
      return null;
    }
    char quote = 0;
    for (int i = 0; i + 1 < written.length(); i++) {
      char c = written.charAt(i);
      if (quote != 0) {
        if (c == quote) {
          quote = 0;
        }
      } else if (c == '"' || c == '`') {
        quote = c;
      } else if ((c == '-' && written.charAt(i + 1) == '-') || (c == '/' && written.charAt(i + 1) == '*')) {
        return null;
      }
    }
    Scan scan = scan(written);
    if (scan.problem() != null || scan.end() != written.length()
        || scan.tokens().stream().anyMatch(Token::backslash)) {
      return null;
    }
    Name name = name(scan.tokens(), 0);
    if (name == null || tokens(name) != scan.tokens().size() || name.parts().size() > 3) {
      return null;
    }
    return name;
  }

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

  static int tokens(Name name) {
    return name.parts().size() * 2 - 1;
  }

  private static String part(Token tok) {
    return tok.type() == T.WORD ? tok.text().toLowerCase(Locale.ROOT) : tok.text();
  }

  private static boolean word(List<Token> t, int j, String word) {
    return j < t.size() && t.get(j).is(word);
  }

  private static String wordAt(List<Token> t, int j) {
    return j < t.size() && t.get(j).type() == T.WORD ? t.get(j).text() : "";
  }
}
