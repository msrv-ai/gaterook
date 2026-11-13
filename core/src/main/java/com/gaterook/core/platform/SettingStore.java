package com.gaterook.core.platform;

import com.gaterook.core.platform.internal.persistence.SettingEntity;
import com.gaterook.core.platform.internal.persistence.SettingRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Table {@code setting}: one JSON document per key (ARCHITECTURE.md §4). The {@code settings} module builds the typed
 * Settings sections on top; other modules keep their small state here ({@code catalog.sequence},
 * {@code audit.head-mac}, {@code activity.saved-views}…). Writes are optimistic: the caller passes the version it read.
 *
 * <p>The keys of {@link #SIGNED} can do harm by themselves when another program edits {@code gateway.db}, so their
 * rows carry a {@link RowMac} (V87) over the key, the version and the value. Every {@link #put} of such a key signs,
 * after the flush that settled the version, and every read says whether the row still verifies
 * ({@link Setting#verified}). What a row that doesn't verify means is its owner's decision: it refuses the row's
 * effect (fail closed) and raises the integrity alert {@code setting}/key until the core writes the key again. The
 * other keys carry no MAC: an edit there can't authorize anything by itself.
 */
@Service
public class SettingStore {

  /**
   * The keys whose rows carry a MAC: {@code audit}, the audit retention (an edit could make retention delete history
   * without any record); {@code catalog.last-good} and {@code catalog.sequence}, the catalog's last good index and the
   * highest sequence the core accepted (an edit could lower what counts as a rollback).
   */
  public static final Set<String> SIGNED = Set.of("audit", "catalog.last-good", "catalog.sequence",
      "modules.last-good", "modules.sequence");

  /** The kind of the integrity alert of a signed key whose row doesn't verify; its id is the key. */
  public static final String ALERT_KIND = "setting";

  private static final String TABLE = "setting";
  private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9.-]{0,127}");

  /**
   * One stored value.
   *
   * @param version  {@code 1} after the first write and one more after each write since; {@code 0} is left for a key
   *     never written, so a form that read nothing and one that read the first value can't both win
   * @param verified whether the row is as the core wrote it: {@code false} for a key of {@link #SIGNED} whose MAC
   *     doesn't hold (changed outside GateRook, written by an older version and never signed, made under another
   *     master key) or can't be checked (the core is locked); {@code true} for the other keys, which carry no MAC
   */
  public record Setting(String key, JsonNode value, long version, Instant updatedAt, boolean verified) {

    /** A value of a key without a MAC. */
    public Setting(String key, JsonNode value, long version, Instant updatedAt) {
      this(key, value, version, updatedAt, true);
    }
  }

  private final SettingRepository settings;
  private final JsonMapper json;
  private final Clock clock;
  /** Looked up when a signed key is first read or written: the master key behind it keeps its own state here. */
  private final ObjectProvider<RowMac> rowMac;

  public SettingStore(SettingRepository settings, JsonMapper json, Clock clock, ObjectProvider<RowMac> rowMac) {
    this.settings = settings;
    this.json = json;
    this.clock = clock;
    this.rowMac = rowMac;
  }

  /** Whether the rows of {@code key} carry a MAC. */
  public static boolean signed(String key) {
    return SIGNED.contains(key);
  }

  @Transactional(readOnly = true)
  public Optional<Setting> find(String key) {
    return settings.findById(checked(key)).map(this::toSetting);
  }

  /**
   * Creates or replaces the value of {@code key}; signs the row when the key is {@linkplain #signed signed}.
   *
   * @param expectedVersion the version the caller read ({@code 0}: the key must not exist yet), or {@code null} to
   *     write unconditionally (internal counters and state)
   * @throws OptimisticLockingFailureException when the stored version is not {@code expectedVersion}
   * @throws IllegalStateException for a signed key while the core is locked (no master key to sign with)
   */
  @Transactional
  public Setting put(String key, JsonNode value, Long expectedVersion) {
    Objects.requireNonNull(value, "value");
    String text = json.writeValueAsString(value);
    Instant now = clock.instant();
    SettingEntity entity = settings.findById(checked(key)).orElse(null);
    if (entity == null) {
      if (expectedVersion != null && expectedVersion != 0) {
        throw new OptimisticLockingFailureException("Setting " + key + " doesn't exist (expected version "
            + expectedVersion + ")");
      }
      entity = new SettingEntity(key, text, now);
    } else {
      if (expectedVersion != null && expectedVersion != version(entity)) {
        throw new OptimisticLockingFailureException("Setting " + key + " is at version " + version(entity)
            + ", not " + expectedVersion);
      }
      entity.setValue(text, now);
    }
    SettingEntity saved = settings.saveAndFlush(entity);
    if (signed(key)) {
      saved.setMac(mac(saved));
      saved = settings.saveAndFlush(saved);
    }
    return toSetting(saved);
  }

  /**
   * Signs the row of a {@linkplain #signed signed} key as it is when it has no MAC yet. Only for the start that
   * applies V87 ({@link LegacyRows}), which vouches for what an older version stored; any other row without a MAC
   * stays unverified.
   *
   * @return whether there was such a row
   */
  @Transactional
  public boolean signLegacy(String key) {
    if (!signed(checked(key))) {
      throw new IllegalArgumentException("Setting " + key + " carries no MAC");
    }
    SettingEntity entity = settings.findById(key).orElse(null);
    if (entity == null || entity.getMac() != null) {
      return false;
    }
    entity.setMac(mac(entity));
    settings.saveAndFlush(entity);
    return true;
  }

  private Setting toSetting(SettingEntity entity) {
    JsonNode value = json.readTree(entity.getValue());
    boolean verified = !signed(entity.getKey()) || rowMac.getObject().verify(TABLE, entity.getKey(),
        entity.getVersion(), fields(value), entity.getMac());
    return new Setting(entity.getKey(), value, version(entity), entity.getUpdatedAt(), verified);
  }

  private byte[] mac(SettingEntity entity) {
    return rowMac.getObject().sign(TABLE, entity.getKey(), entity.getVersion(),
        fields(json.readTree(entity.getValue())));
  }

  /** What the MAC covers besides the key and the version: the value as it reads back. */
  private static ObjectNode fields(JsonNode value) {
    ObjectNode f = JsonNodeFactory.instance.objectNode();
    f.set("value", value);
    return f;
  }

  /** Hibernate counts a row's writes from 0; callers see them from 1, 0 meaning "absent". */
  private static long version(SettingEntity entity) {
    return entity.getVersion() + 1;
  }

  private static String checked(String key) {
    if (key == null || !KEY.matcher(key).matches()) {
      throw new IllegalArgumentException("Invalid setting key: " + key);
    }
    return key;
  }
}
