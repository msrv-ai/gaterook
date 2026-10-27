package ai.msrv.gaterook.connectors.http;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connectors.support.Glob;
import ai.msrv.gaterook.connectors.support.egress.UrlRules;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okio.Buffer;
import tools.jackson.databind.JsonNode;

/**
 * Turns a call into the exact request it sends (report 10 §3, §6.2–§6.3), and checks it: what the connection allows
 * ({@link #checkConnection}, also before an approved request runs) and what the environment's policy allows
 * ({@link #checkPolicy}, in {@code classify}). Nothing here does I/O.
 */
final class RequestPlanner {

  static final Set<String> METHODS = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE");
  static final int MAX_QUERY_PARAMETERS = 50;
  static final int MAX_FORM_FIELDS = 100;

  private RequestPlanner() {}

  /** The request of a call: {@code http_get}, {@code http_request} or a tool compiled from an OpenAPI template. */
  static PlannedRequest plan(ToolCall call, ConnectionConfig cfg, HttpSettings s) throws Refusal {
    if (call.template() != null) {
      return OpenApiCall.plan(call, cfg, s);
    }
    return switch (call.tool()) {
      case HttpTools.GET -> forTool(call, s, false);
      case HttpTools.REQUEST -> forTool(call, s, true);
      default -> throw Refusal.of("invalid-request", "HTTP API has no tool " + call.tool() + ".");
    };
  }

  private static PlannedRequest forTool(ToolCall call, HttpSettings s, boolean anyMethod) throws Refusal {
    JsonNode args = call.arguments();
    String method = args.path("method").asString(anyMethod ? "" : "GET").toUpperCase(Locale.ROOT);
    if (anyMethod ? !METHODS.contains(method) : !PlannedRequest.READ_METHODS.contains(method)) {
      throw Refusal.of("invalid-request", call.tool() + " sends " + (anyMethod ? "GET, HEAD, POST, PUT, PATCH or "
          + "DELETE" : "GET or HEAD") + ", not " + (method.isEmpty() ? "nothing" : method) + ".");
    }
    String path = call.string("path");
    if (path == null || path.isEmpty()) {
      throw Refusal.of("invalid-request", "Give the path under the base URL, e.g. /v1/invoices.");
    }
    HttpUrl url;
    HttpUrl base;
    if (path.regionMatches(true, 0, "http://", 0, 7) || path.regionMatches(true, 0, "https://", 0, 8)) {
      try {
        url = UrlRules.parse(path);
      } catch (UrlRules.UrlRejectedException e) {
        throw Refusal.of("url-not-allowed", e.getMessage() + ".");
      }
      HttpUrl target = url;
      base = s.baseOf(url).orElseThrow(() -> Refusal.of("url-not-allowed", target + " isn't under a base URL of "
          + "the connection: " + bases(s) + "."));
    } else {
      base = s.baseUrls().getFirst();
      try {
        url = UrlRules.resolve(base, path);
      } catch (UrlRules.UrlRejectedException e) {
        throw Refusal.of("url-not-allowed", e.getMessage() + ".");
      }
    }
    url = withQuery(url, args.path("query"), s);
    List<PlannedRequest.Header> headers = headers(args.path("headers"), s);

    byte[] body = null;
    String contentType = null;
    JsonNode json = args.get("json");
    JsonNode text = args.get("body");
    JsonNode form = args.get("form");
    int bodies = (json != null ? 1 : 0) + (text != null && !text.isNull() ? 1 : 0)
        + (form != null && !form.isNull() ? 1 : 0);
    if (bodies > 1) {
      throw Refusal.of("invalid-request", "Send one body: json, body or form.");
    }
    if (bodies == 1 && PlannedRequest.READ_METHODS.contains(method)) {
      throw Refusal.of("invalid-request", method + " requests can't carry a body.");
    }
    if (json != null) {
      body = JsonText.pretty(json);
      contentType = "application/json";
    } else if (text != null && !text.isNull()) {
      body = text.asString("").getBytes(StandardCharsets.UTF_8);
      contentType = contentType(args.path("contentType").asString("text/plain"));
    } else if (form != null && !form.isNull()) {
      body = form(form);
      contentType = "application/x-www-form-urlencoded";
    } else if (args.hasNonNull("contentType")) {
      throw Refusal.of("invalid-request", "contentType goes with a text body only.");
    }
    return new PlannedRequest(method, url, base, headers, body, contentType, null);
  }

  /** Query parameters of the call; an array repeats the key. */
  static HttpUrl withQuery(HttpUrl url, JsonNode query, HttpSettings s) throws Refusal {
    if (query == null || query.isMissingNode() || query.isNull()) {
      return checkQueryNames(url, s);
    }
    if (!query.isObject()) {
      throw Refusal.of("invalid-request", "query is an object of names and values.");
    }
    if (query.size() > MAX_QUERY_PARAMETERS) {
      throw Refusal.of("invalid-request", "At most " + MAX_QUERY_PARAMETERS + " query parameters.");
    }
    HttpUrl.Builder b = url.newBuilder();
    for (Map.Entry<String, JsonNode> e : query.properties()) {
      String name = e.getKey();
      if (name.isEmpty() || name.length() > 256 || name.chars().anyMatch(c -> c < 0x20 || c == 0x7F)) {
        throw Refusal.of("invalid-request", "A query parameter name is 1 to 256 characters without control "
            + "characters.");
      }
      JsonNode v = e.getValue();
      if (v.isArray()) {
        for (JsonNode item : v) {
          b.addQueryParameter(name, scalar(name, item));
        }
      } else if (!v.isNull()) {
        b.addQueryParameter(name, scalar(name, v));
      }
    }
    return checkQueryNames(b.build(), s);
  }

  private static HttpUrl checkQueryNames(HttpUrl url, HttpSettings s) throws Refusal {
    HttpSettings.Auth auth = s.auth();
    if (auth.type() == HttpSettings.AuthType.API_KEY_QUERY && url.queryParameterNames().contains(auth.name())) {
      throw new Refusal("query-not-allowed", "The query parameter " + auth.name() + " carries the connection's key: "
          + "the gateway sets it.", "Leave it out; MCP Gateway adds it when it sends the request.");
    }
    return url;
  }

  static String scalar(String name, JsonNode v) throws Refusal {
    if (v.isString()) {
      return v.asString();
    }
    if (v.isNumber() || v.isBoolean()) {
      return v.asString();
    }
    throw Refusal.of("invalid-request", "The value of " + name + " must be a string, a number or a boolean.");
  }

  /** The connection's default headers, then the agent's (an agent's header replaces a default of that name). */
  static List<PlannedRequest.Header> headers(JsonNode given, HttpSettings s) throws Refusal {
    List<PlannedRequest.Header> agent = new ArrayList<>();
    if (given != null && !given.isMissingNode() && !given.isNull()) {
      if (!given.isObject()) {
        throw Refusal.of("invalid-request", "headers is an object of names and values.");
      }
      for (Map.Entry<String, JsonNode> e : given.properties()) {
        agent.add(new PlannedRequest.Header(e.getKey(), e.getValue().isString() ? e.getValue().asString()
            : e.getValue().toString()));
      }
    }
    checkAgentHeaders(agent, s);
    Set<String> overridden = new HashSet<>();
    agent.forEach(h -> overridden.add(h.name().toLowerCase(Locale.ROOT)));
    List<PlannedRequest.Header> out = new ArrayList<>();
    s.defaultHeaders().forEach((name, value) -> {
      if (!overridden.contains(name.toLowerCase(Locale.ROOT))) {
        out.add(new PlannedRequest.Header(name, value));
      }
    });
    out.addAll(agent);
    return out;
  }

  /** The header rules of report 10 §6.3: names, values, sizes, and nothing the HTTP layer or the gateway sets. */
  static void checkAgentHeaders(List<PlannedRequest.Header> headers, HttpSettings s) throws Refusal {
    if (headers.size() > Headers.MAX_HEADERS) {
      throw Refusal.of("header-not-allowed", "At most " + Headers.MAX_HEADERS + " headers.");
    }
    Set<String> gateway = s.gatewayHeaders();
    Set<String> names = new HashSet<>();
    int total = 0;
    for (PlannedRequest.Header h : headers) {
      if (!Headers.validName(h.name())) {
        throw Refusal.of("header-not-allowed", "A header name is letters, digits and !#$%&'*+.^_`|~-, up to 64 "
            + "characters.");
      }
      String lower = h.name().toLowerCase(Locale.ROOT);
      if (lower.equals("content-type")) {
        throw new Refusal("header-not-allowed", "Content-Type follows the body.", "Send json or form, or body with "
            + "contentType.");
      }
      if (Headers.isForbidden(h.name()) || gateway.contains(lower)) {
        throw new Refusal("header-not-allowed", h.name() + " can't be sent through MCP Gateway: "
            + (gateway.contains(lower) || lower.equals("authorization") ? "the gateway adds the connection's "
            + "credentials itself." : "the HTTP layer or the gateway sets it."), null);
      }
      if (!names.add(lower)) {
        throw Refusal.of("header-not-allowed", h.name() + " is given twice.");
      }
      if (!Headers.validValue(h.value())) {
        throw Refusal.of("header-not-allowed", "The value of " + h.name() + " has a line break or a control "
            + "character, or is longer than " + Headers.MAX_VALUE + " characters.");
      }
      total += h.name().length() + h.value().length() + 4;
    }
    if (total > Headers.MAX_TOTAL) {
      throw Refusal.of("header-not-allowed", "The headers are larger than 8 KB.");
    }
  }

  private static String contentType(String value) throws Refusal {
    if (value.length() > 256 || value.chars().anyMatch(c -> c < 0x20 || c == 0x7F) || MediaType.parse(value) == null) {
      throw Refusal.of("invalid-request", "contentType isn't a media type like text/plain or application/xml.");
    }
    return value;
  }

  private static byte[] form(JsonNode form) throws Refusal {
    if (!form.isObject()) {
      throw Refusal.of("invalid-request", "form is an object of names and values.");
    }
    if (form.size() > MAX_FORM_FIELDS) {
      throw Refusal.of("invalid-request", "At most " + MAX_FORM_FIELDS + " form fields.");
    }
    FormBody.Builder b = new FormBody.Builder(StandardCharsets.UTF_8);
    for (Map.Entry<String, JsonNode> e : form.properties()) {
      b.add(e.getKey(), scalar(e.getKey(), e.getValue()));
    }
    return formBytes(b.build());
  }

  static byte[] formBytes(FormBody body) {
    Buffer buffer = new Buffer();
    try {
      body.writeTo(buffer);
    } catch (java.io.IOException e) {
      throw new IllegalStateException("A form body is written to memory", e);
    }
    return buffer.readByteArray();
  }

  // ── Checks ──────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * What the connection allows, judged without the policy, so {@code execute} repeats it on every request it sends:
   * the base URL, the allowed paths, plain HTTP.
   */
  static void checkConnection(PlannedRequest r, HttpSettings s, ConnectionConfig cfg) throws Refusal {
    if (!UrlRules.within(r.base(), r.url())) {
      throw Refusal.of("url-not-allowed", r.url() + " isn't under " + r.base() + ".");
    }
    String path = r.relativePath();
    if (!allowedPath(r, s)) {
      throw Refusal.of("url-not-allowed", path + " is outside the allowed paths of " + cfg.name() + " under "
          + r.base() + ". Allowed: " + String.join(", ", s.allowedPaths()) + ".");
    }
    if (r.url().scheme().equals("http") && (!s.allowPlainHttp() || cfg.env() == Env.PROD)) {
      throw new Refusal("plain-http", cfg.name() + " would send this request over plain http://.", "Use an https "
          + "base URL" + (cfg.env() == Env.PROD ? "" : ", or turn on Allow plain HTTP for a private address you "
          + "allowed in Policies") + ".");
    }
  }

  /** {@link #checkConnection} plus the header rules, for a request read back from an approval. */
  static void checkStored(PlannedRequest r, HttpSettings s) throws Refusal {
    List<PlannedRequest.Header> agentHeaders = new ArrayList<>();
    for (PlannedRequest.Header h : r.headers()) {
      String value = s.defaultHeaders().get(h.name());
      if (value == null || !value.equals(h.value())) {
        agentHeaders.add(h);
      } else if (!Headers.validValue(h.value())) {
        throw Refusal.of("header-not-allowed", "A stored header isn't valid. Not executed.");
      }
    }
    checkAgentHeaders(agentHeaders, s);
    if (r.body() != null && PlannedRequest.READ_METHODS.contains(r.method())) {
      throw Refusal.of("invalid-request", r.method() + " requests can't carry a body.");
    }
    if (r.contentType() != null && MediaType.parse(r.contentType()) == null) {
      throw Refusal.of("invalid-request", "The stored content type isn't valid. Not executed.");
    }
  }

  /**
   * What the environment's policy allows ({@code http} section): the request lies under one of its allowed base URLs
   * as well; its body fits {@code maxRequestKb}; a read that may follow redirects stays under the allowlist whatever
   * path of its base URL it is sent to.
   */
  static void checkPolicy(PlannedRequest r, HttpSettings s, ConnectionConfig cfg, PolicyView policy,
      boolean followsRedirects) throws Refusal {
    List<HttpUrl> allowed = new ArrayList<>();
    for (String text : policy.strings("allowedBaseUrls")) {
      try {
        allowed.add(UrlRules.parse(text));
      } catch (UrlRules.UrlRejectedException e) {
        // An entry the policy schema let through but the rules refuse allows nothing.
      }
    }
    String env = policy.env().id();
    if (allowed.stream().noneMatch(a -> UrlRules.within(a, r.url()))) {
      throw new Refusal("url-not-allowed", r.url().newBuilder().query(null).build() + " isn't in the " + env
          + " allowlist of HTTP egress.", "A person adds the API's base URL under Policies › " + env
          + " › HTTP egress › Allowed base URLs.");
    }
    if (policy.limits() != null && r.body() != null && r.body().length > policy.limits().maxRequestBytes()) {
      throw Refusal.of("body-too-large", Sizes.tooLarge(r.body().length, policy.limits().maxRequestBytes(), env)
          + " (HTTP egress › Max request body).");
    }
    if (followsRedirects && s.followSameOrigin() && allowed.stream().noneMatch(a -> UrlRules.within(a, r.base()))) {
      throw new Refusal("redirects-outside-allowlist", cfg.name() + " follows redirects, but the " + env
          + " allowlist covers only part of its base URL " + r.base() + ".", "A person narrows the base URL to the "
          + "allowlist or sets Follow redirects to Off.");
    }
  }

  /**
   * Whether the request's path is one the connection allows, as written and as a server routes it
   * ({@link PlannedRequest#routedPath}): {@code /a/*}{@code /b} matches {@code /a//b} as written, the server serves
   * {@code /a/b}.
   */
  static boolean allowedPath(PlannedRequest r, HttpSettings s) {
    return matchesAny(s.allowedPaths(), r.relativePath()) && matchesAny(s.allowedPaths(), r.routedPath());
  }

  /**
   * Whether a GET or HEAD goes to a path the connection marked as changing state, in any spelling a server routes
   * there: as written, without empty segments and {@code ;parameters}, and in any case (some servers match paths
   * without it). Broader is the safe side here: such a GET asks a person first.
   */
  static boolean mutatingGet(PlannedRequest r, HttpSettings s) {
    if (!r.readMethod() || s.mutatingGetPaths().isEmpty()) {
      return false;
    }
    List<String> globs = s.mutatingGetPaths().stream().map(g -> g.toLowerCase(Locale.ROOT)).toList();
    return matchesAny(globs, r.relativePath().toLowerCase(Locale.ROOT))
        || matchesAny(globs, r.routedPath().toLowerCase(Locale.ROOT));
  }

  static boolean matchesAny(List<String> globs, String path) {
    for (String g : globs) {
      if (Glob.path(g).matches(path)) {
        return true;
      }
    }
    return false;
  }

  private static String bases(HttpSettings s) {
    List<String> out = new ArrayList<>();
    s.baseUrls().forEach(b -> out.add(b.toString()));
    return String.join(", ", out);
  }
}
