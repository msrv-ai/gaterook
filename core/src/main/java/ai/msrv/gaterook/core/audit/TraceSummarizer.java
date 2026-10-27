package ai.msrv.gaterook.core.audit;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.audit.internal.persistence.CallPhase;
import ai.msrv.gaterook.core.audit.internal.persistence.ToolCallEntity;
import ai.msrv.gaterook.core.platform.KebabCaseEnumConverter;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Activity › Traces (ARCHITECTURE.md §4 "Traces"): what one agent task did, in phases a person reads at a glance.
 * <ul>
 *   <li><b>Explore</b> — reads before anything changes;</li>
 *   <li><b>Change</b> — a call that changes data or went through approval, together with the reads on the same
 *       connection right before it (the agent checking what it is about to change);</li>
 *   <li><b>Wait for deploy</b> — after a change, {@code k8s_get} polls and any read repeated with the same arguments;</li>
 *   <li><b>Verify</b> — other reads after a change;</li>
 *   <li><b>Clean up</b> — refused destructive calls at the end of the task ({@code TRUNCATE}, {@code DROP},
 *       {@code DELETE}), by their own classification rather than as a change.</li>
 * </ul>
 * The title is what the client called the task ({@code _meta["ai.msrv.gaterook/task"]}), else the first {@code note} an
 * agent wrote, else the client's name; it is masked like everything in the audit, cut to 120 characters, and always
 * shown as unverified. A pure function of the rows: the golden tests run it on the kit's traces.
 */
public final class TraceSummarizer {

  /** A task with no call for this long is over (unless it still waits for a person). */
  public static final Duration IN_PROGRESS = Duration.ofMinutes(5);

  /** Longest title kept. */
  public static final int TITLE_CHARS = 120;

  private static final Pattern DESTRUCTIVE = Pattern.compile("^\\s*(TRUNCATE|DROP|DELETE)\\b",
      Pattern.CASE_INSENSITIVE);
  private static final Pattern WORDS = Pattern.compile("\\s+");
  private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}\\p{Cf}]");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** One call of the trace, from its rows. */
  public record Step(String callId, String tool, String connection, Env env, Instant startedAt, Instant endedAt,
      String decision, boolean changing, String ruleId, String errorKind, long approvalWaitMs, JsonNode args,
      String task, String clientName, String client, String endpoint, String argsSha256) {

    boolean refused() {
      return "denied".equals(decision) || "rejected".equals(decision) || "expired".equals(decision);
    }
  }

  /** One phase: what the timeline of Activity › Traces shows. */
  public record Phase(String name, Instant from, Instant to, int calls, Map<String, Integer> decisions,
      String summary) {}

  /**
   * One task.
   *
   * @param titleSource {@code meta}, {@code note} or {@code client}; {@code null} without a title
   * @param waiting     a call still waits for a person
   */
  public record Summary(String title, String titleSource, Instant firstAt, Instant lastAt, int calls,
      Map<String, Integer> decisions, long waitedForPeopleMs, List<Phase> phases, boolean waiting, String client,
      String endpoint) {

    /** {@code waiting}, {@code in-progress} or {@code ended} at {@code now}. */
    public String status(Instant now) {
      if (waiting) {
        return "waiting";
      }
      return lastAt.plus(IN_PROGRESS).isAfter(now) ? "in-progress" : "ended";
    }

    public Duration duration() {
      return Duration.between(firstAt, lastAt);
    }
  }

  private TraceSummarizer() {}

  /** The calls of a trace from its audit rows (any order of ids; rows of one call share its call id). */
  public static List<Step> steps(List<ToolCallEntity> rows) {
    Map<String, List<ToolCallEntity>> byCall = new LinkedHashMap<>();
    rows.stream().sorted((a, b) -> Long.compare(a.getId() == null ? 0 : a.getId(), b.getId() == null ? 0 : b.getId()))
        .forEach(r -> byCall.computeIfAbsent(r.getCallId(), k -> new ArrayList<>()).add(r));
    List<Step> steps = new ArrayList<>();
    for (List<ToolCallEntity> call : byCall.values()) {
      ToolCallEntity first = call.get(0);
      ToolCallEntity last = call.get(call.size() - 1);
      ToolCallEntity received = call.stream().filter(r -> r.getPhase() == CallPhase.RECEIVED).findFirst().orElse(first);
      String approvalId = null;
      long waited = 0;
      String errorKind = null;
      String rule = null;
      for (ToolCallEntity r : call) {
        approvalId = r.getApprovalId() != null ? r.getApprovalId() : approvalId;
        waited = Math.max(waited, r.getApprovalWaitMs() == null ? 0 : r.getApprovalWaitMs());
        errorKind = r.getErrorKind() != null ? r.getErrorKind() : errorKind;
        rule = r.getRuleId() != null ? r.getRuleId() : rule;
      }
      Map<String, Object> facets = received.getFacets();
      String nature = facets == null ? null : Objects.toString(facets.get("nature"), null);
      Classification.Kind kind = received.getClassification();
      boolean changing = kind == Classification.Kind.MUTATION || approvalId != null
          || (kind == Classification.Kind.DENY && nature != null && !"read".equals(nature));
      String task = facets == null ? null : Objects.toString(facets.get("task"), null);
      Map<String, Object> clientInfo = received.getClientInfo();
      String clientName = clientInfo == null ? null : Objects.toString(clientInfo.get("name"), null);
      steps.add(new Step(first.getCallId(), first.getToolName(), first.getConnectionName(), first.getEnv(),
          first.getCreatedAt(), last.getCreatedAt(), KebabCaseEnumConverter.idOf(last.getDecision()), changing, rule,
          errorKind, waited, received.getArgsMasked() == null ? null : JSON.valueToTree(received.getArgsMasked()),
          task, clientName, received.getClient(), first.getEndpointSlug(), received.getArgsSha256()));
    }
    return steps;
  }

  public static Summary summarize(List<Step> steps) {
    if (steps.isEmpty()) {
      throw new IllegalArgumentException("A trace has at least one call");
    }
    List<List<Step>> groups = new ArrayList<>();
    List<String> names = new ArrayList<>();
    int tail = steps.size();
    while (tail > 0 && cleanUp(steps.get(tail - 1))) {
      tail--;
    }
    boolean changed = false;
    Step previous = null;
    for (int i = 0; i < tail; i++) {
      Step s = steps.get(i);
      String name;
      if (s.changing()) {
        name = "Change";
        List<Step> pulled = new ArrayList<>();
        if (!names.isEmpty() && !"Change".equals(last(names))) {
          List<Step> current = groups.get(groups.size() - 1);
          while (!current.isEmpty() && !current.get(current.size() - 1).changing()
              && Objects.equals(current.get(current.size() - 1).connection(), s.connection())) {
            pulled.add(0, current.remove(current.size() - 1));
          }
          if (current.isEmpty()) {
            groups.remove(groups.size() - 1);
            names.remove(names.size() - 1);
          }
        }
        if (!names.isEmpty() && "Change".equals(last(names))) {
          groups.get(groups.size() - 1).addAll(pulled);
          groups.get(groups.size() - 1).add(s);
        } else {
          List<Step> group = new ArrayList<>(pulled);
          group.add(s);
          groups.add(group);
          names.add(name);
        }
        changed = true;
        previous = s;
        continue;
      }
      if (changed && poll(s, previous)) {
        name = "Wait for deploy";
      } else {
        name = changed ? "Verify" : "Explore";
      }
      if (!names.isEmpty() && name.equals(last(names))) {
        groups.get(groups.size() - 1).add(s);
      } else {
        List<Step> group = new ArrayList<>();
        group.add(s);
        groups.add(group);
        names.add(name);
      }
      previous = s;
    }
    if (tail < steps.size()) {
      groups.add(new ArrayList<>(steps.subList(tail, steps.size())));
      names.add("Clean up");
    }

    List<Phase> phases = new ArrayList<>();
    for (int i = 0; i < groups.size(); i++) {
      List<Step> group = groups.get(i);
      phases.add(new Phase(names.get(i), group.get(0).startedAt(), endOf(group), group.size(), decisions(group),
          summary(names.get(i), group)));
    }
    Instant first = steps.get(0).startedAt();
    Instant last = endOf(steps);
    long waited = steps.stream().mapToLong(Step::approvalWaitMs).sum();
    String[] title = title(steps);
    Step head = steps.get(0);
    return new Summary(title[0], title[1], first, last, steps.size(), decisions(steps), waited, List.copyOf(phases),
        steps.stream().anyMatch(s -> "pending".equals(s.decision())), head.client(), head.endpoint());
  }

  /** A refused call that would have destroyed data: at the end of a task it is the "Clean up". */
  private static boolean cleanUp(Step s) {
    return s.changing() && s.refused() && DESTRUCTIVE.matcher(statement(s)).find();
  }

  /** After a change: {@code k8s_get}, or a read repeated with the same arguments, is the agent waiting. */
  private static boolean poll(Step s, Step previous) {
    if ("k8s_get".equals(s.tool())) {
      return true;
    }
    return previous != null && !previous.changing() && s.tool().equals(previous.tool())
        && s.argsSha256() != null && s.argsSha256().equals(previous.argsSha256());
  }

  private static Instant endOf(List<Step> group) {
    Instant end = group.get(0).endedAt();
    for (Step s : group) {
      if (s.endedAt().isAfter(end)) {
        end = s.endedAt();
      }
    }
    return end;
  }

  private static Map<String, Integer> decisions(List<Step> steps) {
    Map<String, Integer> out = new LinkedHashMap<>();
    for (String d : List.of("allowed", "approved", "pending", "denied", "rejected", "expired", "error")) {
      int n = (int) steps.stream().filter(s -> d.equals(s.decision())).count();
      if (n > 0) {
        out.put(d, n);
      }
    }
    return out;
  }

  // ── Texts ───────────────────────────────────────────────────────────────────────────────────────────────────

  private static String summary(String phase, List<Step> group) {
    return switch (phase) {
      case "Change" -> change(group);
      case "Clean up" -> cleanUpText(group);
      case "Wait for deploy" -> waitText(group);
      default -> pairs(group);
    };
  }

  /**
   * {@code logs_search on app-logs, sql_query on dev-db, redis_get on cache-redis}: each tool and connection once, in
   * order. Errors show as the phase's decisions ("1 error"), not in the text.
   */
  private static String pairs(List<Step> group) {
    Set<String> seen = new LinkedHashSet<>();
    for (Step s : group) {
      seen.add(s.connection() == null ? s.tool() : s.tool() + " on " + s.connection());
    }
    return String.join(", ", seen);
  }

  /** {@code sql_query twice, then sql_execute approved by you after 47 s}: the connection is the phase's own. */
  private static String change(List<Step> group) {
    List<String> parts = new ArrayList<>();
    Map<String, Integer> reads = new LinkedHashMap<>();
    StringBuilder out = new StringBuilder();
    for (Step s : group) {
      if (!s.changing()) {
        reads.merge(s.tool(), 1, Integer::sum);
        continue;
      }
      if (!reads.isEmpty()) {
        List<String> r = new ArrayList<>();
        reads.forEach((tool, n) -> r.add(tool + " " + times(n)));
        parts.add(String.join(", ", r));
        reads.clear();
      }
      String change = s.tool() + " " + outcome(s);
      parts.add(parts.isEmpty() ? change : "then " + change);
    }
    out.append(String.join(", ", parts));
    if (!reads.isEmpty()) {
      List<String> r = new ArrayList<>();
      reads.forEach((tool, n) -> r.add(tool + " " + times(n)));
      out.append(", then ").append(String.join(", ", r));
    }
    return out.toString();
  }

  private static String outcome(Step s) {
    return switch (s.decision()) {
      case "approved" -> "approved by you" + (s.approvalWaitMs() > 0 ? " after " + seconds(s.approvalWaitMs()) : "");
      case "pending" -> "waiting for you";
      case "rejected" -> "rejected";
      case "expired" -> "expired without a decision";
      case "denied" -> "denied by " + (s.ruleId() == null ? "policy" : s.ruleId());
      case "error" -> "failed";
      default -> s.decision();
    };
  }

  /** {@code sql_execute with TRUNCATE, then DROP TABLE on prod-replica; both stopped by prod.sql.block_ddl}. */
  private static String cleanUpText(List<Step> group) {
    Set<String> tools = new LinkedHashSet<>();
    List<String> verbs = new ArrayList<>();
    Set<String> connections = new LinkedHashSet<>();
    Set<String> rules = new LinkedHashSet<>();
    boolean allDenied = true;
    for (Step s : group) {
      tools.add(s.tool());
      verbs.add(verb(statement(s)));
      if (s.connection() != null) {
        connections.add(s.connection());
      }
      if (s.ruleId() != null) {
        rules.add(s.ruleId());
      }
      allDenied &= "denied".equals(s.decision());
    }
    StringBuilder out = new StringBuilder(String.join(", ", tools)).append(" with ").append(verbs.get(0));
    for (int i = 1; i < verbs.size(); i++) {
      out.append(i == verbs.size() - 1 ? ", then " : ", ").append(verbs.get(i));
    }
    if (!connections.isEmpty()) {
      out.append(" on ").append(String.join(", ", connections));
    }
    String who = group.size() == 1 ? "" : group.size() == 2 ? "both " : "all " + group.size() + " ";
    if (allDenied && rules.size() == 1) {
      out.append("; ").append(who).append("stopped by ").append(rules.iterator().next());
    } else {
      out.append("; ").append(who).append("not run");
    }
    return out.toString();
  }

  /** {@code k8s_get on dev-cluster, polling the rollout of deployment/order-sync}. */
  private static String waitText(List<Step> group) {
    String text = pairs(group);
    Step first = group.get(0);
    if ("k8s_get".equals(first.tool()) && first.args() != null) {
      String kind = first.args().path("kind").asString("");
      String name = first.args().path("name").asString("");
      if (!kind.isBlank() && !name.isBlank()) {
        text += ", polling the rollout of " + kind.toLowerCase(Locale.ROOT) + "/" + name;
      }
    }
    return text;
  }

  /** The statement of a SQL call (or the first text argument): what the clean-up rule reads. */
  private static String statement(Step s) {
    if (s.args() == null) {
      return "";
    }
    JsonNode sql = s.args().path("sql");
    if (sql.isString()) {
      return sql.asString();
    }
    JsonNode command = s.args().path("command");
    return command.isString() ? command.asString() : "";
  }

  /** {@code TRUNCATE}, {@code DROP TABLE}, {@code DELETE}: the statement's verb as people name it. */
  private static String verb(String statement) {
    String[] words = WORDS.split(statement.strip(), 3);
    if (words.length == 0 || words[0].isEmpty()) {
      return "a statement";
    }
    String verb = words[0].toUpperCase(Locale.ROOT);
    if ((verb.equals("DROP") || verb.equals("CREATE") || verb.equals("ALTER")) && words.length > 1) {
      return verb + " " + words[1].toUpperCase(Locale.ROOT);
    }
    return verb;
  }

  private static String[] title(List<Step> steps) {
    for (Step s : steps) {
      if (s.task() != null && !s.task().isBlank()) {
        return new String[] {clean(s.task()), "meta"};
      }
    }
    for (Step s : steps) {
      JsonNode note = s.args() == null ? null : s.args().path("note");
      if (note != null && note.isString() && !note.asString().isBlank()) {
        return new String[] {clean(note.asString()), "note"};
      }
    }
    for (Step s : steps) {
      if (s.clientName() != null && !s.clientName().isBlank()) {
        return new String[] {clean(s.clientName()), "client"};
      }
    }
    return new String[] {null, null};
  }

  private static String clean(String text) {
    String flat = CONTROL.matcher(text).replaceAll(" ").strip().replaceAll(" {2,}", " ");
    return AuditWriter.cut(flat, TITLE_CHARS);
  }

  private static String times(int n) {
    return n == 1 ? "once" : n == 2 ? "twice" : n + " times";
  }

  private static String seconds(long ms) {
    long s = Math.round(ms / 1000.0);
    return s < 60 ? s + " s" : (s / 60) + " min " + (s % 60) + " s";
  }

  private static String last(List<String> names) {
    return names.get(names.size() - 1);
  }

  /** The summary as stored in {@code trace.summary}. */
  public static JsonNode toJson(Summary s) {
    var n = JsonNodeFactory.instance.objectNode();
    n.put("waiting", s.waiting());
    n.put("waitedForPeopleMs", s.waitedForPeopleMs());
    n.put("durationMs", s.duration().toMillis());
    n.set("decisions", JSON.valueToTree(s.decisions()));
    var phases = n.putArray("phases");
    for (Phase p : s.phases()) {
      var o = phases.addObject();
      o.put("name", p.name());
      o.put("from", p.from().toEpochMilli());
      o.put("to", p.to().toEpochMilli());
      o.put("calls", p.calls());
      o.set("decisions", JSON.valueToTree(p.decisions()));
      o.put("summary", p.summary());
    }
    return n;
  }
}
