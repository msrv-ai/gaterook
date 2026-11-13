package com.gaterook.connectors.http;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.ExecContext;
import com.gaterook.connector.api.SecretResolver;
import com.gaterook.connector.api.Suggestions;
import com.gaterook.connector.api.Suggestions.Option;
import com.gaterook.connectors.support.egress.GuardedEgress;
import com.gaterook.connectors.support.egress.UrlRules;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLHandshakeException;
import okhttp3.HttpUrl;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Help for the HTTP API form ({@link com.gaterook.connector.api.Connector#suggest}). Local, without any network:
 * how a well-known API signs in, judged by the host of the first base URL (Anthropic's {@code x-api-key}, Azure's
 * {@code api-key}, Mailgun's user {@code api}…), the usual API key header and query parameter names, and Allow plain
 * HTTP for an {@code http://} base URL. Service: the API's own OpenAPI document, fetched anonymously from a few usual
 * places under the base URL (nothing is signed, nothing trusted: a certificate that isn't is a note), for its servers,
 * its paths as allowed-path globs and the sign-in it declares.
 */
final class HttpSuggestions {

  static final String COMMON_HEADERS = "common API key headers";
  static final String COMMON_PARAMETERS = "common API key parameters";
  static final String DOCUMENT = "the OpenAPI document";

  /** How an API that GateRook knows by its host signs in, and what else it needs. */
  private record Vendor(String name, Predicate<String> host, String authType, String header, String query,
      String username, String note) {
    String source() {
      return "what the " + name + " API expects";
    }
  }

  private static final List<Vendor> VENDORS = List.of(
      new Vendor("Anthropic", h -> h.equals("api.anthropic.com"), "apiKeyHeader", "x-api-key", null, null,
          "The Anthropic API also needs the header anthropic-version: 2023-06-01: add it under Default headers."),
      new Vendor("Azure OpenAI", h -> h.endsWith(".openai.azure.com"), "apiKeyHeader", "api-key", null, null, null),
      new Vendor("Azure AI services", h -> h.endsWith(".cognitiveservices.azure.com")
          || h.endsWith(".api.cognitive.microsoft.com"), "apiKeyHeader", "Ocp-Apim-Subscription-Key", null, null,
          null),
      new Vendor("Azure API Management", h -> h.endsWith(".azure-api.net"), "apiKeyHeader",
          "Ocp-Apim-Subscription-Key", null, null, null),
      new Vendor("Datadog", h -> h.matches("api\\.([a-z0-9]+\\.)?(datadoghq\\.com|datadoghq\\.eu|ddog-gov\\.com)"),
          "apiKeyHeader", "DD-API-KEY", null, null,
          "Most Datadog reads also need DD-APPLICATION-KEY: add it under More secret headers."),
      new Vendor("Supabase", h -> h.endsWith(".supabase.co"), "apiKeyHeader", "apikey", null, null, null),
      new Vendor("Shopify", h -> h.endsWith(".myshopify.com"), "apiKeyHeader", "X-Shopify-Access-Token", null, null,
          null),
      new Vendor("RapidAPI", h -> h.endsWith(".p.rapidapi.com"), "apiKeyHeader", "X-RapidAPI-Key", null, null,
          "RapidAPI also needs X-RapidAPI-Host with the API's host name: add it under Default headers."),
      new Vendor("Figma", h -> h.equals("api.figma.com"), "apiKeyHeader", "X-Figma-Token", null, null, null),
      new Vendor("ElevenLabs", h -> h.equals("api.elevenlabs.io"), "apiKeyHeader", "xi-api-key", null, null, null),
      new Vendor("Brave Search", h -> h.equals("api.search.brave.com"), "apiKeyHeader", "X-Subscription-Token", null,
          null, null),
      new Vendor("Gemini", h -> h.equals("generativelanguage.googleapis.com"), "apiKeyHeader", "x-goog-api-key", null,
          null, null),
      new Vendor("Mailgun", h -> h.equals("api.mailgun.net") || h.equals("api.eu.mailgun.net"), "basic", null, null,
          "api", "Mailgun signs in as the user api with an API key as the password."),
      new Vendor("Twilio", h -> h.endsWith(".twilio.com"), "basic", null, null, null,
          "Twilio signs in with the Account SID (AC…) as the user and its auth token as the password."),
      new Vendor("Atlassian Cloud", h -> h.endsWith(".atlassian.net"), "basic", null, null, null,
          "Atlassian Cloud signs in with your account email as the user and an API token as the password."),
      new Vendor("Notion", h -> h.equals("api.notion.com"), null, null, null, null,
          "The Notion API also needs Notion-Version, such as 2022-06-28: add it under Default headers."),
      new Vendor("GitLab", h -> h.equals("gitlab.com") || h.startsWith("gitlab."), null, "PRIVATE-TOKEN", null, null,
          null),
      new Vendor("Google", h -> h.endsWith(".googleapis.com"), null, "X-Goog-Api-Key", "key", null, null));

  /** The header names APIs use for a key, with who uses them. */
  private static final List<Option> HEADERS = List.of(
      Option.of("X-API-Key", "the most common"),
      Option.of("api-key", "Azure OpenAI"),
      Option.of("Ocp-Apim-Subscription-Key", "Azure API Management, Azure AI services"),
      Option.of("apikey", "Supabase, Kong"),
      Option.of("Authorization", "the key as it is, without Bearer"),
      Option.of("X-Auth-Token", "OpenStack and many self-hosted APIs"),
      Option.of("PRIVATE-TOKEN", "GitLab"),
      Option.of("X-Goog-Api-Key", "Google APIs"),
      Option.of("DD-API-KEY", "Datadog"),
      Option.of("X-RapidAPI-Key", "RapidAPI"),
      Option.of("X-Shopify-Access-Token", "Shopify"),
      Option.of("X-Subscription-Token", "Brave Search"),
      Option.of("X-Figma-Token", "Figma"),
      Option.of("xi-api-key", "ElevenLabs"));

  private static final List<Option> PARAMETERS = List.of(
      Option.of("api_key", "the most common"),
      Option.of("apikey", null),
      Option.of("key", "Google APIs"),
      Option.of("access_token", null),
      Option.of("token", null),
      Option.of("appid", "OpenWeather"));

  /** Where an API describes itself, under its base URL, then at the origin. */
  private static final List<String> DOCUMENTS = List.of("/openapi.json", "/openapi.yaml", "/v3/api-docs");
  /** A document a suggestion reads; a larger one is left to Import HTTP API, which reads 20 MB. */
  static final long MAX_DOCUMENT = 4L * 1_024 * 1_024;
  private static final int MAX_GLOBS = 50;
  private static final Duration KEEP = Duration.ofMinutes(2);
  private static final int MAX_KEPT = 32;
  private static final Pattern VARIABLE = Pattern.compile("\\{([^{}]{1,128})}");

  /** What the API's document said, kept a short while: a form asks again after every change. */
  record Found(String url, String title, int paths, List<String> servers, List<String> globs, JsonNode auth,
      String problem, Instant at) {}

  private final Map<String, Found> kept = new LinkedHashMap<>(16, 0.75f, true) {
    @Override
    protected boolean removeEldestEntry(Map.Entry<String, Found> eldest) {
      return size() > MAX_KEPT;
    }
  };

  // ── Local ───────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * What the form alone tells: no network, no secret read. The sign-in of a well-known API is filled in only while no
   * token was typed for the default one (it would be left in a field that no longer shows): a note says it then.
   */
  static Suggestions local(ConnectionConfig cfg, SecretResolver secrets) {
    Suggestions.Builder out = Suggestions.builder();
    List<String> bases = new ArrayList<>();
    for (JsonNode n : cfg.config().path("baseUrls")) {
      if (n.isString() && !n.asString().isBlank()) {
        bases.add(n.asString().strip());
      }
    }
    String host = bases.isEmpty() ? null : host(bases.getFirst());
    Vendor vendor = host == null ? null : VENDORS.stream().filter(v -> v.host().test(host)).findFirst().orElse(null);
    if (vendor != null && vendor.authType() != null) {
      String type = cfg.string("/auth/type");
      boolean typed = secrets != null && secrets.isSet(HttpSettings.TOKEN);
      if (!typed) {
        out.value("/auth/type", vendor.source(), vendor.authType(), List.of());
      } else if (!vendor.authType().equals(type)) {
        out.note("The " + vendor.name() + " API signs in with " + (vendor.header() != null ? "an API key in the "
            + vendor.header() + " header" : "a user and password") + ": choose " + title(vendor.authType())
            + " under Authentication.");
      }
    }
    if (vendor != null && vendor.header() != null) {
      out.value("/auth/headerName", vendor.source(), vendor.header(), List.of(Option.of(vendor.header(),
          vendor.name())));
    } else {
      out.options("/auth/headerName", COMMON_HEADERS, HEADERS);
    }
    if (vendor != null && vendor.query() != null) {
      out.value("/auth/queryName", vendor.source(), vendor.query(), List.of(Option.of(vendor.query(),
          vendor.name())));
    } else {
      out.options("/auth/queryName", COMMON_PARAMETERS, PARAMETERS);
    }
    if (vendor != null && vendor.username() != null) {
      out.value("/auth/username", vendor.source(), vendor.username(), List.of(Option.of(vendor.username())));
    }
    if (vendor != null && vendor.note() != null) {
      out.note(vendor.note());
    }
    if (bases.stream().anyMatch(b -> b.regionMatches(true, 0, "http://", 0, 7))) {
      if (cfg.env() == Env.PROD) {
        out.note("Prod connections never send plain http://: use an https:// base URL.");
      } else {
        if (!cfg.bool("/advanced/allowPlainHttp", false)) {
          out.value("/advanced/allowPlainHttp", "the http:// base URL", "true", List.of());
        }
        out.note("An http:// base URL needs Allow plain HTTP under Advanced settings, and plain HTTP goes only to a "
            + "private address you allow in Policies.");
      }
    }
    return out.build();
  }

  /** The host of a base URL as typed, with or without a scheme, lower case; {@code null} when there is none. */
  static String host(String url) {
    String u = url.strip();
    int scheme = u.indexOf("://");
    if (scheme >= 0) {
      u = u.substring(scheme + 3);
    }
    int end = 0;
    while (end < u.length() && "/?#".indexOf(u.charAt(end)) < 0) {
      end++;
    }
    String authority = u.substring(0, end);
    int at = authority.lastIndexOf('@');
    if (at >= 0) {
      authority = authority.substring(at + 1);
    }
    if (authority.startsWith("[")) {
      int close = authority.indexOf(']');
      return close > 1 ? authority.substring(1, close).toLowerCase(Locale.ROOT) : null;
    }
    int colon = authority.indexOf(':');
    String host = (colon >= 0 ? authority.substring(0, colon) : authority).toLowerCase(Locale.ROOT);
    while (host.endsWith(".")) {
      host = host.substring(0, host.length() - 1);
    }
    return host.isEmpty() ? null : host;
  }

  // ── Service ─────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * The API's OpenAPI document, if it publishes one at a usual place under the first base URL: at most four GETs
   * without credentials, each under the deadline, stopping at the first document or at a connection problem. Read
   * only; a certificate that isn't trusted yet is a note (Test connection offers to trust it).
   */
  Suggestions service(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    Suggestions.Builder out = Suggestions.builder();
    HttpSettings s;
    try {
      s = HttpSettings.of(cfg);
    } catch (ConnectorException e) {
      return out.note(e.getMessage()).build();
    }
    HttpUrl base = s.baseUrls().getFirst();
    if (base.scheme().equals("http") && (!s.allowPlainHttp() || cfg.env() == Env.PROD)) {
      return out.build(); // calls would be refused: the local note says what to change
    }
    // What the anonymous look depends on: where it goes, whom it trusts, the headers it sends.
    String key = base + "|" + cfg.config().path("tls") + "|" + cfg.config().path("defaultHeaders") + "|"
        + cfg.config().path("advanced").path("connectTimeoutSeconds").asInt(5);
    Found found;
    synchronized (kept) {
      found = kept.get(key);
    }
    if (found == null || found.at().plus(KEEP).isBefore(ctx.clock().instant())) {
      found = discover(s, base, secrets, ctx);
      if (found == null) {
        return out.build();
      }
      synchronized (kept) {
        kept.put(key, found);
      }
    }
    offer(out, found, s);
    return out.build();
  }

  /** The first document found, a problem worth a note, or a "nothing found"; {@code null}: ran out of time. */
  private static Found discover(HttpSettings s, HttpUrl base, SecretResolver secrets, ExecContext ctx) {
    GuardedEgress guard;
    try {
      guard = HttpExecutor.guard(ctx, s);
    } catch (ConnectorException e) {
      return new Found(null, null, 0, List.of(), List.of(), null, e.getMessage(), Instant.EPOCH);
    }
    List<HttpUrl> candidates = new ArrayList<>();
    for (String path : DOCUMENTS) {
      candidates.add(UrlRules.resolve(base, path));
    }
    if (base.encodedPath().length() > 1) {
      candidates.add(base.newBuilder().encodedPath("/openapi.json").build());
    }
    List<PlannedRequest.Header> headers = new ArrayList<>();
    s.defaultHeaders().forEach((n, v) -> headers.add(new PlannedRequest.Header(n, v)));
    for (HttpUrl url : candidates) {
      if (ctx.cancelled() || ctx.remaining().compareTo(Duration.ofSeconds(1)) < 0) {
        return null;
      }
      PlannedRequest r = new PlannedRequest("GET", url, base, headers, null, null, null);
      CapturingTrust trust = new CapturingTrust(s.tls());
      HttpExecutor.Exchange e;
      try {
        e = HttpExecutor.exchange(guard, r, s, secrets, ctx, false, MAX_DOCUMENT, trust);
      } catch (IOException io) {
        IOException cause = io.getCause() instanceof IOException c ? c : io;
        String problem = cause instanceof SSLHandshakeException && trust.refused()
            ? "The certificate of " + base.host() + " isn't trusted yet: Test connection shows it for you to trust, "
                + "then the API's OpenAPI document can be read."
            : null;
        // Not kept: the VPN may be on by the next look.
        return new Found(null, null, 0, List.of(), List.of(), null, problem, Instant.EPOCH);
      } catch (ConnectorException ce) {
        return new Found(null, null, 0, List.of(), List.of(), null, null, Instant.EPOCH);
      }
      int status = e.answer().status();
      if (status < 200 || status >= 300 || e.answer().truncated() || e.answer().body().length == 0) {
        continue;
      }
      JsonNode root;
      try {
        root = OpenApiDocument.read(e.answer().body());
      } catch (OpenApiDocument.Rejected rejected) {
        continue;
      }
      return summary(url, root, s, ctx.clock().instant());
    }
    return new Found(null, null, 0, List.of(), List.of(), null, null, ctx.clock().instant());
  }

  /** What a form can use of the document: its servers, its paths as globs under the base URL, its sign-in. */
  private static Found summary(HttpUrl url, JsonNode root, HttpSettings s, Instant at) {
    HttpUrl base = s.baseUrls().getFirst();
    List<String> servers = new ArrayList<>();
    HttpUrl serverUnderBase = null;
    for (JsonNode server : root.path("servers")) {
      if (servers.size() >= 10) {
        break;
      }
      String text = fill(server.path("url").asString(""), server.path("variables"));
      HttpUrl parsed = text.isEmpty() || text.contains("{") ? null : url.resolve(text);
      if (parsed == null) {
        continue;
      }
      String clean = parsed.newBuilder().query(null).fragment(null).build().toString();
      if (clean.endsWith("/") && parsed.encodedPath().length() > 1) {
        clean = clean.substring(0, clean.length() - 1);
      }
      HttpUrl normalized = HttpUrl.parse(clean);
      if (normalized != null && !servers.contains(clean)) {
        servers.add(clean);
        if (serverUnderBase == null && UrlRules.within(base, normalized)) {
          serverUnderBase = normalized;
        }
      }
    }
    // Paths are relative to the server; without one under the base URL, to the base URL itself.
    HttpUrl server = serverUnderBase != null ? serverUnderBase : root.path("servers").isEmpty() ? base : null;
    Set<String> globs = new LinkedHashSet<>();
    int paths = 0;
    for (String path : root.path("paths").propertyNames()) {
      paths++;
      if (server == null || globs.size() >= MAX_GLOBS || !path.startsWith("/")) {
        continue;
      }
      String prefix = relative(base, server);
      String[] parts = path.substring(1).split("/", 2);
      String first = parts[0];
      globs.add(first.isEmpty() || first.contains("{") ? (prefix.isEmpty() ? "/**" : prefix + "/**")
          : prefix + "/" + first + "/**");
    }
    globs.remove("/**");
    JsonNode auth = security(root);
    String title = ToolText.clean(root.path("info").path("title").asString(""), 80);
    return new Found(url.toString(), title.isBlank() ? null : title, paths, List.copyOf(servers),
        List.copyOf(globs), auth, null, at);
  }

  /** The sign-in the document asks for: the first supported scheme of its global requirements, else its only one. */
  private static JsonNode security(JsonNode root) {
    JsonNode schemes = root.path("components").path("securitySchemes");
    List<String> names = new ArrayList<>();
    for (JsonNode requirement : root.path("security")) {
      if (requirement.isObject() && requirement.size() == 1) {
        names.addAll(requirement.propertyNames());
      }
    }
    if (names.isEmpty() && schemes.size() == 1) {
      names.addAll(schemes.propertyNames());
    }
    for (String name : names) {
      JsonNode scheme = OpenApiDocument.deref(root, schemes.path(name));
      ObjectNode config = scheme == null ? null : OpenApiSecurity.config(scheme);
      if (config != null) {
        return config;
      }
    }
    return null;
  }

  /** The server's path below the base path: {@code /v1} for server {@code …/v1} on base {@code …/}. */
  private static String relative(HttpUrl base, HttpUrl server) {
    String b = base.encodedPath();
    String p = server.encodedPath();
    String rest = p.length() > b.length() ? p.substring(b.endsWith("/") ? b.length() - 1 : b.length()) : "";
    return rest.endsWith("/") ? rest.substring(0, rest.length() - 1) : rest;
  }

  private void offer(Suggestions.Builder out, Found found, HttpSettings s) {
    if (found.url() == null) {
      if (found.problem() != null) {
        out.note(found.problem());
      }
      return;
    }
    Set<String> bases = new LinkedHashSet<>();
    s.baseUrls().forEach(b -> {
      String t = b.toString();
      bases.add(t.endsWith("/") && b.encodedPath().length() > 1 ? t.substring(0, t.length() - 1) : t);
    });
    List<Option> servers = new ArrayList<>();
    for (String server : found.servers()) {
      if (!bases.contains(server) && !bases.contains(server + "/")) {
        servers.add(Option.of(server, "a server of the document"));
      }
    }
    out.options("/baseUrls", DOCUMENT, servers);
    List<Option> globs = new ArrayList<>();
    for (String glob : found.globs()) {
      if (!s.allowedPaths().contains(glob)) {
        globs.add(Option.of(glob));
      }
    }
    out.options("/allowedPaths", DOCUMENT, globs);
    JsonNode auth = found.auth();
    if (auth != null) {
      String type = auth.path("type").asString("");
      if (!type.equals(s.auth().type().id)) {
        out.note("The OpenAPI document signs in with " + describe(auth) + ": choose " + title(type)
            + " under Authentication.");
      } else if (type.equals("apiKeyHeader") && !auth.path("headerName").asString("").equalsIgnoreCase(s.auth().name())) {
        out.value("/auth/headerName", DOCUMENT, auth.path("headerName").asString(), List.of(
            Option.of(auth.path("headerName").asString())));
      } else if (type.equals("apiKeyQuery") && !auth.path("queryName").asString("").equals(s.auth().name())) {
        out.value("/auth/queryName", DOCUMENT, auth.path("queryName").asString(), List.of(
            Option.of(auth.path("queryName").asString())));
      }
    }
    out.note("Found the OpenAPI document" + (found.title() == null ? "" : " of " + found.title()) + " at "
        + found.url() + ": " + found.paths() + (found.paths() == 1 ? " path" : " paths") + ". Import HTTP API "
        + "turns its operations into tools.");
  }

  private static String describe(JsonNode auth) {
    return switch (auth.path("type").asString("")) {
      case "bearer" -> "a bearer token";
      case "basic" -> "a user and password";
      case "apiKeyHeader" -> "an API key in the " + auth.path("headerName").asString() + " header";
      case "apiKeyQuery" -> "an API key in the " + auth.path("queryName").asString() + " query parameter";
      default -> "another sign-in";
    };
  }

  private static String title(String type) {
    return switch (type) {
      case "bearer" -> "Bearer token";
      case "basic" -> "User and password";
      case "apiKeyHeader" -> "API key in a header";
      case "apiKeyQuery" -> "API key in the query";
      default -> "None";
    };
  }

  /** {@code url} with each {@code {name}} of a declared variable replaced by its default. */
  private static String fill(String url, JsonNode variables) {
    if (url.length() > 2_048 || !variables.isObject() || variables.isEmpty()) {
      return url.length() > 2_048 ? "" : url;
    }
    Matcher m = VARIABLE.matcher(url);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      JsonNode variable = variables.get(m.group(1));
      String value = variable == null ? m.group() : variable.path("default").asString("");
      m.appendReplacement(out, Matcher.quoteReplacement(value));
    }
    m.appendTail(out);
    return out.toString();
  }
}
