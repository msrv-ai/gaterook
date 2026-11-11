package gaterook.plugins.h2;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** H2's SQL as GateRook reads it, without I/O: what a read admits, what a change names, what's refused everywhere. */
class H2SqlTest {

  @Test
  void queriesThatCallOnlyH2sBuiltInsAreReads() {
    for (String q : new String[] {
        "SELECT * FROM ORDERS",
        "select id, note from orders where id in (1, 2) order by id fetch first 5 rows only",
        "WITH t(a, b) AS (SELECT 1, 2), u AS (SELECT * FROM t) SELECT * FROM u",
        "WITH RECURSIVE r(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM r WHERE n < 3) SELECT n FROM r",
        "(SELECT 1) UNION (SELECT 2)",
        "VALUES (1, 'a'), (2, 'b')",
        "TABLE ORDERS",
        "SHOW TABLES",
        "EXPLAIN SELECT * FROM ORDERS",
        "EXPLAIN PLAN FOR SELECT 1",
        "SELECT COUNT(*), SUM(TOTAL), LISTAGG(NOTE, ',') WITHIN GROUP (ORDER BY NOTE) FROM ORDERS",
        "SELECT ROW_NUMBER() OVER (PARTITION BY NOTE ORDER BY ID) FROM ORDERS",
        "SELECT CAST(TOTAL AS DECIMAL(10, 2)), TOTAL::NUMERIC(8, 1), CAST(NOTE AS CHARACTER VARYING(20)) FROM ORDERS",
        "SELECT LEFT(NOTE, 2), POSITION('a' IN NOTE), TRIM(LEADING 'x' FROM NOTE), SUBSTRING(NOTE FROM 1 FOR 2) FROM T",
        "SELECT CURRENT_TIMESTAMP(3), NOW(), YEAR(AT), DATEADD('DAY', 1, AT), EXTRACT(YEAR FROM AT) FROM T",
        "SELECT * FROM SYSTEM_RANGE(1, 10)",
        "SELECT * FROM TABLE(X INT = ARRAY[1, 2])",
        "SELECT * FROM (SELECT 1 AS A) T(B)",
        "SELECT CURRENT VALUE FOR SEQ, CURRVAL('SEQ')",
        "SELECT 'FILE_READ(''/etc/passwd'')' AS X -- FILE_READ('/etc/passwd')",
        "SELECT 1 /* CALL x() */",
        "SELECT \"Mixed\".\"Id\" FROM \"Mixed\"",
        "SELECT * FROM ORDERS WHERE NOTE LIKE ? AND ID = ?1",
        "SELECT 1;",
        "SELECT JSON_OBJECT(KEY 'a' VALUE 1), ARRAY_AGG(ID) FILTER (WHERE ID > 1) FROM ORDERS"}) {
      H2Sql.Verdict v = H2Sql.read(q);
      assertThat(v.problem()).as(q).isNull();
      assertThat(v.kind()).as(q).isEqualTo(H2Sql.Kind.READ);
    }
    assertThat(H2Sql.read("SELECT 1;").text()).isEqualTo("SELECT 1");
  }

  @Test
  void aReadCallsNoFunctionOfTheDatabaseAndNothingThatReachesOutside() {
    assertThat(H2Sql.read("SELECT MYF(1)").problem()).isEqualTo("MYF isn't one of H2's built-in functions: a "
        + "function the database defines can run Java code and change data, so reads may call H2's built-in "
        + "functions only.");
    assertThat(H2Sql.read("SELECT PUBLIC.ABS(1)").problem()).startsWith("A function named with its schema");
    assertThat(H2Sql.read("SELECT \"ABS\"(1)").problem()).startsWith("A function written in quotes");
    assertThat(H2Sql.read("SELECT FILE_READ('/etc/passwd')").problem()).startsWith("FILE_READ reaches outside the "
        + "database");
    for (String q : new String[] {"SELECT * FROM CSVREAD('/tmp/x.csv')", "SELECT CSVWRITE('/tmp/x.csv', 'SELECT 1')",
        "SELECT * FROM LINK_SCHEMA('L', '', 'jdbc:h2:mem:', 'sa', '', 'PUBLIC')", "SELECT CANCEL_SESSION(3)",
        "SELECT FILE_WRITE(X'00', '/tmp/x')", "SELECT abort_session(1)"}) {
      assertThat(H2Sql.read(q).problem()).as(q).contains("reaches outside the database");
    }
    assertThat(H2Sql.read("SELECT NEXTVAL('SEQ')").problem()).startsWith("NEXTVAL advances a sequence");
    assertThat(H2Sql.read("SELECT NEXT VALUE FOR SEQ").problem()).startsWith("NEXT VALUE FOR advances a sequence");
    assertThat(H2Sql.read("SELECT * FROM FINAL TABLE (INSERT INTO T VALUES (1))").problem())
        .startsWith("FINAL TABLE runs a change");
    assertThat(H2Sql.read("SELECT * FROM OLD TABLE (DELETE FROM T)").problem()).startsWith("OLD TABLE runs");
    assertThat(H2Sql.read("SELECT * FROM T FOR UPDATE").problem()).isEqualTo("FOR UPDATE locks rows: a read here "
        + "doesn't lock.");
    assertThat(H2Sql.read("EXPLAIN ANALYZE SELECT 1").problem()).startsWith("EXPLAIN ANALYZE runs the statement");
    assertThat(H2Sql.read("EXPLAIN DELETE FROM T").problem()).startsWith("EXPLAIN here explains a query only");
    assertThat(H2Sql.read("WITH t AS (SELECT 1) DELETE FROM T").problem()).startsWith("A WITH here must end in a "
        + "query");
  }

  @Test
  void whatChangesOrControlsTheSessionIsNoRead() {
    assertThat(H2Sql.read("DELETE FROM T").problem()).startsWith("DELETE changes the database: h2_query only reads.");
    assertThat(H2Sql.read("CALL ABS(1)").problem()).startsWith("CALL runs a function");
    assertThat(H2Sql.read("SET MODE MySQL").problem()).startsWith("SET changes the session");
    assertThat(H2Sql.read("SCRIPT TO '/tmp/x.sql'").problem()).startsWith("SCRIPT reads or writes files");
    assertThat(H2Sql.read("RUNSCRIPT FROM '/tmp/x.sql'").problem()).startsWith("RUNSCRIPT reads or writes files");
    assertThat(H2Sql.read("SHUTDOWN").problem()).startsWith("SHUTDOWN closes the database");
    assertThat(H2Sql.read("COMMIT").problem()).startsWith("Transactions are GateRook's");
    assertThat(H2Sql.read("EXECUTE IMMEDIATE 'DROP TABLE T'").problem()).startsWith("EXECUTE IMMEDIATE runs SQL");
    assertThat(H2Sql.read("GRANT SELECT ON T TO U").problem()).startsWith("Rights are the person's");
  }

  @Test
  void whatCantBeReadWithCertaintyIsRefused() {
    for (String q : new String[] {"", "  ", "SELECT 'open", "SELECT \"open", "SELECT /* open", "SELECT $$ open",
        "SELECT /* a /* nested */ */ 1", "{call ABS(1)}", "SELECT {fn ABS(1)}", "SELECT `x` FROM T", "SELECT #x",
        "SELECT @x", "SELECT 1; SELECT 2", "SELECT (1", "SELECT 1)", "SELECT 1abc", "SELECT X := 1",
        "x".repeat(H2Sql.MAX_LENGTH + 1)}) {
      assertThat(H2Sql.read(q).ok()).as(q).isFalse();
      assertThat(H2Sql.change(q).ok()).as(q).isFalse();
    }
    assertThat(H2Sql.read("{call ABS(1)}").problem()).startsWith("GateRook doesn't run JDBC escapes");
    assertThat(H2Sql.read("SELECT 1; SELECT 2").problem()).isEqualTo("One statement per call: this text holds more "
        + "than one.");
  }

  @Test
  void changesNameWhatTheyChange() {
    assertThat(H2Sql.change("INSERT INTO ORDERS (ID) VALUES (1)")).extracting(H2Sql.Verdict::kind,
        H2Sql.Verdict::verb, H2Sql.Verdict::target).containsExactly(H2Sql.Kind.CHANGE, "INSERT", "ORDERS");
    assertThat(H2Sql.change("update \"Sales\".\"Orders\" set NOTE = 'x'").target()).isEqualTo("\"Sales\".\"Orders\"");
    assertThat(H2Sql.change("DELETE FROM PUBLIC.ORDERS WHERE ID = 1").target()).isEqualTo("PUBLIC.ORDERS");
    assertThat(H2Sql.change("MERGE INTO ORDERS KEY (ID) VALUES (1, 'x')").target()).isEqualTo("ORDERS");
    assertThat(H2Sql.change("TRUNCATE TABLE ORDERS").verb()).isEqualTo("TRUNCATE TABLE");
    assertThat(H2Sql.change("CREATE TABLE IF NOT EXISTS T (ID INT PRIMARY KEY)")).extracting(H2Sql.Verdict::verb,
        H2Sql.Verdict::target).containsExactly("CREATE TABLE", "T");
    assertThat(H2Sql.change("CREATE UNIQUE INDEX BY_NOTE ON ORDERS(NOTE)")).extracting(H2Sql.Verdict::verb,
        H2Sql.Verdict::target).containsExactly("CREATE INDEX", "ORDERS");
    assertThat(H2Sql.change("CREATE OR REPLACE VIEW V AS SELECT 1").target()).isEqualTo("V");
    assertThat(H2Sql.change("ALTER TABLE ORDERS ADD COLUMN NOTE2 VARCHAR(10)").verb()).isEqualTo("ALTER TABLE");
    assertThat(H2Sql.change("DROP TABLE IF EXISTS ORDERS CASCADE")).extracting(H2Sql.Verdict::verb,
        H2Sql.Verdict::target).containsExactly("DROP TABLE", "ORDERS");
    assertThat(H2Sql.change("COMMENT ON TABLE ORDERS IS 'x'").verb()).isEqualTo("COMMENT ON TABLE");
    assertThat(H2Sql.change("DROP ALIAS MYF").ok()).isFalse();
  }

  @Test
  void javaCodeLinkedTablesUsersRightsAndFilesAreRefusedEvenWithApproval() {
    assertThat(H2Sql.change("CREATE ALIAS X FOR 'java.lang.Runtime.exec'").problem()).isEqualTo("ALIAS runs Java "
        + "code on the server: GateRook never creates one.");
    assertThat(H2Sql.change("CREATE TRIGGER T BEFORE INSERT ON X CALL 'a.B'").problem()).startsWith("TRIGGER runs");
    assertThat(H2Sql.change("CREATE AGGREGATE A FOR 'a.B'").problem()).startsWith("AGGREGATE runs");
    assertThat(H2Sql.change("CREATE LINKED TABLE L('', 'jdbc:x', '', '', 'T')").problem())
        .startsWith("A linked table reaches another database");
    assertThat(H2Sql.change("CREATE USER U PASSWORD 'x'").problem()).startsWith("Users and roles are the person's");
    assertThat(H2Sql.change("ALTER USER SA ADMIN TRUE").problem()).startsWith("Users and roles are the person's");
    assertThat(H2Sql.change("GRANT ALL ON T TO U").problem()).startsWith("Rights are the person's");
    assertThat(H2Sql.change("INSERT INTO T SELECT FILE_READ('/etc/passwd')").problem()).startsWith("FILE_READ "
        + "reaches outside");
    assertThat(H2Sql.change("BACKUP TO '/tmp/x.zip'").problem()).startsWith("BACKUP reads or writes files");
    assertThat(H2Sql.change("SELECT 1").problem()).startsWith("SELECT only reads");
    assertThat(H2Sql.change("CHECKPOINT SYNC").problem()).startsWith("CHECKPOINT is the database's maintenance");
    assertThat(H2Sql.change("CREATE TABLE T(ID INT) ENGINE \"org.example.Engine\"").problem())
        .startsWith("A table engine is Java code");
  }

  @Test
  void theFunctionListHasNoDuplicatesAndLeavesOutWhatReachesOutside() {
    // Set.of would refuse a duplicate when the class loads; the list must also never admit these.
    assertThat(H2Sql.READ_FUNCTIONS).doesNotContainAnyElementsOf(H2Sql.OUTSIDE).doesNotContain("NEXTVAL");
    Set<String> seen = new HashSet<>(H2Sql.READ_FUNCTIONS);
    assertThat(seen).hasSameSizeAs(H2Sql.READ_FUNCTIONS);
  }
}
