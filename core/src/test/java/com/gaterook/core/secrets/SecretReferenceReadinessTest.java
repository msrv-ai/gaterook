package com.gaterook.core.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gaterook.core.platform.ProblemException;
import com.gaterook.core.secrets.internal.persistence.ExternalStoreEntity;
import com.gaterook.core.secrets.store.StoreBinding;
import com.gaterook.core.secrets.store.StoreIntegrity;
import com.gaterook.core.secrets.store.StoreResolution;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class SecretReferenceReadinessTest {
  private final StoreBinding binding = mock(StoreBinding.class);
  private final StoreIntegrity integrity = mock(StoreIntegrity.class);
  private final StoreResolution resolution = mock(StoreResolution.class);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
  private final SecretService service = new SecretService(null, null, null, List.of(), null, null, null,
      transactions, Clock.systemUTC(), binding, resolution, null, null, null);

  @Test void checksOnlyLocalStoreMetadataAndIntegrityWithoutResolvingCredentials() {
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    when(binding.integrity()).thenReturn(integrity);
    assertThat(service.referenceConnected("vault://kv/example#password", false, null)).isFalse();
    ExternalStoreEntity store = mock(ExternalStoreEntity.class);
    when(binding.candidates(any(), eq("example-store"), eq("/secrets"))).thenReturn(List.of(store));
    when(integrity.verifyStore(store)).thenReturn(true);
    assertThat(service.referenceConnected("vault://kv/example#password", false, "example-store")).isTrue();
    when(integrity.verifyStore(store)).thenReturn(false);
    assertThat(service.referenceConnected("vault://kv/example#password", false, "example-store")).isFalse();
    when(binding.candidates(any(), eq("missing-store"), eq("/secrets")))
        .thenThrow(ProblemException.unprocessable("No such store", "Configure the store.", "/secrets"));
    assertThat(service.referenceConnected("vault://kv/example#password", false, "missing-store")).isFalse();
    // Several Vaults are asked in turn for a value; short-lived credentials need the one chosen.
    ExternalStoreEntity other = mock(ExternalStoreEntity.class);
    when(binding.candidates(any(), eq(null), eq("/secrets"))).thenReturn(List.of(store, other));
    when(integrity.verifyStore(other)).thenReturn(true);
    assertThat(service.referenceConnected("vault://kv/example#password", false, null)).isTrue();
    assertThat(service.referenceConnected("vault://database/creds/reader", true, null)).isFalse();
    verifyNoInteractions(resolution);
  }

  @Test void malformedOrWrongKindOfReferenceRemainsAnErrorInsteadOfIncompleteSetup() {
    for (String uri : List.of("https://example.test/secret", "vault://kv/../password", "vault://database/creds/reader")) {
      assertThatThrownBy(() -> service.referenceConnected(uri, false, null)).isInstanceOf(ProblemException.class);
    }
    assertThatThrownBy(() -> service.referenceConnected("vault://kv/example#password", true, null))
        .isInstanceOf(ProblemException.class);
    verifyNoInteractions(transactions, binding, resolution);
  }
}
