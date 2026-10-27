package ai.msrv.gaterook.core.audit.export;

import ai.msrv.gaterook.core.audit.internal.persistence.AuditExportEntity;
import ai.msrv.gaterook.core.audit.internal.persistence.AuditExportRepository;
import ai.msrv.gaterook.core.audit.internal.persistence.ExportKind;
import ai.msrv.gaterook.core.platform.IntegrityAlerts;
import ai.msrv.gaterook.core.platform.KebabCaseEnumConverter;
import ai.msrv.gaterook.core.platform.MasterKeyLock;
import ai.msrv.gaterook.core.platform.RowMac;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The rows of {@code audit_export} as the exports read them. What the person set is signed ({@link RowMac}, V80): a
 * destination says where the audit goes, so a row written around the core reads as {@code verified = false}, sends
 * nothing and raises an integrity alert until the person saves it again. The export position — the cursor and the
 * batch in flight — is signed with it (review K5): it decides what leaves the core and what retention may delete, and
 * one edit of {@code gateway.db} by a process of the same OS user (an agent's shell) would otherwise skip rows without
 * a word. The core's own moves sign the new position ({@link #position}); a row that doesn't verify holds retention
 * back entirely ({@link #retentionBound}).
 */
@Component
public class Destinations {

  static final String TABLE = "audit_export";

  /**
   * One destination.
   *
   * @param config      its settings as stored (never a secret: {@link ExportSettings})
   * @param secretId    the one secret its kind may need, or {@code null}
   * @param pendingToId the last row of the batch in flight, or {@code null}
   * @param verified    the row's MAC holds
   */
  public record Destination(String id, ExportKind kind, String name, boolean enabled, ObjectNode config,
      Schedule schedule, String secretId, Long lastExportedId, Instant lastExportedAt, Long pendingToId, String state,
      Instant failingSince, Instant lastAttemptAt, String lastError, long version, boolean verified) {

    /** A destination of the demo world (GW_DEMO=1): shown, never contacted. */
    public boolean demo() {
      return config.path("demo").asBoolean(false);
    }

    public String kindId() {
      return KebabCaseEnumConverter.idOf(kind);
    }

    /** Where it writes: {@code siem.acme.internal:6514}, {@code s3://acme-audit/mcp-gateway/}, a table. */
    public String target() {
      return ExportSettings.describe(kind, config);
    }

    /** Active: switched on, trustworthy, real. */
    public boolean runs() {
      return enabled && verified && !demo();
    }

    public long cursor() {
      return lastExportedId == null ? 0 : lastExportedId;
    }
  }

  private final AuditExportRepository rows;
  private final RowMac mac;
  private final IntegrityAlerts alerts;
  private final MasterKeyLock lock;
  private final JsonMapper json;
  private final TransactionTemplate readTx;

  Destinations(AuditExportRepository rows, RowMac mac, IntegrityAlerts alerts, MasterKeyLock lock, JsonMapper json,
      PlatformTransactionManager tx) {
    this.rows = rows;
    this.mac = mac;
    this.alerts = alerts;
    this.lock = lock;
    this.json = json;
    this.readTx = new TransactionTemplate(tx);
    this.readTx.setReadOnly(true);
  }

  public List<Destination> all() {
    return readTx.execute(s -> rows.findAllByOrderByNameAsc().stream().map(this::view).toList());
  }

  public Optional<Destination> find(String id) {
    return readTx.execute(s -> rows.findById(id).map(this::view));
  }

  /**
   * The destination of a row read in the caller's transaction. While the core is locked no MAC can be checked: the
   * row counts as unverified (nothing is sent) without an alert — it wasn't changed, the key is missing.
   */
  public Destination view(AuditExportEntity e) {
    boolean locked = lock.locked();
    boolean verified = !locked && mac.verify(TABLE, e.getId(), e.getVersion(), fields(e, e.getLastExportedId(),
        e.getPendingToId()), e.getMac());
    Schedule schedule;
    try {
      schedule = Schedule.parse(e.getSchedule());
    } catch (IllegalArgumentException invalid) {
      // Only an edit around the core stores a schedule the API refuses; the MAC can't hold either.
      schedule = Schedule.DAILY;
      verified = false;
    }
    if (!verified && !locked) {
      alerts.report("audit-export", e.getId(), "Audit export · " + e.getName(), "the destination was changed outside "
          + "GateRook: nothing is exported to it until you save it again");
    }
    ObjectNode config = json.valueToTree(e.getConfig());
    return new Destination(e.getId(), e.getKind(), e.getName(), e.isEnabled(), config, schedule,
        e.getSecretId(), e.getLastExportedId(), e.getLastExportedAt(),
        e.getPendingToId(), e.getState(), e.getFailingSince(), e.getLastAttemptAt(), e.getLastError(), e.getVersion(),
        verified);
  }

  /**
   * Signs a row the person just saved, after the flush that settled its version: saving is the review that makes a
   * changed row trusted again. Call in the save's transaction.
   */
  public AuditExportEntity sign(AuditExportEntity e) {
    AuditExportEntity saved = rows.saveAndFlush(e);
    saved.setMac(mac.sign(TABLE, saved.getId(), saved.getVersion(), fields(saved, saved.getLastExportedId(),
        saved.getPendingToId())));
    saved = rows.saveAndFlush(saved);
    alerts.resolve("audit-export", saved.getId());
    return saved;
  }

  /**
   * The MAC of {@code e} once its position is {@code exportedId} with {@code pendingToId} in flight, for the
   * conditional update that moves it (over {@code e}'s version: the update holds only while that is the row's). The
   * caller checked {@code e} first — signing a new position over a row changed outside would vouch for the change.
   *
   * @throws IllegalStateException while the core is locked
   */
  public byte[] position(AuditExportEntity e, Long exportedId, Long pendingToId) {
    return mac.sign(TABLE, e.getId(), e.getVersion(), fields(e, exportedId, pendingToId));
  }

  /**
   * How far retention may delete agent call rows: the lowest position among the destinations, where one that never
   * exported, and one whose row doesn't verify (changed outside GateRook, the core locked), counts as 0 — its
   * position may have been moved past rows it never received. {@link Long#MAX_VALUE} without destinations. A paused
   * destination holds the bound too: the kit promises records go only "after every export destination has received
   * them". Call in the delete's transaction.
   */
  public long retentionBound() {
    long bound = Long.MAX_VALUE;
    for (AuditExportEntity e : rows.findAll()) {
      Destination d = view(e);
      bound = Math.min(bound, d.verified() ? d.cursor() : 0);
    }
    return bound;
  }

  /** The core runs without its master key: no destination verifies, none is changed. */
  public boolean locked() {
    return lock.locked();
  }

  /** Forgets the alert of a destination that is gone. */
  public void forget(String id) {
    alerts.resolve("audit-export", id);
  }

  private ObjectNode fields(AuditExportEntity e, Long exportedId, Long pendingToId) {
    ObjectNode f = mac.fields();
    f.put("kind", KebabCaseEnumConverter.idOf(e.getKind()));
    f.put("name", e.getName());
    f.put("enabled", e.isEnabled());
    f.set("config", e.getConfig() == null ? JsonNodeFactory.instance.nullNode() : mac.tree(e.getConfig()));
    f.put("schedule", e.getSchedule());
    f.put("secretId", e.getSecretId());
    f.put("lastExportedId", exportedId);
    f.put("pendingToId", pendingToId);
    return f;
  }

  /** The stored form of a config: what {@link ExportSettings#normalize} returned, as the JSON column keeps it. */
  @SuppressWarnings("unchecked")
  public java.util.Map<String, Object> column(JsonNode config) {
    return json.convertValue(config, java.util.Map.class);
  }
}
