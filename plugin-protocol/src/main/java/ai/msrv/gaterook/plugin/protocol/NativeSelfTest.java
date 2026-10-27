package ai.msrv.gaterook.plugin.protocol;

import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.time.Duration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A native self-test of GPP/1 (report 16 §10.4, the {@code --selftest} fallback): the codec, the framer and the peer
 * must work in a GraalVM native image with no reflection and no metadata, because that is where the native core runs
 * them. Built into a tiny native binary ({@code native-image … NativeSelfTest}) and run from a JVM test; it exercises a
 * tool and result round trip, canonical hashing, byte-level framing and a request/response over in-memory pipes, then
 * exits {@code 0} on success or {@code 1} on the first mismatch.
 *
 * <p>Not {@code native:test} because the GraalVM JUnit-native support artifacts aren't available offline here; this
 * proves the same property — the protocol is native-safe — without them.
 */
public final class NativeSelfTest {

  private NativeSelfTest() {}

  public static void main(String[] args) throws Exception {
    check("tool round trip", toolRoundTrip());
    check("result round trip", resultRoundTrip());
    check("canonical hash", canonicalHash());
    check("frame split", frameSplit());
    check("peer round trip", peerRoundTrip());
    System.out.println("NativeSelfTest OK");
  }

  private static boolean toolRoundTrip() {
    ToolDefinition t = ToolDefinition.builder("jira_request_sla").title("Request SLA").description("d")
        .inputSchema(GppJson.MAPPER.readTree("{\"type\":\"object\"}"))
        .annotations(new ToolDefinition.Annotations(true, false, true, false))
        .nature(ToolDefinition.Nature.READ)
        .defaultMode(ToolDefinition.Mode.APPROVAL, ToolDefinition.Mode.APPROVAL).build();
    ToolDefinition back = WireCodec.tool(WireCodec.tool(t));
    return back.name().equals("jira_request_sla") && back.nature() == ToolDefinition.Nature.READ
        && WireCodec.definitionHash(back).equals(WireCodec.definitionHash(t));
  }

  private static boolean resultRoundTrip() {
    ToolResult r = ToolResult.text("SD-123 ongoing");
    ToolResult back = WireCodec.result(WireCodec.result(r));
    return !back.isError() && back.content().getFirst().text().equals("SD-123 ongoing");
  }

  private static boolean canonicalHash() {
    JsonNode a = GppJson.MAPPER.readTree("{\"b\":1,\"a\":2}");
    JsonNode b = GppJson.MAPPER.readTree("{\"a\":2,\"b\":1}");
    return Canonical.sha256Hex(a).equals(Canonical.sha256Hex(b));
  }

  private static boolean frameSplit() throws Exception {
    byte[] bytes = "{\"one\":1}\n{\"two\":2}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    FrameReader reader = new FrameReader(new java.io.ByteArrayInputStream(bytes), Gpp.MAX_FRAME_BYTES);
    int n1 = reader.next();
    JsonNode first = GppJson.MAPPER.createParser(reader.buffer(), 0, n1).readValueAsTree();
    reader.wipe(n1);
    int n2 = reader.next();
    JsonNode second = GppJson.MAPPER.createParser(reader.buffer(), 0, n2).readValueAsTree();
    return first.path("one").asInt() == 1 && second.path("two").asInt() == 2 && reader.next() < 0;
  }

  private static boolean peerRoundTrip() throws Exception {
    MemoryPipe aToB = new MemoryPipe();
    MemoryPipe bToA = new MemoryPipe();
    JsonRpcPeer server = new JsonRpcPeer(aToB.in(), bToA.out(), (method, params, call) -> {
      ObjectNode o = GppJson.object();
      o.put("echo", params.path("x").asInt() + 1);
      return o;
    }, JsonRpcPeer.Options.defaults().name("b").hostIds(true)).start();
    JsonRpcPeer client = new JsonRpcPeer(bToA.in(), aToB.out(), (method, params, call) -> null,
        JsonRpcPeer.Options.defaults().name("a")).start();
    try {
      JsonNode result = client.request("ping", GppJson.object().put("x", 41)).get(Duration.ofSeconds(5));
      return result.path("echo").asInt() == 42;
    } finally {
      client.close();
      server.close();
    }
  }

  private static void check(String what, boolean ok) {
    if (!ok) {
      System.err.println("NativeSelfTest FAILED: " + what);
      System.exit(1);
    }
  }
}
