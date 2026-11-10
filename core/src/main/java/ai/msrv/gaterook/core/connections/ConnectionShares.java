package ai.msrv.gaterook.core.connections;

import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.connections.internal.PersonOnlyFields;
import ai.msrv.gaterook.core.connections.internal.SchemaPointers;
import ai.msrv.gaterook.core.connections.internal.SecretFields;
import ai.msrv.gaterook.core.connectors.ConnectorRegistry;
import ai.msrv.gaterook.core.platform.ProblemException;
import ai.msrv.gaterook.core.platform.Masker;
import ai.msrv.gaterook.core.platform.MaskPolicy;
import ai.msrv.gaterook.core.platform.Taint;
import ai.msrv.gaterook.core.secrets.SecretInfo;
import ai.msrv.gaterook.core.secrets.SecretOwner;
import ai.msrv.gaterook.core.secrets.SecretService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Portable connection settings, with explicit local-secret consent and no external credential resolution.
 *
 * <p>Trust settings never travel: what a connector marks {@code x-person-only} (a trusted host key, certificate,
 * cluster or credential command, an account pin, the opt-in to run a local command) records a decision the person made
 * on one computer. An export leaves them out and says so; an import drops them from an entry that carries them (an
 * older GateRook exported them, and a file is untrusted input), whatever they hold, before any check, and the
 * connection becomes a disabled draft with one Setup required item per kind of decision, which the person makes again
 * on this computer with Test connection. The entry's own warnings never decide any of this.
 */
@Service
public class ConnectionShares {
  private final ConnectionConfigs configs;
  private final ConnectionService connections;
  private final ConnectorRegistry registry;
  private final SecretService secrets;
  private final Masker masker;

  public ConnectionShares(ConnectionConfigs configs, ConnectionService connections, ConnectorRegistry registry,
      SecretService secrets, Masker masker) {
    this.configs = configs;
    this.connections = connections;
    this.registry = registry;
    this.secrets = secrets;
    this.masker = masker;
  }

  public boolean supported(String type) {
    return registry.type(type).filter(t -> t.usable() && t.kind() != ConnectorManifest.Kind.MCP_SERVER).isPresent();
  }

  /**
   * The entry's config for the review, masked: without the trust settings an import drops, and without the values that
   * may hold a key.
   */
  public JsonNode previewConfig(JsonNode entry) {
    ObjectNode out = portable(entry).map(Portable::config)
        .orElseGet(() -> (ObjectNode) entry.path("config").deepCopy());
    registry.manifest(entry.path("type").asString()).ifPresent(manifest -> {
      for (String field : SecretFields.keyAllowedTemplates(manifest.configSchema()))
        SchemaPointers.values(out, field).keySet().forEach(p -> remove(out, p));
    });
    return masker.tree(out, null, MaskPolicy.SECRETS_ONLY).tree();
  }

  /** The secrets an import can take over: none of a trust setting. */
  public int secretCount(JsonNode entry) {
    var manifest = registry.manifest(entry.path("type").asString());
    if (manifest.isEmpty()) return entry.path("secrets").size();
    Portable portable = portable(entry).orElseThrow();
    int count = portable.secrets().size();
    for (String field : SecretFields.keyAllowedTemplates(manifest.get().configSchema()))
      count += SchemaPointers.values(portable.config(), field).size();
    return count;
  }

  /**
   * What the review says about an entry that carries trust settings: they are not imported, and the connection waits,
   * off and with Setup required, until the person confirms each kind named here. Empty for an entry without any.
   */
  public Optional<String> trustNote(JsonNode entry) {
    List<String> kinds = portable(entry).map(Portable::trust).orElse(List.of());
    return kinds.isEmpty() ? Optional.empty() : Optional.of("Trust settings in this file are not imported: "
        + names(kinds) + ". The connection is imported off, with Setup required, until you confirm them on this "
        + "computer with Test connection.");
  }

  public ObjectNode export(String id, boolean includeSecrets) {
    var stored = configs.byId(id).orElseThrow(() -> invalid("The selected connection no longer exists."));
    if (!stored.verified()) throw invalid("Review connections changed outside GateRook before sharing them.");
    var config = stored.config();
    ObjectNode out = JsonNodeFactory.instance.objectNode().put("id", config.name()).put("name", config.name())
        .put("type", config.type()).put("env", config.env().id());
    ObjectNode values = out.putObject("secrets");
    ArrayNode omitted = out.putArray("omittedSecretFields");
    List<String> warnings = new ArrayList<>();
    ObjectNode clean = (ObjectNode) config.config().deepCopy();
    if (!supported(config.type())) {
      clean.removeAll();
      warnings.add("This connector requires its dedicated setup flow on this computer and cannot be imported.");
    } else {
      JsonNode schema = registry.manifest(config.type()).orElseThrow().configSchema();
      // Decisions made on this computer stay here; the destination makes its own with Test connection.
      Set<String> trust = new LinkedHashSet<>(leaveOutTrust(clean, schema));
      for (String field : SecretFields.templates(schema)) {
        SchemaPointers.values(clean, field).keySet().forEach(p -> { remove(clean, p); omitted.add(p); });
      }
      for (String field : SecretFields.keyAllowedTemplates(schema)) {
        if (!includeSecrets) SchemaPointers.values(clean, field).keySet().forEach(p -> { remove(clean, p); omitted.add(p); });
      }
      Taint taint = new Taint();
      try (var resolver = secrets.resolver(SecretOwner.connection(id), stored.secretIds().keySet(), taint)) {
        for (Map.Entry<String, String> field : stored.secretIds().entrySet()) {
          remove(clean, field.getKey());
          // A credential of a trust setting stays with that setting: neither shared nor asked for.
          Optional<String> ofTrust = trustOf(field.getKey(), schema);
          if (ofTrust.isPresent()) { trust.add(ofTrust.get()); continue; }
          SecretInfo info = secrets.get(field.getValue());
          if (includeSecrets && "local".equals(info.source()) && "stored".equals(info.state())
              && SecretOwner.connection(id).equals(info.owner()) && field.getKey().equals(info.field())) {
            try (var secret = resolver.secret(field.getKey())) { values.put(field.getKey(), secret.unsafeString()); }
            catch (ai.msrv.gaterook.connector.api.SecretUnavailableException e) {
              throw invalid("A selected local secret is unavailable. Restore it or export without secrets.");
            }
          } else omitted.add(field.getKey());
        }
      } finally { taint.clear(); }
      List<String> local = localFields(clean, schema);
      local.forEach(p -> remove(clean, p));
      if (!local.isEmpty()) warnings.add("Local file paths and credential profiles were omitted; select them on the destination computer.");
      if (!trust.isEmpty()) warnings.add("Trust settings were omitted (" + names(trust)
          + "); confirm them on the destination computer with Test connection.");
    }
    out.set("config", clean);
    List<String> omittedFields = new ArrayList<>();
    omitted.forEach(field -> { if (!values.has(field.asString()) && !omittedFields.contains(field.asString())) omittedFields.add(field.asString()); });
    omitted.removeAll();
    omittedFields.forEach(omitted::add);
    ArrayNode notes = out.putArray("warnings");
    warnings.forEach(notes::add);
    return out;
  }

  /**
   * Validates untrusted file contents without connecting, running a command or reading local files. Trust settings
   * are left out first, whatever they hold: the checks see what an import creates, and they never make a file invalid.
   */
  public void validate(JsonNode entry, boolean includeSecrets) {
    if (!entry.path("config").isObject() || !entry.path("secrets").isObject()) throw invalid("Invalid connection settings.");
    String name = entry.path("name").asString("");
    if (name.length() > 64 || !ConnectionService.NAME.matcher(name).matches()) throw invalid("Invalid connection name.");
    Env env;
    try { env = Env.of(entry.path("env").asString("")); }
    catch (IllegalArgumentException e) { throw invalid("Invalid connection environment."); }
    String type = entry.path("type").asString("");
    if (!supported(type)) return;
    JsonNode schema = registry.manifest(type).orElseThrow().configSchema();
    Portable portable = portable(entry).orElseThrow();
    if (SecretFields.mentionsPlaceholder(portable.config()))
      throw invalid("Stored secret identifiers cannot be imported.");
    if (!localFields(portable.config(), schema).isEmpty())
      throw invalid("Choose local files and credential profiles on this computer after importing.");
    Map<String, SecretInput> values = inputs(portable.secrets(), includeSecrets);
    try { connections.missingImportSettings(uniqueName(name), type, env, portable.config(), values); }
    catch (ProblemException e) { throw invalid("Shared connection settings do not match the installed connector. Review the source settings."); }
    finally { values.values().forEach(SecretInput::clear); }
  }

  /** Exact, caller-reviewed inputs retained only until the enclosing import finishes or fails. */
  public record Prepared(String name, String type, Env env, JsonNode config, Map<String, SecretInput> secrets,
      List<String> missing) implements AutoCloseable {
    public boolean needsSetup() { return !missing.isEmpty(); }
    @Override public void close() { secrets.values().forEach(SecretInput::clear); }
    @Override public String toString() { return "Prepared[name=" + name + ", settings=<redacted>]"; }
  }

  public Prepared prepareImport(JsonNode entry, boolean includeSecrets, Set<String> reservedNames) {
    String type = entry.path("type").asString();
    if (!supported(type)) throw invalid("Skip unavailable connectors and use their dedicated setup flow.");
    if (!entry.path("config").isObject() || !entry.path("secrets").isObject())
      throw invalid("Invalid connection settings.");
    // Nothing of a trust setting is taken over, and each kind the entry carried keeps the connection a draft until the
    // person confirms their own on this computer: no test, health check or call runs before.
    Portable portable = portable(entry).orElseThrow();
    ObjectNode reviewed = (ObjectNode) entry.deepCopy();
    reviewed.set("config", portable.config());
    reviewed.set("secrets", portable.secrets());
    List<String> missing = new ArrayList<>();
    entry.path("omittedSecretFields").forEach(field -> missing.add(field.asString() + ": Supply the omitted credential."));
    // An omitted optional credential must not silently turn an authenticated connection into anonymous access.
    if (!includeSecrets) {
      portable.secrets().propertyNames().forEach(field -> missing.add(field + ": Include or supply the credential."));
      ObjectNode clean = (ObjectNode) reviewed.path("config");
      for (String field : SecretFields.keyAllowedTemplates(registry.manifest(type).orElseThrow().configSchema()))
        SchemaPointers.values(clean, field).keySet().forEach(p -> {
          remove(clean, p);
          missing.add(p + ": Include or supply the credential.");
        });
    }
    if (entry.path("warnings").valueStream().anyMatch(w -> w.asString().startsWith("Local file paths and credential profiles were omitted")))
      missing.add("Choose the omitted local files and credential profiles on this computer.");
    portable.trust().forEach(kind -> missing.add(kind + ": Not imported. Confirm this trust setting on this computer "
        + "with Test connection, or set it yourself."));
    validate(reviewed, includeSecrets);
    Map<String, SecretInput> values = inputs(reviewed.path("secrets"), includeSecrets);
    try {
      String name = uniqueName(entry.path("name").asString(), reservedNames);
      Env env = Env.of(entry.path("env").asString());
      missing.addAll(connections.missingImportSettings(name, type, env, reviewed.path("config"), values));
      reservedNames.add(name);
      return new Prepared(name, type, env, reviewed.path("config"), values, missing.stream().distinct().toList());
    } catch (RuntimeException e) {
      values.values().forEach(SecretInput::clear);
      throw e;
    }
  }

  /** Resource checks happen before the batch transaction; incomplete entries never contact a resource. */
  public void preflightImport(Prepared prepared, String confirm) {
    if (!prepared.needsSetup()) connections.preflightCreate(prepared.name(), prepared.type(), prepared.env(),
        prepared.config(), prepared.secrets(), confirm);
  }

  /** Only complete entries that passed the batch preflight become enabled connections. */
  public ConnectionService.Summary importPrepared(Prepared prepared, String reason) {
    return prepared.needsSetup()
        ? connections.createImportDraft(prepared.name(), prepared.type(), prepared.env(), prepared.config(),
            prepared.secrets(), prepared.missing(), null, reason)
        : connections.create(prepared.name(), prepared.type(), prepared.env(), prepared.config(), prepared.secrets(),
            reason, ConnectionService.CONFIRM_DANGER);
  }

  public String uniqueName(String source) {
    return uniqueName(source, Set.of());
  }

  private String uniqueName(String source, Set<String> reservedNames) {
    Set<String> names = new HashSet<>(reservedNames);
    configs.all().forEach(c -> names.add(c.name()));
    if (!names.contains(source)) return source;
    for (int i = 2; i < 100_000; i++) {
      String suffix = "-" + i;
      String candidate = source.substring(0, Math.min(source.length(), 64 - suffix.length())).replaceAll("-+$", "") + suffix;
      if (!names.contains(candidate)) return candidate;
    }
    throw invalid("Too many connections with the same name.");
  }

  /**
   * An entry's config and credentials as an import takes them over, and the kinds of trust setting it carried
   * ({@code *} for array indexes, in the order the entry holds them).
   */
  private record Portable(ObjectNode config, ObjectNode secrets, List<String> trust) {}

  /**
   * The entry without its trust settings: found by the installed connector's schema, never by what the entry says
   * about itself. Empty when no installed connector has the entry's type.
   */
  private Optional<Portable> portable(JsonNode entry) {
    var manifest = registry.manifest(entry.path("type").asString());
    if (manifest.isEmpty()) return Optional.empty();
    JsonNode schema = manifest.get().configSchema();
    ObjectNode config = entry.path("config") instanceof ObjectNode given ? given.deepCopy()
        : JsonNodeFactory.instance.objectNode();
    Set<String> trust = new LinkedHashSet<>(leaveOutTrust(config, schema));
    ObjectNode values = entry.path("secrets") instanceof ObjectNode given ? given.deepCopy()
        : JsonNodeFactory.instance.objectNode();
    for (String field : List.copyOf(values.propertyNames())) {
      trustOf(field, schema).ifPresent(kind -> { values.remove(field); trust.add(kind); });
    }
    return Optional.of(new Portable(config, values, List.copyOf(trust)));
  }

  /**
   * Removes the trust settings ({@code x-person-only}) from {@code config}, whatever they hold ({@code null},
   * {@code false} and empty ones too), wherever the schema marks them.
   *
   * @return the kinds removed ({@code *} for array indexes), in the order the config held them
   */
  private static List<String> leaveOutTrust(ObjectNode config, JsonNode schema) {
    List<PersonOnlyFields.Held> held;
    try { held = PersonOnlyFields.held(config, schema); }
    catch (IllegalArgumentException e) { throw tooComplex(); }
    // From the last to the first: removing an array item keeps the indexes of those before it.
    for (int i = held.size() - 1; i >= 0; i--) leaveOut(config, held.get(i).pointer());
    return held.stream().map(PersonOnlyFields.Held::template).distinct().toList();
  }

  /** The kind of trust setting a credential field belongs to, if it belongs to one. */
  private static Optional<String> trustOf(String field, JsonNode schema) {
    try { return PersonOnlyFields.covering(schema, field); }
    catch (IllegalArgumentException e) { throw tooComplex(); }
  }

  private static ProblemException tooComplex() {
    return invalid("The connector's settings schema is too complex to check for trust settings.");
  }

  private static void leaveOut(ObjectNode root, String pointer) {
    if (pointer.isEmpty()) { root.removeAll(); return; }
    int slash = pointer.lastIndexOf('/');
    JsonNode parent = root.at(pointer.substring(0, slash));
    String key = SecretFields.unescape(pointer.substring(slash + 1));
    if (parent instanceof ObjectNode object) object.remove(key);
    else if (parent instanceof ArrayNode array && key.matches("\\d+")) array.remove(Integer.parseInt(key));
  }

  /** The kinds for a warning, cut well within the 1,024 characters a share allows per warning. */
  private static String names(Collection<String> kinds) {
    StringBuilder out = new StringBuilder();
    int shown = 0;
    for (String kind : kinds) {
      if (out.length() + kind.length() > 600) break;
      if (!out.isEmpty()) out.append(", ");
      out.append(kind);
      shown++;
    }
    if (shown == 0) return kinds.size() + (kinds.size() == 1 ? " setting" : " settings");
    if (shown < kinds.size()) out.append(" and ").append(kinds.size() - shown).append(" more");
    return out.toString();
  }

  private static Map<String, SecretInput> inputs(JsonNode secrets, boolean included) {
    Map<String, SecretInput> out = new LinkedHashMap<>();
    if (!included) return out;
    for (var field : secrets.properties()) {
      if (!field.getKey().startsWith("/") || field.getKey().length() > 512 || !field.getValue().isString()
          || field.getValue().asString().length() > 1_048_576) throw invalid("Invalid shared secret field.");
      out.put(field.getKey(), SecretInput.local(field.getValue().asString().toCharArray()));
    }
    return out;
  }

  private static List<String> localFields(JsonNode config, JsonNode schema) {
    Set<String> fields = new java.util.LinkedHashSet<>();
    for (var field : SchemaPointers.collect(schema, s -> s.has("x-file-picker"))) fields.addAll(SchemaPointers.values(config, field.template()).keySet());
    collectLocal(config, "", fields);
    return List.copyOf(fields);
  }

  private static void collectLocal(JsonNode node, String path, Set<String> out) {
    if (node.isObject()) for (var field : node.properties()) {
      String p = path + "/" + SecretFields.escape(field.getKey());
      if (Set.of("privateKeyPath", "kubeconfigPath", "profile", "credentialFile", "credentialsFile", "tokenFile", "caFile", "certFile", "keyFile").contains(field.getKey())) out.add(p);
      else collectLocal(field.getValue(), p, out);
    }
    else if (node.isArray()) for (int i = 0; i < node.size(); i++) collectLocal(node.get(i), path + "/" + i, out);
  }

  private static void remove(JsonNode root, String path) {
    int slash = path.lastIndexOf('/');
    JsonNode parent = root.at(path.substring(0, slash));
    String key = SecretFields.unescape(path.substring(slash + 1));
    if (parent instanceof ObjectNode object) object.remove(key);
    else if (parent instanceof ArrayNode array && key.matches("\\d+")) array.set(Integer.parseInt(key), JsonNodeFactory.instance.nullNode());
  }

  private static ProblemException invalid(String detail) {
    return ProblemException.unprocessable("Check endpoint share", detail, "/bundle/connections");
  }
}
