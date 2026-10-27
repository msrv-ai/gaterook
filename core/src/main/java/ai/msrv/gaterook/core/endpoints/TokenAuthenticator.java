package ai.msrv.gaterook.core.endpoints;

import ai.msrv.gaterook.core.endpoints.internal.GrantRows;
import ai.msrv.gaterook.core.endpoints.internal.persistence.ApiTokenRepository;
import ai.msrv.gaterook.core.endpoints.internal.persistence.ClientGrantEntity;
import ai.msrv.gaterook.core.endpoints.internal.persistence.TokenKind;
import ai.msrv.gaterook.core.platform.Authenticator;
import ai.msrv.gaterook.core.platform.Caller;
import ai.msrv.gaterook.core.platform.Ingress;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code gwt_} named and {@code gwb_} bridge tokens on the local listener (ARCHITECTURE.md §5 step 2): the SHA-256 is
 * looked up on every request, the token must be live and issued for the endpoint of the URL; a bridge token also
 * needs its grant unrevoked, for the same endpoint and as the core signed it ({@link GrantRows}). The principal is the
 * named token or the grant, never the rotating bridge token itself. Any mismatch is the same empty answer (401).
 */
@Component
@Order(10)
public class TokenAuthenticator implements Authenticator, ApplicationListener<ApplicationReadyEvent>, DisposableBean {

  private final ApiTokenRepository tokens;
  private final TokenService service;
  private final GrantRows grantRows;
  private final Clock clock;
  private final TransactionTemplate readTx;
  private ScheduledExecutorService flusher;

  public TokenAuthenticator(ApiTokenRepository tokens, TokenService service, GrantRows grantRows, Clock clock,
      PlatformTransactionManager transactions) {
    this.tokens = tokens;
    this.service = service;
    this.grantRows = grantRows;
    this.clock = clock;
    this.readTx = new TransactionTemplate(transactions);
    this.readTx.setReadOnly(true);
  }

  @Override
  public boolean supports(Ingress ingress) {
    return ingress == Ingress.LOCAL;
  }

  @Override
  public Optional<Caller> authenticate(Request request) {
    String token = request.bearerToken();
    boolean named = token.startsWith(TokenService.NAMED_PREFIX);
    if (!named && !token.startsWith(TokenService.BRIDGE_PREFIX) || token.length() > 128) {
      return Optional.empty();
    }
    String hash = TokenService.hash(token);
    Instant now = clock.instant();
    Optional<Caller> caller = readTx.execute(s -> tokens.findByHash(hash).flatMap(t -> {
      if (!t.isLive(now) || !t.getEndpoint().getSlug().equals(request.endpointSlug())) {
        return Optional.empty();
      }
      if (named != (t.getKind() == TokenKind.NAMED)) {
        return Optional.empty();
      }
      if (t.getKind() == TokenKind.NAMED) {
        return Optional.of(new Caller(Caller.Kind.LOCAL_TOKEN, t.getId(), t.getName(), null, Ingress.LOCAL,
            request.remoteAddress()));
      }
      ClientGrantEntity grant = t.getGrant();
      if (grant == null || grant.isRevoked() || !grant.getEndpoint().getId().equals(t.getEndpoint().getId())
          || !grantRows.verify(grant)) {
        return Optional.empty();
      }
      return Optional.of(new Caller(Caller.Kind.BRIDGE, grant.getId(), grant.getLabel(), grant.getClient(),
          Ingress.LOCAL, request.remoteAddress()));
    }));
    caller.filter(c -> c.kind() == Caller.Kind.LOCAL_TOKEN).ifPresent(c -> service.used(c.principalId(), now));
    return caller;
  }

  /** {@code last_used_at} is written once a minute, not on every call (ARCHITECTURE.md §4). */
  @Override
  public synchronized void onApplicationEvent(ApplicationReadyEvent event) {
    if (flusher != null) {
      return;
    }
    flusher = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("token-last-used").factory());
    flusher.scheduleWithFixedDelay(() -> {
      try {
        service.flushLastUsed();
      } catch (RuntimeException ignored) {
        // A busy database: the next minute writes them.
      }
    }, 1, 1, TimeUnit.MINUTES);
  }

  @Override
  public synchronized void destroy() throws InterruptedException {
    if (flusher != null) {
      flusher.shutdownNow();
      flusher.awaitTermination(5, TimeUnit.SECONDS);
      flusher = null;
    }
    try {
      service.flushLastUsed();
    } catch (RuntimeException ignored) {
      // The database is already closing: the uses of the last minute are lost, nothing else.
    }
  }
}
