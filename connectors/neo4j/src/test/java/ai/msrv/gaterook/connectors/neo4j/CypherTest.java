package ai.msrv.gaterook.connectors.neo4j;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** GateRook's reading of Cypher: by Cypher's own tokens, refusing what it can't read with certainty. */
class CypherTest {

  private static Cypher.Statement read(String text) {
    return Cypher.read(text);
  }

  private static void kind(Cypher.Kind kind, String... texts) {
    for (String text : texts) {
      Cypher.Statement st = read(text);
      assertThat(st.kind()).as(text + " → " + st.problem()).isEqualTo(kind);
    }
  }

  private static void refused(String rule, String... texts) {
    for (String text : texts) {
      Cypher.Statement st = read(text);
      assertThat(st.kind()).as(text).isIn(Cypher.Kind.FORBIDDEN, Cypher.Kind.UNREADABLE);
      assertThat(st.rule()).as(text + " → " + st.problem()).isEqualTo(rule);
      assertThat(st.problem()).as(text).isNotBlank();
    }
  }

  @Test
  void readsAreReads() {
    kind(Cypher.Kind.READ,
        "MATCH (n:Person {name: $name}) RETURN n LIMIT 5",
        "match p = (a)-[:KNOWS*1..3]->(b) return p",
        "CALL db.labels() YIELD label RETURN label",
        "CALL db.relationshipTypes()",
        "CALL db.propertyKeys",
        "CALL db.schema.visualization()",
        "CALL db.schema.nodeTypeProperties() YIELD nodeType RETURN nodeType",
        "MATCH (n) WHERE n.note = 'DELETE; DROP' RETURN n",
        "MATCH (n) // CREATE (m)\nRETURN n",
        "MATCH (n) /* SET n.x = 1; LOAD CSV */ RETURN n",
        "RETURN point.distance(point({x: 1, y: 2}), point({x: 2, y: 3}))",
        "RETURN date.truncate('month', date()), datetime.fromepoch(0, 0), duration.between(date(), date())",
        "RETURN vector.similarity.cosine([1.0, 2.0], [2.0, 1.0])",
        "RETURN apoc.text.join(['a', 'b'], ','), apoc.coll.sum([1, 2])",
        "MATCH (n) RETURN n.call, n.use, n.load, n.set, n.create, n.show",
        "MATCH (n:Load)-[:CREATE]->(m:Use) RETURN n",
        "RETURN {use: 1, load: 2, set: 3, call: 4, create: 5}",
        "MATCH (call:Event) RETURN call.duration",
        "MATCH (load:Job) WHERE load.size > 1 RETURN load.size",
        "MATCH (n:`Person Name`) RETURN n.`full name`, `n`",
        "RETURN \"a\\\"b\", 'it\\'s', 'back\\\\slash'",
        "UNWIND range(1, 10) AS x RETURN x",
        "MATCH (n) RETURN count(*), collect(n.name)[0..3]",
        "WITH 1_000 AS x, 0x1F AS y, 1.5e-3 AS z, .5 AS w RETURN x + y",
        "CALL { MATCH (n) RETURN n } RETURN n",
        "CALL () { MATCH (n) RETURN n } RETURN n",
        "MATCH (n) RETURN EXISTS { MATCH (n)-->() }, COUNT { (n)--() }",
        "MATCH (n) WHERE n.since >= $since AND n.tags IS NOT NULL RETURN n",
        "MATCH (ü:Straße) RETURN ü.größe",
        "RETURN $`odd name`, $0",
        "MATCH (a), (b) WHERE a <> b RETURN a.x =~ 'x.*'",
        "MATCH (n) RETURN db.nameFromElementId(elementId(n))");
  }

  @Test
  void writesAndSchemaChangesAreChanges() {
    kind(Cypher.Kind.WRITE,
        "CREATE (n:Person {name: $name})",
        "MERGE (n:Person {id: 1}) ON CREATE SET n.created = timestamp()",
        "MATCH (n) SET n.x = 1",
        "MATCH (n) DETACH DELETE n",
        "MATCH (n) REMOVE n:Old",
        "UNWIND $rows AS r FOREACH (x IN r | CREATE (:Item {id: x}))",
        "MATCH (n) CALL { WITH n SET n.seen = true } RETURN n",
        "MATCH (n) WITH n MERGE (n)-[:SEEN]->(:Day {d: date()})");
    assertThat(read("MERGE (n:A) ON CREATE SET n.x = 1").writes()).containsExactly("MERGE", "CREATE", "SET");
    assertThat(read("MATCH (n) DETACH DELETE n").changes()).isEqualTo("DETACH, DELETE");
    kind(Cypher.Kind.SCHEMA,
        "CREATE INDEX person_name IF NOT EXISTS FOR (n:Person) ON (n.name)",
        "CREATE TEXT INDEX t FOR (n:Doc) ON (n.body)",
        "CREATE CONSTRAINT person_id FOR (n:Person) REQUIRE n.id IS UNIQUE",
        "DROP INDEX person_name",
        "DROP CONSTRAINT person_id IF EXISTS",
        "CREATE VECTOR INDEX v FOR (n:Doc) ON (n.embedding)");
    assertThat(read("CREATE INDEX i FOR (n:A) ON (n.x)").changes()).isEqualTo("the schema");
  }

  @Test
  void administrationAndSecurityStayWithPeople() {
    refused("admin",
        "SHOW USERS", "show databases", "SHOW PRIVILEGES", "SHOW ROLES", "SHOW TRANSACTIONS", "SHOW SETTINGS",
        "SHOW INDEXES", "SHOW CURRENT USER", "SHOW PROCEDURES",
        "CREATE USER bob SET PASSWORD 'secret-0001'", "CREATE OR REPLACE DATABASE x", "CREATE COMPOSITE DATABASE c",
        "CREATE ROLE writer", "CREATE ALIAS a FOR DATABASE b",
        "ALTER CURRENT USER SET PASSWORD FROM 'a' TO 'b'", "ALTER USER bob SET PASSWORD 'x'",
        "DROP USER bob", "DROP DATABASE x", "DROP ROLE r", "DROP ALIAS a FOR DATABASE",
        "GRANT ROLE admin TO bob", "DENY WRITE ON GRAPH * TO r", "REVOKE ROLE admin FROM bob",
        "START DATABASE x", "STOP DATABASE x", "TERMINATE TRANSACTIONS 'neo4j-transaction-1'",
        "ENABLE SERVER 'abc'", "RENAME USER a TO b", "DEALLOCATE DATABASES FROM SERVER 'x'",
        "MATCH (n) WITH n AS show RETURN show");
  }

  @Test
  void loadCsvUseAndInTransactionsAreRefused() {
    refused("load-csv",
        "LOAD CSV FROM 'file:///etc/passwd' AS r RETURN r",
        "load csv with headers from 'https://example.com/a.csv' as r return r",
        "MATCH (n) WITH n LOAD CSV FROM 'https://example.com/a.csv' AS r RETURN r",
        "CALL { LOAD CSV FROM 'x' AS r RETURN r } RETURN r");
    // A variable named like a keyword reads as the keyword: refused, with the hint to write it in backticks.
    refused("load-csv", "MATCH (load) RETURN load");
    assertThat(read("MATCH (load) RETURN load").hint()).contains("backticks");
    kind(Cypher.Kind.READ, "MATCH (`load`) RETURN `load`");
    refused("use",
        "USE system SHOW USERS", "USE neo4j MATCH (n) RETURN n",
        "CALL { USE other MATCH (n) RETURN n } RETURN n",
        "USE graph.byName('x') MATCH (n) RETURN n");
    refused("in-transactions",
        "MATCH (n) CALL { WITH n DETACH DELETE n } IN TRANSACTIONS OF 100 ROWS",
        "UNWIND range(1, 10) AS x CALL { WITH x CREATE (:A {x: x}) } IN 4 CONCURRENT TRANSACTIONS",
        "USING PERIODIC COMMIT 500 MATCH (n) RETURN n");
    refused("multiple-statements",
        "MATCH (n) RETURN n; MATCH (m) DETACH DELETE m", "RETURN 1;");
    refused("query-options",
        "EXPLAIN MATCH (n) RETURN n", "PROFILE MATCH (n) RETURN n", "CYPHER 5 MATCH (n) RETURN n",
        "cypher runtime=parallel MATCH (n) RETURN n", "// leading comment\nEXPLAIN MATCH (n) RETURN n");
  }

  @Test
  void onlyTheSchemaProceduresAreCalled() {
    refused("procedure",
        "CALL dbms.listConfig()", "CALL dbms.security.listUsers()", "CALL dbms.components",
        "CALL apoc.load.json('https://example.com/x.json') YIELD value RETURN value",
        "CALL apoc.import.csv([{fileName: 'file:///x.csv', labels: ['A']}], [], {})",
        "CALL apoc.export.json.all('out.json', {})",
        "CALL apoc.periodic.iterate('MATCH (n) RETURN n', 'DELETE n', {})",
        "CALL apoc.cypher.run('MATCH (n) RETURN n', {}) YIELD value RETURN value",
        "CALL apoc.do.when(true, 'CREATE (n)', '', {})",
        "CALL apoc.trigger.add('t', 'RETURN 1', {})",
        "CALL apoc.systemdb.execute('SHOW USERS')",
        "CALL gds.graph.project('g', 'Person', 'KNOWS')",
        "CALL db.createLabel('X')", "CALL db.index.fulltext.queryNodes('i', 'x')", "CALL tx.setMetaData({a: 1})",
        "CALL `apoc`.`load`.json('x')", "CALL `apoc.load.json`('x')", "OPTIONAL CALL apoc.load.json('x')",
        "MATCH (n) CALL db.labels() YIELD label CALL dbms.killQuery('q') YIELD queryId RETURN label",
        "CALL db.schema.visualization.extra()", "CALL DB.LABELS()");
    assertThat(read("CALL apoc.cypher.run('x', {})").problem()).contains("runs text as Cypher");
    assertThat(read("CALL dbms.listConfig()").problem()).contains("administer the server");
    assertThat(read("CALL db.labels() YIELD label RETURN label").procedures()).containsExactly("db.labels");
  }

  @Test
  void functionsThatRunCypherOrComeFromUnknownPluginsAreRefused() {
    refused("function",
        "RETURN apoc.cypher.runFirstColumnSingle('MATCH (n) RETURN count(n)', {})",
        "RETURN `apoc.cypher.runFirstColumn`('x', {})",
        "RETURN `apoc`.cypher.runFirstColumnMany('x', {})",
        "RETURN APOC.CYPHER.runFirstColumnSingle('x', {})",
        "RETURN apoc.static.get('secret')",
        "RETURN gds.util.asNode(1)",
        "RETURN custom.fn(1)",
        "MATCH (n) RETURN n.name(1)");
  }

  @Test
  void whatTheReaderCantReadWithCertaintyIsUnreadable() {
    refused("unparsed",
        "", "   ", "// only a comment", "/* only */",
        "RETURN 'open", "RETURN \"open", "MATCH (n:`open) RETURN n", "MATCH (n) /* open", "RETURN 'ends \\'",
        "RETURN '\\u0027'", "RETURN 1 // \\u000a CREATE (n)", "RETURN \"\\U0001F600\"",
        "MATCH (n) RETURN n \\ 1",
        "RETURN 1\u0000", "RETURN 1\u2028CREATE (n)", "RETURN 1\u2029", "RETURN 1\u0085", "RETURN 1\u001b[31m",
        "LOAD\u00a0CSV FROM 'x' AS r RETURN r", "LO\u200bAD CSV FROM 'x' AS r RETURN r",
        "LOAD C\u017fV FROM 'x' AS r RETURN r", "MATCH (n) RETURN n.\u0131d",
        "\uff2c\uff2f\uff21\uff24 CSV FROM 'x' AS r RETURN r", "MATCH (n:\u212aind) RETURN n",
        "MATCH (n) RETURN n.na\u0301me",
        "RETURN $", "RETURN $ x", "RETURN #x", "RETURN @x", "RETURN 1 ? 2",
        "MATCH (n) CALL", "MATCH (n) CALL 1");
    // Characters Cypher reads as text stay text inside strings and names.
    kind(Cypher.Kind.READ, "RETURN 'a\u00a0b\u200bc', `x\u00a0y`", "RETURN 'Ünïcödé ſ ı'");
  }

  @Test
  void theReaderNamesWhatItFound() {
    Cypher.Statement st = read("MATCH (a:Person) CREATE (a)-[:KNOWS]->(:Person) SET a.x = 1");
    assertThat(st.kind()).isEqualTo(Cypher.Kind.WRITE);
    assertThat(st.writes()).isEqualTo(List.of("CREATE", "SET"));
    assertThat(st.changes()).isEqualTo("CREATE, SET");
    assertThat(read("MATCH (n) RETURN n").changes()).isEqualTo("nothing");
    assertThat(read("RETURN 'open").hint()).contains("parameters");
  }
}
