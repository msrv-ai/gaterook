package ai.msrv.gaterook.connectors.http;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import okhttp3.HttpUrl;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * One request exactly as it will be sent, before the gateway adds the credentials: the canonical form an approval
 * stores ({@link #payload}) and executes after the person approved it, never a new build from the arguments.
 *
 * @param base        the connection's base URL the request lies under
 * @param headers     the agent's headers and the connection's default headers, in sending order
 * @param body        the exact body bytes, or {@code null}
 * @param contentType sent with the body, or {@code null}
 * @param operation   the OpenAPI operation of a template tool, or {@code null}
 */
record PlannedRequest(String method, HttpUrl url, HttpUrl base, List<Header> headers, byte[] body, String contentType,
    Operation operation) {

  /** Format of {@link #payload}; a stored approval with another version is refused, not guessed at. */
  static final int PAYLOAD_VERSION = 1;

  static final Set<String> READ_METHODS = Set.of("GET", "HEAD");

  record Header(String name, String value) {}

  /**
   * @param label {@code createRefund · billing-api v2 (template v3)}, the approver's "Operation" line
   */
  record Operation(String templateId, int version, String opKey, String label, String facet) {}

  PlannedRequest {
    headers = List.copyOf(headers);
  }

  boolean readMethod() {
    return READ_METHODS.contains(method);
  }

  /** The path under the base URL, decoded ({@code /refunds/re_1}); {@code /} for the base itself. */
  String relativePath() {
    List<String> segments = url.pathSegments();
    List<String> baseSegments = new ArrayList<>(base.pathSegments());
    if (!baseSegments.isEmpty() && baseSegments.getLast().isEmpty()) {
      baseSegments.removeLast();
    }
    StringBuilder out = new StringBuilder();
    for (int i = baseSegments.size(); i < segments.size(); i++) {
      out.append('/').append(segments.get(i));
    }
    String path = out.toString();
    if (path.endsWith("/") && path.length() > 1) {
      path = path.substring(0, path.length() - 1);
    }
    return path.isEmpty() ? "/" : path;
  }

  /**
   * {@link #relativePath} as most servers read it before they route it: empty segments dropped ({@code /export//run})
   * and each segment's path parameters cut off ({@code /export;x/run}, as Tomcat, Jetty and Spring do). Path rules
   * hold for both forms.
   */
  String routedPath() {
    List<String> segments = url.pathSegments();
    List<String> baseSegments = new ArrayList<>(base.pathSegments());
    if (!baseSegments.isEmpty() && baseSegments.getLast().isEmpty()) {
      baseSegments.removeLast();
    }
    StringBuilder out = new StringBuilder();
    for (int i = baseSegments.size(); i < segments.size(); i++) {
      String segment = segments.get(i);
      int parameters = segment.indexOf(';');
      String bare = parameters < 0 ? segment : segment.substring(0, parameters);
      if (!bare.isEmpty()) {
        out.append('/').append(bare);
      }
    }
    return out.isEmpty() ? "/" : out.toString();
  }

  /** {@code POST /v1/refunds} as a person reads it in an approval summary. */
  String line() {
    return method + " " + url.encodedPath();
  }

  String bodyText() {
    return body == null ? null : new String(body, StandardCharsets.UTF_8);
  }

  /** The canonical request kept with an approval: no secret, only what the gateway adds (by kind and name). */
  ObjectNode payload(HttpSettings settings) {
    ObjectNode p = JsonNodeFactory.instance.objectNode();
    p.put("v", PAYLOAD_VERSION);
    p.put("method", method);
    p.put("url", url.toString());
    ArrayNode h = p.putArray("headers");
    for (Header header : headers) {
      h.addArray().add(header.name()).add(header.value());
    }
    if (body != null) {
      p.put("contentType", contentType);
      p.put("body", Base64.getEncoder().encodeToString(body));
    }
    p.put("auth", authOf(settings));
    ArrayNode secret = p.putArray("secretHeaders");
    for (HttpSettings.SecretHeader s : settings.secretHeaders()) {
      if (s.appliesTo(method)) {
        secret.add(s.name());
      }
    }
    if (operation != null) {
      p.putObject("operation").put("template", operation.templateId()).put("version", operation.version())
          .put("opKey", operation.opKey());
    }
    return p;
  }

  /** What the gateway signs the request with, by kind and name ({@code apiKeyHeader:X-API-Key}). */
  static String authOf(HttpSettings settings) {
    HttpSettings.Auth a = settings.auth();
    return switch (a.type()) {
      case NONE -> "none";
      case BEARER -> "bearer";
      case BASIC -> "basic:" + a.username();
      case API_KEY_HEADER -> "apiKeyHeader:" + a.name();
      case API_KEY_QUERY -> "apiKeyQuery:" + a.name();
    };
  }

  /**
   * Reads a stored payload back. The checks the request passed when it was asked for are made again on what is stored:
   * a payload edited in the database (its MAC is the core's) can't send anything the connection doesn't allow.
   */
  static PlannedRequest fromPayload(JsonNode p, HttpSettings settings, Operation operation) throws Refusal {
    if (p == null || !p.isObject() || p.path("v").asInt(0) != PAYLOAD_VERSION) {
      throw Refusal.of("invalid-request", "The approved request isn't in a form this version of GateRook runs. "
          + "Not executed.");
    }
    if (!authOf(settings).equals(p.path("auth").asString(""))) {
      throw Refusal.of("invalid-request", "The credentials of the connection changed after approval. Not executed.");
    }
    String method = p.path("method").asString("").toUpperCase(Locale.ROOT);
    if (!RequestPlanner.METHODS.contains(method)) {
      throw Refusal.of("invalid-request", "The approved request has no method this connector sends. Not executed.");
    }
    HttpUrl url = HttpUrl.parse(p.path("url").asString(""));
    if (url == null) {
      throw Refusal.of("invalid-request", "The approved request has no valid URL. Not executed.");
    }
    HttpUrl base = settings.baseOf(url).orElseThrow(() -> Refusal.of("url-not-allowed", url + " is no longer under "
        + "a base URL of the connection. Not executed."));
    List<Header> headers = new ArrayList<>();
    for (JsonNode h : p.path("headers")) {
      headers.add(new Header(h.path(0).asString(""), h.path(1).asString("")));
    }
    byte[] body = null;
    String contentType = null;
    if (p.hasNonNull("body")) {
      try {
        body = Base64.getDecoder().decode(p.get("body").asString(""));
      } catch (IllegalArgumentException e) {
        throw Refusal.of("invalid-request", "The approved request's body is damaged. Not executed.");
      }
      contentType = p.path("contentType").asString(null);
    }
    PlannedRequest r = new PlannedRequest(method, url, base, headers, body, contentType, operation);
    RequestPlanner.checkStored(r, settings);
    return r;
  }
}
