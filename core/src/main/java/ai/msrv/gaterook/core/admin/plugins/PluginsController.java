package ai.msrv.gaterook.core.admin.plugins;

import com.fasterxml.jackson.annotation.JsonProperty;
import ai.msrv.gaterook.core.admin.Reasons;
import ai.msrv.gaterook.core.connections.ConnectionConfigs;
import ai.msrv.gaterook.core.connectors.Plugins;
import ai.msrv.gaterook.core.platform.ProblemException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

/**
 * The plugins REST API (ARCHITECTURE.md §6 connectors, steps K4 and K6): the signed catalog, checking a staged plugin
 * from a file or a catalog entry, installing it (unsigned only with acknowledgement), the diff of an update and the
 * update itself, and enable, disable, restart, roll back and uninstall. The shapes match the UI's
 * {@code plugins.api.ts}; the plugin subsystem is reached through the {@code connectors} module's {@link Plugins}
 * facade.
 */
@RestController
@RegisterReflectionForBinding({PluginsController.CatalogView.class, PluginsController.SignatureView.class,
    PluginsController.CatalogEntryView.class, PluginsController.CheckResult.class, PluginsController.ReportView.class,
    PluginsController.ToolView.class, PluginsController.InstallRequest.class, PluginsController.CheckRequest.class,
    PluginsController.UpdatePreview.class, PluginsController.Package.class, PluginsController.Diff.class,
    PluginsController.Hunk.class, PluginsController.DiffLine.class, PluginsController.Reason.class,
    PluginsController.ToRequest.class, PluginsController.Done.class})
class PluginsController {

  /** {@code problem}: why the last check failed or why nothing installs from the catalog now, else {@code null}. */
  record CatalogView(Instant checkedAt, String source, boolean offline, SignatureView signature,
      List<CatalogEntryView> entries, String problem) {}

  record SignatureView(String keyId, long sequence, Instant expiresAt, boolean ok) {}

  record CatalogEntryView(String id, String kind, String name, String version, boolean signed, String description,
      Integer tools, String installed, String icon, List<String> connectionTypes) {}

  record CheckRequest(String url, String catalog) {}

  record CheckResult(String stagingId, ReportView report) {}

  /** {@code sandbox}: {@code landlock} or {@code sandbox-exec}, {@code null} where this system has none. */
  record ReportView(boolean signed, String sha256, long size, String id, String version, String name, String file,
      JsonNode requires, List<ToolView> tools, JsonNode access, JsonNode configPreview, String sandbox) {}

  record ToolView(String name, JsonNode declared) {}

  /** {@code acknowledgeUnsigned} may be left out (false): a missing flag is a refusal, not a malformed request. */
  record InstallRequest(String stagingId, Boolean acknowledgeUnsigned, String reason) {}

  /** {@code package} is a Java keyword: the component is {@code pkg}, the JSON field {@code package} (U5). */
  record UpdatePreview(String id, String name, String from, String to, boolean signed,
      @JsonProperty("package") Package pkg, String summary, Diff diff, List<String> toolsAdded,
      List<String> toolsRemoved) {}

  record Package(String file, long size, String sha256) {}

  record Diff(int added, int removed, List<Hunk> hunks) {}

  record Hunk(String header, List<DiffLine> lines) {}

  record DiffLine(String t, String v) {}

  /** {@code acknowledgeUnsigned} as at install: an update to a release the catalog doesn't sign needs it (§9). */
  record ToRequest(String to, Boolean acknowledgeUnsigned, String reason) {}

  record Reason(String reason) {}

  record Done(boolean ok) {}

  private final Plugins plugins;
  private final ConnectionConfigs connections;

  PluginsController(Plugins plugins, ConnectionConfigs connections) {
    this.plugins = plugins;
    this.connections = connections;
  }

  // ── Catalog ─────────────────────────────────────────────────────────────────────────────────────────────────

  @GetMapping("/api/catalog")
  CatalogView catalog() {
    return catalogView(plugins.catalog());
  }

  @PostMapping("/api/catalog/refresh")
  CatalogView refresh() {
    return catalogView(plugins.refreshCatalog());
  }

  // ── Check / install ─────────────────────────────────────────────────────────────────────────────────────────

  @PostMapping(value = "/api/plugins/check", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  CheckResult checkFile(@RequestParam("file") MultipartFile file) {
    byte[] bytes;
    try {
      bytes = file.getBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return result(plugins.checkFile(bytes, file.getOriginalFilename()));
  }

  @PostMapping(value = "/api/plugins/check", consumes = MediaType.APPLICATION_JSON_VALUE)
  CheckResult check(@RequestBody CheckRequest request) {
    if (request.catalog() != null && !request.catalog().isBlank()) {
      String[] parts = request.catalog().split("@", 2);
      if (parts.length != 2) {
        throw ProblemException.unprocessable("Bad catalog reference", "A catalog reference is id@version.",
            "/catalog");
      }
      return result(plugins.checkCatalog(parts[0], parts[1]));
    }
    if (request.url() != null && !request.url().isBlank()) {
      throw ProblemException.unprocessable("URL install not available yet", "Install from a file or the catalog; "
          + "installing from a URL isn't available in this build.", "/url");
    }
    throw ProblemException.unprocessable("Nothing to check", "Send a file or a catalog reference.", "/url");
  }

  @PostMapping(value = "/api/plugins/install", consumes = MediaType.APPLICATION_JSON_VALUE)
  Done install(@RequestBody InstallRequest request) {
    if (request.stagingId() == null || request.stagingId().isBlank()) {
      throw ProblemException.unprocessable("Nothing staged", "Check the plugin first, then install what was checked.",
          "/stagingId");
    }
    plugins.install(request.stagingId(), Boolean.TRUE.equals(request.acknowledgeUnsigned()),
        Reasons.check(request.reason()));
    return new Done(true);
  }

  // ── Update ──────────────────────────────────────────────────────────────────────────────────────────────────

  @GetMapping("/api/plugins/{id}/update-preview")
  UpdatePreview updatePreview(@PathVariable String id, @RequestParam("to") String to) {
    Plugins.UpdatePlan plan = plugins.updatePlan(id, to);
    List<String> before = plan.toolsBefore().stream().map(Plugins.ToolLine::name).toList();
    List<String> after = plan.toolsAfter().stream().map(Plugins.ToolLine::name).toList();
    List<String> added = after.stream().filter(n -> !before.contains(n)).toList();
    List<String> removed = before.stream().filter(n -> !after.contains(n)).toList();
    boolean annotations = plan.toolsBefore().stream().anyMatch(b -> plan.toolsAfter().stream()
        .anyMatch(a -> a.name().equals(b.name()) && !a.annotations().equals(b.annotations())));
    String oldText = manifestSummary(plan.from(), plan.toolsBefore(), plan.hostsBefore(), plan.commandsBefore());
    String newText = manifestSummary(plan.to(), plan.toolsAfter(), plan.hostsAfter(), plan.commandsAfter());
    return new UpdatePreview(id, plan.name(), plan.from(), plan.to(), plan.signed(),
        new Package(plan.packageFile(), plan.packageSize(), plan.packageSha256()), summary(added, removed,
            annotations, !plan.hostsBefore().equals(plan.hostsAfter()),
            !plan.commandsBefore().equals(plan.commandsAfter())),
        ManifestDiff.unified(oldText, newText), added, removed);
  }

  @PostMapping("/api/plugins/{id}/update")
  Done update(@PathVariable String id, @RequestBody ToRequest request) {
    if (request == null || request.to() == null || request.to().isBlank()) {
      throw ProblemException.unprocessable("Version missing", "Say which version to update to.", "/to");
    }
    plugins.update(id, request.to(), Boolean.TRUE.equals(request.acknowledgeUnsigned()),
        Reasons.check(request.reason()));
    return new Done(true);
  }

  @PostMapping("/api/plugins/{id}/enable")
  Done enable(@PathVariable String id, @RequestBody(required = false) Reason reason) {
    plugins.setEnabled(id, true, reason == null ? null : Reasons.check(reason.reason()));
    return new Done(true);
  }

  @PostMapping("/api/plugins/{id}/disable")
  Done disable(@PathVariable String id, @RequestBody(required = false) Reason reason) {
    plugins.setEnabled(id, false, reason == null ? null : Reasons.check(reason.reason()));
    return new Done(true);
  }

  @PostMapping("/api/plugins/{id}/restart")
  Done restart(@PathVariable String id) {
    plugins.restart(id);
    return new Done(true);
  }

  @PostMapping("/api/plugins/{id}/rollback")
  Done rollback(@PathVariable String id, @RequestBody(required = false) Reason reason) {
    plugins.rollback(id, reason == null ? null : Reasons.check(reason.reason()));
    return new Done(true);
  }

  @DeleteMapping("/api/plugins/{id}")
  Done uninstall(@PathVariable String id, @RequestBody(required = false) Reason reason) {
    plugins.uninstall(id, reason == null ? null : Reasons.check(reason.reason()));
    return new Done(true);
  }

  // ── Mapping ─────────────────────────────────────────────────────────────────────────────────────────────────

  private static CheckResult result(Plugins.Check c) {
    List<ToolView> tools = c.tools().stream().map(t -> new ToolView(t.name(), t.declared())).toList();
    return new CheckResult(c.stagingId(), new ReportView(c.signed(), c.sha256(), c.size(), c.id(), c.version(),
        c.name(), c.file(), c.requires(), tools, c.access(), configPreview(c.configPreview()), c.sandbox()));
  }

  /**
   * The connection form in words, as the kit's review says it ("Host, port, database, user and password, from its
   * config schema"): the titles of the top-level fields a connection asks for, without the collapsed sections
   * ({@code x-collapsed}: TLS, limits) the form keeps folded. The UI joins them into the sentence.
   */
  static JsonNode configPreview(JsonNode configSchema) {
    tools.jackson.databind.node.ArrayNode out = tools.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
    if (configSchema != null) {
      for (var e : configSchema.path("properties").properties()) {
        JsonNode field = e.getValue();
        if (field.path("x-collapsed").asBoolean(false) || field.path("readOnly").asBoolean(false)) {
          continue;
        }
        out.add(field.path("title").asString(e.getKey()));
      }
    }
    return out;
  }

  private CatalogView catalogView(Plugins.Catalog catalog) {
    SignatureView signature = catalog.signature() == null ? null : new SignatureView(catalog.signature().keyId(),
        catalog.signature().sequence(), catalog.signature().expiresAt(), catalog.signature().ok());
    Map<String, String> servers = catalog.entries().stream().anyMatch(e -> e.kind().equals("mcp-server"))
        ? installedServers() : Map.of();
    List<CatalogEntryView> entries = catalog.entries().stream().map(e -> new CatalogEntryView(e.id(), e.kind(),
        e.name(), e.version(), e.signed(), e.description(), e.tools(),
        e.kind().equals("mcp-server") ? servers.get(e.id()) : e.installed(), e.icon(), e.connectionTypes()))
        .toList();
    return new CatalogView(catalog.checkedAt(), catalog.source(), catalog.offline(), signature, entries,
        catalog.problem());
  }

  /**
   * MCP servers added from the catalog, by entry id: the name of the connection whose config names the entry
   * ({@code catalogEntry: github-mcp@0.6.2}) — the card's "Installed as github-mcp".
   */
  private Map<String, String> installedServers() {
    Map<String, String> out = new LinkedHashMap<>();
    for (ConnectionConfigs.Stored s : connections.all()) {
      if (!"mcp-server".equals(s.config().type())) {
        continue;
      }
      String ref = s.config().config().path("catalogEntry").asString(null);
      int at = ref == null ? -1 : ref.lastIndexOf('@');
      if (at > 0) {
        out.putIfAbsent(ref.substring(0, at), s.name());
      }
    }
    return out;
  }

  /**
   * The manifest as the kit's update dialog diffs it: the version, each tool with {@code readOnly} or {@code writes}
   * as declared, the hosts and the commands it may reach.
   */
  static String manifestSummary(String version, List<Plugins.ToolLine> tools, List<String> hosts,
      List<String> commands) {
    StringBuilder b = new StringBuilder("version: ").append(version).append('\n').append("tools:\n");
    tools.forEach(t -> b.append("  ").append(t.name()).append(t.readOnly() ? " readOnly" : " writes").append('\n'));
    b.append("hosts:\n");
    hosts.forEach(h -> b.append("  ").append(h).append('\n'));
    if (commands.isEmpty()) {
      b.append("commands: none\n");
    } else {
      b.append("commands:\n");
      commands.forEach(c -> b.append("  ").append(c).append('\n'));
    }
    return b.toString();
  }

  /** "1 tool added · annotations, hosts and commands unchanged" (kit {@code dlg-update-jira}). */
  static String summary(List<String> added, List<String> removed, boolean annotations, boolean hosts,
      boolean commands) {
    List<String> parts = new ArrayList<>();
    if (!added.isEmpty()) {
      parts.add(added.size() + (added.size() == 1 ? " tool added" : " tools added"));
    }
    if (!removed.isEmpty()) {
      parts.add(removed.size() + (removed.size() == 1 ? " tool removed" : " tools removed"));
    }
    List<String> changed = new ArrayList<>();
    List<String> same = new ArrayList<>();
    (annotations ? changed : same).add("annotations");
    (hosts ? changed : same).add("hosts");
    (commands ? changed : same).add("commands");
    if (!changed.isEmpty()) {
      parts.add(join(changed) + " changed");
    }
    if (!same.isEmpty()) {
      parts.add(join(same) + " unchanged");
    }
    return String.join(" · ", parts);
  }

  private static String join(List<String> words) {
    return words.size() == 1 ? words.getFirst()
        : String.join(", ", words.subList(0, words.size() - 1)) + " and " + words.getLast();
  }

  /** A unified diff of two manifest summaries as hunks the kit's {@code gw-diff} draws. */
  static final class ManifestDiff {

    private ManifestDiff() {}

    static Diff unified(String before, String after) {
      List<String> a = before.lines().toList();
      List<String> b = after.lines().toList();
      com.github.difflib.patch.Patch<String> patch = com.github.difflib.DiffUtils.diff(a, b);
      if (patch.getDeltas().isEmpty()) {
        return new Diff(0, 0, List.of());
      }
      // The whole manifest as context, as the kit draws it: one hunk over every line.
      List<String> unified = com.github.difflib.UnifiedDiffUtils.generateUnifiedDiff("before", "after", a, patch,
          Math.max(a.size(), b.size()));
      List<Hunk> hunks = new ArrayList<>();
      List<DiffLine> lines = null;
      String header = null;
      int added = 0;
      int removed = 0;
      for (String line : unified) {
        if (line.startsWith("---") || line.startsWith("+++")) {
          continue;
        }
        if (line.startsWith("@@")) {
          if (header != null) {
            hunks.add(new Hunk(header, List.copyOf(lines)));
          }
          header = line;
          lines = new ArrayList<>();
        } else if (lines != null) {
          char kind = line.isEmpty() ? ' ' : line.charAt(0);
          String text = line.isEmpty() ? "" : line.substring(1);
          switch (kind) {
            case '+' -> {
              lines.add(new DiffLine("add", text));
              added++;
            }
            case '-' -> {
              lines.add(new DiffLine("del", text));
              removed++;
            }
            default -> lines.add(new DiffLine("context", text));
          }
        }
      }
      if (header != null) {
        hunks.add(new Hunk(header, List.copyOf(lines)));
      }
      return new Diff(added, removed, List.copyOf(hunks));
    }
  }
}
