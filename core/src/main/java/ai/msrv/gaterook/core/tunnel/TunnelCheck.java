package ai.msrv.gaterook.core.tunnel;

import java.time.Instant;
import java.util.List;

/**
 * The self-test after Publish and of "Check public URL" (report 02 §5): DNS, the resource metadata, the authorization
 * server metadata and an anonymous {@code POST /mcp/<slug>} through the public URL, as claude.ai would see them.
 *
 * @param text      "Reachable · 212 ms via Cloudflare (FRA)" or what failed
 * @param latencyMs the anonymous MCP call's round trip, or null when it didn't get that far
 * @param addresses the public IPv4 addresses the hostname resolves to
 * @param steps     each check with its outcome
 * @param warnings  what the person should know about this publication
 */
public record TunnelCheck(boolean ok, String text, Long latencyMs, String colo, List<String> addresses,
    List<Step> steps, List<String> warnings, Instant at) {

  /** One check: {@code dns}, {@code resource-metadata}, {@code authorization-server}, {@code mcp-401}. */
  public record Step(String name, boolean ok, String detail) {}
}
