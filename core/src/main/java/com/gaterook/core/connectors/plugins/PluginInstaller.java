package com.gaterook.core.connectors.plugins;

import com.gaterook.connector.api.ApiVersion;
import com.gaterook.connector.api.ToolDefinition;
import com.gaterook.core.connectors.ConnectorRegistry;
import com.gaterook.core.connectors.internal.persistence.PluginSource;
import com.gaterook.core.platform.CoreBuild;
import com.gaterook.core.platform.GatewayPaths;
import com.gaterook.core.platform.ProblemException;
import com.gaterook.plugin.protocol.PluginManifest;
import com.gaterook.plugin.protocol.Semver;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * Staging and checking a plugin before anything of it runs (ARCHITECTURE.md §7.5, report 11 §4.2, 16 §6.1): the JAR is
 * written to {@code plugins/.staging/}, its ZIP is checked (size, entry count, no {@code ..}, no nested jars, the
 * manifest present), its {@code mcp-gateway-plugin.json} parsed, its SHA-256 taken and matched against the signed
 * catalog (signed vs unsigned, revoked), its {@code requires.gateway}/{@code connectorApi}/{@code javaModules}
 * checked, and the forbidden packages ({@code com/gaterook/**}, {@code tools/jackson/**}, {@code org/pf4j/**}, …)
 * refused. No class is loaded here. Install then moves it to {@code plugins/<id>/<version>/} and loads it once in a
 * fresh host without any connection or secret.
 *
 * <p>A file a person chooses may be a plugin or a connector module (docs/connector-delivery.md): its manifest says
 * which, and each has its own limits. A module's package is staged here too; whether GateRook's signed list pins it,
 * and whether its connector types can be added on this computer, is the module service's to say
 * ({@code ModuleService.reviewFile}).
 */
public final class PluginInstaller {

  static final long MAX_JAR_BYTES = 50L * 1024 * 1024;
  static final long MAX_UNCOMPRESSED = 200L * 1024 * 1024;
  static final int MAX_ENTRIES = 10_000;

  /** A connector module's limits: a vendor's driver stack is larger than a plugin may be. */
  static final long MAX_MODULE_BYTES = 150L * 1024 * 1024;
  static final long MAX_MODULE_UNCOMPRESSED = 600L * 1024 * 1024;
  static final int MAX_MODULE_ENTRIES = 60_000;

  /** Packages a plugin may not ship: the host serves them parent-first, so a copy only shadows nothing or the SPI. */
  static final List<String> FORBIDDEN_PREFIXES = List.of("com/gaterook/", "tools/jackson/",
      "com/fasterxml/jackson/", "org/pf4j/", "org/slf4j/");

  private static final Logger log = LoggerFactory.getLogger(PluginInstaller.class);

  /** A plugin checked and waiting to be installed. */
  public record Staged(String stagingId, Path jar, String sha256, long sizeBytes, PluginManifest manifest,
      boolean signed, PluginSource source, String catalogEntry, String fileName, Report report) {}

  /**
   * The report shown before Install (admin maps it to its DTO). {@code sandbox}: the OS sandbox the plugin will run in
   * here ({@code landlock}, {@code sandbox-exec}), {@code null} where there is none — the review then says "Not
   * sandboxed on this system" (ARCHITECTURE.md §7.5).
   */
  public record Report(boolean signed, String sha256, long size, String id, String version, String name,
      String fileName, JsonNode requires, List<Tool> tools, JsonNode access, JsonNode configSchema, String sandbox,
      List<TypeReport> types) {

    /** A plugin's report: one connector type, its own. */
    public Report(boolean signed, String sha256, long size, String id, String version, String name, String fileName,
        JsonNode requires, List<Tool> tools, JsonNode access, JsonNode configSchema, String sandbox) {
      this(signed, sha256, size, id, version, name, fileName, requires, tools, access, configSchema, sandbox, null);
    }

    /** Whether the package is a connector module: {@code types} are the connector types it adds. */
    public boolean module() {
      return types != null;
    }

    Report signed(boolean value) {
      return new Report(value, sha256, size, id, version, name, fileName, requires, tools, access, configSchema,
          sandbox, types);
    }
  }

  public record Tool(String name, JsonNode declared) {}

  /** One connector type a module's package adds, as the review of a module from a file shows it. */
  public record TypeReport(String id, String name, List<Tool> tools, JsonNode configSchema) {}

  private final PluginPaths paths;
  private final PluginCatalog catalog;
  private final HostRuntimeLocator runtime;
  private final HostLauncher launcher;
  private final ConnectorRegistry registry;
  private final Clock clock;
  private final Map<String, Staged> staged = new ConcurrentHashMap<>();

  public PluginInstaller(PluginPaths paths, PluginCatalog catalog, HostRuntimeLocator runtime, HostLauncher launcher,
      ConnectorRegistry registry, Clock clock) {
    this.paths = paths;
    this.catalog = catalog;
    this.runtime = runtime;
    this.launcher = launcher;
    this.registry = registry;
    this.clock = clock;
  }

  /**
   * Stages and checks a JAR's bytes; {@code source} says where it came from, {@code catalogEntry} is {@code id@ver}.
   * From the plugin catalog it must be a plugin. A file may be a connector module as well: it is staged as one,
   * unsigned until the module service finds that GateRook's signed list pins it ({@link #pinned}).
   */
  public Staged check(byte[] bytes, PluginSource source, String catalogEntry, String fileName) {
    return check(bytes, source, catalogEntry, fileName, source == PluginSource.FILE ? null : Boolean.FALSE);
  }

  /**
   * Stages and checks the package of a connector module that the module channel's signed index pins ({@code bytes}
   * were verified against it).
   */
  public Staged checkModule(byte[] bytes, String channelEntry, String fileName) {
    return check(bytes, PluginSource.CATALOG, channelEntry, fileName, Boolean.TRUE);
  }

  /** @param module whether the package must be a module ({@code true}), a plugin ({@code false}) or may be either */
  private Staged check(byte[] bytes, PluginSource source, String catalogEntry, String fileName, Boolean module) {
    if (bytes.length > (Boolean.FALSE.equals(module) ? MAX_JAR_BYTES : MAX_MODULE_BYTES)) {
      throw ProblemException.unprocessable("Plugin too large", Boolean.FALSE.equals(module)
          ? "A plugin JAR is at most 50 MB." : "A connector module is at most 150 MB.", "/file");
    }
    String stagingId = com.gaterook.core.platform.Ids.next("stg_");
    Path jar = paths.staging().resolve(stagingId + ".jar");
    try {
      Files.write(jar, bytes);
      GatewayPaths.ownerOnlyFile(jar);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    try {
      return stage(stagingId, jar, source, catalogEntry, fileName, module);
    } catch (RuntimeException e) {
      deleteQuietly(jar);
      throw e;
    }
  }

  private Staged stage(String stagingId, Path jar, PluginSource source, String catalogEntry, String fileName,
      Boolean expected) {
    String sha256 = sha256(jar);
    // The manifest first: it says whether this is a plugin or a module, and each has its own limits.
    PluginManifest manifest = parse(jar);
    boolean module = manifest.module();
    if (expected != null && module != expected) {
      throw expected ? ProblemException.unprocessable("Not a connector module", "The package isn't a connector "
          + "module.", "/file") : ProblemException.unprocessable("Not a plugin", manifest.name() + " is a "
          + "connector module, not a plugin: the plugin catalog doesn't deliver those.", "/file");
    }
    if (!module && fileSize(jar) > MAX_JAR_BYTES) {
      throw ProblemException.unprocessable("Plugin too large", "A plugin JAR is at most 50 MB.", "/file");
    }
    checkZip(jar, module);
    checkRequires(manifest);
    if (module) {
      // From the channel, its signed index pins these bytes. A file is unsigned until the module service finds that
      // the list pins it. The registry decides about names when the types register.
      boolean pinned = source != PluginSource.FILE;
      List<Tool> all = new ArrayList<>();
      List<TypeReport> types = new ArrayList<>();
      for (com.gaterook.connector.api.ConnectorManifest type : manifest.connectorManifests()) {
        List<Tool> tools = new ArrayList<>();
        for (ToolDefinition t : type.tools()) {
          tools.add(new Tool(t.name(), com.gaterook.core.connectors.ManifestCodec.writeTool(t)
              .path("annotations")));
        }
        all.addAll(tools);
        types.add(new TypeReport(type.id(), type.name(), List.copyOf(tools), type.configSchema()));
      }
      Report report = new Report(pinned, sha256, fileSize(jar), manifest.id(), manifest.version(), manifest.name(),
          fileName, manifest.raw().path("requires"), List.copyOf(all), manifest.raw().path("access"), null,
          launcher.sandboxKind(), List.copyOf(types));
      Staged s = new Staged(stagingId, jar, sha256, fileSize(jar), manifest, pinned, source, catalogEntry, fileName,
          report);
      staged.put(stagingId, s);
      return s;
    }
    if (registry.manifest(manifest.id()).filter(m -> m.kind() != com.gaterook.connector.api.ConnectorManifest.Kind
        .PLUGIN).isPresent()) {
      throw ProblemException.conflict("Name taken", manifest.id() + " is already a built-in connector type.");
    }
    for (ToolDefinition t : manifest.tools()) {
      registry.reservedProblem(t.name()).ifPresent(problem -> {
        throw ProblemException.unprocessable("Reserved tool name", "This plugin's " + problem + ".", "/tools");
      });
    }
    // Signed: the current (unexpired) catalog lists this very JAR and vouches for it. An entry with signed:false only
    // pins the file (community code nobody reviewed): it installs through the unsigned dialog.
    boolean signed = catalog.installProblem().isEmpty() && catalog.entry("plugin", manifest.id(), manifest.version())
        .map(e -> sha256.equalsIgnoreCase(e.path("artifact").path("sha256").asString())
            && e.path("signed").asBoolean(true)).orElse(false)
        && !catalog.isRevoked(sha256);
    if (catalog.isRevoked(sha256)) {
      throw ProblemException.conflict("Revoked", "This plugin build was revoked in the catalog and can't be "
          + "installed.");
    }
    List<Tool> tools = new ArrayList<>();
    for (ToolDefinition t : manifest.tools()) {
      tools.add(new Tool(t.name(), com.gaterook.core.connectors.ManifestCodec.writeTool(t).path("annotations")));
    }
    Report report = new Report(signed, sha256, fileSize(jar), manifest.id(), manifest.version(), manifest.name(),
        fileName, manifest.requires() == null ? null : manifest.raw().path("requires"), tools,
        manifest.raw().path("access"), manifest.configSchema(), launcher.sandboxKind());
    Staged s = new Staged(stagingId, jar, sha256, fileSize(jar), manifest, signed, source, catalogEntry, fileName,
        report);
    staged.put(stagingId, s);
    return s;
  }

  /**
   * A module's package that came as a file turned out to be one GateRook's signed list pins ({@code channelEntry},
   * {@code id@version}): it is GateRook's own module, wherever its bytes came from.
   */
  Staged pinned(String stagingId, String channelEntry) {
    Staged s = staged.computeIfPresent(stagingId, (id, old) -> new Staged(id, old.jar(), old.sha256(),
        old.sizeBytes(), old.manifest(), true, PluginSource.CATALOG, channelEntry, old.fileName(),
        old.report().signed(true)));
    if (s == null) {
      throw ProblemException.notFound("Not staged", "Check the package again before installing it.");
    }
    return s;
  }

  /** A staged plugin by its id, or empty when it expired. */
  public Optional<Staged> staged(String stagingId) {
    return Optional.ofNullable(staged.get(stagingId));
  }

  /**
   * Installs a staged plugin: it moves to {@code plugins/<id>/<version>/plugin.jar}, and a fresh host loads it once
   * without a connection or a secret. The caller writes the database row and wires the supervisor.
   *
   * @throws ProblemException unsigned without {@code acknowledgeUnsigned}, or the probe load failed
   */
  public Staged install(String stagingId, boolean acknowledgeUnsigned) {
    Staged s = staged.get(stagingId);
    if (s == null) {
      throw ProblemException.notFound("Not staged", "Check the plugin again before installing it.");
    }
    if (!Files.isRegularFile(s.jar())) {
      // The staged file is gone (removed from the disk meanwhile): what was reviewed can't be installed.
      staged.remove(stagingId);
      throw ProblemException.notFound("Not staged", "The checked package is no longer on this computer. Check it "
          + "again before installing it.");
    }
    if (!s.signed() && !acknowledgeUnsigned) {
      throw s.manifest().module() ? ProblemException.unprocessable("Unsigned connector module", "This connector "
          + "module isn't one GateRook signed. Confirm that you trust it before installing it.",
          "/acknowledgeUnsigned") : ProblemException.unprocessable("Unsigned plugin", "This plugin isn't signed by "
          + "the catalog. Confirm that you trust it before installing it.", "/acknowledgeUnsigned");
    }
    // A first download may have supplied Java modules since the preview was checked.
    checkRequires(s.manifest());
    Path versionDir = paths.versionDir(s.manifest().id(), s.manifest().version());
    Path jar = paths.jar(s.manifest().id(), s.manifest().version());
    try {
      GatewayPaths.ownerOnlyDirectory(versionDir);
      Files.copy(s.jar(), jar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      GatewayPaths.ownerOnlyFile(jar);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    probeLoad(s, jar);
    deleteQuietly(s.jar());
    staged.remove(stagingId);
    return new Staged(stagingId, jar, s.sha256(), s.sizeBytes(), s.manifest(), s.signed(), s.source(),
        s.catalogEntry(), s.fileName(), s.report());
  }

  /**
   * A fresh host loads the plugin with no connection and no secret; its tools must match the manifest (the launcher
   * refuses a generation whose code reports others).
   */
  void probeLoad(Staged s, Path jar) {
    if (!runtime.available()) {
      // Without a runtime the probe can't run; the install goes ahead and the first call will report it.
      log.info("No plugin runtime available: skipping the probe load of {}", s.manifest().id());
      return;
    }
    HostLauncher.Spec spec = HostLauncher.Spec.of(s.manifest(), jar, s.sha256());
    try {
      HostProcess host = launcher.start(spec, "info");
      host.shutdown(Duration.ofSeconds(2));
      host.log().close();
    } catch (HostLauncher.HostStartException e) {
      deleteQuietly(paths.versionDir(s.manifest().id(), s.manifest().version()));
      throw ProblemException.unprocessable(s.manifest().module() ? "Connector didn't load" : "Plugin didn't load",
          s.manifest().name() + " couldn't load (" + e.phase() + "): " + e.getMessage(), "/file");
    }
  }

  /** Drops a staged plugin that won't be installed. */
  public void discard(String stagingId) {
    Staged s = staged.remove(stagingId);
    if (s != null) {
      deleteQuietly(s.jar());
    }
  }

  // ── Checks ──────────────────────────────────────────────────────────────────────────────────────────────────

  private void checkZip(Path jar, boolean module) {
    int maxEntries = module ? MAX_MODULE_ENTRIES : MAX_ENTRIES;
    long maxUncompressed = module ? MAX_MODULE_UNCOMPRESSED : MAX_UNCOMPRESSED;
    // A module is GateRook's own connector code: of com/gaterook/ only what the host provides is closed to it.
    List<String> forbidden = module ? PluginManifest.MODULE_SHARED_PACKAGES.stream().map(p -> p.replace('.', '/'))
        .toList() : FORBIDDEN_PREFIXES;
    try (ZipFile zip = new ZipFile(jar.toFile())) {
      int entries = 0;
      long uncompressed = 0;
      boolean manifest = false;
      var it = zip.entries();
      while (it.hasMoreElements()) {
        ZipEntry entry = it.nextElement();
        if (++entries > maxEntries) {
          throw ProblemException.unprocessable("Plugin has too many files", "A plugin has at most " + maxEntries
              + " entries.", "/file");
        }
        String name = entry.getName();
        if (name.contains("..") || name.startsWith("/") || name.contains("\\")) {
          throw ProblemException.unprocessable("Unsafe plugin", "A plugin entry name is unsafe: " + name, "/file");
        }
        if (!entry.isDirectory() && name.matches("(?i).*/lib/[^/]+\\.jar") || name.matches("(?i)lib/[^/]+\\.jar")) {
          throw ProblemException.unprocessable("Nested jars", "A plugin is one JAR; it can't bundle other jars ("
              + name + ").", "/file");
        }
        if (PluginManifest.FILE.equals(name)) {
          manifest = true;
        }
        if (entry.getName().endsWith(".class")) {
          String path = name;
          for (String prefix : forbidden) {
            if (path.startsWith(prefix)) {
              throw ProblemException.unprocessable("Forbidden package", "A plugin can't ship " + prefix
                  + "**: the host provides it.", "/file");
            }
          }
        }
        long size = entry.getSize();
        uncompressed += size < 0 ? 0 : size;
        if (uncompressed > maxUncompressed) {
          throw ProblemException.unprocessable("Plugin too large", "A plugin's contents are at most "
              + maxUncompressed / (1024 * 1024) + " MB.", "/file");
        }
      }
      if (!manifest) {
        throw ProblemException.unprocessable("Not a plugin", "The JAR has no " + PluginManifest.FILE + ".", "/file");
      }
    } catch (IOException e) {
      throw ProblemException.unprocessable("Not a JAR", "The file isn't a readable JAR: " + e.getMessage(), "/file");
    }
  }

  private PluginManifest parse(Path jar) {
    try (ZipFile zip = new ZipFile(jar.toFile())) {
      ZipEntry entry = zip.getEntry(PluginManifest.FILE);
      if (entry == null) {
        throw ProblemException.unprocessable("Not a plugin", "The JAR has no " + PluginManifest.FILE + ".", "/file");
      }
      try (InputStream in = zip.getInputStream(entry)) {
        // A plugin's manifest is at most 1 MB, a module's 4 MB: the reader applies the limit of what it finds.
        return PluginManifest.read(in.readNBytes(PluginManifest.MAX_MODULE_BYTES + 1));
      }
    } catch (PluginManifest.InvalidManifest e) {
      throw ProblemException.unprocessable("Invalid manifest", e.getMessage(), "/file");
    } catch (IOException e) {
      throw ProblemException.unprocessable("Can't read the manifest", e.getMessage(), "/file");
    }
  }

  private void checkRequires(PluginManifest manifest) {
    PluginManifest.Requires requires = manifest.requires();
    Semver gateway = version(CoreBuild.VERSION);
    if (requires.gateway() != null && !Semver.satisfies(gateway.release(), requires.gateway())) {
      throw ProblemException.unprocessable("Needs another gateway", manifest.name() + " needs GateRook "
          + requires.gateway() + "; this is " + CoreBuild.VERSION + ".", "/requires");
    }
    if (requires.connectorApi() != null && !Semver.satisfies(version(ApiVersion.CURRENT), requires.connectorApi())) {
      throw ProblemException.unprocessable("Needs another connector API", manifest.name() + " needs connector-api "
          + requires.connectorApi() + "; this is " + ApiVersion.CURRENT + ".", "/requires");
    }
    Set<String> modules = runtime.jreModules();
    if (!modules.isEmpty()) {
      for (String module : requires.javaModules()) {
        if (!modules.contains(module)) {
          throw ProblemException.unprocessable("Needs a Java module", manifest.name() + " needs the Java module "
              + module + ", which isn't in the bundled runtime.", "/requires");
        }
      }
    }
  }

  private static Semver version(String text) {
    try {
      return Semver.parse(text);
    } catch (RuntimeException e) {
      return new Semver(0, 0, 0, null);
    }
  }

  static String sha256(Path file) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
        in.transferTo(java.io.OutputStream.nullOutputStream());
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (IOException | NoSuchAlgorithmException e) {
      throw new UncheckedIOException(new IOException("Can't hash " + file, e));
    }
  }

  private static long fileSize(Path file) {
    try {
      return Files.size(file);
    } catch (IOException e) {
      return 0;
    }
  }

  private static void deleteQuietly(Path path) {
    try {
      if (Files.isDirectory(path)) {
        try (var walk = Files.walk(path)) {
          walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
            try {
              Files.deleteIfExists(p);
            } catch (IOException ignored) {
              // Best effort.
            }
          });
        }
      } else {
        Files.deleteIfExists(path);
      }
    } catch (IOException ignored) {
      // Best effort.
    }
  }
}
