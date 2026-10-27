package ai.msrv.gaterook.core.approvals.internal;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.core.approvals.ApprovalView;
import ai.msrv.gaterook.core.approvals.internal.persistence.ApprovalEntity;
import ai.msrv.gaterook.core.audit.AuditWriter;
import ai.msrv.gaterook.core.platform.CanonicalJson;
import ai.msrv.gaterook.core.platform.Caller;
import ai.msrv.gaterook.core.platform.Ingress;
import ai.msrv.gaterook.core.platform.KebabCaseEnumConverter;
import ai.msrv.gaterook.core.platform.RowMac;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * What an approval row means, signs and repeats (ARCHITECTURE.md §4 "Integrity", §5 step 11, §9 "Approve the exact
 * action").
 * <ul>
 *   <li>The canonical call ({@code call_canonical}): tool, connection id, name and config version, arguments, template
 *       binding — what runs after approval — plus the origin of the call for its audit rows (not hashed).</li>
 *   <li>The digest: SHA-256 over tool, connection, config version, arguments, template and version, the exact action
 *       with its payload, and {@code prepare}'s state. The executor computes it again before it runs anything.</li>
 *   <li>The MAC: over the columns fixed at creation (who asked, what for, which rule, until when, and the digest, which
 *       binds the rest). Signed as version 0: those columns never change, while every status CAS bumps the row's
 *       version; the status itself is guarded by the versioned CAS and the audit rows. The foreign keys are left out,
 *       as deleting the endpoint or connection nulls them for the history.</li>
 * </ul>
 */
@Component
public class ApprovalRows {

  static final String TABLE = "approval";

  private final RowMac mac;

  ApprovalRows(RowMac mac) {
    this.mac = mac;
  }

  /** The stored canonical call. */
  public static ObjectNode canonical(ToolCall call, String toolKey, String connectionId, String connectionName,
      long cfgVersion, ObjectNode origin) {
    ObjectNode n = JsonNodeFactory.instance.objectNode();
    n.put("tool", call.tool());
    n.put("toolKey", toolKey);
    ObjectNode connection = n.putObject("connection");
    connection.put("id", connectionId);
    connection.put("name", connectionName);
    connection.put("version", cfgVersion);
    n.set("arguments", call.arguments() == null ? JsonNodeFactory.instance.objectNode() : call.arguments().deepCopy());
    if (call.template() != null) {
      ObjectNode t = n.putObject("template");
      t.put("id", call.template().templateId());
      t.put("version", call.template().version());
      t.put("opKey", call.template().opKey());
      t.set("binding", call.template().binding());
    } else {
      n.putNull("template");
    }
    n.set("origin", origin);
    return n;
  }

  /** The digest of a canonical call with its action and prepared state. */
  public static String digest(JsonNode canonical, JsonNode action, JsonNode preparedState) {
    ObjectNode n = JsonNodeFactory.instance.objectNode();
    n.put("tool", canonical.path("tool").asString());
    n.put("connection", canonical.path("connection").path("id").asString());
    n.put("cfgVersion", canonical.path("connection").path("version").asLong());
    n.set("arguments", canonical.path("arguments"));
    JsonNode template = canonical.path("template");
    if (template.isObject()) {
      ObjectNode t = n.putObject("template");
      t.put("id", template.path("id").asString());
      t.put("version", template.path("version").asInt());
      t.put("opKey", template.path("opKey").asString());
    }
    ObjectNode a = n.putObject("action");
    a.put("text", action.path("text").asString(""));
    a.set("payload", action.path("payload").isMissingNode() ? JsonNodeFactory.instance.nullNode()
        : action.path("payload"));
    n.set("preparedState", preparedState == null ? JsonNodeFactory.instance.nullNode() : preparedState);
    return CanonicalJson.sha256Hex(n);
  }

  /** {@code action_sha256}: the exact action and its payload, what the approver saw and what executes. */
  public static String actionSha256(JsonNode action) {
    ObjectNode n = JsonNodeFactory.instance.objectNode();
    n.put("text", action.path("text").asString(""));
    n.set("payload", action.path("payload").isMissingNode() ? JsonNodeFactory.instance.nullNode()
        : action.path("payload"));
    return CanonicalJson.sha256Hex(n);
  }

  /** The stored call as a {@link ToolCall} again: the arguments (and template) the person approved. */
  public static ToolCall toolCall(String callId, JsonNode canonical) {
    JsonNode t = canonical.path("template");
    ToolCall.TemplateBinding binding = t.isObject() ? new ToolCall.TemplateBinding(t.path("id").asString(),
        t.path("version").asInt(), t.path("opKey").asString(), t.path("binding")) : null;
    ObjectNode args = canonical.path("arguments").isObject() ? (ObjectNode) canonical.path("arguments").deepCopy()
        : JsonNodeFactory.instance.objectNode();
    return new ToolCall(callId, canonical.path("tool").asString(), canonical.path("connection").path("name")
        .asString(), args, binding);
  }

  public void sign(ApprovalEntity a) {
    a.setMac(mac.sign(TABLE, a.getId(), 0, fields(a)));
  }

  public boolean verify(ApprovalEntity a) {
    return mac.verify(TABLE, a.getId(), 0, fields(a), a.getMac());
  }

  private ObjectNode fields(ApprovalEntity a) {
    ObjectNode f = mac.fields();
    f.put("callId", a.getCallId());
    f.put("endpointSlug", a.getEndpointSlug());
    f.put("connectionName", a.getConnectionName());
    f.put("tool", a.getTool());
    f.put("toolKey", a.getToolKey());
    f.put("env", a.getEnv().id());
    f.put("principalKind", a.getPrincipalKind().name());
    f.put("principalId", a.getPrincipalId());
    f.put("clientLabel", a.getClientLabel());
    f.put("digest", a.getDigest());
    f.put("actionSha256", a.getActionSha256());
    f.put("ruleId", a.getRuleId());
    f.put("commentPolicy", KebabCaseEnumConverter.idOf(a.getCommentPolicy()));
    f.put("createdAt", a.getCreatedAt().toEpochMilli());
    f.put("expiresAt", a.getExpiresAt().toEpochMilli());
    f.put("traceContext", a.getTraceContext());
    return f;
  }

  public ApprovalView view(ApprovalEntity a) {
    JsonNode call = mac.tree(a.getCallCanonical());
    ActionPreview action = Codecs.action(mac.tree(a.getAction()));
    ToolResult result = a.getResultMasked() == null ? null : Codecs.result(mac.tree(a.getResultMasked()));
    return new ApprovalView(a.getId(), a.getCallId(), a.getEndpointId(), a.getEndpointSlug(), a.getConnectionId(),
        a.getConnectionName(), a.getTool(), a.getToolKey(), a.getEnv(), a.getPrincipalKind(), a.getPrincipalId(),
        a.getClientLabel(), KebabCaseEnumConverter.idOf(a.getStatus()), a.getDigest(), action, a.getActionSha256(),
        a.getAgentNote(), a.getRuleId(), KebabCaseEnumConverter.idOf(a.getCommentPolicy()), a.getComment(),
        a.getDecidedVia(), a.getDecidedAt(), a.getCreatedAt(), a.getExpiresAt(), a.getExecutedAt(), result,
        a.getResultExpiresAt(), a.getError(), a.getTraceContext(), call);
  }

  /** The audit identity of the request's call, rebuilt from the row: its later rows repeat its received row. */
  public AuditWriter.Call auditCall(ApprovalEntity a) {
    JsonNode call = mac.tree(a.getCallCanonical());
    JsonNode origin = call.path("origin");
    Ingress ingress;
    try {
      ingress = Ingress.valueOf(origin.path("ingress").asString("LOCAL"));
    } catch (IllegalArgumentException e) {
      ingress = Ingress.LOCAL;
    }
    Caller caller = new Caller(a.getPrincipalKind(), a.getPrincipalId(), a.getClientLabel(),
        origin.path("client").asString(null), ingress, origin.path("ip").asString(null));
    JsonNode clientInfo = origin.path("clientInfo");
    return new AuditWriter.Call(a.getCallId(), a.getEndpointSlug(), a.getTool(), a.getToolKey(),
        a.getConnectionName(), a.getEnv(), caller, origin.path("client").asString(null),
        clientInfo.isObject() ? clientInfo : null, origin.path("protocol").asString(null),
        origin.path("traceId").asString(null), origin.path("spanId").asString(null),
        origin.path("traceFromClient").asBoolean(false), null, null, null);
  }
}
