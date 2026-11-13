package com.gaterook.connectors.cassandra;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.connectors.cassandra.Cql.Kind;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The CQL reader: Cassandra's tokens, the statement kinds, the names they act on, and what can't be read. */
class CqlTest {

  private static Cql.Statement read(String cql) {
    return Cql.read(cql);
  }

  @Test
  void selectsNameTheirOneTableQualifiedOrNot() {
    Cql.Statement st = read("SELECT id, total FROM shop.orders WHERE customer_id = ? LIMIT 10");
    assertThat(st.kind()).isEqualTo(Kind.SELECT);
    assertThat(st.target()).isEqualTo(new Cql.Name("shop", "orders"));
    assertThat(st.markers()).hasSize(1);
    assertThat(read("select * from Orders").target()).isEqualTo(new Cql.Name(null, "orders"));
    assertThat(read("SELECT * FROM \"Shop\".\"Order\"\"s\"").target()).isEqualTo(new Cql.Name("Shop", "Order\"s"));
    assertThat(read("SELECT * FROM shop . orders").target()).isEqualTo(new Cql.Name("shop", "orders"));
    assertThat(read("SELECT JSON * FROM shop.orders").target().name()).isEqualTo("orders");
    assertThat(read("SELECT DISTINCT customer_id FROM shop.orders;").kind()).isEqualTo(Kind.SELECT);
    assertThat(read("SELECT count(*), max(total), writetime(note) FROM shop.orders WHERE id IN (1, 2)").target())
        .isEqualTo(new Cql.Name("shop", "orders"));
    assertThat(read("SELECT CAST(total AS text) FROM shop.orders").kind()).isEqualTo(Kind.SELECT);
  }

  @Test
  void stringsNamesAndCommentsHideWhatTheyHold() {
    Cql.Statement st = read("SELECT * FROM shop.orders WHERE note = 'x; DROP TABLE shop.orders; FROM other.t' "
        + "AND code = $$it's ; FROM x$$ -- FROM evil.t ;\n /* FROM evil.u; */ // FROM evil.v\n");
    assertThat(st.analyzed()).as(st.problem()).isTrue();
    assertThat(st.target()).isEqualTo(new Cql.Name("shop", "orders"));
    assertThat(read("SELECT \"from\" FROM shop.orders").target()).isEqualTo(new Cql.Name("shop", "orders"));
    assertThat(read("SELECT * FROM shop.orders WHERE a = 'it''s'").analyzed()).isTrue();
    assertThat(read("SELECT * FROM shop.orders WHERE a = '' AND b = ''''").analyzed()).isTrue();
    assertThat(read("SELECT * FROM shop.orders WHERE a = 'two\nlines'").analyzed()).isTrue();
    assertThat(read("SELECT * FROM shop.orders WHERE a = 'Grüße, 你好'").analyzed()).isTrue();
    assertThat(read("SELECT * FROM shop.orders -- Grüße\n").analyzed()).isTrue();
  }

  @Test
  void literalsAreTokensOfTheirOwn() {
    assertThat(read("SELECT * FROM shop.orders WHERE id = 123e4567-e89b-12d3-a456-426614174000").analyzed())
        .isTrue();
    assertThat(read("SELECT * FROM shop.orders WHERE id = deadbeef-dead-beef-dead-beefdeadbeef").analyzed())
        .isTrue();
    assertThat(read("SELECT * FROM shop.events WHERE d = 1h30m AND e = 1.5e-3 AND f = 0xCAFE AND g = -42 "
        + "AND h = 2mo3w AND i = 250ms AND j = PT1H AND k = NaN AND l = -Infinity").analyzed()).isTrue();
    assertThat(read("SELECT * FROM shop.events WHERE a = 1 - 2").analyzed()).isTrue();
  }

  @Test
  void markersArePositionalOrNamed() {
    Cql.Statement st = read("SELECT * FROM shop.orders WHERE a = :a AND b = :\"B\" AND c = ? LIMIT ?");
    assertThat(st.markers()).extracting(Cql.Marker::name).containsExactly("a", "B", null, null);
    assertThat(read("UPDATE shop.orders SET m = m + {'k': ?} WHERE id = ?").markers()).hasSize(2);
    assertThat(read("SELECT * FROM shop.orders WHERE a = ':nope'").markers()).isEmpty();
  }

  @Test
  void allowFilteringIsFoundOutsideStrings() {
    assertThat(read("SELECT * FROM shop.orders WHERE total > 5 ALLOW FILTERING").allowFiltering()).isTrue();
    assertThat(read("SELECT * FROM shop.orders WHERE total > 5 allow   filtering;").allowFiltering()).isTrue();
    assertThat(read("SELECT * FROM shop.orders WHERE note = 'ALLOW FILTERING'").allowFiltering()).isFalse();
    assertThat(read("SELECT * FROM shop.orders /* ALLOW FILTERING */").allowFiltering()).isFalse();
  }

  @Test
  void functionsNamedWithAKeyspaceAreFound() {
    assertThat(read("SELECT other.fmt(total) FROM shop.orders").functionKeyspaces()).containsExactly("other");
    assertThat(read("SELECT system.now() FROM shop.orders").functionKeyspaces()).isEmpty();
    assertThat(read("SELECT address.city FROM shop.users").functionKeyspaces()).isEmpty();
    assertThat(read("INSERT INTO shop.orders (id) VALUES (1)").functionKeyspaces()).isEmpty();
    assertThat(read("CREATE TABLE shop.t (id int PRIMARY KEY)").functionKeyspaces()).isEmpty();
  }

  @Test
  void writesNameTheirTableAndLightweightTransactionsAreKnown() {
    Cql.Statement insert = read("INSERT INTO shop.orders (id, note) VALUES (?, 'IF') IF NOT EXISTS USING TTL 60");
    assertThat(insert.kind()).isEqualTo(Kind.INSERT);
    assertThat(insert.target()).isEqualTo(new Cql.Name("shop", "orders"));
    assertThat(insert.conditional()).isTrue();
    assertThat(read("INSERT INTO shop.orders JSON '{\"id\": 1}'").conditional()).isFalse();
    Cql.Statement update = read("UPDATE shop.orders USING TIMESTAMP 1700000000000000 SET total = 1 WHERE id = 1 "
        + "IF total = 0");
    assertThat(update.kind()).isEqualTo(Kind.UPDATE);
    assertThat(update.conditional()).isTrue();
    assertThat(update.usingTimestamp()).isTrue();
    assertThat(read("UPDATE shop.orders SET timestamp = 5 WHERE id = 1").usingTimestamp()).isFalse();
    assertThat(read("UPDATE shop.orders USING TTL 5 AND TIMESTAMP ? SET a = 1 WHERE id = 1").usingTimestamp())
        .isTrue();
    Cql.Statement delete = read("DELETE m['FROM'], note FROM shop.orders WHERE id = 1 IF EXISTS");
    assertThat(delete.kind()).isEqualTo(Kind.DELETE);
    assertThat(delete.target()).isEqualTo(new Cql.Name("shop", "orders"));
    assertThat(delete.conditional()).isTrue();
    assertThat(read("DELETE FROM orders WHERE id = 1").target()).isEqualTo(new Cql.Name(null, "orders"));
    assertThat(read("TRUNCATE shop.orders").kind()).isEqualTo(Kind.TRUNCATE);
    assertThat(read("TRUNCATE TABLE shop.orders;").target()).isEqualTo(new Cql.Name("shop", "orders"));
    assertThat(read("TRUNCATE shop.orders, shop.items").kind()).isEqualTo(Kind.UNREADABLE);
  }

  @Test
  void schemaChangesOfTablesIndexesTypesAndViews() {
    assertThat(read("CREATE TABLE IF NOT EXISTS shop.t (id int PRIMARY KEY)").kind()).isEqualTo(Kind.CREATE_TABLE);
    assertThat(read("CREATE TABLE IF NOT EXISTS shop.t (id int PRIMARY KEY)").target())
        .isEqualTo(new Cql.Name("shop", "t"));
    assertThat(read("CREATE COLUMNFAMILY shop.t (id int PRIMARY KEY)").verb()).isEqualTo("CREATE TABLE");
    assertThat(read("ALTER TABLE shop.t ADD note text").kind()).isEqualTo(Kind.ALTER_TABLE);
    assertThat(read("DROP TABLE IF EXISTS shop.t").kind()).isEqualTo(Kind.DROP_TABLE);
    assertThat(read("DROP TABLE IF EXISTS shop.t").target()).isEqualTo(new Cql.Name("shop", "t"));
    Cql.Statement index = read("CREATE INDEX IF NOT EXISTS t_note ON shop.t (note)");
    assertThat(index.kind()).isEqualTo(Kind.CREATE_INDEX);
    assertThat(index.target()).isEqualTo(new Cql.Name("shop", "t"));
    assertThat(read("CREATE INDEX ON shop.t (note)").target()).isEqualTo(new Cql.Name("shop", "t"));
    assertThat(read("CREATE CUSTOM INDEX t_sai ON shop.t (note) USING 'StorageAttachedIndex'").target())
        .isEqualTo(new Cql.Name("shop", "t"));
    assertThat(read("DROP INDEX IF EXISTS shop.t_note").target()).isEqualTo(new Cql.Name("shop", "t_note"));
    assertThat(read("CREATE TYPE shop.address (street text, city text)").kind()).isEqualTo(Kind.CREATE_TYPE);
    assertThat(read("ALTER TYPE shop.address ADD zip text").kind()).isEqualTo(Kind.ALTER_TYPE);
    assertThat(read("DROP TYPE IF EXISTS shop.address").kind()).isEqualTo(Kind.DROP_TYPE);
    Cql.Statement view = read("CREATE MATERIALIZED VIEW shop.by_note AS SELECT * FROM other.t WHERE note IS NOT "
        + "NULL AND id IS NOT NULL PRIMARY KEY (note, id)");
    assertThat(view.kind()).isEqualTo(Kind.CREATE_VIEW);
    assertThat(view.target()).isEqualTo(new Cql.Name("shop", "by_note"));
    assertThat(view.base()).isEqualTo(new Cql.Name("other", "t"));
    assertThat(read("ALTER MATERIALIZED VIEW shop.by_note WITH comment = 'x'").kind()).isEqualTo(Kind.ALTER_VIEW);
    assertThat(read("DROP MATERIALIZED VIEW shop.by_note").kind()).isEqualTo(Kind.DROP_VIEW);
    assertThat(Kind.CREATE_INDEX.ddl()).isTrue();
    assertThat(Kind.TRUNCATE.change()).isTrue();
    assertThat(Kind.TRUNCATE.ddl()).isFalse();
  }

  @Test
  void accessKeyspacesCodeBatchesUseAndDescribeAreRefusedEverywhere() {
    for (String cql : List.of("GRANT SELECT ON KEYSPACE shop TO bob", "REVOKE MODIFY ON shop.orders FROM bob",
        "LIST ROLES", "LIST ALL PERMISSIONS OF bob", "CREATE ROLE bob WITH PASSWORD = 'x' AND LOGIN = true",
        "ALTER ROLE bob WITH SUPERUSER = true", "DROP USER bob", "CREATE USER bob WITH PASSWORD 'x'",
        "ADD IDENTITY 'spiffe://x' TO ROLE 'bob'", "DROP IDENTITY 'spiffe://x'")) {
      assertThat(read(cql).kind()).as(cql).isEqualTo(Kind.ACCESS);
    }
    for (String cql : List.of("CREATE KEYSPACE x WITH replication = {'class': 'SimpleStrategy', "
        + "'replication_factor': 1}", "ALTER KEYSPACE shop WITH replication = {'class': 'NetworkTopologyStrategy'}",
        "DROP KEYSPACE shop", "CREATE SCHEMA x WITH replication = {}")) {
      assertThat(read(cql).kind()).as(cql).isEqualTo(Kind.KEYSPACE);
    }
    for (String cql : List.of("CREATE FUNCTION shop.f(a int) RETURNS NULL ON NULL INPUT RETURNS int LANGUAGE java "
        + "AS $$ return a; $$", "CREATE OR REPLACE FUNCTION shop.f(a int) CALLED ON NULL INPUT RETURNS int "
        + "LANGUAGE java AS 'return a;'", "CREATE AGGREGATE shop.a(int) SFUNC f STYPE int", "DROP FUNCTION shop.f",
        "DROP AGGREGATE shop.a", "CREATE TRIGGER t ON shop.orders USING 'org.example.Trigger'",
        "DROP TRIGGER t ON shop.orders")) {
      assertThat(read(cql).kind()).as(cql).isEqualTo(Kind.CODE);
    }
    assertThat(read("BEGIN BATCH INSERT INTO shop.t (id) VALUES (1); APPLY BATCH").kind()).isEqualTo(Kind.BATCH);
    assertThat(read("BEGIN UNLOGGED BATCH UPDATE shop.t SET a = 1 WHERE id = 1; APPLY BATCH;").kind())
        .isEqualTo(Kind.BATCH);
    assertThat(read("APPLY BATCH").kind()).isEqualTo(Kind.BATCH);
    assertThat(read("BEGIN COUNTER BATCH UPDATE shop.c SET n = n + 1 WHERE id = 1 APPLY BATCH").kind())
        .isEqualTo(Kind.BATCH);
    assertThat(read("USE shop").kind()).isEqualTo(Kind.USE);
    assertThat(read("DESCRIBE KEYSPACES").kind()).isEqualTo(Kind.OTHER);
    assertThat(read("DESC TABLE shop.orders").kind()).isEqualTo(Kind.OTHER);
    assertThat(read("CREATE SEARCH INDEX ON shop.orders").kind()).isEqualTo(Kind.OTHER);
    assertThat(read("ALTER INDEX shop.i").kind()).isEqualTo(Kind.OTHER);
    assertThat(read("PRUNE MATERIALIZED VIEW shop.v").kind()).isEqualTo(Kind.OTHER);
    assertThat(read("SELECT * FROM shop.t").kind().refused()).isFalse();
    assertThat(Kind.BATCH.refused()).isTrue();
  }

  @Test
  void whatCassandraWouldReadDifferentlyIsNotRead() {
    for (String cql : List.of(
        "SELECT * FROM shop.t; SELECT * FROM shop.u",
        "SELECT * FROM shop.t; DROP TABLE shop.t",
        "SELECT * FROM shop.t WHERE a = 'open",
        "SELECT * FROM shop.t WHERE a = $$open",
        "SELECT * FROM \"open",
        "SELECT * FROM \"\"",
        "SELECT * FROM shop.t /* open",
        "SELECT * FROM shop.t /* outer /* inner */ */",
        "SELECT * FROM shop.t WHERE a = 1 --2",
        "SELECT * FROM shop.t WHERE a = 1 // at the very end",
        "SELECT 1from shop.t",
        "SELECT * FROM shop.t WHERE a = 0xCAFEG",
        "SELECT * FROM shop.t WHERE a = $1",
        "SELECT * FROM shop.t WHERE a = 1\u0000",
        "SELECT * FROM shop.t WHERE a = 1\f",
        "SELECT * FROM shop.t WHERE ä = 1",
        "SELECT * FROM shop.t WHERE a = 1 -- ‮hidden\n",
        "SELECT * FROM shop.t /* ​ */",
        "SELECT * FROM shop.t WHERE a IN (1, 2",
        "SELECT * FROM shop.t WHERE a = 1)",
        "SELECT * FROM shop.t WHERE a IN (1; 2)",
        "SELECT * FROM shop.t WHERE a = `b`",
        "SELECT * FROM shop.t WHERE a = @b",
        "SELECT * FROM shop.t WHERE a = 1 \\",
        "SELECT * FROM \"bad\u0001name\"",
        "",
        "   ",
        "-- only a comment\n",
        "(SELECT * FROM shop.t)",
        "SELECT a, b",
        "SELECT * FROM shop.t, FROM shop.u",
        "INSERT shop.t (id) VALUES (1)",
        "UPDATE SET a = 1",
        "DELETE FROM",
        "DROP TABLE",
        "CREATE MATERIALIZED shop.v",
        "CREATE MATERIALIZED VIEW shop.v AS SELECT a FROM WHERE b = 1",
        "SELECT * FROM shop.t" + " ".repeat(Cql.MAX_LENGTH))) {
      assertThat(read(cql).kind()).as("<%s>", cql).isEqualTo(Kind.UNREADABLE);
      assertThat(read(cql).problem()).as("<%s>", cql).isNotBlank();
    }
    assertThat(read("SELECT * FROM shop.t; -- after\n /* and */").analyzed()).isTrue();
    assertThat(read("SELECT * FROM shop.t -- a comment\r").analyzed()).isTrue();
    assertThat(read("SELECT * FROM shop.t;   ").analyzed()).isTrue();
  }

  @Test
  void tableNamesOfTheDescribeToolAreOneName() {
    assertThat(Cql.tableName("shop.orders")).isEqualTo(new Cql.Name("shop", "orders"));
    assertThat(Cql.tableName("Orders")).isEqualTo(new Cql.Name(null, "orders"));
    assertThat(Cql.tableName("\"Shop\".\"Orders\"")).isEqualTo(new Cql.Name("Shop", "Orders"));
    for (String bad : new String[] {null, "", "shop.", ".orders", "shop.orders.items", "shop orders",
        "shop.orders; DROP TABLE x", "'shop'.orders", "select", "a" + "b".repeat(400)}) {
      assertThat(Cql.tableName(bad)).as(bad).isNull();
    }
  }

  @Test
  void namesAreQuotedOnlyWhereCqlNeedsIt() {
    assertThat(Cql.quote("orders")).isEqualTo("orders");
    assertThat(Cql.quote("Orders")).isEqualTo("\"Orders\"");
    assertThat(Cql.quote("select")).isEqualTo("\"select\"");
    assertThat(Cql.quote("a\"b")).isEqualTo("\"a\"\"b\"");
    assertThat(new Cql.Name("Shop", "orders").text()).isEqualTo("\"Shop\".orders");
    assertThat(new Cql.Name(null, "orders").text()).isEqualTo("orders");
  }
}
