package ai.msrv.gaterook.connector.api;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ActionPreview.Field;
import ai.msrv.gaterook.connector.api.TestResult.Check;
import ai.msrv.gaterook.connector.api.TestResult.Confirmation;
import ai.msrv.gaterook.connector.api.TestResult.Hint;
import ai.msrv.gaterook.connector.api.TestResult.Level;
import ai.msrv.gaterook.connector.api.TestResult.Permissions;
import ai.msrv.gaterook.connector.api.TestResult.Severity;
import ai.msrv.gaterook.connector.api.ToolResult.Column;
import ai.msrv.gaterook.connector.api.ToolResult.Content;
import ai.msrv.gaterook.connector.api.ToolResult.Table;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

/** Factories and with-ers of the value types a connector returns. */
class ResultFactoriesTest {

  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  @Test
  void classification() {
    JsonNode facts = F.objectNode().put("statements", 1);

    assertThat(Classification.read()).isEqualTo(new Classification(Classification.Kind.READ, null, null, null, null));
    assertThat(Classification.mutation("UPDATE orders"))
        .isEqualTo(new Classification(Classification.Kind.MUTATION, null, "UPDATE orders", null, null));
    assertThat(Classification.mutation("UPDATE orders", facts).facts()).isEqualTo(facts);
    assertThat(Classification.deny("prod.sql.block_ddl", "DDL is not allowed on prod", "Ask a person"))
        .isEqualTo(new Classification(Classification.Kind.DENY, "prod.sql.block_ddl", "DDL is not allowed on prod",
            "Ask a person", null));
  }

  @Test
  void preparedAction() {
    ActionPreview p = ActionPreview.of(ActionPreview.Kind.SQL, "sql", "UPDATE t SET a = 1", "dev-db", "UPDATE t");
    JsonNode state = F.objectNode().put("rv", "42");

    assertThat(PreparedAction.approval(p, state))
        .isEqualTo(new PreparedAction(PreparedAction.Outcome.APPROVAL, p, state, null));
    assertThat(PreparedAction.reject("deployment/api is paused"))
        .isEqualTo(new PreparedAction(PreparedAction.Outcome.REJECT, null, null, "deployment/api is paused"));
    assertThat(PreparedAction.noChange("unchanged"))
        .isEqualTo(new PreparedAction(PreparedAction.Outcome.NO_CHANGE, null, null, "unchanged"));
  }

  @Test
  void actionPreviewWithersReplaceOneFieldAndCopy() {
    ActionPreview p = ActionPreview.of(ActionPreview.Kind.HTTP, "http", "POST /v1/refunds", "billing-api", "POST");
    JsonNode payload = F.objectNode().put("method", "POST");

    ActionPreview full = p.withFields(List.of(new Field("Host", "billing.stage.acme.dev")))
        .withImpact(List.of(new Field("Rows", "1")))
        .withWarnings(List.of("Body over 3,000 characters"))
        .withFacets(Map.of("template", "billing-api"))
        .withPayload(payload);

    assertThat(p.fields()).isEmpty();
    assertThat(p.impact()).isEmpty();
    assertThat(p.warnings()).isEmpty();
    assertThat(p.auditFacets()).isEmpty();
    assertThat(p.payload()).isNull();
    assertThat(full.kind()).isEqualTo(ActionPreview.Kind.HTTP);
    assertThat(full.text()).isEqualTo("POST /v1/refunds");
    assertThat(full.fields()).containsExactly(new Field("Host", "billing.stage.acme.dev"));
    assertThat(full.impact()).containsExactly(new Field("Rows", "1"));
    assertThat(full.warnings()).containsExactly("Body over 3,000 characters");
    assertThat(full.auditFacets()).containsEntry("template", "billing-api");
    assertThat(full.payload()).isEqualTo(payload);
    assertThat(full.extra()).isNull();
  }

  @Test
  void testResultFactories() {
    Permissions perms = new Permissions(Level.READ, "SELECT on public", null, List.of(), null);
    TestResult ok = TestResult.success(12, "PostgreSQL 16.15", "gw_reader", perms);

    assertThat(ok.ok()).isTrue();
    assertThat(ok.latencyMs()).isEqualTo(12);
    assertThat(ok.serverVersion()).isEqualTo("PostgreSQL 16.15");
    assertThat(ok.identity()).isEqualTo("gw_reader");
    assertThat(ok.permissions()).isEqualTo(perms);
    assertThat(ok.hints()).isEmpty();
    assertThat(ok.error()).isNull();

    TestResult failed = TestResult.failure("pg.refused", "Connection refused on port 5432.", "ECONNREFUSED");
    assertThat(failed.ok()).isFalse();
    assertThat(failed.error()).isEqualTo(new TestResult.TestError("pg.refused", "Connection refused on port 5432.",
        "ECONNREFUSED"));

    Confirmation hostKey = new Confirmation("host-key", "First connection to 10.20.0.14:22.", null, null);
    TestResult confirm = TestResult.confirm(hostKey, List.of(new Check("tcp", true, 3, null)));
    assertThat(confirm.ok()).isFalse();
    assertThat(confirm.needsConfirmation()).isEqualTo(hostKey);
    assertThat(confirm.checks()).containsExactly(new Check("tcp", true, 3, null));
  }

  @Test
  void testResultWithersAccumulate() {
    Hint first = new Hint(Severity.WARNING, "pg.can-write", "credentials.user", "This user can write.");
    Hint second = new Hint(Severity.INFO, "pg.replica", null, "Read replica.");
    JsonNode meta = F.objectNode().put("server", "16.15");
    JsonNode suggestion = F.objectNode().put("kind", "allow-private");
    JsonNode details = F.objectNode().put("tls", true);

    TestResult r = TestResult.success(5, "16", "me", null)
        .withHint(first).withHint(second)
        .withChecks(List.of(new Check("connect", true, 5, null)))
        .withMetadata(meta).withSuggestedPolicyChange(suggestion).withDetails(details);

    assertThat(r.hints()).containsExactly(first, second);
    assertThat(r.checks()).hasSize(1);
    assertThat(r.metadataUpdate()).isEqualTo(meta);
    assertThat(r.suggestedPolicyChange()).isEqualTo(suggestion);
    assertThat(r.details()).isEqualTo(details);
    assertThat(r.ok()).isTrue();
  }

  @Test
  void healthResult() {
    assertThat(HealthResult.healthy(42))
        .isEqualTo(new HealthResult(HealthResult.State.HEALTHY, 42, null));
    assertThat(HealthResult.healthy(1_000).state()).isEqualTo(HealthResult.State.HEALTHY);
    assertThat(HealthResult.healthy(1_001).state()).as("slower than 1 s").isEqualTo(HealthResult.State.DEGRADED);
    assertThat(HealthResult.unreachable("Connection refused"))
        .isEqualTo(new HealthResult(HealthResult.State.UNREACHABLE, 0, "Connection refused"));
  }

  @Test
  void toolResultFactories() {
    assertThat(ToolResult.text("12 rows").content()).containsExactly(Content.text("12 rows"));
    assertThat(ToolResult.text("x").isError()).isFalse();
    assertThat(Content.text("x")).isEqualTo(new Content("text", "x", null, null, null));

    JsonNode structured = F.objectNode().put("rowCount", 12);
    assertThat(ToolResult.json(structured, null).content()).isEmpty();
    assertThat(ToolResult.json(structured, "12 rows").content()).containsExactly(Content.text("12 rows"));
    assertThat(ToolResult.json(structured, null).structuredContent()).isEqualTo(structured);

    ArrayNode rows = F.arrayNode().add(F.arrayNode().add(48240).add("pending"));
    Table table = new Table(List.of(new Column("id", "int4", "public", "orders", "id")), rows, null);
    ToolResult t = ToolResult.table(table, structured);
    assertThat(t.table()).isEqualTo(table);
    assertThat(t.content()).as("the core renders the text").isEmpty();

    ToolResult error = ToolResult.error("Can't reach db.dev.internal:5432.");
    assertThat(error.isError()).isTrue();
    assertThat(error.content()).containsExactly(Content.text("Can't reach db.dev.internal:5432."));
  }

  @Test
  void toolResultWithers() {
    JsonNode meta = F.objectNode().put("ai.msrv.gaterook/callId", "tc_1");
    ToolResult r = ToolResult.text("x").truncated("row_limit").maskPaths(List.of("$.rows[*][2]")).withMeta(meta);

    assertThat(r.truncated()).isTrue();
    assertThat(r.truncatedReason()).isEqualTo("row_limit");
    assertThat(r.maskPaths()).containsExactly("$.rows[*][2]");
    assertThat(r.meta()).isEqualTo(meta);

    ToolResult accounted = r.accounted(List.of(Content.text("‹masked›")), null, null, 3, 812);
    assertThat(accounted.maskedCount()).isEqualTo(3);
    assertThat(accounted.resultBytes()).isEqualTo(812);
    assertThat(accounted.content()).containsExactly(Content.text("‹masked›"));
    assertThat(accounted.truncatedReason()).as("kept").isEqualTo("row_limit");
    assertThat(accounted.meta()).isEqualTo(meta);
  }

  @Test
  void compileResultIsOkWithoutErrors() {
    assertThat(new TemplateKind.CompileResult(List.of(), List.of(), List.of(), List.of("w"), "d").ok()).isTrue();
    assertThat(new TemplateKind.CompileResult(List.of(), List.of(), List.of("bad"), List.of(), "d").ok()).isFalse();
  }
}
