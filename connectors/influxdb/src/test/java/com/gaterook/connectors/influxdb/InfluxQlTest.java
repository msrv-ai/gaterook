package com.gaterook.connectors.influxdb;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.connectors.influxdb.InfluxQl.Kind;
import com.gaterook.connectors.influxdb.InfluxQl.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * InfluxQL as GateRook reads it: SELECT without INTO and SHOW of data pass, with the databases they name; what the
 * scanners of InfluxDB 1.x, 2.x and 3 could read differently doesn't.
 */
class InfluxQlTest {

  @Test
  void readsPass() {
    for (String q : List.of("SELECT * FROM cpu WHERE host = 'web-1' AND time > now() - 1h",
        "SELECT mean(usage) FROM cpu WHERE time > now() - 30m GROUP BY time(5m), host fill(none)",
        "SELECT usage / 2, usage*3 FROM cpu", "SELECT \"usage\" / \"cores\" FROM \"cpu\"",
        "SELECT * FROM cpu WHERE host =~ /web-[12];x/ AND region !~ /^us/",
        "SELECT /us/ FROM cpu", "SELECT mean(/us/) FROM cpu", "SELECT a, /b/ FROM cpu",
        "SELECT * FROM /cpu.*/", "SELECT * FROM cpu, /mem.*/ LIMIT 1", "SELECT * FROM cpu GROUP BY /host|region/",
        "SELECT * FROM (SELECT mean(usage) AS u FROM cpu GROUP BY host) WHERE u > 10",
        "SELECT * FROM cpu WHERE host = $host LIMIT 5", "SELECT * FROM \"a \\\"quoted\\\" m\"",
        "SELECT * FROM cpu WHERE note = 'it\\'s; DROP DATABASE metrics'", "SELECT usage::integer FROM cpu",
        "SELECT * FROM cpu WHERE host =~ /a\\/b/", "SELECT count(*) FROM cpu WHERE usage > .5",
        "SHOW DATABASES", "SHOW MEASUREMENTS", "SHOW MEASUREMENTS WITH MEASUREMENT =~ /c.*/ LIMIT 10",
        "SHOW TAG KEYS FROM cpu", "SHOW TAG VALUES FROM cpu WITH KEY = \"host\"", "SHOW FIELD KEYS",
        "SHOW SERIES FROM /cpu/ WHERE host = 'a'", "SHOW RETENTION POLICIES", "SHOW SERIES EXACT CARDINALITY",
        "SHOW TAG VALUES WITH KEY IN (\"host\", \"region\")", "show measurements;")) {
      Statement st = InfluxQl.read(q);
      assertThat(st.kind()).as(q + ": " + st.problem()).isEqualTo(Kind.READ);
    }
  }

  @Test
  void theDatabasesAStatementNamesAreFound() {
    Statement crossDb = InfluxQl.read("SELECT * FROM \"other db\".\"autogen\".\"cpu\", cpu");
    assertThat(crossDb.databases()).containsExactly("other db");
    assertThat(crossDb.defaultDatabase()).isTrue();
    Statement defaultRp = InfluxQl.read("SELECT * FROM telemetry..cpu");
    assertThat(defaultRp.databases()).containsExactly("telemetry");
    assertThat(defaultRp.defaultDatabase()).isFalse();
    assertThat(InfluxQl.read("SELECT * FROM telemetry.autogen./cpu.*/").databases()).containsExactly("telemetry");
    assertThat(InfluxQl.read("SELECT * FROM (SELECT * FROM a.b.c) WHERE x > 1").databases()).containsExactly("a");
    Statement on = InfluxQl.read("SHOW MEASUREMENTS ON \"metrics\"");
    assertThat(on.databases()).containsExactly("metrics");
    assertThat(on.defaultDatabase()).isFalse();
    Statement rpOnly = InfluxQl.read("SELECT * FROM autogen.cpu");
    assertThat(rpOnly.databases()).isEmpty();
    assertThat(rpOnly.defaultDatabase()).isTrue();
    assertThat(InfluxQl.read("SHOW DATABASES").defaultDatabase()).isFalse();
    assertThat(InfluxQl.read("SHOW MEASUREMENTS").defaultDatabase()).isTrue();
  }

  @Test
  void writesAndAdministrationAreRefused() {
    for (String q : List.of("SELECT * INTO cpu_copy FROM cpu", "SELECT mean(usage) INTO \"db\".\"rp\".\"m\" FROM cpu",
        "DROP DATABASE metrics", "DROP MEASUREMENT cpu", "DELETE FROM cpu WHERE time < now() - 1d",
        "CREATE DATABASE x", "ALTER RETENTION POLICY autogen ON metrics DURATION 1h",
        "CREATE CONTINUOUS QUERY cq ON metrics BEGIN SELECT mean(usage) INTO m FROM cpu GROUP BY time(1h) END",
        "SELECT * FROM cpu WHERE drop = 'x'")) {
      assertThat(InfluxQl.read(q).kind()).as(q).isEqualTo(Kind.WRITE);
    }
    for (String q : List.of("GRANT ALL TO bob", "REVOKE READ ON metrics FROM bob", "KILL QUERY 3",
        "SET PASSWORD FOR bob = 'x'", "SHOW USERS", "SHOW GRANTS FOR bob", "SHOW QUERIES", "SHOW SUBSCRIPTIONS",
        "SHOW DIAGNOSTICS", "SHOW STATS", "SHOW SHARDS", "SHOW CONTINUOUS QUERIES", "EXPLAIN SELECT * FROM cpu",
        "SELECT 1; SELECT 2", "SELECT * FROM cpu; DROP DATABASE metrics")) {
      assertThat(InfluxQl.read(q).kind()).as(q).isEqualTo(Kind.FORBIDDEN);
    }
  }

  @Test
  void whatTheScannersCouldReadDifferentlyIsRefused() {
    for (String q : List.of("SELECT * FROM cpu -- comment", "SELECT /* c */ * FROM cpu",
        "SELECT * FROM cpu WHERE host =~ /web\\\\/", "SELECT * FROM cpu WHERE host =~ /*x/",
        "SELECT * FROM cpu WHERE host =~ /open", "SELECT * FROM cpu WHERE host = 'open",
        "SELECT * FROM cpu WHERE host = 'a\\nb'", "SELECT * FROM cpu WHERE host = 'a\nb'",
        "SELECT * FROM other\"db\".autogen.cpu", "SELECT * FROM $m", "SHOW MEASUREMENTS ON $db",
        "SELECT * FROM a.b.c.d", "SELECT (/x/) FROM cpu", "SELECT * FROM cpu WHERE usage > 1. ",
        "SELECT * FROM température", "SELECT * FROM cpu WHERE x = 1 / /y/", "SELECT ! FROM cpu",
        "SELECT * FROM cpu WHERE (a; b)", "SELECT * FROM (SELECT * FROM cpu", "", "  ",
        "SELECT * FROM cpu WHERE host = $\"x\"", "SELECT * FROM cpu ORDER BY /x/")) {
      assertThat(InfluxQl.read(q).kind()).as(q).isIn(Kind.UNREADABLE, Kind.FORBIDDEN);
    }
  }

  @Test
  void aNumberKeepsItsDurationButAKeywordAfterAFractionIsAWord() {
    // InfluxDB's Go scanner reads 1into as one duration token, 1.5into as 1.5 and INTO.
    assertThat(InfluxQl.read("SELECT a, 1.5into x FROM cpu").kind()).isEqualTo(Kind.WRITE);
    assertThat(InfluxQl.read("SELECT * FROM cpu WHERE time > now() - 10m").kind()).isEqualTo(Kind.READ);
  }

  @Test
  void fluxIsRecognizedSoItsRefusalCanSaySo() {
    assertThat(InfluxQl.looksLikeFlux("from(bucket: \"metrics\") |> range(start: -1h)")).isTrue();
    assertThat(InfluxQl.looksLikeFlux("import \"sql\"\nsql.to(driverName: \"x\")")).isTrue();
    assertThat(InfluxQl.looksLikeFlux("SELECT * FROM cpu")).isFalse();
    assertThat(InfluxQl.read("from(bucket: \"metrics\") |> range(start: -1h)").kind()).isNotEqualTo(Kind.READ);
  }

  @Test
  void quotingAnIdentifierEscapesItsQuotesAndBackslashes() {
    assertThat(InfluxQl.quote("a \"b\" \\c")).isEqualTo("\"a \\\"b\\\" \\\\c\"");
    assertThat(InfluxQl.read("SHOW FIELD KEYS FROM " + InfluxQl.quote("my \"m\"")).kind()).isEqualTo(Kind.READ);
  }
}
