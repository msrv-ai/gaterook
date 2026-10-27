package ai.msrv.gaterook.connectors.http;

import static ai.msrv.gaterook.connectors.http.Fixtures.cfg;
import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.TemplateKind;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connectors.support.TemplateImporter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The {@code openapi} template kind (report 10 §3.6, §6.5, §7.4, §8.1): the billing fixture of the report with its
 * traps (allOf, nullable, a recursive Node, two parameters named {@code id}, gateway headers), the OAI examples
 * petstore-expanded (3.0) and tictactoe (3.1), and what the importer and the compiler refuse.
 */
class OpenApiTemplateKindTest {

  private static final OpenApiTemplateKind KIND = (OpenApiTemplateKind) new HttpConnector().templateKinds().getFirst();

  static final ConnectionConfig BILLING = cfg("billing-api", Env.STAGE, """
      {"baseUrls":["https://billing.stage.acme.dev/v1"],"auth":{"type":"bearer"}}""");

  static byte[] resource(String name) {
    try (InputStream in = OpenApiTemplateKindTest.class.getResourceAsStream("/openapi/" + name)) {
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static TemplateImporter.Result imported(ConnectionConfig cfg, String file, String options) {
    return KIND.importDocument(resource(file), cfg, options == null ? null : Fixtures.tree(options));
  }

  /** What a compiled template publishes, in a stable shape for the golden files. */
  static byte[] golden(ConnectionConfig cfg, String file) {
    TemplateImporter.Result r = imported(cfg, file, null);
    TemplateKind.CompileResult c = KIND.compile(r.definition(), cfg);
    assertThat(c.errors()).isEmpty();
    ArrayNode out = JsonNodeFactory.instance.arrayNode();
    for (TemplateKind.CompiledTool t : c.tools()) {
      ToolDefinition d = t.definition();
      ObjectNode o = out.addObject();
      o.put("opKey", t.opKey());
      o.put("name", d.name());
      o.put("title", d.title());
      o.put("description", d.description());
      o.put("nature", d.nature().name());
      o.put("mode", d.defaultMode().name() + " / prod " + d.prodDefaultMode().name());
      o.put("annotations", "readOnly=" + d.annotations().readOnlyHint() + " destructive="
          + d.annotations().destructiveHint() + " idempotent=" + d.annotations().idempotentHint() + " openWorld="
          + d.annotations().openWorldHint());
      o.set("inputSchema", d.inputSchema());
      o.set("binding", t.binding());
    }
    byte[] pretty = JsonText.pretty(out);
    byte[] withNewline = new byte[pretty.length + 1];
    System.arraycopy(pretty, 0, withNewline, 0, pretty.length);
    withNewline[pretty.length] = '\n';
    return withNewline;
  }

  // ── Import ──────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void theBillingFileBecomesSixToolsUnderTheConnectionsName() {
    TemplateImporter.Result r = imported(BILLING, "billing-30.yaml", null);

    assertThat(r.errors()).isEmpty();
    assertThat(r.warnings()).isEmpty();
    assertThat(r.operations()).extracting(TemplateImporter.Operation::opKey).containsExactly("POST /refunds",
        "GET /refunds", "GET /refunds/{refundId}", "DELETE /customers/{id}", "GET /tree", "PUT /tree");
    assertThat(r.operations()).extracting(TemplateImporter.Operation::name).containsExactly("billing_create_refund",
        "billing_list_refunds", "billing_get_refund_by_id", "billing_delete_customer", "billing_get_tree",
        "billing_put_tree");
    assertThat(r.operations()).allMatch(TemplateImporter.Operation::selected);
    assertThat(r.definition().path("name").asString()).isEqualTo("billing-api v2");
    assertThat(r.definition().path("prefix").asString()).isEqualTo("billing");
    assertThat(r.definition().path("server").asString()).isEqualTo("https://billing.stage.acme.dev/v1");
    assertThat(r.definition().at("/source/sha256").asString()).hasSize(64);
    assertThat(r.info().path("servers").toString()).isEqualTo("[\"https://billing.stage.acme.dev/v1\"]");
  }

  @Test
  void theBillingToolsMatchTheGolden() {
    assertThat(new String(golden(BILLING, "billing-30.yaml"), StandardCharsets.UTF_8))
        .isEqualTo(new String(resource("billing-30.tools.json"), StandardCharsets.UTF_8));
  }

  @Test
  void theTrapsOfTheBillingFile() {
    JsonNode ops = imported(BILLING, "billing-30.yaml", null).definition().path("operations");
    JsonNode refund = ops.get(0).path("inputSchema");
    // The gateway sets Authorization itself: the parameter is no input; Idempotency-Key stays the agent's.
    assertThat(refund.path("properties").has("Authorization")).isFalse();
    assertThat(refund.path("properties").has("Idempotency-Key")).isTrue();
    assertThat(refund.at("/properties/body/allOf/1/properties/reason/type").toString())
        .isEqualTo("[\"string\",\"null\"]");
    assertThat(ops.get(1).at("/inputSchema/properties/status/enum").toString())
        .isEqualTo("[\"pending\",\"succeeded\",\"failed\",null]");
    // id in the path and id in the query: two properties, the second named by its place.
    assertThat(ops.get(3).at("/inputSchema/properties").propertyNames()).containsExactly("id", "query_id");
    assertThat(ops.get(3).at("/inputSchema/properties/id").toString())
        .isEqualTo("{\"type\":\"integer\",\"format\":\"int64\",\"minimum\":1}");
    assertThat(ops.get(5).at("/inputSchema/properties/body/properties/children/items/description").asString())
        .isEqualTo("Recursive Node");
  }

  @ParameterizedTest
  @ValueSource(strings = {"billing-30.yaml", "petstore-expanded.yaml", "tictactoe.yaml"})
  void everyInputSchemaIsJsonSchema2020_12(String file) {
    ConnectionConfig cfg = cfg("pets", Env.DEV, "{\"baseUrls\":[\"https://billing.stage.acme.dev/v1\","
        + "\"https://petstore.swagger.io/v2\"],\"auth\":{\"type\":\"apiKeyHeader\",\"headerName\":\"api-key\"}}");
    TemplateImporter.Result r = imported(cfg, file, null);
    TemplateKind.CompileResult c = KIND.compile(r.definition(), cfg);
    assertThat(c.errors()).isEmpty();
    assertThat(c.tools()).isNotEmpty();

    SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    Schema meta = registry.getSchema(SchemaLocation.of("https://json-schema.org/draft/2020-12/schema"));
    for (TemplateKind.CompiledTool t : c.tools()) {
      JsonNode schema = t.definition().inputSchema();
      assertThat(meta.validate(schema)).as(t.definition().name()).isEmpty();
      assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
      assertThat(JsonText.compact(schema).getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(32 * 1024);
      assertThat(t.definition().name()).matches("[a-z][a-z0-9_]{0,63}");
      assertThat(schema.path("properties").has("connection")).isFalse();
    }
  }

  @Test
  void theOaiExamplesImportWithTheirOwnNames() {
    ConnectionConfig pets = cfg("petstore", Env.DEV, """
        {"baseUrls":["https://petstore.swagger.io/v2"],"auth":{"type":"none"}}""");
    TemplateImporter.Result petstore = imported(pets, "petstore-expanded.yaml", null);
    assertThat(petstore.info().path("openapi").asString()).isEqualTo("3.0.0");
    assertThat(petstore.operations()).extracting(TemplateImporter.Operation::name).containsExactly(
        "petstore_find_pets", "petstore_add_pet", "petstore_find_pet_by_id", "petstore_delete_pet");

    // tictactoe has no servers: the tools call the connection's first base URL.
    ConnectionConfig game = cfg("tictactoe", Env.DEV, """
        {"baseUrls":["https://ttt.test/api"],"auth":{"type":"apiKeyHeader","headerName":"api-key"}}""");
    TemplateImporter.Result ttt = imported(game, "tictactoe.yaml", null);
    assertThat(ttt.info().path("openapi").asString()).isEqualTo("3.1.0");
    assertThat(ttt.definition().path("server").asString()).isEqualTo("https://ttt.test/api");
    assertThat(ttt.operations()).extracting(TemplateImporter.Operation::name).containsExactly(
        "tictactoe_get_board", "tictactoe_get_square", "tictactoe_put_square");
    List<TemplateKind.CompiledTool> tools = KIND.compile(ttt.definition(), game).tools();
    assertThat(tools.get(2).definition().inputSchema().at("/properties/body/enum").toString())
        .isEqualTo("[\".\",\"X\",\"O\"]");
    assertThat(tools.get(2).definition().description()).endsWith("PUT /api/board/{row}/{column}. Needs approval: a "
        + "person approves the exact request.");
  }

  // ── What the importer refuses ───────────────────────────────────────────────────────────────────────────────

  private static List<String> errors(String document) {
    return KIND.importDocument(document.getBytes(StandardCharsets.UTF_8), BILLING, null).errors();
  }

  @ParameterizedTest
  @ValueSource(strings = {"file:///etc/passwd", "./money.yaml#/Money", "http://169.254.169.254/latest/money.yaml"})
  void aReferenceToAnotherFileIsRefused(String ref) {
    String doc = new String(resource("billing-30.yaml"), StandardCharsets.UTF_8)
        .replace("'#/components/schemas/Money'", "'" + ref + "'");
    assertThat(errors(doc)).singleElement().asString().startsWith("The file refers to other files (1 external $ref, "
        + "e.g. " + ref + "). Bundle the file into one document first.");
  }

  @Test
  void otherFormatsAndHostileYamlAreRefused() {
    assertThat(errors("{\"swagger\":\"2.0\",\"info\":{}}")).containsExactly("This is Swagger 2.0. Convert it to "
        + "OpenAPI 3 first (npx swagger2openapi).");
    assertThat(errors("openapi: 2.9.0\npaths: {}\n")).containsExactly("Only OpenAPI 3.0 and 3.1 can be imported; "
        + "this is 2.9.0.");
    assertThat(errors("")).containsExactly("The file is empty.");
    assertThat(errors("[1, 2]")).containsExactly("The file isn't an OpenAPI document: it has no top-level object.");
    assertThat(errors("openapi: 3.0.3\nopenapi: 3.1.0\n")).singleElement().asString()
        .startsWith("The file isn't valid YAML");
    assertThat(errors("openapi: 3.0.3\nx: &a [*a]\n")).singleElement().asString()
        .matches("The file (has a recursive YAML alias\\.|isn't valid YAML.*)");

    // Billion laughs: aliases of aliases, refused at the alias limit long before memory runs out.
    StringBuilder bomb = new StringBuilder("openapi: 3.0.3\na0: &a0 [x, x, x, x, x, x, x, x, x, x]\n");
    for (int i = 1; i < 10; i++) {
      bomb.append("a").append(i).append(": &a").append(i).append(" [");
      for (int j = 0; j < 10; j++) {
        bomb.append(j == 0 ? "" : ", ").append("*a").append(i - 1);
      }
      bomb.append("]\n");
    }
    assertThat(errors(bomb.toString())).singleElement().asString().startsWith("The file isn't valid YAML");

    StringBuilder deep = new StringBuilder("{\"openapi\":\"3.1.0\",\"x\":");
    deep.append("[".repeat(300)).append("]".repeat(300)).append('}');
    assertThat(errors(deep.toString())).singleElement().asString().startsWith("The file isn't valid JSON");
  }

  @Test
  void yamlKeepsYesAndDatesAsStrings() throws Exception {
    JsonNode root = OpenApiDocument.read("openapi: 3.1.0\ninfo: {title: yes, version: 2026-10-25}\n"
        .getBytes(StandardCharsets.UTF_8));
    assertThat(root.at("/info/title").asString()).isEqualTo("yes");
    assertThat(root.at("/info/version").asString()).isEqualTo("2026-10-25");
  }

  @Test
  void aServerOutsideTheConnectionIsRefused() {
    ConnectionConfig other = cfg("billing-api", Env.STAGE, """
        {"baseUrls":["https://billing.prod.acme.dev/v1"],"auth":{"type":"bearer"}}""");
    TemplateImporter.Result r = imported(other, "billing-30.yaml", null);
    assertThat(r.ok()).isFalse();
    assertThat(r.errors()).containsExactly("None of the document's servers is under a base URL of billing-api "
        + "(https://billing.prod.acme.dev/v1). Add the server to its Base URLs, or pick a server under them.");
  }

  // ── Selection and names ─────────────────────────────────────────────────────────────────────────────────────

  /** A document of {@code n} GET operations {@code /items/<i>}. */
  private static byte[] many(int n) {
    StringBuilder doc = new StringBuilder("{\"openapi\":\"3.1.0\",\"info\":{\"title\":\"Many\",\"version\":\"1\"},"
        + "\"paths\":{");
    for (int i = 0; i < n; i++) {
      doc.append(i == 0 ? "" : ",").append("\"/items/").append(i).append("\":{\"get\":{\"operationId\":\"item")
          .append(i).append("\",\"responses\":{}}}");
    }
    return doc.append("}}").toString().getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void aLargeFileSelectsNothingUntilThePersonPicks() {
    TemplateImporter.Result all = KIND.importDocument(many(51), BILLING, null);
    assertThat(all.operations()).noneMatch(TemplateImporter.Operation::selected);
    assertThat(all.warnings()).contains("The file has 51 operations: pick up to 200 (by tag or path) to make tools of.");
    assertThat(KIND.compile(all.definition(), BILLING).errors()).containsExactly("Select at least one operation.");

    TemplateImporter.Result picked = KIND.importDocument(many(51), BILLING, Fixtures.tree(
        "{\"select\":[\"GET /items/3\",\"GET /items/7\",\"GET /nowhere\"]}"));
    assertThat(picked.definition().path("operations")).hasSize(2);
    assertThat(picked.warnings()).contains("GET /nowhere isn't an operation of the file.");

    ArrayNode select = JsonNodeFactory.instance.arrayNode();
    for (int i = 0; i < 201; i++) {
      select.add("GET /items/" + i);
    }
    ObjectNode options = JsonNodeFactory.instance.objectNode();
    options.set("select", select);
    TemplateImporter.Result tooMany = KIND.importDocument(many(201), BILLING, options);
    assertThat(tooMany.ok()).isFalse();
    assertThat(tooMany.errors()).containsExactly("201 operations are selected; a template has at most 200.");
  }

  @Test
  void twoHundredOperationsCompileAndOneMoreDoesnt() {
    ArrayNode select = JsonNodeFactory.instance.arrayNode();
    for (int i = 0; i < 200; i++) {
      select.add("GET /items/" + i);
    }
    ObjectNode options = JsonNodeFactory.instance.objectNode();
    options.set("select", select);
    JsonNode definition = KIND.importDocument(many(200), BILLING, options).definition();
    TemplateKind.CompileResult ok = KIND.compile(definition, BILLING);
    assertThat(ok.errors()).isEmpty();
    assertThat(ok.tools()).hasSize(200);
    assertThat(ok.warnings()).contains("200 tools: some clients show only the first 40 tools of a server. Add only "
        + "what an endpoint needs.");

    ObjectNode more = (ObjectNode) definition.deepCopy();
    ObjectNode extra = ((ObjectNode) more.path("operations").get(0)).deepCopy();
    extra.put("opKey", "GET /extra");
    extra.put("path", "/extra");
    extra.put("name", "billing_extra");
    ((ArrayNode) more.path("operations")).add(extra);
    assertThat(KIND.compile(more, BILLING).errors()).containsExactly("201 operations; a template has at most 200.");
  }

  @Test
  void namesStayOffTheBuiltInTools() {
    ObjectNode reserved = (ObjectNode) Fixtures.tree("{\"reserved\":{\"names\":[\"describe_connection\","
        + "\"http_get\"],\"prefixes\":[\"http_\",\"sql_\"]}}");
    ConnectionConfig http = cfg("http-api", Env.DEV, """
        {"baseUrls":["https://billing.stage.acme.dev/v1"],"auth":{"type":"bearer"}}""");

    // The default prefix of http-api would be http, the family of http_get: it becomes httpapi.
    assertThat(KIND.importDocument(resource("billing-30.yaml"), http, reserved).definition().path("prefix")
        .asString()).isEqualTo("httpapi");
    ObjectNode chosen = reserved.deepCopy().put("prefix", "sql");
    assertThat(KIND.importDocument(resource("billing-30.yaml"), http, chosen).errors())
        .containsExactly("The prefix sql is the family of built-in tools (sql_…). Pick another.");

    // Without a prefix, operationIds that read as built-in tools are renamed.
    String doc = "{\"openapi\":\"3.1.0\",\"info\":{\"title\":\"t\",\"version\":\"1\"},\"paths\":{"
        + "\"/q\":{\"get\":{\"operationId\":\"sqlQuery\",\"responses\":{}}},"
        + "\"/c\":{\"get\":{\"operationId\":\"describeConnection\",\"responses\":{}}}}}";
    TemplateImporter.Result bare = KIND.importDocument(doc.getBytes(StandardCharsets.UTF_8), http,
        reserved.deepCopy().put("prefix", ""));
    assertThat(bare.operations()).extracting(TemplateImporter.Operation::name)
        .containsExactly("op_sql_query", "describe_connection_2");
  }

  @Test
  void toolNamesAreSnakeCaseAndAtMost64() {
    assertThat(OpenApiImport.toolName("billing", "createRefund", "POST", "/refunds")).isEqualTo("billing_create_refund");
    assertThat(OpenApiImport.toolName("gh", "repos/list-for-authenticated-user", "GET", "/user/repos"))
        .isEqualTo("gh_repos_list_for_authenticated_user");
    assertThat(OpenApiImport.toolName("billing", null, "GET", "/refunds/{refundId}"))
        .isEqualTo("billing_get_refunds_by_refund_id");
    assertThat(OpenApiImport.toolName("api", "HTTPServerStatus", "GET", "/")).isEqualTo("api_http_server_status");
    String longName = OpenApiImport.toolName("stripe", "GetIssuingAuthorizationsAuthorizationVerificationsAndMore"
        + "AndMoreStill", "GET", "/v1/x");
    assertThat(longName).hasSize(64).matches("stripe_get_issuing_[a-z_]+_[0-9a-f]{8}");
    assertThat(OpenApiTemplateKind.defaultPrefix("billing-api")).isEqualTo("billing");
    assertThat(OpenApiTemplateKind.defaultPrefix("42-ops")).isEqualTo("api");
    assertThat(OpenApiTemplateKind.defaultName("billing-api", "2.0", "Billing API")).isEqualTo("billing-api v2");
  }

  // ── What the compiler refuses ───────────────────────────────────────────────────────────────────────────────

  private static ObjectNode billingDefinition() {
    return (ObjectNode) imported(BILLING, "billing-30.yaml", null).definition();
  }

  private static ObjectNode op(ObjectNode definition, int i) {
    return (ObjectNode) definition.path("operations").get(i);
  }

  @Test
  void aHandEditedDefinitionIsCheckedAgain() {
    ObjectNode d = billingDefinition();
    op(d, 1).put("name", "billing_create_refund");
    assertThat(KIND.compile(d, BILLING).errors()).containsExactly("billing_create_refund is the name of two "
        + "operations.");

    d = billingDefinition();
    op(d, 1).put("name", "list_refunds");
    assertThat(KIND.compile(d, BILLING).errors()).containsExactly("GET /refunds: the tool name list_refunds doesn't "
        + "start with the prefix billing_.");

    d = billingDefinition();
    ((ObjectNode) op(d, 2).path("inputSchema").path("properties")).putObject("connection").put("type", "string");
    assertThat(KIND.compile(d, BILLING).errors()).containsExactly("GET /refunds/{refundId}: a template tool has no "
        + "connection argument; rename the parameter.");

    d = billingDefinition();
    ((ObjectNode) op(d, 0).path("params").get(0)).put("name", "Host");
    assertThat(KIND.compile(d, BILLING).errors()).containsExactly("POST /refunds: the header Host can't be sent by "
        + "an agent.");

    d = billingDefinition();
    d.put("server", "https://evil.example/v1");
    assertThat(KIND.compile(d, BILLING).errors()).containsExactly("Server https://evil.example/v1 isn't under a base "
        + "URL of billing-api.");

    d = billingDefinition();
    op(d, 2).put("path", "/refunds/{other}");
    op(d, 2).put("opKey", "GET /refunds/{other}");
    assertThat(KIND.compile(d, BILLING).errors()).containsExactly("GET /refunds/{other}: path parameter {other} has "
        + "no binding.");
  }

  @Test
  void aPostMarkedReadOnlyNeedsConfirmationAndNeverOnProd() {
    ObjectNode d = billingDefinition();
    op(d, 0).put("nature", "read");
    assertThat(KIND.compile(d, BILLING).errors()).containsExactly("POST /refunds is marked read only: confirm that it "
        + "doesn't change state.");

    op(d, 0).put("readOnlyConfirmed", true);
    TemplateKind.CompileResult confirmed = KIND.compile(d, BILLING);
    assertThat(confirmed.errors()).isEmpty();
    assertThat(confirmed.warnings()).contains("POST /refunds runs without approval as a read: you confirmed it "
        + "doesn't change state.");
    assertThat(confirmed.tools().getFirst().definition().nature()).isEqualTo(ToolDefinition.Nature.READ);

    ConnectionConfig prod = cfg("billing-api", Env.PROD, """
        {"baseUrls":["https://billing.stage.acme.dev/v1"],"auth":{"type":"bearer"}}""");
    assertThat(KIND.compile(d, prod).errors()).containsExactly("POST /refunds can't be marked read only on prod: "
        + "every POST there waits for a person.");

    ObjectNode get = billingDefinition();
    op(get, 4).put("nature", "mutation");
    TemplateKind.CompiledTool marked = KIND.compile(get, BILLING).tools().get(4);
    assertThat(marked.definition().nature()).isEqualTo(ToolDefinition.Nature.MUTATION);
    assertThat(marked.definition().defaultMode()).isEqualTo(ToolDefinition.Mode.APPROVAL);
  }

  // ── Requests ────────────────────────────────────────────────────────────────────────────────────────────────

  private static ActionPreview preview(String operation, String arguments) {
    ObjectNode args = (ObjectNode) Fixtures.tree("{\"operation\":\"" + operation + "\",\"arguments\":" + arguments
        + "}");
    return KIND.preview(billingDefinition(), args, BILLING);
  }

  @Test
  void theRequestOfAnOperationIsBuiltFromItsBinding() {
    assertThat(preview("billing_create_refund", "{\"Idempotency-Key\":\"refund-48213\",\"body\":{\"order_id\":48213,"
        + "\"amount\":1999,\"currency\":\"EUR\"}}").text()).isEqualTo("""
        POST https://billing.stage.acme.dev/v1/refunds
        Content-Type: application/json
        Idempotency-Key: refund-48213
        Authorization: ‹masked›

        {
          "order_id": 48213,
          "amount": 1999,
          "currency": "EUR"
        }""");
    assertThat(preview("GET /refunds", "{\"limit\":5,\"status\":\"pending\",\"created\":{\"gte\":1700000000}}").text())
        .isEqualTo("GET https://billing.stage.acme.dev/v1/refunds?limit=5&status=pending&created%5Bgte%5D=1700000000"
            + "\nAuthorization: ‹masked›");
    assertThat(preview("billing_get_refund_by_id", "{\"refundId\":\"re_ü 1\"}").text())
        .startsWith("GET https://billing.stage.acme.dev/v1/refunds/re_%C3%BC%201\n");
    assertThat(preview("billing_delete_customer", "{\"id\":42,\"query_id\":\"x\"}").text())
        .startsWith("DELETE https://billing.stage.acme.dev/v1/customers/42?id=x\n");
  }

  @ParameterizedTest
  @ValueSource(strings = {"../admin", "a/b", "..", ".", "", "x?y", "x#y", "a\\\\b"})
  void aPathParameterNeverLeavesItsSegment(String value) {
    ActionPreview p = preview("billing_get_refund_by_id", "{\"refundId\":\"" + value + "\"}");
    assertThat(p.kind()).isEqualTo(ActionPreview.Kind.TEXT);
    assertThat(p.summary()).isEqualTo("Can't build the request");
  }

  @Test
  void aTemplateCallClassifiesByItsNatureAndRunsOnItsConnection() {
    ObjectNode d = billingDefinition();
    TemplateKind.CompileResult c = KIND.compile(d, BILLING);
    HttpConnector connector = new HttpConnector();
    var policy = Fixtures.policy(Env.STAGE, "https://billing.stage.acme.dev/v1");

    var list = templateCall(c.tools().get(1), "{\"limit\":3}");
    assertThat(connector.classify(list, BILLING, policy).kind()).isEqualTo(
        ai.msrv.gaterook.connector.api.Classification.Kind.READ);
    var refund = templateCall(c.tools().get(0), "{\"body\":{\"order_id\":1,\"amount\":1,\"currency\":\"EUR\"}}");
    assertThat(connector.classify(refund, BILLING, policy).kind()).isEqualTo(
        ai.msrv.gaterook.connector.api.Classification.Kind.MUTATION);
    ActionPreview action = connector.describeAction(refund, BILLING);
    assertThat(action.fields()).first().isEqualTo(new ActionPreview.Field("Operation",
        "createRefund · billing-api v2 (template v3)"));
    assertThat(action.auditFacets()).containsEntry("template", "billing-api v2@v3")
        .containsEntry("http.operation", "POST /refunds");
    assertThat(action.payload().path("operation").toString())
        .isEqualTo("{\"template\":\"tpl_billing\",\"version\":3,\"opKey\":\"POST /refunds\"}");
  }

  // ── Hostile documents: the import ends in seconds whatever a file tries ─────────────────────────────────────

  private static TemplateImporter.Result importWithin(String document) {
    return org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(10),
        () -> KIND.importDocument(document.getBytes(StandardCharsets.UTF_8), BILLING, null));
  }

  private static final String HEAD = "openapi: 3.0.3\ninfo: {title: t, version: '1'}\n"
      + "servers: [{url: 'https://billing.stage.acme.dev/v1'}]\n";

  @Test
  void yamlAliasesUnderTheAliasLimitDontExpandPastTheFile() {
    // 45 aliases of collections, within snakeyaml's 50: 3^16 nodes once expanded.
    StringBuilder bomb = new StringBuilder(HEAD).append("x-a0: &a0 [x, x, x]\n");
    for (int i = 1; i <= 15; i++) {
      bomb.append("x-a").append(i).append(": &a").append(i).append(" [*a").append(i - 1).append(", *a")
          .append(i - 1).append(", *a").append(i - 1).append("]\n");
    }
    assertThat(importWithin(bomb.append("paths: {}\n").toString()).errors()).singleElement().asString()
        .startsWith("The file's YAML aliases expand to more than");

    // Aliases of a scalar have no limit in snakeyaml: one long string repeated in an enum.
    StringBuilder scalars = new StringBuilder(HEAD).append("x-s: &s '").append("y".repeat(100_000)).append("'\n")
        .append("paths:\n  /p:\n    get:\n      parameters:\n      - {name: q, in: query, schema: {type: string, enum: [");
    for (int i = 0; i < 20_000; i++) {
      scalars.append(i == 0 ? "" : ", ").append("*s");
    }
    scalars.append("]}}\n      responses: {}\n");
    assertThat(importWithin(scalars.toString()).errors()).singleElement().asString()
        .startsWith("The file's YAML aliases expand to more than");
  }

  @Test
  void aLargeSchemaReferencedManyTimesIsSkippedNotExpanded() {
    // A 2 MB enum that every one of 1,500 properties refers to: gigabytes once inlined.
    StringBuilder doc = new StringBuilder("{\"openapi\":\"3.1.0\",\"info\":{\"title\":\"t\",\"version\":\"1\"},"
        + "\"components\":{\"schemas\":{\"Big\":{\"type\":\"string\",\"enum\":[");
    for (int i = 0; i < 2_000; i++) {
      doc.append(i == 0 ? "" : ",").append('"').append(i).append("z".repeat(1_000)).append('"');
    }
    doc.append("]}}},\"paths\":{\"/p\":{\"post\":{\"operationId\":\"bomb\",\"requestBody\":{\"content\":{"
        + "\"application/json\":{\"schema\":{\"type\":\"object\",\"properties\":{");
    for (int i = 0; i < 1_500; i++) {
      doc.append(i == 0 ? "" : ",").append("\"p").append(i).append("\":{\"$ref\":\"#/components/schemas/Big\"}");
    }
    doc.append("}}}}},\"responses\":{}}},\"/ok\":{\"get\":{\"operationId\":\"fine\",\"responses\":{}}}}}");

    TemplateImporter.Result r = importWithin(doc.toString());
    assertThat(r.errors()).isEmpty();
    assertThat(r.operations()).filteredOn(o -> o.opKey().equals("POST /p")).singleElement()
        .extracting(TemplateImporter.Operation::skipped).asString().contains("more than 32 KB");
    assertThat(r.operations()).filteredOn(o -> o.opKey().equals("GET /ok")).singleElement()
        .extracting(TemplateImporter.Operation::skipped).isNull();
  }

  @Test
  void aFileWhoseSchemasExpandPastTheBudgetIsRefused() {
    // 400 operations, each with a body that refers to a 30 KB schema: under the limit of one, over that of the file.
    StringBuilder doc = new StringBuilder("{\"openapi\":\"3.1.0\",\"info\":{\"title\":\"t\",\"version\":\"1\"},"
        + "\"components\":{\"schemas\":{\"Mid\":{\"type\":\"object\",\"properties\":{");
    for (int i = 0; i < 300; i++) {
      doc.append(i == 0 ? "" : ",").append("\"f").append(i).append("\":{\"type\":\"string\",\"default\":\"")
          .append("d".repeat(80)).append("\"}");
    }
    doc.append("}}}},\"paths\":{");
    for (int i = 0; i < 4_000; i++) {
      doc.append(i == 0 ? "" : ",").append("\"/p").append(i).append("\":{\"post\":{\"requestBody\":{\"content\":{"
          + "\"application/json\":{\"schema\":{\"$ref\":\"#/components/schemas/Mid\"}}}},\"responses\":{}}}");
    }
    doc.append("}}");

    TemplateImporter.Result r = importWithin(doc.toString());
    assertThat(r.errors()).singleElement().asString().startsWith("The file's schemas expand to more than");
  }

  @Test
  void serverVariablesAreFilledInOnce() {
    // Each default names the next variable: replaced one after another, the URL would double 40 times.
    StringBuilder vars = new StringBuilder();
    for (int i = 0; i < 40; i++) {
      vars.append(i == 0 ? "" : ", ").append("v").append(i).append(": {default: '{v").append(i + 1).append("}{v")
          .append(i + 1).append("}'}");
    }
    String doc = "openapi: 3.0.3\ninfo: {title: t, version: '1'}\n"
        + "servers: [{url: 'https://billing.stage.acme.dev/v1/x{v0}', variables: {" + vars + "}},"
        + " {url: 'https://billing.stage.acme.dev/{env}', variables: {env: {default: v1}}}]\n"
        + "paths:\n  /ok:\n    get: {operationId: fine, responses: {}}\n";

    TemplateImporter.Result r = importWithin(doc);
    assertThat(r.errors()).isEmpty();
    assertThat(r.info().path("servers").toString()).hasSizeLessThan(400)
        .contains("https://billing.stage.acme.dev/v1\"");
  }

  @Test
  void whatOnlyAHostileFileHasIsSkippedOrRefused() {
    // No operationId: the tool name came from a path of 49,000 capitals (or braces) through a quadratic regex;
    // Jackson's own limit on names is 50,000 characters.
    String longPath = "{\"openapi\":\"3.1.0\",\"info\":{\"title\":\"t\",\"version\":\"1\"},\"paths\":{"
        + "\"/ok\":{\"get\":{\"operationId\":\"fine\",\"responses\":{}}},"
        + "\"/" + "A".repeat(49_000) + "\":{\"get\":{\"responses\":{}}},"
        + "\"/" + "{".repeat(49_000) + "\":{\"get\":{\"responses\":{}}}}}";
    TemplateImporter.Result paths = importWithin(longPath);
    assertThat(paths.errors()).isEmpty();
    assertThat(paths.operations()).filteredOn(o -> o.skipped() != null).hasSize(2)
        .allSatisfy(o -> assertThat(o.skipped()).isEqualTo("the path is longer than 2,048 characters"));

    // 50,000 distinct names that make the same argument name: each took a longer search for a free one.
    StringBuilder params = new StringBuilder("{\"openapi\":\"3.1.0\",\"info\":{\"title\":\"t\",\"version\":\"1\"},"
        + "\"paths\":{\"/p\":{\"get\":{\"operationId\":\"flood\",\"parameters\":[");
    for (int i = 0; i < 50_000; i++) {
      params.append(i == 0 ? "" : ",").append("{\"name\":\"a").append((char) (0x4E00 + i % 20_000))
          .append((char) (0x4E00 + i / 20_000)).append("b\",\"in\":\"query\",\"schema\":{\"type\":\"string\"}}");
    }
    params.append("],\"responses\":{}}}}}");
    TemplateImporter.Result flood = importWithin(params.toString());
    assertThat(flood.operations()).singleElement().extracting(TemplateImporter.Operation::skipped)
        .isEqualTo("more than 200 parameters");

    assertThat(KIND.importDocument(many(10_001), BILLING, null).errors()).containsExactly("The file has more than "
        + "10,000 operations. Import a smaller file with only the paths you need.");
  }

  static ai.msrv.gaterook.connector.api.ToolCall templateCall(TemplateKind.CompiledTool tool, String arguments) {
    return new ai.msrv.gaterook.connector.api.ToolCall("tc_tpl", tool.definition().name(), "billing-api",
        Fixtures.object(arguments), new ai.msrv.gaterook.connector.api.ToolCall.TemplateBinding("tpl_billing", 3,
        tool.opKey(), tool.binding()));
  }
}
