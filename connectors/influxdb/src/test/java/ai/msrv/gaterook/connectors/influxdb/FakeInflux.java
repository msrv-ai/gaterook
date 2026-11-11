package ai.msrv.gaterook.connectors.influxdb;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * An InfluxDB on this computer: answers by method and path ({@code POST /api/v3/query_sql}, {@code POST /query}), the
 * longest matching prefix winning, and records every request. Unknown routes answer 404 as InfluxDB does.
 */
final class FakeInflux implements AutoCloseable {

  static final JsonMapper JSON = JsonMapper.builder().build();

  final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
  private final Map<String, Function<RecordedRequest, MockResponse>> routes = new ConcurrentHashMap<>();
  private final MockWebServer server = new MockWebServer();

  FakeInflux() throws IOException {
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        requests.add(request);
        String key = request.getMethod() + " " + request.getUrl().encodedPath();
        String best = null;
        for (String prefix : routes.keySet()) {
          if (key.startsWith(prefix) && (best == null || prefix.length() > best.length())) {
            best = prefix;
          }
        }
        if (best == null) {
          return json(404, "{\"error\":\"no fake for " + key + "\"}");
        }
        return routes.get(best).apply(request);
      }
    });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
  }

  /** {@code http://127.0.0.1:<port>}: plain http goes to this computer. */
  String url() {
    return "http://127.0.0.1:" + server.getPort();
  }

  FakeInflux on(String prefix, Function<RecordedRequest, MockResponse> answer) {
    routes.put(prefix, answer);
    return this;
  }

  FakeInflux on(String prefix, int code, String body) {
    return on(prefix, r -> json(code, body));
  }

  /** The requests whose {@code METHOD /path} starts with {@code prefix}. */
  List<RecordedRequest> to(String prefix) {
    return requests.stream().filter(r -> (r.getMethod() + " " + r.getUrl().encodedPath()).startsWith(prefix)).toList();
  }

  static MockResponse json(int code, String body) {
    return new MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build();
  }

  static MockResponse text(int code, String body) {
    return new MockResponse.Builder().code(code).addHeader("Content-Type", "text/plain").body(body).build();
  }

  /** {@code /ping} with InfluxDB's version headers. */
  static MockResponse ping(String version, String build) {
    MockResponse.Builder b = new MockResponse.Builder().code(204);
    if (version != null) {
      b.addHeader("X-Influxdb-Version", version);
    }
    if (build != null) {
      b.addHeader("X-Influxdb-Build", build);
    }
    return b.build();
  }

  static String body(RecordedRequest r) {
    return r.getBody() == null ? "" : r.getBody().utf8();
  }

  static JsonNode jsonBody(RecordedRequest r) {
    return JSON.readTree(body(r));
  }

  /** An {@code application/x-www-form-urlencoded} body as its fields. */
  static Map<String, String> form(RecordedRequest r) {
    Map<String, String> out = new LinkedHashMap<>();
    for (String pair : body(r).split("&")) {
      int eq = pair.indexOf('=');
      if (eq > 0) {
        out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
            URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
      }
    }
    return out;
  }

  static String param(RecordedRequest r, String name) {
    return r.getUrl().queryParameter(name);
  }

  private boolean closed;

  @Override
  public synchronized void close() {
    if (!closed) {
      closed = true;
      server.close();
    }
  }
}
