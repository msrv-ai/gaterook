package ai.msrv.gaterook.core.oauth.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.msrv.gaterook.core.endpoints.EndpointRemovalParticipant;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshot;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshotService;
import ai.msrv.gaterook.core.oauth.internal.persistence.OAuthClientEntity;
import ai.msrv.gaterook.core.oauth.internal.persistence.OAuthClientKind;
import ai.msrv.gaterook.core.oauth.internal.persistence.OAuthClientRepository;
import ai.msrv.gaterook.core.oauth.internal.persistence.OAuthGrantEntity;
import ai.msrv.gaterook.core.oauth.internal.persistence.OAuthGrantRepository;
import ai.msrv.gaterook.core.oauth.internal.persistence.OAuthTokenRepository;
import ai.msrv.gaterook.core.platform.EventBus;
import ai.msrv.gaterook.core.platform.Ingress;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

class GrantEventsTest {

  private static final Instant NOW = Instant.parse("2026-10-28T10:00:00Z");
  private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
  private final Clock clock = mock(Clock.class);
  private final EventBus events = new EventBus(clock);
  private final TestTransactions transactions = new TestTransactions();
  private final OAuthGrantRepository rows = mock(OAuthGrantRepository.class);
  private final OAuthTokenRepository tokens = mock(OAuthTokenRepository.class);
  private final EndpointSnapshotService snapshots = mock(EndpointSnapshotService.class);
  private final OAuthClientEntity client = new OAuthClientEntity(OAuthClientKind.DCR, Ingress.LOCAL, "client-id",
      "Cursor", "localhost", List.of("http://localhost:4321/callback"), NOW);
  private final OAuthGrantEntity grant = new OAuthGrantEntity(client, "endpoint-id", "http://127.0.0.1:7465/mcp/dev",
      "mcp offline_access", NOW, NOW.plusSeconds(3600));

  @Test
  void tokenExchangeAndRevocationAnnounceOnlyAfterCommit() throws Exception {
    Grants grants = grants();
    transactions.beforeCommit = () -> assertThat(events.lastId()).isZero();
    try (EventBus.Subscription listener = events.subscribe(OptionalLong.empty())) {
      grants.issue(grant.getId());
      EventBus.GatewayEvent issued = listener.next(Duration.ZERO).orElseThrow();
      assertThat(issued.type()).isEqualTo("oauth.grant.changed");
      assertThat(issued.data().path("id").asString()).isEqualTo(grant.getId());
      assertThat(issued.data().path("endpoint").asString()).isEqualTo("dev");
      assertThat(issued.data().path("state").asString()).isEqualTo("active");
      assertThat(issued.data().path("lastUsedAt").asString()).isEqualTo(NOW.toString());

      transactions.beforeCommit = () -> assertThat(events.lastId()).isEqualTo(1);
      grants.revoke(grant.getId(), "Revoked in GateRook");
      EventBus.GatewayEvent revoked = listener.next(Duration.ZERO).orElseThrow();
      assertThat(revoked.data().path("state").asString()).isEqualTo("revoked");
      assertThat(revoked.data().path("lastUsedAt").isNull()).isTrue();
    }
  }

  @Test
  void aFailedTokenCommitPublishesNoConnectedEvidence() {
    Grants grants = grants();
    transactions.failCommit = true;
    assertThatThrownBy(() -> grants.issue(grant.getId())).isInstanceOf(TransactionSystemException.class);
    assertThat(events.lastId()).isZero();
  }

  @Test
  void authenticatedUseRefreshesAtMostOncePerSecondAndTheThrottleIsBounded() {
    Grants grants = grants();
    grants.used("og_cursor", "dev");
    grants.used("og_cursor", "dev");
    assertThat(events.lastId()).isEqualTo(1);
    now.set(NOW.plusSeconds(1));
    grants.used("og_cursor", "dev");
    assertThat(events.lastId()).isEqualTo(2);

    for (int i = 0; i < 1_024; i++) {
      grants.used("og_other_" + i, "dev");
    }
    long before = events.lastId();
    grants.used("og_cursor", "dev");
    assertThat(events.lastId()).isEqualTo(before + 1);
  }

  @SuppressWarnings("unchecked")
  private Grants grants() {
    when(clock.instant()).thenAnswer(call -> now.get());
    when(rows.findById(grant.getId())).thenReturn(Optional.of(grant));
    when(tokens.findByGrantId(grant.getId())).thenReturn(List.of());
    EndpointSnapshot endpoint = mock(EndpointSnapshot.class);
    when(endpoint.slug()).thenReturn("dev");
    when(snapshots.get("endpoint-id")).thenReturn(Optional.of(endpoint));
    ObjectProvider<EndpointRemovalParticipant> participants = mock(ObjectProvider.class);
    when(participants.orderedStream()).thenAnswer(call -> Stream.empty());
    ObjectProvider<SetupChangeRecorder> setup = mock(ObjectProvider.class);
    when(setup.getObject()).thenReturn(mock(SetupChangeRecorder.class));
    return new Grants(rows, tokens, mock(OAuthClientRepository.class), snapshots, participants, setup,
        mock(ApplicationEventPublisher.class), events, clock, transactions);
  }

  private static final class TestTransactions extends AbstractPlatformTransactionManager {
    private static final long serialVersionUID = 1L;
    Runnable beforeCommit = () -> {};
    boolean failCommit;

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {}

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      beforeCommit.run();
      if (failCommit) throw new TransactionSystemException("Commit failed");
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {}
  }
}
