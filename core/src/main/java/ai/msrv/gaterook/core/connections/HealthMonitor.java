package ai.msrv.gaterook.core.connections;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.core.connectors.CallContext;
import ai.msrv.gaterook.core.connectors.CallContexts;
import ai.msrv.gaterook.core.connectors.ConnectorRegistry;
import ai.msrv.gaterook.core.connectors.EnvironmentPolicies;
import ai.msrv.gaterook.core.platform.Ids;
import ai.msrv.gaterook.core.platform.Masker;
import ai.msrv.gaterook.core.platform.MasterKeyLock;
import ai.msrv.gaterook.core.platform.Taint;
import ai.msrv.gaterook.core.secrets.SecretService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Connection health (kit Connections "Healthy · 42 ms", "Slow · 2.4 s", "Unreachable"): newly created connections
 * are checked after commit, then every {@code interval} each connection is asked {@code health()} under a deadline,
 * a few at a time; the result is stored without a new version and published as {@code connection.health}.
 * "Test all" ({@code POST /api/connections/test-all}) runs the full test
 * of each connection in the background, the same way.
 *
 * <p>Connections that changed outside the core, of a type that is off, or while the core is locked are skipped:
 * their health stays as it was.
 */
@Component
public class HealthMonitor implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

  private static final Logger log = LoggerFactory.getLogger(HealthMonitor.class);
  private static final int PARALLEL = 4;

  private final ConnectionConfigs configs;
  private final ConnectorRegistry registry;
  private final ConnectionService connections;
  private final ConnectionTester tester;
  private final SecretService secrets;
  private final CallContexts contexts;
  private final EnvironmentPolicies policies;
  private final Masker masker;
  private final MasterKeyLock lock;
  private final Duration interval;
  private final Duration initialDelay;
  private final Duration timeout;
  /** {@code GW_DEMO=1} outside a release: the kit's world, whose hosts don't exist (no rounds, see below). */
  private final boolean demoWorld;
  private final ExecutorService workers = Executors.newThreadPerTaskExecutor(
      Thread.ofVirtual().name("connection-health-", 0).factory());
  private final Semaphore healthPermits = new Semaphore(PARALLEL);
  private final AtomicBoolean testAllRunning = new AtomicBoolean();
  /** Connection versions ({@code id:version}) this process already ran a full test for, to learn their facts. */
  private final Set<String> factsTried = ConcurrentHashMap.newKeySet();
  private ScheduledExecutorService scheduler;

  public HealthMonitor(ConnectionConfigs configs, ConnectorRegistry registry, ConnectionService connections,
      ConnectionTester tester, SecretService secrets, CallContexts contexts, EnvironmentPolicies policies,
      Masker masker, MasterKeyLock lock,
      @Value("${gateway.connections.health-interval:5m}") Duration interval,
      @Value("${gateway.connections.health-initial-delay:30s}") Duration initialDelay,
      @Value("${gateway.connections.health-timeout:10s}") Duration timeout,
      @Value("${GW_DEMO:${gateway.demo:false}}") String demo) {
    this.configs = configs;
    this.registry = registry;
    this.connections = connections;
    this.tester = tester;
    this.secrets = secrets;
    this.contexts = contexts;
    this.policies = policies;
    this.masker = masker;
    this.lock = lock;
    this.interval = interval;
    this.initialDelay = initialDelay;
    this.timeout = timeout;
    this.demoWorld = !ai.msrv.gaterook.core.platform.BuildFlags.RELEASE && demo != null
        && (demo.equals("1") || demo.equalsIgnoreCase("true"));
  }

  /**
   * How often every connection's health is checked ("Health is checked every minute"); zero when the core doesn't
   * check by itself ({@code gateway.connections.health-interval=0s}).
   */
  public Duration interval() {
    return interval.isNegative() ? Duration.ZERO : interval;
  }

  /**
   * Starts the rounds. Not with {@code GW_DEMO=1}: the demo world's hosts don't exist, and a round would reach for them
   * on its own — DNS, AWS with the demo's key, GCP with this computer's application default credentials — and mark
   * every row unreachable; its rows keep the health the seed gave them, and Test connection and Test all still check
   * when the person asks (review API #6).
   */
  @Override
  public synchronized void onApplicationEvent(ApplicationReadyEvent event) {
    if (scheduler != null || interval.isZero() || interval.isNegative()) {
      return;
    }
    if (demoWorld) {
      log.info("Demo world: connection health isn't checked by itself (Test connection and Test all still are)");
      return;
    }
    scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("connection-health").factory());
    scheduler.scheduleWithFixedDelay(this::checkAllQuietly, initialDelay.toMillis(), interval.toMillis(),
        TimeUnit.MILLISECONDS);
  }

  private void checkAllQuietly() {
    try {
      checkAll();
    } catch (RuntimeException e) {
      log.debug("Health round skipped: {}", e.toString());
    }
  }

  /** Starts the first check without waiting for the next round or holding the creation request open. */
  @TransactionalEventListener
  public void onConnectionCreated(ConnectionCreated event) {
    if (demoWorld || interval.isZero() || interval.isNegative() || lock.locked()) {
      return;
    }
    workers.submit(() -> {
      healthPermits.acquireUninterruptibly();
      try {
        if (!lock.locked()) {
          configs.byId(event.connectionId()).filter(ConnectionConfigs.Stored::verified)
              .filter(s -> registry.usable(s.config().type())).ifPresent(this::check);
        }
      } catch (RuntimeException e) {
        log.debug("Initial health check failed: {}", masker.log(e.toString()));
      } finally {
        healthPermits.release();
      }
    });
  }

  /** One round of {@code health()} over every connection; returns how many were checked. */
  public int checkAll() {
    if (lock.locked()) {
      return 0;
    }
    List<ConnectionConfigs.Stored> all = eligible();
    List<Future<?>> running = new ArrayList<>();
    for (ConnectionConfigs.Stored stored : all) {
      running.add(workers.submit(() -> {
        healthPermits.acquireUninterruptibly();
        try {
          check(stored);
        } finally {
          healthPermits.release();
        }
      }));
    }
    for (Future<?> f : running) {
      try {
        f.get();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      } catch (ExecutionException e) {
        log.debug("Health check failed: {}", masker.log(String.valueOf(e.getCause())));
      }
    }
    return all.size();
  }

  /**
   * "Test all": the full test of every connection, in the background; each result arrives as
   * {@code connection.health}.
   *
   * @return how many connections will be tested, or -1 when a round is still running
   */
  public int testAll() {
    if (lock.locked() || !testAllRunning.compareAndSet(false, true)) {
      return -1;
    }
    List<ConnectionConfigs.Stored> all = eligible();
    Thread.ofVirtual().name("connection-test-all").start(() -> {
      try {
        Semaphore permits = new Semaphore(PARALLEL);
        List<Thread> threads = new ArrayList<>();
        for (ConnectionConfigs.Stored stored : all) {
          threads.add(Thread.ofVirtual().start(() -> {
            permits.acquireUninterruptibly();
            try {
              tester.testStored(stored, null, 0);
            } catch (RuntimeException e) {
              log.debug("Test of {} failed: {}", stored.name(), masker.log(e.toString()));
            } finally {
              permits.release();
            }
          }));
        }
        for (Thread t : threads) {
          t.join();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } finally {
        testAllRunning.set(false);
      }
    });
    return all.size();
  }

  private List<ConnectionConfigs.Stored> eligible() {
    return configs.all().stream().filter(ConnectionConfigs.Stored::verified)
        .filter(s -> registry.usable(s.config().type())).toList();
  }

  void check(ConnectionConfigs.Stored stored) {
    ConnectionConfig cfg = stored.config();
    Connector connector = registry.connectorFor(cfg).orElse(null);
    if (connector == null) {
      return;
    }
    Taint taint = new Taint();
    CallContext ctx = contexts.builder(Ids.next("hc_"), cfg, policies.limits(cfg.env())).timeout(timeout)
        .taint(taint).build();
    HealthResult result;
    try (var resolver = secrets.resolver(cfg, taint)) {
      Future<HealthResult> f = workers.submit(() -> connector.health(cfg, resolver, ctx));
      try {
        result = f.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      } catch (TimeoutException e) {
        ctx.cancel();
        f.cancel(true);
        result = HealthResult.unreachable("No answer within " + timeout.toSeconds() + " s");
      } catch (ExecutionException e) {
        result = HealthResult.unreachable("The health check failed inside the connector");
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
    if (result == null) {
      return;
    }
    String message = result.message() == null ? null
        : masker.text(result.message(), taint, ai.msrv.gaterook.core.platform.MaskPolicy.SECRETS_ONLY).text();
    HealthResult masked = new HealthResult(result.state(), result.latencyMs(), message);
    connections.recordHealth(cfg.id(), masked.state(),
        masked.state() == HealthResult.State.UNREACHABLE ? null : masked.latencyMs(), masked.message());
    tester.publishHealth(cfg.name(), cfg.env(), masked);
    learnFacts(stored, masked);
  }

  /**
   * A connection saved after a test of its draft has no stored test, so the list doesn't know what it runs ("PostgreSQL
   * 16", the AWS account): once per version and process, a reachable one gets the full test Test connection runs,
   * which records them. Not for MCP servers: their test starts the server's process.
   */
  private void learnFacts(ConnectionConfigs.Stored stored, HealthResult health) {
    ConnectionConfig cfg = stored.config();
    boolean known = cfg.metadata() != null && cfg.metadata().path("serverVersion").isString();
    boolean server = registry.manifest(cfg.type()).map(m -> m.kind() == ConnectorManifest.Kind.MCP_SERVER)
        .orElse(true);
    if (known || server || health.state() == HealthResult.State.UNREACHABLE
        || !factsTried.add(cfg.id() + ":" + cfg.version())) {
      return;
    }
    try {
      tester.testStored(stored, null, 0);
    } catch (RuntimeException e) {
      log.debug("The test of {} for its facts failed: {}", cfg.name(), masker.log(e.toString()));
    }
  }

  @Override
  public synchronized void destroy() throws InterruptedException {
    if (scheduler != null) {
      scheduler.shutdownNow();
      scheduler.awaitTermination(5, TimeUnit.SECONDS);
      scheduler = null;
    }
    workers.shutdownNow();
  }
}
