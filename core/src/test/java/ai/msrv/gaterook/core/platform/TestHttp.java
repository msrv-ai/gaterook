package ai.msrv.gaterook.core.platform;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP for tests of the local listener: signed admin requests as Electron's main process sends them, and raw requests
 * with headers {@link HttpClient} refuses to set ({@code Host}).
 */
public final class TestHttp {

  private static final SecureRandom RANDOM = new SecureRandom();

  private final int port;
  private final LaunchSecret secret;
  private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  public TestHttp(int port, LaunchSecret secret) {
    this.port = port;
    this.secret = secret;
  }

  public static String nonce() {
    byte[] bytes = new byte[16];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /** The {@code X-Gateway-Auth} value for a request now. */
  public String sign(String method, String target) {
    return secret.sign(method, target, System.currentTimeMillis(), nonce());
  }

  public HttpRequest.Builder request(String target) {
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + target)).timeout(Duration.ofSeconds(30));
  }

  /** A signed GET. */
  public HttpResponse<String> signedGet(String target) {
    return send(request(target).header(LaunchSecret.HEADER, sign("GET", target)).GET().build());
  }

  /** A signed request with a JSON body ({@code null}: no body). */
  public HttpResponse<String> signed(String method, String target, String json) {
    HttpRequest.Builder builder = request(target).header(LaunchSecret.HEADER, sign(method, target));
    if (json == null) {
      builder.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json));
    }
    return send(builder.build());
  }

  public HttpResponse<String> send(HttpRequest request) {
    try {
      return client.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  public HttpClient client() {
    return client;
  }

  /** A raw HTTP/1.1 request; returns the status code and headers (lower-case names) and body. */
  public Raw raw(String method, String target, Map<String, String> headers) {
    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(10_000);
      StringBuilder request = new StringBuilder(method + " " + target + " HTTP/1.1\r\n");
      Map<String, String> all = new LinkedHashMap<>();
      all.put("Host", "127.0.0.1:" + port);
      all.putAll(headers);
      all.put("Connection", "close");
      all.forEach((k, v) -> request.append(k).append(": ").append(v).append("\r\n"));
      request.append("\r\n");
      OutputStream out = socket.getOutputStream();
      out.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
      out.flush();
      InputStream in = socket.getInputStream();
      String response = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
      int headerEnd = response.indexOf("\r\n\r\n");
      String head = headerEnd < 0 ? response : response.substring(0, headerEnd);
      String[] lines = head.split("\r\n");
      int status = Integer.parseInt(lines[0].split(" ")[1]);
      Map<String, String> responseHeaders = new LinkedHashMap<>();
      for (int i = 1; i < lines.length; i++) {
        int colon = lines[i].indexOf(':');
        responseHeaders.put(lines[i].substring(0, colon).trim().toLowerCase(), lines[i].substring(colon + 1).trim());
      }
      return new Raw(status, responseHeaders, headerEnd < 0 ? "" : response.substring(headerEnd + 4));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** A raw response. */
  public record Raw(int status, Map<String, String> headers, String body) {}
}
