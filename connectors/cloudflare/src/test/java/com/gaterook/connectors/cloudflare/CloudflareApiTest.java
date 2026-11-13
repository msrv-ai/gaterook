package com.gaterook.connectors.cloudflare;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gaterook.connector.api.ConnectorException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import mockwebserver3.MockResponse;
import org.junit.jupiter.api.Test;

/** The API client: its URLs, its errors, the request budget and the deadline. */
class CloudflareApiTest extends CloudflareTestBase {

  @Test
  void urlsAreBuiltFromEncodedSegmentsUnderTheBase() {
    CloudflareApi api = new CloudflareApi(null);
    assertThat(api.uri(List.of("accounts", ACCOUNT, "storage", "kv", "namespaces", RECORD, "values", "a/b c"),
        Map.of()).toString()).isEqualTo("https://api.cloudflare.com/client/v4/accounts/" + ACCOUNT
        + "/storage/kv/namespaces/" + RECORD + "/values/a%2Fb%20c");
    java.util.LinkedHashMap<String, String> q = new java.util.LinkedHashMap<>();
    q.put("name.contains", "a b&c");
    q.put("skipped", null);
    q.put("page", "2");
    assertThat(api.uri(List.of("zones"), q).toString())
        .isEqualTo("https://api.cloudflare.com/client/v4/zones?name.contains=a%20b%26c&page=2");
    assertThat(CloudflareApi.encode("é")).isEqualTo("%C3%A9");
    for (String bad : new String[] {"", ".", ".."}) {
      assertThatThrownBy(() -> api.uri(List.of("zones", bad), Map.of()))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void onlyTheBaseIsEverRequested() {
    cf.zones(ACCOUNT, ZONE, "example.com");
    Call c = new Call(cfg(null), new CloudflareSettings(ACCOUNT, List.of()), new CloudflareApi(cf.base()),
        new Zones(), secrets, TestCtx.of());
    for (String foreign : new String[] {"https://evil.example/client/v4/zones", cf.base().toString().replace(
        "/client/v4", "/other"), cf.base() + "/../admin"}) {
      assertThatThrownBy(() -> c.api().get(c, URI.create(foreign), "zones"))
          .as(foreign).isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(cf.requests).isEmpty();
  }

  @Test
  void errorsSayWhatHappenedAndWhatToDo() {
    cf.fail("GET", "/zones/a", 400, 7003, "Could not route");
    cf.fail("GET", "/zones/b", 401, 1000, "Invalid API Token");
    cf.on("GET", "/zones/c", r -> new MockResponse.Builder().code(429).addHeader("Retry-After", "37")
        .body("{\"success\":false,\"errors\":[{\"code\":971,\"message\":\"Please wait\"}]}").build());
    cf.fail("GET", "/zones/d", 503, 0, "Service unavailable");
    cf.fail("GET", "/zones/f", 403, 9109, "Invalid access token");
    cf.on("GET", "/zones/e", r -> new MockResponse.Builder().code(502).body("<html>bad gateway</html>").build());
    Call c = new Call(cfg(null), new CloudflareSettings(ACCOUNT, List.of()), new CloudflareApi(cf.base()),
        new Zones(), secrets, TestCtx.of());
    assertError(c, "a", ConnectorException.Kind.BAD_INPUT, "Cloudflare refused the request (400): 7003 Could not route");
    assertError(c, "b", ConnectorException.Kind.RESOURCE_ERROR, "token is active and not expired");
    assertError(c, "c", ConnectorException.Kind.RESOURCE_ERROR, "Try again in 37 seconds.");
    assertError(c, "d", ConnectorException.Kind.RESOURCE_UNAVAILABLE, "failed (503)");
    assertError(c, "e", ConnectorException.Kind.RESOURCE_ERROR, "isn't JSON: <html>bad gateway</html>");
    // As api.cloudflare.com answers a resource request with a token it doesn't know: the token, not a permission.
    assertThatThrownBy(() -> c.api().get(c, c.api().uri("zones", "f"), "zones"))
        .hasMessageContaining("refused the API token (403): 9109 Invalid access token")
        .hasMessageNotContaining("The token needs");
  }

  private static void assertError(Call c, String zone, ConnectorException.Kind kind, String text) {
    assertThatThrownBy(() -> c.api().get(c, c.api().uri("zones", zone), "zones"))
        .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.kind()).isEqualTo(kind))
        .hasMessageContaining(text);
  }

  @Test
  void theRequestBudgetIsTakenBeforeEachRequest() {
    cf.zones(ACCOUNT, ZONE, "example.com");
    TestCtx ctx = TestCtx.of().quota((k, n) -> false);
    Call c = new Call(cfg(null), new CloudflareSettings(ACCOUNT, List.of()), new CloudflareApi(cf.base()),
        new Zones(), secrets, ctx);
    assertThatThrownBy(() -> c.api().get(c, c.api().uri("zones", ZONE), "zones"))
        .hasMessageContaining("share of Cloudflare API requests for this account (180 per minute) is used up");
    assertThat(ctx.quotaKeys).containsExactly("cloudflare:" + ACCOUNT + "@180");
    assertThat(cf.requests).isEmpty();
  }

  @Test
  void aSlowAnswerEndsAtTheDeadline() {
    cf.on("GET", "/zones/slow", r -> new MockResponse.Builder().headersDelay(3, java.util.concurrent.TimeUnit.SECONDS)
        .body("{}").build());
    Call c = new Call(cfg(null), new CloudflareSettings(ACCOUNT, List.of()), new CloudflareApi(cf.base()),
        new Zones(), secrets, TestCtx.of().timeout(Duration.ofMillis(600)));
    assertThatThrownBy(() -> c.api().get(c, c.api().uri("zones", "slow"), "zones"))
        .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.kind())
            .isEqualTo(ConnectorException.Kind.TIMEOUT));
  }
}
