package ai.msrv.gaterook.core.tunnel.internal;

import ai.msrv.gaterook.core.tunnel.TunnelProvider;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A provider's agent (ARCHITECTURE.md §7.3): starts its process towards {@code http://127.0.0.1:<publicPort>} and
 * returns once the public hostname is known and the agent says it is connected to its edge — the URL is published
 * only then. Each agent reads its own state from its local HTTP interface (cloudflared's metrics server, ngrok's agent
 * API) on a loopback port of its own, without any proxy.
 */
public interface TunnelAgent {

  /** What a start needs; {@code secret} is the provider's token (named tunnel, ngrok), zeroed by the caller after. */
  record StartRequest(TunnelProvider provider, Path binary, int publicPort, String hostname, char[] secret,
      Path runDir, Path logsDir) {

    @Override
    public String toString() {
      return "StartRequest[" + provider + ", " + binary + ", publicPort=" + publicPort + ", hostname=" + hostname
          + ", secret=" + (secret == null ? "none" : "‹redacted›") + "]";
    }

    /** Clears the secret: the agent has it in its environment now. */
    void wipe() {
      if (secret != null) {
        Arrays.fill(secret, '\0');
      }
    }
  }

  /** A running tunnel: its public hostname, the process and what it said about itself. */
  record Started(String host, AgentProcess process, String detail) {}

  Started start(StartRequest request) throws TunnelException, InterruptedException;

  /** A free loopback port for the agent's own HTTP interface. */
  static int freeLoopbackPort() throws TunnelException {
    try (ServerSocket s = new ServerSocket()) {
      s.setReuseAddress(false);
      s.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0));
      return s.getLocalPort();
    } catch (IOException e) {
      throw new TunnelException("No free loopback port for the tunnel agent: " + e.getMessage());
    }
  }

  /** The agent's local interface, never through a proxy. */
  HttpClient LOOPBACK = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY)
      .connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();

  /** GET {@code http://127.0.0.1:<port><path>}: status and body, or empty while nothing answers yet. */
  static Optional<HttpResponse<String>> localGet(int port, String path) throws InterruptedException {
    return localGet(port, path, Duration.ofSeconds(3));
  }

  /** {@link #localGet(int, String)} with the whole answer due within {@code deadline}. */
  static Optional<HttpResponse<String>> localGet(int port, String path, Duration deadline)
      throws InterruptedException {
    try {
      return Optional.of(within(LOOPBACK.sendAsync(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
          + path)).timeout(deadline).GET().build(), HttpResponse.BodyHandlers.ofString()), deadline, "the agent"));
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  /**
   * The answer of {@code exchange} within {@code deadline}, its body included — {@code java.net.http}'s request
   * timeout ends with the headers — or an {@link IOException} (the exchange is cancelled on the way out).
   */
  static <T> HttpResponse<T> within(CompletableFuture<HttpResponse<T>> exchange, Duration deadline, String who)
      throws IOException, InterruptedException {
    try {
      return exchange.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      exchange.cancel(true);
      throw new HttpTimeoutException(who + " didn't answer within " + (deadline.toMillis() % 1000 == 0
          ? deadline.toSeconds() + " s" : deadline.toMillis() + " ms"));
    } catch (InterruptedException e) {
      exchange.cancel(true);
      throw e;
    } catch (ExecutionException e) {
      Throwable cause = e.getCause() == null ? e : e.getCause();
      if (cause instanceof IOException io) {
        throw io;
      }
      throw new IOException(cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage(),
          cause);
    }
  }

  /** The most telling error line of an agent's log, cut to one sentence's length. */
  static String lastError(List<String> tail) {
    for (int i = tail.size() - 1; i >= 0; i--) {
      String line = tail.get(i);
      String l = line.toLowerCase(java.util.Locale.ROOT);
      if (l.contains(" err ") || l.contains("\"lvl\":\"eror\"") || l.contains("\"lvl\":\"crit\"") || l.contains("error")
          || l.contains("failed")) {
        String s = line.strip();
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
      }
    }
    return null;
  }
}
