package ai.msrv.gaterook.core.admin.clients;

import ai.msrv.gaterook.core.admin.Reasons;
import ai.msrv.gaterook.core.clients.ClientService;
import ai.msrv.gaterook.core.clients.ClientViews;
import ai.msrv.gaterook.core.clients.ClientViews.Clients;
import ai.msrv.gaterook.core.clients.ClientViews.Link;
import ai.msrv.gaterook.core.clients.ConfigEditException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Clients (ARCHITECTURE.md §6 clients, kit {@code pages/clients.html} and {@code client-card}): the clients found on
 * this computer, "What will change" before Connect, Disconnect as a reverse edit, Check, backups and Restore, Remove
 * bridge, manual setup. What a card says about calls (last call, the revision spoken) comes from the audit. No answer
 * holds a token: Connect writes a named token straight into the client's file.
 */
@RestController
@RegisterReflectionForBinding({ClientViews.Clients.class, ClientViews.Card.class, ClientViews.Method.class,
    ClientViews.Link.class, ClientViews.Preview.class, ClientViews.FileChange.class, ClientViews.Diff.class,
    ClientViews.Hunk.class, ClientViews.Line.class, ClientViews.Connected.class, ClientViews.Disconnected.class,
    ClientViews.Check.class, ClientViews.Step.class, ClientViews.Backup.class, ClientViews.Restored.class,
    ClientViews.BridgeRemoved.class, ClientViews.Manual.class, ClientViews.OtherEndpoint.class,
    ClientViews.Snippet.class, ClientsController.EditProblem.class, ClientsController.McpbFile.class,
    ClientsController.ConnectBody.class, ClientsController.Confirm.class, ClientsController.RestoreBody.class})
class ClientsController {

  /** {@code POST …/connect/preview}: endpoint, method and the options of the method. */
  record ConnectBody(String endpoint, String method, String grantLabel, ClientService.TokenOptions token,
      ClientService.CodexOptions codex) {}

  /** {@code POST …/connect}, {@code …/disconnect}, {@code …/restore}: the preview to carry out. */
  record Confirm(String previewId, String reason) {}

  record RestoreBody(String backupId) {}

  /** {@code GET /internal/clients/links/{id}/mcpb}. */
  record McpbFile(String path) {}

  /**
   * A config file GateRook won't edit: the problem+json of the admin API plus why, where, and the entry to add by
   * hand.
   */
  record EditProblem(String type, String title, String detail, String field, List<Object> errors, String reason,
      int line, int column, String fragment) {}

  private final ClientService clients;
  private final ClientCalls calls;

  ClientsController(ClientService clients, ClientCalls calls) {
    this.clients = clients;
    this.calls = calls;
  }

  @GetMapping("/api/clients")
  Clients list() {
    return withCalls(clients.clients());
  }

  @PostMapping("/api/clients/scan")
  Clients scan() {
    return withCalls(clients.scan());
  }

  @PostMapping("/api/clients/{client}/connect/preview")
  ClientViews.Preview preview(@PathVariable String client, @RequestBody ConnectBody body) {
    return clients.previewConnect(client, new ClientService.ConnectRequest(body.endpoint(), body.method(),
        body.grantLabel(), body.token(), body.codex()));
  }

  @PostMapping("/api/clients/{client}/connect")
  ClientViews.Connected connect(@PathVariable String client, @RequestBody Confirm body) {
    Reasons.check(body.reason());
    return clients.connect(client, body.previewId());
  }

  @PostMapping("/api/clients/links/{id}/disconnect/preview")
  ClientViews.Preview disconnectPreview(@PathVariable String id) {
    return clients.previewDisconnect(id);
  }

  @PostMapping("/api/clients/links/{id}/disconnect")
  ClientViews.Disconnected disconnect(@PathVariable String id, @RequestBody Confirm body) {
    Reasons.check(body.reason());
    return clients.disconnect(id, body.previewId());
  }

  /** Check, and from the audit its step 4: the client's last call and the revision it spoke. */
  @PostMapping("/api/clients/links/{id}/check")
  ClientViews.Check check(@PathVariable String id) {
    ClientViews.Check check = clients.check(id);
    Link link = clients.clients().clients().stream().flatMap(c -> c.links().stream())
        .filter(l -> l.id().equals(id)).findFirst().orElse(null);
    if (link == null) {
      return check;
    }
    Link withCall = calls.withCall(link);
    List<ClientViews.Step> steps = new ArrayList<>(check.steps());
    String text = withCall.lastCallAt() == null ? "No call through this entry yet: restart the client, then ask it to "
        + "use a tool." : "Last call " + withCall.lastCallAt() + (withCall.revision() == null ? ""
        : " · revision " + withCall.revision());
    int at = Math.max(0, steps.size() - 1);
    steps.add(at, new ClientViews.Step("calls", "Calls", withCall.lastCallAt() == null ? "warning" : "ok", text,
        null));
    return new ClientViews.Check(check.linkId(), check.summary(), List.copyOf(steps));
  }

  @GetMapping("/api/clients/{client}/backups")
  List<ClientViews.Backup> backups(@PathVariable String client) {
    return clients.backups(client);
  }

  @PostMapping("/api/clients/{client}/restore/preview")
  ClientViews.Preview restorePreview(@PathVariable String client, @RequestBody RestoreBody body) {
    return clients.previewRestore(client, body.backupId());
  }

  @PostMapping("/api/clients/{client}/restore")
  ClientViews.Restored restore(@PathVariable String client, @RequestBody Confirm body) {
    Reasons.check(body.reason());
    return clients.restore(client, body.previewId());
  }

  @PostMapping("/api/clients/claude-desktop/remove-bridge")
  ClientViews.BridgeRemoved removeBridge() {
    return clients.removeBridge();
  }

  @GetMapping("/api/clients/manual")
  ClientViews.Manual manual(@RequestParam String endpoint) {
    return clients.manual(endpoint);
  }

  /** Electron main asks for the extension to open (preload {@code openMcpb(linkId)}); the path is checked there too. */
  @GetMapping("/internal/clients/links/{id}/mcpb")
  McpbFile mcpb(@PathVariable String id) {
    return new McpbFile(clients.mcpbPath(id).toString());
  }

  @ExceptionHandler(ConfigEditException.class)
  ResponseEntity<EditProblem> refused(ConfigEditException e) {
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(new EditProblem("about:blank", "GateRook won't edit this file", e.getMessage(), null, List.of(),
            e.reason().name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'), e.line(), e.column(),
            e.fragment()));
  }

  // ── What the audit adds ─────────────────────────────────────────────────────────────────────────────────────

  private Clients withCalls(Clients page) {
    return calls.withCalls(page);
  }
}
