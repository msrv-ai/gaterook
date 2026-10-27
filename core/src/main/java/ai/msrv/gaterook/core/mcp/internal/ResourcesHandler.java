package ai.msrv.gaterook.core.mcp.internal;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ResourceDescriptor;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.core.audit.AuditUnavailableException;
import ai.msrv.gaterook.core.audit.AuditWriter;
import ai.msrv.gaterook.core.connectors.ConnectorRegistry;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshot;
import ai.msrv.gaterook.core.invocation.ConnectorInvoker;
import ai.msrv.gaterook.core.invocation.ToolGuard;
import ai.msrv.gaterook.core.platform.CanonicalJson;
import ai.msrv.gaterook.core.platform.Ids;
import ai.msrv.gaterook.core.policy.Decision;
import ai.msrv.gaterook.core.policy.PolicyEngine;
import ai.msrv.gaterook.core.policy.PolicyService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Resources (report 01 §3.6): what connectors offer as {@code mcpgw://connections/<name>/…} — the schema of a database,
 * the runbooks of a host. A connection's resources are listed only while one of its pairs runs at once (Read only):
 * reading a resource is a read without a person, so a connection whose tools all wait for approval offers none. A
 * descriptor whose URI holds {@code {…}} is a template.
 *
 * <p>Resources pass the same fail-closed gates as {@code tools/call} (§4 "Integrity", §5 step 7): an endpoint whose
 * rows didn't verify offers nothing (its pair modes can't be trusted), and every resource is decided by
 * {@link PolicyEngine#decide} as a READ of its pair, so a policy that didn't verify ({@code core.integrity}) or a prod
 * connection outside a prod endpoint lists nothing and reads nothing. {@code resources/read} then goes the way of a
 * READ tool call: audit {@code received} (a refusal is recorded too), the invoker (secrets, guard, masking, limits),
 * audit {@code completed}, then the answer.
 */
@Component
public class ResourcesHandler {

  /** The pseudo-tool of resource reads in the audit. */
  static final String TOOL = "resources/read";

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{[a-zA-Z0-9_]+\\}");

  private final ConnectorRegistry registry;
  private final PolicyEngine engine;
  private final PolicyService policies;
  private final ToolGuard guard;
  private final AuditWriter audit;
  private final ConnectorInvoker invoker;
  private final Calls calls;

  public ResourcesHandler(ConnectorRegistry registry, PolicyEngine engine, PolicyService policies, ToolGuard guard,
      AuditWriter audit, ConnectorInvoker invoker, Calls calls) {
    this.registry = registry;
    this.engine = engine;
    this.policies = policies;
    this.guard = guard;
    this.audit = audit;
    this.invoker = invoker;
    this.calls = calls;
  }

  /** One offered resource and the pair it reads through. */
  private record Offer(ResourceDescriptor descriptor, EndpointSnapshot.Pair pair, Pattern template) {}

  public ObjectNode list(McpRequest r, boolean templates) {
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    ArrayNode items = out.putArray(templates ? "resourceTemplates" : "resources");
    for (Offer o : offers(r.snapshot())) {
      if ((o.template() != null) != templates || decide(o).outcome() != Decision.Outcome.ALLOW) {
        continue;
      }
      ObjectNode n = items.addObject();
      n.put(templates ? "uriTemplate" : "uri", o.descriptor().uri());
      n.put("name", o.descriptor().name() == null ? o.descriptor().uri() : o.descriptor().name());
      if (o.descriptor().mimeType() != null) {
        n.put("mimeType", o.descriptor().mimeType());
      }
      if (o.descriptor().description() != null) {
        n.put("description", o.descriptor().description());
      }
    }
    return out;
  }

  public ObjectNode read(McpRequest r) {
    String uri = r.env().params().path("uri").asString(null);
    if (uri == null || uri.isEmpty()) {
      throw McpException.invalidParams("params.uri is required");
    }
    Optional<Offer> offer = offers(r.snapshot()).stream().filter(o -> o.template() == null
        ? o.descriptor().uri().equals(uri) : o.template().matcher(uri).matches()).findFirst();
    if (offer.isEmpty()) {
      throw new McpException(r.env().era() == Era.MODERN ? JsonRpc.INVALID_PARAMS : JsonRpc.RESOURCE_NOT_FOUND,
          "Resource not found: " + uri, JsonNodeFactory.instance.objectNode().put("uri", uri));
    }
    EndpointSnapshot.Pair pair = offer.get().pair();
    PolicyEngine.Rates rates = engine.rates(pair.view(), pair.config());
    ToolGuard.Admission admission = guard.admit(r.caller().principalId(), TOOL, rates.perToken(), rates.perTool());
    if (!admission.allowed()) {
      throw new McpException(JsonRpc.INVALID_PARAMS, "Too many reads on this token. Retry after "
          + Math.max(1, admission.retryAfterMs() / 1000) + " s.");
    }
    Decision decision = decide(offer.get());
    String callId = Ids.next("tc_");
    ObjectNode args = JsonNodeFactory.instance.objectNode().put("uri", uri);
    ToolDefinition read = ToolDefinition.builder("resources_read").build();
    AuditWriter.Call call = calls.call(r, callId, TOOL, "resource:" + uri, pair.connectionName(),
        pair.config().env(), read, args, null);
    if (decision.outcome() != Decision.Outcome.ALLOW) {
      // Only a policy that didn't verify or the prod line refuses a READ of a Read only pair: say which rule.
      received(call, AuditWriter.Received.of(AuditWriter.Verdict.DENIED, Classification.Kind.READ,
          decision.ruleId()));
      r.span().error("denied");
      throw new McpException(JsonRpc.INVALID_PARAMS, "Denied by policy " + decision.ruleId() + ": "
          + decision.reason(), JsonNodeFactory.instance.objectNode().put("uri", uri)
          .put("rule", decision.ruleId()));
    }
    received(call, AuditWriter.Received.of(AuditWriter.Verdict.ALLOWED, Classification.Kind.READ, null));
    long started = System.nanoTime();
    ConnectorInvoker.Outcome o = invoker.readResource(new ConnectorInvoker.Request(callId, pair.config(),
        new ToolCall(callId, TOOL, pair.connectionName(), args, null), decision.limits(),
        policies.maskPolicy(pair.config().env()), r.span().traceId(), r.span().traceparent()), uri);
    long durationMs = (System.nanoTime() - started) / 1_000_000;
    try {
      audit.completed(call, new AuditWriter.Completion(o.ok() ? AuditWriter.Verdict.ALLOWED
          : AuditWriter.Verdict.ERROR, null, null, null, null, durationMs, Math.max(0, durationMs - o.resourceMs()),
          o.resourceMs(), null, o.resultBytes(), o.resultSha256(), o.preview(), o.maskedCount(), o.truncated(),
          o.errorKind() == null ? null : o.errorKind().name(), o.errorMessage(), null, null, null));
    } catch (AuditUnavailableException e) {
      throw new McpException(JsonRpc.INTERNAL_ERROR, "The audit couldn't record the read, so it is not shown.");
    }
    if (!o.ok()) {
      throw new McpException(JsonRpc.INTERNAL_ERROR, o.errorMessage());
    }
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    ArrayNode contents = out.putArray("contents");
    String mime = offer.get().descriptor().mimeType() == null ? "text/plain" : offer.get().descriptor().mimeType();
    for (ToolResult.Content c : o.result().content()) {
      if (c.text() != null) {
        contents.addObject().put("uri", uri).put("mimeType", mime).put("text", c.text());
      }
    }
    if (contents.isEmpty() && o.result().structuredContent() != null) {
      contents.addObject().put("uri", uri).put("mimeType", "application/json")
          .put("text", CanonicalJson.text(o.result().structuredContent()));
    }
    return out;
  }

  /** A resource read is a READ of its Read only pair: the same policy gates as a tool call of it. */
  private Decision decide(Offer o) {
    return engine.decide(o.pair().view(), o.pair().config(), Classification.read());
  }

  private void received(AuditWriter.Call call, AuditWriter.Received received) {
    try {
      audit.received(call, received);
    } catch (AuditUnavailableException e) {
      throw new McpException(JsonRpc.INTERNAL_ERROR, "The audit is unavailable, so nothing ran. Try again later.");
    }
  }

  /**
   * The resources the endpoint's connections declare, each with the Read only pair it reads through. An endpoint
   * whose rows didn't verify offers none: its wiring and pair modes are what someone wrote around the gateway.
   */
  private List<Offer> offers(EndpointSnapshot s) {
    List<Offer> out = new ArrayList<>();
    if (s.blocked()) {
      return out;
    }
    for (EndpointSnapshot.Wired w : s.connections()) {
      if (!w.usable()) {
        continue;
      }
      Optional<EndpointSnapshot.Pair> readOnly = s.pairs().stream()
          .filter(p -> p.config().id().equals(w.config().id()) && p.mode() == Mode.READ_ONLY).findFirst();
      if (readOnly.isEmpty()) {
        continue;
      }
      Optional<Connector> connector = registry.connectorFor(w.config());
      if (connector.isEmpty()) {
        continue;
      }
      List<ResourceDescriptor> descriptors;
      try {
        descriptors = connector.get().resources(w.config());
      } catch (RuntimeException e) {
        continue;
      }
      for (ResourceDescriptor d : descriptors) {
        if (d.uri() == null || !d.uri().startsWith("mcpgw://connections/" + w.config().name() + "/")) {
          // A connector names only its own connection's resources.
          continue;
        }
        Pattern template = d.uri().contains("{") ? Pattern.compile(templateRegex(d.uri())) : null;
        out.add(new Offer(d, readOnly.get(), template));
      }
    }
    return out;
  }

  private static String templateRegex(String uriTemplate) {
    StringBuilder regex = new StringBuilder();
    var m = PLACEHOLDER.matcher(uriTemplate);
    int last = 0;
    while (m.find()) {
      regex.append(Pattern.quote(uriTemplate.substring(last, m.start()))).append("[^/{}?#]+");
      last = m.end();
    }
    regex.append(Pattern.quote(uriTemplate.substring(last)));
    return regex.toString();
  }
}
