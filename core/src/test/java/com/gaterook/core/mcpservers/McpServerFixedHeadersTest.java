package com.gaterook.core.mcpservers;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gaterook.core.platform.ProblemException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Adding or editing a server checks its headers with a fixed value before anything is saved (§7.5). */
class McpServerFixedHeadersTest {

  private static ObjectNode config(String json) {
    return (ObjectNode) JsonMapper.builder().build().readTree(json);
  }

  @Test
  void aSwitchBesideAHeaderFromASecretIsFine() {
    assertThatCode(() -> McpServerService.fixedHeaders(config("""
        {"transport":"http","url":"https://mcp.example.com/mcp","headers":[{"name":"X-Api-Key"}],
         "fixedHeaders":[{"name":"X-MCP-Readonly","text":"true"}]}"""))).doesNotThrowAnyException();
    assertThatCode(() -> McpServerService.fixedHeaders(config("""
        {"transport":"http","url":"https://mcp.example.com/mcp"}"""))).doesNotThrowAnyException();
  }

  @Test
  void aCredentialOrAHeaderFromASecretTwiceIsRefused() {
    assertThatThrownBy(() -> McpServerService.fixedHeaders(config("""
        {"transport":"http","url":"https://mcp.example.com/mcp",
         "fixedHeaders":[{"name":"Authorization","text":"Bearer key"}]}""")))
        .isInstanceOf(ProblemException.class).hasMessageContaining("credential");
    assertThatThrownBy(() -> McpServerService.fixedHeaders(config("""
        {"transport":"http","url":"https://mcp.example.com/mcp","headers":[{"name":"x-mcp-readonly"}],
         "fixedHeaders":[{"name":"X-MCP-Readonly","text":"true"}]}""")))
        .isInstanceOf(ProblemException.class).hasMessageContaining("keep one");
  }

  @Test
  void aCommandHasNoHeaders() {
    assertThatThrownBy(() -> McpServerService.fixedHeaders(config("""
        {"transport":"stdio","command":["npx","-y","server"],
         "fixedHeaders":[{"name":"X-MCP-Readonly","text":"true"}]}""")))
        .isInstanceOf(ProblemException.class).hasMessageContaining("gets variables, not headers");
  }
}
