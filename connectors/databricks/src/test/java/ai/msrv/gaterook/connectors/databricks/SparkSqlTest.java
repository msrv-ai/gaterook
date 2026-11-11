package ai.msrv.gaterook.connectors.databricks;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connectors.databricks.SparkSql.Kind;
import ai.msrv.gaterook.connectors.databricks.SparkSql.Ref;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** How GateRook reads a Databricks SQL statement: the route of each, before anything runs. */
class SparkSqlTest {

  static Stream<Arguments> statements() {
    return Stream.of(
        // Reads.
        Arguments.of("SELECT id FROM main.sales.orders WHERE note = 'DELETE; DROP'", Kind.READ, "SELECT"),
        Arguments.of("select * from `main`.`sales`.`orders` limit 5", Kind.READ, "SELECT"),
        Arguments.of("WITH t AS (SELECT 1 AS x) SELECT x FROM t", Kind.READ, "WITH"),
        Arguments.of("VALUES (1, 'a'), (2, 'b')", Kind.READ, "VALUES"),
        Arguments.of("TABLE main.sales.orders", Kind.READ, "TABLE"),
        Arguments.of("FROM main.sales.orders SELECT id", Kind.READ, "FROM"),
        Arguments.of("SHOW TABLES IN main.sales", Kind.READ, "SHOW TABLES"),
        Arguments.of("SHOW CATALOGS", Kind.READ, "SHOW CATALOGS"),
        Arguments.of("DESCRIBE TABLE EXTENDED main.sales.orders", Kind.READ, "DESCRIBE"),
        Arguments.of("DESCRIBE HISTORY main.sales.orders", Kind.READ, "DESCRIBE"),
        Arguments.of("EXPLAIN SELECT * FROM main.sales.orders", Kind.READ, "EXPLAIN"),
        Arguments.of("EXPLAIN FORMATTED WITH x AS (SELECT 1) SELECT * FROM x", Kind.READ, "EXPLAIN"),
        Arguments.of("SELECT 1 -- ; DROP TABLE main.sales.orders", Kind.READ, "SELECT"),
        Arguments.of("SELECT 1 /* ; DELETE FROM t */ ;", Kind.READ, "SELECT"),
        Arguments.of("SELECT \"it's; fine\", 'a\\\\b;c\\td', r'C:/x' FROM t", Kind.READ, "SELECT"),
        Arguments.of("SELECT /*+ BROADCAST(s) */ o.id FROM main.sales.orders o JOIN main.sales.staging s ON "
            + "o.id = s.id", Kind.READ, "SELECT"),
        Arguments.of("SELECT amount::DECIMAL(12,2), raw:owner FROM t WHERE id = :id", Kind.READ, "SELECT"),
        Arguments.of("SELECT `read_files` FROM t", Kind.READ, "SELECT"),
        // Changes.
        Arguments.of("UPDATE main.sales.orders SET paid = true WHERE id = :id", Kind.CHANGE, "UPDATE"),
        Arguments.of("INSERT INTO main.sales.orders VALUES (1)", Kind.CHANGE, "INSERT"),
        Arguments.of("INSERT OVERWRITE TABLE main.sales.daily SELECT * FROM main.sales.orders", Kind.CHANGE,
            "INSERT"),
        Arguments.of("DELETE FROM main.sales.orders WHERE id = 1", Kind.CHANGE, "DELETE"),
        Arguments.of("MERGE INTO main.sales.orders t USING main.sales.staging s ON t.id = s.id WHEN MATCHED THEN "
            + "UPDATE SET *", Kind.CHANGE, "MERGE"),
        Arguments.of("TRUNCATE TABLE main.sales.staging", Kind.CHANGE, "TRUNCATE"),
        Arguments.of("CREATE TABLE main.sales.daily AS SELECT 1 AS x", Kind.CHANGE, "CREATE TABLE"),
        Arguments.of("CREATE OR REPLACE TABLE stores (id INT, location STRING, options STRING)", Kind.CHANGE,
            "CREATE TABLE"),
        Arguments.of("CREATE OR REPLACE VIEW main.sales.v AS SELECT 1", Kind.CHANGE, "CREATE VIEW"),
        Arguments.of("CREATE MATERIALIZED VIEW main.sales.mv AS SELECT 1", Kind.CHANGE, "CREATE MATERIALIZED VIEW"),
        Arguments.of("CREATE SCHEMA IF NOT EXISTS main.scratch", Kind.CHANGE, "CREATE SCHEMA"),
        Arguments.of("ALTER TABLE main.sales.orders ADD COLUMN note STRING", Kind.CHANGE, "ALTER TABLE"),
        Arguments.of("DROP TABLE IF EXISTS main.sales.staging", Kind.CHANGE, "DROP TABLE"),
        Arguments.of("OPTIMIZE main.sales.orders ZORDER BY (id)", Kind.CHANGE, "OPTIMIZE"),
        Arguments.of("VACUUM main.sales.orders RETAIN 168 HOURS", Kind.CHANGE, "VACUUM"),
        Arguments.of("ANALYZE TABLE main.sales.orders COMPUTE STATISTICS", Kind.CHANGE, "ANALYZE"),
        Arguments.of("REFRESH MATERIALIZED VIEW main.sales.mv", Kind.CHANGE, "REFRESH"),
        Arguments.of("RESTORE TABLE main.sales.orders TO VERSION AS OF 3", Kind.CHANGE, "RESTORE"),
        Arguments.of("COMMENT ON TABLE main.sales.orders IS 'Paid orders'", Kind.CHANGE, "COMMENT"),
        // Access control, files, data movement, procedures, session settings, scripting.
        Arguments.of("GRANT SELECT ON TABLE main.sales.orders TO `mallory@example.com`", Kind.FORBIDDEN, "GRANT"),
        Arguments.of("REVOKE ALL PRIVILEGES ON SCHEMA main.sales FROM analysts", Kind.FORBIDDEN, "REVOKE"),
        Arguments.of("COPY INTO main.sales.orders FROM 's3://acme-bucket/orders' FILEFORMAT = CSV", Kind.FORBIDDEN,
            "COPY"),
        Arguments.of("LIST '/Volumes/main/sales/files'", Kind.FORBIDDEN, "LIST"),
        Arguments.of("PUT '/tmp/x.csv' INTO '/Volumes/main/sales/files/x.csv'", Kind.FORBIDDEN, "PUT"),
        Arguments.of("GET '/Volumes/main/sales/files/x.csv' TO '/tmp/x.csv'", Kind.FORBIDDEN, "GET"),
        Arguments.of("REMOVE '/Volumes/main/sales/files/x.csv'", Kind.FORBIDDEN, "REMOVE"),
        Arguments.of("INSERT OVERWRITE DIRECTORY 's3://acme-bucket/out' SELECT * FROM main.sales.orders",
            Kind.FORBIDDEN, "INSERT OVERWRITE DIRECTORY"),
        Arguments.of("INSERT OVERWRITE LOCAL DIRECTORY '/tmp/out' SELECT 1", Kind.FORBIDDEN,
            "INSERT OVERWRITE DIRECTORY"),
        Arguments.of("CALL main.sales.refresh()", Kind.FORBIDDEN, "CALL"),
        Arguments.of("SET spark.sql.ansi.enabled = false", Kind.FORBIDDEN, "SET"),
        Arguments.of("SET VAR x = 1", Kind.FORBIDDEN, "SET"),
        Arguments.of("RESET", Kind.FORBIDDEN, "RESET"),
        Arguments.of("USE CATALOG hr", Kind.FORBIDDEN, "USE"),
        Arguments.of("BEGIN SELECT 1; END", Kind.FORBIDDEN, "script"),
        Arguments.of("BEGIN TRANSACTION", Kind.FORBIDDEN, "BEGIN"),
        Arguments.of("DECLARE VARIABLE x INT", Kind.FORBIDDEN, "DECLARE"),
        Arguments.of("EXECUTE IMMEDIATE 'DROP TABLE t'", Kind.FORBIDDEN, "EXECUTE"),
        Arguments.of("CREATE CATALOG hr", Kind.FORBIDDEN, "CREATE CATALOG"),
        Arguments.of("DROP CATALOG main CASCADE", Kind.FORBIDDEN, "DROP CATALOG"),
        Arguments.of("CREATE SHARE sales_share", Kind.FORBIDDEN, "CREATE SHARE"),
        Arguments.of("ALTER SHARE sales_share ADD TABLE main.sales.orders", Kind.FORBIDDEN, "ALTER SHARE"),
        Arguments.of("CREATE RECIPIENT partner", Kind.FORBIDDEN, "CREATE RECIPIENT"),
        Arguments.of("CREATE PROVIDER p USING 'x'", Kind.FORBIDDEN, "CREATE PROVIDER"),
        Arguments.of("CREATE CONNECTION pg TYPE postgresql OPTIONS (host 'db.example.com')", Kind.FORBIDDEN,
            "CREATE CONNECTION"),
        Arguments.of("CREATE STORAGE CREDENTIAL c WITH (AWS_IAM_ROLE = 'arn')", Kind.FORBIDDEN, "CREATE STORAGE"),
        Arguments.of("CREATE EXTERNAL LOCATION l URL 's3://acme/x' WITH (STORAGE CREDENTIAL c)", Kind.FORBIDDEN,
            "CREATE EXTERNAL"),
        Arguments.of("CREATE FOREIGN CATALOG f USING CONNECTION pg OPTIONS (database 'x')", Kind.FORBIDDEN,
            "CREATE FOREIGN"),
        Arguments.of("CREATE FUNCTION main.sales.f(x INT) RETURNS INT RETURN x + 1", Kind.FORBIDDEN,
            "CREATE FUNCTION"),
        Arguments.of("CREATE TABLE main.sales.ext (id INT) LOCATION 's3://acme/ext'", Kind.FORBIDDEN, "CREATE TABLE"),
        Arguments.of("CREATE TABLE main.sales.j USING JDBC OPTIONS (url 'jdbc:postgresql://db/x')", Kind.FORBIDDEN,
            "CREATE TABLE"),
        Arguments.of("ALTER TABLE main.sales.orders SET LOCATION 's3://elsewhere/'", Kind.FORBIDDEN, "ALTER TABLE"),
        Arguments.of("ALTER TABLE main.sales.orders OWNER TO `mallory@example.com`", Kind.FORBIDDEN, "ALTER TABLE"),
        Arguments.of("ALTER TABLE main.sales.orders SET ROW FILTER f ON (region)", Kind.FORBIDDEN, "ALTER TABLE"),
        Arguments.of("ALTER TABLE main.sales.orders ALTER COLUMN email SET MASK m", Kind.FORBIDDEN, "ALTER TABLE"),
        Arguments.of("CREATE SCHEMA main.s MANAGED LOCATION 's3://acme/s'", Kind.FORBIDDEN, "CREATE SCHEMA"),
        Arguments.of("SELECT * FROM read_files('s3://acme-bucket/orders/')", Kind.FORBIDDEN, "SELECT"),
        Arguments.of("SELECT http_request(conn => 'x', method => 'GET', path => '/')", Kind.FORBIDDEN, "SELECT"),
        Arguments.of("SELECT ai_query('endpoint', note) FROM t", Kind.FORBIDDEN, "SELECT"),
        Arguments.of("SELECT ai_summarize(note) FROM t", Kind.FORBIDDEN, "SELECT"),
        Arguments.of("SELECT * FROM read_kafka(bootstrapServers => 'kafka:9092', subscribe => 'x')", Kind.FORBIDDEN,
            "SELECT"),
        Arguments.of("SELECT * FROM read_pubsub(subscriptionId => 'x')", Kind.FORBIDDEN, "SELECT"),
        Arguments.of("SELECT secret('scope', 'key')", Kind.FORBIDDEN, "SELECT"),
        Arguments.of("SELECT `secret` ('scope', 'key')", Kind.FORBIDDEN, "SELECT"),
        Arguments.of("SELECT system.builtin.secret('scope', 'key')", Kind.FORBIDDEN, "SELECT"),
        Arguments.of("SELECT java_method('java.lang.System', 'exit', 0)", Kind.FORBIDDEN, "SELECT"),
        Arguments.of("SELECT reflect('java.lang.Runtime', 'getRuntime')", Kind.FORBIDDEN, "SELECT"),
        Arguments.of("SELECT system.ai.python_exec('print(1)')", Kind.FORBIDDEN, "SELECT"),
        Arguments.of("SELECT * FROM cloud_files('s3://acme/x', 'json')", Kind.FORBIDDEN, "SELECT"),
        Arguments.of("SELECT * FROM IDENTIFIER(:t)", Kind.FORBIDDEN, "SELECT"),
        Arguments.of("SELECT * FROM delta.`/Volumes/main/sales/raw`", Kind.FORBIDDEN, "SELECT"),
        Arguments.of("SELECT * FROM parquet.`s3://acme-bucket/x/`", Kind.FORBIDDEN, "SELECT"),
        Arguments.of("EXPLAIN INSERT INTO t SELECT 1", Kind.FORBIDDEN, "EXPLAIN"),
        Arguments.of("EXPLAIN ANALYZE TABLE t COMPUTE STATISTICS", Kind.FORBIDDEN, "EXPLAIN"),
        Arguments.of("WITH x AS (SELECT 1) INSERT INTO t SELECT * FROM x", Kind.FORBIDDEN, "WITH"),
        Arguments.of("FROM t INSERT INTO u SELECT *", Kind.FORBIDDEN, "FROM"),
        Arguments.of("SELECT 1; SELECT 2", Kind.FORBIDDEN, "script"),
        Arguments.of("SELECT 1; DELETE FROM main.sales.orders", Kind.FORBIDDEN, "script"),
        // What can't be read with certainty.
        Arguments.of("SELECT 'open", Kind.UNREADABLE, "statement"),
        Arguments.of("SELECT 1 /* open", Kind.UNREADABLE, "statement"),
        Arguments.of("SELECT 1 /* outer /* inner */ ; DROP TABLE t */", Kind.UNREADABLE, "statement"),
        Arguments.of("SELECT r'a\\'; DROP TABLE t; --'", Kind.UNREADABLE, "statement"),
        Arguments.of("SELECT 'a\\'; DROP TABLE t; --'", Kind.UNREADABLE, "statement"),
        Arguments.of("SELECT r'C:\\x' FROM t", Kind.UNREADABLE, "statement"),
        Arguments.of("SELECT 1 -- note \\\nDELETE FROM main.sales.orders", Kind.UNREADABLE, "statement"),
        Arguments.of("SELECT \"a\\\"; DROP TABLE t; --\"", Kind.UNREADABLE, "statement"),
        Arguments.of("SELECT `open FROM t", Kind.UNREADABLE, "statement"),
        Arguments.of("SELECT '${spark.databricks.token}'", Kind.UNREADABLE, "statement"),
        Arguments.of("CREATE FUNCTION f() RETURNS INT LANGUAGE PYTHON AS $$ return 1 $$", Kind.UNREADABLE,
            "statement"),
        Arguments.of("SELECT 1\u0000; DROP TABLE t", Kind.UNREADABLE, "statement"),
        Arguments.of("", Kind.UNREADABLE, "statement"),
        Arguments.of("-- only a comment", Kind.UNREADABLE, "statement"),
        Arguments.of("FROBNICATE main.sales.orders", Kind.UNREADABLE, "FROBNICATE"));
  }

  @ParameterizedTest(name = "{1} {2}: {0}")
  @MethodSource("statements")
  void eachStatementTakesItsRoute(String sql, Kind kind, String type) {
    SparkSql.Statement st = SparkSql.read(sql);
    assertThat(st.kind()).as(sql).isEqualTo(kind);
    assertThat(st.type()).as(sql).isEqualTo(type);
    if (kind == Kind.FORBIDDEN || kind == Kind.UNREADABLE) {
      assertThat(st.problem()).as(sql).isNotBlank();
    }
  }

  private static String names(String sql) {
    StringBuilder out = new StringBuilder();
    for (SparkSql.Name n : SparkSql.read(sql).names()) {
      out.append(out.isEmpty() ? "" : " ").append(n.ref().name().charAt(0)).append(':').append(n.text());
    }
    return out.toString();
  }

  @Test
  void theNamesAStatementUsesAreFoundWhereverTheyStand() {
    assertThat(names("SELECT o.id, c.name FROM main.sales.orders AS o JOIN `Main`.`CRM`.customers c ON o.c = "
        + "c.id, hr.salaries s WHERE o.x IN (SELECT y FROM audit.log)"))
        .isEqualTo("T:main.sales.orders T:main.crm.customers T:hr.salaries T:audit.log");
    assertThat(names("SELECT * FROM a, b x, c AS y WHERE 1 = 1")).isEqualTo("T:a T:b T:c");
    assertThat(names("SELECT main.sales.discount(price), upper(name) FROM t")).isEqualTo("F:main.sales.discount T:t");
    assertThat(names("SELECT * FROM range(10)")).isEmpty();
    assertThat(names("INSERT INTO TABLE main.sales.daily SELECT * FROM main.sales.orders"))
        .isEqualTo("T:main.sales.daily T:main.sales.orders");
    assertThat(names("MERGE INTO main.sales.orders t USING main.staging.orders s ON t.id = s.id WHEN MATCHED THEN "
        + "DELETE")).isEqualTo("T:main.sales.orders T:main.staging.orders");
    assertThat(names("ALTER TABLE main.sales.orders RENAME TO hr.orders"))
        .isEqualTo("T:main.sales.orders T:hr.orders");
    assertThat(names("CREATE TABLE main.sales.copy SHALLOW CLONE hr.salaries"))
        .isEqualTo("T:main.sales.copy T:hr.salaries");
    assertThat(names("CREATE TABLE main.sales.t2 LIKE hr.salaries")).isEqualTo("T:main.sales.t2 T:hr.salaries");
    assertThat(names("DROP SCHEMA IF EXISTS main.scratch CASCADE")).isEqualTo("S:main.scratch");
    assertThat(names("SHOW TABLES IN hr")).isEqualTo("S:hr");
    assertThat(names("SHOW SCHEMAS IN hr_catalog")).isEqualTo("C:hr_catalog");
    assertThat(names("SHOW COLUMNS IN salaries IN hr")).isEqualTo("T:salaries S:hr");
    assertThat(names("SHOW GRANTS ON SCHEMA hr")).isEqualTo("S:hr");
    assertThat(names("SHOW CREATE TABLE hr.salaries")).isEqualTo("T:hr.salaries");
    assertThat(names("DESCRIBE DETAIL hr.salaries")).isEqualTo("T:hr.salaries");
    assertThat(names("DESCRIBE CATALOG hr_catalog")).isEqualTo("C:hr_catalog");
    assertThat(names("OPTIMIZE main.sales.orders")).isEqualTo("T:main.sales.orders");
    assertThat(SparkSql.read("UPDATE main.sales.orders SET a = 1").target()).isEqualTo("main.sales.orders");
    assertThat(SparkSql.read("CREATE SCHEMA main.scratch").target()).isEqualTo("main.scratch");
  }

  @Test
  void parametersAreTheNamedMarkersOfTheCode() {
    assertThat(SparkSql.read("SELECT * FROM t WHERE a = :a AND b::INT = :b_2 AND c = ':c' AND raw:path = 1 AND "
        + "`x:d` = 1 AND d IN (:e, :f)").parameters()).containsExactly("a", "b_2", "e", "f");
    assertThat(SparkSql.read("SELECT a::INT, raw:owner FROM t").parameters()).isEmpty();
  }

  @Test
  void aDmlChangeIsWholeOrNothing() {
    assertThat(SparkSql.read("MERGE INTO t USING s ON t.id = s.id WHEN MATCHED THEN DELETE").dml()).isTrue();
    assertThat(SparkSql.read("VACUUM t").dml()).isFalse();
    assertThat(SparkSql.read("CREATE TABLE t (id INT)").dml()).isFalse();
  }
}
