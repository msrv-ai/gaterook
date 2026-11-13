package com.gaterook.core.mcp.internal;

import com.gaterook.connector.api.Classification;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.ToolDefinition;
import com.gaterook.core.audit.AuditWriter;
import com.gaterook.core.platform.Caller;
import com.gaterook.core.platform.CanonicalJson;
import com.gaterook.core.platform.MaskPolicy;
import com.gaterook.core.platform.Masker;
import com.gaterook.core.platform.Taint;
import com.gaterook.core.policy.PolicyService;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The audit identity of a call (ARCHITECTURE.md §5 step 8): masked arguments, their hash and their size as sent, the
 * client family, the protocol revision, the trace, the facets the query language and the trace summarizer read.
 */
@Component
public class Calls {

  /** {@code _meta} key a client may name its task with (the trace's title, unverified). */
  public static final String META_TASK = "ai.msrv.gaterook/task";

  /** The audit keeps this much of a task's name. */
  static final int MAX_TASK = 512;

  /** The audit keeps a call's masked arguments whole up to this many characters of JSON; beyond, a preview. */
  static final int MAX_STORED_ARGUMENTS = 32 * 1024;

  static final int ARGUMENTS_PREVIEW = 8 * 1024;

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
      facets.put("task", cut(masker.text(task.asString(), new Taint(), policies.maskPolicy(maskEnv)).text(),
          MAX_TASK));
    }
    if (extraFacets != null) {
      facets.setAll(extraFacets);
    }
    // One canonical text gives the arguments' digest and what the app sent with the call (Activity's "in").
    byte[] sent = arguments == null ? null : CanonicalJson.bytes(arguments);
    String sha256 = sent == null ? null : CanonicalJson.sha256Hex(sent);
    return new AuditWriter.Call(callId, r.snapshot().slug(), tool, toolKey, connection, env, r.caller(),
        client(r.caller(), r.env().clientInfo()), clientInfo(r.env().clientInfo()), r.env().version(),
        r.span().traceId(), r.span().spanId(), r.span().fromClient() || facets.has("task"),
        storedArguments(masked, sha256), sha256, sent == null ? null : (long) sent.length, facets);
  }

  /**
   * What the audit keeps of a client's self-description: its name, title and version, each cut short. The rest of
   * whatever object it sent isn't kept: the audit's rows stay for a year, and a client could send as much as a body
   * holds with every call.
   */
  static JsonNode clientInfo(JsonNode sent) {
    if (sent == null || !sent.isObject()) {
      return null;
    }
    ObjectNode kept = JsonNodeFactory.instance.objectNode();
    for (String field : List.of("name", "title", "version")) {
      JsonNode v = sent.get(field);
      if (v != null && v.isString()) {
        kept.put(field, cut(v.asString(), field.equals("version") ? 32 : 64));
      }
    }
    return kept;
  }

  /**
   * The masked arguments as the audit keeps them: whole up to {@value #MAX_STORED_ARGUMENTS} characters of their
   * JSON; a longer call's are their size, their hash (the same one the row has) and their first
   * {@value #ARGUMENTS_PREVIEW} characters.
   */
  static JsonNode storedArguments(JsonNode masked, String sha256) {
    if (masked == null) {
      return null;
    }
    String text = CanonicalJson.text(masked);
    if (text.length() <= MAX_STORED_ARGUMENTS) {
      return masked;
    }
    ObjectNode cut = JsonNodeFactory.instance.objectNode();
    cut.putObject("‹truncated›").put("chars", text.length()).put("sha256", sha256)
        .put("preview", text.substring(0, ARGUMENTS_PREVIEW) + "…");
    return cut;
  }

  private static String cut(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max - 1) + "…";
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
      com.gaterook.core.policy.Decision decision, com.gaterook.core.policy.PolicyEngine.Rates rates,
      com.gaterook.core.invocation.ToolGuard.Admission admission) {
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
    com.gaterook.connector.api.Limits l = decision.limits();
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
   * The client family for Activity: what the credential implies (a grant's client, an OAuth client GateRook
   * recognises, the Playground). A named token implies none, so for its calls the family is a guess from the name the
   * client gives itself, shown only as "reports itself as …" ({@link Caller#familyReported}). Any other credential that
   * implies no family records none: whoever holds it could give itself any name, and Clients reads an OAuth call's
   * family as the sign-in being used.
   */
  static String client(Caller caller, JsonNode clientInfo) {
    String hint = caller.clientHint();
    if (hint != null && !hint.isBlank()) {
      return hint;
    }
    if (!Caller.familyReported(caller.kind())) {
      return null;
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
