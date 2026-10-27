package ai.msrv.gaterook.connectors.http;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connectors.support.egress.UrlRules;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import tools.jackson.databind.JsonNode;

/**
 * The request of a tool compiled from an OpenAPI operation (report 10 §6.2, §7.4): the binding the template stored
 * ({@code server}, {@code path}, parameters with their location and style, the body's media type) filled with the
 * agent's arguments. A path parameter is one segment — no {@code /}, {@code \}, {@code ?}, {@code #}, control
 * characters, {@code .} or {@code ..} — percent-encoded; query parameters in {@code form} style (an array repeats
 * the key, {@code explode: false} joins with commas) or {@code deepObject} ({@code created[gte]=1}); the body as
 * two-space JSON or as a form.
 */
final class OpenApiCall {

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}/]+)}");

  private OpenApiCall() {}

  static PlannedRequest plan(ToolCall call, ConnectionConfig cfg, HttpSettings s) throws Refusal {
    return plan(call.template().binding(), call.arguments(), s, HttpConnector.operation(call));
  }

  static PlannedRequest plan(JsonNode binding, JsonNode args, HttpSettings s, PlannedRequest.Operation operation)
      throws Refusal {
    String method = binding.path("method").asString("");
    if (!RequestPlanner.METHODS.contains(method)) {
      throw Refusal.of("invalid-request", "The template's operation has no method this connector sends.");
    }
    HttpUrl server = HttpUrl.parse(binding.path("server").asString(""));
    if (server == null) {
      throw Refusal.of("invalid-request", "The template's operation has no valid server URL.");
    }
    HttpUrl base = s.baseOf(server).orElseThrow(() -> Refusal.of("url-not-allowed", "The template's server "
        + server + " isn't under a base URL of the connection. A person edits the template or the connection."));

    Map<String, JsonNode> bound = new java.util.LinkedHashMap<>();
    List<JsonNode> query = new ArrayList<>();
    List<JsonNode> headerParams = new ArrayList<>();
    for (JsonNode p : binding.path("params")) {
      switch (p.path("in").asString("")) {
        case "path" -> bound.put(p.path("name").asString(""), p);
        case "query" -> query.add(p);
        case "header" -> headerParams.add(p);
        default -> throw Refusal.of("invalid-request", "The template has a parameter in an unknown place.");
      }
    }

    String template = binding.path("path").asString("/");
    StringBuilder path = new StringBuilder();
    Matcher m = PLACEHOLDER.matcher(template);
    int last = 0;
    while (m.find()) {
      path.append(template, last, m.start());
      JsonNode p = bound.get(m.group(1));
      if (p == null) {
        throw Refusal.of("invalid-request", "The template's path parameter {" + m.group(1) + "} has no binding.");
      }
      JsonNode value = args.get(p.path("arg").asString(""));
      if (value == null || value.isNull()) {
        throw Refusal.of("invalid-request", p.path("arg").asString("") + " is required.");
      }
      path.append(encodeSegment(segment(p.path("arg").asString(""), value, p.path("explode").asBoolean(false))));
      last = m.end();
    }
    path.append(template.substring(last));

    HttpUrl url;
    try {
      url = UrlRules.resolve(server, path.toString());
    } catch (UrlRules.UrlRejectedException e) {
      throw Refusal.of("url-not-allowed", e.getMessage() + ".");
    }
    HttpUrl.Builder ub = url.newBuilder();
    for (JsonNode p : query) {
      String arg = p.path("arg").asString("");
      String name = p.path("name").asString("");
      JsonNode value = args.get(arg);
      if (value == null || value.isNull()) {
        if (p.path("required").asBoolean(false)) {
          throw Refusal.of("invalid-request", arg + " is required.");
        }
        continue;
      }
      boolean explode = p.path("explode").asBoolean(true);
      if ("deepObject".equals(p.path("style").asString(""))) {
        if (!value.isObject()) {
          throw Refusal.of("invalid-request", arg + " is an object.");
        }
        deepObject(ub, name, value, 0);
      } else if (value.isArray()) {
        if (explode) {
          for (JsonNode item : value) {
            ub.addQueryParameter(name, RequestPlanner.scalar(arg, item));
          }
        } else {
          List<String> items = new ArrayList<>();
          for (JsonNode item : value) {
            items.add(RequestPlanner.scalar(arg, item));
          }
          ub.addQueryParameter(name, String.join(",", items));
        }
      } else if (value.isObject()) {
        List<String> pairs = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : value.properties()) {
          if (explode) {
            ub.addQueryParameter(e.getKey(), RequestPlanner.scalar(arg, e.getValue()));
          } else {
            pairs.add(e.getKey());
            pairs.add(RequestPlanner.scalar(arg, e.getValue()));
          }
        }
        if (!explode) {
          ub.addQueryParameter(name, String.join(",", pairs));
        }
      } else {
        ub.addQueryParameter(name, RequestPlanner.scalar(arg, value));
      }
    }
    url = RequestPlanner.withQuery(ub.build(), null, s);

    List<PlannedRequest.Header> agentHeaders = new ArrayList<>();
    for (JsonNode p : headerParams) {
      String arg = p.path("arg").asString("");
      JsonNode value = args.get(arg);
      if (value == null || value.isNull()) {
        if (p.path("required").asBoolean(false)) {
          throw Refusal.of("invalid-request", arg + " is required.");
        }
        continue;
      }
      agentHeaders.add(new PlannedRequest.Header(p.path("name").asString(""), segment(arg, value,
          p.path("explode").asBoolean(false))));
    }
    RequestPlanner.checkAgentHeaders(agentHeaders, s);
    List<PlannedRequest.Header> headers = new ArrayList<>();
    java.util.Set<String> overridden = new java.util.HashSet<>();
    agentHeaders.forEach(h -> overridden.add(h.name().toLowerCase(java.util.Locale.ROOT)));
    s.defaultHeaders().forEach((n, v) -> {
      if (!overridden.contains(n.toLowerCase(java.util.Locale.ROOT))) {
        headers.add(new PlannedRequest.Header(n, v));
      }
    });
    headers.addAll(agentHeaders);

    byte[] body = null;
    String contentType = null;
    JsonNode b = binding.get("body");
    if (b != null && b.isObject()) {
      JsonNode value = args.get(b.path("arg").asString("body"));
      if (value == null) {
        if (b.path("required").asBoolean(false)) {
          throw Refusal.of("invalid-request", b.path("arg").asString("body") + " is required.");
        }
      } else {
        contentType = b.path("mediaType").asString("application/json");
        if (contentType.equals("application/x-www-form-urlencoded")) {
          if (!value.isObject()) {
            throw Refusal.of("invalid-request", b.path("arg").asString("body") + " is an object of form fields.");
          }
          FormBody.Builder fb = new FormBody.Builder(StandardCharsets.UTF_8);
          formFields(fb, null, value, 0);
          body = RequestPlanner.formBytes(fb.build());
        } else {
          body = JsonText.pretty(value);
        }
      }
    }
    if (body != null && PlannedRequest.READ_METHODS.contains(method)) {
      throw Refusal.of("invalid-request", method + " requests can't carry a body.");
    }
    return new PlannedRequest(method, url, base, headers, body, contentType, operation);
  }

  /** {@code simple} style: a value, an array joined with commas, an object as {@code k,v,…} or {@code k=v,…}. */
  private static String segment(String arg, JsonNode value, boolean explode) throws Refusal {
    if (value.isArray()) {
      List<String> items = new ArrayList<>();
      for (JsonNode item : value) {
        items.add(RequestPlanner.scalar(arg, item));
      }
      return String.join(",", items);
    }
    if (value.isObject()) {
      List<String> items = new ArrayList<>();
      for (Map.Entry<String, JsonNode> e : value.properties()) {
        items.add(explode ? e.getKey() + "=" + RequestPlanner.scalar(arg, e.getValue())
            : e.getKey() + "," + RequestPlanner.scalar(arg, e.getValue()));
      }
      return String.join(",", items);
    }
    return RequestPlanner.scalar(arg, value);
  }

  /** One path segment from a value: refused if it could leave its segment, percent-encoded otherwise. */
  static String encodeSegment(String value) throws Refusal {
    if (value.isEmpty() || value.equals(".") || value.equals("..")) {
      throw Refusal.of("url-not-allowed", "A path parameter can't be empty, . or ..");
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '/' || c == '\\' || c == '?' || c == '#' || c < 0x20 || c == 0x7F) {
        throw Refusal.of("url-not-allowed", "A path parameter can't contain /, \\, ?, # or control characters.");
      }
    }
    StringBuilder out = new StringBuilder();
    for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
      int c = b & 0xFF;
      if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '.'
          || c == '_' || c == '~' || c == ',' || c == ':' || c == '@') {
        out.append((char) c);
      } else {
        out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
            .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
      }
    }
    return out.toString();
  }

  private static void deepObject(HttpUrl.Builder ub, String prefix, JsonNode value, int depth) throws Refusal {
    if (depth > 8) {
      throw Refusal.of("invalid-request", prefix + " nests too deeply.");
    }
    for (Map.Entry<String, JsonNode> e : value.properties()) {
      String key = prefix + "[" + e.getKey() + "]";
      if (e.getValue().isObject()) {
        deepObject(ub, key, e.getValue(), depth + 1);
      } else if (e.getValue().isArray()) {
        for (JsonNode item : e.getValue()) {
          ub.addQueryParameter(key + "[]", RequestPlanner.scalar(key, item));
        }
      } else if (!e.getValue().isNull()) {
        ub.addQueryParameter(key, RequestPlanner.scalar(key, e.getValue()));
      }
    }
  }

  /** A form body: flat fields, nested objects as {@code a[b]=}, arrays repeated as {@code a[]=} (as Stripe reads them). */
  private static void formFields(FormBody.Builder fb, String prefix, JsonNode value, int depth) throws Refusal {
    if (depth > 8) {
      throw Refusal.of("invalid-request", "The form nests too deeply.");
    }
    for (Map.Entry<String, JsonNode> e : value.properties()) {
      String key = prefix == null ? e.getKey() : prefix + "[" + e.getKey() + "]";
      JsonNode v = e.getValue();
      if (v.isObject()) {
        formFields(fb, key, v, depth + 1);
      } else if (v.isArray()) {
        int i = 0;
        for (JsonNode item : v) {
          if (item.isObject()) {
            formFields(fb, key + "[" + i + "]", item, depth + 1);
          } else {
            fb.add(key + "[]", RequestPlanner.scalar(key, item));
          }
          i++;
        }
      } else if (!v.isNull()) {
        fb.add(key, RequestPlanner.scalar(key, v));
      }
    }
  }
}
