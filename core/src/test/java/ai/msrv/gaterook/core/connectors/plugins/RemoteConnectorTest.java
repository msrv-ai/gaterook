package ai.msrv.gaterook.core.connectors.plugins;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.PolicyView;
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

  @Test
  void aPluginsRuleIdsStayInItsOwnNamespace() {
    PolicyView policy = new PolicyView(Env.DEV, "dev.jira-service-desk", GppJson.object(), null);

    // Its own rules, named with the policy view it was given, keep their ids.
    assertThat(RemoteConnector.fromPlugin(Classification.deny(policy.rule("sd.closed"), "Closed", null), policy)
        .ruleId()).isEqualTo("dev.jira-service-desk.sd.closed");
    // A rule of the core or of another connector is put inside the plugin's namespace: it can't pass for that rule.
    for (String foreign : List.of("core.integrity", "prod.sql.block_ddl", "dev.endpoint.read-only")) {
      assertThat(RemoteConnector.fromPlugin(Classification.deny(foreign, "Denied", null), policy).ruleId())
          .as(foreign).isEqualTo("dev.jira-service-desk." + foreign);
    }
    // What isn't a plain rule name becomes one name for all of them.
    for (String odd : List.of("", "Core Integrity", "core.integrity‮", "a..b", ".a", "x".repeat(65),
        "dev.jira-service-desk.")) {
      assertThat(RemoteConnector.fromPlugin(Classification.deny(odd, "Denied", null), policy).ruleId())
          .as(odd).isEqualTo("dev.jira-service-desk." + RemoteConnector.UNNAMED_RULE);
    }
  }

  @Test
  void whatAPluginSaysInAClassificationIsShownAndClipped() {
    PolicyView policy = new PolicyView(Env.DEV, "dev.jira-service-desk", GppJson.object(), null);

    Classification change = RemoteConnector.fromPlugin(Classification.mutation("Changes ‮data"), policy);
    Classification denied = RemoteConnector.fromPlugin(Classification.deny("dev.jira-service-desk.sd.closed",
        "Closed " + "y".repeat(4_000), "Reopen it first\u0007"), policy);

    assertThat(change.kind()).isEqualTo(Classification.Kind.MUTATION);
    assertThat(change.ruleId()).isNull();
    assertThat(change.reason()).isEqualTo("Changes \\u202Edata");
    assertThat(denied.kind()).isEqualTo(Classification.Kind.DENY);
    assertThat(denied.reason()).startsWith("Closed yyy").endsWith("…").hasSize(RemoteConnector.MAX_PLUGIN_TEXT);
    assertThat(denied.hint()).startsWith("Reopen it first").doesNotContain("\u0007");
  }

  private static RemoteConnector connector(boolean signed) {
    PluginSupervisor supervisor = new PluginSupervisor("jira-service-desk", null, Clock.systemUTC());
    return new RemoteConnector(MANIFEST, supervisor, new SecretCallbackRegistry(), null, null, signed);
  }

  @Test
  void anIpv6HostIsWrittenAsInAUrlForTheEgressGrant() {
    assertThat(RemoteConnector.connectionHost(config("{\"host\":\"::1\"}"))).isEqualTo("[::1]");
    assertThat(RemoteConnector.connectionHost(config("{\"host\":\"[fd00::5]\"}"))).isEqualTo("[fd00::5]");
    assertThat(RemoteConnector.connectionHost(config("{\"host\":\" DB.Example \"}"))).isEqualTo("db.example");
    assertThat(RemoteConnector.connectionHost(config("{\"baseUrl\":\"http://[fd00::5]:8123/\"}")))
        .isEqualTo("[fd00::5]");
  }

  @Test
  void tcpDestinationsTakeTheConnectionsHostAndPortOrTheSchemasDefaultPort() {
    tools.jackson.databind.JsonNode schema = GppJson.MAPPER.readTree(
        "{\"type\":\"object\",\"properties\":{\"port\":{\"type\":\"integer\",\"default\":50000}}}");
    List<String> entries = List.of("{connection.host}:{connection.port}", "{connection.host}:9092", "db.acme.com:443");
    ConnectionConfig withPort = config("{\"host\":\"db2.acme.internal\",\"port\":50001}");
    assertThat(RemoteConnector.resolved(entries, RemoteConnector.connectionHost(withPort),
        RemoteConnector.connectionPort(withPort, schema)))
        .containsExactly("db2.acme.internal:50001", "db2.acme.internal:9092", "db.acme.com:443");
    ConnectionConfig defaultPort = config("{\"host\":\"::1\"}");
    assertThat(RemoteConnector.resolved(entries, RemoteConnector.connectionHost(defaultPort),
        RemoteConnector.connectionPort(defaultPort, schema))).containsExactly("[::1]:50000", "[::1]:9092",
        "db.acme.com:443");
    // Without a host or a port to fill in, the entries that name them grant nothing.
    ConnectionConfig nothing = config("{}");
    assertThat(RemoteConnector.resolved(entries, RemoteConnector.connectionHost(nothing),
        RemoteConnector.connectionPort(nothing, null))).containsExactly("db.acme.com:443");
    assertThat(RemoteConnector.connectionPort(config("{\"port\":70000}"), schema)).isNull();
  }

  private static ConnectionConfig config(String json) {
    return new ConnectionConfig("c_2", "ch", "clickhouse", Env.DEV, 1, (ObjectNode) GppJson.MAPPER.readTree(json),
        Set.of(), null);
  }
}
