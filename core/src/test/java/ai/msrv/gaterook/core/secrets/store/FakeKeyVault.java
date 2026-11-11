package ai.msrv.gaterook.core.secrets.store;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Entra ID's token endpoint and Azure Key Vault as far as the gateway talks to them, on one server: the
 * {@code gateway.cloud.endpoints} keys {@code azure.login} and {@code azure.keyvault} both point here, and a vault's
 * host is the first path segment ({@code /acme-kv.vault.azure.net/secrets/billing-db}). It signs in one service
 * principal by its client secret, answers only requests that carry a token it issued, and keeps canary values
 * ({@code cnry-…}) so a leak test can look for them.
 */
public final class FakeKeyVault implements AutoCloseable {

  public static final String TENANT = "6f1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d";
  public static final String CLIENT = "0a1b2c3d-1111-4222-8333-444455556666";
  public static final String CLIENT_SECRET = "cnry-azure-client-secret-5e1a";
  public static final String DB_PASSWORD = "cnry-kv-db-password-3Rt8";
  public static final String VERSION = "4387e9f3d6e14c459867679a90fd0f79";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  public final MockWebServer server = new MockWebServer();
  public final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
  /** The tokens it issued, in order. */
  public final List<String> tokens = new CopyOnWriteArrayList<>();
  /** Secret values by {@code <vault host>/<name>}. */
  public final Map<String, String> secrets = new ConcurrentHashMap<>(Map.of(
      "acme-kv.vault.azure.net/billing-db", "{\"username\":\"billing\",\"password\":\"" + DB_PASSWORD + "\"}",
      "other-kv.vault.azure.net/orders-db", "{\"password\":\"" + DB_PASSWORD + "\"}"));

  public FakeKeyVault() throws IOException {
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        requests.add(request);
        return answer(request);
      }
    });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
  }

  /** The value of {@code gateway.cloud.endpoints} that sends Azure's sign-in and Key Vault here. */
  public String endpoints() {
    String base = server.url("/").toString();
    return "azure.login=" + base + ",azure.keyvault=" + base;
  }

  private MockResponse answer(RecordedRequest r) {
    String path = r.getUrl().encodedPath();
    if (path.equals("/" + TENANT + "/oauth2/v2.0/token")) {
      String body = r.getBody() == null ? "" : r.getBody().utf8();
      if (!CLIENT_SECRET.equals(form(body, "client_secret")) || !CLIENT.equals(form(body, "client_id"))) {
        return json(401, "{\"error\":\"invalid_client\",\"error_description\":\"AADSTS7000215: Invalid client "
            + "secret provided.\"}");
      }
      String token = token(tokens.size() + 1);
      tokens.add(token);
      return json(200, "{\"token_type\":\"Bearer\",\"expires_in\":3599,\"access_token\":\"" + token + "\"}");
    }
    String auth = r.getHeaders().get("Authorization");
    if (auth == null || !auth.startsWith("Bearer ") || !tokens.contains(auth.substring("Bearer ".length()))) {
      return json(401, "{\"error\":{\"code\":\"Unauthorized\",\"message\":\"AKV10000: Request is missing a Bearer "
          + "or PoP token.\"}}");
    }
    String[] parts = path.substring(1).split("/");
    if (parts.length == 2 && parts[1].equals("secrets")) {
      return json(200, "{\"value\":[],\"nextLink\":null}");
    }
    String value = parts.length >= 3 && parts[1].equals("secrets") ? secrets.get(parts[0] + "/" + parts[2]) : null;
    if (value == null || parts.length > 4 || (parts.length == 4 && !parts[3].equals(VERSION))) {
      return json(404, "{\"error\":{\"code\":\"SecretNotFound\",\"message\":\"A secret with (name/id) x was not "
          + "found in this key vault.\"}}");
    }
    ObjectNode body = JSON.createObjectNode().put("value", value).put("id", "https://" + parts[0] + "/secrets/"
        + parts[2] + "/" + VERSION);
    body.putObject("attributes").put("enabled", true).put("created", 1_760_600_000L).put("updated", 1_760_600_000L);
    return json(200, body.toString());
  }

  /** A token of the tenant for the service principal: an unsigned JWT, read and never checked. */
  private static String token(int n) {
    ObjectNode claims = JSON.createObjectNode().put("tid", TENANT).put("appid", CLIENT).put("idtyp", "app")
        .put("oid", "sp-object-id").put("exp", Instant.now().plusSeconds(3600).getEpochSecond())
        .put("n", "cnry-kv-token-" + n);
    Base64.Encoder e = Base64.getUrlEncoder().withoutPadding();
    return e.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
        + e.encodeToString(JSON.writeValueAsBytes(claims)) + ".c2ln";
  }

  private static String form(String body, String name) {
    for (String pair : body.split("&")) {
      int eq = pair.indexOf('=');
      if (eq > 0 && URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8).equals(name)) {
        return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
      }
    }
    return null;
  }

  private static MockResponse json(int code, String body) {
    return new MockResponse.Builder().code(code).addHeader("Content-Type", "application/json; charset=utf-8")
        .body(body).build();
  }

  @Override
  public void close() {
    server.close();
  }
}
