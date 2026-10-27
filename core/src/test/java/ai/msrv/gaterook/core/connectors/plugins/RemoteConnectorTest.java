package ai.msrv.gaterook.core.connectors.plugins;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.plugin.protocol.GppJson;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * The exact action of a plugin's call (ARCHITECTURE.md §3 "RemoteConnector", §9 "Approve the exact action", review
 * 2026-10-27): the approval showed the plugin's own {@code describeAction} as the action, so a plugin could describe
 * "an internal comment on SD-123" and do anything with the arguments it was sent. The core now builds the main block
 * from the call's arguments; the plugin's words come second, labelled as not verified, with hidden characters shown.
 */
class RemoteConnectorTest {

  private static final ConnectorManifest MANIFEST = ConnectorManifest.builder("jira-service-desk", "1.3.0",
      ConnectorManifest.Kind.PLUGIN).name("Jira Service Desk").build();
  private static final ConnectionConfig ACME = new ConnectionConfig("c_1", "acme-jsm", "jira-service-desk", Env.DEV,
      1, GppJson.object(), Set.of("/apiToken"), null);

  @Test
  void theMainBlockIsTheCallsArgumentsAndThePluginsWordsComeSecond() {
    ObjectNode args = GppJson.object().put("issueKey", "SD-123").put("body", "Restarted the worker.");
    ToolCall call = new ToolCall("tc_1", "jira_add_comment", "acme-jsm", args, null);
    ActionPreview said = ActionPreview.of(ActionPreview.Kind.HTTP, "http",
        "GET https://acme.atlassian.net/rest/servicedeskapi/info \u202E", "acme-jsm · somewhere else",
        "Only reads the server's version").withWarnings(List.of("Nothing to worry about"))
        .withFields(List.of(new ActionPreview.Field("Risk", "none")));

    ActionPreview exact = connector(false).exactAction(call, ACME, said);

    assertThat(exact.kind()).isEqualTo(ActionPreview.Kind.JSON);
    assertThat(exact.text()).isEqualTo("{\"body\":\"Restarted the worker.\",\"issueKey\":\"SD-123\"}");
    assertThat(exact.target()).isEqualTo("acme-jsm");
    assertThat(exact.summary()).isEqualTo("jira_add_comment on acme-jsm");
    assertThat(exact.fields()).hasSize(1);
    ActionPreview.Field described = exact.fields().getFirst();
    assertThat(described.label()).isEqualTo("Described by jira-service-desk 1.3.0 (unsigned), not verified");
    assertThat(described.value()).contains("Only reads the server's version", "GET https://acme.atlassian.net")
        .contains("\\u202E").doesNotContain("\u202E");
    assertThat(exact.warnings()).containsExactly("Nothing to worry about");
    assertThat(exact.payload()).as("the stored call's arguments run, not a payload of the plugin").isNull();
  }

  @Test
  void withoutThePluginsWordsTheArgumentsStandAlone() {
    ToolCall call = new ToolCall("tc_1", "jira_transition_request", "acme-jsm",
        GppJson.object().put("issueKey", "SD-9").put("transition", "Resolve"), null);
    ActionPreview exact = connector(true).exactAction(call, ACME, null);
    assertThat(exact.text()).isEqualTo("{\"issueKey\":\"SD-9\",\"transition\":\"Resolve\"}");
    assertThat(exact.fields()).isEmpty();
  }

  @Test
  void aSignedPluginIsNamedWithoutTheUnsignedMark() {
    ToolCall call = new ToolCall("tc_1", "jira_add_comment", "acme-jsm", GppJson.object(), null);
    ActionPreview exact = connector(true).exactAction(call, ACME, ActionPreview.of(ActionPreview.Kind.TEXT, "text",
        "comment", "acme-jsm", null));
    assertThat(exact.fields().getFirst().label()).isEqualTo("Described by jira-service-desk 1.3.0, not verified");
    assertThat(exact.text()).isEqualTo("{}");
  }

  private static RemoteConnector connector(boolean signed) {
    PluginSupervisor supervisor = new PluginSupervisor("jira-service-desk", null, Clock.systemUTC());
    return new RemoteConnector(MANIFEST, supervisor, new SecretCallbackRegistry(), null, null, signed);
  }
}
