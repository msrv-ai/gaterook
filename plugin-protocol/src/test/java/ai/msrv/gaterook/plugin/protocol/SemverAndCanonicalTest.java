package ai.msrv.gaterook.plugin.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SemverAndCanonicalTest {

  @Test
  void rangesOfRequiresHoldAsWritten() {
    Semver gateway = Semver.parse("0.4.0");
    assertThat(Semver.satisfies(gateway, ">=0.4.0 & <1.0.0")).isTrue();
    assertThat(Semver.satisfies(gateway, ">=0.9.0")).isFalse();
    assertThat(Semver.satisfies(gateway, "<0.3.0 | >=0.4.0 & <0.5.0")).isTrue();
    assertThat(Semver.satisfies(gateway, "*")).isTrue();
    assertThat(Semver.satisfies(Semver.parse("1.0.0"), ">=0.4.0 & <1.0.0")).isFalse();
    // A development build of 0.4.0 is older than 0.4.0 by semver; the core compares its release().
    assertThat(Semver.satisfies(Semver.parse("0.4.0-SNAPSHOT"), ">=0.4.0")).isFalse();
    assertThat(Semver.satisfies(Semver.parse("0.4.0-SNAPSHOT").release(), ">=0.4.0")).isTrue();
    assertThat(Semver.validRange(">=banana")).isFalse();
    assertThat(Semver.validRange("")).isTrue();
    assertThatThrownBy(() -> Semver.parse("1.2")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void preReleasesOrderBySemverRules() {
    assertThat(Semver.parse("1.0.0-alpha").compareTo(Semver.parse("1.0.0-alpha.1"))).isNegative();
    assertThat(Semver.parse("1.0.0-alpha.1").compareTo(Semver.parse("1.0.0-beta"))).isNegative();
    assertThat(Semver.parse("1.0.0-beta.2").compareTo(Semver.parse("1.0.0-beta.11"))).isNegative();
    assertThat(Semver.parse("1.0.0-rc.1").compareTo(Semver.parse("1.0.0"))).isNegative();
    assertThat(Semver.parse("1.2.0+build.7").compareTo(Semver.parse("1.2.0"))).isZero();
  }

  @Test
  void canonicalJsonSortsMembersAtEveryDepth() {
    String a = Canonical.text(GppJson.MAPPER.readTree("{\"b\":1,\"a\":{\"z\":[{\"y\":1,\"x\":2}],\"c\":null}}"));
    assertThat(a).isEqualTo("{\"a\":{\"c\":null,\"z\":[{\"x\":2,\"y\":1}]},\"b\":1}");
    assertThat(Canonical.sha256Hex(GppJson.MAPPER.readTree("{\"b\":1,\"a\":2}")))
        .isEqualTo(Canonical.sha256Hex(GppJson.MAPPER.readTree("{\"a\":2,\"b\":1}")));
  }
}
