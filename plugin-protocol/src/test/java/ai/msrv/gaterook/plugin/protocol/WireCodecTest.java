package ai.msrv.gaterook.plugin.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Limits;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class WireCodecTest {

  @Test
  void anUnrestrictedLogRangeRoundTripsThroughNumericZero() {
    Limits original = limits(null);
    var wire = WireCodec.limits(original);
    assertThat(wire.path("maxRangeMs").asLong()).isZero();
    assertThat(WireCodec.limits(wire)).isEqualTo(original);
  }

  @Test
  void missingOrNullLogRangesUseTheUnrestrictedDefault() {
    var wire = WireCodec.limits(limits(Duration.ofHours(24)));
    wire.remove("maxRangeMs");
    assertThat(WireCodec.limits(wire).maxRange()).isNull();
    wire.putNull("maxRangeMs");
    assertThat(WireCodec.limits(wire).maxRange()).isNull();
  }

  @Test
  void explicitLogRangesPreserveTheirExistingWireValues() {
    for (long hours : new long[] {24, 168, 17_520, 2_562_047_788_015L}) {
      Limits original = limits(Duration.ofHours(hours));
      var wire = WireCodec.limits(original);
      assertThat(wire.path("maxRangeMs").asLong()).isEqualTo(hours * 3_600_000);
      assertThat(WireCodec.limits(wire)).isEqualTo(original);
    }
  }

  private static Limits limits(Duration range) {
    return new Limits(1000, 1000, 524_288, 1_048_576, 262_144, 65_536, 8192, Duration.ofSeconds(5),
        Duration.ofSeconds(30), Duration.ofSeconds(10), range);
  }
}
