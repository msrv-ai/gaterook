package ai.msrv.gaterook.core.mcp.internal;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the client of a stdio bridge declared in its own legacy {@code initialize}, which the bridge repeats on every
 * later request in {@code X-Gateway-Bridge-Client}: base64url of
 * {@code {"capabilities": {…}, "clientInfo": {…}}}, at most {@value #MAX_LENGTH} characters. One grant can serve
 * several clients at once (Claude Desktop runs its chat, its Code and Cowork sessions and a version probe as separate
 * processes of one extension), and a core restart forgets every legacy declaration while the bridges keep their
 * clients' sessions open. With the header, each connection keeps its own presentation. Like {@code initialize} itself,
 * it selects presentation and delivery only: it never authenticates a person or authorizes a decision.
 *
 * @param capabilities the client's capabilities object
 * @param clientInfo   the client's name, title and version as the bridge forwarded them; {@code null} when not sent
 */
public record BridgeClient(JsonNode capabilities, JsonNode clientInfo) {

  public static final String HEADER = "X-Gateway-Bridge-Client";
  static final int MAX_LENGTH = 4096;

  /** The declaration in a header value; empty when there is none, or when it is too long or malformed. */
  static Optional<BridgeClient> parse(String value, JsonMapper json) {
    if (value == null || value.isEmpty() || value.length() > MAX_LENGTH) {
      return Optional.empty();
    }
    try {
      JsonNode n = json.readTree(new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8));
      JsonNode capabilities = n.path("capabilities");
      JsonNode info = n.get("clientInfo");
      if (!n.isObject() || !capabilities.isObject() || info != null && !info.isObject()) {
        return Optional.empty();
      }
      return Optional.of(new BridgeClient(capabilities, info));
    } catch (IllegalArgumentException | JacksonException e) {
      return Optional.empty();
    }
  }
}
