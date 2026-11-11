package ai.msrv.gaterook.connectors.gcp;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connectors.cloudauth.CloudEndpoints;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import tools.jackson.databind.JsonNode;

/**
 * Google's API Discovery documents: the HTTP method, path and parameters of every method of a Google Cloud API. The
 * agent names a method ({@code run} {@code projects.locations.services.list}); the gateway, never the agent, turns it
 * into a URL from the document. Documents are public: they are fetched without the connection's token, through the
 * Discovery directory ({@code www.googleapis.com/discovery/v1/apis}), and only their methods are kept in memory, for a
 * day.
 *
 * <p>{@link #build} expands the method's path template (RFC 6570 as Google uses it) with checked values: a reserved
 * expansion ({@code {+name}}) takes resource-name characters without {@code :}, {@code %}, {@code ?}, {@code #}, dot or
 * empty segments — a colon would let a GET template reach a custom method such as {@code …/versions/1:access} — except
 * a container image digest ({@code @sha256:<hex>}); a simple expansion ({@code {object}}) is percent-encoded whole. Only
 * the document's parameters are accepted, its patterns are enforced and its required parameters must be given.
 */
final class Discovery {

  static final URI DIRECTORY = URI.create("https://www.googleapis.com");
  static final String DIRECTORY_KEY = "gcp.discovery";
  static final Duration TTL = Duration.ofHours(24);
  static final int MAX_DOCUMENTS = 64;

  static final Pattern API = Pattern.compile("^[a-z][a-z0-9]{1,62}$");
  static final Pattern VERSION = Pattern.compile("^v[0-9][a-z0-9.]{0,15}$");
  static final Pattern METHOD = Pattern.compile("^[A-Za-z][A-Za-z0-9]*(\\.[A-Za-z][A-Za-z0-9]*){0,11}$");
  private static final Pattern TEMPLATE = Pattern.compile("\\{(\\+?)([A-Za-z0-9_.$-]+)}");
  /** Characters of a reserved expansion: RFC 3986 pchar and "/" without ":" (custom methods) and "%". */
  private static final Pattern RESERVED = Pattern.compile("^[A-Za-z0-9._~!$&'()*+,;=@/-]+$");
  private static final Pattern DIGEST = Pattern.compile("@sha256:[0-9a-f]{64}");
  private static final Pattern ROOT = Pattern.compile("^https://[a-z0-9-]+(\\.[a-z0-9-]+)*\\.googleapis\\.com/$");

  /** One parameter of a method. */
  record Param(String location, boolean required, boolean repeated, Pattern pattern) {}

  /**
   * One method of a document.
   *
   * @param api        the API as the agent names it ({@code sqladmin})
   * @param key        the method's id without its first segment ({@code instances.list} of {@code sql.instances.list})
   * @param body       whether it takes a request body
   * @param operation  whether it answers with a long-running operation
   */
  record Method(String api, String version, String title, String id, String key, String httpMethod, String path,
      Map<String, Param> params, boolean body, boolean operation, String rootUrl, String servicePath) {

    boolean reads() {
      return ApiRules.reads(httpMethod, key);
    }
  }

  /** The URL of one call, built from a method and checked values. */
  record Built(URI uri, String path, String resourcePath) {}

  /** The methods of one API version, by key and by id. */
  record Document(String api, String version, Map<String, Method> byKey, Map<String, Method> byId,
      long atNanos) {

    Method find(String method) {
      Method m = byKey.get(method);
      return m != null ? m : byId.get(method);
    }
  }

  private record Preferred(String version, long atNanos) {}

  private final Map<String, Document> documents = new ConcurrentHashMap<>();
  private final Map<String, Preferred> preferred = new ConcurrentHashMap<>();

  /** The method if its document is in memory (no I/O: {@code classify}'s view), else {@code null}. */
  Method cached(String api, String version, String method) {
    String v = version != null ? version : preferredVersion(api);
    if (v == null) {
      return null;
    }
    Document d = fresh(documents.get(api + ":" + v));
    return d == null ? null : d.find(method);
  }

  /** The method, from memory or from Google's Discovery directory. */
  Method method(GoogleApi google, CloudEndpoints endpoints, String api, String version, String method)
      throws ConnectorException {
    Document d = document(google, endpoints, api, version);
    Method m = d.find(method);
    if (m == null) {
      throw ConnectorException.badInput("The " + api + " " + d.version() + " API has no method " + method + "."
          + suggestions(d, method));
    }
    return m;
  }

  private Document document(GoogleApi google, CloudEndpoints endpoints, String api, String version)
      throws ConnectorException {
    String v = version != null ? version : preferredVersion(api);
    if (v != null) {
      Document d = fresh(documents.get(api + ":" + v));
      if (d != null) {
        return d;
      }
    }
    URI base = GoogleApi.base(endpoints, DIRECTORY_KEY, DIRECTORY);
    String root = base.toString().endsWith("/") ? base.toString().substring(0, base.toString().length() - 1)
        : base.toString();
    JsonNode directory = google.getPublic(URI.create(root + "/discovery/v1/apis?" + Call.param("name", api)
        + (version == null ? "&" + Call.param("preferred", "true") : "")), "Google API Discovery");
    JsonNode item = null;
    List<String> versions = new ArrayList<>();
    for (JsonNode i : directory.path("items")) {
      if (!api.equals(i.path("name").asString(""))) {
        continue;
      }
      versions.add(i.path("version").asString(""));
      if (version == null ? i.path("preferred").asBoolean(false) : version.equals(i.path("version").asString(""))) {
        item = i;
      }
    }
    if (item == null) {
      throw ConnectorException.badInput("Google's API directory has no " + api + (version == null ? ""
          : " " + version) + " API." + (versions.isEmpty() ? " Use the API's short name, such as run, compute, "
          + "storage or sqladmin." : " Versions: " + String.join(", ", versions) + "."));
    }
    String found = item.path("version").asString("");
    URI url;
    try {
      url = URI.create(item.path("discoveryRestUrl").asString(""));
    } catch (IllegalArgumentException e) {
      throw ConnectorException.resourceError("Google's API directory gave an unusable address for " + api + ".",
          null);
    }
    JsonNode doc = google.getPublic(url, "Google API Discovery");
    Document d = parse(doc, api, found, endpoints.overridden());
    if (documents.size() >= MAX_DOCUMENTS) {
      documents.entrySet().stream().min(Comparator.comparingLong(e -> e.getValue().atNanos()))
          .ifPresent(e -> documents.remove(e.getKey()));
    }
    documents.put(api + ":" + found, d);
    if (version == null) {
      preferred.put(api, new Preferred(found, System.nanoTime()));
    }
    return d;
  }

  private String preferredVersion(String api) {
    Preferred p = preferred.get(api);
    return p == null || System.nanoTime() - p.atNanos() > TTL.toNanos() ? null : p.version();
  }

  private static Document fresh(Document d) {
    return d == null || System.nanoTime() - d.atNanos() > TTL.toNanos() ? null : d;
  }

  /** The methods of one document. {@code test}: a development build, whose test endpoints aren't Google's hosts. */
  static Document parse(JsonNode doc, String api, String version, boolean test) throws ConnectorException {
    String rootUrl = doc.path("rootUrl").asString("");
    if (!test && !ROOT.matcher(rootUrl).matches()) {
      throw ConnectorException.resourceError("The Discovery document of " + api + " names a root outside "
          + "googleapis.com; it isn't used.", null);
    }
    String servicePath = doc.path("servicePath").asString("");
    if (servicePath.contains("..") || servicePath.contains("//") || servicePath.startsWith("/")) {
      throw ConnectorException.resourceError("The Discovery document of " + api + " has an unusable service path.",
          null);
    }
    String title = doc.path("title").asString(api);
    Map<String, Method> byKey = new LinkedHashMap<>();
    Map<String, Method> byId = new LinkedHashMap<>();
    walk(doc, api, version, title, rootUrl, servicePath, byKey, byId);
    return new Document(api, version, Map.copyOf(byKey), Map.copyOf(byId), System.nanoTime());
  }

  private static void walk(JsonNode node, String api, String version, String title, String rootUrl,
      String servicePath, Map<String, Method> byKey, Map<String, Method> byId) {
    for (Map.Entry<String, JsonNode> e : node.path("methods").properties()) {
      Method m = method(e.getValue(), api, version, title, rootUrl, servicePath);
      if (m != null) {
        byKey.put(m.key(), m);
        byId.put(m.id(), m);
      }
    }
    for (Map.Entry<String, JsonNode> e : node.path("resources").properties()) {
      walk(e.getValue(), api, version, title, rootUrl, servicePath, byKey, byId);
    }
  }

  private static Method method(JsonNode m, String api, String version, String title, String rootUrl,
      String servicePath) {
    String id = m.path("id").asString("");
    String httpMethod = m.path("httpMethod").asString("").toUpperCase(Locale.ROOT);
    String path = m.path("path").asString("");
    int dot = id.indexOf('.');
    if (dot <= 0 || httpMethod.isEmpty() || path.isEmpty()) {
      return null;
    }
    Map<String, Param> params = new LinkedHashMap<>();
    for (Map.Entry<String, JsonNode> p : m.path("parameters").properties()) {
      JsonNode v = p.getValue();
      Pattern pattern = null;
      String regex = v.path("pattern").asString("");
      if (!regex.isEmpty()) {
        try {
          pattern = Pattern.compile(regex);
        } catch (PatternSyntaxException ignored) {
          // A pattern Java can't read checks nothing; the value checks of build() still apply.
        }
      }
      params.put(p.getKey(), new Param(v.path("location").asString("query"), v.path("required").asBoolean(false),
          v.path("repeated").asBoolean(false), pattern));
    }
    String response = m.path("response").path("$ref").asString("");
    boolean operation = response.equals("Operation") || response.endsWith("LongrunningOperation");
    return new Method(api, version, title, id, id.substring(dot + 1), httpMethod, path, Map.copyOf(params),
        m.has("request"), operation, rootUrl, servicePath);
  }

  /**
   * The URL of {@code m} with {@code params}, every value checked.
   *
   * @param base the test endpoint of the API in a development build, or {@code null} for the document's root
   */
  static Built build(Method m, URI base, JsonNode params) throws Refusal {
    Map<String, String> pathValues = new LinkedHashMap<>();
    StringBuilder path = new StringBuilder();
    Matcher t = TEMPLATE.matcher(m.path());
    int at = 0;
    while (t.find()) {
      path.append(m.path(), at, t.start());
      boolean reserved = !t.group(1).isEmpty();
      String name = t.group(2);
      JsonNode v = params.get(name);
      if (v == null || v.isNull() || v.isContainer() || v.asString("").isEmpty()) {
        throw new Refusal("bad_input", m.api() + " " + m.key() + " needs params." + name + ".", "Its parameters: "
            + String.join(", ", new TreeSet<>(m.params().keySet())) + ".");
      }
      String value = v.asString("");
      Param p = m.params().get(name);
      if (p != null && p.pattern() != null && !p.pattern().matcher(value).find()) {
        throw new Refusal("bad_input", "params." + name + " \"" + DescribeRequest.abbreviate(value) + "\" doesn't "
            + "have the form " + m.api() + " expects (" + p.pattern().pattern() + ").", null);
      }
      path.append(reserved ? reserved(name, value) : simple(name, value));
      pathValues.put(name, value);
      at = t.end();
    }
    path.append(m.path().substring(at));
    StringBuilder query = new StringBuilder();
    for (Map.Entry<String, JsonNode> e : params.properties()) {
      String name = e.getKey();
      if (pathValues.containsKey(name)) {
        continue;
      }
      Param p = m.params().get(name);
      boolean standard = ApiRules.STANDARD_PARAMS.contains(name);
      if (!standard && (p == null || !"query".equals(p.location()))) {
        throw new Refusal("bad_input", m.api() + " " + m.key() + " has no parameter " + name + ".",
            "Its parameters: " + String.join(", ", new TreeSet<>(m.params().keySet())) + ".");
      }
      JsonNode v = e.getValue();
      List<String> values = new ArrayList<>();
      if (v.isArray()) {
        if (p == null || !p.repeated()) {
          throw new Refusal("bad_input", "params." + name + " takes one value, not a list.", null);
        }
        v.forEach(item -> values.add(item.asString("")));
      } else if (!v.isNull()) {
        values.add(v.asString(""));
      }
      for (String value : values) {
        if (p != null && p.pattern() != null && !p.pattern().matcher(value).find()) {
          throw new Refusal("bad_input", "params." + name + " \"" + DescribeRequest.abbreviate(value) + "\" doesn't "
              + "have the form " + m.api() + " expects (" + p.pattern().pattern() + ").", null);
        }
        query.append(query.isEmpty() ? "" : "&").append(Call.param(name, value));
      }
    }
    for (Map.Entry<String, Param> e : m.params().entrySet()) {
      if (e.getValue().required() && !pathValues.containsKey(e.getKey()) && !params.has(e.getKey())) {
        throw new Refusal("bad_input", m.api() + " " + m.key() + " needs params." + e.getKey() + ".", null);
      }
    }
    String root = base != null ? base.toString() : m.rootUrl();
    if (!root.endsWith("/")) {
      root = root + "/";
    }
    String rel = m.servicePath() + path;
    URI uri = URI.create(root + rel + (query.isEmpty() ? "" : "?" + query));
    return new Built(uri, uri.getRawPath(), resourcePath(m, rel));
  }

  /**
   * The path of the resource a call is about, as Google writes resource names ({@code projects/p/locations/l/…},
   * {@code b/<bucket>/o/<object>} for Cloud Storage): the API path without its service path, version and custom method.
   */
  static String resourcePath(Method m, String rel) {
    String p = rel.startsWith(m.servicePath()) ? rel.substring(m.servicePath().length()) : rel;
    p = p.replaceFirst("^v[0-9][a-z0-9.]*/", "");
    int colon = p.lastIndexOf(':');
    if (colon > p.lastIndexOf('/')) {
      p = p.substring(0, colon);
    }
    return p;
  }

  private static String reserved(String name, String value) throws Refusal {
    String withoutDigests = DIGEST.matcher(value).replaceAll("@sha256-digest");
    if (!RESERVED.matcher(withoutDigests).matches() || value.startsWith("/") || value.endsWith("/")
        || value.contains("//")) {
      throw new Refusal("bad_input", "params." + name + " \"" + DescribeRequest.abbreviate(value) + "\" isn't a "
          + "resource name.", "Pass names as Google returns them, like projects/acme-stage/locations/europe-west1/"
          + "services/api, without a :method, URL or query.");
    }
    for (String segment : value.split("/", -1)) {
      if (segment.equals(".") || segment.equals("..")) {
        throw new Refusal("bad_input", "params." + name + " has a dot segment.", null);
      }
    }
    return value;
  }

  private static String simple(String name, String value) throws Refusal {
    if (value.equals(".") || value.equals("..") || value.chars().anyMatch(c -> c < 0x20 || c == 0x7f)
        || value.length() > 1024) {
      throw new Refusal("bad_input", "params." + name + " isn't a usable value.", null);
    }
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static String suggestions(Document d, String method) {
    String last = ApiRules.last(method);
    List<String> close = d.byKey().keySet().stream().filter(k -> ApiRules.last(k).equals(last)
        || k.startsWith(method.contains(".") ? method.substring(0, method.lastIndexOf('.') + 1) : method)).sorted()
        .limit(8).toList();
    return close.isEmpty() ? " Method names look like projects.locations.services.list." : " Close: "
        + String.join(", ", close) + ".";
  }
}
