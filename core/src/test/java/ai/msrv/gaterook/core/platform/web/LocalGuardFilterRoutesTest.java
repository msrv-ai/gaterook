package ai.msrv.gaterook.core.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.core.platform.web.LocalGuardFilter.Route;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The allowlist of the local listener (ARCHITECTURE.md §5 step 1): what exists, and every way around it that doesn't. */
class LocalGuardFilterRoutesTest {

  @ParameterizedTest
  @CsvSource({
      "POST, /mcp/dev, MCP",
      "GET, /mcp/incident-prod, MCP",
      "GET, /approvals/ap_3kq7m2x9vt4p, APPROVAL_PAGE",
      "POST, /approvals/ap_3kq7m2x9vt4p, NONE",
      "GET, /approvals/ap_3kq7m2x9vt4, NONE",
      "GET, /approvals/ap_3KQ7M2X9VT4P, NONE",
      "GET, /oauth/authorize, OAUTH",
      "GET, /oauth/authorize/status, OAUTH",
      "GET, /.well-known/oauth-authorization-server, OAUTH",
      "GET, /.well-known/oauth-protected-resource/mcp/dev, OAUTH",
      "GET, /api/events, API",
      "POST, /api/connections/test, API",
      "POST, /internal/bridge/tokens, INTERNAL",
      "GET, /error, NONE",
      "GET, /actuator/health, NONE",
      "GET, /api, NONE",
      "GET, /, NONE",
      "GET, /index.html, NONE",
      "POST, /mcp/Dev, NONE",
      "POST, /mcp/-dev, NONE",
      "POST, /mcp/dev/extra, NONE",
      "GET, /.well-known/openid-configuration, NONE",
  })
  void routes(String method, String uri, Route expected) {
    assertThat(LocalGuardFilter.route(method, uri)).isEqualTo(expected);
  }

  @ParameterizedTest
  @ValueSource(strings = {"/api/events/", "/api//events", "/api/./events", "/api/../mcp/dev", "/api/%2e%2e/x",
      "/api/a%2Fb", "/api/a%5cb", "/api/a;jsessionid=1", "/mcp/dev;x", "/api/%252e", "/api/a%00", "api/events",
      "/api\\events", "/mcp/dev/"})
  void malformedPathsMatchNothing(String uri) {
    assertThat(LocalGuardFilter.wellFormed(uri)).isFalse();
    assertThat(LocalGuardFilter.route("GET", uri)).isEqualTo(Route.NONE);
  }

  @ParameterizedTest
  @CsvSource({
      "127.0.0.1:7465, true",
      "localhost:7465, true",
      "LOCALHOST:7465, true",
      "127.0.0.1:7466, false",
      "127.0.0.1, false",
      "evil.example:7465, false",
      "abc.trycloudflare.com, false",
      "[::1]:7465, false",
      "127.0.0.1:7465.evil.example, false",
  })
  void hosts(String host, boolean allowed) {
    assertThat(LocalGuardFilter.allowedHost(host, 7465)).isEqualTo(allowed);
  }
}
