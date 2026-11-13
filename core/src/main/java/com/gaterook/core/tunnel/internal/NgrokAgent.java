package com.gaterook.core.tunnel.internal;

import com.gaterook.core.tunnel.TunnelPublication;
import java.io.IOException;
import java.net.URI;
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
 * {@code NGROK_AUTHTOKEN} only. The config switches off the agent's local web interface and API
 * ({@code web_addr: false}), its update check and its console. The public URL comes from the agent's own log: its
 * JSON line {@code "msg":"started tunnel"} carries the {@code url}, and the agent writes it only once its session to
 * the edge is up and the edge serves the endpoint, which is ngrok's "ready". Only this run's lines count
 * ({@link AgentProcess#followLog()}).
 *
 * <p>The local API stays off because it has no authentication (ngrok offers none): any process on this computer that
 * reached its port — a sandboxed MCP server too, whose sandbox closes GateRook's files but not the network — could
 * open more tunnels on the person's authtoken ({@code POST /api/tunnels}) or repoint this one. The agent's log is in
 * GateRook's home, which that sandbox can't open. Inspection is off as well: the agent keeps none of the requests and
 * responses it carries — claude.ai's bearer tokens, the token endpoint's access and refresh tokens, MCP results —
 * of which the core keeps only hashes (ARCHITECTURE.md §9).
 *
 * <p>What remains is the OS account's trust boundary (ARCHITECTURE.md §9): a process of the same account that isn't
 * sandboxed may still read the agent's environment, the authtoken in it.
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
    Path config = r.runDir().resolve("ngrok.yml");
    try {
      Files.writeString(config, "# Written by GateRook for its tunnel: no local API, no update check, no console.\n"
          + "version: \"3\"\nagent:\n  web_addr: false\n  update_check: false\n  console_ui: false\n",
          StandardCharsets.UTF_8);
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
      String host = awaitTunnel(process, domain);
      return new Started(host, process, "local API off");
    } catch (TunnelException | InterruptedException | RuntimeException e) {
      process.stop();
      throw e;
    }
  }

  private String awaitTunnel(AgentProcess process, String domain) throws TunnelException, InterruptedException {
    AgentProcess.LogFollower log = process.followLog();
    long deadline = System.nanoTime() + readyWait.toNanos();
    while (System.nanoTime() < deadline) {
      if (!process.alive()) {
        throw new TunnelException(failure(process, "ngrok stopped (exit " + process.exitCode().orElse(-1) + ")."));
      }
      for (String line : log.next()) {
        Optional<String> host = startedHost(line);
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

  /** The host of a {@code "started tunnel"} line of the agent's JSON log whose {@code url} is https, else empty. */
  static Optional<String> startedHost(String line) {
    if (!line.contains("started tunnel")) {
      return Optional.empty();
    }
    try {
      JsonNode entry = JSON.readTree(line);
      if (entry.isObject() && entry.path("msg").asString("").equals("started tunnel")) {
        String url = entry.path("url").asString("");
        if (url.startsWith("https://")) {
          String host = URI.create(url).getHost();
          if (host != null && TunnelPublication.validHost(host)) {
            return Optional.of(host.toLowerCase(Locale.ROOT));
          }
        }
      }
    } catch (JacksonException | IllegalArgumentException ignored) {
      // Not a line of ngrok's JSON log.
    }
    return Optional.empty();
  }

  private static String failure(AgentProcess process, String text) {
    String last = TunnelAgent.lastError(process.logTail());
    return last == null ? text : text + " Last log line: " + last;
  }
}
