package ai.msrv.gaterook.connectors.access;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Access SQL as GateRook reads it, without I/O. */
class AccessSqlTest {

  @Test
  void selectsInAccessSqlAreReads() {
    for (String q : new String[] {
        "SELECT * FROM Orders",
        "SELECT ID, Note & '!' AS X, IIf(Paid, 'y', 'n') AS P, Format(Total, '0.00') FROM Orders WHERE Note LIKE 'f*'",
        "SELECT [Order Lines].N FROM [Order Lines] WHERE OrderID = 1",
        "SELECT * FROM Orders WHERE At >= #2026-11-01# AND Note = \"first\"",
        "SELECT TOP 5 * FROM Orders ORDER BY ID DESC",
        "WITH t AS (SELECT ID FROM Orders) SELECT * FROM t",
        "(SELECT 1 FROM Orders) UNION (SELECT 2 FROM Orders)",
        "SELECT Count(*), Sum(Total), Max(At) FROM Orders GROUP BY Paid HAVING Count(*) > 0",
        "SELECT * FROM Orders WHERE ID = ? AND Note <> 'it''s'",
        "SELECT 'LOAD_FILE(x)' AS L, \"DLookup(1)\" AS D FROM Orders -- LOAD_FILE('/etc/passwd')",
        "SELECT 1 FROM Orders /* INTO x */;"}) {
      AccessSql.Verdict v = AccessSql.read(q);
      assertThat(v.problem()).as(q).isNull();
      assertThat(v.kind()).as(q).isEqualTo(AccessSql.Kind.READ);
    }
    assertThat(AccessSql.read("SELECT 1 FROM Orders;").text()).isEqualTo("SELECT 1 FROM Orders");
  }

  @Test
  void aReadNeitherReadsFilesNorRunsSqlFromTextNorMakesATable() {
    assertThat(AccessSql.read("SELECT LOAD_FILE('/etc/passwd') FROM Orders").problem())
        .isEqualTo("LOAD_FILE reads files outside the database: GateRook never runs it.");
    assertThat(AccessSql.read("SELECT load_file('x') FROM Orders").problem()).startsWith("LOAD_FILE reads files");
    for (String d : new String[] {"DLookup", "DCount", "DSum", "DAvg", "DMin", "DMax", "DFirst", "DLast"}) {
      assertThat(AccessSql.read("SELECT " + d + "(\"Note\", \"Orders\", \"ID=1\") FROM Orders").problem()).as(d)
          .contains("runs SQL built from its text arguments");
    }
    assertThat(AccessSql.read("SELECT Note INTO Copy FROM Orders").problem()).isEqualTo("SELECT … INTO creates a "
        + "table: access_query only reads.");
    assertThat(AccessSql.read("SELECT NEXT VALUE FOR S FROM Orders").problem()).startsWith("NEXT VALUE FOR");
    assertThat(AccessSql.read("SELECT * FROM Orders FOR UPDATE").problem()).startsWith("FOR UPDATE locks rows");
    assertThat(AccessSql.read("WITH t AS (SELECT 1 FROM Orders) DELETE FROM Orders").problem())
        .startsWith("A WITH here must end in a SELECT");
  }

  @Test
  void whatActsOnTheCopyOfTheDatabaseIsRefusedEverywhere() {
    assertThat(AccessSql.read("SHUTDOWN").problem()).startsWith("SHUTDOWN closes GateRook's copy");
    assertThat(AccessSql.change("SHUTDOWN COMPACT").problem()).startsWith("SHUTDOWN closes");
    for (String q : new String[] {"SET DATABASE SQL SYNTAX ORA TRUE", "CHECKPOINT", "SCRIPT '/tmp/x.sql'",
        "BACKUP DATABASE TO '/tmp/' BLOCKING"}) {
      assertThat(AccessSql.read(q).problem()).as(q).contains("acts on GateRook's copy of the database");
      assertThat(AccessSql.change(q).problem()).as(q).contains("acts on GateRook's copy of the database");
    }
    assertThat(AccessSql.change("CALL ABS(1)").problem()).startsWith("CALL runs a routine");
    assertThat(AccessSql.change("COMMIT").problem()).startsWith("Transactions are GateRook's");
    assertThat(AccessSql.change("GRANT SELECT ON Orders TO PUBLIC").problem()).startsWith("Rights aren't");
    assertThat(AccessSql.change("CREATE FUNCTION f(x INT) RETURNS INT RETURN x").problem())
        .startsWith("FUNCTION is code");
    assertThat(AccessSql.change("CREATE TRIGGER t BEFORE INSERT ON Orders CALL 'x'").problem())
        .startsWith("TRIGGER is code");
    assertThat(AccessSql.change("CREATE VIEW v AS SELECT 1").problem()).startsWith("Queries of an Access file");
    assertThat(AccessSql.change("CREATE SCHEMA s").problem()).contains("acts on GateRook's copy");
    assertThat(AccessSql.read("DELETE FROM Orders").problem()).startsWith("DELETE isn't a query: access_query only "
        + "reads.");
  }

  @Test
  void changesNameTheirTable() {
    assertThat(AccessSql.change("INSERT INTO Orders (Note) VALUES ('third')")).extracting(AccessSql.Verdict::kind,
        AccessSql.Verdict::verb, AccessSql.Verdict::target).containsExactly(AccessSql.Kind.CHANGE, "INSERT", "Orders");
    assertThat(AccessSql.change("UPDATE [Order Lines] SET N = 2").target()).isEqualTo("Order Lines");
    assertThat(AccessSql.change("DELETE * FROM Orders WHERE ID = 2").target()).isEqualTo("Orders");
    assertThat(AccessSql.change("DELETE Orders.* FROM Orders WHERE ID = 2").target()).isEqualTo("Orders");
    assertThat(AccessSql.change("CREATE TABLE Notes (ID COUNTER PRIMARY KEY, Text TEXT(50))"))
        .extracting(AccessSql.Verdict::verb, AccessSql.Verdict::target).containsExactly("CREATE TABLE", "Notes");
    assertThat(AccessSql.change("CREATE UNIQUE INDEX ByNote ON Orders (Note)")).extracting(AccessSql.Verdict::verb,
        AccessSql.Verdict::target).containsExactly("CREATE INDEX", "Orders");
    assertThat(AccessSql.change("ALTER TABLE Orders ADD COLUMN Extra TEXT(10)").verb()).isEqualTo("ALTER TABLE");
    assertThat(AccessSql.change("DROP TABLE [Order Lines]").target()).isEqualTo("Order Lines");
    assertThat(AccessSql.change("INSERT INTO Orders (Note) SELECT LOAD_FILE('/etc/passwd') FROM Orders").problem())
        .startsWith("LOAD_FILE reads files");
    assertThat(AccessSql.change("UPDATE Orders SET Note = DLookup(\"Note\", \"Orders\", \"ID=1\")").problem())
        .contains("write the statement without it");
    assertThat(AccessSql.change("SELECT Note INTO Copy FROM Orders").problem()).startsWith("SELECT … INTO (a "
        + "make-table query)");
    assertThat(AccessSql.change("INSERT Orders VALUES (1)").problem()).startsWith("INSERT here is INSERT INTO");
    assertThat(AccessSql.change("DROP INDEX ByNote ON Orders").problem()).startsWith("DROP INDEX isn't a change");
  }

  @Test
  void whatCantBeReadWithCertaintyIsRefused() {
    for (String q : new String[] {"", "  ", "SELECT 'open", "SELECT \"open", "SELECT [open", "SELECT /* open",
        "SELECT /* a /* b */ */ 1", "SELECT #not a date at all, with ; and stuff that runs long# FROM Orders",
        "SELECT `x` FROM Orders", "{call x()}", "SELECT 1; SELECT 2", "SELECT (1", "SELECT 1)", "SELECT 1abc",
        "SELECT @x", "x".repeat(AccessSql.MAX_LENGTH + 1)}) {
      assertThat(AccessSql.read(q).ok()).as(q).isFalse();
      assertThat(AccessSql.change(q).ok()).as(q).isFalse();
    }
    assertThat(AccessSql.read("{call x()}").problem()).startsWith("GateRook doesn't run JDBC escapes");
    assertThat(AccessSql.read("SELECT #x").problem()).startsWith("GateRook reads # only around a date");
  }
}
