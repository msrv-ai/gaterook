package ai.msrv.gaterook.core.admin.playground;

import ai.msrv.gaterook.core.admin.activity.ActivityDtos;
import ai.msrv.gaterook.core.audit.AuditQueries;
import ai.msrv.gaterook.core.playground.PlaygroundService;
import java.util.List;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Playground (ARCHITECTURE.md §6 playground, kit {@code playground.html}): every tool of an endpoint with its mode and
 * why Off blocks Run, the {@code tools/list} a client of the chosen revision gets, one call through the same pipeline
 * as an agent's (a mutation becomes an approval request), and the Playground's own history.
 */
@RestController
@RegisterReflectionForBinding({PlaygroundService.Tools.class, PlaygroundService.Tool.class,
    PlaygroundService.CallResult.class, PlaygroundService.Meta.class, PlaygroundService.Approval.class,
    PlaygroundController.CallView.class, PlaygroundController.Wire.class, ActivityDtos.Page.class,
    ActivityDtos.Event.class})
class PlaygroundController {

  /** {@code era}: {@code 2026-07-28} (default) or {@code 2025-11-25}. */
  record Call(String endpoint, String tool, JsonNode arguments, String era) {}

  /** The JSON-RPC exactly as it went over the wire in the chosen revision. */
  record Wire(JsonNode request, JsonNode response, int httpStatus) {}

  record CallView(String callId, Wire wire, JsonNode structured, String text, boolean isError,
      PlaygroundService.Meta meta, PlaygroundService.Approval approval) {}

  private final PlaygroundService playground;

  PlaygroundController(PlaygroundService playground) {
    this.playground = playground;
  }

  @GetMapping("/api/playground/tools")
  PlaygroundService.Tools tools(@RequestParam(name = "endpoint") String endpoint,
      @RequestParam(name = "era", required = false) String era) {
    return playground.tools(endpoint, era);
  }

  @PostMapping("/api/playground/call")
  CallView call(@RequestBody Call request) {
    PlaygroundService.CallResult r = playground.call(request.endpoint(), request.tool(), request.arguments(),
        request.era());
    return new CallView(r.callId(), new Wire(r.request(), r.response(), r.httpStatus()), r.structured(), r.text(),
        r.isError(), r.meta(), r.approval());
  }

  @GetMapping("/api/playground/history")
  ActivityDtos.Page history(@RequestParam(name = "endpoint") String endpoint,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", defaultValue = "20") int limit) {
    AuditQueries.Events events = playground.history(endpoint, cursor, limit);
    List<ActivityDtos.Event> items = events.events().stream().map(ActivityDtos::event).toList();
    return new ActivityDtos.Page(items, events.nextCursor());
  }
}
