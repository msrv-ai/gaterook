package com.gaterook.connectors.azure;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * One call of {@code azure_api_read} or {@code azure_api_change}: a Resource Manager request the gateway builds from a
 * method, a path under one of the connection's subscriptions, an {@code api-version}, query parameters and a JSON body
 * — never a URL. Checked without I/O, the same way in {@code classify}, the approval preview and {@code execute}.
 *
 * <p>The classification follows AutoRest's {@code x-ms-secret} contract: a GET never returns a secret, a POST action
 * may. So:
 * <ol>
 *   <li>actions that hand out keys, secrets, credentials, connection strings, SAS tokens or callback URLs are denied
 *       whatever the method ({@link #secretAction});</li>
 *   <li>GET is a read;</li>
 *   <li>a POST is a read only when it is a known query that changes nothing: Cost Management {@code query} and
 *       {@code forecast}, the subscription's metrics batch, {@code checkNameAvailability};</li>
 *   <li>every other POST and every PUT, PATCH and DELETE changes something and waits for the person's approval of
 *       the exact request.</li>
 * </ol>
 * Reads go through {@code azure_api_read} and changes through {@code azure_api_change}; each tool refuses the other's
 * requests and names the right one.
 *
 * @param query the query parameters besides {@code api-version}, sorted, as they are sent
 * @param body  the JSON body, or {@code null}
 */
record CallRequest(String tool, String method, ResourceIds.Parsed path, String rawPath, String apiVersion,
    Map<String, String> query, JsonNode body, Kind kind) {

  /** What the call is, for the classification. */
  enum Kind { READ, READ_POST, CHANGE }

  static final String READ = "azure_api_read";
  static final String CHANGE = "azure_api_change";
  static final Set<String> READ_METHODS = Set.of("GET", "POST");
  static final Set<String> CHANGE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
  static final Pattern API_VERSION = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}(-(preview|beta|alpha|privatepreview))?$");
  private static final Pattern QUERY_KEY = Pattern.compile("^\\$?[A-Za-z][A-Za-z0-9_.-]{0,40}$");
  static final int MAX_QUERY_VALUE = 4_096;
  static final int MAX_QUERY = 8_192;

  /**
   * Words in an action's name that say it hands out a credential ({@code listKeys}, {@code listCredential}), or values
   * that hold them: app settings ({@code listAppSettings} of Static Web Apps), an endpoint's full URL with its query
   * keys ({@code getFullUrl} of Event Grid), delivery attributes with secret header values.
   */
  private static final List<String> SECRET_WORDS = List.of("key", "secret", "credential", "connectionstring",
      "password", "token", "sas", "callbackurl", "publishingprofile", "dataplaneaccess", "appsetting", "fullurl",
      "deliveryattribute");
  /**
   * App Service configuration resources ({@code …/sites/<app>/config/<name>}) that hold values: app settings,
   * connection strings, mounted storage with its access key, metadata, publishing credentials, legacy auth settings.
   * Azure reads them through {@code …/list} actions; a GET of one is refused too, whatever an API version answers.
   */
  private static final Set<String> VALUE_CONFIG = Set.of("appsettings", "connectionstrings", "azurestorageaccounts",
      "metadata", "publishingcredentials", "authsettings");

  static CallRequest of(String tool, JsonNode args, Settings s, AzurePolicy p) throws Refusal {
    boolean change = CHANGE.equals(tool);
    String method = text(args, "method", change ? "" : "GET").toUpperCase(Locale.ROOT);
    if (!(change ? CHANGE_METHODS : READ_METHODS).contains(method)) {
      throw new Refusal("bad_input", change ? "method of azure_api_change is POST, PUT, PATCH or DELETE."
          : "method of azure_api_read is GET or POST (read-only queries only).", change ? "Reads go through "
          + "azure_api_read." : null);
    }
    String raw = text(args, "path", "");
    ResourceIds.Parsed path = ResourceIds.parse(raw);
    if (path == null) {
      throw new Refusal("azure_path", "\"" + Settings.abbreviate(raw) + "\" isn't a Resource Manager path under a "
          + "subscription (/subscriptions/<id>/resourceGroups/<group>/providers/<namespace>/<type>/<name>).",
          "Write the path without host, query or encoded characters.");
    }
    if (!s.hasSubscription(path.subscription())) {
      throw new Refusal("subscription", "Subscription " + path.subscription() + " isn't one of this connection's "
          + "subscriptions.", "Use one of: " + String.join(", ", s.subscriptions()) + ".");
    }
    String apiVersion = text(args, "apiVersion", "");
    if (!API_VERSION.matcher(apiVersion).matches()) {
      throw new Refusal("bad_input", "apiVersion is the provider's API version, like 2024-04-01 or "
          + "2023-10-01-preview.", "The Azure REST API reference names it for each operation.");
    }
    Map<String, String> query = new TreeMap<>();
    int total = 0;
    JsonNode q = args.path("query");
    if (!q.isMissingNode() && !q.isNull()) {
      if (!q.isObject()) {
        throw new Refusal("bad_input", "query is an object of parameter names and text values.", null);
      }
      for (Map.Entry<String, JsonNode> e : q.properties()) {
        String key = e.getKey();
        if (!QUERY_KEY.matcher(key).matches() || key.equalsIgnoreCase("api-version")) {
          throw new Refusal("bad_input", "\"" + Settings.abbreviate(key) + "\" can't be a query parameter here"
              + (key.equalsIgnoreCase("api-version") ? ": pass apiVersion instead." : "."), null);
        }
        JsonNode v = e.getValue();
        if (!v.isValueNode() || v.isNull()) {
          throw new Refusal("bad_input", "The query parameter " + key + " needs a text, number or boolean value.",
              null);
        }
        String value = v.asString();
        if (value.length() > MAX_QUERY_VALUE) {
          throw new Refusal("bad_input", "The query parameter " + key + " is longer than " + MAX_QUERY_VALUE
              + " characters.", null);
        }
        total += key.length() + value.length();
        query.put(key, value);
      }
    }
    if (total > MAX_QUERY) {
      throw new Refusal("bad_input", "The query parameters are longer than " + MAX_QUERY + " characters.", null);
    }
    JsonNode body = args.get("body");
    if (body != null && body.isNull()) {
      body = null;
    }
    if (body != null && (method.equals("GET") || method.equals("DELETE"))) {
      throw new Refusal("bad_input", method + " requests have no body.", null);
    }
    if (body != null && !body.isContainer()) {
      throw new Refusal("bad_input", "body is a JSON object or array.", null);
    }
    String secret = secretAction(path);
    if (secret == null && method.equals("GET")) {
      secret = valueConfig(path);
    }
    if (secret != null) {
      throw new Refusal("azure_secret_action", "The action " + secret + " returns keys, secrets or credentials, "
          + "which GateRook never hands to agents.", "Read the resource's properties with GET instead; a person "
          + "uses the Azure portal for its keys.");
    }
    checkPolicy(path, p);
    Kind kind = switch (method) {
      case "GET" -> Kind.READ;
      case "POST" -> readOnlyPost(path) ? Kind.READ_POST : Kind.CHANGE;
      default -> Kind.CHANGE;
    };
    String what = method + " " + Settings.abbreviate(raw);
    if (!change && kind == Kind.CHANGE) {
      throw new Refusal("not_a_read", what + " changes Azure.", "Call it with azure_api_change: a person approves "
          + "the exact request.");
    }
    if (change && kind != Kind.CHANGE) {
      throw new Refusal("is_a_read", what + " only reads.", "Call it with azure_api_read.");
    }
    return new CallRequest(change ? CHANGE : READ, method, path, raw, apiVersion, query, body, kind);
  }

  boolean read() {
    return kind != Kind.CHANGE;
  }

  /**
   * The segment that names a credential-handing action, or {@code null}: {@code listKeys}, {@code listSecrets},
   * {@code listCredentials}, {@code listConnectionStrings}, {@code listClusterUserCredential}, {@code listAccountSas},
   * {@code listCallbackUrl}, {@code regenerateKey}, {@code generateCredentials}, {@code publishxml}, App Service's
   * {@code config/<name>/list} (app settings, connection strings, publishing credentials, auth settings).
   */
  static String secretAction(ResourceIds.Parsed path) {
    List<String> segments = path.segments();
    int i = 2;
    while (i < segments.size()) {
      String segment = segments.get(i);
      if (segment.equalsIgnoreCase("resourceGroups") || segment.equalsIgnoreCase("providers")) {
        i += 2;
        continue;
      }
      // A type or an action: resource names (the segment after a type) are never judged.
      String s = segment.toLowerCase(Locale.ROOT);
      boolean credentialWord = SECRET_WORDS.stream().anyMatch(s::contains);
      boolean list = s.startsWith("list") && credentialWord;
      boolean regenerate = s.startsWith("regenerate") || s.startsWith("rotatekey");
      boolean issue = (s.startsWith("generate") || s.startsWith("get") || s.startsWith("fetch")) && credentialWord;
      boolean named = s.equals("publishxml") || s.equals("readonlykeys") || s.contains("withsecrets");
      boolean webConfigList = s.equals("list") && i >= 4 && segments.get(i - 2).equalsIgnoreCase("config");
      if (list || regenerate || issue || named) {
        return segment;
      }
      if (webConfigList) {
        return segments.get(i - 2) + "/" + segments.get(i - 1) + "/" + segment;
      }
      i += 2;
    }
    return null;
  }

  /** {@code config/appsettings} and the other value-bearing App Service configuration a path ends at, or {@code null}. */
  static String valueConfig(ResourceIds.Parsed path) {
    List<String> segments = path.segments();
    int n = segments.size();
    if (n < 2 || !path.namespaces().contains("microsoft.web") || !segments.get(n - 2).equalsIgnoreCase("config")) {
      return null;
    }
    String name = segments.get(n - 1);
    return VALUE_CONFIG.contains(name.toLowerCase(Locale.ROOT)) ? "config/" + name : null;
  }

  /**
   * POST actions that only read: Cost Management {@code query} and {@code forecast}, the subscription's metrics batch
   * ({@code /subscriptions/<id>/providers/Microsoft.Insights/metrics}) and {@code checkNameAvailability}.
   */
  static boolean readOnlyPost(ResourceIds.Parsed path) {
    String last = path.last();
    List<String> ns = path.namespaces();
    String lastNamespace = ns.isEmpty() ? null : ns.getLast();
    if (last.equals("checknameavailability")) {
      return true;
    }
    if ("microsoft.costmanagement".equals(lastNamespace) && (last.equals("query") || last.equals("forecast"))) {
      List<String> seg = path.segments();
      return seg.size() >= 2 && seg.get(seg.size() - 2).equalsIgnoreCase("Microsoft.CostManagement");
    }
    if ("microsoft.insights".equals(lastNamespace) && last.equals("metrics")) {
      // Only at subscription scope: /subscriptions/<id>/providers/Microsoft.Insights/metrics (POST is its batch).
      return path.segments().size() == 5 && path.resourceGroup() == null;
    }
    return false;
  }

  /** Services and resource masks of the path; locations are checked on what Azure answers. */
  static void checkPolicy(ResourceIds.Parsed path, AzurePolicy p) throws Refusal {
    List<String> services = path.namespaces().isEmpty() ? List.of("resources")
        : path.namespaces().stream().map(Services::ofNamespace).distinct().toList();
    for (String service : services) {
      if (!p.serviceAllowed(service)) {
        throw new Refusal(Services.sensitive(service) && !p.servicesRestricted() ? "sensitive_service"
            : "service_not_allowed", "The service " + service + " isn't allowed: " + p.servicesText() + ".",
            Services.sensitive(service) ? "Key Vault and role assignments are shown only when the Cloud policy names "
                + "them under Allowed services." : null);
      }
    }
    if (!p.resourceAllowed(path)) {
      throw new Refusal("resource_not_allowed", "The resource " + Settings.abbreviate(path.lower()) + " isn't "
          + "allowed by the Cloud policy's resource masks on " + p.env().id() + ".", null);
    }
  }

  /** The path, percent-encoded, as it goes to Resource Manager. */
  String encodedPath() {
    return ResourceIds.encodedPath(path);
  }

  /** {@code api-version} first, then the other parameters, sorted. */
  Map<String, String> parameters() {
    Map<String, String> out = Arm.query(apiVersion);
    out.putAll(query);
    return out;
  }

  /** The canonical form an approval stores and executes. */
  ObjectNode payload() {
    ObjectNode p = JsonNodeFactory.instance.objectNode();
    p.put("tool", tool);
    ObjectNode a = p.putObject("arguments");
    a.put("method", method);
    a.put("path", rawPath);
    a.put("apiVersion", apiVersion);
    if (!query.isEmpty()) {
      ObjectNode q = a.putObject("query");
      new LinkedHashMap<>(query).forEach(q::put);
    }
    if (body != null) {
      a.set("body", body.deepCopy());
    }
    return p;
  }

  long bodyBytes() {
    return body == null ? 0 : body.toString().getBytes(StandardCharsets.UTF_8).length;
  }

  private static String text(JsonNode node, String field, String dflt) {
    JsonNode n = node.get(field);
    return n == null || n.isNull() || !n.isValueNode() || n.asString().isBlank() ? dflt : n.asString().strip();
  }
}
