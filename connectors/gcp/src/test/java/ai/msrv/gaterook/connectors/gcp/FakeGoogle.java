package ai.msrv.gaterook.connectors.gcp;

import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudEndpoints;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
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

/**
 * Google on this computer (report 09 §8): a token server for the sign-in, IAM's {@code generateAccessToken} for the
 * impersonated service account, and whatever API a test wires in by path prefix — Cloud Asset, Monitoring, Resource
 * Manager, the services of the live reads. Every Google API key of {@code gateway.cloud.endpoints} points here.
 */
final class FakeGoogle implements AutoCloseable {

  static final String SA = "gw-reader@acme-stage.iam.gserviceaccount.com";
  static final List<String> KEYS = List.of("gcp.cloudasset", "gcp.monitoring", "gcp.cloudresourcemanager",
      "gcp.compute", "gcp.run", "gcp.sqladmin", "gcp.container", "gcp.pubsub", "gcp.storage", "gcp.cloudfunctions",
      "gcp.redis", "gcp.logging");

  final String token = "ya29.impersonated-cnry-" + Long.toHexString(System.nanoTime());
  final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
  private final Map<String, Function<RecordedRequest, MockResponse>> routes = new ConcurrentHashMap<>();
  private final MockWebServer server = new MockWebServer();
  final CloudCredentials cloud;
  volatile int iamStatus = 200;

  FakeGoogle() throws IOException {
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        requests.add(request);
        String path = request.getUrl().encodedPath();
        if (path.equals("/token")) {
          return json(200, "{\"access_token\":\"ya29.user-sign-in\",\"expires_in\":3600,\"token_type\":\"Bearer\"}");
        }
        if (path.endsWith(":generateAccessToken")) {
          if (iamStatus != 200) {
            return json(iamStatus, "{\"error\":{\"code\":403,\"message\":\"Permission 'iam.serviceAccounts."
                + "getAccessToken' denied\",\"status\":\"PERMISSION_DENIED\"}}");
          }
          return json(200, "{\"accessToken\":\"" + token + "\",\"expireTime\":\"" + Instant.now().plusSeconds(900)
              + "\"}");
        }
        String best = null;
        for (String prefix : routes.keySet()) {
          if (path.startsWith(prefix) && (best == null || prefix.length() > best.length())) {
            best = prefix;
          }
        }
        if (best == null) {
          return json(404, "{\"error\":{\"code\":404,\"message\":\"no fake for " + path + "\",\"status\":"
              + "\"NOT_FOUND\"}}");
        }
        return routes.get(best).apply(request);
      }
    });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
    Path dir = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "gcp-");
    Path adc = Files.writeString(dir.resolve("adc.json"), "{\"type\":\"authorized_user\",\"client_id\":\"c.apps"
        + ".googleusercontent.com\",\"client_secret\":\"d-cnry\",\"refresh_token\":\"1//cnry-refresh\"}");
    Map<String, URI> services = new LinkedHashMap<>();
    for (String key : KEYS) {
      services.put(key, URI.create(base()));
    }
    cloud = new CloudCredentials(new CloudEndpoints(server.url("/token").uri(),
        base() + "/v1/projects/-/serviceAccounts/%s:generateAccessToken", adc, null, null, null, services),
        Clock.systemUTC());
  }

  String base() {
    String url = server.url("/").toString();
    return url.substring(0, url.length() - 1);
  }

  /** Answers every request whose path starts with {@code prefix} (the longest prefix wins). */
  FakeGoogle on(String prefix, Function<RecordedRequest, MockResponse> answer) {
    routes.put(prefix, answer);
    return this;
  }

  /** The requests to Google's APIs, without the token server and IAM. */
  List<RecordedRequest> api() {
    return requests.stream().filter(r -> !r.getUrl().encodedPath().equals("/token")
        && !r.getUrl().encodedPath().endsWith(":generateAccessToken")).toList();
  }

  List<RecordedRequest> to(String prefix) {
    return requests.stream().filter(r -> r.getUrl().encodedPath().startsWith(prefix)).toList();
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

  @Override
  public void close() {
    cloud.close();
    server.close();
  }
}
