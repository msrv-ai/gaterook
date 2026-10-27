package ai.msrv.gaterook.core.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.admin.AdminMvc;
import ai.msrv.gaterook.core.endpoints.EndpointService;
import ai.msrv.gaterook.core.endpoints.TokenService;
import ai.msrv.gaterook.core.settings.SettingsService;
import ai.msrv.gaterook.core.tunnel.TunnelWorld;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * The negative matrix of report 02 §8 and the cross-ingress rules (ARCHITECTURE.md §7.3, §9): every request a
 * stranger, a confused client or a stolen token could make against either authorization server, and what it gets.
 * Errors before the client and its redirect are known never redirect; the others go back with {@code state} and
 * {@code iss}. A token, code or client of one server is nothing on the other.
 */
class OAuthNegativeIT {

  private static final String REDIRECT = "https://claude.ai/api/mcp/auth_callback";

  private static TunnelWorld world;
  private static AdminMvc api;
  private static OAuthFlow tunnel;
  private static OAuthFlow local;
  private static String host;
  private static String resource;
  private static String localDev;

  @BeforeAll
  static void start() {
    world = TunnelWorld.start("oauth-negative");
    api = world.admin();
    world.endpoint("stage-readonly", Env.STAGE);
    world.endpoint("dev", Env.DEV);
    world.endpoint("other", Env.DEV);
    world.endpoint("incident-prod", Env.PROD);
    for (String slug : List.of("dev", "other", "stage-readonly")) {
      oauth(slug, true);
    }
    try {
      oauth("incident-prod", true);
    } catch (RuntimeException refused) {
      // A prod endpoint may refuse OAuth outright; the checks below hold either way.
    }
    world.cloudflared().control(Map.of("mode", "ready", "hostname", "gw-neg-4k2m.trycloudflare.com"));
    world.publish("stage-readonly");
    host = world.host();
    resource = "https://" + host + "/mcp/stage-readonly";
    java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger(30);
    tunnel = new OAuthFlow(world, true, () -> "203.0.113." + next.getAndIncrement() % 200);
    local = new OAuthFlow(world, false);
    localDev = "http://127.0.0.1:" + world.localPort() + "/mcp/dev";
  }

  @AfterAll
  static void stop() {
    if (world != null) {
      world.close();
    }
  }

  private static void oauth(String slug, boolean on) {
    EndpointService endpoints = world.bean(EndpointService.class);
    endpoints.updateSettings(slug, new EndpointService.SettingsUpdate(null, null, null, null, null, null, on),
        endpoints.get(slug).version(), null);
  }

  private static Map<String, String> params(String resourceUri) {
    return OAuthFlow.params(world.clientId(), REDIRECT, OAuthFlow.Pkce.create(), resourceUri, "st-neg");
  }

  private static Map<String, String> with(Map<String, String> base, String key, String value) {
    Map<String, String> p = new LinkedHashMap<>(base);
    if (value == null) {
      p.remove(key);
    } else {
      p.put(key, value);
    }
    return p;
  }

  private static void assertErrorPage(TunnelWorld.Answer a) {
    assertThat(a.status()).as(a.body()).isEqualTo(400);
    assertThat(a.header("location")).as("never a redirect").isNull();
    assertThat(a.header("content-type")).startsWith("text/html");
    assertThat(a.header("set-cookie")).isNull();
  }

  private static Map<String, String> assertRedirectError(TunnelWorld.Answer a, String error) {
    assertThat(a.status()).as(a.body()).isEqualTo(302);
    String location = a.header("location");
    assertThat(location).startsWith(REDIRECT + "?");
    Map<String, String> q = OAuthFlow.queryOf(location);
    assertThat(q).containsEntry("error", error).containsEntry("state", "st-neg").containsEntry("iss",
        "https://" + host).doesNotContainKey("code");
    return q;
  }

  // ── Authorization requests ──────────────────────────────────────────────────────────────────────────────────

  @Test
  void aWrongClientOrRedirectIsAnErrorPageNeverARedirect() {
    assertErrorPage(tunnel.authorize(with(params(resource), "redirect_uri", "https://evil.example/cb")));
    assertErrorPage(tunnel.authorize(with(params(resource), "redirect_uri", REDIRECT + "/")));
    assertErrorPage(tunnel.authorize(with(params(resource), "redirect_uri", null)));
    assertErrorPage(tunnel.authorize(with(params(resource), "client_id", "https://evil.example/meta")));
    assertErrorPage(tunnel.authorize(with(params(resource), "client_id", "gwc_unknownclientid0000")));
    assertErrorPage(tunnel.authorize(with(params(resource), "client_id", null)));
    // Request objects and repeated parameters are refused the same way.
    assertErrorPage(tunnel.get(TunnelWorld.BROWSER, "/oauth/authorize?" + OAuthFlow.query(params(resource))
        + "&request_uri=urn%3Aietf%3Aparams%3Aoauth%3Arequest_uri%3Ax", Map.of()));
    assertErrorPage(tunnel.get(TunnelWorld.BROWSER, "/oauth/authorize?" + OAuthFlow.query(params(resource))
        + "&resource=" + java.net.URLEncoder.encode("https://" + host + "/mcp/dev", java.nio.charset.StandardCharsets.UTF_8),
        Map.of()));
    assertThat(api.ok("/api/oauth/consents").size()).isZero();
  }

  @Test
  void pkceResourceAndResponseTypeGoBackToTheClient() {
    assertRedirectError(tunnel.authorize(with(params(resource), "code_challenge_method", "plain")), "invalid_request");
    assertRedirectError(tunnel.authorize(with(params(resource), "code_challenge", null)), "invalid_request");
    assertRedirectError(tunnel.authorize(with(params(resource), "code_challenge", "short")), "invalid_request");
    assertRedirectError(tunnel.authorize(with(params(resource), "response_type", "token")),
        "unsupported_response_type");
    assertRedirectError(tunnel.authorize(with(params(resource), "resource", null)), "invalid_target");
    assertRedirectError(tunnel.authorize(params("https://" + host + "/mcp/dev")), "invalid_target");
    assertRedirectError(tunnel.authorize(params("https://elsewhere.example/mcp/stage-readonly")), "invalid_target");
    assertRedirectError(tunnel.authorize(params(localDev)), "invalid_target");
    assertThat(api.ok("/api/oauth/consents").size()).isZero();
  }

  @Test
  void atMostThreeSignInsWaitPerEndpoint() {
    List<OAuthFlow.Waiting> waiting = new java.util.ArrayList<>();
    for (int i = 0; i < 3; i++) {
      waiting.add(tunnel.waiting(params(resource)));
    }
    TunnelWorld.Answer fourth = tunnel.authorize(params(resource));
    assertThat(fourth.status()).isEqualTo(429);
    assertThat(fourth.header("location")).isNull();
    assertThat(fourth.body()).contains("Another sign-in is waiting");
    for (OAuthFlow.Waiting w : waiting) {
      assertThat(tunnel.decide(w, "deny").status()).isEqualTo(204);
    }
  }

  @Test
  void oneBrowserAddressOpensAtMostTenSignInsAMinute() {
    for (int i = 0; i < 10; i++) {
      TunnelWorld.Answer page = tunnel.authorizeFrom("198.51.100.99", params(resource));
      assertThat(page.status()).as("sign-in " + (i + 1)).isEqualTo(200);
      String rid = page.body().replaceAll("(?s).*data-rid=\"(ar_[A-Za-z0-9]+)\".*", "$1");
      api.post("/api/oauth/consents/" + rid, JsonNodeFactory.instance.objectNode().put("decision", "deny"));
    }
    TunnelWorld.Answer eleventh = tunnel.authorizeFrom("198.51.100.99", params(resource));
    assertThat(eleventh.status()).isEqualTo(429);
    assertThat(tunnel.authorizeFrom("198.51.100.98", params(resource)).status()).as("another address").isEqualTo(200);
    for (JsonNode c : api.ok("/api/oauth/consents")) {
      api.post("/api/oauth/consents/" + c.path("id").asString(), JsonNodeFactory.instance.objectNode()
          .put("decision", "deny"));
    }
  }

  @Test
  void everyAuthorizeCountsAgainstItsAddressBeforeTheClientIsLookedUp() {
    // The sign-in page is open to the internet and its client lookup may fetch a metadata document: ten requests of
    // a stranger, valid or not, use up the address's minute.
    for (int i = 0; i < 10; i++) {
      assertErrorPage(tunnel.authorizeFrom("198.51.100.77", with(params(resource), "client_id", "gwc_none" + i)));
    }
    TunnelWorld.Answer eleventh = tunnel.authorizeFrom("198.51.100.77", params(resource));
    assertThat(eleventh.status()).isEqualTo(429);
    assertThat(eleventh.header("location")).isNull();
    assertThat(eleventh.header("set-cookie")).isNull();

    // An IPv6 caller is one subscriber's /64: a fresh address in it is no fresh minute.
    for (int i = 1; i <= 10; i++) {
      assertErrorPage(tunnel.authorizeFrom("2001:db8:77:1::" + Integer.toHexString(i), with(params(resource),
          "client_id", "gwc_none" + i)));
    }
    assertThat(tunnel.authorizeFrom("2001:db8:77:1::ff", params(resource)).status()).isEqualTo(429);
    TunnelWorld.Answer otherNetwork = tunnel.authorizeFrom("2001:db8:77:2::1", params(resource));
    assertThat(otherNetwork.status()).as(otherNetwork.body()).isEqualTo(200);
    String rid = otherNetwork.body().replaceAll("(?s).*data-rid=\"(ar_[A-Za-z0-9]+)\".*", "$1");
    assertThat(api.post("/api/oauth/consents/" + rid, JsonNodeFactory.instance.objectNode().put("decision", "deny"))
        .status()).isEqualTo(204);
  }

  // ── Token endpoint ──────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void codesVerifiersAndTokensAreTakenFromTheBodyOnly() {
    String query = "grant_type=refresh_token&refresh_token=gwr_inthequery&client_id=" + java.net.URLEncoder.encode(
        world.clientId(), java.nio.charset.StandardCharsets.UTF_8);
    for (String path : List.of("/oauth/token", "/oauth/revoke")) {
      TunnelWorld.Answer inUrl = tunnel.post(TunnelWorld.CLAUDE, path + "?" + query, Map.of("Content-Type",
          "application/x-www-form-urlencoded"), "");
      assertThat(inUrl.status()).as(path).isEqualTo(400);
      assertThat(inUrl.json().path("error").asString()).isEqualTo("invalid_request");
      assertThat(inUrl.header("cache-control")).isEqualTo("no-store");
    }
  }

  @Test
  void theCodeIsBoundToItsClientRedirectResourceVerifierAndServer() {
    OAuthFlow.Pkce pkce = OAuthFlow.Pkce.create();
    OAuthFlow.Waiting w = tunnel.waiting(OAuthFlow.params(world.clientId(), REDIRECT, pkce, resource, "st-neg"));
    String code = tunnel.allow(w).get("code");

    // Another server: the local token endpoint doesn't know the tunnel's code (and the attempt doesn't burn it).
    TunnelWorld.Answer elsewhere = local.exchange(world.clientId(), REDIRECT, code, pkce, resource);
    assertThat(elsewhere.status()).isEqualTo(400);
    assertThat(elsewhere.json().path("error").asString()).isEqualTo("invalid_grant");
    // That attempt redeemed it: what follows is a replay, and replays revoke.
    TunnelWorld.Answer replay = tunnel.exchange(world.clientId(), REDIRECT, code, pkce, resource);
    assertThat(replay.json().path("error").asString()).isEqualTo("invalid_grant");

    // Each binding on its own code.
    assertThat(exchangeFresh(Map.of("client_id", "https://evil.example/meta"))).isEqualTo("invalid_grant");
    assertThat(exchangeFresh(Map.of("redirect_uri", "https://claude.ai/other"))).isEqualTo("invalid_grant");
    assertThat(exchangeFresh(Map.of("resource", "https://" + host + "/mcp/dev"))).isEqualTo("invalid_target");
    assertThat(exchangeFresh(Map.of("code_verifier", "x".repeat(43)))).isEqualTo("invalid_grant");
    assertThat(exchangeFresh(Map.of("code_verifier", ""))).isEqualTo("invalid_grant");

    Map<String, String> form = new LinkedHashMap<>(Map.of("grant_type", "authorization_code", "code",
        "gwa_unknown", "client_id", world.clientId(), "code_verifier", pkce.verifier()));
    assertThat(tunnel.token(form).json().path("error").asString()).isEqualTo("invalid_grant");
    assertThat(tunnel.token(Map.of("grant_type", "password", "username", "a", "password", "b")).json()
        .path("error").asString()).isEqualTo("unsupported_grant_type");
    assertThat(tunnel.token(Map.of("client_id", world.clientId())).json().path("error").asString())
        .isEqualTo("invalid_request");
    TunnelWorld.Answer basic = tunnel.post(TunnelWorld.CLAUDE, "/oauth/token", Map.of("Content-Type",
        "application/x-www-form-urlencoded", "Authorization", "Basic Y2xpZW50OnNlY3JldA=="),
        "grant_type=authorization_code&code=gwa_x");
    assertThat(basic.status()).isEqualTo(401);
    assertThat(basic.json().path("error").asString()).isEqualTo("invalid_client");
    TunnelWorld.Answer twice = tunnel.post(TunnelWorld.CLAUDE, "/oauth/token", Map.of("Content-Type",
        "application/x-www-form-urlencoded"), "grant_type=refresh_token&refresh_token=a&refresh_token=b");
    assertThat(twice.status()).isEqualTo(400);
    assertThat(twice.json().path("error").asString()).isEqualTo("invalid_request");
  }

  /** A new code, exchanged with one field changed: the token endpoint's error. */
  private static String exchangeFresh(Map<String, String> change) {
    OAuthFlow.Pkce pkce = OAuthFlow.Pkce.create();
    OAuthFlow.Waiting w = tunnel.waiting(OAuthFlow.params(world.clientId(), REDIRECT, pkce, resource, "st-neg"));
    String code = tunnel.allow(w).get("code");
    Map<String, String> form = new LinkedHashMap<>();
    form.put("grant_type", "authorization_code");
    form.put("code", code);
    form.put("client_id", world.clientId());
    form.put("redirect_uri", REDIRECT);
    form.put("code_verifier", pkce.verifier());
    form.put("resource", resource);
    form.putAll(change);
    TunnelWorld.Answer a = tunnel.token(form);
    assertThat(a.status()).isEqualTo(400);
    return a.json().path("error").asString();
  }

  @Test
  void refreshTokensAreBoundToTheirClientResourceAndServer() {
    JsonNode tokens = tunnel.signIn(world.clientId(), REDIRECT, resource);
    String refresh = tokens.path("refresh_token").asString();
    assertThat(tunnel.refresh("https://evil.example/meta", refresh, resource).status()).isEqualTo(400);
    assertThat(tunnel.refresh(world.clientId(), refresh, "https://" + host + "/mcp/dev").status()).isEqualTo(400);
    assertThat(local.refresh(world.clientId(), refresh, null).status()).as("the other server").isEqualTo(400);
    // None of these consumed it.
    TunnelWorld.Answer ok = tunnel.refresh(world.clientId(), refresh, resource);
    assertThat(ok.status()).as(ok.body()).isEqualTo(200);

    // RFC 7009: revoke answers 200 whatever it got, and the token stops.
    String access = ok.json().path("access_token").asString();
    assertThat(tunnel.post(TunnelWorld.CLAUDE, "/oauth/revoke", Map.of("Content-Type",
        "application/x-www-form-urlencoded"), "token=gwr_nonsense").status()).isEqualTo(200);
    TunnelWorld.Answer revoked = tunnel.post(TunnelWorld.CLAUDE, "/oauth/revoke", Map.of("Content-Type",
        "application/x-www-form-urlencoded"), "token=" + access + "&client_id=" + java.net.URLEncoder.encode(
            world.clientId(), java.nio.charset.StandardCharsets.UTF_8));
    assertThat(revoked.status()).isEqualTo(200);
    assertThat(tunnel.mcp("stage-readonly", access, "tools/list").status()).isEqualTo(401);
  }

  // ── Tokens on MCP calls ─────────────────────────────────────────────────────────────────────────────────────

  @Test
  void aTokenWorksOnlyOnItsEndpointAndItsIngress() {
    // Endpoint tokens never pass the tunnel.
    String gwt = world.bean(TokenService.class).issue("stage-readonly", "laptop", 7, null).token();
    assertThat(local.mcp("stage-readonly", gwt, "tools/list").status()).as("works locally").isEqualTo(200);
    TunnelWorld.Answer viaTunnel = tunnel.mcp("stage-readonly", gwt, "tools/list");
    assertThat(viaTunnel.status()).isEqualTo(401);
    assertThat(viaTunnel.header("www-authenticate")).startsWith("Bearer error=\"invalid_token\", resource_metadata=");

    // The tunnel's sign-in: nothing locally, even on the same endpoint with OAuth on.
    String tunnelAccess = tunnel.signIn(world.clientId(), REDIRECT, resource).path("access_token").asString();
    assertThat(tunnel.mcp("stage-readonly", tunnelAccess, "tools/list").status()).isEqualTo(200);
    assertThat(local.mcp("stage-readonly", tunnelAccess, "tools/list").status()).isEqualTo(401);

    // A local sign-in (Cursor-like): its endpoint only, and never through the tunnel.
    String clientId = register("http://127.0.0.1:43123/callback", "Cursor");
    String localAccess = local.signIn(clientId, "http://127.0.0.1:51004/callback", localDev).path("access_token")
        .asString();
    assertThat(local.mcp("dev", localAccess, "tools/list").status()).isEqualTo(200);
    assertThat(local.mcp("other", localAccess, "tools/list").status()).as("endpoint A's token on B").isEqualTo(401);
    assertThat(tunnel.mcp("stage-readonly", localAccess, "tools/list").status()).isEqualTo(401);
    // The local client isn't one of the tunnel's.
    TunnelWorld.Answer foreignClient = tunnel.authorize(OAuthFlow.params(clientId, "http://127.0.0.1:43123/callback",
        OAuthFlow.Pkce.create(), resource, "st-neg"));
    assertErrorPage(foreignClient);

    // A local endpoint with OAuth off stops taking its sign-ins.
    oauth("dev", false);
    try {
      TunnelWorld.Answer off = local.mcp("dev", localAccess, "tools/list");
      assertThat(off.status()).isEqualTo(401);
      assertThat(off.header("www-authenticate")).isEqualTo("Bearer realm=\"mcp-gateway\"");
    } finally {
      oauth("dev", true);
    }
  }

  // ── Dynamic registration ────────────────────────────────────────────────────────────────────────────────────

  @Test
  void registrationIsOffThroughTheTunnelUntilSettingsTurnItOn() {
    String body = "{\"client_name\":\"Some client\",\"redirect_uris\":[\"https://claude.ai/cb\"]}";
    assertThat(tunnel.post(TunnelWorld.CLAUDE, "/oauth/register", Map.of("Content-Type", "application/json"), body)
        .status()).isEqualTo(404);
    SettingsService settings = world.bean(SettingsService.class);
    settings.update("authorization", JsonNodeFactory.instance.objectNode().put("clientRegistration", "cimd+dcr"),
        settings.get("authorization").version(), "test");
    try {
      assertThat(tunnel.get(TunnelWorld.CLAUDE, "/.well-known/oauth-authorization-server", Map.of()).json()
          .path("registration_endpoint").asString()).isEqualTo("https://" + host + "/oauth/register");
      TunnelWorld.Answer created = tunnel.post(TunnelWorld.CLAUDE, "/oauth/register", Map.of("Content-Type",
          "application/json"), "{\"client_name\":\"Some client\",\"redirect_uris\":[\"https://claude.ai/cb\"],"
          + "\"token_endpoint_auth_method\":\"client_secret_post\"}");
      assertThat(created.status()).as(created.body()).isEqualTo(201);
      assertThat(created.json().path("client_id").asString()).startsWith("gwc_");
      assertThat(created.json().path("token_endpoint_auth_method").asString()).isEqualTo("none");
      assertThat(created.json().has("client_secret")).isFalse();
      for (String bad : List.of("http://claude.ai/cb", "https://evil.example/cb", "http://127.0.0.1:5/cb",
          "cursor://anysphere.cursor-mcp/oauth/callback")) {
        TunnelWorld.Answer refused = tunnel.post(TunnelWorld.CLAUDE, "/oauth/register", Map.of("Content-Type",
            "application/json"), "{\"redirect_uris\":[\"" + bad + "\"]}");
        assertThat(refused.status()).as(bad).isEqualTo(400);
        assertThat(refused.json().path("error").asString()).isEqualTo("invalid_redirect_uri");
      }
      assertThat(tunnel.post(TunnelWorld.CLAUDE, "/oauth/register", Map.of("Content-Type", "text/plain"), body)
          .status()).isEqualTo(415);
      assertThat(tunnel.post(TunnelWorld.CLAUDE, "/oauth/register", Map.of("Content-Type", "application/json"),
          "not json").json().path("error").asString()).isEqualTo("invalid_client_metadata");
      // The registered client signs in like claude.ai, with the consent here.
      String dcrClient = created.json().path("client_id").asString();
      JsonNode t = tunnel.signIn(dcrClient, "https://claude.ai/cb", resource);
      assertThat(tunnel.mcp("stage-readonly", t.path("access_token").asString(), "tools/list").status())
          .isEqualTo(200);
      assertThat(api.ok("/api/activity/changes?q=OAuth").toString()).contains("OAuth client registered");
    } finally {
      settings.update("authorization", JsonNodeFactory.instance.objectNode().put("clientRegistration", "cimd"),
          settings.get("authorization").version(), "test");
    }
    assertThat(tunnel.get(TunnelWorld.CLAUDE, "/.well-known/oauth-authorization-server", Map.of()).json()
        .has("registration_endpoint")).isFalse();
  }

  // ── The public port and its callers ─────────────────────────────────────────────────────────────────────────

  @Test
  void strangersGetAnEmpty403AndOnlyTheSignInPageIsOpenToBrowsers() {
    for (String path : List.of("/.well-known/oauth-authorization-server",
        "/.well-known/oauth-protected-resource/mcp/stage-readonly")) {
      TunnelWorld.Answer a = tunnel.get("203.0.113.24", path, Map.of());
      assertThat(a.status()).as(path).isEqualTo(403);
      assertThat(a.body()).isEmpty();
    }
    TunnelWorld.Answer token = tunnel.post("203.0.113.24", "/oauth/token", Map.of("Content-Type",
        "application/x-www-form-urlencoded"), "grant_type=refresh_token&refresh_token=gwr_x");
    assertThat(token.status()).isEqualTo(403);
    TunnelWorld.Answer mcp = world.edge("203.0.113.24", "POST", "/mcp/stage-readonly", Map.of("Content-Type",
        "application/json"), "{}");
    assertThat(mcp.status()).as("not even the 401").isEqualTo(403);
    // The same address opens the sign-in page.
    OAuthFlow.Waiting w = tunnel.waiting(params(resource));
    assertThat(tunnel.decide(w, "deny").status()).isEqualTo(204);
  }

  @Test
  void thePublicPortServesNothingElse() {
    for (String path : List.of("/api/health", "/api/tunnel", "/actuator/health", "/", "/mcp/dev", "/error",
        "/internal/clients/bridge", "/approvals/ap_000000000000", "/oauth/userinfo", "/MCP/stage-readonly",
        "/mcp/stage-readonly/", "/mcp/%73tage-readonly", "/mcp/stage-readonly;x=1")) {
      assertThat(world.edge(TunnelWorld.CLAUDE, "GET", path, Map.of(), null).status()).as(path).isEqualTo(404);
    }
    // Straight to 7466 with any other Host, or none of the tunnel's.
    for (String h : List.of("evil.example", "127.0.0.1:" + world.publicPort(), "localhost:" + world.publicPort(),
        host + ".evil.example")) {
      assertThat(world.direct(world.publicPort(), h, "GET", "/.well-known/oauth-authorization-server", Map.of(
          "CF-Connecting-IP", TunnelWorld.CLAUDE), null).status()).as(h).isEqualTo(404);
    }
    // The tunnel's Host on the local port: the local guard refuses it.
    assertThat(world.direct(world.localPort(), host, "GET", "/.well-known/oauth-authorization-server", Map.of(),
        null).status()).isEqualTo(421);
    // A body over 64 KiB never reaches a controller.
    assertThat(world.edge(TunnelWorld.CLAUDE, "POST", "/mcp/stage-readonly", Map.of("Content-Type",
        "application/json"), "{\"x\":\"" + "y".repeat(70_000) + "\"}").status()).isEqualTo(413);
  }

  // ── The local server ────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void theLocalServerSignsInCursorAndAntigravityToEndpointsWithOauthOn() {
    TunnelWorld.Answer challenge = local.mcp("dev", null, "initialize");
    assertThat(challenge.status()).isEqualTo(401);
    assertThat(challenge.header("www-authenticate")).isEqualTo("Bearer resource_metadata=\"http://127.0.0.1:"
        + world.localPort() + "/.well-known/oauth-protected-resource/mcp/dev\", scope=\"mcp\"");
    JsonNode prm = local.get(null, "/.well-known/oauth-protected-resource/mcp/dev", Map.of()).json();
    assertThat(prm.path("resource").asString()).isEqualTo(localDev);
    assertThat(prm.path("authorization_servers").get(0).asString()).isEqualTo("http://127.0.0.1:" + world.localPort());
    JsonNode as = local.get(null, "/.well-known/oauth-authorization-server", Map.of()).json();
    assertThat(as.path("issuer").asString()).isEqualTo("http://127.0.0.1:" + world.localPort());
    assertThat(as.path("registration_endpoint").asString()).endsWith("/oauth/register");
    assertThat(as.has("client_id_metadata_document_supported")).isFalse();
    assertThat(local.get(null, "/.well-known/oauth-protected-resource", Map.of()).status()).isEqualTo(404);

    // Prod is never signed in to, OAuth on or not.
    assertThat(local.get(null, "/.well-known/oauth-protected-resource/mcp/incident-prod", Map.of()).status())
        .isEqualTo(404);
    assertThat(local.mcp("incident-prod", null, "initialize").header("www-authenticate"))
        .isEqualTo("Bearer realm=\"mcp-gateway\"");

    // Cursor's and Antigravity's own callbacks, and loopback ones; nothing else.
    String cursor = register("cursor://anysphere.cursor-mcp/oauth/callback", "Cursor");
    String antigravity = register("https://antigravity.google/oauth-callback", "Antigravity");
    for (String bad : List.of("https://evil.example/cb", "http://10.0.0.5/cb", "cursor://other/cb")) {
      TunnelWorld.Answer refused = local.post(null, "/oauth/register", Map.of("Content-Type", "application/json"),
          "{\"redirect_uris\":[\"" + bad + "\"]}");
      assertThat(refused.status()).as(bad).isEqualTo(400);
    }

    OAuthFlow.Pkce pkce = OAuthFlow.Pkce.create();
    OAuthFlow.Waiting w = local.waiting(OAuthFlow.params(cursor, "cursor://anysphere.cursor-mcp/oauth/callback", pkce,
        localDev, "st-cursor"));
    assertThat(w.page().header("set-cookie")).doesNotContain("Secure");
    JsonNode consent = local.consent(w);
    assertThat(consent.path("ingress").asString()).isEqualTo("LOCAL");
    assertThat(consent.path("clientName").asString()).isEqualTo("Cursor");
    Map<String, String> back = local.allow(w);
    assertThat(back.get("iss")).isEqualTo("http://127.0.0.1:" + world.localPort());
    TunnelWorld.Answer t = local.exchange(cursor, "cursor://anysphere.cursor-mcp/oauth/callback", back.get("code"),
        pkce, localDev);
    assertThat(t.status()).as(t.body()).isEqualTo(200);
    assertThat(local.mcp("dev", t.json().path("access_token").asString(), "tools/list").status()).isEqualTo(200);

    JsonNode ag = local.signIn(antigravity, "https://antigravity.google/oauth-callback", localDev);
    assertThat(local.mcp("dev", ag.path("access_token").asString(), "tools/list").status()).isEqualTo(200);
    assertThat(StreamSupport.stream(api.ok("/api/oauth/grants").spliterator(), false)
        .filter(g -> g.path("ingress").asString().equals("LOCAL") && g.path("state").asString().equals("active"))
        .map(g -> g.path("clientName").asString())).contains("Cursor", "Antigravity");

    // A localhost issuer for a client that addressed the core as localhost.
    TunnelWorld.Answer viaLocalhost = world.direct(world.localPort(), "localhost:" + world.localPort(), "GET",
        "/.well-known/oauth-authorization-server", Map.of(), null);
    assertThat(viaLocalhost.json().path("issuer").asString()).isEqualTo("http://localhost:" + world.localPort());
  }

  private static String register(String redirect, String name) {
    TunnelWorld.Answer a = local.post(null, "/oauth/register", Map.of("Content-Type", "application/json"),
        "{\"client_name\":\"" + name + "\",\"redirect_uris\":[\"" + redirect + "\"],\"grant_types\":"
            + "[\"authorization_code\",\"refresh_token\"],\"token_endpoint_auth_method\":\"none\"}");
    assertThat(a.status()).as(a.body()).isEqualTo(201);
    return a.json().path("client_id").asString();
  }
}
