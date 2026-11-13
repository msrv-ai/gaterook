package com.gaterook.connectors.snowflake;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.Env;
import com.gaterook.connectors.snowflake.SnowflakeClassifier.Kind;
import com.gaterook.connectors.snowflake.SnowflakeClassifier.Refusal;
import com.gaterook.connectors.snowflake.SnowflakeClassifier.Statement;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Snowflake SQL read by its own tokens, and what each tool may run, without I/O. */
class SnowflakeClassifierTest {

  private static SnowflakeSettings settings(String... tables) {
    ObjectNode c = JsonNodeFactory.instance.objectNode().put("account", "myorg-myaccount").put("user", "gw_reader")
        .put("database", "analytics").put("schema", "public");
    c.putObject("authentication").put("method", "keyPair");
    var list = c.putArray("tables");
    for (String t : tables) {
      list.add(t);
    }
    try {
      return SnowflakeSettings.of(new ConnectionConfig("c", "dev-sf", "snowflake", Env.DEV, 1, c, Set.of(), null));
    } catch (ConnectorException e) {
      throw new AssertionError(e);
    }
  }

  private static final SnowflakeSettings OPEN = settings();
  private static final SnowflakeSettings LISTED = settings("ANALYTICS.PUBLIC.ORD*", "ANALYTICS.PUBLIC.CUSTOMERS");

  private static Refusal query(String sql) {
    return SnowflakeClassifier.query(SnowflakeClassifier.analyze(sql), OPEN, "dev");
  }

  private static Refusal query(String sql, SnowflakeSettings s) {
    return SnowflakeClassifier.query(SnowflakeClassifier.analyze(sql), s, "dev");
  }

  private static Refusal execute(String sql) {
    return SnowflakeClassifier.execute(SnowflakeClassifier.analyze(sql), OPEN, "dev");
  }

  private static Refusal execute(String sql, SnowflakeSettings s) {
    return SnowflakeClassifier.execute(SnowflakeClassifier.analyze(sql), s, "dev");
  }

  // ── The lexer ───────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void stringsNamesAndCommentsAreReadAsSnowflakeReadsThem() {
    for (String sql : List.of(
        "SELECT 'it\\'s; DELETE FROM t' AS s",
        "SELECT 'it''s; DROP TABLE t' AS s",
        "SELECT $$ it's ; DELETE FROM t $$ AS s",
        "SELECT \"DELETE\"\"; x\" FROM t",
        "SELECT 1 -- ; DELETE FROM t",
        "SELECT 1 // ; DELETE FROM t",
        "SELECT 1 /* ; DELETE FROM t */",
        "SELECT 1;",
        "SELECT 1; -- only a comment after it",
        "(SELECT 1) UNION ALL (SELECT 2)")) {
      Statement st = SnowflakeClassifier.analyze(sql);
      assertThat(st.readable()).as(sql).isTrue();
      assertThat(st.count()).as(sql).isEqualTo(1);
      assertThat(st.kind()).as(sql).isEqualTo(Kind.READ);
      assertThat(SnowflakeClassifier.words(st)).as(sql).doesNotContain("DELETE", "DROP");
      assertThat(query(sql)).as(sql).isNull();
    }
    SnowflakeLexer.Scan scan = SnowflakeLexer.scan("SELECT \"a\"\"b\", 'c\\'d', $$e'f$$ FROM x.\"Y\"");
    assertThat(scan.tokens()).extracting(SnowflakeLexer.Token::text).containsExactly("SELECT", "a\"b", ",",
        "c\\'d", ",", "e'f", "FROM", "X", ".", "Y");
  }

  @Test
  void whatTheLexerCantReadIsNeverRun() {
    for (String sql : List.of(
        "SELECT 'open",
        "SELECT 'ends in a backslash\\'",
        "SELECT \"open",
        "SELECT $$ open",
        "SELECT 1 /* open",
        "SELECT 1 /* outer /* inner */ */",
        "SELECT a$$ DELETE $$",
        "SELECT x$$y FROM t",
        "SELECT 1\u0000",
        "SELECT 1 -- ‮ hidden",
        "SELECT \"name​\" FROM t",
        "SELECT 1 FROM t WHERE x = 1; DELETE FROM t",
        "SELECT ＤＥＬＥＴＥ FROM t",
        "SELECT (1",
        "SELECT 1)",
        "",
        "   ",
        "-- only a comment")) {
      Statement st = SnowflakeClassifier.analyze(sql);
      assertThat(st.readable()).as(sql).isFalse();
      assertThat(query(sql).rule()).as(sql).isEqualTo("unparsed");
      assertThat(execute(sql).rule()).as(sql).isEqualTo("unparsed");
    }
    // A string's own characters are data.
    assertThat(SnowflakeClassifier.analyze("SELECT 'Grüße ​'").readable()).isTrue();
  }

  @Test
  void severalStatementsAreRefused() {
    for (String sql : List.of("SELECT 1; SELECT 2", "SELECT 1; DELETE FROM t", "SELECT 1;;SELECT 2")) {
      assertThat(query(sql).rule()).as(sql).isEqualTo("multi-statement");
      assertThat(execute(sql).rule()).as(sql).isEqualTo("multi-statement");
    }
    assertThat(query("SHOW TABLES ->> SELECT * FROM $1").rule()).isEqualTo("forbidden");
  }

  // ── Kinds ───────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void statementsAreReadsChangesOrForbidden() {
    for (String sql : List.of("SELECT 1", "select * from t", "WITH a AS (SELECT 1) SELECT * FROM a", "SHOW TABLES",
        "DESCRIBE TABLE t", "desc table t",
        "WITH RECURSIVE r (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM r WHERE n < 3) SELECT n FROM r")) {
      assertThat(SnowflakeClassifier.analyze(sql).kind()).as(sql).isEqualTo(Kind.READ);
    }
    for (String[] c : new String[][] {
        {"INSERT INTO orders VALUES (1)", "INSERT", "ORDERS"},
        {"INSERT OVERWRITE INTO a.b.\"T\" SELECT 1", "INSERT", "A.B.T"},
        {"INSERT INTO \"Mixed\".t VALUES (1)", "INSERT", "\"Mixed\".T"},
        {"UPDATE orders SET x = 1 WHERE id = 2", "UPDATE", "ORDERS"},
        {"DELETE FROM public.orders WHERE id = 2", "DELETE", "PUBLIC.ORDERS"},
        {"MERGE INTO orders o USING staged s ON o.id = s.id WHEN MATCHED THEN DELETE", "MERGE", "ORDERS"},
        {"TRUNCATE TABLE IF EXISTS orders", "TRUNCATE", "ORDERS"},
        {"CREATE OR REPLACE TRANSIENT TABLE notes (id INT)", "CREATE TABLE", "NOTES"},
        {"CREATE TABLE IF NOT EXISTS notes (id INT)", "CREATE TABLE", "NOTES"},
        {"ALTER TABLE orders ADD COLUMN note VARCHAR", "ALTER TABLE", "ORDERS"},
        {"DROP VIEW IF EXISTS v", "DROP VIEW", "V"},
        {"CREATE MATERIALIZED VIEW mv AS SELECT 1", "CREATE MATERIALIZED VIEW", "MV"},
        {"UNDROP TABLE orders", "UNDROP TABLE", "ORDERS"},
        {"COMMENT ON COLUMN orders.status IS 'x'", "COMMENT", "ORDERS.STATUS"},
        {"CREATE WAREHOUSE wh", "CREATE WAREHOUSE", "WH"},
        {"CREATE OR REPLACE PROCEDURE p() RETURNS INT LANGUAGE SQL AS $$ BEGIN RETURN 1; END $$", "CREATE PROCEDURE",
            "P"}}) {
      Statement st = SnowflakeClassifier.analyze(c[0]);
      assertThat(st.kind()).as(c[0]).isEqualTo(Kind.CHANGE);
      assertThat(st.type()).as(c[0]).isEqualTo(c[1]);
      assertThat(st.target().display()).as(c[0]).isEqualTo(c[2]);
      assertThat(execute(c[0])).as(c[0]).isNull();
      assertThat(query(c[0]).rule()).as(c[0]).isEqualTo("query-writes");
    }
    assertThat(SnowflakeClassifier.analyze("INSERT ALL INTO a VALUES (1) INTO b VALUES (2) SELECT 1").target())
        .isNull();
  }

  @Test
  void sessionTransactionCodeFilesAndAccessAreForbiddenForBothTools() {
    for (String sql : List.of("USE ROLE accountadmin", "USE WAREHOUSE big", "ALTER SESSION SET QUERY_TAG = 'x'",
        "SET x = 1", "UNSET x", "BEGIN", "BEGIN TRANSACTION", "START TRANSACTION", "COMMIT", "ROLLBACK",
        "CALL p()", "EXECUTE IMMEDIATE 'DELETE FROM t'", "EXECUTE IMMEDIATE $$ DELETE FROM t $$", "EXECUTE TASK t",
        "DECLARE x INT; BEGIN RETURN 1; END",
        "WITH p AS PROCEDURE () RETURNS INT LANGUAGE SQL AS $$ BEGIN RETURN 1; END $$ CALL p()",
        "PUT file:///tmp/x @stage", "GET @stage file:///tmp/", "COPY INTO t FROM @stage",
        "COPY INTO @stage FROM t", "LIST @stage", "REMOVE @stage", "RM @stage",
        "GRANT SELECT ON TABLE t TO ROLE r", "REVOKE ROLE sysadmin FROM USER u",
        "CREATE USER eve PASSWORD = 'x'", "ALTER USER gw_reader SET RSA_PUBLIC_KEY = 'MII'", "DROP USER u",
        "CREATE ROLE r", "CREATE DATABASE ROLE db.r", "CREATE SECURITY INTEGRATION i TYPE = SAML2",
        "CREATE OR REPLACE EXTERNAL ACCESS INTEGRATION i ALLOWED_NETWORK_RULES = (r) ENABLED = TRUE",
        "CREATE STORAGE INTEGRATION s TYPE = EXTERNAL_STAGE", "CREATE NETWORK POLICY p ALLOWED_IP_LIST = ('0.0.0.0/0')",
        "ALTER NETWORK POLICY p SET ALLOWED_IP_LIST = ()", "CREATE NETWORK RULE r MODE = EGRESS",
        "ALTER ACCOUNT SET x = 1",
        "CREATE SHARE s", "ALTER SHARE s ADD ACCOUNTS = other", "CREATE SECRET s TYPE = PASSWORD",
        "ALTER AUTHENTICATION POLICY p SET MFA_ENROLLMENT = OPTIONAL", "CREATE PASSWORD POLICY p",
        "ALTER SESSION POLICY p SET SESSION_IDLE_TIMEOUT_MINS = 600", "DROP DATABASE analytics",
        "COMMENT ON USER u IS 'x'", "CREATE OR REPLACE FAILOVER GROUP g OBJECT_TYPES = DATABASES")) {
      Statement st = SnowflakeClassifier.analyze(sql);
      assertThat(st.kind()).as(sql).isEqualTo(Kind.FORBIDDEN);
      Refusal q = query(sql);
      Refusal e = execute(sql);
      assertThat(q.rule()).as(sql).isIn("forbidden", "multi-statement");
      assertThat(e.rule()).as(sql).isIn("forbidden", "multi-statement");
    }
    assertThat(execute("USE ROLE x").hint()).contains("DATABASE.SCHEMA.TABLE");
    assertThat(execute("COMMIT").reason()).contains("commits every statement");
    assertThat(execute("GRANT SELECT ON TABLE t TO ROLE r").reason()).contains("changes access");
  }

  @Test
  void eachToolRefusesTheOthersWork() {
    assertThat(execute("SELECT 1").rule()).isEqualTo("execute-read");
    assertThat(execute("SHOW TABLES").rule()).isEqualTo("execute-read");
    assertThat(query("EXPLAIN SELECT 1").rule()).isEqualTo("unknown-statement");
    assertThat(execute("EXPLAIN SELECT 1").rule()).isEqualTo("unknown-statement");
    assertThat(query("WITH a AS (SELECT 1) DELETE FROM t").rule()).isEqualTo("unknown-statement");
    assertThat(query("VALUES (1)").rule()).isEqualTo("unknown-statement");
    assertThat(execute("CREATE FROBNICATOR f").rule()).isEqualTo("unknown-statement");
  }

  // ── Reads ───────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void readsCallOnlySystemFunctionsThatRead() {
    assertThat(query("SELECT SYSTEM$TYPEOF(1)")).isNull();
    assertThat(query("SELECT SYSTEM$CLUSTERING_INFORMATION('orders')")).isNull();
    for (String sql : List.of("SELECT SYSTEM$CANCEL_QUERY('01a')", "SELECT system$abort_session(1)",
        "SELECT SYSTEM$SEND_EMAIL('i', 'a@example.com', 's', 'b')", "SELECT \"SYSTEM$CANCEL_ALL_QUERIES\"(1)",
        "SELECT SYSTEM$WAIT(10)", "SELECT 1 WHERE SYSTEM$PIPE_FORCE_RESUME('p') IS NOT NULL")) {
      assertThat(query(sql).rule()).as(sql).isEqualTo("function-denied");
    }
    assertThat(query("SELECT IDENTIFIER('SYSTEM$CANCEL_QUERY')('x')").rule()).isEqualTo("function-denied");
    // The words in strings are data: a query may look for them.
    assertThat(query("SELECT * FROM t WHERE note = 'SYSTEM$CANCEL_QUERY'")).isNull();
    assertThat(query("SELECT seq.NEXTVAL").rule()).isEqualTo("forbidden");
    assertThat(query("SELECT * FROM t FOR UPDATE").rule()).isEqualTo("forbidden");
    assertThat(query("SELECT t.\"FOR\", t.update FROM t")).isNull();
  }

  @Test
  void aTableListLimitsWhatAReadNames() {
    assertThat(query("SELECT * FROM orders", LISTED)).isNull();
    assertThat(query("DESCRIBE TABLE orders", LISTED)).isNull();
    assertThat(query("DESC TABLE analytics.public.customers", LISTED)).isNull();
    for (String sql : List.of("SHOW TABLES", "DESCRIBE TABLE hr.people", "DESCRIBE USER u", "DESCRIBE VIEW secrets",
        "SELECT * FROM INFORMATION_SCHEMA.COLUMNS", "SELECT * FROM \"INFORMATION_SCHEMA\".tables",
        "SELECT * FROM TABLE(RESULT_SCAN('01a'))", "SELECT GET_DDL('table', 'x')",
        "SELECT SYSTEM$CLUSTERING_INFORMATION('secrets')", "SELECT $1 FROM @s", "SELECT * FROM TABLE(INFER_SCHEMA("
            + "LOCATION => '@s'))")) {
      assertThat(query(sql, LISTED).rule()).as(sql).isEqualTo("table-not-allowed");
    }
  }

  // ── Changes with a table list ───────────────────────────────────────────────────────────────────────────────

  @Test
  void aTableListLimitsWhatAChangeChangesAndReads() {
    for (String sql : List.of("INSERT INTO orders (id) VALUES (1)", "UPDATE orders SET x = 1 WHERE id = 2",
        "DELETE FROM analytics.public.orders_2026 WHERE id = 2", "MERGE INTO orders o USING customers c ON o.id = c.id "
            + "WHEN MATCHED THEN UPDATE SET o.x = c.x",
        "UPDATE orders o SET x = c.x FROM customers c WHERE o.id = c.id",
        "DELETE FROM orders USING customers c, orders_old WHERE orders.id = c.id", "TRUNCATE TABLE orders",
        "CREATE TABLE orders_new (id INT)", "ALTER TABLE orders ADD COLUMN note VARCHAR", "DROP TABLE orders_old",
        "COMMENT ON COLUMN orders.status IS 'x'", "UPDATE orders SET name = 'a' WHERE name LIKE 'b%'",
        "UPDATE orders SET y = EXTRACT(YEAR FROM created) WHERE id = 1")) {
      assertThat(execute(sql, LISTED)).as(sql).isNull();
    }
    for (String sql : List.of("INSERT INTO secrets VALUES (1)", "UPDATE hr.people.salaries SET x = 1",
        "INSERT INTO orders SELECT * FROM secrets", "INSERT ALL INTO orders VALUES (1) SELECT 1",
        "UPDATE orders SET x = (SELECT max(x) FROM secrets)", "UPDATE orders o SET x = s.x FROM secrets s",
        "MERGE INTO orders USING (SELECT * FROM secrets) s ON 1 = 1 WHEN MATCHED THEN DELETE",
        "DELETE FROM orders USING secrets", "CREATE TABLE orders_copy CLONE secrets",
        "CREATE TABLE orders_like LIKE secrets", "ALTER TABLE orders RENAME TO secrets",
        "ALTER TABLE orders SWAP WITH secrets",
        "CREATE VIEW orders_v AS SELECT 1", "CREATE SCHEMA analytics.other", "COPY INTO orders FROM @s",
        "INSERT INTO orders SELECT $1 FROM @s", "INSERT INTO orders VALUES (GET_DDL('table', 'secrets'))",
        "INSERT INTO IDENTIFIER('secrets') VALUES (1)", "UPDATE orders o SET x = 1 FROM customers c JOIN secrets s "
            + "ON c.id = s.id")) {
      Refusal r = execute(sql, LISTED);
      assertThat(r).as(sql).isNotNull();
      assertThat(r.rule()).as(sql).isIn("table-not-allowed", "forbidden");
    }
  }
}
