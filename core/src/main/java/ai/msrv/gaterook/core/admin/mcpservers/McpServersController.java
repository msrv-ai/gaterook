package ai.msrv.gaterook.core.admin.mcpservers;

import ai.msrv.gaterook.core.admin.ApiRequestException;
import ai.msrv.gaterook.core.admin.Reasons;
import ai.msrv.gaterook.core.connections.SecretInput;
import ai.msrv.gaterook.core.mcpservers.McpServerService;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * External MCP servers (ARCHITECTURE.md §6 connectors, §7.5): review what would run, add a server (it starts once
 * before anything is saved), Test, Start, Restart, Stop, its reviewed tools, remove it. A server's list row, config and
 * secret names are its connection's ({@code /api/connections}); its process is a {@code kind: "mcp-server"} entry of
 * {@code /api/connector-types}.
 */
@RestController
@RegisterReflectionForBinding({McpServersController.Request.class, McpServersController.SecretSource.class,
    McpServersController.Reason.class, McpServersController.Review.class, McpServersController.Started.class,
    McpServersController.Tool.class})
class McpServersController {

  /**
   * Review or add: a {@code config} as the Add MCP server form sends it ({@code name}, {@code transport},
   * {@code command} or {@code url}, {@code env} or {@code headers}), the values of new secrets in {@code secrets} by
   * JSON Pointer ({@code /env/0/secretRef}), or a {@code catalog} entry ({@code github-mcp@0.6.2}).
   */
  record Request(JsonNode config, Map<String, SecretSource> secrets, String catalog, String env, Boolean acknowledge,
      String reason) {}

  /** A secret as the connection form sends it: typed here ({@code local}) or a reference to a store. */
  record SecretSource(String source, char[] value, String ref, String store) {

    @Override
    public String toString() {
      return "SecretSource[source=" + source + (value == null ? "" : ", value=‹redacted›") + "]";
    }
  }

  record Reason(String reason) {}

  /**
   * What will run: the command as resolved ({@code node …/dist/index.js}, never npx for a catalog server), the
   * variables (or headers) that get secrets — names only —, the sandbox, and what to know before it starts.
   */
  record Review(List<String> argv, String resolvedCommand, String node, List<String> env, List<String> inherited,
      boolean notPinned, String catalogEntry, String protocol, String sandbox, String name, String transport,
      String url, String workDir, String log, List<String> warnings) {}

  record Started(String name, String state, Integer tools, Integer previousTools) {}

  /** One reviewed tool; {@code annotations} are what the server declared, not verified ("Declared by …"). */
  record Tool(String name, String original, String title, String description, String nature, JsonNode annotations,
      String declaredBy, boolean verified, JsonNode inputSchema) {}

  private final McpServerService servers;

  McpServersController(McpServerService servers) {
    this.servers = servers;
  }

  @PostMapping("/api/mcp-servers/review")
  Review review(@RequestBody Request request) {
    Map<String, SecretInput> secrets = secrets(request.secrets());
    try {
      McpServerService.ReviewView v = servers.review(source(request, secrets));
      var r = v.review();
      return new Review(r.argv(), r.resolvedCommand(), r.node(), r.variables(), r.inherited(), r.notPinned(),
          v.catalogEntry(), v.protocol(), r.sandbox(), v.name(), r.transport(), r.url(), r.workDir(), r.log(),
          r.warnings());
    } finally {
      clear(secrets, request.secrets());
    }
  }

  @PostMapping("/api/mcp-servers")
  ResponseEntity<Started> create(@RequestBody Request request) {
    Map<String, SecretInput> secrets = secrets(request.secrets());
    try {
      McpServerService.Started s = servers.create(source(request, secrets), Boolean.TRUE.equals(request.acknowledge()),
          Reasons.check(request.reason()));
      return ResponseEntity.status(HttpStatus.CREATED).body(started(s));
    } finally {
      clear(secrets, request.secrets());
    }
  }

  @PostMapping("/api/mcp-servers/{name}/test")
  Started test(@PathVariable String name) {
    return started(servers.test(name));
  }

  @PostMapping("/api/mcp-servers/{name}/start")
  Started start(@PathVariable String name) {
    return started(servers.start(name, false));
  }

  @PostMapping("/api/mcp-servers/{name}/restart")
  Started restart(@PathVariable String name) {
    return started(servers.start(name, true));
  }

  @PostMapping("/api/mcp-servers/{name}/stop")
  Started stop(@PathVariable String name) {
    return started(servers.stop(name));
  }

  @GetMapping("/api/mcp-servers/{name}/tools")
  List<Tool> tools(@PathVariable String name) {
    return servers.tools(name).stream().map(t -> new Tool(t.name(), t.original(), t.title(), t.description(),
        t.nature(), t.annotations(), t.declaredBy(), false, t.inputSchema())).toList();
  }

  @PostMapping("/api/mcp-servers/{name}/confirm-integrity")
  Started confirmIntegrity(@PathVariable String name, @RequestBody(required = false) Reason request) {
    return started(servers.confirmIntegrity(name, request == null ? null : Reasons.check(request.reason())));
  }

  @DeleteMapping("/api/mcp-servers/{name}")
  ResponseEntity<Void> delete(@PathVariable String name, @RequestBody(required = false) Reason request) {
    servers.delete(name, request == null ? null : Reasons.check(request.reason()));
    return ResponseEntity.noContent().build();
  }

  // ── Mapping ─────────────────────────────────────────────────────────────────────────────────────────────────

  private static McpServerService.Source source(Request r, Map<String, SecretInput> secrets) {
    return new McpServerService.Source(r.config(), secrets, r.catalog(), r.env());
  }

  private static Started started(McpServerService.Started s) {
    return new Started(s.name(), s.state(), s.tools(), s.previousTools());
  }

  /** Like the connection form's secrets: a typed value or a reference to a connected store. */
  private static Map<String, SecretInput> secrets(Map<String, SecretSource> given) {
    Map<String, SecretInput> out = new LinkedHashMap<>();
    if (given == null) {
      return out;
    }
    given.forEach((field, s) -> {
      if (s == null) {
        return;
      }
      String source = s.source() == null ? (s.value() != null ? "local" : "reference") : s.source();
      switch (source) {
        case "local" -> out.put(field, SecretInput.local(s.value() == null ? new char[0] : s.value().clone()));
        case "reference", "vault", "gcp-sm", "aws-sm" -> {
          if (s.ref() == null || s.ref().isBlank()) {
            throw ApiRequestException.unprocessable("Reference missing", "Enter the reference of " + field + ".",
                "/secrets");
          }
          out.put(field, SecretInput.reference(s.ref(), s.store()));
        }
        default -> throw ApiRequestException.unprocessable("Unknown secret source", "A server's secret is stored here "
            + "(local) or referenced in a connected store (reference).", "/secrets");
      }
    });
    return out;
  }

  private static void clear(Map<String, SecretInput> secrets, Map<String, SecretSource> given) {
    secrets.values().forEach(SecretInput::clear);
    if (given != null) {
      given.values().forEach(s -> {
        if (s != null && s.value() != null) {
          Arrays.fill(s.value(), '\0');
        }
      });
    }
  }
}
