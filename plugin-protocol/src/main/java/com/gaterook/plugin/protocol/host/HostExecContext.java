package com.gaterook.plugin.protocol.host;

import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.ExecContext;
import com.gaterook.connector.api.Limits;
import com.gaterook.plugin.protocol.Gpp;
import com.gaterook.plugin.protocol.GppJson;
import com.gaterook.plugin.protocol.JsonRpcPeer;
import com.gaterook.plugin.protocol.Messages;
import com.gaterook.plugin.protocol.RpcException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The {@link ExecContext} of one call inside the plugin host (report 16 §9): what the core sent with the call (ids,
 * environment, limits, budget, approval, prepared state, approved payload) plus what the host provides — cancellation
 * by {@code $/cancel}, a logger whose lines go to the core, sealed handles through the core's key, quotas of this
 * process, the plugin's data folder, guarded egress. No identity, no secret store, no other connection.
 *
 * <p>A connector module's call ({@link Spec#coreServices}) also gets what a compiled-in connector has from the core:
 * remote quotas that every connector of an account shares, the masked view of a tree, and the progress and log
 * messages the call's client asked for. A plugin keeps its host's own quotas and gets none of the others.
 */
public final class HostExecContext implements ExecContext {

  /**
   * Everything a context is built from.
   *
   * @param coreServices   the call is a connector module's: quotas and masking are asked of the core
   * @param wantsProgress  the call's client asked for progress
   * @param clientLogLevel the level from which the call's client wants the resource's log messages, or {@code null}
   */
  public record Spec(String callId, String callToken, String traceId, String traceparent, Env env, Limits limits,
      Duration timeout, boolean approved, String approvalId, JsonNode preparedState, JsonNode approvedPayload,
      Path workDir, Egress egress, String loggerName, System.Logger.Level logLevel, boolean coreServices,
      boolean wantsProgress, String clientLogLevel) {

    /** A plugin's call. */
    public Spec(String callId, String callToken, String traceId, String traceparent, Env env, Limits limits,
        Duration timeout, boolean approved, String approvalId, JsonNode preparedState, JsonNode approvedPayload,
        Path workDir, Egress egress, String loggerName, System.Logger.Level logLevel) {
      this(callId, callToken, traceId, traceparent, env, limits, timeout, approved, approvalId, preparedState,
          approvedPayload, workDir, egress, loggerName, logLevel, false, false, null);
    }
  }

  private final JsonRpcPeer peer;
  private final JsonRpcPeer.Incoming incoming;
  private final Spec spec;
  private final Clock clock;
  private final Instant deadline;
  private final LocalQuotas quotas;

  public HostExecContext(JsonRpcPeer peer, JsonRpcPeer.Incoming incoming, Spec spec, Clock clock,
      LocalQuotas quotas) {
    this.peer = Objects.requireNonNull(peer, "peer");
    this.incoming = incoming;
    this.spec = Objects.requireNonNull(spec, "spec");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.deadline = clock.instant().plus(spec.timeout());
    this.quotas = quotas;
  }

  @Override
  public String callId() {
    return spec.callId();
  }

  @Override
  public String traceId() {
    return spec.traceId();
  }

  @Override
  public String traceparent() {
    return spec.traceparent();
  }

  @Override
  public Env env() {
    return spec.env();
  }

  @Override
  public Limits limits() {
    return spec.limits();
  }

  @Override
  public Duration timeout() {
    return spec.timeout();
  }

  @Override
  public Instant deadline() {
    return deadline;
  }

  @Override
  public boolean cancelled() {
    return (incoming != null && incoming.cancelled()) || Thread.currentThread().isInterrupted();
  }

  /** A cancel runs with the call's TCP grant, where the host gates the plugin's connections: it may open one. */
  @Override
  public void onCancel(Runnable action) {
    if (incoming != null) {
      incoming.onCancel(spec.egress() instanceof ScopedEgress scoped ? scoped.wrap(action) : action);
    }
  }

  @Override
  public boolean approved() {
    return spec.approved();
  }

  @Override
  public Optional<String> approvalId() {
    return Optional.ofNullable(spec.approvalId());
  }

  @Override
  public Optional<JsonNode> preparedState() {
    return Optional.ofNullable(spec.preparedState());
  }

  @Override
  public Optional<JsonNode> approvedPayload() {
    return Optional.ofNullable(spec.approvedPayload());
  }

  @Override
  public System.Logger log() {
    return new PeerLogger(() -> peer, spec.loggerName(), spec.callId(), spec.logLevel());
  }

  @Override
  public Clock clock() {
    return clock;
  }

  @Override
  public HandleCodec handles() {
    return new HandleCodec() {
      @Override
      public String seal(String purpose, JsonNode payload, Duration ttl) {
        ObjectNode p = GppJson.object();
        p.put("callToken", spec.callToken());
        p.put("purpose", purpose);
        p.set("payload", payload);
        p.put("ttlSeconds", ttl.toSeconds());
        JsonNode r = await(peer.request(Gpp.HANDLES_SEAL, p));
        return r == null ? null : r.path("handle").asString(null);
      }

      @Override
      public Optional<JsonNode> open(String purpose, String handle) {
        ObjectNode p = GppJson.object();
        p.put("callToken", spec.callToken());
        p.put("purpose", purpose);
        p.put("handle", handle);
        JsonNode r = await(peer.request(Gpp.HANDLES_OPEN, p));
        JsonNode payload = r == null ? null : r.get("payload");
        return payload == null || payload.isNull() ? Optional.empty() : Optional.of(payload);
      }
    };
  }

  private static JsonNode await(JsonRpcPeer.Call<JsonNode> call) {
    try {
      return call.get(Duration.ofSeconds(5));
    } catch (RpcException e) {
      throw new IllegalStateException(e.getMessage());
    } catch (TimeoutException e) {
      throw new IllegalStateException("GateRook didn't answer in time");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("The call was cancelled");
    }
  }

  /**
   * A plugin's quotas are this process's. A module's are the core's, shared with every connector that reaches the same
   * account; when the core can't be asked the permit isn't given.
   */
  @Override
  public boolean acquire(String remoteQuotaKey, int permitsPerMinute) {
    if (!spec.coreServices()) {
      return quotas == null || quotas.acquire(remoteQuotaKey, permitsPerMinute);
    }
    try {
      JsonNode r = peer.request(Gpp.QUOTA_ACQUIRE, Messages.quota(spec.callToken(), remoteQuotaKey, permitsPerMinute))
          .get(Duration.ofSeconds(5));
      return r != null && r.path("granted").asBoolean(false);
    } catch (RpcException | TimeoutException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /** A module's tree as the core will mask the call's answer; a call that can't ask the core fails instead. */
  @Override
  public JsonNode masked(JsonNode tree) {
    if (!spec.coreServices() || tree == null) {
      return tree;
    }
    JsonNode r = await(peer.request(Gpp.MASK_PREVIEW, Messages.mask(spec.callToken(), tree)));
    JsonNode masked = r == null ? null : r.get("tree");
    if (masked == null) {
      throw new IllegalStateException("GateRook didn't return the masked view");
    }
    return masked;
  }

  @Override
  public boolean wantsProgress() {
    return spec.coreServices() && spec.wantsProgress();
  }

  @Override
  public void progress(double progress, Double total, String message) {
    if (wantsProgress()) {
      peer.notify(Gpp.PROGRESS, Messages.progress(spec.callToken(), progress, total, message));
    }
  }

  @Override
  public String clientLogLevel() {
    return spec.coreServices() ? spec.clientLogLevel() : null;
  }

  @Override
  public void clientLog(String level, String logger, JsonNode data) {
    if (clientLogLevel() != null && level != null) {
      peer.notify(Gpp.CLIENT_LOG, Messages.clientLog(spec.callToken(), level, logger, data));
    }
  }

  @Override
  public Path workDir() {
    return spec.workDir();
  }

  @Override
  public Egress egress() {
    Egress e = spec.egress();
    return e != null ? e : (request, maxBytes) -> {
      throw ConnectorException.denied("This call has no network access");
    };
  }

  /** Tells the core about a credential the plugin derived itself; ordered before the call's answer. */
  @Override
  public void taint(CharSequence value) {
    if (value != null && value.length() > 0) {
      peer.notify(Gpp.TAINT_ADD, Messages.taint(spec.callToken(), value.toString()));
    }
  }
}
