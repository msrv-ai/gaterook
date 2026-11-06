package ai.msrv.gaterook.core.connections;

import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.Env;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Portable connection settings, with explicit local-secret consent and no external credential resolution. */
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

  public JsonNode previewConfig(JsonNode entry) {
    ObjectNode out = (ObjectNode) entry.path("config").deepCopy();
    registry.manifest(entry.path("type").asString()).ifPresent(manifest -> {
      for (String field : SecretFields.keyAllowedTemplates(manifest.configSchema()))
        SchemaPointers.values(out, field).keySet().forEach(p -> remove(out, p));
    });
    return masker.tree(out, null, MaskPolicy.SECRETS_ONLY).tree();
  }

  public int secretCount(JsonNode entry) {
    int count = entry.path("secrets").size();
    var manifest = registry.manifest(entry.path("type").asString());
    if (manifest.isPresent()) for (String field : SecretFields.keyAllowedTemplates(manifest.get().configSchema()))
      count += SchemaPointers.values(entry.path("config"), field).size();
    return count;
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

  /** Validates untrusted file contents without connecting, running a command or reading local files. */
  public void validate(JsonNode entry, boolean includeSecrets) {
    if (!entry.path("config").isObject() || !entry.path("secrets").isObject()) throw invalid("Invalid connection settings.");
    String name = entry.path("name").asString("");
    if (name.length() > 64 || !ConnectionService.NAME.matcher(name).matches()) throw invalid("Invalid connection name.");
    Env env;
    try { env = Env.of(entry.path("env").asString("")); }
    catch (IllegalArgumentException e) { throw invalid("Invalid connection environment."); }
    if (!supported(entry.path("type").asString(""))) return;
    JsonNode schema = registry.manifest(entry.path("type").asString()).orElseThrow().configSchema();
    if (SecretFields.mentionsPlaceholder(entry.path("config"))) throw invalid("Stored secret identifiers cannot be imported.");
    if (!localFields(entry.path("config"), schema).isEmpty()) throw invalid("Choose local files and credential profiles on this computer after importing.");
    Map<String, SecretInput> values = inputs(entry, includeSecrets);
    try { connections.missingImportSettings(uniqueName(name), entry.path("type").asString(), env, entry.path("config"), values); }
    catch (ProblemException e) { throw invalid("Shared connection settings do not match the installed connector. Review the source settings."); }
    finally { values.values().forEach(SecretInput::clear); }
  }

  public ConnectionService.Summary importDraft(JsonNode entry, boolean includeSecrets, String reason) {
    String type = entry.path("type").asString();
    if (!supported(type)) throw invalid("Skip unavailable connectors and use their dedicated setup flow.");
    ObjectNode reviewed = (ObjectNode) entry.deepCopy();
    if (!includeSecrets) {
      ObjectNode clean = (ObjectNode) reviewed.path("config");
      for (String field : SecretFields.keyAllowedTemplates(registry.manifest(type).orElseThrow().configSchema()))
        SchemaPointers.values(clean, field).keySet().forEach(p -> remove(clean, p));
    }
    validate(reviewed, includeSecrets);
    Map<String, SecretInput> values = inputs(reviewed, includeSecrets);
    return connections.createImportDraft(uniqueName(entry.path("name").asString()), type,
        Env.of(entry.path("env").asString()), reviewed.path("config"), values,
        List.of("Review imported settings and credentials on this computer before enabling access."), null, reason);
  }

  public String uniqueName(String source) {
    Set<String> names = new HashSet<>();
    configs.all().forEach(c -> names.add(c.name()));
    if (!names.contains(source)) return source;
    for (int i = 2; i < 100_000; i++) {
      String suffix = "-" + i;
      String candidate = source.substring(0, Math.min(source.length(), 64 - suffix.length())).replaceAll("-+$", "") + suffix;
      if (!names.contains(candidate)) return candidate;
    }
    throw invalid("Too many connections with the same name.");
  }

  private static Map<String, SecretInput> inputs(JsonNode entry, boolean included) {
    Map<String, SecretInput> out = new LinkedHashMap<>();
    if (!included) return out;
    for (var field : entry.path("secrets").properties()) {
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
