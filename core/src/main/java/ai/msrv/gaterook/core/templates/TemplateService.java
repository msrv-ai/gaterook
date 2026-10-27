package ai.msrv.gaterook.core.templates;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.TemplateKind;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.core.connections.ConnectionConfigs;
import ai.msrv.gaterook.core.connections.ConnectionRemovalParticipant;
import ai.msrv.gaterook.core.connectors.ConnectorRegistry;
import ai.msrv.gaterook.core.connectors.SignedCatalog;
import ai.msrv.gaterook.core.platform.IntegrityAlerts;
import ai.msrv.gaterook.core.platform.KebabCaseEnumConverter;
import ai.msrv.gaterook.core.platform.ProblemException;
import ai.msrv.gaterook.core.platform.RowMac;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder.SetupChange;
import ai.msrv.gaterook.core.templates.internal.persistence.TemplateEntity;
import ai.msrv.gaterook.core.templates.internal.persistence.TemplateRepository;
import ai.msrv.gaterook.core.templates.internal.persistence.TemplateStatus;
import ai.msrv.gaterook.core.templates.internal.persistence.TemplateType;
import ai.msrv.gaterook.core.templates.internal.persistence.TemplateVersionEntity;
import ai.msrv.gaterook.core.templates.internal.persistence.TemplateVersionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.annotation.Order;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * No-code templates (ARCHITECTURE.md §2.2 {@code templates}, report 10 §7.6): SQL queries, SSH runbooks and OpenAPI
 * imports bound to the connection they run on ("Runs on"). The core stores them in append-only signed versions and
 * compiles them through the connector's {@link TemplateKind} against that connection; a version is part of the
 * approval digest, so an edit after a request was approved stops its execution.
 *
 * <p>A compiled tool may not take the name of a built-in tool or of the core's, nor of another template on the same
 * connection. Deleting the connection leaves its templates {@code invalid} (they keep their history).
 */
@Service
@Order(20)
public class TemplateService implements ConnectionRemovalParticipant {

  private static final String TABLE = "template_version";
  private static final String TEMPLATE_TABLE = "template";
  private static final Pattern TOOL_NAME = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");

  /**
   * One row of Connectors › Templates.
   *
   * @param verified   whether the template's rows verified: one changed outside GateRook serves nothing until the
   *                   person reviews it ({@link #confirmIntegrity})
   * @param parameters the current version's {@code params} as its definition declares them (the list's signature,
   *                   "orders_by_status(status, since)"), or {@code null} for a kind without them (OpenAPI) and for a
   *                   template that doesn't verify
   */
  public record TemplateInfo(String id, String kind, String name, String connectionId, String connectionName,
      int version, String status, String catalogRef, JsonNode natures, Instant updatedAt, boolean verified,
      JsonNode parameters) {}

  /** The outcome of a save: the template (if it was saved) and what compilation said. */
  public record SaveResult(TemplateInfo template, TemplateKind.CompileResult compile) {}

  /** One template compiled against the current config of its connection. */
  public record Compiled(String templateId, String name, String kind, int version, String connectionId,
      List<TemplateKind.CompiledTool> tools, List<JsonNode> contributions, String digest) {

    /** {@code endpoint_tool.tool_key} of a compiled tool: {@code tpl:<id>:<opKey>}. */
    public String toolKey(TemplateKind.CompiledTool tool) {
      return "tpl:" + templateId + ":" + tool.opKey();
    }
  }

  /** One stored version. */
  public record VersionInfo(int version, String digest, JsonNode natures, String note, Instant createdAt) {}

  private final TemplateRepository templates;
  private final TemplateVersionRepository versions;
  private final ConnectionConfigs connections;
  private final ConnectorRegistry registry;
  private final RowMac rowMac;
  private final IntegrityAlerts alerts;
  private final ObjectProvider<SetupChangeRecorder> setupChanges;
  private final ApplicationEventPublisher publisher;
  private final JsonMapper json;
  private final Clock clock;
  private final TransactionTemplate writeTx;
  private final TransactionTemplate readTx;
  private final SignedCatalog catalog;
  private final Map<String, Compiled> compiled = new ConcurrentHashMap<>();

  public TemplateService(TemplateRepository templates, TemplateVersionRepository versions,
      ConnectionConfigs connections, ConnectorRegistry registry, RowMac rowMac, IntegrityAlerts alerts,
      ObjectProvider<SetupChangeRecorder> setupChanges, ApplicationEventPublisher publisher, JsonMapper json,
      Clock clock, PlatformTransactionManager transactions, SignedCatalog catalog) {
    this.catalog = catalog;
    this.templates = templates;
    this.versions = versions;
    this.connections = connections;
    this.registry = registry;
    this.rowMac = rowMac;
    this.alerts = alerts;
    this.setupChanges = setupChanges;
    this.publisher = publisher;
    this.json = json;
    this.clock = clock;
    this.writeTx = new TransactionTemplate(transactions);
    this.readTx = new TransactionTemplate(transactions);
    this.readTx.setReadOnly(true);
  }

  // ── Reading ─────────────────────────────────────────────────────────────────────────────────────────────────

  public List<TemplateInfo> list(String kind) {
    return readTx.execute(s -> (kind == null ? templates.findAllByOrderByNameAsc()
        : templates.findByKindOrderByNameAsc(type(kind))).stream().map(this::info).toList());
  }

  public TemplateInfo get(String id) {
    return readTx.execute(s -> info(require(id)));
  }

  public List<VersionInfo> versions(String id) {
    return readTx.execute(s -> {
      require(id);
      return versions.findByTemplateIdOrderByTemplateVersionDesc(id).stream().map(v -> new VersionInfo(
          v.getTemplateVersion(), v.getDigest(), rowMac.tree(v.getNatures()), v.getNote(), v.getCreatedAt())).toList();
    });
  }

  /**
   * The active templates of a connection compiled against its current config; a template whose row or current version
   * no longer verifies, or that no longer compiles, is left out (and reported). Every row is verified on every call,
   * before the cache: the compiled result is cached per template version and config version.
   */
  public List<Compiled> compiledFor(String connectionId) {
    ConnectionConfig cfg = connections.byId(connectionId).filter(ConnectionConfigs.Stored::verified)
        .map(ConnectionConfigs.Stored::config).orElse(null);
    if (cfg == null) {
      return List.of();
    }
    // The binding, the current version and the status are read only from rows that verify: a template repointed at
    // another connection, rolled back or switched on outside the core is left out, not taken at its word.
    List<TemplateEntity> active = readTx.execute(s -> templates.findByConnectionId(connectionId).stream()
        .filter(this::verify).filter(t -> t.getStatus() == TemplateStatus.ACTIVE).toList());
    List<Compiled> out = new ArrayList<>();
    for (TemplateEntity t : active) {
      String key = t.getId() + ":" + t.getCurrentVersion() + ":" + cfg.version();
      Compiled c = compiled.get(key);
      if (c == null) {
        c = compileStored(t, cfg).orElse(null);
        if (c != null) {
          compiled.put(key, c);
        }
      }
      if (c != null) {
        out.add(c);
      }
    }
    return out;
  }

  // ── Changes ─────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * {@code POST /api/templates {kind, connection, definition}}: compiles and, if it compiles, stores version 1. A
   * definition that doesn't compile comes back with its errors and nothing is saved.
   */
  public SaveResult create(String kind, String connectionName, String name, JsonNode definition, String note,
      String reason) {
    TemplateType type = type(kind);
    TemplateKind templateKind = kind(kind);
    ConnectionConfigs.Stored runsOn = connections.byName(connectionName).orElseThrow(() ->
        ProblemException.unprocessable("Connection not found", "There is no connection " + connectionName + ".",
            "/connection"));
    requireRunsOn(templateKind, runsOn);
    String templateName = name != null ? name : definition.path("name").asString(null);
    if (templateName == null || templateName.isBlank() || templateName.length() > 64) {
      throw ProblemException.unprocessable("Name missing", "Give the template a name of up to 64 characters.",
          "/name");
    }
    TemplateKind.CompileResult result = compile(templateKind, definition, runsOn.config(), null);
    if (!result.ok()) {
      return new SaveResult(null, result);
    }
    String id = writeTx.execute(s -> {
      if (templates.findByConnectionIdAndKindAndName(runsOn.id(), type, templateName).isPresent()) {
        throw ProblemException.conflict("Name in use", runsOn.name() + " already has a template " + templateName
            + ".");
      }
      TemplateEntity t = templates.saveAndFlush(new TemplateEntity(type, templateName, runsOn.id(), clock.instant()));
      appendVersion(t, 1, definition, result, note);
      setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.CONNECTION,
          "Template " + templateName + " added to " + runsOn.name(), null, "v1", reason, runsOn.config().env(), null,
          runsOn.name(), null));
      publisher.publishEvent(new TemplateChanged(t.getId(), runsOn.id()));
      return t.getId();
    });
    return new SaveResult(get(id), result);
  }

  /**
   * {@code POST /api/templates {catalogRef, connection}} (step K6): a template of the signed catalog
   * ({@code pg-slow-queries@1.0.0}) installed on a connection — one template per definition of the entry, each
   * compiled against the connection like a pasted one and saved with its {@code catalogRef}, all or none. Read-only
   * confirmations a definition carries ({@code readOnlyConfirmedFor} of a runbook, {@code readOnlyConfirmed} of an
   * OpenAPI operation) are dropped first: a catalog states what a template does, the person confirms it here
   * (ARCHITECTURE.md §6, §9 "READ is a declaration"). Nothing is saved when one doesn't compile: 200 with the errors.
   *
   * @param kind the kind the caller expects, or {@code null}; it must be the entry's
   */
  public SaveResult createFromCatalog(String catalogRef, String kind, String connectionName, String reason) {
    SignedCatalog.Entry entry = catalog.entry("template", catalogRef).orElseThrow(() ->
        ProblemException.unprocessable("Not in the catalog", catalogRef + " isn't a template of the catalog.",
            "/catalogRef"));
    if (!entry.signed()) {
      throw ProblemException.unprocessable("Not signed", entry.ref() + " isn't signed by the catalog, so it isn't "
          + "installed.", "/catalogRef");
    }
    JsonNode json = entry.json();
    String templateKind = json.path("templateKind").asString(null);
    if (templateKind == null || (kind != null && !kind.isBlank() && !kind.equals(templateKind))) {
      throw ProblemException.unprocessable("Wrong kind", entry.ref() + " is a " + templateKind + " template, not "
          + kind + ".", "/kind");
    }
    TemplateType type = type(templateKind);
    TemplateKind templateKindImpl = kind(templateKind);
    ConnectionConfigs.Stored runsOn = connections.byName(connectionName).orElseThrow(() ->
        ProblemException.unprocessable("Connection not found", "There is no connection " + connectionName + ".",
            "/connection"));
    List<String> types = new ArrayList<>();
    json.path("connectionTypes").forEach(t -> types.add(t.asString()));
    if (!types.isEmpty() && !types.contains(runsOn.config().type())) {
      throw ProblemException.unprocessable("Runs elsewhere", json.path("name").asString(entry.id()) + " runs on "
          + String.join(", ", types) + " connections; " + runsOn.name() + " is " + runsOn.config().type() + ".",
          "/connection");
    }
    requireRunsOn(templateKindImpl, runsOn);
    List<JsonNode> definitions = new ArrayList<>();
    json.path("definitions").forEach(d -> definitions.add(withoutConfirmations(d)));
    if (definitions.isEmpty()) {
      throw ProblemException.unprocessable("Empty template", entry.ref() + " has no definitions.", "/catalogRef");
    }
    List<TemplateKind.CompileResult> results = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    List<TemplateKind.CompiledTool> tools = new ArrayList<>();
    Set<String> names = new HashSet<>();
    for (JsonNode definition : definitions) {
      String name = definition.path("name").asString(null);
      TemplateKind.CompileResult r = compile(templateKindImpl, definition, runsOn.config(), null);
      results.add(r);
      r.errors().forEach(e -> errors.add((name == null ? "" : name + ": ") + e));
      warnings.addAll(r.warnings());
      tools.addAll(r.tools());
      if (name == null || name.isBlank() || name.length() > 64 || !names.add(name)) {
        errors.add("The catalog's definition " + (name == null ? "without a name" : name) + " can't be saved as a "
            + "template of its own.");
      }
    }
    TemplateKind.CompileResult merged = new TemplateKind.CompileResult(List.copyOf(tools), List.of(),
        List.copyOf(errors), List.copyOf(warnings), null);
    if (!errors.isEmpty()) {
      return new SaveResult(null, merged);
    }
    String first = writeTx.execute(s -> {
      String firstId = null;
      for (int i = 0; i < definitions.size(); i++) {
        JsonNode definition = definitions.get(i);
        String name = definition.path("name").asString();
        if (templates.findByConnectionIdAndKindAndName(runsOn.id(), type, name).isPresent()) {
          throw ProblemException.conflict("Name in use", runsOn.name() + " already has a template " + name + ".");
        }
        TemplateEntity t = new TemplateEntity(type, name, runsOn.id(), clock.instant());
        t.setCatalogRef(entry.ref());
        t = templates.saveAndFlush(t);
        appendVersion(t, 1, definition, results.get(i), "From the catalog: " + entry.ref());
        publisher.publishEvent(new TemplateChanged(t.getId(), runsOn.id()));
        firstId = firstId == null ? t.getId() : firstId;
      }
      setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.CONNECTION,
          "Template " + json.path("name").asString(entry.id()) + " installed on " + runsOn.name() + " from the catalog",
          null, entry.ref(), reason, runsOn.config().env(), null, runsOn.name(), null));
      return firstId;
    });
    return new SaveResult(get(first), merged);
  }

  /**
   * A catalog definition without the read-only confirmations a person gives in this gateway: the keys
   * {@code readOnlyConfirmedFor} and {@code readOnlyConfirmed}, at any depth.
   */
  static JsonNode withoutConfirmations(JsonNode definition) {
    JsonNode copy = definition.deepCopy();
    strip(copy);
    return copy;
  }

  private static void strip(JsonNode node) {
    if (node instanceof ObjectNode o) {
      o.remove("readOnlyConfirmedFor");
      o.remove("readOnlyConfirmed");
      o.forEach(TemplateService::strip);
    } else if (node != null && node.isArray()) {
      node.forEach(TemplateService::strip);
    }
  }

  /** {@code PUT /api/templates/{id} {definition, baseVersion}}: a new version if it compiles. */
  public SaveResult update(String id, JsonNode definition, int baseVersion, String note, String reason) {
    TemplateEntity t = readTx.execute(s -> require(id));
    if (t.getConnectionId() == null) {
      throw ProblemException.conflict("Template invalid", "Its connection was deleted: the template can't run.");
    }
    if (t.getCurrentVersion() != baseVersion) {
      throw new OptimisticLockingFailureException("Template " + id + " is at version " + t.getCurrentVersion());
    }
    ConnectionConfigs.Stored runsOn = connections.byId(t.getConnectionId()).orElseThrow();
    TemplateKind templateKind = kind(t.getKind().kindId());
    requireRunsOn(templateKind, runsOn);
    TemplateKind.CompileResult result = compile(templateKind, definition, runsOn.config(), id);
    if (!result.ok()) {
      return new SaveResult(info(t), result);
    }
    writeTx.executeWithoutResult(s -> {
      TemplateEntity row = require(id);
      // The row is signed again below: it must verify here, and still run on what the definition was compiled for.
      requireVerified(row);
      if (!runsOn.id().equals(row.getConnectionId())) {
        throw new OptimisticLockingFailureException("Template " + id + " moved to another connection");
      }
      if (row.getCurrentVersion() != baseVersion) {
        throw new OptimisticLockingFailureException("Template " + id + " is at version " + row.getCurrentVersion());
      }
      int next = row.getCurrentVersion() + 1;
      appendVersion(row, next, definition, result, note);
      setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.CONNECTION,
          "Template " + row.getName() + " on " + runsOn.name(), "v" + baseVersion, "v" + next, reason,
          runsOn.config().env(), null, runsOn.name(), null));
      publisher.publishEvent(new TemplateChanged(id, row.getConnectionId()));
    });
    return new SaveResult(get(id), result);
  }

  /** {@code POST /api/templates/{id}/preview {arguments}}: the exact action, no I/O. */
  public ActionPreview preview(String id, ObjectNode arguments) {
    TemplateEntity t = readTx.execute(s -> require(id));
    if (t.getConnectionId() == null) {
      throw ProblemException.conflict("Template invalid", "Its connection was deleted: the template can't run.");
    }
    ConnectionConfig cfg = connections.require(t.getConnectionId());
    TemplateVersionEntity v = verifiedVersion(t).orElseThrow(() -> ProblemException.conflict(
        "Changed outside GateRook", "Template " + t.getName() + " was changed outside GateRook."));
    return kind(t.getKind().kindId()).preview(rowMac.tree(v.getDefinition()), arguments, cfg);
  }

  public void setStatus(String id, boolean active, String reason) {
    writeTx.executeWithoutResult(s -> {
      TemplateEntity t = require(id);
      requireVerified(t);
      if (t.getStatus() == TemplateStatus.INVALID) {
        throw ProblemException.conflict("Template invalid", "Its connection was deleted: the template can't run.");
      }
      TemplateStatus next = active ? TemplateStatus.ACTIVE : TemplateStatus.DISABLED;
      if (t.getStatus() == next) {
        return;
      }
      t.setStatus(next, clock.instant());
      saveSigned(t);
      setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.CONNECTION,
          "Template " + t.getName(), active ? "Disabled" : "Active", active ? "Active" : "Disabled", reason, null,
          null, null, null));
      publisher.publishEvent(new TemplateChanged(id, t.getConnectionId()));
    });
  }

  /** {@code DELETE /api/templates/{id}}: its versions go with it; calls already audited keep their facets. */
  public void delete(String id, String reason) {
    writeTx.executeWithoutResult(s -> {
      TemplateEntity t = require(id);
      String connectionId = t.getConnectionId();
      templates.delete(t);
      templates.flush();
      setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.CONNECTION,
          "Template " + t.getName() + " deleted", "v" + t.getCurrentVersion(), null, reason, null, null, null, null));
      publisher.publishEvent(new TemplateChanged(id, connectionId));
    });
  }

  /**
   * Its connection is being deleted: every template it ran becomes invalid (connection_id NULL). A detached template
   * never runs again, but a row that didn't verify is still not signed: it stays reported until the person reviews it.
   */
  @Override
  public Removal onRemoving(String connectionId, String connectionName, String reason) {
    List<String> names = new ArrayList<>();
    for (TemplateEntity t : templates.findByConnectionId(connectionId)) {
      boolean verified = verify(t);
      t.detach(clock.instant());
      if (verified) {
        saveSigned(t);
      }
      names.add(t.getName());
      publisher.publishEvent(new TemplateChanged(t.getId(), null));
    }
    templates.flush();
    return new Removal(List.of(), 0, List.copyOf(names), List.of());
  }

  /**
   * The person reviewed a template changed outside the core and keeps it as stored (Setup change): its row is signed
   * again. Only what an ordinary change could have produced is kept: it runs on a connection that verifies and is of a
   * type its kind serves (or on none, and then it is invalid), and its current version is the newest one, which must
   * verify itself: versions are append-only and are never signed again, so a version changed outside the core leaves
   * only deleting the template.
   */
  public TemplateInfo confirmIntegrity(String id, String reason) {
    writeTx.executeWithoutResult(s -> {
      TemplateEntity t = require(id);
      if (verify(t) && verifiedVersion(t).isPresent()) {
        return;
      }
      String runsOnName = null;
      if (t.getConnectionId() == null) {
        if (t.getStatus() != TemplateStatus.INVALID) {
          throw ProblemException.unprocessable("Can't keep it as stored", "Template " + t.getName() + " runs on no "
              + "connection, so it can only be invalid. Delete it or create it again.", "/status");
        }
      } else {
        ConnectionConfigs.Stored runsOn = connections.byId(t.getConnectionId()).orElseThrow();
        TemplateKind kind = registry.templateKinds().get(t.getKind().kindId());
        if (!runsOn.verified() || kind == null || !kind.connectionTypes().contains(runsOn.config().type())) {
          throw ProblemException.unprocessable("Can't keep it as stored", "Template " + t.getName() + " runs on "
              + runsOn.name() + ", which " + (!runsOn.verified() ? "was changed outside GateRook too: review it "
              + "first." : "doesn't serve " + t.getKind().kindId() + " templates."), "/connection");
        }
        if (t.getStatus() == TemplateStatus.INVALID) {
          throw ProblemException.unprocessable("Can't keep it as stored", "Template " + t.getName() + " is invalid "
              + "but runs on " + runsOn.name() + ". Delete it or create it again.", "/status");
        }
        runsOnName = runsOn.name();
      }
      Optional<String> versionProblem = versionProblem(t);
      if (versionProblem.isPresent()) {
        throw ProblemException.unprocessable("Can't keep it as stored", versionProblem.get(), "/version");
      }
      saveSigned(t);
      alerts.resolve("template", t.getId());
      setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.CONNECTION,
          "Template " + t.getName() + " changed outside GateRook", "Unverified", "Kept as stored: v"
              + t.getCurrentVersion() + ", " + KebabCaseEnumConverter.idOf(t.getStatus())
              + (runsOnName == null ? "" : ", runs on " + runsOnName), reason, null, null, runsOnName, null));
      publisher.publishEvent(new TemplateChanged(t.getId(), t.getConnectionId()));
    });
    return get(id);
  }

  // ── Internals ───────────────────────────────────────────────────────────────────────────────────────────────

  private TemplateKind.CompileResult compile(TemplateKind kind, JsonNode definition, ConnectionConfig runsOn,
      String templateId) {
    if (definition == null || !definition.isObject()) {
      throw ProblemException.unprocessable("Invalid template", "The definition must be an object.", "/definition");
    }
    TemplateKind.CompileResult result = kind.compile(definition, runsOn);
    List<String> errors = new ArrayList<>(result.errors());
    Set<String> taken = new HashSet<>();
    for (Compiled other : compiledFor(runsOn.id())) {
      if (!other.templateId().equals(templateId)) {
        other.tools().forEach(t -> taken.add(t.definition().name()));
      }
    }
    Set<String> own = new HashSet<>();
    for (TemplateKind.CompiledTool tool : result.tools()) {
      String name = tool.definition().name();
      if (!TOOL_NAME.matcher(name).matches()) {
        errors.add(name + ": use lowercase letters, digits and _");
      } else if (registry.isReserved(name)) {
        errors.add(registry.reservedProblem(name).orElseThrow());
      } else if (taken.contains(name) || !own.add(name)) {
        errors.add(name + " is already a tool on " + runsOn.name());
      }
    }
    List<String> warnings = new ArrayList<>(result.warnings());
    warnings.addAll(sharedNames(own, runsOn.id()));
    return errors.equals(result.errors()) && warnings.equals(result.warnings()) ? result
        : new TemplateKind.CompileResult(result.tools(), result.contributions(), List.copyOf(errors),
            List.copyOf(warnings), result.digest());
  }

  /**
   * Names a template of another connection already compiles to. Allowed, since templates of different connections
   * rarely meet; but an endpoint with both connections publishes neither (its tool list says why), so say it now.
   */
  private List<String> sharedNames(Set<String> names, String connectionId) {
    if (names.isEmpty()) {
      return List.of();
    }
    Set<String> others = new java.util.TreeSet<>();
    readTx.execute(s -> templates.findAllByOrderByNameAsc()).stream()
        .filter(t -> t.getStatus() == TemplateStatus.ACTIVE && t.getConnectionId() != null
            && !t.getConnectionId().equals(connectionId))
        .forEach(t -> others.add(t.getConnectionId()));
    List<String> out = new ArrayList<>();
    for (String other : others) {
      String otherName = connections.byId(other).map(ConnectionConfigs.Stored::name).orElse(null);
      for (Compiled c : compiledFor(other)) {
        for (TemplateKind.CompiledTool t : c.tools()) {
          if (otherName != null && names.contains(t.definition().name())) {
            out.add(t.definition().name() + " is also a template tool on " + otherName + ": an endpoint with both "
                + "connections publishes neither.");
          }
        }
      }
    }
    return out;
  }

  private void appendVersion(TemplateEntity t, int number, JsonNode definition, TemplateKind.CompileResult result,
      String note) {
    Map<String, Object> natures = natures(result);
    Map<String, Object> definitionMap = map(definition);
    String digest = result.digest() == null ? ai.msrv.gaterook.core.platform.CanonicalJson.sha256Hex(definition)
        : result.digest();
    versions.save(new TemplateVersionEntity(t, number, definitionMap, null, digest, natures, note,
        versionId -> rowMac.sign(TABLE, versionId, number, macFields(t.getId(), number, definitionMap, digest,
            natures)), clock.instant()));
    t.advanceTo(number, clock.instant());
    saveSigned(t);
  }

  /** Flushes the template row (settling its version) and signs it. */
  private void saveSigned(TemplateEntity t) {
    TemplateEntity saved = templates.saveAndFlush(t);
    saved.setMac(rowMac.sign(TEMPLATE_TABLE, saved.getId(), saved.getVersion(), templateFields(saved)));
    templates.saveAndFlush(saved);
  }

  private boolean verify(TemplateEntity t) {
    if (rowMac.verify(TEMPLATE_TABLE, t.getId(), t.getVersion(), templateFields(t), t.getMac())) {
      return true;
    }
    alerts.report("template", t.getId(), t.getName(), "template row");
    return false;
  }

  private void requireVerified(TemplateEntity t) {
    if (!verify(t)) {
      throw ProblemException.conflict("Changed outside GateRook", "Template " + t.getName() + " was changed "
          + "outside GateRook. Review it first.");
    }
  }

  /** What the template row signs: what runs (kind, name), where (Runs on), which version and whether it may. */
  private JsonNode templateFields(TemplateEntity t) {
    ObjectNode f = rowMac.fields();
    f.put("kind", t.getKind().kindId());
    f.put("name", t.getName());
    f.put("connectionId", t.getConnectionId());
    f.put("currentVersion", t.getCurrentVersion());
    f.put("status", KebabCaseEnumConverter.idOf(t.getStatus()));
    f.put("catalogRef", t.getCatalogRef());
    return f;
  }

  private Optional<Compiled> compileStored(TemplateEntity t, ConnectionConfig cfg) {
    Optional<TemplateVersionEntity> v = readTx.execute(s -> verifiedVersion(t));
    if (v.isEmpty()) {
      return Optional.empty();
    }
    TemplateKind k = registry.templateKinds().get(t.getKind().kindId());
    if (k == null) {
      return Optional.empty();
    }
    TemplateKind.CompileResult result = k.compile(rowMac.tree(v.get().getDefinition()), cfg);
    if (!result.ok()) {
      return Optional.empty();
    }
    return Optional.of(new Compiled(t.getId(), t.getName(), t.getKind().kindId(), t.getCurrentVersion(),
        t.getConnectionId(), List.copyOf(result.tools()), List.copyOf(result.contributions()), v.get().getDigest()));
  }

  /**
   * The current version of a template whose row verifies, if it is the newest version and verifies itself. Versions
   * are append-only (a trigger refuses UPDATE), so pointing the row at an older one is a rollback the core never makes.
   */
  private Optional<TemplateVersionEntity> verifiedVersion(TemplateEntity t) {
    if (!verify(t)) {
      return Optional.empty();
    }
    Optional<String> problem = versionProblem(t);
    if (problem.isPresent()) {
      alerts.report("template", t.getId(), t.getName(), "template version " + t.getCurrentVersion());
      return Optional.empty();
    }
    return versions.findByTemplateIdAndTemplateVersion(t.getId(), t.getCurrentVersion());
  }

  /** Why the current version can't be used (missing, not the newest, doesn't verify), or empty. */
  private Optional<String> versionProblem(TemplateEntity t) {
    List<TemplateVersionEntity> all = versions.findByTemplateIdOrderByTemplateVersionDesc(t.getId());
    if (all.isEmpty() || all.get(0).getTemplateVersion() != t.getCurrentVersion()) {
      return Optional.of("Template " + t.getName() + " points at v" + t.getCurrentVersion() + ", but its newest "
          + "version is " + (all.isEmpty() ? "missing" : "v" + all.get(0).getTemplateVersion()) + ".");
    }
    TemplateVersionEntity row = all.get(0);
    if (!rowMac.verify(TABLE, row.getId(), row.getTemplateVersion(), macFields(t.getId(), row.getTemplateVersion(),
        row.getDefinition(), row.getDigest(), row.getNatures()), row.getMac())) {
      return Optional.of("Version " + row.getTemplateVersion() + " of template " + t.getName() + " was changed "
          + "outside GateRook. Versions are never signed again: delete the template and create it again.");
    }
    return Optional.empty();
  }

  private JsonNode macFields(String templateId, int number, Map<String, Object> definition, String digest,
      Map<String, Object> natures) {
    ObjectNode f = rowMac.fields();
    f.put("templateId", templateId);
    f.put("templateVersion", number);
    f.set("definition", rowMac.tree(definition));
    f.put("digest", digest);
    f.set("natures", rowMac.tree(natures));
    return f;
  }

  private static Map<String, Object> natures(TemplateKind.CompileResult result) {
    Map<String, Object> out = new LinkedHashMap<>();
    int read = 0;
    int mutation = 0;
    for (TemplateKind.CompiledTool tool : result.tools()) {
      if (tool.definition().nature() == ToolDefinition.Nature.READ) {
        read++;
      } else {
        mutation++;
      }
    }
    out.put("read", read);
    out.put("mutation", mutation);
    return out;
  }

  private TemplateInfo info(TemplateEntity t) {
    String connectionName = t.getConnectionId() == null ? null
        : connections.byId(t.getConnectionId()).map(ConnectionConfigs.Stored::name).orElse(null);
    Optional<TemplateVersionEntity> current = versions.findByTemplateIdAndTemplateVersion(t.getId(),
        t.getCurrentVersion());
    JsonNode natures = current.map(v -> rowMac.tree(v.getNatures())).orElse(null);
    boolean verified = verify(t) && versionProblem(t).isEmpty();
    JsonNode params = !verified || "openapi".equals(t.getKind().kindId()) ? null
        : parameters(current.map(v -> rowMac.tree(v.getDefinition())).orElse(null));
    return new TemplateInfo(t.getId(), t.getKind().kindId(), t.getName(), t.getConnectionId(), connectionName,
        t.getCurrentVersion(), KebabCaseEnumConverter.idOf(t.getStatus()), t.getCatalogRef(), natures,
        t.getUpdatedAt(), verified, params);
  }

  /**
   * The parameters a definition declares, in order: {@code params} as an array of {@code {name, type, …}} (SQL query,
   * runbook), or as a map name → {@code {type, …}} (an older form); empty without any.
   */
  static JsonNode parameters(JsonNode definition) {
    var out = JsonNodeFactory.instance.arrayNode();
    JsonNode declared = definition == null ? null : definition.get("params");
    if (declared != null && declared.isArray()) {
      declared.forEach(p -> out.add(p.deepCopy()));
    } else if (declared != null && declared.isObject()) {
      for (Map.Entry<String, JsonNode> p : declared.properties()) {
        ObjectNode param = out.addObject().put("name", p.getKey());
        if (p.getValue().isObject()) {
          p.getValue().properties().forEach(e -> param.set(e.getKey(), e.getValue().deepCopy()));
        }
      }
    }
    return out;
  }

  /**
   * The current version's definition as stored (the SQL or the command, its parameters, the read-only confirmation):
   * what the editor opens and {@code PUT …{definition, baseVersion}} starts from. Empty when the template or that
   * version doesn't verify: a definition changed outside GateRook is never shown as the gateway's own.
   */
  public Optional<JsonNode> definition(String id) {
    return readTx.execute(s -> verifiedVersion(require(id)).map(v -> (JsonNode) rowMac.tree(v.getDefinition())
        .deepCopy()));
  }

  private TemplateEntity require(String id) {
    return templates.findById(id).orElseThrow(() -> ProblemException.notFound("Template not found",
        "There is no template " + id + "."));
  }

  private void requireRunsOn(TemplateKind kind, ConnectionConfigs.Stored runsOn) {
    if (!runsOn.verified()) {
      throw ProblemException.conflict("Changed outside GateRook", runsOn.name() + " was changed outside GateRook. Review it first.");
    }
    if (!kind.connectionTypes().contains(runsOn.config().type())) {
      throw ProblemException.unprocessable("Wrong connection", kind.kind() + " templates run on "
          + String.join(", ", kind.connectionTypes()) + " connections.", "/connection");
    }
  }

  private TemplateKind kind(String kind) {
    TemplateKind k = registry.templateKinds().get(kind);
    if (k == null) {
      throw ProblemException.unprocessable("Unknown template kind", "No connector offers " + kind + " templates.",
          "/kind");
    }
    return k;
  }

  private static TemplateType type(String kind) {
    for (TemplateType t : TemplateType.values()) {
      if (t.kindId().equals(kind)) {
        return t;
      }
    }
    throw ProblemException.unprocessable("Unknown template kind", "Templates are sql-query, ssh-runbook or openapi.",
        "/kind");
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> map(JsonNode tree) {
    return json.treeToValue(tree, Map.class);
  }
}
