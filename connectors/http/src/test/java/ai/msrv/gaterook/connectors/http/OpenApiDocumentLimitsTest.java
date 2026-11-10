package ai.msrv.gaterook.connectors.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import ai.msrv.gaterook.connector.api.TemplateKind;
import ai.msrv.gaterook.connectors.support.TemplateImporter;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * What a hostile or broken OpenAPI file can't do to the import (report 10 §6.5): every limit the JSON reader has holds
 * for YAML too (nesting, number and text lengths, keys that are collections), a number that a double or a decimal can't
 * hold is refused or kept as text instead of failing inside the importer, and an operation whose schema holds a number
 * the argument check can't read is skipped instead of being published as a tool that fails on every call.
 */
class OpenApiDocumentLimitsTest {

  private static final OpenApiTemplateKind KIND = (OpenApiTemplateKind) new HttpConnector().templateKinds().getFirst();
  private static final String HEAD = "openapi: 3.1.0\ninfo: {title: t, version: '1'}\n"
      + "servers: [{url: 'https://billing.stage.acme.dev/v1'}]\n";
  private static final SchemaRegistry SCHEMAS = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

  private static TemplateImporter.Result imported(String document) {
    return assertTimeoutPreemptively(Duration.ofSeconds(20), () -> KIND.importDocument(
        document.getBytes(StandardCharsets.UTF_8), OpenApiTemplateKindTest.BILLING, null));
  }

  @Test
  void aYamlKeyThatIsACollectionIsRefusedBeforeItIsTurnedIntoText() {
    assertThat(imported(HEAD + "x-k:\n  ? [a, b]\n  : c\npaths: {}\n").errors()).singleElement().asString()
        .startsWith("The file isn't valid YAML");
  }

  @Test
  void yamlNestedFarDeeperThanTheLimitIsRefusedWithoutExhaustingTheStack() {
    String deep = HEAD + "x-deep: " + "[".repeat(200_000) + "]".repeat(200_000) + "\npaths: {}\n";

    assertThat(imported(deep).errors()).containsExactly("The file nests deeper than 200 levels.");
    String fine = HEAD + "x-ok: " + "[".repeat(150) + "]".repeat(150) + "\npaths: {}\n";
    assertThat(imported(fine).errors()).isEmpty();
  }

  @Test
  void aYamlNumberOrTextLongerThanTheJsonReaderAllowsIsRefused() {
    assertThat(imported(HEAD + "x-n: " + "7".repeat(1_001) + "\npaths: {}\n").errors())
        .containsExactly("The file has a number longer than 1,000 characters.");
    assertThat(imported(HEAD + "x-n: 0." + "7".repeat(998) + "\npaths: {}\n").errors()).isEmpty();
    assertThat(imported(HEAD + "x-s: '" + "y".repeat(5_000_001) + "'\npaths: {}\n").errors())
        .containsExactly("The file has a text value longer than 5,000,000 characters.");
  }

  @Test
  void yamlNumbersADoubleCantHoldAreReadAsJsonReadsThemNotAFailureOfTheImport() throws Exception {
    JsonNode root = OpenApiDocument.read((HEAD + "x-v: [.inf, -.inf, .nan, 1e999, -1.5e-999, 2.5, 1e5, 7]\n"
        + "paths: {}\n").getBytes(StandardCharsets.UTF_8));
    JsonNode json = OpenApiDocument.read("{\"openapi\":\"3.1.0\",\"x-v\":[1e999,-1.5e-999]}"
        .getBytes(StandardCharsets.UTF_8));

    // JSON has no number for an infinity or NaN: they stay text. The others are the decimals the JSON reader makes.
    assertThat(root.path("x-v").toString()).isEqualTo("[\".inf\",\"-.inf\",\".nan\",1E+999,-1.5E-999,2.5,100000.0,7]");
    assertThat(root.at("/x-v/3")).isEqualTo(json.at("/x-v/0"));
    assertThat(root.at("/x-v/4")).isEqualTo(json.at("/x-v/1"));
  }

  @Test
  void aJsonNumberBeyondWhatADecimalHoldsIsRefusedNotAFailureOfTheImport() {
    assertThat(imported("{\"openapi\":\"3.1.0\",\"x\":1e2147483648,\"paths\":{}}").errors()).singleElement()
        .asString().startsWith("The file isn't valid JSON");
    assertThat(imported("{\"openapi\":\"3.1.0\",\"x\":1e999999999,\"paths\":{}}").errors()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"\"multipleOf\":1e400", "\"multipleOf\":1e999", "\"minimum\":\"0\"", "\"maximum\":\"10\"",
      "\"minLength\":3000000000", "\"maxItems\":1e300", "\"maxContains\":4294967296"})
  void anOperationWhoseSchemaHoldsANumberTheArgumentCheckCantReadIsSkipped(String keyword) {
    String doc = "{\"openapi\":\"3.1.0\",\"info\":{\"title\":\"t\",\"version\":\"1\"},"
        + "\"servers\":[{\"url\":\"https://billing.stage.acme.dev/v1\"}],\"paths\":{"
        + "\"/bad\":{\"get\":{\"operationId\":\"bad\",\"parameters\":[{\"name\":\"q\",\"in\":\"query\","
        + "\"schema\":{\"type\":\"number\"," + keyword + "}}],\"responses\":{}}},"
        + "\"/ok\":{\"get\":{\"operationId\":\"fine\",\"parameters\":[{\"name\":\"q\",\"in\":\"query\","
        + "\"schema\":{\"type\":\"number\",\"multipleOf\":0.5,\"minimum\":0,\"maxLength\":10}}],\"responses\":{}}}}}";

    TemplateImporter.Result r = imported(doc);

    assertThat(r.errors()).isEmpty();
    assertThat(r.operations()).filteredOn(o -> o.opKey().equals("GET /bad")).singleElement()
        .extracting(TemplateImporter.Operation::skipped).asString()
        .startsWith("the schema's " + keyword.substring(1, keyword.indexOf('"', 1)) + " ")
        .endsWith("isn't a number the argument check can use");
    assertThat(r.operations()).filteredOn(o -> o.opKey().equals("GET /ok")).singleElement()
        .extracting(TemplateImporter.Operation::skipped).isNull();
    TemplateKind.CompileResult c = KIND.compile(r.definition(), OpenApiTemplateKindTest.BILLING);
    assertThat(c.errors()).isEmpty();
    for (TemplateKind.CompiledTool t : c.tools()) {
      // What the core does with a tool's input schema at every call: it must compile and run.
      Schema schema = SCHEMAS.getSchema(t.definition().inputSchema());
      assertThat(schema.validate(Fixtures.tree("{\"q\":1}"))).as(t.opKey()).isEmpty();
    }
  }
}
