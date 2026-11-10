package ai.msrv.gaterook.core.admin.approvals;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.core.approvals.ActionInspector;
import ai.msrv.gaterook.core.approvals.ApprovalService;
import ai.msrv.gaterook.core.approvals.ApprovalView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * The approval DTOs of the admin API (ARCHITECTURE.md §6 approvals), shared by Approvals and Overview's "Waiting for
 * you". They are built from {@link ApprovalView}; the exact action reaches the UI only as {@link ActionInspector}
 * segments plus its text (for copying), never re-formatted, and the texts shown with it carry the inspector's cut where
 * it found something ({@link Detail}). Nothing here carries a secret: the action, its fields and the stored result were
 * masked before they were stored (§5 step 11).
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

  /**
   * One line the review shows with the action: the connector's label and its value (a template's parameter values, the
   * estimated rows), which can hold what the agent chose, and a plugin's own words. {@code labelSegments} and
   * {@code segments} are the {@link ActionInspector}'s cut of the label and the value when it found a hidden character
   * or a letter that passes for another in them, else {@code null}: the UI draws them as it draws the action's.
   */
  public record Field(String label, String text, List<ActionInspector.Segment> labelSegments,
      List<ActionInspector.Segment> segments) {}

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
   * <p>Everything the review shows besides the action that the agent, the connector, a plugin or an upstream service
   * chose is inspected as the action is (ARCHITECTURE.md §8.5): the target, every field and impact line, every warning
   * and the agent's note. Their segments are given where the inspector found something, and
   * {@code detailsHiddenChars} and {@code detailsConfusables} count it all, so a hidden character in a template's
   * parameter value gets a warning as one in the action does.
   *
   * @param eventId            the call in Activity: the id of its latest audit row, as the events table names the
   *                           call ({@code /activity#event-<id>}); {@code null} when the audit no longer keeps it
   * @param traceId            the call's trace (the kit's "Trace 4f2a9c…"), {@code null} without one
   * @param traceIsTask        whether Activity › Traces lists that trace as an agent's task (a client's traceparent or
   *                           title)
   * @param targetSegments     the inspector's cut of {@code target}, or {@code null} when it found nothing
   * @param warningSegments    one entry per warning, in order: its cut, or {@code null} when it found nothing
   * @param agentNoteSegments  the inspector's cut of the agent's note, or {@code null} when it found nothing
   * @param detailsHiddenChars the hidden characters of the target, the fields, the impact, the warnings and the note
   * @param detailsConfusables the letters that pass for others there
   */
  public record Detail(String id, String callId, String tool, String toolKey, String connection, String env,
      String endpoint, String client, String tokenLabel, Instant requestedAt, Instant expiresAt, String status,
      Action action, String target, String summary, List<Field> fields, List<Field> impact, List<String> warnings,
      Rule rule, String agentNote, String commentPolicy, String comment, String digest, String decidedVia,
      Instant decidedAt, Instant executedAt, String error, Result result, boolean resultExpired,
      List<TimelineEntry> timeline, Channels channels, Long eventId, String traceId, boolean traceIsTask,
      List<ActionInspector.Segment> targetSegments, List<List<ActionInspector.Segment>> warningSegments,
      List<ActionInspector.Segment> agentNoteSegments, int detailsHiddenChars, int detailsConfusables) {}

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

  /**
   * An exact action as {@code gw-code-block}/{@code gw-diff} draw it: the inspector's segments, never re-formatted;
   * a diff's hunks only when they show all of it ({@link #diff}).
   */
  public static Action action(ActionPreview p) {
    ActionInspector.Inspection inspection = ActionInspector.inspect(p.text(), p.language());
    return new Action(p.kind() == null ? null : p.kind().name().toLowerCase(java.util.Locale.ROOT), p.language(),
        p.text(), inspection.segments(), inspection.hiddenChars(), inspection.confusables(), inspection.warning(),
        "diff".equals(p.language()) ? diff(p.text()) : null);
  }

  /**
   * {@code POST /api/templates/{id}/preview}: what a call with these arguments would run, as an approver sees it, with
   * the target, fields and warnings inspected as {@link Detail} has them.
   */
  public record Preview(Action action, String target, String summary, List<Field> fields, List<Field> impact,
      List<String> warnings, List<ActionInspector.Segment> targetSegments,
      List<List<ActionInspector.Segment>> warningSegments, int detailsHiddenChars, int detailsConfusables) {}

  public static Preview preview(ActionPreview p) {
    Shown shown = new Shown();
    List<Field> fields = shown.fields(p.fields());
    List<Field> impact = shown.fields(p.impact());
    List<String> warnings = p.warnings() == null ? List.of() : List.copyOf(p.warnings());
    return new Preview(action(p), p.target(), p.summary(), fields, impact, warnings, shown.marks(p.target()),
        shown.marks(warnings), shown.hidden, shown.confusables);
  }

  public static Detail detail(ApprovalView a, List<ApprovalService.TimelineEntry> timeline) {
    return detail(a, timeline, CallRef.NONE);
  }

  public static Detail detail(ApprovalView a, List<ApprovalService.TimelineEntry> timeline, CallRef call) {
    ActionPreview p = a.action();
    Shown shown = new Shown();
    Action action = null;
    List<Field> fields = List.of();
    List<Field> impact = List.of();
    List<String> warnings = List.of();
    if (p != null) {
      action = action(p);
      fields = shown.fields(p.fields());
      impact = shown.fields(p.impact());
      warnings = p.warnings() == null ? List.of() : List.copyOf(p.warnings());
    }
    String target = p == null ? null : p.target();
    List<ActionInspector.Segment> targetSegments = shown.marks(target);
    List<List<ActionInspector.Segment>> warningSegments = shown.marks(warnings);
    List<ActionInspector.Segment> noteSegments = shown.marks(a.agentNote());
    List<TimelineEntry> entries = timeline.stream().map(e -> new TimelineEntry(e.id(), e.at(), e.kind(), e.detail()))
        .toList();
    boolean expired = "completed".equals(a.status()) && a.result() == null;
    return new Detail(a.id(), a.callId(), a.tool(), a.toolKey(), a.connectionName(), a.env().id(), a.endpointSlug(),
        client(a), a.clientLabel(), a.createdAt(), a.expiresAt(), a.status(), action, target,
        p == null ? null : p.summary(), fields, impact, warnings, new Rule(a.ruleId(), ruleText(a)), a.agentNote(),
        a.commentPolicy(), a.comment(), a.digest(), a.decidedVia(), a.decidedAt(), a.executedAt(), a.error(),
        result(a.result()), expired, entries, channels(timeline), call.eventId(), call.traceId(), call.traceIsTask(),
        targetSegments, warningSegments, noteSegments, shown.hidden, shown.confusables);
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

  /**
   * The inspector's verdict on the texts a review shows besides the action, counted together. They are inspected as
   * plain text, the way a chat message's details are ({@code notifications.internal.Notices}): a letter of another
   * script outside quotes counts, a quoted value (a template's string parameter) is read like a literal of the action,
   * and every hidden character counts wherever it is. So the window warns about whatever made a channel withhold
   * Approve.
   */
  private static final class Shown {

    private int hidden;
    private int confusables;

    /** The cut of {@code text}, or {@code null} when the inspector found nothing in it (or it is empty). */
    List<ActionInspector.Segment> marks(String text) {
      if (text == null || text.isEmpty()) {
        return null;
      }
      ActionInspector.Inspection inspection = ActionInspector.inspect(text, null);
      hidden += inspection.hiddenChars();
      confusables += inspection.confusables();
      return inspection.clean() ? null : inspection.segments();
    }

    /** One entry per text, in order: its cut, or {@code null}. */
    List<List<ActionInspector.Segment>> marks(List<String> texts) {
      List<List<ActionInspector.Segment>> out = new ArrayList<>(texts.size());
      for (String text : texts) {
        out.add(marks(text));
      }
      return Collections.unmodifiableList(out);
    }

    List<Field> fields(List<ActionPreview.Field> fields) {
      if (fields == null) {
        return List.of();
      }
      List<Field> out = new ArrayList<>(fields.size());
      for (ActionPreview.Field f : fields) {
        out.add(new Field(f.label(), f.value(), marks(f.label()), marks(f.value())));
      }
      return List.copyOf(out);
    }
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

  /** What GNU diff and git write after a line that doesn't end with a line break; the only note a hunk may hold. */
  private static final String NO_NEWLINE = "\\ No newline at end of file";

  /**
   * Splits a unified diff into hunks, or {@code null} when the hunks would not show all of it: the diff view draws
   * the hunks and leaves out a file header ({@code --- }/{@code +++}) before the first one, a line's marker and the
   * {@link #NO_NEWLINE} note. Anything else (no hunk at all, other text before the first, a line without a marker,
   * another note) would be invisible there, so such a text is shown as it is.
   */
  static Diff diff(String text) {
    List<Hunk> hunks = new ArrayList<>();
    String header = null;
    List<DiffLine> lines = null;
    List<String> before = new ArrayList<>();
    int added = 0;
    int removed = 0;
    for (String line : text == null ? new String[0] : text.split("\n")) {
      String bare = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
      if (line.startsWith("@@")) {
        if (header != null) {
          hunks.add(new Hunk(header, List.copyOf(lines)));
        }
        header = line;
        lines = new ArrayList<>();
      } else if (header == null) {
        before.add(line);
      } else if (line.startsWith("+")) {
        lines.add(new DiffLine("add", line.substring(1)));
        added++;
      } else if (line.startsWith("-")) {
        lines.add(new DiffLine("del", line.substring(1)));
        removed++;
      } else if (bare.isEmpty() || line.startsWith(" ")) {
        lines.add(new DiffLine("context", line.isEmpty() ? "" : line.substring(1)));
      } else if (!bare.equals(NO_NEWLINE)) {
        return null;
      }
    }
    if (header == null || !fileHeader(before)) {
      return null;
    }
    hunks.add(new Hunk(header, List.copyOf(lines)));
    return new Diff(added, removed, List.copyOf(hunks));
  }

  /** Nothing, or one {@code --- old} / {@code +++ new} pair: what may precede the first hunk unseen. */
  private static boolean fileHeader(List<String> lines) {
    return lines.isEmpty()
        || (lines.size() == 2 && lines.get(0).startsWith("--- ") && lines.get(1).startsWith("+++ "));
  }
}
