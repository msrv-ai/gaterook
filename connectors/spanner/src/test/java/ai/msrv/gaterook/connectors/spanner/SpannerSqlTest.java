package ai.msrv.gaterook.connectors.spanner;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connectors.spanner.SpannerSql.Kind;
import org.junit.jupiter.api.Test;

/** The route of a statement in either dialect, read without I/O. */
class SpannerSqlTest {

  private static SpannerSql.Statement read(String sql) {
    return SpannerSql.read(sql);
  }

  @Test
  void queriesOfBothDialectsAreReads() {
    for (String q : new String[] {"SELECT * FROM Orders", "  with t AS (SELECT 1) SELECT * FROM t",
        "FROM Orders |> WHERE id > 3 |> SELECT id", "GRAPH FinGraph MATCH (a:Account) RETURN a.id",
        "(SELECT 1) UNION ALL (SELECT 2)", "@{USE_ADDITIONAL_PARALLELISM=TRUE} SELECT 1", "/*@ FORCE_INDEX=_BASE_TABLE */"
        + " SELECT 1", "SELECT 'a;b' AS x; ", "select $$ ; DELETE $$, $tag$ x $tag$ FROM t WHERE id = $1",
        "SELECT \"Quoted\".id FROM \"Quoted\"", "-- note\nSELECT 1 # trailing", "TABLE orders"}) {
      assertThat(read(q).kind()).as(q).isEqualTo(Kind.READ);
    }
  }

  @Test
  void dmlAndDdlNameWhatTheyChange() {
    assertThat(read("INSERT INTO Orders (id) VALUES (1)")).extracting(SpannerSql.Statement::kind,
        SpannerSql.Statement::type, SpannerSql.Statement::target).containsExactly(Kind.DML, "INSERT", "Orders");
    assertThat(read("INSERT OR UPDATE Orders (id) VALUES (1)").target()).isEqualTo("Orders");
    assertThat(read("update \"public\".\"orders\" set note = 'x' where id = 1 RETURNING id").target())
        .isEqualTo("\"public\".\"orders\"");
    assertThat(read("DELETE FROM sales.Orders WHERE true").target()).isEqualTo("sales.Orders");
    assertThat(read("CREATE TABLE Orders (id INT64) PRIMARY KEY (id)")).extracting(SpannerSql.Statement::kind,
        SpannerSql.Statement::target).containsExactly(Kind.DDL, "Orders");
    assertThat(read("CREATE UNIQUE NULL_FILTERED INDEX ByNote ON Orders(note)").target()).isEqualTo("Orders");
    assertThat(read("ALTER TABLE Orders ADD COLUMN note STRING(MAX)").target()).isEqualTo("Orders");
    assertThat(read("DROP TABLE IF EXISTS Orders").target()).isEqualTo("Orders");
    assertThat(read("CREATE CHANGE STREAM Everything FOR ALL").target()).isEqualTo("Everything");
    assertThat(read("ANALYZE").kind()).isEqualTo(Kind.DDL);
  }

  @Test
  void accessTransactionsProceduresAndVertexAiStayWithPeople() {
    for (String q : new String[] {"GRANT SELECT ON TABLE Orders TO ROLE r", "REVOKE SELECT ON TABLE Orders FROM ROLE r",
        "CREATE ROLE agent", "DROP ROLE agent", "ALTER DATABASE orders SET OPTIONS (version_retention_period = '7d')",
        "CALL cancel_query('123')", "BEGIN", "COMMIT", "SET statement_timeout = 10", "SHOW DATABASE DDL",
        "CREATE MODEL m INPUT (x INT64) OUTPUT (y INT64) REMOTE OPTIONS (endpoint = 'x')",
        "SELECT * FROM ML.PREDICT(MODEL m, TABLE t)", "SELECT spanner.ml_predict_row('x', '{}')",
        "SELECT 1; SELECT 2"}) {
      assertThat(read(q).kind()).as(q).isEqualTo(Kind.FORBIDDEN);
    }
    // A function's name inside a string or a comment calls nothing.
    assertThat(read("SELECT 'ML.PREDICT(x)' -- ML.PREDICT(\nFROM t").kind()).isEqualTo(Kind.READ);
  }

  @Test
  void whatCantBeReadWithCertaintyIsRefused() {
    for (String q : new String[] {"", "   ", "SELECT 'open", "SELECT /* open", "SELECT `a\\b` FROM t",
        "SELECT $$ open", "@{OPEN SELECT 1", "SELECT /* a /* nested */ */ 1", "MERGE INTO t USING s ON true",
        "TRUNCATE TABLE t", "(DELETE FROM t)", "x".repeat(SpannerSql.MAX_LENGTH + 1)}) {
      assertThat(read(q).kind()).as(q).isEqualTo(Kind.UNREADABLE);
    }
  }

  @Test
  void quotedSemicolonsAndKeywordsAreNotStatements() {
    assertThat(SpannerSql.scan("SELECT 'a;b', \"c;d\", `e;f`, $$g;h$$ FROM t;").statements()).isEqualTo(1);
    assertThat(SpannerSql.scan("SELECT 'it''s' ; SELECT 2").statements()).isEqualTo(2);
    assertThat(read("SELECT 'DELETE FROM t' AS x").kind()).isEqualTo(Kind.READ);
  }
}
