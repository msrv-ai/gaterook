package com.gaterook.connectors.azure;

import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.ToolResult;
import com.gaterook.connectors.support.OutputSanitizer;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Runs one {@code azure_api_read} or {@code azure_api_change}: the request exactly as {@link CallRequest} built it (a
 * change: as the person approved it), the answer as data — a 4xx is Azure's answer, not a failure of the tool.
 *
 * <p>A read's answer is checked against the Cloud policy where it says what it is: the items of a list
 * ({@code value}) and a single resource are hidden when their service, location or ID isn't allowed. Every answer is
 * masked ({@link Redactor}). Paging and long-running operations come back as arguments for the next call, never as
 * URLs: {@code next.query} (the {@code $skipToken} of {@code nextLink}) and {@code operation} (the path and API
 * version of {@code Azure-AsyncOperation} or {@code Location}, to GET).
 */
final class AzureCall {

  private final Call call;

  AzureCall(Call call) {
    this.call = call;
  }

  ToolResult run(CallRequest r) throws ConnectorException {
    call.quota(r.path().subscription(), "Resource Manager", Call.ARM_PER_MINUTE);
    URI uri = call.arm().url(r.encodedPath(), r.parameters());
    Arm.Answer a = call.arm().send(r.method(), uri, r.body(), "Azure Resource Manager");
    JsonNode body = a.body();
    int hidden = 0;
    List<String> notes = new ArrayList<>();
    if (a.ok() && r.read()) {
      if (body.path("value").isArray()) {
        ArrayNode kept = JsonNodeFactory.instance.arrayNode();
        for (JsonNode item : body.path("value")) {
          if (item instanceof ObjectNode o && !allowed(o)) {
            hidden++;
          } else {
            kept.add(item);
          }
        }
        if (hidden > 0) {
          ((ObjectNode) body).set("value", kept);
          notes.add(hidden + (hidden == 1 ? " item is" : " items are") + " hidden by the Cloud policy of "
              + call.cfg().env().id() + ".");
        }
      } else if (body instanceof ObjectNode o && o.has("id") && !allowed(o)) {
        hidden = 1;
        body = JsonNodeFactory.instance.objectNode().put("hiddenByPolicy", true);
        notes.add("The resource is hidden by the Cloud policy of " + call.cfg().env().id() + ".");
      }
    }
    int masked = Redactor.redact(body);
    ObjectNode s = JsonNodeFactory.instance.objectNode();
    s.put("provider", "azure");
    s.put("method", r.method());
    s.put("path", r.rawPath());
    s.put("apiVersion", r.apiVersion());
    s.put("status", a.status());
    s.put("ok", a.ok());
    ObjectNode next = next(body.path("nextLink").asString(null), r);
    if (next != null) {
      s.set("next", next);
    }
    ObjectNode operation = operation(a);
    if (operation != null) {
      s.set("operation", operation);
    }
    String requestId = a.header("x-ms-request-id");
    if (requestId != null) {
      s.put("requestId", OutputSanitizer.sanitize(requestId));
    }
    String retryAfter = a.header("Retry-After");
    if (retryAfter != null) {
      s.put("retryAfterSeconds", OutputSanitizer.sanitize(retryAfter));
    }
    s.put("hiddenByPolicy", hidden);
    s.put("masked", masked);
    String reason = null;
    JsonNode shown = body;
    long budget = call.budget() - 2_048;
    if (Call.utf8(shown.toString()) > budget) {
      int keep = 64;
      while (Call.utf8(shown.toString()) > budget && keep >= 1) {
        shown = shrink(body, keep);
        keep /= 2;
      }
      reason = "size_limit";
      notes.add("The answer was cut to the size limit: long lists keep their first items. Narrow it with "
          + "$filter, $top or $select.");
    }
    if (a.truncated()) {
      reason = "size_limit";
      notes.add("Azure's answer was larger than 8 MB; only its start was read.");
    }
    s.set("body", shown);
    s.put("truncated", reason != null);
    ArrayNode n = s.putArray("notes");
    notes.forEach(n::add);
    StringBuilder t = new StringBuilder();
    t.append(r.method()).append(' ').append(r.rawPath()).append(" (api-version ").append(r.apiVersion())
        .append(", ").append(call.cfg().name()).append("): ").append(a.status());
    if (!a.ok()) {
      JsonNode error = body.path("error");
      t.append(' ').append(error.path("code").asString("")).append(' ')
          .append(Arm.excerpt(error.path("message").asString("")));
    }
    String pretty = ResourceGraph.pretty(shown);
    t.append('\n').append(Call.utf8(pretty) + Call.utf8(s.toString()) <= call.budget() ? pretty
        : "The answer is in structuredContent body.");
    if (next != null) {
      t.append("\nMore: call azure_api_read again with the same arguments and query ").append(next.path("query"));
    }
    if (operation != null) {
      t.append("\nThe operation runs on: follow it with azure_api_read GET ").append(operation.path("path").asString())
          .append(" api-version ").append(operation.path("apiVersion").asString());
    }
    for (String note : notes) {
      t.append('\n').append(note);
    }
    t.append('\n').append(ResourceGraph.DATA_NOTE);
    ToolResult result = ToolResult.json(s, OutputSanitizer.sanitize(t.toString()));
    return reason == null ? result : result.truncated(reason);
  }

  /** Whether the Cloud policy lets agents see one resource of an answer. */
  private boolean allowed(ObjectNode o) {
    AzurePolicy p = call.policy();
    String type = o.path("type").asString(null);
    if (type != null) {
      String service = Services.ofType(type);
      if (service != null && !p.serviceAllowed(service)) {
        return false;
      }
    }
    String id = o.path("id").asString(null);
    if (id != null) {
      ResourceIds.Parsed parsed = ResourceIds.parse(id);
      if (parsed != null) {
        if (!p.resourceAllowed(parsed)) {
          return false;
        }
        for (String ns : parsed.namespaces()) {
          if (!p.serviceAllowed(Services.ofNamespace(ns))) {
            return false;
          }
        }
      } else if (!p.resources().isEmpty()) {
        return false;
      }
    }
    String location = o.path("location").asString(null);
    return location == null || p.regionAllowed(location);
  }

  /** The next page as query parameters of the same path: the {@code nextLink}'s, without {@code api-version}. */
  private ObjectNode next(String nextLink, CallRequest r) {
    if (nextLink == null || nextLink.isBlank()) {
      return null;
    }
    URI uri;
    try {
      uri = URI.create(nextLink);
    } catch (IllegalArgumentException e) {
      return null;
    }
    ResourceIds.Parsed parsed = ResourceIds.parse(uri.getPath());
    if (parsed == null || !parsed.lower().equals(r.path().lower()) || uri.getRawQuery() == null) {
      return null;
    }
    Map<String, String> params = new LinkedHashMap<>();
    for (String pair : uri.getRawQuery().split("&")) {
      int eq = pair.indexOf('=');
      if (eq <= 0) {
        continue;
      }
      String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
      if (!key.equalsIgnoreCase("api-version")) {
        params.put(key, URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
      }
    }
    if (params.isEmpty()) {
      return null;
    }
    ObjectNode next = JsonNodeFactory.instance.objectNode();
    ObjectNode q = next.putObject("query");
    params.forEach(q::put);
    return next;
  }

  /** {@code Azure-AsyncOperation} or {@code Location} of an accepted change, as a path and API version to GET. */
  private ObjectNode operation(Arm.Answer a) {
    for (String header : List.of("Azure-AsyncOperation", "Location")) {
      String value = a.header(header);
      if (value == null) {
        continue;
      }
      try {
        URI uri = URI.create(value);
        ResourceIds.Parsed parsed = ResourceIds.parse(uri.getPath());
        if (parsed == null || !call.settings().hasSubscription(parsed.subscription())) {
          continue;
        }
        String apiVersion = null;
        for (String pair : uri.getRawQuery() == null ? new String[0] : uri.getRawQuery().split("&")) {
          if (pair.toLowerCase(java.util.Locale.ROOT).startsWith("api-version=")) {
            apiVersion = URLDecoder.decode(pair.substring("api-version=".length()), StandardCharsets.UTF_8);
          }
        }
        if (apiVersion == null || !CallRequest.API_VERSION.matcher(apiVersion).matches()) {
          continue;
        }
        return JsonNodeFactory.instance.objectNode().put("path", uri.getPath()).put("apiVersion", apiVersion);
      } catch (IllegalArgumentException e) {
        // Not a URL: nothing to offer.
      }
    }
    return null;
  }

  /** {@code node} with every list cut to its first {@code keep} items and every text to {@code keep * 64} chars. */
  static JsonNode shrink(JsonNode node, int keep) {
    if (node.isObject()) {
      ObjectNode out = JsonNodeFactory.instance.objectNode();
      node.properties().forEach(e -> out.set(e.getKey(), shrink(e.getValue(), keep)));
      return out;
    }
    if (node.isArray()) {
      ArrayNode out = JsonNodeFactory.instance.arrayNode();
      for (int i = 0; i < Math.min(keep, node.size()); i++) {
        out.add(shrink(node.get(i), keep));
      }
      if (node.size() > keep) {
        out.add("… " + (node.size() - keep) + " more");
      }
      return out;
    }
    if (node.isString() && node.asString().length() > keep * 64) {
      return JsonNodeFactory.instance.stringNode(node.asString().substring(0, keep * 64) + "…");
    }
    return node;
  }
}
