package ai.msrv.gaterook.core.endpoints;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.core.connections.ConnectionConfigs;
import ai.msrv.gaterook.core.connections.ConnectionShares;
import ai.msrv.gaterook.core.connections.ConnectionService;
import ai.msrv.gaterook.core.connectors.ConnectorRegistry;
import ai.msrv.gaterook.core.endpoints.internal.EndpointRows;
import ai.msrv.gaterook.core.endpoints.internal.ToolPairs;
import ai.msrv.gaterook.core.endpoints.internal.Wiring;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointAccess;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointEntity;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointRepository;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointToolEntity;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointToolRepository;
import ai.msrv.gaterook.core.platform.ProblemException;
import ai.msrv.gaterook.core.platform.Masker;
import ai.msrv.gaterook.core.platform.MaskPolicy;
import ai.msrv.gaterook.core.platform.SchemaValidator;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder;
import ai.msrv.gaterook.core.policy.PolicyService;
import ai.msrv.gaterook.core.templates.TemplateShares;
import ai.msrv.gaterook.core.templates.TemplateService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Selective, additive endpoint transfer. Encryption and file handles stay in the desktop main process. */
@Service
public class EndpointShareService {
  public static final String FORMAT = "gaterook-endpoint-share";
  public static final int MAX_BYTES = 6 * 1024 * 1024;
  private static final String REVIEW = "Import enables the new endpoint and complete connections. Connections missing settings or credentials stay disabled until setup is finished.";
  private static final String ACCESS = "Client grants, tokens, OAuth sign-ins, tunnels and temporary bypasses are not transferred. Bypass becomes Approval.";
  private final EndpointService endpoints;
  private final EndpointRepository repository;
  private final EndpointToolRepository toolRows;
  private final EndpointRows rows;
  private final ConnectionConfigs configs;
  private final ConnectionShares connections;
  private final TemplateShares templates;
  private final TemplateService templateService;
  private final ConnectorRegistry registry;
  private final Wiring wiring;
  private final PolicyService policies;
  private final SchemaValidator validator;
  private final Masker masker;
  private final ApplicationEventPublisher publisher;
  private final SetupChangeRecorder changes;
  private final Clock clock;
  private final TransactionTemplate tx;

  public EndpointShareService(EndpointService endpoints, EndpointRepository repository, EndpointToolRepository toolRows,
      EndpointRows rows, ConnectionConfigs configs, ConnectionShares connections, TemplateShares templates, TemplateService templateService,
      ConnectorRegistry registry, Wiring wiring, PolicyService policies, SchemaValidator validator, Masker masker,
      ApplicationEventPublisher publisher, SetupChangeRecorder changes, Clock clock, PlatformTransactionManager transactions) {
    this.endpoints = endpoints; this.repository = repository; this.toolRows = toolRows; this.rows = rows;
    this.configs = configs; this.connections = connections; this.templates = templates; this.templateService = templateService; this.registry = registry;
    this.wiring = wiring; this.policies = policies; this.validator = validator; this.masker = masker; this.publisher = publisher;
    this.changes = changes; this.clock = clock; this.tx = new TransactionTemplate(transactions);
  }

  public ObjectNode export(String slug, List<String> selected, boolean includeSecrets) {
    return tx.execute(status -> {
      EndpointSnapshot snapshot = endpoints.uncachedSnapshot(slug);
      if (snapshot.blocked()) throw invalid("Review the endpoint's integrity before sharing it.");
      Set<String> names = selection(selected);
      if (names.stream().anyMatch(n -> snapshot.connection(n).isEmpty())) throw invalid("Only this endpoint's connections can be shared.");
      ObjectNode out = object().put("format", FORMAT).put("version", 1).put("createdAt", clock.instant().toString());
      ObjectNode endpoint = out.putObject("endpoint").put("slug", snapshot.slug()).put("description", snapshot.description())
          .put("access", snapshot.readOnly() ? "read-only" : "standard").put("instructions", snapshot.instructions());
      endpoint.set("settings", snapshot.settings().deepCopy());
      ArrayNode entries = out.putArray("connections");
      for (var wired : snapshot.connections()) if (names.contains(wired.config().name())) {
        ObjectNode entry = connections.export(wired.config().id(), includeSecrets);
        var sharedTemplates = connections.supported(wired.config().type()) ? templates.exportFor(wired.config().id(), includeSecrets)
            : new TemplateShares.Exported(JsonNodeFactory.instance.arrayNode(), List.of());
        entry.set("templates", sharedTemplates.templates());
        ArrayNode warnings = (ArrayNode) entry.path("warnings");
        sharedTemplates.warnings().forEach(warnings::add);
        ArrayNode tools = entry.putArray("tools");
        for (EndpointToolEntity tool : toolRows.findByEndpointIdAndConnectionId(snapshot.id(), wired.config().id())) {
          if (!rows.verify(tool)) throw invalid("Review tool settings changed outside GateRook before sharing.");
          tools.add(tool(tool.getToolKey(), tool.getMode(), rows.tree(tool.getOverrides())));
        }
        entries.add(entry);
      }
      ArrayNode core = out.putArray("coreTools");
      snapshot.coreTools().forEach(t -> core.add(tool(t.tool(), t.storedMode(), null)));
      bounded(out);
      return out;
    });
  }

  public ObjectNode preview(JsonNode bundle) {
    validate(bundle);
    ObjectNode out = object();
    ObjectNode endpointPreview = (ObjectNode) bundle.path("endpoint").deepCopy();
    for (String key : List.of("description", "instructions")) if (endpointPreview.path(key).isString())
      endpointPreview.put(key, masker.text(endpointPreview.path(key).asString(), null, MaskPolicy.SECRETS_ONLY).text());
    out.set("endpoint", endpointPreview);
    out.put("suggestedSlug", uniqueSlug(bundle.path("endpoint").path("slug").asString()));
    boolean readOnly = "read-only".equals(bundle.path("endpoint").path("access").asString());
    ArrayNode corePreview = out.putArray("coreTools");
    for (JsonNode source : bundle.path("coreTools")) {
      Mode mode = safeMode(source);
      if (readOnly) mode = ToolPairs.clampReadOnly(CoreTools.withMode(source.path("toolKey").asString()), mode);
      corePreview.add(tool(source.path("toolKey").asString(), mode, source.path("overrides")));
    }
    ArrayNode list = out.putArray("connections");
    for (JsonNode entry : bundle.path("connections")) {
      ObjectNode row = object().put("id", entry.path("id").asString()).put("name", entry.path("name").asString())
          .put("suggestedName", connections.uniqueName(entry.path("name").asString())).put("type", entry.path("type").asString())
          .put("env", entry.path("env").asString()).put("secretCount", connections.secretCount(entry)
              + (int) java.util.stream.StreamSupport.stream(entry.path("templates").spliterator(), false)
                  .filter(t -> templates.containsSecrets(t.path("definition"))).count())
          .put("templateCount", entry.path("templates").size())
          .put("supported", connections.supported(entry.path("type").asString()));
      row.set("omittedSecretFields", entry.path("omittedSecretFields").deepCopy());
      ArrayNode toolPreview = row.putArray("tools");
      boolean adjustedModes = false;
      for (JsonNode source : entry.path("tools")) {
        String key = source.path("toolKey").asString();
        Mode mode = previewMode(source, entry.path("type").asString(), readOnly);
        adjustedModes |= mode != Mode.valueOf(source.path("mode").asString());
        toolPreview.add(tool(key, mode, source.path("overrides")));
      }
      row.set("config", connections.previewConfig(entry));
      ArrayNode warnings = row.putArray("warnings");
      if (adjustedModes) warnings.add("The shown tool modes include destination restrictions. Bypass and template Read only confirmations are not transferred.");
      entry.path("warnings").forEach(n -> warnings.add(masker.text(n.asString(), null, MaskPolicy.SECRETS_ONLY).text()));
      if (!row.path("supported").asBoolean()) warnings.add("This connector is unavailable for import. Use its dedicated setup flow.");
      if (!entry.path("omittedSecretFields").isEmpty()) warnings.add("Some credentials were omitted and must be supplied locally.");
      if (!entry.path("templates").isEmpty()) warnings.add("Included templates: " + entry.path("templates").size() + ". Review their operations before enabling access.");
      if (row.path("supported").asBoolean()) templates.validate(entry.path("templates"), entry.path("type").asString()).forEach(warnings::add);
      list.add(row);
    }
    out.putArray("warnings").add(REVIEW).add(ACCESS).add("Existing endpoints and connections are kept. Name conflicts receive a numeric suffix.");
    return out;
  }

  public ObjectNode importBundle(JsonNode bundle, List<String> selected, boolean includeEndpoint,
      boolean includeSecrets, String requestedSlug, String reason) {
    return importBundle(bundle, selected, includeEndpoint, includeSecrets, requestedSlug, reason, null);
  }

  public ObjectNode importBundle(JsonNode bundle, List<String> selected, boolean includeEndpoint,
      boolean includeSecrets, String requestedSlug, String reason, String confirm) {
    validate(bundle);
    Set<String> ids = selection(selected);
    Map<String, JsonNode> entries = new LinkedHashMap<>();
    bundle.path("connections").forEach(c -> entries.put(c.path("id").asString(), c));
    if (!entries.keySet().containsAll(ids) || !includeEndpoint && ids.isEmpty()) throw invalid("Choose an endpoint or at least one available connection.");
    for (String id : ids) if (!connections.supported(entries.get(id).path("type").asString())) throw invalid("Skip unavailable connectors before importing.");
    String slug = requestedSlug == null || requestedSlug.isBlank() ? uniqueSlug(bundle.path("endpoint").path("slug").asString()) : requestedSlug;
    if (includeEndpoint && !EndpointService.SLUG.matcher(slug).matches()) throw invalid("Use a lowercase endpoint name of up to 63 characters.");
    if (includeEndpoint && repository.existsBySlug(slug)) throw ProblemException.conflict("Name in use", "Choose another endpoint name.");
    Map<String, ConnectionShares.Prepared> prepared = new LinkedHashMap<>();
    try {
      Set<String> reservedNames = new HashSet<>();
      for (String id : ids) prepared.put(id, connections.prepareImport(entries.get(id), includeSecrets, reservedNames));
      if (includeEndpoint && prepared.values().stream().anyMatch(p -> p.env() == Env.PROD)
          && prepared.values().stream().anyMatch(p -> p.env() != Env.PROD))
        throw invalid("Production connections cannot share an endpoint with development or stage connections.");
      List<ProblemException.FieldError> warnings = new ArrayList<>();
      for (var item : prepared.entrySet()) {
        try { connections.preflightImport(item.getValue(), confirm); }
        catch (ProblemException e) {
          if (e.kind() != ProblemException.Kind.CONFLICT || !ConnectionService.CONFIRM_DANGER.equals(e.confirm())) throw e;
          warnings.add(new ProblemException.FieldError("/bundle/connections/" + item.getKey(), e.getMessage()));
        }
      }
      if (!warnings.isEmpty()) throw new ProblemException(ProblemException.Kind.CONFLICT,
          "Review production access warnings", String.join("\n\n", warnings.stream().map(ProblemException.FieldError::message).toList()),
          warnings.getFirst().field(), ConnectionService.CONFIRM_DANGER, warnings);
      return tx.execute(status -> {
        if (includeEndpoint && repository.existsBySlug(slug)) throw ProblemException.conflict("Name in use", "Choose another endpoint name.");
        ObjectNode result = object().put("endpointSlug", includeEndpoint ? slug : null);
        ArrayNode imported = result.putArray("connections");
        ArrayNode notes = result.putArray("warnings").add(REVIEW).add(ACCESS);
        EndpointEntity endpoint = null;
        if (includeEndpoint) {
          JsonNode source = bundle.path("endpoint");
          endpoint = new EndpointEntity(slug, Env.DEV, "read-only".equals(source.path("access").asString())
              ? EndpointAccess.READ_ONLY : EndpointAccess.STANDARD, rows.map(source.path("settings")), clock.instant());
          endpoint.setDescription(source.path("description").asString(null));
          endpoint.setInstructions(source.path("instructions").asString(null));
          endpoint.setOauthEnabled(false);
          endpoint = rows.save(endpoint);
          for (JsonNode tool : bundle.path("coreTools")) rows.save(new EndpointToolEntity(endpoint, null,
              tool.path("toolKey").asString(), safeCoreMode(tool, endpoint), clock.instant()));
        }
        for (String id : ids) {
          JsonNode entry = entries.get(id);
          var ready = prepared.get(id);
          var added = connections.importPrepared(ready, reason);
          var sharedTemplates = templates.importFor(entry.path("templates"), added.name(), includeSecrets, reason);
          sharedTemplates.warnings().forEach(notes::add);
          imported.addObject().put("id", added.id()).put("name", added.name()).put("needsSetup", ready.needsSetup());
          if (endpoint != null) {
            var stored = configs.byId(added.id()).orElseThrow();
            Wiring.mixing(endpoint.getEnv(), !wiring.wired(endpoint.getId()).isEmpty(), stored.config().env(), slug, added.name())
                .ifPresent(message -> { throw invalid(message); });
            var manifest = registry.manifest(added.type()).orElseThrow();
            wiring.wire(endpoint, stored, manifest, offered -> Mode.OFF);
            Map<String, EndpointToolEntity> written = new LinkedHashMap<>();
            toolRows.findByEndpointIdAndConnectionId(endpoint.getId(), added.id()).forEach(t -> written.put(t.getToolKey(), t));
            for (var tool : manifest.tools()) {
              String key = ToolPairs.key(manifest, stored.config(), tool.name());
              if (!written.containsKey(key)) written.put(key, rows.save(new EndpointToolEntity(endpoint, added.id(), key, Mode.OFF, clock.instant())));
            }
            // Reserve every compiled tool as Off before applying the selected source modes. Reconciliation must
            // not treat an omitted first-version template tool as new and enable its default mode.
            for (var compiled : templateService.compiledFor(added.id())) for (var tool : compiled.tools()) {
              String key = compiled.toolKey(tool);
              if (!written.containsKey(key)) written.put(key, rows.save(new EndpointToolEntity(endpoint, added.id(), key, Mode.OFF, clock.instant())));
            }
            for (JsonNode sourceTool : entry.path("tools")) {
              String key = remapTool(sourceTool.path("toolKey").asString(), sharedTemplates.ids());
              if (key == null) continue;
              EndpointToolEntity target = written.get(key);
              if (target == null) continue;
              Mode mode = safeMode(sourceTool);
              if ((key.startsWith("tpl:") || key.startsWith("plugin:")) && mode == Mode.READ_ONLY) mode = Mode.APPROVAL;
              final String toolKey = key;
              var definition = manifest.tools().stream().filter(t -> ToolPairs.key(manifest, stored.config(), t.name()).equals(toolKey)).findFirst();
              if (mode == Mode.READ_ONLY && definition.isPresent() && !definition.get().readOnlyModeAllowed()) mode = Mode.APPROVAL;
              if (endpoint.getAccess() == EndpointAccess.READ_ONLY) {
                mode = definition.isPresent() ? ToolPairs.clampReadOnly(definition.get(), mode) : Mode.OFF;
              }
              target.setMode(mode, clock.instant());
              if (sourceTool.path("overrides").isObject()) target.setOverrides(rows.map(sourceTool.path("overrides")), clock.instant());
              rows.save(target);
            }
            endpoint.setEnv(wiring.deriveEnv(endpoint.getId(), endpoint.getEnv()));
            endpoint = rows.save(endpoint);
          }
        }
        if (endpoint != null) {
          changes.record(new SetupChangeRecorder.SetupChange(SetupChangeRecorder.What.SETTINGS,
              "Endpoint " + slug + " imported", null, "Enabled; selected connections copied", reason, endpoint.getEnv(), slug, null, null));
          publisher.publishEvent(new EndpointChanged(endpoint.getId(), slug));
        }
        return result;
      });
    } finally { prepared.values().forEach(ConnectionShares.Prepared::close); }
  }

  private void validate(JsonNode bundle) {
    bounded(bundle);
    fields(bundle, Set.of("format", "version", "createdAt", "endpoint", "connections", "coreTools"));
    if (!FORMAT.equals(bundle.path("format").asString()) || !bundle.path("version").isInt() || bundle.path("version").asInt() != 1) throw invalid("Unsupported endpoint share format or version.");
    JsonNode endpoint = bundle.path("endpoint");
    fields(endpoint, Set.of("slug", "description", "access", "instructions", "settings"));
    if (!EndpointService.SLUG.matcher(endpoint.path("slug").asString("")).matches()) throw invalid("Invalid endpoint name.");
    if (!Set.of("standard", "read-only").contains(endpoint.path("access").asString(""))) throw invalid("Invalid endpoint access.");
    text(endpoint.path("description"), 500, true); text(endpoint.path("instructions"), EndpointService.MAX_INSTRUCTIONS, true);
    JsonNode settings = endpoint.path("settings");
    fields(settings, Set.of("limits", "protocol", "approvalWait"));
    if (!settings.path("limits").isObject()) throw invalid("Invalid endpoint limits.");
    policies.checkNarrowing(settings.path("limits"), "/bundle/endpoint/settings/limits", "per endpoint");
    for (String name : List.of("protocol", "approvalWait")) if (!validator.validate(EndpointService.SETTINGS_SCHEMA.path(name), settings.path(name)).isEmpty()) throw invalid("Invalid endpoint protocol settings.");
    if (!bundle.path("connections").isArray() || bundle.path("connections").size() > 100) throw invalid("Share at most 100 connections.");
    Set<String> ids = new HashSet<>();
    for (JsonNode entry : bundle.path("connections")) {
      fields(entry, Set.of("id", "name", "type", "env", "config", "secrets", "omittedSecretFields", "tools", "templates", "warnings"));
      String id = text(entry.path("id"), 64, false);
      if (!ids.add(id) || !connectionName(entry.path("name"))) throw invalid("Connection names and identifiers must be unique and valid.");
      text(entry.path("type"), 64, false);
      texts(entry.path("omittedSecretFields"), 200, 512); texts(entry.path("warnings"), 100, 1024);
      validateTools(entry.path("tools"), false);
      connections.validate(entry, true);
      if (connections.supported(entry.path("type").asString())) templates.validate(entry.path("templates"), entry.path("type").asString());
    }
    validateTools(bundle.path("coreTools"), true);
  }

  private void validateTools(JsonNode tools, boolean core) {
    if (!tools.isArray() || tools.size() > 2000) throw invalid("Invalid shared tool list.");
    Set<String> keys = new HashSet<>();
    for (JsonNode tool : tools) {
      fields(tool, Set.of("toolKey", "mode", "overrides"));
      String key = text(tool.path("toolKey"), 256, false);
      if (!keys.add(key) || core && !CoreTools.WITH_MODE.contains(key)) throw invalid("Invalid shared tool identifier.");
      try { Mode.valueOf(tool.path("mode").asString("")); } catch (IllegalArgumentException e) { throw invalid("Invalid shared tool mode."); }
      if (tool.hasNonNull("overrides")) policies.checkNarrowing(tool.path("overrides"), "/bundle/tools/overrides", "per tool");
    }
  }

  private static boolean connectionName(JsonNode node) {
    return node.isString() && node.asString().length() <= 64 && ai.msrv.gaterook.core.connections.ConnectionService.NAME.matcher(node.asString()).matches();
  }
  private static Mode safeMode(JsonNode tool) { Mode mode = Mode.valueOf(tool.path("mode").asString()); return mode == Mode.BYPASS ? Mode.APPROVAL : mode; }
  private Mode previewMode(JsonNode source, String type, boolean readOnly) {
    String key = source.path("toolKey").asString();
    Mode mode = safeMode(source);
    if (key.startsWith("tpl:")) return readOnly ? Mode.OFF : mode == Mode.READ_ONLY ? Mode.APPROVAL : mode;
    if (key.startsWith("plugin:") && mode == Mode.READ_ONLY) mode = Mode.APPROVAL;
    var definition = registry.manifest(type).flatMap(m -> m.tools().stream()
        .filter(t -> (m.kind() == ai.msrv.gaterook.connector.api.ConnectorManifest.Kind.PLUGIN ? "plugin:" + m.id() + ":" + t.name() : t.name()).equals(key)).findFirst());
    if (definition.isEmpty()) return Mode.OFF;
    if (mode == Mode.READ_ONLY && !definition.get().readOnlyModeAllowed()) mode = Mode.APPROVAL;
    return readOnly ? ToolPairs.clampReadOnly(definition.get(), mode) : mode;
  }
  private static Mode safeCoreMode(JsonNode tool, EndpointEntity endpoint) {
    Mode mode = safeMode(tool);
    return endpoint.getAccess() == EndpointAccess.READ_ONLY ? ToolPairs.clampReadOnly(CoreTools.withMode(tool.path("toolKey").asString()), mode) : mode;
  }
  private static String remapTool(String key, Map<String, String> ids) {
    if (!key.startsWith("tpl:")) return key;
    int end = key.indexOf(':', 4);
    if (end < 0 || !ids.containsKey(key.substring(4, end))) return null;
    return "tpl:" + ids.get(key.substring(4, end)) + key.substring(end);
  }
  private String uniqueSlug(String source) {
    if (!repository.existsBySlug(source)) return source;
    for (int i = 2; i < 100_000; i++) {
      String suffix = "-" + i;
      String value = source.substring(0, Math.min(source.length(), 63 - suffix.length())).replaceAll("-+$", "") + suffix;
      if (!repository.existsBySlug(value)) return value;
    }
    throw invalid("Too many endpoints with the same name.");
  }
  private static Set<String> selection(List<String> values) {
    if (values == null || values.size() > 100 || values.stream().anyMatch(v -> v == null || v.isBlank())) throw invalid("Choose up to 100 connections.");
    Set<String> result = new java.util.LinkedHashSet<>(values);
    if (result.size() != values.size()) throw invalid("Choose each connection once.");
    return result;
  }
  private static ObjectNode tool(String key, Mode mode, JsonNode overrides) {
    ObjectNode out = object().put("toolKey", key).put("mode", mode.name());
    out.set("overrides", overrides == null ? JsonNodeFactory.instance.nullNode() : overrides.deepCopy());
    return out;
  }
  private static void fields(JsonNode node, Set<String> allowed) { if (!node.isObject() || node.propertyNames().stream().anyMatch(k -> !allowed.contains(k))) throw invalid("The share contains unsupported fields."); }
  private static String text(JsonNode node, int max, boolean optional) {
    if (optional && (node.isMissingNode() || node.isNull())) return null;
    if (!node.isString() || node.asString().length() > max || !optional && node.asString().isBlank()) throw invalid("A shared text field is invalid or too long.");
    return node.asString();
  }
  private static void texts(JsonNode node, int count, int max) { if (!node.isArray() || node.size() > count) throw invalid("Invalid share notes."); for (JsonNode value : node) text(value, max, false); }
  private static void bounded(JsonNode node) {
    if (node == null) throw invalid("Choose a valid endpoint share.");
    bound(node, 0, new int[] {0});
    if (node.toString().getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw invalid("Endpoint shares must be at most 6 MiB before encryption.");
  }
  private static void bound(JsonNode node, int depth, int[] count) {
    if (depth > 48 || ++count[0] > 100_000) throw invalid("The endpoint share is too complex.");
    if (node.isObject() || node.isArray()) for (JsonNode child : node) bound(child, depth + 1, count);
  }
  private static ObjectNode object() { return JsonNodeFactory.instance.objectNode(); }
  private static ProblemException invalid(String detail) { return ProblemException.unprocessable("Check endpoint share", detail, "/bundle"); }
}
