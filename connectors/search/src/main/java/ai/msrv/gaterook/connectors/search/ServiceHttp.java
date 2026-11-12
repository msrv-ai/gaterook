package ai.msrv.gaterook.connectors.search;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connectors.support.BuiltIn;
import ai.msrv.gaterook.connectors.support.egress.AddressPolicy;
import ai.msrv.gaterook.connectors.support.egress.Cidr;
import ai.msrv.gaterook.connectors.support.egress.EgressSpec;
import ai.msrv.gaterook.connectors.support.egress.GuardedEgress;
import ai.msrv.gaterook.connectors.support.egress.UrlRules;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import okhttp3.HttpUrl;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * HTTP to the vector database or search engine a person configured, through the core's egress guard: the guard's
 * clients ({@code GuardDns} and {@code GuardSocketFactory} judge every address a connection really goes to, so DNS
 * rebinding and redirects can't reach anything else), without redirects, retries or cookies, each body sent at most
 * once. As for the log, search and Vault clients (ARCHITECTURE.md §9), every private range stays allowed for a server
 * a person typed in; link-local, cloud metadata and reserved addresses and the core's own ports never are. Plain http
 * goes only to this computer. TLS trusts the platform's CAs and the connection's private CA, if it has one.
 *
 * <p>Agents never choose a host or a path: each backend builds the request from checked arguments on the connection's
 * base URL (or a vendor's fixed host). A request runs against the call's deadline, at most {@link #MAX_REQUEST}, and an
 * answer is read up to a cap. Credentials go into headers only, never into a URL or an error, and are tainted.
 */
final class ServiceHttp {

  static final Duration MARGIN = Duration.ofMillis(150);
  static final Duration MAX_REQUEST = Duration.ofSeconds(30);
  static final Duration CONNECT = Duration.ofSeconds(5);
  static final long MAX_ERROR = 64 * 1024;
  static final String USER_AGENT = "GateRook/" + BuiltIn.VERSION;
  static final JsonMapper JSON = JsonMapper.builder().build();

  /** Every private range of {@link AddressPolicy}. */
  static final List<Cidr> PRIVATE = Stream.of("127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12",
      "192.168.0.0/16", "100.64.0.0/10", "198.18.0.0/15", "fc00::/7", "fec0::/10", "64:ff9b:1::/48")
      .map(Cidr::parse).toList();
  static final List<Cidr> LOOPBACK = Stream.of("127.0.0.0/8", "::1/128").map(Cidr::parse).toList();

  private ServiceHttp() {}

  /**
   * Where the requests of a connection go: its base URL (scheme, host, port, an optional path prefix, no trailing
   * slash) and the certificates its TLS trusts besides the platform's.
   */
  record Endpoint(URI base, List<X509Certificate> ca) {

    Endpoint {
      ca = ca == null ? List.of() : List.copyOf(ca);
    }

    boolean plain() {
      return "http".equalsIgnoreCase(base.getScheme());
    }

    boolean loopback() {
      return loopbackHost(base.getHost());
    }

    /** {@code qdrant.example.com:6333/prefix}, for previews and texts. */
    String target() {
      String path = base.getRawPath() == null ? "" : base.getRawPath();
      return (base.getPort() < 0 ? base.getHost() : base.getHost() + ":" + base.getPort()) + path;
    }

    /**
     * The URL of {@code rawPath} (starting with {@code /}, its variable segments encoded with {@link #seg}) under the
     * base, with {@code query} as {@code key, value…} pairs, each encoded; a {@code null} value leaves its key out.
     */
    URI uri(String rawPath, String... query) {
      StringBuilder out = new StringBuilder(base.toString()).append(rawPath);
      char sep = '?';
      for (int k = 0; k + 1 < query.length; k += 2) {
        if (query[k + 1] == null) {
          continue;
        }
        out.append(sep).append(query[k]).append('=').append(component(query[k + 1]));
        sep = '&';
      }
      return URI.create(out.toString());
    }
  }

  /**
   * One request.
   *
   * @param what   what it does, for texts: "Qdrant's collection list"
   * @param change whether it changes data: an answer lost after sending leaves the outcome unknown
   */
  record Request(String method, URI uri, Map<String, List<String>> headers, byte[] body, String what,
      boolean change) {

    static Request get(URI uri, Map<String, List<String>> headers, String what) {
      return new Request("GET", uri, headers, null, what, false);
    }

    static Request json(String method, URI uri, Map<String, List<String>> headers, JsonNode body, String what,
        boolean change) {
      return new Request(method, uri, headers, body == null ? null : bytes(body), what, change);
    }
  }

  /** A whole answer: status, headers by lower-case name, the body as far as it was read. */
  record Response(int status, Map<String, List<String>> headers, byte[] body, boolean truncated) {

    boolean ok() {
      return status / 100 == 2;
    }

    String text() {
      return new String(body, StandardCharsets.UTF_8);
    }

    String header(String name) {
      List<String> v = headers.get(name.toLowerCase(Locale.ROOT));
      return v == null || v.isEmpty() ? null : v.getFirst();
    }
  }

  /**
   * The URL a person typed, checked by the egress guard's URL rules (no credentials, query or fragment, a canonical
   * host, no dot segments): https anywhere, plain http only to this computer, never an IP literal of a cloud metadata
   * or reserved address. Its path is kept as a prefix (a server behind a reverse proxy); the API's paths are GateRook's
   * to add.
   *
   * @param apiPaths path endings that belong to the API, refused in the URL ({@code /v1}, {@code /api/v2})
   */
  static URI base(String url, String example, String... apiPaths) throws ConnectorException {
    if (url == null || url.isBlank()) {
      throw ConnectorException.badInput("Enter the server's URL, such as " + example + ".");
    }
    HttpUrl parsed;
    try {
      parsed = UrlRules.parse(url.strip());
    } catch (IllegalArgumentException e) {
      throw ConnectorException.badInput(sentence(e.getMessage()));
    }
    if (parsed.query() != null) {
      throw ConnectorException.badInput("The server URL can't have a ?query.");
    }
    String host = parsed.host();
    Optional<InetAddress> literal = AddressPolicy.canonicalIp(host);
    if (literal.isPresent()) {
      AddressPolicy.Verdict v = AddressPolicy.builder().build().check(literal.get(), parsed.port());
      if (v.reason() != AddressPolicy.Reason.PUBLIC && v.reason() != AddressPolicy.Reason.PRIVATE
          && v.reason() != AddressPolicy.Reason.ALLOWED_PRIVATE) {
        throw ConnectorException.badInput(sentence(v.text()));
      }
    }
    if (parsed.scheme().equals("http") && !loopbackHost(host)) {
      throw ConnectorException.badInput("Plain http:// reaches only this computer (localhost). Use https:// for "
          + host + ".");
    }
    String path = parsed.encodedPath();
    while (path.endsWith("/")) {
      path = path.substring(0, path.length() - 1);
    }
    for (String api : apiPaths) {
      if (path.endsWith(api) || path.contains(api + "/")) {
        throw ConnectorException.badInput("Leave " + api + " out of the server URL: GateRook adds the API's paths "
            + "itself.");
      }
    }
    String origin = parsed.newBuilder().encodedPath("/").build().toString();
    return URI.create(origin.substring(0, origin.length() - 1) + path);
  }

  /** Whether {@code host} names this computer: {@code localhost}, or a loopback IP literal. */
  static boolean loopbackHost(String host) {
    if (host == null) {
      return false;
    }
    String h = host.toLowerCase(Locale.ROOT);
    if (h.startsWith("[") && h.endsWith("]")) {
      h = h.substring(1, h.length() - 1);
    }
    if (h.equals("localhost") || h.equals("localhost.")) {
      return true;
    }
    Optional<InetAddress> ip = AddressPolicy.canonicalIp(h);
    return ip.isPresent() && ip.get().isLoopbackAddress();
  }

  /**
   * Sends {@code r} and reads at most {@code maxBytes} of its answer (an error answer at most {@link #MAX_ERROR}).
   * Any status comes back: callers decide what a 404 means. No connection, a timeout or a refusal of the guard is an
   * exception; a change whose answer is lost after it was sent is {@code OUTCOME_UNKNOWN}, a read's is not.
   */
  static Response send(ExecContext ctx, Endpoint endpoint, Request r, long maxBytes) throws ConnectorException {
    if (ctx.cancelled()) {
      throw ConnectorException.timeout(r.what() + " was cancelled.");
    }
    Duration left = ctx.remaining().minus(MARGIN);
    if (left.isNegative() || left.isZero()) {
      throw ConnectorException.timeout(r.what() + " didn't answer in time.");
    }
    Map<String, List<String>> headers = new LinkedHashMap<>();
    headers.put("Accept", List.of("application/json"));
    headers.put("User-Agent", List.of(USER_AGENT));
    if (r.body() != null && !hasHeader(r.headers(), "Content-Type")) {
      headers.put("Content-Type", List.of("application/json"));
    }
    if (r.headers() != null) {
      headers.putAll(r.headers());
    }
    ExecContext.EgressRequest request = new ExecContext.EgressRequest(r.method(), r.uri(), headers, r.body(),
        left.compareTo(MAX_REQUEST) > 0 ? MAX_REQUEST : left);
    ExecContext.EgressResponse response;
    try {
      response = egress(ctx, endpoint).send(request, Math.max(maxBytes, MAX_ERROR));
    } catch (ConnectorException e) {
      if (e.kind() == ConnectorException.Kind.OUTCOME_UNKNOWN) {
        if (r.change()) {
          throw ConnectorException.outcomeUnknown("The connection to " + endpoint.target() + " ended after "
              + r.what() + " was sent, so whether it changed anything is not known. Read the records back before "
              + "trying again.", e);
        }
        throw ConnectorException.unavailable("The connection to " + endpoint.target() + " ended before " + r.what()
            + " answered. Nothing changed; try again.", e);
      }
      throw e;
    }
    byte[] body = response.body() == null ? new byte[0] : response.body();
    boolean ok = response.status() / 100 == 2;
    if (!ok && body.length > MAX_ERROR) {
      body = java.util.Arrays.copyOf(body, (int) MAX_ERROR);
    }
    Map<String, List<String>> lower = new LinkedHashMap<>();
    if (response.headers() != null) {
      response.headers().forEach((k, v) -> lower.put(k.toLowerCase(Locale.ROOT), v));
    }
    return new Response(response.status(), lower, body, ok && response.truncated());
  }

  /**
   * The egress of a call: the core's guard with the connection's address rules and TLS, or, without the guard (a
   * plugin host, a test), the context's own egress, which can't trust a private CA.
   */
  static ExecContext.Egress egress(ExecContext ctx, Endpoint endpoint) throws ConnectorException {
    Optional<GuardedEgress> guard = GuardedEgress.of(ctx);
    if (guard.isEmpty()) {
      if (!endpoint.ca().isEmpty()) {
        throw ConnectorException.unavailable("A private CA certificate needs GateRook's own network guard, which "
            + "this call doesn't have.", null);
      }
      return ctx.egress();
    }
    GuardedEgress g = guard.get();
    AddressPolicy policy = AddressPolicy.builder().allowPrivate(endpoint.plain() ? LOOPBACK : PRIVATE).build();
    EgressSpec.Tls tls = endpoint.ca().isEmpty() ? EgressSpec.Tls.PLATFORM
        : new EgressSpec.Tls(endpoint.ca(), true, List.of(), false);
    return new GuardedEgress(g.clients(), new EgressSpec(g.spec().poolKey(), policy, CONNECT, MAX_REQUEST, tls));
  }

  private static boolean hasHeader(Map<String, List<String>> headers, String name) {
    return headers != null && headers.keySet().stream().anyMatch(k -> k.equalsIgnoreCase(name));
  }

  /** {@code body} as compact JSON bytes. */
  static byte[] bytes(JsonNode body) {
    return JSON.writeValueAsBytes(body);
  }

  /** The answer as JSON, or why it isn't. */
  static JsonNode json(Response r, String what) throws ConnectorException {
    if (r.truncated()) {
      throw ConnectorException.badInput(what + " is larger than this call may read. Ask for fewer records or "
          + "fields, or leave the vectors out.");
    }
    try {
      return JSON.readTree(r.body());
    } catch (JacksonException e) {
      throw ConnectorException.resourceError(what + " isn't JSON: the URL may not be the server's API.", null);
    }
  }

  /** One path segment, percent-encoded: everything but RFC 3986's unreserved characters. */
  static String seg(String value) {
    return encode(value, false);
  }

  /** A query component, percent-encoded like a segment. */
  static String component(String value) {
    return encode(value, false);
  }

  private static String encode(String value, boolean keepSlash) {
    StringBuilder out = new StringBuilder();
    for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
      int c = b & 0xff;
      if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '.'
          || c == '_' || c == '~' || (keepSlash && c == '/')) {
        out.append((char) c);
      } else {
        out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
            .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
      }
    }
    return out.toString();
  }

  /** Headers as the egress takes them. */
  static Map<String, List<String>> headers(String... pairs) {
    Map<String, List<String>> out = new LinkedHashMap<>();
    for (int k = 0; k + 1 < pairs.length; k += 2) {
      if (pairs[k + 1] != null) {
        out.put(pairs[k], List.of(pairs[k + 1]));
      }
    }
    return out;
  }

  /** {@code text} ending in a full stop. */
  static String sentence(String text) {
    if (text == null || text.isBlank()) {
      return "";
    }
    return text.endsWith(".") ? text : text + ".";
  }
}
