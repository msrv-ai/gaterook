package ai.msrv.gaterook.core.audit.internal.persistence;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.platform.Caller;
import ai.msrv.gaterook.core.platform.EnvConverter;
import ai.msrv.gaterook.core.platform.Ingress;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One audit row of an agent call: one per phase ({@code received}, {@code resolved}, {@code completed}), chained by
 * {@code prev_sha256}/{@code row_sha256}. Append-only on three levels (report 15 §9 rule 21): {@code @Immutable}, a
 * repository with only {@code save} and reads, and the V1 triggers. Built with {@link #builder}; nothing changes after
 * the insert.
 */
@Entity
@Immutable
@Table(name = "tool_call")
public class ToolCallEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", columnDefinition = "integer", nullable = false, updatable = false)
  private Long id;

  @Column(name = "call_id", length = 40, nullable = false)
  private String callId;

  @Convert(converter = CallPhase.Converter.class)
  @Column(name = "phase", length = 10, nullable = false)
  private CallPhase phase;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  /** Epoch hours of {@code createdAt}: histograms group by an integer, without date functions. */
  @Column(name = "hour_bucket", nullable = false)
  private long hourBucket;

  @Column(name = "endpoint_slug", length = 63, nullable = false)
  private String endpointSlug;

  @Column(name = "tool_name", length = 128, nullable = false)
  private String toolName;

  @Column(name = "tool_key", length = 256)
  private String toolKey;

  @Column(name = "connection_name", length = 64)
  private String connectionName;

  @Convert(converter = EnvConverter.class)
  @Column(name = "env", length = 8)
  private Env env;

  @Column(name = "client", length = 32)
  private String client;

  /** What the client reports about itself: shown only as "reports itself as …". */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "client_info")
  private Map<String, Object> clientInfo;

  @Column(name = "token_label", length = 64)
  private String tokenLabel;

  @Enumerated(EnumType.STRING)
  @Column(name = "principal_kind", length = 12)
  private Caller.Kind principalKind;

  @Enumerated(EnumType.STRING)
  @Column(name = "ingress", length = 10)
  private Ingress ingress;

  @Column(name = "caller_ip", length = 45)
  private String callerIp;

  @Column(name = "protocol_version", length = 10)
  private String protocolVersion;

  @Convert(converter = ApprovalDeliveryMode.Converter.class)
  @Column(name = "approval_delivery", length = 8)
  private ApprovalDeliveryMode approvalDelivery;

  @Column(name = "trace_id", length = 32)
  private String traceId;

  @Column(name = "span_id", length = 16)
  private String spanId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "args_masked")
  private Map<String, Object> argsMasked;

  @Column(name = "args_sha256", length = 64)
  private String argsSha256;

  @Enumerated(EnumType.STRING)
  @Column(name = "classification", length = 10)
  private Classification.Kind classification;

  @Convert(converter = CallDecision.Converter.class)
  @Column(name = "decision", length = 10, nullable = false)
  private CallDecision decision;

  @Column(name = "rule_id", length = 128)
  private String ruleId;

  @Column(name = "approval_id", length = 40)
  private String approvalId;

  @Column(name = "duration_ms")
  private Long durationMs;

  @Column(name = "gateway_ms")
  private Long gatewayMs;

  @Column(name = "resource_ms")
  private Long resourceMs;

  @Column(name = "approval_wait_ms")
  private Long approvalWaitMs;

  @Column(name = "result_bytes")
  private Long resultBytes;

  @Column(name = "result_sha256", length = 64)
  private String resultSha256;

  /** Masked, at most 4 KB. */
  @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
  @Column(name = "result_preview")
  private String resultPreview;

  @Column(name = "masked_count")
  private Integer maskedCount;

  @Column(name = "truncated")
  private Boolean truncated;

  @Column(name = "error_kind", length = 24)
  private String errorKind;

  @Column(name = "error_message", length = 1000)
  private String errorMessage;

  /** Searchable facets: runbook, template, k8s.*. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "facets")
  private Map<String, Object> facets;

  @Column(name = "action_sha256", length = 64)
  private String actionSha256;

  @Column(name = "executed_sha256", length = 64)
  private String executedSha256;

  @Column(name = "outcome_unknown")
  private Boolean outcomeUnknown;

  @Column(name = "decided_via", length = 24)
  private String decidedVia;

  @Column(name = "comment_sha256", length = 64)
  private String commentSha256;

  @Column(name = "prev_sha256", length = 64, nullable = false)
  private String prevSha256;

  @Column(name = "row_sha256", length = 64, nullable = false)
  private String rowSha256;

  protected ToolCallEntity() {}

  /**
   * A row with its mandatory columns; the chain hashes come last, from the audit writer, once every other column is
   * known ({@link Builder#chain}).
   */
  public static Builder builder(String callId, CallPhase phase, Instant createdAt, String endpointSlug,
      String toolName, CallDecision decision) {
    return new Builder(callId, phase, createdAt, endpointSlug, toolName, decision);
  }

  public Long getId() {
    return id;
  }

  public String getCallId() {
    return callId;
  }

  public CallPhase getPhase() {
    return phase;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public long getHourBucket() {
    return hourBucket;
  }

  public String getEndpointSlug() {
    return endpointSlug;
  }

  public String getToolName() {
    return toolName;
  }

  public String getToolKey() {
    return toolKey;
  }

  public String getConnectionName() {
    return connectionName;
  }

  public Env getEnv() {
    return env;
  }

  public String getClient() {
    return client;
  }

  public Map<String, Object> getClientInfo() {
    return clientInfo;
  }

  public String getTokenLabel() {
    return tokenLabel;
  }

  public Caller.Kind getPrincipalKind() {
    return principalKind;
  }

  public Ingress getIngress() {
    return ingress;
  }

  public String getCallerIp() {
    return callerIp;
  }

  public String getProtocolVersion() {
    return protocolVersion;
  }

  public ApprovalDeliveryMode getApprovalDelivery() {
    return approvalDelivery;
  }

  public String getTraceId() {
    return traceId;
  }

  public String getSpanId() {
    return spanId;
  }

  public Map<String, Object> getArgsMasked() {
    return argsMasked;
  }

  public String getArgsSha256() {
    return argsSha256;
  }

  public Classification.Kind getClassification() {
    return classification;
  }

  public CallDecision getDecision() {
    return decision;
  }

  public String getRuleId() {
    return ruleId;
  }

  public String getApprovalId() {
    return approvalId;
  }

  public Long getDurationMs() {
    return durationMs;
  }

  public Long getGatewayMs() {
    return gatewayMs;
  }

  public Long getResourceMs() {
    return resourceMs;
  }

  public Long getApprovalWaitMs() {
    return approvalWaitMs;
  }

  public Long getResultBytes() {
    return resultBytes;
  }

  public String getResultSha256() {
    return resultSha256;
  }

  public String getResultPreview() {
    return resultPreview;
  }

  public Integer getMaskedCount() {
    return maskedCount;
  }

  public Boolean getTruncated() {
    return truncated;
  }

  public String getErrorKind() {
    return errorKind;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public Map<String, Object> getFacets() {
    return facets;
  }

  public String getActionSha256() {
    return actionSha256;
  }

  public String getExecutedSha256() {
    return executedSha256;
  }

  public Boolean getOutcomeUnknown() {
    return outcomeUnknown;
  }

  public String getDecidedVia() {
    return decidedVia;
  }

  public String getCommentSha256() {
    return commentSha256;
  }

  public String getPrevSha256() {
    return prevSha256;
  }

  public String getRowSha256() {
    return rowSha256;
  }

  /** Assembles one row; a builder is used once. */
  public static final class Builder {

    private final ToolCallEntity row = new ToolCallEntity();
    private boolean built;

    private Builder(String callId, CallPhase phase, Instant createdAt, String endpointSlug, String toolName,
        CallDecision decision) {
      row.callId = Objects.requireNonNull(callId, "callId");
      row.phase = Objects.requireNonNull(phase, "phase");
      row.createdAt = Objects.requireNonNull(createdAt, "createdAt");
      row.hourBucket = Math.floorDiv(createdAt.toEpochMilli(), Duration.ofHours(1).toMillis());
      row.endpointSlug = Objects.requireNonNull(endpointSlug, "endpointSlug");
      row.toolName = Objects.requireNonNull(toolName, "toolName");
      row.decision = Objects.requireNonNull(decision, "decision");
    }

    public Builder tool(String toolKey, String connectionName, Env env) {
      row.toolKey = toolKey;
      row.connectionName = connectionName;
      row.env = env;
      return this;
    }

    public Builder caller(Caller.Kind principalKind, Ingress ingress, String tokenLabel, String callerIp) {
      row.principalKind = principalKind;
      row.ingress = ingress;
      row.tokenLabel = tokenLabel;
      row.callerIp = callerIp;
      return this;
    }

    public Builder client(String client, Map<String, Object> clientInfo, String protocolVersion) {
      row.client = client;
      row.clientInfo = clientInfo;
      row.protocolVersion = protocolVersion;
      return this;
    }

    public Builder trace(String traceId, String spanId) {
      row.traceId = traceId;
      row.spanId = spanId;
      return this;
    }

    public Builder arguments(Map<String, Object> argsMasked, String argsSha256) {
      row.argsMasked = argsMasked;
      row.argsSha256 = argsSha256;
      return this;
    }

    public Builder policy(Classification.Kind classification, String ruleId) {
      row.classification = classification;
      row.ruleId = ruleId;
      return this;
    }

    public Builder approval(String approvalId, ApprovalDeliveryMode delivery, String decidedVia, String commentSha256,
        String actionSha256, String executedSha256) {
      row.approvalId = approvalId;
      row.approvalDelivery = delivery;
      row.decidedVia = decidedVia;
      row.commentSha256 = commentSha256;
      row.actionSha256 = actionSha256;
      row.executedSha256 = executedSha256;
      return this;
    }

    public Builder timing(Long durationMs, Long gatewayMs, Long resourceMs, Long approvalWaitMs) {
      row.durationMs = durationMs;
      row.gatewayMs = gatewayMs;
      row.resourceMs = resourceMs;
      row.approvalWaitMs = approvalWaitMs;
      return this;
    }

    public Builder result(Long resultBytes, String resultSha256, String resultPreview, Integer maskedCount,
        Boolean truncated) {
      row.resultBytes = resultBytes;
      row.resultSha256 = resultSha256;
      row.resultPreview = resultPreview;
      row.maskedCount = maskedCount;
      row.truncated = truncated;
      return this;
    }

    public Builder error(String errorKind, String errorMessage, Boolean outcomeUnknown) {
      row.errorKind = errorKind;
      row.errorMessage = errorMessage;
      row.outcomeUnknown = outcomeUnknown;
      return this;
    }

    public Builder facets(Map<String, Object> facets) {
      row.facets = facets;
      return this;
    }

    /** The hash chain: SHA-256 of the previous row and of this one. Required. */
    public Builder chain(String prevSha256, String rowSha256) {
      row.prevSha256 = prevSha256;
      row.rowSha256 = rowSha256;
      return this;
    }

    public ToolCallEntity build() {
      if (built) {
        throw new IllegalStateException("This builder already built its row");
      }
      Objects.requireNonNull(row.prevSha256, "prevSha256");
      Objects.requireNonNull(row.rowSha256, "rowSha256");
      built = true;
      return row;
    }
  }
}
