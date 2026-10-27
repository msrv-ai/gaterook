package ai.msrv.gaterook.core.audit;

import ai.msrv.gaterook.core.audit.export.Destinations;
import ai.msrv.gaterook.core.audit.export.ExportException;
import ai.msrv.gaterook.core.audit.export.ExportRunner;
import ai.msrv.gaterook.core.audit.export.ExportScheduler;
import ai.msrv.gaterook.core.audit.export.ExportSecrets;
import ai.msrv.gaterook.core.audit.export.ExportSenders;
import ai.msrv.gaterook.core.audit.export.ExportSettings;
import ai.msrv.gaterook.core.audit.export.Schedule;
import ai.msrv.gaterook.core.audit.internal.persistence.AuditExportEntity;
import ai.msrv.gaterook.core.audit.internal.persistence.AuditExportRepository;
import ai.msrv.gaterook.core.audit.internal.persistence.ExportKind;
import ai.msrv.gaterook.core.audit.internal.persistence.ToolCallEntity;
import ai.msrv.gaterook.core.audit.internal.persistence.ToolCallRepository;
import ai.msrv.gaterook.core.platform.BuildFlags;
import ai.msrv.gaterook.core.platform.KebabCaseEnumConverter;
import ai.msrv.gaterook.core.platform.ProblemException;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder;
import ai.msrv.gaterook.core.secrets.SecretInfo;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Settings › Audit & retention › Export (ARCHITECTURE.md §6 settings, §10 K5): destinations of every kind — a SIEM over
 * syslog TLS, an S3 bucket, a BigQuery table — each with its schedule, its cursor and its state; Test, Export now and
 * Retry; Activity's Send to SIEM. Every change is a Setup change written with it; a destination's secret is a
 * write-only secret of the gateway, never in its config or in an answer. Exports carry the audit as stored — masked
 * before it was written — and the signed head of the chain.
 */
@Component
public class AuditExports {

  /** Calls one Send to SIEM takes at most. */
  public static final int MAX_SENT = 100_000;

  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 ._-]{0,63}");
  private static final Duration RUN_NOW = Duration.ofSeconds(60);
  private static final Map<ExportKind, String> TITLES = Map.of(ExportKind.SIEM, "SIEM", ExportKind.S3, "Amazon S3",
      ExportKind.BIGQUERY, "BigQuery");

  /**
   * One destination as Settings shows it.
   *
   * @param config        its settings, never a secret ({@code secrets} says what is stored for them)
   * @param target        where it writes: {@code siem.acme.internal:6514}, {@code s3://acme-audit/gaterook/}
   * @param state         {@code ok}, {@code failing} (with {@code failingSince} and {@code lastError}) or
   *                      {@code null} before its first run
   * @param lastExportedId the last agent call row it has; retention never deletes past it
   * @param pending       a batch is on its way (or waits for its retry)
   * @param verified      {@code false}: changed outside GateRook, nothing is exported until it is saved again
   * @param demo          a destination of the demo world, never contacted
   */
  public record Destination(String id, String kind, String name, boolean enabled, ObjectNode config, String schedule,
      String target, String state, Instant failingSince, Instant lastAttemptAt, String lastError,
      Instant lastExportedAt, Long lastExportedId, boolean pending, List<StoredSecret> secrets, boolean verified,
      boolean demo, long version) {}

  /** A secret stored for a destination: where it lives and its state, never its value. */
  public record StoredSecret(String field, String name, String source, Integer version, String state) {}

  /** A secret as a form sends it: a value to store here ({@code local}), or a reference to a connected store. */
  public record SecretInput(String source, char[] value, String ref, String store) {

    @Override
    public String toString() {
      return "SecretInput[source=" + source + (value == null ? "" : ", value=‹redacted›") + "]";
    }
  }

  /** {@code POST /api/audit-export}. */
  public record Draft(String kind, String name, Boolean enabled, JsonNode config, String schedule,
      Map<String, SecretInput> secrets) {}

  /** {@code PUT /api/audit-export/{id}}: what is given changes, a {@code config} replaces the whole config. */
  public record Patch(String name, Boolean enabled, JsonNode config, String schedule, Map<String, SecretInput> secrets,
      Long version) {}

  /** Test: what was found, how long it took. */
  public record Tested(boolean ok, long latencyMs, String message) {}

  /** Send to SIEM: where the calls went, how many, how many bytes, as what. */
  public record Sent(String target, long rows, long bytes, String format) {}

  @PersistenceContext
  private EntityManager em;

  private final Destinations destinations;
  private final AuditExportRepository rows;
  private final ToolCallRepository calls;
  private final ExportSecrets secrets;
  private final ExportSenders senders;
  private final ExportRunner runner;
  private final ExportScheduler scheduler;
  private final AuditQueries queries;
  private final ObjectProvider<SetupChangeRecorder> recorder;
  private final Clock clock;
  private final TransactionTemplate writeTx;
  private final TransactionTemplate readTx;

  AuditExports(Destinations destinations, AuditExportRepository rows, ToolCallRepository calls, ExportSecrets secrets,
      ExportSenders senders, ExportRunner runner, ExportScheduler scheduler, AuditQueries queries,
      ObjectProvider<SetupChangeRecorder> recorder, Clock clock, PlatformTransactionManager tx) {
    this.destinations = destinations;
    this.rows = rows;
    this.calls = calls;
    this.secrets = secrets;
    this.senders = senders;
    this.runner = runner;
    this.scheduler = scheduler;
    this.queries = queries;
    this.recorder = recorder;
    this.clock = clock;
    this.writeTx = new TransactionTemplate(tx);
    this.readTx = new TransactionTemplate(tx);
    this.readTx.setReadOnly(true);
  }

  public List<Destination> list() {
    return destinations.all().stream().map(this::view).toList();
  }

  /** @throws ProblemException 404 */
  public Destination get(String id) {
    return view(found(id));
  }

  /**
   * Adds a destination; its first run exports the audit from its first row, so retention waits for it.
   *
   * @throws ProblemException 422 at the field at fault ({@code /name}, {@code /config/bucket}, {@code /secrets/…})
   */
  public Destination create(Draft draft, String reason) {
    try {
      ExportKind kind = kind(draft.kind());
      String name = name(draft.name(), null);
      Schedule schedule = schedule(kind, draft.schedule(), Schedule.DAILY);
      ObjectNode config = ExportSettings.normalize(kind, draft.config(), senders.corePorts());
      String field = ExportSettings.secretField(kind, config);
      SecretInput secret = secret(draft.secrets(), field, true);
      boolean enabled = draft.enabled() == null || draft.enabled();
      String id = writeTx.execute(s -> {
        AuditExportEntity e = new AuditExportEntity(kind, name, destinations.column(config), schedule.id());
        e.setEnabled(enabled);
        e = rows.saveAndFlush(e);
        if (secret != null) {
          e.setSecretId(secrets.store(e.getId(), name, field, input(secret), reason, pointer(field)));
        }
        destinations.sign(e);
        record(name, null, TITLES.get(kind) + " · " + ExportSettings.describe(kind, config) + " · "
            + label(schedule) + (enabled ? "" : " · paused"), reason, details(kind, config, schedule));
        return e.getId();
      });
      scheduler.refresh();
      return get(id);
    } finally {
      clear(draft.secrets());
    }
  }

  /**
   * Changes a destination. A new config keeps the cursor: the new place gets what comes next (add a destination for
   * a full copy). A secret the new config no longer needs is destroyed. Saving a destination changed outside
   * GateRook signs it again and starts its export over from the first row (its position can't be vouched for).
   *
   * @throws ProblemException 404, 409 when {@code version} is stale, 422 at the field at fault
   */
  public Destination update(String id, Patch patch, String reason) {
    try {
      Destinations.Destination before = found(id);
      if (before.demo()) {
        throw ProblemException.conflict("Demo destination", "The demo world's destinations can't be changed: add "
            + "your own.");
      }
      if (patch.version() != null && patch.version() != before.version()) {
        throw ProblemException.conflict("Changed meanwhile", "Someone changed this destination in the meantime. "
            + "Reload and try again.");
      }
      ExportKind kind = before.kind();
      String name = patch.name() == null ? before.name() : name(patch.name(), id);
      Schedule schedule = patch.schedule() == null ? before.schedule() : schedule(kind, patch.schedule(), null);
      ObjectNode config = patch.config() == null ? before.config()
          : ExportSettings.normalize(kind, patch.config(), senders.corePorts());
      boolean enabled = patch.enabled() == null ? before.enabled() : patch.enabled();
      String field = ExportSettings.secretField(kind, config);
      String oldField = ExportSettings.secretField(kind, before.config());
      boolean stored = field != null && field.equals(oldField) && before.secretId() != null;
      SecretInput secret = secret(patch.secrets(), field, !stored);
      writeTx.executeWithoutResult(s -> {
        AuditExportEntity e = rows.findById(id).orElseThrow(() -> notFound(id));
        if (e.getVersion() != before.version()) {
          throw new org.springframework.dao.OptimisticLockingFailureException("Audit export " + id + " changed");
        }
        // A row changed outside the core may have had its position moved too (past rows it never sent): nothing
        // vouches for what the destination received, so saving it again starts its export over from the first row.
        boolean startOver = !destinations.view(e).verified();
        if (startOver) {
          e.restart();
        }
        e.setName(name);
        e.setEnabled(enabled);
        e.setSchedule(schedule.id());
        e.setConfig(destinations.column(config));
        if (oldField != null && !oldField.equals(field)) {
          secrets.destroy(id, List.of(oldField));
          e.setSecretId(null);
        }
        if (secret != null) {
          e.setSecretId(secrets.store(id, name, field, input(secret), reason, pointer(field)));
        }
        destinations.sign(e);
        List<String> from = new ArrayList<>();
        List<String> to = new ArrayList<>();
        change(from, to, before.name(), name);
        change(from, to, before.enabled() ? "On" : "Paused", enabled ? "On" : "Paused");
        change(from, to, label(before.schedule()), label(schedule));
        change(from, to, before.target(), ExportSettings.describe(kind, config));
        if (secret != null) {
          from.add("credentials");
          to.add("new credentials");
        }
        if (startOver) {
          from.add("changed outside GateRook");
          to.add("export starts again from the first row");
        }
        record(before.name(), from.isEmpty() ? null : String.join("; ", from), to.isEmpty() ? "Saved again"
            : String.join("; ", to), reason, details(kind, config, schedule));
      });
      scheduler.refresh();
      return get(id);
    } finally {
      clear(patch.secrets());
    }
  }

  /**
   * Removes a destination and destroys its secret. Its cursor no longer holds retention back: rows it never took may
   * go once they are old enough.
   */
  public void delete(String id, String reason) {
    Destinations.Destination d = found(id);
    writeTx.executeWithoutResult(s -> {
      secrets.destroyAll(id);
      rows.findById(id).ifPresent(rows::delete);
      record(d.name(), d.target(), "Removed", reason, details(d.kind(), d.config(), d.schedule()));
    });
    destinations.forget(id);
    scheduler.refresh();
  }

  /** Test: connects and checks without exporting rows; the destination's state doesn't change. */
  public Tested test(String id) {
    Destinations.Destination d = found(id);
    if (d.demo()) {
      throw demo();
    }
    long started = System.nanoTime();
    try {
      String message = runner.test(d);
      return new Tested(true, (System.nanoTime() - started) / 1_000_000, message);
    } catch (ExportException e) {
      return new Tested(false, (System.nanoTime() - started) / 1_000_000, e.getMessage());
    }
  }

  /**
   * Export now / Retry: exports what the destination hasn't received yet, here, for up to a minute (the scheduler
   * sends the rest); a failure leaves it {@code failing} with the error.
   *
   * @throws ProblemException 404, 409 for a paused, demo or changed-outside destination
   */
  public Destination run(String id) {
    Destinations.Destination d = found(id);
    if (d.demo()) {
      throw demo();
    }
    if (!d.verified()) {
      throw ProblemException.conflict("Changed outside GateRook", "This destination was changed outside "
          + "GateRook. Check it in Settings and save it again to resume exports.");
    }
    if (!d.enabled()) {
      throw ProblemException.conflict("Paused", "Turn the destination on to export to it.");
    }
    try {
      scheduler.runNow(id, RUN_NOW);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return get(id);
  }

  /**
   * Activity › Send to SIEM: the calls matching {@code q} in {@code [from, to)} — the latest row of each, as the
   * scheduled exports write rows — once, with the chain's head, outside the destination's cursor. Any kind of
   * destination takes them.
   *
   * @throws ProblemException 404, 409 (paused, demo, changed outside), 422 (more than {@value #MAX_SENT} calls, an
   *     invalid query)
   * @throws AuditExportFailedException the destination didn't take them
   */
  public Sent send(String id, String q, Instant from, Instant to) {
    Destinations.Destination d = found(id);
    if (d.demo()) {
      throw demo();
    }
    if (!d.verified() || !d.enabled()) {
      throw ProblemException.conflict(d.enabled() ? "Changed outside GateRook" : "Paused", d.enabled()
          ? "Check the destination in Settings and save it again first." : "Turn the destination on first.");
    }
    ActivityQueryParser.parse(q);
    List<Long> ids = new ArrayList<>();
    String cursor = null;
    do {
      AuditQueries.Events page = queries.events(q, from, to, cursor, 500);
      page.events().forEach(e -> ids.add(e.id()));
      cursor = page.nextCursor();
      if (ids.size() > MAX_SENT) {
        throw ProblemException.unprocessable("Too many calls", "Send at most " + String.format(Locale.ROOT, "%,d",
            MAX_SENT) + " calls at a time: narrow the range or the filters.", "/q");
      }
    } while (cursor != null);
    List<ToolCallEntity> selected = new ArrayList<>();
    for (int i = 0; i < ids.size(); i += 500) {
      List<Long> chunk = ids.subList(i, Math.min(ids.size(), i + 500));
      selected.addAll(readTx.execute(s -> calls.findByIdInOrderByIdAsc(chunk)));
    }
    selected.sort(java.util.Comparator.comparing(ToolCallEntity::getId));
    try {
      long bytes = runner.sendOnce(d, selected, runner.batchSize(d));
      String format = d.kind() == ExportKind.S3 ? d.config().path("format").asString("jsonl") : "jsonl";
      return new Sent(d.target(), selected.size(), bytes, format);
    } catch (ExportException e) {
      throw new AuditExportFailedException(e.getMessage(), e);
    }
  }

  /**
   * The kit's destinations in the demo world (design/BLUEPRINT.md §9 "Audit": {@code siem.acme.internal:6514},
   * {@code s3://acme-audit/gaterook/}, BigQuery {@code acme-prod.audit.mcp_calls}) in the states Settings shows:
   * shown, signed, never contacted ({@code demo} in their config). Never in a release build.
   */
  public void seedDemo(JsonNode list) {
    if (BuildFlags.RELEASE || list == null || !list.isArray()) {
      return;
    }
    Instant now = clock.instant();
    writeTx.executeWithoutResult(s -> {
      for (JsonNode d : list) {
        ExportKind kind = kind(d.path("kind").asString());
        ObjectNode config = (ObjectNode) d.path("config").deepCopy();
        config.put("demo", true);
        AuditExportEntity e = new AuditExportEntity(kind, d.path("name").asString(), destinations.column(config),
            Schedule.parse(d.path("schedule").asString("daily@02:00")).id());
        Instant exportedAt = now.minus(Duration.ofMinutes(d.path("exportedMinutesAgo").asLong(0)));
        Long upTo = em.createQuery("select max(c.id) from ToolCallEntity c where c.createdAt <= :at", Long.class)
            .setParameter("at", exportedAt).getSingleResult();
        e.exported(upTo == null ? 0 : upTo, exportedAt);
        if (d.hasNonNull("failingMinutesAgo")) {
          e.failed(d.path("error").asString("The export failed"),
              now.minus(Duration.ofMinutes(d.path("failingMinutesAgo").asLong())),
              now.minus(Duration.ofMinutes(d.path("attemptMinutesAgo").asLong(0))));
        }
        destinations.sign(e);
      }
    });
    scheduler.refresh();
  }

  // ── Views and checks ────────────────────────────────────────────────────────────────────────────────────────

  private Destination view(Destinations.Destination d) {
    ObjectNode config = d.config().deepCopy();
    config.remove("demo");
    List<StoredSecret> stored = new ArrayList<>();
    for (SecretInfo s : secrets.stored(d.id())) {
      stored.add(new StoredSecret(s.field(), s.name(), s.source(), s.version(), s.state()));
    }
    String state = d.state();
    Instant failingSince = d.failingSince();
    String error = d.lastError();
    if (!d.verified()) {
      // Shown as failing: the person sees that nothing goes out and why.
      state = "failing";
      error = destinations.locked() ? "The master key is locked: nothing is exported until it opens."
          : "Changed outside GateRook: nothing is exported to this destination until you save it again.";
      failingSince = failingSince != null ? failingSince : d.lastAttemptAt();
    }
    return new Destination(d.id(), d.kindId(), d.name(), d.enabled(), config, d.schedule().id(), d.target(), state,
        failingSince, d.lastAttemptAt(), error, d.lastExportedAt(), d.lastExportedId(), d.pendingToId() != null,
        List.copyOf(stored), d.verified(), d.demo(), d.version());
  }

  private Destinations.Destination found(String id) {
    return destinations.find(id).orElseThrow(() -> notFound(id));
  }

  private static ProblemException notFound(String id) {
    return ProblemException.notFound("Destination not found", "There is no audit export destination " + id + ".");
  }

  private static ProblemException demo() {
    return ProblemException.conflict("Demo destination", "This destination is part of the demo world: GateRook "
        + "doesn't send the demo's audit anywhere. Add your own destination to export.");
  }

  private static ExportKind kind(String kind) {
    for (ExportKind k : ExportKind.values()) {
      if (KebabCaseEnumConverter.idOf(k).equals(kind)) {
        return k;
      }
    }
    throw ProblemException.unprocessable("Unknown kind", "A destination is a siem, s3 or bigquery.", "/kind");
  }

  private String name(String name, String self) {
    String n = name == null ? "" : name.strip();
    if (!NAME.matcher(n).matches()) {
      throw ProblemException.unprocessable("Invalid name", "A name has 1 to 64 letters, digits, spaces, dots, "
          + "underscores and hyphens, and starts with a letter or digit.", "/name");
    }
    Optional<AuditExportEntity> taken = readTx.execute(s -> rows.findByName(n));
    if (taken != null && taken.isPresent() && !taken.get().getId().equals(self)) {
      throw ProblemException.unprocessable("Name taken", "Another destination is called " + n + ".", "/name");
    }
    return n;
  }

  private static Schedule schedule(ExportKind kind, String text, Schedule dflt) {
    Schedule schedule;
    if (text == null && dflt != null) {
      schedule = dflt;
    } else {
      try {
        schedule = Schedule.parse(text);
      } catch (IllegalArgumentException e) {
        throw ProblemException.unprocessable("Invalid schedule", e.getMessage(), "/schedule");
      }
    }
    if (kind == ExportKind.S3 && schedule.kind() == Schedule.Kind.STREAM) {
      throw ProblemException.unprocessable("Invalid schedule", "S3 takes exports hourly or daily: a stream would "
          + "write an object for every few calls.", "/schedule");
    }
    return schedule;
  }

  /**
   * The secret given for {@code field}; required when nothing is stored for it yet.
   *
   * @throws ProblemException 422 at {@code /secrets/<field>}, or at {@code /secrets} for a field the kind doesn't take
   */
  private static SecretInput secret(Map<String, SecretInput> given, String field, boolean required) {
    Map<String, SecretInput> all = given == null ? Map.of() : given;
    for (String f : all.keySet()) {
      if (!f.equals(field)) {
        throw ProblemException.unprocessable("Unknown secret", "This destination takes " + (field == null
            ? "no secret" : "only " + field) + ".", "/secrets");
      }
    }
    SecretInput s = field == null ? null : all.get(field);
    boolean empty = s == null || ((s.value() == null || s.value().length == 0) && (s.ref() == null
        || s.ref().isBlank()));
    if (empty && required && field != null) {
      throw ProblemException.unprocessable("Secret missing", switch (field) {
        case ExportSettings.SIEM_CLIENT_KEY -> "Give the private key of the client certificate (PEM).";
        case ExportSettings.S3_SECRET_KEY -> "Give the secret access key of the access key ID.";
        default -> "Give the service account key (JSON).";
      }, pointer(field));
    }
    return empty ? null : s;
  }

  /** Where secret {@code field} is in a request: {@code /secrets/~1credentials~1secretAccessKey} (RFC 6901). */
  static String pointer(String field) {
    return "/secrets/" + field.replace("~", "~0").replace("/", "~1");
  }

  private static ExportSecrets.Input input(SecretInput s) {
    return new ExportSecrets.Input(s.source(), s.value() == null ? null : s.value().clone(), s.ref(), s.store());
  }

  private static void clear(Map<String, SecretInput> given) {
    if (given != null) {
      given.values().stream().filter(Objects::nonNull).map(SecretInput::value).filter(Objects::nonNull)
          .forEach(v -> Arrays.fill(v, '\0'));
    }
  }

  private static String label(Schedule s) {
    return switch (s.kind()) {
      case STREAM -> "Streaming";
      case HOURLY -> "Hourly";
      case DAILY -> "Daily at " + s.at();
    };
  }

  private static void change(List<String> from, List<String> to, String before, String after) {
    if (!Objects.equals(before, after)) {
      from.add(before);
      to.add(after);
    }
  }

  private static ObjectNode details(ExportKind kind, JsonNode config, Schedule schedule) {
    ObjectNode n = JsonNodeFactory.instance.objectNode();
    n.put("kind", KebabCaseEnumConverter.idOf(kind));
    n.put("target", ExportSettings.describe(kind, config));
    n.put("schedule", schedule.id());
    return n;
  }

  private void record(String name, String from, String to, String reason, ObjectNode details) {
    recorder.getObject().record(new SetupChangeRecorder.SetupChange(SetupChangeRecorder.What.SETTINGS,
        "Audit export " + name, from, to, reason, null, null, null, details));
  }
}
