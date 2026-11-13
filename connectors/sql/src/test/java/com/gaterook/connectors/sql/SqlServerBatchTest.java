package com.gaterook.connectors.sql;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** One query by T-SQL's own tokens: reserved words, which can't be names, decide where a statement ends. */
class SqlServerBatchTest {

  @ParameterizedTest
  @ValueSource(strings = {
      "SELECT 1",
      "SELECT 1 AS [delete], 'commit' AS \"update\"",
      "SELECT 1 /* delete */ -- drop",
      "(SELECT 1) UNION (SELECT 2)",
      "SELECT 1 UNION ALL SELECT 2 EXCEPT SELECT 3",
      "WITH a AS (SELECT 1 AS x) SELECT x FROM a",
      "WITH a (x) AS (SELECT 1), b AS (SELECT 2 AS y) SELECT * FROM a CROSS JOIN b",
      "WITH a (x, y) AS (SELECT 1, 2), b AS (SELECT x FROM a), c AS (SELECT y FROM a) SELECT * FROM b CROSS JOIN c",
      "WITH r (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM r WHERE n < 3) SELECT n FROM r",
      "SELECT CASE WHEN 1 = 1 THEN 'a' ELSE 'b' END",
      "SELECT id FROM t ORDER BY id OFFSET 5 ROWS FETCH NEXT 10 ROWS ONLY",
      "SELECT TOP (3) WITH TIES id FROM t ORDER BY id",
      "SELECT id FROM t WITH (NOLOCK) GROUP BY id WITH ROLLUP",
      "SELECT * FROM OPENJSON(N'[]') WITH (a int '$.a')",
      "SELECT id FROM t FOR SYSTEM_TIME AS OF '2020-01-01'",
      "SELECT id FROM t FOR BROWSE",
      "SELECT 0x1F, 1e5, 1.5E-3, $12.50, @@VERSION, #t.id",
      // A word after a dot is part of a qualified name, whatever it is spelled: the catalog views have such columns.
      "SELECT c.precision FROM sys.columns c",
      "SELECT c.precision FROM sys.columns AS c",
      "SELECT c.name, c.max_length, c.precision, c.scale FROM sys.columns c WHERE c.object_id = OBJECT_ID('dbo.orders')",
      "SELECT [c].precision, \"c\".scale, dbo.t.user, db..t.end FROM sys.columns AS c",
      "SELECT CASE WHEN t.end = 1 THEN t.case ELSE 0 END FROM dbo.t t"
  })
  void oneQueryIsOneQuery(String sql) {
    assertThat(SqlServerBatch.oneQuery(sql)).as(sql).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {
      // A reserved statement word is never an alias on the server: the statement ends before it.
      "SELECT 1 DELETE FROM t",
      "SELECT 1 FROM t COMMIT",
      "SELECT 1 UNION SELECT 2 DROP TABLE t",
      "SELECT 1 /* x */ DROP",
      "SELECT (1) COMMIT",
      // Two queries, a query that waits for its second half, and brackets that don't close.
      "SELECT 1 SELECT 2",
      "WITH a AS (SELECT 1) DELETE FROM t",
      "WITH a AS (SELECT 1), SELECT 2",
      "(SELECT 1) SELECT 2",
      "SELECT 1 UNION",
      "SELECT (1",
      "SELECT 1)",
      "SELECT CASE WHEN 1 = 1 THEN 2",
      "SELECT 1 END",
      // A closing parenthesis or an END before its opening, which a later one would balance.
      "SELECT 1) UNION (SELECT 2",
      "SELECT 1 END, CASE WHEN 1 = 1 THEN 2",
      // Common table expressions without their query.
      "WITH a AS (SELECT 1)",
      // A reserved statement word among the common table expressions, before a SELECT that follows a parenthesis.
      "WITH a AS (SELECT 1), truncate AS (SELECT 2) SELECT * FROM a",
      "WITH a AS (SELECT 1) DELETE FROM t WHERE id IN (SELECT 1) SELECT 2",
      // A cursor FETCH and a WITH that isn't a hint or a modifier.
      "SELECT 1 FROM t FETCH NEXT FROM c",
      "SELECT id FROM t ORDER BY id OFFSET 5 ROWS FETCH NEXT GLOBAL c",
      // A FETCH that is a cursor's even after OFFSET ... ROWS, one that does not follow ROWS, and one cut short.
      "SELECT id FROM t ORDER BY id OFFSET 5 ROWS FETCH NEXT FROM c",
      "SELECT 1 FETCH NEXT 5 ROWS ONLY",
      "SELECT 1 FROM t FETCH",
      "SELECT 1 FROM t OFFSET 5 ROWS FETCH NEXT",
      "SELECT 1 FROM t WITH x AS (SELECT 1) SELECT 2",
      "SELECT 1 FROM t WITH x AS (SELECT 1)",
      "SELECT 1 FROM t WITH",
      // Words the server splits where a run of characters would join them.
      "SELECT 1 $DELETE FROM t",
      "SELECT 0x1ROLLBACK",
      "SELECT 1DELETE FROM t",
      "SELECT 1 DELETE FROM t",
      "SELECT 1 é DELETE FROM t",
      // A dot that is not part of a name does not make the word after it one: a number, a string, a parenthesis, or a
      // reserved word before it. Only a name or a word that is not reserved can be qualified.
      "SELECT 1. DELETE FROM t",
      "SELECT 1 .DELETE FROM t",
      "SELECT 0x1F. DELETE FROM t",
      "SELECT 'a'.DELETE FROM t",
      "SELECT (1).DELETE FROM t",
      "SELECT 1 FROM t WHERE EXISTS.DELETE FROM t",
      "SELECT 1 FROM t WHERE a = .DELETE FROM t",
      // Not a query at all.
      "DELETE FROM t",
      "EXEC p",
      "1 SELECT 2",
      ""
  })
  void aStatementWordOrAFragmentIsNotOneQuery(String sql) {
    assertThat(SqlServerBatch.oneQuery(sql)).as(sql).isFalse();
  }
}
