package ai.msrv.gaterook.core.audit;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.audit.internal.AuditBacklog;
import ai.msrv.gaterook.core.audit.internal.AuditCommits;
import ai.msrv.gaterook.core.audit.internal.CallRow;
import ai.msrv.gaterook.core.audit.internal.ChainHash;
import ai.msrv.gaterook.core.audit.internal.TraceBook;
import ai.msrv.gaterook.core.audit.internal.persistence.ApprovalDeliveryMode;
import ai.msrv.gaterook.core.audit.internal.persistence.CallDecision;
import ai.msrv.gaterook.core.audit.internal.persistence.CallPhase;
import ai.msrv.gaterook.core.audit.internal.persistence.ToolCallEntity;
import ai.msrv.gaterook.core.audit.internal.persistence.ToolCallRepository;
import ai.msrv.gaterook.core.platform.Caller;
import ai.msrv.gaterook.core.platform.EventBus;
import ai.msrv.gaterook.core.platform.KebabCaseEnumConverter;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The audit of agent calls, fail-closed (ARCHITECTURE.md §5 steps 8, 10, 11; §9 "Fail-closed append-only audit"):
 * one {@code tool_call} row per phase of a call, each joined to the hash chain as it is inserted.
 * <ul>
 *   <li>{@link #received} — the decision, before anything runs; if it can't be written the call doesn't run;</li>
 *   <li>{@link #resolved} — a person (or the timeout) decided an approval request, in the transaction of that
 *       decision: no approved call runs without it;</li>
 *   <li>{@link #completed} — the outcome, before a READ result goes to the agent. A row that can't be written waits in
 *       a bounded backlog (and {@code core.log}) and is retried; while it waits {@link #available()} is false and the
 *       MCP layer refuses new calls. So does a write that belongs with such a row
 *       ({@link #retryUntilWritten}).</li>
 * </ul>
 * The same call repeated while its request is pending reuses the request, under a call id of its own (§5 step 11): the
 * request's {@code resolved} and {@code completed} rows are written for each such call too, with its own identity, so
 * that no call stays {@code pending} in Activity and Traces after the request ended.
 * A request that ended without running (rejected, expired) lasted as long as it waited: its rows carry that wait as
 * the call's {@code duration_ms} too, so Activity shows "52 s" rather than nothing and Insights counts the waits (K5).
 * Arguments, results and errors arrive already masked: this class stores, it never sees a secret. Each committed row
 * is announced as {@code audit.call} (Activity › Live) and refreshes its trace.
 *
 * <p>The writer pool has one connection with {@code BEGIN IMMEDIATE}: reading the chain's head and inserting the next
 * row are serialized with every other write of the core.
 */
@Component
public class AuditWriter {

  private static final Logger log = LoggerFactory.getLogger(AuditWriter.class);

  /** Result previews are cut to this many characters (the column holds 4 KB of text). */
  public static final int PREVIEW_CHARS = 4_096;

  /** {@code tool_call.decision}: the Decision facet of Activity. */
  public enum Verdict { ALLOWED, APPROVED, PENDING, DENIED, REJECTED, EXPIRED, ERROR }

  /** {@code tool_call.approval_delivery}: how the outcome of an approval reaches the agent (A3). */
  public enum Delivery { MRTR, HANDLE, TASK }

  /**
   * What every row of one call repeats: the call id, where it went, who made it, how it was traced.
   *
   * @param argsMasked      the arguments after masking, or {@code null}
   * @param traceFromClient the trace id came from the client's {@code traceparent}: the call belongs to an agent's
   *                        task and its trace is summarized for Activity › Traces
   * @param facets          non-secret facts for the query language and the summarizer (runbook, template, task)
   */
  public record Call(String callId, String endpointSlug, String tool, String toolKey, String connectionName, Env env,
      Caller caller, String client, JsonNode clientInfo, String protocolVersion, String traceId, String spanId,
      boolean traceFromClient, JsonNode argsMasked, String argsSha256, JsonNode facets) {

    public Call {
      Objects.requireNonNull(callId, "callId");
      Objects.requireNonNull(endpointSlug, "endpointSlug");
      Objects.requireNonNull(tool, "tool");
    }
  }

  /** Phase {@code received}: the decision before anything runs. */
  public record Received(Verdict verdict, Classification.Kind classification, String ruleId, String approvalId,
      Delivery delivery, String errorKind, String errorMessage) {

    public static Received of(Verdict verdict, Classification.Kind classification, String ruleId) {
      return new Received(verdict, classification, ruleId, null, null, null, null);
    }
  }

  /** Phase {@code resolved}: an approval request was decided. */
  public record Resolution(Verdict verdict, String approvalId, String ruleId, String decidedVia,
      String commentSha256, String actionSha256, Long approvalWaitMs, String errorMessage) {}

  /** Phase {@code completed}: what happened, with sizes and timings; text fields already masked. */
  public record Completion(Verdict verdict, String ruleId, String approvalId, Delivery delivery, String decidedVia,
      Long durationMs, Long gatewayMs, Long resourceMs, Long approvalWaitMs, Long resultBytes, String resultSha256,
      String resultPreview, Integer maskedCount, Boolean truncated, String errorKind, String errorMessage,
      Boolean outcomeUnknown, String actionSha256, String executedSha256) {}

  private final ToolCallRepository calls;
  private final AuditBacklog backlog;
  private final TraceBook traces;
  private final AuditCommits commits;
  private final EventBus events;
  private final JsonMapper json;
  private final Clock clock;
  private final TransactionTemplate writeTx;

  public AuditWriter(ToolCallRepository calls, AuditBacklog backlog, TraceBook traces, AuditCommits commits,
      EventBus events, JsonMapper json, Clock clock, PlatformTransactionManager transactions) {
    this.calls = calls;
    this.backlog = backlog;
    this.traces = traces;
    this.commits = commits;
    this.events = events;
    this.json = json;
    this.clock = clock;
    this.writeTx = new TransactionTemplate(transactions);
    this.writeTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
  }

  /**
   * Whether new calls may start: no {@code completed} row (nor a write that belongs with one) is waiting to be written.
   * A completed mutation whose row is still in the backlog means the audit lags behind what happened, and nothing else
   * may happen until it caught up.
   */
  public boolean available() {
    return backlog.isEmpty();
  }

  /**
   * Writes the {@code received} row: in the caller's transaction when there is one (an approval request and its
   * row commit together), else in its own.
   *
   * @throws AuditUnavailableException when the row can't be written: the call must not run
   */
  public void received(Call call, Received r) {
    write(receivedRow(call, r, now()), call);
  }

  /**
   * Writes the {@code resolved} row inside the transaction of the decision (the approval's status change): if it
   * can't be written, the decision rolls back.
   *
   * @throws AuditUnavailableException when the row can't be written
   * @throws IllegalStateException     outside a transaction
   */
  public void resolved(Call call, Resolution r) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("A resolved row is written in the transaction of the decision");
    }
    write(resolvedRow(call, r, now()), call);
  }

  /**
   * Writes the {@code completed} row. When that fails the row goes to the backlog and is retried; the caller decides
   * what the failure means: a READ result is not handed out, an executed mutation stays executed.
   *
   * @throws AuditUnavailableException when the row couldn't be written now (it waits in the backlog)
   */
  public void completed(Call call, Completion c) {
    CallRow row = completedRow(call, c);
    try {
      write(row, call);
    } catch (AuditUnavailableException e) {
      queue(row, call);
      throw e;
    }
  }

  /**
   * Puts the {@code completed} row in the backlog without trying it now: the transaction it belonged to failed after
   * the call ran (the status change of its approval request, or the commit), so it wasn't written, and an executed
   * mutation's row must not be lost. New calls are refused until it is written.
   */
  public void completedLater(Call call, Completion c) {
    queue(completedRow(call, c), call);
  }

  /**
   * Retries {@code write} with the backlog every second, after its rows, until it returns normally; until then
   * {@link #available()} is false. For what must be stored with a {@code completed} row the database refused: the
   * status of an approved request, which says how the request ended. In memory: a stop before it succeeds leaves it to
   * the next start.
   */
  public void retryUntilWritten(String what, Runnable write) {
    if (!backlog.offer(new AuditBacklog.Retry(what, write))) {
      log.error("Audit backlog full: {} is lost until the next start", what);
    } else {
      log.error("New calls are refused until {} is written: the database refuses writes", what);
    }
  }

  /**
   * Writes one row now, chained after the current head; the backlog uses it to catch up. A {@code completed} row whose
   * call already has one (a commit that reported a failure but went through) isn't written twice.
   */
  public void writeQueued(CallRow row, String traceId) {
    List<Saved> saved = writeTx.execute(s -> alreadyCompleted(row) ? List.<Saved>of()
        : insertWithRepeats(row, traceId));
    saved.forEach(this::afterCommit);
  }

  private boolean alreadyCompleted(CallRow row) {
    return row.phase() == CallPhase.COMPLETED && calls.findByCallIdOrderByIdAsc(row.callId()).stream()
        .anyMatch(r -> r.getPhase() == CallPhase.COMPLETED);
  }

  private CallRow completedRow(Call call, Completion c) {
    return completedRow(call, c, now());
  }

  /** The {@code received} row of {@code call} as written at {@code at} (the call path: now; the demo's past). */
  CallRow receivedRow(Call call, Received r, Instant at) {
    return row(call, at, CallPhase.RECEIVED, decision(r.verdict()), r.classification(), r.ruleId(), r.approvalId(),
        delivery(r.delivery()), null, null, null, null, null, null, null, null, null, null, null, null,
        r.errorKind(), r.errorMessage(), null, true, null);
  }

  CallRow resolvedRow(Call call, Resolution r, Instant at) {
    return row(call, at, CallPhase.RESOLVED, decision(r.verdict()), null, r.ruleId(), r.approvalId(), null,
        r.decidedVia(), r.commentSha256(), r.actionSha256(), null, endedWaiting(r.verdict(), null,
            r.approvalWaitMs()), null, null, r.approvalWaitMs(), null, null, null, null, null, r.errorMessage(),
        null, false, null);
  }

  CallRow completedRow(Call call, Completion c, Instant at) {
    return row(call, at, CallPhase.COMPLETED, decision(c.verdict()), null, c.ruleId(), c.approvalId(),
        delivery(c.delivery()), c.decidedVia(), null, c.actionSha256(), c.executedSha256(),
        endedWaiting(c.verdict(), c.durationMs(), c.approvalWaitMs()), c.gatewayMs(), c.resourceMs(),
        c.approvalWaitMs(), c.resultBytes(), c.resultSha256(), cut(c.resultPreview(), PREVIEW_CHARS),
        c.maskedCount(), c.errorKind(), c.errorMessage(), c.outcomeUnknown(), false, c.truncated());
  }

  /**
   * The call's duration: what it measured, else — for a request rejected or expired, which never ran — the time it
   * waited for a person (REMAINING §4: Activity's Duration and Insights' waits had nothing for those).
   */
  private static Long endedWaiting(Verdict verdict, Long durationMs, Long approvalWaitMs) {
    if (durationMs != null) {
      return durationMs;
    }
    return verdict == Verdict.REJECTED || verdict == Verdict.EXPIRED ? approvalWaitMs : null;
  }

  /** The time a row written now carries: milliseconds, like the column. */
  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MILLIS);
  }

  private void queue(CallRow row, Call call) {
    if (!backlog.offer(row, call.traceFromClient() ? call.traceId() : null)) {
      log.error("Audit backlog full: the completed row of {} ({} on {}) is lost", call.callId(), call.tool(),
          call.endpointSlug());
    } else {
      log.error("The completed row of {} ({} on {}) waits in the audit backlog: new calls are refused until it is "
          + "written", call.callId(), call.tool(), call.endpointSlug());
    }
  }

  private void write(CallRow row, Call call) {
    List<Saved> saved;
    try {
      saved = writeTx.execute(s -> insertWithRepeats(row, call.traceFromClient() ? call.traceId() : null));
    } catch (RuntimeException e) {
      throw new AuditUnavailableException("The audit row of " + call.callId() + " couldn't be written", e);
    }
    saved.forEach(this::afterCommit);
  }

  /** A row written, and the trace it refreshes: its own call's ({@code known}), or another's only if it is a trace. */
  private record Saved(ToolCallEntity entity, String traceId, boolean known) {}

  /**
   * The row and, when it resolves or completes an approval request, the same row for every other call that reused
   * the request (their {@code received} rows name it; index {@code tool_call_approval}). One transaction: all or none.
   */
  private List<Saved> insertWithRepeats(CallRow row, String traceId) {
    List<Saved> out = new ArrayList<>();
    out.add(new Saved(insert(row), traceId, true));
    if (row.approvalId() != null && row.phase() != CallPhase.RECEIVED) {
      Set<String> done = new HashSet<>();
      done.add(row.callId());
      for (ToolCallEntity other : calls.findByApprovalIdAndPhaseOrderByIdAsc(row.approvalId(), CallPhase.RECEIVED)) {
        if (done.add(other.getCallId())) {
          out.add(new Saved(insert(row.repeatedBy(other)), other.getTraceId(), false));
        }
      }
    }
    return out;
  }

  private ToolCallEntity insert(CallRow row) {
    String prev = calls.findTopByOrderByIdDesc().map(ToolCallEntity::getRowSha256).orElse(ChainHash.GENESIS);
    return calls.save(row.chained(prev));
  }

  /** Live and Traces hear a row only once it committed (with the caller's transaction, if there was one). */
  private void afterCommit(Saved row) {
    ToolCallEntity saved = row.entity();
    String traceId = row.traceId();
    Runnable announce = () -> {
      commits.committed();
      try {
        events.publish("audit.call", event(saved));
        if (traceId != null && row.known()) {
          traces.touch(traceId);
        } else if (traceId != null) {
          traces.touchIfKnown(traceId);
        }
      } catch (RuntimeException e) {
        log.debug("Couldn't announce audit row {}: {}", saved.getId(), e.toString());
      }
    };
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          announce.run();
        }
      });
    } else {
      announce.run();
    }
  }

  private static ObjectNode event(ToolCallEntity r) {
    ObjectNode n = JsonNodeFactory.instance.objectNode();
    n.put("id", r.getId());
    n.put("callId", r.getCallId());
    n.put("at", r.getCreatedAt().toString());
    n.put("phase", KebabCaseEnumConverter.idOf(r.getPhase()));
    n.put("decision", KebabCaseEnumConverter.idOf(r.getDecision()));
    n.put("tool", r.getToolName());
    n.put("connection", r.getConnectionName());
    n.put("env", r.getEnv() == null ? null : r.getEnv().id());
    n.put("client", r.getClient());
    n.put("endpoint", r.getEndpointSlug());
    n.put("token", r.getTokenLabel());
    n.put("durationMs", r.getDurationMs());
    n.put("rule", r.getRuleId());
    n.put("approvalId", r.getApprovalId());
    n.put("traceId", r.getTraceId());
    return n;
  }

  private CallRow row(Call call, Instant at, CallPhase phase, CallDecision decision,
      Classification.Kind classification, String ruleId, String approvalId, ApprovalDeliveryMode delivery,
      String decidedVia, String commentSha256, String actionSha256, String executedSha256, Long durationMs,
      Long gatewayMs, Long resourceMs, Long approvalWaitMs, Long resultBytes, String resultSha256,
      String resultPreview, Integer maskedCount, String errorKind, String errorMessage, Boolean outcomeUnknown,
      boolean withArguments, Boolean truncated) {
    Caller caller = call.caller();
    return new CallRow(call.callId(), phase, at.truncatedTo(ChronoUnit.MILLIS), cut(call.endpointSlug(), 63),
        cut(call.tool(), 128),
        decision, cut(call.toolKey(), 256), cut(call.connectionName(), 64), call.env(),
        caller == null ? null : caller.kind(), caller == null ? null : caller.ingress(),
        caller == null ? null : cut(caller.label(), 64), caller == null ? null : cut(caller.ip(), 45),
        cut(call.client(), 32), map(call.clientInfo()), cut(call.protocolVersion(), 10), call.traceId(),
        call.spanId(), withArguments ? map(call.argsMasked()) : null, withArguments ? call.argsSha256() : null,
        classification, cut(ruleId, 128), approvalId, delivery, cut(decidedVia, 24), commentSha256, actionSha256,
        executedSha256, durationMs, gatewayMs, resourceMs, approvalWaitMs, resultBytes, resultSha256, resultPreview,
        maskedCount, truncated, cut(errorKind, 24), cut(errorMessage, 1000), outcomeUnknown,
        withArguments ? map(call.facets()) : null);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> map(JsonNode node) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return null;
    }
    if (!node.isObject()) {
      ObjectNode wrapped = JsonNodeFactory.instance.objectNode();
      wrapped.set("value", node);
      node = wrapped;
    }
    return json.convertValue(node, Map.class);
  }

  private static CallDecision decision(Verdict v) {
    return CallDecision.valueOf(v.name());
  }

  private static ApprovalDeliveryMode delivery(Delivery d) {
    return d == null ? null : ApprovalDeliveryMode.valueOf(d.name());
  }

  static String cut(String value, int max) {
    if (value == null || value.length() <= max) {
      return value;
    }
    int end = max;
    if (Character.isHighSurrogate(value.charAt(end - 1))) {
      end--;
    }
    return value.substring(0, end);
  }

  /** SHA-256 hex of a person's comment ({@code tool_call.comment_sha256}): the text itself stays on the request. */
  public static String commentSha256(String comment) {
    return comment == null || comment.isEmpty() ? null : ChainHash.sha256(comment);
  }
}
