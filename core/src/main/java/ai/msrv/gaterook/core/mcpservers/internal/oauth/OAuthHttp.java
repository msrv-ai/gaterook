package ai.msrv.gaterook.core.mcpservers.internal.oauth;

import ai.msrv.gaterook.connectors.support.egress.EgressClients;
import ai.msrv.gaterook.connectors.support.egress.EgressDenial;
import ai.msrv.gaterook.connectors.support.egress.UrlRules;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okio.BufferedSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The HTTP of a sign-in (metadata, registration, tokens) on the connection's egress guard: the address policy of its
 * environment, no redirect followed, no automatic retry of a POST, answers capped at {@link #MAX_BYTES}. Errors carry
 * what the server said in a form that holds no token, so their message may reach the person.
 */
public final class OAuthHttp {

  /** Metadata, a registration or a token answer is small; anything bigger is refused. */
  static final long MAX_BYTES = 256 * 1024;
  static final JsonMapper JSON = JsonMapper.builder().build();
  private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

  private final OkHttpClient client;
  private final String userAgent;

  /** @param client the connection's guarded client (no redirects, its address policy) */
  public OAuthHttp(OkHttpClient client, Duration timeout, String userAgent) {
    this.client = client.newBuilder().callTimeout(timeout).readTimeout(timeout).build();
    this.userAgent = userAgent;
  }

  /** What a server answered: its status, headers that matter here and the body (bounded, UTF-8). */
  public record Answer(int status, String contentType, List<String> challenges, String sessionId, String body) {

    public boolean ok() {
      return status >= 200 && status < 300;
    }

    /** The body as JSON, or {@code null} when it isn't. */
    public JsonNode json() {
      if (body == null || body.isBlank()) {
        return null;
      }
      try {
        return JSON.readTree(body);
      } catch (JacksonException e) {
        return null;
      }
    }
  }

  /** A sign-in step that failed: the message is for the person and holds no secret. */
  public static final class Failure extends Exception {

    private static final long serialVersionUID = 1L;
    private final String error;

    public Failure(String message) {
      this(message, null, null);
    }

    public Failure(String message, String error, Throwable cause) {
      super(message, cause);
      this.error = error;
    }

    /** The OAuth error code the server answered ({@code invalid_grant}), or {@code null}. */
    public String error() {
      return error;
    }
  }

  public Answer get(URI url, String accept) throws Failure {
    Request.Builder b = new Request.Builder().url(httpUrl(url)).get().header("Accept", accept);
    return execute(b, url, true);
  }

  /** A POST of JSON (dynamic client registration). */
  public Answer postJson(URI url, JsonNode body, Map<String, String> headers) throws Failure {
    return postJson(url, body, headers, true);
  }

  /**
   * The unauthenticated look at an MCP server: a POST of {@code initialize}. Only a refusal's body is read; a
   * server that answers may keep its event stream open, and what it says doesn't matter here.
   */
  public Answer probe(URI url, JsonNode body, Map<String, String> headers) throws Failure {
    return postJson(url, body, headers, false);
  }

  private Answer postJson(URI url, JsonNode body, Map<String, String> headers, boolean readSuccess) throws Failure {
    byte[] bytes = JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
    Request.Builder b = new Request.Builder().url(httpUrl(url)).post(EgressClients.onceBody(bytes, JSON_TYPE));
    headers.forEach(b::header);
    return execute(b, url, readSuccess);
  }

  /** Ends the MCP session a probe opened ({@code DELETE} with its id), best effort. */
  public void forget(URI url, String sessionId) {
    Request.Builder b = new Request.Builder().url(httpUrl(url)).delete();
    try {
      b.header("Mcp-Session-Id", sessionId);
      execute(b, url, false);
    } catch (Failure | IllegalArgumentException e) {
      // The server forgets an idle session by itself.
    }
  }

  /**
   * A form POST to a token or revocation endpoint (RFC 6749 §3.2): {@code Accept: application/json}, the client
   * authenticated with {@code client_secret_basic} when {@code basic} names a client and secret.
   */
  public Answer postForm(URI url, Map<String, String> form, String[] basic) throws Failure {
    FormBody.Builder fb = new FormBody.Builder(StandardCharsets.UTF_8);
    form.forEach((k, v) -> {
      if (v != null) {
        fb.add(k, v);
      }
    });
    FormBody formBody = fb.build();
    okio.Buffer buffer = new okio.Buffer();
    try {
      formBody.writeTo(buffer);
    } catch (IOException e) {
      throw new Failure("Couldn't write the request to " + host(url) + ".");
    }
    byte[] bytes = buffer.readByteArray();
    Request.Builder b = new Request.Builder().url(httpUrl(url))
        .post(EgressClients.onceBody(bytes, formBody.contentType())).header("Accept", "application/json");
    if (basic != null) {
      // RFC 6749 §2.3.1: both parts are form-encoded before they are joined.
      String pair = formEncode(basic[0]) + ":" + formEncode(basic[1]);
      b.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8)));
    }
    return execute(b, url, true);
  }

  private Answer execute(Request.Builder b, URI url, boolean readSuccess) throws Failure {
    b.header("User-Agent", userAgent);
    try (Response r = client.newCall(b.build()).execute()) {
      String body = null;
      if (r.body() != null && (readSuccess || !r.isSuccessful())) {
        BufferedSource source = r.body().source();
        if (source.request(MAX_BYTES + 1)) {
          throw new Failure(host(url) + " sent more than " + (MAX_BYTES / 1024) + " KB.");
        }
        body = source.readUtf8();
      }
      return new Answer(r.code(), r.header("Content-Type", ""), r.headers("WWW-Authenticate"),
          r.header("Mcp-Session-Id"), body);
    } catch (InterruptedIOException e) {
      throw new Failure(host(url) + " didn't answer in time.", null, e);
    } catch (IOException e) {
      EgressDenial denial = EgressDenial.find(e);
      if (denial != null) {
        throw new Failure(host(url) + " is a private address, which this environment's policy doesn't allow. "
            + "Allow its range for the server in Policies (HTTP: allowed private ranges).", null, e);
      }
      throw new Failure("Couldn't reach " + host(url) + ": " + clean(e.getMessage()), null, e);
    }
  }

  /**
   * The {@code error} and {@code error_description} of an OAuth error answer (RFC 6749 §5.2), as a failure whose
   * message names the step; or the status and a short, cleaned piece of the body.
   */
  public static Failure failure(String step, URI url, Answer a) {
    JsonNode j = a.json();
    String error = j != null && j.path("error").isString() ? j.path("error").asString() : null;
    String description = j != null && j.path("error_description").isString() ? j.path("error_description").asString()
        : null;
    StringBuilder m = new StringBuilder(step).append(": ").append(host(url)).append(" answered HTTP ")
        .append(a.status());
    if (error != null) {
      m.append(" (").append(clean(error)).append(')');
    }
    if (description != null) {
      m.append(": ").append(clean(description));
    } else if (error == null && a.body() != null && !a.body().isBlank() && !a.contentType().contains("html")) {
      m.append(": ").append(clean(a.body()));
    }
    return new Failure(m.toString(), error, null);
  }

  /**
   * A URL GateRook may send a sign-in to: {@code https} anywhere the egress guard allows, plain {@code http} only to
   * this computer (a server under development); no credentials, no fragment.
   */
  public static URI checked(String url, String what) throws Failure {
    HttpUrl parsed;
    try {
      parsed = UrlRules.parse(url);
    } catch (UrlRules.UrlRejectedException e) {
      throw new Failure(what + " isn't a URL GateRook uses: " + clean(e.getMessage()));
    }
    if (!parsed.username().isEmpty() || !parsed.password().isEmpty() || parsed.fragment() != null) {
      throw new Failure(what + " has credentials or a fragment in it, which a sign-in URL can't have.");
    }
    if (!parsed.isHttps() && !loopback(parsed.host())) {
      throw new Failure(what + " (" + parsed.host() + ") isn't HTTPS. GateRook signs in over HTTPS only, or over "
          + "HTTP to this computer.");
    }
    return parsed.uri();
  }

  static boolean loopback(String host) {
    String h = host.toLowerCase(Locale.ROOT);
    return h.equals("127.0.0.1") || h.equals("localhost") || h.equals("::1") || h.equals("[::1]");
  }

  static String host(URI url) {
    return url == null || url.getHost() == null ? "The server" : url.getHost();
  }

  /** At most 300 characters of what a server said, without controls. */
  public static String clean(String text) {
    if (text == null) {
      return "";
    }
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < text.length() && out.length() < 300; i++) {
      char c = text.charAt(i);
      out.append(c < 0x20 || c == 0x7f || (c >= 0x80 && c < 0xa0) || Character.getType(c) == Character.FORMAT ? ' '
          : c);
    }
    String s = out.toString().strip().replaceAll("\\s+", " ");
    return text.length() > 300 ? s + "…" : s;
  }

  private static HttpUrl httpUrl(URI url) {
    return HttpUrl.get(url.toString());
  }

  private static String formEncode(String s) {
    return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
  }

  /** A JSON object with the given string fields, in order (null values left out). */
  static ObjectNode object(String... pairs) {
    ObjectNode o = JSON.createObjectNode();
    for (int i = 0; i + 1 < pairs.length; i += 2) {
      if (pairs[i + 1] != null) {
        o.put(pairs[i], pairs[i + 1]);
      }
    }
    return o;
  }

  /** The strings of a JSON array (others skipped). */
  static List<String> strings(JsonNode array) {
    List<String> out = new ArrayList<>();
    if (array != null && array.isArray()) {
      for (JsonNode v : array) {
        if (v.isString()) {
          out.add(v.asString());
        }
      }
    }
    return out;
  }

  /** {@code a=b&c=d} of a query, decoded; repeated names keep their first value. */
  public static Map<String, String> query(String raw) {
    Map<String, String> out = new LinkedHashMap<>();
    if (raw == null || raw.isEmpty()) {
      return out;
    }
    for (String part : raw.split("&")) {
      int eq = part.indexOf('=');
      String k = eq < 0 ? part : part.substring(0, eq);
      String v = eq < 0 ? "" : part.substring(eq + 1);
      try {
        out.putIfAbsent(java.net.URLDecoder.decode(k, StandardCharsets.UTF_8),
            java.net.URLDecoder.decode(v, StandardCharsets.UTF_8));
      } catch (IllegalArgumentException e) {
        // A malformed escape: that parameter is left out.
      }
    }
    return out;
  }
}
