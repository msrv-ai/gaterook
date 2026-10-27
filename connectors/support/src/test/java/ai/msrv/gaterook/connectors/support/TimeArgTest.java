package ai.msrv.gaterook.connectors.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class TimeArgTest {

  private static final Instant NOW = Instant.parse("2026-10-22T12:30:00Z");

  @ParameterizedTest
  @CsvSource({
      "now,                       2026-10-22T12:30:00Z",
      "-15m,                      2026-10-22T12:15:00Z",
      "now-1h,                    2026-10-22T11:30:00Z",
      "-2d,                       2026-10-20T12:30:00Z",
      "-1w,                       2026-10-15T12:30:00Z",
      "-90s,                      2026-10-22T12:28:30Z",
      "2026-10-22T14:00:00+02:00, 2026-10-22T12:00:00Z",
      "2026-10-22T12:00:00Z,      2026-10-22T12:00:00Z",
      "2026-10-22T12:00:00.123456789Z, 2026-10-22T12:00:00.123456789Z",
  })
  void parsesAbsoluteAndRelativeTimes(String text, String expected) {
    assertThat(TimeArg.parse(text, NOW)).isEqualTo(Instant.parse(expected));
  }

  @Test
  void blankMeansNow() {
    assertThat(TimeArg.parse(null, NOW)).isEqualTo(NOW);
    assertThat(TimeArg.parse("  ", NOW)).isEqualTo(NOW);
  }

  @ParameterizedTest
  @ValueSource(strings = {"1729600000", "1729600000000", "2026-10-22T12:00:00", "yesterday", "-1y", "+1h", "now+1h",
      "-1234567m", "2026-13-01T00:00:00Z"})
  void refusesAmbiguousOrBrokenTimes(String text) {
    assertThatThrownBy(() -> TimeArg.parse(text, NOW)).isInstanceOf(TimeArg.InvalidTimeException.class)
        .hasMessageContaining("ISO-8601");
  }

  @Test
  void checksTheRangeAgainstThePolicy() {
    TimeArg.Range range = TimeArg.range("-1h", "now", NOW, Duration.ofHours(24));
    assertThat(range.length()).isEqualTo(Duration.ofHours(1));
    assertThatThrownBy(() -> TimeArg.range("now", "-1h", NOW, null)).hasMessageContaining("before");
    assertThatThrownBy(() -> TimeArg.range("-2d", "now", NOW, Duration.ofHours(24)))
        .isInstanceOf(TimeArg.InvalidTimeException.class).hasMessageContaining("at most 24 h");
    assertThatThrownBy(() -> TimeArg.range("-8d", "now", NOW, Duration.ofDays(7)))
        .hasMessageContaining("at most 7 days");
  }
}
