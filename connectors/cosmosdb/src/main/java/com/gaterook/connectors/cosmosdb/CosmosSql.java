package com.gaterook.connectors.cosmosdb;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * One Cosmos DB SQL query as GateRook sends it: the text, checked for size and for being a {@code SELECT}, and its
 * {@code @name} parameters. Cosmos DB's query language only reads, so a query can't change anything; what this class
 * finds out is whether its result may be paged through the REST gateway.
 *
 * <p>The gateway pages a query by Cosmos DB's continuation tokens, which keep no state of the query itself (REST API:
 * "Queries that cannot be served by gateway"). {@code TOP}, {@code ORDER BY}, {@code OFFSET}/{@code LIMIT},
 * {@code DISTINCT}, {@code GROUP BY} and aggregate functions need such state: across partitions the gateway refuses
 * them, and in one partition a page of them is only exact while it is the whole result. {@link #shape} looks for these
 * words outside string literals, by their own tokens and conservatively — a word in a subquery or in a comment counts
 * too —, so that a query that may need state is never continued page by page.
 */
final class CosmosSql {

  /** The longest query text agents may send (Cosmos DB takes up to 512 KB). */
  static final int MAX_LENGTH = 32_768;
  static final int MAX_PARAMETERS = 100;
  static final Pattern PARAMETER = Pattern.compile("@[A-Za-z_][A-Za-z0-9_]{0,127}");
  private static final Set<String> CLAUSES = Set.of("TOP", "ORDER", "OFFSET", "LIMIT", "DISTINCT", "GROUP");
  private static final Set<String> AGGREGATES = Set.of("COUNT", "SUM", "MIN", "MAX", "AVG", "COUNTIF", "MAKELIST",
      "MAKESET", "DCOUNT");
  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  private CosmosSql() {}

  /**
   * What a query holds that the gateway can't continue page by page.
   *
   * @param features   the words found, in order: {@code TOP}, {@code ORDER BY}, {@code OFFSET}, {@code LIMIT},
   *                   {@code DISTINCT}, {@code GROUP BY}, {@code COUNT(…)} and other aggregates, or {@code comment}
   *                   and {@code unterminated} when the text can't be read token by token with certainty
   * @param aggregates whether an aggregate or {@code GROUP BY} is among them: a page of those may be a part of the
   *                   answer, not its beginning
   */
  record Shape(Set<String> features, boolean aggregates) {

    /** Whether the result may be continued with Cosmos DB's continuation token. */
    boolean pageable() {
      return features.isEmpty();
    }
  }

  /** The words of {@code query} that need state across pages. */
  static Shape shape(String query) {
    Set<String> found = new LinkedHashSet<>();
    boolean aggregates = false;
    int n = query.length();
    int i = 0;
    while (i < n) {
      char c = query.charAt(i);
      if (c == '\'' || c == '"') {
        int end = stringEnd(query, i);
        if (end < 0) {
          found.add("unterminated");
          break;
        }
        i = end + 1;
      } else if (c == '-' && i + 1 < n && query.charAt(i + 1) == '-') {
        found.add("comment");
        int nl = query.indexOf('\n', i);
        i = nl < 0 ? n : nl + 1;
      } else if (c == '/' && i + 1 < n && query.charAt(i + 1) == '*') {
        found.add("comment");
        int end = query.indexOf("*/", i + 2);
        if (end < 0) {
          found.add("unterminated");
          break;
        }
        i = end + 2;
      } else if (identifierStart(c)) {
        int start = i;
        while (i < n && identifierPart(query.charAt(i))) {
          i++;
        }
        char before = start == 0 ? ' ' : query.charAt(start - 1);
        if (before == '.' || before == '@') {
          continue; // a property of an object, or a parameter
        }
        String word = query.substring(start, i).toUpperCase(Locale.ROOT);
        if (CLAUSES.contains(word)) {
          found.add(word.equals("ORDER") ? "ORDER BY" : word.equals("GROUP") ? "GROUP BY" : word);
          aggregates |= word.equals("GROUP");
        } else if (AGGREGATES.contains(word) && nextIsParenthesis(query, i)) {
          found.add(word + "(…)");
          aggregates = true;
        }
      } else {
        i++;
      }
    }
    return new Shape(Collections.unmodifiableSet(found), aggregates || found.contains("unterminated")
        || found.contains("comment"));
  }

  /** The index of the quote that closes the string literal opened at {@code start}, or -1. */
  private static int stringEnd(String q, int start) {
    char quote = q.charAt(start);
    for (int i = start + 1; i < q.length(); i++) {
      char c = q.charAt(i);
      if (c == '\\') {
        i++;
      } else if (c == quote) {
        return i;
      }
    }
    return -1;
  }

  private static boolean nextIsParenthesis(String q, int from) {
    for (int i = from; i < q.length(); i++) {
      char c = q.charAt(i);
      if (!Character.isWhitespace(c)) {
        return c == '(';
      }
    }
    return false;
  }

  private static boolean identifierStart(char c) {
    return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_';
  }

  private static boolean identifierPart(char c) {
    return identifierStart(c) || (c >= '0' && c <= '9');
  }

  /** Why {@code query} can't be sent, or {@code null}. */
  static String problem(String query) {
    if (query == null || query.isBlank()) {
      return "The query is empty: write one Cosmos DB SQL SELECT, such as SELECT * FROM c WHERE c.status = @status.";
    }
    if (query.length() > MAX_LENGTH) {
      return "The query is longer than " + MAX_LENGTH + " characters.";
    }
    for (int i = 0; i < query.length(); i++) {
      char c = query.charAt(i);
      if ((c < 0x20 && c != '\t' && c != '\n' && c != '\r') || c == 0x7f) {
        return "The query holds a control character.";
      }
    }
    String first = firstWord(query);
    if (!"SELECT".equals(first)) {
      return "Cosmos DB's query language only reads: a query starts with SELECT. Change items with cosmosdb_execute.";
    }
    return null;
  }

  /** The first word of the query outside comments, in upper case, or {@code null}. */
  private static String firstWord(String q) {
    int i = 0;
    while (i < q.length()) {
      char c = q.charAt(i);
      if (Character.isWhitespace(c) || c == '(') {
        i++;
      } else if (q.startsWith("--", i)) {
        int nl = q.indexOf('\n', i);
        i = nl < 0 ? q.length() : nl + 1;
      } else if (q.startsWith("/*", i)) {
        int end = q.indexOf("*/", i + 2);
        if (end < 0) {
          return null;
        }
        i = end + 2;
      } else {
        int start = i;
        while (i < q.length() && identifierPart(q.charAt(i))) {
          i++;
        }
        return q.substring(start, i).toUpperCase(Locale.ROOT);
      }
    }
    return null;
  }

  /**
   * The parameters of a query in the REST API's shape, {@code [{"name": "@id", "value": "c-42"}]}, from the agent's
   * object {@code {"@id": "c-42"}}.
   */
  static ArrayNode parameters(JsonNode given) throws Refusal {
    ArrayNode out = F.arrayNode();
    if (given == null || given.isMissingNode() || given.isNull()) {
      return out;
    }
    if (!given.isObject()) {
      throw Refusal.bad("parameters is an object of @name values, such as {\"@status\": \"paid\"}.", null);
    }
    if (given.size() > MAX_PARAMETERS) {
      throw Refusal.bad("A query takes at most " + MAX_PARAMETERS + " parameters.", null);
    }
    for (Map.Entry<String, JsonNode> e : given.properties()) {
      if (!PARAMETER.matcher(e.getKey()).matches()) {
        throw Refusal.bad("The parameter " + CosmosIds.shown(e.getKey()) + " isn't a Cosmos DB parameter name: "
            + "@ and a name, such as @status.", null);
      }
      String problem = CosmosValues.problem(e.getValue());
      if (problem != null) {
        throw Refusal.bad("The value of " + e.getKey() + " " + problem + ".", null);
      }
      out.addObject().put("name", e.getKey()).set("value", e.getValue());
    }
    return out;
  }
}
