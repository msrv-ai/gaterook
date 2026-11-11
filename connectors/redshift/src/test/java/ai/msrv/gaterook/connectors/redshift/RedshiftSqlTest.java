package ai.msrv.gaterook.connectors.redshift;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connectors.redshift.RedshiftSql.Kind;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** How GateRook reads a Redshift statement: the route of each, before anything runs. */
class RedshiftSqlTest {

  static Stream<Arguments> statements() {
    return Stream.of(
        // Reads.
        Arguments.of("SELECT id FROM sales.orders WHERE note = 'DELETE; DROP'", Kind.READ, "SELECT"),
        Arguments.of("select * from \"Sales\".\"Orders\" limit 5", Kind.READ, "SELECT"),
        Arguments.of("WITH t AS (SELECT 1 AS x) SELECT x FROM t", Kind.READ, "WITH"),
        Arguments.of("(SELECT 1) UNION ALL (SELECT 2)", Kind.READ, "SELECT"),
        Arguments.of("SHOW search_path", Kind.READ, "SHOW"),
        Arguments.of("SHOW TABLES FROM SCHEMA dev.public", Kind.READ, "SHOW"),
        Arguments.of("EXPLAIN SELECT * FROM sales.orders", Kind.READ, "EXPLAIN"),
        Arguments.of("EXPLAIN VERBOSE WITH x AS (SELECT 1) SELECT * FROM x", Kind.READ, "EXPLAIN"),
        Arguments.of("SELECT 1 -- ; DROP TABLE sales.orders", Kind.READ, "SELECT"),
        Arguments.of("SELECT 1 /* ; DELETE FROM t */ ;", Kind.READ, "SELECT"),
        Arguments.of("SELECT 'it''s; fine', \"a;b\" FROM t", Kind.READ, "SELECT"),
        Arguments.of("SELECT amount::numeric(12,2) FROM sales.orders WHERE id = :id", Kind.READ, "SELECT"),
        Arguments.of("SELECT col$1 FROM t", Kind.READ, "SELECT"),
        Arguments.of("SELECT \"pg_terminate_backend\" FROM t", Kind.READ, "SELECT"),
        // Changes.
        Arguments.of("UPDATE sales.orders SET paid = true WHERE id = :id", Kind.CHANGE, "UPDATE"),
        Arguments.of("INSERT INTO sales.orders (id) VALUES (1)", Kind.CHANGE, "INSERT"),
        Arguments.of("DELETE FROM sales.orders WHERE id = 1", Kind.CHANGE, "DELETE"),
        Arguments.of("MERGE INTO sales.orders USING sales.staging s ON orders.id = s.id WHEN MATCHED THEN UPDATE "
            + "SET paid = s.paid", Kind.CHANGE, "MERGE"),
        Arguments.of("TRUNCATE sales.staging", Kind.CHANGE, "TRUNCATE"),
        Arguments.of("CREATE TABLE sales.daily AS SELECT 1 AS x", Kind.CHANGE, "CREATE TABLE"),
        Arguments.of("CREATE TEMP TABLE scratch (id int)", Kind.CHANGE, "CREATE TABLE"),
        Arguments.of("CREATE TABLE stores (id int, location varchar(64), owner varchar(64))", Kind.CHANGE,
            "CREATE TABLE"),
        Arguments.of("CREATE OR REPLACE VIEW sales.v AS SELECT 1", Kind.CHANGE, "CREATE VIEW"),
        Arguments.of("CREATE MATERIALIZED VIEW sales.mv AS SELECT 1", Kind.CHANGE, "CREATE MATERIALIZED VIEW"),
        Arguments.of("ALTER TABLE sales.orders ADD COLUMN note varchar(256)", Kind.CHANGE, "ALTER TABLE"),
        Arguments.of("DROP TABLE IF EXISTS sales.staging", Kind.CHANGE, "DROP TABLE"),
        Arguments.of("COMMENT ON TABLE sales.orders IS 'Paid orders'", Kind.CHANGE, "COMMENT"),
        Arguments.of("VACUUM DELETE ONLY sales.orders", Kind.CHANGE, "VACUUM"),
        Arguments.of("ANALYZE sales.orders", Kind.CHANGE, "ANALYZE"),
        Arguments.of("REFRESH MATERIALIZED VIEW sales.mv", Kind.CHANGE, "REFRESH"),
        // Access control, data movement, procedures, transaction and session control.
        Arguments.of("GRANT SELECT ON sales.orders TO bob", Kind.FORBIDDEN, "GRANT"),
        Arguments.of("REVOKE ALL ON SCHEMA sales FROM bob", Kind.FORBIDDEN, "REVOKE"),
        Arguments.of("CREATE USER bob PASSWORD 'Secret123'", Kind.FORBIDDEN, "CREATE USER"),
        Arguments.of("ALTER USER bob CREATEUSER", Kind.FORBIDDEN, "ALTER USER"),
        Arguments.of("DROP ROLE analyst", Kind.FORBIDDEN, "DROP ROLE"),
        Arguments.of("CREATE GROUP readers", Kind.FORBIDDEN, "CREATE GROUP"),
        Arguments.of("COPY sales.orders FROM 's3://acme-bucket/orders' IAM_ROLE default", Kind.FORBIDDEN, "COPY"),
        Arguments.of("UNLOAD ('SELECT * FROM sales.orders') TO 's3://acme-bucket/out' IAM_ROLE default",
            Kind.FORBIDDEN, "UNLOAD"),
        Arguments.of("CALL sales.refresh()", Kind.FORBIDDEN, "CALL"),
        Arguments.of("BEGIN", Kind.FORBIDDEN, "BEGIN"),
        Arguments.of("START TRANSACTION READ WRITE", Kind.FORBIDDEN, "START"),
        Arguments.of("COMMIT", Kind.FORBIDDEN, "COMMIT"),
        Arguments.of("END", Kind.FORBIDDEN, "END"),
        Arguments.of("ROLLBACK", Kind.FORBIDDEN, "ROLLBACK"),
        Arguments.of("SET SESSION AUTHORIZATION 'admin'", Kind.FORBIDDEN, "SET"),
        Arguments.of("SET statement_timeout TO 0", Kind.FORBIDDEN, "SET"),
        Arguments.of("RESET ALL", Kind.FORBIDDEN, "RESET"),
        Arguments.of("LOCK sales.orders", Kind.FORBIDDEN, "LOCK"),
        Arguments.of("PREPARE p AS SELECT 1", Kind.FORBIDDEN, "PREPARE"),
        Arguments.of("EXECUTE p", Kind.FORBIDDEN, "EXECUTE"),
        Arguments.of("DECLARE c CURSOR FOR SELECT 1", Kind.FORBIDDEN, "DECLARE"),
        Arguments.of("CANCEL 4711", Kind.FORBIDDEN, "CANCEL"),
        Arguments.of("CREATE EXTERNAL SCHEMA spectrum FROM DATA CATALOG DATABASE 'x' IAM_ROLE default",
            Kind.FORBIDDEN, "CREATE EXTERNAL"),
        Arguments.of("CREATE EXTERNAL FUNCTION f(int) RETURNS int VOLATILE LAMBDA 'f' IAM_ROLE default",
            Kind.FORBIDDEN, "CREATE EXTERNAL"),
        Arguments.of("CREATE DATASHARE sales_share", Kind.FORBIDDEN, "CREATE DATASHARE"),
        Arguments.of("ALTER DATASHARE sales_share ADD SCHEMA sales", Kind.FORBIDDEN, "ALTER DATASHARE"),
        Arguments.of("ALTER DEFAULT PRIVILEGES GRANT SELECT ON TABLES TO bob", Kind.FORBIDDEN, "ALTER DEFAULT"),
        Arguments.of("CREATE MODEL m FROM sales.orders TARGET paid FUNCTION f IAM_ROLE default", Kind.FORBIDDEN,
            "CREATE MODEL"),
        Arguments.of("CREATE LIBRARY lib LANGUAGE plpythonu FROM 's3://acme/lib.zip'", Kind.FORBIDDEN,
            "CREATE LIBRARY"),
        Arguments.of("CREATE RLS POLICY p WITH (region varchar) USING (region = 'eu')", Kind.FORBIDDEN, "CREATE RLS"),
        Arguments.of("ATTACH RLS POLICY p ON sales.orders TO ROLE analyst", Kind.FORBIDDEN, "ATTACH"),
        Arguments.of("CREATE MASKING POLICY m WITH (email varchar) USING ('***')", Kind.FORBIDDEN,
            "CREATE MASKING"),
        Arguments.of("ALTER TABLE sales.orders OWNER TO mallory", Kind.FORBIDDEN, "ALTER TABLE"),
        Arguments.of("ALTER TABLE sales.orders ROW LEVEL SECURITY OFF", Kind.FORBIDDEN, "ALTER TABLE"),
        Arguments.of("ALTER TABLE spectrum.events SET LOCATION 's3://elsewhere/'", Kind.FORBIDDEN, "ALTER TABLE"),
        Arguments.of("ALTER TABLE spectrum.events ADD PARTITION (d = '2026') LOCATION 's3://elsewhere/'",
            Kind.FORBIDDEN, "ALTER TABLE"),
        Arguments.of("CREATE SCHEMA sales AUTHORIZATION mallory", Kind.FORBIDDEN, "CREATE SCHEMA"),
        Arguments.of("DROP DATABASE dev", Kind.FORBIDDEN, "DROP DATABASE"),
        Arguments.of("CREATE PROCEDURE p() AS 'BEGIN END' LANGUAGE plpgsql", Kind.FORBIDDEN, "CREATE PROCEDURE"),
        Arguments.of("SELECT pg_terminate_backend(4711)", Kind.FORBIDDEN, "SELECT"),
        Arguments.of("SELECT pg_catalog.pg_cancel_backend(4711)", Kind.FORBIDDEN, "SELECT"),
        Arguments.of("SELECT \"pg_terminate_backend\" (4711)", Kind.FORBIDDEN, "SELECT"),
        Arguments.of("SELECT set_config('statement_timeout', '0', false)", Kind.FORBIDDEN, "SELECT"),
        Arguments.of("SELECT change_query_priority(4711, 'critical')", Kind.FORBIDDEN, "SELECT"),
        Arguments.of("EXPLAIN ANALYZE SELECT 1", Kind.FORBIDDEN, "EXPLAIN"),
        Arguments.of("EXPLAIN DELETE FROM sales.orders", Kind.FORBIDDEN, "EXPLAIN"),
        Arguments.of("SELECT * INTO sales.copy FROM sales.orders", Kind.FORBIDDEN, "SELECT"),
        Arguments.of("WITH x AS (SELECT 1) INSERT INTO t SELECT * FROM x", Kind.FORBIDDEN, "WITH"),
        Arguments.of("SELECT 1; SELECT 2", Kind.FORBIDDEN, "script"),
        Arguments.of("SELECT 1; DELETE FROM sales.orders", Kind.FORBIDDEN, "script"),
        // What can't be read with certainty.
        Arguments.of("SELECT 'open", Kind.UNREADABLE, "statement"),
        Arguments.of("SELECT 1 /* open", Kind.UNREADABLE, "statement"),
        Arguments.of("SELECT 1 /* outer /* inner */ ; DROP TABLE t */", Kind.UNREADABLE, "statement"),
        Arguments.of("SELECT 'a\\'; DROP TABLE t; --'", Kind.UNREADABLE, "statement"),
        Arguments.of("SELECT E'\\x41'", Kind.UNREADABLE, "statement"),
        Arguments.of("SELECT \"open", Kind.UNREADABLE, "statement"),
        Arguments.of("CREATE FUNCTION f(int) RETURNS int STABLE AS $$ SELECT $1 $$ LANGUAGE sql", Kind.UNREADABLE,
            "statement"),
        Arguments.of("SELECT $tag$;$tag$", Kind.UNREADABLE, "statement"),
        Arguments.of("SELECT 1\u0000; DROP TABLE t", Kind.UNREADABLE, "statement"),
        Arguments.of("", Kind.UNREADABLE, "statement"),
        Arguments.of("   -- only a comment", Kind.UNREADABLE, "statement"),
        Arguments.of("FROBNICATE sales.orders", Kind.UNREADABLE, "FROBNICATE"),
        Arguments.of("DESC DATASHARE sales_share", Kind.UNREADABLE, "DESC"));
  }

  @ParameterizedTest(name = "{1} {2}: {0}")
  @MethodSource("statements")
  void eachStatementTakesItsRoute(String sql, Kind kind, String type) {
    RedshiftSql.Statement st = RedshiftSql.read(sql);
    assertThat(st.kind()).as(sql).isEqualTo(kind);
    assertThat(st.type()).as(sql).isEqualTo(type);
    if (kind == Kind.FORBIDDEN || kind == Kind.UNREADABLE) {
      assertThat(st.problem()).as(sql).isNotBlank();
    }
  }

  @Test
  void changesNameWhatTheyActOnAsWritten() {
    assertThat(RedshiftSql.read("UPDATE \"Sales\".\"Orders\" SET paid = true").target())
        .isEqualTo("\"Sales\".\"Orders\"");
    assertThat(RedshiftSql.read("DELETE FROM sales.orders WHERE id = 1").target()).isEqualTo("sales.orders");
    assertThat(RedshiftSql.read("CREATE TABLE IF NOT EXISTS sales.daily (id int)").target()).isEqualTo("sales.daily");
    assertThat(RedshiftSql.read("COMMENT ON COLUMN sales.orders.note IS 'x'").target())
        .isEqualTo("sales.orders.note");
    assertThat(RedshiftSql.read("VACUUM").target()).isNull();
    assertThat(RedshiftSql.read("REFRESH MATERIALIZED VIEW sales.mv").target()).isEqualTo("sales.mv");
  }

  @Test
  void redshiftPlansOnlyWhatItsExplainTakes() {
    assertThat(RedshiftSql.read("UPDATE t SET a = 1").explainable()).isTrue();
    assertThat(RedshiftSql.read("INSERT INTO t SELECT * FROM u").explainable()).isTrue();
    assertThat(RedshiftSql.read("DELETE FROM t").explainable()).isTrue();
    assertThat(RedshiftSql.read("CREATE TABLE t AS SELECT 1").explainable()).isTrue();
    assertThat(RedshiftSql.read("CREATE TABLE t (id int DEFAULT 1)").explainable()).isFalse();
    assertThat(RedshiftSql.read("MERGE INTO t USING u ON t.id = u.id WHEN MATCHED THEN DELETE").explainable())
        .isFalse();
    assertThat(RedshiftSql.read("TRUNCATE t").explainable()).isFalse();
  }

  @Test
  void parametersAreTheNamedPlaceholdersOfTheCode() {
    assertThat(RedshiftSql.read("SELECT * FROM t WHERE a = :a AND b::int = :b_2 AND c = ':c' AND \"x:d\" = 1")
        .parameters()).containsExactly("a", "b_2");
    assertThat(RedshiftSql.read("SELECT a::int, b::varchar FROM t").parameters()).isEmpty();
  }
}
