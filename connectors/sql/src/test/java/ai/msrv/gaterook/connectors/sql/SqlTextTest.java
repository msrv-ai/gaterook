package ai.msrv.gaterook.connectors.sql;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connectors.sql.IdentifierRules.TableRef;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** How the gateway reads SQL text without a parser: statements, names, template placeholders (report 04 §6.3). */
class SqlTextTest {

  // ── SqlLexer ────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void postgresStatementsEndAtSemicolonsInCodeOnly() {
    String sql = "SELECT ';' AS a, $$;$$, $t$ ; $t$, \"x;y\" /* ; /* ; */ ; */ FROM t; -- ;\nSELECT 2;";

    assertThat(SqlLexer.scan(sql, false).statements())
        .containsExactly("SELECT ';' AS a, $$;$$, $t$ ; $t$, \"x;y\" /* ; /* ; */ ; */ FROM t", "-- ;\nSELECT 2");
  }

  @Test
  void postgresReadsABackslashAsAPlainCharacter() {
    // standard_conforming_strings is on in every prelude: 'a\' is a whole literal.
    assertThat(SqlLexer.scan("SELECT 'a\\'; SELECT 2", false).statements()).hasSize(2);
  }

  @Test
  void mysqlReadsBackslashEscapesHashCommentsAndExecutableComments() {
    assertThat(SqlLexer.scan("SELECT 'a\\'; SELECT 2'", true).statements()).hasSize(1);
    assertThat(SqlLexer.scan("SELECT 1 # ; not a statement\n", true).statements()).hasSize(1);
    assertThat(SqlLexer.scan("SELECT 1 /*! ; DROP TABLE t */", true).executableComment()).isTrue();
    // Optimizer hints act too: SET_VAR(max_execution_time = 0) would lift the statement timeout.
    assertThat(SqlLexer.scan("SELECT /*+ SET_VAR(max_execution_time = 0) */ 1", true).executableComment()).isTrue();
    assertThat(SqlLexer.scan("SELECT 1 /* plain */", true).executableComment()).isFalse();
    // "--" starts a comment only before white space in MySQL.
    assertThat(SqlLexer.scan("SELECT 1--2; SELECT 3", true).statements()).hasSize(2);
  }

  @Test
  void anUnclosedQuoteOrCommentIsNeverTrusted() {
    assertThat(SqlLexer.scan("SELECT 'open", false).unterminated()).isTrue();
    assertThat(SqlLexer.scan("SELECT $x$ open", false).unterminated()).isTrue();
    assertThat(SqlLexer.scan("SELECT /* /* */ open", false).unterminated()).isTrue();
    assertThat(SqlLexer.scan("SELECT `open", true).unterminated()).isTrue();
  }

  @Test
  void theFirstKeywordSkipsCommentsAndParentheses() {
    assertThat(SqlLexer.scan("/* hi */ (( select 1))", false).firstKeyword()).isEqualTo("SELECT");
    assertThat(SqlLexer.scan("-- x\n  begin", false).firstKeyword()).isEqualTo("BEGIN");
    assertThat(SqlLexer.scan("'lit'", false).firstKeyword()).isEmpty();
  }

  // ── IdentifierRules ─────────────────────────────────────────────────────────────────────────────────────

  @Test
  void postgresFoldsUnquotedNamesAndKeepsQuotedOnes() {
    assertThat(IdentifierRules.table("Orders", false, "public")).isEqualTo(new TableRef("public", "orders"));
    assertThat(IdentifierRules.table("\"Orders\"", false, "public")).isEqualTo(new TableRef("public", "Orders"));
    assertThat(IdentifierRules.table("billing.\"Inv.oices\"", false, "public"))
        .isEqualTo(new TableRef("billing", "Inv.oices"));
    assertThat(IdentifierRules.table("app.public.orders", false, "public"))
        .isEqualTo(new TableRef("public", "orders"));
    // The catalog comes before search_path for pg_ names.
    assertThat(IdentifierRules.table("pg_authid", false, "public")).isEqualTo(new TableRef("pg_catalog", "pg_authid"));
    assertThat(IdentifierRules.table("\"pg_x\"", false, "public")).isEqualTo(new TableRef("public", "pg_x"));
  }

  @Test
  void mysqlKeepsNamesAsWritten() {
    assertThat(IdentifierRules.table("Orders", true, "app")).isEqualTo(new TableRef("app", "Orders"));
    assertThat(IdentifierRules.table("`my db`.`t``1`", true, "app")).isEqualTo(new TableRef("my db", "t`1"));
    assertThat(IdentifierRules.table("a.b.c", true, "app")).isNull();
    assertThat(IdentifierRules.table("orders", true, null)).isEqualTo(new TableRef(null, "orders"));
  }

  @Test
  void malformedNamesAreNoNames() {
    for (String bad : List.of("", "a.", ".a", "\"open", "a b", "a..b", "\"\"")) {
      assertThat(IdentifierRules.table(bad, false, "public")).as(bad).isNull();
    }
  }

  @Test
  void namesAreQuotedOnlyWhereTheServerNeedsIt() {
    assertThat(IdentifierRules.display(new TableRef("public", "orders"), false)).isEqualTo("public.orders");
    assertThat(IdentifierRules.display(new TableRef("public", "Orders"), false)).isEqualTo("public.\"Orders\"");
    assertThat(IdentifierRules.display(new TableRef("app", "Orders"), true)).isEqualTo("app.Orders");
    assertThat(IdentifierRules.display(new TableRef("app", "a`b"), true)).isEqualTo("app.`a``b`");
  }

  @Test
  void theAllowlistMatchesNamesAsTheServerResolvesThem() {
    Allowlist pg = Allowlist.of(List.of("public.orders", "reporting.*", "\"Mixed\""), List.of(), false);
    assertThat(pg.allows(new TableRef("public", "orders"))).isTrue();
    assertThat(pg.allows(new TableRef("public", "Orders"))).isFalse();
    assertThat(pg.allows(new TableRef("reporting", "daily"))).isTrue();
    assertThat(pg.allows(new TableRef("Mixed", "t"))).isTrue();
    assertThat(pg.allows(new TableRef("mixed", "t"))).isFalse();
    assertThat(pg.mayAllowSchema("reporting")).isTrue();
    assertThat(pg.mayAllowSchema("billing")).isFalse();

    Allowlist everything = Allowlist.of(List.of("*"), List.of(), false);
    assertThat(everything.allows(new TableRef("billing", "x"))).isTrue();
    assertThat(everything.allows(new TableRef("pg_catalog", "pg_authid"))).isFalse();

    // The connection's schema allowlist narrows the policy's.
    Allowlist both = Allowlist.of(List.of("public.*", "billing.*"), List.of("billing"), false);
    assertThat(both.allows(new TableRef("public", "orders"))).isFalse();
    assertThat(both.allows(new TableRef("billing", "invoices"))).isTrue();

    assertThat(Allowlist.of(List.of(), List.of(), false).restricted()).isFalse();
  }

  // ── NamedSql ────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void placeholdersBecomeJdbcParametersInCodeOnly() {
    NamedSql n = NamedSql.compile("SELECT ':x' AS lit, \":x\" FROM orders /* :x */ WHERE (:since::timestamptz IS NULL "
        + "OR created_at >= :since) AND meta ? 'vip' LIMIT :lim", false, Set.of("since", "lim", "x"));

    assertThat(n.errors()).isEmpty();
    assertThat(n.jdbc()).isEqualTo("SELECT ':x' AS lit, \":x\" FROM orders /* :x */ WHERE (?::timestamptz IS NULL "
        + "OR created_at >= ?) AND meta ?? 'vip' LIMIT ?");
    assertThat(n.order()).containsExactly("since", "since", "lim");
    assertThat(n.used()).containsExactlyInAnyOrder("since", "lim");
  }

  @Test
  void whatTheDriverWouldRewriteIsAnError() {
    assertThat(NamedSql.compile("SELECT * FROM t WHERE id = ?", true, Set.of()).errors())
        .containsExactly("Write :name for a parameter: a bare ? isn't allowed in a MySQL template.");
    assertThat(NamedSql.compile("SELECT {fn now()}", false, Set.of()).errors()).singleElement()
        .satisfies(e -> assertThat(e).contains("JDBC escapes"));
    assertThat(NamedSql.compile("SELECT :nope", false, Set.of()).errors()).singleElement()
        .satisfies(e -> assertThat(e).startsWith(":nope isn't a parameter"));
    assertThat(NamedSql.compile("SELECT 'open", false, Set.of()).errors()).singleElement()
        .satisfies(e -> assertThat(e).contains("isn't closed"));
    // MySQL's := assignment and PostgreSQL's :: cast are not placeholders.
    assertThat(NamedSql.compile("SELECT a::text, b := 1", false, Set.of()).errors()).isEmpty();
  }
}
