package ai.msrv.gaterook.core.tunnel.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.core.tunnel.TunnelCheck;
import ai.msrv.gaterook.core.tunnel.TunnelProvider;
import ai.msrv.gaterook.core.tunnel.TunnelPublication;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** "Check public URL" (report 02 §5): every step claude.ai's own connection takes, in order, with its verdict. */
class SelfTestTest {

  private static final TunnelPublication.Publication P = new TunnelPublication.Publication(
      "gw-smoke-7f3k.trycloudflare.com", "stage-readonly", TunnelProvider.CLOUDFLARE_QUICK);

  /** A probe that answers from a table and remembers what it was asked. */
  private static final class Probe implements TunnelProbe {
    final List<String> asked = new ArrayList<>();
    final List<Map<String, String>> headers = new ArrayList<>();
    List<InetAddress> addresses;
    Function<String, Answer> answers;

    Probe(List<InetAddress> addresses, Function<String, Answer> answers) {
      this.addresses = addresses;
      this.answers = answers;
    }

    @Override
    public List<InetAddress> resolve(String host) throws UnknownHostException {
      if (addresses == null) {
        throw new UnknownHostException(host);
      }
      return addresses;
    }

    @Override
    public Answer send(String method, URI uri, Map<String, String> h, String body) throws IOException {
      asked.add(method + " " + uri);
      headers.add(h);
      Answer a = answers.apply(method + " " + uri.getPath());
      if (a == null) {
        throw new IOException("connection reset");
      }
      return a;
    }
  }

  private static TunnelProbe.Answer answer(int status, String body, Map<String, List<String>> headers) {
    return new TunnelProbe.Answer(status, headers, body, 212);
  }

  private static Function<String, TunnelProbe.Answer> healthy() {
    return q -> switch (q) {
      case "GET /.well-known/oauth-protected-resource/mcp/stage-readonly" -> answer(200,
          "{\"resource\":\"https://gw-smoke-7f3k.trycloudflare.com/mcp/stage-readonly\"}", Map.of());
      case "GET /.well-known/oauth-authorization-server" -> answer(200,
          "{\"issuer\":\"https://gw-smoke-7f3k.trycloudflare.com\"}", Map.of());
      case "POST /mcp/stage-readonly" -> answer(401, "{}", Map.of("www-authenticate", List.of(
          "Bearer resource_metadata=\"https://gw-smoke-7f3k.trycloudflare.com/.well-known/oauth-protected-resource/"
              + "mcp/stage-readonly\", scope=\"mcp\""), "cf-ray", List.of("8d2c1f0a9b7e3c4d-FRA")));
      default -> null;
    };
  }

  private static List<InetAddress> addresses(String... ips) {
    return java.util.Arrays.stream(ips).map(InetAddress::ofLiteral).toList();
  }

  @Test
  void aReachableTunnelReportsItsLatencyAndCloudflaresColo() throws Exception {
    Probe probe = new Probe(addresses("104.16.230.132", "2606:4700::6810:e684"), healthy());
    CallerGate gate = new CallerGate(Clock.systemUTC());
    TunnelCheck c = new SelfTest(probe, gate, Clock.systemUTC()).run(P, List.of("a warning"));
    assertThat(c.ok()).isTrue();
    assertThat(c.text()).isEqualTo("Reachable · 212 ms via Cloudflare (FRA)");
    assertThat(c.colo()).isEqualTo("FRA");
    assertThat(c.addresses()).containsExactly("104.16.230.132");
    assertThat(c.steps()).extracting(TunnelCheck.Step::name).containsExactly("dns", "resource-metadata",
        "authorization-server", "mcp-401");
    assertThat(c.warnings()).containsExactly("a warning");
    // Each request carried a fresh nonce for the Callers gate, and each is spent by the gate only once.
    List<String> nonces = probe.headers.stream().map(h -> h.get(CallerGate.SELFTEST_HEADER)).toList();
    assertThat(nonces).hasSize(3).doesNotHaveDuplicates().allMatch(n -> n != null && n.length() == 22);
    assertThat(gate.admits("203.0.113.24", nonces.getFirst())).isTrue();
    assertThat(gate.admits("203.0.113.24", nonces.getFirst())).isFalse();
  }

  @Test
  void eachFailureNamesWhatClaudeAiWouldTripOver() throws Exception {
    CallerGate gate = new CallerGate(Clock.systemUTC());
    assertThat(run(new Probe(null, healthy()), gate).text()).contains("doesn't resolve yet");
    assertThat(run(new Probe(addresses("2606:4700::6810:e684"), healthy()), gate).text()).contains("no IPv4");
    assertThat(run(new Probe(addresses("104.16.230.132", "10.0.0.5"), healthy()), gate).text())
        .contains("10.0.0.5, which isn't a public address");
    assertThat(run(new Probe(addresses("100.64.1.2"), healthy()), gate).text()).contains("isn't a public address");

    Function<String, TunnelProbe.Answer> wrongResource = q -> q.startsWith("GET /.well-known/oauth-protected")
        ? answer(200, "{\"resource\":\"https://elsewhere/mcp/x\"}", Map.of()) : healthy().apply(q);
    assertThat(run(new Probe(addresses("104.16.230.132"), wrongResource), gate).text())
        .contains("names another resource");

    Function<String, TunnelProbe.Answer> redirect = q -> q.startsWith("POST") ? answer(308, "", Map.of())
        : healthy().apply(q);
    TunnelCheck r = run(new Probe(addresses("104.16.230.132"), redirect), gate);
    assertThat(r.ok()).isFalse();
    assertThat(r.text()).contains("redirects (308)");

    Function<String, TunnelProbe.Answer> open = q -> q.startsWith("POST") ? answer(200, "{}", Map.of())
        : healthy().apply(q);
    assertThat(run(new Probe(addresses("104.16.230.132"), open), gate).text()).contains("answered 200 instead of 401");

    Function<String, TunnelProbe.Answer> bare = q -> q.startsWith("POST") ? answer(401, "", Map.of(
        "www-authenticate", List.of("Bearer realm=\"mcp-gateway\""))) : healthy().apply(q);
    assertThat(run(new Probe(addresses("104.16.230.132"), bare), gate).text()).contains("no resource_metadata");

    Function<String, TunnelProbe.Answer> down = q -> q.contains("authorization-server") ? null : healthy().apply(q);
    TunnelCheck d = run(new Probe(addresses("104.16.230.132"), down), gate);
    assertThat(d.text()).contains("didn't answer: connection reset");
    assertThat(d.steps()).extracting(TunnelCheck.Step::ok).containsExactly(true, true, false);
  }

  @Test
  void ngrokHasNoColo() throws Exception {
    TunnelPublication.Publication ng = new TunnelPublication.Publication("gw-acme.ngrok.app", "stage-readonly",
        TunnelProvider.NGROK);
    Function<String, TunnelProbe.Answer> answers = q -> switch (q) {
      case "GET /.well-known/oauth-protected-resource/mcp/stage-readonly" -> answer(200,
          "{\"resource\":\"https://gw-acme.ngrok.app/mcp/stage-readonly\"}", Map.of());
      case "GET /.well-known/oauth-authorization-server" -> answer(200, "{\"issuer\":\"https://gw-acme.ngrok.app\"}",
          Map.of());
      default -> answer(401, "", Map.of("www-authenticate", List.of("Bearer resource_metadata=\"x\"")));
    };
    TunnelCheck c = new SelfTest(new Probe(addresses("3.125.102.39"), answers), new CallerGate(Clock.systemUTC()),
        Clock.systemUTC()).run(ng, List.of());
    assertThat(c.text()).isEqualTo("Reachable · 212 ms via ngrok");
    assertThat(SelfTest.colo("8d2c1f0a9b7e3c4d-FRA")).isEqualTo("FRA");
    assertThat(SelfTest.colo("garbage")).isNull();
    assertThat(SelfTest.colo(null)).isNull();
  }

  private static TunnelCheck run(Probe probe, CallerGate gate) throws InterruptedException {
    return new SelfTest(probe, gate, Clock.systemUTC()).run(P, List.of());
  }
}
