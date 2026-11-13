package com.gaterook.connectors.cosmosdb;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Which queries the gateway may continue page by page: none that holds a word needing state, wherever it is. */
class CosmosSqlTest {

  @Test
  void plainProjectionsAndFiltersPage() {
    for (String q : List.of("SELECT * FROM c", "SELECT c.id, c.order, c.top, c.limit FROM c WHERE c.count > 1",
        "SELECT * FROM c WHERE c.status IN ('ORDER BY', \"TOP 1\", 'COUNT(1)') AND c.note = 'it\\'s DISTINCT'",
        "SELECT VALUE c FROM c JOIN t IN c.tags WHERE t = @group AND c[\"order\"] = @top",
        "SELECT * FROM c WHERE STARTSWITH(c.name, 'max(') AND udf.count(c) > 0",
        "SELECT * FROM orders o WHERE o.minimum > 2 AND o.maximum < 3 AND o.summary = 'x'")) {
      CosmosSql.Shape s = CosmosSql.shape(q);
      assertThat(s.pageable()).as(q).isTrue();
      assertThat(s.aggregates()).as(q).isFalse();
    }
  }

  @Test
  void everyWordThatNeedsStateIsFound() {
    Map<String, Set<String>> cases = Map.of(
        "SELECT TOP 10 * FROM c", Set.of("TOP"),
        "select * from c order by c._ts desc", Set.of("ORDER BY"),
        "SELECT * FROM c OFFSET 10 LIMIT 5", Set.of("OFFSET", "LIMIT"),
        "SELECT DISTINCT VALUE c.status FROM c", Set.of("DISTINCT"),
        "SELECT c.status, COUNT(1) AS n FROM c GROUP BY c.status", Set.of("COUNT(…)", "GROUP BY"),
        "SELECT VALUE sum (c.total) FROM c", Set.of("SUM(…)"),
        "SELECT AVG(c.x), MIN(c.x), MAX(c.x) FROM c", Set.of("AVG(…)", "MIN(…)", "MAX(…)"),
        "SELECT c.id, ARRAY_LENGTH(c.t) FROM c WHERE EXISTS(SELECT VALUE COUNT(1) FROM t IN c.tags)",
        Set.of("COUNT(…)"),
        "SELECT * FROM c ORDER BY VectorDistance(c.v, @q)", Set.of("ORDER BY"),
        "SELECT * FROM c ORDER BY RANK FullTextScore(c.text, 'red')", Set.of("ORDER BY"));
    cases.forEach((q, words) -> assertThat(CosmosSql.shape(q).features()).as(q).isEqualTo(words));
    assertThat(CosmosSql.shape("SELECT VALUE COUNT(1) FROM c").aggregates()).isTrue();
    assertThat(CosmosSql.shape("SELECT c.a FROM c GROUP BY c.a").aggregates()).isTrue();
    assertThat(CosmosSql.shape("SELECT TOP 1 * FROM c ORDER BY c.a").aggregates()).isFalse();
  }

  @Test
  void whatCantBeReadWithCertaintyNeverPages() {
    for (String q : List.of("SELECT * FROM c WHERE c.a = 'open", "SELECT * FROM c -- it's\nWHERE c.a = 1",
        "SELECT * FROM c /* ORDER BY", "SELECT * /* x */ FROM c")) {
      CosmosSql.Shape s = CosmosSql.shape(q);
      assertThat(s.pageable()).as(q).isFalse();
      assertThat(s.aggregates()).as(q).isTrue();
    }
  }

  @Test
  void aQueryIsOneSelectOfBoundedText() {
    assertThat(CosmosSql.problem("SELECT * FROM c")).isNull();
    assertThat(CosmosSql.problem("\n  (SELECT VALUE 1)")).isNull();
    assertThat(CosmosSql.problem("-- comment\nSELECT * FROM c")).isNull();
    assertThat(CosmosSql.problem("UPDATE c SET c.a = 1")).contains("only reads");
    assertThat(CosmosSql.problem("/* SELECT */ DELETE FROM c")).contains("only reads");
    assertThat(CosmosSql.problem("SELECT \u0001")).contains("control character");
    assertThat(CosmosSql.problem("SELECT " + "x".repeat(CosmosSql.MAX_LENGTH))).contains("longer than");
    assertThat(CosmosSql.problem("")).contains("empty");
  }
}
