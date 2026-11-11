package ai.msrv.gaterook.connectors.trino;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connectors.trino.TrinoSql.Kind;
import ai.msrv.gaterook.connectors.trino.TrinoSql.Role;
import ai.msrv.gaterook.connectors.trino.TrinoSql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Trino's statements as the gateway reads them: by Trino's tokens, refusing what it can't read with certainty. */
class TrinoSqlTest {

  private static Statement read(String sql) {
    return TrinoSql.read(sql);
  }

  @Test
  void readsAreQueriesShowsDescribesAndExplains() {
    for (String sql : List.of("SELECT * FROM tpch.tiny.nation", "select 1", "WITH t AS (SELECT 1 x) SELECT x FROM t",
        "VALUES 1, 2", "((SELECT 1))", "SHOW TABLES FROM tpch.tiny", "SHOW SCHEMAS", "DESCRIBE tpch.tiny.nation",
        "EXPLAIN SELECT 1", "EXPLAIN (TYPE IO, FORMAT JSON) SELECT * FROM nation", "SHOW CREATE TABLE nation",
        "SHOW STATS FOR nation", "SHOW STATS FOR (SELECT * FROM nation)", "SHOW COLUMNS FROM nation",
        "SHOW FUNCTIONS LIKE 'abs'", "SHOW GRANTS ON TABLE nation", "WITH RECURSIVE t(n) AS (VALUES 1) SELECT * FROM t",
        "SELECT * FROM TABLE(sequence(start => 1, stop => 3))",
        "SELECT * FROM TABLE(system.builtin.exclude_columns(input => TABLE(nation), columns => DESCRIPTOR(comment)))")) {
      Statement st = read(sql);
      assertThat(st.kind()).as(sql).isEqualTo(Kind.READ);
      assertThat(st.planned()).as(sql).isNotNull();
    }
    // The plan of EXPLAIN is the plan of the query it explains.
    assertThat(read("EXPLAIN (TYPE DISTRIBUTED) SELECT * FROM nation").planned()).isEqualTo("SELECT * FROM nation");
    assertThat(read("SHOW STATS FOR (SELECT * FROM nation)").planned()).isEqualTo("SELECT * FROM nation");
  }

  @Test
  void stringsNamesAndCommentsHideNothing() {
    // A semicolon or a keyword inside a string, a name or a comment is neither.
    assertThat(read("SELECT 'a; DROP TABLE x' AS s").kind()).isEqualTo(Kind.READ);
    assertThat(read("SELECT 'it''s', \"na\"\"me\" FROM t").kind()).isEqualTo(Kind.READ);
    assertThat(read("SELECT 1 -- ; DELETE FROM t\n").kind()).isEqualTo(Kind.READ);
    assertThat(read("SELECT 1 /* ; DROP */").kind()).isEqualTo(Kind.READ);
    assertThat(read("SELECT U&'\\0041' UESCAPE '\\'").kind()).isEqualTo(Kind.READ);
    // A final semicolon is cut off: Trino refuses it; what runs is the statement without it.
    Statement st = read("SELECT 1;  -- done");
    assertThat(st.kind()).isEqualTo(Kind.READ);
    assertThat(st.sql()).isEqualTo("SELECT 1");
    // A comment ends at a line break, and a line break ends it whatever follows.
    assertThat(read("SELECT 1 -- x\rDELETE FROM t").kind()).isEqualTo(Kind.READ);
  }

  @Test
  void whatCantBeReadWithCertaintyIsRefused() {
    for (String sql : List.of("SELECT 'open", "SELECT \"open", "SELECT 1 /* open", "SELECT 1 /* a /* b */ */",
        "SELECT 1 */", "SELECT `x` FROM t", "SELECT 1 FROM t\u0000", "SELECT \"\" FROM t", "", "   ",
        "SELECT naïve FROM t", "SELECT 1 --   DELETE", "FROBNICATE t", "(SHOW TABLES)", "TABLE nation")) {
      Statement st = read(sql);
      assertThat(st.kind()).as(sql).isIn(Kind.UNREADABLE, Kind.FORBIDDEN);
      assertThat(st.problem()).as(sql).isNotBlank();
    }
    for (String sql : List.of("SELECT 1; SELECT 2", "SELECT 1; DELETE FROM t", "SELECT 1;;")) {
      assertThat(read(sql).kind()).as(sql).isEqualTo(Kind.FORBIDDEN);
    }
  }

  @Test
  void sessionsAccessTransactionsAndProceduresStayWithPeople() {
    for (String sql : List.of("SET SESSION query_max_run_time = '1h'", "RESET SESSION query_max_run_time",
        "SET ROLE admin", "SET SESSION AUTHORIZATION alice", "RESET SESSION AUTHORIZATION", "SET PATH hive.sales",
        "SET TIME ZONE 'UTC'", "USE hive.sales", "GRANT SELECT ON t TO alice", "REVOKE SELECT ON t FROM alice",
        "DENY SELECT ON t TO alice", "CREATE ROLE admin", "DROP ROLE admin", "START TRANSACTION", "COMMIT",
        "ROLLBACK", "PREPARE q FROM SELECT 1", "EXECUTE q", "EXECUTE IMMEDIATE 'DELETE FROM t'", "DEALLOCATE PREPARE q",
        "CALL system.runtime.kill_query(query_id => 'x')", "CREATE CATALOG pg USING postgresql",
        "DROP CATALOG pg", "WITH SESSION query_max_run_time = '1h' SELECT 1", "EXPLAIN ANALYZE SELECT 1",
        "EXPLAIN ANALYZE INSERT INTO t VALUES 1", "EXPLAIN INSERT INTO t VALUES 1", "SHOW SESSION",
        "ALTER SCHEMA s SET AUTHORIZATION alice", "ALTER TABLE t SET AUTHORIZATION ROLE r",
        "CREATE SCHEMA s AUTHORIZATION alice", "DESCRIBE INPUT q", "DESCRIBE OUTPUT q")) {
      Statement st = read(sql);
      assertThat(st.kind()).as(sql).isEqualTo(Kind.FORBIDDEN);
      assertThat(st.problem()).as(sql).isNotBlank();
    }
  }

  @Test
  void tableFunctionsThatReachOutsideAndAiFunctionsAreRefused() {
    for (String sql : List.of("SELECT * FROM TABLE(postgresql.system.query(query => 'DELETE FROM users'))",
        "SELECT * FROM TABLE(system.query(query => 'SELECT 1'))", "SELECT * FROM TABLE(query(query => 'x'))",
        "SELECT * FROM TABLE ( \"pg\".\"system\".\"query\" (query => 'x'))",
        "SELECT * FROM TABLE(elastic.system.raw_query(schema => 'a', index => 'b', query => '{}'))",
        "SELECT llm.ai.ai_gen('hello')", "SELECT ai_classify(comment, ARRAY['a', 'b']) FROM nation",
        "INSERT INTO memory.default.t SELECT * FROM TABLE(pg.system.query(query => 'x'))")) {
      assertThat(read(sql).kind()).as(sql).isEqualTo(Kind.FORBIDDEN);
    }
    // A name that only looks like one, in a string or a comment, isn't a call.
    assertThat(read("SELECT 'TABLE(pg.system.query(x))' -- ai_gen(").kind()).isEqualTo(Kind.READ);
    // A table argument of a built-in function is a table, not a call.
    assertThat(read("SELECT * FROM TABLE(exclude_columns(input => TABLE(tpch.tiny.nation), "
        + "columns => DESCRIPTOR(comment)))").kind()).isEqualTo(Kind.READ);
  }

  @Test
  void qualifiedFunctionsBecomeCatalogRefs() {
    Statement st = read("SELECT other.lib.f(x), abs(x), system.builtin.abs(x), s.g(x) FROM t");
    assertThat(st.kind()).isEqualTo(Kind.READ);
    assertThat(st.refs()).extracting(r -> r.name().text()).containsExactly("other.lib.f");
    assertThat(st.refs().getFirst().role()).isEqualTo(Role.FUNCTION);
    assertThat(read("SELECT a.b.c.d(1)").kind()).isEqualTo(Kind.FORBIDDEN);
  }

  @Test
  void changesNameTheirTarget() {
    record Case(String sql, String type, String target, Role role) {}
    for (Case c : List.of(
        new Case("INSERT INTO memory.default.t (id) VALUES (1)", "INSERT", "memory.default.t", Role.TABLE),
        new Case("insert into t select * from u", "INSERT", "t", Role.TABLE),
        new Case("UPDATE hive.s.t SET a = 1 WHERE id = 2", "UPDATE", "hive.s.t", Role.TABLE),
        new Case("DELETE FROM \"Hive\".s.t WHERE true", "DELETE", "hive.s.t", Role.TABLE),
        new Case("MERGE INTO t USING u ON t.id = u.id WHEN MATCHED THEN DELETE", "MERGE", "t", Role.TABLE),
        new Case("TRUNCATE TABLE memory.default.t", "TRUNCATE TABLE", "memory.default.t", Role.TABLE),
        new Case("CREATE TABLE memory.default.t (id bigint)", "CREATE TABLE", "memory.default.t", Role.TABLE),
        new Case("CREATE TABLE IF NOT EXISTS memory.default.t AS SELECT 1 x", "CREATE TABLE AS", "memory.default.t",
            Role.TABLE),
        new Case("CREATE OR REPLACE VIEW v AS SELECT 1 x", "CREATE VIEW", "v", Role.TABLE),
        new Case("CREATE MATERIALIZED VIEW IF NOT EXISTS mv AS SELECT 1 x", "CREATE MATERIALIZED VIEW", "mv",
            Role.TABLE),
        new Case("CREATE SCHEMA IF NOT EXISTS memory.reports", "CREATE SCHEMA", "memory.reports", Role.SCHEMA),
        new Case("ALTER TABLE IF EXISTS memory.default.t ADD COLUMN c varchar", "ALTER TABLE", "memory.default.t",
            Role.TABLE),
        new Case("ALTER TABLE hive.s.t EXECUTE optimize(file_size_threshold => '10MB')", "ALTER TABLE", "hive.s.t",
            Role.TABLE),
        new Case("DROP TABLE IF EXISTS memory.default.t", "DROP TABLE", "memory.default.t", Role.TABLE),
        new Case("DROP SCHEMA memory.reports CASCADE", "DROP SCHEMA", "memory.reports", Role.SCHEMA),
        new Case("COMMENT ON COLUMN memory.default.t.id IS 'key'", "COMMENT ON COLUMN", "memory.default.t.id",
            Role.COLUMN),
        new Case("REFRESH MATERIALIZED VIEW hive.s.mv", "REFRESH MATERIALIZED VIEW", "hive.s.mv", Role.TABLE),
        new Case("ANALYZE hive.s.t", "ANALYZE", "hive.s.t", Role.TABLE),
        new Case("CREATE FUNCTION memory.default.twice(x bigint) RETURNS bigint RETURN x * 2", "CREATE FUNCTION",
            "memory.default.twice", Role.FUNCTION))) {
      Statement st = read(c.sql());
      assertThat(st.kind()).as(c.sql()).isEqualTo(Kind.CHANGE);
      assertThat(st.type()).as(c.sql()).isEqualTo(c.type());
      assertThat(st.target().name().text()).as(c.sql()).isEqualTo(c.target());
      assertThat(st.target().role()).as(c.sql()).isEqualTo(c.role());
    }
    // A renamed table and a LIKE source are names the connection checks too.
    assertThat(read("ALTER TABLE a.s.t RENAME TO b.s.t").refs()).extracting(r -> r.name().text())
        .containsExactly("b.s.t");
    assertThat(read("CREATE TABLE a.s.t (LIKE b.s.u INCLUDING PROPERTIES)").refs()).extracting(r -> r.name().text())
        .containsExactly("b.s.u");
  }

  @Test
  void aWriteIsPlannedByAReadOfWhatItReads() {
    record Case(String sql, String read) {}
    for (Case c : List.of(
        // INSERT: its query, after the target, a branch and a column list.
        new Case("INSERT INTO memory.default.t SELECT * FROM tpch.tiny.nation", "SELECT * FROM tpch.tiny.nation"),
        new Case("INSERT INTO t (id, \"Name, Full\") VALUES (1, 'x, y')", "VALUES (1, 'x, y')"),
        new Case("insert into t (select 1)", "select 1"),
        new Case("INSERT INTO t (a) ((SELECT 1) UNION ALL (SELECT 2))", "(SELECT 1) UNION ALL (SELECT 2)"),
        new Case("INSERT INTO t WITH x AS (SELECT 1 a) SELECT a FROM x;", "WITH x AS (SELECT 1 a) SELECT a FROM x"),
        new Case("INSERT INTO iceberg.s.t @ audit (id) SELECT id FROM u -- copy\n", "SELECT id FROM u"),
        new Case("  INSERT INTO t /* the query */ SELECT 'INSERT INTO x' AS s", "SELECT 'INSERT INTO x' AS s"),
        // CREATE TABLE AS: its query, without WITH [NO] DATA and the parentheses around it.
        new Case("CREATE TABLE IF NOT EXISTS memory.default.t (a, b) COMMENT 'AS SELECT' WITH (x = 'AS') AS "
            + "SELECT 1, 2 WITH NO DATA", "SELECT 1, 2"),
        new Case("CREATE OR REPLACE TABLE t AS (WITH x AS (SELECT 1 a) SELECT a FROM x) WITH DATA",
            "WITH x AS (SELECT 1 a) SELECT a FROM x"),
        new Case("CREATE TABLE \"AS\" AS SELECT * FROM (SELECT 1 AS \"WITH\") WHERE true", "SELECT * FROM (SELECT 1 "
            + "AS \"WITH\") WHERE true"),
        // DELETE: the rows it reads.
        new Case("DELETE FROM memory.default.t WHERE id IN (SELECT id FROM hive.s.u) -- old",
            "SELECT 1 FROM memory.default.t WHERE id IN (SELECT id FROM hive.s.u)"),
        new Case("DELETE FROM \"My Table\"", "SELECT 1 FROM \"My Table\""),
        new Case("DELETE FROM t -- all old rows\nWHERE ts < DATE '2020-01-01'",
            "SELECT 1 FROM t WHERE ts < DATE '2020-01-01'"),
        new Case("DELETE FROM t @ b WHERE id = 1", "SELECT 1 FROM t WHERE id = 1"),
        // UPDATE: the new values, from the rows it reads.
        new Case("UPDATE t SET a = 'x, y = z', b = coalesce(b, ARRAY[1, 2]), \"c\" = CASE WHEN a = 1 THEN 2 ELSE 3 "
            + "END WHERE id = (SELECT max(id) FROM u)", "SELECT 'x, y = z', coalesce(b, ARRAY[1, 2]), CASE WHEN a = 1 "
            + "THEN 2 ELSE 3 END FROM t WHERE id = (SELECT max(id) FROM u)"),
        new Case("UPDATE hive.s.t SET paid = true", "SELECT true FROM hive.s.t"),
        // MERGE: every condition and value, over the target joined with the source.
        new Case("MERGE INTO memory.default.t AS a USING (SELECT * FROM tpch.tiny.nation) n ON a.id = n.nationkey "
            + "WHEN MATCHED AND n.name = 'WHEN MATCHED' THEN UPDATE SET name = n.name, note = 'x' "
            + "WHEN MATCHED THEN DELETE "
            + "WHEN NOT MATCHED THEN INSERT (id, name) VALUES (n.nationkey, upper(n.name))",
            "SELECT n.name = 'WHEN MATCHED', n.name, 'x', n.nationkey, upper(n.name) FROM memory.default.t a FULL "
                + "JOIN (SELECT * FROM tpch.tiny.nation) n ON a.id = n.nationkey"),
        new Case("MERGE INTO t USING u ON t.id = u.id WHEN MATCHED THEN DELETE",
            "SELECT 1 FROM t FULL JOIN u ON t.id = u.id"),
        new Case("MERGE INTO t \"T\" USING s.u AS \"U\" ON CASE WHEN \"U\".k = 1 THEN \"T\".id ELSE 0 END = \"U\".id "
            + "WHEN NOT MATCHED AND (SELECT count(*) FROM v) > 0 THEN INSERT VALUES (\"U\".id, ROW(1, 2))",
            "SELECT (SELECT count(*) FROM v) > 0, \"U\".id, ROW(1, 2) FROM t \"T\" FULL JOIN s.u AS \"U\" ON CASE "
                + "WHEN \"U\".k = 1 THEN \"T\".id ELSE 0 END = \"U\".id"),
        // Statements that read and write one table: the table.
        new Case("ANALYZE hive.s.t WITH (partitions = ARRAY[ARRAY['1']])", "SELECT 1 FROM hive.s.t"),
        new Case("REFRESH MATERIALIZED VIEW hive.s.mv", "SELECT 1 FROM hive.s.mv"),
        new Case("ALTER TABLE hive.s.t EXECUTE optimize(file_size_threshold => '10MB') WHERE part = 1",
            "SELECT 1 FROM hive.s.t WHERE part = 1"),
        new Case("ALTER TABLE iceberg.s.t EXECUTE expire_snapshots", "SELECT 1 FROM iceberg.s.t"),
        new Case("ALTER MATERIALIZED VIEW iceberg.s.mv EXECUTE optimize", "SELECT 1 FROM iceberg.s.mv"))) {
      Statement st = read(c.sql());
      assertThat(st.kind()).as(c.sql()).isEqualTo(Kind.CHANGE);
      assertThat(st.planned()).as(c.sql()).isEqualTo(c.read());
      // The read is one query the gateway reads as a read: never the write itself.
      assertThat(read(st.planned()).kind()).as(c.sql()).isEqualTo(Kind.READ);
    }
    // What is sent is still the statement itself.
    assertThat(read("INSERT INTO t SELECT 1;").sql()).isEqualTo("INSERT INTO t SELECT 1");
  }

  @Test
  void aDataDefinitionStatementHasNoRead() {
    for (String sql : List.of("CREATE TABLE t (id bigint)", "CREATE TABLE a.s.t (LIKE b.s.u)", "DROP TABLE t",
        "ALTER TABLE t ADD COLUMN c varchar", "ALTER TABLE t RENAME TO u", "ALTER MATERIALIZED VIEW mv SET PROPERTIES "
            + "x = 1", "TRUNCATE TABLE t", "COMMENT ON TABLE t IS 'x'", "CREATE VIEW v AS SELECT * FROM t",
        "CREATE MATERIALIZED VIEW mv AS SELECT 1 x", "CREATE SCHEMA memory.s", "ALTER VIEW v REFRESH",
        "CREATE FUNCTION memory.default.twice(x bigint) RETURNS bigint RETURN x * 2")) {
      Statement st = read(sql);
      assertThat(st.kind()).as(sql).isEqualTo(Kind.CHANGE);
      assertThat(st.planned()).as(sql).isNull();
    }
  }

  @Test
  void aWriteWhoseReadCantBeBuiltWithCertaintyIsRefused() {
    for (String sql : List.of("INSERT INTO t", "INSERT INTO t (a, b)", "INSERT INTO t (a b) SELECT 1, 2",
        "INSERT INTO t (a,) SELECT 1", "INSERT INTO t TABLE u", "INSERT INTO t @ SELECT 1", "INSERT INTO t x SELECT 1",
        "INSERT INTO t VALUES (1", "INSERT INTO t SELECT ARRAY[1, 2) FROM u",
        "CREATE TABLE t AS", "CREATE TABLE t AS WITH NO DATA", "CREATE TABLE t AS TABLE u",
        "DELETE FROM t x WHERE true", "DELETE FROM t WHERE", "DELETE FROM t USING u",
        "UPDATE t SET (a, b) = (1, 2)", "UPDATE t SET a = WHERE id = 1", "UPDATE t SET a = 1, WHERE id = 1",
        "UPDATE t SET a = 1 WHERE", "UPDATE t a SET a = 1", "UPDATE t SET a = CASE WHEN b THEN 1 WHERE c",
        "UPDATE t SET a.b = 1",
        "MERGE INTO t USING a JOIN b ON a.id = b.id ON t.id = a.id WHEN MATCHED THEN DELETE",
        "MERGE INTO t USING u ON t.id = u.id", "MERGE INTO t USING u WHEN MATCHED THEN DELETE",
        "MERGE INTO t USING u ON t.id = u.id WHEN MATCHED THEN UPSERT",
        "MERGE INTO t USING u ON t.id = u.id WHEN MATCHED AND THEN DELETE",
        "MERGE INTO t USING u ON t.id = u.id WHEN MATCHED THEN DELETE x",
        "MERGE INTO t USING u ON t.id = u.id WHEN NOT MATCHED BY SOURCE THEN DELETE",
        "MERGE INTO t USING u ON t.id = u.id WHEN NOT MATCHED THEN INSERT VALUES ()",
        "MERGE INTO t USING u ON t.id = u.id WHEN NOT MATCHED THEN INSERT VALUES (1) x",
        "MERGE INTO t USING u ON t.id = u.id WHEN MATCHED THEN UPDATE SET",
        "ANALYZE t x", "REFRESH MATERIALIZED VIEW mv x", "ALTER TABLE t EXECUTE", "ALTER TABLE t EXECUTE x y",
        "ALTER TABLE t EXECUTE optimize(a => 1 WHERE p = 1")) {
      Statement st = read(sql);
      assertThat(st.kind()).as(sql).isIn(Kind.UNREADABLE, Kind.FORBIDDEN);
      assertThat(st.problem()).as(sql).isNotBlank();
      assertThat(st.planned()).as(sql).isNull();
    }
    // A CREATE TABLE AS whose query sets session properties is refused as the read would be.
    Statement session = read("CREATE TABLE t AS WITH SESSION query_max_run_time = '1h' SELECT 1");
    assertThat(session.kind()).isEqualTo(Kind.FORBIDDEN);
    assertThat(session.problem()).contains("WITH SESSION");
    assertThat(read("INSERT INTO t TABLE u").problem()).contains("SELECT * FROM table");
    assertThat(read("MERGE INTO t USING a JOIN b ON a.id = b.id ON t.id = a.id WHEN MATCHED THEN DELETE").problem())
        .contains("subquery");
  }

  @Test
  void theTextOfAnExplainedQueryIsWhereItsTokensAre() {
    // Leading white space before the statement doesn't move where the explained query starts.
    assertThat(read("  \n EXPLAIN SELECT 1").planned()).isEqualTo("SELECT 1");
    assertThat(read("\tSHOW STATS FOR (SELECT * FROM nation)").planned()).isEqualTo("SELECT * FROM nation");
  }

  @Test
  void showAndDescribeNameWhatTheyShow() {
    assertThat(read("SHOW CATALOGS").catalogs()).isTrue();
    assertThat(read("SHOW SCHEMAS FROM hive").refs()).extracting(r -> r.name().catalog(r.role()))
        .containsExactly("hive");
    assertThat(read("SHOW TABLES IN hive.sales").refs()).extracting(r -> r.name().catalog(r.role()))
        .containsExactly("hive");
    assertThat(read("DESCRIBE hive.sales.orders").refs()).extracting(r -> r.name().catalog(r.role()))
        .containsExactly("hive");
    assertThat(read("DESCRIBE orders").refs().getFirst().name().catalog(Role.TABLE)).isNull();
    Statement create = read("SHOW CREATE TABLE secret.s.t");
    assertThat(create.planless()).isTrue();
    assertThat(create.refs().getFirst().name().catalog(create.refs().getFirst().role())).isEqualTo("secret");
    assertThat(read("SHOW STATS FOR secret.s.t").planless()).isTrue();
    assertThat(read("SHOW BRANCHES FROM TABLE iceberg.s.t").refs()).hasSize(1);
    assertThat(read("SHOW ROLES FROM hive").refs()).hasSize(1);
  }

  @Test
  void anArgumentNameIsOneDottedName() {
    assertThat(TrinoSql.qualified("tpch.tiny.nation").parts()).containsExactly("tpch", "tiny", "nation");
    assertThat(TrinoSql.qualified("\"Sales\".\"Order Lines\"").parts()).containsExactly("Sales", "Order Lines");
    assertThat(TrinoSql.qualified("ORDERS").parts()).containsExactly("orders");
    for (String bad : List.of("a.b.c.d", "a; DROP TABLE b", "a b", "a.", "'a'", "a -- x", "a;", "")) {
      assertThat(TrinoSql.qualified(bad)).as(bad).isNull();
    }
  }
}
