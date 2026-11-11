package ai.msrv.gaterook.core.mcpservers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.core.mcpservers.internal.oauth.Discovery;
import ai.msrv.gaterook.core.mcpservers.internal.oauth.OAuthHttp;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The review and the sign-in of an MCP server whose authorization server is the Microsoft identity platform, as
 * Microsoft's remote Azure DevOps server's is (ARCHITECTURE.md §7.6): the person registers an app in their Entra
 * tenant as a public client, with a redirect URI the Entra admin center takes and any port, and the scopes must name
 * the server. An authorization server on any other host keeps the rules of every other server.
 */
class McpServerMicrosoftSignInTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String TENANT = "72f988bf-86f1-41af-91ab-2d7cd011db47";
  private static final URI DEVOPS = URI.create("https://mcp.dev.azure.com/dnceng-public");
  /** Entra's token endpoint authentication methods (2026-11-11): no {@code none}. */
  private static final List<String> ENTRA_METHODS = List.of("client_secret_post", "private_key_jwt",
      "client_secret_basic", "self_signed_tls_client_auth");

  private static Discovery.Server server(String host) {
    String base = "https://" + host + "/" + TENANT;
    return new Discovery.Server(base + "/v2.0", URI.create(base + "/oauth2/v2.0/authorize"),
        URI.create(base + "/oauth2/v2.0/token"), null, null, List.of("openid", "profile", "email", "offline_access"),
        List.of(), ENTRA_METHODS, false, false, false, URI.create(base + "/oauth2/v2.0/devicecode"), List.of());
  }

  private static Discovery.Result devOps(Discovery.Server server, List<String> scopes) {
    Discovery.Resource metadata = new Discovery.Resource(
        URI.create("https://mcp.dev.azure.com/.well-known/oauth-protected-resource/dnceng-public"),
        "https://mcp.dev.azure.com/dnceng-public", List.of(server.issuer()), scopes, null);
    return new Discovery.Result(Discovery.Kind.OAUTH, DEVOPS, server.microsoftIdentityPlatform() ? null
        : metadata.resource(), metadata, server, null, null);
  }

  private static Discovery.Result devOps(Discovery.Server server) {
    return devOps(server, List.of("https://mcp.dev.azure.com/.default"));
  }

  private static ObjectNode auth() {
    return JSON.createObjectNode().put("type", "oauth");
  }

  @Test
  void theReviewAsksForAnEntraAppWithoutSecretAndARedirectTheAdminCenterTakes() {
    McpServerSignIns.AuthView v = McpServerSignIns.review(devOps(server("login.microsoftonline.com")), auth(), null,
        false);

    assertThat(v.kind()).isEqualTo("oauth");
    assertThat(v.registration()).isEqualTo("app-required");
    assertThat(v.secretRequired()).as("a public client").isFalse();
    assertThat(v.redirectUri()).as("Entra ignores a loopback port").isEqualTo("http://localhost/callback");
    assertThat(v.scope()).isEqualTo("https://mcp.dev.azure.com/.default offline_access");
    assertThat(v.authorizationHost()).isEqualTo("login.microsoftonline.com");
    assertThat(v.tokenEndpoint()).isEqualTo("https://login.microsoftonline.com/" + TENANT + "/oauth2/v2.0/token");
    assertThat(v.message()).contains("Microsoft Entra ID doesn't let apps register themselves")
        .contains("http://localhost/callback under Mobile and desktop applications")
        .contains("Allow public client flows").contains("admin consent").contains("Application (client) ID")
        .contains("It needs no client secret").doesNotContain("127.0.0.1").doesNotContain("names another API");
  }

  @Test
  void withTheAppsClientIdTheReviewShowsTheSameRedirect() {
    McpServerSignIns.AuthView v = McpServerSignIns.review(devOps(server("login.microsoftonline.us")),
        auth().put("clientId", "11111111-2222-3333-4444-555555555555"), null, false);

    assertThat(v.registration()).isEqualTo("app");
    assertThat(v.secretRequired()).isFalse();
    assertThat(v.redirectUri()).isEqualTo("http://localhost/callback");
    assertThat(v.message()).isNull();
  }

  @Test
  void atMicrosoftTheSignInListensOnAnyFreePortOfLocalhost() {
    Discovery.Server entra = server("login.microsoftonline.com");

    assertThat(McpServerSignIns.appRedirect(auth(), entra)).hasToString("http://localhost:0/callback");
    // The person's choices stay theirs; the port isn't part of what they register.
    ObjectNode chosen = auth().put("callbackPort", 8400).put("callbackPath", "/gaterook");
    assertThat(McpServerSignIns.appRedirect(chosen, entra)).hasToString("http://localhost:8400/gaterook");
    assertThat(McpServerSignIns.appRedirectToRegister(chosen, entra)).isEqualTo("http://localhost/gaterook");
    ObjectNode address = auth().put("callbackHost", "127.0.0.1");
    assertThat(McpServerSignIns.appRedirectToRegister(address, entra)).as("registered in the app's manifest")
        .isEqualTo("http://127.0.0.1/callback");
  }

  @Test
  void anAuthorizationServerOnAnyOtherHostKeepsTheRulesOfEveryOtherServer() {
    for (String host : List.of("login.microsoftonline.com.attacker.example", "login-microsoftonline.com",
        "evil.login.microsoftonline.com", "login.windows.net")) {
      Discovery.Server other = server(host);
      assertThat(other.microsoftIdentityPlatform()).as(host).isFalse();

      McpServerSignIns.AuthView v = McpServerSignIns.review(devOps(other), auth(), null, false);

      assertThat(v.secretRequired()).as(host).isTrue();
      assertThat(v.redirectUri()).as(host).isEqualTo("http://127.0.0.1:7471/callback");
      assertThat(v.message()).as(host).startsWith(other.issuer() + " doesn't let apps register themselves")
          .doesNotContain("Entra");
      assertThat(McpServerSignIns.appRedirect(auth(), other)).as(host)
          .isEqualTo(McpServerSignIns.redirectFor(auth(), true));
      assertThatCode(() -> McpServerSignIns.requireAudience(devOps(other, List.of("https://graph.microsoft.com/"
          + ".default")), "https://graph.microsoft.com/.default", DEVOPS)).as(host + ": the resource binds its tokens")
          .doesNotThrowAnyException();
    }
  }

  @Test
  void aSignInStartsOnlyWhenTheScopesNameTheServer() {
    Discovery.Server entra = server("login.microsoftonline.com");
    assertThatCode(() -> McpServerSignIns.requireAudience(devOps(entra), "https://mcp.dev.azure.com/.default "
        + "offline_access", DEVOPS)).doesNotThrowAnyException();

    Discovery.Result graph = devOps(entra, List.of("https://graph.microsoft.com/.default"));
    String scope = McpServerSignIns.scope(graph, auth());
    assertThat(scope).isEqualTo("https://graph.microsoft.com/.default offline_access");
    assertThatThrownBy(() -> McpServerSignIns.requireAudience(graph, scope, DEVOPS))
        .isInstanceOf(OAuthHttp.Failure.class).hasMessageContaining("names another API");
    assertThat(McpServerSignIns.review(graph, auth(), null, false).message()).as("the review says so too")
        .contains("Microsoft Entra ID doesn't let apps register themselves").contains("names another API");

    // The person's own scopes are held to the same rule.
    JsonNode person = auth().put("scopes", "User.Read");
    assertThatThrownBy(() -> McpServerSignIns.requireAudience(devOps(entra), McpServerSignIns.scope(devOps(entra),
        person), DEVOPS)).isInstanceOf(OAuthHttp.Failure.class);
  }
}
