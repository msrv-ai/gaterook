package ai.msrv.gaterook.connectors.kubernetes;

import io.fabric8.mockwebserver.MockWebServer;
import io.fabric8.mockwebserver.http.Dispatcher;
import io.fabric8.mockwebserver.http.MockResponse;
import io.fabric8.mockwebserver.http.RecordedRequest;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * An API server stand-in over HTTPS on the mock web server of {@code kubernetes-server-mock}: routes by method and
 * path, everything else answers a 404 {@code Status} like the real one. Its self-signed certificate is the CA of the
 * connection, so TLS is verified as in production.
 */
final class FakeApiServer implements AutoCloseable {

  /** One request as it arrived: the path without the query, the query decoded, headers and body. */
  record Request(String method, String path, Map<String, String> query, Map<String, List<String>> headers,
      String body) {

    String header(String name) {
      for (Map.Entry<String, List<String>> e : headers.entrySet()) {
        if (e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) {
          return e.getValue().get(0);
        }
      }
      return null;
    }
  }

  private final MockWebServer server = new MockWebServer();
  private final Map<String, Function<Request, MockResponse>> routes = new ConcurrentHashMap<>();
  final List<Request> requests = new CopyOnWriteArrayList<>();
  final String caPem;

  FakeApiServer() throws IOException {
    server.useHttps();
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest r) {
        Request req = request(r);
        requests.add(req);
        Function<Request, MockResponse> route = routes.get(req.method() + " " + req.path());
        if (route == null) {
          return status(404, "NotFound", "the server could not find the requested resource");
        }
        return route.apply(req);
      }
    });
    server.start();
    caPem = Files.readString(Path.of(server.getSelfSignedCertificate().certificatePath()));
  }

  String url() {
    return "https://localhost:" + server.getPort();
  }

  FakeApiServer on(String method, String path, Function<Request, MockResponse> answer) {
    routes.put(method + " " + path, answer);
    return this;
  }

  FakeApiServer json(String method, String path, String body) {
    return on(method, path, r -> ok(body));
  }

  List<Request> requests(String method, String path) {
    return requests.stream().filter(r -> r.method().equals(method) && r.path().equals(path)).toList();
  }

  static MockResponse ok(String json) {
    return new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(json);
  }

  static MockResponse status(int code, String reason, String message) {
    String body = "{\"kind\":\"Status\",\"apiVersion\":\"v1\",\"status\":\"Failure\",\"reason\":\"" + reason
        + "\",\"message\":" + K8sApi.JSON.writeValueAsString(message) + ",\"code\":" + code + "}";
    return new MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body);
  }

  /** A connection of this server with a bearer token, the server's certificate as its CA. */
  String tokenConfig(String namespace) {
    return "{\"auth\":\"token\",\"server\":\"" + url() + "\",\"namespace\":\"" + namespace + "\",\"caCert\":"
        + K8sApi.JSON.writeValueAsString(caPem) + "}";
  }

  private static Request request(RecordedRequest r) {
    String full = r.getPath();
    int q = full.indexOf('?');
    String path = q < 0 ? full : full.substring(0, q);
    Map<String, String> query = new LinkedHashMap<>();
    if (q >= 0) {
      for (String p : full.substring(q + 1).split("&")) {
        int eq = p.indexOf('=');
        String k = URLDecoder.decode(eq < 0 ? p : p.substring(0, eq), StandardCharsets.UTF_8);
        String v = eq < 0 ? "" : URLDecoder.decode(p.substring(eq + 1), StandardCharsets.UTF_8);
        query.put(k, v);
      }
    }
    String body = r.getBody() == null ? "" : r.getUtf8Body();
    return new Request(r.getMethod(), path, query, r.getHeaders().toMultimap(), body);
  }

  @Override
  public void close() {
    server.shutdown();
  }
}
