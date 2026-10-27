package ai.msrv.gaterook.core.admin.activity;

import ai.msrv.gaterook.core.admin.ApiProblem;
import ai.msrv.gaterook.core.admin.ApiRequestException;
import ai.msrv.gaterook.core.audit.ActivityQueryParser;
import ai.msrv.gaterook.core.audit.AuditExportFailedException;
import ai.msrv.gaterook.core.audit.AuditExports;
import ai.msrv.gaterook.core.audit.AuditQueries;
import ai.msrv.gaterook.core.audit.TraceSummarizer;
import ai.msrv.gaterook.core.platform.FileGrants;
import ai.msrv.gaterook.core.platform.GatewayPaths;
import ai.msrv.gaterook.core.platform.ProblemException;
import ai.msrv.gaterook.core.platform.SettingStore;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Activity (ARCHITECTURE.md §6 activity, kit {@code activity.html}, {@code log-explorer}, {@code event-detail}): the
 * events of the query language, the histogram by decision, the facets, one call with every row it wrote, the agents'
 * tasks (Traces) with their phases, Setup changes, the export to a file and the saved views. Everything here was
 * masked before the audit stored it; nothing is masked again or unmasked.
 */
@RestController
@RegisterReflectionForBinding({ActivityDtos.Page.class, ActivityDtos.Event.class, ActivityDtos.Change.class,
    ActivityController.Changes.class, AuditQueries.Histogram.class, AuditQueries.Bucket.class,
    AuditQueries.FacetValue.class, ActivityController.Detail.class, AuditQueries.Row.class,
    ActivityController.TraceRow.class, ActivityController.Traces.class, ActivityController.TraceDetail.class,
    ActivityController.Phase.class, ActivityController.Views.class, ActivityController.View.class,
    ActivityController.Exported.class})
class ActivityController {

  /** The saved views live in this setting (§4: {@code activity.saved-views}). */
  static final String VIEWS = "activity.saved-views";

  private static final int MAX_EXPORT = 1_000_000;

  record Detail(ActivityDtos.Event event, List<AuditQueries.Row> rows) {}

  /**
   * One task of Activity › Traces.
   *
   * @param status {@code in-progress}, {@code waiting} (a call waits for a person) or {@code ended}
   * @param titleSource {@code meta}, {@code note} or {@code client}: the title is what the client said, unverified
   */
  record TraceRow(String traceId, String title, String titleSource, String client, String endpoint, Instant startedAt,
      Instant endedAt, int calls, String status, Map<String, Object> decisions, long waitedForPeopleMs) {}

  record Traces(List<TraceRow> traces, String nextCursor) {}

  record Phase(String name, Instant from, Instant to, int calls, Map<String, Object> decisions, String summary) {}

  record TraceDetail(TraceRow trace, List<Phase> phases, List<ActivityDtos.Event> calls) {}

  record Changes(List<ActivityDtos.Change> changes, String nextCursor) {}

  /** A saved view: its name, the query and the tab ({@code events}, {@code traces}, {@code changes}). */
  record View(String name, String q, String view) {}

  record Views(List<View> views, long version) {}

  /**
   * {@code target}: {@code file} (with the {@code saveToken} of a save dialog) or {@code siem} (with the
   * {@code destinationId} of a destination of Settings › Audit, which may be of any kind).
   */
  record Export(String q, String from, String to, String target, String format, String saveToken,
      String destinationId) {}

  record Exported(String path, long rows, long bytes, String format) {}

  private final AuditQueries audit;
  private final AuditExports exports;
  private final SettingStore settings;
  private final FileGrants files;
  private final JsonMapper json;
  private final Clock clock;

  ActivityController(AuditQueries audit, AuditExports exports, SettingStore settings, FileGrants files,
      JsonMapper json, Clock clock) {
    this.audit = audit;
    this.exports = exports;
    this.settings = settings;
    this.files = files;
    this.json = json;
    this.clock = clock;
  }

  /** Send to SIEM reached the core but not the destination: what it answered, as a 502. */
  @org.springframework.web.bind.annotation.ExceptionHandler(AuditExportFailedException.class)
  org.springframework.http.ResponseEntity<ApiProblem> notSent(AuditExportFailedException e) {
    return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.BAD_GATEWAY)
        .contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
        .body(ApiProblem.of("The calls weren't sent", e.getMessage()));
  }

  @GetMapping("/api/activity/events")
  ActivityDtos.Page events(@RequestParam(name = "q", required = false) String q,
      @RequestParam(name = "from", required = false) String from,
      @RequestParam(name = "to", required = false) String to,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", defaultValue = "100") int limit) {
    AuditQueries.Events e = audit.events(q, instant(from, "/from"), instant(to, "/to"), cursor, limit);
    return new ActivityDtos.Page(e.events().stream().map(ActivityDtos::event).toList(), e.nextCursor());
  }

  /** Without a range: the last 24 hours, one bar per hour. */
  @GetMapping("/api/activity/histogram")
  AuditQueries.Histogram histogram(@RequestParam(name = "q", required = false) String q,
      @RequestParam(name = "from", required = false) String from,
      @RequestParam(name = "to", required = false) String to) {
    Instant end = Optional.ofNullable(instant(to, "/to")).orElse(clock.instant());
    Instant start = Optional.ofNullable(instant(from, "/from")).orElse(end.minus(Duration.ofHours(24)));
    if (!start.isBefore(end)) {
      throw ApiRequestException.unprocessable("Invalid range", "The range must end after it starts.", "/to");
    }
    return audit.histogram(q, start, end);
  }

  @GetMapping("/api/activity/facets")
  Map<String, List<AuditQueries.FacetValue>> facets(@RequestParam(name = "q", required = false) String q,
      @RequestParam(name = "from", required = false) String from,
      @RequestParam(name = "to", required = false) String to) {
    return audit.facets(q, instant(from, "/from"), instant(to, "/to"));
  }

  @GetMapping("/api/activity/events/{id}")
  Detail event(@PathVariable long id) {
    AuditQueries.Detail d = audit.detail(id).orElseThrow(() -> ProblemException.notFound("Call not found",
        "There is no call " + id + " in the audit."));
    return new Detail(ActivityDtos.event(d.event()), d.rows());
  }

  // ── Traces ──────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * Tasks newest first; {@code cursor} ("Load older tasks") is the start of the next page. With {@code q}, only the
   * tasks that have a call matching it; with {@code from}/{@code to} (the range of the events tab), only those with a
   * call in it. Both look at the whole audit: every page is full while older tasks match.
   */
  @GetMapping("/api/activity/traces")
  Traces traces(@RequestParam(name = "q", required = false) String q,
      @RequestParam(name = "from", required = false) String from,
      @RequestParam(name = "to", required = false) String to,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", defaultValue = "20") int limit) {
    Instant before = cursor == null || cursor.isBlank() ? clock.instant().plusSeconds(1) : cursorInstant(cursor);
    int size = Math.max(1, Math.min(limit, 100));
    List<AuditQueries.Trace> page = audit.traces(q, instant(from, "/from"), instant(to, "/to"), before, size + 1);
    List<TraceRow> out = page.subList(0, Math.min(size, page.size())).stream().map(this::traceRow).toList();
    String next = page.size() > size ? Long.toString(page.get(size - 1).lastAt().toEpochMilli(), 36) : null;
    return new Traces(out, next);
  }

  @GetMapping("/api/activity/traces/{traceId}")
  TraceDetail trace(@PathVariable String traceId) {
    AuditQueries.Trace t = audit.trace(traceId).orElseThrow(() -> ProblemException.notFound("Task not found",
        "There is no task " + traceId + "."));
    List<Phase> phases = new ArrayList<>();
    Object stored = t.summary() == null ? null : t.summary().get("phases");
    if (stored instanceof List<?> list) {
      for (Object o : list) {
        if (o instanceof Map<?, ?> m) {
          phases.add(new Phase(String.valueOf(m.get("name")), epoch(m.get("from")), epoch(m.get("to")),
              m.get("calls") instanceof Number n ? n.intValue() : 0, decisions(m.get("decisions")),
              m.get("summary") == null ? null : String.valueOf(m.get("summary"))));
        }
      }
    }
    List<ActivityDtos.Event> calls = audit.events("trace:" + t.traceId(), null, null, null, 500).events().stream()
        .map(ActivityDtos::event).toList().reversed();
    return new TraceDetail(traceRow(t), List.copyOf(phases), calls);
  }

  // ── Setup changes ───────────────────────────────────────────────────────────────────────────────────────────

  @GetMapping("/api/activity/changes")
  Changes changes(@RequestParam(name = "q", required = false) String q,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", defaultValue = "100") int limit) {
    Long before = null;
    if (cursor != null && !cursor.isBlank()) {
      try {
        before = Long.parseLong(cursor, 36);
      } catch (NumberFormatException e) {
        throw ApiRequestException.unprocessable("Invalid cursor", "Start the list again.", "/cursor");
      }
    }
    int size = Math.max(1, Math.min(limit, 500));
    List<AuditQueries.Change> page = audit.changes(q, before, size + 1);
    List<ActivityDtos.Change> out = page.subList(0, Math.min(size, page.size())).stream().map(ActivityDtos::change)
        .toList();
    return new Changes(out, page.size() > size ? Long.toString(out.get(out.size() - 1).id(), 36) : null);
  }

  // ── Export and saved views ──────────────────────────────────────────────────────────────────────────────────

  /**
   * Exports the calls matching the query to the file the person chose ({@code saveToken} of a save dialog), or —
   * {@code target: "siem"} — sends them once to an audit export destination of Settings ({@code destinationId}, K5),
   * as JSON lines with the signed head of the audit; the destination's own schedule and cursor don't change.
   */
  @PostMapping("/api/activity/export")
  Exported export(@RequestBody Export request) {
    if ("siem".equals(request.target())) {
      if (request.format() != null && !"jsonl".equals(request.format())) {
        throw ApiRequestException.unprocessable("JSON lines only", "The core sends JSON lines; CEF isn't available "
            + "yet.", "/format");
      }
      if (request.destinationId() == null || request.destinationId().isBlank()) {
        throw ApiRequestException.unprocessable("Choose a destination", "Pick one of the destinations of Settings › "
            + "Audit and retention.", "/destinationId");
      }
      AuditExports.Sent sent = exports.send(request.destinationId(), request.q(), instant(request.from(), "/from"),
          instant(request.to(), "/to"));
      return new Exported(sent.target(), sent.rows(), sent.bytes(), sent.format());
    }
    if (request.target() != null && !"file".equals(request.target())) {
      throw ApiRequestException.unprocessable("Export to a file", "Export to a file, or send to a destination of "
          + "Settings › Audit (target siem).", "/target");
    }
    String format = request.format() == null ? "jsonl" : request.format();
    if (!"csv".equals(format) && !"jsonl".equals(format)) {
      throw ApiRequestException.unprocessable("Unknown format", "Export as csv or jsonl.", "/format");
    }
    ActivityQueryParser.parse(request.q());
    Path target;
    try {
      target = files.take(request.saveToken(), FileGrants.Purpose.AUDIT_EXPORT);
    } catch (IllegalArgumentException e) {
      throw ApiRequestException.unprocessable("Choose the file again", e.getMessage(), "/saveToken");
    }
    Instant from = instant(request.from(), "/from");
    Instant to = instant(request.to(), "/to");
    Path temp = target.resolveSibling("." + target.getFileName() + ".part");
    long rows = 0;
    try {
      Files.deleteIfExists(temp);
      Files.createFile(temp);
      GatewayPaths.ownerOnlyFile(temp);
      try (BufferedWriter out = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
        if ("csv".equals(format)) {
          out.write(String.join(",", CSV_COLUMNS));
          out.write("\n");
        }
        String cursor = null;
        do {
          AuditQueries.Events page = audit.events(request.q(), from, to, cursor, 500);
          for (AuditQueries.Event e : page.events()) {
            ActivityDtos.Event event = ActivityDtos.event(e);
            out.write("csv".equals(format) ? csv(event) : json.writeValueAsString(event));
            out.write("\n");
            rows++;
          }
          cursor = page.nextCursor();
        } while (cursor != null && rows < MAX_EXPORT);
      }
      Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      return new Exported(target.toString(), rows, Files.size(target), format);
    } catch (IOException e) {
      throw new UncheckedIOException("Can't write the export to " + target, e);
    } finally {
      try {
        Files.deleteIfExists(temp);
      } catch (IOException ignored) {
        // The temp file is owner-only and holds only masked audit rows.
      }
    }
  }

  @GetMapping("/api/activity/views")
  Views views() {
    Optional<SettingStore.Setting> s = settings.find(VIEWS);
    List<View> out = new ArrayList<>();
    s.map(SettingStore.Setting::value).filter(JsonNode::isArray).ifPresent(list -> list.forEach(v -> out.add(
        new View(v.path("name").asString(""), v.path("q").asString(""), v.path("view").asString("events")))));
    return new Views(List.copyOf(out), s.map(SettingStore.Setting::version).orElse(0L));
  }

  @PutMapping("/api/activity/views")
  Views saveViews(@RequestBody Views request) {
    List<View> views = request.views() == null ? List.of() : request.views();
    if (views.size() > 50) {
      throw ApiRequestException.unprocessable("Too many views", "Keep at most 50 saved views.", "/views");
    }
    ArrayNode list = JsonNodeFactory.instance.arrayNode();
    for (int i = 0; i < views.size(); i++) {
      View v = views.get(i);
      if (v.name() == null || v.name().isBlank() || v.name().length() > 64) {
        throw ApiRequestException.unprocessable("Name the view", "A view's name has 1 to 64 characters.",
            "/views/" + i + "/name");
      }
      if (v.q() != null && v.q().length() > 2_000) {
        throw ApiRequestException.unprocessable("Query too long", "A query has at most 2,000 characters.",
            "/views/" + i + "/q");
      }
      ActivityQueryParser.parse(v.q());
      String view = v.view() == null ? "events" : v.view();
      if (!List.of("events", "traces", "changes").contains(view)) {
        throw ApiRequestException.unprocessable("Unknown tab", "A view opens events, traces or changes.",
            "/views/" + i + "/view");
      }
      ObjectNode n = list.addObject();
      n.put("name", v.name().strip());
      n.put("q", v.q() == null ? "" : v.q());
      n.put("view", view);
    }
    settings.put(VIEWS, list, request.version());
    return views();
  }

  // ── Mapping ─────────────────────────────────────────────────────────────────────────────────────────────────

  private static final List<String> CSV_COLUMNS = List.of("id", "callId", "at", "decision", "tool", "connection",
      "env", "client", "endpoint", "tokenLabel", "durationMs", "rule", "approvalId", "traceId", "errorKind",
      "errorMessage");

  private static String csv(ActivityDtos.Event e) {
    List<Object> values = List.of(e.id(), nz(e.callId()), nz(e.at()), nz(e.decision()), nz(e.tool()),
        nz(e.connection()), nz(e.env()), nz(e.client()), nz(e.endpoint()), nz(e.tokenLabel()), nz(e.durationMs()),
        nz(e.rule()), nz(e.approvalId()), nz(e.traceId()), nz(e.errorKind()), nz(e.errorMessage()));
    List<String> cells = new ArrayList<>();
    for (Object v : values) {
      String text = String.valueOf(v);
      // Quoted always; a leading formula character is defused so a spreadsheet never runs a cell.
      if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
        text = "'" + text;
      }
      cells.add("\"" + text.replace("\"", "\"\"") + "\"");
    }
    return String.join(",", cells);
  }

  private static Object nz(Object v) {
    return v == null ? "" : v;
  }

  private TraceRow traceRow(AuditQueries.Trace t) {
    Map<String, Object> summary = t.summary() == null ? Map.of() : t.summary();
    boolean waiting = Boolean.TRUE.equals(summary.get("waiting"));
    String status = waiting ? "waiting" : t.lastAt().plus(TraceSummarizer.IN_PROGRESS).isAfter(clock.instant())
        ? "in-progress" : "ended";
    long waited = summary.get("waitedForPeopleMs") instanceof Number n ? n.longValue() : 0;
    return new TraceRow(t.traceId(), t.title(), t.titleSource(), t.client(), t.endpoint(), t.firstAt(), t.lastAt(),
        t.calls(), status, decisions(summary.get("decisions")), waited);
  }

  private static Map<String, Object> decisions(Object value) {
    Map<String, Object> out = new LinkedHashMap<>();
    if (value instanceof Map<?, ?> m) {
      m.forEach((k, v) -> out.put(String.valueOf(k), v));
    }
    return out;
  }

  private static Instant epoch(Object value) {
    return value instanceof Number n ? Instant.ofEpochMilli(n.longValue()) : null;
  }

  private static Instant cursorInstant(String cursor) {
    try {
      return Instant.ofEpochMilli(Long.parseLong(cursor, 36));
    } catch (NumberFormatException e) {
      throw ApiRequestException.unprocessable("Invalid cursor", "Start the list again.", "/cursor");
    }
  }

  private static Instant instant(String value, String field) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException e) {
      try {
        return java.time.OffsetDateTime.parse(value).toInstant();
      } catch (DateTimeParseException e2) {
        throw ApiRequestException.unprocessable("Invalid time", "Give the time as ISO 8601, like "
            + "2026-10-22T14:30:00+02:00.", field);
      }
    }
  }
}
