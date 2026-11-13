package com.gaterook.connectors.support.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** What an HTTP server's headers with a fixed value may be: switches, not credentials or the transport's headers. */
class FixedHeadersTest {

  private static FixedHeaders.Header h(String name, String text) {
    return new FixedHeaders.Header(name, text);
  }

  @Test
  void aServersSwitchesAreFine() {
    assertThat(FixedHeaders.problem(List.of(h("X-MCP-Readonly", "true"), h("X-MCP-Toolsets", "repos,issues")),
        List.of("X-Api-Key"))).isNull();
    assertThat(FixedHeaders.problem(List.of(), List.of())).isNull();
  }

  @Test
  void aCredentialBelongsInAHeaderFromASecret() {
    for (String name : List.of("Authorization", "authorization", "Proxy-Authorization", "Cookie")) {
      assertThat(FixedHeaders.problem(List.of(h(name, "x")), List.of())).as(name).contains("credential");
    }
  }

  @Test
  void theTransportsHeadersStayItsOwn() {
    for (String name : List.of("Host", "Content-Type", "Accept", "Mcp-Session-Id", "MCP-Protocol-Version",
        "Mcp-Param-Region", "Last-Event-ID")) {
      assertThat(FixedHeaders.problem(List.of(h(name, "x")), List.of())).as(name).contains("GateRook sets it");
    }
  }

  @Test
  void eachNameOnceAndNotAlsoFromASecret() {
    assertThat(FixedHeaders.problem(List.of(h("X-A", "1"), h("x-a", "2")), List.of())).contains("given twice");
    assertThat(FixedHeaders.problem(List.of(h("X-Api-Key", "1")), List.of("x-api-key"))).contains("keep one");
  }
}
