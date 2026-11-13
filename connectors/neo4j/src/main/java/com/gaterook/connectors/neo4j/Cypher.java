package com.gaterook.connectors.neo4j;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reads one Cypher statement by Cypher's own tokens, without I/O: strings in {@code '…'} and {@code "…"} with
 * backslash escapes, names in backticks with doubled backticks, {@code //} and {@code /* … *}{@code /} comments,
 * parameters ({@code $name}) and words. Keywords are words outside property access ({@code n.call}), labels and
 * relationship types ({@code :Load}) and map keys ({@code {use: 1}}).
 *
 * <p>What the reader can't read with certainty is {@link Kind#UNREADABLE}: an open quote, name or comment, a backslash
 * outside a string, any {@code \}{@code u} escape (some Cypher parsers decode them before they read the text, others
 * inside strings only), control characters and line separators, spaces and characters Cypher doesn't use outside
 * strings, and letters that stand for other letters (a word whose upper or lower case, or NFKC form, holds letters
 * the word doesn't: {@code ſ}, {@code ı}, fullwidth letters). What GateRook never runs is {@link Kind#FORBIDDEN}:
 * administration and security commands, {@code SHOW} commands, {@code LOAD CSV}, procedures off the read-only schema
 * list, functions that run Cypher text, read files or URLs, or come from an unknown plugin, {@code USE},
 * {@code CALL { … } IN TRANSACTIONS}, more than one statement, and {@code EXPLAIN}, {@code PROFILE} and {@code CYPHER}
 * options (GateRook plans the statement itself). The rest is a {@link Kind#READ}, a {@link Kind#WRITE} (a write clause
 * appears) or a {@link Kind#SCHEMA} command. The server has the last word: reads run in READ transactions after its
 * {@code EXPLAIN} calls them read only.
 */
final class Cypher {

  enum Kind { UNREADABLE, FORBIDDEN, READ, WRITE, SCHEMA }

  /**
   * What one statement is.
   *
   * @param rule       for {@link Kind#UNREADABLE} and {@link Kind#FORBIDDEN}: the policy rule id ({@code admin})
   * @param problem    what is wrong, for the agent
   * @param hint       what to do instead, or {@code null}
   * @param writes     the write clauses found, in order (CREATE, MERGE, SET, DELETE, …)
   * @param procedures the procedures called, as written
   */
  record Statement(Kind kind, String rule, String problem, String hint, List<String> writes,
      List<String> procedures) {

    boolean refused() {
      return kind == Kind.UNREADABLE || kind == Kind.FORBIDDEN;
    }

    /** What the statement changes, in words: {@code CREATE, SET}, a schema command, or nothing. */
    String changes() {
      if (kind == Kind.SCHEMA) {
        return "the schema";
      }
      return writes.isEmpty() ? "nothing" : String.join(", ", writes);
    }
  }

  /** Read-only procedures of the schema an agent may call; everything else stays with people. */
  static final Set<String> PROCEDURES = Set.of("db.labels", "db.relationshipTypes", "db.propertyKeys");
  private static final String SCHEMA_PROCEDURES = "db.schema.";

  /** Namespaces of Cypher's built-in functions ({@code date.truncate}, {@code point.distance}). */
  private static final Set<String> BUILT_IN_NAMESPACES = Set.of("date", "datetime", "localdatetime", "localtime",
      "time", "duration", "point", "vector", "vector.similarity", "coll");
  private static final Set<String> BUILT_IN_FUNCTIONS = Set.of("db.nameelementid", "db.namefromelementid",
      "graph.names", "graph.byname", "graph.byelementid", "graph.propertiesbyname");
  /** APOC functions that run Cypher text, read files, URLs or settings, or touch triggers and the system database. */
  private static final List<String> APOC_REFUSED = List.of("apoc.cypher.", "apoc.load.", "apoc.import.",
      "apoc.export.", "apoc.periodic.", "apoc.trigger.", "apoc.systemdb.", "apoc.static.", "apoc.config.",
      "apoc.custom.", "apoc.log.", "apoc.ml.", "apoc.util.sleep");

  private static final Set<String> WRITE_CLAUSES = Set.of("CREATE", "MERGE", "SET", "DELETE", "DETACH", "NODETACH",
      "REMOVE", "FOREACH", "INSERT");
  /** Words that start an administration or security command. */
  private static final Set<String> ADMIN_START = Set.of("GRANT", "DENY", "REVOKE", "START", "STOP", "TERMINATE",
      "ENABLE", "DEALLOCATE", "REALLOCATE", "DRYRUN", "RENAME", "ALTER", "SHOW");
  /** Never a keyword of a query: commands that administer users, roles, databases, servers or transactions. */
  private static final Set<String> ADMIN_ANYWHERE = Set.of("GRANT", "DENY", "REVOKE", "TERMINATE", "SHOW", "ALTER");
  /** What {@code CREATE} and {@code DROP} administer instead of a graph or its schema. */
  private static final Set<String> ADMIN_OBJECTS = Set.of("USER", "USERS", "ROLE", "ROLES", "DATABASE", "DATABASES",
      "COMPOSITE", "ALIAS", "ALIASES", "SERVER", "SERVERS", "CURRENT");
  private static final Set<String> SCHEMA_OBJECTS = Set.of("INDEX", "INDEXES", "CONSTRAINT", "CONSTRAINTS", "RANGE",
      "TEXT", "POINT", "LOOKUP", "FULLTEXT", "VECTOR", "BTREE");
  private static final String SYMBOLS = "()[]{},.:;|&!%*+-/^=<>~";

  private Cypher() {}

  private enum T { WORD, NAME, STRING, NUMBER, PARAM, SYMBOL }

  /** One token; {@code text} is a word as written, a name without its backticks, a symbol. */
  private record Token(T type, String text) {

    boolean word(String upper) {
      return type == T.WORD && text.equalsIgnoreCase(upper);
    }

    boolean symbol(char c) {
      return type == T.SYMBOL && text.length() == 1 && text.charAt(0) == c;
    }

    String upper() {
      return text.toUpperCase(Locale.ROOT);
    }

    boolean namePart() {
      return type == T.WORD || type == T.NAME;
    }
  }

  /** Thrown inside the reader: the text can't be read with certainty. */
  private static final class Unreadable extends Exception {
    private static final long serialVersionUID = 1L;

    Unreadable(String message) {
      super(message, null, false, false);
    }
  }

  static Statement read(String text) {
    if (text == null || text.isBlank()) {
      return unreadable("The statement is empty.");
    }
    List<Token> tokens;
    try {
      tokens = tokens(text);
    } catch (Unreadable e) {
      return unreadable(e.getMessage());
    }
    if (tokens.isEmpty()) {
      return unreadable("The statement holds only comments.");
    }
    return analyze(tokens);
  }

  private static Statement unreadable(String problem) {
    return new Statement(Kind.UNREADABLE, "unparsed", problem, "Write one plain Cypher statement and pass values as "
        + "parameters ($name).", List.of(), List.of());
  }

  private static Statement forbidden(String rule, String problem, String hint) {
    return new Statement(Kind.FORBIDDEN, rule, problem, hint, List.of(), List.of());
  }

  // ── Tokens ──────────────────────────────────────────────────────────────────────────────────────────────────

  private static List<Token> tokens(String text) throws Unreadable {
    int n = text.length();
    for (int k = 0; k < n; k++) {
      char c = text.charAt(k);
      if ((c < 0x20 && c != '\t' && c != '\n' && c != '\r') || (c >= 0x7F && c <= 0x9F) || c == ' '
          || c == ' ') {
        throw new Unreadable("The statement holds a control character or a line separator (U+"
            + String.format(Locale.ROOT, "%04X", (int) c) + ").");
      }
      if (c == '\\' && k + 1 < n && (text.charAt(k + 1) == 'u' || text.charAt(k + 1) == 'U')) {
        throw new Unreadable("The statement holds a \\u escape. Write the character itself, or pass the value as a "
            + "parameter.");
      }
    }
    List<Token> out = new ArrayList<>();
    int i = 0;
    while (i < n) {
      char c = text.charAt(i);
      char next = i + 1 < n ? text.charAt(i + 1) : 0;
      if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
        i++;
      } else if (c == '/' && next == '/') {
        i += 2;
        while (i < n && text.charAt(i) != '\n' && text.charAt(i) != '\r') {
          i++;
        }
      } else if (c == '/' && next == '*') {
        int end = text.indexOf("*/", i + 2);
        if (end < 0) {
          throw new Unreadable("A /* comment isn't closed.");
        }
        i = end + 2;
      } else if (c == '\'' || c == '"') {
        int j = i + 1;
        while (j < n && text.charAt(j) != c) {
          j += text.charAt(j) == '\\' ? 2 : 1;
        }
        if (j >= n) {
          throw new Unreadable("A string opened with " + c + " isn't closed.");
        }
        out.add(new Token(T.STRING, text.substring(i + 1, j)));
        i = j + 1;
      } else if (c == '`') {
        int[] end = new int[1];
        out.add(new Token(T.NAME, quoted(text, i, end)));
        i = end[0];
      } else if (c == '$') {
        int j = i + 1;
        if (j < n && text.charAt(j) == '`') {
          int[] end = new int[1];
          out.add(new Token(T.PARAM, quoted(text, j, end)));
          i = end[0];
        } else {
          while (j < n && identifierPart(text.codePointAt(j))) {
            j += Character.charCount(text.codePointAt(j));
          }
          if (j == i + 1) {
            throw new Unreadable("A $ isn't followed by a parameter name.");
          }
          out.add(new Token(T.PARAM, text.substring(i + 1, j)));
          i = j;
        }
      } else if (c >= '0' && c <= '9') {
        i = number(text, i);
        out.add(new Token(T.NUMBER, ""));
      } else if (identifierStart(text.codePointAt(i))) {
        int j = i;
        while (j < n && identifierPart(text.codePointAt(j))) {
          j += Character.charCount(text.codePointAt(j));
        }
        String word = text.substring(i, j);
        checkLetters(word);
        out.add(new Token(T.WORD, word));
        i = j;
      } else if (c == '\\') {
        throw new Unreadable("The statement holds a backslash outside a string.");
      } else if (SYMBOLS.indexOf(c) >= 0) {
        out.add(new Token(T.SYMBOL, String.valueOf(c)));
        i++;
      } else {
        throw new Unreadable("The statement holds a character Cypher doesn't use outside strings (U+"
            + String.format(Locale.ROOT, "%04X", text.codePointAt(i)) + ").");
      }
    }
    return out;
  }

  /** A backticked name from {@code at}; {@code end[0]} is the index after it. Doubled backticks are one. */
  private static String quoted(String text, int at, int[] end) throws Unreadable {
    StringBuilder name = new StringBuilder();
    int j = at + 1;
    while (j < text.length()) {
      char c = text.charAt(j);
      if (c == '`') {
        if (j + 1 < text.length() && text.charAt(j + 1) == '`') {
          name.append('`');
          j += 2;
          continue;
        }
        end[0] = j + 1;
        return name.toString();
      }
      name.append(c);
      j++;
    }
    throw new Unreadable("A name opened with ` isn't closed.");
  }

  /** The end of a number from {@code at}: digits, letters and underscores ({@code 0x1F}, {@code 1_000}), a fraction. */
  private static int number(String text, int at) {
    int n = text.length();
    int j = at;
    while (j < n && asciiWordChar(text.charAt(j))) {
      j++;
    }
    if (j + 1 < n && text.charAt(j) == '.' && text.charAt(j + 1) >= '0' && text.charAt(j + 1) <= '9') {
      j++;
      while (j < n && asciiWordChar(text.charAt(j))) {
        j++;
      }
    }
    char last = text.charAt(j - 1);
    if ((last == 'e' || last == 'E') && j + 1 < n && (text.charAt(j) == '+' || text.charAt(j) == '-')
        && text.charAt(j + 1) >= '0' && text.charAt(j + 1) <= '9') {
      j++;
      while (j < n && asciiWordChar(text.charAt(j))) {
        j++;
      }
    }
    return j;
  }

  private static boolean asciiWordChar(char c) {
    return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
  }

  private static boolean identifierStart(int cp) {
    return cp == '_' || Character.isLetter(cp);
  }

  private static boolean identifierPart(int cp) {
    return cp == '_' || Character.isLetterOrDigit(cp);
  }

  /**
   * A word whose letters could be read as other letters: one with a letter that isn't ASCII but is one ASCII letter
   * in another case ({@code ſ} → S, {@code ı} → I, the Kelvin sign → k), or whose NFKC form differs (fullwidth
   * letters, ligatures). A parser that folds case or normalizes text would read a keyword where this reader reads a
   * name. ({@code ß} → SS stays a name: no keyword outside the refused administration commands holds SS.)
   */
  private static void checkLetters(String word) throws Unreadable {
    boolean ascii = true;
    for (int k = 0; k < word.length(); k++) {
      char c = word.charAt(k);
      if (c > 127) {
        ascii = false;
        String s = String.valueOf(c);
        if (oneAscii(s.toUpperCase(Locale.ROOT)) || oneAscii(s.toLowerCase(Locale.ROOT))) {
          throw new Unreadable(word + " holds a letter that reads as an ASCII letter in another case. Write the name "
              + "in backticks.");
        }
      }
    }
    if (!ascii && !Normalizer.normalize(word, Normalizer.Form.NFKC).equals(word)) {
      throw new Unreadable(word + " holds letters that stand for other letters. Write the name in backticks.");
    }
  }

  private static boolean oneAscii(String s) {
    return s.length() == 1 && s.charAt(0) < 128;
  }

  // ── Analysis ────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * Whether the word at {@code i} is a keyword here: not a property ({@code n.call}, {@code n {.set}}), not a label or
   * a type ({@code :Load}), not a map key or a variable with a label ({@code {use: 1}}, {@code (call:Event)}), and not
   * the variable of a property access ({@code load.size}).
   */
  private static boolean keyword(List<Token> t, int i) {
    if (t.get(i).type() != T.WORD) {
      return false;
    }
    if (i > 0 && (t.get(i - 1).symbol('.') || t.get(i - 1).symbol(':'))) {
      return false;
    }
    return !(i + 1 < t.size() && (t.get(i + 1).symbol(':') || t.get(i + 1).symbol('.')));
  }

  private static Statement analyze(List<Token> t) {
    for (Token token : t) {
      if (token.symbol(';')) {
        return forbidden("multiple-statements", "GateRook runs one statement per call, without a semicolon.",
            "Leave out the semicolon; send each statement as its own call.");
      }
    }
    boolean schema = false;
    Token first = t.getFirst();
    if (first.type() == T.WORD) {
      String w = first.upper();
      if (w.equals("EXPLAIN") || w.equals("PROFILE") || w.equals("CYPHER")) {
        return forbidden("query-options", w + " options don't run here: GateRook plans the statement with EXPLAIN "
            + "itself.", "Send the statement without " + w + ".");
      }
      if (w.equals("SHOW")) {
        return forbidden("admin", "SHOW commands don't run here.", "neo4j_schema lists labels, relationship types, "
            + "property keys, indexes and constraints; the other SHOW commands administer the server and stay with "
            + "people.");
      }
      if (ADMIN_START.contains(w)) {
        return admin(w);
      }
      if (w.equals("CREATE") || w.equals("DROP")) {
        int k = 1;
        while (k < t.size() && (t.get(k).word("OR") || t.get(k).word("REPLACE"))) {
          k++;
        }
        Token object = k < t.size() ? t.get(k) : null;
        if (object != null && object.type() == T.WORD && ADMIN_OBJECTS.contains(object.upper())) {
          return admin(w + " " + object.upper());
        }
        if (w.equals("DROP") || (object != null && object.type() == T.WORD
            && SCHEMA_OBJECTS.contains(object.upper()))) {
          schema = true;
        }
      }
    }
    Set<String> writes = new LinkedHashSet<>();
    List<String> procedures = new ArrayList<>();
    for (int i = 0; i < t.size(); i++) {
      Token token = t.get(i);
      if (keyword(t, i)) {
        String w = token.upper();
        if (w.equals("USE")) {
          return forbidden("use", "USE doesn't run here: every call uses the connection's database.",
              "Leave out USE. If use is a name here, write it in backticks.");
        }
        if (w.equals("LOAD")) {
          return forbidden("load-csv", "LOAD CSV doesn't run here: it makes the server read files and URLs.",
              "Pass the values as parameters instead. If load is a name here, write it in backticks.");
        }
        if (w.equals("TRANSACTIONS") || w.equals("PERIODIC")) {
          return forbidden("in-transactions", "CALL { … } IN TRANSACTIONS doesn't run here: it commits on its own, "
              + "outside the one transaction GateRook runs and a person approves.", "Send one statement that runs "
              + "in a single transaction.");
        }
        if (ADMIN_ANYWHERE.contains(w)) {
          return admin(w);
        }
        if (WRITE_CLAUSES.contains(w) && !(schema && i == 0)) {
          writes.add(w.equals("NODETACH") ? "DELETE" : w);
        }
        if (w.equals("CALL")) {
          Token after = i + 1 < t.size() ? t.get(i + 1) : null;
          if (after != null && (after.symbol('{') || after.symbol('('))) {
            continue;
          }
          if (after == null || !after.namePart()) {
            return unreadable("CALL is followed neither by a procedure name nor by a subquery.");
          }
          List<String> parts = new ArrayList<>();
          int j = name(t, i + 1, parts);
          String procedure = String.join(".", parts);
          Statement refused = procedure(procedure);
          if (refused != null) {
            return refused;
          }
          procedures.add(procedure);
          i = j - 1;
          continue;
        }
      }
      if (token.namePart() && !(i > 0 && t.get(i - 1).symbol('.'))
          && !(i > 0 && keyword(t, i - 1) && t.get(i - 1).word("CALL"))) {
        List<String> parts = new ArrayList<>();
        int j = name(t, i, parts);
        if (j < t.size() && t.get(j).symbol('(') && (parts.size() > 1 || parts.getFirst().contains("."))) {
          Statement refused = function(String.join(".", parts));
          if (refused != null) {
            return refused;
          }
        }
      }
    }
    if (schema) {
      return new Statement(Kind.SCHEMA, null, null, null, List.copyOf(writes), List.copyOf(procedures));
    }
    return new Statement(writes.isEmpty() ? Kind.READ : Kind.WRITE, null, null, null, List.copyOf(writes),
        List.copyOf(procedures));
  }

  private static Statement admin(String what) {
    return forbidden("admin", what + " administers users, roles, privileges, databases, servers or transactions, "
        + "which stays with people.", "Ask a person to run it in Neo4j's own tools.");
  }

  /** A dotted name from {@code at} into {@code parts}; the index after it. */
  private static int name(List<Token> t, int at, List<String> parts) {
    int j = at;
    parts.add(t.get(j).text());
    j++;
    while (j + 1 < t.size() && t.get(j).symbol('.') && t.get(j + 1).namePart()) {
      parts.add(t.get(j + 1).text());
      j += 2;
    }
    return j;
  }

  /** {@code null} when an agent may call the procedure, else the refusal. */
  static Statement procedure(String name) {
    if (PROCEDURES.contains(name) || (name.startsWith(SCHEMA_PROCEDURES)
        && name.length() > SCHEMA_PROCEDURES.length() && name.indexOf('.', SCHEMA_PROCEDURES.length()) < 0)) {
      return null;
    }
    String lower = name.toLowerCase(Locale.ROOT);
    String why;
    if (lower.startsWith("dbms.")) {
      why = "dbms procedures administer the server.";
    } else if (lower.startsWith("apoc.load.") || lower.startsWith("apoc.import.")) {
      why = "it makes the server read files and URLs.";
    } else if (lower.startsWith("apoc.export.")) {
      why = "it makes the server write files.";
    } else if (lower.startsWith("apoc.periodic.")) {
      why = "it runs Cypher in transactions of its own.";
    } else if (lower.startsWith("apoc.cypher.") || lower.startsWith("apoc.do.") || lower.equals("apoc.when")
        || lower.equals("apoc.case")) {
      why = "it runs text as Cypher, which GateRook can't read first.";
    } else if (lower.startsWith("apoc.trigger.")) {
      why = "it installs triggers that run later.";
    } else if (lower.startsWith("apoc.systemdb.")) {
      why = "it runs commands on the system database.";
    } else if (lower.startsWith("gds.")) {
      why = "Graph Data Science procedures project, mutate and write graphs.";
    } else {
      why = "GateRook calls only the read-only schema procedures.";
    }
    return forbidden("procedure", "CALL " + name + " doesn't run here: " + why, "Agents may call db.labels, "
        + "db.relationshipTypes, db.propertyKeys and db.schema.*; neo4j_schema shows the schema.");
  }

  /** {@code null} when an agent may call the function, else the refusal. */
  static Statement function(String name) {
    String lower = name.toLowerCase(Locale.ROOT);
    int dot = lower.lastIndexOf('.');
    String namespace = dot < 0 ? "" : lower.substring(0, dot);
    if (BUILT_IN_NAMESPACES.contains(namespace) || BUILT_IN_FUNCTIONS.contains(lower)) {
      return null;
    }
    if (lower.startsWith("apoc.")) {
      for (String refused : APOC_REFUSED) {
        if (lower.startsWith(refused)) {
          return forbidden("function", name + " doesn't run here: it runs Cypher text, reads files, URLs or "
              + "settings, or touches triggers or the system database.", "Write the Cypher itself; pass values as "
              + "parameters.");
        }
      }
      return null;
    }
    return forbidden("function", name + " isn't one of the functions GateRook runs: Cypher's built-in functions "
        + "and APOC's.", "Use a built-in function, or ask a person to run it.");
  }
}
