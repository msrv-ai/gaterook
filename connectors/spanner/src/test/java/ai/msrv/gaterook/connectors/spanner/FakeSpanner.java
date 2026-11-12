package ai.msrv.gaterook.connectors.spanner;

import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudEndpoints;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
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
 * Google on this computer: a token server for the sign-in, IAM's {@code generateAccessToken} for an impersonated
 * service account, and Spanner's REST API by method and path ({@code POST /v1/projects/…/sessions}). The test endpoint
 * {@code gcp.spanner} points here. Sessions are answered as {@link #SESSION}; queries by {@link #onSql}.
 */
final class FakeSpanner implements AutoCloseable {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final String USER_TOKEN = "ya29.user-sign-in-cnry";
  static final String DATABASE = "projects/acme-prod/instances/orders-prod/databases/orders";
  static final String SESSION = DATABASE + "/sessions/s-1";

  final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
  private final Map<String, Function<RecordedRequest, MockResponse>> routes = new ConcurrentHashMap<>();
  /** Answers by {@code METHOD /path} from the API's root ({@code GET /v1/projects/acme-prod/instances}). */
  private final Map<String, Function<RecordedRequest, MockResponse>> exact = new ConcurrentHashMap<>();
  /** Answers of executeSql and executeStreamingSql, by a text the statement contains (the longest match wins). */
  private final Map<String, Function<RecordedRequest, MockResponse>> sql = new ConcurrentHashMap<>();
  private final MockWebServer server = new MockWebServer();
  final CloudCredentials cloud;
  volatile boolean postgres;

  FakeSpanner() throws IOException {
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        requests.add(request);
        String path = request.getUrl().encodedPath();
        if (path.equals("/token")) {
          return json(200, "{\"access_token\":\"" + USER_TOKEN + "\",\"expires_in\":3600,\"token_type\":\"Bearer\"}");
        }
        if (path.endsWith(":generateAccessToken")) {
          return json(200, "{\"accessToken\":\"ya29.impersonated-cnry\",\"expireTime\":\""
              + Instant.now().plusSeconds(900) + "\"}");
        }
        String key = request.getMethod() + " " + path;
        if (exact.containsKey(key)) {
          return exact.get(key).apply(request);
        }
        if (key.equals("POST /v1/" + DATABASE + "/sessions") && !routes.containsKey("POST /sessions")) {
          return json(200, "{\"name\":\"" + SESSION + "\"}");
        }
        if (key.equals("DELETE /v1/" + SESSION)) {
          return json(200, "{}");
        }
        if (key.equals("POST /v1/" + SESSION + ":executeSql")
            || key.equals("POST /v1/" + SESSION + ":executeStreamingSql")) {
          String text = body(request).path("sql").asString("");
          if (text.equals(SpannerConnector.DIALECT_SQL)) {
            return json(200, resultSet(List.of(field("DIALECT", "STRING")), List.of(List.of(postgres ? "\"POSTGRESQL\""
                : "\"GOOGLE_STANDARD_SQL\""))));
          }
          String best = null;
          for (String part : sql.keySet()) {
            if (text.contains(part) && (best == null || part.length() > best.length())) {
              best = part;
            }
          }
          if (best != null) {
            return sql.get(best).apply(request);
          }
        }
        String bestRoute = null;
        for (String prefix : routes.keySet()) {
          String full = prefix.replaceFirst(" /", " /v1/" + DATABASE + "/");
          if (key.startsWith(full) && (bestRoute == null || prefix.length() > bestRoute.length())) {
            bestRoute = prefix;
          }
        }
        if (bestRoute == null) {
          return json(404, "{\"error\":{\"code\":404,\"message\":\"no fake for " + key + "\",\"status\":"
              + "\"NOT_FOUND\"}}");
        }
        return routes.get(bestRoute).apply(request);
      }
    });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
    Path dir = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "spanner-");
    Path adc = Files.writeString(dir.resolve("adc.json"), "{\"type\":\"authorized_user\",\"client_id\":\"c.apps"
        + ".googleusercontent.com\",\"client_secret\":\"d-cnry\",\"refresh_token\":\"1//cnry-refresh\"}");
    cloud = new CloudCredentials(new CloudEndpoints(server.url("/token").uri(),
        base() + "/v1/projects/-/serviceAccounts/%s:generateAccessToken", adc, null, null, null,
        Map.of("gcp.spanner", URI.create(base() + "/v1"))), Clock.systemUTC());
  }

  String base() {
    String url = server.url("/").toString();
    return url.substring(0, url.length() - 1);
  }

  /**
   * Answers every request whose {@code METHOD /path} starts with {@code prefix}, the path relative to the database
   * ({@code POST /sessions/s-1:commit}, {@code PATCH /ddl}); the longest prefix wins.
   */
  FakeSpanner on(String prefix, Function<RecordedRequest, MockResponse> answer) {
    routes.put(prefix, answer);
    return this;
  }

  FakeSpanner on(String prefix, String body) {
    return on(prefix, r -> json(200, body));
  }

  /** Answers {@code METHOD /path} exactly, the path from the API's root ({@code GET /v1/projects/p/instances}). */
  FakeSpanner onExact(String methodAndPath, Function<RecordedRequest, MockResponse> answer) {
    exact.put(methodAndPath, answer);
    return this;
  }

  /** Answers a query or a change whose text contains {@code part}. */
  FakeSpanner onSql(String part, Function<RecordedRequest, MockResponse> answer) {
    sql.put(part, answer);
    return this;
  }

  /** The requests whose {@code METHOD /path} starts with {@code prefix}, relative to the database as in {@link #on}. */
  List<RecordedRequest> to(String prefix) {
    String full = prefix.replaceFirst(" /", " /v1/" + DATABASE + "/");
    return requests.stream().filter(r -> (r.getMethod() + " " + r.getUrl().encodedPath()).startsWith(full)).toList();
  }

  /** The queries and changes sent, by their text. */
  List<JsonNode> statements() {
    return requests.stream().filter(r -> r.getUrl().encodedPath().endsWith("Sql")).map(FakeSpanner::body)
        .filter(b -> !SpannerConnector.DIALECT_SQL.equals(b.path("sql").asString(""))).toList();
  }

  static JsonNode body(RecordedRequest r) {
    String text = r.getBody() == null ? "" : r.getBody().utf8();
    return text.isEmpty() ? JSON.createObjectNode() : JSON.readTree(text);
  }

  static MockResponse json(int code, String body) {
    return new MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build();
  }

  /** A column of a result's {@code rowType}: {@code {"name": …, "type": {"code": …}}}. */
  static String field(String name, String code) {
    return "{\"name\":\"" + name + "\",\"type\":{\"code\":\"" + code + "\"}}";
  }

  /** A whole ResultSet: columns, and rows of JSON values as written. */
  static String resultSet(List<String> fields, List<List<String>> rows) {
    StringBuilder out = new StringBuilder("{\"metadata\":{\"rowType\":{\"fields\":[").append(String.join(",", fields))
        .append("]},\"transaction\":{\"readTimestamp\":\"2026-11-11T10:00:00.000001Z\"}},\"rows\":[");
    for (int i = 0; i < rows.size(); i++) {
      out.append(i == 0 ? "" : ",").append('[').append(String.join(",", rows.get(i))).append(']');
    }
    return out.append("]}").toString();
  }

  /** A streamed answer as Google's REST API sends it: a JSON array of PartialResultSet messages. */
  static MockResponse stream(String... messages) {
    return json(200, "[" + String.join(",\n", messages) + "]");
  }

  /** A streamed answer as a gRPC gateway (the emulator) sends it: one {@code {"result": …}} object per line. */
  static MockResponse gatewayStream(String... messages) {
    StringBuilder out = new StringBuilder();
    for (String m : messages) {
      out.append("{\"result\":").append(m).append("}\n");
    }
    return json(200, out.toString());
  }

  /** The first message of a stream: its columns, the read timestamp and the first values. */
  static String first(List<String> fields, String values) {
    return first(fields, values, false);
  }

  /** The first message of a stream; {@code chunked} when its last value goes on in the next message. */
  static String first(List<String> fields, String values, boolean chunked) {
    return "{\"metadata\":{\"rowType\":{\"fields\":[" + String.join(",", fields) + "]},\"transaction\":"
        + "{\"readTimestamp\":\"2026-11-11T10:00:00.000001Z\"}},\"values\":[" + values + "]"
        + (chunked ? ",\"chunkedValue\":true" : "") + "}";
  }

  /** A later message: more values; {@code chunked} when its last value goes on in the next message. */
  static String more(String values, boolean chunked) {
    return "{\"values\":[" + values + "]" + (chunked ? ",\"chunkedValue\":true" : "") + "}";
  }

  @Override
  public void close() {
    cloud.close();
    server.close();
  }
}
