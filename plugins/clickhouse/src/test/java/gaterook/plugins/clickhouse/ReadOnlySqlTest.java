package gaterook.plugins.clickhouse;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The plugin's own read-only check (step K6): reads pass with their trailing semicolon dropped; a write, a second
 * statement, a table function that reaches outside the server or INTO OUTFILE is refused — also when it hides behind
 * comments, quoted names or a string that only looks like the end of a literal.
 */
class ReadOnlySqlTest {

  @Test
  void readsPass() {
    for (String sql : List.of("SELECT 1", "select count() from events;", "WITH t AS (SELECT 1 AS x) SELECT x FROM t",
        "SHOW TABLES FROM analytics", "DESCRIBE TABLE events", "desc events", "EXPLAIN PLAN SELECT 1",
        "EXISTS TABLE events", "-- a comment\nSELECT 1 /* inline */", "SELECT 'a;b' AS semicolon_in_a_string",
        "SELECT 'it''s', 'a\\'b' FROM numbers(10)", "SELECT `url` FROM visits", "SELECT format('{}', 1)")) {
      ReadOnlySql.Result r = ReadOnlySql.check(sql);
      assertThat(r.ok()).as(sql + " → " + r.refusal()).isTrue();
    }
    assertThat(ReadOnlySql.check("SELECT 1;  ").checked().sql()).isEqualTo("SELECT 1");
    assertThat(ReadOnlySql.check("  select 1").checked().firstWord()).isEqualTo("SELECT");
  }

  @Test
  void writesAndSecondStatementsAreRefused() {
    for (String sql : List.of("INSERT INTO events VALUES (1)", "ALTER TABLE events DELETE WHERE 1",
        "DROP TABLE events", "TRUNCATE TABLE events", "OPTIMIZE TABLE events FINAL", "SYSTEM FLUSH LOGS",
        "KILL QUERY WHERE 1", "SET max_execution_time = 0", "CREATE TABLE t (x UInt8) ENGINE = Memory",
        "GRANT ALL ON *.* TO me", "/* SELECT */ DELETE FROM events WHERE 1", "(SELECT 1)")) {
      assertThat(ReadOnlySql.check(sql).ok()).as(sql).isFalse();
    }
    assertThat(ReadOnlySql.check("SELECT 1; DROP TABLE events").refusal()).contains("One statement");
    assertThat(ReadOnlySql.check("SELECT 'x\\'; DROP TABLE t; --'; DROP TABLE t").refusal())
        .contains("One statement");
    assertThat(ReadOnlySql.check("").refusal()).contains("empty");
    assertThat(ReadOnlySql.check("SELECT 'unclosed").refusal()).contains("isn't closed");
    assertThat(ReadOnlySql.check("SELECT 1 /* unclosed").refusal()).contains("isn't closed");
  }

  @Test
  void tableFunctionsThatReachOutsideAndOutfileAreRefused() {
    for (String sql : List.of("SELECT * FROM url('http://169.254.169.254/', CSV)",
        "SELECT * FROM s3 ('https://bucket/x.csv')", "SELECT * FROM remote('other:9000', db.t)",
        "SELECT * FROM `file`('/etc/passwd', LineAsString)", "DESCRIBE TABLE url('https://example.com/a.csv')",
        "SELECT * FROM mysql('db:3306', 'x', 't', 'u', 'p')", "SELECT * FROM postgresql('db', 'x', 't', 'u', 'p')",
        "SELECT * FROM executable('id', TSV, 'x String')", "SELECT * FROM t INTO OUTFILE '/tmp/x'")) {
      assertThat(ReadOnlySql.check(sql).ok()).as(sql).isFalse();
    }
    assertThat(ReadOnlySql.check("SELECT * FROM URL('http://x')").refusal()).contains("URL()");
  }

  /**
   * A heredoc string ({@code $tag$ … $tag$}) is read as ClickHouse reads it (review 2026-10-27): a quote inside it
   * starts no literal, so the code after it is checked — before, {@code $$'$$} opened a "string" that hid {@code url()}
   * up to the quote in a trailing comment.
   */
  @Test
  void aHeredocHidesNothing() {
    for (String sql : List.of("SELECT $$'$$ FROM url('http://169.254.169.254/latest', CSV, 'a String') -- '",
        "SELECT $q$'$q$ FROM s3('https://bucket/x.csv') #'", "SELECT $$--$$ FROM remote('other:9000', db.t)",
        "SELECT $$/*$$ FROM file('/etc/passwd', LineAsString) /* */")) {
      assertThat(ReadOnlySql.check(sql).ok()).as(sql).isFalse();
    }
    assertThat(ReadOnlySql.check("SELECT $$it's url('x')$$, $tag$; DROP$tag$ AS text").ok()).isTrue();
    assertThat(ReadOnlySql.check("SELECT $$unclosed").refusal()).contains("isn't closed");
    assertThat(ReadOnlySql.check("SELECT $1 FROM t").refusal()).contains("$ outside a string");
  }

  @Test
  void explainTakesQueriesOnly() {
    assertThat(ReadOnlySql.checkQuery("SELECT 1").ok()).isTrue();
    assertThat(ReadOnlySql.checkQuery("WITH 1 AS x SELECT x").ok()).isTrue();
    assertThat(ReadOnlySql.checkQuery("SHOW TABLES").ok()).isFalse();
    assertThat(ReadOnlySql.checkQuery("INSERT INTO t SELECT 1").refusal()).contains("SELECT or WITH");
  }
}
