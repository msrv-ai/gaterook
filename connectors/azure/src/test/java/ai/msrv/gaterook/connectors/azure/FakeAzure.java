package ai.msrv.gaterook.connectors.azure;

import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudEndpoints;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
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
import tools.jackson.databind.node.ObjectNode;

/**
 * Azure on this computer: Entra ID's token endpoint of the test tenant, and whatever Resource Manager or Log Analytics
 * API a test wires in by method and path prefix. Every Azure key of {@code gateway.cloud.endpoints} points here; a
 * data-plane host (a storage account's service, a namespace, a vault) is the first path segment
 * ({@code /acct.blob.core.windows.net/uploads?comp=list}).
 */
final class FakeAzure implements AutoCloseable {

  static final String TENANT = "6f1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d";
  static final String CLIENT = "0a1b2c3d-1111-4222-8333-444455556666";
  static final String SUB = "0b1f6471-1bf0-4dda-aec3-111122223333";
  static final String OTHER_SUB = "9c9c9c9c-1bf0-4dda-aec3-999988887777";

  final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
  private final Map<String, Function<RecordedRequest, MockResponse>> routes = new ConcurrentHashMap<>();
  private final MockWebServer server = new MockWebServer();
  final CloudCredentials cloud;
  /** The tenant the issued tokens say they belong to. */
  volatile String tokenTenant = TENANT;
  volatile int issued;

  FakeAzure() throws IOException {
    this(null);
  }

  FakeAzure(Path az) throws IOException {
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        requests.add(request);
        String path = request.getUrl().encodedPath();
        if (path.endsWith("/oauth2/v2.0/token")) {
          Function<RecordedRequest, MockResponse> custom = routes.get("POST /token");
          if (custom != null) {
            return custom.apply(request);
          }
          issued++;
          return json(200, "{\"token_type\":\"Bearer\",\"expires_in\":3599,\"access_token\":\"" + token() + "\"}");
        }
        String key = request.getMethod() + " " + path;
        String best = null;
        for (String prefix : routes.keySet()) {
          if (key.startsWith(prefix) && (best == null || prefix.length() > best.length())) {
            best = prefix;
          }
        }
        if (best == null) {
          return json(404, "{\"error\":{\"code\":\"ResourceNotFound\",\"message\":\"no fake for " + key + "\"}}");
        }
        return routes.get(best).apply(request);
      }
    });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
    Map<String, URI> services = new LinkedHashMap<>();
    for (String k : List.of("azure.login", "azure.arm", "azure.logs", "azure.storage", "azure.servicebus",
        "azure.keyvault")) {
      services.put(k, URI.create(base() + "/"));
    }
    cloud = new CloudCredentials(new CloudEndpoints(null, null, null, null, null, null, services, az),
        Clock.systemUTC());
  }

  /** A token of {@link #tokenTenant} for the service principal: an unsigned JWT, read and never checked. */
  String token() {
    ObjectNode c = TestCtx.JSON.createObjectNode();
    c.put("tid", tokenTenant).put("appid", CLIENT).put("idtyp", "app").put("oid", "sp-object-id")
        .put("exp", Instant.now().plusSeconds(3600).getEpochSecond()).put("n", issued);
    Base64.Encoder e = Base64.getUrlEncoder().withoutPadding();
    return e.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
        + e.encodeToString(TestCtx.JSON.writeValueAsBytes(c)) + ".c2ln";
  }

  String base() {
    String url = server.url("/").toString();
    return url.substring(0, url.length() - 1);
  }

  /** Answers every request whose {@code METHOD path} starts with {@code prefix} (the longest prefix wins). */
  FakeAzure on(String prefix, Function<RecordedRequest, MockResponse> answer) {
    routes.put(prefix, answer);
    return this;
  }

  /** The requests to Azure's APIs, without the token endpoint. */
  List<RecordedRequest> api() {
    return requests.stream().filter(r -> !r.getUrl().encodedPath().endsWith("/oauth2/v2.0/token")).toList();
  }

  List<RecordedRequest> to(String prefix) {
    return requests.stream().filter(r -> (r.getMethod() + " " + r.getUrl().encodedPath()).startsWith(prefix))
        .toList();
  }

  /** The scopes the token requests asked for, in order. */
  List<String> scopes() {
    return requests.stream().filter(r -> r.getUrl().encodedPath().endsWith("/oauth2/v2.0/token"))
        .map(r -> form(r.getBody().utf8(), "scope")).toList();
  }

  private static String form(String body, String name) {
    for (String pair : body.split("&")) {
      int eq = pair.indexOf('=');
      if (eq > 0 && java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8).equals(name)) {
        return java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
      }
    }
    return null;
  }

  static MockResponse xml(int code, String body) {
    return new MockResponse.Builder().code(code).addHeader("Content-Type", "application/xml")
        .body("\uFEFF<?xml version=\"1.0\" encoding=\"utf-8\"?>" + body).build();
  }

  static MockResponse json(int code, String body) {
    return new MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build();
  }

  static String param(RecordedRequest r, String name) {
    List<String> values = r.getUrl().queryParameterValues(name).stream().map(v -> v == null ? "" : v).toList();
    return values.isEmpty() ? null : values.getFirst();
  }

  @Override
  public void close() {
    cloud.close();
    server.close();
  }
}
