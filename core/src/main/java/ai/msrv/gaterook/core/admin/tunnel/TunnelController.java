package ai.msrv.gaterook.core.admin.tunnel;

import ai.msrv.gaterook.core.admin.ApiRequestException;
import ai.msrv.gaterook.core.admin.Reasons;
import ai.msrv.gaterook.core.platform.Bootstrap;
import ai.msrv.gaterook.core.platform.EventBus;
import ai.msrv.gaterook.core.platform.LocalListener;
import ai.msrv.gaterook.core.tunnel.PublishPreview;
import ai.msrv.gaterook.core.tunnel.TunnelCheck;
import ai.msrv.gaterook.core.tunnel.TunnelProvider;
import ai.msrv.gaterook.core.tunnel.TunnelService;
import ai.msrv.gaterook.core.tunnel.TunnelSettings;
import ai.msrv.gaterook.core.tunnel.TunnelState;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Instant;
import java.util.List;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The tunnel for the UI (ARCHITECTURE.md §6 tunnel / OAuth): state, what Publish would make public, Publish (202, the
 * rest follows as {@code tunnel.state} events), stop, the self-test, and Settings › Tunnel with Callers — the single
 * switch of the Anthropic IP gate. Publishing always goes through the confirmation in the UI: there is no other way to
 * turn the tunnel on, and Undo never publishes again.
 */
@RestController
@RegisterReflectionForBinding({TunnelController.Tunnel.class, TunnelController.Callers.class,
    TunnelController.Agent.class, TunnelController.Session.class, TunnelController.Check.class,
    TunnelController.Step.class, TunnelController.Preview.class, TunnelController.Connection.class,
    TunnelController.Tools.class, TunnelController.Section.class, TunnelController.CheckAnswer.class})
class TunnelController {

  /**
   * {@code GET /api/tunnel}.
   *
   * @param ngrokDomain   the reserved ngrok domain of Settings › Tunnel while the provider is ngrok, else {@code null}
   * @param stableAddress whether the public address stays the same across starts (a named Cloudflare tunnel, ngrok
   *                      with a reserved domain): a new public port or a restart leaves claude.ai's URL unchanged
   */
  record Tunnel(String provider, String state, String host, String publicUrl, String endpoint, Instant since,
      Callers callers, Agent cloudflared, Agent ngrok, String error, long refused, long calls, Check check,
      List<Session> history, int publicPort, String rangesVerified, String ngrokDomain, boolean stableAddress) {}

  record Callers(boolean anthropicOnly, List<String> ranges) {}

  record Agent(String version, boolean present, boolean sha256Ok, String error) {}

  record Session(Instant from, Instant to, String endpoint, String host, String provider, long calls, long refused) {}

  record Check(boolean ok, String text, Long latencyMs, String colo, List<String> addresses, List<Step> steps,
      List<String> warnings, Instant at) {}

  record Step(String name, boolean ok, String detail) {}

  /** {@code POST /api/tunnel/check}: what the UI shows next to "Check public URL". */
  record CheckAnswer(boolean ok, String text, List<String> warnings, Check check) {}

  record Preview(String endpoint, String env, String provider, List<String> publicPaths, List<String> local,
      List<Connection> connections, Tools tools, String risk, String refusal, boolean anthropicOnly,
      List<String> warnings) {}

  record Connection(String name, String env) {}

  record Tools(int read, int approval, int off) {}

  /** Settings › Tunnel as a section: {@code values} and the {@code version} to send back. */
  record Section(String name, ObjectNode values, long version, Instant updatedAt) {}

  record PublishRequest(String endpoint, String reason) {}

  record StopRequest(String reason) {}

  private final TunnelService tunnel;
  private final Bootstrap bootstrap;
  private final LocalListener listener;
  private final EventBus events;

  TunnelController(TunnelService tunnel, Bootstrap bootstrap, LocalListener listener, EventBus events) {
    this.tunnel = tunnel;
    this.bootstrap = bootstrap;
    this.listener = listener;
    this.events = events;
  }

  @GetMapping("/api/tunnel")
  Tunnel get() {
    return view(tunnel.state());
  }

  @PostMapping("/api/tunnel/publish/preview")
  Preview preview(@RequestBody PublishRequest request) {
    PublishPreview p = tunnel.preview(slug(request == null ? null : request.endpoint()));
    return new Preview(p.endpoint(), p.env(), p.provider().id(), p.publicPaths(), p.local(),
        p.connections().stream().map(c -> new Connection(c.name(), c.env())).toList(),
        new Tools(p.tools().read(), p.tools().approval(), p.tools().off()), p.risk(), p.refusal(), p.anthropicOnly(),
        p.warnings());
  }

  @PostMapping("/api/tunnel/publish")
  ResponseEntity<Tunnel> publish(@RequestBody PublishRequest request) {
    String reason = Reasons.check(request == null ? null : request.reason());
    TunnelState state = tunnel.publish(slug(request == null ? null : request.endpoint()), reason);
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(view(state));
  }

  @PostMapping("/api/tunnel/stop")
  Tunnel stop(@RequestBody(required = false) StopRequest request) {
    return view(tunnel.stop(Reasons.check(request == null ? null : request.reason())));
  }

  @PostMapping("/api/tunnel/check")
  CheckAnswer check() {
    TunnelCheck c = tunnel.check();
    if (c == null) {
      throw ApiRequestException.conflict("Tunnel off", "The tunnel stopped while it was being checked.");
    }
    return new CheckAnswer(c.ok(), c.text(), c.warnings(), check(c));
  }

  // ── Settings › Tunnel ───────────────────────────────────────────────────────────────────────────────────────

  @GetMapping("/api/settings/tunnel")
  Section settings() {
    return section(tunnel.settings());
  }

  /**
   * {@code PUT /api/settings/tunnel {provider?, namedHostname?, ngrokDomain?, tunnelToken?, ngrokAuthtoken?,
   * publicPort?, version, reason?}}. The tokens are stored as secrets of the tunnel and never answered; the public
   * port is Electron's ({@code desktop.json}): a new one restarts the core ({@code core.restart-required}).
   */
  @PutMapping("/api/settings/tunnel")
  Section update(@RequestBody JsonNode body) {
    if (body == null || !body.isObject()) {
      throw ApiRequestException.unprocessable("Invalid settings", "Send the section's fields as an object.", "");
    }
    JsonNode version = body.get("version");
    if (version == null || !version.isIntegralNumber()) {
      throw ApiRequestException.unprocessable("Version missing", "Send the version you edited.", "/version");
    }
    String reason = Reasons.check(text(body, "reason"));
    Integer publicPort = null;
    if (body.has("publicPort") && !body.get("publicPort").isNull()) {
      publicPort = checkPort(body.get("publicPort"));
    }
    char[] tunnelToken = chars(body, "tunnelToken");
    char[] ngrokAuthtoken = chars(body, "ngrokAuthtoken");
    TunnelSettings saved = tunnel.updateSettings(new TunnelService.SettingsUpdate(text(body, "provider"),
        text(body, "namedHostname"), text(body, "ngrokDomain"), tunnelToken, ngrokAuthtoken), version.asLong(),
        reason);
    if (publicPort != null && publicPort != bootstrap.publicPort()) {
      ObjectNode data = JsonNodeFactory.instance.objectNode();
      data.put("port", listener.port());
      data.put("publicPort", publicPort);
      data.put("reason", "public-port");
      events.publish("core.restart-required", data);
    }
    return section(saved);
  }

  /** {@code PUT /api/settings/tunnel/callers {anthropicOnly, reason?}}: the only Callers switch; applies at once. */
  @PutMapping("/api/settings/tunnel/callers")
  Section callers(@RequestBody JsonNode body) {
    JsonNode value = body == null ? null : body.get("anthropicOnly");
    if (value == null || !value.isBoolean()) {
      throw ApiRequestException.unprocessable("Invalid setting", "anthropicOnly is true or false.", "/anthropicOnly");
    }
    return section(tunnel.setCallers(value.asBoolean(), Reasons.check(text(body, "reason"))));
  }

  // ── Views ───────────────────────────────────────────────────────────────────────────────────────────────────

  private Tunnel view(TunnelState s) {
    // The address settings can't change while the tunnel is on (409), so they are the running tunnel's.
    String ngrokDomain = s.provider() == TunnelProvider.NGROK ? tunnel.settings().ngrokDomain() : null;
    return new Tunnel(s.provider() == null ? null : s.provider().id(), s.state(), s.host(), s.publicUrl(),
        s.endpoint(), s.since(), new Callers(s.anthropicOnly(), TunnelService.anthropicRanges()),
        agent(s.cloudflared()), agent(s.ngrok()), s.error(), s.refused(), s.calls(),
        s.check() == null ? null : check(s.check()), s.history().stream().map(h -> new Session(h.from(), h.to(),
            h.endpoint(), h.host(), h.provider(), h.calls(), h.refused())).toList(), bootstrap.publicPort(),
        TunnelService.anthropicRangesVerified(), ngrokDomain,
        s.provider() != null && s.provider().stableAddress(ngrokDomain));
  }

  private static Agent agent(TunnelState.Agent a) {
    return a == null ? null : new Agent(a.version(), a.present(), a.sha256Ok(), a.error());
  }

  private static Check check(TunnelCheck c) {
    return new Check(c.ok(), c.text(), c.latencyMs(), c.colo(), c.addresses(),
        c.steps().stream().map(s -> new Step(s.name(), s.ok(), s.detail())).toList(), c.warnings(), c.at());
  }

  private Section section(TunnelSettings s) {
    ObjectNode values = JsonNodeFactory.instance.objectNode();
    values.put("provider", s.provider().id());
    values.put("namedHostname", s.namedHostname());
    values.put("ngrokDomain", s.ngrokDomain());
    values.put("anthropicOnly", s.anthropicOnly());
    values.put("endpoint", s.endpoint());
    values.put("publicPort", bootstrap.publicPort());
    values.put("tunnelTokenStored", s.tunnelTokenStored());
    values.put("ngrokAuthtokenStored", s.ngrokAuthtokenStored());
    return new Section("tunnel", values, s.version(), null);
  }

  private int checkPort(JsonNode port) {
    if (!port.isIntegralNumber() || port.asInt() < 1024 || port.asInt() > 65535) {
      throw ApiRequestException.unprocessable("Invalid port", "Use a port from 1024 to 65535.", "/publicPort");
    }
    int value = port.asInt();
    if (value == listener.port()) {
      throw ApiRequestException.unprocessable("Port taken", value + " is the local API's port. Choose "
          + "another one.", "/publicPort");
    }
    if (value != bootstrap.publicPort() && !free(value)) {
      throw ApiRequestException.unprocessable("Port in use", "Another program listens on " + value + ". Choose "
          + "another one.", "/publicPort");
    }
    return value;
  }

  private static boolean free(int port) {
    try (ServerSocket socket = new ServerSocket()) {
      socket.setReuseAddress(false);
      socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  private static String slug(String endpoint) {
    if (endpoint == null || endpoint.isBlank()) {
      throw ApiRequestException.unprocessable("Endpoint missing", "Name the endpoint to publish.", "/endpoint");
    }
    return endpoint;
  }

  private static String text(JsonNode body, String field) {
    JsonNode n = body.get(field);
    return n == null || n.isNull() ? null : n.asString();
  }

  /** A token field as characters; the JSON tree still holds its text until the request is collected. */
  private static char[] chars(JsonNode body, String field) {
    JsonNode n = body.get(field);
    if (n == null || n.isNull()) {
      return null;
    }
    if (!n.isString() || n.asString().length() > 4096) {
      throw ApiRequestException.unprocessable("Invalid token", field + " is the token's text.", "/" + field);
    }
    return n.asString().strip().toCharArray();
  }
}
