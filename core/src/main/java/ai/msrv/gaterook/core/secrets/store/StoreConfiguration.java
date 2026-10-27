package ai.msrv.gaterook.core.secrets.store;

import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.core.platform.Bootstrap;
import ai.msrv.gaterook.core.platform.BuildFlags;
import ai.msrv.gaterook.core.platform.IntegrityAlerts;
import ai.msrv.gaterook.core.platform.LocalListener;
import ai.msrv.gaterook.core.platform.RowMac;
import ai.msrv.gaterook.core.platform.SchemaValidator;
import ai.msrv.gaterook.core.platform.SettingStore;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder;
import ai.msrv.gaterook.core.secrets.SecretOwners;
import ai.msrv.gaterook.core.secrets.SecretService;
import ai.msrv.gaterook.core.secrets.internal.persistence.ExternalStoreRepository;
import ai.msrv.gaterook.core.secrets.internal.persistence.SecretLeaseRepository;
import ai.msrv.gaterook.core.secrets.internal.persistence.SecretRepository;
import ai.msrv.gaterook.core.secrets.store.vault.VaultStore;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationListener;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Beans of the external stores (ARCHITECTURE.md §2.2 {@code secrets}: "external stores, LeaseManager, short-lived
 * credentials"). Plain classes wired here, no component scan of their own; the schemas of the store forms are resources of
 * this package, registered for the native image below.
 */
@Configuration(proxyBeanMethods = false)
@ImportRuntimeHints(StoreConfiguration.Hints.class)
class StoreConfiguration {

  private static final Logger log = LoggerFactory.getLogger(StoreConfiguration.class);

  @Bean
  SecretCache secretCache(Clock clock) {
    return new SecretCache(clock);
  }

  @Bean
  StoreIntegrity storeIntegrity(RowMac rowMac, SettingStore settings) {
    return new StoreIntegrity(rowMac, settings);
  }

  @Bean
  StoreSchemas storeSchemas(JsonMapper json) {
    return new StoreSchemas(json);
  }

  /**
   * Test endpoints of the cloud stores, honoured by development builds only: a release never sends credentials
   * anywhere but Google and AWS (like {@code gateway.cloud.*} of {@code CloudCredentials}).
   */
  @Bean
  StoreClients.Endpoints storeEndpoints(Environment environment) {
    StoreClients.Endpoints e = new StoreClients.Endpoints(uri(environment,
        "gateway.cloud.gcp.secretmanager-endpoint"), uri(environment, "gateway.cloud.aws.secretsmanager-endpoint"));
    if ((e.gcpSecretManager() != null || e.awsSecretsManager() != null) && BuildFlags.RELEASE) {
      log.warn("Secret store endpoint overrides are ignored in a release build");
      return new StoreClients.Endpoints(null, null);
    }
    return e;
  }

  @Bean(destroyMethod = "close")
  StoreClients storeClients(ExternalStoreRepository stores, SecretRepository secrets, StoreIntegrity integrity,
      CloudCredentials cloud, ObjectProvider<SecretService> secretService, IntegrityAlerts alerts, Bootstrap bootstrap,
      LocalListener listener, StoreClients.Endpoints endpoints, JsonMapper json, Clock clock,
      PlatformTransactionManager tx) {
    return new StoreClients(stores, secrets, integrity, cloud, secretService, alerts, bootstrap, listener, endpoints,
        json, clock, tx);
  }

  /** Closed by {@link StoreLifecycle#stop()}, which revokes the leases while the stores still answer. */
  @Bean(destroyMethod = "")
  LeaseManager leaseManager(StoreClients clients, SecretLeaseRepository leases, SecretRepository secrets,
      ExternalStoreRepository stores, Clock clock, PlatformTransactionManager tx) {
    return new LeaseManager(id -> {
      if (clients.client(id) instanceof VaultStore vault) {
        return vault;
      }
      throw StoreException.invalid("Only Vault issues leases.");
    }, leases, secrets, stores, clock, tx);
  }

  @Bean
  StoreBinding storeBinding(ExternalStoreRepository stores, StoreIntegrity integrity) {
    return new StoreBinding(stores, integrity);
  }

  @Bean
  StoreResolution storeResolution(StoreClients clients, SecretCache cache, LeaseManager leases,
      ExternalStoreRepository stores, SecretRepository secrets, ApplicationEventPublisher publisher, Clock clock,
      PlatformTransactionManager tx) {
    return new StoreResolution(clients, cache, leases, stores, secrets, publisher, clock, tx);
  }

  @Bean
  StoreAdmin storeAdmin(ExternalStoreRepository stores, SecretRepository secrets, SecretLeaseRepository leaseRows,
      StoreClients clients, StoreBinding binding, StoreResolution resolution, SecretCache cache, LeaseManager leases,
      StoreSchemas schemas, SchemaValidator validator, ObjectProvider<SecretService> secretService,
      ObjectProvider<SecretOwners> owners, ObjectProvider<SetupChangeRecorder> setupChanges, IntegrityAlerts alerts,
      ApplicationEventPublisher publisher, JsonMapper json, Clock clock, PlatformTransactionManager tx) {
    return new StoreAdmin(stores, secrets, leaseRows, clients, binding, resolution, cache, leases, schemas, validator,
        secretService, owners, setupChanges, alerts, publisher, json, clock, tx);
  }

  @Bean
  StoreLifecycle storeLifecycle(SecretCache cache, LeaseManager leases) {
    return new StoreLifecycle(cache, leases);
  }

  private static URI uri(Environment environment, String key) {
    String value = environment.getProperty(key);
    return value == null || value.isBlank() ? null : URI.create(value);
  }

  /**
   * The cache sweep (every 5 s) and the lease job (every 30 s) from the ready core on; at the stop, while the stores
   * still answer and before the database closes, every lease is revoked and the cache zeroed.
   */
  static final class StoreLifecycle implements SmartLifecycle, ApplicationListener<ApplicationReadyEvent> {
    private final SecretCache cache;
    private final LeaseManager leases;
    private final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(
        Thread.ofVirtual().name("secret-cache-sweep").factory());
    private volatile boolean running;

    StoreLifecycle(SecretCache cache, LeaseManager leases) {
      this.cache = cache;
      this.leases = leases;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
      sweeper.scheduleWithFixedDelay(cache::sweep, 5, 5, TimeUnit.SECONDS);
      try {
        leases.start(Duration.ofSeconds(30));
      } catch (RuntimeException e) {
        log.warn("Vault leases aren't maintained: {}", e.getMessage());
      }
    }

    @Override
    public void start() {
      running = true;
    }

    @Override
    public void stop() {
      running = false;
      sweeper.shutdownNow();
      leases.close();
      cache.clear();
    }

    @Override
    public boolean isRunning() {
      return running;
    }

    /** Stops early (high phase), before the web server and the database go. */
    @Override
    public int getPhase() {
      return Integer.MAX_VALUE - 1_000;
    }
  }

  /** The store forms are resources read at run time (report 15 §9). */
  static final class Hints implements RuntimeHintsRegistrar {
    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
      hints.resources().registerPattern("ai/msrv/gaterook/core/secrets/store/*.schema.json");
    }
  }
}
