package ai.msrv.gaterook.core.secrets.store;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.core.secrets.internal.persistence.StoreKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The form of every store kind: a resource of its own, named and titled as the kind, whose secret fields are exactly
 * the store's own sign-in secrets, and registered for the native image.
 */
class StoreSchemasTest {

  private final StoreSchemas schemas = new StoreSchemas(JsonMapper.builder().build());

  @ParameterizedTest
  @EnumSource(StoreKind.class)
  void everyKindHasAFormWhoseSecretFieldsAreTheStoresSignInSecrets(StoreKind kind) {
    JsonNode schema = schemas.schema(kind);
    assertThat(schema.path("$id").asString()).isEqualTo("mcp-gateway:store/" + StoreSchemas.id(kind));
    assertThat(schema.path("title").asString()).isEqualTo(StoreResolution.title(kind));
    List<String> secret = new ArrayList<>();
    secretFields(schema, "", secret);
    assertThat(secret).containsExactlyInAnyOrderElementsOf(StoreSchemas.secretFields(kind));
    assertThat(StoreSchemas.kind(StoreSchemas.id(kind))).isEqualTo(kind);
  }

  @Test
  void theFormsAreResourcesOfTheNativeImage() {
    RuntimeHints hints = new RuntimeHints();
    new StoreConfiguration.Hints().registerHints(hints, getClass().getClassLoader());
    for (StoreKind kind : StoreKind.values()) {
      assertThat(RuntimeHintsPredicates.resource().forResource("ai/msrv/gaterook/core/secrets/store/"
          + StoreSchemas.id(kind) + ".schema.json")).as(kind.name()).accepts(hints);
    }
  }

  /** The pointers of the properties marked {@code x-gateway-secret}, through properties and subschemas. */
  private static void secretFields(JsonNode node, String pointer, List<String> out) {
    if (node.isArray()) {
      node.forEach(n -> secretFields(n, pointer, out));
      return;
    }
    if (!node.isObject()) {
      return;
    }
    if (node.has("x-gateway-secret")) {
      out.add(pointer);
    }
    for (Map.Entry<String, JsonNode> e : node.properties()) {
      if (e.getKey().equals("properties")) {
        for (Map.Entry<String, JsonNode> p : e.getValue().properties()) {
          secretFields(p.getValue(), pointer + "/" + p.getKey(), out);
        }
      } else if (e.getValue().isContainer()) {
        secretFields(e.getValue(), pointer, out);
      }
    }
  }
}
