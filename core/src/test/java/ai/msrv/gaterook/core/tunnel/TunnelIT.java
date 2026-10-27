package ai.msrv.gaterook.core.tunnel;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.admin.AdminMvc;
import ai.msrv.gaterook.core.endpoints.EndpointService;
import ai.msrv.gaterook.core.platform.EventBus;
import ai.msrv.gaterook.core.tunnel.internal.FakeAgents;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The tunnel on a whole core with the fake cloudflared and ngrok as child processes (ARCHITECTURE.md §7.3, §10 K3):
 * Publish goes public only once the agent knows its hostname and reached its edge, the self-test runs through the
 * edge and the Callers gate, Stop and an agent that dies take the URL down at once, a prod endpoint is never published,
 * and a tampered agent never starts.
 */
class TunnelIT {

  private static TunnelWorld world;
  private static AdminMvc api;

  @BeforeAll
  static void start() {
    world = TunnelWorld.start("tunnel", "--gateway.test.tunnel-wait=3s");
    api = world.admin();
    world.endpoint("stage-readonly", Env.STAGE);
    world.endpoint("dev", Env.DEV);
    world.endpoint("incident-prod", Env.PROD);
  }

  @AfterAll
  static void stop() {
    if (world != null) {
      world.close();
    }
  }

  @AfterEach
  void off() {
    api.post("/api/tunnel/stop", JsonNodeFactory.instance.objectNode());
    world.cloudflared().control(Map.of("mode", "ready", "hostname", "gw-smoke-7f3k.trycloudflare.com"));
    world.ngrok().control(Map.of("mode", "ready"));
  }

  private static ObjectNode endpoint(String slug) {
    return JsonNodeFactory.instance.objectNode().put("endpoint", slug);
  }

  @Test
  void publishGoesPublicOnlyWhenTheEdgeIsUpAndStopTakesItDownAtOnce() throws Exception {
    world.cloudflared().control(Map.of("mode", "ready", "hostname", "gw-smoke-7f3k.trycloudflare.com",
        "readyDelayMs", "800"));
    JsonNode before = world.state();
    assertThat(before.path("state").asString()).isEqualTo("off");
    assertThat(before.path("provider").asString()).isEqualTo("cloudflare-quick");
    assertThat(before.path("callers").path("anthropicOnly").asBoolean()).isTrue();
    assertThat(before.path("callers").path("ranges").get(0).asString()).isEqualTo("160.79.104.0/21");
    assertThat(before.path("cloudflared").path("version").asString()).isEqualTo("2026.10.0");
    assertThat(before.path("cloudflared").path("sha256Ok").asBoolean()).isTrue();
    assertThat(before.path("publicUrl").isNull()).isTrue();
    assertThat(before.path("stableAddress").asBoolean()).as("a quick tunnel's address is new each start").isFalse();
    assertThat(before.path("ngrokDomain").isNull()).isTrue();

    JsonNode preview = api.expect(200, HttpMethod.POST, "/api/tunnel/publish/preview", endpoint("stage-readonly"));
    assertThat(each(preview.path("publicPaths"))).containsExactly("/mcp/stage-readonly", "/oauth/*",
        "/.well-known/oauth-*");
    assertThat(each(preview.path("local"))).contains("/mcp/dev", "/mcp/incident-prod")
        .anyMatch(s -> s.startsWith("The app and its admin API"));
    assertThat(preview.path("refusal").isNull()).isTrue();
    assertThat(preview.path("connections").get(0).path("env").asString()).isEqualTo("stage");
    assertThat(each(preview.path("warnings"))).anyMatch(w -> w.contains("new address each time"));

    List<JsonNode> events = new ArrayList<>();
    var subscription = world.bean(EventBus.class);
    AutoCloseable listening = listen(subscription, events);
    try {
      AdminMvc.Answer published = api.post("/api/tunnel/publish", endpoint("stage-readonly"));
      assertThat(published.status()).isEqualTo(202);
      assertThat(published.json().path("state").asString()).isEqualTo("starting");
      assertThat(published.json().path("publicUrl").isNull()).as("not public before the edge").isTrue();
      // While starting, nothing is served on the public port yet.
      assertThat(world.direct(world.publicPort(), "gw-smoke-7f3k.trycloudflare.com", "GET",
          "/.well-known/oauth-authorization-server", Map.of(), null).status()).isEqualTo(404);

      TunnelWorld.await(() -> "on".equals(world.state().path("state").asString()), "on");
      JsonNode on = world.state();
      assertThat(on.path("host").asString()).isEqualTo("gw-smoke-7f3k.trycloudflare.com");
      assertThat(on.path("publicUrl").asString()).isEqualTo("https://gw-smoke-7f3k.trycloudflare.com/mcp/stage-readonly");
      assertThat(on.path("endpoint").asString()).isEqualTo("stage-readonly");

      // The self-test ran through the fake edge from "this computer": past the gate on its nonce only.
      TunnelWorld.await(() -> !world.state().path("check").isNull(), "the self-test");
      JsonNode check = world.state().path("check");
      assertThat(check.path("ok").asBoolean()).as(check.toString()).isTrue();
      assertThat(check.path("text").asString()).matches("Reachable · \\d+ ms via Cloudflare \\(FRA\\)");
      assertThat(each(check.path("steps"), "name")).containsExactly("dns", "resource-metadata",
          "authorization-server", "mcp-401");
      JsonNode again = api.expect(200, HttpMethod.POST, "/api/tunnel/check", null);
      assertThat(again.path("ok").asBoolean()).isTrue();
      assertThat(again.path("text").asString()).startsWith("Reachable");

      // Health and the sidebar say so; the endpoint is the tunnel's.
      JsonNode health = api.ok("/api/health");
      assertThat(health.path("tunnel").path("state").asString()).isEqualTo("on");
      assertThat(health.path("tunnel").path("host").asString()).isEqualTo("gw-smoke-7f3k.trycloudflare.com");
      assertThat(api.ok("/api/nav/counters").path("tunnel").asString()).isEqualTo("on");
      assertThat(world.bean(EndpointService.class).get("stage-readonly").published()).isTrue();

      // The agent got the argv of report 02 §7.3 towards the public port.
      assertThat(world.cloudflared().argv()).containsSubsequence("--protocol", "auto", "--url",
          "http://127.0.0.1:" + world.publicPort());
      long pid = world.cloudflared().pid();

      JsonNode stopped = api.expect(200, HttpMethod.POST, "/api/tunnel/stop", JsonNodeFactory.instance.objectNode()
          .put("reason", "done for today"));
      assertThat(stopped.path("state").asString()).isEqualTo("off");
      assertThat(stopped.path("publicUrl").isNull()).isTrue();
      FakeAgents.awaitGone(pid);
      assertThat(world.direct(world.publicPort(), "gw-smoke-7f3k.trycloudflare.com", "GET",
          "/.well-known/oauth-authorization-server", Map.of(), null).status()).isEqualTo(404);
      assertThat(world.bean(EndpointService.class).get("stage-readonly").published())
          .as("stays the tunnel's endpoint while off").isTrue();
      JsonNode session = world.state().path("history").get(0);
      assertThat(session.path("endpoint").asString()).isEqualTo("stage-readonly");
      assertThat(session.path("to").isNull()).isFalse();
      assertThat(session.path("host").asString()).isEqualTo("gw-smoke-7f3k.trycloudflare.com");
      assertThat(Files.exists(world.home().resolve("run/tunnel.pid"))).isFalse();
    } finally {
      listening.close();
    }
    assertThat(events).extracting(e -> e.path("state").asString()).contains("starting", "on", "off");

    String changes = api.ok("/api/activity/changes?q=Tunnel").toString();
    assertThat(changes).contains("Tunnel · stage-readonly").contains("\"from\":\"Off\",\"to\":\"On\"")
        .contains("\"from\":\"On\",\"to\":\"Off\"").contains("done for today");
  }

  @Test
  void anAgentThatDiesOrNeverConnectsLeavesNothingPublic() throws Exception {
    world.cloudflared().control(Map.of("mode", "exit-after-ready", "exitAfterMs", "2500",
        "hostname", "gw-dying.trycloudflare.com"));
    world.publish("stage-readonly");
    long pid = world.cloudflared().pid();
    TunnelWorld.await(() -> "error".equals(world.state().path("state").asString()), "the agent's exit");
    JsonNode error = world.state();
    assertThat(error.path("error").asString()).contains("Cloudflare Tunnel stopped (exit 3)")
        .contains("connection with edge closed");
    assertThat(error.path("publicUrl").isNull()).isTrue();
    FakeAgents.awaitGone(pid);
    assertThat(world.direct(world.publicPort(), "gw-dying.trycloudflare.com", "POST", "/mcp/stage-readonly",
        Map.of("Content-Type", "application/json"), "{}").status()).isEqualTo(404);
    assertThat(api.ok("/api/nav/counters").path("tunnel").asString()).isEqualTo("error");

    world.cloudflared().control(Map.of("mode", "never-ready"));
    assertThat(api.post("/api/tunnel/publish", endpoint("stage-readonly")).status()).isEqualTo(202);
    TunnelWorld.await(() -> "error".equals(world.state().path("state").asString())
        && world.state().path("error").asString().contains("7844"), "the edge to time out");
    FakeAgents.awaitGone(world.cloudflared().pid());
    assertThat(world.state().path("error").asString()).contains("Last log line").contains("failed to dial");
    assertThat(Files.exists(world.home().resolve("run/tunnel.pid"))).isFalse();
  }

  @Test
  void stopWhileTheAgentStartsKillsItAndStaysOff() {
    world.cloudflared().control(Map.of("mode", "never-ready"));
    assertThat(api.post("/api/tunnel/publish", endpoint("stage-readonly")).status()).isEqualTo(202);
    long pid = world.cloudflared().pid();
    assertThat(world.state().path("state").asString()).isEqualTo("starting");
    JsonNode stopped = api.expect(200, HttpMethod.POST, "/api/tunnel/stop", JsonNodeFactory.instance.objectNode());
    assertThat(stopped.path("state").asString()).isEqualTo("off");
    FakeAgents.awaitGone(pid);
    // The interrupted start doesn't turn the stopped tunnel into an error afterwards.
    JsonNode after = world.state();
    assertThat(after.path("state").asString()).isEqualTo("off");
    assertThat(after.path("error").isNull()).isTrue();
    TunnelWorld.await(() -> !Files.exists(world.home().resolve("run/tunnel.pid")), "the pid file to go");
  }

  @Test
  void prodDisabledAndASecondEndpointAreRefused() {
    JsonNode preview = api.expect(200, HttpMethod.POST, "/api/tunnel/publish/preview", endpoint("incident-prod"));
    assertThat(preview.path("refusal").asString()).contains("never published");
    JsonNode refused = api.expect(422, HttpMethod.POST, "/api/tunnel/publish", endpoint("incident-prod"));
    assertThat(refused.path("detail").asString()).contains("prod");
    assertThat(world.bean(EndpointService.class).get("incident-prod").published()).isFalse();

    world.publish("stage-readonly");
    JsonNode busy = api.expect(409, HttpMethod.POST, "/api/tunnel/publish", endpoint("dev"));
    assertThat(busy.path("detail").asString()).contains("one endpoint at a time");
    // Publishing the same one again is not an error: it is already public.
    assertThat(api.post("/api/tunnel/publish", endpoint("stage-readonly")).status()).isEqualTo(202);
    api.expect(404, HttpMethod.POST, "/api/tunnel/publish/preview", endpoint("nope"));
    api.expect(422, HttpMethod.POST, "/api/tunnel/publish", JsonNodeFactory.instance.objectNode());
  }

  @Test
  void aPublishedEndpointCantTakeAProdConnectionAndDeletingItTakesTheTunnelDown() throws Exception {
    world.endpoint("drifting", Env.DEV);
    world.publish("drifting");
    long pid = world.cloudflared().pid();
    world.bean(ai.msrv.gaterook.core.connections.ConnectionService.class).create("drift-prod-db", "pgfake", Env.PROD,
        ai.msrv.gaterook.testfixtures.TestCore.config("db.prod.internal"), Map.of("/credentials/password",
            ai.msrv.gaterook.core.connections.SecretInput.local("pw-drift-prod-0123".toCharArray())), "test");
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> world.bean(
        ai.msrv.gaterook.core.endpoints.EndpointConnections.class).add("drifting", "drift-prod-db", null))
        .hasMessageContaining("prod");
    assertThat(world.state().path("state").asString()).isEqualTo("on");

    world.bean(EndpointService.class).delete("drifting", "drifting", "not needed");
    TunnelWorld.await(() -> "off".equals(world.state().path("state").asString()), "the tunnel to stop");
    FakeAgents.awaitGone(pid);
    assertThat(world.state().path("endpoint").isNull()).isTrue();
  }

  @Test
  void callersIsOneSwitchThatAppliesAtOnce() {
    world.publish("stage-readonly");
    TunnelWorld.Answer refused = world.edge("203.0.113.24", "GET", "/.well-known/oauth-authorization-server",
        Map.of(), null);
    assertThat(refused.status()).isEqualTo(403);
    assertThat(refused.body()).isEmpty();
    assertThat(world.edge(TunnelWorld.CLAUDE, "GET", "/.well-known/oauth-authorization-server", Map.of(), null)
        .status()).isEqualTo(200);
    TunnelWorld.await(() -> world.state().path("refused").asLong() >= 1, "the refusal count");

    JsonNode section = api.expect(200, HttpMethod.PUT, "/api/settings/tunnel/callers", JsonNodeFactory.instance
        .objectNode().put("anthropicOnly", false).put("reason", "testing from home"));
    assertThat(section.path("values").path("anthropicOnly").asBoolean()).isFalse();
    assertThat(world.edge("203.0.113.24", "GET", "/.well-known/oauth-authorization-server", Map.of(), null)
        .status()).isEqualTo(200);
    JsonNode preview = api.expect(200, HttpMethod.POST, "/api/tunnel/publish/preview", endpoint("stage-readonly"));
    assertThat(each(preview.path("warnings"))).anyMatch(w -> w.contains("any address"));
    api.expect(200, HttpMethod.PUT, "/api/settings/tunnel/callers", JsonNodeFactory.instance.objectNode()
        .put("anthropicOnly", true));
    assertThat(world.edge("203.0.113.24", "GET", "/.well-known/oauth-authorization-server", Map.of(), null)
        .status()).isEqualTo(403);
    api.expect(422, HttpMethod.PUT, "/api/settings/tunnel/callers", JsonNodeFactory.instance.objectNode()
        .put("anthropicOnly", "yes"));
    assertThat(api.ok("/api/activity/changes?q=Callers").toString()).contains("Any address")
        .contains("Anthropic IP ranges only").contains("testing from home");
  }

  @Test
  void settingsKeepTokensWriteOnlyAndNamedTunnelsNeedBoth() throws Exception {
    JsonNode s = api.ok("/api/settings/tunnel");
    long version = s.path("version").asLong();
    // Changing the provider or its address while public is refused.
    world.publish("stage-readonly");
    api.expect(409, HttpMethod.PUT, "/api/settings/tunnel", JsonNodeFactory.instance.objectNode()
        .put("provider", "cloudflare-named").put("version", version));
    api.post("/api/tunnel/stop", JsonNodeFactory.instance.objectNode());

    api.expect(422, HttpMethod.PUT, "/api/settings/tunnel", JsonNodeFactory.instance.objectNode()
        .put("provider", "carrier-pigeon").put("version", version));
    api.expect(422, HttpMethod.PUT, "/api/settings/tunnel", JsonNodeFactory.instance.objectNode()
        .put("namedHostname", "not a host").put("version", version));
    api.expect(422, HttpMethod.PUT, "/api/settings/tunnel", JsonNodeFactory.instance.objectNode()
        .put("publicPort", world.localPort()).put("version", version));

    String token = "eyJhIjoiY2FucmFyeS1hY2NvdW50IiwidCI6ImNhbmFyeS10dW5uZWwiLCJzIjoiY2FucmFyeS0wMTIzIn0";
    JsonNode named = api.expect(200, HttpMethod.PUT, "/api/settings/tunnel", JsonNodeFactory.instance.objectNode()
        .put("provider", "cloudflare-named").put("version", version));
    assertThat(named.path("values").path("provider").asString()).isEqualTo("cloudflare-named");
    JsonNode refusal = api.expect(200, HttpMethod.POST, "/api/tunnel/publish/preview", endpoint("stage-readonly"));
    assertThat(refusal.path("refusal").asString()).contains("needs its hostname");
    JsonNode withHost = api.expect(200, HttpMethod.PUT, "/api/settings/tunnel", JsonNodeFactory.instance.objectNode()
        .put("namedHostname", "GW.Example.com").put("version", named.path("version").asLong()));
    assertThat(withHost.path("values").path("namedHostname").asString()).isEqualTo("gw.example.com");
    assertThat(api.expect(200, HttpMethod.POST, "/api/tunnel/publish/preview", endpoint("stage-readonly"))
        .path("refusal").asString()).contains("needs its token");
    JsonNode withToken = api.expect(200, HttpMethod.PUT, "/api/settings/tunnel", JsonNodeFactory.instance
        .objectNode().put("tunnelToken", token).put("version", withHost.path("version").asLong()));
    assertThat(withToken.path("values").path("tunnelTokenStored").asBoolean()).isTrue();
    assertThat(withToken.toString()).doesNotContain(token);
    api.expect(409, HttpMethod.PUT, "/api/settings/tunnel", JsonNodeFactory.instance.objectNode()
        .put("namedHostname", "other.example.com").put("version", version));

    // A named tunnel: the route is the person's, the token goes to the agent in its environment only.
    world.cloudflared().control(Map.of("mode", "ready", "target", "http://127.0.0.1:" + world.publicPort(),
        "hostname", "gw.example.com"));
    JsonNode on = world.publish("stage-readonly");
    assertThat(on.path("host").asString()).isEqualTo("gw.example.com");
    assertThat(on.path("provider").asString()).isEqualTo("cloudflare-named");
    assertThat(world.state().path("stableAddress").asBoolean()).isTrue();
    assertThat(world.state().path("ngrokDomain").isNull()).isTrue();
    assertThat(world.cloudflared().report().getProperty("tunnelToken")).isEqualTo(token);
    assertThat(world.cloudflared().argv()).contains("run").noneMatch(a -> a.contains(token));
    assertThat(world.edge(TunnelWorld.CLAUDE, "GET", "/.well-known/oauth-protected-resource/mcp/stage-readonly",
        Map.of(), null).json().path("resource").asString()).isEqualTo("https://gw.example.com/mcp/stage-readonly");
    api.post("/api/tunnel/stop", JsonNodeFactory.instance.objectNode());

    // No answer anywhere carries the token, and the log doesn't either.
    for (AdminMvc.Answer a : api.answers()) {
      assertThat(a.body()).as(a.method() + " " + a.target()).doesNotContain(token);
      assertThat(AdminMvc.secretLookingValues(a.json())).as(a.target()).isEmpty();
    }
    assertThat(Files.readString(world.home().resolve("logs/cloudflared.log"))).doesNotContain(token);

    // Back to a quick tunnel for the other tests.
    JsonNode current = api.ok("/api/settings/tunnel");
    api.expect(200, HttpMethod.PUT, "/api/settings/tunnel", JsonNodeFactory.instance.objectNode()
        .put("provider", "cloudflare-quick").put("version", current.path("version").asLong()));
  }

  @Test
  void ngrokRunsWithItsAuthtokenAndCallersFromItsForwardedHop() throws Exception {
    JsonNode s = api.ok("/api/settings/tunnel");
    JsonNode ngrok = api.expect(200, HttpMethod.PUT, "/api/settings/tunnel", JsonNodeFactory.instance.objectNode()
        .put("provider", "ngrok").put("ngrokDomain", "gw-acme.ngrok.app").put("ngrokAuthtoken",
            "2cnryNgrokAuthtoken_0123456789abcdefghijKLMN").put("version", s.path("version").asLong()));
    assertThat(ngrok.path("values").path("ngrokAuthtokenStored").asBoolean()).isTrue();
    assertThat(world.state().path("ngrok").path("present").asBoolean()).as("pinned by path").isTrue();
    JsonNode on = world.publish("stage-readonly");
    assertThat(on.path("host").asString()).isEqualTo("gw-acme.ngrok.app");
    assertThat(world.state().path("ngrokDomain").asString()).isEqualTo("gw-acme.ngrok.app");
    assertThat(world.state().path("stableAddress").asBoolean()).as("a reserved domain").isTrue();
    assertThat(world.ngrok().report().getProperty("authtoken")).isEqualTo("2cnryNgrokAuthtoken_0123456789abcdefghijKLMN");
    TunnelWorld.await(() -> !world.state().path("check").isNull(), "the self-test");
    assertThat(world.state().path("check").path("text").asString()).matches("Reachable · \\d+ ms via ngrok");
    // ngrok appends the caller to X-Forwarded-For: a forged first hop doesn't get past the gate.
    assertThat(world.edge("203.0.113.24", "GET", "/.well-known/oauth-authorization-server", Map.of(
        "X-Forwarded-For", TunnelWorld.CLAUDE), null).status()).isEqualTo(403);
    assertThat(world.edge(TunnelWorld.CLAUDE, "GET", "/.well-known/oauth-authorization-server", Map.of(), null)
        .status()).isEqualTo(200);
    api.post("/api/tunnel/stop", JsonNodeFactory.instance.objectNode());
    JsonNode current = api.ok("/api/settings/tunnel");
    api.expect(200, HttpMethod.PUT, "/api/settings/tunnel", JsonNodeFactory.instance.objectNode()
        .put("provider", "cloudflare-quick").put("version", current.path("version").asLong()));
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

  private static List<String> each(JsonNode array) {
    return StreamSupport.stream(array.spliterator(), false).map(JsonNode::asString).toList();
  }

  private static List<String> each(JsonNode array, String field) {
    return StreamSupport.stream(array.spliterator(), false).map(n -> n.path(field).asString()).toList();
  }

  /** Collects the {@code tunnel.state} events of the bus while the test runs. */
  private static AutoCloseable listen(EventBus bus, List<JsonNode> into) {
    return TunnelEvents.listen(bus, "tunnel.state", into);
  }
}
