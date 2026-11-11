package ai.msrv.gaterook.connectors.cosmosdb;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import tools.jackson.databind.JsonNode;

/**
 * Cosmos DB's gateway and Microsoft Entra ID's token endpoint on this computer. Every request to the gateway is
 * checked as Cosmos DB checks it: {@code x-ms-version}, {@code x-ms-date}, and an authorization that is either the
 * master-key signature of the request — recomputed here from its verb, its path's resource type and link and its date,
 * with {@link #KEY} — or {@code type=aad} with the token the fake token endpoint issued. A wrong one is answered 401,
 * as Cosmos DB does. Tests wire answers in by {@code "METHOD /path"} prefix; the longest prefix wins.
 */
final class FakeCosmos implements AutoCloseable {

  /** The fake account's key, Base64 as the portal shows keys: a test value, not a key of any account. */
  static final String KEY = Base64.getEncoder().encodeToString("acme-test-account-key-for-gaterook-tests-only-0123456789abcdefghi"
      .getBytes(StandardCharsets.US_ASCII));
  static final String TENANT = "11111111-2222-3333-4444-555555555555";
  static final String CLIENT = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
  static final String OID = "99999999-8888-7777-6666-555555555555";

  final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
  final List<String> refused = new CopyOnWriteArrayList<>();
  final AtomicInteger tokenRequests = new AtomicInteger();
  private final Map<String, Function<RecordedRequest, MockResponse>> routes = new ConcurrentHashMap<>();
  private final MockWebServer server = new MockWebServer();
  volatile String token = jwt("first");
  /** The token endpoint's answer; by default a token for any client of {@link #TENANT}. */
  volatile Function<RecordedRequest, MockResponse> tokenAnswer = r -> json(200, "{\"token_type\": \"Bearer\", "
      + "\"expires_in\": 3599, \"access_token\": \"" + token + "\"}");

  FakeCosmos() throws IOException {
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest r) {
        requests.add(r);
        String path = r.getUrl().encodedPath();
        if (path.endsWith("/oauth2/v2.0/token")) {
          tokenRequests.incrementAndGet();
          return tokenAnswer.apply(r);
        }
        String problem = authProblem(r);
        if (problem != null) {
          refused.add(problem);
          return json(401, "{\"code\": \"Unauthorized\", \"message\": \"The input authorization token can't serve "
              + "the request. " + problem + "\\r\\nActivityId: 0b1c2d3e-0000-0000-0000-000000000000, "
              + "Microsoft.Azure.Documents.Common/2.14.0\"}");
        }
        String key = r.getMethod() + " " + path;
        String best = null;
        for (String prefix : routes.keySet()) {
          if (key.startsWith(prefix) && (best == null || prefix.length() > best.length())) {
            best = prefix;
          }
        }
        if (best == null) {
          return json(404, "{\"code\": \"NotFound\", \"message\": \"Resource Not Found. Learn more: "
              + "https://aka.ms/cosmosdb-tsg-not-found\"}");
        }
        return routes.get(best).apply(r);
      }
    });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
  }

  /** Why Cosmos DB would refuse the request's authorization, or {@code null}. */
  private String authProblem(RecordedRequest r) {
    String version = r.getHeaders().get("x-ms-version");
    String date = r.getHeaders().get("x-ms-date");
    String auth = r.getHeaders().get("authorization");
    if (!CosmosHttp.API_VERSION.equals(version) || date == null || auth == null) {
      return "version, date or authorization missing";
    }
    String decoded = URLDecoder.decode(auth, StandardCharsets.UTF_8);
    if (decoded.startsWith("type=aad&ver=1.0&sig=")) {
      return decoded.substring("type=aad&ver=1.0&sig=".length()).equals(token) ? null : "unknown token";
    }
    if (!decoded.startsWith("type=master&ver=1.0&sig=")) {
      return "unknown authorization type";
    }
    String[] typeAndLink = typeAndLink(URI.create("http://x" + r.getUrl().encodedPath()).getPath());
    try {
      String expected = CosmosAuth.signature(KEY.toCharArray(), r.getMethod(), typeAndLink[0], typeAndLink[1], date);
      return decoded.substring("type=master&ver=1.0&sig=".length()).equals(expected) ? null
          : "signature mismatch for " + r.getMethod() + " " + typeAndLink[0] + " " + typeAndLink[1];
    } catch (Exception e) {
      return "unsignable";
    }
  }

  /**
   * The resource type and link a path's signature covers, as Cosmos DB derives them: a feed ({@code /dbs/app/colls})
   * is signed with its parent's link, a resource ({@code /dbs/app/colls/orders}) with its own.
   */
  static String[] typeAndLink(String path) {
    List<String> segments = new ArrayList<>();
    for (String s : path.split("/")) {
      if (!s.isEmpty()) {
        segments.add(s);
      }
    }
    if (segments.isEmpty()) {
      return new String[] {"", ""};
    }
    if (segments.size() % 2 == 1) {
      return new String[] {segments.getLast(), String.join("/", segments.subList(0, segments.size() - 1))};
    }
    return new String[] {segments.get(segments.size() - 2), String.join("/", segments)};
  }

  URI base() {
    String url = server.url("/").toString();
    return URI.create(url.substring(0, url.length() - 1));
  }

  /** Answers every request whose {@code "METHOD /encoded/path"} starts with {@code prefix}. */
  FakeCosmos on(String prefix, Function<RecordedRequest, MockResponse> answer) {
    routes.put(prefix, answer);
    return this;
  }

  /** The requests to the gateway (not to the token endpoint) whose {@code "METHOD /path"} starts with {@code prefix}. */
  List<RecordedRequest> to(String prefix) {
    return requests.stream().filter(r -> (r.getMethod() + " " + r.getUrl().encodedPath()).startsWith(prefix)).toList();
  }

  static MockResponse json(int code, String body, String... headers) {
    MockResponse.Builder b = new MockResponse.Builder().code(code).addHeader("Content-Type", "application/json")
        .addHeader("x-ms-request-charge", "2.5").body(body);
    for (int i = 0; i + 1 < headers.length; i += 2) {
      b.setHeader(headers[i], headers[i + 1]);
    }
    return b.build();
  }

  static JsonNode body(RecordedRequest r) {
    return TestCtx.JSON.readTree(r.getBody().utf8());
  }

  /** A token shaped like Entra ID's, with the claims Test connection reads. Fictional values throughout. */
  static String jwt(String tag) {
    Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
    String header = b64.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
    String claims = b64.encodeToString(("{\"aud\":\"https://acme.documents.azure.com\",\"tid\":\"" + TENANT
        + "\",\"appid\":\"" + CLIENT + "\",\"oid\":\"" + OID + "\",\"tag\":\"" + tag + "\"}")
        .getBytes(StandardCharsets.UTF_8));
    return header + "." + claims + "." + b64.encodeToString(("sig-" + tag).getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public void close() {
    server.close();
  }
}
