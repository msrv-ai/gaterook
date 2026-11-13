package com.gaterook.e2e.mcpservers;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OAuthMcpServerTest {

  @Test
  void restrictsCallbacksToRegisteredLoopbackDestinations() {
    OAuthMcpServer.Client registered = new OAuthMcpServer.Client("test",
        List.of("http://localhost:12345/callback"), null);
    assertThat(registered.redirect("http://localhost:12345/callback"))
        .isEqualTo("http://localhost:12345/callback");
    assertThat(registered.redirect("http://localhost:12346/callback")).isNull();
    OAuthMcpServer.Client metadata = new OAuthMcpServer.Client("test",
        List.of("http://localhost/callback", "http://127.0.0.1/callback"), null);
    assertThat(metadata.redirect("http://127.0.0.1:12346/callback"))
        .isEqualTo("http://127.0.0.1:12346/callback");
    assertThat(metadata.redirect(null)).isNull();
    for (String redirect : List.of("http://localhost:12345@evil.example/callback",
        "http://attacker@localhost:12345/callback", "http://localhost.evil.example:12345/callback",
        "http://localhost:12345/callback?next=https://evil.example", "http://localhost:12345/callback#fragment",
        "http://localhost:12345/other", "https://localhost:12345/callback", "http://localhost:0/callback",
        "http://localhost:99999/callback", "http://localhost:invalid/callback", "not a URI")) {
      assertThat(metadata.redirect(redirect)).as(redirect).isNull();
    }
  }

  @Test
  void registrationAndBothAuthorizationResponsesUseValidatedCallbacks(@TempDir Path directory) throws Exception {
    try (OAuthMcpServer server = new OAuthMcpServer(directory);
         HttpClient client = HttpClient.newHttpClient()) {
      HttpResponse<String> invalidRegistration = client.send(HttpRequest.newBuilder(URI.create(server.base
          + "/register")).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(
              "{\"redirect_uris\":[\"http://localhost:12345@evil.example/callback\"]}")).build(),
          HttpResponse.BodyHandlers.ofString());
      assertThat(invalidRegistration.statusCode()).isEqualTo(400);
      assertThat(server.clients).isEmpty();

      String callback = "http://127.0.0.1:12345/callback";
      server.clients.put("fixture", new OAuthMcpServer.Client("fixture", List.of(callback), null));
      String authorize = server.base + "/authorize?client_id=fixture&redirect_uri=" + enc(callback)
          + "&state=" + enc("safe&extra=1");
      HttpResponse<String> error = get(client, authorize);
      assertThat(error.statusCode()).isEqualTo(302);
      assertThat(error.headers().firstValue("Location"))
          .hasValue(callback + "?error=invalid_request&state=safe%26extra%3D1");
      HttpResponse<String> success = get(client, authorize + "&response_type=code&code_challenge_method=S256"
          + "&code_challenge=test-challenge&resource=" + enc(server.base + "/mcp"));
      assertThat(success.statusCode()).isEqualTo(302);
      assertThat(success.headers().firstValue("Location").orElseThrow())
          .startsWith(callback + "?code=trk-code-").contains("&state=safe%26extra%3D1&iss=");

      HttpResponse<String> attack = get(client, server.base + "/authorize?client_id=fixture&redirect_uri="
          + enc("http://127.0.0.1:12345@evil.example/callback"));
      assertThat(attack.statusCode()).isEqualTo(400);
      assertThat(attack.headers().firstValue("Location")).isEmpty();
    }
  }

  private static HttpResponse<String> get(HttpClient client, String uri) throws Exception {
    return client.send(HttpRequest.newBuilder(URI.create(uri)).build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String enc(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
