package ai.msrv.gaterook.core.approvals.internal.persistence;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.platform.Caller;
import ai.msrv.gaterook.core.platform.EnvConverter;
import ai.msrv.gaterook.core.platform.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OptimisticLock;
import org.hibernate.type.SqlTypes;

/**
 * An approval request ({@code ap_…}): the canonical call a person decides on and the gateway itself executes after
 * approval (A3). What identifies the request — call, digest, action, principal — is fixed at creation
 * ({@code updatable = false}); the status moves only by {@link ApprovalRepository#transition versioned CAS}, never by
 * a dirty-checked flush, and {@code @DynamicUpdate} keeps the other writes (result, error) to their own columns.
 *
 * <p>Endpoint and connection are referenced by id with name snapshots, so the history survives their deletion.
 */
@Entity
@DynamicUpdate
@Table(name = "approval")
public class ApprovalEntity {

  @Id
  @Column(name = "id", length = 40, nullable = false, updatable = false)
  private String id;

  @Column(name = "call_id", length = 40, nullable = false, updatable = false)
  private String callId;

  @Column(name = "endpoint_id", length = 36)
  private String endpointId;

  @Column(name = "endpoint_slug", length = 63, nullable = false, updatable = false)
  private String endpointSlug;

  @Column(name = "connection_id", length = 36)
  private String connectionId;

  @Column(name = "connection_name", length = 64, updatable = false)
  private String connectionName;

  @Column(name = "tool", length = 128, nullable = false, updatable = false)
  private String tool;

  @Column(name = "tool_key", length = 256, nullable = false, updatable = false)
  private String toolKey;

  @Convert(converter = EnvConverter.class)
  @Column(name = "env", length = 8, nullable = false, updatable = false)
  private Env env;

  @Enumerated(EnumType.STRING)
  @Column(name = "principal_kind", length = 12, nullable = false, updatable = false)
  private Caller.Kind principalKind;

  @Column(name = "principal_id", length = 40, nullable = false, updatable = false)
  private String principalId;

  @Column(name = "client_label", length = 64, updatable = false)
  private String clientLabel;

  /** Tool, connection, config version, arguments and template binding: exactly what executes after approval. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "call_canonical", nullable = false, updatable = false)
  private Map<String, Object> callCanonical;

  @Column(name = "digest", length = 64, nullable = false, updatable = false)
  private String digest;

  /** The {@code ActionPreview} the person sees. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "action", nullable = false, updatable = false)
  private Map<String, Object> action;

  @Column(name = "action_sha256", length = 64, nullable = false, updatable = false)
  private String actionSha256;

  /** {@code PreparedAction.preparedState()} as JSON text: any JSON value, so not a map. */
  @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
  @Column(name = "prepared_state", updatable = false)
  private String preparedState;

  /** What the agent wrote about the call: shown as "Agent's note · unverified", never sent to channels. */
  @Column(name = "agent_note", length = 500, updatable = false)
  private String agentNote;

  @Column(name = "rule_id", length = 128, nullable = false, updatable = false)
  private String ruleId;

  @Convert(converter = CommentPolicy.Converter.class)
  @Column(name = "comment_policy", length = 12, nullable = false, updatable = false)
  private CommentPolicy commentPolicy;

  /** Written only by {@link ApprovalRepository#transition}. */
  @Convert(converter = ApprovalStatus.Converter.class)
  @Column(name = "status", length = 12, nullable = false, updatable = false)
  private ApprovalStatus status;

  @Column(name = "comment", length = 2000)
  private String comment;

  @Column(name = "decided_via", length = 24)
  private String decidedVia;

  @Column(name = "decided_external_id", length = 64)
  private String decidedExternalId;

  @Column(name = "decided_at")
  private Instant decidedAt;

  @Column(name = "expires_at", nullable = false, updatable = false)
  private Instant expiresAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "executed_at")
  private Instant executedAt;

  @Column(name = "executed_sha256", length = 64)
  private String executedSha256;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "result_masked")
  private Map<String, Object> resultMasked;

  @Column(name = "result_expires_at")
  private Instant resultExpiresAt;

  @Column(name = "error", length = 1000)
  private String error;

  /** W3C trace context of the call, the parent of the execution span. */
  @Column(name = "trace_context", length = 128, updatable = false)
  private String traceContext;

  /**
   * HMAC of the row ({@code platform.RowMac}); excluded from optimistic locking, so that writing it after the
   * flush that settled {@code version} doesn't bump the version it signs.
   */
  @OptimisticLock(excluded = true)
  @Column(name = "mac")
  private byte[] mac;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  protected ApprovalEntity() {}

  private ApprovalEntity(Builder b) {
    this.id = Ids.next("ap_");
    this.callId = Objects.requireNonNull(b.callId, "callId");
    this.endpointId = b.endpointId;
    this.endpointSlug = Objects.requireNonNull(b.endpointSlug, "endpointSlug");
    this.connectionId = b.connectionId;
    this.connectionName = b.connectionName;
    this.tool = Objects.requireNonNull(b.tool, "tool");
    this.toolKey = Objects.requireNonNull(b.toolKey, "toolKey");
    this.env = Objects.requireNonNull(b.env, "env");
    this.principalKind = Objects.requireNonNull(b.principalKind, "principalKind");
    this.principalId = Objects.requireNonNull(b.principalId, "principalId");
    this.clientLabel = b.clientLabel;
    this.callCanonical = Objects.requireNonNull(b.callCanonical, "callCanonical");
    this.digest = Objects.requireNonNull(b.digest, "digest");
    this.action = Objects.requireNonNull(b.action, "action");
    this.actionSha256 = Objects.requireNonNull(b.actionSha256, "actionSha256");
    this.preparedState = b.preparedState;
    this.agentNote = b.agentNote;
    this.ruleId = Objects.requireNonNull(b.ruleId, "ruleId");
    this.commentPolicy = Objects.requireNonNull(b.commentPolicy, "commentPolicy");
    this.status = ApprovalStatus.PENDING;
    this.createdAt = Objects.requireNonNull(b.createdAt, "createdAt");
    this.expiresAt = Objects.requireNonNull(b.expiresAt, "expiresAt");
    this.traceContext = b.traceContext;
  }

  public static Builder builder() {
    return new Builder();
  }

  public String getId() {
    return id;
  }

  public String getCallId() {
    return callId;
  }

  public String getEndpointId() {
    return endpointId;
  }

  public String getEndpointSlug() {
    return endpointSlug;
  }

  public String getConnectionId() {
    return connectionId;
  }

  public String getConnectionName() {
    return connectionName;
  }

  /** The endpoint or connection was deleted: the request keeps the names for its history. */
  public void detach(boolean endpoint, boolean connection) {
    if (endpoint) {
      this.endpointId = null;
    }
    if (connection) {
      this.connectionId = null;
    }
  }

  public String getTool() {
    return tool;
  }

  public String getToolKey() {
    return toolKey;
  }

  public Env getEnv() {
    return env;
  }

  public Caller.Kind getPrincipalKind() {
    return principalKind;
  }

  public String getPrincipalId() {
    return principalId;
  }

  public String getClientLabel() {
    return clientLabel;
  }

  public Map<String, Object> getCallCanonical() {
    return callCanonical;
  }

  public String getDigest() {
    return digest;
  }

  public Map<String, Object> getAction() {
    return action;
  }

  public String getActionSha256() {
    return actionSha256;
  }

  public String getPreparedState() {
    return preparedState;
  }

  public String getAgentNote() {
    return agentNote;
  }

  public String getRuleId() {
    return ruleId;
  }

  public CommentPolicy getCommentPolicy() {
    return commentPolicy;
  }

  public ApprovalStatus getStatus() {
    return status;
  }

  public String getComment() {
    return comment;
  }

  public String getDecidedVia() {
    return decidedVia;
  }

  public String getDecidedExternalId() {
    return decidedExternalId;
  }

  public Instant getDecidedAt() {
    return decidedAt;
  }

  /** Who decided and how, written in the same transaction as the status CAS. */
  public void decided(String via, String externalId, String comment, Instant at) {
    this.decidedVia = via;
    this.decidedExternalId = externalId;
    this.comment = comment;
    this.decidedAt = Objects.requireNonNull(at, "at");
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getExecutedAt() {
    return executedAt;
  }

  public String getExecutedSha256() {
    return executedSha256;
  }

  public Map<String, Object> getResultMasked() {
    return resultMasked;
  }

  public Instant getResultExpiresAt() {
    return resultExpiresAt;
  }

  /** The masked result of the approved execution, kept until {@code keepUntil} (60 min). */
  public void executed(String executedSha256, Map<String, Object> resultMasked, Instant at, Instant keepUntil) {
    this.executedSha256 = executedSha256;
    this.resultMasked = resultMasked;
    this.executedAt = Objects.requireNonNull(at, "at");
    this.resultExpiresAt = keepUntil;
  }

  /** The retention of the result ran out. */
  public void forgetResult() {
    this.resultMasked = null;
    this.resultExpiresAt = null;
  }

  public String getError() {
    return error;
  }

  public void setError(String error) {
    this.error = error;
  }

  public String getTraceContext() {
    return traceContext;
  }

  public byte[] getMac() {
    return mac;
  }

  public void setMac(byte[] mac) {
    this.mac = mac;
  }

  public long getVersion() {
    return version;
  }

  /** The fields fixed at creation. */
  public static final class Builder {

    private String callId;
    private String endpointId;
    private String endpointSlug;
    private String connectionId;
    private String connectionName;
    private String tool;
    private String toolKey;
    private Env env;
    private Caller.Kind principalKind;
    private String principalId;
    private String clientLabel;
    private Map<String, Object> callCanonical;
    private String digest;
    private Map<String, Object> action;
    private String actionSha256;
    private String preparedState;
    private String agentNote;
    private String ruleId;
    private CommentPolicy commentPolicy;
    private Instant createdAt;
    private Instant expiresAt;
    private String traceContext;

    private Builder() {}

    public Builder call(String callId, String tool, String toolKey) {
      this.callId = callId;
      this.tool = tool;
      this.toolKey = toolKey;
      return this;
    }

    public Builder endpoint(String endpointId, String endpointSlug) {
      this.endpointId = endpointId;
      this.endpointSlug = endpointSlug;
      return this;
    }

    public Builder connection(String connectionId, String connectionName, Env env) {
      this.connectionId = connectionId;
      this.connectionName = connectionName;
      this.env = env;
      return this;
    }

    public Builder principal(Caller.Kind kind, String principalId, String clientLabel) {
      this.principalKind = kind;
      this.principalId = principalId;
      this.clientLabel = clientLabel;
      return this;
    }

    public Builder canonical(Map<String, Object> callCanonical, String digest) {
      this.callCanonical = callCanonical;
      this.digest = digest;
      return this;
    }

    public Builder action(Map<String, Object> action, String actionSha256, String preparedState) {
      this.action = action;
      this.actionSha256 = actionSha256;
      this.preparedState = preparedState;
      return this;
    }

    public Builder agentNote(String agentNote) {
      this.agentNote = agentNote;
      return this;
    }

    public Builder rule(String ruleId, CommentPolicy commentPolicy) {
      this.ruleId = ruleId;
      this.commentPolicy = commentPolicy;
      return this;
    }

    public Builder window(Instant createdAt, Instant expiresAt) {
      this.createdAt = createdAt;
      this.expiresAt = expiresAt;
      return this;
    }

    public Builder traceContext(String traceContext) {
      this.traceContext = traceContext;
      return this;
    }

    public ApprovalEntity build() {
      return new ApprovalEntity(this);
    }
  }
}
