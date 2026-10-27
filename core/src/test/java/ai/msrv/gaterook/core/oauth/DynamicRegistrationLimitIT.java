package ai.msrv.gaterook.core.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.endpoints.EndpointService;
import ai.msrv.gaterook.core.tunnel.TunnelWorld;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * The limit of 20 registered clients per authorization server (report 02 §6.6) on the local server, where every
 * client registers itself (Cursor, Antigravity, an inspector): at the limit the registration that holds no live
 * sign-in and was used least recently makes room — rows of ended sign-ins are never deleted, so a limit that only
 * counted would refuse every registration for good once 20 clients had signed in once. A live sign-in is never what
 * makes room.
 */
class DynamicRegistrationLimitIT {

  private static TunnelWorld world;
  private static OAuthFlow local;
  private static String resource;

  @BeforeAll
  static void start() {
    world = TunnelWorld.start("dcr-limit");
    world.endpoint("dev", Env.DEV);
    EndpointService endpoints = world.bean(EndpointService.class);
    endpoints.updateSettings("dev", new EndpointService.SettingsUpdate(null, null, null, null, null, null, true),
        endpoints.get("dev").version(), null);
    local = new OAuthFlow(world, false);
    resource = "http://127.0.0.1:" + world.localPort() + "/mcp/dev";
  }

  @AfterAll
  static void stop() {
    if (world != null) {
      world.shift(Duration.ZERO);
      world.close();
    }
  }

  @Test
  void theTwentyFirstRegistrationTakesTheRoomOfTheOldestIdleOneNeverOfALiveSignIn() {
    String keptRedirect = "http://127.0.0.1:41000/callback";
    String kept = register(keptRedirect, "Kept");
    String access = local.signIn(kept, keptRedirect, resource).path("access_token").asString();

    // Nineteen that never sign in; ten registrations a minute from one address, so the core's clock moves on.
    List<String> idle = new ArrayList<>();
    try {
      for (int i = 0; i < 19; i++) {
        if (i == 9) {
          world.shift(Duration.ofSeconds(61));
        }
        idle.add(register("http://127.0.0.1:" + (41001 + i) + "/callback", "Idle " + i));
      }
      world.shift(Duration.ofSeconds(122));
      // The 21st: before, "Too many clients are registered here" for good.
      register("http://127.0.0.1:41100/callback", "Newest");
    } finally {
      world.shift(Duration.ZERO);
    }

    // The oldest idle one made room; the live sign-in kept its client and works.
    TunnelWorld.Answer gone = local.authorize(OAuthFlow.params(idle.getFirst(), "http://127.0.0.1:41001/callback",
        OAuthFlow.Pkce.create(), resource, "st-gone"));
    assertThat(gone.status()).isEqualTo(400);
    assertThat(gone.body()).contains("isn&#39;t registered here");
    assertThat(local.mcp("dev", access, "tools/list").status()).isEqualTo(200);
    OAuthFlow.Waiting next = local.waiting(OAuthFlow.params(idle.get(1), "http://127.0.0.1:41002/callback",
        OAuthFlow.Pkce.create(), resource, "st-next"));
    assertThat(local.decide(next, "deny").status()).isEqualTo(204);
    assertThat(world.admin().ok("/api/activity/changes?q=OAuth").toString()).contains("OAuth client removed · Idle 0")
        .contains("held no live sign-in");
  }

  private static String register(String redirect, String name) {
    TunnelWorld.Answer a = local.post(null, "/oauth/register", Map.of("Content-Type", "application/json"),
        JsonNodeFactory.instance.objectNode().put("client_name", name).set("redirect_uris",
            JsonNodeFactory.instance.arrayNode().add(redirect)).toString());
    assertThat(a.status()).as(name + ": " + a.body()).isEqualTo(201);
    return a.json().path("client_id").asString();
  }
}
