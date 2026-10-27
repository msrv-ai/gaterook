package ai.msrv.gaterook.core.mcp.internal;

import ai.msrv.gaterook.core.platform.CanonicalJson;
import ai.msrv.gaterook.core.platform.HmacKeys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code requestState} of MRTR approval rounds (ARCHITECTURE.md §5 step 11, §9 "Approval cannot be forged through the
 * protocol"): {@code base64url(JSON).base64url(HMAC-SHA256)} under the {@code request-state/v1} key, the JSON being
 * {@code {v, ap, p, e, m, n, d, r, t0, exp}} — request, principal, endpoint, method, tool, digest of the current
 * arguments, round, start of the first round, expiry. The state is attacker-controlled (MRTR says so): a state whose
 * MAC, expiry, principal, endpoint, method, tool or argument digest doesn't match is treated as absent, and the call
 * goes the ordinary way (which finds the same pending request by its digest — never a second execution). Single use
 * isn't needed: executing follows the decision, a retry only reads.
 */
@Component
public final class RequestStateCodec {

  /** What a valid state says. */
  public record State(String approvalId, int round, Instant t0) {}

  private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DEC = Base64.getUrlDecoder();

  private final HmacKeys keys;
  private final JsonMapper json;
  private final Clock clock;

  public RequestStateCodec(HmacKeys keys, JsonMapper json, Clock clock) {
    this.keys = keys;
    this.json = json;
    this.clock = clock;
  }

  /** The digest {@code d}: tool and arguments as the client sends them again on every round. */
  public static String argumentsDigest(String tool, JsonNode arguments) {
    ObjectNode n = JsonNodeFactory.instance.objectNode();
    n.put("tool", tool);
    n.set("arguments", arguments == null ? JsonNodeFactory.instance.objectNode() : arguments);
    return CanonicalJson.sha256Hex(n);
  }

  public String seal(String approvalId, String principalId, String endpointId, String method, String tool,
      String argumentsDigest, int round, Instant t0, Instant expires) {
    ObjectNode n = JsonNodeFactory.instance.objectNode();
    n.put("v", 1);
    n.put("ap", approvalId);
    n.put("p", principalId);
    n.put("e", endpointId);
    n.put("m", method);
    n.put("n", tool);
    n.put("d", argumentsDigest);
    n.put("r", round);
    n.put("t0", t0.toEpochMilli());
    n.put("exp", expires.toEpochMilli());
    byte[] body = json.writeValueAsBytes(n);
    return ENC.encodeToString(body) + "." + ENC.encodeToString(keys.mac(HmacKeys.Purpose.REQUEST_STATE, body));
  }

  /** The state, when every check holds; empty otherwise (never an exception: to the client all bad states look alike). */
  public Optional<State> open(String state, String principalId, String endpointId, String method, String tool,
      String argumentsDigest) {
    if (state == null || state.length() > 4_096) {
      return Optional.empty();
    }
    int dot = state.indexOf('.');
    if (dot <= 0 || dot != state.lastIndexOf('.')) {
      return Optional.empty();
    }
    try {
      byte[] body = DEC.decode(state.substring(0, dot));
      byte[] mac = DEC.decode(state.substring(dot + 1));
      if (!keys.verify(HmacKeys.Purpose.REQUEST_STATE, body, mac)) {
        return Optional.empty();
      }
      JsonNode n = json.readTree(new String(body, StandardCharsets.UTF_8));
      if (n.path("v").asInt(0) != 1 || n.path("exp").asLong(0) < clock.instant().toEpochMilli()
          || !principalId.equals(n.path("p").asString(null)) || !endpointId.equals(n.path("e").asString(null))
          || !method.equals(n.path("m").asString(null)) || !tool.equals(n.path("n").asString(null))
          || !argumentsDigest.equals(n.path("d").asString(null))) {
        return Optional.empty();
      }
      String approvalId = n.path("ap").asString(null);
      if (approvalId == null) {
        return Optional.empty();
      }
      return Optional.of(new State(approvalId, n.path("r").asInt(1), Instant.ofEpochMilli(n.path("t0").asLong(0))));
    } catch (IllegalArgumentException | JacksonException | IllegalStateException e) {
      return Optional.empty();
    }
  }
}
