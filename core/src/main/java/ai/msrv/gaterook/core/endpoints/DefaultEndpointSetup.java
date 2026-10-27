package ai.msrv.gaterook.core.endpoints;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.core.endpoints.internal.EndpointRows;
import ai.msrv.gaterook.core.endpoints.internal.Wiring;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointAccess;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointEntity;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointRepository;
import ai.msrv.gaterook.core.platform.MasterKeyChanged;
import ai.msrv.gaterook.core.platform.MasterKeyLock;
import ai.msrv.gaterook.core.platform.SettingStore;
import java.time.Clock;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.BooleanNode;

/**
 * An installation without endpoints starts with an empty, usable {@code default} endpoint. Existing endpoints are
 * left alone, and the initialization marker keeps a later deletion of the default from being undone on restart.
 */
@Component
class DefaultEndpointSetup {

  static final String INITIALIZED = "endpoints.default-initialized";

  private final EndpointRepository endpoints;
  private final EndpointRows rows;
  private final Wiring wiring;
  private final SettingStore settings;
  private final MasterKeyLock lock;
  private final ApplicationEventPublisher publisher;
  private final Clock clock;
  private final TransactionTemplate writeTx;

  DefaultEndpointSetup(EndpointRepository endpoints, EndpointRows rows, Wiring wiring, SettingStore settings,
      MasterKeyLock lock, ApplicationEventPublisher publisher, Clock clock, PlatformTransactionManager transactions) {
    this.endpoints = endpoints;
    this.rows = rows;
    this.wiring = wiring;
    this.settings = settings;
    this.lock = lock;
    this.publisher = publisher;
    this.clock = clock;
    this.writeTx = new TransactionTemplate(transactions);
  }

  /** After the optional demo seed, before the ready line lets the desktop open the welcome page. */
  @EventListener(ApplicationReadyEvent.class)
  @Order(Ordered.HIGHEST_PRECEDENCE + 1)
  void initialize() {
    if (lock.locked()) {
      return;
    }
    writeTx.executeWithoutResult(status -> {
      if (settings.find(INITIALIZED).isPresent()) {
        return;
      }
      if (endpoints.count() == 0) {
        EndpointEntity endpoint = new EndpointEntity("default", Env.DEV, EndpointAccess.STANDARD,
            rows.map(EndpointService.defaultSettings()), clock.instant());
        endpoint.setDescription("Your first endpoint");
        endpoint = rows.save(endpoint);
        wiring.ensureCoreTools(endpoint, Mode.READ_ONLY);
        publisher.publishEvent(new EndpointChanged(endpoint.getId(), endpoint.getSlug()));
      }
      settings.put(INITIALIZED, BooleanNode.TRUE, 0L);
    });
  }

  /** A locked installation can finish its initial setup after key recovery, without restarting the desktop. */
  @Async
  @TransactionalEventListener
  public void onMasterKeyChanged(MasterKeyChanged event) {
    if (event.kind() == MasterKeyChanged.Kind.CREATED || event.kind() == MasterKeyChanged.Kind.RESTORED) {
      initialize();
    }
  }
}
