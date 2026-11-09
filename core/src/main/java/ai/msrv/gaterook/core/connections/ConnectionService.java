package ai.msrv.gaterook.core.connections;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.core.connections.internal.ConnectionRows;
import ai.msrv.gaterook.core.connections.internal.ConnectionTargets;
import ai.msrv.gaterook.core.connections.internal.EnvLocks;
import ai.msrv.gaterook.core.connections.internal.PartialConnectionSchema;
import ai.msrv.gaterook.core.connections.internal.SchemaPointers;
import ai.msrv.gaterook.core.connections.internal.SecretFields;
import ai.msrv.gaterook.core.connections.internal.persistence.ConnectionEntity;
import ai.msrv.gaterook.core.connections.internal.persistence.ConnectionRepository;
import ai.msrv.gaterook.core.connectors.ConnectorRegistry;
import ai.msrv.gaterook.core.connectors.ConnectorType;
import ai.msrv.gaterook.core.connectors.EnvironmentPolicies;
import ai.msrv.gaterook.core.platform.IntegrityAlerts;
import ai.msrv.gaterook.core.platform.Masker;
import ai.msrv.gaterook.core.platform.ProblemException;
import ai.msrv.gaterook.core.platform.SchemaValidator;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder.SetupChange;
import ai.msrv.gaterook.core.secrets.SecretInfo;
import ai.msrv.gaterook.core.secrets.SecretOwner;
import ai.msrv.gaterook.core.secrets.SecretService;
import ai.msrv.gaterook.core.secrets.SecretVersionChanged;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

/**
 * Connections (ARCHITECTURE.md §2.2 {@code connections}, §6 connections and connection-new): create, edit, duplicate,
 * delete. The config is validated against the type's {@code configSchema} (networknt) and the locks the schema puts
 * on its environment ({@code x-forbidden-env-values}, {@code x-forbidden-env}); secret fields never sit in it —
 * their values go to the secrets module and the config keeps {@code {"$secret": "<id>"}}. A value that looks like a
 * secret in a non-secret field is refused ("store it as a secret"). Every change is a new signed version, a Setup
 * change and a {@link ConnectionChanged}; changing where the connection connects while secrets are stored needs the
 * person's confirmation ({@code send-secret-to-new-host}).
 */
@Service
public class ConnectionService {

  /** {@code dev-db}: lower-case letters, digits and hyphens. */
  public static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$");

  /** "PostgreSQL 16.4 (Debian 16.4-1)": the product (if any) and the major version of what a test reported. */
  private static final Pattern SERVER_VERSION = Pattern.compile("^(?:(.*\\S)\\s+)?v?(\\d+)[.\\d]*(?:\\s.*)?$");

  /**
   * The longest server version kept and shown ({@code metadata.serverVersion}): product and version, the start of a
   * banner — the Type column, Overview and the descriptions agents read of the built-in tools.
   */
  static final int VERSION_CHARS = 80;

  /** The confirmation an edit needs when it moves stored secrets to another host. */
  public static final String CONFIRM_NEW_HOST = "send-secret-to-new-host";

  /**
   * The confirmation saving a prod connection needs when its test finds rights that can do harm beyond the agent's
   * tools (ARCHITECTURE.md §9 "SQL reads cannot write": a superuser, {@code FILE}, {@code SUPER}; a DANGER hint).
   * Confirmations accumulate in one value, separated by commas: {@code send-secret-to-new-host,danger}.
   */
  public static final String CONFIRM_DANGER = "danger";

  private static final List<String> SETUP_METADATA = List.of("setupRequired", "setupMissingFields", "setupSources");

  public static boolean needsSetup(ConnectionConfig config) {
    return config.metadata() != null && config.metadata().path("setupRequired").asBoolean(false);
  }

  public boolean referenceConnected(SecretInput input) {
    return input.isLocal() || secrets.referenceConnected(input.reference(), input.dynamic(), input.store());
  }

  public static ObjectNode availableImportConfig(JsonNode config) {
    return PartialConnectionSchema.available(requestConfig(config));
  }

  /**
   * One row of Connections.
   *
   * @param target        where it connects, in the list's words ({@link ConnectionTargets#display})
   * @param typeLabel     the Type column of the kit: the form variant ("Loki", "GCP Cloud Logging"), for a database
   *                      the server its last successful test saw ("PostgreSQL 16"), else the connector's name
   * @param variant       the {@code x-variants} id of the form it was made with ({@code loki}), or {@code null}
   * @param serverVersion what the last successful test called the server ({@code metadata.serverVersion}:
   *                      "PostgreSQL 16", "Loki 3.1.0", "Kubernetes v1.31.2"), or {@code null} before one
   */
  public record Summary(String id, String name, String type, String typeName, Env env, String target,
      String secretSource, ConnectionConfigs.Health health, List<String> usedBy, boolean verified, long version,
      String typeLabel, String variant, String serverVersion, boolean enabled) {}

  /** One stored secret field of a connection, without its value. */
  public record SecretField(String field, String secretId, String name, String state, Integer version,
      String source) {}

  /** The edit view: config without secrets, which secrets are stored, "Used by". */
  public record Detail(String id, String name, String type, Env env, JsonNode config, long version,
      List<SecretField> secrets, ConnectionConfigs.Health health, List<ConnectionUsage.UsedBy> usedBy,
      boolean verified) {}

  /** {@code ?duplicate=}: settings to start from, never credentials. */
  public record Draft(String type, Env env, JsonNode config) {}

  /** What a deletion stopped, rejected and destroyed; the audit stays. */
  public record DeleteSummary(List<String> stoppedTools, int rejectedApprovals, List<String> templates,
      List<String> clients, List<String> secretsDestroyed) {}

  private final ConnectionRepository repository;
  private final ConnectionRows rows;
  private final ConnectionConfigs configs;
  private final ConnectorRegistry registry;
  private final SecretService secrets;
  private final SchemaValidator validator;
  private final Masker masker;
  private final IntegrityAlerts alerts;
  private final ObjectProvider<ConnectionUsage> usage;
  private final ObjectProvider<ConnectionRemovalParticipant> participants;
  private final ObjectProvider<ConnectionRenameParticipant> renameParticipants;
  private final ObjectProvider<EnvironmentPolicies> policies;
  private final ObjectProvider<SetupChangeRecorder> setupChanges;
  private final ObjectProvider<ConnectionTester> tester;
  private final ApplicationEventPublisher publisher;
  private final Clock clock;
  private final TransactionTemplate writeTx;

  public ConnectionService(ConnectionRepository repository, ConnectionRows rows, ConnectionConfigs configs,
      ConnectorRegistry registry, SecretService secrets, SchemaValidator validator, Masker masker,
      IntegrityAlerts alerts, ObjectProvider<ConnectionUsage> usage,
      ObjectProvider<ConnectionRemovalParticipant> participants,
      ObjectProvider<ConnectionRenameParticipant> renameParticipants, ObjectProvider<EnvironmentPolicies> policies,
      ObjectProvider<SetupChangeRecorder> setupChanges, ObjectProvider<ConnectionTester> tester,
      ApplicationEventPublisher publisher, Clock clock, PlatformTransactionManager transactions) {
    this.repository = repository;
    this.rows = rows;
    this.configs = configs;
    this.registry = registry;
    this.secrets = secrets;
    this.validator = validator;
    this.masker = masker;
    this.alerts = alerts;
    this.usage = usage;
    this.participants = participants;
    this.renameParticipants = renameParticipants;
    this.policies = policies;
    this.setupChanges = setupChanges;
    this.tester = tester;
    this.publisher = publisher;
    this.clock = clock;
    this.writeTx = new TransactionTemplate(transactions);
  }

  // ── Reading ─────────────────────────────────────────────────────────────────────────────────────────────────

  public List<Summary> list() {
    List<Summary> out = new ArrayList<>();
    for (ConnectionConfigs.Stored s : configs.all()) {
      out.add(summary(s));
    }
    return out;
  }

  public Summary summary(String name) {
    return summary(stored(name));
  }

  public Detail get(String name) {
    ConnectionConfigs.Stored s = stored(name);
    List<SecretField> fields = new ArrayList<>();
    Map<String, SecretInfo> byId = new LinkedHashMap<>();
    secrets.listForOwner(SecretOwner.connection(s.id())).forEach(i -> byId.put(i.id(), i));
    s.secretIds().forEach((field, id) -> {
      SecretInfo info = byId.get(id);
      fields.add(info == null ? new SecretField(field, id, null, "missing", null, null)
          : new SecretField(field, id, info.name(), info.state(), info.version(),
              info.dynamicReference() ? "vault-dynamic" : info.source()));
    });
    return new Detail(s.id(), s.name(), s.config().type(), s.config().env(), s.config().config().deepCopy(),
        s.config().version(), List.copyOf(fields), s.health(), usage().map(u -> u.usedBy(s.id())).orElse(List.of()),
        s.verified());
  }

  /** {@code GET /api/connections/{name}/duplicate}: type, env and config without any credential. */
  public Draft duplicate(String name) {
    ConnectionConfigs.Stored s = stored(name);
    return new Draft(s.config().type(), s.config().env(), s.config().config().deepCopy());
  }

  // ── Create ──────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * {@code POST /api/connections}. The values in {@code newSecrets} are encrypted under the new connection and zeroed.
   *
   * @throws ProblemException 422 for an invalid name, type, config or secret; 409 for a name in use
   */
  public Summary create(String name, String type, Env env, JsonNode config, Map<String, SecretInput> newSecrets,
      String reason) {
    return create(name, type, env, config, newSecrets, reason, null);
  }

  /**
   * Validate the exact reviewed input and check production rights before a caller opens a batch transaction.
   * No connection is created and caller-owned credentials remain available for the subsequent create call.
   */
  public void preflightCreate(String name, String type, Env env, JsonNode config,
      Map<String, SecretInput> newSecrets, String confirm) {
    Objects.requireNonNull(env, "env");
    checkName(name);
    ConnectorManifest manifest = usableType(type);
    ObjectNode cleaned = requestConfig(config);
    Map<String, SecretInput> given = checkSecrets(manifest.configSchema(), SecretFields.templates(
        manifest.configSchema()), newSecrets);
    Set<String> dynamic = new LinkedHashSet<>();
    given.forEach((field, input) -> { if (!input.isLocal() && input.dynamic()) dynamic.add(field); });
    validate(manifest, cleaned, given.keySet(), dynamic, env, false);
    if (repository.existsByName(name)) {
      throw ProblemException.conflict("Name in use", "A connection named " + name + " already exists.");
    }
    if (env == Env.PROD && !confirmed(confirm, CONFIRM_DANGER)) {
      Map<String, SecretInput> copies = new HashMap<>();
      given.forEach((field, input) -> copies.put(field, input.isLocal() ? SecretInput.local(input.value().clone()) : input));
      try {
        requireNoDanger(name, tester.getObject().testDraft(type, env, cleaned, copies, null, null).result());
      } finally { copies.values().forEach(SecretInput::clear); }
    }
  }

  /**
   * @param confirm {@link #CONFIRM_DANGER} when the person agreed to keep a prod connection whose test found rights
   *     that can do harm; without it such a save is a 409 that names them
   */
  public Summary create(String name, String type, Env env, JsonNode config, Map<String, SecretInput> newSecrets,
      String reason, String confirm) {
    try {
      Objects.requireNonNull(env, "env");
      checkName(name);
      ConnectorManifest manifest = usableType(type);
      ObjectNode cleaned = requestConfig(config);
      List<String> templates = SecretFields.templates(manifest.configSchema());
      Map<String, SecretInput> given = checkSecrets(manifest.configSchema(), templates, newSecrets);
      Set<String> dynamic = new LinkedHashSet<>();
      given.forEach((field, input) -> { if (!input.isLocal() && input.dynamic()) dynamic.add(field); });
      validate(manifest, cleaned, given.keySet(), dynamic, env, false);
      if (env == Env.PROD && !confirmed(confirm, CONFIRM_DANGER)) {
        // The test gets copies: it zeroes what it is given, and these are about to be stored.
        Map<String, SecretInput> copies = new HashMap<>();
        given.forEach((field, input) -> copies.put(field, input.isLocal() ? SecretInput.local(input.value().clone())
            : input));
        requireNoDanger(name, tester.getObject().testDraft(type, env, cleaned, copies, null, null).result());
      }
      return persistCreation(name, type, env, cleaned, withStores(given), manifest, reason, null);
    } finally {
      if (newSecrets != null) {
        newSecrets.values().forEach(SecretInput::clear);
      }
    }
  }

  private Summary persistCreation(String name, String type, Env env, ObjectNode cleaned,
      Map<String, SecretInput> given, ConnectorManifest manifest, String reason, JsonNode setup) {
    String id = writeTx.execute(s -> {
      if (repository.existsByName(name)) {
        throw ProblemException.conflict("Name in use", "A connection named " + name + " already exists.");
      }
      ConnectionEntity row = repository.saveAndFlush(new ConnectionEntity(name, type, env, rows.map(cleaned),
          clock.instant()));
      ObjectNode stored = cleaned.deepCopy();
      for (Map.Entry<String, SecretInput> secret : given.entrySet()) {
        SecretInput input = secret.getValue();
        String secretName = uniqueSecretName(name, secret.getKey(), cleaned);
        SecretInfo info = input.isLocal()
            ? secrets.createLocal(SecretOwner.connection(row.getId()), secret.getKey(), secretName, input.value())
            : secrets.createReference(SecretOwner.connection(row.getId()), secret.getKey(), secretName,
                input.reference(), input.dynamic(), input.store(), secretPointer(secret.getKey()));
        SecretFields.set(stored, secret.getKey(), SecretFields.placeholder(info.id()));
      }
      row.reconfigure(env, rows.map(stored), clock.instant());
      if (setup != null) {
        row.setMetadata(rows.map(setup));
        row.setEnabled(false, clock.instant());
      }
      row = repository.saveAndFlush(row);
      rows.sign(row);
      setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.CONNECTION,
          "Connection " + name + " added", null, manifest.name() + " · " + env.id(), reason, env, null, name,
          null));
      if (setup == null) publisher.publishEvent(new ConnectionConfigured(name, type, env, cleaned.deepCopy(), reason));
      publisher.publishEvent(new ConnectionChanged(row.getId(), name, env, row.getVersion()));
      publisher.publishEvent(new ConnectionCreated(row.getId()));
      return row.getId();
    });
    return summary(configs.byId(id).orElseThrow());
  }

  // ── Edit ────────────────────────────────────────────────────────────────────────────────────────────────────

  /** Validate available settings without testing a resource. Only missing required inputs may remain incomplete. */
  public List<String> missingImportSettings(String name, String type, Env env, JsonNode config,
      Map<String, SecretInput> newSecrets) {
    checkName(name);
    ConnectorManifest manifest = usableType(type);
    ObjectNode cleaned = PartialConnectionSchema.available(requestConfig(config));
    List<String> secretFields = SecretFields.templates(manifest.configSchema());
    for (String field : secretFields) {
      if (!SchemaPointers.values(cleaned, field).isEmpty()) {
        throw ProblemException.unprocessable("Use secret fields", "Credentials must be supplied separately from settings.", "/config");
      }
    }
    Map<String, SecretInput> given = checkSecrets(manifest.configSchema(), secretFields, newSecrets);
    Set<String> dynamic = new LinkedHashSet<>();
    given.forEach((field, input) -> { if (input.dynamic()) dynamic.add(field); });
    validate(manifest, cleaned, given.keySet(), dynamic, env, true, PartialConnectionSchema.relax(manifest.configSchema()));
    if (repository.existsByName(name)) throw ProblemException.conflict("Name in use", "A connection named " + name + " already exists.");
    try {
      validate(manifest, cleaned, given.keySet(), dynamic, env, false);
      return List.of();
    } catch (ProblemException e) {
      return List.of((e.field() == null ? "Settings" : e.field()) + ": " + e.getMessage());
    }
  }

  /** Persist reviewed available data, signed and disabled, without resource tests or usable endpoint tools. */
  public Summary createImportDraft(String name, String type, Env env, JsonNode config,
      Map<String, SecretInput> newSecrets, List<String> missing, JsonNode sources, String reason) {
    try {
      List<String> required = missingImportSettings(name, type, env, config, newSecrets);
      ConnectorManifest manifest = usableType(type);
      ObjectNode cleaned = PartialConnectionSchema.available(requestConfig(config));
      Map<String, SecretInput> given = checkSecrets(manifest.configSchema(), SecretFields.templates(
          manifest.configSchema()), newSecrets);
      ObjectNode setup = JsonNodeFactory.instance.objectNode().put("setupRequired", true);
      var fields = setup.putArray("setupMissingFields");
      java.util.stream.Stream.concat(required.stream(), missing.stream()).distinct().limit(50).forEach(fields::add);
      setup.set("setupSources", sources == null ? JsonNodeFactory.instance.arrayNode() : sources.deepCopy());
      return persistCreation(name, type, env, cleaned, withStores(given), manifest, reason, setup);
    } finally {
      if (newSecrets != null) newSecrets.values().forEach(SecretInput::clear);
    }
  }

  /**
   * {@code PUT /api/connections/{name}}: new config and env; secrets stay as stored ("Applies from the next call").
   *
   * @param confirm {@link #CONFIRM_NEW_HOST} when the person agreed to send the stored secrets to a new target
   * @throws ProblemException 409 {@code send-secret-to-new-host} when a target changes and secrets are stored
   */
  public Summary update(String name, Env env, JsonNode config, long version, String reason, String confirm) {
    return update(name, null, env, config, version, reason, confirm, Map.of());
  }

  /** Completing an imported draft can supply previously missing credentials in the same validated transaction. */
  public Summary update(String name, Env env, JsonNode config, long version, String reason, String confirm,
      Map<String, SecretInput> newSecrets) {
    return update(name, null, env, config, version, reason, confirm, newSecrets);
  }

  /**
   * The same edit, saved under {@code newName} when it is given and differs ({@code PUT /api/connections/{name}} with
   * {@code name}; ARCHITECTURE.md §8.6 contract 13). In the edit's transaction the row takes the new name and a new
   * signed version, and what refers to the connection by name follows it: its secrets' names, the policies' private
   * ranges allowed to it, the keys of an MCP server's tools on its endpoints with their modes, and the server's folder
   * and log ({@link ConnectionRenameParticipant}). What only records the past keeps the old name: the audit, approval
   * requests and Setup changes. Agents see the new name from their next tool list.
   *
   * @throws ProblemException 422 for an invalid name, 409 for a name in use
   */
  public Summary update(String name, String newName, Env env, JsonNode config, long version, String reason,
      String confirm, Map<String, SecretInput> newSecrets) {
    try {
      Objects.requireNonNull(env, "env");
      ConnectionConfigs.Stored current = stored(name);
      requireVerified(current);
      String target = newName == null || newName.equals(name) ? name : newName;
      boolean renaming = !target.equals(name);
      if (renaming) {
        checkName(target);
        if (repository.existsByName(target)) {
          throw nameInUse(target);
        }
      }
      ConnectorManifest manifest = usableType(current.config().type());
      boolean completing = needsSetup(current.config());
      // New secrets and text fields read from a store join an edit; a stored one changes through its Secrets control.
      Map<String, SecretInput> given = checkSecrets(manifest.configSchema(), SecretFields.templates(
          manifest.configSchema()), newSecrets);
      for (String field : given.keySet()) {
        if (current.secretIds().containsKey(field)) throw ProblemException.unprocessable("Use secret replacement", "Replace an existing credential through its Secrets control.", secretPointer(field));
      }
      ObjectNode cleaned = requestConfig(config);
      if (completing) {
        for (String field : SecretFields.templates(manifest.configSchema())) {
          if (!SchemaPointers.values(cleaned, field).isEmpty()) throw ProblemException.unprocessable("Use secret fields", "Credentials must be supplied separately from settings.", "/config");
        }
      }
      // The secrets stay: their placeholders move into the new config wherever their field still has a place. A text
      // field read from a store that is typed now keeps the typed value, and its reference goes.
      ObjectNode next = cleaned.deepCopy();
      Map<String, String> dropped = new LinkedHashMap<>();
      List<String> secretTemplates = SecretFields.templates(manifest.configSchema());
      List<String> texts = SecretFields.textTemplates(manifest.configSchema());
      current.secretIds().forEach((field, id) -> {
        JsonNode typed = cleaned.at(field);
        boolean retyped = !SecretFields.matches(secretTemplates, field) && SecretFields.matches(texts, field)
            && typed.isString() && !typed.asString().isBlank();
        if (retyped || !SecretFields.parentExists(next, field)
            || !SecretFields.set(next, field, SecretFields.placeholder(id))) {
          dropped.put(field, id);
        }
      });
      Set<String> dynamic = new LinkedHashSet<>(dynamicSecretFields(SecretFields.placeholders(next)));
      given.forEach((field, input) -> { if (input.dynamic()) dynamic.add(field); });
      validate(manifest, next, given.keySet(), dynamic, env, false);
      if (completing) {
        for (SecretInput input : given.values()) {
          if (!referenceConnected(input)) throw ProblemException.unprocessable("Connect the secret store", "Configure the credential source before finishing setup.", "/secrets");
        }
        for (var entry : current.secretIds().entrySet()) {
          if (dropped.containsKey(entry.getKey())) continue;
          SecretInfo info = secrets.get(entry.getValue());
          if (info.ref() != null && !secrets.referenceConnected(info.ref(), info.dynamicReference(), info.store())
              || info.ref() == null && !"stored".equals(info.state())) {
            throw ProblemException.unprocessable("Complete the credential", "Configure the missing credential source before finishing setup.", secretPointer(entry.getKey()));
          }
        }
      }
      Map<String, SecretInput> bound = withStores(given);
      // Shown and recorded without a key an MCP server's URL may carry.
      Optional<String> newTarget = ConnectionTargets.changedTarget(current.config().config(), cleaned).map(masker::url);
      if (newTarget.isPresent() && !current.secretIds().isEmpty() && !confirmed(confirm, CONFIRM_NEW_HOST)) {
        throw ProblemException.needsConfirmation("Send the stored secret to a new host?",
            "Send the stored credentials of " + name + " to " + newTarget.get() + "? Confirm, or enter new values.",
            CONFIRM_NEW_HOST);
      }
      // A prod connection is tested again when it becomes prod or its settings change: rights may be new.
      if (env == Env.PROD && !confirmed(confirm, CONFIRM_DANGER)
          && (completing || current.config().env() != Env.PROD || !next.equals(current.config().config()))) {
        Map<String, SecretInput> copies = new LinkedHashMap<>();
        bound.forEach((field, input) -> copies.put(field, input.isLocal() ? SecretInput.local(input.value().clone()) : input));
        try {
          requireNoDanger(name, tester.getObject().testDraft(current.config().type(), env, cleaned, copies, name,
              newTarget.isPresent() ? CONFIRM_NEW_HOST : null).result());
        } finally { copies.values().forEach(SecretInput::clear); }
      }
      writeTx.executeWithoutResult(s -> {
        ConnectionEntity row = repository.findById(current.id()).orElseThrow();
        if (row.getVersion() != version) {
          throw new OptimisticLockingFailureException("Connection " + name + " is at version " + row.getVersion());
        }
        // Checked again in the transaction that signs it: an edit made since the read above must not ride along.
        if (!rows.verify(row)) {
          alerts.report("connection", row.getId(), row.getName(), "connection row");
          throw ProblemException.conflict("Changed outside GateRook", name + " was changed outside GateRook. "
              + "Review it first.");
        }
        Env before = row.getEnv();
        if (env != before) {
          // In the writing transaction: the single writer orders it with the connection joining an endpoint, so the
          // two can't both pass and leave prod mixed with dev on one endpoint.
          Optional<String> problem = usage().flatMap(u -> u.envChangeProblem(current.id(), env));
          if (problem.isPresent()) {
            throw ProblemException.unprocessable("Can't change the environment", problem.get(), "/env");
          }
        }
        if (renaming) {
          // Again in the writing transaction: another save may have taken the name since the check above.
          if (repository.existsByName(target)) {
            throw nameInUse(target);
          }
          row.rename(target, clock.instant());
        }
        ObjectNode storedBefore = rows.storedConfig(row);
        for (Map.Entry<String, SecretInput> entry : bound.entrySet()) {
          SecretInput input = entry.getValue();
          String secretName = uniqueSecretName(target, entry.getKey(), cleaned);
          SecretInfo info = input.isLocal()
              ? secrets.createLocal(SecretOwner.connection(row.getId()), entry.getKey(), secretName, input.value())
              : secrets.createReference(SecretOwner.connection(row.getId()), entry.getKey(), secretName,
                  input.reference(), input.dynamic(), input.store(), secretPointer(entry.getKey()));
          SecretFields.set(next, entry.getKey(), SecretFields.placeholder(info.id()));
        }
        if (completing) {
          ObjectNode metadata = (ObjectNode) current.config().metadata().deepCopy();
          SETUP_METADATA.forEach(metadata::remove);
          row.setMetadata(rows.map(metadata));
          row.setEnabled(false, clock.instant());
        }
        boolean settingsChanged = completing || env != before || !bound.isEmpty() || !dropped.isEmpty()
            || !next.equals(storedBefore);
        row.reconfigure(env, rows.map(next), clock.instant());
        row = repository.saveAndFlush(row);
        rows.sign(row);
        if (!dropped.isEmpty()) {
          // A field the new config no longer has (another sign-in method): its secret goes away with it.
          secrets.deleteForOwnerFields(SecretOwner.connection(row.getId()), dropped.keySet());
        }
        if (renaming) {
          renameReferences(row, name, target, reason);
        }
        if (!renaming || settingsChanged) {
          setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.CONNECTION,
              "Connection " + target + " edited", before == env ? null : before.id(), before == env ? null : env.id(),
              reason, env, null, target, newTarget.map(t -> (JsonNode) JsonNodeFactory.instance.objectNode()
                  .put("newTarget", t)).orElse(null)));
        }
        publisher.publishEvent(new ConnectionConfigured(target, row.getType(), env, cleaned.deepCopy(), reason));
        publisher.publishEvent(new ConnectionChanged(row.getId(), target, env, row.getVersion()));
      });
      return summary(target);
    } finally {
      if (newSecrets != null) newSecrets.values().forEach(SecretInput::clear);
    }
  }

  /**
   * What refers to a renamed connection by its name takes the new one, inside the rename's transaction and after the
   * row was signed with it: the names of its secrets, the policies' private ranges allowed to it, then the
   * {@link ConnectionRenameParticipant}s (endpoints, MCP servers), and a Setup change. A participant that throws vetoes
   * the rename.
   */
  private void renameReferences(ConnectionEntity row, String from, String to, String reason) {
    Map<String, String> secretNames = secrets.renameForOwner(SecretOwner.connection(row.getId()), from, to);
    EnvironmentPolicies environments = policies.getIfAvailable();
    if (environments != null) {
      environments.renameConnection(from, to, reason);
    }
    for (ConnectionRenameParticipant p : renameParticipants.orderedStream().toList()) {
      p.onRenaming(row.getId(), from, to, reason);
    }
    ObjectNode details = null;
    if (!secretNames.isEmpty()) {
      details = JsonNodeFactory.instance.objectNode();
      ObjectNode names = details.putObject("secrets");
      secretNames.forEach(names::put);
    }
    setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.CONNECTION,
        "Connection " + from + " renamed to " + to, from, to, reason, row.getEnv(), null, to, details));
  }

  private static ProblemException nameInUse(String name) {
    return ProblemException.conflict("Name in use", "A connection named " + name + " already exists.");
  }

  /**
   * A connector's test found facts for {@code classify} ({@code TestResult.metadataUpdate}: k8s discovery, built-in
   * functions of PostgreSQL): stored as a new version. The server's version is kept as one short line
   * ({@link #VERSION_CHARS}): a server writes it, and the lists and the descriptions agents read show it.
   */
  public void recordMetadata(String connectionId, JsonNode metadata) {
    recordMetadata(connectionId, metadata, -1);
  }

  /**
   * The same for the facts of a test of the connection at {@code testedVersion} ({@code -1}: any): a row that has moved
   * on since — the person pointed it at another server while the test ran (Test all, the health monitor's own test of
   * a round's snapshot) — doesn't get them: they describe a server it no longer connects to (its built-in functions,
   * which the SQL classifier reads; "Read replica").
   */
  public void recordMetadata(String connectionId, JsonNode metadata, long testedVersion) {
    JsonNode facts = withCleanVersion(metadata);
    writeTx.executeWithoutResult(s -> {
      ConnectionEntity row = repository.findById(connectionId).orElse(null);
      if (row == null || !rows.verify(row) || testedVersion >= 0 && row.getVersion() != testedVersion) {
        return;
      }
      ObjectNode guarded = facts instanceof ObjectNode object ? object.deepCopy() : JsonNodeFactory.instance.objectNode();
      SETUP_METADATA.forEach(guarded::remove);
      JsonNode current = configs.stored(row).config().metadata();
      if (current != null) for (String key : SETUP_METADATA) if (current.has(key)) guarded.set(key, current.get(key).deepCopy());
      Map<String, Object> next = rows.map(guarded);
      if (Objects.equals(next, row.getMetadata())) {
        return;
      }
      row.setMetadata(next);
      row.touch(clock.instant());
      row = repository.saveAndFlush(row);
      rows.sign(row);
      publisher.publishEvent(new ConnectionChanged(row.getId(), row.getName(), row.getEnv(), row.getVersion()));
    });
  }

  /** {@code metadata} with its {@code serverVersion} as one short line, or without it when nothing of it is left. */
  static JsonNode withCleanVersion(JsonNode metadata) {
    if (metadata == null || !metadata.isObject() || !metadata.path("serverVersion").isString()) {
      return metadata;
    }
    ObjectNode out = (ObjectNode) metadata.deepCopy();
    String clean = ConnectionTargets.oneLine(metadata.path("serverVersion").asString(), VERSION_CHARS);
    if (clean == null) {
      out.remove("serverVersion");
    } else {
      out.put("serverVersion", clean);
    }
    return out;
  }

  /**
   * Applies a connector's {@code configPatch} after the person trusted a host key or certificate (Setup change
   * {@code host-key}).
   */
  public void applyTrustedPatch(String connectionId, JsonNode patch, String kind, String text, String reason) {
    writeTx.executeWithoutResult(s -> {
      ConnectionEntity row = repository.findById(connectionId).orElseThrow(() -> ProblemException.notFound(
          "Connection not found", "The connection was deleted."));
      if (!rows.verify(row)) {
        throw ProblemException.conflict("Changed outside GateRook", row.getName() + " was changed outside "
            + "GateRook. Review it first.");
      }
      JsonNode patched = ai.msrv.gaterook.core.platform.JsonMergePatch.apply(rows.storedConfig(row), patch);
      validate(registry.requireManifest(row.getType()), (ObjectNode) patched, java.util.Set.of(), row.getEnv());
      row.reconfigure(row.getEnv(), rows.map(patched), clock.instant());
      row = repository.saveAndFlush(row);
      rows.sign(row);
      setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.HOST_KEY,
          "Trusted " + kind + " for " + row.getName(), null, text, reason, row.getEnv(), null, row.getName(), patch));
      publisher.publishEvent(new ConnectionChanged(row.getId(), row.getName(), row.getEnv(), row.getVersion()));
    });
  }

  /**
   * Records a health check without a new version: health is not part of the row's MAC and must not drop the
   * connector's pools every few minutes.
   */
  public void recordHealth(String connectionId, HealthResult.State state, Long latencyMs, String message) {
    recordHealth(connectionId, state, latencyMs, message, clock.instant());
  }

  /** The same, checked at {@code at}: the demo world's checks of the past (the kit's "since 11:42"). */
  public void recordHealth(String connectionId, HealthResult.State state, Long latencyMs, String message,
      Instant at) {
    writeTx.executeWithoutResult(s -> repository.recordHealth(connectionId, state, latencyMs,
        message == null ? null : message.length() > 512 ? message.substring(0, 512) : message, at));
  }

  /** A new version of every connection of {@code env}: its policy changed, pools keyed on the old one must go. */
  public void touchEnv(Env env) {
    writeTx.executeWithoutResult(s -> {
      for (ConnectionEntity row : repository.findByEnvOrderByNameAsc(env)) {
        touch(row);
      }
    });
  }

  /** A secret of a connection got a new version: the connection gets one too. */
  @Async
  @TransactionalEventListener
  public void onSecretVersionChanged(SecretVersionChanged event) {
    if (event.owner().kind() != SecretOwner.Kind.CONNECTION) {
      return;
    }
    writeTx.executeWithoutResult(s -> repository.findById(event.owner().id()).ifPresent(this::touch));
  }

  private void touch(ConnectionEntity row) {
    if (!rows.verify(row)) {
      // Never re-sign a row nobody vouches for.
      alerts.report("connection", row.getId(), row.getName(), "connection row");
      return;
    }
    // A later updated_at makes the row dirty, so the flush takes the version step pools key on.
    Instant now = clock.instant();
    row.touch(now.isAfter(row.getUpdatedAt()) ? now : row.getUpdatedAt().plusMillis(1));
    ConnectionEntity saved = repository.saveAndFlush(row);
    rows.sign(saved);
    publisher.publishEvent(new ConnectionChanged(saved.getId(), saved.getName(), saved.getEnv(),
        saved.getVersion()));
  }

  /** The person reviewed a connection changed outside the core and keeps it as stored (Setup change). */
  public Summary confirmIntegrity(String name, String reason) {
    writeTx.executeWithoutResult(s -> {
      ConnectionEntity row = repository.findByName(name).orElseThrow(() -> notFound(name));
      if (rows.verify(row)) {
        return;
      }
      ConnectorManifest manifest = usableType(row.getType());
      ObjectNode stored = rows.storedConfig(row);
      boolean partial = row.getMetadata() != null && Boolean.TRUE.equals(row.getMetadata().get("setupRequired"));
      if (partial) {
        validate(manifest, stored, Set.of(), dynamicSecretFields(SecretFields.placeholders(stored)), row.getEnv(), true,
            PartialConnectionSchema.relax(manifest.configSchema()));
        row.setEnabled(false, clock.instant());
        row = repository.saveAndFlush(row);
      } else validate(manifest, stored, Set.of(), row.getEnv());
      rows.sign(row);
      alerts.resolve("connection", row.getId());
      setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.CONNECTION,
          "Connection " + name + " changed outside GateRook", "Unverified", "Kept as stored", reason,
          row.getEnv(), null, name, null));
      publisher.publishEvent(new ConnectionChanged(row.getId(), name, row.getEnv(), row.getVersion()));
    });
    return summary(name);
  }

  // ── Delete ──────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * {@code DELETE /api/connections/{name}}: in one transaction its tools leave every endpoint, its templates become
   * invalid, its pending requests are rejected and its secrets destroyed; the audit keeps its name.
   *
   * @param confirmName the name typed in the danger dialog
   */
  public DeleteSummary delete(String name, String confirmName, String reason) {
    if (!name.equals(confirmName)) {
      throw ProblemException.unprocessable("Type the name to delete", "Type " + name + " to confirm.",
          "/confirmName");
    }
    return writeTx.execute(s -> {
      ConnectionEntity row = repository.findByName(name).orElseThrow(() -> notFound(name));
      List<String> stopped = new ArrayList<>();
      List<String> templates = new ArrayList<>();
      List<String> clients = new ArrayList<>();
      int rejected = 0;
      for (ConnectionRemovalParticipant p : participants.orderedStream().toList()) {
        ConnectionRemovalParticipant.Removal r = p.onRemoving(row.getId(), name, reason);
        stopped.addAll(r.stoppedTools());
        templates.addAll(r.templates());
        clients.addAll(r.clients());
        rejected += r.rejectedApprovals();
      }
      List<String> destroyed = secrets.deleteForOwner(SecretOwner.connection(row.getId()));
      repository.delete(row);
      repository.flush();
      alerts.resolve("connection", row.getId());
      setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.CONNECTION,
          "Connection " + name + " deleted", row.getType() + " · " + row.getEnv().id(), null, reason, row.getEnv(),
          null, name, null));
      publisher.publishEvent(new ConnectionRemoved(row.getId(), name, row.getType(), row.getEnv()));
      return new DeleteSummary(List.copyOf(stopped), rejected, List.copyOf(templates), List.copyOf(clients),
          destroyed);
    });
  }

  // ── Internals ───────────────────────────────────────────────────────────────────────────────────────────────

  /** Pauses agent access everywhere without changing wiring, credentials or tool modes. */
  public Summary setEnabled(String name, boolean enabled, String reason) {
    writeTx.executeWithoutResult(tx -> {
      ConnectionEntity row = repository.findByName(name).orElseThrow(() -> notFound(name));
      requireVerified(configs.stored(row));
      if (enabled && needsSetup(configs.stored(row).config())) {
        throw ProblemException.unprocessable("Finish setup first", "Complete the missing settings and save this connection before enabling it.", "/enabled");
      }
      if (enabled) validate(usableType(row.getType()), rows.storedConfig(row), Set.of(), row.getEnv());
      if (row.isEnabled() == enabled) return;
      row.setEnabled(enabled, clock.instant());
      repository.saveAndFlush(row);
      rows.sign(row);
      setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.SETTINGS,
          "Connection " + name, enabled ? "Disabled" : "Enabled", enabled ? "Enabled" : "Disabled",
          reason, row.getEnv(), null, name, null));
      publisher.publishEvent(new ConnectionChanged(row.getId(), name, row.getEnv(), row.getVersion()));
    });
    return summary(name);
  }

  private Summary summary(ConnectionConfigs.Stored s) {
    ConnectorManifest manifest = registry.manifest(s.config().type()).orElse(null);
    String typeName = manifest == null ? s.config().type() : manifest.name();
    List<SecretInfo> owned = s.secretIds().isEmpty() ? List.of() : secrets.listForOwner(SecretOwner.connection(s.id()));
    String secretSource = owned.isEmpty() ? null
        : owned.stream().map(SecretInfo::source).anyMatch(src -> !"local".equals(src))
            ? owned.stream().map(SecretInfo::source).filter(src -> !"local".equals(src)).findFirst().orElseThrow()
            : "encrypted";
    JsonNode schema = manifest == null ? null : manifest.configSchema();
    JsonNode metadata = s.config().metadata();
    // Facts recorded before they were cleaned on the way in (recordMetadata) are cleaned here too.
    String serverVersion = metadata == null || !metadata.path("serverVersion").isString() ? null
        : ConnectionTargets.oneLine(metadata.path("serverVersion").asString(), VERSION_CHARS);
    Optional<ConnectionForms.Variant> variant = ConnectionForms.variant(schema, s.config().config());
    return new Summary(s.id(), s.name(), s.config().type(), typeName, s.config().env(),
        ConnectionTargets.display(s.config().config(), schema, metadata), secretSource, s.health(),
        usage().map(u -> u.endpoints(s.id())).orElse(List.of()), s.verified(), s.config().version(),
        typeLabel(manifest, typeName, variant.orElse(null), serverVersion),
        variant.map(ConnectionForms.Variant::id).orElse(null), serverVersion, s.enabled());
  }

  /**
   * The kit's Type column: "Loki" and "GCP Cloud Logging" for the Logs variants,
   * "PostgreSQL 16", "MongoDB 7", "Redis 7" for databases whose test saw the server (product and major version of what
   * it reported: "MongoDB 7.0" → "MongoDB 7"), the connector's name otherwise ("SSH", "Kubernetes", "AWS", "HTTP API").
   */
  static String typeLabel(ConnectorManifest manifest, String typeName, ConnectionForms.Variant variant,
      String serverVersion) {
    if (variant != null) {
      return variant.name();
    }
    if (serverVersion == null || manifest == null || !"Databases".equals(manifest.group())) {
      return typeName;
    }
    java.util.regex.Matcher m = SERVER_VERSION.matcher(serverVersion.strip());
    if (!m.matches()) {
      return serverVersion;
    }
    // A plugin may report the bare version ("24.8"): its product is the connector's name.
    return (m.group(1) == null ? typeName : m.group(1)) + " " + m.group(2);
  }

  private ConnectionConfigs.Stored stored(String name) {
    return configs.byName(name).orElseThrow(() -> notFound(name));
  }

  private static ProblemException notFound(String name) {
    return ProblemException.notFound("Connection not found", "There is no connection " + name + ".");
  }

  private static void requireVerified(ConnectionConfigs.Stored s) {
    if (!s.verified()) {
      throw ProblemException.conflict("Changed outside GateRook", s.name() + " was changed outside GateRook. "
          + "Review it before you edit it.");
    }
  }

  private Optional<ConnectionUsage> usage() {
    return Optional.ofNullable(usage.getIfAvailable());
  }

  private static void checkName(String name) {
    if (name == null || name.length() > 64 || !NAME.matcher(name).matches()) {
      throw ProblemException.unprocessable("Invalid name", "Use lower-case letters, digits and hyphens, like dev-db.",
          "/name");
    }
  }

  private ConnectorManifest usableType(String type) {
    ConnectorType t = registry.type(type == null ? "" : type).orElseThrow(() -> ProblemException.unprocessable(
        "Unknown connector", "There is no connector type " + type + ".", "/type"));
    if (!t.usable()) {
      throw ProblemException.unprocessable("Connector off", t.manifest().name() + " is switched off or was changed "
          + "outside GateRook.", "/type");
    }
    return t.manifest();
  }

  /** The config of a request: an object, and never a secret placeholder (it could name another connection's). */
  private static ObjectNode requestConfig(JsonNode config) {
    if (config == null || !config.isObject()) {
      throw ProblemException.unprocessable("Invalid settings", "The settings must be an object.", "/config");
    }
    if (SecretFields.mentionsPlaceholder(config)) {
      throw ProblemException.unprocessable("Invalid settings", "Settings can't name stored secrets.", "/config");
    }
    return (ObjectNode) config.deepCopy();
  }

  /** JSON pointer of a secret field in the request ({@code /secrets/~1credentials~1password}). */
  static String secretPointer(String field) {
    return "/secrets/" + field.replace("~", "~0").replace("/", "~1");
  }

  /**
   * The secrets of a request: each secret field with a value or a reference, and each text field read from a store
   * ({@link SecretFields#textTemplates}, a login's user among them): a reference only, as its typed value belongs in
   * the config.
   *
   * @param templates the type's secret fields
   */
  static Map<String, SecretInput> checkSecrets(JsonNode schema, List<String> templates,
      Map<String, SecretInput> given) {
    Map<String, SecretInput> out = new LinkedHashMap<>();
    if (given == null) {
      return out;
    }
    List<String> users = SecretFields.loginUserTemplates(schema);
    List<String> texts = SecretFields.textTemplates(schema);
    for (Map.Entry<String, SecretInput> e : given.entrySet()) {
      String field = secretPointer(e.getKey());
      boolean text = !SecretFields.matches(templates, e.getKey()) && SecretFields.matches(texts, e.getKey());
      String key = SecretFields.unescape(e.getKey().substring(e.getKey().lastIndexOf('/') + 1));
      if (!text && ConnectionTargets.isTargetKey(key)) {
        throw ProblemException.unprocessable("Type the " + key, "Type it into the form: where a connection sends "
            + "its credentials is never read from a store.", field);
      }
      if (!text && !SecretFields.matches(templates, e.getKey())) {
        throw ProblemException.unprocessable("Not a secret field", e.getKey() + " is not a secret field of this "
            + "connector, nor a text field a store can hold.", field);
      }
      SecretInput input = e.getValue();
      if (input == null || (!input.isLocal() && input.reference() == null)) {
        throw ProblemException.unprocessable("Secret missing", "Enter a value for " + e.getKey() + ".", field);
      }
      if (text && (input.isLocal() || input.dynamic())) {
        boolean user = SecretFields.matches(users, e.getKey());
        throw ProblemException.unprocessable(user ? "Type the user" : "Type the value", "Type the "
            + (user ? "user" : key) + " into the form, or read it from a store with a reference such as "
            + "vault://kv/prod/db#username.", field);
      }
      if (!input.isLocal()) {
        // Parsed (and bound to its store) by the secrets module when it is stored; here only its presence counts.
        if (input.reference().isBlank()) {
          throw ProblemException.unprocessable("Secret missing", "Enter a reference for " + e.getKey() + ".", field);
        }
        out.put(e.getKey(), input);
        continue;
      }
      if (input.value().length == 0) {
        throw ProblemException.unprocessable("Secret missing", "Enter a value for " + e.getKey() + ".", field);
      }
      out.put(e.getKey(), input);
    }
    for (String user : out.keySet().stream().filter(f -> SecretFields.matches(users, f)).toList()) {
      List<String> login = SecretFields.loginSecretsOf(templates, user);
      if (login.stream().map(out::get).anyMatch(i -> i != null && i.dynamic())) {
        throw ProblemException.unprocessable("Leave the user out", "Short-lived Vault credentials bring their own "
            + "user.", secretPointer(user));
      }
    }
    return out;
  }

  /**
   * The secrets of a request with the store each reference reads from: the one chosen; else the natural or only one
   * of its kind; else, with several connected, the first, by name, that has it ({@link SecretService#storeHolding}
   * asks them in turn). Call it outside a transaction; inputs that name their store are kept as they are.
   */
  public Map<String, SecretInput> withStores(Map<String, SecretInput> given) {
    Map<String, SecretInput> out = new LinkedHashMap<>();
    given.forEach((field, input) -> out.put(field, input.isLocal() || (input.store() != null
        && !input.store().isBlank()) ? input : new SecretInput(null, input.reference(), input.dynamic(),
        secrets.storeHolding(input.reference(), input.dynamic(), null, secretPointer(field)))));
    return out;
  }

  /**
   * Refuses a reference typed as a value ({@code vault://kv/prod/db#username} in the config): a value read from a
   * store is sent as the field's reference in {@code secrets}, which only a person sets, in the window.
   */
  private static void checkNoReferenceValues(JsonNode node, String pointer) {
    if (SecretFields.isPlaceholder(node)) {
      return;
    }
    if (node.isObject()) {
      for (Map.Entry<String, JsonNode> e : node.properties()) {
        checkNoReferenceValues(e.getValue(), pointer + "/" + SecretFields.escape(e.getKey()));
      }
    } else if (node.isArray()) {
      for (int i = 0; i < node.size(); i++) {
        checkNoReferenceValues(node.get(i), pointer + "/" + i);
      }
    } else if (node.isString() && SecretFields.looksLikeReference(node.asString())) {
      throw ProblemException.unprocessable("Read it from the store", "This names a value kept in a store. Send it "
          + "as the field's reference, so that GateRook reads the value from there at call time.", "/config" + pointer);
    }
  }

  /**
   * Validates a config against the type's schema, with every stored or new secret counted as present; refuses what
   * the schema forbids on {@code env} ({@link EnvLocks}) and secret-looking values in fields that aren't secret.
   *
   * @param env the environment the config is saved or tested for: a move to prod is checked against prod's locks
   */
  public void validate(ConnectorManifest manifest, ObjectNode config, java.util.Set<String> newSecretFields, Env env) {
    validate(manifest, config, newSecretFields, env, false);
  }

  void validate(ConnectorManifest manifest, ObjectNode config, java.util.Set<String> newSecretFields, Env env,
      boolean discovery) {
    validate(manifest, config, newSecretFields, dynamicSecretFields(SecretFields.placeholders(config)), env, discovery);
  }

  /** Stored references carry the lease type even when their store is disconnected and state says not-connected. */
  Set<String> dynamicSecretFields(Map<String, String> secretIds) {
    Set<String> dynamic = new LinkedHashSet<>();
    secretIds.forEach((field, id) -> {
      try {
        if (secrets.get(id).dynamicReference()) dynamic.add(field);
      } catch (SecretService.NoSuchSecretException ignored) {
        // Missing references cannot supply a login during validation.
      }
    });
    return Set.copyOf(dynamic);
  }

  void validate(ConnectorManifest manifest, ObjectNode config, Set<String> newSecretFields,
      Set<String> dynamicSecretFields, Env env, boolean discovery) {
    validate(manifest, config, newSecretFields, dynamicSecretFields, env, discovery, manifest.configSchema());
  }

  /** Project choices need credentials first; their own value is irrelevant until the draft is tested or saved. */
  void validateProjectDiscovery(ConnectorManifest manifest, ObjectNode config, Set<String> newSecretFields,
      Set<String> dynamicSecretFields, Env env) {
    String path = manifest.configSchema().path("x-project-selection").path("path").asString("");
    if (!path.matches("/[^/]+")) {
      throw ProblemException.unprocessable("Project discovery unavailable", "This connector can't list projects.",
          "/type");
    }
    JsonNode schema = manifest.configSchema().deepCopy();
    relaxProjectSelection(schema, path.substring(1).replace("~1", "/").replace("~0", "~"));
    validate(manifest, config, newSecretFields, dynamicSecretFields, env, true, schema);
  }

  /** Only same-instance schema branches are relaxed; nested credentials retain every constraint. */
  private static void relaxProjectSelection(JsonNode schema, String field) {
    if (!(schema instanceof ObjectNode object)) return;
    if (object.path("properties") instanceof ObjectNode properties && properties.has(field)) {
      properties.putObject(field);
    }
    if (object.path("required").isArray()) {
      List<JsonNode> required = new ArrayList<>();
      for (JsonNode name : object.path("required")) {
        if (!field.equals(name.asString())) required.add(name);
      }
      object.putArray("required").addAll(required);
    }
    for (String keyword : List.of("allOf", "anyOf", "oneOf")) {
      for (JsonNode branch : object.path(keyword)) relaxProjectSelection(branch, field);
    }
    for (String keyword : List.of("if", "then", "else")) {
      relaxProjectSelection(object.path(keyword), field);
    }
  }

  private void validate(ConnectorManifest manifest, ObjectNode config, Set<String> newSecretFields,
      Set<String> dynamicSecretFields, Env env, boolean discovery, JsonNode validationSchema) {
    Objects.requireNonNull(env, "env");
    checkNoReferenceValues(config, "");
    ObjectNode probe = config.deepCopy();
    // Stand-ins for what the secrets module holds: a stored or new secret, a text field read from a store. Their
    // values aren't known here, so only their presence is checked, not a pattern or format of the field.
    Set<String> standIns = new LinkedHashSet<>(SecretFields.placeholders(config).keySet());
    for (String field : standIns) {
      SecretFields.set(probe, field, StringNode.valueOf("‹secret›"));
    }
    for (String field : newSecretFields) {
      if (!SecretFields.set(probe, field, StringNode.valueOf("‹secret›"))) {
        throw ProblemException.unprocessable("Add the credential field", "Add its containing settings or array row before supplying this credential.", secretPointer(field));
      }
      standIns.add(field);
    }
    SecretFields.dynamicLoginUsers(manifest.configSchema(), probe, dynamicSecretFields);
    for (SchemaValidator.Violation v : validator.validate(validationSchema, probe)) {
      if (standIns.contains(v.pointer())) {
        continue;
      }
      throw ProblemException.unprocessable("Check the settings", v.message(), "/config" + v.pointer());
    }
    JsonNode selection = manifest.configSchema().path("x-database-selection");
    if (!discovery && selection.path("required").asBoolean(false)) {
      String path = selection.path("path").asString("");
      String legacyPath = selection.path("legacyPath").asString("");
      JsonNode databases = path.startsWith("/") ? config.at(path) : null;
      JsonNode legacy = legacyPath.startsWith("/") ? config.at(legacyPath) : null;
      if ((databases == null || !databases.isArray() || databases.isEmpty())
          && (legacy == null || !legacy.isString() || legacy.asString().isBlank())) {
        throw ProblemException.unprocessable("Choose databases", "Select at least one database.", "/config" + path);
      }
    }
    JsonNode files = manifest.configSchema().path("x-file-selection");
    if (!discovery && files.path("required").asBoolean(false)) {
      String rootPath = files.path("rootPath").asString("");
      String path = files.path("path").asString("");
      JsonNode root = rootPath.startsWith("/") ? config.at(rootPath) : null;
      JsonNode allowed = path.startsWith("/") ? config.at(path) : null;
      if (root == null || !root.isString() || root.asString().isBlank()) {
        throw ProblemException.unprocessable("Choose a folder", "Select a remote log folder.",
            "/config" + rootPath);
      }
      if (allowed == null || !allowed.isArray() || allowed.isEmpty()) {
        throw ProblemException.unprocessable("Choose log files", "Select at least one allowed log file.",
            "/config" + path);
      }
    }
    EnvLocks.check(manifest.configSchema(), config, env).ifPresent(v -> {
      throw ProblemException.unprocessable("Not allowed on " + env.id(), v.message(), "/config" + v.pointer());
    });
    List<String> exempt = new ArrayList<>(SecretFields.templates(manifest.configSchema()));
    exempt.addAll(SecretFields.notSecretTemplates(manifest.configSchema()));
    exempt.addAll(SecretFields.keyAllowedTemplates(manifest.configSchema()));
    checkLooksLikeSecret(config, "", exempt);
  }

  /**
   * @param exempt secret fields (their values never stay in the config), public identifiers and values that may carry
   *     a key ({@value SecretFields#KEY_ALLOWED})
   */
  private void checkLooksLikeSecret(JsonNode node, String pointer, List<String> exempt) {
    if (SecretFields.isPlaceholder(node)) {
      return;
    }
    if (node.isObject()) {
      for (Map.Entry<String, JsonNode> e : node.properties()) {
        checkLooksLikeSecret(e.getValue(), pointer + "/" + e.getKey().replace("~", "~0").replace("/", "~1"),
            exempt);
      }
    } else if (node.isArray()) {
      for (int i = 0; i < node.size(); i++) {
        checkLooksLikeSecret(node.get(i), pointer + "/" + i, exempt);
      }
    } else if (node.isString() && !SecretFields.matches(exempt, pointer)) {
      String value = node.asString();
      if (value.startsWith("-----BEGIN CERTIFICATE-----")) {
        return;
      }
      if (masker.looksLikeSecret(value, false).isPresent()) {
        throw ProblemException.unprocessable("This looks like a secret", "This looks like a secret: store it in a "
            + "secret field instead, so that it is encrypted and agents never see it.", "/config" + pointer);
      }
    }
  }

  /**
   * {@code dev-db/password}; a secret header is named by its header ({@code billing-api/webhook-secret} for
   * {@code Webhook-Secret}), not by its position in the list.
   */
  private String uniqueSecretName(String connection, String field, JsonNode config) {
    String display = SecretFields.displayName(field);
    java.util.regex.Matcher header = java.util.regex.Pattern.compile("/secretHeaders/(\\d+)/value").matcher(field);
    if (header.matches()) {
      String headerName = config.path("secretHeaders").path(Integer.parseInt(header.group(1))).path("name")
          .asString("");
      if (headerName.matches("[A-Za-z0-9-]{1,64}")) {
        display = headerName.toLowerCase(java.util.Locale.ROOT);
      }
    }
    // An MCP server's variable or header: named by it (github-mcp/github-personal-access-token).
    java.util.regex.Matcher binding = java.util.regex.Pattern.compile("/(env|headers)/(\\d+)/secretRef")
        .matcher(field);
    if (binding.matches()) {
      String bound = config.path(binding.group(1)).path(Integer.parseInt(binding.group(2))).path("name").asString("");
      if (bound.matches("[A-Za-z0-9_-]{1,64}")) {
        display = bound.toLowerCase(java.util.Locale.ROOT).replace('_', '-');
      }
    }
    String base = connection + "/" + display;
    String name = base;
    List<String> taken = secrets.list().stream().map(SecretInfo::name).toList();
    for (int i = 2; taken.contains(name); i++) {
      name = base + "-" + i;
    }
    return name.length() > 128 ? name.substring(0, 128) : name;
  }

  /** For tests and the summary: when the row was last changed. */
  Instant updatedAt(String name) {
    return stored(name).updatedAt();
  }

  /** Whether {@code confirm} (comma-separated) contains {@code which}. */
  static boolean confirmed(String confirm, String which) {
    return confirm != null && Arrays.asList(confirm.split(",")).contains(which);
  }

  /**
   * A 409 asking for {@link #CONFIRM_DANGER} when the test found DANGER hints. A test that didn't get as far as the
   * rights (unreachable, a host key to trust first) can't tell, and doesn't block: the person saw that test.
   */
  private static void requireNoDanger(String name, TestResult result) {
    List<String> dangers = result.hints() == null ? List.of() : result.hints().stream()
        .filter(h -> h.severity() == TestResult.Severity.DANGER).map(TestResult.Hint::text).toList();
    if (!dangers.isEmpty()) {
      throw ProblemException.needsConfirmation("Keep these rights on prod?", name + ": " + String.join(" ", dangers)
          + " Agents only use the gateway's tools, but these rights reach beyond them. Use a user with fewer rights, "
          + "or confirm to save anyway.", CONFIRM_DANGER);
    }
  }
}
