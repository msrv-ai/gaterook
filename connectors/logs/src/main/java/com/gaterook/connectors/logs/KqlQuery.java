package com.gaterook.connectors.logs;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a KQL query of Azure Monitor Logs reads beyond the workspaces and components of its connection, judged from its
 * text without I/O, as {@link InsightsQuery} does for CloudWatch's {@code SOURCE}:
 * <ul>
 *   <li>the cross-resource functions {@code workspace()}, {@code app()}, {@code resource()}, {@code adx()},
 *       {@code arg()} and Data Explorer's {@code cluster()}, {@code database()}, {@code external_table()},
 *       {@code materialized_view()}, {@code entity_group()}, {@code stored_query_result()};</li>
 *   <li>{@code externaldata}, which makes the service fetch a URL, and the plugins that call other services
 *       ({@code http_request}, {@code sql_request}, {@code cosmosdb_sql_request}, …), {@code macro-expand};</li>
 *   <li>control commands (a statement starting with {@code .}).</li>
 * </ul>
 * The text is read as it is, with every {@code //} comment removed, and with the characters that draw as nothing
 * removed; any reading finding one refuses the query. A function counts as called when its name is followed by
 * {@code (}, after optional spaces, or appears quoted as {@code ['name']}: a column called {@code app} stays fine. A
 * name inside a string is refused too: whether text is a string is the service's grammar, and the check errs on the
 * side of refusing.
 */
final class KqlQuery {

  /** What the query reaches, as the last segment of the policy rule ({@code <env>.logs.<rule>}). */
  enum Reach {
    CROSS_RESOURCE("kql_cross_resource"), EXTERNAL("kql_external_data"), COMMAND("kql_command");

    final String rule;

    Reach(String rule) {
      this.rule = rule;
    }
  }

  /** A refused query: what it reaches and the word that does. */
  record Refused(Reach reach, String word) {}

  static final Set<String> CROSS_RESOURCE = Set.of("workspace", "app", "resource", "adx", "arg", "cluster",
      "database", "external_table", "materialized_view", "entity_group", "stored_query_result");
  static final Set<String> EXTERNAL = Set.of("externaldata", "external_data", "http_request", "http_request_post",
      "sql_request", "cosmosdb_sql_request", "mysql_request", "postgresql_request",
      "azure_digital_twins_query_request", "ai_embeddings", "ai_chat_completion", "ai_chat_completion_prompt");

  private static final Pattern CALL = Pattern.compile("(?i)(?<![\\w$])([a-z_][a-z0-9_]*)\\s*\\(");
  private static final Pattern QUOTED = Pattern.compile("(?i)\\[\\s*(?:@)?['\"]\\s*([a-z_][a-z0-9_]*)\\s*['\"]\\s*\\]");
  private static final Pattern WORD = Pattern.compile("(?i)(?<![\\w$])([a-z_][a-z0-9_]*|macro-expand)(?![\\w$-])");
  private static final Pattern COMMENT = Pattern.compile("//[^\\r\\n]*");
  private static final Pattern COMMAND = Pattern.compile("(?:^|;)\\s*\\.[A-Za-z]");
  /** Operators after which rows are no longer log entries but a table. */
  private static final Pattern AGGREGATE = Pattern.compile("(?i)\\|\\s*(summarize|make-series|top-nested|distinct"
      + "|count|evaluate|render|reduce|top-hitters|facet|getschema)(?![\\w-])");

  private KqlQuery() {}

  /** The first way {@code query} reaches outside its sources, or empty. */
  static Optional<Refused> outside(String query) {
    for (String text : List.of(query, COMMENT.matcher(query).replaceAll(" "), withoutInvisible(query),
        COMMENT.matcher(withoutInvisible(query)).replaceAll(" "))) {
      if (COMMAND.matcher(text).find()) {
        return Optional.of(new Refused(Reach.COMMAND, "."));
      }
      Matcher calls = CALL.matcher(text);
      while (calls.find()) {
        String name = calls.group(1).toLowerCase(Locale.ROOT);
        if (CROSS_RESOURCE.contains(name)) {
          return Optional.of(new Refused(Reach.CROSS_RESOURCE, name));
        }
      }
      Matcher quoted = QUOTED.matcher(text);
      while (quoted.find()) {
        String name = quoted.group(1).toLowerCase(Locale.ROOT);
        if (CROSS_RESOURCE.contains(name) || EXTERNAL.contains(name)) {
          return Optional.of(new Refused(CROSS_RESOURCE.contains(name) ? Reach.CROSS_RESOURCE : Reach.EXTERNAL, name));
        }
      }
      Matcher words = WORD.matcher(text);
      while (words.find()) {
        String word = words.group(1).toLowerCase(Locale.ROOT);
        if (EXTERNAL.contains(word) || word.equals("macro-expand")) {
          return Optional.of(new Refused(Reach.EXTERNAL, word));
        }
      }
    }
    return Optional.empty();
  }

  /** Whether the query's rows are a table (summarize, count, …) rather than log entries. */
  static boolean aggregates(String query) {
    return AGGREGATE.matcher(COMMENT.matcher(query).replaceAll(" ")).find();
  }

  /** The text without what draws as nothing, as a service that skips those characters reads it. */
  private static String withoutInvisible(String text) {
    StringBuilder out = new StringBuilder(text.length());
    text.codePoints().filter(cp -> cp == '\n' || cp == '\r' || cp == '\t' || !invisible(cp))
        .forEach(out::appendCodePoint);
    return out.toString();
  }

  private static boolean invisible(int cp) {
    int type = Character.getType(cp);
    return type == Character.FORMAT || type == Character.CONTROL || type == Character.NON_SPACING_MARK
        || type == Character.ENCLOSING_MARK || type == Character.UNASSIGNED || type == Character.PRIVATE_USE
        || type == Character.SURROGATE || cp == 0x115F || cp == 0x1160 || cp == 0x3164 || cp == 0xFFA0;
  }
}
