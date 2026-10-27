package ai.msrv.gaterook.core.tunnel.internal;

import ai.msrv.gaterook.core.tunnel.TunnelPublication;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The ngrok agent as a child process (ARCHITECTURE.md A6, §7.3): {@code ngrok http 127.0.0.1:7466 --config
 * <run>/ngrok.yml --log stdout --log-format json --inspect=false [--url https://<reserved domain>]}, the authtoken in
 * {@code NGROK_AUTHTOKEN} only. The config pins the agent's local API to a loopback port of ours and switches its
 * update check and console off. The public URL comes from that API ({@code GET /api/tunnels}); the agent lists a
 * tunnel only once its session to the edge is up, which is ngrok's "ready".
 *
 * <p>Inspection is off: by default the agent keeps every request and response it carries — claude.ai's bearer tokens,
 * the token endpoint's access and refresh tokens, MCP results — and hands them to anyone on this computer who asks its
 * local API ({@code /api/requests/http}), while the core keeps only their hashes (ARCHITECTURE.md §9).
 */
public final class NgrokAgent implements TunnelAgent {

  static final Duration READY_WAIT = Duration.ofSeconds(30);

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final Duration readyWait;

  public NgrokAgent() {
    this(READY_WAIT);
  }

  NgrokAgent(Duration readyWait) {
    this.readyWait = readyWait;
  }

  @Override
  public Started start(StartRequest r) throws TunnelException, InterruptedException {
    if (r.secret() == null || r.secret().length == 0) {
      throw new TunnelException("ngrok needs your authtoken: paste it in Settings › Tunnel.");
    }
    String domain = r.hostname() == null || r.hostname().isBlank() ? null : r.hostname().toLowerCase(Locale.ROOT);
    if (domain != null && !TunnelPublication.validHost(domain)) {
      throw new TunnelException("The ngrok domain must be a hostname like gw-acme.ngrok.app.");
    }
    int web = TunnelAgent.freeLoopbackPort();
    Path config = r.runDir().resolve("ngrok.yml");
    try {
      Files.writeString(config, "# Written by MCP Gateway for its tunnel.\nversion: \"3\"\nagent:\n"
          + "  web_addr: 127.0.0.1:" + web + "\n  update_check: false\n  console_ui: false\n", StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new TunnelException("Can't write " + config + ": " + e.getMessage());
    }
    List<String> argv = new ArrayList<>(List.of(r.binary().toString(), "http", "127.0.0.1:" + r.publicPort(),
        "--config", config.toString(), "--log", "stdout", "--log-format", "json", "--inspect=false"));
    if (domain != null) {
      argv.addAll(List.of("--url", "https://" + domain));
    }
    AgentProcess process = AgentProcess.start(argv, AgentProcess.env("NGROK_AUTHTOKEN", r.secret()), r.runDir(),
        r.logsDir().resolve("ngrok.log"), r.runDir().resolve("tunnel.pid"), config.toString());
    try {
      String host = awaitTunnel(process, web, domain);
      return new Started(host, process, "agent API 127.0.0.1:" + web);
    } catch (TunnelException | InterruptedException | RuntimeException e) {
      process.stop();
      throw e;
    }
  }

  private String awaitTunnel(AgentProcess process, int web, String domain) throws TunnelException,
      InterruptedException {
    long deadline = System.nanoTime() + readyWait.toNanos();
    while (System.nanoTime() < deadline) {
      if (!process.alive()) {
        throw new TunnelException(failure(process, "ngrok stopped (exit " + process.exitCode().orElse(-1) + ")."));
      }
      Optional<HttpResponse<String>> rs = TunnelAgent.localGet(web, "/api/tunnels");
      if (rs.isPresent() && rs.get().statusCode() == 200) {
        Optional<String> host = httpsHost(rs.get().body());
        if (host.isPresent()) {
          if (domain != null && !domain.equals(host.get())) {
            throw new TunnelException("ngrok published " + host.get() + " instead of " + domain + ".");
          }
          return host.get();
        }
      }
      Thread.sleep(250);
    }
    throw new TunnelException(failure(process, "ngrok didn't connect to its edge within " + readyWait.toSeconds()
        + " s."));
  }

  /** The host of the first {@code https://} tunnel the agent lists. */
  static Optional<String> httpsHost(String body) {
    try {
      for (JsonNode t : JSON.readTree(body).path("tunnels")) {
        String url = t.path("public_url").asString("");
        if (url.startsWith("https://")) {
          String host = URI.create(url).getHost();
          if (host != null && TunnelPublication.validHost(host)) {
            return Optional.of(host.toLowerCase(Locale.ROOT));
          }
        }
      }
    } catch (JacksonException | IllegalArgumentException ignored) {
      // Not ready, or not ngrok answering.
    }
    return Optional.empty();
  }

  private static String failure(AgentProcess process, String text) {
    String last = TunnelAgent.lastError(process.logTail());
    return last == null ? text : text + " Last log line: " + last;
  }
}
