package ai.msrv.gaterook.core.notifications.internal;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * The module's one HTTP client ({@code java.net.http}: no dependency, works in the native image, does WebSocket):
 * HTTP/1.1, no redirects (an API answer is never followed somewhere else with the token), 10 s to connect, the
 * system's proxy settings. Its callbacks run on virtual threads. {@code jdk.httpclient.HttpClient.log} stays off: the
 * Telegram token is part of the URL.
 */
@Component
public class NotifyHttp implements DisposableBean {

  private final ExecutorService executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual()
      .name("notify-http-", 0).factory());
  private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
      .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).executor(executor).build();

  public HttpClient client() {
    return client;
  }

  @Override
  public void destroy() {
    client.shutdownNow();
    executor.shutdownNow();
  }
}
