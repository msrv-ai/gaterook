package ai.msrv.gaterook.connectors.bigquery;

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
 * service account, and BigQuery's REST API by method and path ({@code POST /projects/acme-analytics/jobs}). The test
 * endpoint {@code gcp.bigquery} points here.
 */
final class FakeBigQuery implements AutoCloseable {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final String USER_TOKEN = "ya29.user-sign-in-cnry";

  final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
  private final Map<String, Function<RecordedRequest, MockResponse>> routes = new ConcurrentHashMap<>();
  private final MockWebServer server = new MockWebServer();
  final CloudCredentials cloud;

  FakeBigQuery() throws IOException {
    this(true);
  }

  /** @param tokenServer whether the endpoints name a test token server; without one, tokens go to Google only */
  FakeBigQuery(boolean tokenServer) throws IOException {
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
        String best = null;
        for (String prefix : routes.keySet()) {
          if (key.startsWith(prefix) && (best == null || prefix.length() > best.length())) {
            best = prefix;
          }
        }
        if (best == null) {
          return json(404, "{\"error\":{\"code\":404,\"message\":\"no fake for " + key + "\",\"status\":"
              + "\"NOT_FOUND\"}}");
        }
        return routes.get(best).apply(request);
      }
    });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
    Path dir = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "bigquery-");
    Path adc = Files.writeString(dir.resolve("adc.json"), "{\"type\":\"authorized_user\",\"client_id\":\"c.apps"
        + ".googleusercontent.com\",\"client_secret\":\"d-cnry\",\"refresh_token\":\"1//cnry-refresh\"}");
    cloud = new CloudCredentials(new CloudEndpoints(tokenServer ? server.url("/token").uri() : null,
        base() + "/v1/projects/-/serviceAccounts/%s:generateAccessToken", adc, null, null, null,
        Map.of("gcp.bigquery", URI.create(base() + "/bigquery/v2"))), Clock.systemUTC());
  }

  String base() {
    String url = server.url("/").toString();
    return url.substring(0, url.length() - 1);
  }

  /** Answers every request whose {@code METHOD /path} starts with {@code prefix} (the longest prefix wins). */
  FakeBigQuery on(String prefix, Function<RecordedRequest, MockResponse> answer) {
    routes.put(prefix.replaceFirst(" /", " /bigquery/v2/"), answer);
    return this;
  }

  FakeBigQuery on(String prefix, String body) {
    return on(prefix, r -> json(200, body));
  }

  /** The requests to BigQuery whose {@code METHOD /path} starts with {@code prefix}. */
  List<RecordedRequest> to(String prefix) {
    String full = prefix.replaceFirst(" /", " /bigquery/v2/");
    return requests.stream().filter(r -> (r.getMethod() + " " + r.getUrl().encodedPath()).startsWith(full)).toList();
  }

  static JsonNode body(RecordedRequest r) {
    return JSON.readTree(r.getBody().utf8());
  }

  static MockResponse json(int code, String body) {
    return new MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build();
  }

  static String param(RecordedRequest r, String name) {
    List<String> values = r.getUrl().queryParameterValues(name).stream().map(v -> v == null ? "" : v).toList();
    return values.isEmpty() ? null : values.getFirst();
  }

  /** A dry run's answer: {@code statementType}, bytes and the tables it reads ({@code project.dataset.table}). */
  static String dryRun(String type, long bytes, String... tables) {
    StringBuilder refs = new StringBuilder();
    for (String t : tables) {
      String[] p = t.split("\\.");
      refs.append(refs.isEmpty() ? "" : ",").append("{\"projectId\":\"").append(p[0]).append("\",\"datasetId\":\"")
          .append(p[1]).append("\",\"tableId\":\"").append(p[2]).append("\"}");
    }
    return "{\"kind\":\"bigquery#job\",\"status\":{\"state\":\"DONE\"},\"statistics\":{\"totalBytesProcessed\":\""
        + bytes + "\",\"query\":{\"statementType\":\"" + type + "\",\"totalBytesProcessed\":\"" + bytes + "\","
        + "\"referencedTables\":[" + refs + "]}},\"configuration\":{\"dryRun\":true}}";
  }

  @Override
  public void close() {
    cloud.close();
    server.close();
  }
}
