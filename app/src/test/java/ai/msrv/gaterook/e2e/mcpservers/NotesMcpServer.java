package ai.msrv.gaterook.e2e.mcpservers;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * A small stdio MCP server of the SDK for the core-process test, started by the core as a child JVM: {@code search}
 * declares itself read-only, {@code create_note} declares nothing (so it may change data), {@code whoami} answers
 * the token it got in {@code NOTES_TOKEN} — the gateway must mask it. Exits when its stdin closes, as real servers
 * do; logs go to stderr so stdout carries only the protocol.
 */
public final class NotesMcpServer {

  private NotesMcpServer() {}

  public static void main(String[] args) throws Exception {
    PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
    System.setOut(System.err);
    InputStream in = new FilterInputStream(System.in) {
      @Override
      public int read(byte[] b, int off, int len) throws IOException {
        int n = super.read(b, off, len);
        if (n < 0) {
          System.exit(0);
        }
        return n;
      }
    };
    Map<String, Object> schema = Map.of("type", "object", "properties", Map.of("q", Map.of("type", "string")));
    McpSyncServer server = McpServer.sync(new StdioServerTransportProvider(
        new JacksonMcpJsonMapper(JsonMapper.builder().build()), in, out))
        .serverInfo("notes", "1.0.0")
        .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
        .tools(
            tool("search", "Searches the notes.", new McpSchema.ToolAnnotations(null, true, false, true, false, null),
                schema, (ex, req) -> text("found: " + req.arguments().get("q"))),
            tool("create_note", "Creates a note.", null, schema, (ex, req) -> text("created " + req.arguments()
                .get("q"))),
            tool("whoami", "Answers who it runs as.", new McpSchema.ToolAnnotations(null, true, false, true, false,
                null), Map.of("type", "object"), (ex, req) -> text("token " + System.getenv("NOTES_TOKEN"))))
        .build();
    Thread.currentThread().join();
    server.close();
  }

  private static McpServerFeatures.SyncToolSpecification tool(String name, String description,
      McpSchema.ToolAnnotations annotations, Map<String, Object> schema,
      java.util.function.BiFunction<io.modelcontextprotocol.server.McpSyncServerExchange, McpSchema.CallToolRequest,
          McpSchema.CallToolResult> handler) {
    McpSchema.Tool.Builder t = McpSchema.Tool.builder().name(name).description(description).inputSchema(schema);
    if (annotations != null) {
      t.annotations(annotations);
    }
    return McpServerFeatures.SyncToolSpecification.builder().tool(t.build()).callHandler(handler).build();
  }

  private static McpSchema.CallToolResult text(String text) {
    return McpSchema.CallToolResult.builder().addTextContent(text).build();
  }
}
