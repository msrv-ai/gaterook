package ai.msrv.gaterook.core.tunnel.internal;

import ai.msrv.gaterook.core.tunnel.TunnelCheck;
import ai.msrv.gaterook.core.tunnel.TunnelProvider;
import ai.msrv.gaterook.core.tunnel.TunnelPublication;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * "Check public URL" (report 02 §5): what claude.ai will see, from this computer through the internet.
 * <ol>
 *   <li>DNS: the hostname has an A record and every address is globally routable — claude.ai refuses private, CGNAT and
 *       loopback addresses, a mix of them, and hosts with AAAA records only.</li>
 *   <li>{@code GET /.well-known/oauth-protected-resource/mcp/<slug>}: 200, {@code resource} is the endpoint's URL.</li>
 *   <li>{@code GET /.well-known/oauth-authorization-server}: 200, {@code issuer} is the tunnel's origin.</li>
 *   <li>{@code POST /mcp/<slug>} without a token: 401 naming the resource metadata, and no redirect.</li>
 * </ol>
 * Our own address isn't in Anthropic's ranges, so each request carries a single-use {@code X-Gateway-Selftest} nonce
 * that lets it past the Callers gate (and only that gate).
 */
public final class SelfTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final TunnelProbe probe;
  private final CallerGate gate;
  private final Clock clock;

  public SelfTest(TunnelProbe probe, CallerGate gate, Clock clock) {
    this.probe = probe;
    this.gate = gate;
    this.clock = clock;
  }

  public TunnelCheck run(TunnelPublication.Publication p, List<String> warnings) throws InterruptedException {
    List<TunnelCheck.Step> steps = new ArrayList<>();
    List<String> addresses = new ArrayList<>();
    String failed = null;
    Long latency = null;
    String colo = null;

    try {
      List<InetAddress> all = probe.resolve(p.host());
      boolean ipv4 = false;
      String bad = null;
      for (InetAddress a : all) {
        ipv4 |= a instanceof Inet4Address;
        if (a instanceof Inet4Address) {
          addresses.add(a.getHostAddress());
        }
        if (!global(a)) {
          bad = a.getHostAddress();
        }
      }
      if (!ipv4) {
        failed = p.host() + " has no IPv4 address; claude.ai connects over IPv4 only.";
      } else if (bad != null) {
        failed = p.host() + " resolves to " + bad + ", which isn't a public address.";
      }
      steps.add(new TunnelCheck.Step("dns", failed == null, failed == null ? String.join(", ", addresses) : failed));
    } catch (IOException e) {
      failed = p.host() + " doesn't resolve yet. A new tunnel can take a minute to appear in DNS.";
      steps.add(new TunnelCheck.Step("dns", false, failed));
    }

    if (failed == null) {
      failed = step(steps, "resource-metadata", () -> {
        TunnelProbe.Answer a = get(p.origin() + "/.well-known/oauth-protected-resource/mcp/" + p.endpointSlug());
        if (a.status() != 200) {
          return "The resource metadata answered " + a.status() + " instead of 200.";
        }
        return p.resource().equals(field(a.body(), "resource")) ? null
            : "The resource metadata names another resource than " + p.resource() + ".";
      });
    }
    if (failed == null) {
      failed = step(steps, "authorization-server", () -> {
        TunnelProbe.Answer a = get(p.origin() + "/.well-known/oauth-authorization-server");
        if (a.status() != 200) {
          return "The authorization server metadata answered " + a.status() + " instead of 200.";
        }
        return p.origin().equals(field(a.body(), "issuer")) ? null
            : "The authorization server names another issuer than " + p.origin() + ".";
      });
    }
    if (failed == null) {
      TunnelProbe.Answer[] mcp = new TunnelProbe.Answer[1];
      failed = step(steps, "mcp-401", () -> {
        TunnelProbe.Answer a = probe.send("POST", URI.create(p.resource()), Map.of("Content-Type",
            "application/json", "Accept", "application/json, text/event-stream", CallerGate.SELFTEST_HEADER,
            gate.selftestNonce()), "{\"jsonrpc\":\"2.0\",\"id\":\"selftest\",\"method\":\"ping\"}");
        mcp[0] = a;
        if (a.status() >= 300 && a.status() < 400) {
          return "The endpoint redirects (" + a.status() + "): claude.ai would lose its sign-in on the way.";
        }
        if (a.status() != 401) {
          return "Without a token the endpoint answered " + a.status() + " instead of 401.";
        }
        String challenge = a.header("www-authenticate");
        return challenge != null && challenge.contains("resource_metadata=") ? null
            : "The 401 doesn't tell claude.ai where to sign in (no resource_metadata).";
      });
      if (mcp[0] != null) {
        latency = mcp[0].millis();
        colo = colo(mcp[0].header("cf-ray"));
      }
    }

    boolean ok = failed == null;
    String via = p.provider().cloudflare() ? "Cloudflare" + (colo == null ? "" : " (" + colo + ")")
        : p.provider() == TunnelProvider.NGROK ? "ngrok" : p.provider().label();
    String text = ok ? "Reachable · " + latency + " ms via " + via : "Not reachable yet: " + failed;
    return new TunnelCheck(ok, text, latency, colo, List.copyOf(addresses), List.copyOf(steps), List.copyOf(warnings),
        clock.instant());
  }

  private interface Check {
    String run() throws IOException, InterruptedException;
  }

  private static String step(List<TunnelCheck.Step> steps, String name, Check check) throws InterruptedException {
    String problem;
    try {
      problem = check.run();
    } catch (IOException e) {
      problem = "The public URL didn't answer: " + (e.getMessage() == null ? e.getClass().getSimpleName()
          : e.getMessage()) + ".";
    }
    steps.add(new TunnelCheck.Step(name, problem == null, problem == null ? "ok" : problem));
    return problem;
  }

  private TunnelProbe.Answer get(String url) throws IOException, InterruptedException {
    return probe.send("GET", URI.create(url), Map.of("Accept", "application/json", CallerGate.SELFTEST_HEADER,
        gate.selftestNonce()), null);
  }

  private static String field(String body, String name) {
    try {
      JsonNode n = JSON.readTree(body);
      return n.path(name).asString(null);
    } catch (JacksonException e) {
      return null;
    }
  }

  /** {@code 8d2c1f0a9b7e3c4d-FRA} → {@code FRA}. */
  static String colo(String cfRay) {
    if (cfRay == null) {
      return null;
    }
    int dash = cfRay.lastIndexOf('-');
    String code = dash < 0 ? "" : cfRay.substring(dash + 1).strip();
    return code.matches("[A-Z]{3}") ? code : null;
  }

  /** Globally routable: not loopback, private, link-local, CGNAT, unique-local, multicast or unspecified. */
  static boolean global(InetAddress a) {
    if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
        || a.isMulticastAddress()) {
      return false;
    }
    byte[] b = a.getAddress();
    if (b.length == 4) {
      int first = b[0] & 0xff;
      int second = b[1] & 0xff;
      return !(first == 100 && second >= 64 && second <= 127) && first != 0 && !(first == 192 && second == 0
          && (b[2] & 0xff) == 0) && !(first == 198 && (second == 18 || second == 19)) && first < 224;
    }
    return (b[0] & 0xfe) != 0xfc;
  }
}
