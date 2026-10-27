package ai.msrv.gaterook.core.tunnel.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.core.platform.Bootstrap;
import ai.msrv.gaterook.core.platform.Ingress;
import ai.msrv.gaterook.core.platform.LaunchSecret;
import ai.msrv.gaterook.core.tunnel.TunnelProvider;
import ai.msrv.gaterook.core.tunnel.TunnelPublication;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The deny-by-default of the tunnel port (ARCHITECTURE.md §7.3): host, raw path, body limit, the local port. */
class PublicIngressFilterTest {

  private static final int LOCAL = 7465;
  private static final int PUBLIC = 7466;
  private static final TunnelPublication.Publication SMOKE =
      new TunnelPublication.Publication("gw-smoke-7f3k.trycloudflare.com", "smoke");

  private static final String SECRET = "Y29yZS1wcm9jZXNzLWl0LWxhdW5jaC1zZWNyZXQtMzI";

  private final Bootstrap bootstrap = new Bootstrap(LaunchSecret.fromBase64Url(SECRET), "test-instance-0123456789",
      null, "off", LOCAL, PUBLIC, false);
  private final TunnelPublication tunnel = new TunnelPublication("", "");
  private final CallerGate gate = new CallerGate(Clock.systemUTC());
  private final PublicIngressFilter filter = new PublicIngressFilter(bootstrap, tunnel, gate);

  @Test
  void onlyThePublishedEndpointAndTheOauthPathsOfTheTunnelsHostPass() {
    String host = SMOKE.host();
    for (String path : List.of("/mcp/smoke", "/oauth/authorize", "/oauth/authorize/status", "/oauth/token",
        "/oauth/register", "/oauth/revoke", "/.well-known/oauth-authorization-server",
        "/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp/smoke")) {
      assertThat(PublicIngressFilter.allowed(SMOKE, host, path)).as(path).isTrue();
    }
    assertThat(PublicIngressFilter.allowed(SMOKE, host + ":443", "/mcp/smoke")).as("a port").isTrue();
    assertThat(PublicIngressFilter.allowed(SMOKE, "GW-SMOKE-7F3K.trycloudflare.com", "/mcp/smoke")).isTrue();
    for (String path : List.of("/mcp/other", "/mcp/smoke/", "/mcp/smoke;jsessionid=1", "/mcp/%73moke", "/mcp//smoke",
        "/mcp/../mcp/smoke", "/MCP/smoke", "/oauth/userinfo", "/api/health", "/internal/files/grant", "/error", "/",
        "/actuator/health", "/approvals/ap_000000000000", "/.well-known/oauth-protected-resource/mcp/other")) {
      assertThat(PublicIngressFilter.allowed(SMOKE, host, path)).as(path).isFalse();
    }
    assertThat(PublicIngressFilter.allowed(SMOKE, "127.0.0.1:" + PUBLIC, "/mcp/smoke")).as("not the tunnel").isFalse();
    assertThat(PublicIngressFilter.allowed(SMOKE, null, "/mcp/smoke")).isFalse();
  }

  @Test
  void withoutAPublicationEverythingOnThePublicPortIs404AndTheLocalPortIsUntouched() throws Exception {
    assertThat(run(request(PUBLIC, "/mcp/smoke", SMOKE.host(), null)).status).isEqualTo(404);
    Result local = run(request(LOCAL, "/api/health", "127.0.0.1:" + LOCAL, null));
    assertThat(local.passed).isTrue();
    assertThat(local.marked).as("the local guard decides there").isFalse();
  }

  @Test
  void aPublishedEndpointPassesMarkedAndItsBodyIsBounded() throws Exception {
    tunnel.publish(SMOKE);
    Result ok = run(request(PUBLIC, "/mcp/smoke", SMOKE.host(), "{}"));
    assertThat(ok.passed).isTrue();
    assertThat(ok.marked).isTrue();
    assertThat(run(request(PUBLIC, "/api/health", SMOKE.host(), null)).status).isEqualTo(404);

    String tooLarge = "x".repeat(PublicIngressFilter.MAX_BODY + 1);
    MockHttpServletRequest large = request(PUBLIC, "/mcp/smoke", SMOKE.host(), tooLarge);
    assertThat(run(large).status).isEqualTo(413);

    // A chunked body has no length up front: reading past the limit fails.
    List<ServletRequest> seen = new ArrayList<>();
    filter.doFilter(chunked(), new MockHttpServletResponse(), (req, res) -> seen.add(req));
    assertThatThrownBy(() -> seen.getFirst().getInputStream().readAllBytes()).isInstanceOf(IOException.class)
        .hasMessageContaining("larger than");

    // Read by the controller, the overrun comes back through the filter: an empty 413, not the container's 500.
    MockHttpServletResponse answered = new MockHttpServletResponse();
    answered.getWriter().write("partial");
    filter.doFilter(chunked(), answered, (req, res) -> {
      try {
        req.getInputStream().readNBytes(1 << 20);
      } catch (IOException e) {
        throw new IllegalStateException("handler failed", e);
      }
    });
    assertThat(answered.getStatus()).isEqualTo(413);
    assertThat(answered.getContentAsString()).isEmpty();

    // Any other failure is not the filter's to answer.
    assertThatThrownBy(() -> filter.doFilter(request(PUBLIC, "/mcp/smoke", SMOKE.host(), "{}"),
        new MockHttpServletResponse(), (req, res) -> {
          throw new IOException("broken pipe");
        })).isInstanceOf(IOException.class).hasMessage("broken pipe");
  }

  @Test
  void serverCallsFromOutsideAnthropicsRangesGetAnEmpty403AndTheBrowserPagesNever() throws Exception {
    TunnelPublication.Publication cf = new TunnelPublication.Publication(SMOKE.host(), "smoke",
        TunnelProvider.CLOUDFLARE_QUICK);
    tunnel.publish(cf);
    MockHttpServletRequest inside = request(PUBLIC, "/mcp/smoke", cf.host(), "{}");
    inside.addHeader("CF-Connecting-IP", "160.79.104.10");
    Result in = run(inside);
    assertThat(in.passed).isTrue();
    assertThat(in.remote).as("the edge's caller, not cloudflared").isEqualTo("160.79.104.10");

    for (String path : List.of("/mcp/smoke", "/oauth/token", "/oauth/register", "/oauth/revoke",
        "/.well-known/oauth-authorization-server", "/.well-known/oauth-protected-resource/mcp/smoke")) {
      MockHttpServletRequest outside = request(PUBLIC, path, cf.host(), "{}");
      outside.addHeader("CF-Connecting-IP", "203.0.113.24");
      Result r = run(outside);
      assertThat(r.status).as(path).isEqualTo(403);
      assertThat(r.passed).as(path).isFalse();
      assertThat(r.body).as(path).isEmpty();
    }
    assertThat(gate.refusedCount()).isEqualTo(6);

    // No header (a local process on 7466), a forged list, a name: refused, never resolved.
    MockHttpServletRequest none = request(PUBLIC, "/mcp/smoke", cf.host(), "{}");
    none.setRemoteAddr("160.79.104.10");
    assertThat(run(none).status).isEqualTo(403);
    for (String forged : List.of("160.79.104.10, 203.0.113.24", "anthropic.com", "160.79.104.10:443", "")) {
      MockHttpServletRequest r = request(PUBLIC, "/mcp/smoke", cf.host(), "{}");
      r.addHeader("CF-Connecting-IP", forged);
      assertThat(run(r).status).as(forged).isEqualTo(403);
    }

    // The person's own browser opens the sign-in from anywhere: the consent here guards it.
    for (String path : List.of("/oauth/authorize", "/oauth/authorize/status")) {
      MockHttpServletRequest browser = request(PUBLIC, path, cf.host(), null);
      browser.setMethod("GET");
      browser.addHeader("CF-Connecting-IP", "203.0.113.24");
      Result r = run(browser);
      assertThat(r.passed).as(path).isTrue();
      assertThat(r.remote).isEqualTo("203.0.113.24");
    }

    // The self-test calls from this computer once per nonce.
    String nonce = gate.selftestNonce();
    MockHttpServletRequest selftest = request(PUBLIC, "/mcp/smoke", cf.host(), "{}");
    selftest.addHeader("CF-Connecting-IP", "198.51.100.7");
    selftest.addHeader(CallerGate.SELFTEST_HEADER, nonce);
    assertThat(run(selftest).passed).isTrue();
    MockHttpServletRequest replay = request(PUBLIC, "/mcp/smoke", cf.host(), "{}");
    replay.addHeader("CF-Connecting-IP", "198.51.100.7");
    replay.addHeader(CallerGate.SELFTEST_HEADER, nonce);
    assertThat(run(replay).status).as("single use").isEqualTo(403);

    // "Any address" lets everyone through; the allowlist of paths still holds.
    gate.setAnthropicOnly(false);
    MockHttpServletRequest any = request(PUBLIC, "/mcp/smoke", cf.host(), "{}");
    any.addHeader("CF-Connecting-IP", "203.0.113.24");
    assertThat(run(any).passed).isTrue();
    MockHttpServletRequest api = request(PUBLIC, "/api/health", cf.host(), null);
    api.addHeader("CF-Connecting-IP", "160.79.104.10");
    assertThat(run(api).status).isEqualTo(404);
  }

  @Test
  void ngrokCallersComeFromTheLastForwardedHopItsEdgeAppended() throws Exception {
    TunnelPublication.Publication ng = new TunnelPublication.Publication("gw-acme.ngrok.app", "smoke",
        TunnelProvider.NGROK);
    tunnel.publish(ng);
    MockHttpServletRequest spoofed = request(PUBLIC, "/mcp/smoke", ng.host(), "{}");
    spoofed.addHeader("X-Forwarded-For", "160.79.104.10, 203.0.113.24");
    spoofed.addHeader("CF-Connecting-IP", "160.79.104.10");
    assertThat(run(spoofed).status).as("the client's own hop is ignored").isEqualTo(403);
    MockHttpServletRequest real = request(PUBLIC, "/mcp/smoke", ng.host(), "{}");
    real.addHeader("X-Forwarded-For", "203.0.113.24, 160.79.104.10");
    assertThat(run(real).passed).isTrue();

    // The client's own line first, the edge's appended as a line of its own: the edge's hop counts.
    MockHttpServletRequest twoLines = request(PUBLIC, "/mcp/smoke", ng.host(), "{}");
    twoLines.addHeader("X-Forwarded-For", "160.79.104.10");
    twoLines.addHeader("X-Forwarded-For", "203.0.113.24");
    assertThat(run(twoLines).status).isEqualTo(403);
    MockHttpServletRequest edgeLast = request(PUBLIC, "/mcp/smoke", ng.host(), "{}");
    edgeLast.addHeader("X-Forwarded-For", "203.0.113.24");
    edgeLast.addHeader("X-Forwarded-For", "160.79.104.10");
    assertThat(run(edgeLast).passed).isTrue();
  }

  @Test
  void aCloudflareCallerIsOneConnectingIpLineOrNobody() throws Exception {
    TunnelPublication.Publication cf = new TunnelPublication.Publication(SMOKE.host(), "smoke",
        TunnelProvider.CLOUDFLARE_NAMED);
    tunnel.publish(cf);
    // A client's line first and the edge's second (or the other way round): which is the edge's is unknown.
    for (List<String> lines : List.of(List.of("160.79.104.10", "203.0.113.24"),
        List.of("203.0.113.24", "160.79.104.10"))) {
      MockHttpServletRequest r = request(PUBLIC, "/oauth/token", cf.host(), "{}");
      lines.forEach(v -> r.addHeader("CF-Connecting-IP", v));
      assertThat(run(r).status).as(lines.toString()).isEqualTo(403);
    }
    MockHttpServletRequest one = request(PUBLIC, "/oauth/token", cf.host(), "{}");
    one.addHeader("CF-Connecting-IP", "160.79.104.10");
    assertThat(run(one).passed).isTrue();
  }

  @Test
  void callerAddressesAreLiteralsOnly() {
    assertThat(PublicIngressFilter.literal(" 160.79.104.10 ")).isEqualTo("160.79.104.10");
    assertThat(PublicIngressFilter.literal("2607:6bc0::10")).isEqualTo("2607:6bc0:0:0:0:0:0:10");
    assertThat(PublicIngressFilter.literal("localhost")).isNull();
    assertThat(PublicIngressFilter.literal("1.2.3.4.5")).isNull();
    assertThat(PublicIngressFilter.literal(null)).isNull();
  }

  /** A body over the limit without a length, as a chunked request arrives. */
  private static ServletRequest chunked() {
    MockHttpServletRequest body = request(PUBLIC, "/mcp/smoke", SMOKE.host(), null);
    body.setContent("y".repeat(PublicIngressFilter.MAX_BODY + 10).getBytes(StandardCharsets.US_ASCII));
    return new HttpServletRequestWrapper(body) {
      @Override
      public long getContentLengthLong() {
        return -1;
      }

      @Override
      public int getContentLength() {
        return -1;
      }
    };
  }

  private record Result(int status, boolean passed, boolean marked, String remote, String body) {}

  private Result run(MockHttpServletRequest request) throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    List<ServletRequest> passed = new ArrayList<>();
    filter.doFilter(request, response, (req, res) -> passed.add(req));
    return new Result(response.getStatus(), !passed.isEmpty(),
        !passed.isEmpty() && Boolean.TRUE.equals(passed.getFirst().getAttribute(Ingress.TUNNEL_ACCEPTED_ATTRIBUTE)),
        passed.isEmpty() ? null : passed.getFirst().getRemoteAddr(), response.getContentAsString());
  }

  private static MockHttpServletRequest request(int port, String path, String host, String body) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
    request.setLocalPort(port);
    request.addHeader("Host", host);
    if (body != null) {
      request.setContent(body.getBytes(StandardCharsets.UTF_8));
      request.addHeader("Content-Length", body.length());
    }
    return request;
  }
}
