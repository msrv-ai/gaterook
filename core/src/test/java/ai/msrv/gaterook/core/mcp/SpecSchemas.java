package ai.msrv.gaterook.core.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.core.platform.SchemaValidator;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Golden checks of the gateway's answers against the specification's own JSON Schemas (report 01 §8.2): the
 * {@code $defs} of {@code schema/<revision>/schema.json}, validated with the same networknt validator the core uses.
 */
final class SpecSchemas {

  private static final SchemaValidator VALIDATOR = new SchemaValidator();
  private static final JsonNode LEGACY = load("2025-11-25");
  private static final JsonNode MODERN = load("2026-07-28");

  private SpecSchemas() {}

  private static JsonNode load(String revision) {
    try (InputStream in = SpecSchemas.class.getResourceAsStream("/mcp-schema/" + revision + "/schema.json")) {
      return McpClient.JSON.readTree(in);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The problems of {@code instance} against {@code $defs/<type>} of {@code revision}. */
  static List<SchemaValidator.Violation> violations(String revision, String type, JsonNode instance) {
    JsonNode doc = revision.equals("2026-07-28") ? MODERN : LEGACY;
    if (!doc.path("$defs").has(type)) {
      throw new IllegalArgumentException(revision + " has no $defs/" + type);
    }
    ObjectNode wrapper = McpClient.JSON.createObjectNode();
    wrapper.put("$schema", "https://json-schema.org/draft/2020-12/schema");
    wrapper.set("$defs", doc.path("$defs"));
    wrapper.put("$ref", "#/$defs/" + type);
    return VALIDATOR.validate(wrapper, instance);
  }

  static void assertValid(String revision, String type, JsonNode instance) {
    assertThat(violations(revision, type, instance)).as(type + " (" + revision + "): " + instance).isEmpty();
  }

  /** {@code structuredContent} against the tool's published {@code outputSchema}. */
  static void assertMatchesOutputSchema(JsonNode outputSchema, JsonNode structuredContent) {
    assertThat(VALIDATOR.validate(outputSchema, structuredContent)).as("structuredContent vs outputSchema")
        .isEmpty();
  }
}
