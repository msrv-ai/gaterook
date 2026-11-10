package ai.msrv.gaterook.connectors.ssh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;

import ai.msrv.gaterook.connector.api.ConnectorException;
import com.google.re2j.Pattern;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The size an RE2 expression compiles to, estimated before RE2/J expands its counted repetitions: never below what
 * RE2/J really compiles, realistic log searches far inside the budget, and counts nested inside counts refused before
 * they cost memory.
 */
class RegexBudgetTest {

  /** What RE2/J compiled {@code expression} to, read from its program (package-private in RE2/J). */
  private static int compiled(String expression) throws ReflectiveOperationException {
    Pattern pattern = Pattern.compile(expression);
    Method re2 = Pattern.class.getDeclaredMethod("re2");
    re2.setAccessible(true);
    Object compiled = re2.invoke(pattern);
    Field prog = compiled.getClass().getDeclaredField("prog");
    prog.setAccessible(true);
    Object program = prog.get(compiled);
    Method numInst = program.getClass().getDeclaredMethod("numInst");
    numInst.setAccessible(true);
    return (int) numInst.invoke(program);
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "error",
      "ERROR [0-9]+",
      "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}",
      "(?:[0-9]{1,3}\\.){3}[0-9]{1,3}",
      "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
      "(?i)timeout|refused|reset by peer|broken pipe",
      "^\\s*(WARN|ERROR|FATAL)\\b.*$",
      "\\w{1,1000}",
      "(?:\\w+\\s+){1,50}failed",
      "(a{10}){10}",
      "((ab){3,5}c?){2,4}",
      "(?P<code>[45]\\d\\d) (?<path>/\\S*)",
      "\\Qa.b*c\\E{3}",
      "[]a-z]{2,}x{4,}",
      "[[:alpha:]_]{1,20}",
      "\\p{Greek}+\\x{263a}{2}\\pL",
      "a{,3}b{x}c{01}",
      "(?s:.{100})(?m:^$)"})
  void theEstimateIsNeverBelowWhatRe2Compiles(String expression) throws ReflectiveOperationException {
    assertThat(RegexBudget.instructions(expression)).as(expression).isGreaterThanOrEqualTo(compiled(expression));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}",
      "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
      "\\w{1,1000}",
      ".{1000}",
      "(?:\\w+\\s+){1,50}failed"})
  void realisticSearchesStayFarInsideTheBudget(String expression) {
    assertThat(RegexBudget.instructions(expression)).as(expression)
        .isLessThan(RegexBudget.MAX_INSTRUCTIONS / 2);
    assertThatNoException().isThrownBy(() -> RegexBudget.check(expression));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "(x{1000}){1000}",
      "((x{100}){100}){100}",
      "(?:(?:x{30}){30}){30}",
      "(x{1000})(x{1000})(x{1000})(x{1000})(x{1000})(x{1000})(x{1000})(x{1000})(x{1000})(x{1000})(x{1000})",
      "((((((((((x{1000}){1000}){1000}){1000}){1000}){1000}){1000}){1000}){1000}){1000})"})
  void countsInsideCountsAreRefusedBeforeTheyAreExpanded(String expression) {
    assertThatThrownBy(() -> RegexBudget.check(expression)).as(expression).isInstanceOf(ConnectorException.class)
        .hasMessageContaining("too large once its repetitions are expanded");
  }
}
