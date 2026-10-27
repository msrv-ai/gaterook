package ai.msrv.gaterook.core.secrets.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.secrets.SecretOwner;
import ai.msrv.gaterook.core.secrets.internal.persistence.ExternalStoreRepository;
import ai.msrv.gaterook.core.secrets.internal.persistence.SecretRepository;
import ai.msrv.gaterook.core.secrets.internal.persistence.StoreKind;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * How a reference is resolved for a call (report 12 §4.3, §7.6): prod reuses a value 15 s at most, other
 * environments the store's TTL; a reference whose row isn't vouched for, or whose store isn't connected, fails with
 * the words the person sees.
 */
class StoreResolutionTest {

  private final VaultStoreTest.TestClock clock = new VaultStoreTest.TestClock(Instant.parse("2026-10-20T10:00:00Z"));
  private final StoreClients clients = mock(StoreClients.class);
  private final SecretStore store = mock(SecretStore.class);
  private StoreResolution resolution;

  @BeforeEach
  void setUp() {
    when(clients.client("st_1")).thenReturn(store);
    when(store.kind()).thenReturn(StoreKind.VAULT);
    when(store.cacheTtl()).thenReturn(Duration.ofSeconds(60));
    when(store.read(any(), any())).thenAnswer(i -> new RemoteValue("cnry-v".getBytes(StandardCharsets.UTF_8), "7",
        null, "string", List.of()));
    resolution = new StoreResolution(clients, new SecretCache(clock), mock(LeaseManager.class),
        mock(ExternalStoreRepository.class), mock(SecretRepository.class), mock(ApplicationEventPublisher.class), clock,
        mock(PlatformTransactionManager.class));
  }

  private static StoreResolution.Pointer pointer(String storeId, boolean verified) {
    return new StoreResolution.Pointer("sec_1", "stage-db/password", SecretOwner.connection("c1"), "/credentials/"
        + "password", false, "vault://kv/stage/db#password", storeId, "7", verified);
  }

  private void read(Env env) {
    try (RemoteValue v = resolution.value(pointer("st_1", true), env, StoreCall.within(t -> { }, Duration.ofSeconds(5),
        clock))) {
      assertThat(new String(v.value(), StandardCharsets.UTF_8)).isEqualTo("cnry-v");
    }
  }

  @Test
  void prodReadsAgainAfterFifteenSecondsOthersAfterTheStoresTtl() {
    read(Env.PROD);
    clock.advance(Duration.ofSeconds(10));
    read(Env.PROD);
    verify(store, times(1)).read(any(), any());
    clock.advance(Duration.ofSeconds(6));
    read(Env.PROD);
    verify(store, times(2)).read(any(), any());
    clock.advance(Duration.ofSeconds(20));
    read(Env.STAGE);
    verify(store, times(2)).read(any(), any());
    clock.advance(Duration.ofSeconds(41));
    read(Env.STAGE);
    verify(store, times(3)).read(any(), any());
  }

  @Test
  void aStoreErrorNamesTheSecret() {
    when(store.read(any(), any())).thenThrow(StoreException.gone("Version 3 of kv/stage/db is deleted."));
    assertThatThrownBy(() -> resolution.value(pointer("st_1", true), Env.DEV, StoreCall.within(t -> { },
        Duration.ofSeconds(5), clock))).hasMessage("stage-db/password can't be resolved: Version 3 of kv/stage/db is "
            + "deleted.");
  }

  @Test
  void anUnverifiedOrUnboundReferenceIsNotRead() {
    StoreCall call = StoreCall.within(t -> { }, Duration.ofSeconds(5), clock);
    assertThatThrownBy(() -> resolution.value(pointer("st_1", false), Env.DEV, call))
        .hasMessage("stage-db/password was changed outside MCP Gateway: review its reference in Secrets.");
    assertThatThrownBy(() -> resolution.value(pointer(null, true), Env.DEV, call))
        .hasMessage("stage-db/password can't be resolved: HashiCorp Vault isn't connected.");
    verify(store, times(0)).read(any(), any());
  }
}
