package ai.msrv.gaterook.core.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

class ClientMemoryTest {

  private final ClientMemory memory = new ClientMemory();

  @Test
  void initializationInfoAndCapabilitiesAreDeepCopiedBeforeTheyAreRemembered() {
    ObjectNode info = info("card-client");
    info.putObject("extra").put("label", "original");
    ObjectNode capabilities = uiCapabilities();
    memory.remember("principal-one", info, capabilities);
    info.put("name", "changed");
    ((ObjectNode) info.path("extra")).put("label", "changed");
    ((ObjectNode) capabilities.path("extensions")).removeAll();

    assertThat(memory.recall("principal-one").path("name").asString()).isEqualTo("card-client");
    assertThat(memory.recall("principal-one").at("/extra/label").asString()).isEqualTo("original");
    assertThat(memory.capabilities("principal-one")).isEqualTo(uiCapabilities());
  }

  @Test
  void aLaterInitializationWithoutUiClearsPreviouslyNegotiatedCapabilities() {
    memory.remember("principal-one", info("card-client"), uiCapabilities());
    memory.remember("principal-one", info("plain-client"), JsonNodeFactory.instance.objectNode());
    assertThat(memory.recall("principal-one").path("name").asString()).isEqualTo("plain-client");
    assertThat(memory.capabilities("principal-one").size()).isZero();

    memory.remember("principal-one", info("card-client"), uiCapabilities());
    memory.remember("principal-one", info("legacy-client"));
    assertThat(memory.recall("principal-one").path("name").asString()).isEqualTo("legacy-client");
    assertThat(memory.capabilities("principal-one")).isNull();
  }

  @Test
  void nonObjectInitializationDataCannotRetainPriorClientInfoOrCapabilities() {
    memory.remember("principal-one", info("card-client"), uiCapabilities());
    memory.remember("principal-one", JsonNodeFactory.instance.textNode("invalid"),
        JsonNodeFactory.instance.arrayNode());
    assertThat(memory.recall("principal-one")).isNull();
    assertThat(memory.capabilities("principal-one")).isNull();

    memory.remember("principal-one", info("card-client"), uiCapabilities());
    memory.remember("principal-one", null, null);
    assertThat(memory.recall("principal-one")).isNull();
    assertThat(memory.capabilities("principal-one")).isNull();
  }

  @Test
  void capabilitiesAreScopedToTheAuthenticatedPrincipal() {
    memory.remember("principal-one", info("card-client"), uiCapabilities());
    memory.remember("principal-two", info("plain-client"), JsonNodeFactory.instance.objectNode());
    assertThat(memory.capabilities("principal-one")).isEqualTo(uiCapabilities());
    assertThat(memory.capabilities("principal-two").size()).isZero();
    assertThat(memory.recall("principal-one").path("name").asString()).isEqualTo("card-client");
    assertThat(memory.recall("principal-two").path("name").asString()).isEqualTo("plain-client");
    assertThat(memory.recall("unknown-principal")).isNull();
    assertThat(memory.capabilities("unknown-principal")).isNull();
  }

  @Test
  void theBoundedCacheEvictsClientInfoAndCapabilitiesTogether() {
    for (int i = 0; i < 1_024; i++) {
      memory.remember("principal-" + i, info("client-" + i), uiCapabilities());
    }
    assertThat(memory.recall("principal-0")).isNotNull();
    assertThat(memory.capabilities("principal-1")).isNotNull();
    memory.remember("principal-new", info("new-client"), uiCapabilities());

    assertThat(memory.recall("principal-2")).isNull();
    assertThat(memory.capabilities("principal-2")).isNull();
    assertThat(memory.recall("principal-0")).isNotNull();
    assertThat(memory.capabilities("principal-1")).isNotNull();
    assertThat(memory.recall("principal-new").path("name").asString()).isEqualTo("new-client");
    assertThat(memory.capabilities("principal-new")).isEqualTo(uiCapabilities());
  }

  private static ObjectNode info(String name) {
    return JsonNodeFactory.instance.objectNode().put("name", name).put("version", "1.0");
  }

  private static ObjectNode uiCapabilities() {
    ObjectNode capabilities = JsonNodeFactory.instance.objectNode();
    capabilities.putObject("extensions").putObject("io.modelcontextprotocol/ui").putArray("mimeTypes")
        .add("text/html;profile=mcp-app");
    return capabilities;
  }
}
