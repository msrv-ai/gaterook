package ai.msrv.gaterook.core.admin.approvals;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.core.approvals.ActionInspector;
import ai.msrv.gaterook.core.approvals.ApprovalService;
import ai.msrv.gaterook.core.approvals.ApprovalView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * The approval DTOs of the admin API (ARCHITECTURE.md §6 approvals), shared by Approvals and Overview's "Waiting for
 * you". They are built from {@link ApprovalView}; the exact action reaches the UI only as {@link ActionInspector}
 * segments plus its text (for copying), never re-formatted. Nothing here carries a secret: the action, its fields and
 * the stored result were masked before they were stored (§5 step 11).
 */
public final class ApprovalDtos {

  /**
   * One row of Approvals › Pending or History, and of Overview's "Waiting for you".
   *
   * @param tokenLabel the trusted label of who asked (token name, grant label: {@code codex-laptop})
   * @param client     the client family Activity shows ({@code codex}, {@code claude-desktop}, {@code playground})
   */
  public record Item(String id, String tool, String connection, String env, String endpoint, String client,
      String tokenLabel, Instant requestedAt, Instant expiresAt, String status, String summary, String decidedVia,
      Instant decidedAt, String commentPolicy) {}

  /** The exact action: segments to draw ({@code t}: {@code text}, {@code invisible}, {@code confusable}). */
  public record Action(String kind, String language, String text, List<ActionInspector.Segment> segments,
      int hiddenChars, int confusables, String warning, Diff diff) {}

  /** A unified diff as hunks for {@code gw-diff}. */
  public record Diff(int added, int removed, List<Hunk> hunks) {}

  /** {@code header}: the {@code @@ … @@} line. */
  public record Hunk(String header, List<DiffLine> lines) {}

  /** {@code t}: {@code context}, {@code add} or {@code del}. */
  public record DiffLine(String t, String v) {}

  public record Field(String label, String text) {}

  /** {@code text}: what the rule means for the person ("Every change waits for a person on dev."). */
  public record Rule(String id, String text) {}

  /** The masked result of an executed request while it is kept (60 min). */
  public record Result(String text, JsonNode structured, boolean isError, boolean truncated) {}

  public record TimelineEntry(long id, Instant at, String kind, JsonNode detail) {}

  /** Where the request was announced: {@code sent}, {@code failed} or {@code off} per channel. */
  public record Channels(String desktop, String slack, String telegram) {}

  /**
   * {@code GET /api/approvals/{id}}.
   *
   * @param eventId     the call in Activity: the id of its latest audit row, as the events table names the call
   *                    ({@code /activity#event-<id>}); {@code null} when the audit no longer keeps it
   * @param traceId     the call's trace (the kit's "Trace 4f2a9c…"), {@code null} without one
   * @param traceIsTask whether Activity › Traces lists that trace as an agent's task (a client's traceparent or title)
   */
  public record Detail(String id, String callId, String tool, String toolKey, String connection, String env,
      String endpoint, String client, String tokenLabel, Instant requestedAt, Instant expiresAt, String status,
      Action action, String target, String summary, List<Field> fields, List<Field> impact, List<String> warnings,
      Rule rule, String agentNote, String commentPolicy, String comment, String digest, String decidedVia,
      Instant decidedAt, Instant executedAt, String error, Result result, boolean resultExpired,
      List<TimelineEntry> timeline, Channels channels, Long eventId, String traceId, boolean traceIsTask) {}

  /** Where the request's call is in Activity: its event and its trace. */
  public record CallRef(Long eventId, String traceId, boolean traceIsTask) {

    public static final CallRef NONE = new CallRef(null, null, false);
  }

  private ApprovalDtos() {}

  public static Item item(ApprovalView a) {
    return new Item(a.id(), a.tool(), a.connectionName(), a.env().id(), a.endpointSlug(), client(a), a.clientLabel(),
        a.createdAt(), a.expiresAt(), a.status(), a.action() == null ? null : a.action().summary(), a.decidedVia(),
        a.decidedAt(), a.commentPolicy());
  }

  /** An exact action as {@code gw-code-block}/{@code gw-diff} draw it: the inspector's segments, never re-formatted. */
  public static Action action(ActionPreview p) {
    ActionInspector.Inspection inspection = ActionInspector.inspect(p.text(), p.language());
    return new Action(p.kind() == null ? null : p.kind().name().toLowerCase(java.util.Locale.ROOT), p.language(),
        p.text(), inspection.segments(), inspection.hiddenChars(), inspection.confusables(), inspection.warning(),
        "diff".equals(p.language()) ? diff(p.text()) : null);
  }

  /** {@code POST /api/templates/{id}/preview}: what a call with these arguments would run, as an approver sees it. */
  public record Preview(Action action, String target, String summary, List<Field> fields, List<Field> impact,
      List<String> warnings) {}

  public static Preview preview(ActionPreview p) {
    return new Preview(action(p), p.target(), p.summary(), fields(p.fields()), fields(p.impact()),
        p.warnings() == null ? List.of() : List.copyOf(p.warnings()));
  }

  public static Detail detail(ApprovalView a, List<ApprovalService.TimelineEntry> timeline) {
    return detail(a, timeline, CallRef.NONE);
  }

  public static Detail detail(ApprovalView a, List<ApprovalService.TimelineEntry> timeline, CallRef call) {
    ActionPreview p = a.action();
    Action action = null;
    List<Field> fields = List.of();
    List<Field> impact = List.of();
    List<String> warnings = List.of();
    if (p != null) {
      action = action(p);
      fields = fields(p.fields());
      impact = fields(p.impact());
      warnings = p.warnings() == null ? List.of() : List.copyOf(p.warnings());
    }
    List<TimelineEntry> entries = timeline.stream().map(e -> new TimelineEntry(e.id(), e.at(), e.kind(), e.detail()))
        .toList();
    boolean expired = "completed".equals(a.status()) && a.result() == null;
    return new Detail(a.id(), a.callId(), a.tool(), a.toolKey(), a.connectionName(), a.env().id(), a.endpointSlug(),
        client(a), a.clientLabel(), a.createdAt(), a.expiresAt(), a.status(), action, p == null ? null : p.target(),
        p == null ? null : p.summary(), fields, impact, warnings, new Rule(a.ruleId(), ruleText(a)), a.agentNote(),
        a.commentPolicy(), a.comment(), a.digest(), a.decidedVia(), a.decidedAt(), a.executedAt(), a.error(),
        result(a.result()), expired, entries, channels(timeline), call.eventId(), call.traceId(), call.traceIsTask());
  }

  /** The client family the request's call reported, from its canonical call ({@code origin.client}). */
  static String client(ApprovalView a) {
    JsonNode client = a.call() == null ? null : a.call().path("origin").path("client");
    return client != null && client.isString() ? client.asString() : null;
  }

  static String ruleText(ApprovalView a) {
    String rule = a.ruleId() == null ? "" : a.ruleId();
    if (rule.endsWith(".write-approval")) {
      return "Every change waits for a person on " + a.env().id() + ".";
    }
    if (rule.endsWith(".approval-mode")) {
      return a.tool() + " is set to Approval on " + a.endpointSlug() + ", so every call waits for a person.";
    }
    if (rule.endsWith(".runbook-approval")) {
      return "This runbook changes state, so every run waits for a person.";
    }
    return "Rule " + rule + " asks a person first.";
  }

  private static List<Field> fields(List<ActionPreview.Field> fields) {
    if (fields == null) {
      return List.of();
    }
    return fields.stream().map(f -> new Field(f.label(), f.value())).toList();
  }

  private static Result result(ToolResult r) {
    if (r == null) {
      return null;
    }
    StringBuilder text = new StringBuilder();
    for (ToolResult.Content c : r.content()) {
      if (c.text() != null) {
        text.append(text.isEmpty() ? "" : "\n").append(c.text());
      }
    }
    return new Result(text.toString(), r.structuredContent(), r.isError(), r.truncated());
  }

  private static Channels channels(List<ApprovalService.TimelineEntry> timeline) {
    String desktop = "off";
    String slack = "off";
    String telegram = "off";
    for (ApprovalService.TimelineEntry e : timeline) {
      if (!"notified".equals(e.kind()) || e.detail() == null) {
        continue;
      }
      String channel = e.detail().path("channel").asString("");
      String state = e.detail().path("state").asString("sent");
      switch (channel) {
        case "desktop" -> desktop = state;
        case "slack" -> slack = state;
        case "telegram" -> telegram = state;
        default -> { }
      }
    }
    return new Channels(desktop, slack, telegram);
  }

  /** Splits a unified diff into hunks; lines before the first {@code @@} (file headers) are left out. */
  static Diff diff(String text) {
    List<Hunk> hunks = new ArrayList<>();
    String header = null;
    List<DiffLine> lines = null;
    int added = 0;
    int removed = 0;
    for (String line : text == null ? new String[0] : text.split("\n")) {
      if (line.startsWith("@@")) {
        if (header != null) {
          hunks.add(new Hunk(header, List.copyOf(lines)));
        }
        header = line;
        lines = new ArrayList<>();
      } else if (header != null) {
        if (line.startsWith("+")) {
          lines.add(new DiffLine("add", line.substring(1)));
          added++;
        } else if (line.startsWith("-")) {
          lines.add(new DiffLine("del", line.substring(1)));
          removed++;
        } else if (!line.startsWith("\\")) {
          lines.add(new DiffLine("context", line.isEmpty() ? "" : line.substring(1)));
        }
      }
    }
    if (header != null) {
      hunks.add(new Hunk(header, List.copyOf(lines)));
    }
    return new Diff(added, removed, List.copyOf(hunks));
  }
}
