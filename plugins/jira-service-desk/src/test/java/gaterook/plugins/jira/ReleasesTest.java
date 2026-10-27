package gaterook.plugins.jira;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.plugin.protocol.PluginManifest;
import ai.msrv.gaterook.plugin.protocol.WireCodec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The two releases as the gateway reads them (report 16 §8): both manifests pass the parser the core and the host
 * share, the code serves exactly the declared tools (the host's "tools ⊆ manifest" check by definition hash), and
 * 1.3.0 is 1.2.0 plus {@code jira_request_sla} — same hosts, same requirements, same connection form.
 */
class ReleasesTest {

  @Test
  void bothManifestsParseAndTheCodeServesExactlyTheirTools() throws Exception {
    for (String version : List.of("1.2.0", "1.3.0")) {
      JsonNode json = TestCalls.release(version);
      PluginManifest manifest = PluginManifest.parse(json);
      assertThat(manifest.id()).isEqualTo("jira-service-desk");
      assertThat(manifest.version()).isEqualTo(version);
      assertThat(manifest.connectorClass()).isEqualTo(JiraServiceDeskConnector.class.getName());

      Map<String, String> served = new LinkedHashMap<>();
      for (ToolDefinition t : new JiraServiceDeskConnector(json).manifest().tools()) {
        served.put(t.name(), WireCodec.definitionHash(t));
      }
      assertThat(served).as(version).isEqualTo(manifest.definitionHashes());
    }
  }

  @Test
  void release130AddsTheSlaToolAndNothingElse() throws Exception {
    PluginManifest v12 = PluginManifest.parse(TestCalls.release("1.2.0"));
    PluginManifest v13 = PluginManifest.parse(TestCalls.release("1.3.0"));

    assertThat(v12.tools()).extracting(ToolDefinition::name).containsExactly("jira_search_requests",
        "jira_get_request", "jira_list_queues", "jira_add_comment", "jira_transition_request");
    assertThat(v13.tools()).extracting(ToolDefinition::name).containsExactly("jira_search_requests",
        "jira_request_sla", "jira_get_request", "jira_list_queues", "jira_add_comment", "jira_transition_request");
    Map<String, String> before = v12.definitionHashes();
    Map<String, String> after = new LinkedHashMap<>(v13.definitionHashes());
    String sla = after.remove("jira_request_sla");
    assertThat(after).isEqualTo(before);
    assertThat(sla).isNotNull();
    ToolDefinition t = v13.tool("jira_request_sla").orElseThrow();
    assertThat(t.nature()).isEqualTo(ToolDefinition.Nature.READ);
    assertThat(t.annotations()).isEqualTo(new ToolDefinition.Annotations(true, false, true, false));

    // "No new hosts": access, requirements and the connection form are those of 1.2.0.
    assertThat(v13.access()).isEqualTo(v12.access());
    assertThat(v13.requires()).isEqualTo(v12.requires());
    assertThat(v13.configSchema()).isEqualTo(v12.configSchema());
    ObjectNode rest12 = v12.rawObject();
    ObjectNode rest13 = v13.rawObject();
    for (ObjectNode o : List.of(rest12, rest13)) {
      o.remove("version");
      o.remove("tools");
    }
    assertThat(rest13).isEqualTo(rest12);
  }

  @Test
  void mutatingToolsAreDeclaredAsSuchAndEveryToolStartsInApproval() throws Exception {
    PluginManifest v13 = PluginManifest.parse(TestCalls.release("1.3.0"));
    for (ToolDefinition t : v13.connectorManifest().tools()) {
      assertThat(t.defaultMode()).as(t.name()).isEqualTo(ToolDefinition.Mode.APPROVAL);
      assertThat(t.prodDefaultMode()).as(t.name()).isEqualTo(ToolDefinition.Mode.APPROVAL);
    }
    assertThat(v13.tool("jira_add_comment").orElseThrow().nature()).isEqualTo(ToolDefinition.Nature.MUTATION);
    assertThat(v13.tool("jira_transition_request").orElseThrow().annotations().destructiveHint()).isTrue();
    assertThat(v13.configSchema().path("properties").path("apiToken").path("writeOnly").asBoolean()).isTrue();
  }
}
