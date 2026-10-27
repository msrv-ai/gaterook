package ai.msrv.gaterook.connectors.support.egress;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connectors.support.TrustStores;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.X509TrustManager;
import okhttp3.Call;
import okhttp3.CertificatePinner;
import okhttp3.ConnectionPool;
import okhttp3.ConnectionSpec;
import okhttp3.CookieJar;
import okhttp3.EventListener;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.TlsVersion;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;

/**
 * The one way the gateway talks HTTP to places a person configured (report 10 §6.1, ARCHITECTURE.md §9 "SSRF"):
 * the HTTP connector and OpenAPI tools, MCP servers over HTTP, downloads of plugins, specs and the catalog, and
 * {@code ExecContext.egress()} of every connector.
 *
 * <p>Every client it makes:
 * <ul>
 *   <li>resolves through {@link GuardDns} and connects through {@link GuardSocketFactory} with the spec's
 *       {@link AddressPolicy} plus the core's own ports;</li>
 *   <li>uses {@link Proxy#NO_PROXY} (behind a system proxy the guard would only see the proxy);</li>
 *   <li>never follows redirects: a 3xx is data for the caller, each hop it decides to follow is a new checked call;</li>
 *   <li>never retries ({@code retryOnConnectionFailure(false)}): OkHttp would silently send a POST twice after a
 *       dropped pooled connection, i.e. two refunds; and since that flag doesn't stop the repeat OkHttp makes for a
 *       503 with {@code Retry-After: 0} (or a 421 on a coalesced connection), bodies are one-shot
 *       ({@link #onceBody}) and a follow-up of a request without a body is refused ({@link #refuseFollowUp});</li>
 *   <li>has no cookie jar, no cache and no interceptors: since OkHttp 5.4 an interceptor can swap the DNS, socket
 *       factory and proxy of a chain, so none is ever added.</li>
 * </ul>
 */
public final class EgressClients implements AutoCloseable {

  /**
   * @param userAgent {@code mcp-gateway/<version>}, set when a request has none
   * @param corePorts the core's listeners (local API, tunnel listener), blocked on loopback for every client; read
   *                  when a client is built, so a port bound after this object exists (tests: port 0) still counts
   * @param resolver  name resolution; tests substitute it
   */
  public record Settings(String userAgent, Supplier<Set<Integer>> corePorts, HostResolver resolver) {
    public Settings {
      Objects.requireNonNull(userAgent, "userAgent");
      Objects.requireNonNull(corePorts, "corePorts");
      resolver = resolver == null ? HostResolver.SYSTEM : resolver;
    }

    public Settings(String userAgent, Set<Integer> corePorts, HostResolver resolver) {
      this(userAgent, fixed(corePorts), resolver);
    }

    private static Supplier<Set<Integer>> fixed(Set<Integer> ports) {
      Set<Integer> copy = Set.copyOf(ports);
      return () -> copy;
    }
  }

  /** Response headers a caller may keep; others ({@code Set-Cookie} first) never leave the client. */
  private static final Set<String> DROPPED_RESPONSE_HEADERS = Set.of("set-cookie", "set-cookie2");
  /** An HTTP method as connectors send it: letters only (RFC 9110 allows more, no API of ours needs it). */
  private static final Pattern METHOD = Pattern.compile("[A-Z]{1,32}");
  /** An HTTP token (RFC 9110 §5.6.2), safe to repeat in a message. */
  private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,64}");

  private final Settings settings;
  private final OkHttpClient base;
  private final Map<String, ConnectionPool> pools = new ConcurrentHashMap<>();
  private final Map<EgressSpec, OkHttpClient> clients = new ConcurrentHashMap<>();

  public EgressClients(Settings settings) {
    this.settings = Objects.requireNonNull(settings, "settings");
    this.base = new OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .cookieJar(CookieJar.NO_COOKIES)
        .cache(null)
        .build();
  }

  public Settings settings() {
    return settings;
  }

  /**
   * The guarded client of {@code spec}, cached per spec. Callers must not add interceptors to it or derive clients
   * with another DNS, socket factory or proxy.
   */
  public OkHttpClient client(EgressSpec spec) {
    return clients.computeIfAbsent(spec, this::build);
  }

  /**
   * {@code ExecContext.egress()} on top of {@link #send}: the neutral interface connectors and plugins see, which a
   * built-in connector may also read as the {@link GuardedEgress} it is.
   */
  public GuardedEgress egress(EgressSpec spec) {
    return new GuardedEgress(this, spec);
  }

  /**
   * Sends one request and reads at most {@code maxBytes} of the (decompressed) body.
   *
   * <p>Errors become the {@link ConnectorException} kinds the core counts: a refusal of the guard is
   * {@code DENIED}; no connection is {@code RESOURCE_UNAVAILABLE}; a timeout is {@code TIMEOUT}; a failure after a
   * non-idempotent request was sent is {@code OUTCOME_UNKNOWN} — it may have been applied and is never retried.
   */
  public ExecContext.EgressResponse send(EgressSpec spec, ExecContext.EgressRequest request, long maxBytes)
      throws ConnectorException {
    Objects.requireNonNull(request, "request");
    if (maxBytes < 0) {
      throw new IllegalArgumentException("maxBytes < 0");
    }
    HttpUrl url;
    try {
      url = UrlRules.parse(request.uri().toString());
    } catch (UrlRules.UrlRejectedException e) {
      throw ConnectorException.denied(e.getMessage());
    }
    String method = request.method() == null ? "" : request.method().toUpperCase(Locale.ROOT);
    // OkHttp writes the method into the request line as it is: only a plain token, or a request could be smuggled.
    if (!METHOD.matcher(method).matches()) {
      throw ConnectorException.badInput("Unsupported HTTP method");
    }
    boolean idempotent = Set.of("GET", "HEAD", "OPTIONS").contains(method);
    if (request.body() != null && request.body().length > 0 && (method.equals("GET") || method.equals("HEAD"))) {
      throw ConnectorException.badInput(method + " requests can't carry a body");
    }

    Request.Builder builder = new Request.Builder().url(url);
    boolean hasUserAgent = false;
    if (request.headers() != null) {
      for (Map.Entry<String, List<String>> header : request.headers().entrySet()) {
        for (String value : header.getValue()) {
          try {
            builder.addHeader(header.getKey(), value);
          } catch (IllegalArgumentException | NullPointerException e) {
            // OkHttp's message quotes the value, which may be a token of the Authorization header: name it only.
            throw ConnectorException.badInput("The header " + headerName(header.getKey()) + " has a line break, a "
                + "control character or no value: HTTP doesn't allow that");
          }
        }
        hasUserAgent |= "User-Agent".equalsIgnoreCase(header.getKey());
      }
    }
    if (!hasUserAgent) {
      builder.header("User-Agent", settings.userAgent());
    }
    try {
      builder.method(method, body(method, request));
    } catch (IllegalArgumentException e) {
      // A method OkHttp requires a body for (POST, PUT, PATCH, …) without one: its message has the method only.
      throw ConnectorException.badInput(e.getMessage());
    }

    AtomicBoolean sent = new AtomicBoolean();
    AtomicReference<Response> repeatAsked = new AtomicReference<>();
    OkHttpClient client = client(spec).newBuilder().eventListener(new EventListener() {
      @Override
      public void requestHeadersEnd(Call call, Request req) {
        sent.set(true);
      }

      @Override
      public void followUpDecision(Call call, Response networkResponse, Request nextRequest) {
        refuseFollowUp(call, networkResponse, nextRequest, repeatAsked);
      }
    }).build();
    Call call = client.newCall(builder.build());
    Duration timeout = request.timeout() != null ? request.timeout() : spec.callTimeout();
    call.timeout().timeout(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);

    String target = url.host() + ":" + url.port();
    try (Response response = call.execute()) {
      byte[] body;
      boolean truncated;
      ResponseBody responseBody = response.body();
      BufferedSource source = responseBody.source();
      Buffer buffer = new Buffer();
      // Read the head up to maxBytes + 1 decompressed bytes: a gzip bomb stops at the cap, not at its real size.
      while (buffer.size() <= maxBytes && source.read(buffer, 8192) != -1) {
        // keep reading
      }
      truncated = buffer.size() > maxBytes;
      body = buffer.readByteArray(Math.min(buffer.size(), maxBytes));
      buffer.clear();
      return new ExecContext.EgressResponse(response.code(), headers(response.headers()), body, truncated);
    } catch (IOException e) {
      Response asked = repeatAsked.get();
      if (asked != null) {
        // The answer that asked for the repeat is the answer: its body went with the follow-up OkHttp prepared.
        return new ExecContext.EgressResponse(asked.code(), headers(asked.headers()), new byte[0], false);
      }
      throw translate(e, target, sent.get() && !idempotent, call.isCanceled());
    }
  }

  /**
   * A request body OkHttp sends at most once. {@code retryOnConnectionFailure(false)} doesn't stop every repeat: when
   * a server answers 503 with {@code Retry-After: 0}, or 421 on a coalesced HTTP/2 connection, OkHttp's
   * {@code RetryAndFollowUpInterceptor} (5.5.1) sends the same request again by itself — a second refund. With a
   * one-shot body it hands the answer back instead. Every request with a body the gateway sends goes out with one.
   */
  public static RequestBody onceBody(byte[] bytes, MediaType type) {
    RequestBody body = RequestBody.create(bytes, type);
    return new RequestBody() {
      @Override
      public MediaType contentType() {
        return body.contentType();
      }

      @Override
      public long contentLength() throws IOException {
        return body.contentLength();
      }

      @Override
      public void writeTo(BufferedSink sink) throws IOException {
        body.writeTo(sink);
      }

      @Override
      public boolean isOneShot() {
        return true;
      }
    };
  }

  /**
   * For {@code EventListener.followUpDecision}: a request without a body (DELETE, GET) can't be one-shot, so a follow-up
   * OkHttp is about to send on its own (see {@link #onceBody}) cancels the call before it goes out; {@code asked} keeps
   * the answer that asked for it, which the caller returns as the answer.
   */
  public static void refuseFollowUp(Call call, Response networkResponse, Request nextRequest,
      AtomicReference<Response> asked) {
    if (nextRequest != null) {
      asked.compareAndSet(null, networkResponse);
      call.cancel();
    }
  }

  /** Drops pooled connections and cached clients of {@code poolKey}: its connection or policy changed. */
  public void evict(String poolKey) {
    ConnectionPool pool = pools.remove(poolKey);
    if (pool != null) {
      pool.evictAll();
    }
    clients.keySet().removeIf(spec -> spec.poolKey().equals(poolKey));
  }

  @Override
  public void close() {
    pools.values().forEach(ConnectionPool::evictAll);
    pools.clear();
    clients.clear();
    base.dispatcher().cancelAll();
    base.dispatcher().executorService().shutdown();
  }

  private OkHttpClient build(EgressSpec spec) {
    AddressPolicy policy = spec.policy().withCorePorts(settings.corePorts().get());
    OkHttpClient.Builder builder = base.newBuilder()
        .dns(new GuardDns(settings.resolver(), policy))
        .socketFactory(new GuardSocketFactory(policy))
        .connectionPool(pools.computeIfAbsent(spec.poolKey(), key -> new ConnectionPool(4, 1, TimeUnit.MINUTES)))
        .connectTimeout(spec.connectTimeout())
        .callTimeout(spec.callTimeout());
    EgressSpec.Tls tls = spec.tls();
    ConnectionSpec modern = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
        .tlsVersions(tls.tls13Only() ? new TlsVersion[] {TlsVersion.TLS_1_3}
            : new TlsVersion[] {TlsVersion.TLS_1_3, TlsVersion.TLS_1_2})
        .build();
    builder.connectionSpecs(List.of(modern, ConnectionSpec.CLEARTEXT));
    if (!tls.extraCertificates().isEmpty() || !tls.platformTrust()) {
      X509TrustManager trust = TrustStores.of(tls.extraCertificates(), tls.platformTrust());
      builder.sslSocketFactory(TrustStores.sslContext(trust).getSocketFactory(), trust);
    }
    if (!tls.pins().isEmpty()) {
      CertificatePinner.Builder pinner = new CertificatePinner.Builder();
      for (EgressSpec.Pin pin : tls.pins()) {
        pinner.add(pin.hostPattern(), pin.sha256());
      }
      builder.certificatePinner(pinner.build());
    }
    return builder.build();
  }

  private static RequestBody body(String method, ExecContext.EgressRequest request) {
    byte[] bytes = request.body();
    boolean needsBody = Set.of("POST", "PUT", "PATCH").contains(method);
    if (!needsBody && (bytes == null || method.equals("GET") || method.equals("HEAD"))) {
      return null;
    }
    MediaType type = null;
    if (request.headers() != null) {
      for (Map.Entry<String, List<String>> header : request.headers().entrySet()) {
        if (header.getKey().equalsIgnoreCase("Content-Type") && !header.getValue().isEmpty()) {
          type = MediaType.parse(header.getValue().getFirst());
        }
      }
    }
    // The bytes as they are (create(String, …) would append "; charset=utf-8" to the type), and never twice.
    return onceBody(bytes == null ? new byte[0] : bytes, type);
  }

  /** The header's name for an error message, if it is a plain HTTP token; agents may send anything. */
  private static String headerName(String name) {
    return name != null && HEADER_NAME.matcher(name).matches() ? name : "with an invalid name";
  }

  private static Map<String, List<String>> headers(Headers headers) {
    Map<String, List<String>> out = new LinkedHashMap<>();
    for (String name : headers.names()) {
      String lower = name.toLowerCase(Locale.ROOT);
      if (!DROPPED_RESPONSE_HEADERS.contains(lower)) {
        out.put(lower, List.copyOf(headers.values(name)));
      }
    }
    return out;
  }

  private static ConnectorException translate(IOException e, String target, boolean maybeApplied, boolean canceled) {
    EgressDenial denial = EgressDenial.find(e);
    if (denial != null) {
      return ConnectorException.denied(denial.verdict().text());
    }
    if (maybeApplied) {
      return ConnectorException.outcomeUnknown("Outcome unknown: the connection to " + target
          + " dropped after the request was sent. Check before retrying.", e);
    }
    if (canceled) {
      return ConnectorException.timeout("The call to " + target + " was cancelled");
    }
    if (e instanceof SocketTimeoutException || (e instanceof InterruptedIOException && "timeout".equals(e.getMessage()))) {
      return ConnectorException.timeout(target + " didn't answer in time");
    }
    if (e instanceof UnknownHostException) {
      return ConnectorException.unavailable("Can't resolve " + target + ". Check the host name and the VPN.", e);
    }
    if (e instanceof ConnectException) {
      return ConnectorException.unavailable("Can't reach " + target + ": connection refused. Check the VPN or "
          + "the host, then test again.", e);
    }
    if (e instanceof SSLHandshakeException) {
      return ConnectorException.unavailable("TLS with " + target + " failed: " + e.getMessage()
          + ". Trust the certificate under TLS, then test again.", e);
    }
    if (e instanceof SSLException) {
      return ConnectorException.unavailable("TLS with " + target + " failed: " + e.getMessage(), e);
    }
    return ConnectorException.unavailable("Can't reach " + target + ": " + e.getMessage(), e);
  }

  /** The pool keys currently holding connections (tests, diagnostics). */
  Collection<String> poolKeys() {
    return pools.keySet();
  }
}
