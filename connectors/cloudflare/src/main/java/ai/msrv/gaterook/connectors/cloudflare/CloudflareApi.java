package ai.msrv.gaterook.connectors.cloudflare;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connectors.support.BuiltIn;
import ai.msrv.gaterook.connectors.support.OutputSanitizer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Cloudflare's v4 API and its GraphQL Analytics API on {@code java.net.http}. Every URL is the gateway's own: the base
 * {@code https://api.cloudflare.com/client/v4} and path segments the connector checked and percent-encoded, never a
 * URL an agent or an answer gave. Redirects are never followed (the token must not travel), no proxy is used, nothing
 * is retried, answers are read up to {@link #MAX_BODY}, and every request runs against the call's deadline and takes a
 * share of the account's request budget first.
 */
final class CloudflareApi implements AutoCloseable {

  static final URI BASE = URI.create("https://api.cloudflare.com/client/v4");
  /** Largest answer one request may give. */
  static final long MAX_BODY = 8L * 1024 * 1024;
  /** Time kept back from the deadline for building the answer. */
  static final Duration MARGIN = Duration.ofMillis(150);
  static final Duration MAX_REQUEST = Duration.ofSeconds(30);
  static final String USER_AGENT = "GateRook/" + BuiltIn.VERSION;
  /**
   * Requests per minute GateRook takes from an account: Cloudflare allows 1,200 per five minutes for each user, shared
   * with the dashboard, Wrangler and other tools.
   */
  static final int PER_MINUTE = 180;
  /** GraphQL Analytics queries per minute (Cloudflare limits them separately, to 300 per five minutes). */
  static final int GRAPHQL_PER_MINUTE = 40;

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** Cloudflare's answer: the envelope's {@code result}, {@code result_info} and messages. */
  record Answer(int status, JsonNode result, JsonNode info, JsonNode body) {}

  /** The bytes of a value Cloudflare returns as is (a KV value), up to the limit asked for. */
  record Raw(byte[] bytes, boolean truncated, String contentType) {}

  /** A refusal of Cloudflare's, with its HTTP status and the first error code: Test connection reads them. */
  static final class CloudflareException extends ConnectorException {
    private static final long serialVersionUID = 1L;
    final int status;
    final int code;

    CloudflareException(Kind kind, String message, int status, int code) {
      super(kind, message, null);
      this.status = status;
      this.code = code;
    }
  }

  private final URI base;
  private volatile HttpClient http;

  /** @param testBase tests only: where the fake API listens instead of api.cloudflare.com; never a setting */
  CloudflareApi(URI testBase) {
    this.base = testBase != null ? strip(testBase) : BASE;
  }

  private static URI strip(URI u) {
    String s = u.toString();
    return URI.create(s.endsWith("/") ? s.substring(0, s.length() - 1) : s);
  }

  // ── URLs ────────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * The URL of a path under the base: each segment percent-encoded on its own (a {@code /} inside a KV key stays
   * inside its segment), the query in the given order. A segment that is empty, {@code .} or {@code ..} is refused:
   * Java's client sends it as written and a server that resolves it would answer a path nobody checked.
   */
  URI uri(List<String> segments, Map<String, String> query) {
    StringBuilder s = new StringBuilder(base.toString());
    for (String segment : segments) {
      String e = encode(segment);
      if (e.isEmpty() || e.equals(".") || e.equals("..")) {
        throw new IllegalArgumentException("An empty or dot segment in a Cloudflare path");
      }
      s.append('/').append(e);
    }
    if (query != null && !query.isEmpty()) {
      String sep = "?";
      for (Map.Entry<String, String> q : query.entrySet()) {
        if (q.getValue() == null) {
          continue;
        }
        s.append(sep).append(URLEncoder.encode(q.getKey(), StandardCharsets.UTF_8)).append('=')
            .append(URLEncoder.encode(q.getValue(), StandardCharsets.UTF_8).replace("+", "%20"));
        sep = "&";
      }
    }
    return URI.create(s.toString());
  }

  URI uri(String... segments) {
    return uri(List.of(segments), Map.of());
  }

  URI graphqlUri() {
    return uri("graphql");
  }

  /** RFC 3986 percent-encoding of one path segment: everything but the unreserved characters. */
  static String encode(String segment) {
    StringBuilder out = new StringBuilder();
    for (byte b : segment.getBytes(StandardCharsets.UTF_8)) {
      int c = b & 0xff;
      if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_'
          || c == '~' || c == '.') {
        out.append((char) c);
      } else {
        out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
            .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
      }
    }
    return out.toString();
  }

  /** The request as the approver sees it: the path and query under the base, no host. */
  String relative(URI uri) {
    String s = uri.toString();
    return s.startsWith(base.toString()) ? s.substring(base.toString().length()) : s;
  }

  // ── Requests ────────────────────────────────────────────────────────────────────────────────────────────────

  Answer get(Call c, URI uri, String what) throws ConnectorException {
    return send(c, "GET", uri, null, what, false);
  }

  /**
   * One request of the v4 API.
   *
   * @param change whether it changes something: once it was sent, a lost answer is an unknown outcome, never a
   *               failure to retry
   */
  Answer send(Call c, String method, URI uri, JsonNode body, String what, boolean change) throws ConnectorException {
    quota(c, budget(c), PER_MINUTE);
    Response r = exchange(c, method, uri, body, what, change, MAX_BODY);
    JsonNode tree = parse(r, what);
    if (r.status / 100 != 2 || tree.path("success").isBoolean() && !tree.path("success").asBoolean()) {
      throw error(r.status, tree, what, r.retryAfter);
    }
    return new Answer(r.status, tree.path("result"), tree.path("result_info"), tree);
  }

  /** A value Cloudflare returns as it was stored (a KV value): at most {@code max} bytes of it. */
  Raw raw(Call c, URI uri, int max, String what) throws ConnectorException {
    quota(c, budget(c), PER_MINUTE);
    Response r = exchange(c, "GET", uri, null, what, false, max);
    if (r.status / 100 != 2) {
      JsonNode tree;
      try {
        tree = r.bytes.length == 0 ? JsonNodeFactory.instance.objectNode() : JSON.readTree(r.bytes);
      } catch (JacksonException e) {
        tree = JsonNodeFactory.instance.objectNode();
      }
      throw error(r.status, tree, what, r.retryAfter);
    }
    return new Raw(r.bytes, r.truncated, r.contentType);
  }

  /**
   * A query of the GraphQL Analytics API that the connector wrote itself ({@link Analytics}): its {@code data}.
   * GraphQL reports most failures with HTTP 200 and an {@code errors} list; they become refusals here.
   */
  JsonNode graphql(Call c, String query, String what) throws ConnectorException {
    quota(c, budget(c) + ":graphql", GRAPHQL_PER_MINUTE);
    ObjectNode body = JsonNodeFactory.instance.objectNode().put("query", query);
    Response r = exchange(c, "POST", graphqlUri(), body, what, false, MAX_BODY);
    JsonNode tree = parse(r, what);
    if (r.status / 100 != 2) {
      throw error(r.status, tree, what, r.retryAfter);
    }
    JsonNode errors = tree.path("errors");
    if (errors.isArray() && !errors.isEmpty()) {
      List<String> messages = new ArrayList<>();
      for (JsonNode e : errors) {
        if (messages.size() < 3) {
          messages.add(excerpt(e.path("message").asString("")));
        }
      }
      String text = String.join("; ", messages);
      boolean denied = text.toLowerCase(java.util.Locale.ROOT).contains("authz")
          || text.toLowerCase(java.util.Locale.ROOT).contains("not authorized")
          || text.toLowerCase(java.util.Locale.ROOT).contains("does not have access");
      throw new CloudflareException(ConnectorException.Kind.RESOURCE_ERROR, "Cloudflare's GraphQL Analytics API "
          + "refused the query: " + text + (denied ? " " + permissionHint(what) : ""), r.status, 0);
    }
    return tree.path("data");
  }

  /**
   * The share of the account's request budget a call takes from; a form's suggestions that run before an account is
   * chosen take from the connection's own.
   */
  private static String budget(Call c) {
    String account = c.settings().accountId();
    return "cloudflare:" + (account != null ? account : "connection:" + c.cfg().id());
  }

  private static void quota(Call c, String key, int perMinute) throws ConnectorException {
    if (!c.ctx().acquire(key, perMinute)) {
      throw ConnectorException.resourceError("GateRook's share of Cloudflare API requests for this account ("
          + perMinute + " per minute) is used up. Try again in a minute.", null);
    }
  }

  private record Response(int status, byte[] bytes, boolean truncated, String contentType, String retryAfter) {}

  private Response exchange(Call c, String method, URI uri, JsonNode body, String what, boolean change, long max)
      throws ConnectorException {
    requireOwn(uri);
    HttpRequest.Builder b = HttpRequest.newBuilder(uri)
        .header("Accept", "application/json")
        .header("User-Agent", USER_AGENT);
    try (SecretResolver.Secret token = c.secrets().secret(CloudflareSettings.TOKEN)) {
      b.header("Authorization", "Bearer " + token.unsafeString());
    }
    if (body != null) {
      b.header("Content-Type", "application/json");
      b.method(method, HttpRequest.BodyPublishers.ofString(body.toString()));
    } else {
      b.method(method, HttpRequest.BodyPublishers.noBody());
    }
    Duration left = c.ctx().remaining().minus(MARGIN);
    if (left.isNegative() || left.isZero()) {
      throw ConnectorException.timeout("Cloudflare " + what + " didn't answer in time.");
    }
    HttpRequest request = b.timeout(left.compareTo(MAX_REQUEST) > 0 ? MAX_REQUEST : left).build();
    try {
      HttpResponse<InputStream> r = client().send(request, HttpResponse.BodyHandlers.ofInputStream());
      byte[] bytes;
      boolean truncated = false;
      try (InputStream in = r.body()) {
        if (r.statusCode() / 100 == 2 && max < MAX_BODY) {
          bytes = in.readNBytes((int) max + 1);
          if (bytes.length > max) {
            bytes = java.util.Arrays.copyOf(bytes, (int) max);
            truncated = true;
          }
        } else {
          bytes = read(in, what, MAX_BODY);
        }
      }
      return new Response(r.statusCode(), bytes, truncated, r.headers().firstValue("Content-Type").orElse(""),
          r.headers().firstValue("Retry-After").orElse(null));
    } catch (HttpConnectTimeoutException e) {
      throw ConnectorException.unavailable("Can't connect to " + host(uri) + " (timed out). Check that this computer "
          + "can reach Cloudflare's API.", e);
    } catch (HttpTimeoutException e) {
      if (change) {
        throw ConnectorException.outcomeUnknown("Cloudflare " + what + " didn't answer in time. The change may have "
            + "been applied: read the resource before trying again.", e);
      }
      throw ConnectorException.timeout("Cloudflare " + what + " didn't answer in time.");
    } catch (ConnectException e) {
      throw ConnectorException.unavailable("Can't connect to " + host(uri) + ": " + reason(e) + ".", e);
    } catch (SSLException e) {
      throw ConnectorException.unavailable("The TLS handshake with " + host(uri) + " failed: " + reason(e) + ".", e);
    } catch (IOException e) {
      if (change) {
        throw ConnectorException.outcomeUnknown("Cloudflare " + what + " failed after the request was sent ("
            + reason(e) + "). The change may have been applied: read the resource before trying again.", e);
      }
      throw ConnectorException.unavailable("Cloudflare " + what + " failed: " + reason(e), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      if (change) {
        throw ConnectorException.outcomeUnknown("Cloudflare " + what + " was cancelled after the request was sent. "
            + "The change may have been applied: read the resource before trying again.", e);
      }
      throw ConnectorException.timeout("Cloudflare " + what + " was cancelled.");
    }
  }

  private static JsonNode parse(Response r, String what) throws ConnectorException {
    try {
      return r.bytes.length == 0 ? JsonNodeFactory.instance.objectNode() : JSON.readTree(r.bytes);
    } catch (JacksonException e) {
      throw ConnectorException.resourceError("Cloudflare " + what + " answered " + r.status + " with something "
          + "that isn't JSON: " + excerpt(new String(r.bytes, StandardCharsets.UTF_8)), null);
    }
  }

  /** The gateway's own URLs only: the base of the API, a path under it. */
  private void requireOwn(URI uri) {
    boolean same = base.getScheme().equals(uri.getScheme()) && base.getHost().equalsIgnoreCase(uri.getHost())
        && base.getPort() == uri.getPort() && uri.getUserInfo() == null && uri.getRawPath() != null
        && (uri.getRawPath() + "/").startsWith(base.getRawPath() + "/");
    if (!same) {
      throw new IllegalArgumentException("Not a Cloudflare API URL: " + uri.getScheme() + "://" + uri.getHost());
    }
    for (String segment : uri.getRawPath().split("/", -1)) {
      String s = segment.toLowerCase(java.util.Locale.ROOT).replace("%2e", ".");
      if (s.equals(".") || s.equals("..")) {
        throw new IllegalArgumentException("A dot segment in a Cloudflare path");
      }
    }
  }

  /** What a refusal of Cloudflare's means and what to do. */
  static CloudflareException error(int status, JsonNode tree, String what, String retryAfter) {
    List<String> parts = new ArrayList<>();
    int code = 0;
    for (JsonNode e : tree.path("errors")) {
      if (code == 0 && e.path("code").canConvertToInt()) {
        code = e.path("code").asInt();
      }
      if (parts.size() < 3) {
        String m = excerpt(e.path("message").asString(""));
        parts.add(e.path("code").isNumber() ? e.path("code").asInt() + " " + m : m);
      }
    }
    String message = parts.isEmpty() ? "no details" : String.join("; ", parts);
    ConnectorException.Kind kind;
    String text;
    switch (status) {
      case 400 -> {
        kind = ConnectorException.Kind.BAD_INPUT;
        text = "Cloudflare refused the request (400): " + message;
      }
      case 401 -> {
        kind = ConnectorException.Kind.RESOURCE_ERROR;
        text = "Cloudflare refused the API token (401): " + message + ". Check that the token is active and not "
            + "expired, then test the connection again.";
      }
      case 403 -> {
        kind = ConnectorException.Kind.RESOURCE_ERROR;
        // 9109 "Invalid access token": the token itself, not a permission (seen on api.cloudflare.com, 2026-11).
        text = code == 9109 ? "Cloudflare refused the API token (403): " + message + ". Check that the token is "
            + "active and not expired, then test the connection again."
            : "Cloudflare denied the token (403): " + message + ". " + permissionHint(what);
      }
      case 404 -> {
        kind = ConnectorException.Kind.RESOURCE_ERROR;
        text = "Cloudflare " + what + ": not found (404): " + message;
      }
      case 409 -> {
        kind = ConnectorException.Kind.RESOURCE_ERROR;
        text = "Cloudflare " + what + ": conflict (409): " + message;
      }
      case 429 -> {
        kind = ConnectorException.Kind.RESOURCE_ERROR;
        text = "Cloudflare's rate limit was reached (429): its API allows 1,200 requests per five minutes for each "
            + "user, shared with people and other tools. Try again in "
            + (retryAfter != null && retryAfter.matches("\\d{1,5}") ? retryAfter + " seconds." : "a minute.");
      }
      default -> {
        kind = status >= 500 ? ConnectorException.Kind.RESOURCE_UNAVAILABLE : ConnectorException.Kind.RESOURCE_ERROR;
        text = "Cloudflare " + what + " failed (" + status + "): " + message;
      }
    }
    return new CloudflareException(kind, text, status, code);
  }

  /** The permission the token needs for a family of tools, in the dashboard's words. */
  static String permissionHint(String what) {
    return switch (what) {
      case "zones" -> "The token needs Zone · Zone · Read on the zone.";
      case "DNS" -> "The token needs Zone · DNS · Read, or DNS · Edit to change records, on the zone.";
      case "cache purge" -> "The token needs Zone · Cache Purge · Purge on the zone.";
      case "Workers" -> "The token needs Account · Workers Scripts · Read, or Edit to deploy; routes need Zone · "
          + "Workers Routes · Read.";
      case "Workers Logs" -> "The token needs Account · Workers Observability · Edit: Cloudflare requires it to run "
          + "log queries.";
      case "Workers KV" -> "The token needs Account · Workers KV Storage · Read.";
      case "Pages" -> "The token needs Account · Cloudflare Pages · Read, or Edit to retry or roll back.";
      case "analytics" -> "The token needs Zone · Analytics · Read on the zone.";
      case "tunnels" -> "The token needs Account · Cloudflare Tunnel · Read.";
      case "Logpush" -> "The token needs Logs · Edit on the account or the zone: Cloudflare requires it to list "
          + "Logpush jobs.";
      default -> "Check the token's permissions in the Cloudflare dashboard (My Profile or Manage Account → API "
          + "Tokens).";
    };
  }

  static String excerpt(String text) {
    if (text == null || text.isBlank()) {
      return "";
    }
    String t = OutputSanitizer.sanitize(text.strip());
    return t.length() <= 500 ? t : t.substring(0, 499) + "…";
  }

  static String host(URI uri) {
    return uri.getPort() < 0 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
  }

  private static byte[] read(InputStream in, String what, long max) throws IOException, ConnectorException {
    ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
    byte[] buf = new byte[16384];
    long total = 0;
    int n;
    while ((n = in.read(buf)) > 0) {
      total += n;
      if (total > max) {
        throw ConnectorException.resourceError("Cloudflare " + what + " answered with more than " + (max >> 20)
            + " MB. Narrow the request.", null);
      }
      out.write(buf, 0, n);
    }
    return out.toByteArray();
  }

  private static String reason(Throwable e) {
    String m = e.getMessage();
    return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
  }

  /** One client for every connection: it holds no connection's state, every request carries its call's token. */
  private HttpClient client() {
    HttpClient c = http;
    if (c == null) {
      synchronized (this) {
        if (http == null) {
          http = HttpClient.newBuilder()
              .version(HttpClient.Version.HTTP_1_1)
              .followRedirects(HttpClient.Redirect.NEVER)
              .proxy(HttpClient.Builder.NO_PROXY)
              .connectTimeout(Duration.ofSeconds(5))
              .build();
        }
        c = http;
      }
    }
    return c;
  }

  @Override
  public void close() {
    HttpClient h = http;
    if (h != null) {
      h.shutdownNow();
    }
  }
}
