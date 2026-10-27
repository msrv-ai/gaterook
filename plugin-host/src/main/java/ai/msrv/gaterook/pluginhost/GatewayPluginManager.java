package ai.msrv.gaterook.pluginhost;

import ai.msrv.gaterook.plugin.protocol.GppJson;
import ai.msrv.gaterook.plugin.protocol.PluginManifest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.pf4j.ClassLoadingStrategy;
import org.pf4j.DefaultPluginDescriptor;
import org.pf4j.DefaultPluginManager;
import org.pf4j.JarPluginLoader;
import org.pf4j.Plugin;
import org.pf4j.PluginClassLoader;
import org.pf4j.PluginDescriptor;
import org.pf4j.PluginDescriptorFinder;
import org.pf4j.PluginFactory;
import org.pf4j.PluginLoader;
import org.pf4j.PluginManager;
import org.pf4j.PluginRuntimeException;
import org.pf4j.PluginStatusProvider;
import org.pf4j.PluginWrapper;
import tools.jackson.databind.JsonNode;

/**
 * One PF4J manager per plugin generation (report 11 §1, §7): PF4J can't hold two plugins with the same id in one
 * manager, and one process serves one generation anyway. What PF4J would do on its own is replaced where it matters:
 * the descriptor is our {@code mcp-gateway-plugin.json}, read without loading a class; the plugin's class loader is
 * child-first except for the shared API; the enabled/disabled state lives in the core's database, so nothing is
 * written next to the JAR; the {@code requires} check is the core's (PF4J would silently disable the plugin); and the
 * plugin needs no {@code org.pf4j.Plugin} subclass: its entry point is the manifest's {@code connectorClass}.
 */
final class GatewayPluginManager extends DefaultPluginManager {

  private final ClassLoader apiOnly;

  GatewayPluginManager(Path versionDir, ClassLoader apiOnly) {
    super(versionDir);
    this.apiOnly = apiOnly;
  }

  @Override
  protected PluginDescriptorFinder createPluginDescriptorFinder() {
    return new JsonManifestDescriptorFinder();
  }

  @Override
  protected PluginStatusProvider createPluginStatusProvider() {
    return new PluginStatusProvider() {
      @Override
      public boolean isPluginDisabled(String pluginId) {
        return false;
      }

      @Override
      public void disablePlugin(String pluginId) {
        // The core decides; nothing is written to disk here.
      }

      @Override
      public void enablePlugin(String pluginId) {
        // The core decides; nothing is written to disk here.
      }
    };
  }

  @Override
  protected PluginFactory createPluginFactory() {
    // The lifecycle hooks PF4J calls on a plugin: ours has none, its code starts with connectorClass.
    return wrapper -> new Plugin() {};
  }

  @Override
  protected PluginLoader createPluginLoader() {
    return new JarPluginLoader(this) {
      @Override
      protected PluginClassLoader createPluginClassLoader(Path pluginPath, PluginDescriptor descriptor) {
        return new GatewayPluginClassLoader(pluginManager, descriptor, apiOnly);
      }
    };
  }

  /** Child-first (PF4J's {@code PDA}), except for the shared API: one {@code Connector} class for host and plugin. */
  static final class GatewayPluginClassLoader extends PluginClassLoader {

    GatewayPluginClassLoader(PluginManager manager, PluginDescriptor descriptor, ClassLoader parent) {
      super(manager, descriptor, parent, ClassLoadingStrategy.PDA);
    }

    @Override
    protected boolean shouldDelegateToParent(String className) {
      return super.shouldDelegateToParent(className) || ApiOnlyClassLoader.shared(className);
    }
  }

  /** PF4J's descriptor from {@code mcp-gateway-plugin.json}: id, version, provider, license; no requires. */
  static final class JsonManifestDescriptorFinder implements PluginDescriptorFinder {

    @Override
    public boolean isApplicable(Path pluginPath) {
      return Files.isRegularFile(pluginPath) && pluginPath.getFileName().toString().endsWith(".jar");
    }

    @Override
    public PluginDescriptor find(Path pluginPath) {
      JsonNode m;
      try (ZipFile zip = new ZipFile(pluginPath.toFile())) {
        ZipEntry entry = zip.getEntry(PluginManifest.FILE);
        if (entry == null) {
          throw new PluginRuntimeException("No " + PluginManifest.FILE + " in " + pluginPath.getFileName());
        }
        try (InputStream in = zip.getInputStream(entry)) {
          m = GppJson.MAPPER.readTree(in.readNBytes(PluginManifest.MAX_BYTES + 1));
        }
      } catch (IOException e) {
        throw new PluginRuntimeException(e, "Can't read {}", pluginPath);
      }
      return new Descriptor(m.path("id").asString(), m.path("description").asString(""),
          m.path("version").asString(), m.path("provider").asString(""), m.path("license").asString(""));
    }
  }

  private static final class Descriptor extends DefaultPluginDescriptor {
    Descriptor(String id, String description, String version, String provider, String license) {
      super(id, description, Plugin.class.getName(), version, "*", provider, license);
    }
  }

  /** The wrapper of the one plugin this manager loaded. */
  PluginWrapper only() {
    return getPlugins().getFirst();
  }
}
