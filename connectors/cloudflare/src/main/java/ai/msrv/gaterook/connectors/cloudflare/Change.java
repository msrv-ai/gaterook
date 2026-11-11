package ai.msrv.gaterook.connectors.cloudflare;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.PreparedAction;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * One of the four changes ({@code cloudflare_dns_change}, {@code cloudflare_cache_purge},
 * {@code cloudflare_workers_deploy}, {@code cloudflare_pages_deployment}). Its life:
 *
 * <ol>
 *   <li>{@code classify}: the arguments, checked without I/O ({@code parse} of the subclass), make it a MUTATION;</li>
 *   <li>{@code prepare}: the exact HTTP request is built — zones resolved, names made absolute — and shown with what
 *       it changes, read from Cloudflare (the record before and after, the deployment that serves traffic now); what
 *       must still hold when it runs goes to the prepared state;</li>
 *   <li>{@code execute}, only when approved: the request is built again from the call, compared with the approved
 *       payload, the prepared state checked against Cloudflare again, then sent exactly as approved. Nothing is
 *       retried; a lost answer is an unknown outcome.</li>
 * </ol>
 */
abstract class Change {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** The exact request: method, URL and body ({@code null} for none). */
  record Request(String method, URI uri, JsonNode body) {}

  /** What the approver sees besides the request, and what must still hold when it runs. */
  record Context(String target, String summary, List<ActionPreview.Field> fields, List<ActionPreview.Field> impact,
      List<String> warnings, Map<String, String> facets, JsonNode state) {}

  abstract String tool();

  /** MUTATION with its reason and facts, without I/O. */
  abstract Classification classification(String connection);

  /** The exact request; may resolve zones (I/O). A call that can't be made is a BAD_INPUT or DENIED exception. */
  abstract Request request(Call c) throws ConnectorException;

  /** What the request changes, read from Cloudflare (I/O). */
  abstract Context context(Call c, Request r) throws ConnectorException;

  /** Refuses when what the approval saw no longer holds (the record changed, another deployment happened). */
  abstract void stillCurrent(Call c, Request r, JsonNode state) throws ConnectorException;

  /** Which family of permissions the request needs, for Cloudflare's refusals. */
  abstract String what();

  /** The answer after Cloudflare applied it. */
  abstract ToolResult answer(Call c, Request r, CloudflareApi.Answer a) throws ConnectorException;

  final PreparedAction prepare(Call c) throws ConnectorException {
    Request r;
    Context ctx;
    try {
      r = request(c);
      ctx = context(c, r);
    } catch (ConnectorException e) {
      if (e.kind() == ConnectorException.Kind.BAD_INPUT || e.kind() == ConnectorException.Kind.DENIED) {
        return PreparedAction.reject(e.getMessage());
      }
      throw e;
    }
    return PreparedAction.approval(preview(c, r, ctx), ctx.state());
  }

  final ToolResult execute(Call c, JsonNode approved, JsonNode state) throws ConnectorException {
    Request r = request(c);
    if (approved == null || !canonical(payload(tool(), r)).equals(canonical(approved))) {
      throw ConnectorException.denied("The call doesn't match the request that was approved. Not executed.");
    }
    stillCurrent(c, r, state);
    CloudflareApi.Answer a = c.api().send(c, r.method(), r.uri(), r.body(), what(), true);
    return answer(c, r, a);
  }

  /** The canonical approved request: what {@link #execute} compares and sends. */
  static ObjectNode payload(String tool, Request r) {
    ObjectNode p = Answers.F.objectNode();
    p.put("tool", tool);
    p.put("method", r.method());
    p.put("url", r.uri().toString());
    if (r.body() != null) {
      p.set("body", r.body().deepCopy());
    }
    return p;
  }

  /** The same JSON as stored and read back: number types as a parser gives them. */
  private static JsonNode canonical(JsonNode n) {
    return JSON.readTree(JSON.writeValueAsString(n));
  }

  /** The approver's view: the HTTP request exactly as it will be sent, and what it changes. */
  final ActionPreview preview(Call c, Request r, Context ctx) {
    StringBuilder text = new StringBuilder();
    text.append(r.method()).append(' ').append(r.uri()).append('\n');
    text.append("Authorization: Bearer ‹masked›\n");
    if (r.body() != null) {
      text.append("Content-Type: application/json\n\n");
      text.append(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(r.body()));
    }
    String summary = ctx.summary();
    if (summary.length() > 120) {
      summary = summary.substring(0, 119) + "…";
    }
    List<ActionPreview.Field> fields = new ArrayList<>();
    fields.add(new ActionPreview.Field("Account", c.settings().accountId()));
    fields.addAll(ctx.fields());
    Map<String, String> facets = new LinkedHashMap<>();
    facets.put("cloudflare.account", c.settings().accountId());
    facets.putAll(ctx.facets());
    return ActionPreview.of(ActionPreview.Kind.HTTP, "http", text.toString(), c.cfg().name() + " · " + ctx.target(),
        summary)
        .withFields(fields)
        .withImpact(ctx.impact())
        .withWarnings(ctx.warnings())
        .withFacets(facets)
        .withPayload(payload(tool(), r));
  }

  /** The answer of a change: what was sent and what Cloudflare answered, masked. */
  static ToolResult changed(Call c, String title, String view, Request r, JsonNode result, ObjectNode zone,
      List<String> notes) {
    ObjectNode s = Answers.envelope(c, view);
    if (zone != null) {
      s.set("zone", zone);
    }
    s.put("request", r.method() + " " + c.api().relative(r.uri()));
    s.set("item", result == null || result.isMissingNode() || result.isNull() ? Answers.F.objectNode()
        : result.deepCopy());
    return Answers.answer(c, title, s, notes);
  }
}
