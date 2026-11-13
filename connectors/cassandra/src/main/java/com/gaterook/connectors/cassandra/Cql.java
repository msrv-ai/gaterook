package com.gaterook.connectors.cassandra;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What one CQL statement is, without I/O: the gate before the driver sees it. The text is read by the tokens of
 * Cassandra's own lexer — {@code '…'} strings with {@code ''}, {@code $$…$$} strings, {@code "…"} names with
 * {@code ""}, {@code --} and {@code //} comments up to a line break, block comments — so a semicolon, a keyword or a
 * quote inside a string, a name or a comment is none of them. Whatever this can't read the way Cassandra reads it is
 * {@link Kind#UNREADABLE}, and every tool refuses it: an open quote or comment, a comment inside a comment, a line
 * comment at the very end (Cassandra's lexer needs its line break, and reads {@code 1 --2} without one as
 * {@code 1 - -2}), a {@code $} outside a string, a number that runs into letters ({@code 1from} is {@code 1 FROM} to
 * Cassandra), unbalanced brackets, control characters, invisible characters in comments, and characters outside ASCII
 * outside strings, quoted names and comments.
 *
 * <p>A statement names what it reads or changes in fixed places ({@code FROM}, {@code INTO}, {@code UPDATE},
 * {@code ON}, after the object keywords of DDL); those names, and the keyspaces of functions called as
 * {@code keyspace.function(…)}, are what the keyspace allowlist checks. CQL has no joins or subqueries, so a
 * {@code SELECT} reads exactly one table.
 */
final class Cql {

  /** What a statement does. */
  enum Kind {
    SELECT, INSERT, UPDATE, DELETE, TRUNCATE,
    CREATE_TABLE, ALTER_TABLE, DROP_TABLE, CREATE_INDEX, DROP_INDEX, CREATE_TYPE, ALTER_TYPE, DROP_TYPE,
    CREATE_VIEW, ALTER_VIEW, DROP_VIEW,
    /** Refused everywhere: {@code BEGIN … BATCH} and {@code APPLY BATCH}. */
    BATCH,
    /** Refused everywhere: {@code GRANT}, {@code REVOKE}, {@code LIST}, roles, users and identities. */
    ACCESS,
    /** Refused everywhere: {@code CREATE}, {@code ALTER} and {@code DROP KEYSPACE}. */
    KEYSPACE,
    /** Refused everywhere: functions, aggregates and triggers, which are code on the server. */
    CODE,
    /** Refused everywhere: {@code USE}; the session's keyspace belongs to the gateway. */
    USE,
    /** Refused everywhere: anything else ({@code DESCRIBE}, vendor statements). */
    OTHER,
    /** Refused everywhere: text this reader can't read the way Cassandra reads it. */
    UNREADABLE;

    /** An INSERT, UPDATE, DELETE, TRUNCATE or DDL statement. */
    boolean change() {
      return ordinal() >= INSERT.ordinal() && ordinal() <= DROP_VIEW.ordinal();
    }

    boolean ddl() {
      return ordinal() >= CREATE_TABLE.ordinal() && ordinal() <= DROP_VIEW.ordinal();
    }

    boolean dml() {
      return this == INSERT || this == UPDATE || this == DELETE;
    }

    boolean refused() {
      return ordinal() >= BATCH.ordinal();
    }
  }

  /**
   * A table, view, type or index as a statement names it.
   *
   * @param keyspace the keyspace as Cassandra stores it, or {@code null} when the statement doesn't qualify the name
   * @param name     the object's name as Cassandra stores it: unquoted names in lower case, quoted ones as written
   */
  record Name(String keyspace, String name) {

    boolean qualified() {
      return keyspace != null;
    }

    /** {@code ks.table}, quoted where CQL needs quotes. */
    String text() {
      return (keyspace == null ? "" : quote(keyspace) + ".") + quote(name);
    }
  }

  /**
   * A bind marker: {@code ?}, or {@code :name}.
   *
   * @param name the marker's name as Cassandra stores it, or {@code null} for {@code ?}
   */
  record Marker(String name) {}

  /**
   * One call's text.
   *
   * @param verb              the statement's leading keywords, upper case ({@code SELECT}, {@code CREATE TABLE})
   * @param target            what a statement reads or changes; {@code null} for a refused one
   * @param base              the base table of {@code CREATE MATERIALIZED VIEW}, else {@code null}
   * @param functionKeyspaces keyspaces of the functions it calls as {@code keyspace.function(…)}
   * @param allowFiltering    whether it holds {@code ALLOW FILTERING}
   * @param conditional       a lightweight transaction: {@code IF …} in an INSERT, UPDATE or DELETE
   * @param usingTimestamp    {@code USING TIMESTAMP}: the write carries a timestamp of its own
   * @param markers           its bind markers in order, as this lexer sees them
   * @param problem           why it is refused, in words for the agent; {@code null} for a read or a change
   */
  record Statement(Kind kind, String verb, Name target, Name base, List<String> functionKeyspaces,
      boolean allowFiltering, boolean conditional, boolean usingTimestamp, List<Marker> markers, String problem) {

    boolean analyzed() {
      return problem == null;
    }
  }

  static final int MAX_LENGTH = 20_000;

  /** Words a CQL name can't be unquoted ({@code ks.from} isn't a name): Cassandra's reserved keywords. */
  private static final Set<String> RESERVED = Set.of("ADD", "ALLOW", "ALTER", "AND", "APPLY", "ASC", "AUTHORIZE",
      "BATCH", "BEGIN", "BY", "COLUMNFAMILY", "CREATE", "DELETE", "DESC", "DESCRIBE", "DROP", "ENTRIES", "EXECUTE",
      "FROM", "FULL", "GRANT", "IF", "IN", "INDEX", "INFINITY", "INSERT", "INTO", "KEYSPACE", "LIMIT", "MATERIALIZED",
      "MODIFY", "NAN", "NORECURSIVE", "NOT", "NULL", "OF", "ON", "OR", "ORDER", "PRIMARY", "RENAME", "REPLACE",
      "REVOKE", "SCHEMA", "SELECT", "SET", "TABLE", "TO", "TOKEN", "TRUNCATE", "UNLOGGED", "UPDATE", "USE", "USING",
      "VIEW", "WHERE", "WITH");

  private Cql() {}

  // ── Tokens ──────────────────────────────────────────────────────────────────────────────────────────────────

  enum T { WORD, QUOTED, STRING, NUMBER, PUNCT, MARKER }

  /**
   * One token outside comments.
   *
   * @param text  a word as written, a quoted name unescaped, a punctuation character, a marker's name ({@code null}
   *              for {@code ?}); a string's content is not kept
   * @param depth how many brackets enclose it
   */
  record Token(T type, String text, int depth) {

    boolean word(String upper) {
      return type == T.WORD && text.equalsIgnoreCase(upper);
    }

    boolean punct(char c) {
      return type == T.PUNCT && text.charAt(0) == c;
    }

    /** A name part: an unquoted word in lower case, a quoted name as written; {@code null} if not one. */
    String namePart() {
      if (type == T.QUOTED) {
        return text;
      }
      if (type == T.WORD && !RESERVED.contains(text.toUpperCase(Locale.ROOT))) {
        return text.toLowerCase(Locale.ROOT);
      }
      return null;
    }
  }

  /** The tokens of a text, or why it can't be read. */
  record Lexed(List<Token> tokens, String problem) {}

  static Lexed lex(String text) {
    List<Token> out = new ArrayList<>();
    if (text == null || text.isBlank()) {
      return new Lexed(out, "The statement is empty.");
    }
    if (text.length() > MAX_LENGTH) {
      return new Lexed(out, "The statement is longer than " + MAX_LENGTH + " characters.");
    }
    int n = text.length();
    int depth = 0;
    boolean ended = false;
    int i = 0;
    while (i < n) {
      char c = text.charAt(i);
      if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
        i++;
        continue;
      }
      if ((c == '-' || c == '/') && i + 1 < n && text.charAt(i + 1) == c) {
        int j = i + 2;
        while (j < n && text.charAt(j) != '\n' && text.charAt(j) != '\r') {
          if (!commentChar(text.charAt(j))) {
            return new Lexed(out, "A comment holds a control or invisible character.");
          }
          j++;
        }
        if (j >= n) {
          return new Lexed(out, "A " + c + c + " comment must end with a line break: at the very end of a "
              + "statement Cassandra doesn't read it as a comment. Leave it out.");
        }
        i = j + 1;
        continue;
      }
      if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
        int close = text.indexOf("*/", i + 2);
        if (close < 0) {
          return new Lexed(out, "A /* comment isn't closed.");
        }
        int inner = text.indexOf("/*", i + 2);
        if (inner >= 0 && inner < close) {
          return new Lexed(out, "A comment inside a comment isn't read the same way by every CQL server.");
        }
        for (int j = i + 2; j < close; j++) {
          char d = text.charAt(j);
          if (d != '\n' && d != '\r' && !commentChar(d)) {
            return new Lexed(out, "A comment holds a control or invisible character.");
          }
        }
        i = close + 2;
        continue;
      }
      if (ended) {
        return new Lexed(out, "One statement per call: this text goes on after its semicolon. Send statements one "
            + "by one.");
      }
      if (c == '\'') {
        int j = i + 1;
        boolean closed = false;
        while (j < n) {
          if (text.charAt(j) == '\'') {
            if (j + 1 < n && text.charAt(j + 1) == '\'') {
              j += 2;
              continue;
            }
            closed = true;
            j++;
            break;
          }
          j++;
        }
        if (!closed) {
          return new Lexed(out, "A ' string isn't closed.");
        }
        out.add(new Token(T.STRING, "", depth));
        i = j;
        continue;
      }
      if (c == '$') {
        if (i + 1 < n && text.charAt(i + 1) == '$') {
          int close = text.indexOf("$$", i + 2);
          if (close < 0) {
            return new Lexed(out, "A $$ string isn't closed.");
          }
          out.add(new Token(T.STRING, "", depth));
          i = close + 2;
          continue;
        }
        return new Lexed(out, "A $ outside a string isn't CQL.");
      }
      if (c == '"') {
        Quoted q = quoted(text, i);
        if (q.problem() != null) {
          return new Lexed(out, q.problem());
        }
        out.add(new Token(T.QUOTED, q.value(), depth));
        i = q.end();
        continue;
      }
      if (c == ';') {
        if (depth != 0) {
          return new Lexed(out, "A semicolon inside brackets isn't CQL.");
        }
        ended = true;
        i++;
        continue;
      }
      if (asciiLetter(c) || asciiDigit(c)) {
        int end = uuidEnd(text, i);
        if (end < 0 && asciiLetter(c)) {
          end = i + 1;
          while (end < n && wordChar(text.charAt(end))) {
            end++;
          }
          out.add(new Token(T.WORD, text.substring(i, end), depth));
          i = end;
          continue;
        }
        if (end < 0) {
          end = numberEnd(text, i);
        }
        if (end < n && wordChar(text.charAt(end))) {
          // Cassandra's lexer ends a number where its literal ends: 1from is 1 and FROM.
          return new Lexed(out, "A number runs into letters (" + text.substring(i, end + 1) + "…): put a space "
              + "after it.");
        }
        out.add(new Token(T.NUMBER, text.substring(i, end), depth));
        i = end;
        continue;
      }
      if (c == '?') {
        out.add(new Token(T.MARKER, null, depth));
        i++;
        continue;
      }
      if (c == ':' && i + 1 < n && (asciiLetter(text.charAt(i + 1)) || text.charAt(i + 1) == '"')) {
        // :name and :"Name" are named markers; the ':' of a map or a UDT is followed by its value.
        if (text.charAt(i + 1) == '"') {
          Quoted q = quoted(text, i + 1);
          if (q.problem() != null) {
            return new Lexed(out, q.problem());
          }
          out.add(new Token(T.MARKER, q.value(), depth));
          i = q.end();
        } else {
          int j = i + 1;
          while (j < n && wordChar(text.charAt(j))) {
            j++;
          }
          out.add(new Token(T.MARKER, text.substring(i + 1, j).toLowerCase(Locale.ROOT), depth));
          i = j;
        }
        continue;
      }
      if ("([{".indexOf(c) >= 0) {
        out.add(new Token(T.PUNCT, String.valueOf(c), depth));
        depth++;
        i++;
        continue;
      }
      if (")]}".indexOf(c) >= 0) {
        depth--;
        if (depth < 0) {
          return new Lexed(out, "Brackets aren't balanced.");
        }
        out.add(new Token(T.PUNCT, String.valueOf(c), depth));
        i++;
        continue;
      }
      if (",.=<>!+-*/%:".indexOf(c) >= 0) {
        out.add(new Token(T.PUNCT, String.valueOf(c), depth));
        i++;
        continue;
      }
      if (c < 0x20 || c == 0x7F) {
        return new Lexed(out, "The statement holds a control character outside a string.");
      }
      if (c > 0x7E) {
        return new Lexed(out, "Characters outside ASCII are read only inside strings, quoted names and comments.");
      }
      return new Lexed(out, c + " isn't CQL outside a string.");
    }
    if (depth != 0) {
      return new Lexed(out, "Brackets aren't balanced.");
    }
    if (out.isEmpty()) {
      return new Lexed(out, "The statement has no keyword.");
    }
    return new Lexed(List.copyOf(out), null);
  }

  /** A {@code "…"} name: its value, where it ends, or why it can't be read. */
  private record Quoted(String value, int end, String problem) {}

  private static Quoted quoted(String text, int at) {
    StringBuilder name = new StringBuilder();
    int j = at + 1;
    while (j < text.length()) {
      char d = text.charAt(j);
      if (d == '"') {
        if (j + 1 < text.length() && text.charAt(j + 1) == '"') {
          name.append('"');
          j += 2;
          continue;
        }
        return name.isEmpty() ? new Quoted(null, j + 1, "A quoted name is empty.")
            : new Quoted(name.toString(), j + 1, null);
      }
      if (d < 0x20 || d == 0x7F) {
        return new Quoted(null, j, "A quoted name holds a control character.");
      }
      name.append(d);
      j++;
    }
    return new Quoted(null, j, "A \" name isn't closed.");
  }

  /** The end of a UUID literal that starts at {@code at}, or {@code -1}: Cassandra reads it as one token. */
  private static int uuidEnd(String text, int at) {
    int[] groups = {8, 4, 4, 4, 12};
    int j = at;
    for (int g = 0; g < groups.length; g++) {
      for (int k = 0; k < groups[g]; k++) {
        if (j >= text.length() || !hexDigit(text.charAt(j))) {
          return -1;
        }
        j++;
      }
      if (g < groups.length - 1) {
        if (j >= text.length() || text.charAt(j) != '-') {
          return -1;
        }
        j++;
      }
    }
    return j < text.length() && wordChar(text.charAt(j)) ? -1 : j;
  }

  /**
   * The end of a number that starts with a digit at {@code at}: a hex blob ({@code 0xCAFE}), a duration
   * ({@code 1h30m}; units y mo w d h m s ms us ns) or a decimal with an optional fraction and exponent.
   */
  private static int numberEnd(String text, int at) {
    int n = text.length();
    if (text.charAt(at) == '0' && at + 1 < n && (text.charAt(at + 1) == 'x' || text.charAt(at + 1) == 'X')) {
      int j = at + 2;
      while (j < n && hexDigit(text.charAt(j))) {
        j++;
      }
      return j;
    }
    int j = digits(text, at);
    int unit = durationUnit(text, j);
    if (unit > 0) {
      while (true) {
        j += unit;
        int next = digits(text, j);
        if (next == j) {
          return j;
        }
        unit = durationUnit(text, next);
        if (unit == 0) {
          return next;
        }
        j = next;
      }
    }
    if (j < n && text.charAt(j) == '.') {
      j = digits(text, j + 1);
    }
    if (j < n && (text.charAt(j) == 'e' || text.charAt(j) == 'E')) {
      int k = j + 1;
      if (k < n && (text.charAt(k) == '+' || text.charAt(k) == '-')) {
        k++;
      }
      int end = digits(text, k);
      if (end > k) {
        j = end;
      }
    }
    return j;
  }

  private static int digits(String text, int at) {
    int j = at;
    while (j < text.length() && asciiDigit(text.charAt(j))) {
      j++;
    }
    return j;
  }

  /** The length of a duration unit at {@code at} that no letter continues, or {@code 0}. */
  private static int durationUnit(String text, int at) {
    for (String unit : new String[] {"mo", "ms", "us", "ns", "y", "w", "d", "h", "m", "s"}) {
      if (text.regionMatches(true, at, unit, 0, unit.length())) {
        int after = at + unit.length();
        if (after >= text.length() || !asciiLetter(text.charAt(after))) {
          return unit.length();
        }
      }
    }
    return 0;
  }

  private static boolean asciiLetter(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
  }

  private static boolean asciiDigit(char c) {
    return c >= '0' && c <= '9';
  }

  private static boolean hexDigit(char c) {
    return asciiDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
  }

  private static boolean wordChar(char c) {
    return asciiLetter(c) || asciiDigit(c) || c == '_';
  }

  /** What a comment may hold: anything printable, but no control character and nothing that hides or reorders text. */
  private static boolean commentChar(char c) {
    if (c == '\t') {
      return true;
    }
    if (c < 0x20 || c == 0x7F || (c >= 0x80 && c <= 0x9F)) {
      return false;
    }
    return !((c >= 0x202A && c <= 0x202E) || (c >= 0x2066 && c <= 0x2069) || (c >= 0x200B && c <= 0x200F)
        || c == 0x061C || c == 0x2060 || c == 0xFEFF || c == 0x00AD);
  }

  // ── Statements ──────────────────────────────────────────────────────────────────────────────────────────────

  private static final String BATCHES = "Batches aren't run through GateRook: send one INSERT, UPDATE or DELETE per "
      + "call, and a person approves each.";

  static Statement read(String text) {
    Lexed lexed = lex(text);
    if (lexed.problem() != null) {
      // A batch's statements end with semicolons: say why it is refused rather than that the text goes on.
      List<Token> start = lexed.tokens();
      if (!start.isEmpty() && (start.getFirst().word("BEGIN") || start.getFirst().word("APPLY"))) {
        return refused(Kind.BATCH, "BATCH", BATCHES);
      }
      return refused(Kind.UNREADABLE, "statement", lexed.problem());
    }
    List<Token> t = lexed.tokens();
    Token first = t.getFirst();
    if (first.type() != T.WORD) {
      return refused(Kind.UNREADABLE, "statement", "A statement starts with a keyword such as SELECT.");
    }
    String verb = first.text().toUpperCase(Locale.ROOT);
    return switch (verb) {
      case "SELECT" -> select(t);
      case "INSERT" -> insert(t);
      case "UPDATE" -> update(t);
      case "DELETE" -> delete(t);
      case "TRUNCATE" -> truncate(t);
      case "CREATE", "ALTER", "DROP" -> ddl(t, verb);
      case "GRANT", "REVOKE", "LIST", "ADD" -> refused(Kind.ACCESS, verb, verb + " is access control: roles, users "
          + "and permissions stay with people.");
      case "BEGIN", "APPLY" -> refused(Kind.BATCH, "BATCH", BATCHES);
      case "USE" -> refused(Kind.USE, "USE", "USE isn't allowed: the connection's keyspace belongs to GateRook. Name "
          + "the keyspace in the statement, as keyspace.table.");
      case "DESCRIBE", "DESC" -> refused(Kind.OTHER, verb, verb + " isn't run through GateRook: use "
          + "cassandra_list_keyspaces and cassandra_describe_table.");
      default -> refused(Kind.OTHER, verb, verb + " isn't a statement GateRook runs on Cassandra.");
    };
  }

  private static Statement refused(Kind kind, String verb, String problem) {
    return new Statement(kind, verb, null, null, List.of(), false, false, false, List.of(), problem);
  }

  /** {@code SELECT [JSON | DISTINCT] selectors FROM [ks.]table …}: the only {@code FROM} outside brackets. */
  private static Statement select(List<Token> t) {
    int from = topLevelWord(t, "FROM", 1);
    if (from < 0) {
      return refused(Kind.UNREADABLE, "SELECT", "This SELECT has no FROM.");
    }
    if (topLevelWord(t, "FROM", from + 1) >= 0) {
      return refused(Kind.UNREADABLE, "SELECT", "This SELECT has more than one FROM.");
    }
    Parsed target = name(t, from + 1);
    if (target == null) {
      return refused(Kind.UNREADABLE, "SELECT", "The gateway can't find the table after FROM.");
    }
    return statement(Kind.SELECT, "SELECT", t, target, null);
  }

  private static Statement insert(List<Token> t) {
    if (t.size() < 3 || !t.get(1).word("INTO")) {
      return refused(Kind.UNREADABLE, "INSERT", "An INSERT starts with INSERT INTO table.");
    }
    Parsed target = name(t, 2);
    if (target == null) {
      return refused(Kind.UNREADABLE, "INSERT", "The gateway can't find the table after INTO.");
    }
    return statement(Kind.INSERT, "INSERT", t, target, null);
  }

  private static Statement update(List<Token> t) {
    Parsed target = name(t, 1);
    if (target == null) {
      return refused(Kind.UNREADABLE, "UPDATE", "The gateway can't find the table after UPDATE.");
    }
    return statement(Kind.UPDATE, "UPDATE", t, target, null);
  }

  /** {@code DELETE [selection] FROM [ks.]table …}: the only {@code FROM} outside brackets. */
  private static Statement delete(List<Token> t) {
    int from = topLevelWord(t, "FROM", 1);
    if (from < 0 || topLevelWord(t, "FROM", from + 1) >= 0) {
      return refused(Kind.UNREADABLE, "DELETE", "A DELETE names its table after one FROM.");
    }
    Parsed target = name(t, from + 1);
    if (target == null) {
      return refused(Kind.UNREADABLE, "DELETE", "The gateway can't find the table after FROM.");
    }
    return statement(Kind.DELETE, "DELETE", t, target, null);
  }

  private static Statement truncate(List<Token> t) {
    int at = 1;
    if (t.size() > 1 && (t.get(1).word("TABLE") || t.get(1).word("COLUMNFAMILY"))) {
      at = 2;
    }
    Parsed target = name(t, at);
    if (target == null || target.next() != t.size()) {
      return refused(Kind.UNREADABLE, "TRUNCATE", "A TRUNCATE names one table and nothing else.");
    }
    return statement(Kind.TRUNCATE, "TRUNCATE", t, target, null);
  }

  /** {@code CREATE}, {@code ALTER} and {@code DROP} of tables, indexes, types and views; the rest is refused. */
  private static Statement ddl(List<Token> t, String verb) {
    if (t.size() < 2 || t.get(1).type() != T.WORD) {
      return refused(Kind.UNREADABLE, verb, verb + " needs what it acts on, such as " + verb + " TABLE.");
    }
    String object = t.get(1).text().toUpperCase(Locale.ROOT);
    int at = 2;
    if (object.equals("OR") && verb.equals("CREATE")) {
      // CREATE OR REPLACE FUNCTION | AGGREGATE
      return refused(Kind.CODE, "CREATE OR REPLACE", "Functions and aggregates are code that runs on the server: "
          + "they stay with people.");
    }
    if (object.equals("CUSTOM") && verb.equals("CREATE") && t.size() > 2 && t.get(2).word("INDEX")) {
      object = "INDEX";
      at = 3;
    }
    if (object.equals("MATERIALIZED")) {
      if (t.size() < 3 || !t.get(2).word("VIEW")) {
        return refused(Kind.UNREADABLE, verb, verb + " MATERIALIZED needs VIEW.");
      }
      object = "MATERIALIZED VIEW";
      at = 3;
    }
    String what = verb + " " + object;
    switch (object) {
      case "KEYSPACE", "SCHEMA" -> {
        return refused(Kind.KEYSPACE, what, "Keyspaces and their replication stay with people: " + what
            + " isn't run through GateRook.");
      }
      case "ROLE", "USER", "IDENTITY" -> {
        return refused(Kind.ACCESS, what, what + " is access control: roles, users and permissions stay with "
            + "people.");
      }
      case "FUNCTION", "AGGREGATE", "TRIGGER" -> {
        return refused(Kind.CODE, what, "Functions, aggregates and triggers are code that runs on the server: "
            + "they stay with people.");
      }
      case "TABLE", "COLUMNFAMILY", "TYPE", "MATERIALIZED VIEW" -> {
        at = skipIfExists(t, at, verb.equals("CREATE"));
        Parsed target = name(t, at);
        if (target == null) {
          return refused(Kind.UNREADABLE, what, "The gateway can't find the name after " + what + ".");
        }
        String shown = object.equals("COLUMNFAMILY") ? "TABLE" : object;
        Kind kind = switch (shown) {
          case "TABLE" -> verb.equals("CREATE") ? Kind.CREATE_TABLE : verb.equals("ALTER") ? Kind.ALTER_TABLE
              : Kind.DROP_TABLE;
          case "TYPE" -> verb.equals("CREATE") ? Kind.CREATE_TYPE : verb.equals("ALTER") ? Kind.ALTER_TYPE
              : Kind.DROP_TYPE;
          default -> verb.equals("CREATE") ? Kind.CREATE_VIEW : verb.equals("ALTER") ? Kind.ALTER_VIEW
              : Kind.DROP_VIEW;
        };
        Parsed base = null;
        if (kind == Kind.CREATE_VIEW) {
          // CREATE MATERIALIZED VIEW v AS SELECT … FROM base …
          int from = topLevelWord(t, "FROM", target.next());
          base = from < 0 ? null : name(t, from + 1);
          if (base == null || topLevelWord(t, "FROM", from + 1) >= 0) {
            return refused(Kind.UNREADABLE, what, "The gateway can't find the view's base table after FROM.");
          }
        }
        return statement(kind, verb + " " + shown, t, target, base);
      }
      case "INDEX" -> {
        if (verb.equals("ALTER")) {
          return refused(Kind.OTHER, what, "ALTER INDEX isn't CQL: drop the index and create it again.");
        }
        if (verb.equals("DROP")) {
          at = skipIfExists(t, at, false);
          Parsed target = name(t, at);
          if (target == null || target.next() != t.size()) {
            return refused(Kind.UNREADABLE, what, "A DROP INDEX names one index and nothing else.");
          }
          return statement(Kind.DROP_INDEX, what, t, target, null);
        }
        at = skipIfExists(t, at, true);
        if (at < t.size() && !t.get(at).word("ON")) {
          at++; // the index's own name: unqualified, in the table's keyspace
        }
        if (at >= t.size() || !t.get(at).word("ON")) {
          return refused(Kind.UNREADABLE, what, "A CREATE INDEX names its table after ON.");
        }
        Parsed target = name(t, at + 1);
        if (target == null) {
          return refused(Kind.UNREADABLE, what, "The gateway can't find the table after ON.");
        }
        return statement(Kind.CREATE_INDEX, "CREATE INDEX", t, target, null);
      }
      default -> {
        return refused(Kind.OTHER, what, what + " isn't a statement GateRook runs on Cassandra.");
      }
    }
  }

  /** The index after {@code IF NOT EXISTS} ({@code create}) or {@code IF EXISTS}, or {@code at} without it. */
  private static int skipIfExists(List<Token> t, int at, boolean create) {
    if (create && at + 2 < t.size() && t.get(at).word("IF") && t.get(at + 1).word("NOT")
        && t.get(at + 2).word("EXISTS")) {
      return at + 3;
    }
    if (!create && at + 1 < t.size() && t.get(at).word("IF") && t.get(at + 1).word("EXISTS")) {
      return at + 2;
    }
    return at;
  }

  /** A parsed name and the index of the token after it. */
  private record Parsed(Name name, int start, int next) {}

  /** {@code name} or {@code keyspace.name} at {@code at}, each a word or a quoted name; {@code null} if none. */
  private static Parsed name(List<Token> t, int at) {
    if (at >= t.size() || t.get(at).depth() != 0) {
      return null;
    }
    String first = t.get(at).namePart();
    if (first == null) {
      return null;
    }
    if (at + 1 < t.size() && t.get(at + 1).punct('.')) {
      String second = at + 2 < t.size() ? t.get(at + 2).namePart() : null;
      return second == null ? null : new Parsed(new Name(first, second), at, at + 3);
    }
    return new Parsed(new Name(null, first), at, at + 1);
  }

  /** The facts of a statement whose target was found. */
  private static Statement statement(Kind kind, String verb, List<Token> t, Parsed target, Parsed base) {
    List<String> functions = new ArrayList<>();
    List<Marker> markers = new ArrayList<>();
    boolean allowFiltering = false;
    boolean conditional = false;
    boolean usingTimestamp = false;
    boolean using = false;
    for (int i = 0; i < t.size(); i++) {
      Token k = t.get(i);
      if (k.type() == T.MARKER) {
        markers.add(new Marker(k.text()));
        continue;
      }
      boolean nameStart = i == target.start() || (base != null && i == base.start());
      if (!nameStart && i + 3 < t.size() && t.get(i + 1).punct('.') && t.get(i + 3).punct('(')) {
        String keyspace = k.namePart();
        // keyspace.function(…); built-in functions may be written system.function(…).
        if (keyspace != null && t.get(i + 2).namePart() != null && !keyspace.equals("system")) {
          functions.add(keyspace);
        }
      }
      if (k.depth() != 0 || k.type() != T.WORD) {
        continue;
      }
      if (k.word("ALLOW") && i + 1 < t.size() && t.get(i + 1).word("FILTERING")) {
        allowFiltering = true;
      }
      if (kind.dml() && k.word("IF")) {
        conditional = true;
      }
      if (kind.dml() && k.word("USING")) {
        using = true;
      }
      if (using && k.word("TIMESTAMP") && i + 1 < t.size()
          && (t.get(i + 1).type() == T.NUMBER || t.get(i + 1).type() == T.MARKER)) {
        usingTimestamp = true;
      }
    }
    return new Statement(kind, verb, target.name(), base == null ? null : base.name(), List.copyOf(functions),
        allowFiltering, conditional, usingTimestamp, List.copyOf(markers), null);
  }

  /** The index of the first word {@code upper} outside brackets from {@code from} on, or {@code -1}. */
  private static int topLevelWord(List<Token> t, String upper, int from) {
    for (int i = from; i < t.size(); i++) {
      if (t.get(i).depth() == 0 && t.get(i).word(upper)) {
        return i;
      }
    }
    return -1;
  }

  /**
   * A table name an agent passes on its own ({@code table} of {@code cassandra_describe_table}): {@code table},
   * {@code keyspace.table} or quoted parts; {@code null} if the text is anything else.
   */
  static Name tableName(String text) {
    if (text == null || text.isBlank() || text.length() > 300) {
      return null;
    }
    Lexed lexed = lex(text);
    if (lexed.problem() != null) {
      return null;
    }
    Parsed parsed = name(lexed.tokens(), 0);
    return parsed == null || parsed.next() != lexed.tokens().size() ? null : parsed.name();
  }

  /** {@code name} as CQL writes it: unquoted when it is a plain lower-case name, else in double quotes. */
  static String quote(String name) {
    if (name.matches("[a-z][a-z0-9_]*") && !RESERVED.contains(name.toUpperCase(Locale.ROOT))) {
      return name;
    }
    return "\"" + name.replace("\"", "\"\"") + "\"";
  }
}
