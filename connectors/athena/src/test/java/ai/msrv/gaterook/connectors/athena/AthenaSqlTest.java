package ai.msrv.gaterook.connectors.athena;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connectors.athena.AthenaSql.Kind;
import ai.msrv.gaterook.connectors.athena.AthenaSql.Role;
import ai.msrv.gaterook.connectors.athena.AthenaSql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Athena's statements as the gateway reads them: Trino's queries and Hive's DDL, refusing where the two could part. */
class AthenaSqlTest {

  private static Statement read(String sql) {
    return AthenaSql.read(sql);
  }

  @Test
  void queriesArePlannedAndHiveReadsAreNot() {
    for (String sql : List.of("SELECT * FROM sales.orders", "WITH t AS (SELECT 1 x) SELECT x FROM t", "VALUES 1",
        "EXPLAIN SELECT * FROM sales.orders", "SELECT regexp_like(note, '\\d+') FROM sales.orders")) {
      Statement st = read(sql);
      assertThat(st.kind()).as(sql).isEqualTo(Kind.READ);
      assertThat(st.hive()).as(sql).isFalse();
      assertThat(st.planned()).as(sql).isNotNull();
    }
    for (String sql : List.of("SHOW TABLES IN sales", "SHOW DATABASES", "SHOW COLUMNS IN sales.orders",
        "SHOW PARTITIONS sales.orders", "SHOW TBLPROPERTIES sales.orders", "SHOW CREATE TABLE `sales`.`orders`",
        "DESCRIBE FORMATTED sales.orders", "DESC sales.orders", "SHOW VIEWS IN sales", "DESCRIBE VIEW sales.v")) {
      Statement st = read(sql);
      assertThat(st.kind()).as(sql).isEqualTo(Kind.READ);
      assertThat(st.hive()).as(sql).isTrue();
      assertThat(st.planned()).as(sql).isNull();
    }
    assertThat(read("SHOW COLUMNS IN orders IN sales").refs().getFirst().name().text()).isEqualTo("sales.orders");
  }

  @Test
  void hiveStatementsCarryNoBackslashOrBlockComment() {
    // To Hive a backslash escapes the next character; to Trino it is one. Where they could part, nothing is guessed.
    assertThat(read("SHOW TABLES IN sales 'x\\' ; DROP TABLE t; --'").kind()).isIn(Kind.UNREADABLE, Kind.FORBIDDEN);
    assertThat(read("CREATE EXTERNAL TABLE sales.t (a string) COMMENT 'it\\'s' LOCATION 's3://acme/t/'").kind())
        .isEqualTo(Kind.UNREADABLE);
    assertThat(read("DESCRIBE /* x */ sales.orders").kind()).isEqualTo(Kind.UNREADABLE);
    assertThat(read("DESCRIBE sales.orders -- the table").kind()).isEqualTo(Kind.READ);
    // Backquoted names are Hive's; a query keeps its backslashes in strings.
    Statement ddl = read("CREATE EXTERNAL TABLE `sales`.`clicks` (`id` string) LOCATION 's3://acme-data/clicks/'");
    assertThat(ddl.kind()).isEqualTo(Kind.CHANGE);
    assertThat(ddl.target().name().text()).isEqualTo("sales.clicks");
    assertThat(read("SELECT * FROM \"sa\\les\".orders").kind()).isEqualTo(Kind.UNREADABLE);
  }

  @Test
  void dataExportsExternalFunctionsAndSessionsAreRefused() {
    for (String sql : List.of("UNLOAD (SELECT * FROM sales.orders) TO 's3://acme-exports/' WITH (format = 'PARQUET')",
        "USING EXTERNAL FUNCTION f(x bigint) RETURNS bigint LAMBDA 'acme-fn' SELECT f(1)",
        "PREPARE q FROM SELECT * FROM sales.orders WHERE id = ?", "EXECUTE q USING 1", "DEALLOCATE PREPARE q",
        "EXPLAIN ANALYZE SELECT * FROM sales.orders", "GRANT SELECT ON sales.orders TO alice", "USE sales",
        "SET SESSION x = 1", "START TRANSACTION", "WITH SESSION x = 1 SELECT 1",
        "SELECT * FROM TABLE(system.query(query => 'DELETE FROM users'))", "SELECT 1; SELECT 2",
        "EXPLAIN INSERT INTO sales.t SELECT 1")) {
      Statement st = read(sql);
      assertThat(st.kind()).as(sql).isEqualTo(Kind.FORBIDDEN);
      assertThat(st.problem()).as(sql).isNotBlank();
    }
  }

  @Test
  void changesNameTheirTargetAndEngine() {
    record Case(String sql, String type, String target, Role role, boolean hive) {}
    for (Case c : List.of(
        new Case("INSERT INTO sales.orders SELECT * FROM staging.orders", "INSERT", "sales.orders", Role.TABLE, false),
        new Case("UPDATE sales.orders SET paid = true WHERE id = 1", "UPDATE", "sales.orders", Role.TABLE, false),
        new Case("DELETE FROM sales.orders WHERE id = 1", "DELETE", "sales.orders", Role.TABLE, false),
        new Case("MERGE INTO sales.orders t USING staging.orders s ON t.id = s.id WHEN MATCHED THEN DELETE", "MERGE",
            "sales.orders", Role.TABLE, false),
        new Case("CREATE TABLE sales.daily WITH (format = 'PARQUET') AS SELECT 1 x", "CREATE TABLE AS", "sales.daily",
            Role.TABLE, false),
        new Case("CREATE OR REPLACE VIEW sales.v AS SELECT 1 x", "CREATE OR REPLACE VIEW", "sales.v", Role.TABLE,
            false),
        new Case("CREATE DATABASE IF NOT EXISTS reports", "CREATE DATABASE", "reports", Role.DATABASE, true),
        new Case("ALTER TABLE sales.orders ADD PARTITION (dt = '2026-11-11') LOCATION 's3://acme/o/dt=2026-11-11/'",
            "ALTER TABLE", "sales.orders", Role.TABLE, true),
        new Case("MSCK REPAIR TABLE sales.orders", "MSCK REPAIR TABLE", "sales.orders", Role.TABLE, true),
        new Case("DROP TABLE IF EXISTS sales.daily", "DROP TABLE", "sales.daily", Role.TABLE, true),
        new Case("OPTIMIZE sales.orders REWRITE DATA USING BIN_PACK", "OPTIMIZE", "sales.orders", Role.TABLE, false),
        new Case("VACUUM sales.orders", "VACUUM", "sales.orders", Role.TABLE, false))) {
      Statement st = read(c.sql());
      assertThat(st.kind()).as(c.sql()).isEqualTo(Kind.CHANGE);
      assertThat(st.type()).as(c.sql()).isEqualTo(c.type());
      assertThat(st.target().name().text()).as(c.sql()).isEqualTo(c.target());
      assertThat(st.target().role()).as(c.sql()).isEqualTo(c.role());
      assertThat(st.hive()).as(c.sql()).isEqualTo(c.hive());
    }
  }

  @Test
  void namesKnowTheirCatalogAndDatabase() {
    AthenaSql.Name table = AthenaSql.qualified("awsdatacatalog.sales.orders");
    assertThat(table.catalog(Role.TABLE)).isEqualTo("awsdatacatalog");
    assertThat(table.database(Role.TABLE)).isEqualTo("sales");
    AthenaSql.Name two = AthenaSql.qualified("sales.orders");
    assertThat(two.catalog(Role.TABLE)).isNull();
    assertThat(two.database(Role.TABLE)).isEqualTo("sales");
    assertThat(AthenaSql.qualified("orders").database(Role.TABLE)).isNull();
    assertThat(AthenaSql.qualified("reports").database(Role.DATABASE)).isEqualTo("reports");
    assertThat(AthenaSql.qualified("`sales`.`Order Lines`").parts()).containsExactly("sales", "Order Lines");
    for (String bad : List.of("a.b.c.d", "a; DROP TABLE b", "a b", "a -- x", "\"a\\b\"")) {
      assertThat(AthenaSql.qualified(bad)).as(bad).isNull();
    }
  }
}
