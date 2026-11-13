package com.gaterook.core.platform.mask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The budget of a value: every read and every attempt is paid for, and the last unit is the last. */
class WorkTest {

  @Test
  void everyReadCostsOneUnitUntilTheBudgetIsSpent() {
    Work work = new Work("abcdef", 3);

    assertThat(work.charAt(0)).isEqualTo('a');
    assertThat(work.charAt(1)).isEqualTo('b');
    assertThat(work.charAt(2)).isEqualTo('c');
    assertThat(work.used()).isEqualTo(3);
    assertThat(work.spent()).isFalse();
    assertThatThrownBy(() -> work.charAt(3)).isInstanceOf(Work.Spent.class);
    assertThat(work.spent()).isTrue();
    assertThat(work.used()).as("never more than the budget").isEqualTo(3);
  }

  @Test
  void anAttemptIsPaidInAdvanceOrNotMade() {
    Work work = new Work("abcdef", 20);

    work.charge(Work.ATTEMPT);
    assertThat(work.used()).isEqualTo(16);
    assertThatThrownBy(() -> work.charge(Work.ATTEMPT)).isInstanceOf(Work.Spent.class);
    assertThat(work.spent()).isTrue();
  }

  @Test
  void aRegexReadingThroughTheViewIsStoppedWhereTheBudgetEnds() {
    Work work = new Work("a".repeat(200_000), 50_000);

    assertThatThrownBy(() -> Pattern.compile("a*b").matcher(work).lookingAt()).isInstanceOf(Work.Spent.class);
    assertThat(work.used()).isEqualTo(50_000);
    assertThat(work.spent()).isTrue();
  }

  @Test
  void theBudgetOfAValueIsABasePlusAShareOfEveryCharacter() {
    Work work = Work.forValue("x".repeat(10));
    long budget = Work.BASE + 10 * Work.PER_CHAR;

    work.charge(budget);

    assertThat(work.used()).isEqualTo(budget);
    assertThat(work.spent()).isFalse();
    assertThatThrownBy(() -> work.charge(1)).isInstanceOf(Work.Spent.class);
    assertThat(Work.forValue("").used()).isZero();
    assertThat(Work.counting("x").charAt(0)).isEqualTo('x');
  }
}
