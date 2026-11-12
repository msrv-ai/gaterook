package ai.msrv.gaterook.e2e.modules;

import ai.msrv.gaterook.e2e.platform.CoreProcess;
import ai.msrv.gaterook.plugin.protocol.PluginManifest;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * A connector module's package behind an assembled core, as a release has it: listed by a signed module channel
 * ({@link ModuleChannelServer}), installed by the core when one of its connector types is needed, and run in a
 * module host. The tests of the real modules drive their connector through it against the Compose test services.
 *
 * <p>With {@code -Dgateway.e2e.officialLists=true} the core reads GateRook's published list on GitHub instead of
 * this channel ({@code gateway.lists=official}) and installs the released package: a check of what is published,
 * which needs the network and is never part of a build.
 */
final class BuiltModule implements AutoCloseable {

  /** Read GateRook's published list on GitHub instead of the test's own channel. */
  static final boolean OFFICIAL = Boolean.getBoolean("gateway.e2e.officialLists");

  final PluginManifest manifest;
  final byte[] jar;
  final ModuleChannelServer channel;
  final CoreProcess core;
  final CoreClient api;

  private BuiltModule(PluginManifest manifest, byte[] jar, ModuleChannelServer channel, CoreProcess core) {
    this.manifest = manifest;
    this.jar = jar;
    this.channel = channel;
    this.core = core;
    this.api = new CoreClient(core);
  }

  /** {@code modules/<id>/target/<id>-<version>.jar}, or {@code null} when it isn't built. */
  static Path packageOf(String id) throws Exception {
    Path target = Path.of(System.getProperty("gateway.modulesDir", "../modules"), id, "target");
    if (!Files.isDirectory(target)) {
      return null;
    }
    try (var files = Files.list(target)) {
      return files.filter(f -> f.getFileName().toString().matches(id + "-\\d+\\.\\d+\\.\\d+\\.jar")).findFirst()
          .orElse(null);
    }
  }

  /** The manifest inside a module's package. */
  static PluginManifest manifestOf(byte[] jar) throws Exception {
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jar))) {
      for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
        if (entry.getName().equals(PluginManifest.FILE)) {
          return PluginManifest.read(zip.readAllBytes());
        }
      }
    }
    throw new AssertionError("No " + PluginManifest.FILE + " in the package");
  }

  /** Starts a channel that lists the package, and a core in {@code home} that reads it. */
  static BuiltModule start(Path home, Path built) throws Exception {
    byte[] jar = Files.readAllBytes(built);
    PluginManifest manifest = manifestOf(jar);
    ModuleChannelServer channel = ModuleChannelServer.serving(manifest, jar, built.getFileName().toString());
    CoreProcess core = CoreProcess.start(home, CoreProcess.freePort(), flags(home, channel)).bootstrap();
    core.untilReady(Duration.ofSeconds(90));
    return new BuiltModule(manifest, jar, channel, core);
  }

  /**
   * The flags of a core that installs modules from {@code channel}: the module host's jar, the channel (or the
   * published list), and on a native core a Java runtime to run the host on.
   */
  static List<String> flags(Path home, ModuleChannelServer channel) throws Exception {
    Path cache = Files.createDirectories(home.resolve("cache"));
    Path runtimeJar = cache.resolve("plugin-host.jar");
    if (!Files.exists(runtimeJar)) {
      Files.copy(Path.of(System.getProperty("gateway.pluginHostJar")), runtimeJar);
    }
    List<String> flags = new ArrayList<>(List.of("-Dgateway.pluginHostJar=" + runtimeJar.toAbsolutePath(),
        "-Dgateway.plugins.aot-cache=false"));
    if (OFFICIAL) {
      flags.add("-Dgateway.lists=official");
    } else {
      flags.addAll(channel.flags(home));
    }
    if (CoreProcess.nativeBinary()) {
      Path resources = cache.resolve("resources");
      if (!Files.exists(resources.resolve("jre"))) {
        Files.createDirectories(resources);
        Files.createSymbolicLink(resources.resolve("jre"), Path.of(System.getProperty("java.home")));
      }
      flags.add("-Dgateway.resources=" + resources);
    }
    return flags;
  }

  @Override
  public void close() {
    core.close();
    channel.close();
  }
}
