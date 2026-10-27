package ai.msrv.gaterook.connectors.logs;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.DefaultSchemePortResolver;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.impl.routing.DefaultRoutePlanner;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.client5.http.ssl.DefaultHostnameVerifier;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;

/**
 * OpenSearch REST transport with no retries, redirects, implicit authentication or method/body rewriting. A request
 * may be an approved operation even when its method is GET, and a lost response must never trigger another request.
 */
final class OpenSearchHttp {

  private OpenSearchHttp() {}

  record BoundedResponse(int status, String body, String contentType, boolean truncated) {}

  static BoundedResponse send(HttpRequest signedRequest, String body, ExecContext ctx, long maxBytes,
      SSLContext ssl) throws ConnectorException {
    long millis = Math.min(Http.MAX_REQUEST.toMillis(), ctx.remaining().minus(Http.MARGIN).toMillis());
    if (millis <= 0 || ctx.cancelled()) {
      throw ConnectorException.timeout("The OpenSearch request deadline has expired.");
    }
    HttpUriRequestBase request = new HttpUriRequestBase(signedRequest.method(), signedRequest.uri());
    signedRequest.headers().map().forEach((name, values) -> values.forEach(value -> request.addHeader(name, value)));
    if (body != null) {
      // The already-signed Content-Type header is retained; this entity adds no charset or re-encoding.
      request.setEntity(new ByteArrayEntity(body.getBytes(StandardCharsets.UTF_8), null));
    } else if (Set.of("POST", "PUT", "PATCH").contains(signedRequest.method())) {
      request.setEntity(new ByteArrayEntity(new byte[0], null));
    }
    Timeout timeout = Timeout.ofMilliseconds(millis);
    request.setConfig(RequestConfig.custom().setAuthenticationEnabled(false).setRedirectsEnabled(false)
        .setContentCompressionEnabled(false).setExpectContinueEnabled(false).setProtocolUpgradeEnabled(false)
        .setHardCancellationEnabled(true).setConnectionRequestTimeout(timeout).setResponseTimeout(timeout).build());
    ctx.onCancel(request::cancel);
    AtomicBoolean expired = new AtomicBoolean();
    // A per-call virtual watchdog bounds a slow trickle as well as a stalled read. It owns no thread pool and is
    // interrupted when the call ends; cancellation closes the active socket rather than waiting for entity drain.
    Thread deadline = Thread.ofVirtual().name("opensearch-request-deadline").start(() -> {
      try {
        Thread.sleep(millis);
        expired.set(true);
        request.cancel();
      } catch (InterruptedException ignored) {
        Thread.currentThread().interrupt();
      }
    });
    CloseableHttpClient client = null;
    CloseableHttpResponse response = null;
    PoolingHttpClientConnectionManager manager = null;
    try {
      var tls = ClientTlsStrategyBuilder.create().setSslContext(ssl)
          .setHostnameVerifier(new DefaultHostnameVerifier()).buildClassic();
      manager = PoolingHttpClientConnectionManagerBuilder.create().setTlsSocketStrategy(tls)
          .setMaxConnTotal(1).setMaxConnPerRoute(1)
          .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(timeout).setSocketTimeout(timeout)
              .build()).build();
      client = HttpClients.custom().setConnectionManager(manager)
          .setRoutePlanner(new DefaultRoutePlanner(DefaultSchemePortResolver.INSTANCE))
          .disableAutomaticRetries().disableRedirectHandling().disableCookieManagement().disableAuthCaching()
          .disableContentCompression().disableDefaultUserAgent().build();
      response = CloseableHttpResponse.adapt(client.executeOpen(null, request, HttpClientContext.create()));
      int cap = (int) Math.max(0, Math.min(Http.MAX_BODY, maxBytes));
      ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(cap, 8192));
      boolean truncated = false;
      if (response.getEntity() != null) {
        InputStream input = response.getEntity().getContent();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer, 0, Math.min(buffer.length, cap - bytes.size() + 1))) != -1) {
          int keep = Math.min(count, cap - bytes.size());
          bytes.write(buffer, 0, keep);
          if (count > keep) {
            truncated = true;
            break;
          }
        }
      }
      if (expired.get() || ctx.cancelled()) {
        throw ConnectorException.timeout("The OpenSearch request was cancelled or exceeded its deadline. Check "
            + "server state before retrying a change.");
      }
      Header contentType = response.getFirstHeader("Content-Type");
      return new BoundedResponse(response.getCode(), bytes.toString(StandardCharsets.UTF_8),
          contentType == null ? "" : contentType.getValue(), truncated);
    } catch (IOException e) {
      if (expired.get() || ctx.cancelled() || e instanceof InterruptedIOException) {
        throw ConnectorException.timeout("OpenSearch did not finish its response before the deadline or was "
            + "cancelled. The operation may still be running; inspect server state before retrying a change.");
      }
      if (ctx.approved() && !(e instanceof ConnectException) && !(e instanceof UnknownHostException)
          && !(e instanceof SSLHandshakeException)) {
        throw ConnectorException.outcomeUnknown("The OpenSearch connection failed after dispatch; the approved "
            + "operation may have been applied. Inspect server state before retrying.", e);
      }
      throw ConnectorException.unavailable("The OpenSearch request failed: "
          + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), e);
    } finally {
      deadline.interrupt();
      request.cancel();
      if (response != null) {
        response.close(CloseMode.IMMEDIATE);
      }
      if (client != null) {
        client.close(CloseMode.IMMEDIATE);
      }
      if (manager != null) {
        manager.close(CloseMode.IMMEDIATE);
      }
    }
  }
}
