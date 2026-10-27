package ai.msrv.gaterook.connectors.mcpserver;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connectors.support.egress.AddressPolicy;
import ai.msrv.gaterook.connectors.support.egress.EgressDenial;
import ai.msrv.gaterook.connectors.support.mcp.McpServerRuntime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Test connection of an MCP server in the kit's words: it started (how long it took), what it says it is, which
 * protocol it speaks, how many tools it lists and how many of them declare annotations — and, for a stored server,
 * what changed against the reviewed snapshot.
 */
final class Tests {

  private Tests() {}

  static TestResult success(String name, McpServerRuntime.Handshake h, McpServerRuntime.Snapshot reviewed) {
    List<TestResult.Check> checks = new ArrayList<>();
    checks.add(new TestResult.Check("start", true, h.startMillis(), "Started in " + h.startMillis() + " ms"));
    checks.add(new TestResult.Check("initialize", true, 0, "Protocol " + h.protocolVersion()));
    int annotated = Snapshots.annotated(h.tools());
    checks.add(new TestResult.Check("tools/list", true, 0, h.tools().size() + " tools, " + annotated
        + " declare annotations"));
    String serverName = h.serverInfo() == null ? name : Texts.str(h.serverInfo().path("name"), name);
    TestResult r = TestResult.success(h.startMillis(), h.serverVersion(), serverName, null).withChecks(checks);
    for (McpServerRuntime.Rejected x : h.rejected()) {
      r = r.withHint(new TestResult.Hint(TestResult.Severity.WARNING, "mcp.tool-rejected", null, x.name()
          + " isn't published: " + x.reason() + "."));
    }
    if (annotated > 0) {
      r = r.withHint(new TestResult.Hint(TestResult.Severity.INFO, "mcp.declared", null, "Annotations are declared by "
          + name + ", not verified by MCP Gateway."));
    }
    if (reviewed != null) {
      String diff = diff(reviewed.tools(), h.tools());
      if (diff != null) {
        r = r.withHint(new TestResult.Hint(TestResult.Severity.WARNING, "mcp.tools-changed", null, diff));
      }
    }
    for (String note : h.notes()) {
      if (note.startsWith("Not pinned")) {
        r = r.withHint(new TestResult.Hint(TestResult.Severity.WARNING, "mcp.not-pinned", "/command", note));
      }
    }
    ObjectNode details = Texts.JSON.createObjectNode();
    if (h.serverInfo() != null) {
      details.set("serverInfo", h.serverInfo().deepCopy());
    }
    details.put("protocol", h.protocolVersion());
    details.put("tools", h.tools().size());
    details.put("annotated", annotated);
    ArrayNode names = details.putArray("toolNames");
    h.tools().forEach(t -> names.add(t.path("exposed").asString()));
    return r.withDetails(details);
  }

  static TestResult failure(ConnectorException e, ConnectionConfig cfg) {
    EgressDenial denial = EgressDenial.find(e);
    if (denial != null) {
      AddressPolicy.Verdict v = denial.verdict();
      if (v.suggestedRange().isPresent()) {
        String cidr = v.suggestedRange().get().toString();
        ObjectNode change = Texts.JSON.createObjectNode();
        change.put("kind", "allow-private");
        change.put("cidr", cidr);
        change.put("connection", cfg.name());
        return TestResult.failure("mcp.private-address", denial.host() + " is " + v.address().getHostAddress()
            + ", a private address. Allow " + cidr + " for " + cfg.name() + " on " + cfg.env().id() + ".", null)
            .withSuggestedPolicyChange(change);
      }
      return TestResult.failure("mcp.address-blocked", v.text(), null);
    }
    String message = e.getMessage() == null ? "It didn't start." : e.getMessage();
    String code = switch (e.kind()) {
      case TIMEOUT -> "mcp.timeout";
      case BAD_INPUT -> "mcp.config";
      case DENIED -> "mcp.denied";
      default -> message.contains("2026-07-28") ? "mcp.protocol" : "mcp.start";
    };
    int nl = message.indexOf('\n');
    return nl < 0 ? TestResult.failure(code, message, null)
        : TestResult.failure(code, message.substring(0, nl), message.substring(nl + 1));
  }

  /** "Tools listed again: 2 new (Off), 1 changed, 1 gone." against the snapshot; {@code null} when the same. */
  static String diff(List<JsonNode> before, List<JsonNode> after) {
    Set<String> had = new java.util.HashSet<>();
    java.util.Map<String, String> hashes = new java.util.HashMap<>();
    for (JsonNode t : before) {
      had.add(t.path("original").asString());
      hashes.put(t.path("original").asString(), t.path("definitionHash").asString());
    }
    int added = 0;
    int changed = 0;
    Set<String> now = new java.util.HashSet<>();
    for (JsonNode t : after) {
      String o = t.path("original").asString();
      now.add(o);
      if (!had.contains(o)) {
        added++;
      } else if (!hashes.get(o).equals(t.path("definitionHash").asString())) {
        changed++;
      }
    }
    int gone = 0;
    for (String o : had) {
      if (!now.contains(o)) {
        gone++;
      }
    }
    if (added + changed + gone == 0) {
      return null;
    }
    List<String> parts = new ArrayList<>();
    if (added > 0) {
      parts.add(added + " new (Off on every endpoint)");
    }
    if (changed > 0) {
      parts.add(changed + " changed (Read only asks again)");
    }
    if (gone > 0) {
      parts.add(gone + " gone");
    }
    return "The server lists other tools than were reviewed: " + String.join(", ", parts) + ".";
  }
}
