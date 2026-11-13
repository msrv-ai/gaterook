package com.gaterook.pluginhost;

import com.gaterook.connector.api.Connector;
import com.gaterook.connector.api.ConnectorModule;
import com.gaterook.connector.api.ModuleContext;
import com.gaterook.connector.api.ToolDefinition;
import com.gaterook.plugin.protocol.Gpp;
import com.gaterook.plugin.protocol.GppJson;
import com.gaterook.plugin.protocol.Messages;
import com.gaterook.plugin.protocol.PluginManifest;
import com.gaterook.plugin.protocol.RpcException;
import com.gaterook.plugin.protocol.host.ConnectorEndpoint;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.pf4j.PluginState;
import org.pf4j.PluginWrapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The plugin generations this host process has loaded (report 16 §9): normally exactly one. {@code plugin/load}
 * checks the JAR's SHA-256 again (the file may have changed on disk since the core checked it), reads the manifest,
 * then lets PF4J load it and instantiates {@code connectorClass}. Failures name their phase: {@code verify},
 * {@code sandbox}, {@code pf4j}, {@code instantiate}, {@code tools}.
 *
 * <p>The OS sandbox is in place before this process runs a line of the plugin, and before it runs at all: on Linux
 * the core starts it through {@link SandboxExec} (Landlock confines threads, so a restriction made here would miss the
 * threads already running), on macOS through {@code sandbox-exec}. The load only reports which one it runs in. Windows
 * is the exception: an integrity level belongs to the process token, which every thread shares, so the host drops to
 * low integrity itself here ({@link WindowsIntegrity}), before PF4J reads the plugin.
 */
final class PluginRegistry implements ConnectorEndpoint.Generations {

  /**
   * One loaded generation: a plugin's one connector, or a connector module's connectors by connector type with the
   * module object that made them.
   */
  record LoadedPlugin(String generation, GatewayPluginManager manager, String pluginId,
      Map<String, Connector> connectors, ConnectorModule module, Path dataDir) {

    ConnectorEndpoint.Loaded endpoint() {
      return new ConnectorEndpoint.Loaded(generation, connectors, dataDir, module != null);
    }
  }

  private final Map<String, LoadedPlugin> byGeneration = new ConcurrentHashMap<>();
  private final ClassLoader apiOnly = new ApiOnlyClassLoader(PluginRegistry.class.getClassLoader());
  /**
   * The sandbox this process was started in ({@code landlock}, {@code sandbox-exec}, or {@code low-integrity}, which
   * the host applies itself at load), or {@code null}; set by {@link SandboxExec} or the core.
   */
  private final String sandboxKind;

  PluginRegistry() {
    this(System.getenv(SandboxExec.SANDBOX_ENV));
  }

  PluginRegistry(String sandboxKind) {
    this.sandboxKind = sandboxKind == null || sandboxKind.isBlank() ? null : sandboxKind;
  }

  synchronized Messages.Loaded load(Messages.Load load) throws RpcException {
    long started = System.nanoTime();
    if (byGeneration.containsKey(load.generation())) {
      throw failed("pf4j", load.generation() + " is loaded already");
    }
    Path jar = Path.of(load.jar()).toAbsolutePath().normalize();
    String sha256;
    try {
      sha256 = sha256(jar);
    } catch (IOException e) {
      throw failed("verify", "Can't read the plugin file: " + e.getMessage());
    }
    if (!sha256.equalsIgnoreCase(load.sha256())) {
      throw failed("verify", "Plugin file changed on disk");
    }
    PluginManifest manifest;
    try {
      manifest = PluginManifest.read(readManifestBytes(jar));
    } catch (IOException | RuntimeException e) {
      throw failed("verify", "Can't read " + PluginManifest.FILE + ": " + e.getMessage());
    } catch (PluginManifest.InvalidManifest e) {
      throw failed("verify", e.getMessage());
    }
    if (!load.generation().equals(manifest.id() + "@" + manifest.version())) {
      throw failed("verify", "The file is " + manifest.id() + "@" + manifest.version() + ", not " + load.generation());
    }
    Path dataDir = Path.of(load.dataDir()).toAbsolutePath().normalize();
    try {
      Files.createDirectories(dataDir.resolve("tmp"));
    } catch (IOException e) {
      throw failed("sandbox", "Can't create the plugin's data folder: " + e.getMessage());
    }
    // A core that asks for a sandbox this process wasn't started in gets a refusal, not a plugin running without it.
    if (load.sandbox().enabled() && sandboxKind == null) {
      throw failed("sandbox", "This plugin host wasn't started in an OS sandbox.");
    }
    // On Windows the host drops to low integrity itself, now that it knows the plugin's paths and before it runs a
    // line of the plugin.
    if (WindowsIntegrity.KIND.equals(sandboxKind)) {
      List<Path> readable = new java.util.ArrayList<>(List.of(jar.getParent()));
      load.sandbox().readOnly().forEach(p -> readable.add(Path.of(p)));
      List<Path> writable = new java.util.ArrayList<>(List.of(dataDir));
      load.sandbox().readWrite().forEach(p -> writable.add(Path.of(p)));
      String why = WindowsIntegrity.drop(readable, writable);
      if (why != null) {
        throw failed("sandbox", "Couldn't run the plugin at low integrity: " + why);
      }
    }
    HostVoice.module(manifest.module());
    // A plugin that declares TCP destinations gets the TCP gate before a line of it runs: from now on every
    // java.net.Socket of the process connects through it.
    if (!manifest.access().tcp().isEmpty()) {
      try {
        TcpGate.install();
      } catch (IOException | RuntimeException e) {
        throw failed("sandbox", "Can't start the TCP gate: " + e.getMessage());
      }
    }
    GatewayPluginManager manager = new GatewayPluginManager(jar.getParent(), apiOnly);
    String pluginId;
    try {
      pluginId = manager.loadPlugin(jar);
      PluginState state = manager.startPlugin(pluginId);
      if (state != PluginState.STARTED) {
        PluginWrapper w = manager.getPlugin(pluginId);
        Throwable cause = w == null ? null : w.getFailedException();
        throw failed("pf4j", "PF4J left the plugin " + state + (cause == null ? "" : ": " + cause));
      }
    } catch (RpcException e) {
      throw e;
    } catch (RuntimeException | LinkageError e) {
      manager.unloadPlugins();
      throw failed("pf4j", e.toString());
    }
    Map<String, Connector> connectors = new LinkedHashMap<>();
    ConnectorModule module = null;
    try {
      ClassLoader loader = manager.getPluginClassLoader(pluginId);
      if (manifest.module()) {
        Class<?> type = loader.loadClass(manifest.moduleClass());
        if (!ConnectorModule.class.isAssignableFrom(type)) {
          throw failed("instantiate", manifest.moduleClass() + " doesn't implement ConnectorModule");
        }
        module = (ConnectorModule) type.getDeclaredConstructor().newInstance();
        String moduleId = manifest.id();
        String moduleVersion = manifest.version();
        List<Connector> made = module.connectors(new ModuleContext() {
          @Override
          public String moduleId() {
            return moduleId;
          }

          @Override
          public String moduleVersion() {
            return moduleVersion;
          }
        });
        for (Connector c : made == null ? List.<Connector>of() : made) {
          if (connectors.putIfAbsent(c.manifest().id(), c) != null) {
            throw failed("instantiate", manifest.moduleClass() + " made two connectors of the type "
                + c.manifest().id());
          }
        }
        // The module's connectors are exactly the types its manifest declares: no more, no fewer.
        java.util.Set<String> declared = new java.util.TreeSet<>();
        manifest.types().forEach(t -> declared.add(t.id()));
        if (!declared.equals(new java.util.TreeSet<>(connectors.keySet()))) {
          throw failed("instantiate", "The module's manifest declares the types " + declared + ", its code makes "
              + new java.util.TreeSet<>(connectors.keySet()));
        }
      } else {
        Class<?> type = loader.loadClass(manifest.connectorClass());
        if (!Connector.class.isAssignableFrom(type)) {
          throw failed("instantiate", manifest.connectorClass() + " doesn't implement Connector");
        }
        connectors.put(manifest.id(), (Connector) type.getDeclaredConstructor().newInstance());
      }
    } catch (RpcException e) {
      closeAll(connectors.values(), module);
      manager.unloadPlugins();
      throw e;
    } catch (InvocationTargetException e) {
      closeAll(connectors.values(), module);
      manager.unloadPlugins();
      throw failed("instantiate", String.valueOf(e.getCause()));
    } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
      closeAll(connectors.values(), module);
      manager.unloadPlugins();
      throw failed("instantiate", e.toString());
    }
    List<Messages.TypeTools> types = new ArrayList<>();
    String connectorApi = null;
    try {
      // In the manifest's order, so the first type is the same on both sides.
      for (PluginManifest.Type declared : manifest.types()) {
        Connector c = manifest.module() ? connectors.get(declared.id()) : connectors.values().iterator().next();
        types.add(new Messages.TypeTools(declared.id(), c.manifest().tools()));
        if (connectorApi == null) {
          connectorApi = c.manifest().connectorApi();
        }
      }
    } catch (RuntimeException | LinkageError e) {
      closeAll(connectors.values(), module);
      manager.unloadPlugins();
      throw failed("tools", "The plugin's manifest() failed: " + e);
    }
    byGeneration.put(load.generation(), new LoadedPlugin(load.generation(), manager, pluginId, connectors, module,
        dataDir));
    return new Messages.Loaded(load.generation(), types.getFirst().type(), connectorApi,
        (System.nanoTime() - started) / 1_000_000, types.getFirst().tools(), sandboxKind, types);
  }

  synchronized void unload(String generation) {
    LoadedPlugin p = byGeneration.remove(generation);
    if (p != null) {
      closeAll(p.connectors().values(), p.module());
      p.manager().unloadPlugins();
    }
  }

  synchronized void unloadAll() {
    for (String g : List.copyOf(byGeneration.keySet())) {
      unload(g);
    }
  }

  @Override
  public Optional<ConnectorEndpoint.Loaded> find(String generation) {
    LoadedPlugin p = byGeneration.get(generation);
    return p == null ? Optional.empty() : Optional.of(p.endpoint());
  }

  @Override
  public Collection<ConnectorEndpoint.Loaded> all() {
    return byGeneration.values().stream().map(LoadedPlugin::endpoint).toList();
  }

  String sandboxKind() {
    return sandboxKind;
  }

  private static void close(Connector c) {
    try {
      c.close();
    } catch (RuntimeException e) {
      // The process ends soon anyway.
    }
  }

  /** Closes a generation's connectors, then the module that made them. */
  private static void closeAll(Collection<Connector> connectors, ConnectorModule module) {
    connectors.forEach(PluginRegistry::close);
    if (module != null) {
      try {
        module.close();
      } catch (Exception e) {
        // The process ends soon anyway.
      }
    }
  }

  /** The bytes of the JAR's manifest, at most one more than the largest manifest read ({@code PluginManifest.read}). */
  static byte[] readManifestBytes(Path jar) throws IOException {
    try (ZipFile zip = new ZipFile(jar.toFile())) {
      ZipEntry entry = zip.getEntry(PluginManifest.FILE);
      if (entry == null) {
        throw new IOException("no " + PluginManifest.FILE + " in the JAR");
      }
      try (InputStream in = zip.getInputStream(entry)) {
        return in.readNBytes(PluginManifest.MAX_MODULE_BYTES + 1);
      }
    }
  }

  static JsonNode readManifest(Path jar) throws IOException {
    byte[] bytes = readManifestBytes(jar);
    if (bytes.length > PluginManifest.MAX_MODULE_BYTES) {
      throw new IOException(PluginManifest.FILE + " is larger than 4 MB");
    }
    return GppJson.MAPPER.readTree(bytes);
  }

  static String sha256(Path file) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
        in.transferTo(java.io.OutputStream.nullOutputStream());
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static RpcException failed(String phase, String message) {
    ObjectNode data = GppJson.object();
    data.put("phase", phase);
    return new RpcException(Gpp.PLUGIN_LOAD_FAILED, message, data);
  }
}
