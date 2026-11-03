package ai.msrv.gaterook.core.mcp.internal;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.core.audit.AuditUnavailableException;
import ai.msrv.gaterook.core.audit.AuditWriter;
import ai.msrv.gaterook.core.connectors.ConnectorRegistry;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshotService;
import ai.msrv.gaterook.core.invocation.ConnectorInvoker;
import ai.msrv.gaterook.core.invocation.ToolGuard;
import ai.msrv.gaterook.core.platform.Ids;
import ai.msrv.gaterook.core.policy.Decision;
import ai.msrv.gaterook.core.policy.PolicyEngine;
import ai.msrv.gaterook.core.policy.PolicyService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Prompts and completions (ARCHITECTURE.md §5.4): the gateway's own prompts ({@link Prompts}) and those of every
 * external MCP server the person let share its prompts, named as the endpoint publishes them (its tool prefix and the
 * server's name); {@code completion/complete} for a shared prompt's argument or a shared resource template's variable.
 *
 * <p>A shared prompt's messages are the server's text: data that reaches the conversation when a person picks the
 * prompt, never instructions of the gateway. So {@code prompts/get} of one goes the way of a resource read: the
 * user's pause, the token's rate, the policy's READ decision of the connection, audit {@code received} and
 * {@code completed}, the messages masked like a result (an image of a message is withheld when its bytes carry a
 * secret). Completions are masked and rate-limited too, and capped at 100 values.
 */
@Component
public class PromptsHandler {

  /** The pseudo-tool of shared prompts in the audit. */
  static final String TOOL = "prompts/get";
  static final String COMPLETE = "completion/complete";
  static final int MAX_VALUES = 100;

  private final ConnectorRegistry registry;
  private final PolicyEngine engine;
  private final PolicyService policies;
  private final ToolGuard guard;
  private final AuditWriter audit;
  private final ConnectorInvoker invoker;
  private final Calls calls;
  private final EndpointSnapshotService snapshots;

  public PromptsHandler(ConnectorRegistry registry, PolicyEngine engine, PolicyService policies, ToolGuard guard,
      AuditWriter audit, ConnectorInvoker invoker, Calls calls, EndpointSnapshotService snapshots) {
    this.registry = registry;
    this.engine = engine;
    this.policies = policies;
    this.guard = guard;
    this.audit = audit;
    this.invoker = invoker;
    this.calls = calls;
    this.snapshots = snapshots;
  }

  public ObjectNode list(McpRequest r) {
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    ArrayNode prompts = out.putArray("prompts");
    Set<String> names = new HashSet<>();
    for (JsonNode p : Prompts.list()) {
      prompts.add(p);
      names.add(p.path("name").asString());
    }
    for (Listed l : listed(r)) {
      for (JsonNode p : l.prompts()) {
        if (names.add(p.path("name").asString(""))) {
          prompts.add(p.deepCopy());
        }
      }
    }
    return out;
  }

  /** Which connections of the endpoint share their prompts, as a hash: a list a client keeps is stale when it changes. */
  public String offerHash(ai.msrv.gaterook.core.endpoints.EndpointSnapshot s) {
    StringBuilder out = new StringBuilder();
    for (Sharing.Shared x : Sharing.of(s, registry, Sharing.What.PROMPTS)) {
      out.append(x.name()).append(':').append(x.config().version()).append('|');
    }
    return ai.msrv.gaterook.core.platform.CanonicalJson.sha256Hex(JsonNodeFactory.instance.stringNode(
        out.toString()));
  }

  /** Whether a connection of the endpoint shares prompts of its own. */
  public boolean shared(McpRequest r) {
    return !Sharing.of(r.snapshot(), registry, Sharing.What.PROMPTS).isEmpty();
  }

  public ObjectNode get(McpRequest r) {
    String name = r.env().params().path("name").asString("");
    JsonNode arguments = r.env().params().path("arguments");
    Optional<ObjectNode> own = Prompts.get(name, arguments);
    if (own.isPresent()) {
      return own.get();
    }
    Sharing.Shared s = owner(r, name).orElseThrow(() -> McpException.invalidParams("Unknown prompt: " + name));
    String disabled = r.snapshot().disabledReason(s.name());
    if (disabled != null) throw new McpException(JsonRpc.INVALID_PARAMS, disabled);
    admit(r, s, TOOL);
    Decision decision = decide(s);
    String callId = Ids.next("tc_");
    ObjectNode args = JsonNodeFactory.instance.objectNode().put("name", name);
    args.set("arguments", arguments.isObject() ? arguments.deepCopy() : JsonNodeFactory.instance.objectNode());
    AuditWriter.Call call = calls.call(r, callId, TOOL, "prompt:" + name, s.name(), s.config().env(),
        ToolDefinition.builder("prompts_get").build(), args, null);
    if (decision.outcome() != Decision.Outcome.ALLOW) {
      received(call, AuditWriter.Received.of(AuditWriter.Verdict.DENIED, Classification.Kind.READ,
          decision.ruleId()));
      throw new McpException(JsonRpc.INVALID_PARAMS, "Denied by policy " + decision.ruleId() + ": "
          + decision.reason());
    }
    received(call, AuditWriter.Received.of(AuditWriter.Verdict.ALLOWED, Classification.Kind.READ, null));
    long started = System.nanoTime();
    ConnectorInvoker.Outcome o = invoker.query(request(r, s, callId, args, decision),
        (c, cfg, secrets, ctx) -> asResult(c.getPrompt(name, args.get("arguments"), cfg, secrets, ctx)));
    long durationMs = (System.nanoTime() - started) / 1_000_000;
    try {
      audit.completed(call, new AuditWriter.Completion(o.ok() ? AuditWriter.Verdict.ALLOWED
          : AuditWriter.Verdict.ERROR, null, null, null, null, durationMs, Math.max(0, durationMs - o.resourceMs()),
          o.resourceMs(), null, o.resultBytes(), o.resultSha256(), o.preview(), o.maskedCount(), o.truncated(),
          o.errorKind() == null ? null : o.errorKind().name(), o.errorMessage(), null, null, null));
    } catch (AuditUnavailableException e) {
      throw new McpException(JsonRpc.INTERNAL_ERROR, "The audit couldn't record the prompt, so it is not shown.");
    }
    if (!o.ok()) {
      throw new McpException(JsonRpc.INTERNAL_ERROR, o.errorMessage());
    }
    return asPrompt(o.result());
  }

  /**
   * {@code completion/complete}: nothing for the gateway's own prompts, the server's suggestions for a shared
   * prompt's argument or a shared resource template's variable.
   */
  public ObjectNode complete(McpRequest r) {
    JsonNode ref = r.env().params().path("ref");
    String type = ref.path("type").asString("");
    Optional<Sharing.Shared> owner;
    switch (type) {
      case "ref/prompt" -> {
        String name = ref.path("name").asString("");
        if (Prompts.ALL.stream().anyMatch(p -> p.name().equals(name))) {
          return completion(JsonNodeFactory.instance.arrayNode(), false);
        }
        owner = owner(r, name);
      }
      case "ref/resource" -> owner = Sharing.forUri(r.snapshot(), registry, ref.path("uri").asString(""))
          .filter(s -> decide(s).outcome() == Decision.Outcome.ALLOW);
      default -> throw McpException.invalidParams("params.ref must be a ref/prompt or a ref/resource");
    }
    if (owner.isEmpty()) {
      return completion(JsonNodeFactory.instance.arrayNode(), false);
    }
    Sharing.Shared s = owner.get();
    String disabled = r.snapshot().disabledReason(s.name());
    if (disabled != null) throw new McpException(JsonRpc.INVALID_PARAMS, disabled);
    admit(r, s, COMPLETE);
    Decision decision = decide(s);
    if (decision.outcome() != Decision.Outcome.ALLOW) {
      return completion(JsonNodeFactory.instance.arrayNode(), false);
    }
    ObjectNode params = r.env().params().deepCopy();
    params.remove("_meta");
    ConnectorInvoker.Outcome o = invoker.query(request(r, s, Ids.next("tc_"), params, decision),
        (c, cfg, secrets, ctx) -> {
          JsonNode answer = c.complete(params, cfg, secrets, ctx);
          return answer == null ? null : ToolResult.json(answer, null);
        });
    JsonNode got = o.ok() && o.result().structuredContent() != null ? o.result().structuredContent()
        .path("completion") : null;
    ArrayNode values = JsonNodeFactory.instance.arrayNode();
    if (got != null) {
      for (JsonNode v : got.path("values")) {
        if (v.isString() && values.size() < MAX_VALUES) {
          values.add(v.asString());
        }
      }
    }
    ObjectNode out = completion(values, got != null && got.path("hasMore").asBoolean(false));
    if (got != null && got.path("total").canConvertToInt()) {
      ((ObjectNode) out.get("completion")).put("total", Math.max(values.size(), got.get("total").asInt()));
    }
    return out;
  }

  private static ObjectNode completion(ArrayNode values, boolean hasMore) {
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    ObjectNode c = out.putObject("completion");
    c.set("values", values);
    c.put("hasMore", hasMore);
    return out;
  }

  /** The prompts of one sharing connection, masked. */
  private record Listed(Sharing.Shared shared, List<JsonNode> prompts) {}

  /** The prompts of every sharing connection the policy lets the endpoint read. */
  private List<Listed> listed(McpRequest r) {
    List<Sharing.Shared> sharing = Sharing.of(r.snapshot(), registry, Sharing.What.PROMPTS).stream()
        .filter(s -> decide(s).outcome() == Decision.Outcome.ALLOW).toList();
    return Sharing.each(sharing, s -> {
      ConnectorInvoker.Outcome o = invoker.query(request(r, s, Ids.next("tc_"), null, decide(s)),
          (c, cfg, secrets, ctx) -> {
            JsonNode list = c.listPrompts(cfg, secrets, ctx);
            return list == null ? null : ToolResult.json(list, null);
          });
      if (!o.ok() || o.result().structuredContent() == null) {
        return null;
      }
      List<JsonNode> prompts = new ArrayList<>();
      for (JsonNode p : o.result().structuredContent().path("prompts")) {
        if (p.isObject() && p.path("name").isString()) {
          prompts.add(p);
        }
      }
      return new Listed(s, prompts);
    });
  }

  /** The sharing connection that lists the prompt {@code name}. */
  private Optional<Sharing.Shared> owner(McpRequest r, String name) {
    for (Listed l : listed(r)) {
      if (l.prompts().stream().anyMatch(p -> p.path("name").asString("").equals(name))) {
        return Optional.of(l.shared());
      }
    }
    return Optional.empty();
  }

  private void admit(McpRequest r, Sharing.Shared s, String tool) {
    PolicyEngine.Rates rates = engine.rates(s.view(), s.config());
    ToolGuard.Admission admission = guard.admit(r.caller().principalId(), tool, rates.perToken(), rates.perTool());
    if (!admission.allowed()) {
      throw new McpException(JsonRpc.INVALID_PARAMS, "Too many requests on this token. Retry after "
          + Math.max(1, admission.retryAfterMs() / 1000) + " s.");
    }
  }

  private Decision decide(Sharing.Shared s) {
    return engine.decide(s.view(), s.config(), Classification.read());
  }

  private ConnectorInvoker.Request request(McpRequest r, Sharing.Shared s, String callId, ObjectNode args,
      Decision decision) {
    return new ConnectorInvoker.Request(callId, s.config(), new ToolCall(callId, TOOL, s.name(),
        args == null ? JsonNodeFactory.instance.objectNode() : args, null), decision.limits(),
        policies.maskPolicy(s.config().env()), r.span().traceId(), r.span().traceparent(),
        () -> snapshots.requireAvailable(r.snapshot().id(), s.name()));
  }

  private void received(AuditWriter.Call call, AuditWriter.Received received) {
    try {
      audit.received(call, received);
    } catch (AuditUnavailableException e) {
      throw new McpException(JsonRpc.INTERNAL_ERROR, "The audit is unavailable, so nothing ran. Try again later.");
    }
  }

  // ── A prompt as blocks, so the masker sees its messages the way it sees a result ─────────────────────────────

  /**
   * {@code {"description", "messages": [{role, content}]}} as a result: one block per message, its role in the block's
   * {@code meta}, the description as structured content.
   */
  static ToolResult asResult(JsonNode prompt) {
    if (prompt == null) {
      return ToolResult.json(JsonNodeFactory.instance.objectNode(), null);
    }
    List<ToolResult.Content> content = new ArrayList<>();
    for (JsonNode m : prompt.path("messages")) {
      JsonNode c = m.path("content");
      ObjectNode meta = JsonNodeFactory.instance.objectNode().put("role", m.path("role").asString("user"));
      if (c.path("_meta").isObject()) {
        meta.set("_meta", c.get("_meta").deepCopy());
      }
      String type = c.path("type").asString("text");
      ToolResult.Content block = switch (type) {
        case "image" -> ToolResult.Content.image(c.path("data").asString(""), c.path("mimeType").asString(null));
        case "audio" -> ToolResult.Content.audio(c.path("data").asString(""), c.path("mimeType").asString(null));
        case "resource" -> ToolResult.Content.resource(c.path("resource").path("uri").asString(""),
            c.path("resource").path("mimeType").asString(null), c.path("resource").path("text").asString(null),
            c.path("resource").path("blob").asString(null));
        case "resource_link" -> ToolResult.Content.link(c.path("uri").asString(""), c.path("name").asString(null),
            c.path("mimeType").asString(null));
        default -> ToolResult.Content.text(c.path("text").asString(""));
      };
      content.add(block.withMeta(meta));
    }
    ObjectNode structured = JsonNodeFactory.instance.objectNode();
    if (prompt.path("description").isString()) {
      structured.put("description", prompt.get("description").asString());
    }
    return new ToolResult(List.copyOf(content), structured, false, null, false, null, List.of(), 0, 0, null);
  }

  /** The masked result as {@code prompts/get} answers it. */
  static ObjectNode asPrompt(ToolResult r) {
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    JsonNode s = r.structuredContent();
    if (s != null && s.path("description").isString()) {
      out.put("description", s.get("description").asString());
    }
    ArrayNode messages = out.putArray("messages");
    for (ToolResult.Content c : r.content()) {
      String role = c.meta() != null && "assistant".equals(c.meta().path("role").asString("")) ? "assistant"
          : "user";
      JsonNode inner = c.meta() == null ? null : c.meta().get("_meta");
      ToolResult.Content plain = new ToolResult.Content(c.type(), c.text(), c.uri(), c.mimeType(), c.name(),
          c.data(), inner instanceof ObjectNode ? inner : null);
      ObjectNode m = messages.addObject();
      m.put("role", role);
      m.set("content", ResultWriter.block(plain, false));
    }
    return out;
  }
}
