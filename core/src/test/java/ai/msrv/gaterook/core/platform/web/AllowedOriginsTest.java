package ai.msrv.gaterook.core.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.msrv.gaterook.core.platform.Bootstrap;
import ai.msrv.gaterook.core.platform.SettingStore;
import ai.msrv.gaterook.core.platform.TestGateway;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.json.JsonMapper;

class AllowedOriginsTest {

  private static Bootstrap bootstrap(boolean dev) {
    return new Bootstrap(TestGateway.launchSecret(), "instance-nonce-0001", null, "off", 7465, 7466, dev);
  }

  @SuppressWarnings("unchecked")
  private static ObjectProvider<SettingStore> store(String json) {
    SettingStore store = mock(SettingStore.class);
    when(store.find(anyString())).thenReturn(json == null ? Optional.empty() : Optional.of(new SettingStore.Setting(
        AllowedOrigins.SETTING, JsonMapper.builder().build().readTree(json), 1, Instant.EPOCH)));
    ObjectProvider<SettingStore> provider = mock(ObjectProvider.class);
    when(provider.getObject()).thenReturn(store);
    return provider;
  }

  @Test
  void noOriginByDefault() {
    AllowedOrigins origins = new AllowedOrigins(store(null), bootstrap(false));

    assertThat(origins.allows("https://inspector.example")).isFalse();
    assertThat(origins.allows(AllowedOrigins.DEV_ORIGIN)).isFalse();
  }

  @Test
  void theSettingIsReadOnceAndNullIsNeverTrusted() {
    ObjectProvider<SettingStore> provider = store("{\"allowedOrigins\":[\"https://inspector.example\",\"null\"]}");
    AllowedOrigins origins = new AllowedOrigins(provider, bootstrap(false));

    assertThat(origins.allows("https://inspector.example")).isTrue();
    assertThat(origins.allows("https://inspector.example:8443")).isFalse();
    assertThat(origins.allows("null")).isFalse();
    verify(provider.getObject(), times(1)).find(AllowedOrigins.SETTING);
  }

  @Test
  void devRunsAllowNgServe() {
    assertThat(new AllowedOrigins(store(null), bootstrap(true)).allows(AllowedOrigins.DEV_ORIGIN)).isTrue();
  }
}
