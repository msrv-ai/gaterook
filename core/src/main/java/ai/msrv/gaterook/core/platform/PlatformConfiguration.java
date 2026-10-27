package ai.msrv.gaterook.core.platform;

import ai.msrv.gaterook.connectors.support.egress.EgressClients;
import ai.msrv.gaterook.connectors.support.egress.HostResolver;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.context.annotation.Role;
import org.springframework.scheduling.annotation.AsyncAnnotationBeanPostProcessor;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.config.TaskManagementConfigUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * Beans every module may use: the clock (tests replace it), the event bus, the masker, the master key's lock state,
 * one-time file grants, row MACs, sealed handles, the caller authenticator and the egress guard's clients; plus the
 * core's native-image hints.
 *
 * <p>{@code @Async}: modules hear each other's events with {@code @TransactionalEventListener(AFTER_COMMIT)} +
 * {@code @Async} (ARCHITECTURE.md §2.2), on Boot's task executor, which runs on virtual threads here. The processor is
 * {@code @EnableAsync}'s, registered by hand ({@link GatewayAsync}) so that it only inspects the gateway's own beans;
 * the transaction advisor gets the same scope ({@link TransactionAdvisorScope}).
 */
@Configuration(proxyBeanMethods = false)
@ImportRuntimeHints(CoreHints.class)
class PlatformConfiguration {

  /** What {@code @EnableAsync} registers, under its bean name, limited to {@code ai.msrv.gaterook} classes. */
  @Bean(name = TaskManagementConfigUtils.ASYNC_ANNOTATION_PROCESSOR_BEAN_NAME)
  @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
  static AsyncAnnotationBeanPostProcessor gatewayAsync(ObjectProvider<AsyncConfigurer> configurers) {
    return new GatewayAsync(configurers);
  }

  /** Boot's {@code JacksonComponentModule}, which the core doesn't use (see {@link #withoutJacksonComponentScan}). */
  static final String JACKSON_COMPONENT_MODULE = "jsonComponentModule";

  /**
   * Without Boot's {@code JacksonComponentModule}: at start it asks every bean definition of the context for
   * {@code @JacksonComponent}, a merged-annotation lookup per bean (a dozen milliseconds of the native start,
   * ARCHITECTURE.md §10, M1.7), and the core has no such component — serializers belong to the records and Jackson
   * modules that need them ({@code UnusedFrameworkBeansTest} keeps it that way). Runs at AOT processing too, so the
   * generated registrations don't have the bean.
   */
  @Bean
  static BeanFactoryPostProcessor withoutJacksonComponentScan() {
    return beanFactory -> {
      if (beanFactory instanceof BeanDefinitionRegistry registry
          && registry.containsBeanDefinition(JACKSON_COMPONENT_MODULE)) {
        registry.removeBeanDefinition(JACKSON_COMPONENT_MODULE);
      }
    };
  }

  /** By-type lookups cached from the first post-processor on ({@link EarlyFrozenConfiguration}). */
  @Bean
  @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
  static EarlyFrozenConfiguration earlyFrozenConfiguration() {
    return new EarlyFrozenConfiguration();
  }

  /** {@code @Transactional} is looked for on {@code ai.msrv.gaterook} classes only ({@link TransactionAdvisorScope}). */
  @Bean
  @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
  static TransactionAdvisorScope transactionAdvisorScope() {
    return new TransactionAdvisorScope();
  }

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  EventBus eventBus(Clock clock) {
    return new EventBus(clock);
  }

  @Bean
  Masker masker() {
    return Masker.standard();
  }

  @Bean
  MasterKeyLock masterKeyLock() {
    return new MasterKeyLock();
  }

  @Bean
  FileGrants fileGrants(Clock clock) {
    return new FileGrants(clock);
  }

  @Bean
  RowMac rowMac(HmacKeys keys, JsonMapper json) {
    return new RowMac(keys, json);
  }

  @Bean
  SealedHandles sealedHandles(HmacKeys keys, JsonMapper json, Clock clock) {
    return new SealedHandles(keys, json, clock);
  }

  @Bean
  SchemaValidator schemaValidator() {
    return new SchemaValidator();
  }

  @Bean
  IntegrityAlerts integrityAlerts(EventBus events, Clock clock) {
    return new IntegrityAlerts(events, clock);
  }

  @Bean
  CallerAuthenticator callerAuthenticator(List<Authenticator> authenticators) {
    return new CallerAuthenticator(authenticators);
  }

  /** The port the local listener really bound (the bootstrap may ask for any free one). */
  @Bean
  ListenerPorts listenerPorts(Bootstrap bootstrap) {
    return new ListenerPorts(bootstrap);
  }

  @Bean
  LocalListener localListener(ListenerPorts ports, Bootstrap bootstrap) {
    return new LocalListener(() -> ports.bound.get() > 0 ? ports.bound.get() : bootstrap.port());
  }

  /** Spans of MCP calls; without a tracer (Micrometer Tracing off) ids are made up locally. */
  @Bean
  CallTracing callTracing(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
    return new CallTracing(tracer.getIfAvailable(), propagator.getIfAvailable());
  }

  /**
   * Guarded HTTP for downloads (catalog, plugins, OpenAPI by URL), MCP servers over HTTP and {@code ctx.egress()}:
   * the core's own listeners are never reachable through it.
   */
  @Bean(destroyMethod = "close")
  EgressClients egressClients(ListenerPorts ports) {
    return new EgressClients(new EgressClients.Settings("mcp-gateway/" + CoreBuild.VERSION, ports::all,
        HostResolver.SYSTEM));
  }

  /** The core's ports: what the bootstrap asked for and what the web server bound. */
  static final class ListenerPorts implements ApplicationListener<WebServerInitializedEvent> {
    private final Bootstrap bootstrap;
    private final AtomicInteger bound = new AtomicInteger();

    ListenerPorts(Bootstrap bootstrap) {
      this.bootstrap = bootstrap;
    }

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
      bound.set(event.getWebServer().getPort());
    }

    Set<Integer> all() {
      Set<Integer> ports = new HashSet<>();
      for (int port : new int[] {bootstrap.port(), bootstrap.publicPort(), bound.get()}) {
        if (port > 0) {
          ports.add(port);
        }
      }
      return ports;
    }
  }
}
