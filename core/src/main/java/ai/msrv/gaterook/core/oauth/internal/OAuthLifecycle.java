package ai.msrv.gaterook.core.oauth.internal;

import ai.msrv.gaterook.core.endpoints.EndpointRemovalParticipant;
import ai.msrv.gaterook.core.tunnel.TunnelStateChanged;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Where OAuth meets the rest of the core: an endpoint holding live sign-ins doesn't cross the prod line
 * ({@link #liveAccess}) and its deletion ends them; a tunnel that comes up on a new hostname leaves the sign-ins of
 * the old one {@code address-changed}; {@code last_used_at} is written once a minute and unused registrations are
 * cleaned up daily.
 */
@Component
class OAuthLifecycle implements EndpointRemovalParticipant, DisposableBean {

  private static final Logger log = LoggerFactory.getLogger(OAuthLifecycle.class);

  private final OAuthServer server;
  private ScheduledExecutorService housekeeping;

  OAuthLifecycle(OAuthServer server) {
    this.server = server;
  }

  @Override
  public Removal onEndpointRemoving(String endpointId, String slug, String reason) {
    server.endpointRemoving(endpointId, slug);
    return Removal.none();
  }

  @Override
  public List<String> liveAccess(String endpointId) {
    return server.liveAccess(endpointId);
  }

  @EventListener
  public void onTunnelStateChanged(TunnelStateChanged e) {
    if ("on".equals(e.state()) && e.host() != null) {
      int changed = server.addressChanged(e.host());
      if (changed > 0) {
        log.info("{} sign-in(s) of an earlier tunnel address can't be used on {}", changed, e.host());
      }
    }
  }

  @EventListener(ApplicationReadyEvent.class)
  public synchronized void onReady() {
    if (housekeeping != null) {
      return;
    }
    housekeeping = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("oauth-housekeeping")
        .factory());
    AtomicInteger minutes = new AtomicInteger();
    housekeeping.scheduleWithFixedDelay(() -> {
      try {
        server.housekeeping(minutes.getAndIncrement() % (24 * 60) == 0);
      } catch (RuntimeException e) {
        // A busy database: the next minute tries again.
        log.debug("OAuth housekeeping postponed: {}", e.toString());
      }
    }, 1, 1, TimeUnit.MINUTES);
  }

  @Override
  public synchronized void destroy() throws InterruptedException {
    if (housekeeping != null) {
      housekeeping.shutdownNow();
      housekeeping.awaitTermination(5, TimeUnit.SECONDS);
      housekeeping = null;
    }
    try {
      server.housekeeping(false);
    } catch (RuntimeException ignored) {
      // The database is closing: the uses of the last minute are lost, nothing else.
    }
  }
}
