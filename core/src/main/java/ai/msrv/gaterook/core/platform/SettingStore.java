package ai.msrv.gaterook.core.platform;

import ai.msrv.gaterook.core.platform.internal.persistence.SettingEntity;
import ai.msrv.gaterook.core.platform.internal.persistence.SettingRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Table {@code setting}: one JSON document per key (ARCHITECTURE.md §4). The {@code settings} module builds the typed
 * Settings sections on top; other modules keep their small state here ({@code catalog.sequence},
 * {@code audit.head-mac}, {@code activity.saved-views}…). Writes are optimistic: the caller passes the version it read.
 */
@Service
public class SettingStore {

  private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9.-]{0,127}");

  /**
   * One stored value.
   *
   * @param version {@code 1} after the first write and one more after each write since; {@code 0} is left for a key
   *     never written, so a form that read nothing and one that read the first value can't both win
   */
  public record Setting(String key, JsonNode value, long version, Instant updatedAt) {}

  private final SettingRepository settings;
  private final JsonMapper json;
  private final Clock clock;

  public SettingStore(SettingRepository settings, JsonMapper json, Clock clock) {
    this.settings = settings;
    this.json = json;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public Optional<Setting> find(String key) {
    return settings.findById(checked(key)).map(this::toSetting);
  }

  /**
   * Creates or replaces the value of {@code key}.
   *
   * @param expectedVersion the version the caller read ({@code 0}: the key must not exist yet), or {@code null} to
   *     write unconditionally (internal counters and state)
   * @throws OptimisticLockingFailureException when the stored version is not {@code expectedVersion}
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
    return toSetting(settings.saveAndFlush(entity));
  }

  private Setting toSetting(SettingEntity entity) {
    return new Setting(entity.getKey(), json.readTree(entity.getValue()), version(entity), entity.getUpdatedAt());
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
