package ai.msrv.gaterook.core.audit.export;

import ai.msrv.gaterook.core.audit.internal.persistence.ToolCallEntity;
import ai.msrv.gaterook.core.platform.CanonicalJson;
import ai.msrv.gaterook.core.platform.HmacKeys;
import ai.msrv.gaterook.core.platform.KebabCaseEnumConverter;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * What an export writes (ARCHITECTURE.md §4 "Audit"): one record per {@code tool_call} row with every stored column —
 * arguments, previews and errors as the audit stored them, already masked — and its place in the hash chain
 * ({@code prevSha256}, {@code rowSha256}), so a receiver can check that the rows link and that none is missing; then
 * one {@code audit-head} record that vouches for the batch with an HMAC of the {@code audit-head/v1} key, the key of
 * the anchor in {@code setting audit.head-mac} ("head HMAC in every export"). Only the core holds the key: a
 * head can be checked by this installation, never forged by a reader of the SIEM.
 * <ul>
 *   <li>JSON lines: a record per line, the head last; the format a SIEM gets in each syslog message too;</li>
 *   <li>CSV: the same fields as columns (JSON objects as text) under a header, the head as its last row with
 *       {@code from}, {@code rows} and {@code mac}. A cell that a spreadsheet would run as a formula gets a leading
 *       {@code '}, as in Activity's file export, so the hashes check against the JSON lines, not the CSV.</li>
 * </ul>
 */
public final class ExportRecords {

  /** {@code type} of a row's record. */
  public static final String ROW = "tool-call";

  /** {@code type} of the record that closes a batch. */
  public static final String HEAD = "audit-head";

  /** The fields of a row's record, in order: the CSV header and the BigQuery columns. */
  public static final List<String> FIELDS = List.of("type", "id", "callId", "phase", "at", "endpoint", "tool",
      "toolKey", "connection", "env", "client", "clientInfo", "tokenLabel", "principalKind", "ingress", "callerIp",
      "protocolVersion", "approvalDelivery", "traceId", "spanId", "args", "argsSha256", "classification",
      "decision", "rule", "approvalId", "durationMs", "gatewayMs", "resourceMs", "approvalWaitMs", "resultBytes",
      "resultSha256", "resultPreview", "maskedCount", "truncated", "errorKind", "errorMessage", "facets",
      "actionSha256", "executedSha256", "outcomeUnknown", "decidedVia", "commentSha256", "prevSha256", "rowSha256");

  /** Columns of the head beyond {@link #FIELDS}. */
  public static final List<String> HEAD_FIELDS = List.of("from", "rows", "mac");

  /** The JSON-object fields: text in CSV and BigQuery. */
  static final List<String> OBJECTS = List.of("clientInfo", "args", "facets");

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

  private ExportRecords() {}

  /** The record of one stored row. */
  public static ObjectNode row(ToolCallEntity r) {
    ObjectNode n = JsonNodeFactory.instance.objectNode();
    n.put("type", ROW);
    n.put("id", r.getId());
    n.put("callId", r.getCallId());
    n.put("phase", KebabCaseEnumConverter.idOf(r.getPhase()));
    n.put("at", r.getCreatedAt().toString());
    n.put("endpoint", r.getEndpointSlug());
    n.put("tool", r.getToolName());
    n.put("toolKey", r.getToolKey());
    n.put("connection", r.getConnectionName());
    n.put("env", r.getEnv() == null ? null : r.getEnv().id());
    n.put("client", r.getClient());
    n.set("clientInfo", tree(r.getClientInfo()));
    n.put("tokenLabel", r.getTokenLabel());
    n.put("principalKind", r.getPrincipalKind() == null ? null : lower(r.getPrincipalKind()));
    n.put("ingress", r.getIngress() == null ? null : lower(r.getIngress()));
    n.put("callerIp", r.getCallerIp());
    n.put("protocolVersion", r.getProtocolVersion());
    n.put("approvalDelivery", r.getApprovalDelivery() == null ? null
        : KebabCaseEnumConverter.idOf(r.getApprovalDelivery()));
    n.put("traceId", r.getTraceId());
    n.put("spanId", r.getSpanId());
    n.set("args", tree(r.getArgsMasked()));
    n.put("argsSha256", r.getArgsSha256());
    n.put("classification", r.getClassification() == null ? null : lower(r.getClassification()));
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
    n.put("prevSha256", r.getPrevSha256());
    n.put("rowSha256", r.getRowSha256());
    return n;
  }

  /**
   * The head of a batch, signed: {@code id}/{@code rowSha256} — the last row it carries (for a scheduled batch) or
   * the chain's head when it was sent (a selection from Activity); {@code from}/{@code prevSha256} — where a scheduled
   * batch starts in the chain, {@code null} for a selection; {@code rows} — how many rows came with it.
   *
   * @throws IllegalStateException while the master key is locked: no export goes without its head
   */
  public static ObjectNode head(HmacKeys keys, Long from, long id, int rows, String prevSha256, String rowSha256,
      Instant at) {
    ObjectNode n = JsonNodeFactory.instance.objectNode();
    n.put("type", HEAD);
    n.put("from", from);
    n.put("id", id);
    n.put("rows", rows);
    n.put("prevSha256", prevSha256);
    n.put("rowSha256", rowSha256);
    n.put("at", at.toString());
    n.put("mac", B64.encodeToString(keys.mac(HmacKeys.Purpose.AUDIT_HEAD, CanonicalJson.bytes(n))));
    return n;
  }

  /** Whether {@code head}'s MAC is the core's under {@code keys} (a locked core can't tell: {@code false}). */
  public static boolean verify(HmacKeys keys, JsonNode head) {
    if (!head.isObject() || !HEAD.equals(head.path("type").asString(null))) {
      return false;
    }
    ObjectNode unsigned = (ObjectNode) head.deepCopy();
    unsigned.remove("mac");
    try {
      byte[] mac = Base64.getUrlDecoder().decode(head.path("mac").asString(""));
      return keys.verify(HmacKeys.Purpose.AUDIT_HEAD, CanonicalJson.bytes(unsigned), mac);
    } catch (IllegalArgumentException | IllegalStateException e) {
      return false;
    }
  }

  /** JSON lines, UTF-8, one record per line. */
  public static byte[] jsonl(List<ObjectNode> records) {
    ByteArrayOutputStream out = new ByteArrayOutputStream(records.size() * 512);
    for (ObjectNode r : records) {
      out.writeBytes(JSON.writeValueAsBytes(r));
      out.write('\n');
    }
    return out.toByteArray();
  }

  /** CSV with a header ({@link #FIELDS} and {@link #HEAD_FIELDS}), every cell quoted. */
  public static byte[] csv(List<ObjectNode> records) {
    List<String> columns = new ArrayList<>(FIELDS);
    columns.addAll(HEAD_FIELDS);
    StringBuilder out = new StringBuilder(records.size() * 512);
    out.append(String.join(",", columns)).append('\n');
    for (ObjectNode r : records) {
      List<String> cells = new ArrayList<>(columns.size());
      for (String c : columns) {
        cells.add(cell(text(r.get(c))));
      }
      out.append(String.join(",", cells)).append('\n');
    }
    return out.toString().getBytes(StandardCharsets.UTF_8);
  }

  /**
   * A record as a flat JSON object for BigQuery: the JSON-object fields as their text (a {@code JSON} or
   * {@code STRING} column takes it), everything else as it is.
   */
  public static ObjectNode flat(ObjectNode record) {
    ObjectNode out = record.deepCopy();
    for (String f : OBJECTS) {
      JsonNode v = out.get(f);
      if (v != null && !v.isNull()) {
        out.put(f, JSON.writeValueAsString(v));
      }
    }
    return out;
  }

  /** The SHA-256 of {@code bytes}, as S3's {@code x-amz-checksum-sha256} and our object names want it. */
  static byte[] sha256(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String text(JsonNode v) {
    if (v == null || v.isNull() || v.isMissingNode()) {
      return "";
    }
    return v.isValueNode() ? v.asString() : JSON.writeValueAsString(v);
  }

  private static String cell(String text) {
    String t = text;
    // A leading formula character is defused so a spreadsheet never runs a cell an agent wrote.
    if (!t.isEmpty() && "=+-@\t\r".indexOf(t.charAt(0)) >= 0) {
      t = "'" + t;
    }
    return "\"" + t.replace("\"", "\"\"") + "\"";
  }

  private static JsonNode tree(Map<String, Object> map) {
    return map == null ? JsonNodeFactory.instance.nullNode() : JSON.valueToTree(map);
  }

  private static String lower(Enum<?> e) {
    return e.name().toLowerCase(Locale.ROOT);
  }
}
