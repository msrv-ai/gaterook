package ai.msrv.gaterook.core.invocation.internal;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.core.connectors.CallContext;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * The context of a core-authorized action, from a human approval or an explicit Bypass tool mode. The connector
 * executes the exact prepared payload instead of the arguments. Bypass carries no approval id and is recorded as
 * Bypass in the call's policy audit. Only ConnectorInvoker constructs this context.
 */
public final class ApprovedContext implements ExecContext {

  private final CallContext call;
  private final String approvalId;
  private final JsonNode preparedState;
  private final JsonNode approvedPayload;

  public ApprovedContext(CallContext call, String approvalId, JsonNode preparedState, JsonNode approvedPayload) {
    this.call = Objects.requireNonNull(call, "call");
    this.approvalId = approvalId;
    this.preparedState = preparedState;
    this.approvedPayload = approvedPayload;
  }

  @Override
  public boolean approved() {
    return true;
  }

  @Override
  public Optional<String> approvalId() {
    return Optional.ofNullable(approvalId);
  }

  @Override
  public Optional<JsonNode> preparedState() {
    return Optional.ofNullable(preparedState);
  }

  @Override
  public Optional<JsonNode> approvedPayload() {
    return Optional.ofNullable(approvedPayload);
  }

  @Override
  public String callId() {
    return call.callId();
  }

  @Override
  public String traceId() {
    return call.traceId();
  }

  @Override
  public String traceparent() {
    return call.traceparent();
  }

  @Override
  public Env env() {
    return call.env();
  }

  @Override
  public Limits limits() {
    return call.limits();
  }

  @Override
  public Duration timeout() {
    return call.timeout();
  }

  @Override
  public Instant deadline() {
    return call.deadline();
  }

  @Override
  public boolean cancelled() {
    return call.cancelled();
  }

  @Override
  public void onCancel(Runnable action) {
    call.onCancel(action);
  }

  @Override
  public System.Logger log() {
    return call.log();
  }

  @Override
  public Clock clock() {
    return call.clock();
  }

  @Override
  public HandleCodec handles() {
    return call.handles();
  }

  @Override
  public boolean acquire(String remoteQuotaKey, int permitsPerMinute) {
    return call.acquire(remoteQuotaKey, permitsPerMinute);
  }

  @Override
  public Path workDir() {
    return call.workDir();
  }

  @Override
  public Egress egress() {
    return call.egress();
  }

  @Override
  public void taint(CharSequence value) {
    call.taint(value);
  }

  @Override
  public JsonNode masked(JsonNode tree) {
    return call.masked(tree);
  }
}
