package ai.msrv.gaterook.core.connectors.plugins;

import ai.msrv.gaterook.connector.api.ApiVersion;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.core.connectors.ConnectorRegistry;
import ai.msrv.gaterook.core.connectors.internal.persistence.PluginSource;
import ai.msrv.gaterook.core.platform.CoreBuild;
import ai.msrv.gaterook.core.platform.GatewayPaths;
import ai.msrv.gaterook.core.platform.ProblemException;
import ai.msrv.gaterook.plugin.protocol.PluginManifest;
import ai.msrv.gaterook.plugin.protocol.Semver;
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
 * checked, and the forbidden packages ({@code ai/msrv/gaterook/**}, {@code tools/jackson/**}, {@code org/pf4j/**}, …)
 * refused. No class is loaded here. Install then moves it to {@code plugins/<id>/<version>/} and loads it once in a
 * fresh host without any connection or secret.
 */
public final class PluginInstaller {

  static final long MAX_JAR_BYTES = 50L * 1024 * 1024;
  static final long MAX_UNCOMPRESSED = 200L * 1024 * 1024;
  static final int MAX_ENTRIES = 10_000;

  /** Packages a plugin may not ship: the host serves them parent-first, so a copy only shadows nothing or the SPI. */
  static final List<String> FORBIDDEN_PREFIXES = List.of("ai/msrv/gaterook/", "tools/jackson/",
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
      String fileName, JsonNode requires, List<Tool> tools, JsonNode access, JsonNode configSchema, String sandbox) {}

  public record Tool(String name, JsonNode declared) {}

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

  /** Stages and checks a JAR's bytes; {@code source} says where it came from, {@code catalogEntry} is {@code id@ver}. */
  public Staged check(byte[] bytes, PluginSource source, String catalogEntry, String fileName) {
    if (bytes.length > MAX_JAR_BYTES) {
      throw ProblemException.unprocessable("Plugin too large", "A plugin JAR is at most 50 MB.", "/file");
    }
    String stagingId = ai.msrv.gaterook.core.platform.Ids.next("stg_");
    Path jar = paths.staging().resolve(stagingId + ".jar");
    try {
      Files.write(jar, bytes);
      GatewayPaths.ownerOnlyFile(jar);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    try {
      return stage(stagingId, jar, source, catalogEntry, fileName);
    } catch (RuntimeException e) {
      deleteQuietly(jar);
      throw e;
    }
  }

  private Staged stage(String stagingId, Path jar, PluginSource source, String catalogEntry, String fileName) {
    String sha256 = sha256(jar);
    checkZip(jar);
    PluginManifest manifest = parse(jar);
    checkRequires(manifest);
    if (registry.manifest(manifest.id()).filter(m -> m.kind() != ai.msrv.gaterook.connector.api.ConnectorManifest.Kind
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
      tools.add(new Tool(t.name(), ai.msrv.gaterook.core.connectors.ManifestCodec.writeTool(t).path("annotations")));
    }
    Report report = new Report(signed, sha256, fileSize(jar), manifest.id(), manifest.version(), manifest.name(),
        fileName, manifest.requires() == null ? null : manifest.raw().path("requires"), tools,
        manifest.raw().path("access"), manifest.configSchema(), launcher.sandboxKind());
    Staged s = new Staged(stagingId, jar, sha256, fileSize(jar), manifest, signed, source, catalogEntry, fileName,
        report);
    staged.put(stagingId, s);
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
    if (!s.signed() && !acknowledgeUnsigned) {
      throw ProblemException.unprocessable("Unsigned plugin", "This plugin isn't signed by the catalog. Confirm that "
          + "you trust it before installing it.", "/acknowledgeUnsigned");
    }
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
      throw ProblemException.unprocessable("Plugin didn't load", s.manifest().name() + " couldn't load ("
          + e.phase() + "): " + e.getMessage(), "/file");
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

  private void checkZip(Path jar) {
    try (ZipFile zip = new ZipFile(jar.toFile())) {
      int entries = 0;
      long uncompressed = 0;
      boolean manifest = false;
      var it = zip.entries();
      while (it.hasMoreElements()) {
        ZipEntry entry = it.nextElement();
        if (++entries > MAX_ENTRIES) {
          throw ProblemException.unprocessable("Plugin has too many files", "A plugin has at most " + MAX_ENTRIES
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
          for (String prefix : FORBIDDEN_PREFIXES) {
            if (path.startsWith(prefix)) {
              throw ProblemException.unprocessable("Forbidden package", "A plugin can't ship " + prefix
                  + "**: the host provides it.", "/file");
            }
          }
        }
        long size = entry.getSize();
        uncompressed += size < 0 ? 0 : size;
        if (uncompressed > MAX_UNCOMPRESSED) {
          throw ProblemException.unprocessable("Plugin too large", "A plugin's contents are at most 200 MB.",
              "/file");
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
        byte[] bytes = in.readNBytes(PluginManifest.MAX_BYTES + 1);
        if (bytes.length > PluginManifest.MAX_BYTES) {
          throw ProblemException.unprocessable("Manifest too large", PluginManifest.FILE + " is larger than 1 MB.",
              "/file");
        }
        return PluginManifest.parse(ai.msrv.gaterook.plugin.protocol.GppJson.MAPPER.readTree(bytes));
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
      throw ProblemException.unprocessable("Needs another gateway", manifest.name() + " needs MCP Gateway "
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
