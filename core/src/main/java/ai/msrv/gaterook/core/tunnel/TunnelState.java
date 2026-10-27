package ai.msrv.gaterook.core.tunnel;

import java.time.Instant;
import java.util.List;

/**
 * {@code GET /api/tunnel}: what the tunnel does now, for Clients › claude.ai, Settings › Tunnel, the sidebar and the
 * "Tunnel is public" banner.
 *
 * @param state     {@code off}, {@code starting}, {@code on} or {@code error}
 * @param host      the public hostname while on (the last one while off, for "address changed")
 * @param publicUrl {@code https://<host>/mcp/<endpoint>} while on
 * @param since     when the current state began
 * @param error     what went wrong ({@code error}), in the person's words
 * @param refused   server calls the Callers gate refused in this session
 * @param calls     calls the published endpoint answered in this session
 * @param check     the last self-test of this session, or null
 */
public record TunnelState(TunnelProvider provider, String state, String host, String publicUrl, String endpoint,
    Instant since, boolean anthropicOnly, Agent cloudflared, Agent ngrok, String error, long refused, long calls,
    TunnelCheck check, List<Session> history) {

  /** A tunnel agent: its pinned version, whether it is on disk and matches its pin. */
  public record Agent(String version, boolean present, boolean sha256Ok, String error) {}

  /** One time the tunnel was on: Clients › claude.ai "Tunnel history". */
  public record Session(Instant from, Instant to, String endpoint, String host, String provider, long calls,
      long refused) {}

  public boolean on() {
    return "on".equals(state);
  }
}
