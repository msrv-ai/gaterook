package ai.msrv.gaterook.core.audit;

import ai.msrv.gaterook.core.audit.internal.persistence.CallDecision;
import ai.msrv.gaterook.core.audit.internal.persistence.CallPhase;
import ai.msrv.gaterook.core.audit.internal.persistence.SetupChangeEntity;
import ai.msrv.gaterook.core.audit.internal.persistence.SetupChangeRepository;
import ai.msrv.gaterook.core.audit.internal.persistence.ToolCallEntity;
import ai.msrv.gaterook.core.audit.internal.persistence.ToolCallRepository;
import ai.msrv.gaterook.core.audit.internal.persistence.TraceEntity;
import ai.msrv.gaterook.core.audit.internal.persistence.TraceRepository;
import ai.msrv.gaterook.core.platform.KebabCaseEnumConverter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.AbstractQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the audit for Activity and the admin API (ARCHITECTURE.md §6 activity): calls by the query language,
 * one call's rows, traces, Setup changes. Read-only transactions on the reader pool.
 */
@Component
public class AuditQueries {

  private static final long HOUR_MS = 3_600_000L;

  /** One call as the events table shows it: its latest row. */
  public record Event(long id, String callId, Instant at, String phase, String decision, String tool,
      String connection, String env, String client, String endpoint, String token, Long durationMs, String rule,
      String approvalId, String traceId, String errorKind, String errorMessage) {}

  /** A page of events, newest first; {@code nextCursor} continues it ({@code null}: no more). */
  public record Events(List<Event> events, String nextCursor) {}

  /** One Setup change row. */
  public record Change(long id, Instant at, String what, String subject, String from, String to, String by,
      String reason, String env, String endpoint, String connection, Map<String, Object> details) {}

  /**
   * What a call's {@code completed} row says it came to: its decision ({@code approved}, {@code error},
   * {@code rejected}…), the error, and the hash of what ran.
   */
  public record Outcome(String decision, String errorKind, String errorMessage, String executedSha256,
      boolean outcomeUnknown) {}

  /** One trace as stored by the summarizer. */
  public record Trace(String traceId, String title, String titleSource, Instant firstAt, Instant lastAt,
      String client, String endpoint, int calls, Map<String, Object> summary) {}

  /**
   * One stored row of a call, as the event detail shows it (Request, Arguments masked, Policy, Approval timeline,
   * Result, Timing, Raw). Arguments and the result preview were masked before they were written.
   */
  public record Row(long id, String phase, Instant at, String decision, String classification, String rule,
      String approvalId, String approvalDelivery, String protocolVersion, String ingress, String principalKind,
      String client, Map<String, Object> clientInfo, String tokenLabel, Map<String, Object> args, String argsSha256,
      Long durationMs, Long gatewayMs, Long resourceMs, Long approvalWaitMs, Long resultBytes, String resultSha256,
      String resultPreview, Integer maskedCount, Boolean truncated, String errorKind, String errorMessage,
      Map<String, Object> facets, String actionSha256, String executedSha256, Boolean outcomeUnknown,
      String decidedVia, boolean commented, String traceId, String spanId, String rowSha256) {}

  /** One call: the events table's line and every row it wrote, oldest first. */
  public record Detail(Event event, List<Row> rows) {}

  /** One bar of the Activity histogram: calls by what they came to. */
  public record Bucket(Instant from, Instant to, Map<String, Long> decisions, long total) {}

  /** The histogram of a range; every bar spans {@code stepHours}. */
  public record Histogram(Instant from, Instant to, int stepHours, List<Bucket> buckets) {}

  /** One value of a facet with the number of calls that have it. */
  public record FacetValue(String name, long count) {}

  /**
   * Calls of a range for Overview: how many, by decision, the gateway's own p95 and one count per hour.
   *
   * @param gatewayP95Ms      the 95th percentile of {@code gateway_ms} over the completed calls, or {@code null} without
   * @param gatewayP95PerHour the same for each bar of {@code perHour} (the overhead tile's sparkline), {@code null} for
   *                          a bar without completed calls
   */
  public record Stats(long calls, Map<String, Long> decisions, Long gatewayP95Ms, List<Long> perHour,
      List<Long> gatewayP95PerHour) {}

  /** The busiest value of a column over a short window (Policies › Limits "use in the last minute"). */
  public record Busiest(String value, long calls) {}

  @PersistenceContext
  private EntityManager em;

  private final ToolCallRepository calls;
  private final SetupChangeRepository changes;
  private final TraceRepository traces;

  public AuditQueries(ToolCallRepository calls, SetupChangeRepository changes, TraceRepository traces) {
    this.calls = calls;
    this.changes = changes;
    this.traces = traces;
  }

  /**
   * Calls matching {@code q} between {@code from} and {@code to} (either may be {@code null}), newest first.
   *
   * @param cursor the {@code nextCursor} of the previous page, or {@code null}
   */
  @Transactional(readOnly = true)
  public Events events(String q, Instant from, Instant to, String cursor, int limit) {
    int size = Math.max(1, Math.min(limit, 500));
    CriteriaBuilder cb = em.getCriteriaBuilder();
    CriteriaQuery<ToolCallEntity> cq = cb.createQuery(ToolCallEntity.class);
    Root<ToolCallEntity> root = cq.from(ToolCallEntity.class);
    // The latest row of each call: what the call came to.
    List<Predicate> where = new ArrayList<>(List.of(latestMatching(q, from, to, root, cq, cb)));
    if (cursor != null && !cursor.isBlank()) {
      where.add(cb.lessThan(root.get("id"), parseCursor(cursor)));
    }
    cq.select(root).where(where.toArray(Predicate[]::new)).orderBy(cb.desc(root.get("id")));
    List<ToolCallEntity> rows = em.createQuery(cq).setMaxResults(size + 1).getResultList();
    boolean more = rows.size() > size;
    List<Event> out = rows.stream().limit(size).map(AuditQueries::event).toList();
    return new Events(out, more ? Long.toString(out.get(out.size() - 1).id(), 36) : null);
  }

  /** Every row of one call, oldest first (event detail: request, policy, approval timeline, result). */
  @Transactional(readOnly = true)
  public List<Event> call(String callId) {
    return calls.findByCallIdOrderByIdAsc(callId).stream().map(AuditQueries::event).toList();
  }

  /**
   * When a runbook last ran on its connection (Policies › Runbooks, "Last run 5 h ago"): the {@code completed} row of
   * the newest call whose request named it ({@code ssh_run}'s {@code runbook}) and that reached the server — allowed,
   * approved, or failed there. A request denied, rejected, expired or stopped before it ran doesn't count.
   *
   * <p>Policies asks this for every runbook: the connection's rows are walked newest first by the index
   * {@code (connection_name, created_at)} and each one's request is looked up by its call, so the answer comes with the
   * last run instead of after reading the facets of every request the audit keeps (a year and more).
   */
  @Transactional(readOnly = true)
  public Optional<Instant> lastRun(String connection, String runbook) {
    CriteriaBuilder cb = em.getCriteriaBuilder();
    CriteriaQuery<Instant> cq = cb.createQuery(Instant.class);
    Root<ToolCallEntity> done = cq.from(ToolCallEntity.class);
    cq.select(done.get("createdAt")).where(cb.equal(done.get("connectionName"), connection),
        cb.equal(done.get("phase"), CallPhase.COMPLETED), cb.isNotNull(done.get("resourceMs")),
        done.get("decision").in(CallDecision.ALLOWED, CallDecision.APPROVED, CallDecision.ERROR),
        ActivityQueryParser.requestHas(done, cq, cb, "runbook", runbook))
        .orderBy(cb.desc(done.get("createdAt")));
    return em.createQuery(cq).setMaxResults(1).getResultList().stream().findFirst();
  }

  /** The {@code completed} row of one call, if it has one (a request left {@code running} is closed from it). */
  @Transactional(readOnly = true)
  public Optional<Outcome> outcome(String callId) {
    return calls.findByCallIdOrderByIdAsc(callId).stream()
        .filter(r -> r.getPhase() == CallPhase.COMPLETED)
        .reduce((first, last) -> last)
        .map(r -> new Outcome(KebabCaseEnumConverter.idOf(r.getDecision()), r.getErrorKind(), r.getErrorMessage(),
            r.getExecutedSha256(), Boolean.TRUE.equals(r.getOutcomeUnknown())));
  }

  @Transactional(readOnly = true)
  public Optional<Trace> trace(String traceIdOrPrefix) {
    String id = traceIdOrPrefix.toLowerCase(Locale.ROOT);
    Optional<TraceEntity> exact = traces.findById(id);
    if (exact.isPresent()) {
      return exact.map(AuditQueries::trace);
    }
    List<TraceEntity> byPrefix = em.createQuery("select t from TraceEntity t where t.traceId like :p", TraceEntity.class)
        .setParameter("p", id.replace("%", "").replace("_", "") + "%").setMaxResults(2).getResultList();
    return byPrefix.size() == 1 ? Optional.of(trace(byPrefix.get(0))) : Optional.empty();
  }

  /** Traces whose last call was before {@code before}, newest first. */
  @Transactional(readOnly = true)
  public List<Trace> traces(Instant before, int limit) {
    return traces.findByLastAtBeforeOrderByLastAtDesc(before, PageRequest.ofSize(Math.max(1, Math.min(limit, 200))))
        .stream().map(AuditQueries::trace).toList();
  }

  /**
   * Traces whose last call was before {@code before}, newest first, that have a call between {@code from} and
   * {@code to} (either may be {@code null}) matching {@code q} — the whole audit, not a window of recent calls; a call
   * counts where its latest row is, as on the events tab.
   */
  @Transactional(readOnly = true)
  public List<Trace> traces(String q, Instant from, Instant to, Instant before, int limit) {
    if ((q == null || q.isBlank()) && from == null && to == null) {
      return traces(before, limit);
    }
    CriteriaBuilder cb = em.getCriteriaBuilder();
    CriteriaQuery<TraceEntity> cq = cb.createQuery(TraceEntity.class);
    Root<TraceEntity> trace = cq.from(TraceEntity.class);
    Subquery<String> matching = cq.subquery(String.class);
    Root<ToolCallEntity> call = matching.from(ToolCallEntity.class);
    matching.select(call.get("traceId")).where(latestMatching(q, from, to, call, matching, cb));
    cq.select(trace).where(cb.lessThan(trace.get("lastAt"), before), trace.get("traceId").in(matching))
        .orderBy(cb.desc(trace.get("lastAt")));
    return em.createQuery(cq).setMaxResults(Math.max(1, Math.min(limit, 200))).getResultList().stream()
        .map(AuditQueries::trace).toList();
  }

  /** Setup changes, newest first; {@code text} matches subject, what, reason, endpoint or connection. */
  @Transactional(readOnly = true)
  public List<Change> changes(String text, Long beforeId, int limit) {
    CriteriaBuilder cb = em.getCriteriaBuilder();
    CriteriaQuery<SetupChangeEntity> cq = cb.createQuery(SetupChangeEntity.class);
    Root<SetupChangeEntity> root = cq.from(SetupChangeEntity.class);
    List<Predicate> where = new ArrayList<>();
    if (beforeId != null) {
      where.add(cb.lessThan(root.get("id"), beforeId));
    }
    if (text != null && !text.isBlank()) {
      String like = "%" + text.strip().toLowerCase(Locale.ROOT).replace("%", "").replace("_", "\\_") + "%";
      List<Predicate> any = new ArrayList<>();
      for (String field : List.of("subject", "reason", "endpointSlug", "connectionName")) {
        any.add(cb.like(cb.lower(root.get(field)), like, '\\'));
      }
      where.add(cb.or(any.toArray(Predicate[]::new)));
    }
    cq.select(root).where(where.toArray(Predicate[]::new)).orderBy(cb.desc(root.get("id")));
    return em.createQuery(cq).setMaxResults(Math.max(1, Math.min(limit, 500))).getResultList().stream()
        .map(c -> new Change(c.getId(), c.getCreatedAt(), c.getWhat().id(), c.getSubject(), c.getChangeFrom(),
            c.getChangeTo(), "You", c.getReason(), c.getEnv() == null ? null : c.getEnv().id(), c.getEndpointSlug(),
            c.getConnectionName(), c.getDetails()))
        .toList();
  }

  /** One call by the id of any of its rows (the events table names the latest one). */
  @Transactional(readOnly = true)
  public Optional<Detail> detail(long rowId) {
    return calls.findById(rowId).map(r -> {
      List<ToolCallEntity> rows = calls.findByCallIdOrderByIdAsc(r.getCallId());
      return new Detail(event(rows.get(rows.size() - 1)), rows.stream().map(AuditQueries::row).toList());
    });
  }

  /**
   * Calls matching {@code q} per bar between {@code from} and {@code to}, by the decision of their latest row; a bar
   * spans as many hours as keep the chart at 48 bars or fewer.
   */
  @Transactional(readOnly = true)
  public Histogram histogram(String q, Instant from, Instant to) {
    long fromHour = Math.floorDiv(from.toEpochMilli(), HOUR_MS);
    long toHour = Math.floorDiv(to.toEpochMilli() - 1, HOUR_MS);
    long hours = Math.max(1, toHour - fromHour + 1);
    return histogram(q, from, to, (int) Math.max(1, (hours + 47) / 48));
  }

  /** The same with bars of {@code step} hours (Insights: 1 for a day, 4 for a week, 24 for a month). */
  @Transactional(readOnly = true)
  public Histogram histogram(String q, Instant from, Instant to, int step) {
    long fromHour = Math.floorDiv(from.toEpochMilli(), HOUR_MS);
    long toHour = Math.floorDiv(to.toEpochMilli() - 1, HOUR_MS);
    long hours = Math.max(1, toHour - fromHour + 1);
    CriteriaBuilder cb = em.getCriteriaBuilder();
    CriteriaQuery<Tuple> cq = cb.createTupleQuery();
    Root<ToolCallEntity> root = cq.from(ToolCallEntity.class);
    cq.multiselect(root.get("hourBucket"), root.get("decision"), cb.count(root))
        .where(latestMatching(q, from, to, root, cq, cb))
        .groupBy(root.get("hourBucket"), root.get("decision"));
    Map<Long, Map<String, Long>> byBar = new java.util.TreeMap<>();
    for (Tuple t : em.createQuery(cq).getResultList()) {
      long bar = (t.get(0, Long.class) - fromHour) / step;
      byBar.computeIfAbsent(bar, k -> new java.util.TreeMap<>()).merge(KebabCaseEnumConverter.idOf(
          (Enum<?>) t.get(1)), t.get(2, Long.class), Long::sum);
    }
    List<Bucket> buckets = new ArrayList<>();
    for (long bar = 0; bar * step < hours; bar++) {
      Map<String, Long> decisions = byBar.getOrDefault(bar, Map.of());
      Instant start = Instant.ofEpochMilli((fromHour + bar * step) * HOUR_MS);
      buckets.add(new Bucket(start, start.plusMillis(step * HOUR_MS), Map.copyOf(decisions),
          decisions.values().stream().mapToLong(Long::longValue).sum()));
    }
    return new Histogram(from, to, step, List.copyOf(buckets));
  }

  /** Decision, Tool, Client, Endpoint, Connection and Env with their counts over the calls matching {@code q}. */
  @Transactional(readOnly = true)
  public Map<String, List<FacetValue>> facets(String q, Instant from, Instant to) {
    Map<String, List<FacetValue>> out = new java.util.LinkedHashMap<>();
    Map<String, String> columns = new java.util.LinkedHashMap<>();
    columns.put("decision", "decision");
    columns.put("tool", "toolName");
    columns.put("client", "client");
    columns.put("endpoint", "endpointSlug");
    columns.put("connection", "connectionName");
    columns.put("env", "env");
    CriteriaBuilder cb = em.getCriteriaBuilder();
    columns.forEach((facet, attribute) -> {
      CriteriaQuery<Tuple> cq = cb.createTupleQuery();
      Root<ToolCallEntity> root = cq.from(ToolCallEntity.class);
      cq.multiselect(root.get(attribute), cb.count(root))
          .where(latestMatching(q, from, to, root, cq, cb))
          .groupBy(root.get(attribute));
      List<FacetValue> values = new ArrayList<>();
      for (Tuple t : em.createQuery(cq).getResultList()) {
        Object v = t.get(0);
        if (v != null) {
          values.add(new FacetValue(v instanceof ai.msrv.gaterook.connector.api.Env e ? e.id()
              : v instanceof Enum<?> en ? KebabCaseEnumConverter.idOf(en) : v.toString(), t.get(1, Long.class)));
        }
      }
      values.sort(java.util.Comparator.comparingLong(FacetValue::count).reversed()
          .thenComparing(FacetValue::name));
      out.put(facet, List.copyOf(values));
    });
    return out;
  }

  /** Overview's numbers for {@code [from, to)}: calls by decision, the gateway's p95, calls and p95 per hour. */
  @Transactional(readOnly = true)
  public Stats stats(Instant from, Instant to) {
    Histogram h = histogram(null, from, to);
    Map<String, Long> decisions = new java.util.TreeMap<>();
    List<Long> perHour = new ArrayList<>();
    long total = 0;
    for (Bucket b : h.buckets()) {
      b.decisions().forEach((k, v) -> decisions.merge(k, v, Long::sum));
      perHour.add(b.total());
      total += b.total();
    }
    // One read of the range's gateway times gives the tile's p95 and its sparkline (Overview refreshes on every call).
    List<Long> all = new ArrayList<>();
    List<Long> perBar = gatewayP95PerBar(h, all);
    return new Stats(total, Map.copyOf(decisions), p95(all), List.copyOf(perHour), perBar);
  }

  /**
   * The 95th percentile of {@code gateway_ms} over the completed calls of each bar of {@code h} (Overview's overhead
   * sparkline): one list entry per bar, {@code null} for a bar without completed calls; every value read goes into
   * {@code all} too, for the range's own p95 — the rows {@link #gatewayP95} reads, at most as many.
   */
  private List<Long> gatewayP95PerBar(Histogram h, List<Long> all) {
    long fromHour = Math.floorDiv(h.from().toEpochMilli(), HOUR_MS);
    Map<Long, List<Long>> byBar = new java.util.HashMap<>();
    em.createQuery("select c.hourBucket, c.gatewayMs from ToolCallEntity c where c.phase = :phase "
        + "and c.createdAt >= :from and c.createdAt < :to and c.gatewayMs is not null", Tuple.class)
        .setParameter("phase", CallPhase.COMPLETED).setParameter("from", h.from()).setParameter("to", h.to())
        .setMaxResults(200_000).getResultList()
        .forEach(t -> {
          Long ms = t.get(1, Long.class);
          all.add(ms);
          byBar.computeIfAbsent((t.get(0, Long.class) - fromHour) / h.stepHours(), k -> new ArrayList<>()).add(ms);
        });
    List<Long> out = new ArrayList<>();
    for (int bar = 0; bar < h.buckets().size(); bar++) {
      out.add(p95(byBar.getOrDefault((long) bar, List.of())));
    }
    return java.util.Collections.unmodifiableList(out);
  }

  /** The 95th percentile of {@code values} (nearest rank), {@code null} for none. */
  private static Long p95(List<Long> values) {
    if (values.isEmpty()) {
      return null;
    }
    long[] sorted = values.stream().mapToLong(Long::longValue).sorted().toArray();
    return sorted[(int) Math.min(sorted.length - 1, Math.ceil(sorted.length * 0.95) - 1)];
  }

  /**
   * The 95th percentile of the gateway's own time ({@code gateway_ms}) over the completed calls of {@code [from, to)},
   * of one endpoint or ({@code null}) of all; {@code null} without such calls.
   */
  @Transactional(readOnly = true)
  public Long gatewayP95(String endpointSlug, Instant from, Instant to) {
    var query = em.createQuery("select c.gatewayMs from ToolCallEntity c where c.phase = :phase "
        + "and c.createdAt >= :from and c.createdAt < :to and c.gatewayMs is not null"
        + (endpointSlug == null ? "" : " and c.endpointSlug = :slug"), Long.class)
        .setParameter("phase", CallPhase.COMPLETED).setParameter("from", from).setParameter("to", to);
    if (endpointSlug != null) {
      query.setParameter("slug", endpointSlug);
    }
    return p95(query.setMaxResults(200_000).getResultList());
  }

  /** Calls an endpoint received since {@code since} (its rate limit's use, Settings › Limits). */
  @Transactional(readOnly = true)
  public long received(String endpointSlug, Instant since) {
    return em.createQuery("select count(c) from ToolCallEntity c where c.phase = :phase and c.endpointSlug = :slug "
        + "and c.createdAt >= :since", Long.class).setParameter("phase", CallPhase.RECEIVED)
        .setParameter("slug", endpointSlug).setParameter("since", since).getSingleResult();
  }

  /** One protocol revision an endpoint was called in, with the client families that used it. */
  public record Revision(String protocolVersion, List<String> clients, long calls) {}

  /** The revisions an endpoint's calls used since {@code since}, newest revision first. */
  @Transactional(readOnly = true)
  public List<Revision> revisions(String endpointSlug, Instant since) {
    CriteriaBuilder cb = em.getCriteriaBuilder();
    CriteriaQuery<Tuple> cq = cb.createTupleQuery();
    Root<ToolCallEntity> root = cq.from(ToolCallEntity.class);
    cq.multiselect(root.get("protocolVersion"), root.get("client"), cb.count(root))
        .where(cb.equal(root.get("phase"), CallPhase.RECEIVED), cb.equal(root.get("endpointSlug"), endpointSlug),
            cb.greaterThanOrEqualTo(root.get("createdAt"), since), cb.isNotNull(root.get("protocolVersion")))
        .groupBy(root.get("protocolVersion"), root.get("client"));
    Map<String, java.util.TreeSet<String>> clients = new java.util.TreeMap<>(java.util.Comparator.reverseOrder());
    Map<String, Long> calls = new java.util.HashMap<>();
    for (Tuple t : em.createQuery(cq).getResultList()) {
      String rev = t.get(0, String.class);
      java.util.TreeSet<String> set = clients.computeIfAbsent(rev, k -> new java.util.TreeSet<>());
      if (t.get(1) != null) {
        set.add(t.get(1, String.class));
      }
      calls.merge(rev, t.get(2, Long.class), Long::sum);
    }
    List<Revision> out = new ArrayList<>();
    clients.forEach((rev, set) -> out.add(new Revision(rev, List.copyOf(set), calls.get(rev))));
    return List.copyOf(out);
  }

  /**
   * When a client's link was last used (Clients, Overview: a call after Connect proves the client restarted): the
   * newest row on {@code endpointSlug} since {@code since} (the link's Connect) of the calls that carry
   * {@code tokenLabel}, or, without a label, of the calls of an OAuth sign-in whose client family is
   * {@code oauthClient} — a sign-in through the browser has no access of its own until the client signs in. The family
   * a caller only reports about itself ({@code clientInfo}) under another credential doesn't count. Rows are walked
   * newest first by the indexes {@code (token_label, created_at)} and {@code (endpoint_slug, created_at)}: a link that
   * calls often answers at once, one that never called reads only the rows since its Connect.
   */
  @Transactional(readOnly = true)
  public Optional<Instant> lastCall(String endpointSlug, String tokenLabel, String oauthClient, Instant since) {
    CriteriaBuilder cb = em.getCriteriaBuilder();
    CriteriaQuery<Instant> cq = cb.createQuery(Instant.class);
    Root<ToolCallEntity> row = cq.from(ToolCallEntity.class);
    List<Predicate> where = new ArrayList<>();
    if (tokenLabel != null) {
      where.add(cb.equal(row.get("tokenLabel"), tokenLabel));
    } else if (oauthClient != null) {
      where.add(cb.equal(row.get("client"), oauthClient));
      where.add(cb.equal(row.get("principalKind"), ai.msrv.gaterook.core.platform.Caller.Kind.OAUTH));
    } else {
      return Optional.empty();
    }
    where.add(cb.equal(row.get("endpointSlug"), endpointSlug));
    if (since != null) {
      where.add(cb.greaterThanOrEqualTo(row.get("createdAt"), since));
    }
    cq.select(row.get("createdAt")).where(where.toArray(Predicate[]::new)).orderBy(cb.desc(row.get("createdAt")));
    return em.createQuery(cq).setMaxResults(1).getResultList().stream().findFirst();
  }

  /**
   * The value of {@code column} ({@code token} or {@code tool}) with the most calls received since {@code since},
   * or empty without calls.
   */
  @Transactional(readOnly = true)
  public Optional<Busiest> busiest(String column, Instant since) {
    String attribute = switch (column) {
      case "token" -> "tokenLabel";
      case "tool" -> "toolName";
      default -> throw new IllegalArgumentException("No column " + column);
    };
    CriteriaBuilder cb = em.getCriteriaBuilder();
    CriteriaQuery<Tuple> cq = cb.createTupleQuery();
    Root<ToolCallEntity> root = cq.from(ToolCallEntity.class);
    cq.multiselect(root.get(attribute), cb.count(root))
        .where(cb.equal(root.get("phase"), CallPhase.RECEIVED), cb.greaterThanOrEqualTo(root.get("createdAt"), since),
            cb.isNotNull(root.get(attribute)))
        .groupBy(root.get(attribute))
        .orderBy(cb.desc(cb.count(root)), cb.asc(root.get(attribute)));
    return em.createQuery(cq).setMaxResults(1).getResultList().stream().findFirst()
        .map(t -> new Busiest(t.get(0, String.class), t.get(1, Long.class)));
  }

  /** Whether any call matches {@code q}: getting started ("Try a tool in Playground", "Connect a client"). */
  @Transactional(readOnly = true)
  public boolean any(String q) {
    return !events(q, null, null, null, 1).events().isEmpty();
  }

  // ── Insights (ARCHITECTURE.md §6 insights, A17: "Insights are computed from audit queries") ──────────────────────

  /**
   * One call as Insights counts it: what its latest row says (a call is what it came to), and the masked arguments
   * when that row carries them — the only row of a denied call does, later phases don't.
   *
   * @param id the latest row's id: the call's event in Activity ({@code #event-<id>})
   * @param at the latest row's time, which puts the call in a range (as Activity's histogram does)
   */
  public record CallFacts(long id, String callId, Instant at, String decision, String tool, String connection,
      String env, String client, String endpoint, String rule, String approvalId, String decidedVia, String traceId,
      Long durationMs, Long gatewayMs, Long resourceMs, Long approvalWaitMs, String errorKind, String errorMessage,
      Map<String, Object> args) {}

  /**
   * The calls matching {@code q} whose latest row is in {@code [from, to)}, oldest first, at most {@code limit} (the
   * newest are left out past it). One query, no entity in the persistence context.
   */
  @Transactional(readOnly = true)
  public List<CallFacts> callFacts(String q, Instant from, Instant to, int limit) {
    CriteriaBuilder cb = em.getCriteriaBuilder();
    CriteriaQuery<Tuple> cq = cb.createTupleQuery();
    Root<ToolCallEntity> root = cq.from(ToolCallEntity.class);
    cq.multiselect(root.get("id"), root.get("callId"), root.get("createdAt"), root.get("decision"),
        root.get("toolName"), root.get("connectionName"), root.get("env"), root.get("client"),
        root.get("endpointSlug"), root.get("ruleId"), root.get("approvalId"), root.get("decidedVia"),
        root.get("traceId"), root.get("durationMs"), root.get("gatewayMs"), root.get("resourceMs"),
        root.get("approvalWaitMs"), root.get("errorKind"), root.get("errorMessage"), root.get("argsMasked"))
        .where(latestMatching(q, from, to, root, cq, cb))
        .orderBy(cb.asc(root.get("id")));
    List<CallFacts> out = new ArrayList<>();
    for (Tuple t : em.createQuery(cq).setMaxResults(Math.max(1, limit)).getResultList()) {
      Object env = t.get(6);
      @SuppressWarnings("unchecked")
      Map<String, Object> args = (Map<String, Object>) t.get(19);
      out.add(new CallFacts(t.get(0, Long.class), t.get(1, String.class), t.get(2, Instant.class),
          KebabCaseEnumConverter.idOf((Enum<?>) t.get(3)), t.get(4, String.class), t.get(5, String.class),
          env == null ? null : ((ai.msrv.gaterook.connector.api.Env) env).id(), t.get(7, String.class),
          t.get(8, String.class), t.get(9, String.class), t.get(10, String.class), t.get(11, String.class),
          t.get(12, String.class), t.get(13, Long.class), t.get(14, Long.class), t.get(15, Long.class),
          t.get(16, Long.class), t.get(17, String.class), t.get(18, String.class), args));
    }
    return out;
  }

  /** When each of {@code callIds} was received (its first row), for the calls that still have rows. */
  @Transactional(readOnly = true)
  public Map<String, Instant> receivedAt(java.util.Collection<String> callIds) {
    Map<String, Instant> out = new java.util.HashMap<>();
    List<String> ids = List.copyOf(new java.util.LinkedHashSet<>(callIds));
    for (int i = 0; i < ids.size(); i += 500) {
      List<String> chunk = ids.subList(i, Math.min(ids.size(), i + 500));
      for (Tuple t : em.createQuery("select c.callId, min(c.createdAt) from ToolCallEntity c where c.callId in :ids "
          + "group by c.callId", Tuple.class).setParameter("ids", chunk).getResultList()) {
        out.put(t.get(0, String.class), t.get(1, Instant.class));
      }
    }
    return out;
  }

  /** The agents' tasks among {@code traceIds}: the traces Activity › Traces knows, by id. */
  @Transactional(readOnly = true)
  public Map<String, Trace> tasks(java.util.Collection<String> traceIds) {
    Map<String, Trace> out = new java.util.HashMap<>();
    List<String> ids = List.copyOf(new java.util.LinkedHashSet<>(traceIds));
    for (int i = 0; i < ids.size(); i += 500) {
      List<String> chunk = ids.subList(i, Math.min(ids.size(), i + 500));
      em.createQuery("select t from TraceEntity t where t.traceId in :ids", TraceEntity.class)
          .setParameter("ids", chunk).getResultList().forEach(t -> out.put(t.getTraceId(), trace(t)));
    }
    return out;
  }

  /** When the oldest call the audit keeps reached the gateway; empty before the first call. */
  @Transactional(readOnly = true)
  public Optional<Instant> firstCallAt() {
    return Optional.ofNullable(em.createQuery("select min(c.createdAt) from ToolCallEntity c", Instant.class)
        .getSingleResult());
  }

  /** Calls matching {@code q} with a decision (not waiting for a person) whose latest row is in {@code [from, to)}. */
  @Transactional(readOnly = true)
  public long decided(String q, Instant from, Instant to) {
    CriteriaBuilder cb = em.getCriteriaBuilder();
    CriteriaQuery<Long> cq = cb.createQuery(Long.class);
    Root<ToolCallEntity> root = cq.from(ToolCallEntity.class);
    List<Predicate> where = new ArrayList<>(List.of(latestMatching(q, from, to, root, cq, cb)));
    where.add(cb.notEqual(root.get("decision"), ai.msrv.gaterook.core.audit.internal.persistence.CallDecision.PENDING));
    cq.select(cb.count(root)).where(where.toArray(Predicate[]::new));
    return em.createQuery(cq).getSingleResult();
  }

  /**
   * The latest row of every call between {@code from} and {@code to} that matches {@code q}; {@code cq} (a query or
   * a subquery) makes the subqueries.
   */
  private Predicate[] latestMatching(String q, Instant from, Instant to, Root<ToolCallEntity> root,
      AbstractQuery<?> cq, CriteriaBuilder cb) {
    ActivityQueryParser.Query query = ActivityQueryParser.parse(q);
    List<Predicate> where = new ArrayList<>();
    Subquery<Long> latest = cq.subquery(Long.class);
    Root<ToolCallEntity> other = latest.from(ToolCallEntity.class);
    latest.select(cb.max(other.get("id"))).where(cb.equal(other.get("callId"), root.get("callId")));
    where.add(cb.equal(root.get("id"), latest));
    if (from != null) {
      where.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
    }
    if (to != null) {
      where.add(cb.lessThan(root.get("createdAt"), to));
    }
    if (!query.isEmpty()) {
      where.add(query.predicate(root, cq, cb));
    }
    return where.toArray(Predicate[]::new);
  }

  @Transactional(readOnly = true)
  public long countCalls() {
    return calls.count();
  }

  private static long parseCursor(String cursor) {
    try {
      return Long.parseLong(cursor, 36);
    } catch (NumberFormatException e) {
      throw ai.msrv.gaterook.core.platform.ProblemException.unprocessable("Invalid cursor", "Start the list again.",
          "/cursor");
    }
  }

  private static Event event(ToolCallEntity r) {
    return new Event(r.getId(), r.getCallId(), r.getCreatedAt(), KebabCaseEnumConverter.idOf(r.getPhase()),
        KebabCaseEnumConverter.idOf(r.getDecision()), r.getToolName(), r.getConnectionName(),
        r.getEnv() == null ? null : r.getEnv().id(), r.getClient(), r.getEndpointSlug(), r.getTokenLabel(),
        r.getDurationMs(), r.getRuleId(), r.getApprovalId(), r.getTraceId(), r.getErrorKind(), r.getErrorMessage());
  }

  private static Row row(ToolCallEntity r) {
    return new Row(r.getId(), KebabCaseEnumConverter.idOf(r.getPhase()), r.getCreatedAt(),
        KebabCaseEnumConverter.idOf(r.getDecision()), r.getClassification() == null ? null
            : r.getClassification().name().toLowerCase(Locale.ROOT), r.getRuleId(), r.getApprovalId(),
        r.getApprovalDelivery() == null ? null : KebabCaseEnumConverter.idOf(r.getApprovalDelivery()),
        r.getProtocolVersion(), r.getIngress() == null ? null : r.getIngress().name().toLowerCase(Locale.ROOT),
        r.getPrincipalKind() == null ? null : r.getPrincipalKind().name().toLowerCase(Locale.ROOT), r.getClient(),
        r.getClientInfo(), r.getTokenLabel(), r.getArgsMasked(), r.getArgsSha256(), r.getDurationMs(),
        r.getGatewayMs(), r.getResourceMs(), r.getApprovalWaitMs(), r.getResultBytes(), r.getResultSha256(),
        r.getResultPreview(), r.getMaskedCount(), r.getTruncated(), r.getErrorKind(), r.getErrorMessage(),
        r.getFacets(), r.getActionSha256(), r.getExecutedSha256(), r.getOutcomeUnknown(), r.getDecidedVia(),
        r.getCommentSha256() != null, r.getTraceId(), r.getSpanId(), r.getRowSha256());
  }

  private static Trace trace(TraceEntity t) {
    return new Trace(t.getTraceId(), t.getTitle(), t.getTitleSource() == null ? null
        : KebabCaseEnumConverter.idOf(t.getTitleSource()), t.getFirstAt(), t.getLastAt(), t.getClient(),
        t.getEndpointSlug(), t.getCalls(), t.getSummary());
  }
}
