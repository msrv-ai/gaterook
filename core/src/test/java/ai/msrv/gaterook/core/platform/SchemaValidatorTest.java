package ai.msrv.gaterook.core.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The bounds of an agent's tool arguments (ARCHITECTURE.md §5 step 5): depth ≤ 32, ≤ 2,000 subschemas, a 50 ms budget
 * that holds even inside a backtracking {@code pattern}; and the plain validation the UI's forms rely on.
 */
class SchemaValidatorTest {

  static final JsonMapper JSON = JsonMapper.builder().build();
  final SchemaValidator validator = new SchemaValidator();

  static JsonNode json(String text) {
    return JSON.readTree(text);
  }

  @Test
  void violationsNameTheFieldByItsPointer() {
    JsonNode schema = json("{\"type\":\"object\",\"required\":[\"sql\"],\"properties\":{\"sql\":{\"type\":\"string\"},"
        + "\"limit\":{\"type\":\"integer\",\"maximum\":10}}}");
    assertThat(validator.validate(schema, json("{\"limit\":11}")))
        .extracting(SchemaValidator.Violation::pointer).containsExactlyInAnyOrder("/sql", "/limit");
    assertThat(validator.validateBounded(schema, json("{\"sql\":\"SELECT 1\",\"limit\":3}"), SchemaValidator.BUDGET))
        .isEmpty();
  }

  @Test
  void argumentsNestedDeeperThan32LevelsAreRefusedBeforeTheSchemaRuns() {
    JsonNode schema = json("{\"type\":\"object\"}");
    assertThat(validator.validateBounded(schema, nested(SchemaValidator.MAX_DEPTH), SchemaValidator.BUDGET))
        .isEmpty();
    assertThat(validator.validateBounded(schema, nested(SchemaValidator.MAX_DEPTH + 1), SchemaValidator.BUDGET))
        .singleElement().satisfies(v -> {
          assertThat(v.pointer()).isEqualTo("/");
          assertThat(v.message()).isEqualTo("nested deeper than 32 levels");
        });
    // Arrays count as levels too; a scalar is none.
    ArrayNode deep = JsonNodeFactory.instance.arrayNode();
    ArrayNode at = deep;
    for (int i = 1; i < 40; i++) {
      at = at.addArray();
    }
    ObjectNode args = JsonNodeFactory.instance.objectNode();
    args.set("list", deep);
    assertThat(SchemaValidator.depth(args, 100)).isEqualTo(41);
    assertThat(SchemaValidator.depth(json("\"text\""), 100)).isZero();
  }

  @Test
  void aSchemaWithMoreThan2000SubschemasIsRefused() {
    assertThat(validator.validateBounded(withProperties(SchemaValidator.MAX_SUBSCHEMAS - 1), json("{}"),
        SchemaValidator.BUDGET)).isEmpty();
    assertThat(validator.validateBounded(withProperties(SchemaValidator.MAX_SUBSCHEMAS), json("{}"),
        SchemaValidator.BUDGET)).singleElement()
        .satisfies(v -> assertThat(v.message()).contains("more than 2000 subschemas"));
    // Every place a schema can sit counts; keyword containers and plain values don't.
    assertThat(SchemaValidator.subschemas(json("{\"properties\":{\"a\":{\"items\":{\"type\":\"string\"}},"
        + "\"b\":{\"anyOf\":[{\"type\":\"string\"},true]}},\"$defs\":{\"c\":{\"not\":{\"const\":1}}},"
        + "\"required\":[\"a\"],\"enum\":[{\"type\":\"x\"}]}"), 100)).isEqualTo(8);
  }

  @Test
  void aBacktrackingPatternStopsAtTheBudget() {
    // Polynomial backtracking the JDK's loop memo doesn't cut: seconds on this input without a budget.
    JsonNode schema = json("{\"type\":\"object\",\"properties\":{\"v\":{\"type\":\"string\",\"pattern\":"
        + "\"^(.*a){12}$\"}}}");
    ObjectNode args = JsonNodeFactory.instance.objectNode().put("v", "a".repeat(30) + "b");
    long t0 = System.nanoTime();
    assertThat(validator.validateBounded(schema, args, Duration.ofMillis(50))).singleElement()
        .satisfies(v -> assertThat(v.message()).isEqualTo("took longer than 50 ms to check"));
    assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(2));
    // The same pattern on what it matches, and outside a bounded validation, behaves as networknt's own. Matching
    // still backtracks a lot: with a loaded machine 50 ms isn't enough, and a budget mustn't fail a valid argument.
    assertThat(validator.validateBounded(schema, JsonNodeFactory.instance.objectNode().put("v", "a".repeat(12)),
        Duration.ofSeconds(5))).isEmpty();
    assertThat(validator.validate(schema, JsonNodeFactory.instance.objectNode().put("v", "aab"))).hasSize(1);
  }

  @Test
  void aSlowSchemaStopsAtTheBudgetBetweenKeywords() {
    // Every item against a oneOf of 200 branches: well over 1 ms, so a 1 ms budget runs out between keywords.
    ObjectNode schema = JsonNodeFactory.instance.objectNode();
    schema.put("type", "array");
    ArrayNode branches = schema.putObject("items").putArray("oneOf");
    for (int i = 0; i < 200; i++) {
      branches.addObject().put("const", "value-" + i);
    }
    ArrayNode items = JsonNodeFactory.instance.arrayNode();
    for (int i = 0; i < 5_000; i++) {
      items.add("value-" + (i % 200));
    }
    assertThat(validator.validateBounded(schema, items, Duration.ofMillis(1))).singleElement()
        .satisfies(v -> assertThat(v.message()).isEqualTo("took longer than 1 ms to check"));
  }

  static JsonNode nested(int levels) {
    ObjectNode root = JsonNodeFactory.instance.objectNode();
    ObjectNode at = root;
    for (int i = 1; i < levels; i++) {
      at = at.putObject("n");
    }
    return root;
  }

  static ObjectNode withProperties(int n) {
    ObjectNode schema = JsonNodeFactory.instance.objectNode();
    schema.put("type", "object");
    ObjectNode props = schema.putObject("properties");
    for (int i = 0; i < n; i++) {
      props.putObject("p" + i).put("type", "string");
    }
    return schema;
  }
}
