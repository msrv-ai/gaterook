package ai.msrv.gaterook.core.admin.connections;

import ai.msrv.gaterook.core.admin.Reasons;
import ai.msrv.gaterook.core.connections.SecretInput;
import ai.msrv.gaterook.core.endpoints.ConnectionImportService;
import ai.msrv.gaterook.core.platform.ProblemException;
import java.util.ArrayList;
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

/** Only the local, signed admin API starts discovery and commits the person's reviewed selections. */
@RestController
@RegisterReflectionForBinding({ConnectionImportService.Session.class, ConnectionImportService.Imported.class,
    ConnectionImportService.NeedsSetup.class, ConnectionImportService.Destination.class, ConnectionImportService.Progress.class})
class ConnectionImportsController {
  record Start(String repository, String endpoint) {}
  record Selection(String candidateId, String name, String env, JsonNode config,
      Map<String, ConnectionsController.SecretSource> secrets, String confirm, Boolean allowIncomplete, String endpoint) {}
  record Commit(String endpoint, List<Selection> selections, String reason) {}

  private final ConnectionImportService imports;

  ConnectionImportsController(ConnectionImportService imports) { this.imports = imports; }

  @PostMapping("/api/connection-imports")
  ResponseEntity<ConnectionImportService.Session> start(@RequestBody Start request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(imports.start(request.repository(), request.endpoint()));
  }

  @GetMapping("/api/connection-imports/{id}")
  ConnectionImportService.Session get(@PathVariable String id) { return imports.get(id); }

  @DeleteMapping("/api/connection-imports/{id}")
  ResponseEntity<Void> cancel(@PathVariable String id) {
    imports.cancel(id);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/api/connection-imports/{id}/commit")
  ConnectionImportService.Imported commit(@PathVariable String id, @RequestBody Commit request) {
    List<ConnectionImportService.Selection> selections = new ArrayList<>();
    try {
      if (request.selections() == null || request.selections().size() > ConnectionImportService.MAX_CONNECTIONS) {
        throw ProblemException.unprocessable("Choose connections", "Select at most 50 connections.", "/selections");
      }
      for (Selection selection : request.selections()) {
        if (selection == null) throw ProblemException.unprocessable("Choose connections", "Invalid selection.", "/selections");
        Map<String, SecretInput> secrets = ConnectionsController.secrets(selection.secrets());
        selections.add(new ConnectionImportService.Selection(selection.candidateId(), selection.name(), selection.env(),
            selection.config(), secrets, selection.confirm(), Boolean.TRUE.equals(selection.allowIncomplete()), selection.endpoint()));
      }
      return imports.commit(id, request.endpoint(), selections, Reasons.check(request.reason()));
    } finally {
      selections.forEach(s -> s.secrets().values().forEach(SecretInput::clear));
      if (request.selections() != null) request.selections().stream()
          .filter(s -> s != null && s.secrets() != null).forEach(s -> s.secrets().values().stream()
              .filter(v -> v != null && v.value() != null).forEach(v -> Arrays.fill(v.value(), '\0')));
    }
  }
}
