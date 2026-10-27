package ai.msrv.gaterook.core.connectors.plugins;

import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.core.connectors.ConnectorRegistry;
import ai.msrv.gaterook.core.connectors.PluginGenerationSwitched;
import ai.msrv.gaterook.core.connectors.internal.persistence.ConnectorTypeRepository;
import ai.msrv.gaterook.core.connectors.internal.persistence.PluginEntity;
import ai.msrv.gaterook.core.connectors.internal.persistence.PluginRepository;
import ai.msrv.gaterook.core.connectors.internal.persistence.PluginSource;
import ai.msrv.gaterook.core.connectors.internal.persistence.PluginState;
import ai.msrv.gaterook.core.platform.Ids;
import ai.msrv.gaterook.core.platform.MasterKeyChanged;
import ai.msrv.gaterook.core.platform.MasterKeyLock;
import ai.msrv.gaterook.core.platform.ProblemException;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder.SetupChange;
import ai.msrv.gaterook.plugin.protocol.GppJson;
import ai.msrv.gaterook.plugin.protocol.PluginManifest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * The plugin life cycle the admin API drives (ARCHITECTURE.md §7.5, report 16 §6): install, update side by side,
 * enable, disable, restart, roll back and uninstall. Each change is one database transaction (the installed version,
 * the signed row, a Setup change), after which {@link ConnectorRegistry} upserts the plugin's {@code connector_type}
 * and endpoint snapshots rebuild; new tools of an update start Off. The host is run by a {@link PluginSupervisor} per
 * plugin, held in the {@link PluginConnectorSource}.
 */
@Service
public class PluginService {

  /** How long the previous version is kept for Roll back (report 16 §6.3). */
  static final Duration ROLLBACK_WINDOW = Duration.ofDays(7);

  private static final Logger log = LoggerFactory.getLogger(PluginService.class);

  /** A plugin as the admin API sees it (no persistence type crosses the module boundary). */
  public record PluginView(String id, String name, String version, String previousVersion, boolean signed,
      String source, String state, boolean enabled, String catalogEntry, java.time.Instant installedAt,
      java.time.Instant updatedAt, int tools) {}

  /** What an update would change, for the update preview (the admin builds the diff from it). */
  public record UpdatePlan(String id, String name, String from, String to, boolean signed, String packageFile,
      long packageSize, String packageSha256, List<ToolLine> toolsBefore, List<ToolLine> toolsAfter,
      List<String> hostsBefore, List<String> hostsAfter, List<String> commandsBefore, List<String> commandsAfter) {}

  /**
   * One tool of a manifest as the update preview compares them: its name, whether it is declared read only, and its
   * declared annotations as one canonical text (a change of any hint shows).
   */
  public record ToolLine(String name, boolean readOnly, String annotations) {

    static ToolLine of(ToolDefinition t) {
      ToolDefinition.Annotations a = t.annotations();
      String text = a == null ? "none" : "readOnly=" + a.readOnlyHint() + ",destructive=" + a.destructiveHint()
          + ",idempotent=" + a.idempotentHint() + ",openWorld=" + a.openWorldHint();
      return new ToolLine(t.name(), a != null && Boolean.TRUE.equals(a.readOnlyHint()), text);
    }
  }

  private final PluginStore store;
  private final PluginRepository repository;
  private final ConnectorTypeRepository connectorTypes;
  private final PluginInstaller installer;
  private final PluginConnectorSource source;
  private final PluginCatalog catalog;
  private final PluginArtifacts artifacts;
  private final PluginPaths paths;
  private final HostRuntimeLocator runtime;
  private final AotCacheManager aot;
  private final ConnectorRegistry registry;
  private final HostLauncher launcher;
  private final MasterKeyLock lock;
  private final ObjectProvider<SetupChangeRecorder> setupChanges;
  private final ApplicationEventPublisher publisher;
  private final TransactionTemplate writeTx;
  private final Clock clock;

  public PluginService(PluginStore store, ConnectorTypeRepository connectorTypes, PluginInstaller installer,
      PluginConnectorSource source, PluginCatalog catalog, PluginArtifacts artifacts, PluginPaths paths,
      HostRuntimeLocator runtime, AotCacheManager aot, ConnectorRegistry registry, HostLauncher launcher,
      MasterKeyLock lock, ObjectProvider<SetupChangeRecorder> setupChanges, ApplicationEventPublisher publisher,
      PlatformTransactionManager transactions, Clock clock) {
    this.store = store;
    this.repository = store.repository();
    this.connectorTypes = connectorTypes;
    this.installer = installer;
    this.source = source;
    this.catalog = catalog;
    this.artifacts = artifacts;
    this.paths = paths;
    this.runtime = runtime;
    this.aot = aot;
    this.registry = registry;
    this.launcher = launcher;
    this.lock = lock;
    this.setupChanges = setupChanges;
    this.publisher = publisher;
    this.writeTx = new TransactionTemplate(transactions);
    this.clock = clock;
  }

  // ── Start ───────────────────────────────────────────────────────────────────────────────────────────────────

  @EventListener(ApplicationReadyEvent.class)
  public void onReady() {
    cleanOrphans();
    // What was checked and never installed (up to 50 MB a JAR) doesn't outlive the run that staged it.
    deleteDir(paths.staging());
    if (lock.locked()) {
      log.info("Plugins are loaded once the master key is available");
    } else {
      loadInstalled();
    }
  }

  @Async
  @TransactionalEventListener
  public void onMasterKeyChanged(MasterKeyChanged event) {
    loadInstalled();
  }

  private synchronized void loadInstalled() {
    // What the last good catalog revoked is off before a host could start (a revocation that came with a refresh
    // before a crash, or with an index accepted while the plugin's row was being changed).
    applyRevocations();
    for (PluginEntity row : store.verifiedAll()) {
      // A disabled or revoked plugin is registered too, its host refused to start: its type stays in Connectors,
      // switched off, instead of vanishing with its connections still pointing at it.
      registerLive(row);
      pruneVersions(row);
    }
    registry.synchronize();
    aot.trainInBackground();
  }

  private void registerLive(PluginEntity row) {
    ConnectorManifest manifest = store.manifest(row);
    source.register(manifest, specFor(row.getId(), row.getCurrentVersion(), row.getSha256()), "info", 15,
        row.getState() == PluginState.INSTALLED, row.isSigned());
  }

  // ── Install ─────────────────────────────────────────────────────────────────────────────────────────────────

  /** Stages and checks a JAR uploaded from this computer. */
  public PluginInstaller.Staged checkFile(byte[] bytes, String fileName) {
    return installer.check(bytes, PluginSource.FILE, null, fileName == null ? "plugin.jar" : fileName);
  }

  /** Stages a catalog entry {@code id@version} by downloading its signed artifact. */
  public PluginInstaller.Staged checkCatalog(String id, String version) {
    JsonNode entry = entry(id, version);
    byte[] bytes = artifacts.download(entry);
    return matching(installer.check(bytes, PluginSource.CATALOG, id + "@" + version,
        PluginArtifacts.fileName(entry, id + "-" + version + ".jar")), id, version);
  }

  /** A JAR the catalog lists as {@code id@version} must be that plugin's manifest at that version. */
  private PluginInstaller.Staged matching(PluginInstaller.Staged staged, String id, String version) {
    if (!staged.manifest().id().equals(id) || !staged.manifest().version().equals(version)) {
      installer.discard(staged.stagingId());
      throw ProblemException.conflict("Package doesn't match the signed catalog", "The catalog's " + id + " "
          + version + " is a JAR of " + staged.manifest().id() + " " + staged.manifest().version() + ".");
    }
    return staged;
  }

  public PluginEntity install(String stagingId, boolean acknowledgeUnsigned, String reason) {
    requireRuntime();
    // Before anything touches plugins/<id>/: placing the JAR would replace the running generation's file or the one
    // kept for Roll back, and a failed probe would delete that folder.
    installer.staged(stagingId).ifPresent(s -> {
      if (repository.existsById(s.manifest().id())) {
        installer.discard(stagingId);
        throw ProblemException.conflict("Already installed", s.manifest().name() + " is already installed; update it "
            + "instead.");
      }
    });
    PluginInstaller.Staged staged = installer.install(stagingId, acknowledgeUnsigned);
    PluginEntity row = writeTx.execute(s -> {
      if (repository.existsById(staged.manifest().id())) {
        throw ProblemException.conflict("Already installed", staged.manifest().name() + " is already installed; "
            + "update it instead.");
      }
      PluginEntity entity = new PluginEntity(staged.manifest().id(), staged.manifest().version(), staged.signed(),
          staged.source(), staged.sha256(), staged.sizeBytes(),
          store.map(staged.manifest().connectorManifest()), clock.instant());
      entity.setCatalogEntry(staged.catalogEntry());
      entity = repository.saveAndFlush(entity);
      store.sign(entity);
      setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.PLUGIN,
          "Plugin " + staged.manifest().name(), null, "Installed " + staged.manifest().version(), reason, null, null,
          null, null));
      return entity;
    });
    registerLive(row);
    registry.synchronize();
    aot.trainInBackground();
    return row;
  }

  // ── Update / roll back ──────────────────────────────────────────────────────────────────────────────────────

  /** Downloads and stages the catalog's {@code toVersion} of an installed plugin (preview and update). */
  public PluginInstaller.Staged stageUpdate(String id, String toVersion) {
    PluginEntity row = require(id);
    if (row.getCurrentVersion().equals(toVersion)) {
      throw ProblemException.conflict("Already current", id + " is already " + toVersion + ".");
    }
    JsonNode entry = entry(id, toVersion);
    byte[] bytes = artifacts.download(entry);
    return matching(installer.check(bytes, PluginSource.CATALOG, id + "@" + toVersion,
        PluginArtifacts.fileName(entry, id + "-" + toVersion + ".jar")), id, toVersion);
  }

  /**
   * Switches an installed plugin to the catalog's {@code toVersion}. A release the catalog lists as unsigned loads only
   * with {@code acknowledgeUnsigned}, as an unsigned install does (§9: unsigned code runs only when a person says so).
   */
  public PluginEntity update(String id, String toVersion, boolean acknowledgeUnsigned, String reason) {
    requireRuntime();
    PluginEntity row = require(id);
    PluginInstaller.Staged staged = stageUpdate(id, toVersion);
    if (!staged.signed() && !acknowledgeUnsigned) {
      throw ProblemException.unprocessable("Unsigned plugin", staged.manifest().name() + " " + toVersion + " isn't "
          + "signed by the catalog. Confirm that you trust it before updating to it.", "/acknowledgeUnsigned");
    }
    PluginInstaller.Staged placed = installer.install(staged.stagingId(), acknowledgeUnsigned);
    switchGeneration(row.getId(), row.getCurrentVersion(), placed, reason);
    return require(id);
  }

  public PluginEntity rollback(String id, String reason) {
    PluginEntity row = require(id);
    String previous = row.getPreviousVersion();
    if (previous == null || !Files.isRegularFile(paths.jar(id, previous))) {
      throw ProblemException.conflict("Nothing to roll back to", "The previous version of " + id + " is no longer "
          + "kept.");
    }
    Path jar = paths.jar(id, previous);
    String sha = PluginInstaller.sha256(jar);
    // The kept file must be the one that was installed, as every load is checked against the database (§7.5).
    if (row.getPreviousSha256() != null && !row.getPreviousSha256().equalsIgnoreCase(sha)) {
      throw ProblemException.conflict("Plugin file changed on disk", "The kept " + previous + " of " + id
          + " isn't the file that was installed: it changed on disk. Install or update it instead.");
    }
    PluginManifest manifest = manifestOf(jar);
    if (catalog.isRevoked(sha)) {
      throw ProblemException.conflict("Revoked", "The catalog revoked " + id + " " + previous + ": "
          + catalog.revocationReason(sha).orElse("no reason given") + ".");
    }
    // The previous build is judged on its own: signed only when the current catalog lists this very JAR (the row
    // carries the signature of the build it runs now, which a roll back leaves).
    boolean listed = catalog.installProblem().isEmpty() && catalog.entry("plugin", id, previous)
        .map(e -> sha.equalsIgnoreCase(e.path("artifact").path("sha256").asString())).orElse(false);
    boolean signed = listed && catalog.entry("plugin", id, previous).map(e -> e.path("signed").asBoolean(true))
        .orElse(false);
    PluginInstaller.Staged staged = new PluginInstaller.Staged(Ids.next("stg_"), jar, sha, fileSize(jar), manifest,
        signed, listed ? PluginSource.CATALOG : PluginSource.FILE, listed ? id + "@" + previous : null,
        id + "-" + previous + ".jar", null);
    switchGeneration(id, row.getCurrentVersion(), staged, reason);
    return require(id);
  }

  private void switchGeneration(String id, String from, PluginInstaller.Staged staged, String reason) {
    String to = staged.manifest().version();
    PluginSupervisor supervisor = source.supervisor(id);
    // The launcher refuses a generation whose code reports tools its manifest doesn't declare.
    HostLauncher.Spec nextSpec = HostLauncher.Spec.of(staged.manifest(), staged.jar(), staged.sha256());
    HostProcess next;
    try {
      next = supervisor.startAlternate(nextSpec);
    } catch (HostLauncher.HostStartException e) {
      throw ProblemException.unprocessable("Update didn't load", staged.manifest().name() + " " + to
          + " couldn't load (" + e.phase() + "): " + e.getMessage(), "/to");
    }
    java.util.concurrent.atomic.AtomicBoolean unrevoked = new java.util.concurrent.atomic.AtomicBoolean();
    try {
      writeTx.executeWithoutResult(s -> {
        PluginEntity entity = repository.findById(id).orElseThrow();
        entity.upgrade(to, staged.signed(), staged.source(), staged.sha256(), staged.sizeBytes(),
            store.map(staged.manifest().connectorManifest()), clock.instant().plus(ROLLBACK_WINDOW), clock.instant());
        if (entity.getState() == PluginState.REVOKED) {
          // A build the catalog didn't revoke replaces the revoked one: the plugin runs again.
          entity.setState(PluginState.INSTALLED, clock.instant());
          unrevoked.set(true);
        }
        entity.setCatalogEntry(staged.catalogEntry());
        entity = repository.saveAndFlush(entity);
        store.sign(entity);
        // The live manifest is the new version before the type is upserted, so tools/list sees it.
        source.register(staged.manifest().connectorManifest(), nextSpec, "info", 15, true, staged.signed());
        setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.PLUGIN,
            "Plugin " + staged.manifest().name(), from, to, reason, null, null, null, null));
        publisher.publishEvent(new PluginGenerationSwitched(id, from, to));
      });
    } catch (RuntimeException e) {
      supervisor.discard(next);
      throw e;
    }
    supervisor.switchTo(next, nextSpec);
    registry.synchronize();
    if (unrevoked.get()) {
      registry.setEnabled(id, true, "Replaced the revoked build with " + to);
    }
    store.find(id).ifPresent(this::pruneVersions);
    log.info("Plugin {} updated {} -> {}", id, from, to);
  }

  // ── Enable / disable / restart ──────────────────────────────────────────────────────────────────────────────

  public PluginEntity setEnabled(String id, boolean enabled, String reason) {
    require(id);
    writeTx.executeWithoutResult(s -> {
      PluginEntity entity = repository.findById(id).orElseThrow();
      if (!store.verify(entity)) {
        throw ProblemException.conflict("Changed outside MCP Gateway", id + " was changed outside MCP Gateway.");
      }
      if (enabled && entity.getState() == PluginState.REVOKED) {
        throw ProblemException.conflict("Revoked", "The catalog revoked this build of " + id + ": "
            + catalog.revocationReason(entity.getSha256()).orElse("no reason given") + ". Update it, or install "
            + "another build.");
      }
      entity.setState(enabled ? PluginState.INSTALLED : PluginState.DISABLED, clock.instant());
      entity = repository.saveAndFlush(entity);
      store.sign(entity);
      setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.PLUGIN, "Plugin " + id,
          enabled ? "Disabled" : "Enabled", enabled ? "Enabled" : "Disabled", reason, null, null, null, null));
    });
    PluginSupervisor supervisor = source.supervisor(id);
    if (supervisor != null) {
      if (enabled) {
        supervisor.enable();
      } else {
        supervisor.disable();
      }
    }
    registry.setEnabled(id, enabled, reason);
    return require(id);
  }

  public void restart(String id) {
    require(id);
    PluginSupervisor supervisor = source.supervisor(id);
    try {
      supervisor.restart();
    } catch (ai.msrv.gaterook.connector.api.ConnectorException e) {
      throw ProblemException.conflict("Couldn't restart", e.getMessage());
    }
  }

  // ── Uninstall ───────────────────────────────────────────────────────────────────────────────────────────────

  public void uninstall(String id, String reason) {
    PluginEntity row = require(id);
    source.remove(id);
    try {
      writeTx.executeWithoutResult(s -> {
        connectorTypes.deleteById(id);
        connectorTypes.flush();
        repository.deleteById(id);
        setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.PLUGIN, "Plugin " + row.getId(),
            "Installed", "Uninstalled", reason, null, null, null, null));
      });
    } catch (DataIntegrityViolationException e) {
      registerLive(row);
      throw ProblemException.conflict("In use", "Connections still use " + id + ". Remove them first.");
    }
    deleteDir(paths.pluginDir(id));
    deleteDir(paths.dataDir(id));
    registry.synchronize();
  }

  // ── Reads ───────────────────────────────────────────────────────────────────────────────────────────────────

  public List<PluginView> installed() {
    return store.verifiedAll().stream().map(this::view).toList();
  }

  public Optional<PluginView> view(String id) {
    return store.find(id).map(this::view);
  }

  public PluginCatalog catalog() {
    return catalog;
  }

  /** The catalog for the Catalog tab: checked again when the last check is older than six hours. */
  public PluginCatalog.State catalogState() {
    java.time.Instant before = catalog.state().checkedAt();
    PluginCatalog.State after = catalog.stateRefreshingIfStale();
    if (after.checkedAt() != null && !after.checkedAt().equals(before)) {
      applyRevocations();
    }
    return after;
  }

  /** Checks the catalog now (Try again, refresh) and applies what it revoked. */
  public PluginCatalog.State refreshCatalog() {
    PluginCatalog.State state = catalog.refresh();
    if (!state.offline()) {
      applyRevocations();
    }
    return state;
  }

  /**
   * An installed plugin whose JAR the catalog revoked is switched off and marked revoked (report 11 §6.2): its host
   * stops, its tools stop answering, and it can't be enabled again — only updated or replaced (Setup change).
   */
  void applyRevocations() {
    for (PluginEntity row : store.verifiedAll()) {
      if (row.getState() == PluginState.REVOKED || !catalog.isRevoked(row.getSha256())) {
        continue;
      }
      String why = catalog.revocationReason(row.getSha256()).orElse("no reason given");
      String was = row.getState().name().toLowerCase(java.util.Locale.ROOT);
      writeTx.executeWithoutResult(s -> {
        PluginEntity entity = repository.findById(row.getId()).orElseThrow();
        if (!store.verify(entity)) {
          return;
        }
        entity.setState(PluginState.REVOKED, clock.instant());
        entity = repository.saveAndFlush(entity);
        store.sign(entity);
        setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.PLUGIN, "Plugin " + row.getId(),
            was, "Revoked by the catalog: " + why, null, null, null, null, null));
      });
      PluginSupervisor supervisor = source.supervisor(row.getId());
      if (supervisor != null) {
        supervisor.disable();
      }
      try {
        // Its type goes off too: its tools leave the endpoints until a build that isn't revoked replaces it.
        registry.setEnabled(row.getId(), false, "Revoked by the catalog: " + why);
      } catch (ProblemException e) {
        log.warn("The type of revoked plugin {} couldn't be switched off: {}", row.getId(), e.getMessage());
      }
      log.warn("The catalog revoked plugin {} {} ({}): it is switched off", row.getId(), row.getCurrentVersion(),
          why);
    }
  }

  /** What an update to {@code to} would change, for the preview; the staged package is dropped again. */
  public UpdatePlan updatePlan(String id, String to) {
    PluginEntity row = require(id);
    PluginInstaller.Staged staged = stageUpdate(id, to);
    try {
      ConnectorManifest before = store.manifest(row);
      List<ToolLine> toolsBefore = before.tools().stream().map(ToolLine::of).toList();
      List<ToolLine> toolsAfter = staged.manifest().tools().stream().map(ToolLine::of).toList();
      return new UpdatePlan(id, staged.manifest().name(), row.getCurrentVersion(), to, staged.signed(),
          staged.fileName(), staged.sizeBytes(), staged.sha256(), toolsBefore, toolsAfter,
          before.access() == null ? List.of() : before.access().hosts(), staged.manifest().access().hosts(),
          before.access() == null ? List.of() : before.access().commands(), staged.manifest().access().commands());
    } finally {
      installer.discard(staged.stagingId());
    }
  }

  private PluginView view(PluginEntity row) {
    ConnectorManifest manifest = store.manifest(row);
    return new PluginView(row.getId(), manifest.name(), row.getCurrentVersion(), row.getPreviousVersion(),
        row.isSigned(), row.getSource().name().toLowerCase(java.util.Locale.ROOT),
        row.getState().name().toLowerCase(java.util.Locale.ROOT), row.getState() == PluginState.INSTALLED,
        row.getCatalogEntry(), row.getInstalledAt(), row.getUpdatedAt(), manifest.tools().size());
  }

  // ── Internals ───────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * The spec an installed generation starts from: its heap and its tools as the JAR's own manifest declares them
   * (what the install checked). A JAR that can't be read gets the defaults: the host refuses it anyway, its SHA-256
   * no longer matches.
   */
  private HostLauncher.Spec specFor(String id, String version, String sha256) {
    Path jar = paths.jar(id, version);
    try {
      return HostLauncher.Spec.of(manifestOf(jar), jar, sha256);
    } catch (ProblemException e) {
      log.warn("The manifest of plugin {} {} can't be read: {}", id, version, e.getMessage());
      return new HostLauncher.Spec(id, version, jar, sha256, 256, Map.of(), List.of());
    }
  }

  /**
   * Keeps {@code plugins/<id>/} to the current version and, for its seven days, the previous one: every other version
   * folder an update or roll back left is deleted (report 16 §8 step 7).
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
      log.debug("Couldn't prune the versions of plugin {}: {}", row.getId(), e.toString());
    }
  }

  private PluginEntity require(String id) {
    return store.find(id).orElseThrow(() -> ProblemException.notFound("Plugin not found",
        "There is no plugin " + id + "."));
  }

  private void requireRuntime() {
    if (!runtime.available()) {
      throw ProblemException.conflict("No plugin runtime", runtime.unavailableReason()
          .orElse("This build can't run plugins."));
    }
  }

  /** The catalog's plugin entry {@code id@version}; the catalog must be loaded and not expired. */
  private JsonNode entry(String id, String version) {
    catalog.installProblem().ifPresent(problem -> {
      throw ProblemException.conflict("Catalog unavailable", problem);
    });
    return catalog.entry("plugin", id, version).orElseThrow(() -> ProblemException.notFound("Not in the catalog",
        id + " " + version + " isn't in the catalog."));
  }

  private static PluginManifest manifestOf(Path jar) {
    try (ZipFile zip = new ZipFile(jar.toFile())) {
      ZipEntry entry = zip.getEntry(PluginManifest.FILE);
      try (InputStream in = zip.getInputStream(entry)) {
        return PluginManifest.parse(GppJson.MAPPER.readTree(in.readAllBytes()));
      }
    } catch (IOException | PluginManifest.InvalidManifest | RuntimeException e) {
      throw ProblemException.conflict("Can't read the plugin", e.getMessage());
    }
  }

  private static long fileSize(Path file) {
    try {
      return Files.size(file);
    } catch (IOException e) {
      return 0;
    }
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

  /** Stops the hosts a previous core left running ({@code run/plugin-hosts.json}: pid and start time must match). */
  private void cleanOrphans() {
    int stopped = launcher.pids().reapOrphans();
    if (stopped > 0) {
      log.info("Stopped {} plugin host(s) a previous run left behind", stopped);
    }
  }
}
