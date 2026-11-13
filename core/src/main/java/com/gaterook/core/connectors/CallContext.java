package com.gaterook.core.connectors;

import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.ExecContext;
import com.gaterook.connector.api.Limits;
import com.gaterook.core.platform.MaskPolicy;
import com.gaterook.core.platform.Masker;
import com.gaterook.core.platform.Taint;
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
  private final CallListener listener;
  /** When progress last went to the client, and how many log messages did. */
  private volatile long progressAt;
  private final java.util.concurrent.atomic.AtomicInteger logs = new java.util.concurrent.atomic.AtomicInteger();

  /** At most this often a call's progress goes to its client: the rest of a burst is dropped, never the last step. */
  static final long PROGRESS_EVERY_NANOS = 100_000_000L;
  /** At most this many log messages of one call go to its client, each at most this long as JSON. */
  static final int MAX_LOGS = 100;
  static final int MAX_LOG_BYTES = 8 * 1024;
  /** RFC 5424 severities, least severe first, as MCP names them. */
  static final List<String> LEVELS = List.of("debug", "info", "notice", "warning", "error", "critical", "alert",
      "emergency");

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
    this.listener = b.listener;
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

  @Override
  public boolean wantsProgress() {
    return listener.wantsProgress();
  }

  /** Masked with this call's taint and policy, and no more often than {@link #PROGRESS_EVERY_NANOS}, but the last. */
  @Override
  public void progress(double progress, Double total, String message) {
    if (!listener.wantsProgress() || cancelled.get()) {
      return;
    }
    long now = System.nanoTime();
    boolean last = total != null && progress >= total;
    if (!last && now - progressAt < PROGRESS_EVERY_NANOS) {
      return;
    }
    progressAt = now;
    String text = message == null ? null : Masker.standard().text(message.length() > 1_000 ? message.substring(0,
        1_000) : message, taint, mask).text();
    listener.progress(progress, total, text);
  }

  @Override
  public String clientLogLevel() {
    return listener.logLevel();
  }

  /** At or above the client's level, masked like a result, cut to {@link #MAX_LOG_BYTES}, {@link #MAX_LOGS} at most. */
  @Override
  public void clientLog(String level, String logger, JsonNode data) {
    String wanted = listener.logLevel();
    int at = level == null ? -1 : LEVELS.indexOf(level);
    if (wanted == null || at < 0 || at < LEVELS.indexOf(wanted) || cancelled.get()
        || logs.incrementAndGet() > MAX_LOGS) {
      return;
    }
    JsonNode masked = data == null ? null : Masker.standard().tree(data, taint, mask).tree();
    if (masked != null && com.gaterook.core.platform.CanonicalJson.bytes(masked).length > MAX_LOG_BYTES) {
      String text = masked.isString() ? masked.asString() : masked.toString();
      masked = tools.jackson.databind.node.JsonNodeFactory.instance.stringNode(text.substring(0, Math.min(
          text.length(), MAX_LOG_BYTES / 4)) + "…");
    }
    String name = logger == null ? null : Masker.standard().text(logger.length() > 200 ? logger.substring(0, 200)
        : logger, taint, mask).text();
    listener.log(level, name, masked);
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
    private String loggerName = "com.gaterook.connector";
    private CallListener listener = CallListener.NONE;

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

    /** Who hears the call while it runs (its MCP client's progress and log messages); nobody by default. */
    public Builder listener(CallListener value) {
      this.listener = Objects.requireNonNull(value, "value");
      return this;
    }

    /** The logger connectors get: {@code com.gaterook.connector.<type>}, masked by the core's log pattern. */
    public Builder connectorType(String type) {
      this.loggerName = "com.gaterook.connector." + type;
      return this;
    }

    public CallContext build() {
      if (handles == null) {
        throw new IllegalStateException("A call context needs the core's handle codec");
      }
      if (egress == null) {
        egress = (request, maxBytes) -> {
          throw com.gaterook.connector.api.ConnectorException.denied("This call has no network access");
        };
      }
      return new CallContext(this);
    }
  }
}
