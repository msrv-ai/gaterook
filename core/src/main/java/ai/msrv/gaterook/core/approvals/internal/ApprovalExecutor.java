package ai.msrv.gaterook.core.approvals.internal;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.core.approvals.ApprovalResolved;
import ai.msrv.gaterook.core.approvals.internal.persistence.ApprovalEntity;
import ai.msrv.gaterook.core.approvals.internal.persistence.ApprovalEventEntity;
import ai.msrv.gaterook.core.approvals.internal.persistence.ApprovalEventKind;
import ai.msrv.gaterook.core.approvals.internal.persistence.ApprovalEventRepository;
import ai.msrv.gaterook.core.approvals.internal.persistence.ApprovalRepository;
import ai.msrv.gaterook.core.approvals.internal.persistence.ApprovalStatus;
import ai.msrv.gaterook.core.audit.AuditQueries;
import ai.msrv.gaterook.core.audit.AuditUnavailableException;
import ai.msrv.gaterook.core.audit.AuditWriter;
import ai.msrv.gaterook.core.connectors.ConnectorRegistry;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshot;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshotService;
import ai.msrv.gaterook.core.invocation.ConnectorInvoker;
import ai.msrv.gaterook.core.platform.CallTracing;
import ai.msrv.gaterook.core.platform.IntegrityAlerts;
import ai.msrv.gaterook.core.platform.PrincipalStatus;
import ai.msrv.gaterook.core.policy.Decision;
import ai.msrv.gaterook.core.policy.PolicyEngine;
import ai.msrv.gaterook.core.policy.PolicyService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs an approved request (ARCHITECTURE.md §5 step 11, §9 "Approve the exact action"): the stored call, never the
 * arguments of a later request. Before anything runs it checks once more, on the current state:
 * <ol>
 *   <li>the row's MAC, the digest and the action hash recomputed from what is stored ("changed outside MCP
 *       Gateway");</li>
 *   <li>the endpoint (there, enabled, its rows verified), the pair (the connection still on it, not Off), the
 *       connection's config version and the template version and operation the person approved — the binding that
 *       runs is that version's as the verified snapshot compiled it, never the one stored in the row;</li>
 *   <li>the principal (not revoked since: every {@link PrincipalStatus} that answers for its kind);</li>
 *   <li>{@code classify} and {@code PolicyEngine.decide} (a policy that now denies it wins).</li>
 * </ol>
 * Any change → {@code rejected} with the reason; otherwise {@code ctx.approved() = true} with the prepared state and the
 * approved payload. The masked result is kept 60 minutes; the {@code completed} row carries {@code action_sha256} =
 * {@code executed_sha256}. If the database refuses that row or the request's status change after the call ran, what
 * wasn't stored waits in the audit backlog and is retried (new calls are refused meanwhile, §5 step 11): the row, then
 * the status — the outcome is never lost while this process runs. A failure that may have applied the change is
 * {@code outcome_unknown}, never retried.
 *
 * <p>A request is {@code running} only while this process runs it: the decision claims it before its transaction
 * commits, and the claim ends with the run. A stop waits a bounded time for the calls in flight (the datasource closes
 * after this bean); whatever is still {@code running} at the next start — the core stopped, was killed or crashed
 * between the Approve and the outcome — becomes {@code failed} with {@code outcome_unknown}: it may have applied, and
 * it is never run again (§5 step 11). Unless its {@code completed} row made it to the audit: then it ends as that row
 * says, without a second one.
 */
@Component
public class ApprovalExecutor implements DisposableBean {

  private static final Logger log = LoggerFactory.getLogger(ApprovalExecutor.class);

  private final ApprovalRepository approvals;
  private final ApprovalEventRepository events;
  private final ApprovalRows rows;
  private final ApprovalWaiters waiters;
  private final EndpointSnapshotService snapshots;
  private final ConnectorRegistry registry;
  private final PolicyEngine engine;
  private final PolicyService policies;
  private final List<PrincipalStatus> principals;
  private final ConnectorInvoker invoker;
  private final AuditWriter audit;
  private final AuditQueries auditQueries;
  private final CallTracing tracing;
  private final IntegrityAlerts alerts;
  private final ApplicationEventPublisher publisher;
  private final JsonMapper json;
  private final Clock clock;
  private final TransactionTemplate readTx;
  private final TransactionTemplate writeTx;
  private final ExecutorService threads =
      Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("approved-call-", 0).factory());
  /** Requests this process runs or is about to run: a {@code running} row outside this set was left by a stop. */
  private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

  /** How long a stop waits for approved calls in flight: the longest tool timeout, under the launcher's 20 s halt. */
  static final Duration STOP_WAIT = Duration.ofSeconds(15);

  /** The outcome of a call the core lost track of: it may have applied. */
  static final String STOPPED_WHILE_RUNNING = "GateRook stopped while it ran, so whether it applied is unknown. "
      + "Not retried.";

  /** The approval arrived while the core was stopping: nothing ran. */
  static final String STOPPED_BEFORE_RUNNING = "GateRook was stopping, so it didn't run. Ask again.";

  ApprovalExecutor(ApprovalRepository approvals, ApprovalEventRepository events, ApprovalRows rows,
      ApprovalWaiters waiters, EndpointSnapshotService snapshots, ConnectorRegistry registry, PolicyEngine engine,
      PolicyService policies, List<PrincipalStatus> principals, ConnectorInvoker invoker, AuditWriter audit,
      AuditQueries auditQueries, CallTracing tracing, IntegrityAlerts alerts, ApplicationEventPublisher publisher,
      JsonMapper json, Clock clock, PlatformTransactionManager tx) {
    this.approvals = approvals;
    this.events = events;
    this.rows = rows;
    this.waiters = waiters;
    this.snapshots = snapshots;
    this.registry = registry;
    this.engine = engine;
    this.policies = policies;
    this.principals = List.copyOf(principals);
    this.invoker = invoker;
    this.audit = audit;
    this.auditQueries = auditQueries;
    this.tracing = tracing;
    this.alerts = alerts;
    this.publisher = publisher;
    this.json = json;
    this.clock = clock;
    this.readTx = new TransactionTemplate(tx);
    this.readTx.setReadOnly(true);
    this.writeTx = new TransactionTemplate(tx);
  }

  /**
   * Inside the Approve transaction, right after the request became {@code running}: this process will run it. A
   * rollback gives the claim back.
   */
  public void claim(String id) {
    inFlight.add(id);
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCompletion(int status) {
        if (status != STATUS_COMMITTED) {
          inFlight.remove(id);
        }
      }
    });
  }

  /** Runs request {@code id} on a virtual thread (called after the Approve transaction committed). */
  public void submit(String id) {
    inFlight.add(id);
    try {
      threads.execute(() -> {
        try {
          run(id);
        } catch (RuntimeException e) {
          log.error("Running approved request {} failed", id, e);
        } finally {
          inFlight.remove(id);
        }
      });
    } catch (RejectedExecutionException stopping) {
      // The core is stopping: nothing ran, and the request says so (or the next start does, if this fails too). Not
      // on this thread: it is still inside the decision's afterCommit, holding the single writer connection.
      Thread.ofVirtual().name("approved-call-refused").start(() -> {
        try {
          abandon(id, STOPPED_BEFORE_RUNNING, false);
        } catch (RuntimeException e) {
          log.warn("Couldn't record that approved request {} didn't run: {}", id, e.toString());
        } finally {
          inFlight.remove(id);
        }
      });
    }
  }

  /**
   * At start: a request still {@code running} that this process doesn't run was left by the previous one. If its
   * {@code completed} row is in the audit (the database took the row but not the status change, and the process
   * stopped before the retry), the request ends as that row says. Otherwise it may have applied, so it becomes
   * {@code failed} with {@code outcome_unknown}, its {@code completed} audit row says so, and it is never run again.
   */
  @EventListener(ApplicationReadyEvent.class)
  void reconcile() {
    List<ApprovalEntity> running = readTx.execute(s -> approvals.findByStatusOrderByExpiresAtAsc(
        ApprovalStatus.RUNNING));
    for (ApprovalEntity a : running == null ? List.<ApprovalEntity>of() : running) {
      if (inFlight.contains(a.getId())) {
        continue;
      }
      try {
        Optional<AuditQueries.Outcome> recorded = auditQueries.outcome(a.getCallId());
        if (recorded.isPresent()) {
          log.warn("Approved request {} ({} on {}) was running when GateRook stopped; the audit has its outcome: {}",
              a.getId(), a.getTool(), a.getConnectionName(), recorded.get().decision());
          closeFromAudit(a, recorded.get());
        } else {
          log.warn("Approved request {} ({} on {}) was running when GateRook stopped: outcome unknown, not retried",
              a.getId(), a.getTool(), a.getConnectionName());
          abandon(a.getId(), STOPPED_WHILE_RUNNING, true);
        }
      } catch (RuntimeException e) {
        log.error("Couldn't close approved request {} left running: {}", a.getId(), e.toString());
      }
    }
  }

  /**
   * The request ends as its {@code completed} row says, and no second row is written. The masked result lived only in
   * the stopped process: {@code approval_status} says it is no longer stored and points to Activity.
   */
  private void closeFromAudit(ApprovalEntity a, AuditQueries.Outcome outcome) {
    ApprovalStatus to = switch (outcome.decision()) {
      case "approved" -> ApprovalStatus.COMPLETED;
      case "rejected" -> ApprovalStatus.REJECTED;
      default -> ApprovalStatus.FAILED;
    };
    String error = to == ApprovalStatus.COMPLETED ? null
        : outcome.errorMessage() != null ? outcome.errorMessage() : STOPPED_WHILE_RUNNING;
    writeTx.executeWithoutResult(s -> {
      if (approvals.finish(a.getId(), ApprovalStatus.RUNNING, to, error) != 1) {
        return;
      }
      Instant now = clock.instant();
      ApprovalEntity row = approvals.findById(a.getId()).orElseThrow();
      if (to == ApprovalStatus.COMPLETED) {
        row.executed(outcome.executedSha256(), null, now, null);
        approvals.save(row);
      }
      Map<String, Object> detail = new LinkedHashMap<>();
      detail.put("fromAudit", true);
      if (error != null) {
        detail.put("reason", error);
        detail.put("outcomeUnknown", outcome.outcomeUnknown());
      }
      events.save(new ApprovalEventEntity(row, to == ApprovalStatus.COMPLETED ? ApprovalEventKind.COMPLETED
          : ApprovalEventKind.FAILED, detail, now));
      publisher.publishEvent(new ApprovalResolved(a.getId(), switch (to) {
        case COMPLETED -> "completed";
        case REJECTED -> "rejected";
        default -> "failed";
      }, a.getDecidedVia()));
      signalAfterCommit(a.getId());
    });
  }

  /**
   * What the checks found: the pair, its decision and the call to run, or why it doesn't run. The call's template
   * binding is the verified snapshot's, never the stored one: no MAC covers the binding in the row, only the template's
   * id, version and operation (through the digest), and the snapshot holds what that version compiled to.
   */
  private record Check(String refusal, EndpointSnapshot.Pair pair, Decision decision, Connector connector,
      ToolCall call) {

    static Check refused(String why) {
      return new Check(why, null, null, null, null);
    }
  }

  void run(String id) {
    ApprovalEntity a = readTx.execute(s -> approvals.findById(id).orElse(null));
    if (a == null || a.getStatus() != ApprovalStatus.RUNNING) {
      return;
    }
    AuditWriter.Call call = rows.auditCall(a);
    JsonNode canonical = rows.view(a).call();
    ToolCall toolCall = ApprovalRows.toolCall(a.getCallId(), canonical);
    Check check;
    try {
      check = check(a, canonical, toolCall);
    } catch (RuntimeException e) {
      log.error("Re-checking {} failed", id, e);
      check = Check.refused("The gateway couldn't check the request again. Not executed.");
    }
    if (check.refusal() != null) {
      refuse(a, call, check.refusal());
      return;
    }
    ActionPreview action = rows.view(a).action();
    JsonNode prepared = a.getPreparedState() == null ? null : json.readTree(a.getPreparedState());
    ConnectionConfig cfg = check.pair().config();
    Map<String, String> tags = new LinkedHashMap<>();
    tags.put("gen_ai.tool.name", a.getTool());
    tags.put("mcp.approval.id", id);
    ConnectorInvoker.Outcome outcome;
    long started = System.nanoTime();
    try (CallTracing.Started span = tracing.child("execute " + a.getTool(), a.getTraceContext(), tags)) {
      outcome = invoker.executeApproved(new ConnectorInvoker.Request(a.getCallId(), cfg, check.call(),
          check.decision().limits(), policies.maskPolicy(cfg.env()), span.traceId(), span.traceparent()),
          new ConnectorInvoker.Approval(id, prepared, action.payload()));
      if (!outcome.ok()) {
        span.error(outcome.errorKind().name());
      }
    }
    long durationMs = (System.nanoTime() - started) / 1_000_000;
    finish(a, call, outcome, durationMs);
  }

  private Check check(ApprovalEntity a, JsonNode canonical, ToolCall toolCall) {
    String changed = "The request was changed outside GateRook. Not executed.";
    JsonNode stored = json.valueToTree(a.getAction());
    JsonNode prepared = a.getPreparedState() == null ? null : json.readTree(a.getPreparedState());
    if (!rows.verify(a) || !ApprovalRows.digest(canonical, stored, prepared).equals(a.getDigest())
        || !ApprovalRows.actionSha256(stored).equals(a.getActionSha256())) {
      alerts.report("approval", a.getId(), a.getId(), "approval row");
      return Check.refused(changed);
    }
    if (a.getEndpointId() == null) {
      return Check.refused("Endpoint " + a.getEndpointSlug() + " was deleted. Not executed.");
    }
    Optional<EndpointSnapshot> snapshot = snapshots.get(a.getEndpointId());
    if (snapshot.isEmpty()) {
      return Check.refused("Endpoint " + a.getEndpointSlug() + " was deleted. Not executed.");
    }
    EndpointSnapshot s = snapshot.get();
    if (s.blocked()) {
      return Check.refused(s.slug() + " was changed outside GateRook. Not executed.");
    }
    if (!s.enabled()) {
      return Check.refused("Endpoint " + s.slug() + " is disabled. Not executed.");
    }
    String connectionId = canonical.path("connection").path("id").asString();
    EndpointSnapshot.Pair pair = s.pairs().stream().filter(p -> p.config().id().equals(connectionId)
        && p.toolKey().equals(a.getToolKey())).findFirst().orElse(null);
    if (pair == null) {
      return Check.refused(a.getConnectionName() + " is no longer on " + s.slug() + ". Not executed.");
    }
    if (!pair.on()) {
      return Check.refused(a.getTool() + " is now Off on " + s.slug() + ". Not executed.");
    }
    if (pair.config().version() != canonical.path("connection").path("version").asLong()) {
      return Check.refused(a.getConnectionName() + " changed after approval. Not executed.");
    }
    JsonNode template = canonical.path("template");
    if (template.isObject() != (pair.template() != null) || (pair.template() != null
        && (!pair.template().templateId().equals(template.path("id").asString())
        || pair.template().version() != template.path("version").asInt()
        || !java.util.Objects.equals(pair.template().opKey(), template.path("opKey").asString(null))))) {
      return Check.refused("Template changed after approval. Not executed.");
    }
    if (PrincipalStatus.revoked(principals, a.getPrincipalKind(), a.getPrincipalId())) {
      return Check.refused("Access of " + a.getClientLabel() + " to " + s.slug() + " was revoked. Not executed.");
    }
    // What runs: the stored arguments with the binding of the approved template version as the snapshot compiled it.
    ToolCall verified = new ToolCall(toolCall.callId(), toolCall.tool(), toolCall.connection(), toolCall.arguments(),
        pair.template());
    Optional<Connector> connector = registry.connectorFor(pair.config());
    if (connector.isEmpty()) {
      return Check.refused(pair.manifest().name() + " isn't available. Not executed.");
    }
    Classification classification;
    try {
      classification = connector.get().classify(verified, pair.config(), engine.view(pair.view(), pair.config()));
    } catch (RuntimeException e) {
      classification = null;
    }
    Decision decision = engine.decide(pair.view(), pair.config(), classification);
    switch (decision.outcome()) {
      case DENY -> {
        return Check.refused("Denied by policy " + decision.ruleId() + ": " + decision.reason() + " Not executed.");
      }
      case UNKNOWN_TOOL -> {
        return Check.refused(a.getTool() + " is now Off on " + s.slug() + ". Not executed.");
      }
      default -> {
        return new Check(null, pair, decision, connector.get(), verified);
      }
    }
  }

  /**
   * The person approved, but the request can't run as approved: rejected by the gateway ({@code decided_via} core),
   * with the reason, nothing ran.
   */
  private void refuse(ApprovalEntity a, AuditWriter.Call call, String reason) {
    BooleanSupplier change = () -> {
      if (approvals.refuse(a.getId(), ApprovalStatus.RUNNING, ApprovalStatus.REJECTED, "core", reason) != 1) {
        return false;
      }
      Instant now = clock.instant();
      ApprovalEntity row = approvals.findById(a.getId()).orElseThrow();
      events.save(new ApprovalEventEntity(row, ApprovalEventKind.FAILED, Map.of("reason", reason), now));
      publisher.publishEvent(new ApprovalResolved(a.getId(), "rejected", "core"));
      signalAfterCommit(a.getId());
      return true;
    };
    record(change, call, completion(a, AuditWriter.Verdict.REJECTED, null, null, "REJECTED", reason, false,
        a.getActionSha256(), null));
  }

  /**
   * A {@code running} request that won't run or whose outcome this process lost: {@code failed} with the reason, an
   * event, the decision's listeners and waiters told, and a {@code completed} audit row (with {@code outcome_unknown}
   * when it may have applied).
   */
  private void abandon(String id, String reason, boolean outcomeUnknown) {
    ApprovalEntity a = readTx.execute(s -> approvals.findById(id).orElse(null));
    if (a == null || a.getStatus() != ApprovalStatus.RUNNING) {
      return;
    }
    AuditWriter.Call call = rows.auditCall(a);
    BooleanSupplier change = () -> {
      if (approvals.finish(id, ApprovalStatus.RUNNING, ApprovalStatus.FAILED, reason) != 1) {
        return false;
      }
      Instant now = clock.instant();
      ApprovalEntity row = approvals.findById(id).orElseThrow();
      Map<String, Object> detail = new LinkedHashMap<>();
      detail.put("reason", reason);
      detail.put("outcomeUnknown", outcomeUnknown);
      events.save(new ApprovalEventEntity(row, ApprovalEventKind.FAILED, detail, now));
      publisher.publishEvent(new ApprovalResolved(id, "failed", a.getDecidedVia()));
      signalAfterCommit(id);
      return true;
    };
    record(change, call, completion(a, AuditWriter.Verdict.ERROR, null, null,
        outcomeUnknown ? "OUTCOME_UNKNOWN" : "STOPPED", reason, outcomeUnknown, a.getActionSha256(), null));
  }

  /**
   * The status change and its {@code completed} row in one transaction. A change that lost its CAS writes no row.
   * When the database refuses any of it after the call ran, nothing of the outcome may be lost (§5 step 11):
   * <ul>
   *   <li>the row: the audit refused it ({@link AuditUnavailableException}, it is in the backlog already), or the
   *       status change or the commit failed — then it goes to the backlog now;</li>
   *   <li>the change: tried again on its own, and if that fails too, retried with the backlog until it commits.</li>
   * </ul>
   * Either way new calls are refused until both are stored, and {@code approval_status} says {@code running} until
   * the change is. A stop before then leaves the request to {@link #reconcile}, which ends it from the row if that got
   * written.
   */
  private void record(BooleanSupplier change, AuditWriter.Call call, AuditWriter.Completion completion) {
    String id = completion.approvalId();
    try {
      writeTx.executeWithoutResult(s -> {
        if (change.getAsBoolean()) {
          audit.completed(call, completion);
        }
      });
      return;
    } catch (AuditUnavailableException e) {
      log.error("The completed row of approved request {} waits in the audit backlog", id);
    } catch (RuntimeException e) {
      // The status change or the commit failed: neither the change nor the row is stored. This process owns the
      // running request, so its change would have won the CAS: the row is due.
      log.error("Couldn't store the outcome of approved request {}: {}", id, e.toString());
      audit.completedLater(call, completion);
    }
    Runnable status = () -> writeTx.executeWithoutResult(s -> change.getAsBoolean());
    try {
      status.run();
    } catch (RuntimeException e) {
      audit.retryUntilWritten("the status of approved request " + id, status);
    }
  }

  private void finish(ApprovalEntity a, AuditWriter.Call call, ConnectorInvoker.Outcome outcome, long durationMs) {
    ApprovalStatus to = outcome.ok() ? ApprovalStatus.COMPLETED : ApprovalStatus.FAILED;
    String error = outcome.ok() ? null : outcome.errorMessage();
    String executedSha = ApprovalRows.actionSha256(json.valueToTree(a.getAction()));
    @SuppressWarnings("unchecked")
    Map<String, Object> result = json.convertValue(Codecs.result(outcome.result()), Map.class);
    BooleanSupplier change = () -> {
      if (approvals.finish(a.getId(), ApprovalStatus.RUNNING, to, error) != 1) {
        return false;
      }
      Instant now = clock.instant();
      ApprovalEntity row = approvals.findById(a.getId()).orElseThrow();
      row.executed(executedSha, result, now, now.plus(Duration.ofMinutes(60)));
      approvals.save(row);
      Map<String, Object> detail = new LinkedHashMap<>();
      detail.put("durationMs", durationMs);
      detail.put("resultBytes", outcome.resultBytes());
      if (error != null) {
        detail.put("error", error);
      }
      events.save(new ApprovalEventEntity(row, outcome.ok() ? ApprovalEventKind.COMPLETED : ApprovalEventKind.FAILED,
          detail, now));
      publisher.publishEvent(new ApprovalResolved(a.getId(), to == ApprovalStatus.COMPLETED ? "completed" : "failed",
          a.getDecidedVia()));
      signalAfterCommit(a.getId());
      return true;
    };
    record(change, call, completion(a, outcome.ok() ? AuditWriter.Verdict.APPROVED : AuditWriter.Verdict.ERROR,
        outcome, durationMs, outcome.errorKind() == null ? null : outcome.errorKind().name(), error,
        outcome.outcomeUnknown(), a.getActionSha256(), executedSha));
  }

  private static AuditWriter.Completion completion(ApprovalEntity a, AuditWriter.Verdict verdict,
      ConnectorInvoker.Outcome outcome, Long durationMs, String errorKind, String error, boolean outcomeUnknown,
      String actionSha, String executedSha) {
    long waited = a.getDecidedAt() == null ? 0 : Math.max(0, Duration.between(a.getCreatedAt(), a.getDecidedAt())
        .toMillis());
    return new AuditWriter.Completion(verdict, a.getRuleId(), a.getId(), null, a.getDecidedVia(), durationMs,
        outcome == null || durationMs == null ? null : Math.max(0, durationMs - outcome.resourceMs()),
        outcome == null ? null : outcome.resourceMs(), waited, outcome == null ? null : outcome.resultBytes(),
        outcome == null ? null : outcome.resultSha256(), outcome == null ? null : outcome.preview(),
        outcome == null ? null : outcome.maskedCount(), outcome == null ? null : outcome.truncated(), errorKind, error,
        outcomeUnknown ? Boolean.TRUE : null, actionSha, executedSha);
  }

  private void signalAfterCommit(String id) {
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCommit() {
        waiters.signal(id);
      }
    });
  }

  /**
   * Waits for the approved calls in flight before the datasource closes (this bean depends on it, so it goes first):
   * their outcome and audit row get written. One still running after {@link #STOP_WAIT} is reported at the next
   * start as {@code outcome_unknown}.
   */
  @Override
  public void destroy() throws InterruptedException {
    threads.shutdown();
    if (!threads.awaitTermination(STOP_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
      log.warn("Approved calls still running at stop ({}): the next start reports them as outcome unknown", inFlight);
    }
  }
}
