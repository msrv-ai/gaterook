package ai.msrv.gaterook.connectors.firebase;

import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudEndpoints;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Google on this computer: a token server for the sign-in and the key, IAM's {@code generateAccessToken} for the
 * impersonated service account, the token information, and whatever a test wires in by path prefix — Firestore's
 * {@code /v1/projects/…}, a database's {@code /<path>.json}, the Realtime Database Management API's
 * {@code /v1beta/projects/…}. The test endpoints {@code gcp.firestore}, {@code gcp.firebase-rtdb},
 * {@code gcp.firebasedatabase} and {@code gcp.oauth2} point here, with the test token endpoint that goes with them.
 */
final class FakeGoogle implements AutoCloseable {

  static final String SA = "gw-firebase@acme-stage.iam.gserviceaccount.com";
  static final String KEY_SA = "gw-key@acme-stage.iam.gserviceaccount.com";

  final String userToken = "ya29.user-sign-in-" + Long.toHexString(System.nanoTime());
  final String keyToken = "ya29.key-" + Long.toHexString(System.nanoTime());
  final String impersonatedToken = "ya29.impersonated-" + Long.toHexString(System.nanoTime());
  final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
  private final Map<String, Function<RecordedRequest, MockResponse>> routes = new ConcurrentHashMap<>();
  private final MockWebServer server = new MockWebServer();
  final CloudCredentials cloud;
  final Path adc;
  volatile String tokenInfo = "{\"email\": \"alice@example.com\", \"scope\": \"openid "
      + "https://www.googleapis.com/auth/userinfo.email https://www.googleapis.com/auth/cloud-platform\"}";

  FakeGoogle() throws IOException {
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        requests.add(request);
        String path = request.getUrl().encodedPath();
        if (path.equals("/token")) {
          String body = request.getBody() == null ? "" : request.getBody().utf8();
          String token = body.contains("jwt-bearer") ? keyToken : userToken;
          return json(200, "{\"access_token\":\"" + token + "\",\"expires_in\":3600,\"token_type\":\"Bearer\"}");
        }
        if (path.endsWith(":generateAccessToken")) {
          return json(200, "{\"accessToken\":\"" + impersonatedToken + "\",\"expireTime\":\""
              + Instant.now().plusSeconds(900) + "\"}");
        }
        if (path.equals("/tokeninfo")) {
          return json(200, tokenInfo);
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
    Path dir = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "firebase-");
    adc = Files.writeString(dir.resolve("adc.json"), "{\"type\":\"authorized_user\",\"client_id\":\"c.apps"
        + ".googleusercontent.com\",\"client_secret\":\"d-test-only\",\"refresh_token\":\"1//test-refresh\","
        + "\"quota_project_id\":\"acme-billing\"}");
    cloud = new CloudCredentials(endpoints(true), Clock.systemUTC());
  }

  /** The test endpoints, with the test token endpoint or, with {@code testTokens} false, Google's real one. */
  CloudEndpoints endpoints(boolean testTokens) {
    Map<String, URI> services = new LinkedHashMap<>();
    for (String key : List.of("gcp.firestore", "gcp.firebase-rtdb", "gcp.firebasedatabase", "gcp.oauth2")) {
      services.put(key, URI.create(base()));
    }
    return new CloudEndpoints(testTokens ? server.url("/token").uri() : null,
        testTokens ? base() + "/v1/projects/-/serviceAccounts/%s:generateAccessToken" : null, adc, null, null, null,
        services);
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

  /** The requests to the APIs: without the token server, IAM and the token information. */
  List<RecordedRequest> api() {
    return requests.stream().filter(r -> !r.getUrl().encodedPath().equals("/token")
        && !r.getUrl().encodedPath().endsWith(":generateAccessToken")
        && !r.getUrl().encodedPath().equals("/tokeninfo")).toList();
  }

  List<RecordedRequest> to(String prefix) {
    return requests.stream().filter(r -> r.getUrl().encodedPath().startsWith(prefix)).toList();
  }

  static MockResponse json(int code, String body) {
    return new MockResponse.Builder().code(code).addHeader("Content-Type", "application/json; charset=utf-8")
        .body(body).build();
  }

  static JsonNode body(RecordedRequest r) {
    return TestCtx.JSON.readTree(r.getBody().utf8());
  }

  /** The claims of the JWT a key signed for its token: its {@code scope}. */
  static JsonNode assertion(RecordedRequest tokenRequest) {
    String body = tokenRequest.getBody().utf8();
    String jwt = body.substring(body.indexOf("assertion=") + "assertion=".length());
    int amp = jwt.indexOf('&');
    jwt = amp < 0 ? jwt : jwt.substring(0, amp);
    String claims = jwt.split("\\.")[1];
    return TestCtx.JSON.readTree(new String(Base64.getUrlDecoder().decode(claims), StandardCharsets.UTF_8));
  }

  /** A service account key whose token requests go to Google's own token endpoint (the test one replaces it). */
  static String serviceAccountKey() throws Exception {
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    String pem = "-----BEGIN PRIVATE KEY-----\n"
        + Base64.getMimeEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
        + "\n-----END PRIVATE KEY-----\n";
    ObjectNode key = TestCtx.JSON.createObjectNode();
    key.put("type", "service_account").put("project_id", "acme-stage").put("private_key_id", "abc123")
        .put("private_key", pem).put("client_email", KEY_SA).put("client_id", "1234567890")
        .put("token_uri", "https://oauth2.googleapis.com/token");
    return TestCtx.JSON.writeValueAsString(key);
  }

  @Override
  public void close() {
    cloud.close();
    server.close();
  }
}
