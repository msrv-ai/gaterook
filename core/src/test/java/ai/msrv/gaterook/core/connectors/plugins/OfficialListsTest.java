package ai.msrv.gaterook.core.connectors.plugins;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Which run reads GateRook's published lists (docs/connector-delivery.md): every one, a development build as a
 * release does, so that what GateRook publishes shows up by itself. Only a run that says so reads none, as the tests
 * of this repository do.
 */
class OfficialListsTest {

  @Test
  void everyRunReadsThePublishedListsUnlessItSaysNone() {
    assertThat(OfficialLists.inUse(null)).as("nothing said").isTrue();
    assertThat(OfficialLists.inUse("")).isTrue();
    assertThat(OfficialLists.inUse("official")).as("the word an older launcher passed").isTrue();
    assertThat(OfficialLists.inUse("none")).isFalse();
    assertThat(OfficialLists.inUse(" None ")).isFalse();
  }

  @Test
  void theListIsTheDistributionRepositorysUnderGateRooksKey() {
    assertThat(OfficialLists.MODULES_URL)
        .isEqualTo("https://raw.githubusercontent.com/gaterook/gaterook-plugins/main/modules/");
    assertThat(OfficialLists.MODULES_KEYS).startsWith("modules-2026-1=");
  }
}
