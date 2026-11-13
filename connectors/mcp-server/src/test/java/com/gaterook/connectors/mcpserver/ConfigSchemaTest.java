package com.gaterook.connectors.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The {@code mcp-server} config schema as the core checks a save against it: the root refuses unknown members
 * ({@code additionalProperties: false}), so every member a transport's branch declares has to be allowed at the root
 * too ({@code patternProperties}), or the core refuses what the connection form sends for that transport.
 */
class ConfigSchemaTest {

  private static final JsonNode SCHEMA = McpServerConnector.typeManifest().configSchema();
  private static final Schema COMPILED = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
      .getSchema(SchemaLocation.of("urn:gaterook-test:mcp-server-config"), SCHEMA);

  private static List<String> errors(String config) {
    return COMPILED.validate(Fixtures.object(config)).stream().map(Error::getMessage).toList();
  }

  @Test
  void everyMemberOfATransportBranchIsAllowedAtTheRoot() {
    List<Pattern> allowed = new ArrayList<>();
    SCHEMA.path("patternProperties").propertyNames().forEach(p -> allowed.add(Pattern.compile(p)));
    List<String> refused = new ArrayList<>();
    for (JsonNode condition : SCHEMA.path("allOf")) {
      for (String branch : List.of("then", "else")) {
        for (String member : condition.path(branch).path("properties").propertyNames()) {
          if (!SCHEMA.path("properties").has(member) && allowed.stream().noneMatch(p -> p.matcher(member).find())) {
            refused.add(member);
          }
        }
      }
    }
    assertThat(refused).isEmpty();
  }

  @Test
  void aStdioServerAsTheConnectionFormSendsItIsValid() {
    // A stored secret stands in for its value, as in the core's check of a save.
    assertThat(errors("""
        {"transport":"stdio","command":["node","node_modules/@example/server/dist/index.js"],
         "env":[{"name":"EXAMPLE_TOKEN","secretRef":"‹secret›"}],"inheritEnv":["HTTPS_PROXY"],"offThisComputer":false,
         "start":"on-first-call","idleStopMinutes":15,"startTimeoutSeconds":30,"callTimeoutSeconds":60,
         "maxInFlight":4,"toolPrefix":"example_","share":{"resources":false,"prompts":false}}""")).isEmpty();
    assertThat(errors("{\"transport\":\"stdio\",\"command\":[\"node\"],\"offThisComputer\":true}")).isEmpty();
  }

  @Test
  void anHttpServerIsValidAndUnknownOrMistypedMembersAreStillRefused() {
    assertThat(errors("{\"transport\":\"http\",\"url\":\"https://mcp.example.com/mcp\"}")).isEmpty();
    assertThat(errors("{\"transport\":\"stdio\",\"command\":[\"node\"],\"surprise\":true}")).isNotEmpty();
    assertThat(errors("{\"transport\":\"stdio\",\"command\":[\"node\"],\"offThisComputer\":\"yes\"}")).isNotEmpty();
  }
}
