package ai.msrv.gaterook.connectors.ssh;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.Limits;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import tools.jackson.databind.JsonNode;

/** The call context the core would give: a deadline, limits, cancellation, and approval when a test says so. */
final class TestCtx implements ExecContext {

  private static final java.util.Map<String, JsonNode> CURSORS = new java.util.concurrent.ConcurrentHashMap<>();

  private final String callId;
  private final Limits limits;
  private final Instant deadline;
  private final boolean approved;
  private final String approvalId;
  private final JsonNode payload;
  private final List<Runnable> onCancel = new CopyOnWriteArrayList<>();
  private volatile boolean cancelled;

  private TestCtx(String callId, Limits limits, Duration timeout, boolean approved, String approvalId,
      JsonNode payload) {
    this.callId = callId;
    this.limits = limits;
    this.deadline = Instant.now().plus(timeout);
    this.approved = approved;
    this.approvalId = approvalId;
    this.payload = payload;
  }

  /** Dev limits; the tool timeout is the call's deadline. */
  static Limits limits(Duration commandTimeout, Duration toolTimeout, long maxOutputBytes, long maxStderrBytes) {
    return new Limits(1000, 1000, 512 * 1024, 64 * 1024, maxOutputBytes, maxStderrBytes, 8192,
        Duration.ofSeconds(5), commandTimeout, toolTimeout, Duration.ofHours(168));
  }

  static Limits devLimits() {
    return limits(Duration.ofSeconds(30), Duration.ofSeconds(10), 256 * 1024, 64 * 1024);
  }

  static TestCtx read() {
    return of(devLimits());
  }

  static TestCtx of(Limits limits) {
    return new TestCtx("tc_test", limits, limits.toolTimeout(), false, null, null);
  }

  static TestCtx approved(Limits limits, String approvalId, JsonNode payload) {
    return new TestCtx("tc_test", limits, limits.toolTimeout(), true, approvalId, payload);
  }

  /** The deadline the core gives a call of a command tool ({@code ConnectorInvoker}): what the command timeout needs. */
  static TestCtx command(Limits limits) {
    return new TestCtx("tc_test", limits, limits.commandCallTimeout(false), false, null, null);
  }

  /** Cancels as the core does: the callbacks first, then {@link #cancelled()} answers true. */
  void cancel() {
    cancelled = true;
    onCancel.forEach(Runnable::run);
  }

  @Override
  public String callId() {
    return callId;
  }

  @Override
  public String traceId() {
    return "0af7651916cd43dd8448eb211c80319c";
  }

  @Override
  public String traceparent() {
    return "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
  }

  @Override
  public Env env() {
    return Env.DEV;
  }

  @Override
  public Limits limits() {
    return limits;
  }

  @Override
  public Duration timeout() {
    return limits.toolTimeout();
  }

  @Override
  public Instant deadline() {
    return deadline;
  }

  @Override
  public boolean cancelled() {
    return cancelled;
  }

  @Override
  public void onCancel(Runnable action) {
    onCancel.add(action);
  }

  @Override
  public boolean approved() {
    return approved;
  }

  @Override
  public Optional<String> approvalId() {
    return Optional.ofNullable(approvalId);
  }

  @Override
  public Optional<JsonNode> preparedState() {
    return Optional.empty();
  }

  @Override
  public Optional<JsonNode> approvedPayload() {
    return Optional.ofNullable(payload);
  }

  @Override
  public System.Logger log() {
    return System.getLogger("ssh-test");
  }

  @Override
  public Clock clock() {
    return Clock.systemUTC();
  }

  @Override
  public HandleCodec handles() {
    return new HandleCodec() {
      @Override
      public String seal(String purpose, JsonNode payload, Duration ttl) {
        String token = java.util.UUID.randomUUID().toString();
        CURSORS.put(purpose + ":" + token, payload.deepCopy());
        return token;
      }

      @Override
      public Optional<JsonNode> open(String purpose, String handle) {
        return Optional.ofNullable(CURSORS.get(purpose + ":" + handle)).map(JsonNode::deepCopy);
      }
    };
  }

  @Override
  public boolean acquire(String remoteQuotaKey, int permitsPerMinute) {
    return true;
  }

  @Override
  public Path workDir() {
    return Path.of("target");
  }

  @Override
  public Egress egress() {
    throw new UnsupportedOperationException("SSH uses no HTTP egress");
  }

  private java.util.function.UnaryOperator<JsonNode> masker = java.util.function.UnaryOperator.identity();

  TestCtx withMasker(java.util.function.UnaryOperator<JsonNode> value) {
    masker = value;
    return this;
  }

  @Override
  public JsonNode masked(JsonNode tree) {
    return masker.apply(tree);
  }

  private final List<String> tainted = new CopyOnWriteArrayList<>();

  @Override
  public void taint(CharSequence value) {
    tainted.add(value.toString());
  }

  /** What the connector put into this call's taint itself (the resolver's values aren't here). */
  List<String> tainted() {
    return tainted;
  }
}
