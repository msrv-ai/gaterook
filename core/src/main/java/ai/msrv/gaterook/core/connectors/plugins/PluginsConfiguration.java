package ai.msrv.gaterook.core.connectors.plugins;

import ai.msrv.gaterook.connectors.support.egress.EgressClients;
import ai.msrv.gaterook.core.connectors.ConnectorRegistry;
import ai.msrv.gaterook.core.platform.BuildFlags;
import ai.msrv.gaterook.core.platform.SealedHandles;
import ai.msrv.gaterook.core.platform.SettingStore;
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
  HostRuntimeLocator hostRuntimeLocator(@Value("${gateway.resources:}") String resources,
      @Value("${gateway.pluginHostJar:}") String pluginHostJar) {
    HostRuntimeLocator locator = new HostRuntimeLocator(resources, pluginHostJar);
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

  @Bean
  HostLauncher hostLauncher(HostRuntimeLocator runtime, PluginPaths paths, SecretCallbackRegistry secrets,
      SealedHandles handles, AotCacheManager aot, Clock clock) {
    return new HostLauncher(runtime, paths, secrets, handles, aot, clock);
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
      @Value("${gateway.plugins.catalog-url:}") String baseUrl, Clock clock) {
    return new PluginCatalog(verifier, settings, downloads, baseUrl, clock);
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
