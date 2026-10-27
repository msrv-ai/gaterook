package ai.msrv.gaterook.core.settings;

import ai.msrv.gaterook.core.platform.JsonMergePatch;
import ai.msrv.gaterook.core.platform.ProblemException;
import ai.msrv.gaterook.core.platform.SchemaValidator;
import ai.msrv.gaterook.core.platform.SettingStore;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder.SetupChange;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The typed sections of Settings (ARCHITECTURE.md §6 settings, kit {@code settings.html}) on top of
 * {@link SettingStore}: one {@code setting} row per section, keyed by its name, validated by the section's JSON Schema.
 * A section reads as its schema defaults overlaid with what was stored; an update is a JSON Merge Patch checked
 * against the stored version, validated whole, written with one Setup change per changed key in the same transaction.
 *
 * <p>Other modules read their section through the same keys ({@code authorization.tokenTtlDays} by tokens,
 * {@code audit.retentionYears} by the retention job, {@code local-api.allowedOrigins} by the local guard); a value
 * they don't find falls back to the same default as here.
 */
@Service
public class SettingsService {

  /**
   * The sections people edit, in the kit's order (Users & roles is a static preview; Notifications and Tunnel have
   * their own API).
   */
  public static final List<String> SECTIONS = List.of("general", "appearance", "authorization", "approval-rules",
      "local-api", "audit", "backup");

  /** Origins a browser-based client may call from: scheme, host, port; no path (kit Local API). */
  static final String ORIGIN = "^https?://(\\[[0-9a-f:]+\\]|[a-z0-9]([a-z0-9-]*[a-z0-9])?"
      + "(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*)(:[0-9]{1,5})?$";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** Each section's schema; {@code default} gives the value of a key nobody set, {@code const} one nobody can set. */
  static final Map<String, ObjectNode> SCHEMAS = schemas();

  /**
   * One section as the form shows it.
   *
   * @param values  defaults overlaid with what is stored
   * @param version the stored row's version; {@code 0} while nothing was saved
   */
  public record Section(String name, ObjectNode values, long version, Instant updatedAt) {}

  private final SettingStore store;
  private final SchemaValidator validator;
  private final ObjectProvider<SetupChangeRecorder> setupChanges;
  private final TransactionTemplate writeTx;
  private final TransactionTemplate readTx;

  public SettingsService(SettingStore store, SchemaValidator validator,
      ObjectProvider<SetupChangeRecorder> setupChanges, PlatformTransactionManager transactions) {
    this.store = store;
    this.validator = validator;
    this.setupChanges = setupChanges;
    this.writeTx = new TransactionTemplate(transactions);
    this.readTx = new TransactionTemplate(transactions);
    this.readTx.setReadOnly(true);
  }

  public Map<String, Section> all() {
    Map<String, Section> out = new LinkedHashMap<>();
    readTx.executeWithoutResult(s -> SECTIONS.forEach(name -> out.put(name, read(name))));
    return out;
  }

  /** @throws ProblemException 404 for a section that doesn't exist */
  public Section get(String name) {
    requireSection(name);
    return readTx.execute(s -> read(name));
  }

  /** The schema of a section, for the form and for tests. */
  public ObjectNode schema(String name) {
    return requireSection(name).deepCopy();
  }

  /**
   * {@code PUT /api/settings/{section}}: merges {@code patch} into the section, validates the result and stores it.
   *
   * @param version the version the form read ({@code 0} for a section never saved)
   * @throws ProblemException 422 at the field the schema refuses; 404 for an unknown section
   * @throws OptimisticLockingFailureException when someone saved the section meanwhile (409)
   */
  public Section update(String name, JsonNode patch, long version, String reason) {
    ObjectNode schema = requireSection(name);
    if (patch == null || !patch.isObject()) {
      throw ProblemException.unprocessable("Invalid settings", "Send the section's fields as an object.", "");
    }
    return writeTx.execute(s -> {
      Optional<SettingStore.Setting> stored = store.find(name);
      long current = stored.map(SettingStore.Setting::version).orElse(0L);
      if (current != version) {
        throw new OptimisticLockingFailureException("Settings " + name + " are at version " + current);
      }
      ObjectNode before = effective(schema, stored.map(SettingStore.Setting::value).orElse(null));
      JsonNode merged = JsonMergePatch.apply(before, patch);
      if (!merged.isObject()) {
        throw ProblemException.unprocessable("Invalid settings", "Send the section's fields as an object.", "");
      }
      for (SchemaValidator.Violation v : validator.validate(schema, merged)) {
        throw ProblemException.unprocessable("Check the settings", v.message(), v.pointer());
      }
      ObjectNode after = (ObjectNode) merged;
      List<String> changed = changedKeys(before, after);
      if (changed.isEmpty()) {
        return read(name);
      }
      SettingStore.Setting saved = store.put(name, after, version);
      SetupChangeRecorder recorder = setupChanges.getObject();
      for (String key : changed) {
        recorder.record(new SetupChange(SetupChangeRecorder.What.SETTINGS, "Settings › " + title(name) + " · " + key,
            text(before.get(key)), text(after.get(key)), reason, null, null, null, null));
      }
      return new Section(name, effective(schema, saved.value()), saved.version(), saved.updatedAt());
    });
  }

  /** The value of one key as the section reads it (stored or default); for the modules that act on a setting. */
  public JsonNode value(String section, String key) {
    return get(section).values().path(key);
  }

  private Section read(String name) {
    ObjectNode schema = SCHEMAS.get(name);
    Optional<SettingStore.Setting> stored = store.find(name);
    return new Section(name, effective(schema, stored.map(SettingStore.Setting::value).orElse(null)),
        stored.map(SettingStore.Setting::version).orElse(0L), stored.map(SettingStore.Setting::updatedAt)
            .orElse(null));
  }

  /**
   * Defaults overlaid with the stored keys the schema still knows: a key a newer core dropped is ignored, not an error,
   * and a stored value the schema no longer accepts gives way to the default.
   */
  static ObjectNode effective(ObjectNode schema, JsonNode stored) {
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    for (Map.Entry<String, JsonNode> p : schema.path("properties").properties()) {
      JsonNode dflt = p.getValue().has("const") ? p.getValue().get("const") : p.getValue().get("default");
      if (dflt != null) {
        out.set(p.getKey(), dflt.deepCopy());
      }
    }
    if (stored != null && stored.isObject()) {
      for (Map.Entry<String, JsonNode> e : stored.properties()) {
        JsonNode property = schema.path("properties").path(e.getKey());
        if (property.isObject() && !property.has("const")) {
          out.set(e.getKey(), e.getValue().deepCopy());
        }
      }
    }
    return out;
  }

  private static List<String> changedKeys(ObjectNode before, ObjectNode after) {
    List<String> out = new ArrayList<>();
    for (String key : after.propertyNames()) {
      if (!Objects.equals(before.get(key), after.get(key))) {
        out.add(key);
      }
    }
    for (String key : before.propertyNames()) {
      if (!after.has(key) && !out.contains(key)) {
        out.add(key);
      }
    }
    return out;
  }

  private static String text(JsonNode value) {
    if (value == null || value.isNull() || value.isMissingNode()) {
      return null;
    }
    String text = value.isString() ? value.asString() : JSON.writeValueAsString(value);
    return text.length() > 1_000 ? text.substring(0, 1_000) : text;
  }

  private static ObjectNode requireSection(String name) {
    ObjectNode schema = name == null ? null : SCHEMAS.get(name);
    if (schema == null) {
      throw ProblemException.notFound("No such settings", "Settings have the sections " + String.join(", ", SECTIONS)
          + ".");
    }
    return schema;
  }

  private static String title(String section) {
    return switch (section) {
      case "general" -> "General";
      case "appearance" -> "Appearance";
      case "authorization" -> "Authorization";
      case "approval-rules" -> "Approval rules";
      case "local-api" -> "Local API";
      case "audit" -> "Audit & retention";
      case "backup" -> "Backup";
      default -> section;
    };
  }

  private static Map<String, ObjectNode> schemas() {
    Map<String, ObjectNode> out = new LinkedHashMap<>();
    out.put("general", parse("""
        {"type":"object","additionalProperties":false,"properties":{
          "openAtLogin":{"type":"boolean","default":true,"title":"Start MCP Gateway when you sign in"},
          "startHidden":{"type":"boolean","default":true,"title":"Start minimized"},
          "autoUpdate":{"type":"boolean","default":true,"title":"Check for updates automatically"},
          "singleKeyShortcuts":{"type":"boolean","default":true,"title":"Single-key shortcuts"}}}"""));
    out.put("appearance", parse("""
        {"type":"object","additionalProperties":false,"properties":{
          "theme":{"enum":["system","light","dark"],"default":"system","title":"Theme"},
          "contrast":{"enum":["system","normal","more"],"default":"system","title":"Contrast"}}}"""));
    out.put("authorization", parse("""
        {"type":"object","additionalProperties":false,"properties":{
          "tokenTtlDays":{"type":"integer","enum":[1,7,30,90],"default":7,"title":"Default token lifetime",
            "x-unit":"days"},
          "clientRegistration":{"enum":["cimd","cimd+dcr"],"default":"cimd","title":"Client registration"}}}"""));
    out.put("approval-rules", parse("""
        {"type":"object","additionalProperties":false,"properties":{
          "decisionsFrom":{"enum":["app","app-and-linked"],"default":"app-and-linked",
            "title":"Where decisions can be made"}}}"""));
    out.put("local-api", parse("""
        {"type":"object","additionalProperties":false,"properties":{
          "allowedOrigins":{"type":"array","uniqueItems":true,"maxItems":20,"default":[],"title":"Allowed origins",
            "items":{"type":"string","maxLength":253,"pattern":"%s"}}}}""".formatted(ORIGIN.replace("\\", "\\\\"))));
    out.put("audit", parse("""
        {"type":"object","additionalProperties":false,"properties":{
          "retentionYears":{"type":"integer","enum":[1,2,3,5],"default":1,"title":"Keep records for",
            "x-unit":"years"}}}"""));
    out.put("backup", parse("""
        {"type":"object","additionalProperties":false,"properties":{
          "daily":{"type":"boolean","default":true,"title":"Back up the database every day"},
          "at":{"const":"03:00","title":"Time of the daily copy"},
          "keep":{"const":7,"title":"Copies kept"}}}"""));
    return Map.copyOf(out);
  }

  private static ObjectNode parse(String text) {
    return (ObjectNode) JSON.readTree(text);
  }
}
