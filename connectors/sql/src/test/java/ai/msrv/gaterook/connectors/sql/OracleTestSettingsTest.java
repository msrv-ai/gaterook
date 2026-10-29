package ai.msrv.gaterook.connectors.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class OracleTestSettingsTest {
  private static final Map<String, String> COMPOSE = Map.of("GW_ORACLE_PORT", "55521",
      "GW_ORACLE_SERVICE", "FREEPDB1", "GW_ORACLE_USER", "GATEROOK_TEST", "GW_ORACLE_PASSWORD", "test-password");
  private static final Function<String, String> NO_COMPOSE = key -> {
    throw new AssertionError("External Oracle settings must not read the Compose file");
  };

  @Test
  void composeUsesItsSharedFileAndLoopbackWithoutExternalVariables() {
    OracleTestSettings settings = OracleTestSettings.resolve(Map.of(), true, COMPOSE::get);
    assertThat(settings.host()).isEqualTo("127.0.0.1");
    assertThat(settings.port()).isEqualTo(55521);
    assertThat(settings.service()).isEqualTo("FREEPDB1");
    assertThat(settings.user()).isEqualTo("GATEROOK_TEST");
    assertThat(settings.password()).isEqualTo("test-password");
  }

  @Test
  void explicitHostOverridesComposeAsACompleteExternalTarget() {
    OracleTestSettings settings = OracleTestSettings.resolve(external(), true, NO_COMPOSE);
    assertThat(settings.host()).isEqualTo("oracle.example.test");
    assertThat(settings.port()).isEqualTo(1521);
    assertThat(settings.service()).isEqualTo("test.example.test");
    assertThat(settings.user()).isEqualTo("EXTERNAL_TEST");
    assertThat(settings.password()).isEqualTo("external-test-password");
  }

  @Test
  void incompleteExternalTargetDoesNotFallBackToComposeCredentials() {
    Map<String, String> environment = new HashMap<>(external());
    environment.remove("GW_ORACLE_PASSWORD");
    assertThatThrownBy(() -> OracleTestSettings.resolve(environment, true, NO_COMPOSE))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("GW_ORACLE_PASSWORD");
  }

  @Test
  void servicesEnabledRequiresOracleSettingsInsteadOfSilentlySkipping() {
    assertThatThrownBy(() -> OracleTestSettings.resolve(Map.of(), true, Map.<String, String>of()::get))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("GW_ORACLE_PORT");
    assertThatThrownBy(() -> OracleTestSettings.resolve(Map.of(), false, NO_COMPOSE))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("-Pservices");
  }

  @Test
  void externalPortIsExplicitAndValidatedWithoutLeakingThePassword() {
    Map<String, String> environment = new HashMap<>(external());
    environment.put("GW_ORACLE_PORT", "31521");
    OracleTestSettings settings = OracleTestSettings.resolve(environment, false, NO_COMPOSE);
    assertThat(settings.port()).isEqualTo(31521);
    assertThat(settings.toString()).doesNotContain(settings.password()).contains("<redacted>");
    for (String invalid : new String[] {"0", "65536", "", "port"}) {
      environment.put("GW_ORACLE_PORT", invalid);
      assertThatThrownBy(() -> OracleTestSettings.resolve(environment, false, NO_COMPOSE))
          .isInstanceOf(IllegalStateException.class).hasMessageContaining("GW_ORACLE_PORT");
    }
  }

  private static Map<String, String> external() {
    return Map.of("GW_ORACLE_HOST", "oracle.example.test", "GW_ORACLE_SERVICE", "test.example.test",
        "GW_ORACLE_USER", "EXTERNAL_TEST", "GW_ORACLE_PASSWORD", "external-test-password");
  }
}
