package com.gaterook.core.mcp.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Static MCP Apps templates. No per-request data, credentials, or capabilities are embedded. */
public final class McpAppResource {

  public static final String EXTENSION = "io.modelcontextprotocol/ui";
  public static final String URI = "ui://gaterook/approval-card/v1.html";
  public static final String MIME_TYPE = "text/html;profile=mcp-app";
  private static final String ROOT = "/com/gaterook/core/mcp/apps/";

  private McpAppResource() {}

  public static ObjectNode descriptor() {
    return JsonNodeFactory.instance.objectNode().put("uri", URI).put("name", "GateRook approval card")
        .put("description", "A caller-owned request with status, decline, and protected review in GateRook.")
        .put("mimeType", MIME_TYPE);
  }

  public static ObjectNode read() {
    return read(URI, Template.HTML);
  }

  static ObjectNode read(String uri, String html) {
    ObjectNode result = JsonNodeFactory.instance.objectNode();
    ObjectNode content = result.putArray("contents").addObject().put("uri", uri).put("mimeType", MIME_TYPE)
        .put("text", html);
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
    private static final String HTML = assemble("approval-card");
  }

  static String assemble(String name) {
    // The same bundled stylesheet as the desktop UI; no remote fonts or assets in a sandboxed card.
    String css = resource("gw-kit.css").replaceAll("(?s)@font-face\\s*\\{[^}]*}", "")
        .replaceAll("@charset\\s+\"[^\"]+\";", "");
    String script = resource(name + ".js");
    if (css.contains("</style") || script.contains("</script")) {
      throw new IllegalStateException("MCP App assets contain an unescaped HTML closing tag");
    }
    String csp = "default-src 'none'; script-src 'sha256-" + hash(script) + "'; style-src 'sha256-" + hash(css)
        + "'; connect-src 'none'; img-src 'none'; font-src 'none'; frame-src 'none'; object-src 'none'; "
        + "base-uri 'none'; form-action 'none'";
    return resource(name + ".html").replace("{{CSP}}", csp)
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
