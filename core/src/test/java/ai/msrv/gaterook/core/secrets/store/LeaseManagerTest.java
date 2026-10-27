package ai.msrv.gaterook.core.secrets.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.msrv.gaterook.core.secrets.internal.persistence.ExternalStoreRepository;
import ai.msrv.gaterook.core.secrets.internal.persistence.SecretLeaseRepository;
import ai.msrv.gaterook.core.secrets.internal.persistence.SecretRepository;
import ai.msrv.gaterook.core.secrets.store.vault.VaultStore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Leases of Vault dynamic credentials (report 12 §6): one per connection field, shared by the calls, renewed after two
 * thirds, replaced within a minute of its end, revoked when idle for 15 minutes and when the core stops; every
 * password handed out goes to the call's taint.
 */
class LeaseManagerTest {

  private final VaultStoreTest.TestClock clock = new VaultStoreTest.TestClock(Instant.parse("2026-10-20T10:00:00Z"));
  private final VaultStore vault = mock(VaultStore.class);
  private final AtomicInteger issued = new AtomicInteger();
  private final List<String> taint = new ArrayList<>();
  private final SecretRef.Vault ref = (SecretRef.Vault) SecretRef.parse("vault://database/creds/prod-replica-readonly");
  private LeaseManager leases;

  @BeforeEach
  void setUp() {
    when(vault.issue(any(), any())).thenAnswer(i -> {
      int n = issued.incrementAndGet();
      return new VaultStore.Lease("database/creds/prod-replica-readonly/lease" + n + "abcdefgh", "v-user-" + n,
          ("cnry-dyn-" + n).toCharArray(), Duration.ofHours(1), true);
    });
    when(vault.renew(any(), any(), any())).thenReturn(Duration.ofHours(1));
    leases = new LeaseManager(id -> vault, mock(SecretLeaseRepository.class), mock(SecretRepository.class),
        mock(ExternalStoreRepository.class), clock, mock(PlatformTransactionManager.class));
  }

  private LeaseManager.Login login() {
    return leases.login("sec_1", "st_1", ref, StoreCall.within(v -> taint.add(v.toString()), Duration.ofSeconds(5),
        clock));
  }

  @Test
  void oneLeaseIsSharedRenewedAndReplacedBeforeItsEnd() {
    LeaseManager.Login first = login();
    assertThat(first.username()).isEqualTo("v-user-1");
    assertThat(first.version()).isEqualTo("lease lease1ab");
    assertThat(first.notAfter()).isEqualTo(clock.instant().plus(Duration.ofHours(1)));
    clock.advance(Duration.ofMinutes(10));
    assertThat(login().username()).as("shared by the next call").isEqualTo("v-user-1");
    verify(vault, never()).renew(any(), any(), any());

    clock.advance(Duration.ofMinutes(31));
    LeaseManager.Login renewed = login();
    assertThat(renewed.username()).isEqualTo("v-user-1");
    verify(vault, times(1)).renew(eq("database/creds/prod-replica-readonly/lease1abcdefgh"), any(), any());
    assertThat(renewed.notAfter()).isEqualTo(clock.instant().plus(Duration.ofHours(1)));

    // Renewal refused (the role's max TTL): the lease keeps its end, and within a minute of it a new one replaces it.
    when(vault.renew(any(), any(), any())).thenReturn(Duration.ZERO);
    clock.advance(Duration.ofMinutes(41));
    assertThat(login().notAfter()).isEqualTo(renewed.notAfter());
    verify(vault, times(2)).renew(any(), any(), any());
    clock.advance(Duration.ofMinutes(18).plusSeconds(10));
    LeaseManager.Login replaced = login();
    assertThat(replaced.username()).isEqualTo("v-user-2");
    verify(vault).revoke(eq("database/creds/prod-replica-readonly/lease1abcdefgh"), any());
    // A reused lease's password is tainted here; a new one's by VaultStore.issue (VaultStoreTest).
    assertThat(taint).contains("cnry-dyn-1");
    assertThat(leases.all()).singleElement().satisfies(a -> assertThat(a.ref()).isEqualTo(ref.uri()));
  }

  @Test
  void anIdleLeaseIsRevokedAndStoppingRevokesTheRest() {
    login();
    clock.advance(Duration.ofMinutes(16));
    leases.maintain();
    verify(vault).revoke(eq("database/creds/prod-replica-readonly/lease1abcdefgh"), any());
    assertThat(leases.active("sec_1")).isEmpty();

    login();
    leases.close();
    verify(vault).revoke(eq("database/creds/prod-replica-readonly/lease2abcdefgh"), any());
    assertThat(leases.all()).isEmpty();
  }

  @Test
  void aChangedReferenceGetsANewLease() {
    login();
    SecretRef.Vault other = (SecretRef.Vault) SecretRef.parse("vault://database/creds/other-role");
    LeaseManager.Login next = leases.login("sec_1", "st_1", other, StoreCall.within(v -> { }, Duration.ofSeconds(5),
        clock));
    assertThat(next.username()).isEqualTo("v-user-2");
    verify(vault).revoke(eq("database/creds/prod-replica-readonly/lease1abcdefgh"), any());
  }
}
