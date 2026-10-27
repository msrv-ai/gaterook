package ai.msrv.gaterook.core.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.core.admin.AdminMvc;
import ai.msrv.gaterook.core.tunnel.TunnelWorld;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * One OAuth client as the tests drive it, against either server: through the tunnel's edge (claude.ai: its servers
 * call from {@link TunnelWorld#CLAUDE}, the person's browser from {@link TunnelWorld#BROWSER}) or on the local port
 * (Cursor, Antigravity). The person's answer comes from the admin API, as the app's consent dialog sends it.
 */
public final class OAuthFlow {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Pattern RID = Pattern.compile("data-rid=\"(ar_[A-Za-z0-9]+)\"");
  private static final Pattern CODE = Pattern.compile("aria-label=\"Code\">([2-9A-Z]{3}-[2-9A-Z]{3})<");

  /** Whether requests go through the tunnel (true) or to the local listener. */
  private final boolean tunnel;
  private final TunnelWorld world;
  private final java.util.function.Supplier<String> browser;

  public OAuthFlow(TunnelWorld world, boolean tunnel) {
    this(world, tunnel, () -> TunnelWorld.BROWSER);
  }

  /**
   * @param browser the address each sign-in's browser calls from (a test making many sign-ins spreads them, as
   *     the core admits at most 10 a minute from one address)
   */
  public OAuthFlow(TunnelWorld world, boolean tunnel, java.util.function.Supplier<String> browser) {
    this.world = world;
    this.tunnel = tunnel;
    this.browser = browser;
  }

  /** A fresh PKCE pair: the verifier and its S256 challenge. */
  public record Pkce(String verifier, String challenge) {

    public static Pkce create() {
      byte[] b = new byte[32];
      RANDOM.nextBytes(b);
      String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
      try {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return new Pkce(verifier, Base64.getUrlEncoder().withoutPadding().encodeToString(d));
      } catch (java.security.NoSuchAlgorithmException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  /** The waiting page of a valid authorization request. */
  public record Waiting(TunnelWorld.Answer page, String rid, String code, String cookie) {}

  public TunnelWorld.Answer get(String caller, String pathAndQuery, Map<String, String> headers) {
    return tunnel ? world.edge(caller, "GET", pathAndQuery, headers, null)
        : world.local("GET", pathAndQuery, headers, null);
  }

  public TunnelWorld.Answer post(String caller, String path, Map<String, String> headers, String body) {
    return tunnel ? world.edge(caller, "POST", path, headers, body) : world.local("POST", path, headers, body);
  }

  /** {@code GET /oauth/authorize?…} from the person's browser. */
  public TunnelWorld.Answer authorize(Map<String, String> params) {
    return authorizeFrom(browser.get(), params);
  }

  public TunnelWorld.Answer authorizeFrom(String browserIp, Map<String, String> params) {
    return get(browserIp, "/oauth/authorize?" + query(params), Map.of());
  }

  /** The usual parameters of a sign-in to {@code resource}. */
  public static Map<String, String> params(String clientId, String redirect, Pkce pkce, String resource,
      String state) {
    Map<String, String> p = new LinkedHashMap<>();
    p.put("response_type", "code");
    p.put("client_id", clientId);
    p.put("redirect_uri", redirect);
    p.put("code_challenge", pkce.challenge());
    p.put("code_challenge_method", "S256");
    p.put("state", state);
    p.put("scope", "mcp offline_access");
    if (resource != null) {
      p.put("resource", resource);
    }
    return p;
  }

  /** The waiting page: 200, the code and request id in the page, the cookie of this browser. */
  public Waiting waiting(Map<String, String> params) {
    TunnelWorld.Answer page = authorize(params);
    assertThat(page.status()).as(page.body()).isEqualTo(200);
    Matcher rid = RID.matcher(page.body());
    Matcher code = CODE.matcher(page.body());
    assertThat(rid.find()).as("request id in the page").isTrue();
    assertThat(code.find()).as("code in the page").isTrue();
    String setCookie = page.header("set-cookie");
    assertThat(setCookie).as("the browser's cookie").isNotNull();
    String cookie = setCookie.substring(0, setCookie.indexOf(';'));
    return new Waiting(page, rid.group(1), code.group(1), cookie);
  }

  /** The person answers in the app: the consent with this page's code, by the admin API. */
  public JsonNode consent(Waiting w) {
    JsonNode list = world.admin().ok("/api/oauth/consents");
    return StreamSupport.stream(list.spliterator(), false).filter(c -> c.path("id").asString().equals(w.rid()))
        .findFirst().orElseThrow(() -> new AssertionError("No consent " + w.rid() + " in " + list));
  }

  public AdminMvc.Answer decide(Waiting w, String decision) {
    return world.admin().post("/api/oauth/consents/" + w.rid(), JsonNodeFactory.instance.objectNode()
        .put("decision", decision));
  }

  /** The page's poll after the answer: where the browser goes now. */
  public TunnelWorld.Answer status(Waiting w) {
    return get(TunnelWorld.BROWSER, "/oauth/authorize/status?rid=" + w.rid(), Map.of("Cookie", w.cookie()));
  }

  public static Map<String, String> queryOf(String location) {
    Map<String, String> out = new LinkedHashMap<>();
    String q = URI.create(location).getRawQuery();
    if (q != null) {
      for (String pair : q.split("&")) {
        int eq = pair.indexOf('=');
        out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
            URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
      }
    }
    return out;
  }

  /** Allow, then follow the browser to the redirect: its query (code, state, iss). */
  public Map<String, String> allow(Waiting w) {
    return queryOf(allowLocation(w));
  }

  /** Allow: the location the page sends the browser to. */
  public String allowLocation(Waiting w) {
    AdminMvc.Answer decided = decide(w, "allow");
    assertThat(decided.status()).as(decided.body()).isEqualTo(204);
    TunnelWorld.Answer done = status(w);
    assertThat(done.status()).as(done.body()).isEqualTo(200);
    assertThat(done.json().path("status").asString()).isEqualTo("done");
    return done.json().path("location").asString();
  }

  /** {@code POST /oauth/token} as the client's server sends it. */
  public TunnelWorld.Answer token(Map<String, String> form) {
    return post(TunnelWorld.CLAUDE, "/oauth/token", Map.of("Content-Type", "application/x-www-form-urlencoded",
        "Accept", "application/json"), query(form));
  }

  public TunnelWorld.Answer exchange(String clientId, String redirect, String code, Pkce pkce, String resource) {
    Map<String, String> f = new LinkedHashMap<>();
    f.put("grant_type", "authorization_code");
    f.put("code", code);
    f.put("client_id", clientId);
    f.put("redirect_uri", redirect);
    f.put("code_verifier", pkce.verifier());
    if (resource != null) {
      f.put("resource", resource);
    }
    return token(f);
  }

  public TunnelWorld.Answer refresh(String clientId, String refreshToken, String resource) {
    Map<String, String> f = new LinkedHashMap<>();
    f.put("grant_type", "refresh_token");
    f.put("refresh_token", refreshToken);
    f.put("client_id", clientId);
    if (resource != null) {
      f.put("resource", resource);
    }
    return token(f);
  }

  /** The whole sign-in; the token answer's JSON. */
  public JsonNode signIn(String clientId, String redirect, String resource) {
    Pkce pkce = Pkce.create();
    Waiting w = waiting(params(clientId, redirect, pkce, resource, "st-" + RANDOM.nextInt(1_000_000)));
    Map<String, String> back = allow(w);
    TunnelWorld.Answer t = exchange(clientId, redirect, back.get("code"), pkce, resource);
    assertThat(t.status()).as(t.body()).isEqualTo(200);
    return t.json();
  }

  /** {@code tools/list} (and {@code initialize} first) on {@code slug} with {@code token}: the last answer. */
  public TunnelWorld.Answer mcp(String slug, String token, String method) {
    Map<String, String> h = new LinkedHashMap<>();
    h.put("Content-Type", "application/json");
    h.put("Accept", "application/json, text/event-stream");
    h.put("MCP-Protocol-Version", "2025-11-25");
    if (token != null) {
      h.put("Authorization", "Bearer " + token);
    }
    String params = method.equals("initialize") ? "{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
        + "\"clientInfo\":{\"name\":\"claude-ai\",\"version\":\"0.1.0\"}}" : "{}";
    return post(TunnelWorld.CLAUDE, "/mcp/" + slug, h, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method
        + "\",\"params\":" + params + "}");
  }

  public static String query(Map<String, String> params) {
    StringBuilder out = new StringBuilder();
    params.forEach((k, v) -> {
      if (!out.isEmpty()) {
        out.append('&');
      }
      out.append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=')
          .append(URLEncoder.encode(v, StandardCharsets.UTF_8));
    });
    return out.toString();
  }

  /** {@code DELETE /api/oauth/grants/{id}} — Revoke in "Signed in". */
  public void revoke(String grantId) {
    world.admin().expect(204, HttpMethod.DELETE, "/api/oauth/grants/" + grantId, JsonNodeFactory.instance
        .objectNode().put("reason", "lost laptop"));
  }
}
