package com.gaterook.core.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

class ConnectionImportCardsTest {
  private static final String ID = "ci_0123456789ab";

  @Test
  void theCardProjectsOnlyAnOpaqueHandleAndCountWithAnIdOnlyFallbackLink() {
    ObjectNode summary = summary();
    summary.put("submissionToken", "capability-must-stay-private");
    summary.put("importFile", "/private/import.json");
    summary.putObject("manifest").put("password", "private-value");
    summary.put("reviewUrl", "https://untrusted.example");
    var result = ConnectionImportCards.result(summary);
    assertThat(result.structuredContent().propertyNames()).containsExactlyInAnyOrder("kind", "version", "sessionId",
        "status", "count", "reviewUrl", "canOpen");
    assertThat(result.structuredContent().path("reviewUrl").asString())
        .isEqualTo("gaterook://connection-import/" + ID);
    assertThat(result.structuredContent().toString()).doesNotContain("private", "untrusted", "submissionToken", "manifest");
    assertThat(result.content().toString()).contains("Open import in GateRook", "gaterook://connection-import/" + ID)
        .doesNotContain("private", "untrusted");
  }

  @Test
  void invalidInternalSummariesCannotCreateNavigationTargets() {
    for (ObjectNode invalid : List.of(summary().put("sessionId", "../settings"), summary().put("count", -1),
        summary().put("count", 51), summary().put("count", "1"), summary().put("status", "awaiting"))) {
      assertThatThrownBy(() -> ConnectionImportCards.result(invalid)).isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(ConnectionImportCards.result(summary().put("status", "imported")).structuredContent()
        .path("canOpen").asBoolean()).isFalse();
  }

  @Test
  void theResourceHasExactInlineHashesNoExternalCapabilitiesAndOnlyAnOpenButton() throws Exception {
    JsonNode descriptor = ConnectionImportCards.descriptor();
    JsonNode content = ConnectionImportCards.read().path("contents").get(0);
    assertThat(content.path("uri")).isEqualTo(descriptor.path("uri"));
    assertThat(content.path("mimeType").asString()).isEqualTo(McpAppResource.MIME_TYPE);
    String html = content.path("text").asString();
    String script = match(html, "<script[^>]*>(.*?)</script>");
    String style = match(html, "<style>(.*?)</style>");
    String csp = match(html, "Content-Security-Policy\" content=\"([^\"]+)\"");
    assertThat(csp).contains("script-src 'sha256-" + hash(script) + "'", "style-src 'sha256-" + hash(style) + "'")
        .contains("connect-src 'none'", "form-action 'none'").doesNotContain("unsafe-inline", "unsafe-eval");
    assertThat(html).contains("Open import in GateRook", "open_connection_import")
        .doesNotContain("{{", "@font-face", "@charset", "submissionToken", "importFile", "fetch(", "innerHTML");
    assertThat(Pattern.compile("<button\\b").matcher(html).results().count()).isEqualTo(1);
    assertThat(Pattern.compile("<(?:script|img|link|iframe)\\b[^>]*\\s(?:src|href)=").matcher(html).find()).isFalse();
    for (String name : List.of("connectDomains", "resourceDomains", "frameDomains", "baseUriDomains")) {
      assertThat(content.at("/_meta/ui/csp/" + name).size()).isZero();
    }
  }

  private static ObjectNode summary() {
    return JsonNodeFactory.instance.objectNode().put("sessionId", ID).put("status", "review").put("count", 3);
  }

  private static String match(String value, String expression) {
    var matcher = Pattern.compile(expression, Pattern.DOTALL).matcher(value);
    assertThat(matcher.find()).isTrue();
    return matcher.group(1);
  }

  private static String hash(String value) throws Exception {
    return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256")
        .digest(value.getBytes(StandardCharsets.UTF_8)));
  }
}
