package ai.msrv.gaterook.connectors.http;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connectors.support.Pem;
import ai.msrv.gaterook.connectors.support.egress.EgressSpec;
import ai.msrv.gaterook.connectors.support.egress.UrlRules;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import okhttp3.HttpUrl;
import tools.jackson.databind.JsonNode;

/**
 * The config of one HTTP connection ({@code config.schema.json}, report 10 §4), read once per call. Secrets are only
 * named here (JSON pointers of {@code writeOnly} fields); their values come from the call's {@code SecretResolver}.
 *
 * @param baseUrls         where requests may go, path included; a relative path goes under the first
 * @param allowedPaths     path globs under the base path ({@code /invoices/**}); {@code /**} by default
 * @param mutatingGetPaths GET paths that change state: {@code http_get} refuses them, the rest asks a person
 * @param followSameOrigin follow redirects of reads on the same origin (never on prod: the schema locks it)
 * @param testPath         Test connection's path under the first base URL
 */
record HttpSettings(List<HttpUrl> baseUrls, List<String> allowedPaths, List<String> mutatingGetPaths, Auth auth,
    List<SecretHeader> secretHeaders, Map<String, String> defaultHeaders, EgressSpec.Tls tls, Duration connectTimeout,
    boolean followSameOrigin, boolean allowPlainHttp, int maxConcurrentCalls, String testMethod, String testPath) {

  static final String TOKEN = "/auth/token";
  static final String API_KEY = "/auth/apiKey";
  static final String PASSWORD = "/auth/password";

  /** How the connection signs its requests. */
  enum AuthType {
    NONE("none"), BEARER("bearer"), API_KEY_HEADER("apiKeyHeader"), API_KEY_QUERY("apiKeyQuery"), BASIC("basic");

    final String id;

    AuthType(String id) {
      this.id = id;
    }

    static AuthType of(String id) throws ConnectorException {
      for (AuthType t : values()) {
        if (t.id.equals(id)) {
          return t;
        }
      }
      throw ConnectorException.badInput("Credentials: unknown type " + id + ".");
    }
  }

  /**
   * @param field the secret's pointer ({@code /auth/token}), or {@code null} for {@code none}
   * @param name  the header ({@code apiKeyHeader}) or query parameter ({@code apiKeyQuery}) that carries the key
   */
  record Auth(AuthType type, String field, String name, String username) {

    /** The header the gateway sets itself, or {@code null}: agents may never send it. */
    String header() {
      return switch (type) {
        case BEARER, BASIC -> "Authorization";
        case API_KEY_HEADER -> name;
        case NONE, API_KEY_QUERY -> null;
      };
    }
  }

  /** A secret header the gateway adds; {@code methods} empty means every request. */
  record SecretHeader(String name, String field, Set<String> methods) {

    boolean appliesTo(String method) {
      return methods.isEmpty() || methods.contains(method);
    }
  }

  /** Reads the config; a config the form would have refused is a bad input with the field named. */
  static HttpSettings of(ConnectionConfig cfg) throws ConnectorException {
    JsonNode c = cfg.config();
    List<HttpUrl> bases = new ArrayList<>();
    int i = 0;
    for (JsonNode n : c.path("baseUrls")) {
      i++;
      HttpUrl url;
      try {
        url = UrlRules.parse(n.asString(""));
      } catch (UrlRules.UrlRejectedException e) {
        throw ConnectorException.badInput("Base URL " + i + ": " + e.getMessage() + ".");
      }
      if (url.encodedQuery() != null) {
        throw ConnectorException.badInput("Base URL " + i + " can't have a ?query: put parameters in the call.");
      }
      bases.add(url);
    }
    if (bases.isEmpty()) {
      throw ConnectorException.badInput(cfg.name() + " has no base URL.");
    }
    List<String> allowed = strings(c.path("allowedPaths"));
    if (!c.has("allowedPaths")) {
      allowed = List.of("/**");
    }
    for (String p : allowed) {
      requireGlob("Allowed paths", p);
    }
    List<String> mutating = strings(c.path("mutatingGetPaths"));
    for (String p : mutating) {
      requireGlob("GET paths that change state", p);
    }

    JsonNode a = c.path("auth");
    AuthType type = AuthType.of(a.path("type").asString("bearer"));
    Auth auth = switch (type) {
      case NONE -> new Auth(type, null, null, null);
      case BEARER -> new Auth(type, TOKEN, null, null);
      case API_KEY_HEADER -> new Auth(type, API_KEY, headerName(a.path("headerName").asString("X-API-Key")), null);
      case API_KEY_QUERY -> new Auth(type, API_KEY, queryName(a.path("queryName").asString("api_key")), null);
      case BASIC -> new Auth(type, PASSWORD, null, username(a.path("username").asString("")));
    };

    List<SecretHeader> secretHeaders = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    int h = 0;
    for (JsonNode n : c.path("secretHeaders")) {
      String name = headerName(n.path("name").asString(""));
      if (!seen.add(name.toLowerCase(Locale.ROOT)) || Headers.isForbidden(name)
          || name.equalsIgnoreCase(auth.header() == null ? "" : auth.header())) {
        throw ConnectorException.badInput("More secret headers: " + name + " can't be set this way.");
      }
      Set<String> methods = new LinkedHashSet<>();
      for (JsonNode m : n.path("methods")) {
        methods.add(m.asString("").toUpperCase(Locale.ROOT));
      }
      secretHeaders.add(new SecretHeader(name, "/secretHeaders/" + h + "/value", Set.copyOf(methods)));
      h++;
    }

    Map<String, String> defaults = new LinkedHashMap<>();
    for (Map.Entry<String, JsonNode> e : c.path("defaultHeaders").properties()) {
      String name = headerName(e.getKey());
      if (Headers.isForbidden(name) || name.equalsIgnoreCase(auth.header() == null ? "" : auth.header())
          || seen.contains(name.toLowerCase(Locale.ROOT))) {
        throw ConnectorException.badInput("Default headers: " + name + " is set by the gateway or not allowed.");
      }
      String value = e.getValue().asString("");
      if (!Headers.validValue(value)) {
        throw ConnectorException.badInput("Default headers: the value of " + name + " has a line break or a "
            + "control character.");
      }
      defaults.put(name, value);
    }

    JsonNode t = c.path("tls");
    List<X509Certificate> certificates;
    String pem = t.path("caCertificates").asString("");
    try {
      certificates = pem.isBlank() ? List.of() : Pem.certificates(pem);
    } catch (RuntimeException e) {
      throw ConnectorException.badInput("TLS: the certificates aren't valid PEM.");
    }
    List<EgressSpec.Pin> pins = new ArrayList<>();
    for (JsonNode p : t.path("pins")) {
      for (HttpUrl base : bases) {
        try {
          pins.add(new EgressSpec.Pin(base.host(), p.asString("")));
        } catch (IllegalArgumentException e) {
          throw ConnectorException.badInput("TLS: a pin is sha256/ followed by 44 base64 characters.");
        }
      }
    }
    // A trusted certificate is added to the platform's CAs, never instead of them: the connection keeps working when
    // the API moves to a public certificate.
    EgressSpec.Tls tls = new EgressSpec.Tls(certificates, true, List.copyOf(new LinkedHashSet<>(pins)),
        "TLSv1.3".equals(t.path("minVersion").asString("TLSv1.2")));

    JsonNode adv = c.path("advanced");
    int connect = adv.path("connectTimeoutSeconds").asInt(5);
    if (connect < 1 || connect > 30) {
      throw ConnectorException.badInput("Advanced: the connect timeout is 1 to 30 s.");
    }
    int concurrent = adv.path("maxConcurrentCalls").asInt(4);
    if (concurrent < 1 || concurrent > 16) {
      throw ConnectorException.badInput("Advanced: max concurrent calls is 1 to 16.");
    }
    String testMethod = adv.path("test").path("method").asString("GET").toUpperCase(Locale.ROOT);
    if (!testMethod.equals("GET") && !testMethod.equals("HEAD")) {
      throw ConnectorException.badInput("Advanced: Test connection sends GET or HEAD.");
    }
    String testPath = adv.path("test").path("path").asString("/");
    return new HttpSettings(List.copyOf(bases), List.copyOf(allowed), List.copyOf(mutating), auth,
        List.copyOf(secretHeaders), java.util.Collections.unmodifiableMap(defaults), tls, Duration.ofSeconds(connect),
        "same-origin".equals(adv.path("followRedirects").asString("off")), adv.path("allowPlainHttp").asBoolean(false),
        concurrent, testMethod, testPath.isBlank() ? "/" : testPath);
  }

  /** Every header name the gateway sets for this connection, lower case: agents may not send them. */
  Set<String> gatewayHeaders() {
    Set<String> out = new LinkedHashSet<>();
    if (auth.header() != null) {
      out.add(auth.header().toLowerCase(Locale.ROOT));
    }
    for (SecretHeader s : secretHeaders) {
      out.add(s.name().toLowerCase(Locale.ROOT));
    }
    return out;
  }

  /** The base URL a URL lies under, the longest first when several do; empty when none does. */
  java.util.Optional<HttpUrl> baseOf(HttpUrl url) {
    HttpUrl best = null;
    for (HttpUrl base : baseUrls) {
      if (UrlRules.within(base, url) && (best == null || base.encodedPath().length() > best.encodedPath().length())) {
        best = base;
      }
    }
    return java.util.Optional.ofNullable(best);
  }

  private static List<String> strings(JsonNode array) {
    List<String> out = new ArrayList<>();
    for (JsonNode n : array) {
      out.add(n.asString(""));
    }
    return out;
  }

  private static void requireGlob(String field, String glob) throws ConnectorException {
    if (!glob.startsWith("/") || glob.length() > 256 || glob.chars().anyMatch(ch -> ch < 0x20 || ch == 0x7F)) {
      throw ConnectorException.badInput(field + ": " + glob + " must start with / and have no control characters.");
    }
  }

  private static String headerName(String name) throws ConnectorException {
    if (!Headers.validName(name)) {
      throw ConnectorException.badInput("A header name is letters, digits and -, up to 64 characters.");
    }
    return name;
  }

  private static String queryName(String name) throws ConnectorException {
    if (name.isEmpty() || name.length() > 64 || name.chars().anyMatch(ch -> ch <= 0x20 || ch == 0x7F || ch == '&'
        || ch == '=' || ch == '#')) {
      throw ConnectorException.badInput("Credentials: the query parameter name is up to 64 characters without "
          + "spaces, &, = or #.");
    }
    return name;
  }

  private static String username(String name) throws ConnectorException {
    if (name.indexOf(':') >= 0 || name.chars().anyMatch(ch -> ch < 0x20 || ch == 0x7F)) {
      throw ConnectorException.badInput("Credentials: the user name can't contain : or control characters.");
    }
    return name;
  }
}
