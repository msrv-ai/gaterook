package com.gaterook.core.connectors.plugins;

import com.gaterook.connectors.support.egress.EgressClients;
import com.gaterook.core.connectors.ConnectorRegistry;
import com.gaterook.core.platform.BuildFlags;
import com.gaterook.core.platform.IntegrityAlerts;
import com.gaterook.core.platform.MasterKeyLock;
import com.gaterook.core.platform.SealedHandles;
import com.gaterook.core.platform.SettingStore;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Wiring of the plugin subsystem (ARCHITECTURE.md §7.5). The runtime locator and the host launcher find the JRE and
 * {@code plugin-host.jar}; the AOT cache, the signed catalog and its verifier, the artifact downloader and the
 * installer complete the install path. The trusted catalog keys ({@code gateway.plugins.catalog-keys}) and the catalog
 * URL ({@code gateway.plugins.catalog-url}) are configuration a release sets (step K6, {@code catalog/README.md});
 * without them the Catalog stays offline and installed plugins still work.
 */
@Configuration(proxyBeanMethods = false)
class PluginsConfiguration {

  private static final Logger log = LoggerFactory.getLogger(PluginsConfiguration.class);

  @Bean
  PluginRuntime pluginRuntime(com.gaterook.core.platform.GatewayPaths paths) {
    return new PluginRuntime(paths.home());
  }

  @Bean
  HostRuntimeLocator hostRuntimeLocator(@Value("${gateway.resources:}") String resources,
      @Value("${gateway.pluginHostJar:}") String pluginHostJar, PluginRuntime downloads) {
    HostRuntimeLocator locator = new HostRuntimeLocator(resources, pluginHostJar, downloads);
    locator.unavailableReason().ifPresent(reason -> log.info("Plugin host runtime: {}", reason));
    return locator;
  }

  @Bean
  AotCacheManager aotCacheManager(HostRuntimeLocator runtime, PluginPaths paths,
      @Value("${gateway.plugins.aot-cache:true}") boolean enabled) {
    return new AotCacheManager(runtime, paths, enabled);
  }

  @Bean
  SecretCallbackRegistry secretCallbackRegistry() {
    return new SecretCallbackRegistry();
  }

  /** Hosts are kept from the core's own listeners: the ports the egress guard refuses ({@link EgressClients}). */
  @Bean
  HostLauncher hostLauncher(HostRuntimeLocator runtime, PluginPaths paths, SecretCallbackRegistry secrets,
      SealedHandles handles, AotCacheManager aot, EgressClients egress, Clock clock) {
    return new HostLauncher(runtime, paths, secrets, handles, aot, egress.settings().corePorts(), clock);
  }

  @Bean
  CatalogVerifier catalogVerifier(@Value("${gateway.plugins.catalog-keys:}") String keys, Clock clock) {
    return new CatalogVerifier(parseKeys(keys), clock);
  }

  /**
   * Catalog downloads through the egress guard. {@code gateway.test.catalog.ca} (a PEM file of CA certificates) is a
   * test override of a non-release build: the catalog is trusted with those CAs only and may be served from loopback
   * (an HTTPS MockWebServer); a release ignores it, like {@code gateway.test.oauth.cimd-ca}.
   */
  @Bean
  CatalogDownloads catalogDownloads(EgressClients egress, @Value("${gateway.test.catalog.ca:}") String testCa) {
    List<X509Certificate> cas = List.of();
    if (testCa != null && !testCa.isBlank() && !BuildFlags.RELEASE) {
      cas = certificates(Path.of(testCa.strip()));
      log.warn("Test override: the catalog is downloaded with the CA of {} and may live on loopback", testCa);
    }
    return new CatalogDownloads(egress, cas);
  }

  @Bean
  PluginCatalog pluginCatalog(CatalogVerifier verifier, SettingStore settings, CatalogDownloads downloads,
      @Value("${gateway.plugins.catalog-url:}") String baseUrl, IntegrityAlerts alerts, MasterKeyLock lock,
      PlatformTransactionManager transactions, Clock clock) {
    return new PluginCatalog(verifier, settings, downloads, baseUrl, alerts, lock, transactions, clock);
  }

  /**
   * The channel of GateRook's own connector modules (docs/connector-delivery.md): the catalog's machinery under keys
   * and settings of its own. A release reads GateRook's official list ({@link OfficialLists}); a development build
   * reads none unless it is given one ({@code gateway.modules.channel-keys} and {@code gateway.modules.channel-url})
   * or asked for the official one ({@code gateway.lists=official}). Without a channel nothing downloads and the
   * installed modules still work. Its index is not a bean: whatever asks for the catalog gets the plugin catalog.
   */
  @Bean
  ModuleChannel moduleChannel(@Value("${gateway.modules.channel-keys:}") String keys,
      @Value("${gateway.modules.channel-url:}") String baseUrl, @Value("${gateway.lists:}") String lists,
      SettingStore settings, CatalogDownloads downloads, IntegrityAlerts alerts, MasterKeyLock lock,
      PlatformTransactionManager transactions, Clock clock) {
    if (baseUrl.isBlank() && keys.isBlank() && OfficialLists.inUse(lists)) {
      baseUrl = OfficialLists.MODULES_URL;
      keys = OfficialLists.MODULES_KEYS;
    }
    PluginCatalog index = new PluginCatalog(PluginCatalog.Names.MODULES, new CatalogVerifier(parseKeys(keys), clock),
        settings, downloads, baseUrl, alerts, lock, transactions, clock);
    return new ModuleChannel(index, new PluginArtifacts(downloads, index));
  }

  @Bean
  PluginArtifacts pluginArtifacts(CatalogDownloads downloads, PluginCatalog catalog) {
    return new PluginArtifacts(downloads, catalog);
  }

  @Bean
  PluginInstaller pluginInstaller(PluginPaths paths, PluginCatalog catalog, HostRuntimeLocator runtime,
      HostLauncher launcher, ConnectorRegistry registry, Clock clock) {
    return new PluginInstaller(paths, catalog, runtime, launcher, registry, clock);
  }

  private static List<X509Certificate> certificates(Path pem) {
    try (InputStream in = Files.newInputStream(pem)) {
      List<X509Certificate> out = new ArrayList<>();
      for (Certificate c : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
        out.add((X509Certificate) c);
      }
      if (out.isEmpty()) {
        throw new IllegalStateException("gateway.test.catalog.ca names no certificate: " + pem);
      }
      return List.copyOf(out);
    } catch (IOException | CertificateException e) {
      throw new IllegalStateException("gateway.test.catalog.ca can't be read: " + pem, e);
    }
  }

  /** {@code keyId=base64url32,keyId2=…}: the Ed25519 keys the catalog may be signed with (a release ships them). */
  private static Map<String, byte[]> parseKeys(String text) {
    Map<String, byte[]> keys = new LinkedHashMap<>();
    if (text == null || text.isBlank()) {
      return keys;
    }
    for (String pair : text.split(",")) {
      int eq = pair.indexOf('=');
      if (eq <= 0) {
        continue;
      }
      try {
        keys.put(pair.substring(0, eq).strip(), Base64.getUrlDecoder().decode(pair.substring(eq + 1).strip()));
      } catch (IllegalArgumentException e) {
        log.warn("Ignoring an invalid catalog key for {}", pair.substring(0, eq));
      }
    }
    return keys;
  }
}
