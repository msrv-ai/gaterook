package ai.msrv.gaterook.core.connectors;

import ai.msrv.gaterook.core.connectors.plugins.PluginCatalog;
import ai.msrv.gaterook.core.connectors.plugins.PluginInstaller;
import ai.msrv.gaterook.core.connectors.plugins.PluginService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The public face of the plugin subsystem for the admin API (ARCHITECTURE.md §2.2, §6): the {@code connectors} module
 * keeps installation, the host protocol and the host process in its internal {@code connectors.plugins} subpackage, and
 * exposes only what {@code admin.plugins} needs here — the catalog, checking and installing, the update plan and the
 * life-cycle actions — in types of this module's public API. So the admin controller never reaches into the module's
 * internals, and the module boundaries stay the ones the architecture declares.
 */
@Component
public class Plugins {

  /**
   * The signed catalog's state for {@code GET /api/catalog}.
   *
   * @param problem why the last check failed, or why nothing installs from the catalog now; {@code null} when fine
   */
  public record Catalog(Instant checkedAt, String source, boolean offline, Signature signature, List<Entry> entries,
      String problem) {}

  public record Signature(String keyId, long sequence, Instant expiresAt, boolean ok) {}

  /**
   * One entry of the catalog as the Catalog tab shows it.
   *
   * @param installed the installed version of a plugin; {@code null} otherwise (the admin adds an MCP server's name)
   * @param group     the connection-new group of a plugin's type ({@code Databases}), else {@code null}
   */
  public record Entry(String id, String kind, String name, String version, boolean signed, String description,
      Integer tools, String installed, String icon, List<String> connectionTypes, String group) {}

  /**
   * A checked, not-yet-installed plugin. {@code sandbox}: the OS sandbox it will run in here, or {@code null} ("Not
   * sandboxed on this system").
   */
  public record Check(String stagingId, boolean signed, String sha256, long size, String id, String version,
      String name, String file, JsonNode requires, List<Tool> tools, JsonNode access, JsonNode configPreview,
      String sandbox) {}

  public record Tool(String name, JsonNode declared) {}

  /** An installed plugin as Connectors shows it. */
  public record Installed(String id, String name, String version, String previousVersion, boolean signed,
      String source, String state, boolean enabled, String catalogEntry, Instant installedAt, Instant updatedAt,
      int tools) {}

  /** What an update would change, for the preview. */
  public record UpdatePlan(String id, String name, String from, String to, boolean signed, String packageFile,
      long packageSize, String packageSha256, List<ToolLine> toolsBefore, List<ToolLine> toolsAfter,
      List<String> hostsBefore, List<String> hostsAfter, List<String> commandsBefore, List<String> commandsAfter) {}

  /** A tool of a manifest: its name, whether it is declared read only, its declared annotations as one text. */
  public record ToolLine(String name, boolean readOnly, String annotations) {}

  private final PluginService service;

  Plugins(PluginService service) {
    this.service = service;
  }

  // ── Catalog ─────────────────────────────────────────────────────────────────────────────────────────────────

  /** The catalog, checked again when the last check is older than six hours. */
  public Catalog catalog() {
    return catalog(service.catalogState());
  }

  public Catalog refreshCatalog() {
    return catalog(service.refreshCatalog());
  }

  /** The catalog's plugin entries that aren't installed (the wizard's disabled cards), without a network check. */
  public List<Entry> notInstalledPlugins() {
    PluginCatalog.State state = service.catalog().state();
    List<Entry> out = new ArrayList<>();
    if (state.verified() != null) {
      for (JsonNode e : state.verified().index().path("entries")) {
        Entry entry = entry(e);
        if (entry.kind().equals("plugin") && entry.installed() == null) {
          out.add(entry);
        }
      }
    }
    return List.copyOf(out);
  }

  // ── Check / install ─────────────────────────────────────────────────────────────────────────────────────────

  public Check checkFile(byte[] bytes, String fileName) {
    return check(service.checkFile(bytes, fileName));
  }

  public Check checkCatalog(String id, String version) {
    return check(service.checkCatalog(id, version));
  }

  public void install(String stagingId, boolean acknowledgeUnsigned, String reason) {
    service.install(stagingId, acknowledgeUnsigned, reason);
  }

  // ── Update / life cycle ─────────────────────────────────────────────────────────────────────────────────────

  public UpdatePlan updatePlan(String id, String to) {
    PluginService.UpdatePlan p = service.updatePlan(id, to);
    return new UpdatePlan(p.id(), p.name(), p.from(), p.to(), p.signed(), p.packageFile(), p.packageSize(),
        p.packageSha256(), lines(p.toolsBefore()), lines(p.toolsAfter()), p.hostsBefore(), p.hostsAfter(),
        p.commandsBefore(), p.commandsAfter());
  }

  public void update(String id, String to, boolean acknowledgeUnsigned, String reason) {
    service.update(id, to, acknowledgeUnsigned, reason);
  }

  public void setEnabled(String id, boolean enabled, String reason) {
    service.setEnabled(id, enabled, reason);
  }

  public void restart(String id) {
    service.restart(id);
  }

  public void rollback(String id, String reason) {
    service.rollback(id, reason);
  }

  public void uninstall(String id, String reason) {
    service.uninstall(id, reason);
  }

  // ── Reads ───────────────────────────────────────────────────────────────────────────────────────────────────

  public List<Installed> installed() {
    return service.installed().stream().map(Plugins::installed).toList();
  }

  public Optional<Installed> view(String id) {
    return service.view(id).map(Plugins::installed);
  }

  // ── Mapping ─────────────────────────────────────────────────────────────────────────────────────────────────

  private static Check check(PluginInstaller.Staged staged) {
    PluginInstaller.Report r = staged.report();
    List<Tool> tools = r.tools().stream().map(t -> new Tool(t.name(), t.declared())).toList();
    return new Check(staged.stagingId(), r.signed(), r.sha256(), r.size(), r.id(), r.version(), r.name(), r.fileName(),
        r.requires(), tools, r.access(), r.configSchema(), r.sandbox());
  }

  private static Installed installed(PluginService.PluginView v) {
    return new Installed(v.id(), v.name(), v.version(), v.previousVersion(), v.signed(), v.source(), v.state(),
        v.enabled(), v.catalogEntry(), v.installedAt(), v.updatedAt(), v.tools());
  }

  private static List<ToolLine> lines(List<PluginService.ToolLine> lines) {
    return lines.stream().map(l -> new ToolLine(l.name(), l.readOnly(), l.annotations())).toList();
  }

  private Catalog catalog(PluginCatalog.State state) {
    Signature signature = state.signature() == null ? null : new Signature(state.signature().keyId(),
        state.signature().sequence(), state.signature().expiresAt(), state.signature().ok());
    List<Entry> entries = new ArrayList<>();
    if (state.verified() != null) {
      for (JsonNode e : state.verified().index().path("entries")) {
        entries.add(entry(e));
      }
    }
    return new Catalog(state.checkedAt(), state.source(), state.offline(), signature, List.copyOf(entries),
        state.problem());
  }

  private Entry entry(JsonNode e) {
    String id = e.path("id").asString();
    String kind = e.path("kind").asString("plugin");
    String installed = kind.equals("plugin") ? service.view(id).map(PluginService.PluginView::version).orElse(null)
        : null;
    Integer tools = e.path("tools").isNumber() ? e.get("tools").asInt() : null;
    List<String> connectionTypes = new ArrayList<>();
    for (JsonNode t : e.path("connectionTypes")) {
      connectionTypes.add(t.asString());
    }
    return new Entry(id, kind, e.path("name").asString(id), PluginCatalog.entryVersion(e),
        !e.path("signed").isBoolean() || e.get("signed").asBoolean(true), e.path("description").asString(""), tools,
        installed, e.path("icon").asString(null), connectionTypes.isEmpty() ? null : List.copyOf(connectionTypes),
        e.path("group").asString(null));
  }
}
