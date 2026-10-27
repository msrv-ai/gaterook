package ai.msrv.gaterook.core.approvals;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.core.approvals.internal.ApprovalExecutor;
import ai.msrv.gaterook.core.approvals.internal.ApprovalRows;
import ai.msrv.gaterook.core.approvals.internal.ApprovalWaiters;
import ai.msrv.gaterook.core.approvals.internal.Codecs;
import ai.msrv.gaterook.core.approvals.internal.persistence.ApprovalEntity;
import ai.msrv.gaterook.core.approvals.internal.persistence.ApprovalEventEntity;
import ai.msrv.gaterook.core.approvals.internal.persistence.ApprovalEventKind;
import ai.msrv.gaterook.core.approvals.internal.persistence.ApprovalEventRepository;
import ai.msrv.gaterook.core.approvals.internal.persistence.ApprovalRepository;
import ai.msrv.gaterook.core.approvals.internal.persistence.ApprovalStatus;
import ai.msrv.gaterook.core.approvals.internal.persistence.CommentPolicy;
import ai.msrv.gaterook.core.audit.AuditUnavailableException;
import ai.msrv.gaterook.core.audit.AuditWriter;
import ai.msrv.gaterook.core.platform.Caller;
import ai.msrv.gaterook.core.platform.CanonicalJson;
import ai.msrv.gaterook.core.platform.ProblemException;
import ai.msrv.gaterook.core.policy.PolicyService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Approval requests (ARCHITECTURE.md A3, §5 step 11): one mechanism on the server. A call that needs a person becomes
 * a request holding the canonical call and its digest; the person decides in the window, from a notification or a
 * channel; the gateway itself then runs the stored call ({@link ApprovalExecutor}) and keeps the masked result for 60
 * minutes. A timeout is a denial.
 * <ul>
 *   <li>{@link #requestOrReuse}: the same call by the same principal while pending gets the same handle; at most
 *       {@value #MAX_PENDING_PER_PRINCIPAL} pending per principal; the request and its call's {@code received} audit row
 *       commit together (no request without its audit, nothing ran before either).</li>
 *   <li>{@link #decide}: Deny is a versioned CAS {@code pending → rejected}; Approve is one IMMEDIATE transaction —
 *       the CAS {@code pending → running} before expiry plus the {@code resolved} audit row; if that row can't be
 *       written nothing runs and the request is {@code failed} "Audit unavailable". Two decisions racing (window and
 *       Slack) affect exactly one row: one execution.</li>
 *   <li>{@link #await}: long polls without a transaction, woken by the change and re-reading the row.</li>
 * </ul>
 * Never {@code @Transactional} itself: the waits must not hold a database connection.
 */
@Service
public class ApprovalService {

  private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

  /** Pending requests one principal may have (§5 step 5). */
  public static final int MAX_PENDING_PER_PRINCIPAL = 10;

  /** How long an executed result is kept for {@code approval_status} (A3). */
  public static final Duration RESULT_KEPT = Duration.ofMinutes(60);

  /** Channels that may decide; an OS notification may only deny (§6 approvals). */
  public static final Set<String> CHANNELS = Set.of("app", "desktop-notification", "slack", "telegram");

  /** Approve or deny. */
  public enum Decision { APPROVE, DENY }

  /**
   * A call that needs approval, after {@code prepare}.
   *
   * @param call           the call as it will run (the arguments without {@code connection})
   * @param audit          the call's audit identity; its {@code received} row commits with the request
   * @param delivery       how the outcome will reach the agent ({@code mrtr} or {@code handle})
   */
  public record NewRequest(Caller caller, String endpointId, String endpointSlug, String callId, ToolCall call,
      String toolKey, ConnectionConfig cfg, ActionPreview preview, JsonNode preparedState, String agentNote,
      String ruleId, Classification.Kind classification, String traceparent, AuditWriter.Call audit,
      AuditWriter.Delivery delivery) {

    public NewRequest {
      Objects.requireNonNull(caller, "caller");
      Objects.requireNonNull(call, "call");
      Objects.requireNonNull(cfg, "cfg");
      Objects.requireNonNull(preview, "preview");
      Objects.requireNonNull(audit, "audit");
    }
  }

  /** The request a call got, and whether it was an existing one (the same call repeated while pending). */
  public record Ticket(ApprovalView approval, boolean reused) {}

  /** The principal already has {@value #MAX_PENDING_PER_PRINCIPAL} requests waiting. */
  public static final class TooManyWaitingException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public TooManyWaitingException() {
      super(MAX_PENDING_PER_PRINCIPAL + " requests are already waiting for a person. Wait for a decision, or ask the "
          + "user to decide or deny them, before you ask again.");
    }
  }

  private final ApprovalRepository approvals;
  private final ApprovalEventRepository events;
  private final ApprovalRows rows;
  private final ApprovalWaiters waiters;
  private final ApprovalExecutor executor;
  private final AuditWriter audit;
  private final PolicyService policies;
  private final ApplicationEventPublisher publisher;
  private final JsonMapper json;
  private final Clock clock;
  private final TransactionTemplate writeTx;
  private final TransactionTemplate readTx;

  public ApprovalService(ApprovalRepository approvals, ApprovalEventRepository events, ApprovalRows rows,
      ApprovalWaiters waiters, ApprovalExecutor executor, AuditWriter audit, PolicyService policies,
      ApplicationEventPublisher publisher, JsonMapper json, Clock clock, PlatformTransactionManager tx) {
    this.approvals = approvals;
    this.events = events;
    this.rows = rows;
    this.waiters = waiters;
    this.executor = executor;
    this.audit = audit;
    this.policies = policies;
    this.publisher = publisher;
    this.json = json;
    this.clock = clock;
    this.writeTx = new TransactionTemplate(tx);
    this.readTx = new TransactionTemplate(tx);
    this.readTx.setReadOnly(true);
  }

  // ── Requests ────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * The request of this call: an existing pending one of the same principal with the same digest, or a new one.
   * Its {@code received} audit row (decision {@code pending}) is written in the same transaction.
   *
   * @throws TooManyWaitingException   when the principal already has the maximum waiting
   * @throws AuditUnavailableException when the audit row can't be written: no request exists then
   */
  @SuppressWarnings("unchecked")
  public Ticket requestOrReuse(NewRequest r) {
    ObjectNode origin = origin(r.audit());
    ObjectNode canonical = ApprovalRows.canonical(r.call(), r.toolKey(), r.cfg().id(), r.cfg().name(),
        r.cfg().version(), origin);
    ObjectNode action = Codecs.action(r.preview());
    ActionInspector.Inspection inspection = ActionInspector.inspect(r.preview().text(), r.preview().language());
    action.putObject("inspection").put("hiddenChars", inspection.hiddenChars())
        .put("confusables", inspection.confusables());
    String digest = ApprovalRows.digest(canonical, action, r.preparedState());
    String actionSha = ApprovalRows.actionSha256(action);
    Env env = r.cfg().env();
    JsonNode section = policies.section(env, "approvals");
    Duration window = Duration.ofMinutes(section.path("timeoutMinutes").asInt(env == Env.DEV ? 5 : 10));
    CommentPolicy comment = commentPolicy(section.path("comment").asString(env == Env.PROD ? "required"
        : "optional"));
    Ticket ticket = writeTx.execute(s -> {
      Instant now = clock.instant();
      Optional<ApprovalEntity> existing = approvals.findFirstByEndpointIdAndPrincipalIdAndDigestAndStatus(
          r.endpointId(), r.caller().principalId(), digest, ApprovalStatus.PENDING);
      if (existing.isPresent() && existing.get().getExpiresAt().isAfter(now)) {
        ApprovalEntity a = existing.get();
        audit.received(r.audit(), new AuditWriter.Received(AuditWriter.Verdict.PENDING, r.classification(),
            a.getRuleId(), a.getId(), r.delivery(), null, null));
        return new Ticket(rows.view(a), true);
      }
      if (approvals.countByPrincipalIdAndStatus(r.caller().principalId(), ApprovalStatus.PENDING)
          >= MAX_PENDING_PER_PRINCIPAL) {
        throw new TooManyWaitingException();
      }
      ApprovalEntity a = ApprovalEntity.builder()
          .call(r.callId(), r.call().tool(), r.toolKey())
          .endpoint(r.endpointId(), r.endpointSlug())
          .connection(r.cfg().id(), r.cfg().name(), env)
          .principal(r.caller().kind(), r.caller().principalId(), cut(r.caller().label(), 64))
          .canonical(json.convertValue(canonical, Map.class), digest)
          .action(json.convertValue(action, Map.class), actionSha,
              r.preparedState() == null ? null : CanonicalJson.text(r.preparedState()))
          .agentNote(cut(r.agentNote(), 500))
          .rule(r.ruleId(), comment)
          .window(now, now.plus(window))
          .traceContext(r.traceparent())
          .build();
      rows.sign(a);
      approvals.saveAndFlush(a);
      Map<String, Object> detail = new LinkedHashMap<>();
      detail.put("rule", r.ruleId());
      detail.put("env", env.id());
      detail.put("by", r.caller().label());
      events.save(new ApprovalEventEntity(a, ApprovalEventKind.REQUESTED, detail, now));
      audit.received(r.audit(), new AuditWriter.Received(AuditWriter.Verdict.PENDING, r.classification(),
          r.ruleId(), a.getId(), r.delivery(), null, null));
      publisher.publishEvent(new ApprovalRequested(a.getId(), r.endpointSlug(), r.cfg().name(), env,
          r.call().tool(), r.preview().summary(), comment.name().toLowerCase(java.util.Locale.ROOT),
          a.getExpiresAt()));
      return new Ticket(rows.view(a), false);
    });
    return ticket;
  }

  /** Requests of one principal that wait for a person (the limit of §5 step 5). */
  public long pendingCount(String principalId) {
    Long n = readTx.execute(s -> approvals.countByPrincipalIdAndStatus(principalId, ApprovalStatus.PENDING));
    return n == null ? 0 : n;
  }

  public Optional<ApprovalView> find(String id) {
    return readTx.execute(s -> approvals.findById(id).map(rows::view));
  }

  // ── Lists (Approvals screen) ───────────────────────────────────────────────────────────────────────────────

  /** A page of the history; {@code nextCursor} continues it, {@code null} when there is no more. */
  public record Page(List<ApprovalView> items, String nextCursor) {}

  /** One entry of a request's timeline ({@code approval_event}): requested, notified, decided, running, … */
  public record TimelineEntry(long id, Instant at, String kind, JsonNode detail) {}

  /** Requests that wait for a person, the one closest to its auto-deny first. */
  public List<ApprovalView> pending() {
    return readTx.execute(s -> approvals.findByStatusOrderByExpiresAtAsc(ApprovalStatus.PENDING).stream()
        .map(rows::view).toList());
  }

  /** How many requests wait for a person (the sidebar's counter). */
  public long pendingTotal() {
    Long n = readTx.execute(s -> approvals.countByStatus(ApprovalStatus.PENDING));
    return n == null ? 0 : n;
  }

  /**
   * Requests that no longer wait, newest first.
   *
   * @param cursor the {@code nextCursor} of the previous page, or {@code null}
   * @throws ProblemException 422 for a cursor this method didn't hand out
   */
  public Page history(String cursor, int limit) {
    int size = Math.max(1, Math.min(limit, 200));
    org.springframework.data.domain.PageRequest page = org.springframework.data.domain.PageRequest.ofSize(size + 1);
    List<ApprovalView> rowsRead = readTx.execute(s -> {
      List<ApprovalEntity> found;
      if (cursor == null || cursor.isBlank()) {
        found = approvals.findByStatusNotOrderByCreatedAtDescIdDesc(ApprovalStatus.PENDING, page);
      } else {
        Cursor c = Cursor.parse(cursor);
        found = approvals.historyAfter(ApprovalStatus.PENDING, c.at(), c.id(), page);
      }
      return found.stream().map(rows::view).toList();
    });
    boolean more = rowsRead.size() > size;
    List<ApprovalView> items = more ? rowsRead.subList(0, size) : rowsRead;
    String next = more ? Cursor.of(items.get(items.size() - 1)) : null;
    return new Page(List.copyOf(items), next);
  }

  /** Requests made since {@code since} by status ({@code approved} counts completed and failed runs alike). */
  public Map<String, Long> statusCounts(Instant since) {
    Map<String, Long> out = new LinkedHashMap<>();
    readTx.executeWithoutResult(s -> approvals.statusesSince(since).forEach(status -> out.merge(
        ai.msrv.gaterook.core.platform.KebabCaseEnumConverter.idOf(status), 1L, Long::sum)));
    return out;
  }

  /** The request's timeline, oldest first. */
  public List<TimelineEntry> timeline(String id) {
    return readTx.execute(s -> events.findByApprovalIdOrderByIdAsc(id).stream()
        .map(e -> new TimelineEntry(e.getId(), e.getAt(), ai.msrv.gaterook.core.platform.KebabCaseEnumConverter.idOf(
            e.getKind()), e.getDetail() == null ? null : json.valueToTree(e.getDetail())))
        .toList());
  }

  /** A history cursor: base64url of {@code <createdAt ms>.<id>}, opaque to the UI. */
  private record Cursor(Instant at, String id) {

    static String of(ApprovalView a) {
      String raw = a.createdAt().toEpochMilli() + "." + a.id();
      return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(
          java.nio.charset.StandardCharsets.UTF_8));
    }

    static Cursor parse(String cursor) {
      try {
        String raw = new String(java.util.Base64.getUrlDecoder().decode(cursor),
            java.nio.charset.StandardCharsets.UTF_8);
        int dot = raw.indexOf('.');
        return new Cursor(Instant.ofEpochMilli(Long.parseLong(raw.substring(0, dot))), raw.substring(dot + 1));
      } catch (RuntimeException e) {
        throw ProblemException.unprocessable("Invalid cursor", "Start the list again.", "/cursor");
      }
    }
  }

  /**
   * The request if it belongs to this endpoint and principal; anything else is unknown to the caller (a handle of
   * another principal is not even confirmed to exist).
   */
  public Optional<ApprovalView> forCaller(String id, String endpointId, String principalId) {
    return find(id).filter(a -> Objects.equals(a.endpointId(), endpointId)
        && Objects.equals(a.principalId(), principalId));
  }

  /**
   * Waits up to {@code max} for the request to end (completed, rejected, expired, failed); returns its state then or
   * at the deadline. Holds no transaction while it waits.
   */
  public ApprovalView await(String id, Duration max) {
    long deadline = System.nanoTime() + Math.max(0, max.toNanos());
    while (true) {
      CompletableFuture<Void> next = waiters.next(id);
      ApprovalView now = find(id).orElseThrow(() -> new IllegalArgumentException("No request " + id));
      long left = deadline - System.nanoTime();
      if (now.terminal() || left <= 0) {
        return now;
      }
      try {
        // Re-read at least every second: a signal of another process (or a lost one) costs a poll, not an answer.
        ApprovalWaiters.await(next, Math.min(left / 1_000_000, 1_000));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return now;
      }
    }
  }

  // ── Decisions ───────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * A person's decision ({@code POST /api/approvals/{id}/decision}, a channel's button).
   *
   * @param via          {@code app}, {@code desktop-notification}, {@code slack}, {@code telegram}
   * @param digestPrefix what the person saw ({@code digest} or at least its first 8 characters): required to approve,
   *                     optional to deny
   * @throws ProblemException 404 unknown; 409 already decided or expired; 422 a missing required comment, a digest
   *     that is missing (Approve) or doesn't match, Approve from an OS notification
   */
  public ApprovalView decide(String id, Decision decision, String via, String comment, String digestPrefix) {
    return decide(id, decision, via, comment, digestPrefix, null);
  }

  /**
   * {@link #decide(String, Decision, String, String, String)} from a Slack or Telegram account, which the request then
   * names ({@code approval.decided_external_id}: the Slack user id, the Telegram {@code from.id}) for the audit; the
   * window never shows it.
   *
   * @param externalId the linked account that decided, or {@code null} in the app and from an OS notification
   */
  public ApprovalView decide(String id, Decision decision, String via, String comment, String digestPrefix,
      String externalId) {
    String external = externalId == null || externalId.isBlank() ? null : cut(externalId, 64);
    if (!CHANNELS.contains(via)) {
      throw ProblemException.unprocessable("Unknown channel", "Decide in the app, from a notification, in Slack or "
          + "in Telegram.", "/via");
    }
    if (decision == Decision.APPROVE && "desktop-notification".equals(via)) {
      throw ProblemException.unprocessable("Approve in the window", "A system notification can only deny. Open the "
          + "request in MCP Gateway to approve it.", "/decision");
    }
    String text = comment == null || comment.isBlank() ? null : cut(comment.strip(), 2_000);
    ApprovalEntity current = readTx.execute(s -> approvals.findById(id).orElse(null));
    if (current == null) {
      throw ProblemException.notFound("Request not found", "There is no approval request " + id + ".");
    }
    if (current.getStatus() != ApprovalStatus.PENDING) {
      throw ProblemException.conflict("Already decided", "The request is " + rows.view(current).status() + ".");
    }
    // §9 "Stricter production rules": Approve from Slack or Telegram on prod — never, whatever the comment rule says. The channels
    // never offer it (notifications.Eligibility); this holds even if one did.
    if (decision == Decision.APPROVE && ("slack".equals(via) || "telegram".equals(via))
        && current.getEnv() == ai.msrv.gaterook.connector.api.Env.PROD) {
      throw ProblemException.unprocessable("Approve in the window", "A production request is approved in MCP "
          + "Gateway only, never from Slack or Telegram.", "/via");
    }
    if (!clock.instant().isBefore(current.getExpiresAt())) {
      throw ProblemException.conflict("Expired", "The request expired: nothing ran. The agent has to ask again.");
    }
    // An Approve proves what the person saw; a Deny is always safe and may come without it (an OS notification).
    if (decision == Decision.APPROVE && (digestPrefix == null || digestPrefix.isBlank())) {
      throw ProblemException.unprocessable("Approve what you saw", "An approval names the action it approves: send "
          + "the request's digest with it.", "/digest");
    }
    if (digestPrefix != null && (digestPrefix.length() < 8 || !current.getDigest().startsWith(digestPrefix))) {
      throw ProblemException.unprocessable("The request changed", "This isn't the action you saw. Open the request "
          + "again.", "/digest");
    }
    if (decision == Decision.APPROVE && current.getCommentPolicy() == CommentPolicy.REQUIRED && text == null) {
      throw ProblemException.unprocessable("Comment required", "Add a comment to approve on " + current.getEnv().id()
          + ".", "/comment");
    }
    AuditWriter.Call call = rows.auditCall(current);
    if (decision == Decision.DENY) {
      writeTx.executeWithoutResult(s -> {
        Instant now = clock.instant();
        if (approvals.decide(id, ApprovalStatus.PENDING, ApprovalStatus.REJECTED, via, external, text, now) != 1) {
          throw conflict(id);
        }
        ApprovalEntity a = approvals.findById(id).orElseThrow();
        event(a, ApprovalEventKind.DECIDED, Map.of("decision", "rejected", "by", "you", "via", via), now);
        audit.resolved(call, new AuditWriter.Resolution(AuditWriter.Verdict.REJECTED, id, a.getRuleId(), via,
            AuditWriter.commentSha256(text), a.getActionSha256(), waited(a, now), null));
        resolved(id, "rejected", via);
      });
      return find(id).orElseThrow();
    }
    try {
      writeTx.executeWithoutResult(s -> {
        Instant now = clock.instant();
        if (approvals.decide(id, ApprovalStatus.PENDING, ApprovalStatus.RUNNING, via, external, text, now) != 1) {
          throw conflict(id);
        }
        ApprovalEntity a = approvals.findById(id).orElseThrow();
        event(a, ApprovalEventKind.DECIDED, Map.of("decision", "approved", "by", "you", "via", via), now);
        audit.resolved(call, new AuditWriter.Resolution(AuditWriter.Verdict.APPROVED, id, a.getRuleId(), via,
            AuditWriter.commentSha256(text), a.getActionSha256(), waited(a, now), null));
        resolved(id, "running", via);
        // Claimed before commit: a start-up check never mistakes this request for one a stopped core left running.
        executor.claim(id);
        afterCommit(() -> executor.submit(id));
      });
    } catch (AuditUnavailableException e) {
      log.error("The approval of {} couldn't be recorded in the audit: not executed", id, e);
      Boolean failed = writeTx.execute(s -> {
        if (approvals.finish(id, ApprovalStatus.PENDING, ApprovalStatus.FAILED, AUDIT_REFUSED_DECISION) != 1) {
          return false;
        }
        event(approvals.findById(id).orElseThrow(), ApprovalEventKind.FAILED, Map.of("reason", "audit"),
            clock.instant());
        resolved(id, "failed", via);
        return true;
      });
      if (Boolean.TRUE.equals(failed)) {
        // The call's own record of how it ended, so Activity doesn't show it pending for good: written now if the
        // audit takes it, else from the backlog (new calls wait for it, as they would for any refused row).
        try {
          audit.completed(call, new AuditWriter.Completion(AuditWriter.Verdict.ERROR, current.getRuleId(), id, null,
              via, null, null, null, waited(current, clock.instant()), null, null, null, null, null, "AUDIT",
              AUDIT_REFUSED_DECISION, null, current.getActionSha256(), null));
        } catch (AuditUnavailableException queued) {
          log.warn("The completed row of {} waits in the audit backlog", id);
        }
      }
    }
    return find(id).orElseThrow();
  }

  /**
   * A channel announced the request: one {@code notified} entry of its timeline ("Notified on the desktop and in
   * Slack"). Only an insert into the feed; the request itself is never written by a channel (§4).
   *
   * @param detail what the timeline shows: {@code channel} ({@code desktop}, {@code slack}, {@code telegram}),
   *               {@code state} ({@code sent}, {@code failed}) and why it failed; no agent note, no secret
   */
  public void notified(String id, Map<String, Object> detail) {
    writeTx.executeWithoutResult(s -> approvals.findById(id).ifPresent(a -> event(a, ApprovalEventKind.NOTIFIED,
        detail, clock.instant())));
  }

  /** The request's error when the audit refused the {@code resolved} row of an Approve. */
  static final String AUDIT_REFUSED_DECISION = "Audit unavailable: the decision couldn't be recorded, so nothing ran.";

  /**
   * The MCP client's user declined to open the approval link ({@code inputResponses…action = decline}): the request is
   * rejected, which is always safe.
   */
  public ApprovalView declineInClient(String id) {
    ApprovalEntity current = readTx.execute(s -> approvals.findById(id).orElse(null));
    if (current == null || current.getStatus() != ApprovalStatus.PENDING) {
      return current == null ? null : rows.view(current);
    }
    AuditWriter.Call call = rows.auditCall(current);
    String why = "Declined in the client. Not executed.";
    writeTx.executeWithoutResult(s -> {
      Instant now = clock.instant();
      if (approvals.rejectByCore(id, ApprovalStatus.PENDING, ApprovalStatus.REJECTED, "client", why, now) != 1) {
        return;
      }
      ApprovalEntity a = approvals.findById(id).orElseThrow();
      event(a, ApprovalEventKind.DECIDED, Map.of("decision", "rejected", "by", "client", "reason", why), now);
      audit.resolved(call, new AuditWriter.Resolution(AuditWriter.Verdict.REJECTED, id, a.getRuleId(), "client",
          null, a.getActionSha256(), waited(a, now), why));
      resolved(id, "rejected", "client");
    });
    return find(id).orElseThrow();
  }

  // ── Timeouts and retention ──────────────────────────────────────────────────────────────────────────────────

  /** Pending requests past their window become {@code expired}: a timeout is a denial. Returns how many. */
  public int expireDue() {
    List<ApprovalEntity> due = readTx.execute(s -> approvals.findByStatusAndExpiresAtBefore(ApprovalStatus.PENDING,
        clock.instant().plusMillis(1)));
    int expired = 0;
    for (ApprovalEntity a : due == null ? List.<ApprovalEntity>of() : due) {
      AuditWriter.Call call = rows.auditCall(a);
      try {
        Boolean done = writeTx.execute(s -> {
          Instant now = clock.instant();
          if (approvals.expire(a.getId(), ApprovalStatus.PENDING, ApprovalStatus.EXPIRED, "timeout", now) != 1) {
            return false;
          }
          ApprovalEntity row = approvals.findById(a.getId()).orElseThrow();
          event(row, ApprovalEventKind.EXPIRED, Map.of("at", now.toString()), now);
          audit.resolved(call, new AuditWriter.Resolution(AuditWriter.Verdict.EXPIRED, a.getId(), a.getRuleId(),
              "timeout", null, a.getActionSha256(), waited(row, now), null));
          resolved(a.getId(), "expired", "timeout");
          return true;
        });
        expired += Boolean.TRUE.equals(done) ? 1 : 0;
      } catch (RuntimeException e) {
        // The audit refused: the request stays pending (it can't be approved past its window) and expires next time.
        log.warn("Couldn't expire {}: {}", a.getId(), e.toString());
      }
    }
    return expired;
  }

  /** Results kept past 60 minutes are forgotten (Activity keeps their hash and preview). */
  public int forgetResults() {
    Integer n = writeTx.execute(s -> {
      List<ApprovalEntity> old = approvals.findByResultExpiresAtBefore(clock.instant());
      old.forEach(ApprovalEntity::forgetResult);
      approvals.saveAll(old);
      return old.size();
    });
    return n == null ? 0 : n;
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

  private void event(ApprovalEntity a, ApprovalEventKind kind, Map<String, Object> detail, Instant at) {
    events.save(new ApprovalEventEntity(a, kind, new LinkedHashMap<>(detail), at));
  }

  /** Inside the transaction of the change: listeners hear it after commit; waiters wake then. */
  private void resolved(String id, String status, String via) {
    publisher.publishEvent(new ApprovalResolved(id, status, via));
    afterCommit(() -> waiters.signal(id));
  }

  private static void afterCommit(Runnable r) {
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCommit() {
        r.run();
      }
    });
  }

  private ProblemException conflict(String id) {
    return ProblemException.conflict("Already decided", "Request " + id + " was decided or expired meanwhile.");
  }

  private static long waited(ApprovalEntity a, Instant now) {
    return Math.max(0, Duration.between(a.getCreatedAt(), now).toMillis());
  }

  private static ObjectNode origin(AuditWriter.Call c) {
    ObjectNode o = JsonNodeFactory.instance.objectNode();
    o.put("ingress", c.caller().ingress().name());
    o.put("ip", c.caller().ip());
    o.put("client", c.client());
    o.set("clientInfo", c.clientInfo());
    o.put("protocol", c.protocolVersion());
    o.put("traceId", c.traceId());
    o.put("spanId", c.spanId());
    o.put("traceFromClient", c.traceFromClient());
    return o;
  }

  private static CommentPolicy commentPolicy(String id) {
    return switch (id) {
      case "required" -> CommentPolicy.REQUIRED;
      case "recommended" -> CommentPolicy.RECOMMENDED;
      default -> CommentPolicy.OPTIONAL;
    };
  }

  private static String cut(String s, int max) {
    return s == null || s.length() <= max ? s : s.substring(0, max);
  }
}
