package ai.msrv.gaterook.connectors.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ToolResult;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResultsAndLogTest {

  @Test
  void linksAndResourcesBecomeDataAndBinariesANote() {
    McpSchema.CallToolResult r = McpSchema.CallToolResult.builder()
        .addContent(new McpSchema.TextContent("plain"))
        .addContent(new McpSchema.ImageContent(null, Base64.getEncoder().encodeToString(new byte[2048]),
            "image/png"))
        .addContent(McpSchema.ResourceLink.builder().uri("https://evil.example/x").name("x").mimeType("text/html")
            .build())
        .addContent(new McpSchema.EmbeddedResource(null, new McpSchema.TextResourceContents("file:///a.txt",
            "text/plain", "inside")))
        .structuredContent(Map.of("n", 1))
        .isError(true)
        .build();

    ToolResult out = Results.of(r);

    assertThat(out.content()).extracting(ToolResult.Content::type).containsOnly("text");
    assertThat(out.content()).extracting(ToolResult.Content::text).containsExactly("plain",
        "[image image/png, 2 KB, not passed on by GateRook]",
        "Resource link (data, not fetched by GateRook): https://evil.example/x · x · text/html",
        "Resource file:///a.txt:\ninside");
    assertThat(out.structuredContent().path("n").asInt()).isEqualTo(1);
    assertThat(out.isError()).isTrue();
  }

  @Test
  void aServersSecretLeavesNoTraceInItsLog(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("logs/mcp/github-mcp.log");
    String token = "ghp_S3cr3tValue/With+Chars=";
    try (ServerLog log = new ServerLog(file, Clock.systemUTC())) {
      log.secrets(List.of(token));
      log.line("token " + token);
      log.line("b64 " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8)));
      log.line("url " + URLEncoder.encode(token, StandardCharsets.UTF_8));
      log.line("json {\"t\":" + Texts.JSON.writeValueAsString(token) + "}");
      log.line("bidi ‮hidden");

      assertThat(log.tail()).hasSize(5).allSatisfy(l -> assertThat(l).doesNotContain("S3cr3t"));
      assertThat(log.tail().getLast()).isEqualTo("bidi hidden");
    }
    String text = Files.readString(file);
    assertThat(text).doesNotContain("S3cr3t").contains(ServerLog.MASK);
    assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
  }

  /**
   * A rotated secret starts a new process while the old one finishes its calls with the old value: the log masks
   * both; and a value split by an invisible character is masked after the cleaning joins it.
   */
  @Test
  void aRotatedSecretAndAHiddenSplitAreMaskedToo(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("logs/mcp/github-mcp.log");
    try (ServerLog log = new ServerLog(file, Clock.systemUTC())) {
      log.secrets(List.of("ghp_OldValue1234"));
      log.secrets(List.of("ghp_NewValue5678"));
      log.line("old process: ghp_OldValue1234");
      log.line("new process: ghp_NewValue5678");
      log.line("split: ghp_Old​Value1234");

      assertThat(log.tail()).allSatisfy(l -> assertThat(l).doesNotContain("Value1234").doesNotContain("Value5678"));
    }
    assertThat(Files.readString(file)).doesNotContain("Value1234").doesNotContain("Value5678");
  }
}
