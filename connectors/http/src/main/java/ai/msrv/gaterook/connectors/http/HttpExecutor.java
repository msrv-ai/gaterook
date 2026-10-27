package ai.msrv.gaterook.connectors.http;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connectors.support.egress.AddressPolicy;
import ai.msrv.gaterook.connectors.support.egress.EgressClients;
import ai.msrv.gaterook.connectors.support.egress.EgressDenial;
import ai.msrv.gaterook.connectors.support.egress.GuardedEgress;
import ai.msrv.gaterook.connectors.support.egress.UrlRules;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.X509TrustManager;
import okhttp3.Call;
import okhttp3.EventListener;
import okhttp3.Handshake;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.Buffer;
import okio.BufferedSource;

/**
 * Sends a {@link PlannedRequest} through the core's egress guard ({@link GuardedEgress}: the environment's address
 * policy, the connection's pool) with the connection's TLS, adding the credentials last, and reads at most the size
 * limit of the (decompressed) answer. OkHttp never retries ({@code retryOnConnectionFailure(false)} in the guard), so
 * a request that dropped after it was sent is "outcome unknown", never sent again; and the repeat OkHttp would make
 * for a 503 with {@code Retry-After: 0} is stopped too (one-shot bodies, a refused follow-up): that 503 is the answer.
 */
final class HttpExecutor {

  /**
   * The guarded client's own call timeout: high on purpose, each call sets its deadline on the call itself. A fixed
   * value keeps one cached client per connection version instead of one per remaining time.
   */
  static final Duration CLIENT_CALL_TIMEOUT = Duration.ofSeconds(120);
  static final int MAX_REDIRECTS = 3;

  /** One exchange: the answer and what the connection showed on the way. */
  record Exchange(ResponseReader.Answer answer, Handshake handshake, List<InetAddress> addresses, long connectMs,
      String location) {}

  private HttpExecutor() {}

  /** The guard of the call with this connection's transport, or a refusal when the call has no network access. */
  static GuardedEgress guard(ExecContext ctx, HttpSettings s) throws ConnectorException {
    GuardedEgress guard = GuardedEgress.of(ctx).orElseThrow(() -> ConnectorException.denied(
        "This call has no network access of GateRook."));
    return guard.withTransport(s.tls(), s.connectTimeout(), CLIENT_CALL_TIMEOUT);
  }

  /**
   * Sends a request and, for a read that may ({@code follow}), its same-origin redirects: at most three, each under
   * the same base URL and allowed paths, none to a path that changes state. A redirect it doesn't follow is data.
   */
  static ResponseReader.Answer send(PlannedRequest first, HttpSettings s, SecretResolver secrets, ExecContext ctx,
      boolean follow) throws ConnectorException {
    GuardedEgress guard = guard(ctx, s);
    long maxBytes = Math.max(1, ctx.limits().maxResponseBytes());
    PlannedRequest r = first;
    long started = System.nanoTime();
    for (int hop = 0; ; hop++) {
      Exchange e;
      try {
        e = exchange(guard, r, s, secrets, ctx, true, maxBytes, null);
      } catch (IOException ex) {
        throw translate(ex, r, ctx);
      }
      ResponseReader.Answer a = e.answer();
      long elapsed = (System.nanoTime() - started) / 1_000_000;
      if (!follow || !first.readMethod() || !s.followSameOrigin() || !isRedirect(a.status())
          || e.location() == null) {
        return withTiming(a, elapsed, hop, null, r.url());
      }
      HttpUrl next = r.url().resolve(e.location());
      String refused = hopRefusal(first, next, s);
      if (refused == null && hop >= MAX_REDIRECTS) {
        refused = "not followed: more than " + MAX_REDIRECTS + " redirects.";
      }
      if (refused != null) {
        return withTiming(a, elapsed, hop, refused, r.url());
      }
      r = new PlannedRequest(r.method(), next, first.base(), r.headers(), null, null, r.operation());
    }
  }

  private static ResponseReader.Answer withTiming(ResponseReader.Answer a, long elapsed, int hops, String note,
      HttpUrl url) {
    return new ResponseReader.Answer(a.status(), a.reason(), a.headers(), a.body(), a.truncated(), elapsed, hops,
        note, url);
  }

  /** Why a redirect of a read isn't followed, or {@code null}. */
  private static String hopRefusal(PlannedRequest first, HttpUrl next, HttpSettings s) {
    if (next == null) {
      return "not followed: the Location isn't a URL.";
    }
    if (!next.scheme().equals(first.url().scheme()) || !next.host().equalsIgnoreCase(first.url().host())
        || next.port() != first.url().port()) {
      return "not followed: it leaves " + first.url().scheme() + "://" + first.url().host() + ".";
    }
    try {
      // The rules of a URL a person configured: no user info, no escaped separators or control characters.
      UrlRules.parse(next.newBuilder().fragment(null).build().toString());
    } catch (UrlRules.UrlRejectedException e) {
      return "not followed: " + e.getMessage() + ".";
    }
    PlannedRequest probe = new PlannedRequest(first.method(), next, first.base(), List.of(), null, null, null);
    if (!UrlRules.within(first.base(), next) || !RequestPlanner.allowedPath(probe, s)) {
      return "not followed: " + probe.relativePath() + " is outside the allowed paths under " + first.base() + ".";
    }
    if (RequestPlanner.mutatingGet(probe, s)) {
      return "not followed: GET " + probe.relativePath() + " changes state. Use http_request; it asks first.";
    }
    return null;
  }

  static boolean isRedirect(int status) {
    return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
  }

  /**
   * One request and its answer, nothing translated: Test connection needs the raw failure (a certificate it can offer
   * to trust). {@code sign} false sends it without credentials; {@code trust} replaces the connection's trust manager
   * to see the server's chain.
   */
  static Exchange exchange(GuardedEgress guard, PlannedRequest r, HttpSettings s, SecretResolver secrets,
      ExecContext ctx, boolean sign, long maxBytes, ChainCapture trust) throws IOException, ConnectorException {
    HttpUrl url = r.url();
    Request.Builder builder = new Request.Builder();
    builder.header("User-Agent", guard.clients().settings().userAgent());
    for (PlannedRequest.Header h : r.headers()) {
      builder.addHeader(h.name(), h.value());
    }
    if (sign) {
      url = sign(builder, url, s, r.method(), secrets, ctx);
    }
    builder.url(url);
    // One-shot: OkHttp would otherwise send it again by itself on a 503 with Retry-After: 0 (EgressClients.onceBody).
    RequestBody body = null;
    if (r.body() != null) {
      body = EgressClients.onceBody(r.body(), r.contentType() == null ? null : MediaType.get(r.contentType()));
    } else if (Set.of("POST", "PUT", "PATCH").contains(r.method())) {
      body = EgressClients.onceBody(new byte[0], null);
    }
    builder.method(r.method(), body);

    OkHttpClient client = guard.client();
    if (url.scheme().equals("http")) {
      List<InetAddress> pinned = plainHttpAddresses(guard, url);
      client = client.newBuilder().dns(host -> pinned).build();
    }
    AtomicBoolean sent = new AtomicBoolean();
    AtomicLong connectStart = new AtomicLong();
    AtomicLong connectEnd = new AtomicLong();
    AtomicReference<List<InetAddress>> addresses = new AtomicReference<>(List.of());
    AtomicReference<Response> repeatAsked = new AtomicReference<>();
    OkHttpClient.Builder derived = client.newBuilder().eventListener(new EventListener() {
      @Override
      public void followUpDecision(Call call, Response networkResponse, Request nextRequest) {
        // A request without a body can't be one-shot: the repeat OkHttp would send on its own is refused.
        EgressClients.refuseFollowUp(call, networkResponse, nextRequest, repeatAsked);
      }

      @Override
      public void dnsEnd(Call call, String domainName, List<InetAddress> list) {
        addresses.set(List.copyOf(list));
      }

      @Override
      public void connectStart(Call call, java.net.InetSocketAddress address, java.net.Proxy proxy) {
        connectStart.compareAndSet(0, System.nanoTime());
        if (addresses.get().isEmpty() && address.getAddress() != null) {
          addresses.set(List.of(address.getAddress()));
        }
      }

      @Override
      public void connectEnd(Call call, java.net.InetSocketAddress address, java.net.Proxy proxy,
          okhttp3.Protocol protocol) {
        connectEnd.compareAndSet(0, System.nanoTime());
      }

      @Override
      public void requestHeadersEnd(Call call, Request request) {
        sent.set(true);
      }
    });
    if (trust != null) {
      derived.sslSocketFactory(trust.socketFactory(), trust);
    }
    Call call = derived.build().newCall(builder.build());
    long remaining = Math.max(1, ctx.remaining().toMillis());
    call.timeout().timeout(remaining, TimeUnit.MILLISECONDS);
    ctx.onCancel(call::cancel);
    long t0 = System.nanoTime();
    try (Response response = call.execute()) {
      byte[] bytes = new byte[0];
      boolean truncated = false;
      if (!r.method().equals("HEAD")) {
        BufferedSource source = response.body().source();
        Buffer buffer = new Buffer();
        // At most maxBytes + 1 decompressed bytes: a gzip bomb stops at the cap, not at its real size.
        while (buffer.size() <= maxBytes && source.read(buffer, 8192) != -1) {
          // keep reading
        }
        truncated = buffer.size() > maxBytes;
        bytes = buffer.readByteArray(Math.min(buffer.size(), maxBytes));
        buffer.clear();
      }
      Map<String, List<String>> headers = new LinkedHashMap<>();
      for (String name : response.headers().names()) {
        headers.put(name, List.copyOf(response.headers().values(name)));
      }
      long elapsed = (System.nanoTime() - t0) / 1_000_000;
      long connectMs = connectStart.get() == 0 || connectEnd.get() == 0 ? 0
          : (connectEnd.get() - connectStart.get()) / 1_000_000;
      ResponseReader.Answer answer = new ResponseReader.Answer(response.code(),
          ResponseReader.reason(response.code(), response.message()), headers, bytes, truncated, elapsed, 0, null,
          r.url());
      return new Exchange(answer, response.handshake(), addresses.get(), connectMs, response.header("Location"));
    } catch (IOException e) {
      Response asked = repeatAsked.get();
      if (asked != null) {
        // The answer that asked for the repeat is the answer, without its body (OkHttp closed it for the repeat).
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (String name : asked.headers().names()) {
          headers.put(name, List.copyOf(asked.headers().values(name)));
        }
        ResponseReader.Answer answer = new ResponseReader.Answer(asked.code(), ResponseReader.reason(asked.code(),
            asked.message()), headers, new byte[0], false, (System.nanoTime() - t0) / 1_000_000, 0, null, r.url());
        return new Exchange(answer, asked.handshake(), addresses.get(), 0, asked.header("Location"));
      }
      throw new SentException(e, sent.get(), call.isCanceled());
    }
  }

  /**
   * Plain {@code http://} goes only to private addresses the environment allows (the schema's promise for
   * {@code allowPlainHttp}): the name is resolved and judged here and the connection is pinned to what was judged, so
   * a later answer of the resolver can't point plain text at a public address.
   */
  private static List<InetAddress> plainHttpAddresses(GuardedEgress guard, HttpUrl url) throws ConnectorException {
    AddressPolicy policy = guard.policy();
    List<InetAddress> addresses;
    var literal = AddressPolicy.canonicalIp(url.host());
    try {
      addresses = literal.isPresent() ? List.of(literal.get()) : guard.resolver().resolve(url.host());
    } catch (UnknownHostException e) {
      throw ConnectorException.unavailable("Can't resolve " + url.host() + ". Check the host name and the VPN.", e);
    }
    for (InetAddress a : addresses) {
      AddressPolicy.Verdict v = policy.check(url.host(), a, url.port());
      if (v.reason() != AddressPolicy.Reason.ALLOWED_PRIVATE) {
        throw ConnectorException.denied(v.allowed() ? "Plain http:// goes only to a private address allowed in "
            + "Policies; " + url.host() + " resolves to " + a.getHostAddress() + ". Use https." : v.text());
      }
    }
    return List.copyOf(addresses);
  }

  /**
   * Adds the connection's credentials after the agent's headers ({@code header()} replaces, never appends), and the
   * secret headers that apply to the method. Derived values (Basic) are tainted so an echo is masked.
   */
  private static HttpUrl sign(Request.Builder builder, HttpUrl url, HttpSettings s, String method,
      SecretResolver secrets, ExecContext ctx) throws ConnectorException {
    HttpSettings.Auth auth = s.auth();
    HttpUrl out = url;
    switch (auth.type()) {
      case NONE -> {
        // nothing to add
      }
      case BEARER -> {
        try (SecretResolver.Secret token = secrets.secret(auth.field())) {
          header(builder, "Authorization", "Bearer " + token.unsafeString(), "token");
        }
      }
      case API_KEY_HEADER -> {
        try (SecretResolver.Secret key = secrets.secret(auth.field())) {
          header(builder, auth.name(), key.unsafeString(), "API key");
        }
      }
      case API_KEY_QUERY -> {
        try (SecretResolver.Secret key = secrets.secret(auth.field())) {
          out = url.newBuilder().addQueryParameter(auth.name(), key.unsafeString()).build();
        }
      }
      case BASIC -> {
        try (SecretResolver.Secret password = secrets.secret(auth.field())) {
          String encoded = Base64.getEncoder().encodeToString((auth.username() + ":" + password.unsafeString())
              .getBytes(StandardCharsets.UTF_8));
          ctx.taint(encoded);
          builder.header("Authorization", "Basic " + encoded);
        }
      }
    }
    for (HttpSettings.SecretHeader h : s.secretHeaders()) {
      if (h.appliesTo(method)) {
        try (SecretResolver.Secret value = secrets.secret(h.field())) {
          header(builder, h.name(), value.unsafeString(), "value of " + h.name());
        }
      }
    }
    return out;
  }

  /**
   * Sets a header the gateway signs with. A stored value HTTP can't carry (a token pasted with its line break) is
   * named, never quoted: OkHttp's message quotes the value of every header but Authorization and Cookie.
   */
  private static void header(Request.Builder builder, String name, String value, String what)
      throws ConnectorException {
    try {
      builder.header(name, value);
    } catch (IllegalArgumentException e) {
      throw ConnectorException.badInput("The " + what + " of the connection has a line break or a control "
          + "character, which HTTP can't send. Set it again under Credentials.");
    }
  }

  /** An I/O failure with what was known when it happened. */
  static final class SentException extends IOException {
    private static final long serialVersionUID = 1L;
    final boolean sent;
    final boolean canceled;

    SentException(IOException cause, boolean sent, boolean canceled) {
      super(cause.getMessage(), cause);
      this.sent = sent;
      this.canceled = canceled;
    }
  }

  /**
   * The kinds the core counts: a refusal of the guard is DENIED; no connection RESOURCE_UNAVAILABLE; a timeout
   * TIMEOUT; a failure after a request that changes data was sent OUTCOME_UNKNOWN — it may have been applied and is
   * never sent again.
   */
  static ConnectorException translate(IOException e, PlannedRequest r, ExecContext ctx) {
    boolean sent = e instanceof SentException se && se.sent;
    boolean canceled = e instanceof SentException se2 && se2.canceled;
    IOException cause = e instanceof SentException && e.getCause() instanceof IOException io ? io : e;
    String target = r.url().host() + ":" + r.url().port();
    EgressDenial denial = EgressDenial.find(cause);
    if (denial != null) {
      return ConnectorException.denied(denial.verdict().text());
    }
    boolean timedOut = canceled || ctx.cancelled() || cause instanceof SocketTimeoutException
        || (cause instanceof InterruptedIOException && "timeout".equals(cause.getMessage()));
    if (sent && !r.readMethod()) {
      // The server may have acted on it: never sent again (no retries in the guard), the agent checks first.
      return ConnectorException.outcomeUnknown("Outcome unknown: " + (timedOut ? target + " didn't answer in time"
          : "the connection to " + target + " dropped") + " after the " + r.method() + " was sent. Check before "
          + "retrying.", cause);
    }
    if (canceled || ctx.cancelled()) {
      return ConnectorException.timeout("The call to " + target + " ran out of time.");
    }
    if (timedOut) {
      return ConnectorException.timeout(target + " didn't answer in time.");
    }
    if (cause instanceof UnknownHostException) {
      return ConnectorException.unavailable("Can't resolve " + r.url().host() + ". Check the host name and the VPN.",
          cause);
    }
    if (cause instanceof ConnectException) {
      return ConnectorException.unavailable("Can't reach " + target + ": connection refused. Check the VPN or the "
          + "host, then test again.", cause);
    }
    if (cause instanceof SSLPeerUnverifiedException) {
      return ConnectorException.unavailable("TLS with " + target + " failed: " + cause.getMessage(), cause);
    }
    if (cause instanceof SSLHandshakeException) {
      return ConnectorException.unavailable("TLS with " + target + " failed: the certificate isn't trusted or the "
          + "handshake broke off. Test the connection and trust the certificate, then try again.", cause);
    }
    if (cause instanceof SSLException) {
      return ConnectorException.unavailable("TLS with " + target + " failed: " + cause.getMessage(), cause);
    }
    return ConnectorException.unavailable("Can't reach " + target + ": " + cause.getMessage(), cause);
  }

  /** A trust manager that keeps the server's chain for Test connection, then judges it as the connection would. */
  interface ChainCapture extends X509TrustManager {
    javax.net.ssl.SSLSocketFactory socketFactory();

    List<java.security.cert.X509Certificate> chain();
  }

  static List<String> addressTexts(List<InetAddress> addresses) {
    List<String> out = new ArrayList<>();
    addresses.forEach(a -> out.add(a.getHostAddress()));
    return out;
  }
}
