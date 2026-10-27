package ai.msrv.gaterook.connectors.aws;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Metric math allowlist (report 09 §3.5 "Expression grammar"): arithmetic over the call's ids and the math
 * functions passes; anything that reads or runs outside the call — LAMBDA, SEARCH, SELECT … FROM SCHEMA, Insights,
 * Performance Insights, quotas — another call's id, strings outside METRICS("…") and non-ASCII look-alikes don't.
 */
class ExpressionGuardTest {

  private static final Set<String> IDS = Set.of("m1", "m2", "errors", "e1");

  @ParameterizedTest(name = "allowed: {0}")
  @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
      m1 + m2
      100 * m2 / m1
      (m1 - m2) ^ 2
      SUM(METRICS())
      AVG(METRICS("err"))
      SUM([m1, m2])
      FILL(m1, REPEAT)
      FILL(m1, LINEAR)
      FILL(m1, 0)
      SORT(METRICS(), AVG, DESC)
      SLICE(SORT(METRICS(), MAX, DESC), 0, 3)
      IF(errors > 10 AND m1 >= 1, 1, 0)
      IF(m1 != 0, errors / m1, 0)
      RATE(m1) * PERIOD(m1)
      ANOMALY_DETECTION_BAND(m1, 2)
      RUNNING_SUM(m1)
      REMOVE_EMPTY(METRICS())
      DIFF_TIME(m1) / 60
      TIME_SERIES(1.5e3)
      LOG10(m1) + LOG(m2) + ABS(e1)
      metrics("m")
      """)
  void allowed(String expression) {
    assertThatCode(() -> ExpressionGuard.check(expression, IDS)).doesNotThrowAnyException();
  }

  @ParameterizedTest(name = "refused: {0}")
  @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
      LAMBDA("orders-fn", m1)                                           | LAMBDA
      lambda("orders-fn")                                               | LAMBDA
      SEARCH('{AWS/EC2,InstanceId} MetricName="CPUUtilization"', 'Average', 300) | SEARCH
      SEARCH("{AWS/EC2} CPUUtilization", "Average", 300)                | SEARCH
      SELECT AVG(CPUUtilization) FROM SCHEMA("AWS/EC2", InstanceId)      | SELECT
      INSIGHT_RULE_METRIC("rule", "UniqueContributors")                | INSIGHT_RULE_METRIC
      DB_PERF_INSIGHTS("RDS", "db-ABC", "os.cpuUtilization.total.avg")  | DB_PERF_INSIGHTS
      SERVICE_QUOTA(m1)                                                 | SERVICE_QUOTA
      m1 + m9                                                           | neither an id
      ABS                                                               | is a function
      m1(2)                                                             | neither an id
      UNKNOWN_FN(m1)                                                    | neither an id
      "x"                                                               | only as the argument of METRICS
      SUM(METRICS("a", "b"))                                            | only as the argument of METRICS
      METRICS("a{b}")                                                   | may hold letters
      m1 + m2;                                                          | isn't allowed
      m1 & m2                                                           | isn't allowed
      (m1 + m2                                                          | Unbalanced
      m1 + m2)                                                          | Unbalanced
      ' '                                                               | empty
      1x                                                                | isn't a number
      """)
  void refused(String expression, String why) {
    assertThatThrownBy(() -> ExpressionGuard.check(expression.equals("' '") ? " " : expression, IDS))
        .isInstanceOf(ExpressionGuard.Rejected.class).hasMessageContaining(why);
  }

  @org.junit.jupiter.api.Test
  void lookAlikesAndControlCharactersAreRefused() {
    // Cyrillic U+0430 in the m-a-1 identifier, a right-to-left override, a zero-width space: what the person reads isn't what runs.
    for (String e : new String[] {"mа1 + m2", "m1 + m2‮", "m1​+ m2", "m1\n+ m2"}) {
      assertThatThrownBy(() -> ExpressionGuard.check(e, IDS)).isInstanceOf(ExpressionGuard.Rejected.class)
          .hasMessageContaining("isn't allowed");
    }
  }
}
