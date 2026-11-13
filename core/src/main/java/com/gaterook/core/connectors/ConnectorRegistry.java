package com.gaterook.core.connectors;

import com.gaterook.connector.api.ActionPreview;
import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.Connector;
import com.gaterook.connector.api.ConnectorManifest;
import com.gaterook.connector.api.TemplateKind;
import com.gaterook.connector.api.ToolDefinition;
import com.gaterook.core.connectors.internal.FakeSqlConnector;
import com.gaterook.core.connectors.internal.persistence.ConnectorTypeEntity;
import com.gaterook.core.connectors.internal.persistence.ConnectorTypeRepository;
import com.gaterook.core.connectors.internal.persistence.PluginEntity;
import com.gaterook.core.connectors.internal.persistence.PluginRepository;
import com.gaterook.core.platform.BuildFlags;
import com.gaterook.core.platform.CanonicalJson;
import com.gaterook.core.platform.IntegrityAlerts;
import com.gaterook.core.platform.MasterKeyChanged;
import com.gaterook.core.platform.MasterKeyLock;
import com.gaterook.core.platform.ProblemException;
import com.gaterook.core.platform.RowMac;
import com.gaterook.core.platform.SetupChangeRecorder;
import com.gaterook.core.platform.SetupChangeRecorder.SetupChange;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Every connector the core can use (ARCHITECTURE.md §2.3): the built-in {@link Connector} beans of the connector
 * modules plus whatever each {@link ConnectorSource} contributes (plugins, MCP servers). For the policy, approvals and
 * audit they are all one interface.
 *
 * <p>At start it checks the tools of every type (names, no collision with the core's tools, same-named tools of two
 * types equal up to the {@code connection} enum, so that PostgreSQL's and MySQL's {@code sql_query} merge on one
 * endpoint) and fails the start otherwise; then it upserts {@code connector_type}: manifest, its SHA-256 and the
 * implementation version, signed with {@link RowMac}. {@code enabled} is the person's switch and is protected by the
 * row MAC: a row that doesn't verify counts as off until reviewed ({@link #confirmIntegrity}). While the core is
 * locked (no master key) nothing can be signed, so the upsert waits for the key.
 */
@Component
public class ConnectorRegistry implements SmartInitializingSingleton {

  /** Tools of the core itself (ARCHITECTURE.md §3): no connector, plugin, MCP server or template may take these. */
  public static final Set<String> CORE_TOOLS = Set.of("list_connections", "describe_connection", "approval_status",
      "approval_card", "approval_card_action", "connection_import_schema", "connection_import_progress",
      "submit_connection_import", "open_connection_import");

  private static final Pattern TOOL_NAME = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");
  private static final Pattern TYPE_ID = Pattern.compile("^[a-z][a-z0-9-]{0,47}$");
  private static final String TABLE = "connector_type";
  private static final Logger log = LoggerFactory.getLogger(ConnectorRegistry.class);

  private final Map<String, Connector> builtIn;
  private final List<ConnectorSource> sources;
  private final ConnectorTypeRepository repository;
  private final PluginRepository plugins;
  private final RowMac rowMac;
  private final MasterKeyLock lock;
  private final IntegrityAlerts alerts;
  private final ObjectProvider<SetupChangeRecorder> setupChanges;
  private final ApplicationEventPublisher publisher;
  private final JsonMapper json;
  private final Clock clock;
  private final TransactionTemplate writeTx;
  private final TransactionTemplate readTx;
  /** What the database said at the last upsert: id → (enabled, verified). Until then every type counts as off. */
  private final Map<String, Stored> stored = new ConcurrentHashMap<>();
  /** Types contributed by sources at the last upsert. */
  private volatile Map<String, ConnectorManifest> sourceTypes = Map.of();
  /** The installed package (the {@code plugin} row) behind each source type that has one. */
  private volatile Map<String, String> sourcePackages = Map.of();
  /** The connectors of GateRook's own types that sources deliver (connector modules), by type. */
  private volatile Map<String, Connector> delivered = Map.of();
  /**
   * GateRook's own types that an earlier build compiled in, that this build doesn't have and that connections still
   * use, by type: known from their signed rows and answered by a stand-in until what delivers them arrives
   * ({@link MissingTypes#standIn}).
   */
  private volatile Map<String, Connector> awaited = Map.of();
  /** The tools of the compiled-in types by name: what a tool that a delivered type shares with them is. */
  private final Map<String, ToolDefinition> familyTools;
  /** The names and families of the compiled-in tools and the core's own. */
  private final Set<String> reserved;
  private final Set<String> reservedPrefixes;
  /** The same of GateRook's own connectors that sources deliver: the installed connector modules. */
  private volatile Set<String> deliveredNames = Set.of();
  private volatile Set<String> deliveredPrefixes = Set.of();
  /** The names of those that sources only know of: the modules their channel lists. */
  private volatile Set<String> listedNames = Set.of();
  private volatile Set<String> listedPrefixes = Set.of();

  private record Stored(boolean enabled, boolean verified) {}

  /**
   * What can make a connector type arrive that this registry doesn't have: GateRook's connector modules, which
   * install by themselves when something needs one of their types (docs/connector-delivery.md).
   */
  public interface MissingTypes {

    /**
     * Something looked for {@code typeId} and it isn't registered. Never blocks and never fails: starts whatever
     * makes the type arrive, if anything does.
     */
    void wanted(String typeId);

    /**
     * Makes {@code typeId} arrive and waits up to {@code budget} for it.
     *
     * @return whether something delivers the type at all (it may still be on its way when the wait ends)
     */
    boolean await(String typeId, Duration budget);

    /**
     * The connector that answers for {@code known} until the type arrives: a type GateRook itself added in an earlier
     * build, which this build doesn't compile in and whose row is still here. Its connections keep their tools on
     * their endpoints meanwhile, as the row describes them; the stand-in runs nothing itself. Empty when the type
     * isn't worth keeping: nothing here can make it arrive, or no connection uses it.
     */
    default Optional<Connector> standIn(ConnectorManifest known) {
      return Optional.empty();
    }
  }

  /** How long a request that needs a type waits for its connector module to install. */
  public static final Duration INSTALL_WAIT = Duration.ofSeconds(90);

  private volatile MissingTypes missingTypes;

  /** Sets what is told of types that are looked for and aren't registered. */
  public void missingTypes(MissingTypes handler) {
    this.missingTypes = handler;
  }

  public ConnectorRegistry(List<Connector> connectors, List<ConnectorSource> sources,
      ConnectorTypeRepository repository, PluginRepository plugins, RowMac rowMac, MasterKeyLock lock,
      IntegrityAlerts alerts,
      ObjectProvider<SetupChangeRecorder> setupChanges, ApplicationEventPublisher publisher, JsonMapper json,
      Clock clock, PlatformTransactionManager transactions,
      @Value("${gateway.test.fake-connector:false}") boolean fakeConnector) {
    Map<String, Connector> byId = new LinkedHashMap<>();
    for (Connector connector : connectors) {
      ConnectorManifest m = connector.manifest();
      if (!TYPE_ID.matcher(m.id()).matches()) {
        throw new IllegalStateException("Connector type id " + m.id() + " is not [a-z][a-z0-9-]{0,47}");
      }
      if (byId.putIfAbsent(m.id(), connector) != null) {
        throw new IllegalStateException("Two connector beans declare the type " + m.id());
      }
    }
    if (fakeConnector) {
      addFake(byId);
    }
    checkTools(byId.values().stream().map(Connector::manifest).toList());
    // In registration order: the test connector stays last, so a real connector's template kind schema leads.
    this.builtIn = Collections.unmodifiableMap(new LinkedHashMap<>(byId));
    this.sources = List.copyOf(sources);
    this.repository = repository;
    this.plugins = plugins;
    this.rowMac = rowMac;
    this.lock = lock;
    this.alerts = alerts;
    this.setupChanges = setupChanges;
    this.publisher = publisher;
    this.json = json;
    this.clock = clock;
    this.writeTx = new TransactionTemplate(transactions);
    this.readTx = new TransactionTemplate(transactions);
    this.readTx.setReadOnly(true);
    Map<String, ToolDefinition> family = new HashMap<>();
    byId.values().forEach(c -> c.manifest().tools().forEach(tool -> family.putIfAbsent(tool.name(), tool)));
    this.familyTools = Map.copyOf(family);
    this.reserved = reservedNames(byId.values().stream().map(Connector::manifest).toList());
    this.reservedPrefixes = reservedPrefixes(byId.values().stream().map(Connector::manifest).toList());
  }

  /**
   * The test connector (ARCHITECTURE.md §10, M1.7), last so that every template kind a real connector declares stays
   * its own. Shaped like the first built-in type with {@code sql_query}, so the tools stay equal and merge. A release
   * ignores the switch: it is a test override like the others of {@link BuildFlags}.
   */
  private static void addFake(Map<String, Connector> byId) {
    if (BuildFlags.RELEASE) {
      log.warn("gateway.test.fake-connector is ignored: this is a release build");
      return;
    }
    Optional<ConnectorManifest> sql = byId.values().stream().map(Connector::manifest)
        .filter(m -> m.tools().stream().anyMatch(t -> t.name().equals("sql_query"))).findFirst();
    FakeSqlConnector fake = FakeSqlConnector.like(sql);
    if (byId.putIfAbsent(FakeSqlConnector.TYPE, fake) != null) {
      throw new IllegalStateException("A connector bean already declares the type " + FakeSqlConnector.TYPE);
    }
    log.warn("Test connector {} is on (gateway.test.fake-connector): it answers from memory, never from a database",
        FakeSqlConnector.TYPE);
  }

  // ── Start ───────────────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public void afterSingletonsInstantiated() {
    if (lock.locked()) {
      log.info("Connector types are synchronized once the master key is available");
      return;
    }
    synchronize();
  }

  /** The key came back (Restore) or was replaced (Create): the rows can be checked and signed now. */
  @Async
  @TransactionalEventListener
  public void onMasterKeyChanged(MasterKeyChanged event) {
    if (event.kind() == MasterKeyChanged.Kind.RESTORED || event.kind() == MasterKeyChanged.Kind.CREATED
        || event.kind() == MasterKeyChanged.Kind.REPLACED_LOST) {
      synchronize();
    }
  }

  /** Upserts every type into {@code connector_type} and re-reads the switches. */
  public synchronized void synchronize() {
    Map<String, ConnectorManifest> fromSources = new LinkedHashMap<>();
    Map<String, String> packages = new HashMap<>();
    Map<String, Connector> ownConnectors = new LinkedHashMap<>();
    // GateRook's own types first: foreign code then can't take a name one of them has. The types of a module that
    // GateRook didn't sign (a person's own, from a file) come after the signed ones, and pass for none of them.
    for (boolean unverified : new boolean[] {false, true}) {
      for (ConnectorSource source : sources) {
        Map<String, Connector> connectors = new HashMap<>();
        source.firstPartyConnectors().forEach(c -> connectors.put(c.manifest().id(), c));
        for (ConnectorManifest m : source.types()) {
          if (!source.firstParty(m.id()) || source.unverified(m.id()) != unverified) {
            continue;
          }
          if (builtIn.containsKey(m.id())) {
            // Compiled into this build as well: the compiled-in connector serves the type.
            continue;
          }
          String ownPackage = source.packageOf(m.id()).orElse(null);
          Optional<String> problem = firstPartyProblem(m, fromSources);
          if (problem.isEmpty() && unverified) {
            problem = unverifiedProblem(m, fromSources, packages, ownPackage);
          }
          if (problem.isPresent()) {
            log.error("Connector type {} is not registered: {}", m.id(), problem.get());
            continue;
          }
          fromSources.put(m.id(), unverified ? m : inFamily(m));
          if (ownPackage != null) {
            packages.put(m.id(), ownPackage);
          }
          if (connectors.containsKey(m.id())) {
            ownConnectors.put(m.id(), connectors.get(m.id()));
          }
        }
      }
    }
    // Almost every start finds every row as it was left. Reading them in a read-only transaction keeps Hibernate
    // from taking a snapshot of each row (a deep copy of the JSON manifest, the costliest part of the native start);
    // only new or changed types are written, re-read and re-verified inside the write transaction.
    Map<String, ConnectorTypeEntity> rows = new HashMap<>();
    readTx.executeWithoutResult(s -> repository.findAll().forEach(row -> rows.put(row.getId(), row)));
    Map<String, Connector> standIns = standIns(rows, fromSources);
    Set<String> names = new TreeSet<>();
    fromSources.values().forEach(m -> m.tools().forEach(t -> names.add(t.name())));
    standIns.values().forEach(c -> c.manifest().tools().forEach(t -> names.add(t.name())));
    deliveredNames = Set.copyOf(names);
    deliveredPrefixes = prefixes(names);
    Set<String> listed = new TreeSet<>();
    sources.forEach(source -> listed.addAll(source.reservedToolNames()));
    listedNames = Set.copyOf(listed);
    listedPrefixes = prefixes(listed);
    for (ConnectorSource source : sources) {
      for (ConnectorManifest m : source.types()) {
        if (source.firstParty(m.id())) {
          continue;
        }
        Optional<String> problem = standIns.containsKey(m.id())
            ? Optional.of("another connector already uses this id") : sourceTypeProblem(m, fromSources);
        if (problem.isPresent()) {
          log.error("Connector type {} is not registered: {}", m.id(), problem.get());
          continue;
        }
        fromSources.put(m.id(), m);
        source.packageOf(m.id()).ifPresent(p -> packages.put(m.id(), p));
      }
    }
    sourcePackages = Map.copyOf(packages);
    delivered = Collections.unmodifiableMap(ownConnectors);
    sourceTypes = Map.copyOf(fromSources);
    standIns.keySet().forEach(id -> stored.put(id, new Stored(rows.get(id).isEnabled(), true)));
    awaited = Collections.unmodifiableMap(standIns);
    List<ConnectorManifest> all = new ArrayList<>(builtIn.values().stream().map(Connector::manifest).toList());
    all.addAll(fromSources.values());

    List<Upsert> writes = new ArrayList<>();
    for (ConnectorManifest m : all) {
      ObjectNode tree = ManifestCodec.write(m);
      String sha = CanonicalJson.sha256Hex(tree);
      ConnectorTypeEntity row = rows.get(m.id());
      if (row == null) {
        writes.add(new Upsert(m, tree, sha));
      } else if (!verify(row)) {
        // Never re-sign a row we can't vouch for: its switch may have been flipped outside the core.
        alerts.report("connector-type", m.id(), m.name(), "connector_type row");
        stored.put(m.id(), new Stored(row.isEnabled(), false));
      } else if (!sha.equals(row.getManifestSha256()) || !m.version().equals(row.getImplVersion())
          || m.kind() != row.getKind() || packageChanged(m, row)) {
        writes.add(new Upsert(m, tree, sha));
      } else {
        stored.put(m.id(), new Stored(row.isEnabled(), true));
      }
    }
    if (!writes.isEmpty()) {
      writeTx.executeWithoutResult(s -> writes.forEach(this::upsert));
    }
  }

  /**
   * The types that are awaited: rows of GateRook's own types that no package delivered (an earlier build compiled
   * them in), that neither this build nor a source has now, and for which the {@link MissingTypes} handler gives a
   * stand-in. A row that doesn't verify, or whose manifest this build can't hold beside its own types, is left out:
   * the type then isn't here until it arrives, as any type that was never here.
   */
  private Map<String, Connector> standIns(Map<String, ConnectorTypeEntity> rows,
      Map<String, ConnectorManifest> fromSources) {
    MissingTypes handler = missingTypes;
    Map<String, Connector> out = new LinkedHashMap<>();
    if (handler == null) {
      return out;
    }
    Map<String, ConnectorManifest> taken = new LinkedHashMap<>(fromSources);
    for (ConnectorTypeEntity row : rows.values()) {
      String id = row.getId();
      if (builtIn.containsKey(id) || fromSources.containsKey(id) || row.getKind() != ConnectorManifest.Kind.BUILT_IN
          || row.getPlugin() != null || !verify(row)) {
        continue;
      }
      try {
        ConnectorManifest known = ManifestCodec.read(json.valueToTree(row.getManifest()));
        Optional<String> problem = known.id().equals(id) ? firstPartyProblem(known, taken)
            : Optional.of("its row describes " + known.id());
        if (problem.isPresent()) {
          log.warn("Connector type {} isn't kept until its connector module arrives: {}", id, problem.get());
          continue;
        }
        // Its shared tools as this build words them: the row has an earlier build's wording.
        ConnectorManifest current = inFamily(known);
        Optional<Connector> standIn = handler.standIn(current);
        if (standIn.isPresent()) {
          out.put(id, standIn.get());
          taken.put(id, current);
        }
      } catch (RuntimeException e) {
        log.warn("Connector type {} isn't kept until its connector module arrives: {}", id, e.toString());
      }
    }
    return out;
  }

  private record Upsert(ConnectorManifest manifest, ObjectNode tree, String sha) {}

  private void upsert(Upsert u) {
    ConnectorManifest m = u.manifest();
    ConnectorTypeEntity row = repository.findById(m.id()).orElse(null);
    // A delivered type points at its package's row (connector_type.plugin_id): a plugin's own, a module's.
    String packageId = sourcePackages.get(m.id());
    PluginEntity plugin = packageId == null ? null : plugins.findById(packageId).orElse(null);
    if (row == null) {
      row = repository.saveAndFlush(new ConnectorTypeEntity(m.id(), m.kind(), m.version(), map(u.tree()), u.sha(),
          plugin, clock.instant()));
      sign(row);
      stored.put(m.id(), new Stored(true, true));
      return;
    }
    if (!verify(row)) {
      alerts.report("connector-type", m.id(), m.name(), "connector_type row");
      stored.put(m.id(), new Stored(row.isEnabled(), false));
      return;
    }
    boolean changed = !u.sha().equals(row.getManifestSha256()) || !m.version().equals(row.getImplVersion())
        || m.kind() != row.getKind();
    boolean link = packageChanged(m, row);
    if (changed || link) {
      if (changed) {
        row.updateManifest(m.kind(), m.version(), map(u.tree()), u.sha(), clock.instant());
      }
      if (link) {
        row.setPlugin(plugin);
      }
      row = repository.saveAndFlush(row);
      sign(row);
      // Another package delivers the type now (a compiled-in type that became a module, or the other way round): its
      // connections are served by other code, so their endpoints rebuild even when the manifest is the same.
      publisher.publishEvent(new ConnectorTypeChanged(m.id()));
    }
    stored.put(m.id(), new Stored(row.isEnabled(), true));
  }

  /**
   * Whether a type's row points at another package than the one that delivers the type now: none yet (rows of K4,
   * a type that became a module), another one, or one still although the type is compiled in again.
   */
  private boolean packageChanged(ConnectorManifest m, ConnectorTypeEntity row) {
    String packageId = sourcePackages.get(m.id());
    String linked = row.getPlugin() == null ? null : row.getPlugin().getId();
    if (packageId == null) {
      return linked != null;
    }
    return !packageId.equals(linked) && plugins.existsById(packageId);
  }

  // ── Lookups ─────────────────────────────────────────────────────────────────────────────────────────────────

  /** Every known type, in catalog order (group, then name). */
  public List<ConnectorType> types() {
    List<ConnectorType> out = new ArrayList<>();
    for (Connector c : builtIn.values()) {
      out.add(typeOf(c.manifest()));
    }
    for (ConnectorManifest m : sourceTypes.values()) {
      out.add(typeOf(m));
    }
    for (Connector c : awaited.values()) {
      out.add(typeOf(c.manifest()));
    }
    out.sort(Comparator.comparing((ConnectorType t) -> t.manifest().group()).thenComparing(t -> t.manifest().name()));
    return List.copyOf(out);
  }

  /**
   * The type as it is registered now. A type that isn't here but that a connector module adds is asked for in passing
   * ({@link MissingTypes#wanted}): it installs in the background and is here at a later look. So is a type that is
   * awaited: it is answered as its row describes it until its module arrives.
   */
  public Optional<ConnectorType> type(String typeId) {
    Optional<ConnectorType> known = registered(typeId);
    MissingTypes handler = missingTypes;
    if (handler != null && typeId != null && (known.isEmpty() ? TYPE_ID.matcher(typeId).matches()
        : awaited.containsKey(typeId))) {
      handler.wanted(typeId);
    }
    return known;
  }

  /**
   * Waits for {@code typeId} to arrive when a connector module adds it and it isn't installed yet: what a request
   * calls first that is about to work with the type (its form, a new connection, an import). The person is asked
   * nothing. Returns at once for a type that is registered or that nothing delivers, and after {@link #INSTALL_WAIT}
   * at the latest; the caller then looks the type up as usual.
   */
  public void awaitInstalled(String typeId) {
    MissingTypes handler = missingTypes;
    if (handler != null && typeId != null && TYPE_ID.matcher(typeId).matches()
        && (registered(typeId).isEmpty() || awaited.containsKey(typeId))) {
      handler.await(typeId, INSTALL_WAIT);
    }
  }

  /**
   * The types that are awaited: GateRook's own types of an earlier build that this build gets as connector modules
   * and that aren't installed yet, although connections use them. Each is registered as its row describes it.
   */
  public Set<String> awaitedTypes() {
    return awaited.keySet();
  }

  private Optional<ConnectorType> registered(String typeId) {
    Connector c = builtIn.get(typeId);
    if (c != null) {
      return Optional.of(typeOf(c.manifest()));
    }
    ConnectorManifest m = typeId == null ? null : sourceTypes.get(typeId);
    if (m == null && typeId != null) {
      Connector standIn = awaited.get(typeId);
      m = standIn == null ? null : standIn.manifest();
    }
    return m == null ? Optional.empty() : Optional.of(typeOf(m));
  }

  public Optional<ConnectorManifest> manifest(String typeId) {
    return type(typeId).map(ConnectorType::manifest);
  }

  /** The manifest of a type that must exist. */
  public ConnectorManifest requireManifest(String typeId) {
    return manifest(typeId).orElseThrow(() -> new NoSuchElementException("No connector type " + typeId));
  }

  /** Whether the type exists, is switched on and its row verified. */
  public boolean usable(String typeId) {
    return type(typeId).map(ConnectorType::usable).orElse(false);
  }

  /**
   * The connector that serves {@code cfg}: a source's, if one claims the connection (an MCP server connection, a
   * plugin's type), else the built-in bean of its type. Empty for an unknown type. Says nothing about whether the type
   * is switched on: callers check {@link #usable}.
   */
  public Optional<Connector> connectorFor(ConnectionConfig cfg) {
    Connector compiledIn = builtIn.get(cfg.type());
    for (ConnectorSource source : sources) {
      if (compiledIn != null && source.firstParty(cfg.type())) {
        // A module of a type this build compiles in: the compiled-in connector serves its connections.
        continue;
      }
      Optional<Connector> c = source.connectorFor(cfg);
      if (c.isPresent()) {
        return c;
      }
    }
    MissingTypes handler = missingTypes;
    if (compiledIn == null && handler != null && cfg.type() != null) {
      // A connection of a type that isn't here: when a connector module adds it, it is on its way now.
      handler.wanted(cfg.type());
    }
    if (compiledIn != null || cfg.type() == null) {
      return Optional.ofNullable(compiledIn);
    }
    // Awaited: its stand-in answers until the module is here.
    return Optional.ofNullable(awaited.get(cfg.type()));
  }

  /** Whether this build compiles the connector type in (rather than a connector module adding it). */
  public boolean compiledIn(String typeId) {
    return builtIn.containsKey(typeId);
  }

  /**
   * GateRook's own connectors, for broadcasts such as {@code onConnectionChanged}: the compiled-in beans and the
   * connectors of the installed connector modules.
   */
  public Collection<Connector> builtInConnectors() {
    Map<String, Connector> own = delivered;
    if (own.isEmpty()) {
      return builtIn.values();
    }
    List<Connector> all = new ArrayList<>(builtIn.values());
    all.addAll(own.values());
    return List.copyOf(all);
  }

  /**
   * Every no-code template kind the connectors offer ({@code sql-query}, {@code ssh-runbook}, {@code openapi}), by
   * kind. Several connectors may offer the same kind for their own connection types (PostgreSQL and MySQL both
   * {@code sql-query}): the kind then compiles and previews with the connector of the template's Runs on
   * connection, and its form is the first connector's.
   */
  public Map<String, TemplateKind> templateKinds() {
    return templateKinds(builtInConnectors());
  }

  /** The template kinds of {@code connectors} in their order, a kind several of them offer merged into one. */
  static Map<String, TemplateKind> templateKinds(Collection<Connector> connectors) {
    Map<String, List<TemplateKind>> byKind = new LinkedHashMap<>();
    for (Connector c : connectors) {
      for (TemplateKind k : c.templateKinds()) {
        byKind.computeIfAbsent(k.kind(), n -> new ArrayList<>()).add(k);
      }
    }
    Map<String, TemplateKind> out = new LinkedHashMap<>();
    byKind.forEach((kind, kinds) -> out.put(kind, kinds.size() == 1 ? kinds.getFirst() : new SharedKind(kind,
        List.copyOf(kinds))));
    return out;
  }

  /** One template kind offered by several connectors, each for its own connection types. */
  private record SharedKind(String kind, List<TemplateKind> kinds) implements TemplateKind {

    @Override
    public List<String> connectionTypes() {
      return kinds.stream().flatMap(k -> k.connectionTypes().stream()).distinct().toList();
    }

    @Override
    public JsonNode definitionSchema() {
      return kinds.getFirst().definitionSchema();
    }

    @Override
    public CompileResult compile(JsonNode definition, ConnectionConfig runsOn) {
      Optional<TemplateKind> k = of(runsOn);
      return k.isPresent() ? k.get().compile(definition, runsOn) : new CompileResult(List.of(), List.of(),
          List.of(kind + " templates don't run on " + runsOn.type() + " connections."), List.of(), null);
    }

    @Override
    public ActionPreview preview(JsonNode definition, ObjectNode arguments, ConnectionConfig runsOn) {
      return of(runsOn).orElseThrow(() -> new IllegalArgumentException(kind + " templates don't run on "
          + runsOn.type() + " connections")).preview(definition, arguments, runsOn);
    }

    private Optional<TemplateKind> of(ConnectionConfig runsOn) {
      return kinds.stream().filter(k -> k.connectionTypes().contains(runsOn.type())).findFirst();
    }
  }

  /** A tool of a type's manifest. */
  public Optional<ToolDefinition> tool(String typeId, String toolName) {
    return manifest(typeId).flatMap(m -> m.tools().stream().filter(t -> t.name().equals(toolName)).findFirst());
  }

  /**
   * Names no plugin, MCP server or template may publish: the core's tools and every tool of a built-in connector
   * (ARCHITECTURE.md §9 "does not pass itself off as built-in"). See {@link #isReserved} for the prefixes.
   */
  public Set<String> reservedToolNames() {
    if (deliveredNames.isEmpty() && listedNames.isEmpty()) {
      return reserved;
    }
    Set<String> all = new TreeSet<>(reserved);
    all.addAll(deliveredNames);
    all.addAll(listedNames);
    return Set.copyOf(all);
  }

  /**
   * The families of the built-in tools ({@code sql_}, {@code mongo_}, {@code redis_}, {@code ssh_}, {@code logs_},
   * {@code k8s_}, {@code gcp_}, {@code aws_}, {@code http_}): the part of each built-in tool's name up to its first
   * {@code _}. Only built-in connectors name tools that start with one.
   */
  public Set<String> reservedPrefixes() {
    if (deliveredPrefixes.isEmpty() && listedPrefixes.isEmpty()) {
      return reservedPrefixes;
    }
    Set<String> all = new TreeSet<>(reservedPrefixes);
    all.addAll(deliveredPrefixes);
    all.addAll(listedPrefixes);
    return Set.copyOf(all);
  }

  /**
   * Whether a plugin, MCP server or template tool may not be called {@code toolName}: a core tool, a built-in tool or
   * any name in a built-in family ({@code sql_drop_all}, {@code http_post} would pass themselves off as built in).
   */
  public boolean isReserved(String toolName) {
    return reservedProblem(toolName).isPresent();
  }

  /**
   * Why {@code toolName} is reserved ("… is the name of a built-in tool"), or empty. The built-in tools are those of
   * the compiled-in connectors, of the installed connector modules, and of the modules the channel lists: a new
   * plugin, MCP server tool or template takes the name of none of them.
   */
  public Optional<String> reservedProblem(String toolName) {
    return reservedProblem(toolName, true);
  }

  /**
   * @param listed whether the names of modules that are only listed count. They don't for a plugin that is already
   *               installed: it keeps its tools until the module itself is here.
   */
  private Optional<String> reservedProblem(String toolName, boolean listed) {
    if (reserved.contains(toolName) || deliveredNames.contains(toolName)
        || (listed && listedNames.contains(toolName))) {
      return Optional.of(toolName + " is the name of a built-in tool");
    }
    List<Set<String>> families = listed ? List.of(reservedPrefixes, deliveredPrefixes, listedPrefixes)
        : List.of(reservedPrefixes, deliveredPrefixes);
    for (Set<String> prefixes : families) {
      for (String prefix : prefixes) {
        if (toolName.startsWith(prefix)) {
          return Optional.of(toolName + " starts with " + prefix + ", which only built-in tools use");
        }
      }
    }
    return Optional.empty();
  }

  // ── Changes ─────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * Switches a type on or off (Setup change); existing connections keep their rows but serve nothing while off.
   *
   * <p>On the same monitor as {@link #synchronize}: that one reads the rows outside the writer's transaction, and a
   * switch committed between its read and its update of the switches in memory would otherwise be overwritten with
   * the value it read, so a type turned off could keep serving until the next start.
   */
  public synchronized void setEnabled(String typeId, boolean enabled, String reason) {
    ConnectorManifest m = known(typeId);
    writeTx.executeWithoutResult(s -> {
      ConnectorTypeEntity row = repository.findById(typeId).orElseThrow(() -> ProblemException.conflict(
          "Not synchronized yet", m.name() + " isn't recorded yet: the core synchronizes connector types once the "
              + "master key is available."));
      requireVerified(row, m);
      if (row.isEnabled() == enabled) {
        return;
      }
      row.setEnabled(enabled, clock.instant());
      row = repository.saveAndFlush(row);
      sign(row);
      setupChanges.getObject().record(new SetupChange(
          m.kind() == ConnectorManifest.Kind.PLUGIN ? SetupChangeRecorder.What.PLUGIN : SetupChangeRecorder.What.SETTINGS,
          "Connector type " + m.name(), enabled ? "Off" : "On", enabled ? "On" : "Off", reason, null, null, null,
          null));
      storeAfterCommit(typeId, enabled);
      publisher.publishEvent(new ConnectorTypeChanged(typeId));
    });
  }

  /**
   * The person reviewed a type whose row didn't verify and keeps it as stored: the row is signed again with the
   * current key (Setup change). On the monitor of {@link #synchronize}, which it calls again afterwards.
   */
  public synchronized void confirmIntegrity(String typeId, String reason) {
    ConnectorManifest m = known(typeId);
    Boolean kept = writeTx.execute(s -> {
      ConnectorTypeEntity row = repository.findById(typeId).orElseThrow(() -> ProblemException.conflict(
          "Not synchronized yet", m.name() + " isn't recorded yet: the core synchronizes connector types once the "
              + "master key is available."));
      if (verify(row)) {
        return null;
      }
      sign(row);
      setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.SETTINGS,
          "Connector type " + m.name() + " changed outside GateRook", "Unverified",
          row.isEnabled() ? "Kept: on" : "Kept: off", reason, null, null, null, null));
      storeAfterCommit(typeId, row.isEnabled());
      publisher.publishEvent(new ConnectorTypeChanged(typeId));
      return row.isEnabled();
    });
    if (kept != null) {
      alerts.resolve("connector-type", typeId);
    }
    // The manifest may be older than the code: bring it up to date now that the row is trusted again.
    synchronize();
  }

  // ── Internals ───────────────────────────────────────────────────────────────────────────────────────────────

  /** Install committed switches before AFTER_COMMIT event listeners wake readers of endpoint catalogs. */
  private void storeAfterCommit(String typeId, boolean enabled) {
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCommit() {
        stored.put(typeId, new Stored(enabled, true));
      }
    });
  }

  private ConnectorType typeOf(ConnectorManifest m) {
    Stored s = stored.get(m.id());
    return s == null ? new ConnectorType(m, false, false) : new ConnectorType(m, s.enabled(), s.verified());
  }

  /** The manifest of a type the person names (admin API): 404 for an unknown one. */
  private ConnectorManifest known(String typeId) {
    return manifest(typeId).orElseThrow(() -> ProblemException.notFound("Connector type not found",
        "There is no connector type " + typeId + "."));
  }

  private void requireVerified(ConnectorTypeEntity row, ConnectorManifest m) {
    if (!verify(row)) {
      alerts.report("connector-type", m.id(), m.name(), "connector_type row");
      throw ProblemException.conflict("Changed outside GateRook", m.name() + " was changed outside GateRook. "
          + "Review it first.");
    }
  }

  private JsonNode macFields(ConnectorTypeEntity row) {
    ObjectNode f = rowMac.fields();
    f.put("kind", row.getKind().name());
    f.put("implVersion", row.getImplVersion());
    f.put("manifestSha256", row.getManifestSha256());
    f.put("enabled", row.isEnabled());
    f.put("pluginId", row.getPlugin() == null ? null : row.getPlugin().getId());
    return f;
  }

  private void sign(ConnectorTypeEntity row) {
    row.setMac(rowMac.sign(TABLE, row.getId(), row.getVersion(), macFields(row)));
    repository.saveAndFlush(row);
  }

  private boolean verify(ConnectorTypeEntity row) {
    return rowMac.verify(TABLE, row.getId(), row.getVersion(), macFields(row), row.getMac());
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> map(ObjectNode tree) {
    return json.treeToValue(tree, Map.class);
  }

  /**
   * Why one of GateRook's own types that a source delivers (a connector module) can't be registered, or empty. It is
   * held to what the compiled-in types are held to at start: valid names, no tool of the core, and a tool that shares
   * its name with another type's equal to it, so that the two merge on an endpoint. A module that fails this isn't
   * registered; the start goes on.
   */
  private Optional<String> firstPartyProblem(ConnectorManifest m, Map<String, ConnectorManifest> earlier) {
    if (!TYPE_ID.matcher(m.id()).matches()) {
      return Optional.of("the id is not [a-z][a-z0-9-]{0,47}");
    }
    if (m.kind() != ConnectorManifest.Kind.BUILT_IN) {
      return Optional.of("a connector module's types are built-in types");
    }
    if (earlier.containsKey(m.id())) {
      return Optional.of("another connector already uses this id");
    }
    Set<String> own = new TreeSet<>();
    for (ToolDefinition t : m.tools()) {
      if (!TOOL_NAME.matcher(t.name()).matches()) {
        return Optional.of("tool " + t.name() + " has an invalid name");
      }
      if (CORE_TOOLS.contains(t.name())) {
        return Optional.of(t.name() + " is a tool of the core");
      }
      if (!own.add(t.name())) {
        return Optional.of("tool " + t.name() + " is declared twice");
      }
      // A tool of a family that a compiled-in type has too may be worded as another build worded it: the family's
      // definition is the compiled-in one (inFamily). Everything a call depends on must be the same.
      ToolDefinition family = familyTools.get(t.name());
      if (family != null && !sameContract(family, t)) {
        String owner = builtIn.values().stream().map(Connector::manifest)
            .filter(o -> o.tools().stream().anyMatch(x -> x.name().equals(t.name()))).map(ConnectorManifest::id)
            .findFirst().orElse("a compiled-in type");
        return Optional.of("tool " + t.name() + " differs from the one of " + owner
            + ": same-named tools must be equal to share an endpoint");
      }
      ToolDefinition settled = family != null ? family : t;
      for (ConnectorManifest other : earlier.values()) {
        Optional<ToolDefinition> same = other.tools().stream().filter(o -> o.name().equals(t.name())).findFirst();
        if (same.isPresent() && !sameTool(same.get(), settled)) {
          return Optional.of("tool " + t.name() + " differs from the one of " + other.id()
              + ": same-named tools must be equal to share an endpoint");
        }
      }
    }
    return Optional.empty();
  }

  /**
   * Why a type of a module that GateRook didn't sign ({@link ConnectorSource#unverified}) can't be registered beside
   * the others, or empty. Such a type passes for nothing of GateRook's: none of its tools has the name of a built-in
   * tool or of a built-in family, and it shares no tool, nor a family of names, with another package's type.
   *
   * @param ownPackage the package that adds {@code m}: its other types may have the same tools
   */
  private Optional<String> unverifiedProblem(ConnectorManifest m, Map<String, ConnectorManifest> others,
      Map<String, String> packages, String ownPackage) {
    Map<String, String> taken = new HashMap<>();
    for (ConnectorManifest other : others.values()) {
      if (ownPackage == null || !ownPackage.equals(packages.get(other.id()))) {
        other.tools().forEach(tool -> taken.putIfAbsent(tool.name(), other.id()));
      }
    }
    Set<String> families = prefixes(taken.keySet());
    for (ToolDefinition t : m.tools()) {
      String name = t.name();
      if (reserved.contains(name)) {
        return Optional.of("tool " + name + " has the name of a built-in tool");
      }
      if (taken.containsKey(name)) {
        return Optional.of("tool " + name + " is already a tool of " + taken.get(name));
      }
      for (Set<String> prefixes : List.of(reservedPrefixes, families)) {
        for (String prefix : prefixes) {
          if (name.startsWith(prefix)) {
            return Optional.of("tool " + name + " starts with " + prefix + ", which the tools of another connector "
                + "start with");
          }
        }
      }
    }
    return Optional.empty();
  }

  /**
   * Whether the connector types of a module that GateRook didn't sign could be registered now, before the module is
   * installed: what {@link #synchronize} would decide of them, as a reason a person can act on, or empty.
   *
   * @param moduleId the module's id: the types an installed package of that id adds now are the ones it replaces
   */
  public synchronized Optional<String> ownModuleProblem(String moduleId, List<ConnectorManifest> types) {
    Map<String, String> packages = new HashMap<>(sourcePackages);
    Map<String, ConnectorManifest> others = new LinkedHashMap<>();
    sourceTypes.values().stream().filter(known -> !moduleId.equals(packages.get(known.id())))
        .forEach(known -> others.put(known.id(), known));
    awaited.values().forEach(standIn -> others.putIfAbsent(standIn.manifest().id(), standIn.manifest()));
    for (ConnectorManifest m : types) {
      if (builtIn.containsKey(m.id())) {
        return Optional.of(m.name() + " is part of this GateRook: the connector type " + m.id() + " can't come from "
            + "a file");
      }
      Optional<String> problem = firstPartyProblem(m, others);
      if (problem.isEmpty()) {
        problem = unverifiedProblem(m, others, packages, moduleId);
      }
      if (problem.isPresent()) {
        return Optional.of("its connector type " + m.id() + " can't be added: " + problem.get());
      }
      others.put(m.id(), m);
      packages.put(m.id(), moduleId);
    }
    return Optional.empty();
  }

  /**
   * {@code m} with the tools it shares with a compiled-in type as that type defines them. A connector module is
   * built on its own day: a tool of a family ({@code sql_query}, which PostgreSQL has in the core and Oracle Database
   * in a module) may be worded there as another build worded it. On an endpoint the two are one tool, so the family
   * has one definition, the compiled-in one. {@link #firstPartyProblem} lets only a difference of wording through.
   */
  private ConnectorManifest inFamily(ConnectorManifest m) {
    List<ToolDefinition> tools = inFamily(m.tools());
    return tools.equals(m.tools()) ? m : new ConnectorManifest(m.id(), m.version(), m.kind(), m.name(), m.icon(),
        m.group(), m.description(), m.configSchema(), m.policySchema(), tools, m.access(), m.dynamicTools(),
        m.connectorApi());
  }

  private List<ToolDefinition> inFamily(List<ToolDefinition> tools) {
    List<ToolDefinition> out = new ArrayList<>(tools.size());
    for (ToolDefinition t : tools) {
      ToolDefinition family = familyTools.get(t.name());
      out.add(family != null && !sameTool(family, t) && sameContract(family, t) ? family : t);
    }
    return out;
  }

  /**
   * The tools a connector gives for an endpoint ({@code Connector.tools}) as they are published: for a type a source
   * delivers, a tool it shares with a compiled-in type is the family's ({@link #inFamily}), so that the two publish
   * as one tool whatever build worded the module's.
   */
  public List<ToolDefinition> published(String typeId, List<ToolDefinition> tools) {
    return typeId == null || builtIn.containsKey(typeId) || familyTools.isEmpty() ? tools : inFamily(tools);
  }

  private Optional<String> sourceTypeProblem(ConnectorManifest m, Map<String, ConnectorManifest> earlier) {
    if (!TYPE_ID.matcher(m.id()).matches()) {
      return Optional.of("the id is not [a-z][a-z0-9-]{0,47}");
    }
    if (m.kind() == ConnectorManifest.Kind.BUILT_IN) {
      return Optional.of("only GateRook's own connectors are built in");
    }
    if (builtIn.containsKey(m.id()) || earlier.containsKey(m.id())) {
      return Optional.of("another connector already uses this id");
    }
    for (ToolDefinition t : m.tools()) {
      if (!TOOL_NAME.matcher(t.name()).matches()) {
        return Optional.of("tool " + t.name() + " has an invalid name");
      }
      Optional<String> reservedName = reservedProblem(t.name(), false);
      if (reservedName.isPresent()) {
        return Optional.of("tool " + reservedName.get());
      }
    }
    return Optional.empty();
  }

  /**
   * The checks that fail the start (ARCHITECTURE.md §5 step 4): valid names, no core tool names, and same-named tools
   * of different types equal up to the {@code connection} enum.
   */
  static void checkTools(List<ConnectorManifest> manifests) {
    Map<String, ToolDefinition> seen = new HashMap<>();
    Map<String, String> owner = new HashMap<>();
    for (ConnectorManifest m : manifests) {
      Set<String> own = new TreeSet<>();
      for (ToolDefinition t : m.tools()) {
        if (!TOOL_NAME.matcher(t.name()).matches()) {
          throw new IllegalStateException(m.id() + ": tool name " + t.name() + " is not ^[a-z][a-z0-9_]{0,63}$");
        }
        if (CORE_TOOLS.contains(t.name())) {
          throw new IllegalStateException(m.id() + ": " + t.name() + " is a tool of the core");
        }
        if (!own.add(t.name())) {
          throw new IllegalStateException(m.id() + ": tool " + t.name() + " is declared twice");
        }
        ToolDefinition earlier = seen.putIfAbsent(t.name(), t);
        if (earlier != null && !sameTool(earlier, t)) {
          throw new IllegalStateException("Tool " + t.name() + " of " + m.id() + " differs from the one of "
              + owner.get(t.name()) + ": same-named tools must be equal to share an endpoint");
        }
        owner.putIfAbsent(t.name(), m.id());
      }
    }
  }

  /** Equal up to the {@code connection} enum, which the core fills per endpoint. */
  public static boolean sameTool(ToolDefinition a, ToolDefinition b) {
    return a.name().equals(b.name()) && a.title().equals(b.title()) && a.description().equals(b.description())
        && withoutConnectionEnum(a.inputSchema()).equals(withoutConnectionEnum(b.inputSchema()))
        && java.util.Objects.equals(a.outputSchema(), b.outputSchema()) && a.annotations().equals(b.annotations())
        && a.nature() == b.nature() && a.defaultMode() == b.defaultMode()
        && a.prodDefaultMode() == b.prodDefaultMode() && a.resultShape() == b.resultShape()
        && a.risk().equals(b.risk());
  }

  /**
   * Equal in everything a call depends on: the name, the input and output schemas apart from their wording, the
   * annotations, the nature, the modes, the result's shape and the risk. Titles, descriptions and examples may
   * differ, as between two builds of the same connector.
   */
  public static boolean sameContract(ToolDefinition a, ToolDefinition b) {
    return a.name().equals(b.name())
        && java.util.Objects.equals(withoutWording(withoutConnectionEnum(a.inputSchema())),
            withoutWording(withoutConnectionEnum(b.inputSchema())))
        && java.util.Objects.equals(withoutWording(a.outputSchema()), withoutWording(b.outputSchema()))
        && a.annotations().equals(b.annotations()) && a.nature() == b.nature()
        && a.defaultMode() == b.defaultMode() && a.prodDefaultMode() == b.prodDefaultMode()
        && a.resultShape() == b.resultShape() && a.risk().equals(b.risk());
  }

  private static final Set<String> WORDING = Set.of("title", "description", "examples", "$comment");

  /** A copy of a JSON Schema without the keywords that only describe: they never decide what is valid. */
  private static JsonNode withoutWording(JsonNode schema) {
    if (schema == null) {
      return null;
    }
    if (schema.isObject()) {
      ObjectNode out = tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();
      schema.properties().forEach(e -> {
        // Under "properties" the names are the tool's arguments, whatever they are called.
        if ("properties".equals(e.getKey()) && e.getValue().isObject()) {
          ObjectNode properties = out.putObject("properties");
          e.getValue().properties().forEach(p -> properties.set(p.getKey(), withoutWording(p.getValue())));
        } else if (!WORDING.contains(e.getKey())) {
          out.set(e.getKey(), withoutWording(e.getValue()));
        }
      });
      return out;
    }
    if (schema.isArray()) {
      tools.jackson.databind.node.ArrayNode out = tools.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
      schema.forEach(item -> out.add(withoutWording(item)));
      return out;
    }
    return schema;
  }

  private static JsonNode withoutConnectionEnum(JsonNode schema) {
    if (schema == null || !schema.path("properties").path("connection").isObject()) {
      return schema;
    }
    JsonNode copy = schema.deepCopy();
    ((ObjectNode) copy.path("properties").path("connection")).remove("enum");
    return copy;
  }

  private static Set<String> reservedNames(List<ConnectorManifest> manifests) {
    Set<String> names = new TreeSet<>(CORE_TOOLS);
    for (ConnectorManifest m : manifests) {
      if (m.kind() == ConnectorManifest.Kind.BUILT_IN) {
        m.tools().forEach(t -> names.add(t.name()));
      }
    }
    return Set.copyOf(names);
  }

  static Set<String> reservedPrefixes(List<ConnectorManifest> manifests) {
    Set<String> names = new TreeSet<>();
    for (ConnectorManifest m : manifests) {
      if (m.kind() == ConnectorManifest.Kind.BUILT_IN) {
        m.tools().forEach(t -> names.add(t.name()));
      }
    }
    return prefixes(names);
  }

  /** The families of {@code names}: the part of each up to its first {@code _}. */
  private static Set<String> prefixes(Collection<String> names) {
    Set<String> prefixes = new TreeSet<>();
    for (String name : names) {
      int underscore = name.indexOf('_');
      if (underscore > 0) {
        prefixes.add(name.substring(0, underscore + 1));
      }
    }
    return Set.copyOf(prefixes);
  }
}
