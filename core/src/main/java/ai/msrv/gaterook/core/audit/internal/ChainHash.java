package ai.msrv.gaterook.core.audit.internal;

import ai.msrv.gaterook.core.audit.internal.persistence.SetupChangeEntity;
import ai.msrv.gaterook.core.audit.internal.persistence.ToolCallEntity;
import ai.msrv.gaterook.core.platform.CanonicalJson;
import ai.msrv.gaterook.core.platform.KebabCaseEnumConverter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The hash chain of the two append-only audit tables (ARCHITECTURE.md §4, §9 "Fail-closed append-only audit"):
 * {@code row_sha256} = SHA-256({@code prev_sha256} ‖ LF ‖ canonical JSON of the row). The canonical form is computed
 * from the stored columns, so the writer and every later verification run the same function over the same values; JSON
 * columns go through {@link CanonicalJson}, which gives the same text after a round trip through the database
 * whatever order or number typing the map had. The row's own id is left out: it is assigned by the insert, and the
 * {@code prev_sha256} links fix the order anyway.
 */
public final class ChainHash {

  /** {@code prev_sha256} of a table's first row. */
  public static final String GENESIS = "0".repeat(64);

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private ChainHash() {}

  /** {@code row_sha256} of a {@code tool_call} row whose previous row hashed to {@code prev}. */
  public static String of(String prev, ToolCallEntity row) {
    return sha256(prev + "\n" + CanonicalJson.text(canonical(row)));
  }

  /**
   * What a stored {@code tool_call} row must hash to when it is checked: {@link #of} — but only while its
   * {@code hour_bucket} is the hour of its {@code created_at}. The bucket is derived, so the canonical form leaves it
   * out; this keeps an edit of the bucket alone (around the triggers) from moving a call between the Insights and
   * Activity histogram buckets unnoticed: such a row matches no hash and is reported as changed.
   */
  public static String ofStored(String prev, ToolCallEntity row) {
    return row.getHourBucket() == hourOf(row.getCreatedAt()) ? of(prev, row) : "hour_bucket-is-not-its-hour";
  }

  /** The epoch hour {@code hour_bucket} holds for a call made {@code at}. */
  public static long hourOf(Instant at) {
    return Math.floorDiv(at.toEpochMilli(), 3_600_000L);
  }

  /** {@code row_sha256} of a {@code setup_change} row whose previous row hashed to {@code prev}. */
  public static String of(String prev, SetupChangeEntity row) {
    return sha256(prev + "\n" + CanonicalJson.text(canonical(row)));
  }

  static ObjectNode canonical(ToolCallEntity r) {
    ObjectNode n = JsonNodeFactory.instance.objectNode();
    n.put("callId", r.getCallId());
    n.put("phase", KebabCaseEnumConverter.idOf(r.getPhase()));
    n.put("at", millis(r.getCreatedAt()));
    n.put("endpoint", r.getEndpointSlug());
    n.put("tool", r.getToolName());
    n.put("toolKey", r.getToolKey());
    n.put("connection", r.getConnectionName());
    n.put("env", r.getEnv() == null ? null : r.getEnv().id());
    n.put("client", r.getClient());
    n.set("clientInfo", tree(r.getClientInfo()));
    n.put("tokenLabel", r.getTokenLabel());
    n.put("principalKind", r.getPrincipalKind() == null ? null : r.getPrincipalKind().name());
    n.put("ingress", r.getIngress() == null ? null : r.getIngress().name());
    n.put("ip", r.getCallerIp());
    n.put("protocol", r.getProtocolVersion());
    n.put("delivery", r.getApprovalDelivery() == null ? null : KebabCaseEnumConverter.idOf(r.getApprovalDelivery()));
    n.put("traceId", r.getTraceId());
    n.put("spanId", r.getSpanId());
    n.set("args", tree(r.getArgsMasked()));
    n.put("argsSha256", r.getArgsSha256());
    n.put("classification", r.getClassification() == null ? null : r.getClassification().name());
    n.put("decision", KebabCaseEnumConverter.idOf(r.getDecision()));
    n.put("rule", r.getRuleId());
    n.put("approvalId", r.getApprovalId());
    n.put("durationMs", r.getDurationMs());
    n.put("gatewayMs", r.getGatewayMs());
    n.put("resourceMs", r.getResourceMs());
    n.put("approvalWaitMs", r.getApprovalWaitMs());
    n.put("resultBytes", r.getResultBytes());
    n.put("resultSha256", r.getResultSha256());
    n.put("resultPreview", r.getResultPreview());
    n.put("maskedCount", r.getMaskedCount());
    n.put("truncated", r.getTruncated());
    n.put("errorKind", r.getErrorKind());
    n.put("errorMessage", r.getErrorMessage());
    n.set("facets", tree(r.getFacets()));
    n.put("actionSha256", r.getActionSha256());
    n.put("executedSha256", r.getExecutedSha256());
    n.put("outcomeUnknown", r.getOutcomeUnknown());
    n.put("decidedVia", r.getDecidedVia());
    n.put("commentSha256", r.getCommentSha256());
    return n;
  }

  static ObjectNode canonical(SetupChangeEntity r) {
    ObjectNode n = JsonNodeFactory.instance.objectNode();
    n.put("at", millis(r.getCreatedAt()));
    n.put("what", r.getWhat().id());
    n.put("subject", r.getSubject());
    n.put("from", r.getChangeFrom());
    n.put("to", r.getChangeTo());
    n.put("reason", r.getReason());
    n.put("by", r.getByActor());
    n.put("env", r.getEnv() == null ? null : r.getEnv().id());
    n.put("endpoint", r.getEndpointSlug());
    n.put("connection", r.getConnectionName());
    n.set("details", tree(r.getDetails()));
    return n;
  }

  /** The column stores epoch milliseconds: hash what a re-read gives back. */
  private static long millis(Instant at) {
    return at.toEpochMilli();
  }

  private static JsonNode tree(Map<String, Object> map) {
    return map == null ? JsonNodeFactory.instance.nullNode() : JSON.valueToTree(map);
  }

  public static String sha256(String text) {
    return sha256(text.getBytes(StandardCharsets.UTF_8));
  }

  public static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
