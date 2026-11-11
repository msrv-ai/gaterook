package ai.msrv.gaterook.core.admin.mcpservers;

import ai.msrv.gaterook.core.admin.ApiRequestException;
import ai.msrv.gaterook.core.admin.Reasons;
import ai.msrv.gaterook.core.connections.SecretInput;
import ai.msrv.gaterook.core.mcpservers.McpServerService;
import ai.msrv.gaterook.core.mcpservers.McpServerSignIns;
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
 *
 * <p>A server at a URL that wants an OAuth sign-in is signed in to in the person's browser: {@code /api/mcp-sign-ins}
 * starts a sign-in for the server under review (Electron opens its page through {@code /internal/mcp-sign-ins}), and
 * adding the server takes the finished sign-in by its id. A stored server signs in again or out at
 * {@code /api/mcp-servers/{name}/sign-in} and {@code …/sign-out}.
 */
@RestController
@RegisterReflectionForBinding({McpServersController.Request.class, McpServersController.SecretSource.class,
    McpServersController.Reason.class, McpServersController.Review.class, McpServersController.Started.class,
    McpServersController.Tool.class, McpServersController.Auth.class, McpServersController.AuthorizationServer.class,
    McpServersController.SignIn.class,
    McpServersController.SignInStatus.class, McpServersController.Address.class,
    McpServersController.AuthorizationPage.class, McpServerService.ProfileView.class, McpServerService.PinView.class,
    McpServerService.PlaceholderView.class, McpServerService.ToolRuleView.class,
    McpServerService.HeaderView.class})
class McpServersController {

  /**
   * Review or add: a {@code config} as the Add MCP server form sends it ({@code name}, {@code transport},
   * {@code command} or {@code url}, {@code env} or {@code headers}, {@code auth}), the values of new secrets in
   * {@code secrets} by JSON Pointer ({@code /env/0/secretRef}), or a {@code catalog} entry ({@code github-mcp@0.6.2});
   * {@code signIn} is the finished sign-in of a server that signs in with OAuth. A sign-in with the client secret of the
   * person's OAuth app names the {@code authorizationServer} its review showed: the secret goes to that one only.
   */
  record Request(JsonNode config, Map<String, SecretSource> secrets, String catalog, String env, Boolean acknowledge,
      String reason, String signIn, AuthorizationServer authorizationServer) {}

  /** An authorization server as the review showed it: its issuer and the token endpoint a client secret goes to. */
  record AuthorizationServer(String issuer, String tokenEndpoint) {}

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
   * variables (or headers) that get secrets — names only —, the sandbox, and what to know before it starts; for a
   * vendor's own server, GateRook's verified {@code profile} of it, with how each pin stands in the reviewed URL.
   */
  record Review(List<String> argv, String resolvedCommand, String node, List<String> env, List<String> inherited,
      boolean notPinned, String catalogEntry, String protocol, String sandbox, String name, String transport,
      String url, String workDir, String log, List<String> warnings, Auth auth,
      McpServerService.ProfileView profile) {}

  /**
   * How a server at a URL wants clients to sign in: {@code kind} {@code none}, {@code oauth}, {@code optional},
   * {@code refused} (a key in a header) or {@code unknown}; {@code registration} {@code metadata-document} (GateRook's
   * published client metadata), {@code dynamic} (GateRook registers itself), {@code gaterook-app} (GateRook's own app
   * there, with a one-time code the person enters on the provider's page), {@code app} (the person's OAuth app) or
   * {@code app-required}, with the {@code redirectUri} to register; {@code cloud} {@code google} or {@code aws} for a
   * server that can sign in with its cloud's credentials instead, {@code secretRequired} for an OAuth app the provider
   * takes only with its client secret, and the {@code tokenEndpoint} that secret is sent to.
   */
  record Auth(String kind, String issuer, String authorizationHost, String scope, String registration,
      String redirectUri, String message, String cloud, Boolean secretRequired, String tokenEndpoint) {}

  /**
   * A sign-in in progress: {@code state} {@code waiting}, {@code signed-in}, {@code failed}, {@code cancelled},
   * {@code expired}; a device sign-in's {@code userCode} while it waits, which the person enters at
   * {@code authorizationUrl}.
   */
  record SignIn(String id, String state, String server, String issuer, String authorizationHost, String redirectUri,
      String authorizationUrl, String expiresAt, String scope, String message, String userCode) {}

  /** A stored server's sign-in: {@code state} {@code signed-in}, {@code needs-sign-in}, {@code signed-out} or {@code none}. */
  record SignInStatus(String server, String state, String issuer, String scope, String expiresAt, boolean renewable,
      String signedInAt, String renewedAt, String message, boolean registered, String redirectUri) {}

  /** The address the browser ended on after the person allowed access. */
  record Address(String address) {}

  /** The authorization page Electron opens in the browser. */
  record AuthorizationPage(String url) {}

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
          r.warnings(), v.auth() == null ? null : new Auth(v.auth().kind(), v.auth().issuer(),
              v.auth().authorizationHost(), v.auth().scope(), v.auth().registration(), v.auth().redirectUri(),
              v.auth().message(), v.auth().cloud(), v.auth().secretRequired(), v.auth().tokenEndpoint()),
          v.profile());
    } finally {
      clear(secrets, request.secrets());
    }
  }

  /**
   * GateRook's verified profiles of vendors' own servers (§7.5), for the Cloud platforms cards: each one's setup URL
   * or command, its pins and the tools that start in Approval or Off whatever their annotations say.
   */
  @GetMapping("/api/mcp-profiles")
  List<McpServerService.ProfileView> profiles() {
    return servers.profiles();
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

  // ── Sign-ins ────────────────────────────────────────────────────────────────────────────────────────────────

  /** Starts a sign-in to the server under review; the browser opens its page next (Electron, by the id). */
  @PostMapping("/api/mcp-sign-ins")
  ResponseEntity<SignIn> signIn(@RequestBody Request request) {
    Map<String, SecretInput> secrets = secrets(request.secrets());
    try {
      return ResponseEntity.status(HttpStatus.CREATED).body(signIn(servers.signIn(source(request, secrets))));
    } finally {
      clear(secrets, request.secrets());
    }
  }

  @GetMapping("/api/mcp-sign-ins/{id}")
  SignIn signInState(@PathVariable String id) {
    return signIn(servers.signInState(id));
  }

  /** The address the browser ended on, when the redirect couldn't reach GateRook by itself. */
  @PostMapping("/api/mcp-sign-ins/{id}/address")
  SignIn signInAddress(@PathVariable String id, @RequestBody Address request) {
    return signIn(servers.signInAddress(id, request == null ? null : request.address()));
  }

  @DeleteMapping("/api/mcp-sign-ins/{id}")
  ResponseEntity<Void> cancelSignIn(@PathVariable String id) {
    servers.cancelSignIn(id);
    return ResponseEntity.noContent().build();
  }

  /** Electron main asks for the page to open (preload {@code openMcpSignIn(id)}); it checks the URL again. */
  @GetMapping("/internal/mcp-sign-ins/{id}/authorization")
  AuthorizationPage authorizationPage(@PathVariable String id) {
    return new AuthorizationPage(servers.authorizationUrl(id));
  }

  @GetMapping("/api/mcp-servers/{name}/sign-in")
  SignInStatus signInStatus(@PathVariable String name) {
    return status(servers.signInStatus(name));
  }

  /** Signs in to a stored server again: the new tokens replace the old ones once the browser comes back. */
  @PostMapping("/api/mcp-servers/{name}/sign-in")
  ResponseEntity<SignIn> signInAgain(@PathVariable String name) {
    return ResponseEntity.status(HttpStatus.CREATED).body(signIn(servers.signIn(name)));
  }

  @PostMapping("/api/mcp-servers/{name}/sign-out")
  SignInStatus signOut(@PathVariable String name, @RequestBody(required = false) Reason request) {
    return status(servers.signOut(name, request == null ? null : Reasons.check(request.reason())));
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
    AuthorizationServer reviewed = r.authorizationServer();
    return new McpServerService.Source(r.config(), secrets, r.catalog(), r.env(), r.signIn(), reviewed == null ? null
        : new McpServerSignIns.Reviewed(reviewed.issuer(), reviewed.tokenEndpoint()));
  }

  private static SignIn signIn(McpServerSignIns.SignInView v) {
    return new SignIn(v.id(), v.state(), v.server(), v.issuer(), v.authorizationHost(), v.redirectUri(),
        v.authorizationUrl(), v.expiresAt() == null ? null : v.expiresAt().toString(), v.scope(), v.message(),
        v.userCode());
  }

  private static SignInStatus status(McpServerSignIns.StatusView v) {
    return new SignInStatus(v.server(), v.state(), v.issuer(), v.scope(), text(v.expiresAt()), v.renewable(),
        text(v.signedInAt()), text(v.renewedAt()), v.message(), v.registered(), v.redirectUri());
  }

  private static String text(java.time.Instant at) {
    return at == null ? null : at.toString();
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
        case "reference", "vault", "gcp-sm", "aws-sm", "azure-kv" -> {
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
