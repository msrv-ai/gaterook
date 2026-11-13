package com.gaterook.plugin.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gaterook.connector.api.ConnectorManifest;
import com.gaterook.connector.api.ToolDefinition;
import com.gaterook.connectors.fixturemodule.FixtureModule;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A connector module's manifest ({@code manifestVersion 2}): written from the module's own connectors, read back by
 * the parser the core and the host use, and refused where it claims what a module can't have.
 */
class ModuleManifestTest {

  static ObjectNode fixture() throws Exception {
    return ModuleManifests.fromClassPath(GppJson.MAPPER.readTree(FixtureModule.DESCRIPTOR));
  }

  static List<String> problems(ObjectNode manifest) {
    try {
      PluginManifest.parse(manifest);
      return List.of();
    } catch (PluginManifest.InvalidManifest e) {
      return e.problems();
    }
  }

  @Test
  void theManifestIsWrittenFromTheModulesConnectorsAndReadsBackAsTheirTypes() throws Exception {
    ObjectNode written = fixture();
    assertThat(written.path("manifestVersion").asInt()).isEqualTo(2);
    assertThat(written.has("typeCapabilities")).as("only the generator reads it").isFalse();

    PluginManifest m = PluginManifest.read(Canonical.bytes(written));
    assertThat(m.module()).isTrue();
    assertThat(m.id()).isEqualTo("fixture-module");
    assertThat(m.moduleClass()).isEqualTo(FixtureModule.class.getName());
    assertThat(m.connectorClass()).isNull();
    assertThat(m.maxHeapMb()).isEqualTo(128);
    assertThat(m.maxMetaspaceMb()).isEqualTo(96);
    assertThat(m.maxInFlight()).isEqualTo(4);
    assertThat(m.types()).extracting(PluginManifest.Type::id).containsExactly("alpha", "beta");

    PluginManifest.Type alpha = m.type("alpha").orElseThrow();
    assertThat(alpha.capabilities()).containsExactlyInAnyOrder("prepare", "describe", "supportsTool",
        "tools.endpoint", "quota", "masked");
    assertThat(alpha.maxConcurrency()).isEqualTo(2);
    assertThat(alpha.commandTools()).containsExactly("alpha_run");
    assertThat(alpha.templateKinds()).singleElement().satisfies(k -> {
      assertThat(k.kind()).isEqualTo("alpha-query");
      assertThat(k.connectionTypes()).containsExactly("alpha");
      assertThat(k.definitionSchema().path("required").toString()).isEqualTo("[\"name\",\"sql\"]");
    });
    PluginManifest.Type beta = m.type("beta").orElseThrow();
    assertThat(beta.capabilities()).isEmpty();
    assertThat(beta.maxConcurrency()).isEqualTo(4);
    assertThat(beta.templateKinds()).isEmpty();
  }

  @Test
  void aModulesTypesAreBuiltInConnectorsThatKeepTheirConnectionParameterAndTheirModes() throws Exception {
    PluginManifest m = PluginManifest.parse(fixture());
    List<ConnectorManifest> types = m.connectorManifests();
    assertThat(types).extracting(ConnectorManifest::id).containsExactly("alpha", "beta");
    assertThat(types).allSatisfy(t -> {
      assertThat(t.kind()).isEqualTo(ConnectorManifest.Kind.BUILT_IN);
      assertThat(t.version()).isEqualTo("1.0.0");
      assertThat(t.access().hosts()).containsExactly("{connection.host}:443");
    });
    ConnectorManifest alpha = types.getFirst();
    assertThat(alpha.policySchema()).isNotNull();
    assertThat(alpha.tools()).extracting(ToolDefinition::name).containsExactly("alpha_read", "alpha_run",
        "alpha_write");
    assertThat(alpha.tools()).allSatisfy(t -> assertThat(t.inputSchema().path("properties").has("connection"))
        .isTrue());
    ToolDefinition run = alpha.tools().get(1);
    assertThat(run.defaultMode()).isEqualTo(ToolDefinition.Mode.READ_ONLY);
    assertThat(run.prodDefaultMode()).isEqualTo(ToolDefinition.Mode.APPROVAL);
    assertThat(alpha.tools().getFirst().defaultMode()).isEqualTo(ToolDefinition.Mode.READ_ONLY);

    // What the manifest declares is what the connectors' own code reports: the load check compares these hashes.
    FixtureModule module = new FixtureModule();
    assertThat(m.definitionHashesByType().get("alpha")).isEqualTo(m.type("alpha").orElseThrow().definitionHashes());
    module.connectors(null).forEach(c -> {
      PluginManifest.Type declared = m.type(c.manifest().id()).orElseThrow();
      c.manifest().tools().forEach(t -> assertThat(declared.definitionHashes().get(t.name()))
          .isEqualTo(WireCodec.definitionHash(t)));
      // And the whole definition survives the file, modes and risk included.
      assertThat(Canonical.text(WireCodec.tools(declared.tools())))
          .isEqualTo(Canonical.text(WireCodec.tools(c.manifest().tools())));
    });
  }

  @Test
  void whatAModuleCannotHaveIsRefusedWithEveryProblem() throws Exception {
    ObjectNode bad = fixture();
    bad.put("moduleClass", "com.gaterook.plugin.protocol.Evil");
    bad.put("connectorClass", "x.Y");
    bad.put("toolNamespace", "alpha_");
    ObjectNode alpha = (ObjectNode) bad.path("types").get(0);
    ((ArrayNode) alpha.path("capabilities")).add("teleport");
    ((ArrayNode) alpha.putArray("commandTools")).add("alpha_fly");
    ObjectNode write = (ObjectNode) alpha.path("tools").get(2);
    write.put("defaultMode", "READ_ONLY");
    ObjectNode read = (ObjectNode) alpha.path("tools").get(0);
    read.put("prodDefaultMode", "SOMETIMES");
    ((ObjectNode) bad.path("types").get(1)).put("id", "alpha");
    ((ObjectNode) bad.path("resources")).put("maxMetaspaceMb", 32);

    assertThat(problems(bad)).contains(
        "moduleClass can't be in com.gaterook.plugin.protocol",
        "connectorClass belongs to a plugin (manifestVersion 1): a module declares it in types",
        "toolNamespace belongs to a plugin (manifestVersion 1): a module declares it in types",
        "type alpha: this GateRook doesn't know the capability teleport",
        "type alpha: commandTools names alpha_fly, which is not one of its tools",
        "type alpha: tool alpha_write: a mutating tool can't default to Read only",
        "type alpha: tool alpha_read: defaultMode and prodDefaultMode must be OFF, READ_ONLY, APPROVAL or BYPASS",
        "type alpha is declared twice",
        "resources.maxMetaspaceMb must be 64 to 512");
  }

  @Test
  void aModuleLivesInGateRooksConnectorPackagesButNotInTheOnesTheHostProvides() throws Exception {
    ObjectNode ok = fixture();
    ok.put("moduleClass", "com.gaterook.connectors.sql.module.OracleModule");
    assertThat(problems(ok)).isEmpty();
    for (String shared : List.of("com.gaterook.connector.api.X", "com.gaterook.pluginhost.X",
        "tools.jackson.databind.X", "org.pf4j.X", "java.lang.String")) {
      ObjectNode bad = fixture();
      bad.put("moduleClass", shared);
      assertThat(problems(bad)).as(shared).anyMatch(p -> p.startsWith("moduleClass can't be in"));
    }
    ObjectNode empty = fixture();
    empty.putArray("types");
    assertThat(problems(empty)).contains("types must list at least one connector type");
  }

  @Test
  void aPluginManifestMayStillNotDeclareAConnectionParameterOrItsOwnModes() throws Exception {
    ObjectNode plugin = (ObjectNode) GppJson.MAPPER.readTree(PluginManifestTest.JIRA);
    ObjectNode tool = (ObjectNode) plugin.path("tools").get(0);
    tool.put("defaultMode", "READ_ONLY");
    PluginManifest m = PluginManifest.parse(plugin);
    assertThat(m.module()).isFalse();
    assertThat(m.types()).singleElement().satisfies(t -> assertThat(t.id()).isEqualTo("jira-service-desk"));
    assertThat(m.connectorManifests()).singleElement().satisfies(c -> {
      assertThat(c.kind()).isEqualTo(ConnectorManifest.Kind.PLUGIN);
      assertThat(c.tools().getFirst().defaultMode()).as("a plugin's tools start in Approval whatever it says")
          .isEqualTo(ToolDefinition.Mode.APPROVAL);
    });
    ((ObjectNode) tool.path("inputSchema").path("properties")).putObject("connection").put("type", "string");
    assertThat(problems(plugin)).contains(
        "tool jira_request_sla: connection is added by GateRook, a tool doesn't declare it");
  }

  @Test
  void theSizeLimitFollowsTheKindOfPackage() throws Exception {
    byte[] module = Canonical.bytes(fixture());
    assertThat(PluginManifest.read(module).module()).isTrue();

    ObjectNode largePlugin = (ObjectNode) GppJson.MAPPER.readTree(PluginManifestTest.JIRA);
    largePlugin.put("padding", "x".repeat(PluginManifest.MAX_BYTES));
    assertThatThrownBy(() -> PluginManifest.read(Canonical.bytes(largePlugin)))
        .isInstanceOf(PluginManifest.InvalidManifest.class).hasMessageContaining("larger than 1 MB");

    ObjectNode largeModule = fixture();
    largeModule.put("padding", "x".repeat(PluginManifest.MAX_BYTES));
    assertThat(PluginManifest.read(Canonical.bytes(largeModule)).module()).as("a module may be larger").isTrue();
    largeModule.put("padding", "x".repeat(PluginManifest.MAX_MODULE_BYTES));
    assertThatThrownBy(() -> PluginManifest.read(Canonical.bytes(largeModule)))
        .isInstanceOf(PluginManifest.InvalidManifest.class).hasMessageContaining("larger than 4 MB");
    assertThatThrownBy(() -> PluginManifest.read("not json".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(PluginManifest.InvalidManifest.class).hasMessageContaining("is not JSON");
  }

  @Test
  void theGeneratorWritesTheSameBytesEveryTimeAndRefusesADescriptorThatDeclaresTypes(@TempDir Path dir)
      throws Exception {
    Path descriptor = dir.resolve("module.json");
    Files.writeString(descriptor, FixtureModule.DESCRIPTOR);
    Path first = dir.resolve("a/mcp-gateway-plugin.json");
    Path second = dir.resolve("b/mcp-gateway-plugin.json");
    ModuleManifests.main(new String[] {descriptor.toString(), first.toString()});
    ModuleManifests.main(new String[] {descriptor.toString(), second.toString()});
    assertThat(Files.readAllBytes(first)).isEqualTo(Files.readAllBytes(second));
    assertThat(PluginManifest.read(Files.readAllBytes(first)).types()).hasSize(2);

    JsonNode withTypes = GppJson.MAPPER.readTree(FixtureModule.DESCRIPTOR.replace("\"id\":", "\"types\":[],\"id\":"));
    assertThatThrownBy(() -> ModuleManifests.fromClassPath(withTypes)).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("declares neither manifestVersion nor types");
    JsonNode notAModule = GppJson.MAPPER.readTree(FixtureModule.DESCRIPTOR.replace(
        "com.gaterook.connectors.fixturemodule.FixtureModule", "java.lang.Object"));
    assertThatThrownBy(() -> ModuleManifests.fromClassPath(notAModule)).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("doesn't implement ConnectorModule");
  }
}
