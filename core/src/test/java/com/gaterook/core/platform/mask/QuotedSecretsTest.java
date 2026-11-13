package com.gaterook.core.platform.mask;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Quoted values of the {@code sql-password} rule: matched without a stack depth that grows with the value, closed
 * values masked exactly as before, and a value whose closing quote is missing never left readable.
 */
class QuotedSecretsTest {

  private static final String MASK = "‹masked›";

  /** Small enough that a regular expression recursing once per character fails long before the window's end. */
  private static final long SMALL_STACK = 128 * 1024;

  private static int window() {
    return SecretRules.ALL.stream().filter(rule -> rule.id().equals("sql-password")).findFirst().orElseThrow()
        .after();
  }

  /** {@code task}'s answer on a thread with a small stack; whatever it throws fails the test with that cause. */
  private static String onSmallStack(Supplier<String> task) throws InterruptedException {
    AtomicReference<Object> out = new AtomicReference<>();
    Thread thread = new Thread(null, () -> {
      try {
        out.set(task.get());
      } catch (Throwable e) {
        out.set(e);
      }
    }, "small-stack", SMALL_STACK);
    thread.start();
    thread.join();
    if (out.get() instanceof Throwable e) {
      throw new AssertionError("masking failed on a " + SMALL_STACK / 1024 + " KiB stack", e);
    }
    return (String) out.get();
  }

  static Stream<Arguments> quotes() {
    return Stream.of(
        Arguments.of("single quotes", "'", "xyzw"),
        Arguments.of("double quotes", "\"", "xyzw"),
        Arguments.of("single quotes, doubled inside", "'", "ab''cd"),
        Arguments.of("double quotes, doubled inside", "\"", "ab\"\"cd"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("quotes")
  void aLongQuotedValueNeedsNoDeepStack(String name, String quote, String unit) throws InterruptedException {
    // As long as the keyword's window allows: the value, both quotes and "PASSWORD " end just inside it.
    String value = unit.repeat((window() - 20) / unit.length());
    String text = "ALTER USER app PASSWORD " + quote + value + quote;

    String out = onSmallStack(() -> SecretRules.apply(text, MASK, new MaskCounter()));

    assertThat(out).isEqualTo("ALTER USER app " + "PASSWORD " + MASK);
  }

  @Test
  void aClosedValueIsMaskedUpToItsClosingQuoteAndNoFurther() {
    String text = "ALTER ROLE app PASSWORD 'n3w-p@ss''word' VALID UNTIL '2027-01-01'";

    assertThat(SecretRules.apply(text, MASK, new MaskCounter()))
        .isEqualTo("ALTER ROLE app PASSWORD " + MASK + " VALID UNTIL '2027-01-01'");
  }

  @Test
  void aValueWithoutAClosingQuoteIsMaskedToTheEndOfTheText() {
    // A statement cut off inside its literal, as in a truncated log line. The doubled quote must not end the mask.
    String text = "CREATE USER gw IDENTIFIED BY 'half''of-the-secret";

    assertThat(SecretRules.apply(text, MASK, new MaskCounter())).isEqualTo("CREATE USER gw IDENTIFIED BY " + MASK);
  }

  @Test
  void aValueThatIsStillOpenAtTheEndOfItsWindowIsMaskedToThere() throws InterruptedException {
    String keyword = "password";
    String text = "set " + keyword + " '" + "s".repeat(2 * window()) + "' done";
    int windowEnd = text.indexOf(keyword) + window();

    String out = onSmallStack(() -> SecretRules.apply(text, MASK, new MaskCounter()));

    // Everything from the opening quote to the window's end is covered; what lies beyond the window is outside every
    // rule's reach, as for any value longer than its rule's window.
    assertThat(out).isEqualTo("set " + keyword + " " + MASK + text.substring(windowEnd));
  }
}
