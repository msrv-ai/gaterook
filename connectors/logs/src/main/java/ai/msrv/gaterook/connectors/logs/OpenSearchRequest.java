package ai.msrv.gaterook.connectors.logs;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connector.api.ToolCall;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** A canonical, local OpenSearch or Elasticsearch REST request. Unknown operations always use the mutation-classified request tool. */
record OpenSearchRequest(String tool, String method, String path, String body, String contentType) {
  static final String INDICES = "opensearch_indices";
  static final String QUERY = "opensearch_query";
  static final String REQUEST = "opensearch_request";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Set<String> METHODS = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
  private static final Set<String> QUERY_APIS = Set.of("_search", "_count", "_msearch", "_mget", "_field_caps",
      "_analyze", "_termvectors", "_mtermvectors", "_rank_eval");
  private static final Set<String> INDEX_METADATA = Set.of("_settings", "_mapping", "_alias", "_aliases",
      "_stats", "_segments", "_recovery", "_shard_stores");

  static boolean isTool(String tool) {
    return Set.of(INDICES, QUERY, REQUEST, "elasticsearch_indices", "elasticsearch_query", "elasticsearch_request")
        .contains(tool);
  }

  static OpenSearchRequest plan(ToolCall call, Settings.OpenSearch settings, Limits limits)
      throws ConnectorException {
    if (!Set.of(settings.tool("indices"), settings.tool("query"), settings.tool("request")).contains(call.tool())) {
      throw ConnectorException.badInput("This tool requires a matching " + settings.title() + " connection.");
    }
    JsonNode args = call.arguments();
    if (!args.isObject()) {
      throw ConnectorException.badInput(settings.title() + " arguments must be an object.");
    }
    if (call.tool().equals(settings.tool("indices"))) {
      String index = text(args, "index", String.join(",", settings.indices()));
      if (settings.fullApi() && !args.has("index")) {
        index = "*";
      }
      if (!settings.fullApi() && args.has("index") && !settings.indices().contains(index)) {
        throw ConnectorException.denied("Choose one of the connection's configured log index patterns.");
      }
      if (!index.matches("[a-zA-Z0-9.*_?,:-]+") || index.startsWith("-")) {
        throw ConnectorException.badInput("index must be a " + settings.title() + " index selector, without a URL or path.");
      }
      String expand = text(args, "expandWildcards", "open");
      if (!Set.of("open", "closed", "hidden", "none", "all").contains(expand)) {
        throw ConnectorException.badInput("expandWildcards must be open, closed, hidden, none or all.");
      }
      // Resolve Index is structured API output and also discovers aliases and data streams.
      return new OpenSearchRequest(call.tool(), "GET", "/_resolve/index/" + encode(index)
          + "?expand_wildcards=" + expand, null, "application/json");
    }
    if (!settings.fullApi()) {
      throw ConnectorException.denied("Full " + settings.title() + " API access is disabled. A person must set API access to full "
          + "in this connection; server permissions then control access beyond the log index patterns and filter.");
    }
    if (!call.tool().equals(settings.tool("query")) && !call.tool().equals(settings.tool("request"))) {
      throw ConnectorException.badInput("Unknown " + settings.title() + " tool " + call.tool() + ".");
    }
    String method = text(args, "method", "GET").toUpperCase(Locale.ROOT);
    if (!METHODS.contains(method)) {
      throw ConnectorException.badInput("Unsupported HTTP method.");
    }
    String path = text(args, "path", null);
    checkPath(path);
    JsonNode query = args.path("query");
    if (!query.isMissingNode()) {
      if (!query.isObject()) {
        throw ConnectorException.badInput("query must be an object of URL parameter values.");
      }
      StringBuilder target = new StringBuilder(path);
      for (var entry : query.properties()) {
        if (entry.getKey().isBlank()) {
          throw ConnectorException.badInput("Query parameter names cannot be empty.");
        }
        JsonNode value = entry.getValue();
        if (value.isArray()) {
          for (JsonNode item : value) {
            parameter(target, entry.getKey(), item);
          }
        } else {
          parameter(target, entry.getKey(), value);
        }
      }
      path = target.toString();
    }
    checkPath(path);
    String contentType = text(args, "contentType", "application/json");
    if (!Set.of("application/json", "application/x-ndjson").contains(contentType)) {
      throw ConnectorException.badInput("contentType must be application/json or application/x-ndjson.");
    }
    JsonNode supplied = args.get("body");
    String body = supplied == null || supplied.isNull() ? null
        : supplied.isString() ? supplied.asString() : supplied.toString();
    if (body != null) {
      try {
        if (contentType.equals("application/x-ndjson")) {
          if (!supplied.isString()) {
            throw ConnectorException.badInput("An NDJSON body must be a string with one JSON value per line.");
          }
          for (String line : body.split("\n")) {
            if (!line.isBlank()) {
              parseJson(line);
            }
          }
          if (!body.endsWith("\n")) {
            body += "\n";
          }
        } else {
          parseJson(body);
        }
      } catch (JacksonException e) {
        throw ConnectorException.badInput("body must contain valid JSON (or NDJSON with that contentType).");
      }
    }
    OpenSearchRequest request = new OpenSearchRequest(call.tool(), method, path, body, contentType);
    request.checkSize(limits);
    if (call.tool().equals(settings.tool("query")) && !request.knownRead()) {
      throw ConnectorException.denied("This operation is not a known read API. Use " + settings.tool("request")
          + " with approval or explicitly configured Bypass. SQL and other unverified APIs also use "
          + settings.tool("request") + ".");
    }
    return request;
  }

  private static void parseJson(String value) throws ConnectorException {
    if (value.isBlank()) {
      throw ConnectorException.badInput("Use no body instead of an empty JSON body.");
    }
    try (var parser = JSON.createParser(value)) {
      JSON.readTree(parser);
      if (parser.nextToken() != null) {
        throw ConnectorException.badInput("A JSON body must contain exactly one value.");
      }
    }
  }

  private static void parameter(StringBuilder target, String name, JsonNode value) throws ConnectorException {
    if (!value.isString() && !value.isNumber() && !value.isBoolean()) {
      throw ConnectorException.badInput("Query parameter values must be strings, numbers, booleans or arrays of them.");
    }
    target.append(target.indexOf("?") < 0 ? '?' : '&').append(encode(name)).append('=')
        .append(encode(value.isString() ? value.asString() : value.toString()));
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static String text(JsonNode args, String name, String fallback) throws ConnectorException {
    JsonNode value = args.get(name);
    if (value == null) {
      return fallback;
    }
    if (!value.isString() || value.asString().isBlank()) {
      throw ConnectorException.badInput(name + " must be a nonempty string.");
    }
    return value.asString();
  }

  private static void checkPath(String path) throws ConnectorException {
    if (path == null || !path.startsWith("/") || path.startsWith("//") || path.length() > 32_768) {
      throw ConnectorException.badInput("path must start with a single / on the configured search server.");
    }
    try {
      URI uri = URI.create(path);
      if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getRawFragment() != null) {
        throw new IllegalArgumentException();
      }
      String decoded = URLDecoder.decode(uri.getRawPath().replace("+", "%2B"), StandardCharsets.UTF_8);
      if (decoded.contains("\\") || decoded.contains("%") || decoded.contains("//")
          || decoded.chars().anyMatch(c -> c < 32 || c == 127)) {
        throw new IllegalArgumentException();
      }
      for (String segment : decoded.split("/")) {
        if (segment.equals(".") || segment.equals("..")) {
          throw new IllegalArgumentException();
        }
      }
    } catch (IllegalArgumentException e) {
      throw ConnectorException.badInput("Use a local API path without fragments, traversal, backslashes or "
          + "ambiguous escaping. Put URL parameters in query.");
    }
  }

  /** Explicit API shapes, never a suffix match that could accept an unknown plugin's write endpoint. */
  private boolean knownRead() {
    if (!Set.of("GET", "HEAD", "POST").contains(method)) {
      return false;
    }
    String rawPath = URI.create(path).getRawPath();
    // Encoded routing characters may be interpreted differently by proxies and OpenSearch.
    if (rawPath.contains("%")) {
      return false;
    }
    String[] parts = rawPath.substring(1).split("/");
    boolean index = parts.length > 0 && !parts[0].startsWith("_") && !parts[0].isEmpty();
    if ((parts.length == 1 && QUERY_APIS.contains(parts[0]))
        || (index && parts.length == 2 && QUERY_APIS.contains(parts[1]))
        || (index && parts.length == 3 && Set.of("_explain", "_termvectors").contains(parts[1]))
        || (parts.length == 2 && parts[0].equals("_validate") && parts[1].equals("query"))
        || (index && parts.length == 3 && parts[1].equals("_validate") && parts[2].equals("query"))) {
      return true;
    }
    if (method.equals("POST")) {
      return rawPath.equals("/_cluster/allocation/explain");
    }
    if (rawPath.equals("/") || index && parts.length == 1) {
      return true;
    }
    if (parts.length == 1 && INDEX_METADATA.contains(parts[0])
        || index && parts.length == 2 && INDEX_METADATA.contains(parts[1])) {
      return true;
    }
    if (index && parts.length == 3 && Set.of("_doc", "_source", "_mapping", "_alias").contains(parts[1])) {
      return true;
    }
    return rawPath.matches("/_cluster/(health|state|stats|settings|pending_tasks)(/[^/]+)?")
        || rawPath.equals("/_cluster/allocation/explain")
        || rawPath.matches("/_resolve/index/[^/]+")
        || rawPath.matches("/_cat/(indices|aliases|allocation|count|health|nodes|nodeattrs|plugins|recovery|"
            + "repositories|segments|shards|snapshots|tasks|templates|thread_pool)(/[^/]+)?")
        || rawPath.matches("/_nodes(/[^/]+)?(/(stats|info|hot_threads|usage))?(/[^/]+)?")
            && !rawPath.contains("reload")
        || rawPath.matches("/_tasks(/[^/]+)?")
        || rawPath.matches("/_(index_template|component_template|template|data_stream|ingest/pipeline|"
            + "search/pipeline|scripts)(/[^/]+)?");
  }

  void checkSize(Limits limits) throws ConnectorException {
    if (limits != null && body != null && body.getBytes(StandardCharsets.UTF_8).length > limits.maxRequestBytes()) {
      throw ConnectorException.denied("The search request body exceeds maxRequestBytes. Split the batch or "
          + "reduce the request.");
    }
  }

  ObjectNode payload(Settings.OpenSearch settings) {
    ObjectNode node = JsonNodeFactory.instance.objectNode().put("version", 1).put("tool", tool)
        .put("base", settings.base().toString()).put("method", method).put("path", path)
        .put("contentType", contentType);
    if (body != null) {
      node.put("body", body);
    }
    return node;
  }

  static OpenSearchRequest approved(JsonNode payload, ToolCall call, Settings.OpenSearch settings, Limits limits)
      throws ConnectorException {
    if (payload.path("version").asInt() != 1 || !payload.path("tool").asString("").equals(call.tool())
        || !payload.path("base").asString("").equals(settings.base().toString())) {
      throw ConnectorException.denied("The approved " + settings.title() + " request does not match this connection and tool.");
    }
    if (call.tool().equals(settings.tool("indices"))) {
      // Index discovery is deterministic; reject approval replay if its scope changed.
      OpenSearchRequest current = plan(call, settings, limits);
      if (!current.payload(settings).equals(payload)) {
        throw ConnectorException.denied("The approved index discovery scope has changed.");
      }
      return current;
    }
    return plan(new ToolCall(call.callId(), call.tool(), call.connection(), (ObjectNode) payload, null), settings,
        limits);
  }

  ActionPreview preview(Settings.OpenSearch settings, ConnectionConfig cfg) {
    String shown = method + " " + settings.base() + path + "\nAccept: application/json"
        + (body == null ? "" : "\nContent-Type: " + contentType)
        + (settings.auth().mode() == Settings.AuthMode.NONE ? "" : "\nAuthorization: <managed by GateRook>")
        + (body == null ? "" : "\n\n" + body);
    return ActionPreview.of(ActionPreview.Kind.HTTP, "http", shown, cfg.name() + " · " + settings.target(),
        method + " " + path).withFields(List.of(new ActionPreview.Field("API access",
            settings.fullApi() ? "Full API; server permissions apply" : "Configured log indices")))
        .withPayload(payload(settings));
  }
}
