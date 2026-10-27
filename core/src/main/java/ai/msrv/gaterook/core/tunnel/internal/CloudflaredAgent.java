package ai.msrv.gaterook.core.tunnel.internal;

import ai.msrv.gaterook.core.tunnel.TunnelProvider;
import ai.msrv.gaterook.core.tunnel.TunnelPublication;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code cloudflared} as a child process (ARCHITECTURE.md §7.3, report 02 §7.3).
 *
 * <ul>
 *   <li>Quick tunnel: {@code tunnel --no-autoupdate --config <run>/cloudflared.yml --metrics 127.0.0.1:<m>
 *       --edge-ip-version 4 --protocol auto --url http://127.0.0.1:7466}. The hostname comes from
 *       {@code GET /quicktunnel} (≤ 20 s), a new one on every start.</li>
 *   <li>Named tunnel: the same flags and {@code run}, the token in {@code TUNNEL_TOKEN} (never in argv); the hostname
 *       is the one the person routed to {@code http://127.0.0.1:7466} in Cloudflare.</li>
 * </ul>
 * Either is published only once {@code GET /ready} answers 200 (a connection to the edge, ≤ 30 s): the hostname is
 * known before. {@code --protocol auto} matters: without it a quick tunnel stays on QUIC and never falls back to
 * HTTP/2 over TCP 7844 where UDP is blocked. The config file holds only {@code no-autoupdate: true}, so that
 * {@code ~/.cloudflared/config.yml} of the person is never read.
 */
public final class CloudflaredAgent implements TunnelAgent {

  static final Duration HOSTNAME_WAIT = Duration.ofSeconds(20);
  static final Duration READY_WAIT = Duration.ofSeconds(30);

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final Duration hostnameWait;
  private final Duration readyWait;

  public CloudflaredAgent() {
    this(HOSTNAME_WAIT, READY_WAIT);
  }

  CloudflaredAgent(Duration hostnameWait, Duration readyWait) {
    this.hostnameWait = hostnameWait;
    this.readyWait = readyWait;
  }

  @Override
  public Started start(StartRequest r) throws TunnelException, InterruptedException {
    boolean named = r.provider() == TunnelProvider.CLOUDFLARE_NAMED;
    if (named && (r.secret() == null || r.secret().length == 0)) {
      throw new TunnelException("A named tunnel needs its token: paste it in Settings › Tunnel.");
    }
    if (named && !TunnelPublication.validHost(r.hostname())) {
      throw new TunnelException("A named tunnel needs the hostname you routed to it in Cloudflare.");
    }
    Path config = r.runDir().resolve("cloudflared.yml");
    try {
      Files.writeString(config, "# Written by GateRook: keeps ~/.cloudflared/config.yml out of its tunnel.\n"
          + "no-autoupdate: true\n", StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new TunnelException("Can't write " + config + ": " + e.getMessage());
    }
    int metrics = TunnelAgent.freeLoopbackPort();
    List<String> argv = new ArrayList<>(List.of(r.binary().toString(), "tunnel", "--no-autoupdate", "--config",
        config.toString(), "--metrics", "127.0.0.1:" + metrics, "--edge-ip-version", "4", "--protocol", "auto"));
    if (named) {
      argv.add("run");
    } else {
      argv.addAll(List.of("--url", "http://127.0.0.1:" + r.publicPort()));
    }
    AgentProcess process = AgentProcess.start(argv, AgentProcess.env("TUNNEL_TOKEN", named ? r.secret() : null),
        r.runDir(), r.logsDir().resolve("cloudflared.log"), r.runDir().resolve("tunnel.pid"), config.toString());
    try {
      String host = named ? r.hostname() : awaitHostname(process, metrics);
      awaitReady(process, metrics);
      return new Started(host.toLowerCase(java.util.Locale.ROOT), process, "metrics 127.0.0.1:" + metrics);
    } catch (TunnelException | InterruptedException | RuntimeException e) {
      process.stop();
      throw e;
    }
  }

  private String awaitHostname(AgentProcess process, int metrics) throws TunnelException, InterruptedException {
    long deadline = System.nanoTime() + hostnameWait.toNanos();
    while (System.nanoTime() < deadline) {
      requireAlive(process);
      Optional<HttpResponse<String>> rs = TunnelAgent.localGet(metrics, "/quicktunnel");
      if (rs.isPresent() && rs.get().statusCode() == 200) {
        try {
          String host = JSON.readTree(rs.get().body()).path("hostname").asString("");
          if (!host.isBlank()) {
            if (!TunnelPublication.validHost(host)) {
              throw new TunnelException("cloudflared reported an unusable hostname.");
            }
            return host;
          }
        } catch (JacksonException ignored) {
          // Not ready yet.
        }
      }
      Thread.sleep(250);
    }
    throw new TunnelException(failure(process, "cloudflared didn't get an address from Cloudflare within "
        + hostnameWait.toSeconds() + " s."));
  }

  private void awaitReady(AgentProcess process, int metrics) throws TunnelException, InterruptedException {
    long deadline = System.nanoTime() + readyWait.toNanos();
    while (System.nanoTime() < deadline) {
      if (!process.alive() && edgeUnreachable(process.logTail())) {
        // cloudflared gives up by itself when it can't even find its edge (DNS SRV of argotunnel.com).
        throw new TunnelException(failure(process, "Can't reach Cloudflare's edge: cloudflared stopped (exit "
            + process.exitCode().orElse(-1) + "). Check the firewall, VPN or DNS, then try again."));
      }
      requireAlive(process);
      Optional<HttpResponse<String>> rs = TunnelAgent.localGet(metrics, "/ready");
      if (rs.isPresent() && rs.get().statusCode() == 200) {
        return;
      }
      Thread.sleep(250);
    }
    throw new TunnelException(failure(process, "Can't reach Cloudflare's edge on port 7844. Check the firewall or "
        + "VPN, then try again."));
  }

  /** Whether the agent's log says it couldn't find or reach Cloudflare's edge. */
  static boolean edgeUnreachable(List<String> tail) {
    for (String line : tail) {
      String l = line.toLowerCase(java.util.Locale.ROOT);
      if (l.contains("edge discovery") || l.contains("argotunnel.com") || l.contains(":7844")) {
        return true;
      }
    }
    return false;
  }

  private static void requireAlive(AgentProcess process) throws TunnelException {
    if (!process.alive()) {
      throw new TunnelException(failure(process, "cloudflared stopped (exit " + process.exitCode().orElse(-1)
          + ")."));
    }
  }

  private static String failure(AgentProcess process, String text) {
    String last = TunnelAgent.lastError(process.logTail());
    return last == null ? text : text + " Last log line: " + last;
  }
}
