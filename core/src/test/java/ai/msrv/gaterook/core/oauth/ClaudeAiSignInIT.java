package ai.msrv.gaterook.core.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.admin.AdminMvc;
import ai.msrv.gaterook.core.tunnel.TunnelWorld;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * claude.ai's connector against the tunnel, step by step as report 02 §3 traces it (ARCHITECTURE.md §10 K3: the
 * claude.ai emulator): its 401, the protected resource and authorization server metadata, its Client ID Metadata
 * Document over HTTPS, the person's browser on the sign-in page and their Allow in the app with the code to compare,
 * the token exchange with PKCE, MCP calls with the access token, refresh with rotation, reuse detection and Revoke —
 * every request through the fake Cloudflare edge from the addresses claude.ai and a browser call from.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ClaudeAiSignInIT {

  private static final String REDIRECT = "https://claude.ai/api/mcp/auth_callback";

  private static TunnelWorld world;
  private static OAuthFlow claude;
  private static AdminMvc api;
  private static String host;
  private static String resource;

  @BeforeAll
  static void start() {
    world = TunnelWorld.start("claude-ai");
    api = world.admin();
    world.endpoint("stage-readonly", Env.STAGE);
    world.endpoint("dev", Env.DEV);
    world.cloudflared().control(Map.of("mode", "ready", "hostname", "gw-smoke-7f3k.trycloudflare.com"));
    world.publish("stage-readonly");
    host = world.host();
    resource = "https://" + host + "/mcp/stage-readonly";
    claude = new OAuthFlow(world, true);
  }

  @AfterAll
  static void stop() {
    if (world != null) {
      world.shift(Duration.ZERO);
      world.close();
    }
  }

  @Test
  @Order(1)
  void claudeAiSignsInWithTheCodeComparedHereAndCallsTheEndpoint() {
    // 1. Without a token: 401 naming where to sign in, before anything about the endpoint is read.
    TunnelWorld.Answer first = claude.mcp("stage-readonly", null, "initialize");
    assertThat(first.status()).isEqualTo(401);
    assertThat(first.header("www-authenticate")).isEqualTo("Bearer resource_metadata=\"https://" + host
        + "/.well-known/oauth-protected-resource/mcp/stage-readonly\", scope=\"mcp\"");
    assertThat(first.header("cache-control")).isEqualTo("no-store");
    assertThat(first.json().path("error").asString()).isEqualTo("invalid_token");
    assertThat(claude.post(TunnelWorld.CLAUDE, "/mcp/stage-readonly", Map.of("Content-Type", "text/plain"), "x")
        .status()).as("401 comes before the body's checks").isEqualTo(401);
    assertThat(claude.get(TunnelWorld.CLAUDE, "/mcp/stage-readonly", Map.of()).status()).isEqualTo(401);

    // 2. Protected resource metadata (RFC 9728), path form and root form.
    JsonNode prm = claude.get(TunnelWorld.CLAUDE, "/.well-known/oauth-protected-resource/mcp/stage-readonly",
        Map.of()).json();
    assertThat(prm.path("resource").asString()).isEqualTo(resource);
    assertThat(prm.path("authorization_servers").get(0).asString()).isEqualTo("https://" + host);
    assertThat(prm.path("scopes_supported").get(0).asString()).isEqualTo("mcp");
    assertThat(prm.path("bearer_methods_supported").get(0).asString()).isEqualTo("header");
    assertThat(claude.get(TunnelWorld.CLAUDE, "/.well-known/oauth-protected-resource", Map.of()).json()
        .path("resource").asString()).isEqualTo(resource);

    // 3. Authorization server metadata (RFC 8414): CIMD on, no registration endpoint while DCR is off.
    TunnelWorld.Answer asAnswer = claude.get(TunnelWorld.CLAUDE, "/.well-known/oauth-authorization-server",
        Map.of());
    JsonNode as = asAnswer.json();
    assertThat(as.path("issuer").asString()).isEqualTo("https://" + host);
    assertThat(as.path("authorization_endpoint").asString()).isEqualTo("https://" + host + "/oauth/authorize");
    assertThat(as.path("token_endpoint").asString()).isEqualTo("https://" + host + "/oauth/token");
    assertThat(as.path("client_id_metadata_document_supported").asBoolean()).isTrue();
    assertThat(as.path("code_challenge_methods_supported").toString()).isEqualTo("[\"S256\"]");
    assertThat(as.path("token_endpoint_auth_methods_supported").toString()).isEqualTo("[\"none\"]");
    assertThat(as.path("authorization_response_iss_parameter_supported").asBoolean()).isTrue();
    assertThat(as.has("registration_endpoint")).isFalse();
    assertThat(asAnswer.header("cache-control")).isEqualTo("no-store");

    // 4. The person's browser opens the sign-in: a page with the code, no decision there.
    OAuthFlow.Pkce pkce = OAuthFlow.Pkce.create();
    OAuthFlow.Waiting w = claude.waiting(OAuthFlow.params(world.clientId(), REDIRECT, pkce, resource, "st-1"));
    assertThat(w.page().header("content-security-policy")).startsWith("default-src 'none'; script-src 'sha256-")
        .contains("frame-ancestors 'none'").contains("form-action 'none'");
    assertThat(w.page().header("x-frame-options")).isEqualTo("DENY");
    assertThat(w.page().header("referrer-policy")).isEqualTo("no-referrer");
    assertThat(w.page().header("cache-control")).isEqualTo("no-store");
    assertThat(w.page().header("set-cookie")).startsWith("gw_ar=").contains("Path=/oauth/authorize")
        .contains("Secure").contains("HttpOnly").contains("SameSite=Lax");
    assertThat(w.page().body()).contains(w.code()).contains("stage-readonly").doesNotContain("<form");
    TunnelWorld.Answer pending = claude.status(w);
    assertThat(pending.json().path("status").asString()).isEqualTo("pending");
    assertThat(claude.get(TunnelWorld.BROWSER, "/oauth/authorize/status?rid=" + w.rid(), Map.of()).status())
        .as("another browser").isEqualTo(404);
    assertThat(claude.get(TunnelWorld.BROWSER, "/oauth/authorize/status?rid=" + w.rid(), Map.of("Cookie",
        "gw_ar=forged")).status()).isEqualTo(404);

    // 5. The app shows what the browser shows, and where it came from.
    JsonNode consent = claude.consent(w);
    assertThat(consent.path("code").asString()).isEqualTo(w.code());
    assertThat(consent.path("endpoint").asString()).isEqualTo("stage-readonly");
    assertThat(consent.path("env").asString()).isEqualTo("stage");
    assertThat(consent.path("ip").asString()).isEqualTo(TunnelWorld.BROWSER);
    assertThat(consent.path("ingress").asString()).isEqualTo("TUNNEL");
    assertThat(consent.path("redirectHost").asString()).isEqualTo("claude.ai");
    assertThat(consent.path("clientHost").asString()).isEqualTo("localhost");
    assertThat(consent.path("tools").path("read").asInt()).isPositive();

    // 6. Allow: the browser goes back to claude.ai once, with code, state and iss.
    Map<String, String> back = claude.allow(w);
    assertThat(back.get("code")).startsWith("gwa_");
    assertThat(back.get("state")).isEqualTo("st-1");
    assertThat(back.get("iss")).isEqualTo("https://" + host);
    assertThat(claude.status(w).status()).as("the location is handed out once").isEqualTo(404);
    assertThat(claude.decide(w, "allow").status()).isIn(404, 409);
    assertThat(api.ok("/api/oauth/consents").size()).isZero();

    // 7. Token exchange from claude.ai's servers, with the verifier.
    TunnelWorld.Answer t = claude.exchange(world.clientId(), REDIRECT, back.get("code"), pkce, resource);
    assertThat(t.status()).as(t.body()).isEqualTo(200);
    assertThat(t.header("cache-control")).isEqualTo("no-store");
    assertThat(t.header("pragma")).isEqualTo("no-cache");
    JsonNode tokens = t.json();
    String access = tokens.path("access_token").asString();
    String refresh = tokens.path("refresh_token").asString();
    assertThat(access).startsWith("gwo_");
    assertThat(refresh).startsWith("gwr_");
    assertThat(tokens.path("token_type").asString()).isEqualTo("Bearer");
    assertThat(tokens.path("expires_in").asLong()).isEqualTo(3600);
    assertThat(tokens.path("scope").asString()).isEqualTo("mcp offline_access");

    // 8. MCP with the token.
    TunnelWorld.Answer init = claude.mcp("stage-readonly", access, "initialize");
    assertThat(init.status()).as(init.body()).isEqualTo(200);
    assertThat(init.json().path("result").path("serverInfo").isObject()).isTrue();
    TunnelWorld.Answer list = claude.mcp("stage-readonly", access, "tools/list");
    assertThat(list.status()).isEqualTo(200);
    assertThat(list.json().path("result").path("tools").findValuesAsString("name")).contains("sql_query");
    assertThat(claude.mcp("dev", access, "tools/list").status()).as("only the published endpoint").isEqualTo(404);
    // A call is audited with the sign-in as its principal, through the tunnel, from claude.ai's address.
    TunnelWorld.Answer call = claude.post(TunnelWorld.CLAUDE, "/mcp/stage-readonly", Map.of("Content-Type",
        "application/json", "Accept", "application/json, text/event-stream", "MCP-Protocol-Version", "2025-11-25",
        "Authorization", "Bearer " + access), "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\","
        + "\"params\":{\"name\":\"sql_query\",\"arguments\":{\"connection\":\"stage-readonly-db\","
        + "\"sql\":\"SELECT id FROM orders LIMIT 1\"}}}");
    assertThat(call.status()).as(call.body()).isEqualTo(200);
    assertThat(call.json().path("result").path("isError").asBoolean(false)).as(call.body()).isFalse();
    var jdbc = new org.springframework.jdbc.core.JdbcTemplate(world.bean(javax.sql.DataSource.class));
    TunnelWorld.await(() -> jdbc.queryForObject("select count(*) from tool_call where tool_name = 'sql_query'",
        Integer.class) > 0, "the audit row of the call");
    var row = jdbc.queryForMap("select principal_kind, ingress, caller_ip, token_label, client from tool_call "
        + "where tool_name = 'sql_query' order by id limit 1");
    assertThat(row).containsEntry("principal_kind", "OAUTH").containsEntry("ingress", "TUNNEL")
        .containsEntry("caller_ip", TunnelWorld.CLAUDE).containsEntry("token_label", "localhost");

    // 9. "Signed in": one active sign-in for 7 days.
    JsonNode grant = active();
    assertThat(grant.path("endpoint").asString()).isEqualTo("stage-readonly");
    assertThat(grant.path("ingress").asString()).isEqualTo("TUNNEL");
    assertThat(grant.path("client").asString()).isEqualTo("localhost");
    assertThat(Duration.between(Instant.parse(grant.path("createdAt").asString()),
        Instant.parse(grant.path("expiresAt").asString()))).isEqualTo(Duration.ofDays(7));

    // 10. Refresh rotates; the old refresh token presented again revokes the whole sign-in.
    TunnelWorld.Answer r = claude.refresh(world.clientId(), refresh, resource);
    assertThat(r.status()).as(r.body()).isEqualTo(200);
    String access2 = r.json().path("access_token").asString();
    String refresh2 = r.json().path("refresh_token").asString();
    assertThat(refresh2).startsWith("gwr_").isNotEqualTo(refresh);
    assertThat(claude.mcp("stage-readonly", access2, "tools/list").status()).isEqualTo(200);
    TunnelWorld.Answer reuse = claude.refresh(world.clientId(), refresh, resource);
    assertThat(reuse.status()).isEqualTo(400);
    assertThat(reuse.json().path("error").asString()).isEqualTo("invalid_grant");
    TunnelWorld.Answer after = claude.mcp("stage-readonly", access2, "tools/list");
    assertThat(after.status()).isEqualTo(401);
    assertThat(after.header("www-authenticate")).startsWith("Bearer error=\"invalid_token\", resource_metadata=");
    assertThat(claude.refresh(world.clientId(), refresh2, resource).status()).isEqualTo(400);
    assertThat(grant(grant.path("id").asString()).path("state").asString()).isEqualTo("revoked");

    String changes = api.ok("/api/activity/changes?q=Sign-in").toString();
    assertThat(changes).contains("Sign-in · localhost → stage-readonly").contains("Allowed")
        .contains("Code " + w.code()).contains(TunnelWorld.BROWSER).contains("Revoked");
  }

  @Test
  @Order(2)
  void revokeInTheAppEndsTheSignInOnTheNextCall() {
    JsonNode tokens = claude.signIn(world.clientId(), REDIRECT, resource);
    String access = tokens.path("access_token").asString();
    assertThat(claude.mcp("stage-readonly", access, "tools/list").status()).isEqualTo(200);
    JsonNode grant = active();
    claude.revoke(grant.path("id").asString());
    assertThat(claude.mcp("stage-readonly", access, "tools/list").status()).isEqualTo(401);
    assertThat(claude.refresh(world.clientId(), tokens.path("refresh_token").asString(), resource).status())
        .isEqualTo(400);
    assertThat(grant(grant.path("id").asString()).path("state").asString()).isEqualTo("revoked");
    api.expect(404, HttpMethod.DELETE, "/api/oauth/grants/og_nope", null);
  }

  @Test
  @Order(3)
  void aCodeIsGoodOnceAndItsReplayRevokesTheSignIn() {
    OAuthFlow.Pkce pkce = OAuthFlow.Pkce.create();
    OAuthFlow.Waiting w = claude.waiting(OAuthFlow.params(world.clientId(), REDIRECT, pkce, resource, "st-3"));
    Map<String, String> back = claude.allow(w);
    TunnelWorld.Answer t = claude.exchange(world.clientId(), REDIRECT, back.get("code"), pkce, resource);
    assertThat(t.status()).isEqualTo(200);
    TunnelWorld.Answer replay = claude.exchange(world.clientId(), REDIRECT, back.get("code"), pkce, resource);
    assertThat(replay.status()).isEqualTo(400);
    assertThat(replay.json().path("error").asString()).isEqualTo("invalid_grant");
    assertThat(claude.mcp("stage-readonly", t.json().path("access_token").asString(), "tools/list").status())
        .as("tokens of a replayed code stop").isEqualTo(401);
  }

  @Test
  @Order(4)
  void denyAndSilenceGoBackToClaudeAsAccessDenied() throws Exception {
    OAuthFlow.Waiting denied = claude.waiting(OAuthFlow.params(world.clientId(), REDIRECT, OAuthFlow.Pkce.create(),
        resource, "st-deny"));
    assertThat(claude.decide(denied, "deny").status()).isEqualTo(204);
    Map<String, String> back = OAuthFlow.queryOf(claude.status(denied).json().path("location").asString());
    assertThat(back).containsEntry("error", "access_denied").containsEntry("state", "st-deny")
        .containsEntry("iss", "https://" + host).doesNotContainKey("code");
    assertThat(claude.decide(denied, "allow").status()).isIn(404, 409);
    api.expect(422, HttpMethod.POST, "/api/oauth/consents/" + denied.rid(), JsonNodeFactory.instance.objectNode()
        .put("decision", "maybe"));

    // Nobody answers for 5 minutes: the same refusal.
    OAuthFlow.Waiting silent = claude.waiting(OAuthFlow.params(world.clientId(), REDIRECT, OAuthFlow.Pkce.create(),
        resource, "st-silent"));
    world.shift(Duration.ofMinutes(5).plusSeconds(5));
    try {
      // The location is handed out once: keep the answer that has it.
      java.util.concurrent.atomic.AtomicReference<JsonNode> done = new java.util.concurrent.atomic.AtomicReference<>();
      TunnelWorld.await(() -> {
        JsonNode s = claude.status(silent).json();
        done.set(s);
        return s.path("status").asString().equals("done");
      }, "the expiry");
      Map<String, String> expired = OAuthFlow.queryOf(done.get().path("location").asString());
      assertThat(expired).containsEntry("error", "access_denied").containsEntry("state", "st-silent");
    } finally {
      world.shift(Duration.ZERO);
    }
    assertThat(api.ok("/api/activity/changes?q=Sign-in").toString()).contains("Expired")
        .contains("nobody allowed it");
    assertThat(api.ok("/api/oauth/consents").size()).isZero();
  }

  @Test
  @Order(5)
  void aSignInLastsSevenDaysAndAnAccessTokenAnHour() {
    JsonNode tokens = claude.signIn(world.clientId(), REDIRECT, resource);
    String access = tokens.path("access_token").asString();
    String refresh = tokens.path("refresh_token").asString();
    world.shift(Duration.ofMinutes(61));
    try {
      assertThat(claude.mcp("stage-readonly", access, "tools/list").status()).isEqualTo(401);
      TunnelWorld.Answer r = claude.refresh(world.clientId(), refresh, resource);
      assertThat(r.status()).isEqualTo(200);
      refresh = r.json().path("refresh_token").asString();
      world.shift(Duration.ofDays(7).plusMinutes(1));
      assertThat(claude.refresh(world.clientId(), refresh, resource).status()).as("never extended").isEqualTo(400);
    } finally {
      world.shift(Duration.ZERO);
    }
  }

  @Test
  @Order(6)
  void aNewQuickTunnelAddressLeavesTheOldSignInsBehind() {
    JsonNode tokens = claude.signIn(world.clientId(), REDIRECT, resource);
    String access = tokens.path("access_token").asString();
    api.post("/api/tunnel/stop", JsonNodeFactory.instance.objectNode());
    world.cloudflared().control(Map.of("mode", "ready", "hostname", "gw-other-9q2z.trycloudflare.com"));
    world.publish("stage-readonly");
    assertThat(world.host()).isEqualTo("gw-other-9q2z.trycloudflare.com");
    TunnelWorld.Answer old = claude.mcp("stage-readonly", access, "tools/list");
    assertThat(old.status()).isEqualTo(401);
    assertThat(old.header("www-authenticate")).contains("https://gw-other-9q2z.trycloudflare.com/.well-known/");
    TunnelWorld.await(() -> StreamSupport.stream(api.ok("/api/oauth/grants").spliterator(), false)
        .anyMatch(g -> g.path("state").asString().equals("address-changed")), "address-changed");
    // Signing in again on the new address works.
    String newResource = "https://gw-other-9q2z.trycloudflare.com/mcp/stage-readonly";
    JsonNode again = claude.signIn(world.clientId(), REDIRECT, newResource);
    assertThat(claude.mcp("stage-readonly", again.path("access_token").asString(), "tools/list").status())
        .isEqualTo(200);
    resource = newResource;
    host = world.host();
  }

  private static JsonNode active() {
    return StreamSupport.stream(api.ok("/api/oauth/grants").spliterator(), false)
        .filter(g -> g.path("state").asString().equals("active")).findFirst()
        .orElseThrow(() -> new AssertionError("no active sign-in"));
  }

  private static JsonNode grant(String id) {
    return StreamSupport.stream(api.ok("/api/oauth/grants").spliterator(), false)
        .filter(g -> g.path("id").asString().equals(id)).findFirst().orElseThrow();
  }
}
