package ai.msrv.gaterook.connectors.prometheus;

import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;

/**
 * Prometheus' HTTP API on this computer: answers by path ({@code /api/v1/query}, the longest matching prefix wins),
 * every request recorded. An unknown path answers 404 as Prometheus does.
 */
final class FakePrometheus implements AutoCloseable {

  final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
  private final Map<String, Function<RecordedRequest, MockResponse>> routes = new ConcurrentHashMap<>();
  private final MockWebServer server = new MockWebServer();

  FakePrometheus() throws IOException {
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        requests.add(request);
        String path = request.getUrl().encodedPath();
        String best = null;
        for (String prefix : routes.keySet()) {
          if (path.startsWith(prefix) && (best == null || prefix.length() > best.length())) {
            best = prefix;
          }
        }
        if (best == null) {
          return new MockResponse.Builder().code(404).addHeader("Content-Type", "text/plain").body("404 page not "
              + "found\n").build();
        }
        return routes.get(best).apply(request);
      }
    });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
  }

  /** {@code http://127.0.0.1:<port>}: plain http to this computer. */
  String url() {
    String url = server.url("/").toString();
    return url.substring(0, url.length() - 1);
  }

  int port() {
    return server.getPort();
  }

  FakePrometheus on(String path, Function<RecordedRequest, MockResponse> answer) {
    routes.put(path, answer);
    return this;
  }

  FakePrometheus on(String path, String body) {
    return on(path, r -> json(200, body));
  }

  /** The requests to paths that start with {@code path}. */
  List<RecordedRequest> to(String path) {
    return requests.stream().filter(r -> r.getUrl().encodedPath().startsWith(path)).toList();
  }

  static MockResponse json(int code, String body) {
    return new MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build();
  }

  static String param(RecordedRequest r, String name) {
    List<String> values = params(r, name);
    return values.isEmpty() ? null : values.getFirst();
  }

  static List<String> params(RecordedRequest r, String name) {
    return r.getUrl().queryParameterValues(name).stream().map(v -> v == null ? "" : v).toList();
  }

  /** {@code {"status":"success","data":…}}. */
  static String success(String data) {
    return "{\"status\":\"success\",\"data\":" + data + "}";
  }

  /** An instant vector of {@code series}, each {@code [labels JSON, value]}, at {@code time} seconds. */
  static String vector(String time, String... labelsAndValues) {
    StringBuilder result = new StringBuilder();
    for (int i = 0; i + 1 < labelsAndValues.length; i += 2) {
      result.append(result.isEmpty() ? "" : ",").append("{\"metric\":").append(labelsAndValues[i])
          .append(",\"value\":[").append(time).append(",\"").append(labelsAndValues[i + 1]).append("\"]}");
    }
    return success("{\"resultType\":\"vector\",\"result\":[" + result + "]}");
  }

  /** A matrix of one series per labels JSON, each with {@code points} values {@code 0, 1, …} every {@code step}. */
  static String matrix(long start, long step, int points, String... labels) {
    StringBuilder result = new StringBuilder();
    for (String l : labels) {
      StringBuilder values = new StringBuilder();
      for (int i = 0; i < points; i++) {
        values.append(values.isEmpty() ? "" : ",").append('[').append(start + i * step).append(",\"").append(i)
            .append("\"]");
      }
      result.append(result.isEmpty() ? "" : ",").append("{\"metric\":").append(l).append(",\"values\":[")
          .append(values).append("]}");
    }
    return success("{\"resultType\":\"matrix\",\"result\":[" + result + "]}");
  }

  static String error(String type, String message) {
    return "{\"status\":\"error\",\"errorType\":\"" + type + "\",\"error\":\"" + message + "\"}";
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
