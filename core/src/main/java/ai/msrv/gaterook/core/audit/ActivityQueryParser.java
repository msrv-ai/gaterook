package ai.msrv.gaterook.core.audit;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.audit.internal.persistence.CallDecision;
import ai.msrv.gaterook.core.audit.internal.persistence.CallPhase;
import ai.msrv.gaterook.core.audit.internal.persistence.ToolCallEntity;
import ai.msrv.gaterook.core.platform.KebabCaseEnumConverter;
import ai.msrv.gaterook.core.platform.ProblemException;
import jakarta.persistence.criteria.AbstractQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.data.jpa.domain.Specification;

/**
 * The query language of Activity ({@code log-explorer}, ARCHITECTURE.md §6 activity): {@code field:value} filters,
 * the same field again means OR, different fields AND, a leading {@code -} excludes, {@code *} is a wildcard,
 * {@code duration:>1s} compares. Fields: {@code decision tool client endpoint connection env token rule runbook trace
 * duration}; {@code trace} matches a prefix ("a prefix is enough"). A word without a field matches tool, connection,
 * endpoint, client or rule. Values may be quoted ({@code tool:"sql_*"}).
 *
 * <p>The result is a {@link Specification} over {@code tool_call}; it filters calls by their latest row (an approved
 * call is {@code approved}, not the {@code pending} it was). {@code runbook} is a fact of the request: it matches the
 * call's {@code received} row, the only one that keeps the facets.
 */
public final class ActivityQueryParser {

  /** The fields of the language. */
  public static final Set<String> FIELDS = Set.of("decision", "tool", "client", "endpoint", "connection", "env",
      "token", "rule", "runbook", "trace", "duration");

  private static final Pattern TOKEN = Pattern.compile("(-?)(?:([a-z]+):)?(\"[^\"]*\"|\\S+)");
  private static final Pattern DURATION = Pattern.compile("(>=|<=|>|<|=)?(\\d+(?:\\.\\d+)?)(ms|s|m|min|h)?");

  /** One field of the query: positive values (any matches) and excluded ones (none may match). */
  public record Clause(String field, List<String> values, List<String> excluded) {}

  /** A parsed query; {@link #toString()} prints it back in canonical order. */
  public record Query(List<Clause> clauses, List<String> words) {

    public boolean isEmpty() {
      return clauses.isEmpty() && words.isEmpty();
    }

    public Specification<ToolCallEntity> specification() {
      return this::predicate;
    }

    /**
     * The query over the {@code tool_call} rows of {@code root}, in a query or a subquery: {@code query} makes the
     * subqueries a field needs ({@code runbook}).
     */
    public Predicate predicate(Root<ToolCallEntity> root, AbstractQuery<?> query, CriteriaBuilder cb) {
      List<Predicate> all = new ArrayList<>();
      for (Clause c : clauses) {
        if (!c.values().isEmpty()) {
          List<Predicate> any = new ArrayList<>();
          for (String v : c.values()) {
            any.add(match(c.field(), v, root, query, cb));
          }
          all.add(cb.or(any.toArray(Predicate[]::new)));
        }
        for (String v : c.excluded()) {
          Predicate m = match(c.field(), v, root, query, cb);
          Path<?> column = column(c.field(), root);
          // NOT over a NULL column is NULL in SQL: a call without a rule still is "not prod.sql.block_ddl".
          all.add(column == null ? cb.not(m) : cb.or(cb.isNull(column), cb.not(m)));
        }
      }
      for (String w : words) {
        List<Predicate> any = new ArrayList<>();
        for (String field : List.of("tool", "connection", "endpoint", "client", "rule")) {
          any.add(match(field, w.contains("*") ? w : "*" + w + "*", root, query, cb));
        }
        all.add(cb.or(any.toArray(Predicate[]::new)));
      }
      return cb.and(all.toArray(Predicate[]::new));
    }

    @Override
    public String toString() {
      List<String> parts = new ArrayList<>();
      for (Clause c : clauses) {
        c.values().forEach(v -> parts.add(c.field() + ":" + quote(v)));
        c.excluded().forEach(v -> parts.add("-" + c.field() + ":" + quote(v)));
      }
      words.forEach(w -> parts.add(quote(w)));
      return String.join(" ", parts);
    }

    private static String quote(String v) {
      return v.matches("\\S+") ? v : "\"" + v + "\"";
    }
  }

  private ActivityQueryParser() {}

  /**
   * @throws ProblemException 422 on an unknown field or a value the field can't take, pointing at {@code /q}
   */
  public static Query parse(String q) {
    Map<String, List<String>> values = new LinkedHashMap<>();
    Map<String, List<String>> excluded = new LinkedHashMap<>();
    List<String> words = new ArrayList<>();
    if (q != null) {
      Matcher m = TOKEN.matcher(q.strip());
      while (m.find()) {
        boolean negated = !m.group(1).isEmpty();
        String field = m.group(2);
        String value = unquote(m.group(3));
        if (value.isEmpty()) {
          continue;
        }
        if (field == null) {
          words.add(value);
          continue;
        }
        if (!FIELDS.contains(field)) {
          throw ProblemException.unprocessable("Unknown filter", field + " isn't a filter. Use one of: "
              + String.join(", ", FIELDS.stream().sorted().toList()) + ".", "/q");
        }
        check(field, value);
        (negated ? excluded : values).computeIfAbsent(field, k -> new ArrayList<>()).add(value);
      }
    }
    List<Clause> clauses = new ArrayList<>();
    for (String field : FIELDS.stream().sorted().toList()) {
      List<String> v = values.getOrDefault(field, List.of());
      List<String> x = excluded.getOrDefault(field, List.of());
      if (!v.isEmpty() || !x.isEmpty()) {
        clauses.add(new Clause(field, List.copyOf(v), List.copyOf(x)));
      }
    }
    return new Query(List.copyOf(clauses), List.copyOf(words));
  }

  private static void check(String field, String value) {
    switch (field) {
      case "decision" -> {
        if (decision(value) == null) {
          throw ProblemException.unprocessable("Unknown decision", value + " isn't a decision. Use allowed, "
              + "approved, pending, denied, rejected, expired or error.", "/q");
        }
      }
      case "env" -> {
        try {
          Env.of(value);
        } catch (IllegalArgumentException e) {
          throw ProblemException.unprocessable("Unknown environment", value + " isn't dev, stage or prod.", "/q");
        }
      }
      case "duration" -> durationMs(value);
      default -> {
        // Any text: names, rule ids, trace prefixes.
      }
    }
  }

  static CallDecision decision(String value) {
    for (CallDecision d : CallDecision.values()) {
      if (KebabCaseEnumConverter.idOf(d).equals(value.toLowerCase(Locale.ROOT))) {
        return d;
      }
    }
    return null;
  }

  /** {@code >1s} → {@code [">", 1000]}. */
  static Object[] durationMs(String value) {
    Matcher m = DURATION.matcher(value);
    if (!m.matches()) {
      throw ProblemException.unprocessable("Invalid duration", "Write a duration like >1s, <200ms or >=2m.", "/q");
    }
    double n = Double.parseDouble(m.group(2));
    String unit = m.group(3) == null ? "ms" : m.group(3);
    double ms = switch (unit) {
      case "s" -> n * 1_000;
      case "m", "min" -> n * 60_000;
      case "h" -> n * 3_600_000;
      default -> n;
    };
    return new Object[] {m.group(1) == null ? "=" : m.group(1), Math.round(ms)};
  }

  private static Path<?> column(String field, Root<ToolCallEntity> root) {
    return switch (field) {
      case "decision" -> root.get("decision");
      case "tool" -> root.get("toolName");
      case "client" -> root.get("client");
      case "endpoint" -> root.get("endpointSlug");
      case "connection" -> root.get("connectionName");
      case "env" -> root.get("env");
      case "token" -> root.get("tokenLabel");
      case "rule" -> root.get("ruleId");
      case "trace" -> root.get("traceId");
      case "duration" -> root.get("durationMs");
      default -> null;
    };
  }

  @SuppressWarnings("unchecked")
  private static Predicate match(String field, String value, Root<ToolCallEntity> root, AbstractQuery<?> query,
      CriteriaBuilder cb) {
    switch (field) {
      case "decision" -> {
        return cb.equal(root.get("decision"), decision(value));
      }
      case "env" -> {
        return cb.equal(root.get("env"), Env.of(value));
      }
      case "duration" -> {
        Object[] d = durationMs(value);
        Expression<Long> ms = root.get("durationMs");
        long limit = (Long) d[1];
        return switch ((String) d[0]) {
          case ">" -> cb.greaterThan(ms, limit);
          case ">=" -> cb.greaterThanOrEqualTo(ms, limit);
          case "<" -> cb.lessThan(ms, limit);
          case "<=" -> cb.lessThanOrEqualTo(ms, limit);
          default -> cb.equal(ms, limit);
        };
      }
      case "trace" -> {
        return cb.like(root.get("traceId"), escape(value.toLowerCase(Locale.ROOT)).replace("*", "%") + "%", '\\');
      }
      case "runbook" -> {
        return root.get("callId").in(requestsWith(query, cb, "runbook", value, true));
      }
      default -> {
        Expression<String> column = (Expression<String>) column(field, root);
        if (value.contains("*")) {
          return cb.like(column, escape(value).replace("*", "%"), '\\');
        }
        return cb.equal(column, value);
      }
    }
  }

  /**
   * The ids of the calls whose request (the {@code received} row, the only one with facets) has the facet {@code key}
   * with {@code value} ({@code *} a wildcard when {@code wildcards}): {@code facets} is a JSON column whose text holds
   * {@code "key":"value"}.
   */
  static Subquery<String> requestsWith(AbstractQuery<?> query, CriteriaBuilder cb, String key, String value,
      boolean wildcards) {
    Subquery<String> calls = query.subquery(String.class);
    Root<ToolCallEntity> received = calls.from(ToolCallEntity.class);
    return calls.select(received.get("callId")).where(cb.equal(received.get("phase"), CallPhase.RECEIVED),
        hasFacet(received, cb, key, value, wildcards));
  }

  /**
   * Whether the request of {@code call}'s call has the facet {@code key} with exactly {@code value}: correlated by the
   * call's id, so a query that walks rows newest first by an index stops at the first match instead of reading the
   * facets of every request in the audit first ({@link AuditQueries#lastRun}).
   */
  static Predicate requestHas(Root<ToolCallEntity> call, AbstractQuery<?> query, CriteriaBuilder cb, String key,
      String value) {
    Subquery<Long> request = query.subquery(Long.class);
    Root<ToolCallEntity> received = request.from(ToolCallEntity.class);
    return cb.exists(request.select(received.get("id")).where(cb.equal(received.get("callId"), call.get("callId")),
        cb.equal(received.get("phase"), CallPhase.RECEIVED), hasFacet(received, cb, key, value, false)));
  }

  /** {@code facets} is a JSON column whose text holds {@code "key":"value"}, quotes and backslashes JSON-escaped. */
  private static Predicate hasFacet(Root<ToolCallEntity> received, CriteriaBuilder cb, String key, String value,
      boolean wildcards) {
    Expression<String> facets = received.get("facets").as(String.class);
    String pattern = escape(value.replace("\\", "\\\\").replace("\"", "\\\""));
    return cb.like(facets, "%\"" + key + "\":\"" + (wildcards ? pattern.replace("*", "%") : pattern) + "\"%", '\\');
  }

  private static String escape(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private static String unquote(String v) {
    return v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"") ? v.substring(1, v.length() - 1) : v;
  }
}
