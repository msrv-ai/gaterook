package ai.msrv.gaterook.core.endpoints;

import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.connections.ConnectionForms;
import ai.msrv.gaterook.core.connections.ConnectionService;
import ai.msrv.gaterook.core.connections.SecretInput;
import ai.msrv.gaterook.core.connectors.ConnectorRegistry;
import ai.msrv.gaterook.core.connectors.ConnectorType;
import ai.msrv.gaterook.core.platform.CanonicalJson;
import ai.msrv.gaterook.core.platform.EventBus;
import ai.msrv.gaterook.core.platform.Ids;
import ai.msrv.gaterook.core.platform.Masker;
import ai.msrv.gaterook.core.platform.ProblemException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * A person starts a bounded, temporary discovery session. Its endpoint and one-use capability let an AI submit
 * evidence, never provision access. Only the separately authenticated admin API can create reviewed connections.
 * Repository paths and source hints are inert text. The file helper reads only the predetermined handoff file
 * during submission; credential files and external references are used only after the person's confirmation.
 */
@Service
public class ConnectionImportService {

  public static final Duration LIFETIME = Duration.ofHours(2);
  public static final int MAX_SESSIONS = 16;
  public static final int MAX_CONNECTIONS = 50;
  public static final int MAX_BYTES = 128 * 1024;
  private static final Set<String> SPECIALIZED = Set.of("http", "mcp-server");
  private static final Set<String> CANDIDATE_FIELDS = Set.of("id", "name", "type", "env", "config", "evidence",
      "credentialHints", "warnings");

  /** The capability is shown only to the local admin UI, which puts it in the user's chosen AI prompt. */
  public record Session(String id, String repository, String endpoint, Instant expiresAt, String status,
      JsonNode manifest, String submissionToken, String importFile) {
    @Override public String toString() { return "Session[id=" + id + ", status=" + status + ", capability=redacted]"; }
  }

  /** Reviewed input from the admin UI; the connector type is always taken from the staged candidate. */
  public record Selection(String candidateId, String name, String env, JsonNode config,
      Map<String, SecretInput> secrets, String confirm, boolean allowIncomplete) {
    public Selection(String candidateId, String name, String env, JsonNode config, Map<String, SecretInput> secrets,
        String confirm) { this(candidateId, name, env, config, secrets, confirm, false); }
  }

  public record NeedsSetup(String name, List<String> missingFields) {}
  public record Imported(List<String> connections, String endpoint, String status, List<NeedsSetup> needsSetup) {}

  private static final class Pending {
    final String id = Ids.next("ci_");
    final String token = Ids.uuid();
    final String repository;
    final String endpointId;
    final String endpoint;
    final Instant expiresAt;
    final String importFile;
    String status = "awaiting";
    String principal;
    String digest;
    ObjectNode manifest;
    ConnectionImportFiles.Prepared prepared;
    Instant lastOpened = Instant.EPOCH;

    Pending(String repository, EndpointSnapshot endpoint, Instant expiresAt, ConnectionImportFiles files) {
      this.repository = repository;
      this.endpointId = endpoint.id();
      this.endpoint = endpoint.slug();
      this.expiresAt = expiresAt;
      this.importFile = files.create(id);
    }

    Session view() {
      return new Session(id, repository, endpoint, expiresAt, status,
          manifest == null ? null : manifest.deepCopy(), token, importFile);
    }
  }

  private final Map<String, Pending> sessions = new ConcurrentHashMap<>();
  private final EndpointService endpoints;
  private final EndpointConnections wiring;
  private final ConnectionService connections;
  private final ConnectorRegistry registry;
  private final EventBus events;
  private final Masker masker;
  private final Clock clock;
  private final TransactionTemplate writeTx;
  private final ConnectionImportFiles files;

  public ConnectionImportService(EndpointService endpoints, EndpointConnections wiring, ConnectionService connections,
      ConnectorRegistry registry, EventBus events, Masker masker, Clock clock, PlatformTransactionManager transactions,
      ConnectionImportFiles files) {
    this.endpoints = endpoints;
    this.wiring = wiring;
    this.connections = connections;
    this.registry = registry;
    this.events = events;
    this.masker = masker;
    this.clock = clock;
    this.writeTx = new TransactionTemplate(transactions);
    this.files = files;
  }

  public synchronized Session start(String repository, String endpoint) {
    if (repository == null || repository.isBlank() || repository.length() > 2048 || controls(repository)) {
      throw invalid("Choose a repository folder or enter its path.", "/repository");
    }
    checkEndpoint(endpoint);
    EndpointSnapshot source = endpoints.snapshot(endpoint);
    if (source.blocked() || !source.enabled()) {
      throw invalid("Choose an enabled, verified endpoint for the AI app.", "/endpoint");
    }
    expire();
    if (sessions.size() >= MAX_SESSIONS) {
      throw ProblemException.conflict("Too many imports", "Cancel an import or wait for an existing session to expire.");
    }
    Pending pending = new Pending(repository.strip(), source, clock.instant().plus(LIFETIME), files);
    sessions.put(pending.id, pending);
    return pending.view();
  }

  public Session get(String id) {
    Pending pending = pending(id);
    synchronized (pending) { requireActive(pending); return pending.view(); }
  }

  /** Cancelling drops only discovery data, never connections or endpoint settings. */
  public void cancel(String id) {
    Pending pending = sessions.get(id);
    if (pending == null) return;
    synchronized (pending) { discard(pending); }
  }

  public ObjectNode schema(String id, String token, String endpointId, String type) {
    Pending pending = authorize(id, token, endpointId);
    synchronized (pending) {
      requireActive(pending);
      ObjectNode out = JsonNodeFactory.instance.objectNode().put("version", 1);
      out.put("maxConnections", MAX_CONNECTIONS).put("maxBytes", MAX_BYTES);
      if (type == null || type.isBlank()) {
        ArrayNode types = out.putArray("types");
        for (ConnectorType connector : registry.types()) {
          types.add(descriptor(connector, false));
        }
      } else {
        ConnectorType connector = registry.type(type).orElseThrow(() -> invalid("Unknown connector type.", "/type"));
        out.setAll(descriptor(connector, true));
      }
      return out;
    }
  }

  /** Submit once, with an idempotent retry. External credential sources are never resolved during submission. */
  public ObjectNode submit(String id, String token, String endpointId, String principal, JsonNode manifest) {
    Pending pending = authorize(id, token, endpointId);
    synchronized (pending) {
      requireActive(pending);
      // A file submission can be retried after its plaintext file was consumed, but only by its original caller.
      if (manifest == null && "review".equals(pending.status) && principal.equals(pending.principal)) {
        return submissionResult(pending);
      }
      if (!"awaiting".equals(pending.status) && (manifest == null || !principal.equals(pending.principal))) {
        throw ProblemException.conflict("Import already submitted", "Start a new import to submit different results.");
      }
      if (manifest != null && pending.prepared != null) {
        throw ProblemException.conflict("Import already submitted", "Retry the same file submission without an inline manifest.");
      }
      ConnectionImportFiles.Prepared prepared = manifest == null ? files.read(id) : null;
      ObjectNode normalized;
      String digest;
      try {
        JsonNode raw = prepared == null ? manifest : prepared.manifest();
        if (raw == null || !raw.isObject()) throw invalid("Provide an import manifest object.", "/manifest");
        ObjectNode toValidate = (ObjectNode) raw.deepCopy();
        Map<String, JsonNode> sources = new LinkedHashMap<>();
        if (prepared != null) {
          for (JsonNode candidate : toValidate.path("connections")) {
            if (candidate.isObject() && candidate.has("secretSources")) {
              sources.put(candidate.path("id").asString(), ((ObjectNode) candidate).remove("secretSources"));
            }
          }
        }
        normalized = validateManifest(toValidate);
        for (JsonNode candidate : normalized.path("connections")) {
          if (prepared != null) {
            ConnectorManifest connector = registry.manifest(candidate.path("type").asString()).orElse(null);
            List<ConnectionForms.SecretField> allowed = connector == null ? List.of()
                : ConnectionForms.secretFields(connector.configSchema());
            for (String field : prepared.secrets().getOrDefault(candidate.path("id").asString(), Map.of()).keySet()) {
              if (allowed.stream().noneMatch(s -> pointerMatches(s.pointer(), field))) {
                throw invalid("A staged credential does not match a connector secret field.", "/manifest");
              }
            }
          }
          JsonNode secretSources = sources.get(candidate.path("id").asString());
          if (secretSources != null) ((ObjectNode) candidate).set("secretSources", secretSources);
        }
        digest = CanonicalJson.sha256Hex(toValidate);
      } catch (RuntimeException e) {
        if (prepared != null) prepared.close();
        throw e;
      }
      if (!"awaiting".equals(pending.status)) {
        if (!"review".equals(pending.status) || !digest.equals(pending.digest) || !principal.equals(pending.principal)) {
          throw ProblemException.conflict("Import already submitted", "Start a new import to submit different results.");
        }
        return submissionResult(pending);
      }
      pending.manifest = normalized;
      pending.prepared = prepared;
      pending.digest = digest;
      pending.principal = principal;
      pending.status = "review";
      show(pending);
      return submissionResult(pending);
    }
  }

  private static ObjectNode submissionResult(Pending pending) {
    return JsonNodeFactory.instance.objectNode().put("sessionId", pending.id).put("status", "review")
        .put("count", pending.manifest.path("connections").size())
        .put("reviewUrl", "gaterook://connection-import/" + pending.id)
        .put("message", "Discovery is ready for review in GateRook. Nothing has been imported. The user chooses connections and the destination endpoint.");
  }

  /** Navigation only: a card cannot import, select, modify or delete connections or settings. */
  public ObjectNode open(String id, String endpointId, String principal) {
    Pending pending = pending(id);
    synchronized (pending) {
      requireActive(pending);
      if (!pending.endpointId.equals(endpointId) || !principal.equals(pending.principal)
          || !"review".equals(pending.status)) throw unknown();
      show(pending);
      return submissionResult(pending);
    }
  }

  private void show(Pending pending) {
    if (pending.lastOpened.plusSeconds(3).isAfter(clock.instant())) return;
    pending.lastOpened = clock.instant();
    events.publish("ui.open", JsonNodeFactory.instance.objectNode().put("path", "/connection-import/" + pending.id));
  }

  public Imported commit(String id, String endpoint, List<Selection> selections, String reason) {
    Pending pending = pending(id);
    try {
      synchronized (pending) {
        requireActive(pending);
        if (!"review".equals(pending.status)) {
          throw ProblemException.conflict("Import not ready", "Submit discovery results and review them before importing.");
        }
        if (selections == null || selections.isEmpty() || selections.size() > MAX_CONNECTIONS) {
          throw invalid("Choose between 1 and 50 connections.", "/selections");
        }
        checkEndpoint(endpoint);
        EndpointSnapshot destination = endpoints.snapshot(endpoint);
        if (destination.blocked() || !destination.enabled()) {
          throw invalid("Choose an enabled, verified destination endpoint.", "/endpoint");
        }
        Map<String, JsonNode> candidates = new LinkedHashMap<>();
        pending.manifest.path("connections").forEach(c -> candidates.put(c.path("id").asString(), c));
        Set<String> selected = new HashSet<>();
        Set<String> names = new HashSet<>();
        List<Selection> reviewed = new ArrayList<>();
        Map<String, List<String>> incomplete = new LinkedHashMap<>();
        Map<String, ArrayNode> pendingSources = new LinkedHashMap<>();
        try {
          for (int index = 0; index < selections.size(); index++) {
            Selection selection = selections.get(index);
            if (selection == null || !selected.add(selection.candidateId()) || !names.add(selection.name())) {
              throw invalid("Each candidate and connection name must be selected only once.", "/selections");
            }
            JsonNode candidate = candidates.get(selection.candidateId());
            if (candidate == null) throw invalid("Choose a candidate from this import.", "/selections");
            String type = candidate.path("type").asString();
            if (!supported(type)) throw invalid("Use the dedicated setup flow for this connector.", "/selections");
            if ("prod".equals(candidate.path("env").asString()) && !"prod".equals(selection.env())) {
              throw invalid("Production discoveries must retain the production environment.", "/selections/" + index + "/env");
            }
            Map<String, SecretInput> merged = new LinkedHashMap<>();
            List<String> missing = new ArrayList<>();
            ArrayNode sources = JsonNodeFactory.instance.arrayNode();
            try {
              if (selection.secrets() != null) merged.putAll(selection.secrets());
              if (pending.prepared != null) {
                for (var entry : pending.prepared.secrets().getOrDefault(selection.candidateId(), Map.of()).entrySet()) {
                  if (!merged.containsKey(entry.getKey())) {
                    try { merged.put(entry.getKey(), entry.getValue().resolve()); }
                    catch (ProblemException e) {
                      if (!selection.allowIncomplete()) throw e;
                      sources.add(entry.getValue().summary(entry.getKey()));
                      missing.add(entry.getKey() + ": Review the unavailable credential source.");
                    }
                  }
                }
              }
              for (var entry : new ArrayList<>(merged.entrySet())) {
                SecretInput input = entry.getValue();
                if (input == null) throw invalid("Choose a credential source.", "/selections/" + index + "/secrets");
                if (input.isLocal() ? input.value().length == 0 : !connections.referenceConnected(input)) {
                  if (!selection.allowIncomplete()) throw invalid("Configure the missing credential source or keep available settings for later.", "/selections/" + index + "/secrets");
                  sources.add(sourceSummary(entry.getKey(), input));
                  missing.add(entry.getKey() + ": Configure the credential source.");
                  merged.remove(entry.getKey());
                  input.clear();
                }
              }
            } catch (RuntimeException e) {
              merged.values().forEach(SecretInput::clear);
              throw e;
            }
            JsonNode available = selection.allowIncomplete() ? ConnectionService.availableImportConfig(selection.config()) : selection.config();
            Selection ready = new Selection(selection.candidateId(), selection.name(), selection.env(), available, merged, selection.confirm(), selection.allowIncomplete());
            reviewed.add(ready);
            // Run production credential/privilege checks before opening the batch transaction.
            try {
              if (selection.allowIncomplete()) {
                missing.addAll(connections.missingImportSettings(selection.name(), type, env(selection.env()), available, merged));
              }
              if (missing.isEmpty()) connections.preflightCreate(selection.name(), type, env(selection.env()), available,
                  merged, selection.confirm());
              else {
                for (JsonNode hint : candidate.path("credentialHints")) {
                  String field = hint.path("field").asString();
                  boolean already = java.util.stream.StreamSupport.stream(sources.spliterator(), false)
                      .anyMatch(source -> field.equals(source.path("field").asString()));
                  if (!merged.containsKey(field) && !already) sources.add(hint.deepCopy());
                }
                incomplete.put(selection.candidateId(), List.copyOf(missing));
                pendingSources.put(selection.candidateId(), sources);
              }
            } catch (ProblemException e) {
              throw new ProblemException(e.kind(), e.title(), e.getMessage(),
                  "/selections/" + index + (e.field() == null ? "" : e.field()), e.confirm());
            }
          }
          List<String> imported = writeTx.execute(status -> {
            List<String> created = new ArrayList<>();
            for (Selection selection : reviewed) {
              String type = candidates.get(selection.candidateId()).path("type").asString();
              // preflightCreate above checked the exact reviewed input; avoid repeating resource I/O in this transaction.
              ConnectionService.Summary connection = incomplete.containsKey(selection.candidateId())
                  ? connections.createImportDraft(selection.name(), type, env(selection.env()), selection.config(),
                      selection.secrets(), incomplete.get(selection.candidateId()), pendingSources.get(selection.candidateId()), reason)
                  : connections.create(selection.name(), type, env(selection.env()), selection.config(), selection.secrets(),
                      reason, ConnectionService.CONFIRM_DANGER);
              wiring.add(endpoint, connection.name(), reason);
              created.add(connection.name());
            }
            return List.copyOf(created);
          });
          pending.status = "imported";
          if (pending.prepared != null) { pending.prepared.close(); pending.prepared = null; }
          files.delete(pending.id);
          return new Imported(imported, endpoint, pending.status, reviewed.stream()
              .filter(s -> incomplete.containsKey(s.candidateId()))
              .map(s -> new NeedsSetup(s.name(), incomplete.get(s.candidateId()))).toList());
        } finally {
          reviewed.forEach(s -> s.secrets().values().forEach(SecretInput::clear));
        }
      }
    } finally {
      if (selections != null) selections.stream().filter(s -> s != null && s.secrets() != null)
          .forEach(s -> s.secrets().values().forEach(SecretInput::clear));
    }
  }

  private Pending pending(String id) {
    expire();
    Pending pending = Ids.matches("ci_", id) ? sessions.get(id) : null;
    if (pending == null) throw unknown();
    return pending;
  }

  private static ObjectNode sourceSummary(String field, SecretInput input) {
    ObjectNode out = JsonNodeFactory.instance.objectNode().put("field", field)
        .put("source", input.isLocal() ? "local" : input.dynamic() ? "vault-dynamic" : "reference");
    if (!input.isLocal()) {
      out.put("reference", input.reference());
      if (input.store() != null) out.put("store", input.store());
    }
    return out;
  }

  private Pending authorize(String id, String token, String endpointId) {
    Pending pending = pending(id);
    if (token == null || !pending.endpointId.equals(endpointId) || !MessageDigest.isEqual(
        pending.token.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) throw unknown();
    return pending;
  }

  private void expire() {
    Instant now = clock.instant();
    for (Pending pending : sessions.values()) {
      if (!pending.expiresAt.isAfter(now)) synchronized (pending) { discard(pending); }
    }
  }

  @Scheduled(fixedDelay = 60_000)
  public void expireSessions() { expire(); }

  @jakarta.annotation.PreDestroy
  public void close() {
    for (Pending pending : sessions.values()) synchronized (pending) { discard(pending); }
  }

  private void requireActive(Pending pending) {
    if (sessions.get(pending.id) != pending || !pending.expiresAt.isAfter(clock.instant())) throw unknown();
  }

  private void discard(Pending pending) {
    sessions.remove(pending.id, pending);
    if (pending.prepared != null) { pending.prepared.close(); pending.prepared = null; }
    files.delete(pending.id);
  }

  private static ProblemException unknown() {
    return ProblemException.notFound("Import unavailable", "This import is unavailable or expired. Start it in GateRook on this endpoint.");
  }

  private ObjectNode descriptor(ConnectorType connector, boolean schema) {
    ObjectNode out = JsonNodeFactory.instance.objectNode().put("type", connector.id())
        .put("name", connector.manifest().name()).put("supported", supported(connector.id()));
    if (!supported(connector.id())) out.put("reason", unsupportedReason(connector.id()));
    if (schema) {
      out.set("configSchema", connector.manifest().configSchema().deepCopy());
      ArrayNode fields = out.putArray("secretFields");
      ConnectionForms.secretFields(connector.manifest().configSchema()).forEach(f -> fields.add(f.pointer()));
    }
    return out;
  }

  private boolean supported(String type) {
    return !SPECIALIZED.contains(type) && registry.type(type).filter(ConnectorType::usable)
        .filter(t -> t.kind() == ConnectorManifest.Kind.BUILT_IN).isPresent();
  }

  private String unsupportedReason(String type) {
    if ("http".equals(type)) return "Use Import HTTP API to review authentication and operations.";
    if ("mcp-server".equals(type)) return "Use Add MCP server to review execution, authentication and tools.";
    return "This connector is unavailable for repository import. Configure it through its dedicated setup flow.";
  }

  private ObjectNode validateManifest(JsonNode manifest) {
    if (manifest == null || !manifest.isObject()) throw invalid("Provide an import manifest object.", "/manifest");
    bounded(manifest, 0, new int[] {0});
    if (manifest.toString().getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
      throw invalid("The discovery manifest must be at most 128 KiB.", "/manifest");
    }
    fields(manifest, Set.of("version", "connections", "warnings"));
    if (!manifest.path("version").isInt() || manifest.path("version").asInt() != 1) {
      throw invalid("Use import manifest version 1.", "/manifest/version");
    }
    JsonNode connections = manifest.path("connections");
    if (!connections.isArray() || connections.size() > MAX_CONNECTIONS) {
      throw invalid("Provide an array of at most 50 discovered connections.", "/manifest/connections");
    }
    ObjectNode normalized = (ObjectNode) manifest.deepCopy();
    normalized.set("warnings", texts(manifest.path("warnings"), 30, 512));
    ArrayNode out = normalized.putArray("connections");
    Set<String> ids = new HashSet<>();
    for (JsonNode candidate : connections) {
      fields(candidate, CANDIDATE_FIELDS);
      String id = text(candidate.path("id"), 64);
      if (!id.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}") || !ids.add(id)) {
        throw invalid("Candidate IDs must be unique short identifiers.", "/manifest/connections");
      }
      String name = text(candidate.path("name"), 64);
      if (!ConnectionService.NAME.matcher(name).matches()) throw invalid("Use lowercase connection names with hyphens.", "/manifest/connections");
      String type = text(candidate.path("type"), 48);
      if (!type.matches("[a-z][a-z0-9-]{0,47}")) throw invalid("Invalid connector type identifier.", "/manifest/connections");
      env(text(candidate.path("env"), 5));
      JsonNode config = candidate.path("config");
      if (!config.isObject()) throw invalid("Each candidate needs a config object.", "/manifest/connections");
      ConnectorManifest connector = registry.manifest(type).orElse(null);
      if (connector == null && !config.isEmpty()) throw invalid("Unknown connector types must use an empty config.", "/manifest/connections");
      if (connector != null) {
        validateConfig(config, connector.configSchema(), connector.configSchema(), "", 0);
        for (ConnectionForms.SecretField field : ConnectionForms.secretFields(connector.configSchema())) {
          rejectSecretField(config, field.pointer().split("/", -1), 1);
        }
      }
      ObjectNode entry = (ObjectNode) candidate.deepCopy();
      entry.set("evidence", texts(candidate.path("evidence"), 20, 512));
      ArrayNode warnings = texts(candidate.path("warnings"), 20, 512);
      entry.set("warnings", warnings);
      entry.set("credentialHints", hints(candidate.path("credentialHints")));
      entry.put("supported", supported(type));
      if (!supported(type)) {
        entry.put("unsupportedReason", unsupportedReason(type));
        warnings.add(unsupportedReason(type));
      } else entry.putNull("unsupportedReason");
      out.add(entry);
    }
    return normalized;
  }

  private ArrayNode hints(JsonNode value) {
    ArrayNode out = JsonNodeFactory.instance.arrayNode();
    if (value.isMissingNode()) return out;
    if (!value.isArray() || value.size() > 20) throw invalid("Provide at most 20 credential source hints.", "/manifest");
    for (JsonNode hint : value) {
      fields(hint, Set.of("field", "source", "reference", "instructions"));
      String field = text(hint.path("field"), 200);
      if (!field.startsWith("/")) throw invalid("Credential hint fields use JSON Pointer paths.", "/manifest");
      text(hint.path("source"), 64);
      text(hint.path("instructions"), 512);
      if (hint.hasNonNull("reference")) {
        String ref = text(hint.path("reference"), 1024);
        if (!ref.matches("(?:vault|gcp-sm|aws-sm)://[^\\s?@]+")) {
          throw invalid("Secret references must name Vault, GCP Secret Manager or AWS Secrets Manager without credentials.", "/manifest");
        }
      }
      out.add(hint.deepCopy());
    }
    return out;
  }

  private ArrayNode texts(JsonNode values, int max, int chars) {
    ArrayNode out = JsonNodeFactory.instance.arrayNode();
    if (values.isMissingNode()) return out;
    if (!values.isArray() || values.size() > max) throw invalid("Too many discovery notes.", "/manifest");
    for (JsonNode value : values) out.add(text(value, chars));
    return out;
  }

  private String text(JsonNode value, int max) {
    if (!value.isString() || value.asString().isBlank() || value.asString().length() > max || controls(value.asString())) {
      throw invalid("Discovery text is missing, too long or contains control characters.", "/manifest");
    }
    secretText(value.asString());
    return value.asString();
  }

  private void secretText(String value) {
    if (masker.looksLikeSecret(value, false).isPresent()
        || value.matches("(?is).*\\b[a-z][a-z0-9+.-]*://[^/\\s]*@.*")
        || value.matches("(?is).*[(?&;](?:password|passwd|pwd|token|api[-_]?key|secret|authorization)=.+")) {
      throw invalid("Remove credential values and authenticated URLs. Describe their source using credentialHints.", "/manifest");
    }
  }

  private static boolean controls(String text) { return text.codePoints().anyMatch(c -> c < 32 || c == 127); }

  private static void fields(JsonNode value, Set<String> allowed) {
    if (!value.isObject() || value.propertyNames().stream().anyMatch(k -> !allowed.contains(k))) {
      throw invalid("The discovery manifest contains unsupported fields.", "/manifest");
    }
  }

  private void bounded(JsonNode value, int depth, int[] count) {
    if (depth > 16 || ++count[0] > 10_000) throw invalid("The discovery manifest is too complex.", "/manifest");
    if (value.isString()) {
      if (value.asString().length() > 4096 || controls(value.asString())) throw invalid("A discovery value is too long or invalid.", "/manifest");
      secretText(value.asString());
    } else if (value.isObject()) {
      for (Map.Entry<String, JsonNode> property : value.properties()) {
        if (property.getKey().length() > 128 || controls(property.getKey()) || "$secret".equals(property.getKey())) {
          throw invalid("Stored secret identifiers and invalid property names cannot be imported.", "/manifest");
        }
        bounded(property.getValue(), depth + 1, count);
      }
    } else if (value.isArray()) for (JsonNode child : value) bounded(child, depth + 1, count);
  }

  /** Check allowed property paths while leaving incomplete values for the person's ordinary connection form. */
  private void validateConfig(JsonNode value, JsonNode schema, JsonNode root, String path, int depth) {
    if (depth > 24) throw invalid("Connector config is too complex.", "/manifest");
    if (value.isObject()) {
      for (Map.Entry<String, JsonNode> property : value.properties()) {
        List<JsonNode> definitions = propertySchemas(schema, root, property.getKey(), new HashSet<>(), 0);
        if (definitions.isEmpty()) throw invalid("Config contains a field absent from the connector schema. Request connection_import_schema first.", "/manifest");
        if (definitions.stream().anyMatch(s -> s.path("writeOnly").asBoolean(false))) {
          throw invalid("Do not include credential fields in config. Use credentialHints.", "/manifest");
        }
        ObjectNode union = JsonNodeFactory.instance.objectNode();
        union.putArray("anyOf").addAll(definitions);
        validateConfig(property.getValue(), union, root, path + "/" + property.getKey(), depth + 1);
      }
    } else if (value.isArray()) {
      List<JsonNode> items = itemSchemas(schema, root, new HashSet<>(), 0);
      if (items.isEmpty() && !value.isEmpty()) throw invalid("This config field does not accept array values.", "/manifest");
      ObjectNode union = JsonNodeFactory.instance.objectNode();
      union.putArray("anyOf").addAll(items);
      for (JsonNode child : value) validateConfig(child, union, root, path + "/*", depth + 1);
    }
  }

  private static List<JsonNode> propertySchemas(JsonNode schema, JsonNode root, String key, Set<String> refs, int depth) {
    List<JsonNode> out = new ArrayList<>();
    if (depth > 24) return out;
    JsonNode direct = schema.path("properties").get(key);
    if (direct != null) out.add(direct);
    schemaBranches(schema, root, refs, depth).forEach(s -> out.addAll(propertySchemas(s, root, key, new HashSet<>(refs), depth + 1)));
    return out;
  }

  private static List<JsonNode> itemSchemas(JsonNode schema, JsonNode root, Set<String> refs, int depth) {
    List<JsonNode> out = new ArrayList<>();
    if (depth > 24) return out;
    if (schema.path("items").isObject()) out.add(schema.path("items"));
    schemaBranches(schema, root, refs, depth).forEach(s -> out.addAll(itemSchemas(s, root, new HashSet<>(refs), depth + 1)));
    return out;
  }

  private static List<JsonNode> schemaBranches(JsonNode schema, JsonNode root, Set<String> refs, int depth) {
    List<JsonNode> out = new ArrayList<>();
    if (depth > 24) return out;
    String ref = schema.path("$ref").asString("");
    if (ref.startsWith("#/") && refs.add(ref)) out.add(root.at(ref.substring(1)));
    for (String keyword : List.of("oneOf", "anyOf", "allOf")) schema.path(keyword).forEach(out::add);
    for (String keyword : List.of("then", "else")) if (schema.path(keyword).isObject()) out.add(schema.path(keyword));
    return out;
  }

  private static void rejectSecretField(JsonNode value, String[] parts, int index) {
    if (index == parts.length) {
      if (!value.isMissingNode()) throw invalid("Credential fields belong in credentialHints, never config.", "/manifest");
      return;
    }
    String part = parts[index].replace("~1", "/").replace("~0", "~");
    if ("*".equals(part)) value.forEach(child -> rejectSecretField(child, parts, index + 1));
    else rejectSecretField(value.path(part), parts, index + 1);
  }

  private static boolean pointerMatches(String template, String field) {
    String[] expected = template.split("/", -1);
    String[] actual = field.split("/", -1);
    if (expected.length != actual.length) return false;
    for (int i = 0; i < expected.length; i++) {
      if (!expected[i].equals(actual[i]) && !("*".equals(expected[i]) && actual[i].matches("0|[1-9][0-9]*"))) return false;
    }
    return true;
  }

  private static Env env(String value) {
    try { return Env.of(value); }
    catch (RuntimeException e) { throw invalid("Choose dev, stage or prod explicitly.", "/env"); }
  }

  private static void checkEndpoint(String endpoint) {
    if (endpoint == null || !EndpointService.SLUG.matcher(endpoint).matches()) {
      throw invalid("Choose an existing endpoint.", "/endpoint");
    }
  }

  private static ProblemException invalid(String message, String field) {
    return ProblemException.unprocessable("Check connection import", message, field);
  }
}
