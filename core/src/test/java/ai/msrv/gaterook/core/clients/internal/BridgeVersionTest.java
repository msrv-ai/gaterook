package ai.msrv.gaterook.core.clients.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Which extension bridge is older than the app (Semantic Versioning 2.0 precedence): only those get the Reinstall
 * hint. The same version, a newer one and anything that isn't a semantic version never do.
 */
class BridgeVersionTest {

  @ParameterizedTest(name = "{0} is older than {1}")
  @CsvSource({
      "0.4.3, 0.5.0",
      "0.4.9, 0.4.10",
      "0.9.9, 1.0.0",
      "0.5.0-SNAPSHOT, 0.5.0",
      "1.0.0-alpha, 1.0.0-alpha.1",
      "1.0.0-alpha.1, 1.0.0-alpha.beta",
      "1.0.0-alpha.beta, 1.0.0-beta",
      "1.0.0-beta.2, 1.0.0-beta.11",
      "1.0.0-beta.11, 1.0.0-rc.1",
      "1.0.0-rc.1, 1.0.0",
      "0.4.3+build.9, 0.5.0",
  })
  void anOlderBridgeIsOlder(String reported, String current) {
    assertThat(BridgeVersion.older(reported, current)).isTrue();
    assertThat(BridgeVersion.older(current, reported)).as("and the other way round").isFalse();
  }

  @ParameterizedTest(name = "{0} against {1}")
  @CsvSource({
      "0.5.0, 0.5.0",
      "0.5.0-SNAPSHOT, 0.5.0-SNAPSHOT",
      "0.5.0+build.7, 0.5.0",
      "0.5.0, 0.5.0+build.8",
  })
  void theSameVersionIsCurrent(String reported, String current) {
    assertThat(BridgeVersion.older(reported, current)).isFalse();
    assertThat(BridgeVersion.compare(reported, current)).isZero();
  }

  @Test
  void aVersionThatIsMissingOrNotSemanticIsNeverOlder() {
    for (String reported : new String[] {null, "", "dev", "0.4", "0.4.3.1", "00.4.3", "0.4.3-", "0.4.3-01", "v0.4.3"}) {
      assertThat(BridgeVersion.older(reported, "0.5.0")).as(String.valueOf(reported)).isFalse();
      assertThat(BridgeVersion.compare(reported, "0.5.0")).as(String.valueOf(reported)).isNull();
    }
    assertThat(BridgeVersion.older("0.4.3", null)).isFalse();
    assertThat(BridgeVersion.older("0.4.3", "dev")).isFalse();
  }

  @Test
  void numbersAreComparedByValueNotByText() {
    assertThat(BridgeVersion.older("2.0.0", "10.0.0")).isTrue();
    assertThat(BridgeVersion.older("0.10.0", "0.9.0")).isFalse();
    assertThat(BridgeVersion.older("99999999999999999999.0.0", "100000000000000000000.0.0")).isTrue();
  }
}
