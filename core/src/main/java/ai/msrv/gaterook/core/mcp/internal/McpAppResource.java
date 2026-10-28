package ai.msrv.gaterook.core.mcp.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Static MCP Apps template. No approval data, credentials, routes, or per-request capabilities are embedded. */
public final class McpAppResource {

  public static final String EXTENSION = "io.modelcontextprotocol/ui";
  public static final String URI = "ui://gaterook/approval-card/v1.html";
  public static final String MIME_TYPE = "text/html;profile=mcp-app";
  private static final String ROOT = "/ai/msrv/gaterook/core/mcp/apps/";

  private McpAppResource() {}

  public static ObjectNode descriptor() {
    return JsonNodeFactory.instance.objectNode().put("uri", URI).put("name", "GateRook approval card")
        .put("description", "A caller-owned request with status, decline, and protected review in GateRook.")
        .put("mimeType", MIME_TYPE);
  }

  public static ObjectNode read() {
    ObjectNode result = JsonNodeFactory.instance.objectNode();
    ObjectNode content = result.putArray("contents").addObject().put("uri", URI).put("mimeType", MIME_TYPE)
        .put("text", Template.HTML);
    ObjectNode ui = content.putObject("_meta").putObject("ui");
    ui.put("prefersBorder", true);
    ObjectNode csp = ui.putObject("csp");
    csp.putArray("connectDomains");
    csp.putArray("resourceDomains");
    csp.putArray("frameDomains");
    csp.putArray("baseUriDomains");
    return result;
  }

  private static final class Template {
    private static final String HTML = assemble();
  }

  private static String assemble() {
    // The same versioned design kit as the desktop UI; no remote fonts or assets in a sandboxed card.
    String css = resource("gw-kit.css").replaceAll("(?s)@font-face\\s*\\{[^}]*}", "")
        .replaceAll("@charset\\s+\"[^\"]+\";", "");
    String script = resource("approval-card.js");
    if (css.contains("</style") || script.contains("</script")) {
      throw new IllegalStateException("MCP App assets contain an unescaped HTML closing tag");
    }
    String csp = "default-src 'none'; script-src 'sha256-" + hash(script) + "'; style-src 'sha256-" + hash(css)
        + "'; connect-src 'none'; img-src 'none'; font-src 'none'; frame-src 'none'; object-src 'none'; "
        + "base-uri 'none'; form-action 'none'";
    return resource("approval-card.html").replace("{{CSP}}", csp)
        .replace("{{KIT_CSS}}", css).replace("{{APP_JS}}", script);
  }

  private static String resource(String file) {
    try (var stream = McpAppResource.class.getResourceAsStream(ROOT + file)) {
      if (stream == null) throw new IllegalStateException("Missing MCP App resource: " + file);
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("Could not read MCP App resource: " + file, e);
    }
  }

  private static String hash(String text) {
    try {
      return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256")
          .digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
