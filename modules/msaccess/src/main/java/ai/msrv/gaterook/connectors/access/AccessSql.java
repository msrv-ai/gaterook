package ai.msrv.gaterook.connectors.access;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Access SQL as GateRook reads it before UCanAccess translates it for HSQLDB (docs/access.md): Access's tokens, then
 * what one statement is. It fails closed: what it can't read with certainty is refused.
 *
 * <p>Access writes strings in {@code '…'} or {@code "…"} (a doubled quote inside), names in {@code […]}, dates as
 * {@code #…#}; HSQLDB's {@code --} and block comments are read too. A nested comment, a backtick, a brace (a JDBC
 * escape) or a character outside the language is refused.
 *
 * <p>{@link #read} admits one SELECT or WITH … SELECT. It refuses {@code SELECT … INTO} (Access's make-table query),
 * {@code LOAD_FILE} (HSQLDB reads a file with it), the domain functions ({@code DLookup}, {@code DSum} … run SQL
 * built from their string arguments), {@code NEXT VALUE FOR} and {@code FOR UPDATE}. {@link #change} admits one
 * INSERT, UPDATE or DELETE, or CREATE TABLE, ALTER TABLE, DROP TABLE or CREATE INDEX, without LOAD_FILE or a domain
 * function. Both refuse SHUTDOWN, SET, SCRIPT, CHECKPOINT, CALL, rights, routines, triggers, views and transaction
 * control, which act on UCanAccess's copy of the database rather than on the file.
 */
final class AccessSql {

  enum T { WORD, NAME, STRING, DATE, NUMBER, PARAM, OP, LPAREN, RPAREN, COMMA, SEMI, DOT }

  /** One token: a word in upper case, a bracketed name or a string as written inside its delimiters, else the text. */
  record Token(T type, String value, int start, int end) {

    boolean word(String w) {
      return type == T.WORD && value.equals(w);
    }
  }

  enum Kind { READ, CHANGE, REFUSED }

  /**
   * What GateRook makes of one statement.
   *
   * @param problem why it is refused, or {@code null}
   * @param verb    its statement words in upper case ({@code SELECT}, {@code INSERT}, {@code CREATE TABLE})
   * @param text    the statement as written, without a trailing {@code ;}: what runs
   * @param target  the table a change names, or {@code null}
   */
  record Verdict(Kind kind, String problem, String verb, String text, String target) {

    boolean ok() {
      return problem == null;
    }

    static Verdict refused(String problem) {
      return new Verdict(Kind.REFUSED, problem, null, null, null);
    }
  }

  static final int MAX_LENGTH = 100_000;

  /** Access's domain aggregate functions: UCanAccess runs the SQL their string arguments spell. */
  static final Set<String> DOMAIN_FUNCTIONS = Set.of("DLOOKUP", "DCOUNT", "DSUM", "DAVG", "DMIN", "DMAX", "DFIRST",
      "DLAST", "DSTDEV", "DSTDEVP", "DVAR", "DVARP");

  /** HSQLDB functions that reach outside the database copy. */
  static final Set<String> OUTSIDE = Set.of("LOAD_FILE");

  private AccessSql() {}

  // ── Reads ───────────────────────────────────────────────────────────────────────────────────────────────────

  static Verdict read(String sql) {
    Statement s = statement(sql);
    if (s.problem() != null) {
      return Verdict.refused(s.problem());
    }
    List<Token> t = s.tokens();
    int i = 0;
    while (i < t.size() && t.get(i).type() == T.LPAREN) {
      i++;
    }
    if (i >= t.size() || t.get(i).type() != T.WORD) {
      return Verdict.refused("GateRook can't read this statement: access_query runs SELECT and WITH … SELECT.");
    }
    String first = t.get(i).value();
    if (!first.equals("SELECT") && !first.equals("WITH")) {
      String why = refusedEverywhere(t, i);
      return Verdict.refused(why != null ? why : first + " isn't a query: access_query only reads. A change goes "
          + "through access_execute, which waits for a person's approval.");
    }
    if (first.equals("WITH")) {
      String main = afterWith(t, i);
      if (!"SELECT".equals(main) && !"(".equals(main)) {
        return Verdict.refused("A WITH here must end in a SELECT: a change goes through access_execute.");
      }
    }
    String problem = functions(t, true);
    if (problem != null) {
      return Verdict.refused(problem);
    }
    for (int k = 0; k < t.size(); k++) {
      Token tok = t.get(k);
      if (tok.word("INTO")) {
        return Verdict.refused("SELECT … INTO creates a table: access_query only reads.");
      }
      if (tok.word("NEXT") && word(t, k + 1, "VALUE") && word(t, k + 2, "FOR")) {
        return Verdict.refused("NEXT VALUE FOR advances a counter: access_query only reads.");
      }
      if (tok.word("FOR") && word(t, k + 1, "UPDATE")) {
        return Verdict.refused("FOR UPDATE locks rows: a read here doesn't lock.");
      }
    }
    return new Verdict(Kind.READ, null, first, s.text(), null);
  }

  /** LOAD_FILE anywhere; the domain functions too. */
  private static String functions(List<Token> t, boolean read) {
    for (int k = 0; k + 1 < t.size(); k++) {
      Token tok = t.get(k);
      if ((tok.type() != T.WORD && tok.type() != T.NAME) || t.get(k + 1).type() != T.LPAREN) {
        continue;
      }
      String name = tok.value().toUpperCase(Locale.ROOT);
      if (OUTSIDE.contains(name)) {
        return name + " reads files outside the database: GateRook never runs it.";
      }
      if (DOMAIN_FUNCTIONS.contains(name)) {
        return name + " runs SQL built from its text arguments, which GateRook can't check: "
            + (read ? "write the subquery itself." : "write the statement without it.");
      }
    }
    return null;
  }

  /** The first word after a WITH list (or {@code "("}), or {@code null} when the list doesn't end well. */
  static String afterWith(List<Token> t, int i) {
    int j = i + 1;
    if (word(t, j, "RECURSIVE")) {
      j++;
    }
    while (true) {
      if (j >= t.size() || !(t.get(j).type() == T.WORD || t.get(j).type() == T.NAME)) {
        return null;
      }
      j++;
      if (j < t.size() && t.get(j).type() == T.LPAREN) {
        j = skip(t, j);
        if (j < 0) {
          return null;
        }
      }
      if (!word(t, j, "AS")) {
        return null;
      }
      j++;
      if (j >= t.size() || t.get(j).type() != T.LPAREN) {
        return null;
      }
      j = skip(t, j);
      if (j < 0 || j >= t.size()) {
        return null;
      }
      if (t.get(j).type() == T.COMMA) {
        j++;
        continue;
      }
      return t.get(j).type() == T.LPAREN ? "(" : t.get(j).type() == T.WORD ? t.get(j).value() : null;
    }
  }

  // ── Changes ─────────────────────────────────────────────────────────────────────────────────────────────────

  static Verdict change(String sql) {
    Statement s = statement(sql);
    if (s.problem() != null) {
      return Verdict.refused(s.problem());
    }
    List<Token> t = s.tokens();
    if (t.getFirst().type() != T.WORD) {
      return Verdict.refused("GateRook can't read this statement: a change starts with INSERT, UPDATE, DELETE, "
          + "CREATE, ALTER or DROP.");
    }
    String why = refusedEverywhere(t, 0);
    if (why != null) {
      return Verdict.refused(why);
    }
    String problem = functions(t, false);
    if (problem != null) {
      return Verdict.refused(problem);
    }
    String first = t.getFirst().value();
    return switch (first) {
      case "INSERT" -> word(t, 1, "INTO") ? target(s, "INSERT", t, 2)
          : Verdict.refused("INSERT here is INSERT INTO <table> ….");
      case "UPDATE" -> target(s, "UPDATE", t, 1);
      case "DELETE" -> {
        // Access also writes DELETE * FROM t and DELETE t.* FROM t.
        int from = 1;
        while (from < t.size() && !t.get(from).word("FROM")) {
          from++;
        }
        yield from < t.size() ? target(s, "DELETE", t, from + 1)
            : Verdict.refused("DELETE here is DELETE FROM <table> ….");
      }
      case "CREATE" -> {
        if (word(t, 1, "TABLE")) {
          yield target(s, "CREATE TABLE", t, 2);
        }
        int index = word(t, 1, "UNIQUE") ? 2 : 1;
        if (word(t, index, "INDEX")) {
          int on = index + 1;
          while (on < t.size() && !t.get(on).word("ON")) {
            on++;
          }
          yield on < t.size() ? target(s, "CREATE INDEX", t, on + 1)
              : Verdict.refused("CREATE INDEX here is CREATE INDEX <name> ON <table> (…).");
        }
        yield Verdict.refused(notAChange(t));
      }
      case "ALTER" -> word(t, 1, "TABLE") ? target(s, "ALTER TABLE", t, 2) : Verdict.refused(notAChange(t));
      case "DROP" -> word(t, 1, "TABLE") ? target(s, "DROP TABLE", t, 2) : Verdict.refused(notAChange(t));
      case "SELECT", "WITH" -> {
        for (Token tok : t) {
          if (tok.word("INTO")) {
            yield Verdict.refused("SELECT … INTO (a make-table query) isn't something UCanAccess runs: create the "
                + "table, then INSERT INTO it … SELECT.");
          }
        }
        yield Verdict.refused(first + " only reads: run it with access_query.");
      }
      default -> Verdict.refused(notAChange(t));
    };
  }

  private static String notAChange(List<Token> t) {
    String what = t.getFirst().value() + (t.size() > 1 && t.get(1).type() == T.WORD ? " " + t.get(1).value() : "");
    return what + " isn't a change GateRook runs: access_execute runs INSERT, UPDATE, DELETE, CREATE TABLE, ALTER "
        + "TABLE, DROP TABLE and CREATE INDEX.";
  }

  private static Verdict target(Statement s, String verb, List<Token> t, int j) {
    return new Verdict(Kind.CHANGE, null, verb, s.text(), name(s.source(), t, j));
  }

  /** What acts on UCanAccess's copy of the database rather than on the file, for reads and changes alike. */
  private static String refusedEverywhere(List<Token> t, int i) {
    String first = t.get(i).value();
    String second = i + 1 < t.size() && t.get(i + 1).type() == T.WORD ? t.get(i + 1).value() : "";
    return switch (first) {
      case "SHUTDOWN" -> "SHUTDOWN closes GateRook's copy of the database: it never runs.";
      case "SET", "CHECKPOINT", "SCRIPT", "BACKUP", "PERFORM", "DECLARE" -> first + " acts on GateRook's copy of the "
          + "database, not on the file: it never runs.";
      case "CALL" -> "CALL runs a routine GateRook can't see into: select the expression instead.";
      case "COMMIT", "ROLLBACK", "BEGIN", "START", "SAVEPOINT", "RELEASE" -> "Transactions are GateRook's: each call "
          + "runs in its own.";
      case "GRANT", "REVOKE" -> "Rights aren't something GateRook changes: " + first + " never runs.";
      case "TRANSFORM", "PARAMETERS", "PROCEDURE" -> first + " isn't something UCanAccess runs.";
      case "CREATE", "ALTER", "DROP" -> switch (second) {
        case "FUNCTION", "PROCEDURE", "TRIGGER", "ROUTINE", "AGGREGATE", "SPECIFIC" -> second + " is code the copy "
            + "of the database would run: GateRook never changes one.";
        case "VIEW", "QUERY" -> "Queries of an Access file are the person's to change in Access.";
        case "USER", "ROLE", "SCHEMA", "SEQUENCE", "DOMAIN", "TYPE", "CHARACTER", "COLLATION", "SYNONYM" -> first
            + " " + second + " acts on GateRook's copy of the database, not on the file: it never runs.";
        default -> null;
      };
      default -> null;
    };
  }

  // ── Statements and tokens ───────────────────────────────────────────────────────────────────────────────────

  /** One statement: its tokens, its text without a trailing {@code ;}, the text it was read from, or a problem. */
  record Statement(List<Token> tokens, String text, String source, String problem) {}

  static Statement statement(String sql) {
    if (sql == null || sql.isBlank()) {
      return new Statement(List.of(), "", "", "The statement is empty.");
    }
    if (sql.length() > MAX_LENGTH) {
      return new Statement(List.of(), "", "", "The statement is longer than " + MAX_LENGTH + " characters.");
    }
    Lexed lexed = lex(sql);
    if (lexed.problem() != null) {
      return new Statement(List.of(), "", "", lexed.problem());
    }
    List<Token> all = lexed.tokens();
    int last = all.size();
    for (int k = 0; k < all.size(); k++) {
      if (all.get(k).type() == T.SEMI) {
        if (k != all.size() - 1) {
          return new Statement(List.of(), "", "", "One statement per call: this text holds more than one.");
        }
        last = k;
      }
    }
    List<Token> tokens = List.copyOf(all.subList(0, last));
    if (tokens.isEmpty()) {
      return new Statement(List.of(), "", "", "The statement is empty.");
    }
    int depth = 0;
    for (Token tok : tokens) {
      if (tok.type() == T.LPAREN) {
        depth++;
      } else if (tok.type() == T.RPAREN && --depth < 0) {
        return new Statement(List.of(), "", "", "The parentheses of this statement don't balance.");
      }
    }
    if (depth != 0) {
      return new Statement(List.of(), "", "", "The parentheses of this statement don't balance.");
    }
    String text = sql.substring(tokens.getFirst().start(), tokens.getLast().end());
    return new Statement(tokens, text, sql, null);
  }

  record Lexed(List<Token> tokens, String problem) {}

  static Lexed lex(String sql) {
    List<Token> out = new ArrayList<>();
    int i = 0;
    int n = sql.length();
    while (i < n) {
      char c = sql.charAt(i);
      if (Character.isWhitespace(c)) {
        i++;
        continue;
      }
      if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
        int end = sql.indexOf('\n', i);
        i = end < 0 ? n : end + 1;
        continue;
      }
      if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
        int end = sql.indexOf("*/", i + 2);
        if (end < 0) {
          return new Lexed(List.of(), "A comment of this statement isn't closed.");
        }
        if (sql.substring(i + 2, end).contains("/*")) {
          return new Lexed(List.of(), "GateRook can't read a comment inside a comment.");
        }
        i = end + 2;
        continue;
      }
      if (c == '\'' || c == '"') {
        int end = quoted(sql, i, c);
        if (end < 0) {
          return new Lexed(List.of(), "A string of this statement isn't closed.");
        }
        String q = String.valueOf(c);
        out.add(new Token(T.STRING, sql.substring(i + 1, end - 1).replace(q + q, q), i, end));
        i = end;
        continue;
      }
      if (c == '[') {
        int end = sql.indexOf(']', i + 1);
        if (end < 0) {
          return new Lexed(List.of(), "A name in brackets isn't closed.");
        }
        String inside = sql.substring(i + 1, end);
        if (inside.indexOf('[') >= 0 || inside.chars().anyMatch(Character::isISOControl)) {
          return new Lexed(List.of(), "GateRook can't read the name [" + inside + "].");
        }
        out.add(new Token(T.NAME, inside, i, end + 1));
        i = end + 1;
        continue;
      }
      if (c == '#') {
        int end = sql.indexOf('#', i + 1);
        String inside = end < 0 ? "" : sql.substring(i + 1, end);
        if (end < 0 || inside.isEmpty() || !inside.matches("[0-9A-Za-z/:.,\\- ]{1,40}")) {
          return new Lexed(List.of(), "GateRook reads # only around a date, such as #2026-11-12#.");
        }
        out.add(new Token(T.DATE, inside, i, end + 1));
        i = end + 1;
        continue;
      }
      if (Character.isLetter(c) || c == '_') {
        int j = i + 1;
        while (j < n && (Character.isLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_' || sql.charAt(j) == '$')) {
          j++;
        }
        out.add(new Token(T.WORD, sql.substring(i, j).toUpperCase(Locale.ROOT), i, j));
        i = j;
        continue;
      }
      if (Character.isDigit(c) || c == '.' && i + 1 < n && Character.isDigit(sql.charAt(i + 1))) {
        int j = number(sql, i);
        if (j < n && (Character.isLetter(sql.charAt(j)) || sql.charAt(j) == '_')) {
          return new Lexed(List.of(), "GateRook can't read a number with letters stuck to it.");
        }
        out.add(new Token(T.NUMBER, sql.substring(i, j), i, j));
        i = j;
        continue;
      }
      switch (c) {
        case '(' -> out.add(new Token(T.LPAREN, "(", i, i + 1));
        case ')' -> out.add(new Token(T.RPAREN, ")", i, i + 1));
        case ',' -> out.add(new Token(T.COMMA, ",", i, i + 1));
        case ';' -> out.add(new Token(T.SEMI, ";", i, i + 1));
        case '.' -> out.add(new Token(T.DOT, ".", i, i + 1));
        case '?' -> out.add(new Token(T.PARAM, "?", i, i + 1));
        default -> {
          String op = operator(sql, i);
          if (op == null) {
            return new Lexed(List.of(), (c == '{' || c == '}' ? "GateRook doesn't run JDBC escapes ({…}): write "
                : "GateRook can't read the character " + printable(c) + " here: write ") + "the statement in Access "
                + "SQL.");
          }
          out.add(new Token(T.OP, op, i, i + op.length()));
          i += op.length();
          continue;
        }
      }
      i++;
    }
    return new Lexed(List.copyOf(out), null);
  }

  private static final List<String> OPERATORS = List.of("<=", ">=", "<>", "!=", "||", "=", "<", ">", "+", "-", "*",
      "/", "\\", "^", "&", "%");

  private static String operator(String sql, int i) {
    for (String op : OPERATORS) {
      if (sql.startsWith(op, i)) {
        return op;
      }
    }
    return null;
  }

  private static String printable(char c) {
    return Character.isISOControl(c) ? String.format(Locale.ROOT, "U+%04X", (int) c) : "'" + c + "'";
  }

  private static int quoted(String sql, int i, char quote) {
    int j = i + 1;
    while (j < sql.length()) {
      if (sql.charAt(j) == quote) {
        if (j + 1 < sql.length() && sql.charAt(j + 1) == quote) {
          j += 2;
          continue;
        }
        return j + 1;
      }
      j++;
    }
    return -1;
  }

  private static int number(String sql, int i) {
    int j = i;
    int n = sql.length();
    while (j < n && Character.isDigit(sql.charAt(j))) {
      j++;
    }
    if (j < n && sql.charAt(j) == '.') {
      j++;
      while (j < n && Character.isDigit(sql.charAt(j))) {
        j++;
      }
    }
    if (j < n && (sql.charAt(j) == 'e' || sql.charAt(j) == 'E')) {
      int k = j + 1;
      if (k < n && (sql.charAt(k) == '+' || sql.charAt(k) == '-')) {
        k++;
      }
      if (k < n && Character.isDigit(sql.charAt(k))) {
        j = k;
        while (j < n && Character.isDigit(sql.charAt(j))) {
          j++;
        }
      }
    }
    return j;
  }

  static int skip(List<Token> t, int open) {
    int depth = 0;
    for (int k = open; k < t.size(); k++) {
      if (t.get(k).type() == T.LPAREN) {
        depth++;
      } else if (t.get(k).type() == T.RPAREN && --depth == 0) {
        return k + 1;
      }
    }
    return -1;
  }

  /** A table name ({@code Orders}, {@code [Order Details]}) starting at {@code j}, as written, or null. */
  static String name(String source, List<Token> t, int j) {
    if (j >= t.size()) {
      return null;
    }
    Token tok = t.get(j);
    if (tok.type() == T.NAME) {
      return tok.value();
    }
    if (tok.type() == T.WORD && !tok.value().equals("TABLE")) {
      return source.substring(tok.start(), tok.end());
    }
    return null;
  }

  private static boolean word(List<Token> t, int j, String w) {
    return j >= 0 && j < t.size() && t.get(j).word(w);
  }
}
