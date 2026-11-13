package com.gaterook.core.mcpservers.internal.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * The redirect URI on this computer: one GET with the code ends the sign-in and the listener; another path, method or
 * Host is refused without reaching the handler; the page leaks the address to nobody.
 */
class LoopbackCallbackTest {

  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  @Test
  void theBrowsersRedirectReachesTheHandlerOnceAndTheListenerCloses() throws Exception {
    List<Map<String, String>> calls = new CopyOnWriteArrayList<>();
    try (LoopbackCallback cb = LoopbackCallback.open("127.0.0.1", 0, "/callback", q -> {
      calls.add(q);
      return new LoopbackCallback.Page(200, "Signed in to linear-mcp", "<b>close</b> this tab", true);
    })) {
      assertThat(cb.redirectUri().toString()).matches("http://127\\.0\\.0\\.1:\\d+/callback");
      HttpResponse<String> r = get(cb.redirectUri() + "?code=c%2F1&state=s1&iss=https%3A%2F%2Fas");
      assertThat(r.statusCode()).isEqualTo(200);
      assertThat(r.body()).contains("Signed in to linear-mcp").contains("&lt;b&gt;close&lt;/b&gt;");
      assertThat(r.headers().firstValue("Referrer-Policy")).hasValue("no-referrer");
      assertThat(r.headers().firstValue("Cache-Control")).hasValue("no-store");
      assertThat(r.headers().firstValue("Content-Security-Policy").orElseThrow()).startsWith("default-src 'none'");
      assertThat(calls).singleElement().satisfies(q -> {
        assertThat(q).containsEntry("code", "c/1").containsEntry("state", "s1").containsEntry("iss", "https://as");
      });
      waitClosed(cb);
      assertThat(cb.closed()).isTrue();
    }
  }

  @Test
  void otherPathsMethodsAndHostsDontReachTheHandler() throws Exception {
    List<Map<String, String>> calls = new CopyOnWriteArrayList<>();
    try (LoopbackCallback cb = LoopbackCallback.open("localhost", 0, "/oauth/callback", q -> {
      calls.add(q);
      return new LoopbackCallback.Page(400, "Not this sign-in", "no", false);
    })) {
      assertThat(cb.redirectUri().getHost()).isEqualTo("localhost");
      int port = cb.port();
      assertThat(get("http://127.0.0.1:" + port + "/favicon.ico").statusCode()).isEqualTo(404);
      assertThat(raw(port, "POST /oauth/callback?code=x HTTP/1.1\r\nHost: localhost:" + port + "\r\n"
          + "Content-Length: 0\r\n\r\n")).startsWith("HTTP/1.1 405");
      assertThat(raw(port, "GET /oauth/callback?code=x HTTP/1.1\r\nHost: evil.example:" + port + "\r\n\r\n"))
          .startsWith("HTTP/1.1 421");
      assertThat(calls).isEmpty();
      // A refusal that isn't final keeps listening: the right redirect can still come.
      assertThat(get("http://localhost:" + port + "/oauth/callback?state=wrong").statusCode()).isEqualTo(400);
      assertThat(calls).hasSize(1);
      assertThat(cb.closed()).isFalse();
    }
  }

  @Test
  void aTakenPortIsReported() throws Exception {
    try (LoopbackCallback first = LoopbackCallback.open("127.0.0.1", 0, "/callback", q -> null)) {
      try {
        LoopbackCallback.open("127.0.0.1", first.port(), "/callback", q -> null).close();
        org.junit.jupiter.api.Assertions.fail("The port was free");
      } catch (IOException expected) {
        // In use.
      }
    }
  }

  private static HttpResponse<String> get(String url) throws Exception {
    return HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static String raw(int port, String request) throws IOException {
    try (Socket s = new Socket("127.0.0.1", port)) {
      s.setSoTimeout(5000);
      OutputStream out = s.getOutputStream();
      out.write(request.getBytes(StandardCharsets.ISO_8859_1));
      out.flush();
      InputStream in = s.getInputStream();
      return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
    }
  }

  private static void waitClosed(LoopbackCallback cb) throws InterruptedException {
    for (int i = 0; i < 50 && !cb.closed(); i++) {
      Thread.sleep(20);
    }
  }
}
