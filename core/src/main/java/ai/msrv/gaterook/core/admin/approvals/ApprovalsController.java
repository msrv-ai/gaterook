package ai.msrv.gaterook.core.admin.approvals;

import ai.msrv.gaterook.core.admin.ApiRequestException;
import ai.msrv.gaterook.core.approvals.ApprovalService;
import ai.msrv.gaterook.core.approvals.ApprovalView;
import ai.msrv.gaterook.core.audit.AuditQueries;
import ai.msrv.gaterook.core.platform.ProblemException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Approvals (ARCHITECTURE.md §6 approvals, kit {@code approvals.html}): the pending requests by time to their
 * auto-deny, the history page by page, one request with its exact action as segments, and the decision. Approve needs
 * the digest the person saw; an OS notification can only deny; prod needs a comment — {@link ApprovalService#decide}
 * enforces all three, so a channel can't skip them either.
 */
@RestController
@RegisterReflectionForBinding({ApprovalsController.ListPage.class, ApprovalDtos.Item.class, ApprovalDtos.Detail.class,
    ApprovalDtos.Action.class, ApprovalDtos.Diff.class, ApprovalDtos.Hunk.class, ApprovalDtos.DiffLine.class,
    ApprovalDtos.Field.class, ApprovalDtos.Rule.class, ApprovalDtos.Result.class, ApprovalDtos.TimelineEntry.class,
    ApprovalDtos.Channels.class, ApprovalsController.Decided.class})
class ApprovalsController {

  /**
   * A list: {@code counts} are of the last 24 hours ("History 58": approved, rejected, expired, failed).
   *
   * @param pending how many wait now (the tab's count), whatever the list shows
   */
  record ListPage(String status, List<ApprovalDtos.Item> items, String nextCursor, long pending,
      Map<String, Long> counts) {}

  /** {@code POST /api/approvals/{id}/decision}. {@code via}: {@code app} or {@code desktop-notification}. */
  record Decision(String decision, String comment, String digest, String via) {}

  record Decided(String id, String status) {}

  private final ApprovalService approvals;
  private final AuditQueries audit;
  private final Clock clock;

  ApprovalsController(ApprovalService approvals, AuditQueries audit, Clock clock) {
    this.approvals = approvals;
    this.audit = audit;
    this.clock = clock;
  }

  @GetMapping("/api/approvals")
  ListPage list(@RequestParam(name = "status", defaultValue = "pending") String status,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", defaultValue = "50") int limit) {
    long pending = approvals.pendingTotal();
    Map<String, Long> counts = approvals.statusCounts(clock.instant().minus(Duration.ofHours(24)));
    return switch (status) {
      case "pending" -> new ListPage(status, approvals.pending().stream().map(ApprovalDtos::item).toList(), null,
          pending, counts);
      case "history" -> {
        ApprovalService.Page page = approvals.history(cursor, limit);
        yield new ListPage(status, page.items().stream().map(ApprovalDtos::item).toList(), page.nextCursor(),
            pending, counts);
      }
      default -> throw ApiRequestException.unprocessable("Unknown list", "List pending or history requests.",
          "/status");
    };
  }

  @GetMapping("/api/approvals/{id}")
  ApprovalDtos.Detail get(@PathVariable String id) {
    ApprovalView a = approvals.find(id).orElseThrow(() -> ProblemException.notFound("Request not found",
        "There is no approval request " + id + "."));
    return ApprovalDtos.detail(a, approvals.timeline(id), callRef(a));
  }

  /**
   * The request's call in the audit: the latest row names it in Activity, the rows carry its trace (the canonical
   * call's when the audit no longer keeps them). A trace is a task when it came from the client (the canonical call's
   * origin says so: Activity › Traces lists exactly those, a moment after the call, once the trace is summarized);
   * for a request stored without the flag, when Traces knows it already.
   */
  private ApprovalDtos.CallRef callRef(ApprovalView a) {
    List<AuditQueries.Event> rows = a.callId() == null ? List.of() : audit.call(a.callId());
    Long eventId = rows.isEmpty() ? null : rows.getLast().id();
    JsonNode origin = a.call() == null ? null : a.call().path("origin");
    String traceId = rows.stream().map(AuditQueries.Event::traceId).filter(java.util.Objects::nonNull).findFirst()
        .orElse(origin == null ? null : origin.path("traceId").asString(null));
    boolean task = traceId != null && (origin != null && origin.has("traceFromClient")
        ? origin.path("traceFromClient").asBoolean(false)
        : audit.trace(traceId).map(t -> t.traceId().equals(traceId)).orElse(false));
    return new ApprovalDtos.CallRef(eventId, traceId, task);
  }

  @PostMapping("/api/approvals/{id}/decision")
  Decided decide(@PathVariable String id, @RequestBody Decision request) {
    ApprovalService.Decision decision = switch (request.decision() == null ? ""
        : request.decision().toLowerCase(Locale.ROOT)) {
      case "approve" -> ApprovalService.Decision.APPROVE;
      case "deny" -> ApprovalService.Decision.DENY;
      default -> throw ApiRequestException.unprocessable("Approve or deny", "The decision is approve or deny.",
          "/decision");
    };
    String via = request.via() == null || request.via().isBlank() ? "app" : request.via();
    if (!"app".equals(via) && !"desktop-notification".equals(via)) {
      // Slack and Telegram decide through their own channels, never through the window's API.
      throw ApiRequestException.unprocessable("Unknown channel", "Decide in the app or from a notification.",
          "/via");
    }
    ApprovalView a = approvals.decide(id, decision, via, request.comment(), request.digest());
    return new Decided(a.id(), a.status());
  }
}
