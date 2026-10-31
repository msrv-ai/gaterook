package ai.msrv.gaterook.core.mcp.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RequestBudgetTest {

  /** A clock the test moves. */
  private static final class Moving extends Clock {
    Instant now = Instant.parse("2026-10-31T12:00:00Z");

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  @Test
  void aPrincipalsBodiesCountForAnHourAndARefusedOneIsNotCounted() {
    Moving clock = new Moving();
    RequestBudget budget = new RequestBudget(1_000, clock);

    assertThat(budget.charge("tok_a", 600)).isTrue();
    assertThat(budget.charge("tok_a", 500)).as("over by 100").isFalse();
    assertThat(budget.charge("tok_a", 400)).as("the refused 500 weren't counted").isTrue();
    assertThat(budget.charge("tok_a", 1)).isFalse();
    assertThat(budget.charge("tok_b", 1_000)).as("another principal has its own").isTrue();
    assertThat(budget.charge("tok_b", 1)).isFalse();

    clock.now = clock.now.plus(Duration.ofMinutes(59));
    assertThat(budget.charge("tok_a", 1)).as("still the same hour").isFalse();
    clock.now = clock.now.plus(Duration.ofMinutes(1));
    assertThat(budget.charge("tok_a", 1_000)).as("a new hour starts clean").isTrue();
  }

  @Test
  void theDefaultIsThirtyTwoMebibytesAnHour() {
    RequestBudget budget = new RequestBudget(RequestBudget.DEFAULT_BYTES_PER_HOUR, Clock.systemUTC());
    assertThat(budget.bytesPerHour()).isEqualTo(32L << 20);
    assertThat(budget.charge("tok_a", 1 << 20)).isTrue();
  }
}
