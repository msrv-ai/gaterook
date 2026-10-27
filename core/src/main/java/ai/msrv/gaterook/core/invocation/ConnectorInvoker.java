package ai.msrv.gaterook.core.invocation;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connector.api.PreparedAction;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.core.connectors.CallContext;
import ai.msrv.gaterook.core.connectors.CallContexts;
import ai.msrv.gaterook.core.connectors.ConnectorRegistry;
import ai.msrv.gaterook.core.invocation.internal.ApprovedContext;
import ai.msrv.gaterook.core.platform.MaskPolicy;
import ai.msrv.gaterook.core.platform.Masker;
import ai.msrv.gaterook.core.platform.Taint;
import ai.msrv.gaterook.core.secrets.SecretService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Runs one call on a connector (ARCHITECTURE.md §5 steps 10–11): the connection's own secrets through a
 * {@code ScopedSecretResolver} (every value tainted), the {@link ToolGuard} (bulkhead, circuit breaker, deadline), the
 * connector, then the {@link Masker} with the call's taint and the environment's masking policy, then the
 * {@link ResultLimiter}. What comes back is what the agent may see; the caller writes the audit's {@code completed} row
 * before handing it out.
 *
 * <p>Never transactional and holds no database connection while the resource works (§5, the waits are up to 30 s).
 * {@link #executeApproved} is the only path to a context with {@code approved() == true}; only the approval executor
 * calls it.
 */
@Component
public class ConnectorInvoker {

  private static final Logger log = LoggerFactory.getLogger(ConnectorInvoker.class);

  /** Budget of {@code prepare} (dry runs, diffs) before a request exists. */
  public static final Duration PREPARE_TIMEOUT = Duration.ofSeconds(5);

  /** Budget of a connector's {@code describe} contribution to {@code describe_connection}. */
  public static final Duration DESCRIBE_TIMEOUT = Duration.ofSeconds(2);

  /** One call. */
  public record Request(String callId, ConnectionConfig cfg, ToolCall call, Limits limits, MaskPolicy mask,
      String traceId, String traceparent) {

    public Request {
      Objects.requireNonNull(callId, "callId");
      Objects.requireNonNull(cfg, "cfg");
      Objects.requireNonNull(limits, "limits");
      Objects.requireNonNull(mask, "mask");
    }
  }

  /** What an approval hands its execution: the request id, {@code prepare}'s state, the approved payload. */
  public record Approval(String approvalId, JsonNode preparedState, JsonNode payload) {}

  /**
   * What happened, masked and cut.
   *
   * @param errorKind      the connector's error kind, {@code null} when it answered
   * @param outcomeUnknown an approved call failed in a way that may have applied it (dropped during commit, timeout)
   */
  public record Outcome(ToolResult result, ConnectorException.Kind errorKind, String errorMessage, long resourceMs,
      int maskedCount, long resultBytes, String resultSha256, String preview, boolean outcomeUnknown) {

    public boolean ok() {
      return errorKind == null;
    }

    public boolean truncated() {
      return result.truncated();
    }
  }

  /** {@code prepare}'s answer, with what only the approver sees masked. */
  public record Prepared(PreparedAction action, ConnectorException.Kind errorKind, String errorMessage) {

    public boolean ok() {
      return errorKind == null;
    }
  }

  private final ConnectorRegistry registry;
  private final CallContexts contexts;
  private final SecretService secrets;
  private final ToolGuard guard;
  private final Masker masker;

  public ConnectorInvoker(ConnectorRegistry registry, CallContexts contexts, SecretService secrets, ToolGuard guard,
      Masker masker) {
    this.registry = registry;
    this.contexts = contexts;
    this.secrets = secrets;
    this.guard = guard;
    this.masker = masker;
  }

  /** A READ (or a core tool's read of the connection): the context is never approved. */
  public Outcome execute(Request r) {
    return run(r, null, Kind.EXECUTE, null);
  }

  /**
   * The stored call of an approved request: {@code approved() == true}, with the prepared state and the payload the
   * person saw. Only {@code approvals.ApprovalExecutor} calls this.
   */
  public Outcome executeApproved(Request r, Approval approval) {
    return run(r, Objects.requireNonNull(approval, "approval"), Kind.EXECUTE, null);
  }

  /** {@code describe_connection}'s contribution of the connector (≤ 2 s); empty when it has none. */
  public Outcome describe(Request r, String object) {
    return run(r, null, Kind.DESCRIBE, object);
  }

  /** {@code resources/read} of a connector resource. */
  public Outcome readResource(Request r, String uri) {
    return run(r, null, Kind.READ_RESOURCE, uri);
  }

  /** {@code prepare} before an approval request exists (≤ 5 s, secrets of built-ins, no side effects). */
  public Prepared prepare(Request r) {
    Optional<Connector> found = registry.connectorFor(r.cfg());
    if (found.isEmpty()) {
      return new Prepared(null, ConnectorException.Kind.RESOURCE_UNAVAILABLE, r.cfg().name() + " isn't available.");
    }
    Connector connector = found.get();
    Taint taint = new Taint();
    CallContext ctx = context(r, taint, PREPARE_TIMEOUT);
    try (var resolver = secrets.resolver(r.cfg(), taint)) {
      PreparedAction action = guard.run(r.cfg().id(), r.cfg().name(), connector.maxConcurrency(), PREPARE_TIMEOUT,
          ctx, () -> connector.prepare(r.call(), r.cfg(), resolver, ctx));
      if (action == null) {
        action = PreparedAction.approval(connector.describeAction(r.call(), r.cfg()), null);
      }
      return new Prepared(maskForApprover(action, taint, r), null, null);
    } catch (ConnectorException e) {
      return new Prepared(null, e.kind(), message(e, connector, r, taint));
    } catch (RuntimeException e) {
      log.error("prepare of {} on {} failed inside the connector", r.call().tool(), r.cfg().name(), e);
      return new Prepared(null, ConnectorException.Kind.INTERNAL, internal(connector, r));
    } finally {
      taint.clear();
    }
  }

  private enum Kind { EXECUTE, DESCRIBE, READ_RESOURCE }

  private Outcome run(Request r, Approval approval, Kind kind, String argument) {
    Optional<Connector> found = registry.connectorFor(r.cfg());
    if (found.isEmpty()) {
      return failure(ConnectorException.Kind.RESOURCE_UNAVAILABLE, r.cfg().name() + " isn't available right now.",
          0, false);
    }
    Connector connector = found.get();
    Taint taint = new Taint();
    Duration timeout = switch (kind) {
      case DESCRIBE -> min(DESCRIBE_TIMEOUT, r.limits().toolTimeout());
      case EXECUTE -> callTimeout(connector, r, approval != null);
      case READ_RESOURCE -> r.limits().toolTimeout();
    };
    CallContext call = context(r, taint, timeout);
    ExecContext ctx = approval == null ? call
        : new ApprovedContext(call, approval.approvalId(), approval.preparedState(), approval.payload());
    long[] resourceNanos = new long[1];
    try (var resolver = secrets.resolver(r.cfg(), taint)) {
      ToolResult raw = guard.run(r.cfg().id(), r.cfg().name(), connector.maxConcurrency(), timeout, call, () -> {
        long start = System.nanoTime();
        try {
          return invoke(connector, kind, argument, r, resolver, ctx);
        } finally {
          resourceNanos[0] = System.nanoTime() - start;
        }
      });
      long resourceMs = resourceNanos[0] / 1_000_000;
      if (raw == null) {
        raw = ToolResult.text("No result.");
      }
      Masker.MaskedResult masked = masker.result(raw, taint, r.mask());
      ToolResult shaped = ResultLimiter.limit(masked.result(), r.limits(), resourceMs);
      long bytes = ResultLimiter.size(shaped);
      ToolResult accounted = shaped.accounted(shaped.content(), shaped.structuredContent(), shaped.table(),
          masked.outcome().fields(), bytes);
      return new Outcome(accounted, null, null, resourceMs, masked.outcome().fields(), bytes,
          ResultLimiter.sha256(accounted), ResultLimiter.preview(accounted, 4_096), false);
    } catch (ConnectorException e) {
      long resourceMs = resourceNanos[0] / 1_000_000;
      boolean unknown = approval != null && (e.kind() == ConnectorException.Kind.OUTCOME_UNKNOWN
          || e.kind() == ConnectorException.Kind.TIMEOUT || e.kind() == ConnectorException.Kind.INTERNAL);
      if (e.kind() == ConnectorException.Kind.INTERNAL) {
        log.error("{} on {} failed inside the connector ({})", r.call() == null ? kind : r.call().tool(),
            r.cfg().name(), r.callId(), e.getCause() == null ? e : e.getCause());
      }
      return failure(e.kind(), message(e, connector, r, taint), resourceMs, unknown);
    } catch (RuntimeException e) {
      log.error("{} on {} failed inside the core ({})", kind, r.cfg().name(), r.callId(), e);
      return failure(ConnectorException.Kind.INTERNAL, internal(connector, r), resourceNanos[0] / 1_000_000,
          approval != null);
    } finally {
      taint.clear();
    }
  }

  /**
   * The deadline of an {@code execute} call: the tool timeout, or, for a tool that runs a command bounded by its own
   * timeout ({@code ssh_run}, {@code ssh_exec}), what that timeout needs — within the client's wait (55 s) for a call
   * answered in its request, up to ten minutes for one the gateway runs after a person approved it.
   */
  static Duration callTimeout(Connector connector, Request r, boolean approved) {
    return r.call() != null && connector.runsCommand(r.call().tool()) ? r.limits().commandCallTimeout(approved)
        : r.limits().toolTimeout();
  }

  private static ToolResult invoke(Connector connector, Kind kind, String argument, Request r,
      SecretResolver resolver, ExecContext ctx) throws ConnectorException {
    return switch (kind) {
      case EXECUTE -> connector.execute(r.call(), r.cfg(), resolver, ctx);
      case DESCRIBE -> {
        Optional<JsonNode> d = connector.describe(r.cfg(), resolver, ctx, argument);
        yield d.isEmpty() ? null : ToolResult.json(d.get(), null);
      }
      case READ_RESOURCE -> connector.readResource(argument, r.cfg(), resolver, ctx);
    };
  }

  private CallContext context(Request r, Taint taint, Duration timeout) {
    return contexts.builder(r.callId(), r.cfg(), r.limits()).trace(r.traceId(), r.traceparent()).timeout(timeout)
        .taint(taint).mask(r.mask()).build();
  }

  /** The agent gets the connector's message, masked; an internal error only says where to look. */
  private String message(ConnectorException e, Connector connector, Request r, Taint taint) {
    if (e.kind() == ConnectorException.Kind.INTERNAL) {
      return internal(connector, r);
    }
    String text = e.getMessage() == null ? "The call failed." : e.getMessage();
    return masker.text(text, taint, r.mask()).text();
  }

  private static String internal(Connector connector, Request r) {
    return "Internal error in " + connector.manifest().name() + ". See Activity " + r.callId() + ".";
  }

  private Outcome failure(ConnectorException.Kind kind, String message, long resourceMs, boolean outcomeUnknown) {
    ToolResult error = ToolResult.error(message);
    long bytes = ResultLimiter.size(error);
    error = error.accounted(error.content(), null, null, 0, bytes);
    return new Outcome(error, kind, message, resourceMs, 0, bytes, ResultLimiter.sha256(error), message,
        outcomeUnknown);
  }

  /**
   * {@code prepare} runs with the connection's secrets, so everything it describes is checked against the call's
   * taint before a request exists (§9 "Agents never see secrets"):
   * <ul>
   *   <li>the exact action ({@code text}), the payload that runs after approval and the prepared state can't be
   *       masked — they are stored, shown byte for byte and executed as they are — so a stored secret in any of them
   *       refuses the request instead ({@code REJECT}): a secret never belongs in an action a person approves;</li>
   *   <li>what only describes it — target, summary (channels), fields, impact, warnings, audit facets, extra — is
   *       masked with the taint and the environment's masking policy.</li>
   * </ul>
   */
  private PreparedAction maskForApprover(PreparedAction action, Taint taint, Request r) {
    MaskPolicy policy = r.mask();
    ActionPreview p = action.preview();
    String message = action.message() == null ? null : masker.text(action.message(), taint, policy).text();
    if (p == null) {
      return new PreparedAction(action.outcome(), null, action.preparedState(), message);
    }
    if (taint.occursIn(p.text()) || carries(p.payload(), taint) || carries(action.preparedState(), taint)) {
      log.warn("prepare of {} on {} put a stored secret into the exact action ({}): no request", r.call().tool(),
          r.cfg().name(), r.callId());
      return PreparedAction.reject("The exact action of " + r.call().tool() + " on " + r.cfg().name() + " would "
          + "contain a stored secret, so MCP Gateway doesn't ask anyone to approve it. Nothing ran.");
    }
    List<ActionPreview.Field> fields = new ArrayList<>();
    for (ActionPreview.Field f : p.fields()) {
      fields.add(new ActionPreview.Field(text(f.label(), taint, policy), text(f.value(), taint, policy)));
    }
    List<ActionPreview.Field> impact = new ArrayList<>();
    for (ActionPreview.Field f : p.impact()) {
      impact.add(new ActionPreview.Field(text(f.label(), taint, policy), text(f.value(), taint, policy)));
    }
    List<String> warnings = new ArrayList<>();
    for (String w : p.warnings()) {
      warnings.add(text(w, taint, policy));
    }
    Map<String, String> facets = new LinkedHashMap<>();
    p.auditFacets().forEach((k, v) -> {
      if (v != null) {
        facets.put(k, text(v, taint, policy));
      }
    });
    JsonNode extra = p.extra() == null ? null : masker.tree(p.extra(), taint, policy).tree();
    ActionPreview masked = new ActionPreview(p.kind(), p.language(), p.text(), text(p.target(), taint, policy),
        text(p.summary(), taint, policy), List.copyOf(fields), List.copyOf(impact), List.copyOf(warnings),
        Map.copyOf(facets), p.payload(), extra);
    return new PreparedAction(action.outcome(), masked, action.preparedState(), message);
  }

  private String text(String value, Taint taint, MaskPolicy policy) {
    return value == null ? null : masker.text(value, taint, policy).text();
  }

  /** Whether a stored secret occurs in any string, property name or number of {@code tree}. */
  private static boolean carries(JsonNode tree, Taint taint) {
    if (tree == null || taint.isEmpty()) {
      return false;
    }
    if (tree.isObject()) {
      for (Map.Entry<String, JsonNode> e : tree.properties()) {
        if (taint.occursIn(e.getKey()) || carries(e.getValue(), taint)) {
          return true;
        }
      }
      return false;
    }
    if (tree.isArray()) {
      for (JsonNode n : tree) {
        if (carries(n, taint)) {
          return true;
        }
      }
      return false;
    }
    if (tree.isBinary()) {
      return taint.occursIn(java.util.Base64.getEncoder().encodeToString(tree.binaryValue()));
    }
    return tree.isValueNode() && !tree.isNull() && taint.occursIn(tree.asString());
  }

  private static Duration min(Duration a, Duration b) {
    return a.compareTo(b) <= 0 ? a : b;
  }
}
