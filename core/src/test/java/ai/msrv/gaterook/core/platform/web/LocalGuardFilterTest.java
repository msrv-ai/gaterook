package ai.msrv.gaterook.core.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.core.platform.Bootstrap;
import ai.msrv.gaterook.core.platform.LaunchSecret;
import ai.msrv.gaterook.core.platform.SettingStore;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The parts of the guard {@code PlatformIT} can't reach through the one listener M1.2 opens: the public port (its
 * connector arrives with the tunnel, step K3) and a port nobody configured.
 */
class LocalGuardFilterTest {

  private static final int LOCAL = 7465;
  private static final int PUBLIC = 7466;
  private static final Instant NOW = Instant.parse("2026-10-24T08:00:00Z");
  private static final LaunchSecret SECRET = LaunchSecret.fromBase64Url(Base64.getUrlEncoder().withoutPadding()
      .encodeToString("local-guard-filter-test-secret-32".getBytes(StandardCharsets.US_ASCII)));

  private final Bootstrap bootstrap = new Bootstrap(SECRET, "instance-0123456789", null, "off", LOCAL, PUBLIC, false);
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final LocalGuardFilter filter = new LocalGuardFilter(bootstrap,
      // No SettingStore bean: reading the allowed origins fails, and then no origin is allowed.
      new AllowedOrigins(new StaticListableBeanFactory().getBeanProvider(SettingStore.class), bootstrap),
      new ReplayGuard(clock), clock);

  @Test
  void thePublicPortServesOnlyWhatThePublicIngressFilterAccepted() throws Exception {
    MockHttpServletRequest unchecked = request(PUBLIC, "POST", "/mcp/dev");
    unchecked.addHeader("Host", "abc.trycloudflare.com");
    assertThat(run(unchecked).response().getStatus()).isEqualTo(404);

    MockHttpServletRequest signedAdmin = request(PUBLIC, "GET", "/api/events");
    signedAdmin.addHeader(LaunchSecret.HEADER, SECRET.sign("GET", "/api/events", NOW.toEpochMilli(),
        "nonce-0123456789ab"));
    assertThat(run(signedAdmin).response().getStatus()).as("not even a signed admin call").isEqualTo(404);

    MockHttpServletRequest accepted = request(PUBLIC, "POST", "/mcp/dev");
    accepted.addHeader("Host", "abc.trycloudflare.com");
    accepted.setAttribute(LocalGuardFilter.PUBLIC_INGRESS_ACCEPTED, true);
    Outcome outcome = run(accepted);
    assertThat(outcome.passed()).isTrue();
  }

  @Test
  void aPortNobodyConfiguredIsRefused() throws Exception {
    MockHttpServletRequest stray = request(9999, "POST", "/mcp/dev");
    stray.addHeader("Host", "127.0.0.1:9999");

    assertThat(run(stray).response().getStatus()).isEqualTo(404);
  }

  @Test
  void theLocalPortPassesAllowedRoutesAndAnswersOthersWithoutABody() throws Exception {
    MockHttpServletRequest mcp = request(LOCAL, "POST", "/mcp/dev");
    mcp.addHeader("Host", "127.0.0.1:" + LOCAL);
    assertThat(run(mcp).passed()).isTrue();

    MockHttpServletRequest browser = request(LOCAL, "GET", "/api/events");
    browser.addHeader("Host", "127.0.0.1:" + LOCAL);
    browser.addHeader("Origin", "https://evil.example");
    Outcome refused = run(browser);
    assertThat(refused.passed()).isFalse();
    assertThat(refused.response().getStatus()).isEqualTo(403);
    assertThat(refused.response().getContentAsByteArray()).isEmpty();

    MockHttpServletRequest signed = request(LOCAL, "GET", "/api/events");
    signed.addHeader("Host", "localhost:" + LOCAL);
    signed.addHeader(LaunchSecret.HEADER, SECRET.sign("GET", "/api/events", NOW.toEpochMilli(), "nonce-abcdef012345"));
    Outcome admin = run(signed);
    assertThat(admin.passed()).isTrue();
    assertThat(admin.response().getHeader(LocalGuardFilter.INSTANCE_HEADER)).isEqualTo("instance-0123456789");
  }

  private static MockHttpServletRequest request(int port, String method, String uri) {
    MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
    request.setLocalPort(port);
    request.setServerPort(port);
    return request;
  }

  private record Outcome(MockHttpServletResponse response, boolean passed) {}

  private Outcome run(MockHttpServletRequest request) throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(request, response, chain);
    HttpServletRequest passed = (HttpServletRequest) chain.getRequest();
    return new Outcome(response, passed != null);
  }
}
