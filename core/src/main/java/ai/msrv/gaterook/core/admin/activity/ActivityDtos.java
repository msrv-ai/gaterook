package ai.msrv.gaterook.core.admin.activity;

import ai.msrv.gaterook.core.audit.AuditQueries;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** The Activity DTOs (ARCHITECTURE.md §6 activity), shared by Overview's "Recent" and Playground's history. */
public final class ActivityDtos {

  /**
   * One call of the events table, as its latest audit row says.
   *
   * @param id         the latest row's id: {@code GET /api/activity/events/{id}} and {@code #event-<id>}
   * @param tokenLabel the trusted label of who called ({@code codex-laptop}, {@code gwt_K9xP…}, {@code playground})
   */
  public record Event(long id, String callId, Instant at, String phase, String decision, String tool,
      String connection, String env, String client, String endpoint, String tokenLabel, Long durationMs, String rule,
      String approvalId, String traceId, String errorKind, String errorMessage) {}

  public record Page(List<Event> events, String nextCursor) {}

  /** One Setup change: Time · What · Change · By · Reason. */
  public record Change(long id, Instant at, String what, String subject, String from, String to, String by,
      String reason, String env, String endpoint, String connection, Map<String, Object> details) {}

  private ActivityDtos() {}

  public static Event event(AuditQueries.Event e) {
    return new Event(e.id(), e.callId(), e.at(), e.phase(), e.decision(), e.tool(), e.connection(), e.env(),
        e.client(), e.endpoint(), e.token(), e.durationMs(), e.rule(), e.approvalId(), e.traceId(), e.errorKind(),
        e.errorMessage());
  }

  public static Change change(AuditQueries.Change c) {
    return new Change(c.id(), c.at(), c.what(), c.subject(), c.from(), c.to(), c.by(), c.reason(), c.env(),
        c.endpoint(), c.connection(), c.details());
  }
}
