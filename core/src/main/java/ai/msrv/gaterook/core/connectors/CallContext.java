package ai.msrv.gaterook.core.connectors;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.core.platform.MaskPolicy;
import ai.msrv.gaterook.core.platform.Masker;
import ai.msrv.gaterook.core.platform.Taint;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * What one connector call may know (ARCHITECTURE.md §3 {@code ExecContext}): ids, environment, limits, deadline,
 * guarded egress, sealed handles, shared quotas, its work folder and its taint. No identity, no secret store, no other
 * connection. Used as is for tests, health checks, {@code prepare} and READ calls; never approved: only
 * {@code invocation}'s approved context, built by the approval executor, answers {@code approved() == true}.
 *
 * <p>Cancellation is {@link #cancel()}: it marks the call cancelled and runs the connector's {@code onCancel} callbacks
 * once ({@code Statement.cancel()}, closing a cursor), in addition to interrupting the virtual thread, which is the
 * caller's part.
 */
public final class CallContext implements ExecContext {

  private static final Logger log = LoggerFactory.getLogger(CallContext.class);

  private final String callId;
  private final String traceId;
  private final String traceparent;
  private final Env env;
  private final Limits limits;
  private final Duration timeout;
  private final Instant deadline;
  private final Clock clock;
  private final HandleCodec handles;
  private final Egress egress;
  private final Taint taint;
  private final MaskPolicy mask;
  private final Path workDir;
  private final RemoteQuotas quotas;
  private final System.Logger logger;
  private final AtomicBoolean cancelled = new AtomicBoolean();
  private final List<Runnable> onCancel = new ArrayList<>();

  private CallContext(Builder b) {
    this.callId = b.callId;
    this.traceId = b.traceId;
    this.traceparent = b.traceparent;
    this.env = b.env;
    this.limits = b.limits;
    this.timeout = b.timeout;
    this.clock = b.clock;
    this.deadline = b.deadline != null ? b.deadline : b.clock.instant().plus(b.timeout);
    this.handles = b.handles;
    this.egress = b.egress;
    this.taint = b.taint;
    this.mask = b.mask != null ? b.mask : MaskPolicy.defaults(b.env);
    this.workDir = b.workDir;
    this.quotas = b.quotas;
    this.logger = System.getLogger(b.loggerName);
  }

  public static Builder builder(String callId, Env env, Limits limits, Clock clock) {
    return new Builder(callId, env, limits, clock);
  }

  @Override
  public String callId() {
    return callId;
  }

  @Override
  public String traceId() {
    return traceId;
  }

  @Override
  public String traceparent() {
    return traceparent;
  }

  @Override
  public Env env() {
    return env;
  }

  @Override
  public Limits limits() {
    return limits;
  }

  @Override
  public Duration timeout() {
    return timeout;
  }

  @Override
  public Instant deadline() {
    return deadline;
  }

  @Override
  public boolean cancelled() {
    return cancelled.get() || Thread.currentThread().isInterrupted();
  }

  @Override
  public void onCancel(Runnable action) {
    Objects.requireNonNull(action, "action");
    boolean runNow;
    synchronized (onCancel) {
      runNow = cancelled.get();
      if (!runNow) {
        onCancel.add(action);
      }
    }
    if (runNow) {
      runQuietly(action);
    }
  }

  /** Marks the call cancelled and runs every {@code onCancel} callback once. */
  public void cancel() {
    List<Runnable> actions;
    synchronized (onCancel) {
      if (!cancelled.compareAndSet(false, true)) {
        return;
      }
      actions = List.copyOf(onCancel);
      onCancel.clear();
    }
    actions.forEach(CallContext::runQuietly);
  }

  @Override
  public boolean approved() {
    return false;
  }

  @Override
  public Optional<String> approvalId() {
    return Optional.empty();
  }

  @Override
  public Optional<JsonNode> preparedState() {
    return Optional.empty();
  }

  @Override
  public Optional<JsonNode> approvedPayload() {
    return Optional.empty();
  }

  @Override
  public System.Logger log() {
    return logger;
  }

  @Override
  public Clock clock() {
    return clock;
  }

  @Override
  public HandleCodec handles() {
    return handles;
  }

  @Override
  public boolean acquire(String remoteQuotaKey, int permitsPerMinute) {
    return quotas.acquire(remoteQuotaKey, permitsPerMinute);
  }

  @Override
  public Path workDir() {
    return workDir;
  }

  @Override
  public Egress egress() {
    return egress;
  }

  @Override
  public void taint(CharSequence value) {
    taint.add(value);
  }

  /** What this call's resolver and connector added to the taint: the core masks the result with it. */
  public Taint taintOfCall() {
    return taint;
  }

  /** The masker of the answer, ahead of time: this call's taint and mask policy (§9 "Agents never see secrets"). */
  @Override
  public JsonNode masked(JsonNode tree) {
    return tree == null ? null : Masker.standard().tree(tree, taint, mask).tree();
  }

  @Override
  public Optional<String> textMaskMarker() {
    return Optional.of(Masker.MASK);
  }

  private static void runQuietly(Runnable action) {
    try {
      action.run();
    } catch (RuntimeException e) {
      // A cancel callback that fails (a statement already closed) must not hide the timeout itself.
      log.debug("onCancel callback failed: {}", e.toString());
    }
  }

  /** Builder; everything but the four constructor arguments has a safe default. */
  public static final class Builder {
    private final String callId;
    private final Env env;
    private final Limits limits;
    private final Clock clock;
    private String traceId;
    private String traceparent;
    private Duration timeout;
    private Instant deadline;
    private HandleCodec handles;
    private Egress egress;
    private Taint taint = new Taint();
    private MaskPolicy mask;
    private Path workDir;
    private RemoteQuotas quotas = new RemoteQuotas();
    private String loggerName = "ai.msrv.gaterook.connector";

    private Builder(String callId, Env env, Limits limits, Clock clock) {
      this.callId = Objects.requireNonNull(callId, "callId");
      this.env = Objects.requireNonNull(env, "env");
      this.limits = Objects.requireNonNull(limits, "limits");
      this.clock = Objects.requireNonNull(clock, "clock");
      this.timeout = limits.toolTimeout();
    }

    public Builder trace(String traceId, String traceparent) {
      this.traceId = traceId;
      this.traceparent = traceparent;
      return this;
    }

    public Builder timeout(Duration value) {
      this.timeout = Objects.requireNonNull(value, "value");
      return this;
    }

    public Builder deadline(Instant value) {
      this.deadline = value;
      return this;
    }

    public Builder handles(HandleCodec value) {
      this.handles = value;
      return this;
    }

    public Builder egress(Egress value) {
      this.egress = value;
      return this;
    }

    public Builder taint(Taint value) {
      this.taint = Objects.requireNonNull(value, "value");
      return this;
    }

    /** The mask policy of the call's answer ({@link #masked}); the environment's defaults when not set. */
    public Builder mask(MaskPolicy value) {
      this.mask = value;
      return this;
    }

    public Builder workDir(Path value) {
      this.workDir = value;
      return this;
    }

    public Builder quotas(RemoteQuotas value) {
      this.quotas = Objects.requireNonNull(value, "value");
      return this;
    }

    /** The logger connectors get: {@code ai.msrv.gaterook.connector.<type>}, masked by the core's log pattern. */
    public Builder connectorType(String type) {
      this.loggerName = "ai.msrv.gaterook.connector." + type;
      return this;
    }

    public CallContext build() {
      if (handles == null) {
        throw new IllegalStateException("A call context needs the core's handle codec");
      }
      if (egress == null) {
        egress = (request, maxBytes) -> {
          throw ai.msrv.gaterook.connector.api.ConnectorException.denied("This call has no network access");
        };
      }
      return new CallContext(this);
    }
  }
}
