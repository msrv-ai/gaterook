package com.gaterook.connectors.cloudflare;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
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
 * A fake of Cloudflare's v4 API on MockWebServer, under {@code /client/v4} as the real one. Every request must carry
 * the test token as a bearer token and ask for JSON, else it is answered 401 as Cloudflare does and the problem
 * recorded. Routes answer by method and encoded path; the rest is a 404 with Cloudflare's error envelope.
 */
final class FakeCloudflare implements AutoCloseable {

  static final String TOKEN = "test-only-cloudflare-token-0001-abcdef";
  static final String PREFIX = "/client/v4";
  static final JsonMapper JSON = JsonMapper.builder().build();

  final MockWebServer server = new MockWebServer();
  final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
  final List<String> problems = new CopyOnWriteArrayList<>();
  private final Map<String, Function<RecordedRequest, MockResponse>> routes = new ConcurrentHashMap<>();

  FakeCloudflare() {
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        requests.add(request);
        String auth = request.getHeaders().get("Authorization");
        if (!("Bearer " + TOKEN).equals(auth)) {
          problems.add("Authorization " + auth);
          return error(401, 1000, "Invalid API Token");
        }
        if (!"application/json".equals(request.getHeaders().get("Accept"))) {
          problems.add("Accept " + request.getHeaders().get("Accept"));
        }
        String key = request.getMethod() + " " + request.getUrl().encodedPath();
        Function<RecordedRequest, MockResponse> route = routes.get(key);
        if (route == null) {
          return error(404, 7003, "Could not route to " + request.getUrl().encodedPath()
              + ", perhaps your object identifier is invalid?");
        }
        return route.apply(request);
      }
    });
    try {
      server.start(InetAddress.getByName("127.0.0.1"), 0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The base the connector uses instead of https://api.cloudflare.com/client/v4. */
  URI base() {
    return server.url(PREFIX).uri();
  }

  /** A route; {@code path} is under {@code /client/v4}, encoded as sent. */
  FakeCloudflare on(String method, String path, Function<RecordedRequest, MockResponse> answer) {
    routes.put(method + " " + PREFIX + path, answer);
    return this;
  }

  FakeCloudflare ok(String method, String path, String result) {
    return on(method, path, r -> envelope(200, result, null));
  }

  FakeCloudflare ok(String method, String path, String result, String info) {
    return on(method, path, r -> envelope(200, result, info));
  }

  FakeCloudflare fail(String method, String path, int status, int code, String message) {
    return on(method, path, r -> error(status, code, message));
  }

  /** The zones of the fake: by ID and, through {@code GET /zones?name=}, by name. */
  FakeCloudflare zones(String account, String... idAndName) {
    StringBuilder all = new StringBuilder("[");
    for (int i = 0; i < idAndName.length; i += 2) {
      String id = idAndName[i];
      String name = idAndName[i + 1];
      String zone = zone(id, name, account);
      ok("GET", "/zones/" + id, zone);
      all.append(i == 0 ? "" : ",").append(zone);
    }
    all.append(']');
    JsonNode list = JSON.readTree(all.toString());
    return on("GET", "/zones", r -> {
      String name = r.getUrl().queryParameter("name");
      List<JsonNode> out = new ArrayList<>();
      for (JsonNode z : list) {
        if (name == null || name.equalsIgnoreCase(z.path("name").asString())) {
          out.add(z);
        }
      }
      return envelope(200, JSON.writeValueAsString(out), "{\"page\":1,\"per_page\":50,\"count\":" + out.size()
          + ",\"total_count\":" + out.size() + ",\"total_pages\":1}");
    });
  }

  static String zone(String id, String name, String account) {
    return "{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"status\":\"active\",\"paused\":false,\"type\":\"full\","
        + "\"development_mode\":0,\"name_servers\":[\"ada.ns.cloudflare.com\",\"bob.ns.cloudflare.com\"],"
        + "\"plan\":{\"name\":\"Pro Website\"},\"account\":{\"id\":\"" + account + "\",\"name\":\"Acme\"},"
        + "\"owner\":{\"id\":\"x\",\"type\":\"user\",\"email\":\"owner@example.com\"},"
        + "\"created_on\":\"2024-01-01T00:00:00Z\",\"modified_on\":\"2026-11-01T00:00:00Z\"}";
  }

  static MockResponse envelope(int status, String result, String info) {
    String body = "{\"success\":true,\"errors\":[],\"messages\":[],\"result\":" + result
        + (info == null ? "" : ",\"result_info\":" + info) + "}";
    return new MockResponse.Builder().code(status).addHeader("Content-Type", "application/json").body(body).build();
  }

  static MockResponse error(int status, int code, String message) {
    String body = "{\"success\":false,\"errors\":[{\"code\":" + code + ",\"message\":\"" + message
        + "\"}],\"messages\":[],\"result\":null}";
    return new MockResponse.Builder().code(status).addHeader("Content-Type", "application/json").body(body).build();
  }

  /** A JSON body as given: GraphQL's answers have no v4 envelope. */
  static MockResponse body(int status, String json) {
    return new MockResponse.Builder().code(status).addHeader("Content-Type", "application/json").body(json).build();
  }

  static MockResponse raw(String contentType, byte[] bytes) {
    return new MockResponse.Builder().code(200).addHeader("Content-Type", contentType)
        .body(new okio.Buffer().write(bytes)).build();
  }

  /** The requests sent to {@code method path}, path under {@code /client/v4}. */
  List<RecordedRequest> sent(String method, String path) {
    return requests.stream().filter(r -> r.getMethod().equals(method)
        && r.getUrl().encodedPath().equals(PREFIX + path)).toList();
  }

  /** The requests that weren't reads. */
  List<RecordedRequest> changes() {
    return requests.stream().filter(r -> !r.getMethod().equals("GET")
        && !r.getUrl().encodedPath().endsWith("/telemetry/query")
        && !r.getUrl().encodedPath().endsWith("/graphql")).toList();
  }

  static JsonNode body(RecordedRequest r) {
    return JSON.readTree(r.getBody() == null ? "" : r.getBody().utf8());
  }

  @Override
  public void close() {
    server.close();
  }
}
