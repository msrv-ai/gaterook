package com.gaterook.core.endpoints;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorManifest;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.Limits;
import com.gaterook.connector.api.PolicyView;
import com.gaterook.connector.api.ToolDefinition;
import com.gaterook.connector.api.ToolDefinition.Mode;
import com.gaterook.core.policy.PolicyEngine;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** What {@code find_tools} finds (ARCHITECTURE.md §5.7) in a catalog shaped like a real endpoint's. */
class ToolSearchTest {

  private static final ToolCatalog.Catalog CATALOG = catalog();

  @Test
  void findsAToolByWhatItDoesInOtherWordsThanItsOwn() {
    assertThat(first("jira ticket")).startsWith("atlassian_").contains("jira");
    assertThat(first("find jira issues")).isEqualTo("atlassian_searchjiraissuesusingjql");
    assertThat(first("candidate profile")).isEqualTo("pb_getprofile");
    assertThat(first("update a confluence page")).isEqualTo("atlassian_updateconfluencecontent");
    assertThat(first("sentry errors")).isEqualTo("sentry_search_errors");
    assertThat(first("delete rows")).isEqualTo("sql_execute");
    assertThat(first("slow sql queries")).isEqualTo("sql_query");
  }

  @Test
  void aToolsNameOrTitleComesFirst() {
    assertThat(first("getprofile")).as("an MCP server's lowercased name").isEqualTo("pb_getprofile");
    assertThat(first("pb_listproject")).isEqualTo("pb_listproject");
    assertThat(first("Get Jira issue")).isEqualTo("atlassian_getjiraissue");
    assertThat(first("jiraissue")).as("a word inside a name").startsWith("atlassian_").contains("jiraissue");
  }

  @Test
  void aConnectionNarrowsTheMatchesAndTheLimitCutsThem() {
    ToolSearch.Result sentry = CATALOG.search().search("issue", "sentry-mcp", 10);
    assertThat(sentry.hits()).isNotEmpty().allMatch(h -> h.tool().name().startsWith("sentry_"));
    ToolSearch.Result one = CATALOG.search().search("jira", null, 1);
    assertThat(one.hits()).hasSize(1);
    assertThat(one.total()).isEqualTo(3);
    assertThat(CATALOG.search().search("the", null, 5).total()).as("only a common word").isZero();
    assertThat(CATALOG.search().search("quantum entanglement", null, 5).hits()).isEmpty();
  }

  @Test
  void theGatewaysOwnToolsAreNotSearched() {
    assertThat(CATALOG.search().size()).isEqualTo(10);
    assertThat(CATALOG.search().search("approval status", null, 20).hits())
        .noneMatch(h -> h.tool().core());
  }

  @Test
  void wordsMeetWhateverTheirForm() {
    assertThat(List.of("update", "updates", "updated", "updating").stream().map(ToolSearch::stem).distinct())
        .containsExactly("updat");
    assertThat(ToolSearch.stem("queries")).isEqualTo("query");
    assertThat(ToolSearch.stem("searches")).isEqualTo("search");
    assertThat(ToolSearch.stem("status")).isEqualTo("status");
    assertThat(ToolSearch.stem("running")).isEqualTo("run");
    assertThat(ToolSearch.stem("filled")).isEqualTo("fill");
    assertThat(ToolSearch.terms("searchJiraIssuesUsingJQL")).containsExactly("search", "jira", "issu", "jql");
  }

  @Test
  void onlyToolsThatNeverChangeDataAreReadOnly() {
    assertThat(ToolSearch.readOnly(CATALOG.tool("sql_query").orElseThrow())).isTrue();
    assertThat(ToolSearch.readOnly(CATALOG.tool("sql_execute").orElseThrow())).isFalse();
    assertThat(ToolSearch.readOnly(CATALOG.tool("atlassian_createjiraissue").orElseThrow())).isFalse();
    assertThat(ToolSearch.readOnly(CATALOG.tool("approval_status").orElseThrow())).as("the gateway's own").isFalse();
  }

  private static String first(String query) {
    ToolSearch.Result r = CATALOG.search().search(query, null, 5);
    assertThat(r.hits()).as(query).isNotEmpty();
    return r.hits().get(0).tool().name();
  }

  private static ToolCatalog.Catalog catalog() {
    Builder b = new Builder();
    b.mcp("atlassian-mcp", "atlassian_searchjiraissuesusingjql", "Search Jira issues using JQL",
        "Search Jira and JSM issues with a JQL query. Use getJiraIssue for one known issue.", true, "jql", "fields");
    b.mcp("atlassian-mcp", "atlassian_getjiraissue", "Get Jira issue",
        "Get a Jira or JSM issue by key or ID.", true, "issueIdOrKey");
    b.mcp("atlassian-mcp", "atlassian_createjiraissue", "Create Jira issue",
        "Create a new Jira issue in a project.", false, "projectKey", "summary");
    b.mcp("atlassian-mcp", "atlassian_updateconfluencecontent", "Update Confluence content",
        "Update the body or title of an existing Confluence page.", false, "pageId", "body");
    b.mcp("sentry-mcp", "sentry_search_errors", "Search Errors",
        "Search for error events in Sentry with a natural language query.", true, "naturalLanguageQuery");
    b.mcp("sentry-mcp", "sentry_get_issue_details", "Get Issue Details",
        "Get the details of a Sentry issue: its latest event and stacktrace.", true, "issueId");
    b.mcp("pb-mcp", "pb_getprofile", "getProfile",
        "Fetch a single candidate's master profile by id.", true, "profileId");
    b.mcp("pb-mcp", "pb_listproject", "listProject",
        "Search projects by name or list the user's active projects.", true, "name");
    b.builtIn("pb-mysql", "sql_query", "Run a read-only SQL query",
        "Run one validated read query and roll back its transaction.", true, "sql");
    b.builtIn("pb-mysql", "sql_execute", "Change data (needs approval)",
        "Runs INSERT, UPDATE or DELETE statements after approval.", false, "sql");
    return new ToolCatalog(b.engine).forEndpoint(b.snapshot());
  }

  private static final class Builder {
    final PolicyEngine engine = mock(PolicyEngine.class);
    final List<EndpointSnapshot.Wired> connections = new ArrayList<>();
    final List<EndpointSnapshot.Pair> pairs = new ArrayList<>();

    void mcp(String connection, String name, String title, String description, boolean read, String... args) {
      add(connection, ConnectorManifest.Kind.MCP_SERVER, tool(name, title, description, read, false, args));
    }

    void builtIn(String connection, String name, String title, String description, boolean read, String... args) {
      add(connection, ConnectorManifest.Kind.BUILT_IN, tool(name, title, description, read, true, args));
    }

    private static ToolDefinition tool(String name, String title, String description, boolean read,
        boolean takesConnection, String... args) {
      ObjectNode input = JsonNodeFactory.instance.objectNode().put("type", "object");
      ObjectNode properties = input.putObject("properties");
      if (takesConnection) {
        properties.putObject("connection").put("type", "string");
      }
      for (String a : args) {
        properties.putObject(a).put("type", "string");
      }
      return ToolDefinition.builder(name).title(title).description(description).inputSchema(input)
          .nature(read ? ToolDefinition.Nature.READ : ToolDefinition.Nature.MUTATION)
          .defaultMode(read ? Mode.READ_ONLY : Mode.APPROVAL, read ? Mode.READ_ONLY : Mode.APPROVAL)
          .annotations(read ? ToolDefinition.Annotations.read() : ToolDefinition.Annotations.write(false)).build();
    }

    private void add(String connection, ConnectorManifest.Kind kind, ToolDefinition tool) {
      ConnectorManifest manifest = ConnectorManifest.builder(connection + "-type", "1.0.0", kind).name(connection)
          .build();
      ConnectionConfig config = connections.stream().map(EndpointSnapshot.Wired::config)
          .filter(c -> c.name().equals(connection)).findFirst().orElseGet(() -> {
            ConnectionConfig c = new ConnectionConfig(connection, connection, connection + "-type", Env.DEV, 1,
                JsonNodeFactory.instance.objectNode(), Set.of(), null);
            connections.add(new EndpointSnapshot.Wired(c, manifest, null));
            when(engine.view(null, c)).thenReturn(new PolicyView(Env.DEV, "dev." + connection,
                JsonNodeFactory.instance.objectNode(), new Limits(1000, 1000, 524_288, 1_048_576, 262_144, 65_536,
                    8192, Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(10), null)));
            return c;
          });
      Mode mode = tool.mutating() ? Mode.APPROVAL : Mode.READ_ONLY;
      pairs.add(new EndpointSnapshot.Pair(connection + "/" + tool.name(), 1, tool.name(), tool, manifest, config,
          mode, mode, null, null, true, null, null, null, List.of(), null));
    }

    EndpointSnapshot snapshot() {
      return new EndpointSnapshot("ep", "dev", "", Env.DEV, false, true, "", null, false, false, null, 1,
          List.copyOf(connections), List.copyOf(pairs), List.of(), null);
    }
  }
}
