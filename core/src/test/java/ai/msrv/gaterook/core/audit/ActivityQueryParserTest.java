package ai.msrv.gaterook.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.core.platform.ProblemException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The Activity query language as the kit's log-explorer writes it; its SQL runs against rows in AuditIT. */
class ActivityQueryParserTest {

  @Test
  void theSameFieldTwiceIsOrAndDifferentFieldsAreAnd() {
    ActivityQueryParser.Query q = ActivityQueryParser.parse("decision:denied decision:rejected env:prod -rule:"
        + "prod.sql.block_ddl");
    assertThat(q.clauses()).containsExactly(
        new ActivityQueryParser.Clause("decision", List.of("denied", "rejected"), List.of()),
        new ActivityQueryParser.Clause("env", List.of("prod"), List.of()),
        new ActivityQueryParser.Clause("rule", List.of(), List.of("prod.sql.block_ddl")));
    assertThat(q.words()).isEmpty();
    assertThat(q).hasToString("decision:denied decision:rejected env:prod -rule:prod.sql.block_ddl");
  }

  @Test
  void wordsQuotesWildcardsAndPrefixes() {
    ActivityQueryParser.Query q = ActivityQueryParser.parse("  tool:\"sql_*\"  trace:4f2a9c  orders   \"cache redis\" ");
    assertThat(q.clauses()).containsExactly(
        new ActivityQueryParser.Clause("tool", List.of("sql_*"), List.of()),
        new ActivityQueryParser.Clause("trace", List.of("4f2a9c"), List.of()));
    assertThat(q.words()).containsExactly("orders", "cache redis");
    assertThat(q).hasToString("tool:sql_* trace:4f2a9c orders \"cache redis\"");
    assertThat(ActivityQueryParser.parse("").isEmpty()).isTrue();
    assertThat(ActivityQueryParser.parse(null).isEmpty()).isTrue();
    assertThat(ActivityQueryParser.parse("tool:\"\"").isEmpty()).isTrue();
  }

  @Test
  void durationsCompareInMilliseconds() {
    assertThat(ActivityQueryParser.durationMs(">1s")).containsExactly(">", 1_000L);
    assertThat(ActivityQueryParser.durationMs("<=200ms")).containsExactly("<=", 200L);
    assertThat(ActivityQueryParser.durationMs(">=2m")).containsExactly(">=", 120_000L);
    assertThat(ActivityQueryParser.durationMs("1.5s")).containsExactly("=", 1_500L);
    assertThat(ActivityQueryParser.durationMs("<1h")).containsExactly("<", 3_600_000L);
    assertThat(ActivityQueryParser.parse("duration:>1s").clauses().getFirst().values()).containsExactly(">1s");
  }

  @Test
  void decisionsAreTheKitsWords() {
    for (String d : List.of("allowed", "approved", "pending", "denied", "rejected", "expired", "error", "DENIED")) {
      assertThat(ActivityQueryParser.decision(d)).as(d).isNotNull();
    }
    assertThat(ActivityQueryParser.decision("blocked")).isNull();
  }

  @Test
  void unknownFieldsAndBadValuesAre422AtQ() {
    assertThatThrownBy(() -> ActivityQueryParser.parse("status:denied")).isInstanceOf(ProblemException.class)
        .hasMessageContaining("status isn't a filter. Use one of: client, connection, decision, duration, endpoint, "
            + "env, rule, runbook, token, tool, trace.");
    assertThatThrownBy(() -> ActivityQueryParser.parse("decision:blocked")).isInstanceOf(ProblemException.class)
        .hasMessageContaining("blocked isn't a decision");
    assertThatThrownBy(() -> ActivityQueryParser.parse("env:qa")).isInstanceOf(ProblemException.class)
        .hasMessageContaining("qa isn't dev, stage or prod.");
    assertThatThrownBy(() -> ActivityQueryParser.parse("duration:fast")).isInstanceOf(ProblemException.class)
        .hasMessageContaining("Write a duration like >1s");
  }
}
