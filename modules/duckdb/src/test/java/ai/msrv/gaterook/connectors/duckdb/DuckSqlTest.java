package ai.msrv.gaterook.connectors.duckdb;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The classifier alone (docs/duckdb.md): DuckDB's tokens, one statement, reads and changes, and file reads only of
 * absolute paths inside the data folders. DuckDB enforces the same boundary itself (DuckDbEngineTest); here the agent
 * gets its reason before anything runs, and whatever the classifier can't read is refused.
 */
class DuckSqlTest {

  private static final List<String> FOLDERS = List.of("/data/sales/", "/srv/exports/");
  private static final List<String> NONE = List.of();

  private static DuckSql.Verdict read(String sql) {
    return DuckSql.read(sql, FOLDERS);
  }

  private static DuckSql.Verdict change(String sql) {
    return DuckSql.change(sql, FOLDERS);
  }

  // ── Reads ───────────────────────────────────────────────────────────────────────────────────────────────────

  @ParameterizedTest
  @ValueSource(strings = {
      "SELECT 1",
      "select * from orders where id = 1;",
      "WITH recent AS (SELECT * FROM orders WHERE at > now() - INTERVAL 1 DAY) SELECT count(*) FROM recent",
      "WITH RECURSIVE r(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM r WHERE n < 10) SELECT * FROM r",
      "WITH a AS MATERIALIZED (SELECT 1), b AS NOT MATERIALIZED (SELECT 2) SELECT * FROM a, b",
      "FROM orders",
      "FROM orders SELECT customer, sum(total) GROUP BY ALL",
      "VALUES (1, 'a'), (2, 'b')",
      "TABLE orders",
      "(SELECT 3)",
      "PIVOT orders ON status USING count(*)",
      "UNPIVOT (SELECT 1 AS a, 2 AS b) ON a, b INTO NAME k VALUE v",
      "DESCRIBE orders",
      "DESCRIBE SELECT * FROM orders",
      "SHOW TABLES",
      "SHOW ALL TABLES",
      "SUMMARIZE orders",
      "EXPLAIN SELECT * FROM orders",
      "EXPLAIN ANALYZE SELECT count(*) FROM orders",
      "SELECT list_transform([1, 2], lambda x: x + 1), {'a': 1}, [1, 2][1], 1_000, #1 FROM orders",
      "SELECT 'it''s', E'\\'', $$a;b$$, $tag$ DROP TABLE x; $tag$ FROM orders",
      "/* outer /* nested */ still a comment; DROP TABLE x */ SELECT 1 -- ; DELETE FROM x",
      "SELECT \"DELETE\", \"from\" FROM \"Orders\"",
      "SELECT trim('x' FROM 'xabcx'), extract(year FROM DATE '2026-11-11'), substring('abc' FROM 2)",
      "SELECT * FROM orders WHERE note IN ('a', 'b') ORDER BY 1",
      "SELECT * FROM pragma_table_info('orders')",
      "SELECT current_setting('allowed_directories')",
      "SELECT * FROM duckdb_tables() WHERE (comment IS NOT NULL)",
  })
  void readsPass(String sql) {
    DuckSql.Verdict v = read(sql);
    assertThat(v.problem()).as(sql).isNull();
    assertThat(v.ok()).isTrue();
  }

  @Test
  void theStatementRunsAsWrittenWithoutItsTrailingSemicolonOrComment() {
    DuckSql.Verdict v = read("  SELECT 1 -- one\n  AS \"a;b\" -- two\n  ; -- trailing");
    assertThat(v.text()).isEqualTo("SELECT 1 -- one\n  AS \"a;b\"");
    assertThat(v.verb()).isEqualTo("SELECT");
    assertThat(v.rows()).isTrue();
    assertThat(read("EXPLAIN SELECT 1").rows()).isFalse();
    assertThat(read("WITH x AS (SELECT 1) SELECT * FROM x").verb()).isEqualTo("WITH SELECT");
    assertThat(read("EXPLAIN ANALYZE FROM t").verb()).isEqualTo("EXPLAIN FROM");
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "INSERT INTO orders VALUES (1)",
      "UPDATE orders SET total = 0",
      "DELETE FROM orders",
      "CREATE TABLE x (a INT)",
      "CREATE TEMP TABLE x AS SELECT 1",
      "DROP TABLE orders",
      "ALTER TABLE orders ADD COLUMN x INT",
      "TRUNCATE orders",
      "COMMENT ON TABLE orders IS 'x'",
      "MERGE INTO orders USING x ON true WHEN MATCHED THEN DELETE",
      "WITH x AS (SELECT 1) DELETE FROM orders",
      "WITH x AS (DELETE FROM orders RETURNING *) SELECT * FROM x",
      "SELECT * FROM (UPDATE orders SET total = 0 RETURNING *)",
      "EXPLAIN ANALYZE DELETE FROM orders",
      "EXPLAIN EXPLAIN SELECT 1",
      "ATTACH 'other.duckdb' AS o",
      "DETACH o",
      "COPY orders TO '/data/sales/out.csv'",
      "COPY orders FROM '/data/sales/in.csv'",
      "EXPORT DATABASE '/data/sales/x'",
      "IMPORT DATABASE '/data/sales/x'",
      "INSTALL httpfs",
      "FORCE INSTALL httpfs",
      "LOAD httpfs",
      "SET memory_limit = '10GB'",
      "SET VARIABLE x = 1",
      "RESET memory_limit",
      "PRAGMA enable_profiling",
      "CALL pragma_version()",
      "CHECKPOINT",
      "FORCE CHECKPOINT",
      "VACUUM",
      "ANALYZE",
      "USE memory",
      "BEGIN TRANSACTION",
      "START TRANSACTION",
      "COMMIT",
      "ROLLBACK",
      "PREPARE p AS SELECT 1",
      "EXECUTE p",
      "CREATE SECRET s (TYPE s3, KEY_ID 'k', SECRET 's')",
      "SELECT 1; SELECT 2",
      "SELECT 1; DROP TABLE orders",
      "",
      "   ;  ",
      "-- only a comment",
      "SELECT * FROM `orders`",
      "SELECT 'unterminated",
      "SELECT \"unterminated",
      "SELECT 1 /* unterminated",
      "SELECT $$unterminated",
      "SELECT \\x",
      "SELECT (1",
      "SELECT 1)",
      "((DESCRIBE orders))",
      "SELECT * FROM query('SELECT 1')",
      "SELECT * FROM query_table('orders')",
      "SELECT * FROM json_execute_serialized_sql('{}')",
      "SELECT * FROM read_xlsx('/data/sales/x.xlsx')",
      "SELECT * FROM sqlite_scan('/data/sales/x.db', 't')",
      "SELECT * FROM postgres_scan('host=x', 'public', 't')",
      "SELECT * FROM read_duckdb('/data/sales/other.duckdb')",
      "SELECT * FROM checkpoint()",
      "﻿SELECT 1",
      "SELECT 1\u0007",
  })
  void everythingElseIsRefused(String sql) {
    DuckSql.Verdict v = read(sql);
    assertThat(v.ok()).as(sql).isFalse();
    assertThat(v.problem()).as(sql).isNotBlank();
  }

  @Test
  void refusalsSayWhy() {
    assertThat(read("DELETE FROM orders").problem()).contains("only reads").contains("duckdb_execute");
    assertThat(read("ATTACH 'x.duckdb'").problem()).contains("one database file");
    assertThat(read("COPY t TO 'x'").problem()).contains("never writes files");
    assertThat(read("INSTALL httpfs").problem()).contains("Extensions");
    assertThat(read("SET threads = 8").problem()).contains("locks DuckDB's settings");
    assertThat(read("BEGIN").problem()).contains("own transaction");
    assertThat(read("SELECT 1; SELECT 2").problem()).contains("one statement").contains("2");
    assertThat(read("SELECT * FROM query('x')").problem()).contains("runs SQL from text");
    assertThat(read("WITH x AS (DELETE FROM t RETURNING *) SELECT 1").problem()).contains("inside parentheses");
    assertThat(read("SELECT * FROM `t`").problem()).contains("backticks");
  }

  // ── Files ───────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void fileReadersReadAbsolutePathsInsideTheDataFolders() {
    DuckSql.Verdict v = read("SELECT * FROM read_parquet('/data/sales/2026/*.parquet') JOIN "
        + "read_csv(['/srv/exports/a.csv', '/srv/exports/b.csv'], header = true) USING (id)");
    assertThat(v.problem()).isNull();
    assertThat(v.files()).extracting(DuckSql.FileRef::path).containsExactly("/data/sales/2026/*.parquet",
        "/srv/exports/a.csv", "/srv/exports/b.csv");
    assertThat(v.files()).extracting(DuckSql.FileRef::glob).containsExactly(true, false, false);
    assertThat(read("SELECT * FROM read_json_auto('/data/sales/x.json')").ok()).isTrue();
    assertThat(read("SELECT * FROM glob('/data/sales/**')").ok()).isTrue();
    assertThat(read("SELECT * FROM read_text('/data/sales/notes.txt')").ok()).isTrue();
    assertThat(read("SELECT * FROM read_blob($$/data/sales/x.bin$$)").ok()).isTrue();
    assertThat(read("SELECT * FROM system.main.read_csv('/data/sales/x.csv')").ok()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "SELECT * FROM read_csv('/etc/passwd')",
      "SELECT * FROM read_text('/data/salesman/x.txt')",
      "SELECT * FROM read_text('/data/sales')",
      "SELECT * FROM read_text('/data/sales/../secrets/x')",
      "SELECT * FROM read_text('/data/sales/./x')",
      "SELECT * FROM read_text('/data/sales//x')",
      "SELECT * FROM read_text('data/sales/x.csv')",
      "SELECT * FROM read_text('~/x.csv')",
      "SELECT * FROM read_text('s3://bucket/x.csv')",
      "SELECT * FROM read_text('https://example.com/x.csv')",
      "SELECT * FROM read_text('/data/sales/a\\b')",
      "SELECT * FROM read_text(E'/data/sales/x')",
      "SELECT * FROM read_text('/data/sales/' || 'x')",
      "SELECT * FROM read_text(path)",
      "SELECT * FROM read_text(getvariable('p'))",
      "SELECT * FROM read_text(['/data/sales/x', '/etc/passwd'])",
      "SELECT * FROM read_text(['/data/sales/x'] || ['/etc/passwd'])",
      "SELECT * FROM read_text()",
      "SELECT * FROM \"read_text\"('/etc/passwd')",
      "SELECT * FROM READ_TEXT('/etc/passwd')",
      "SELECT * FROM main.read_text('/etc/passwd')",
      "SELECT * FROM '/etc/passwd'",
      "SELECT * FROM '/data/sales/x.csv' '/../../etc/passwd'",
      "SELECT * FROM orders, '/etc/passwd'",
      "SELECT * FROM orders JOIN '/etc/passwd' ON true",
      "SELECT * FROM orders LEFT JOIN '/etc/passwd' x ON true",
      "SELECT * FROM \"/etc/passwd\"",
      "SELECT * FROM \"secrets.csv\"",
      "SELECT * FROM t WHERE EXISTS (SELECT 1 FROM '/etc/passwd')",
      "SELECT * FROM t WHERE x IN (FROM '/etc/passwd')",
      "DESCRIBE '/etc/passwd'",
      "SUMMARIZE '/etc/passwd'",
      "PIVOT '/etc/passwd' ON a",
      "FROM '/etc/passwd' SELECT *",
  })
  void filesOutsideTheDataFoldersAreRefused(String sql) {
    assertThat(read(sql).ok()).as(sql).isFalse();
  }

  @Test
  void withoutDataFoldersNoFileIsRead() {
    DuckSql.Verdict v = DuckSql.read("SELECT * FROM read_csv('/data/sales/x.csv')", NONE);
    assertThat(v.problem()).contains("has none");
    assertThat(DuckSql.read("SELECT * FROM 'x.csv'", NONE).ok()).isFalse();
    // A table whose quoted name doesn't look like a file is a table.
    assertThat(DuckSql.read("SELECT * FROM \"Orders\", \"order items\" JOIN \"schema\".\"t.csv\" ON true",
        NONE).ok()).isTrue();
  }

  @Test
  void aTableFileReadNamesItsPath() {
    DuckSql.Verdict v = read("SELECT * FROM '/data/sales/2026.csv' AS s, \"/srv/exports/x.parquet\"");
    assertThat(v.problem()).isNull();
    assertThat(v.files()).extracting(DuckSql.FileRef::path).containsExactly("/data/sales/2026.csv",
        "/srv/exports/x.parquet");
    assertThat(read("DESCRIBE '/data/sales/x.csv'").ok()).isTrue();
    assertThat(read("SUMMARIZE '/data/sales/x.csv'").ok()).isTrue();
  }

  // ── Changes ─────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void changesNameTheirStatementAndTarget() {
    assertThat(change("INSERT INTO main.orders (id) VALUES (1)")).extracting(DuckSql.Verdict::verb,
        DuckSql.Verdict::target).containsExactly("INSERT", "main.orders");
    assertThat(change("INSERT OR REPLACE INTO orders VALUES (1)").target()).isEqualTo("orders");
    assertThat(change("UPDATE \"Orders\" SET total = 0 WHERE id = 1").target()).isEqualTo("\"Orders\"");
    assertThat(change("DELETE FROM orders WHERE id = 1").verb()).isEqualTo("DELETE");
    assertThat(change("CREATE TABLE IF NOT EXISTS daily AS SELECT * FROM orders")).extracting(DuckSql.Verdict::verb,
        DuckSql.Verdict::target).containsExactly("CREATE TABLE", "daily");
    assertThat(change("CREATE OR REPLACE VIEW v AS SELECT 1").verb()).isEqualTo("CREATE VIEW");
    assertThat(change("CREATE UNIQUE INDEX by_email ON customers (email)")).extracting(DuckSql.Verdict::verb,
        DuckSql.Verdict::target).containsExactly("CREATE INDEX", "customers");
    assertThat(change("CREATE SEQUENCE s").verb()).isEqualTo("CREATE SEQUENCE");
    assertThat(change("CREATE MACRO twice(x) AS x * 2").verb()).isEqualTo("CREATE MACRO");
    assertThat(change("ALTER TABLE orders RENAME TO orders_old").target()).isEqualTo("orders");
    assertThat(change("DROP TABLE IF EXISTS staging.tmp").target()).isEqualTo("staging.tmp");
    assertThat(change("DROP MACRO TABLE m").verb()).isEqualTo("DROP MACRO");
    assertThat(change("TRUNCATE TABLE orders").target()).isEqualTo("orders");
    assertThat(change("COMMENT ON COLUMN orders.total IS 'Euro'").verb()).isEqualTo("COMMENT ON COLUMN");
    assertThat(change("WITH x AS (SELECT 1 AS id) INSERT INTO orders SELECT * FROM x").verb()).isEqualTo("INSERT");
    assertThat(change("CREATE TABLE s AS SELECT * FROM read_parquet('/data/sales/*.parquet')").files())
        .extracting(DuckSql.FileRef::path).containsExactly("/data/sales/*.parquet");
    assertThat(change("INSERT INTO t VALUES (1) RETURNING *").ok()).isTrue();
    assertThat(change("UPDATE t SET x = 1;").text()).isEqualTo("UPDATE t SET x = 1");
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "SELECT 1",
      "WITH x AS (SELECT 1) SELECT * FROM x",
      "MERGE INTO orders USING x ON true WHEN MATCHED THEN DELETE",
      "CREATE TEMP TABLE x (a INT)",
      "CREATE TEMPORARY VIEW v AS SELECT 1",
      "CREATE OR REPLACE TEMP MACRO m() AS 1",
      "CREATE SECRET s (TYPE s3)",
      "CREATE PERSISTENT SECRET s (TYPE s3)",
      "CREATE OR REPLACE SECRET s (TYPE s3)",
      "DROP SECRET s",
      "DROP PERSISTENT SECRET s",
      "CREATE DATABASE x",
      "ALTER SCHEMA s RENAME TO t",
      "UPDATE EXTENSIONS",
      "INSERT INTO t SELECT * FROM read_csv('/etc/passwd')",
      "INSERT INTO t SELECT * FROM '/etc/passwd'",
      "UPDATE t SET x = 1 FROM '/etc/passwd' f",
      "CREATE TABLE t AS SELECT * FROM query('SELECT 1')",
      "WITH x AS (DELETE FROM a RETURNING *) INSERT INTO b SELECT * FROM x",
      "DELETE FROM t; DROP TABLE u",
      "INSERT INTO t VALUES (1); COMMIT",
      "COPY t FROM '/data/sales/x.csv'",
      "COPY t TO '/data/sales/x.csv'",
      "ATTACH '/data/sales/x.duckdb'",
      "EXPORT DATABASE '/data/sales/x'",
      "INSTALL spatial",
      "SET threads = 1",
      "CHECKPOINT",
      "BEGIN",
      "INSERT orders VALUES (1)",
      "DELETE orders",
  })
  void everythingButOneChangeIsRefused(String sql) {
    DuckSql.Verdict v = change(sql);
    assertThat(v.ok()).as(sql).isFalse();
    assertThat(v.problem()).as(sql).isNotBlank();
  }

  @Test
  void changeRefusalsSayWhy() {
    assertThat(change("SELECT 1").problem()).contains("duckdb_query");
    assertThat(change("MERGE INTO t USING s ON true WHEN MATCHED THEN DELETE").problem()).contains("MERGE");
    assertThat(change("CREATE TEMP TABLE x (a INT)").problem()).contains("Temporary");
    assertThat(change("CREATE SECRET s (TYPE s3)").problem()).contains("Secrets");
    assertThat(change("DELETE FROM t; DROP TABLE u").problem()).contains("one statement");
  }

  // ── Tokens ──────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void theLexerKnowsDuckDbsQuotingAndComments() {
    var lexed = DuckSql.lex("SELECT 'a''b' AS \"x\"\"y\", $q$ ' \" $q$, E'\\'', U&'d\\0061', X'ff' -- c\n/* a /* b */ c */ 1");
    assertThat(lexed.problem()).isNull();
    assertThat(lexed.tokens()).extracting(DuckSql.Token::type).containsExactly(DuckSql.T.WORD, DuckSql.T.STRING,
        DuckSql.T.WORD, DuckSql.T.QUOTED, DuckSql.T.COMMA, DuckSql.T.STRING, DuckSql.T.COMMA, DuckSql.T.STRING,
        DuckSql.T.COMMA, DuckSql.T.STRING, DuckSql.T.COMMA, DuckSql.T.STRING, DuckSql.T.NUMBER);
    assertThat(lexed.tokens().get(1).value()).isEqualTo("a'b");
    assertThat(lexed.tokens().get(3).value()).isEqualTo("x\"y");
    assertThat(lexed.tokens().get(5).value()).isEqualTo(" ' \" ");
    assertThat(lexed.tokens().get(7).plain()).isFalse();
    assertThat(DuckSql.lex("SELECT $1, $name, ?").tokens()).extracting(DuckSql.Token::type)
        .containsExactly(DuckSql.T.WORD, DuckSql.T.PARAM, DuckSql.T.COMMA, DuckSql.T.PARAM, DuckSql.T.COMMA,
            DuckSql.T.PARAM);
    assertThat(DuckSql.lex("SELECT 1--2").tokens()).hasSize(2);
    assertThat(DuckSql.lex("SELECT a::INT->>'x'").tokens()).extracting(DuckSql.Token::value)
        .containsExactly("select", "a", "::", "int", "->>", "x");
  }
}
