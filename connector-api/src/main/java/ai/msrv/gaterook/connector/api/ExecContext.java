package ai.msrv.gaterook.connector.api;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * Everything a call may know. No identity, no secret store, no master key, no other connections.
 *
 * <p>Cancellation = interrupt of the virtual thread + {@link #onCancel} callbacks ({@code Statement.cancel()},
 * {@code cursor.close()}, signal TERM).
 */
public interface ExecContext {

  /** {@code tc_…}: Mongo comment, SSH certificate key_id, log correlation. */
  String callId();

  String traceId();

  String traceparent();

  Env env();

  Limits limits();

  /** Tool timeout of the policy. */
  Duration timeout();

  /** Set the native timeouts of the resource ≤ deadline. */
  Instant deadline();

  default Duration remaining() {
    Duration d = Duration.between(clock().instant(), deadline());
    return d.isNegative() ? Duration.ZERO : d;
  }

  boolean cancelled();

  void onCancel(Runnable action);

  /** Only the core's ApprovalExecutor sets it. */
  boolean approved();

  Optional<String> approvalId();

  /** From {@link Connector#prepare}. */
  Optional<JsonNode> preparedState();

  /** {@link ActionPreview#payload()} of the approved request: run it, not the arguments. */
  Optional<JsonNode> approvedPayload();

  /** Masked; {@code core.log} or {@code logs/plugins/<id>.log}. */
  System.Logger log();

  Clock clock();

  HandleCodec handles();

  /** Shared remote quotas: {@code gcp:<project>}, {@code aws:<acct>:<region>:<api>}. */
  boolean acquire(String remoteQuotaKey, int permitsPerMinute);

  /** {@code ~/.gaterook/connectors/<type>/}; plugins: {@code plugin-data/<id>/}. */
  Path workDir();

  /** Guarded HTTP (allowlist ∩ policy, no metadata or private ranges, no redirects). */
  Egress egress();

  /**
   * Derived credentials (GCP/STS/Vault/exec/OAuth tokens) the connector obtained itself: masked like resolver
   * values.
   */
  void taint(CharSequence value);

  /**
   * A copy of {@code tree} as the core will mask it in this call's answer: keys that name secrets, values that look
   * like credentials, this call's taint, the environment's personal data. For a connector that filters by values the
   * agent chose ({@code where}): a filter must match what the answer shows, or which items it keeps tells the agent
   * the masked value one guess at a time. The default — a context without the core's masker — returns the tree as it
   * is.
   */
  default JsonNode masked(JsonNode tree) {
    return tree;
  }

  /** HMAC-signed opaque handles (cursors of logs_tail, redis_scan): the agent can't forge them. */
  interface HandleCodec {
    String seal(String purpose, JsonNode payload, Duration ttl);

    Optional<JsonNode> open(String purpose, String handle);
  }

  /**
   * Neutral types: the core implements it on EgressClients (OkHttp, connect-time address check), the host on
   * EgressHttpClient.
   */
  interface Egress {
    EgressResponse send(EgressRequest request, long maxBytes) throws ConnectorException;
  }

  record EgressRequest(String method, URI uri, Map<String, List<String>> headers, byte[] body, Duration timeout) {}

  record EgressResponse(int status, Map<String, List<String>> headers, byte[] body, boolean truncated) {}
}
