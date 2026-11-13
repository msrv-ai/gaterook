package ai.msrv.gaterook.core.connectors.plugins;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.core.connectors.ConnectorRegistry;
import ai.msrv.gaterook.core.connectors.ConnectorTypesInUse;
import ai.msrv.gaterook.core.connectors.PluginGenerationSwitched;
import ai.msrv.gaterook.core.connectors.Plugins.RuntimeStatus;
import ai.msrv.gaterook.core.connectors.internal.persistence.ConnectorTypeRepository;
import ai.msrv.gaterook.core.connectors.internal.persistence.PluginEntity;
import ai.msrv.gaterook.core.connectors.internal.persistence.PluginRepository;
import ai.msrv.gaterook.core.connectors.internal.persistence.PluginSource;
import ai.msrv.gaterook.core.connectors.internal.persistence.PluginState;
import ai.msrv.gaterook.core.platform.EventBus;
import ai.msrv.gaterook.core.platform.Ids;
import ai.msrv.gaterook.core.platform.MasterKeyChanged;
import ai.msrv.gaterook.core.platform.MasterKeyLock;
import ai.msrv.gaterook.core.platform.ProblemException;
import ai.msrv.gaterook.core.platform.SettingStore;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder.SetupChange;
import ai.msrv.gaterook.plugin.protocol.PluginManifest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * GateRook's own connector modules on this computer (docs/connector-delivery.md): the connectors that aren't part of
 * the installer arrive here when something needs them. Nobody reviews or confirms a module: it is GateRook's own
 * code, the module channel's signed index pins its package by size and SHA-256, and the first thing that needs one of
 * its connector types — a person choosing the type in Add connection, an import that names it, a call on a connection
 * of it — {@link #ensure ensures} it, and waits or goes on.
 *
 * <p>One module installs at a time per module id; whoever asks meanwhile joins the same run. The steps are the Java
 * runtime modules run on (downloaded once, when the application doesn't carry one), the package, its checks (the
 * pinned bytes, the manifest, the versions it needs, the connector types the index promised), a first load in a
 * fresh host, and the signed row. Then its types register like built-in ones and endpoints rebuild.
 *
 * <p>An installed module follows the list: when the list has a higher version that this build can run, the module
 * updates by itself, the new host beside the old one, and what waited for approval on its connections is rejected,
 * because it was reviewed against the old code. The version before stays for a week and {@link #rollback} returns to
 * it; an update then installs only a version past the one rolled back from. A package the list revokes is switched
 * off and replaced by the best one that isn't.
 *
 * <p>A module's row can be here without its files: a backup restored on another computer brings the database, not
 * the packages. The start notices and downloads them again, and so does a call that comes first ({@link #awaitFiles}):
 * the same version the row names when the channel still lists it, else the best one this build can run.
 *
 * <p>A person can install a module's package from a file too ({@link #reviewFile}, {@link #installFile}). One that
 * the signed list pins is GateRook's own module, wherever its bytes came from: a way to install on a computer that
 * doesn't reach the list. Any other is the person's own: they review it and say that they trust it, as for an
 * unsigned plugin; its row is unsigned, the core takes none of its words ({@link ModuleConnector}), it shares no name
 * with another connector, and the list neither updates nor restores it. {@link #replaceOwn} returns to GateRook's
 * build of a module the list has.
 */
@Service
public class ModuleService implements ConnectorRegistry.MissingTypes, DisposableBean {

  /** A type nothing could deliver isn't looked up again sooner than this. */
  static final Duration ASK_AGAIN = Duration.ofMinutes(2);
  /** After a failed installation, a lookup in passing doesn't start another one sooner than this. */
  static final Duration BACKGROUND_RETRY = Duration.ofMinutes(30);
  /** After a failed download of a missing package, calls don't start another one sooner than this. */
  static final Duration CALL_RETRY = Duration.ofMinutes(1);
  /** How often the installed modules are checked against the list, and how soon after the start. */
  static final Duration UPDATE_CHECK = Duration.ofHours(6);
  static final Duration FIRST_UPDATE_CHECK = Duration.ofMinutes(2);
  /** A version whose update failed isn't tried again sooner than this. */
  static final Duration UPDATE_RETRY = Duration.ofHours(6);
  /**
   * {@code {"<module>": "<version>"}}: the highest version a person rolled each module back from. An update installs
   * only a version past it.
   */
  static final String HELD = "modules.held";

  /** How long a call waits for a module's files before it says they are on their way. */
  static final Duration CALL_WAIT = Duration.ofSeconds(20);
  /** The Java runtime's download may take this long. */
  static final Duration RUNTIME_WAIT = Duration.ofMinutes(15);

  private static final Logger log = LoggerFactory.getLogger(ModuleService.class);

  /** Where one module's installation stands. */
  public enum Step { QUEUED, RUNTIME, DOWNLOADING, VERIFYING, INSTALLING, READY, FAILED }

  /**
   * A module as the admin API and the other modules of the core see it.
   *
   * @param state     {@code ready} (installed, its files here), {@code installing}, {@code missing} (installed, its
   *                  files not here and not on their way), {@code available} (listed, not installed),
   *                  {@code failed} (the last installation failed: {@code message}), {@code revoked} (the list
   *                  revoked its package: switched off) or {@code disabled}
   * @param step      the running installation's step, lower case, or {@code null}
   * @param sizeBytes the package's size as the channel lists it, or as installed
   * @param previousVersion the version kept for Roll back, or {@code null}
   * @param listedVersion   a higher version the list has for an installed module, or the version the list has of a
   *                        module whose installed build is the person's own; else {@code null}
   * @param signed          whether GateRook's signed list pins the package: {@code false} only for an installed
   *                        module that a person installed from a file and that the list doesn't pin
   */
  public record Status(String id, String name, String version, String state, String step, long sizeBytes,
      String message, List<ModuleChannel.TypeInfo> types, boolean installed, String previousVersion,
      String listedVersion, boolean signed) {

    public boolean ready() {
      return "ready".equals(state);
    }
  }

  /** One running (or just finished) installation of a module. */
  private static final class Run {
    final String id;
    final String name;
    final String version;
    final long sizeBytes;
    final List<ModuleChannel.TypeInfo> types;
    final CompletableFuture<Void> done = new CompletableFuture<>();
    volatile Step step = Step.QUEUED;
    volatile String message;
    volatile Instant finishedAt;

    /** Whether the run failed less than {@code within} ago. */
    boolean failedRecently(Instant now, Duration within) {
      Instant finished = finishedAt;
      return step == Step.FAILED && finished != null && finished.plus(within).isAfter(now);
    }

    Run(ModuleChannel.Entry entry) {
      this.id = entry.id();
      this.name = entry.name();
      this.version = entry.version();
      this.sizeBytes = entry.size();
      this.types = entry.types();
    }

    /** The installation of a package that came as a file. */
    Run(PluginManifest module, long sizeBytes) {
      this.id = module.id();
      this.name = module.name();
      this.version = module.version();
      this.sizeBytes = sizeBytes;
      this.types = typesOf(module);
    }
  }

  private final PluginStore store;
  private final PluginRepository repository;
  private final ConnectorTypeRepository connectorTypes;
  private final PluginInstaller installer;
  private final PluginConnectorSource source;
  private final ModuleChannel channel;
  private final PluginPaths paths;
  private final HostRuntimeLocator runtime;
  private final AotCacheManager aot;
  private final ConnectorRegistry registry;
  private final MasterKeyLock lock;
  private final ObjectProvider<SetupChangeRecorder> setupChanges;
  private final ObjectProvider<ConnectorTypesInUse> typesInUse;
  private final SettingStore settings;
  private final ApplicationEventPublisher publisher;
  private final boolean autoUpdate;
  private ScheduledExecutorService updates;
  private final EventBus events;
  private final TransactionTemplate writeTx;
  private final Clock clock;
  private final Map<String, Run> runs = new ConcurrentHashMap<>();
  /** When each type that isn't registered was last looked for ({@link #wanted}). */
  private final Map<String, Instant> askedAt = new ConcurrentHashMap<>();

  public ModuleService(PluginStore store, ConnectorTypeRepository connectorTypes, PluginInstaller installer,
      PluginConnectorSource source, ModuleChannel channel, PluginPaths paths, HostRuntimeLocator runtime,
      AotCacheManager aot, ConnectorRegistry registry, MasterKeyLock lock,
      ObjectProvider<SetupChangeRecorder> setupChanges, ObjectProvider<ConnectorTypesInUse> typesInUse,
      SettingStore settings, ApplicationEventPublisher publisher,
      @Value("${gateway.modules.auto-update:true}") boolean autoUpdate, EventBus events,
      PlatformTransactionManager transactions, Clock clock) {
    this.settings = settings;
    this.publisher = publisher;
    this.autoUpdate = autoUpdate;
    this.typesInUse = typesInUse;
    this.store = store;
    this.repository = store.repository();
    this.connectorTypes = connectorTypes;
    this.installer = installer;
    this.source = source;
    this.channel = channel;
    this.paths = paths;
    this.runtime = runtime;
    this.aot = aot;
    this.registry = registry;
    this.lock = lock;
    this.setupChanges = setupChanges;
    this.events = events;
    this.writeTx = new TransactionTemplate(transactions);
    this.clock = clock;
    // No plugin, MCP server or template takes the name of a tool of a module the channel lists.
    source.channelTools(channel::toolNames);
    registry.missingTypes(this);
  }

  // ── A type that isn't here ──────────────────────────────────────────────────────────────────────────────────

  /**
   * Something looked for a connector type that isn't registered: a connection of a type this build no longer
   * compiles in, or one that came with a backup or an import. When a module adds the type, its installation starts
   * in the background. Looked up at most every {@link #ASK_AGAIN} per type, so a lookup in a hot path costs a map
   * read.
   */
  @Override
  public void wanted(String typeId) {
    if (!channel.configured() || lock.locked()) {
      return;
    }
    Instant now = clock.instant();
    Instant asked = askedAt.get(typeId);
    if (asked != null && asked.plus(ASK_AGAIN).isAfter(now)) {
      return;
    }
    if (askedAt.size() > 1_000) {
      askedAt.clear();
    }
    askedAt.put(typeId, now);
    Thread.ofVirtual().name("module-wanted-" + typeId).start(() -> {
      try {
        if (inUse(typeId) && delivers(typeId) && !failedRecently(typeId, BACKGROUND_RETRY)) {
          log.info("Connections use the connector type {}, which isn't installed: installing its module", typeId);
          ensure(typeId);
        }
      } catch (RuntimeException e) {
        log.info("Connector type {} couldn't be installed yet: {}", typeId, e.getMessage());
      }
    });
  }

  /** Whether the last installation of the module of (or for) {@code typeOrModuleId} failed a short while ago. */
  private boolean failedRecently(String typeOrModuleId, Duration within) {
    String moduleId = source.moduleOf(typeOrModuleId).or(() -> listedNow(typeOrModuleId)
        .map(ModuleChannel.Entry::id)).orElse(typeOrModuleId);
    Run last = runs.get(moduleId);
    return last != null && last.failedRecently(clock.instant(), within);
  }

  private boolean inUse(String typeId) {
    ConnectorTypesInUse connections = typesInUse.getIfAvailable();
    return connections == null || connections.inUse(typeId);
  }

  // ── A type an earlier build compiled in ─────────────────────────────────────────────────────────────────────

  /**
   * A type GateRook itself added in an earlier build and that this build gets as a module: while connections use it,
   * it stays registered as its row describes it, and {@link AwaitedConnector} answers for it until the module is
   * installed. The start asks for the module at once ({@link #onReady}).
   */
  @Override
  public Optional<Connector> standIn(ConnectorManifest known) {
    if (!channel.configured() || !inUse(known.id())) {
      return Optional.empty();
    }
    if (!lock.locked() && installedWithType(known.id()).isPresent()) {
      // Its module is installed (ahead, while the build before still compiled the type in): the type registers from
      // the module's row when the core is ready, and nothing is awaited.
      return Optional.empty();
    }
    String typeId = known.id();
    return Optional.of(new AwaitedConnector(known, new AwaitedConnector.Arrival() {
      @Override
      public Optional<Connector> await(ConnectionConfig cfg, Duration budget) {
        return arrived(typeId, cfg, budget);
      }

      @Override
      public String problem() {
        return awaitedProblem(known);
      }
    }));
  }

  /**
   * The connector of an awaited type's connection once the type's module is here. With a budget, the module's
   * installation is started or joined and waited for; a failed one isn't started again by every call.
   */
  private Optional<Connector> arrived(String typeId, ConnectionConfig cfg, Duration budget) {
    if (registry.awaitedTypes().contains(typeId) && budget.isPositive() && !lock.locked()
        && !failedRecently(typeId, CALL_RETRY)) {
      try {
        ensureAndWait(typeId, budget);
      } catch (RuntimeException e) {
        log.debug("Connector type {} isn't here yet: {}", typeId, e.getMessage());
      }
    }
    return registry.connectorFor(cfg).filter(c -> !(c instanceof AwaitedConnector));
  }

  /** Why an awaited type's connector isn't here: on its way, failed, held up, or not to be found. */
  private String awaitedProblem(ConnectorManifest known) {
    String connector = "The " + known.name() + " connector";
    Optional<ModuleChannel.Entry> entry = listedNow(known.id());
    Run run = entry.map(e -> runs.get(e.id())).orElse(null);
    if (run != null && run.step == Step.FAILED) {
      return connector + " couldn't be installed: " + run.message;
    }
    if (run != null && !run.done.isDone()) {
      return connector + " is being downloaded: this version of GateRook installs it when it is first needed. Try "
          + "again in a moment.";
    }
    if (lock.locked()) {
      return connector + " installs once GateRook is unlocked.";
    }
    if (entry.isPresent()) {
      return connector + " isn't installed yet" + channel.installProblem().map(problem -> ": " + problem)
          .orElse(". Try again in a moment.");
    }
    String problem = channel.cached().problem();
    return connector + " isn't installed yet, and GateRook couldn't find it to download"
        + (problem == null ? "." : ": " + problem);
  }

  /**
   * The bridge (docs/connector-delivery.md): a module the channel lists for a connector type that this build still
   * compiles in is installed ahead when a connection uses the type, so that the release that no longer compiles the
   * type in finds its module in place and its connections go on without a pause. Nothing uses the module meanwhile:
   * the compiled-in connector serves the type. It runs with the checks for updates, never at the start.
   *
   * @return the modules whose installation started
   */
  private List<String> installAhead() {
    List<String> started = new ArrayList<>();
    for (ModuleChannel.Entry entry : channel.modules()) {
      if (store.find(entry.id()).isPresent() || failedRecently(entry.id(), UPDATE_RETRY)) {
        continue;
      }
      List<String> ahead = entry.types().stream().map(ModuleChannel.TypeInfo::id)
          .filter(type -> registry.compiledIn(type) && inUse(type)).toList();
      if (ahead.isEmpty()) {
        continue;
      }
      try {
        start(entry, null);
        started.add(entry.id());
        log.info("Connections use {}, which a later release gets as the connector module {}: installing it ahead",
            ahead, entry.id());
      } catch (RuntimeException e) {
        log.info("Connector module {} couldn't be installed ahead: {}", entry.id(), e.getMessage());
      }
    }
    return started;
  }

  /** Asks for the module of every awaited type, in the background. */
  private void askForAwaited() {
    registry.awaitedTypes().forEach(this::wanted);
  }

  @Override
  public boolean await(String typeId, Duration budget) {
    try {
      if (!delivers(typeId)) {
        return false;
      }
      ensureAndWait(typeId, budget);
      return true;
    } catch (ProblemException e) {
      log.info("Connector type {} couldn't be installed: {}", typeId, e.getMessage());
      return true;
    }
  }

  // ── Start ───────────────────────────────────────────────────────────────────────────────────────────────────

  @EventListener(ApplicationReadyEvent.class)
  public void onReady() {
    if (!lock.locked()) {
      loadInstalled();
      askForAwaited();
    }
    startUpdateChecks();
  }

  /** The installed modules are checked against the list soon after the start and then every {@link #UPDATE_CHECK}. */
  private synchronized void startUpdateChecks() {
    if (updates != null || !autoUpdate || !channel.configured()) {
      return;
    }
    updates = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("module-updates").factory());
    updates.scheduleWithFixedDelay(() -> {
      try {
        askForAwaited();
        updateInstalled();
      } catch (RuntimeException e) {
        log.info("The connector modules couldn't be checked for updates: {}", e.getMessage());
      }
    }, FIRST_UPDATE_CHECK.toSeconds(), UPDATE_CHECK.toSeconds(), TimeUnit.SECONDS);
  }

  @Override
  public synchronized void destroy() {
    if (updates != null) {
      updates.shutdownNow();
      updates = null;
    }
  }

  @Async
  @TransactionalEventListener
  public void onMasterKeyChanged(MasterKeyChanged event) {
    loadInstalled();
    askForAwaited();
  }

  /**
   * Registers every installed module from its row, so its connector types are known and their tools listed without
   * a host or even the package; a module whose package isn't on this computer is downloaded again in the background.
   */
  private synchronized void loadInstalled() {
    List<String> missing = new ArrayList<>();
    for (PluginEntity row : modules()) {
      try {
        if (register(row) && row.getState() == PluginState.INSTALLED) {
          missing.add(row.getId());
        }
      } catch (RuntimeException e) {
        log.warn("Connector module {} can't be loaded: {}", row.getId(), e.getMessage());
      }
    }
    registry.synchronize();
    if (!missing.isEmpty()) {
      log.info("The packages of {} aren't on this computer: downloading them again", missing);
      Thread.ofVirtual().name("module-restore").start(() -> missing.forEach(id -> {
        try {
          restore(id);
        } catch (RuntimeException e) {
          log.info("Connector module {} couldn't be downloaded again yet: {}", id, e.getMessage());
        }
      }));
    }
  }

  /** Registers a module's types from its row. @return whether its package is missing on this computer */
  private boolean register(PluginEntity row) {
    PluginManifest module = store.moduleManifest(row);
    Path jar = paths.jar(row.getId(), row.getCurrentVersion());
    String id = row.getId();
    List<String> taken = source.registerModule(module, HostLauncher.Spec.of(module, jar, row.getSha256()),
        row.getState() == PluginState.INSTALLED, deadline -> awaitFiles(id, deadline), row.isSigned());
    if (!taken.isEmpty()) {
      log.error("Connector module {} can't add {}: another package already adds a type of that id", id, taken);
    }
    return !Files.isRegularFile(jar);
  }

  // ── Ensuring a module ───────────────────────────────────────────────────────────────────────────────────────

  /**
   * Makes sure the module that adds the connector type {@code typeOrModuleId} (or the module of that id) is on its
   * way: starts its installation unless it is installed or already installing, and answers at once with where it
   * stands. Nothing asks the person anything.
   *
   * @throws ProblemException 404 when no module adds the type, 409 when modules can't install now
   */
  public Status ensure(String typeOrModuleId) {
    Optional<PluginEntity> installed = installedFor(typeOrModuleId);
    if (installed.isPresent()) {
      PluginEntity row = installed.get();
      if (!Files.isRegularFile(paths.jar(row.getId(), row.getCurrentVersion()))
          && row.getState() == PluginState.INSTALLED) {
        restore(row.getId());
      }
      return status(row);
    }
    ModuleChannel.Entry entry = listed(typeOrModuleId).orElseThrow(() -> ProblemException.notFound(
        "No such connector", channel.configured() ? "No GateRook connector module adds " + typeOrModuleId + "."
            : "This build of GateRook has no connector modules to download."));
    return status(start(entry, null));
  }

  /**
   * {@link #ensure} and then waits up to {@code budget} for the module to be ready. The answer says where it stands
   * when the wait ends; a failure is a state with its message, never an exception.
   */
  public Status ensureAndWait(String typeOrModuleId, Duration budget) {
    Status status = ensure(typeOrModuleId);
    Run run = runs.get(status.id());
    if (run == null || status.ready()) {
      return status;
    }
    try {
      run.done.get(Math.max(1, budget.toMillis()), TimeUnit.MILLISECONDS);
    } catch (TimeoutException | ExecutionException e) {
      // Still installing, or failed: the status says which.
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return statusOf(status.id()).orElse(status);
  }

  /**
   * Whether a module adds the connector type: one that is installed here, or one the channel lists. The channel is
   * checked when it never was or the last check is old.
   */
  public boolean delivers(String typeId) {
    if (source.moduleOf(typeId).isPresent()) {
      return true;
    }
    if (!channel.configured()) {
      return false;
    }
    channel.state();
    return channel.ofType(typeId).isPresent();
  }

  /**
   * The gate of a module's calls ({@link ModuleConnector.Gate}): returns when the module's package is on this
   * computer. A missing one is downloaded again by the call that found it missing, which waits a while for it.
   */
  void awaitFiles(String moduleId, Instant deadline) throws ConnectorException {
    PluginSupervisor supervisor = source.supervisor(moduleId);
    HostLauncher.Spec spec = supervisor == null ? null : supervisor.spec();
    if (spec != null && Files.isRegularFile(spec.jar())) {
      return;
    }
    Run last = runs.get(moduleId);
    if (last != null && last.failedRecently(clock.instant(), CALL_RETRY)) {
      // Not every call starts a download that just failed.
      throw ConnectorException.unavailable(last.name + " couldn't be downloaded again: " + last.message, null);
    }
    Run run;
    try {
      run = restore(moduleId);
    } catch (ProblemException e) {
      throw ConnectorException.unavailable(e.getMessage(), null);
    }
    if (run == null) {
      return;
    }
    long wait = Math.min(CALL_WAIT.toMillis(), Math.max(0, Duration.between(clock.instant(), deadline).toMillis()));
    try {
      run.done.get(wait, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      throw ConnectorException.unavailable(run.name + " is being downloaded again: its files weren't on this "
          + "computer. Try again in a moment.", null);
    } catch (ExecutionException e) {
      throw ConnectorException.unavailable(run.name + " couldn't be downloaded again: " + run.message, null);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw ConnectorException.timeout("The call was cancelled.");
    }
  }

  /**
   * Downloads an installed module's package again: the version its row names when the channel still lists that very
   * package, else the best version this build can run. Joins a run that is already under way.
   *
   * @return the run, or {@code null} when the package is here after all
   */
  private Run restore(String moduleId) {
    PluginEntity row = store.find(moduleId).filter(PluginEntity::isModule).orElseThrow(() ->
        ProblemException.notFound("No such connector", "There is no connector module " + moduleId + "."));
    if (Files.isRegularFile(paths.jar(row.getId(), row.getCurrentVersion()))) {
      return null;
    }
    Run running = runs.get(moduleId);
    if (running != null && !running.done.isDone()) {
      return running;
    }
    channel.state();
    Optional<ModuleChannel.Entry> same = channel.exact(row.getId(), row.getCurrentVersion())
        .filter(e -> e.sha256().equalsIgnoreCase(row.getSha256()));
    if (!row.isSigned() && same.isEmpty()) {
      // A person's own build: only its own file brings it back.
      throw ProblemException.conflict("Not on this computer", store.moduleManifest(row).name() + " "
          + row.getCurrentVersion() + " was installed from a file, and its package isn't on this computer. Install "
          + "it from the file again.");
    }
    ModuleChannel.Entry entry = same.or(() -> channel.module(row.getId())).orElseThrow(() ->
        ProblemException.conflict("Not available", channel.installProblem().orElse("GateRook's list of connector "
            + "modules no longer has " + row.getId() + ".")));
    return start(entry, row);
  }

  /** Starts the installation of {@code entry}, or joins the one that runs for its module. */
  private Run start(ModuleChannel.Entry entry, PluginEntity existing) {
    if (lock.locked()) {
      throw ProblemException.conflict("Locked", "GateRook is locked: connectors install once it is unlocked.");
    }
    channel.installProblem().ifPresent(problem -> {
      throw ProblemException.conflict("Connector modules unavailable", problem);
    });
    Run[] started = new Run[1];
    Run run = runs.compute(entry.id(), (id, current) -> {
      if (current != null && !current.done.isDone()) {
        return current;
      }
      started[0] = new Run(entry);
      return started[0];
    });
    if (started[0] == run) {
      publish(run);
      Thread.ofVirtual().name("module-install-" + entry.id()).start(() -> install(run, entry, existing));
    }
    return run;
  }

  private void install(Run run, ModuleChannel.Entry entry, PluginEntity existing) {
    boolean stopped = false;
    try {
      step(run, Step.RUNTIME);
      requireRuntime();
      step(run, Step.DOWNLOADING);
      byte[] bytes = channel.download(entry);
      step(run, Step.VERIFYING);
      PluginInstaller.Staged staged = installer.checkModule(bytes, entry.ref(), ModuleChannel.fileName(entry));
      try {
        checkAgainst(entry, staged.manifest());
      } catch (RuntimeException e) {
        installer.discard(staged.stagingId());
        throw e;
      }
      step(run, Step.INSTALLING);
      stopped = stopForSameVersion(existing, staged);
      PluginInstaller.Staged placed = installer.install(staged.stagingId(), true);
      record(placed, existing, "GateRook installed its own connector module");
      registry.synchronize();
      store.find(entry.id()).ifPresent(this::pruneVersions);
      aot.trainInBackground();
      run.finishedAt = clock.instant();
      step(run, Step.READY);
      run.done.complete(null);
      log.info("Connector module {} {} is installed", entry.id(), entry.version());
    } catch (RuntimeException e) {
      run.message = e instanceof ProblemException p && p.getMessage() != null ? p.getMessage()
          : "It couldn't be installed (" + e.getClass().getSimpleName() + ").";
      log.warn("Connector module {} {} wasn't installed: {}", entry.id(), entry.version(), run.message,
          e instanceof ProblemException ? null : e);
      if (stopped && existing.getState() == PluginState.INSTALLED) {
        PluginSupervisor supervisor = source.supervisor(entry.id());
        if (supervisor != null) {
          supervisor.enable();
        }
      }
      run.finishedAt = clock.instant();
      step(run, Step.FAILED);
      run.done.completeExceptionally(e);
    }
  }

  /**
   * Another build of the very version that is installed goes into the same folder: the running host is stopped
   * first, so the package it loaded isn't replaced under it. Only GateRook's build of a version that a person had
   * their own build of comes this way ({@link #replaceOwn}): a file never replaces a build of its own version.
   *
   * @return whether a host was stopped
   */
  private boolean stopForSameVersion(PluginEntity existing, PluginInstaller.Staged staged) {
    if (existing == null || !existing.getCurrentVersion().equals(staged.manifest().version())
        || existing.getSha256().equalsIgnoreCase(staged.sha256())) {
      return false;
    }
    PluginSupervisor supervisor = source.supervisor(existing.getId());
    if (supervisor == null) {
      return false;
    }
    supervisor.disable();
    return true;
  }

  // ── A module from a file ────────────────────────────────────────────────────────────────────────────────────

  /**
   * What is known of a module's package that a person chose on this computer, before anything of it is installed
   * (the installer staged and checked the package itself). One that GateRook's signed list pins, by version and
   * SHA-256, is GateRook's own module, wherever its bytes came from: it is marked signed and installs as one from
   * the channel does. Any other is the person's own module: it may add only what is nobody else's, and installs once
   * the person says that they trust it.
   *
   * @throws ProblemException when the package can't be installed here; it is dropped then
   */
  public PluginInstaller.Staged reviewFile(PluginInstaller.Staged staged) {
    try {
      PluginManifest module = staged.manifest();
      if (lock.locked()) {
        throw ProblemException.conflict("Locked", "GateRook is locked: connectors install once it is unlocked.");
      }
      if (channel.configured()) {
        channel.state();
      }
      refuseRevoked(module, staged.sha256());
      versionProblem(installedRow(module.id()), staged).ifPresent(problem -> {
        throw problem;
      });
      Optional<ModuleChannel.Entry> pinned = pinnedBy(module.id(), module.version(), staged.sha256());
      if (pinned.isPresent()) {
        checkAgainst(pinned.get(), module);
        return installer.pinned(staged.stagingId(), pinned.get().ref());
      }
      checkOwn(module);
      return staged;
    } catch (RuntimeException e) {
      installer.discard(staged.stagingId());
      throw e;
    }
  }

  /**
   * Installs a module's package that {@link #reviewFile} reviewed, now: the caller waits for it. An unsigned one
   * installs only with the person's acknowledgement. The same module from the channel can't install meanwhile.
   *
   * @throws ProblemException what stood in the way; nothing of the package stays then
   */
  public Status installFile(String stagingId, boolean acknowledgeUnsigned, String reason) {
    PluginInstaller.Staged staged = installer.staged(stagingId).orElseThrow(() -> ProblemException.notFound(
        "Not staged", "Check the package again before installing it."));
    PluginManifest module = staged.manifest();
    String id = module.id();
    if (!module.module()) {
      throw ProblemException.unprocessable("Not a connector module", "The package isn't a connector module.",
          "/stagingId");
    }
    if (!staged.signed() && !acknowledgeUnsigned) {
      // The package stays staged: the person may still say that they trust it.
      throw ProblemException.unprocessable("Unsigned connector module", module.name() + " isn't a connector "
          + "module GateRook signed. Confirm that you trust it before installing it.", "/acknowledgeUnsigned");
    }
    Run run;
    PluginEntity existing;
    try {
      if (lock.locked()) {
        throw ProblemException.conflict("Locked", "GateRook is locked: connectors install once it is unlocked.");
      }
      // What was true at the review may have changed since.
      refuseRevoked(module, staged.sha256());
      existing = installedRow(id);
      versionProblem(existing, staged).ifPresent(problem -> {
        throw problem;
      });
      if (staged.signed()) {
        checkAgainst(pinnedBy(id, module.version(), staged.sha256()).orElseThrow(() -> ProblemException.conflict(
            "No longer listed", "GateRook's list no longer has this package of " + module.name() + ". Check it "
                + "again.")), module);
      } else {
        checkOwn(module);
      }
      Run[] started = new Run[1];
      run = runs.compute(id, (key, current) -> {
        if (current != null && !current.done.isDone()) {
          return current;
        }
        started[0] = new Run(module, staged.sizeBytes());
        return started[0];
      });
      if (started[0] != run) {
        throw ProblemException.conflict("Being installed", module.name() + " is being installed already. Try "
            + "again in a moment.");
      }
    } catch (RuntimeException e) {
      installer.discard(stagingId);
      throw e;
    }
    publish(run);
    try {
      step(run, Step.RUNTIME);
      requireRuntime();
      step(run, Step.INSTALLING);
      PluginInstaller.Staged placed = installer.install(stagingId, acknowledgeUnsigned);
      record(placed, existing, reason == null || reason.isBlank() ? (placed.signed()
          ? "Installed GateRook's connector module from a file" : "Installed a connector module from a file")
          : reason);
      registry.synchronize();
      store.find(id).ifPresent(this::pruneVersions);
      aot.trainInBackground();
      run.finishedAt = clock.instant();
      step(run, Step.READY);
      run.done.complete(null);
      log.info("Connector module {} {} is installed from a file ({})", id, module.version(), placed.signed()
          ? "pinned by GateRook's list" : "unsigned");
      return statusOf(id).orElseThrow();
    } catch (RuntimeException e) {
      installer.discard(stagingId);
      run.message = e instanceof ProblemException p && p.getMessage() != null ? p.getMessage()
          : "It couldn't be installed (" + e.getClass().getSimpleName() + ").";
      log.warn("Connector module {} {} wasn't installed from a file: {}", id, module.version(), run.message,
          e instanceof ProblemException ? null : e);
      run.finishedAt = clock.instant();
      step(run, Step.FAILED);
      run.done.completeExceptionally(e);
      // The failure went to the person who asked: no card is left saying that an installation failed, and nothing
      // of a module that never got its row stays on the disk.
      runs.remove(id, run);
      if (existing == null && repository.findById(id).isEmpty()) {
        deleteDir(paths.pluginDir(id));
      }
      throw e;
    }
  }

  /**
   * Replaces a person's own build of a module with the one GateRook's list has, as an update does: the way back to
   * GateRook's connector while connections use the module's types.
   */
  public Status replaceOwn(String moduleId) {
    PluginEntity row = store.find(moduleId).filter(PluginEntity::isModule).orElseThrow(() ->
        ProblemException.notFound("No such connector", "There is no connector module " + moduleId + "."));
    if (row.isSigned()) {
      return status(row);
    }
    if (channel.configured()) {
      channel.state();
    }
    ModuleChannel.Entry entry = channel.module(moduleId).orElseThrow(() -> ProblemException.conflict(
        "Not available", channel.installProblem().orElse("GateRook's list has no connector module " + moduleId
            + ".")));
    return status(start(entry, row));
  }

  /** The entry of GateRook's signed list that pins this very package, when the list can be installed from. */
  private Optional<ModuleChannel.Entry> pinnedBy(String id, String version, String sha256) {
    if (!channel.configured() || channel.installProblem().isPresent()) {
      return Optional.empty();
    }
    return channel.exact(id, version).filter(e -> e.sha256().equalsIgnoreCase(sha256));
  }

  private void refuseRevoked(PluginManifest module, String sha256) {
    if (channel.revoked(sha256)) {
      throw ProblemException.conflict("Revoked", "This build of " + module.name() + " was revoked: "
          + channel.revocationReason(sha256).orElse("no reason given") + ".");
    }
  }

  /** The installed module of that id, {@code null} when there is none; a plugin of that id is in the way. */
  private PluginEntity installedRow(String id) {
    PluginEntity row = repository.findById(id).orElse(null);
    if (row == null) {
      return null;
    }
    if (!row.isModule()) {
      throw ProblemException.conflict("Name taken", "A plugin named " + id + " is installed. Uninstall it to "
          + "install this connector module.");
    }
    return store.find(id).orElseThrow(() -> ProblemException.conflict("Changed outside GateRook", "The installed "
        + id + " was changed outside GateRook. Review it in Connectors first."));
  }

  /**
   * A file never replaces another build of the version that is installed: the two would share a folder and a name
   * in every record. The same build again is taken only when its package is missing here (it brings it back).
   */
  private Optional<ProblemException> versionProblem(PluginEntity existing, PluginInstaller.Staged staged) {
    PluginManifest module = staged.manifest();
    if (existing == null || !existing.getCurrentVersion().equals(module.version())) {
      return Optional.empty();
    }
    if (!existing.getSha256().equalsIgnoreCase(staged.sha256())) {
      return Optional.of(ProblemException.conflict("Version taken", module.name() + " " + module.version()
          + " is installed with other contents. Give your build another version, or remove the installed one "
          + "first."));
    }
    return Files.isRegularFile(paths.jar(module.id(), module.version())) ? Optional.of(ProblemException.conflict(
        "Already installed", module.name() + " " + module.version() + " is already installed.")) : Optional.empty();
  }

  /**
   * A module that GateRook's list doesn't pin adds only what is nobody else's: no connector type or tool of this
   * build, of an installed package or of another module the list has. A build of a module the list has may keep
   * that module's own names.
   */
  private void checkOwn(PluginManifest module) {
    java.util.Set<String> ownListed = new java.util.TreeSet<>();
    channel.versions(module.id()).forEach(e -> e.types().forEach(type -> ownListed.addAll(type.tools())));
    java.util.Set<String> listed = channel.toolNames();
    for (PluginManifest.Type type : module.types()) {
      Optional<String> owner = source.packageOf(type.id());
      if (owner.isPresent() && !owner.get().equals(module.id())) {
        throw ProblemException.conflict("Name taken", owner.get() + " already adds a connector type " + type.id()
            + ".");
      }
      Optional<ModuleChannel.Entry> other = channel.ofType(type.id()).filter(e -> !e.id().equals(module.id()));
      if (other.isPresent()) {
        throw ProblemException.conflict("Name taken", type.id() + " is the connector type of GateRook's own "
            + other.get().name() + ".");
      }
      for (ai.msrv.gaterook.connector.api.ToolDefinition tool : type.tools()) {
        if (listed.contains(tool.name()) && !ownListed.contains(tool.name())) {
          throw ProblemException.conflict("Name taken", tool.name() + " is a tool of one of GateRook's own "
              + "connectors.");
        }
      }
    }
    registry.ownModuleProblem(module.id(), module.connectorManifests()).ifPresent(problem -> {
      throw ProblemException.unprocessable("Can't be installed here", module.name() + " can't be installed: "
          + problem + ".", "/file");
    });
  }

  /** The package must be what the index said: this module at this version, adding these connector types. */
  private void checkAgainst(ModuleChannel.Entry entry, PluginManifest manifest) {
    if (!manifest.id().equals(entry.id()) || !manifest.version().equals(entry.version())) {
      throw ProblemException.conflict("Package doesn't match the signed list", "The package of " + entry.ref()
          + " is " + manifest.id() + " " + manifest.version() + ".");
    }
    List<String> listed = entry.types().stream().map(ModuleChannel.TypeInfo::id).sorted().toList();
    List<String> actual = manifest.types().stream().map(PluginManifest.Type::id).sorted().toList();
    if (!listed.equals(actual)) {
      throw ProblemException.conflict("Package doesn't match the signed list", entry.ref() + " adds " + actual
          + ", the list says " + listed + ".");
    }
    for (String type : actual) {
      Optional<String> owner = source.packageOf(type);
      if (owner.isPresent() && !owner.get().equals(entry.id())) {
        throw ProblemException.conflict("Name taken", "The plugin " + owner.get() + " already adds a connector "
            + "type " + type + ". Uninstall it to use GateRook's own " + entry.name() + ".");
      }
    }
    if (repository.findById(entry.id()).filter(row -> !row.isModule()).isPresent()) {
      throw ProblemException.conflict("Name taken", "A plugin named " + entry.id() + " is installed. Uninstall it "
          + "to use GateRook's own " + entry.name() + ".");
    }
  }

  /** Writes the module's signed row and registers its types: a first installation, the same version again, or another. */
  private void record(PluginInstaller.Staged placed, PluginEntity existing, String reason) {
    PluginManifest module = placed.manifest();
    String id = module.id();
    HostLauncher.Spec spec = HostLauncher.Spec.of(module, placed.jar(), placed.sha256());
    PluginSupervisor supervisor = source.supervisor(id);
    boolean sameBuild = existing != null && existing.getCurrentVersion().equals(module.version())
        && existing.getSha256().equalsIgnoreCase(placed.sha256());
    if (sameBuild) {
      // The package came back as it was installed: nothing to record.
      return;
    }
    // Another version of a module whose host runs: the new host loads beside it and takes over.
    HostProcess next = null;
    if (existing != null && supervisor != null && supervisor.running()) {
      try {
        next = supervisor.startAlternate(spec);
      } catch (HostLauncher.HostStartException e) {
        throw ProblemException.unprocessable("Connector didn't load", module.name() + " " + module.version()
            + " couldn't load (" + e.phase() + "): " + e.getMessage(), "/module");
      }
    }
    AtomicBoolean unrevoked = new AtomicBoolean();
    try {
      writeTx.executeWithoutResult(s -> {
        PluginEntity entity = repository.findById(id).orElse(null);
        String from = entity == null ? null : entity.getCurrentVersion();
        if (entity == null) {
          entity = PluginEntity.module(id, module.version(), placed.signed(), placed.source(), placed.sha256(),
              placed.sizeBytes(), store.map(module), clock.instant());
        } else {
          // What waits for approval on the module's connections was reviewed against the old code.
          java.util.Set<String> types = new java.util.TreeSet<>();
          store.moduleManifest(entity).types().forEach(t -> types.add(t.id()));
          module.types().forEach(t -> types.add(t.id()));
          if (entity.getCurrentVersion().equals(module.version())) {
            entity.replaceBuild(placed.signed(), placed.source(), placed.sha256(), placed.sizeBytes(),
                store.map(module), clock.instant());
          } else {
            entity.upgrade(module.version(), placed.signed(), placed.source(), placed.sha256(), placed.sizeBytes(),
                store.map(module), clock.instant().plus(PluginService.ROLLBACK_WINDOW), clock.instant());
          }
          if (entity.getState() == PluginState.REVOKED) {
            entity.setState(PluginState.INSTALLED, clock.instant());
            unrevoked.set(true);
          }
          types.forEach(type -> publisher.publishEvent(PluginGenerationSwitched.ofModuleType(type, from,
              module.version())));
        }
        entity.setCatalogEntry(placed.catalogEntry());
        entity = repository.saveAndFlush(entity);
        store.sign(entity);
        setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.PLUGIN, "Connector "
            + module.name(), from, (from == null ? "Installed " : "") + module.version(), reason, null, null, null,
            null));
      });
    } catch (RuntimeException e) {
      if (next != null) {
        supervisor.discard(next);
      }
      throw e;
    }
    source.registerModule(module, spec, true, deadline -> awaitFiles(id, deadline), placed.signed());
    if (next != null) {
      supervisor.switchTo(next, spec);
    }
    if (existing != null && !(existing.isSigned() && placed.signed())) {
      // Whose word the core takes changed with the build, or a person's confirmations were of the build before: the
      // endpoints of the module's connections are built again from the connector that serves them now.
      writeTx.executeWithoutResult(s -> module.types().forEach(type -> publisher.publishEvent(
          new ai.msrv.gaterook.core.connectors.ConnectorTypeChanged(type.id()))));
    }
    if (unrevoked.get()) {
      registry.synchronize();
      for (PluginManifest.Type type : module.types()) {
        try {
          registry.setEnabled(type.id(), true, "Replaced the revoked build with " + module.version());
        } catch (ProblemException e) {
          log.warn("The type {} of {} couldn't be switched on again: {}", type.id(), id, e.getMessage());
        }
      }
    }
  }

  // ── Updates, roll back, revocation ──────────────────────────────────────────────────────────────────────────

  /**
   * Brings the installed modules to what the list says: a revoked package is switched off, and every module moves to
   * the best listed version this build can run that is past the one a person rolled it back from. Checks the
   * list first when the last check is old. Nothing asks the person anything.
   *
   * @return the modules whose update started
   */
  public List<String> updateInstalled() {
    if (!channel.configured() || lock.locked()) {
      return List.of();
    }
    channel.state();
    if (channel.installProblem().isPresent()) {
      return List.of();
    }
    applyRevocations();
    Map<String, String> held = held();
    List<String> started = new ArrayList<>();
    Instant now = clock.instant();
    for (PluginEntity row : modules()) {
      if (row.getState() == PluginState.DISABLED || !row.isSigned()) {
        // Switched off, or a person's own build: the list replaces neither.
        continue;
      }
      boolean revoked = row.getState() == PluginState.REVOKED;
      // The best version that isn't revoked and that is past the one a person rolled this module back from.
      String floor = held.get(row.getId());
      Optional<ModuleChannel.Entry> best = channel.versions(row.getId()).stream()
          .filter(e -> !channel.revoked(e.sha256()) && (floor == null || ModuleChannel.newer(e.version(), floor)
              || (revoked && !e.version().equals(floor)))).findFirst();
      if (best.isEmpty() || (!revoked && !ModuleChannel.newer(best.get().version(), row.getCurrentVersion()))) {
        continue;
      }
      Run last = runs.get(row.getId());
      if (last != null && (!last.done.isDone() || (last.version.equals(best.get().version())
          && last.failedRecently(now, UPDATE_RETRY)))) {
        continue;
      }
      try {
        log.info("Connector module {} {} updates to {}", row.getId(), row.getCurrentVersion(), best.get().version());
        start(best.get(), row);
        started.add(row.getId());
      } catch (ProblemException e) {
        log.info("Connector module {} couldn't be updated: {}", row.getId(), e.getMessage());
      }
    }
    started.addAll(installAhead());
    return List.copyOf(started);
  }

  /**
   * Returns an installed module to the version it had before, which is kept for a week. An update then installs only
   * a version past the one it was rolled back from.
   */
  public Status rollback(String moduleId, String reason) {
    PluginEntity row = store.find(moduleId).filter(PluginEntity::isModule).orElseThrow(() ->
        ProblemException.notFound("No such connector", "There is no connector module " + moduleId + "."));
    String previous = row.getPreviousVersion();
    Path jar = previous == null ? null : paths.jar(moduleId, previous);
    if (jar == null || !Files.isRegularFile(jar)) {
      throw ProblemException.conflict("Nothing to roll back to", "The previous version of " + moduleId
          + " is no longer kept.");
    }
    String sha = PluginInstaller.sha256(jar);
    // The kept file must be the one that was installed, as every load is checked against the row.
    if (row.getPreviousSha256() == null || !row.getPreviousSha256().equalsIgnoreCase(sha)) {
      throw ProblemException.conflict("Package changed on disk", "The kept " + previous + " of " + moduleId
          + " isn't the package that was installed.");
    }
    if (channel.revoked(sha)) {
      throw ProblemException.conflict("Revoked", moduleId + " " + previous + " was revoked: "
          + channel.revocationReason(sha).orElse("no reason given") + ".");
    }
    PluginManifest manifest;
    try (ZipFile zip = new ZipFile(jar.toFile()); InputStream in = zip.getInputStream(zip.getEntry(
        PluginManifest.FILE))) {
      manifest = PluginManifest.read(in.readAllBytes());
    } catch (IOException | PluginManifest.InvalidManifest | RuntimeException e) {
      throw ProblemException.conflict("Can't read the package", e.getMessage());
    }
    if (!manifest.module() || !manifest.id().equals(moduleId) || !manifest.version().equals(previous)) {
      throw ProblemException.conflict("Package changed on disk", "The kept package isn't " + moduleId + " "
          + previous + ".");
    }
    String from = row.getCurrentVersion();
    long size;
    try {
      size = Files.size(jar);
    } catch (IOException e) {
      size = 0;
    }
    // The kept build is judged on its own: GateRook's when the list pins this very package, else a person's own.
    boolean pinned = pinnedBy(moduleId, previous, sha).isPresent();
    PluginInstaller.Staged staged = new PluginInstaller.Staged(Ids.next("stg_"), jar, sha, size, manifest, pinned,
        pinned ? PluginSource.CATALOG : PluginSource.FILE, pinned ? moduleId + "@" + previous : null,
        moduleId + "-" + previous + ".jar", null);
    record(staged, row, reason == null || reason.isBlank() ? "Rolled back" : reason);
    hold(moduleId, from);
    registry.synchronize();
    log.info("Connector module {} rolled back {} -> {}", moduleId, from, previous);
    return statusOf(moduleId).orElseThrow();
  }

  /**
   * An installed module whose package the list revoked is switched off: its host stops and its types leave the
   * endpoints until {@link #updateInstalled} replaces it with a package that isn't revoked.
   */
  void applyRevocations() {
    for (PluginEntity row : modules()) {
      if (row.getState() == PluginState.REVOKED || !channel.revoked(row.getSha256())) {
        continue;
      }
      String why = channel.revocationReason(row.getSha256()).orElse("no reason given");
      PluginManifest module = store.moduleManifest(row);
      writeTx.executeWithoutResult(s -> {
        PluginEntity entity = repository.findById(row.getId()).orElseThrow();
        if (!store.verify(entity)) {
          return;
        }
        entity.setState(PluginState.REVOKED, clock.instant());
        entity = repository.saveAndFlush(entity);
        store.sign(entity);
        setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.PLUGIN, "Connector "
            + module.name(), row.getCurrentVersion(), "Revoked: " + why, null, null, null, null, null));
      });
      PluginSupervisor supervisor = source.supervisor(row.getId());
      if (supervisor != null) {
        supervisor.disable();
      }
      for (PluginManifest.Type type : module.types()) {
        try {
          registry.setEnabled(type.id(), false, "Revoked: " + why);
        } catch (ProblemException e) {
          log.warn("The type {} of revoked module {} couldn't be switched off: {}", type.id(), row.getId(),
              e.getMessage());
        }
      }
      log.warn("Connector module {} {} was revoked ({}): it is switched off", row.getId(), row.getCurrentVersion(),
          why);
    }
  }

  /** The version each module was rolled back from. */
  private Map<String, String> held() {
    Map<String, String> out = new LinkedHashMap<>();
    settings.find(HELD).ifPresent(s -> s.value().properties().forEach(e -> out.put(e.getKey(),
        e.getValue().asString(""))));
    return out;
  }

  private void hold(String moduleId, String version) {
    Map<String, String> held = held();
    String before = held.get(moduleId);
    if (before != null && !ModuleChannel.newer(version, before)) {
      return;
    }
    ObjectNode value = JsonNodeFactory.instance.objectNode();
    held.forEach(value::put);
    value.put(moduleId, version);
    settings.put(HELD, value, null);
  }

  /**
   * Keeps a module's folder to the current version and, for its week, the previous one: every other version an update
   * or a roll back left is deleted.
   */
  private void pruneVersions(PluginEntity row) {
    Path dir = paths.pluginDir(row.getId());
    if (!Files.isDirectory(dir)) {
      return;
    }
    boolean keepPrevious = row.getPreviousVersion() != null && (row.getPreviousKeptUntil() == null
        || clock.instant().isBefore(row.getPreviousKeptUntil()));
    try (var versions = Files.list(dir)) {
      for (Path v : versions.toList()) {
        String name = v.getFileName().toString();
        boolean keep = name.equals(row.getCurrentVersion()) || (keepPrevious && name.equals(row.getPreviousVersion()));
        if (!keep && Files.isDirectory(v)) {
          deleteDir(v);
        }
      }
    } catch (IOException e) {
      log.debug("Couldn't prune the versions of module {}: {}", row.getId(), e.toString());
    }
  }

  /** The Java runtime modules run on: here already, or downloaded now. */
  private void requireRuntime() {
    if (runtime.available()) {
      return;
    }
    Instant end = clock.instant().plus(RUNTIME_WAIT);
    RuntimeStatus status = runtime.prepareRuntime();
    while ("downloading".equals(status.state()) && clock.instant().isBefore(end)) {
      try {
        Thread.sleep(250);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw ProblemException.conflict("Cancelled", "The installation was cancelled.");
      }
      status = runtime.runtimeStatus();
    }
    if (!"ready".equals(status.state()) && !runtime.available()) {
      throw ProblemException.conflict("Java support is not ready", status.message() == null
          ? "The Java runtime connectors run on couldn't be prepared." : status.message());
    }
  }

  // ── Uninstall ───────────────────────────────────────────────────────────────────────────────────────────────

  /** Removes an installed module, its types and its files. Refused while connections use one of its types. */
  public void uninstall(String moduleId, String reason) {
    PluginEntity row = store.find(moduleId).filter(PluginEntity::isModule).orElseThrow(() ->
        ProblemException.notFound("No such connector", "There is no connector module " + moduleId + "."));
    PluginManifest module = store.moduleManifest(row);
    Path dir = paths.pluginDir(moduleId);
    Path data = paths.dataDir(moduleId);
    source.removeModule(moduleId);
    // A type this build compiles in keeps its row; its link to the module goes with this upsert.
    registry.synchronize();
    try {
      writeTx.executeWithoutResult(s -> {
        for (PluginManifest.Type type : module.types()) {
          if (!registry.compiledIn(type.id())) {
            connectorTypes.findById(type.id()).ifPresent(connectorTypes::delete);
          }
        }
        connectorTypes.flush();
        repository.deleteById(moduleId);
        repository.flush();
        setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.PLUGIN, "Connector "
            + module.name(), "Installed", "Removed", reason, null, null, null, null));
      });
    } catch (DataIntegrityViolationException e) {
      register(row);
      registry.synchronize();
      throw ProblemException.conflict("In use", "Connections still use " + module.name() + ". Remove them first.");
    }
    runs.remove(moduleId);
    deleteDir(dir);
    deleteDir(data);
    registry.synchronize();
  }

  // ── Reads ───────────────────────────────────────────────────────────────────────────────────────────────────

  /** Every module: the installed ones and those the channel lists, by name. Reads never start a download. */
  public List<Status> list() {
    Map<String, Status> out = new LinkedHashMap<>();
    for (PluginEntity row : modules()) {
      try {
        out.put(row.getId(), status(row));
      } catch (RuntimeException e) {
        log.debug("Connector module {} can't be shown: {}", row.getId(), e.toString());
      }
    }
    for (ModuleChannel.Entry entry : channel.modules()) {
      out.computeIfAbsent(entry.id(), id -> status(entry));
    }
    return out.values().stream().sorted(Comparator.comparing(s -> s.name().toLowerCase(Locale.ROOT))).toList();
  }

  /** The module of that id, or the one that adds the connector type of that id. */
  public Optional<Status> statusOf(String typeOrModuleId) {
    Optional<PluginEntity> installed = installedFor(typeOrModuleId);
    if (installed.isPresent()) {
      return installed.map(this::status);
    }
    return listedNow(typeOrModuleId).map(this::status);
  }

  /** The channel, for the admin API. */
  public ModuleChannel channel() {
    return channel;
  }

  private Status status(Run run) {
    return statusOf(run.id).orElseGet(() -> new Status(run.id, run.name, run.version, state(run, "available"),
        step(run), run.sizeBytes, run.message, run.types, false, null, null, true));
  }

  private Status status(ModuleChannel.Entry entry) {
    Run run = runs.get(entry.id());
    return new Status(entry.id(), entry.name(), entry.version(), state(run, "available"), step(run), entry.size(),
        run == null ? null : run.message, entry.types(), false, null, null, true);
  }

  private Status status(PluginEntity row) {
    PluginManifest module = store.moduleManifest(row);
    List<ModuleChannel.TypeInfo> types = typesOf(module);
    Run run = runs.get(row.getId());
    boolean here = Files.isRegularFile(paths.jar(row.getId(), row.getCurrentVersion()));
    String settled = row.getState() == PluginState.REVOKED ? "revoked"
        : row.getState() != PluginState.INSTALLED ? "disabled" : here ? "ready" : "missing";
    boolean keepsPrevious = row.getPreviousVersion() != null && (row.getPreviousKeptUntil() == null
        || clock.instant().isBefore(row.getPreviousKeptUntil()))
        && Files.isRegularFile(paths.jar(row.getId(), row.getPreviousVersion()));
    // For a person's own build: the version GateRook's list has of the module, whatever it is.
    String listed = channel.module(row.getId()).map(ModuleChannel.Entry::version)
        .filter(v -> !row.isSigned() || ModuleChannel.newer(v, row.getCurrentVersion())).orElse(null);
    return new Status(row.getId(), module.name(), row.getCurrentVersion(), state(run, settled), step(run),
        row.getSizeBytes(), run == null ? null : run.message, types, true,
        keepsPrevious ? row.getPreviousVersion() : null, listed, row.isSigned());
  }

  /** A module's connector types as a card shows them. */
  private static List<ModuleChannel.TypeInfo> typesOf(PluginManifest module) {
    return module.types().stream().map(t -> new ModuleChannel.TypeInfo(t.id(), t.name(), t.icon(), t.group(),
        t.description(), t.tools().stream().map(d -> d.name()).toList())).toList();
  }

  /** A run under way is {@code installing}, a failed one {@code failed} unless what it was for is here anyway. */
  private static String state(Run run, String settled) {
    if (run == null || run.step == Step.READY) {
      return settled;
    }
    if (run.step == Step.FAILED) {
      return "ready".equals(settled) ? settled : "failed";
    }
    return "installing";
  }

  private static String step(Run run) {
    return run == null || run.done.isDone() ? null : run.step.name().toLowerCase(Locale.ROOT);
  }

  private void step(Run run, Step step) {
    run.step = step;
    publish(run);
  }

  /** {@code module.state}: the UI's rows and cards follow an installation without asking. */
  private void publish(Run run) {
    ObjectNode data = JsonNodeFactory.instance.objectNode().put("id", run.id).put("version", run.version)
        .put("step", run.step.name().toLowerCase(Locale.ROOT)).put("sizeBytes", run.sizeBytes);
    if (run.message != null) {
      data.put("message", run.message);
    }
    run.types.forEach(t -> data.withArray("types").add(t.id()));
    try {
      events.publish("module.state", data);
    } catch (RuntimeException e) {
      log.debug("module.state wasn't published: {}", e.toString());
    }
  }

  // ── Internals ───────────────────────────────────────────────────────────────────────────────────────────────

  private List<PluginEntity> modules() {
    return store.verifiedAll().stream().filter(PluginEntity::isModule).toList();
  }

  /** The installed module of that id, or the installed one that adds the connector type of that id. */
  private Optional<PluginEntity> installedFor(String typeOrModuleId) {
    String moduleId = source.moduleOf(typeOrModuleId).orElse(typeOrModuleId);
    return store.find(moduleId).filter(PluginEntity::isModule).or(() -> installedWithType(typeOrModuleId));
  }

  /**
   * The installed module whose row says it adds the connector type, registered or not: a module's types register
   * when the core is ready, and a module that was installed ahead for a type the build compiled in never registered
   * that type at all.
   */
  private Optional<PluginEntity> installedWithType(String typeId) {
    for (PluginEntity row : modules()) {
      try {
        if (store.moduleManifest(row).types().stream().anyMatch(type -> type.id().equals(typeId))) {
          return Optional.of(row);
        }
      } catch (RuntimeException e) {
        log.debug("Connector module {} can't be read: {}", row.getId(), e.toString());
      }
    }
    return Optional.empty();
  }

  /** The channel's module of that id or for that type, checking the channel first when the last check is old. */
  private Optional<ModuleChannel.Entry> listed(String typeOrModuleId) {
    if (!channel.configured()) {
      return Optional.empty();
    }
    channel.state();
    return listedNow(typeOrModuleId);
  }

  private Optional<ModuleChannel.Entry> listedNow(String typeOrModuleId) {
    return channel.module(typeOrModuleId).or(() -> channel.ofType(typeOrModuleId));
  }

  private static void deleteDir(Path dir) {
    if (dir == null || !Files.exists(dir)) {
      return;
    }
    try (var walk = Files.walk(dir)) {
      walk.sorted(Comparator.reverseOrder()).forEach(p -> {
        try {
          Files.deleteIfExists(p);
        } catch (IOException ignored) {
          // Best effort.
        }
      });
    } catch (IOException ignored) {
      // Best effort.
    }
  }
}
