package ai.msrv.gaterook.pluginhost;

import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.plugin.protocol.Gpp;
import ai.msrv.gaterook.plugin.protocol.GppJson;
import ai.msrv.gaterook.plugin.protocol.Messages;
import ai.msrv.gaterook.plugin.protocol.PluginManifest;
import ai.msrv.gaterook.plugin.protocol.RpcException;
import ai.msrv.gaterook.plugin.protocol.host.ConnectorEndpoint;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
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
 * threads already running), on macOS through {@code sandbox-exec}. The load only reports which one it runs in.
 */
final class PluginRegistry implements ConnectorEndpoint.Generations {

  /** One loaded generation. */
  record LoadedPlugin(String generation, GatewayPluginManager manager, String pluginId, Connector connector,
      Path dataDir) {}

  private final Map<String, LoadedPlugin> byGeneration = new ConcurrentHashMap<>();
  private final ClassLoader apiOnly = new ApiOnlyClassLoader(PluginRegistry.class.getClassLoader());
  /** The sandbox this process was started in ({@code landlock}), or {@code null}; set by {@link SandboxExec}. */
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
      manifest = PluginManifest.parse(readManifest(jar));
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
    Connector connector;
    try {
      Class<?> type = manager.getPluginClassLoader(pluginId).loadClass(manifest.connectorClass());
      if (!Connector.class.isAssignableFrom(type)) {
        throw failed("instantiate", manifest.connectorClass() + " doesn't implement Connector");
      }
      connector = (Connector) type.getDeclaredConstructor().newInstance();
    } catch (RpcException e) {
      manager.unloadPlugins();
      throw e;
    } catch (InvocationTargetException e) {
      manager.unloadPlugins();
      throw failed("instantiate", String.valueOf(e.getCause()));
    } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
      manager.unloadPlugins();
      throw failed("instantiate", e.toString());
    }
    List<ToolDefinition> tools;
    String connectorApi;
    try {
      tools = connector.manifest().tools();
      connectorApi = connector.manifest().connectorApi();
    } catch (RuntimeException | LinkageError e) {
      close(connector);
      manager.unloadPlugins();
      throw failed("tools", "The plugin's manifest() failed: " + e);
    }
    byGeneration.put(load.generation(), new LoadedPlugin(load.generation(), manager, pluginId, connector, dataDir));
    return new Messages.Loaded(load.generation(), manifest.id(), connectorApi,
        (System.nanoTime() - started) / 1_000_000, tools, sandboxKind);
  }

  synchronized void unload(String generation) {
    LoadedPlugin p = byGeneration.remove(generation);
    if (p != null) {
      close(p.connector());
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
    return p == null ? Optional.empty()
        : Optional.of(new ConnectorEndpoint.Loaded(p.generation(), p.connector(), p.dataDir()));
  }

  @Override
  public Collection<ConnectorEndpoint.Loaded> all() {
    return byGeneration.values().stream()
        .map(p -> new ConnectorEndpoint.Loaded(p.generation(), p.connector(), p.dataDir())).toList();
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

  static JsonNode readManifest(Path jar) throws IOException {
    try (ZipFile zip = new ZipFile(jar.toFile())) {
      ZipEntry entry = zip.getEntry(PluginManifest.FILE);
      if (entry == null) {
        throw new IOException("no " + PluginManifest.FILE + " in the JAR");
      }
      try (InputStream in = zip.getInputStream(entry)) {
        byte[] bytes = in.readNBytes(PluginManifest.MAX_BYTES + 1);
        if (bytes.length > PluginManifest.MAX_BYTES) {
          throw new IOException(PluginManifest.FILE + " is larger than 1 MB");
        }
        return GppJson.MAPPER.readTree(bytes);
      }
    }
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
