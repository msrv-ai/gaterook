package com.gaterook.connectors.dynamodb;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What one PartiQL statement for DynamoDB does, without I/O: its kind and the table it names. DynamoDB runs
 * SELECT, INSERT, UPDATE and DELETE, one statement per request, no joins or subqueries. The text is read by
 * PartiQL's tokens: {@code '…'} strings and {@code "…"} names, each with its quote doubled inside. A comment, a
 * semicolon, a control character or a quote left open is not read at all: the statement is refused rather than
 * analyzed differently from DynamoDB.
 */
final class PartiQl {

  /** What a statement does. */
  enum Kind { SELECT, INSERT, UPDATE, DELETE, OTHER }

  /**
   * One statement.
   *
   * @param table   the table it reads or changes, unquoted; {@code null} when it names none
   * @param problem why it can't be analyzed, in words for the agent; {@code null} when it can
   */
  record Statement(Kind kind, String table, String problem) {

    boolean analyzed() {
      return problem == null;
    }
  }

  static final int MAX_LENGTH = 8_192;
  /** DynamoDB's table names. */
  static final Pattern TABLE = Pattern.compile("[A-Za-z0-9_.-]{3,255}");

  private PartiQl() {}

  static Statement read(String text) {
    if (text == null || text.isBlank()) {
      return new Statement(Kind.OTHER, null, "The statement is empty.");
    }
    if (text.length() > MAX_LENGTH) {
      return new Statement(Kind.OTHER, null, "The statement is longer than " + MAX_LENGTH + " characters.");
    }
    // Code with every string and name blanked, and the names in order.
    StringBuilder code = new StringBuilder(text.length());
    java.util.List<String> names = new java.util.ArrayList<>();
    java.util.List<Integer> nameAt = new java.util.ArrayList<>();
    int i = 0;
    while (i < text.length()) {
      char c = text.charAt(i);
      if (c < 0x20 && c != '\n' && c != '\r' && c != '\t') {
        return new Statement(Kind.OTHER, null, "The statement holds a control character.");
      }
      if (c == '\'' || c == '"') {
        int j = i + 1;
        StringBuilder value = new StringBuilder();
        boolean closed = false;
        while (j < text.length()) {
          if (text.charAt(j) == c) {
            if (j + 1 < text.length() && text.charAt(j + 1) == c) {
              value.append(c);
              j += 2;
              continue;
            }
            closed = true;
            j++;
            break;
          }
          value.append(text.charAt(j++));
        }
        if (!closed) {
          return new Statement(Kind.OTHER, null, "A quote isn't closed.");
        }
        if (c == '"') {
          names.add(value.toString());
          nameAt.add(code.length());
          code.append(" \u0001 ");
        } else {
          code.append(" ' ");
        }
        i = j;
        continue;
      }
      if ((c == '-' && i + 1 < text.length() && text.charAt(i + 1) == '-')
          || (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*')) {
        return new Statement(Kind.OTHER, null, "Comments aren't analyzed: send the statement without them.");
      }
      if (c == ';') {
        return new Statement(Kind.OTHER, null, "DynamoDB runs one statement per call: leave out the semicolon.");
      }
      code.append(c);
      i++;
    }
    String[] words = code.toString().strip().split("[\\s(]+");
    if (words.length == 0 || words[0].isEmpty()) {
      return new Statement(Kind.OTHER, null, "The statement has no keyword.");
    }
    Kind kind = switch (words[0].toUpperCase(Locale.ROOT)) {
      case "SELECT" -> Kind.SELECT;
      case "INSERT" -> Kind.INSERT;
      case "UPDATE" -> Kind.UPDATE;
      case "DELETE" -> Kind.DELETE;
      default -> Kind.OTHER;
    };
    if (kind == Kind.OTHER) {
      return new Statement(Kind.OTHER, null, words[0] + " isn't a statement DynamoDB's PartiQL runs here.");
    }
    String table = table(code.toString(), kind, names, nameAt);
    if (table == null) {
      return new Statement(kind, null, "The gateway couldn't find the table this " + kind + " uses.");
    }
    if (!TABLE.matcher(table).matches()) {
      return new Statement(kind, table, table + " isn't a DynamoDB table name.");
    }
    return new Statement(kind, table, null);
  }

  /**
   * The name after {@code FROM} (SELECT, DELETE), {@code INTO} (INSERT) or the statement's first word (UPDATE): quoted
   * as {@code "Orders"} or bare; for {@code "Orders"."ByCustomer"} the table, not the index.
   */
  private static String table(String code, Kind kind, java.util.List<String> names, java.util.List<Integer> nameAt) {
    String keyword = switch (kind) {
      case SELECT, DELETE -> "FROM";
      case INSERT -> "INTO";
      case UPDATE -> "UPDATE";
      case OTHER -> null;
    };
    java.util.regex.Matcher m = Pattern.compile("(?i)(?<![A-Za-z0-9_])" + keyword + "(?![A-Za-z0-9_])\\s*")
        .matcher(code);
    if (!m.find()) {
      return null;
    }
    int at = m.end();
    if (at < code.length() && code.charAt(at) == '\u0001') {
      for (int k = 0; k < nameAt.size(); k++) {
        if (nameAt.get(k) + 1 == at) {
          return names.get(k);
        }
      }
      return null;
    }
    int end = at;
    while (end < code.length() && (Character.isLetterOrDigit(code.charAt(end)) || code.charAt(end) == '_'
        || code.charAt(end) == '-')) {
      end++;
    }
    return end > at ? code.substring(at, end) : null;
  }
}
