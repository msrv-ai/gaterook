package ai.msrv.gaterook.core.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class McpAppResourceTest {

  @Test
  void theContentSecurityPolicyAuthorizesExactlyTheAssembledInlineScriptAndStyle() throws Exception {
    String html = content().path("text").asString();
    String csp = onlyMatch(html, "<meta\\s+http-equiv=\"Content-Security-Policy\"\\s+content=\"([^\"]+)\"\\s*>");
    String script = onlyMatch(html, "<script\\b[^>]*>(.*?)</script\\s*>");
    String style = onlyMatch(html, "<style\\b[^>]*>(.*?)</style\\s*>");

    assertThat(onlyMatch(csp, "(?:^|;\\s*)script-src\\s+([^;]+)"))
        .isEqualTo("'sha256-" + sha256(script) + "'");
    assertThat(onlyMatch(csp, "(?:^|;\\s*)style-src\\s+([^;]+)"))
        .isEqualTo("'sha256-" + sha256(style) + "'");
    assertThat(matches(csp, "sha256-[A-Za-z0-9+/=]+")).hasSize(2);
    for (String directive : List.of("default-src", "connect-src", "img-src", "font-src", "frame-src",
        "object-src", "base-uri", "form-action")) {
      assertThat(onlyMatch(csp, "(?:^|;\\s*)" + directive + "\\s+([^;]+)")).isEqualTo("'none'");
    }
    assertThat(csp).doesNotContain("unsafe-inline", "unsafe-eval", "https:", "http:", "*");
  }

  @Test
  void theStaticTemplateHasNoUnresolvedPlaceholdersOrExternalAssetLoads() {
    String html = content().path("text").asString();
    assertThat(content().path("text").asString()).isEqualTo(html);
    assertThat(html).contains("Approve in GateRook", "approval_card_action")
        .doesNotContain("{{CSP}}", "{{KIT_CSS}}", "{{APP_JS}}", "@font-face", "@charset", "/approvals/");
    assertThat(matches(html, "ap_[a-z0-9]{12}")).isEmpty();
    assertThat(matches(html, "<(?:script|link|img|iframe|frame|audio|video|source|object|embed)\\b[^>]*"
        + "\\s(?:src|href|data)\\s*=")).isEmpty();
    assertThat(matches(html, "@import\\b|url\\s*\\(")).isEmpty();
    assertThat(matches(html, "\\b(?:fetch|WebSocket|XMLHttpRequest|EventSource)\\s*\\(")).isEmpty();
    assertThat(matches(html, "<(?:base|form|iframe|frame|object|embed)\\b")).isEmpty();
  }

  @Test
  void thePublishedResourceUsesTheAppMimeTypeAndRequestsNoExternalDomains() {
    JsonNode descriptor = McpAppResource.descriptor();
    assertThat(descriptor.path("uri").asString()).isEqualTo("ui://gaterook/approval-card/v1.html");
    assertThat(descriptor.path("mimeType").asString()).isEqualTo("text/html;profile=mcp-app");
    ObjectNode resource = content();
    assertThat(resource.path("uri")).isEqualTo(descriptor.path("uri"));
    assertThat(resource.path("mimeType")).isEqualTo(descriptor.path("mimeType"));
    JsonNode csp = resource.at("/_meta/ui/csp");
    for (String domain : List.of("connectDomains", "resourceDomains", "frameDomains", "baseUriDomains")) {
      assertThat(csp.path(domain).isArray()).as(domain).isTrue();
      assertThat(csp.path(domain).size()).as(domain).isZero();
    }
    // Resource responses must not share mutable metadata between callers.
    ((ObjectNode) resource.path("_meta")).remove("ui");
    assertThat(content().at("/_meta/ui/csp")).isEqualTo(csp);
  }

  private static ObjectNode content() {
    JsonNode contents = McpAppResource.read().path("contents");
    assertThat(contents.size()).isEqualTo(1);
    return (ObjectNode) contents.get(0);
  }

  private static String onlyMatch(String text, String regex) {
    List<String> matches = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(text)
        .results().map(match -> match.group(1)).toList();
    assertThat(matches).hasSize(1);
    return matches.getFirst();
  }

  private static List<String> matches(String text, String regex) {
    return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(text)
        .results().map(match -> match.group()).toList();
  }

  private static String sha256(String text) throws Exception {
    return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256")
        .digest(text.getBytes(StandardCharsets.UTF_8)));
  }
}
