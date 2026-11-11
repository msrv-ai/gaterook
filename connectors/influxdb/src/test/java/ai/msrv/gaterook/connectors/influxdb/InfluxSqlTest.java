package ai.msrv.gaterook.connectors.influxdb;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connectors.influxdb.InfluxSql.Kind;
import java.util.List;
import org.junit.jupiter.api.Test;

/** InfluxDB 3's SQL as GateRook reads it: reads pass, everything else and whatever is ambiguous doesn't. */
class InfluxSqlTest {

  private static Kind kind(String sql) {
    return InfluxSql.read(sql).kind();
  }

  @Test
  void readsPass() {
    for (String sql : List.of("SELECT * FROM cpu WHERE host = 'web-1' ORDER BY time DESC LIMIT 10",
        "select host, avg(usage) from cpu group by host;",
        "WITH t AS (SELECT host, usage FROM cpu) SELECT * FROM t",
        "SELECT date_bin(INTERVAL '1 hour', time) AS bucket, count(*) FROM cpu GROUP BY 1",
        "SELECT * FROM \"my measurement\" WHERE \"my tag\" = 'a ''quoted'' value'",
        "SELECT * FROM cpu WHERE host = $host AND usage > $1",
        "SELECT regexp_like(host, '^web\\d+$') FROM cpu",
        "SHOW TABLES", "SHOW COLUMNS FROM cpu", "VALUES (1), (2)",
        "EXPLAIN SELECT * FROM cpu", "EXPLAIN VERBOSE SELECT 1", "EXPLAIN FORMAT tree SELECT 1",
        "SELECT table_name FROM information_schema.tables",
        "SELECT 1 -- ; DROP TABLE cpu", "SELECT 1 /* ; DELETE FROM cpu */", "SELECT ';DROP' AS x, \"a;b\" FROM cpu",
        "SELECT \"system\" FROM cpu", "SELECT cpu.system FROM cpu", "SELECT 'système' AS t",
        "SELECT load1, load5 FROM system", "SELECT * FROM load WHERE grant = 'x'")) {
      assertThat(InfluxSql.read(sql).kind()).as(sql).isEqualTo(Kind.READ);
    }
  }

  @Test
  void writesAndOtherStatementsAreRefused() {
    for (String sql : List.of("DELETE FROM cpu", "INSERT INTO cpu VALUES (1)", "UPDATE cpu SET usage = 1",
        "DROP TABLE cpu", "CREATE TABLE x AS SELECT 1", "COPY (SELECT 1) TO 'out.parquet'",
        "SELECT * INTO copy FROM cpu", "WITH d AS (DELETE FROM cpu RETURNING *) SELECT * FROM d",
        "EXPLAIN DELETE FROM cpu", "SELECT 1delete FROM cpu")) {
      assertThat(kind(sql)).as(sql).isEqualTo(Kind.WRITE);
    }
    for (String sql : List.of("SET datafusion.execution.batch_size = 1", "EXPLAIN ANALYZE SELECT * FROM cpu",
        "EXPLAIN VERBOSE ANALYZE SELECT 1", "SELECT * FROM system.queries", "SELECT * FROM \"system\".queries",
        "SELECT * FROM public.system.queries", "SELECT 1; SELECT 2", "SELECT 1; DELETE FROM cpu", "DESCRIBE cpu",
        "PREPARE p AS SELECT 1", "EXECUTE p", "LOAD 'httpfs'", "GRANT SELECT ON cpu TO bob",
        "WITH t AS (SELECT 1) INSERT INTO cpu SELECT * FROM t")) {
      assertThat(kind(sql)).as(sql).isIn(Kind.FORBIDDEN, Kind.WRITE);
    }
  }

  @Test
  void whatTwoDialectsCouldReadDifferentlyIsRefused() {
    for (String sql : List.of("SELECT 'open", "SELECT \"open", "SELECT 1 /* open", "SELECT '\\'' ; DELETE FROM cpu",
        "SELECT 'a\\' OR 1=1 --'", "SELECT E'a\\'b'", "SELECT U&'d\\0061t'", "SELECT X'ab'",
        "SELECT /* outer /* inner */ 1 */ 2", "SELECT $$text$$", "SELECT $tag$x$tag$", "SELECT `cpu`",
        "SELECT #x", "SELECT @x", "SELECT abc$def FROM cpu", "SELECT \"a\\\" FROM cpu", "SELECT 1\u0007",
        "SELECT * FROM température", "", "   ", "(SELECT 1)", "EXPLAIN (ANALYZE true) SELECT 1")) {
      assertThat(kind(sql)).as(sql).isIn(Kind.UNREADABLE, Kind.FORBIDDEN);
    }
    // A backslash elsewhere in a string reads the same in both dialects.
    assertThat(kind("SELECT * FROM cpu WHERE note = 'C:\\temp\\x'")).isEqualTo(Kind.READ);
  }

  @Test
  void problemsSayWhatToDo() {
    assertThat(InfluxSql.read("SELECT 1; SELECT 2").problem()).contains("One statement per call");
    assertThat(InfluxSql.read("SELECT * FROM system.queries").problem()).contains("system tables");
    assertThat(InfluxSql.read("EXPLAIN ANALYZE SELECT 1").problem()).contains("EXPLAIN without ANALYZE");
    assertThat(InfluxSql.read("SELECT `x`").problem()).contains("double quotes");
    assertThat(InfluxSql.read("SELECT update FROM cpu").problem()).contains("Quote a name");
  }
}
