package ai.msrv.gaterook.core.mcp.internal;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.core.audit.AuditWriter;
import ai.msrv.gaterook.core.platform.CanonicalJson;
import ai.msrv.gaterook.core.platform.MaskPolicy;
import ai.msrv.gaterook.core.platform.Masker;
import ai.msrv.gaterook.core.platform.Taint;
import ai.msrv.gaterook.core.policy.PolicyService;
import java.util.Locale;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The audit identity of a call (ARCHITECTURE.md §5 step 8): masked arguments and their hash, the client family, the
 * protocol revision, the trace, the facets the query language and the trace summarizer read.
 */
@Component
public class Calls {

  /** {@code _meta} key a client may name its task with (the trace's title, unverified). */
  public static final String META_TASK = "ai.msrv.gaterook/task";

  private final Masker masker;
  private final PolicyService policies;

  public Calls(Masker masker, PolicyService policies) {
    this.masker = masker;
    this.policies = policies;
  }

  /**
   * @param tool       the published tool (or the method, for resources), with its nature
   * @param arguments  the arguments as sent (masked here)
   * @param extraFacets runbook, template, … or {@code null}
   */
  public AuditWriter.Call call(McpRequest r, String callId, String tool, String toolKey, String connection, Env env,
      ToolDefinition definition, JsonNode arguments, ObjectNode extraFacets) {
    Env maskEnv = env == null ? r.snapshot().env() : env;
    JsonNode masked = arguments == null ? null
        : masker.tree(arguments, new Taint(), policies.maskPolicy(maskEnv)).tree();
    ObjectNode facets = JsonNodeFactory.instance.objectNode();
    if (definition != null) {
      facets.put("nature", definition.nature().name().toLowerCase(Locale.ROOT));
    }
    JsonNode task = r.env().meta().path(META_TASK);
    if (task.isString() && !task.asString().isBlank()) {
      facets.put("task", masker.text(task.asString(), new Taint(), policies.maskPolicy(maskEnv)).text());
    }
    if (extraFacets != null) {
      facets.setAll(extraFacets);
    }
    return new AuditWriter.Call(callId, r.snapshot().slug(), tool, toolKey, connection, env, r.caller(),
        client(r.caller().clientHint(), r.env().clientInfo()), r.env().clientInfo() == null
            || !r.env().clientInfo().isObject() ? null : r.env().clientInfo(), r.env().version(), r.span().traceId(),
        r.span().spanId(), r.span().fromClient() || facets.has("task"), masked,
        arguments == null ? null : CanonicalJson.sha256Hex(arguments), facets);
  }

  /**
   * The decision's chain for the event detail's Policy timeline (kit {@code event-detail}: "sql: classified as DML ·
   * UPDATE on 1 table…", "dev.sql.write-approval · Matched rule", "limits: within rate limit · 14 of 120 calls per
   * minute", "Decision: ask for approval"), stored with the {@code received} row as facet {@code policy}:
   * <pre>{"mode": "APPROVAL", "classification": {"kind": "MUTATION", "reason": "…"}, "rule": {"id": "…",
   *  "reason": "…"}, "outcome": "APPROVAL", "rate": {"perToken": 120, "tokenCalls": 14, "perTool": 30,
   *  "toolCalls": 3}, "limits": {"maxRows": 1000, "statementTimeoutMs": 5000, "toolTimeoutMs": 10000,
   *  "maxResponseKb": 512}}</pre>
   * The connector's and the policy's words are masked like the arguments: they may quote them.
   */
  public ObjectNode policyFacet(Env env, ToolDefinition.Mode mode, Classification classification,
      ai.msrv.gaterook.core.policy.Decision decision, ai.msrv.gaterook.core.policy.PolicyEngine.Rates rates,
      ai.msrv.gaterook.core.invocation.ToolGuard.Admission admission) {
    MaskPolicy mask = policies.maskPolicy(env);
    ObjectNode p = JsonNodeFactory.instance.objectNode();
    p.put("mode", mode == null ? null : mode.name());
    if (classification != null) {
      ObjectNode c = p.putObject("classification");
      c.put("kind", classification.kind().name());
      if (classification.reason() != null) {
        c.put("reason", masker.text(classification.reason(), new Taint(), mask).text());
      }
    }
    if (decision.ruleId() != null || decision.reason() != null) {
      ObjectNode rule = p.putObject("rule");
      rule.put("id", decision.ruleId());
      if (decision.reason() != null) {
        rule.put("reason", masker.text(decision.reason(), new Taint(), mask).text());
      }
    }
    p.put("outcome", decision.outcome().name());
    ObjectNode rate = p.putObject("rate");
    rate.put("perToken", rates.perToken());
    rate.put("tokenCalls", admission.principalCalls());
    rate.put("perTool", rates.perTool());
    rate.put("toolCalls", admission.toolCalls());
    ai.msrv.gaterook.connector.api.Limits l = decision.limits();
    if (l != null) {
      ObjectNode limits = p.putObject("limits");
      limits.put("maxRows", l.maxRows());
      if (l.statementTimeout() != null) {
        limits.put("statementTimeoutMs", l.statementTimeout().toMillis());
      }
      if (l.toolTimeout() != null) {
        limits.put("toolTimeoutMs", l.toolTimeout().toMillis());
      }
      limits.put("maxResponseKb", l.maxResponseBytes() / 1024);
    }
    return p;
  }

  /**
   * The client family for Activity: what the credential implies (a grant's client), else a guess from the name the
   * client gives itself, shown as "reports itself as …".
   */
  static String client(String hint, JsonNode clientInfo) {
    if (hint != null && !hint.isBlank()) {
      return hint;
    }
    String name = clientInfo == null ? "" : clientInfo.path("name").asString("").toLowerCase(Locale.ROOT);
    if (name.isEmpty()) {
      return null;
    }
    if (name.contains("codex")) {
      return "codex";
    }
    if (name.contains("claude-ai") || name.equals("claude.ai")) {
      return "claude-ai";
    }
    if (name.contains("claude")) {
      return "claude-desktop";
    }
    if (name.contains("cursor")) {
      return "cursor";
    }
    if (name.contains("antigravity")) {
      return "antigravity";
    }
    if (name.contains("inspector")) {
      return "inspector";
    }
    return "other";
  }
}
