package ai.msrv.gaterook.core.admin.clients;

import ai.msrv.gaterook.core.endpoints.ClientGrants;
import ai.msrv.gaterook.core.endpoints.ClientGrants.TokenAnswer;
import ai.msrv.gaterook.core.endpoints.ClientGrants.TokenRequest;
import ai.msrv.gaterook.core.platform.ProblemException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The broker's side of the core (ARCHITECTURE.md §6 "Electron main only", §7.2). Only Electron's main process
 * reaches {@code /internal/**}: the local guard wants the launch secret's signature on every request, and the renderer
 * never has it. The answer is a tree in the broker protocol v1 that the broker forwards to the bridge as it is:
 * {@code {v, ok, token, expiresAt, url, instance}} or {@code {v, ok:false, error, message}}. It lives with the admin
 * controllers so a refused confirmation is the same problem+json as everywhere else.
 */
@RestController
class BridgeTokenController {

  private final ClientGrants grants;

  BridgeTokenController(ClientGrants grants) {
    this.grants = grants;
  }

  /** {@code {v:1, op:"token", client, endpoint, grant, pid, bridge}}. */
  @PostMapping("/internal/bridge/tokens")
  ObjectNode token(@RequestBody JsonNode request) {
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    out.put("v", 1);
    if (request == null || !request.isObject() || request.path("v").asInt(0) != 1
        || !"token".equals(request.path("op").asString(""))) {
      out.put("ok", false);
      out.put("error", ClientGrants.BAD_REQUEST);
      out.put("message", "The bridge speaks another version of the broker protocol: update MCP Gateway.");
      return out;
    }
    Long pid = request.path("pid").isIntegralNumber() ? request.path("pid").asLong() : null;
    TokenAnswer a = grants.issue(new TokenRequest(text(request, "client"), text(request, "endpoint"),
        text(request, "grant"), pid, text(request, "bridge")));
    out.put("ok", a.ok());
    if (a.ok()) {
      out.put("token", a.token());
      out.put("expiresAt", a.expiresAt().toString());
      out.put("url", a.url());
      out.put("instance", a.instance());
    } else {
      out.put("error", a.error());
      out.put("message", a.message());
    }
    return out;
  }

  /** {@code {grant, decision: "allow"|"deny"}}: the person's answer to "Codex wants to use incident-prod". */
  @PostMapping("/internal/bridge/confirm")
  ResponseEntity<Void> confirm(@RequestBody JsonNode request) {
    String decision = request == null ? "" : request.path("decision").asString("");
    if (!decision.equals("allow") && !decision.equals("deny")) {
      throw ProblemException.unprocessable("Allow or deny", "decision is allow or deny.", "/decision");
    }
    grants.confirm(text(request, "grant"), decision.equals("allow"));
    return ResponseEntity.noContent().build();
  }

  private static String text(JsonNode request, String field) {
    JsonNode n = request.get(field);
    return n == null || !n.isString() ? null : n.asString();
  }
}
