package com.gaterook.connectors.influxdb;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What one InfluxQL statement is, without I/O. The text is read by the tokens of InfluxDB's own scanners (the Go
 * scanner of 1.x and 2.x, the Rust parser of InfluxDB 3): {@code '…'} strings and {@code "…"} names with the escapes
 * {@code \\}, {@code \'} and {@code \"}, regular expressions {@code /…/} with {@code \/} where the grammar takes one,
 * {@code $name} parameters, numbers and durations. Where they could read the text differently, it is
 * {@link Kind#UNREADABLE} and never runs: comments, another escape, a regular expression with {@code \\} or starting
 * with {@code *}, a {@code /} that is neither a division nor a regular expression with certainty, a quote right after a
 * name, characters outside ASCII outside quotes.
 *
 * <p>A read is one {@code SELECT} without {@code INTO}, or a {@code SHOW} of data: databases, retention policies,
 * measurements, series, tag keys and values, field keys and their cardinality. The statement's own database names
 * ({@code ON db}, {@code FROM db.rp.measurement}) are returned for the connection's list.
 */
final class InfluxQl {

  /** The route of a statement. */
  enum Kind { READ, WRITE, FORBIDDEN, UNREADABLE }

  /**
   * One statement.
   *
   * @param type            {@code SELECT}, {@code SHOW MEASUREMENTS}, …, or a description
   * @param databases       the databases the statement names itself, in order
   * @param defaultDatabase whether it reads the request's database (an unqualified measurement, a SHOW without ON)
   * @param problem         why it is not a read, for the agent; {@code null} for a read
   */
  record Statement(Kind kind, String type, List<String> databases, boolean defaultDatabase, String problem) {

    static Statement not(Kind kind, String type, String problem) {
      return new Statement(kind, type, List.of(), false, problem);
    }

    boolean showDatabases() {
      return "SHOW DATABASES".equals(type);
    }
  }

  enum T { WORD, QUOTED, STRING, REGEX, NUMBER, PARAM, OP }

  /** One token; {@code text} of a quoted name or string is its value. */
  record Tok(T type, String text, int start, int end) {

    boolean op(String s) {
      return type == T.OP && text.equals(s);
    }

    boolean word(String upper) {
      return type == T.WORD && text.toUpperCase(Locale.ROOT).equals(upper);
    }
  }

  static final int MAX_LENGTH = 20_000;
  private static final Pattern FLUX = Pattern.compile("(?s)\\|>|^\\s*(import\\s+\"|from\\s*\\(\\s*bucket"
      + "(ID)?\\s*:|option\\s+task\\b)");

  /** InfluxQL's keywords: none of them ends an operand, so a {@code /} after one is no division. */
  private static final Set<String> KEYWORDS = Set.of("ALL", "ALTER", "ANALYZE", "AND", "ANY", "AS", "ASC",
      "BEGIN", "BY", "CARDINALITY", "CONTINUOUS", "CREATE", "DATABASE", "DATABASES", "DEFAULT", "DELETE", "DESC",
      "DESTINATIONS", "DIAGNOSTICS", "DISTINCT", "DROP", "DURATION", "END", "EVERY", "EXACT", "EXPLAIN", "FIELD",
      "FOR", "FROM", "GRANT", "GRANTS", "GROUP", "GROUPS", "IN", "INF", "INSERT", "INTO", "KEY", "KEYS", "KILL",
      "LIMIT", "MEASUREMENT", "MEASUREMENTS", "NAME", "NOT", "OFFSET", "ON", "OR", "ORDER", "PASSWORD", "POLICIES",
      "POLICY", "PRIVILEGES", "QUERIES", "QUERY", "READ", "REPLICATION", "RESAMPLE", "RETENTION", "REVOKE",
      "SELECT", "SERIES", "SET", "SHARD", "SHARDS", "SHOW", "SLIMIT", "SOFFSET", "STATS", "SUBSCRIPTION",
      "SUBSCRIPTIONS", "TAG", "TO", "USER", "USERS", "VALUES", "WHERE", "WITH", "WRITE");
  private static final Set<String> WRITES = Set.of("CREATE", "DROP", "DELETE", "ALTER", "INSERT");
  private static final Set<String> ADMIN = Set.of("GRANT", "REVOKE", "KILL", "SET");
  /** Words after which a FROM list has ended. */
  private static final Set<String> AFTER_FROM = Set.of("WHERE", "GROUP", "ORDER", "LIMIT", "OFFSET", "SLIMIT",
      "SOFFSET", "FILL", "TZ", "WITH");
  /** The SHOW statements of data GateRook runs, as word sequences after SHOW. */
  private static final List<List<String>> SHOW_READS = List.of(List.of("DATABASES"),
      List.of("RETENTION", "POLICIES"), List.of("MEASUREMENTS"), List.of("MEASUREMENT", "CARDINALITY"),
      List.of("MEASUREMENT", "EXACT", "CARDINALITY"), List.of("SERIES"), List.of("SERIES", "CARDINALITY"),
      List.of("SERIES", "EXACT", "CARDINALITY"), List.of("TAG", "KEYS"), List.of("TAG", "KEY", "CARDINALITY"),
      List.of("TAG", "KEY", "EXACT", "CARDINALITY"), List.of("TAG", "VALUES"), List.of("TAG", "VALUES", "CARDINALITY"),
      List.of("TAG", "VALUES", "EXACT", "CARDINALITY"), List.of("FIELD", "KEYS"),
      List.of("FIELD", "KEY", "CARDINALITY"), List.of("FIELD", "KEY", "EXACT", "CARDINALITY"));

  private InfluxQl() {}

  /** Whether {@code query} looks like Flux, which GateRook never runs. */
  static boolean looksLikeFlux(String query) {
    return query != null && FLUX.matcher(query).find();
  }

  static Statement read(String text) {
    if (text == null || text.isBlank()) {
      return Statement.not(Kind.UNREADABLE, "statement", "The statement is empty.");
    }
    if (text.length() > MAX_LENGTH) {
      return Statement.not(Kind.UNREADABLE, "statement", "The statement is longer than " + MAX_LENGTH
          + " characters.");
    }
    List<List<Tok>> statements = new ArrayList<>();
    String problem = new Scanner(text).tokens(statements);
    if (problem != null) {
      return Statement.not(Kind.UNREADABLE, "statement", problem + " GateRook doesn't run what it can't read with "
          + "certainty.");
    }
    if (statements.isEmpty()) {
      return Statement.not(Kind.UNREADABLE, "statement", "The statement has no keyword.");
    }
    if (statements.size() > 1) {
      return Statement.not(Kind.FORBIDDEN, "script", "One statement per call: this text has " + statements.size()
          + ". Send them one by one.");
    }
    return analyze(statements.getFirst());
  }

  private static Statement analyze(List<Tok> t) {
    Tok first = t.getFirst();
    if (first.type() != T.WORD) {
      return Statement.not(Kind.UNREADABLE, "statement", "The statement doesn't start with a keyword.");
    }
    String w = first.text().toUpperCase(Locale.ROOT);
    String type;
    if (w.equals("SELECT")) {
      type = "SELECT";
    } else if (w.equals("SHOW")) {
      type = show(t);
      if (type == null) {
        String what = t.size() > 1 ? "SHOW " + t.get(1).text().toUpperCase(Locale.ROOT) : "SHOW";
        return Statement.not(Kind.FORBIDDEN, what, what + " isn't a SHOW of data: GateRook runs SHOW DATABASES, "
            + "RETENTION POLICIES, MEASUREMENTS, SERIES, TAG KEYS, TAG VALUES, FIELD KEYS and their CARDINALITY. "
            + "Users, grants, queries, subscriptions, shards and diagnostics stay with people.");
      }
    } else if (WRITES.contains(w)) {
      return Statement.not(Kind.WRITE, w, w + " changes data or schema.");
    } else if (ADMIN.contains(w)) {
      return Statement.not(Kind.FORBIDDEN, w, w + " administers the server: it stays with people.");
    } else {
      return Statement.not(Kind.FORBIDDEN, w, w + " isn't an InfluxQL statement GateRook runs: it runs SELECT "
          + "and SHOW.");
    }
    for (Tok tok : t) {
      if (tok.type() != T.WORD) {
        continue;
      }
      String u = tok.text().toUpperCase(Locale.ROOT);
      if (u.equals("INTO")) {
        return Statement.not(Kind.WRITE, type, "SELECT … INTO writes its result into a measurement: "
            + "influxdb_query only reads.");
      }
      if (WRITES.contains(u) || ADMIN.contains(u)) {
        return Statement.not(WRITES.contains(u) ? Kind.WRITE : Kind.FORBIDDEN, type, u + " has no place in a read. "
            + "Quote a name that is spelled like this keyword (\"" + tok.text() + "\").");
      }
    }
    Set<String> databases = new LinkedHashSet<>();
    boolean on = false;
    boolean from = false;
    boolean unqualified = false;
    for (int k = 0; k < t.size(); k++) {
      Tok tok = t.get(k);
      if (tok.word("ON")) {
        Tok db = k + 1 < t.size() ? t.get(k + 1) : null;
        if (db == null || !(db.type() == T.QUOTED || (db.type() == T.WORD && !keyword(db)))) {
          return Statement.not(Kind.UNREADABLE, type, "Name the database after ON as a name, not a parameter. "
              + "GateRook doesn't run what it can't read with certainty.");
        }
        databases.add(db.text());
        on = true;
      } else if (tok.word("FROM")) {
        from = true;
        Sources sources = sources(t, k + 1);
        if (sources.problem() != null) {
          return Statement.not(Kind.UNREADABLE, type, sources.problem() + " GateRook doesn't run what it can't read "
              + "with certainty.");
        }
        databases.addAll(sources.databases());
        unqualified |= sources.unqualified();
      }
    }
    boolean defaultDatabase;
    if (type.equals("SHOW DATABASES")) {
      defaultDatabase = false;
    } else if (on) {
      defaultDatabase = false;
    } else if (from) {
      defaultDatabase = unqualified;
    } else {
      defaultDatabase = true;
    }
    return new Statement(Kind.READ, type, List.copyOf(databases), defaultDatabase, null);
  }

  /** {@code SHOW MEASUREMENTS} … of {@link #SHOW_READS}: the longest form the words start with, or {@code null}. */
  private static String show(List<Tok> t) {
    List<String> best = null;
    for (List<String> form : SHOW_READS) {
      if (t.size() <= form.size()) {
        continue;
      }
      boolean match = true;
      for (int i = 0; i < form.size(); i++) {
        if (!t.get(i + 1).word(form.get(i))) {
          match = false;
          break;
        }
      }
      if (match && (best == null || form.size() > best.size())) {
        best = form;
      }
    }
    return best == null ? null : "SHOW " + String.join(" ", best);
  }

  private static boolean keyword(Tok tok) {
    return tok.type() == T.WORD && KEYWORDS.contains(tok.text().toUpperCase(Locale.ROOT));
  }

  /** What a FROM list reads: the databases its sources name, and whether one reads the request's database. */
  record Sources(List<String> databases, boolean unqualified, String problem) {

    static Sources refused(String problem) {
      return new Sources(List.of(), false, problem);
    }
  }

  /**
   * The sources of the FROM list starting at {@code j}: measurements ({@code m}, {@code rp.m}, {@code db.rp.m},
   * {@code db..m}, the last one possibly a regular expression) and subqueries, separated by commas.
   */
  static Sources sources(List<Tok> t, int j) {
    List<String> databases = new ArrayList<>();
    boolean unqualified = false;
    while (true) {
      if (j >= t.size()) {
        return Sources.refused("FROM needs a measurement.");
      }
      Tok s = t.get(j);
      if (s.op("(")) {
        int close = matching(t, j);
        if (close < 0) {
          return Sources.refused("A parenthesis isn't closed.");
        }
        j = close + 1;
      } else if (s.type() == T.REGEX) {
        unqualified = true;
        j++;
      } else if (s.type() == T.QUOTED
          || (s.type() == T.WORD && !AFTER_FROM.contains(s.text().toUpperCase(Locale.ROOT)))) {
        List<String> segments = new ArrayList<>();
        boolean regex = false;
        segments.add(s.text());
        j++;
        while (j < t.size() && t.get(j).op(".")) {
          if (regex) {
            return Sources.refused("A regular expression is the last part of a measurement's name.");
          }
          j++;
          Tok next = j < t.size() ? t.get(j) : null;
          if (next == null) {
            return Sources.refused("A measurement's name ends with a dot.");
          }
          if (next.op(".")) {
            segments.add("");
          } else if (next.type() == T.QUOTED || next.type() == T.WORD) {
            segments.add(next.text());
            j++;
          } else if (next.type() == T.REGEX) {
            segments.add("");
            regex = true;
            j++;
          } else if (next.type() == T.PARAM) {
            return Sources.refused("Name databases and measurements in the statement itself, not as parameters.");
          } else {
            return Sources.refused("The FROM clause isn't analyzed.");
          }
        }
        if (segments.size() > 3) {
          return Sources.refused("A measurement's name has at most three parts: database.retention_policy."
              + "measurement.");
        }
        if (segments.size() == 3) {
          if (segments.getFirst().isEmpty()) {
            return Sources.refused("A measurement's name starts with a dot.");
          }
          databases.add(segments.getFirst());
        } else {
          unqualified = true;
        }
      } else if (s.type() == T.PARAM) {
        return Sources.refused("Name databases and measurements in the statement itself, not as parameters.");
      } else {
        return Sources.refused("The FROM clause isn't analyzed.");
      }
      if (j < t.size() && t.get(j).op(",")) {
        j++;
        continue;
      }
      if (j >= t.size() || t.get(j).op(")")
          || (t.get(j).type() == T.WORD && AFTER_FROM.contains(t.get(j).text().toUpperCase(Locale.ROOT)))) {
        return new Sources(databases, unqualified, null);
      }
      return Sources.refused("The FROM clause isn't analyzed.");
    }
  }

  /** The index of the {@code )} that closes the {@code (} at {@code open}, or -1. */
  private static int matching(List<Tok> t, int open) {
    int depth = 0;
    for (int k = open; k < t.size(); k++) {
      if (t.get(k).op("(")) {
        depth++;
      } else if (t.get(k).op(")") && --depth == 0) {
        return k;
      }
    }
    return -1;
  }

  /** The tokenizer: it keeps the clause and the parentheses it is in, which decide what a {@code /} is. */
  private static final class Scanner {

    private enum Clause { NONE, SELECT, FROM, BY, OTHER }

    private static final class Frame {
      Clause clause = Clause.NONE;
      final boolean call;

      Frame(boolean call) {
        this.call = call;
      }
    }

    private final String text;
    private final int n;
    private final Deque<Frame> frames = new ArrayDeque<>();
    private List<Tok> current = new ArrayList<>();

    Scanner(String text) {
      this.text = text;
      this.n = text.length();
      frames.push(new Frame(false));
    }

    /** Statements of tokens, or why the text can't be read with certainty. */
    String tokens(List<List<Tok>> out) {
      int i = 0;
      while (i < n) {
        char c = text.charAt(i);
        if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
          i++;
          continue;
        }
        if (c < 0x20 || c == 0x7F) {
          return "The statement holds a control character.";
        }
        if (c > 0x7E) {
          return "Characters outside ASCII are read only inside quotes: write \"name\" or '…'.";
        }
        char next = i + 1 < n ? text.charAt(i + 1) : 0;
        if ((c == '-' && next == '-') || (c == '/' && next == '*' && !regexExpected())) {
          return "Comments aren't analyzed: send the statement without them.";
        }
        int start = i;
        if (c == '/') {
          if (regexExpected()) {
            int end = regex(i);
            if (end < 0) {
              return problem;
            }
            add(T.REGEX, text.substring(i + 1, end - 1), start, end);
            i = end;
          } else if (operandBefore()) {
            add(T.OP, "/", start, i + 1);
            i++;
          } else {
            return "A / here isn't analyzed: InfluxQL reads it as a division or a regular expression depending on "
                + "where it stands.";
          }
          continue;
        }
        if (c == '\'' || c == '"') {
          if (i > 0 && (identChar(text.charAt(i - 1)) || text.charAt(i - 1) == '$')) {
            return "A quote right after a name isn't analyzed: put a space or an operator between them.";
          }
          int end = quoted(i, c);
          if (end < 0) {
            return problem;
          }
          add(c == '\'' ? T.STRING : T.QUOTED, value, start, end);
          i = end;
          continue;
        }
        if (c == '$') {
          int j = i + 1;
          while (j < n && identChar(text.charAt(j))) {
            j++;
          }
          if (j == i + 1) {
            return "A $ starts a parameter such as $host.";
          }
          add(T.PARAM, text.substring(i + 1, j), start, j);
          i = j;
          continue;
        }
        if (identStart(c)) {
          int j = i + 1;
          while (j < n && identChar(text.charAt(j))) {
            j++;
          }
          String word = text.substring(i, j);
          add(T.WORD, word, start, j);
          clause(word);
          i = j;
          continue;
        }
        if (isDigit(c) || (c == '.' && isDigit(next))) {
          int j = number(i);
          if (j < 0) {
            return problem;
          }
          add(T.NUMBER, text.substring(i, j), start, j);
          i = j;
          continue;
        }
        String op = operator(i);
        if (op == null) {
          return c + " isn't analyzed outside quotes.";
        }
        if (op.equals(";")) {
          if (frames.size() > 1) {
            return "A ; inside parentheses isn't analyzed.";
          }
          if (!current.isEmpty()) {
            out.add(current);
          }
          current = new ArrayList<>();
          frames.clear();
          frames.push(new Frame(false));
          i++;
          continue;
        }
        if (op.equals("(")) {
          Tok prev = current.isEmpty() ? null : current.getLast();
          boolean call = prev != null && prev.type() == T.WORD && prev.end() == i;
          Frame frame = new Frame(call);
          frame.clause = call ? Clause.OTHER : frames.peek().clause == Clause.FROM ? Clause.NONE : Clause.OTHER;
          add(T.OP, op, start, i + 1);
          frames.push(frame);
          i++;
          continue;
        }
        if (op.equals(")")) {
          if (frames.size() <= 1) {
            return "A ) has no (.";
          }
          frames.pop();
        }
        add(T.OP, op, start, i + op.length());
        i += op.length();
      }
      if (frames.size() > 1) {
        return "A parenthesis isn't closed.";
      }
      if (!current.isEmpty()) {
        out.add(current);
      }
      return null;
    }

    private String problem;
    private String value;

    private void add(T type, String s, int start, int end) {
      current.add(new Tok(type, s, start, end));
    }

    /** The clause a keyword opens in the current parentheses. */
    private void clause(String word) {
      Frame frame = frames.peek();
      switch (word.toUpperCase(Locale.ROOT)) {
        case "SELECT" -> frame.clause = Clause.SELECT;
        case "FROM" -> frame.clause = Clause.FROM;
        case "BY" -> {
          Tok before = current.size() >= 2 ? current.get(current.size() - 2) : null;
          frame.clause = before != null && before.word("GROUP") ? Clause.BY : Clause.OTHER;
        }
        case "WHERE", "GROUP", "ORDER", "LIMIT", "OFFSET", "SLIMIT", "SOFFSET", "FILL", "TZ", "WITH", "ON", "INTO" ->
            frame.clause = Clause.OTHER;
        default -> {
          // A name or a function: the clause goes on.
        }
      }
    }

    /**
     * Whether the grammar takes a regular expression here: after {@code =~} and {@code !~}, where a field, a source
     * or a dimension starts (after SELECT, FROM, GROUP BY, a comma of those lists, a dot of a source) and as a
     * function's argument. Nowhere else does a {@code /} start one, and in none of these places is it a division.
     */
    private boolean regexExpected() {
      if (current.isEmpty()) {
        return false;
      }
      Tok prev = current.getLast();
      Frame frame = frames.peek();
      if (prev.op("=~") || prev.op("!~")) {
        return true;
      }
      if (prev.word("SELECT") || prev.word("FROM")) {
        return true;
      }
      if (prev.word("BY")) {
        return frame.clause == Clause.BY;
      }
      if (prev.op(",")) {
        return frame.call || frame.clause == Clause.SELECT || frame.clause == Clause.FROM
            || frame.clause == Clause.BY;
      }
      if (prev.op(".")) {
        return frame.clause == Clause.FROM;
      }
      if (prev.op("(")) {
        return frame.call;
      }
      return false;
    }

    /** Whether the token before ends an operand, so that a {@code /} after it divides. */
    private boolean operandBefore() {
      if (current.isEmpty()) {
        return false;
      }
      Tok prev = current.getLast();
      return switch (prev.type()) {
        case QUOTED, STRING, NUMBER, PARAM -> true;
        case WORD -> !keyword(prev);
        case OP -> prev.text().equals(")");
        case REGEX -> false;
      };
    }

    /** A regular expression from the {@code /} at {@code i}: the index after its closing {@code /}, or -1. */
    private int regex(int i) {
      int j = i + 1;
      if (j < n && text.charAt(j) == '*') {
        problem = "A regular expression can't start with *: InfluxQL reads /* as a comment.";
        return -1;
      }
      while (j < n) {
        char d = text.charAt(j);
        if (d == '\n' || d == '\r') {
          problem = "A regular expression isn't closed on its line.";
          return -1;
        }
        if (d < 0x20 && d != '\t' || d == 0x7F) {
          problem = "The statement holds a control character.";
          return -1;
        }
        if (d == '\\') {
          char e = j + 1 < n ? text.charAt(j + 1) : 0;
          if (e == '\\') {
            problem = "Two backslashes in a regular expression are read differently by InfluxDB versions: write a "
                + "backslash as \\x5c.";
            return -1;
          }
          if (e == 0 || e == '\n' || e == '\r') {
            problem = "A regular expression isn't closed.";
            return -1;
          }
          j += 2;
          continue;
        }
        if (d == '/') {
          return j + 1;
        }
        j++;
      }
      problem = "A regular expression isn't closed.";
      return -1;
    }

    /** A string or a quoted name from the quote at {@code i}: the index after it, its value in {@link #value}. */
    private int quoted(int i, char quote) {
      StringBuilder v = new StringBuilder();
      int j = i + 1;
      while (j < n) {
        char d = text.charAt(j);
        if (d == '\n' || d == '\r') {
          problem = quote == '\'' ? "A string isn't closed on its line." : "A quoted name isn't closed on its line.";
          return -1;
        }
        if (d < 0x20 && d != '\t' || d == 0x7F) {
          problem = "The statement holds a control character.";
          return -1;
        }
        if (d == '\\') {
          char e = j + 1 < n ? text.charAt(j + 1) : 0;
          if (e != '\\' && e != '\'' && e != '"') {
            problem = "Inside quotes GateRook reads only the escapes \\\\, \\' and \\\".";
            return -1;
          }
          v.append(e);
          j += 2;
          continue;
        }
        if (d == quote) {
          value = v.toString();
          return j + 1;
        }
        v.append(d);
        j++;
      }
      problem = quote == '\'' ? "A string isn't closed." : "A quoted name isn't closed.";
      return -1;
    }

    /**
     * A number from {@code i}, read as InfluxDB's Go scanner reads it: digits, then a fraction ({@code .5}) or a
     * duration's letters and digits ({@code 10m}, {@code 1h}) as part of the same token. A dot without a digit after
     * it is swallowed by that scanner, so it is refused.
     */
    private int number(int i) {
      int j = i;
      while (j < n && isDigit(text.charAt(j))) {
        j++;
      }
      if (j < n && text.charAt(j) == '.') {
        if (j + 1 < n && isDigit(text.charAt(j + 1))) {
          j++;
          while (j < n && isDigit(text.charAt(j))) {
            j++;
          }
          return j;
        }
        problem = "A number ends with a dot.";
        return -1;
      }
      if (j < n && isLetter(text.charAt(j))) {
        while (j < n && (isLetter(text.charAt(j)) || isDigit(text.charAt(j)))) {
          j++;
        }
      }
      return j;
    }

    private String operator(int i) {
      char c = text.charAt(i);
      char next = i + 1 < n ? text.charAt(i + 1) : 0;
      String two = "" + c + next;
      if (two.equals("=~") || two.equals("!~") || two.equals("!=") || two.equals("<>") || two.equals("<=")
          || two.equals(">=") || two.equals("::")) {
        return two;
      }
      return "=<>+-*%&|^(),.;".indexOf(c) >= 0 ? String.valueOf(c) : null;
    }
  }

  private static boolean identStart(char c) {
    return isLetter(c) || c == '_';
  }

  private static boolean identChar(char c) {
    return isLetter(c) || isDigit(c) || c == '_';
  }

  private static boolean isLetter(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
  }

  private static boolean isDigit(char c) {
    return c >= '0' && c <= '9';
  }

  /** {@code "name"} in InfluxQL, with {@code \} and {@code "} escaped. */
  static String quote(String name) {
    return "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
