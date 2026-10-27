package ai.msrv.gaterook.core.tunnel.internal;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * How the self-test reaches the public URL from this computer: DNS and HTTPS through the system's proxy, never
 * following a redirect (claude.ai would lose its {@code Authorization} on one to another host). Tests of the core
 * register their own bean to answer for a hostname that only exists in the test.
 */
public interface TunnelProbe {

  /** A response: status, headers by lower-case name, body (at most 64 KiB) and round trip. */
  record Answer(int status, Map<String, List<String>> headers, String body, long millis) {

    public String header(String name) {
      List<String> v = headers.get(name.toLowerCase(Locale.ROOT));
      return v == null || v.isEmpty() ? null : v.getFirst();
    }
  }

  List<InetAddress> resolve(String host) throws UnknownHostException;

  Answer send(String method, URI uri, Map<String, String> headers, String body) throws IOException,
      InterruptedException;

  /**
   * The real probe: {@code java.net.http} with the system proxy, 10 s per request — for the whole answer: the
   * client's request timeout ends with the headers, and "Check public URL" must not hang on a body that stalls.
   */
  final class Http implements TunnelProbe {

    static final Duration DEADLINE = Duration.ofSeconds(10);

    private final Duration deadline;
    private final HttpClient client;

    public Http() {
      this(DEADLINE);
    }

    Http(Duration deadline) {
      this.deadline = deadline;
      this.client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
          .proxy(ProxySelector.getDefault()).connectTimeout(deadline).build();
    }

    @Override
    public List<InetAddress> resolve(String host) throws UnknownHostException {
      return List.of(InetAddress.getAllByName(host));
    }

    @Override
    public Answer send(String method, URI uri, Map<String, String> headers, String body) throws IOException,
        InterruptedException {
      HttpRequest.Builder rq = HttpRequest.newBuilder(uri).timeout(deadline)
          .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofString(body));
      headers.forEach(rq::header);
      long start = System.nanoTime();
      HttpResponse<byte[]> rs = TunnelAgent.within(client.sendAsync(rq.build(),
          HttpResponse.BodyHandlers.ofByteArray()), deadline, uri.getHost());
      long millis = Duration.ofNanos(System.nanoTime() - start).toMillis();
      Map<String, List<String>> h = new TreeMap<>();
      rs.headers().map().forEach((k, v) -> h.put(k.toLowerCase(Locale.ROOT), v));
      byte[] b = rs.body();
      String text = new String(b, 0, Math.min(b.length, 64 * 1024), java.nio.charset.StandardCharsets.UTF_8);
      return new Answer(rs.statusCode(), h, text, millis);
    }
  }
}
