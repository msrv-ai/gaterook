package com.gaterook.connectors.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gaterook.connector.api.ActionPreview;
import com.gaterook.connector.api.Classification;
import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.Connector;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.ToolDefinition;
import com.gaterook.connector.api.ToolDefinition.Mode;
import com.gaterook.connector.api.ToolDefinition.Nature;
import com.gaterook.connectors.support.mcp.McpServerRuntime;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * How GateRook's verified profiles of vendors' own servers (the bundled {@code verified-profiles.json}) shape a
 * server's tools: a tool the profile names is a mutation however it is annotated, starts in Approval or Off, and never
 * runs without a person; tools it doesn't name, and other servers' tools, follow their annotations. Nothing here
 * connects or starts: the connector is built from a snapshot without a runtime, which a call refused for want of an
 * approval never reaches.
 */
class VerifiedProfileToolsTest {

  /** A tool as a server lists it, declaring {@code readOnlyHint} as given ({@code null}: no annotations). */
  private static ObjectNode tool(String name, Boolean readOnly) {
    ObjectNode t = Fixtures.object("{\"name\":\"" + name + "\",\"description\":\"" + name + " of the server.\","
        + "\"inputSchema\":{\"type\":\"object\",\"properties\":{\"code\":{\"type\":\"string\"}}}}");
    if (readOnly != null) {
      t.putObject("annotations").put("readOnlyHint", readOnly).put("destructiveHint", false);
    }
    return t;
  }

  private static Connector connector(String url, ObjectNode... tools) {
    Snapshots.Mapped mapped = Snapshots.fromJson("vp_", List.of(tools), n -> Optional.empty());
    return new ServerConnector(null, cfg(url), new McpServerRuntime.Snapshot("vp_", "0.1.0", "2025-11-25",
        mapped.tools(), null), McpServerConnector.typeManifest());
  }

  private static ConnectionConfig cfg(String url) {
    return Fixtures.cfg("cn_vp", "vp-mcp", Env.DEV, 1,
        Fixtures.object("{\"transport\":\"http\",\"url\":\"" + url + "\",\"toolPrefix\":\"vp_\"}"));
  }

  /** The published tool of the one the server lists as {@code original} (these tools have no title of their own). */
  private static ToolDefinition definition(Connector c, String original) {
    return c.manifest().tools().stream().filter(t -> t.title().equals(original)).findFirst().orElseThrow();
  }

  @Test
  void cloudflaresExecuteIsAMutationInApprovalEvenWhenDeclaredReadOnly() throws Exception {
    // The real execute declares readOnlyHint false; a server declaring it read-only still doesn't make it a read.
    Connector c = connector("https://mcp.cloudflare.com/mcp", tool("execute", true), tool("whoami", true),
        tool("search", false));

    ToolDefinition execute = definition(c, "execute");
    assertThat(execute.nature()).isEqualTo(Nature.MUTATION);
    assertThat(execute.defaultMode()).isEqualTo(Mode.APPROVAL);
    assertThat(execute.prodDefaultMode()).isEqualTo(Mode.APPROVAL);
    assertThat(execute.readOnlyModeAllowed()).isFalse();
    assertThat(execute.annotations().readOnlyHint()).isFalse();
    assertThat(execute.annotations().destructiveHint()).isTrue();
    assertThat(execute.outputSchema()).isNull();
    assertThat(definition(c, "whoami").nature()).isEqualTo(Nature.READ);
    assertThat(definition(c, "whoami").defaultMode()).isEqualTo(Mode.READ_ONLY);
    assertThat(definition(c, "search").defaultMode()).isEqualTo(Mode.APPROVAL);

    ConnectionConfig cfg = cfg("https://mcp.cloudflare.com/mcp");
    Classification run = c.classify(Fixtures.call("vp_execute", "{\"code\":\"1\"}"), cfg, Fixtures.policy());
    assertThat(run.kind()).isEqualTo(Classification.Kind.MUTATION);
    assertThat(run.reason()).contains("runs JavaScript").contains("GateRook's verified profile of Cloudflare API");
    assertThat(c.classify(Fixtures.call("vp_whoami", "{}"), cfg, Fixtures.policy()).kind())
        .isEqualTo(Classification.Kind.READ);

    ActionPreview preview = c.describeAction(Fixtures.call("vp_execute", "{\"code\":\"1\"}"), cfg);
    assertThat(preview.warnings()).anySatisfy(w -> assertThat(w).contains("keeps it out of Read only"));

    // It never runs without a person, before anything connects.
    assertThatThrownBy(() -> c.execute(Fixtures.call("vp_execute", "{\"code\":\"1\"}"), cfg, new Fixtures.Secrets(),
        Fixtures.Context.of(Duration.ofSeconds(5)))).isInstanceOf(ConnectorException.class)
        .hasMessageContaining("only after a person approved it");
  }

  @Test
  void aDelegatingToolAndOneThatReturnsCredentialsWaitForApproval() {
    Connector harness = connector("https://harness-runtime.mcp.digitalocean.com/mcp",
        tool("harness-runtime-send-input", false), tool("harness-runtime-list-sessions", true));
    Connector doks = connector("https://doks.mcp.digitalocean.com/mcp", tool("doks-get-kubeconfig", true),
        tool("doks-list-clusters", true));

    ToolDefinition sendInput = definition(harness, "harness-runtime-send-input");
    assertThat(sendInput.defaultMode()).isEqualTo(Mode.APPROVAL);
    assertThat(sendInput.prodDefaultMode()).isEqualTo(Mode.APPROVAL);
    assertThat(sendInput.nature()).isEqualTo(Nature.MUTATION);
    assertThat(definition(harness, "harness-runtime-list-sessions").defaultMode()).isEqualTo(Mode.READ_ONLY);
    ToolDefinition kubeconfig = definition(doks, "doks-get-kubeconfig");
    assertThat(kubeconfig.defaultMode()).isEqualTo(Mode.APPROVAL);
    assertThat(kubeconfig.readOnlyModeAllowed()).isFalse();
    assertThat(definition(doks, "doks-list-clusters").defaultMode()).isEqualTo(Mode.READ_ONLY);
  }

  @Test
  void anotherServerKeepsTheRulesOfItsAnnotations() {
    Connector lookalike = connector("https://mcp.cloudflare.com.example.net/mcp", tool("execute", true));
    Connector vercelDocs = connector("https://mcp.vercel.com/docs/mcp", tool("buy_domain", true));

    assertThat(definition(lookalike, "execute").nature()).isEqualTo(Nature.READ);
    assertThat(definition(lookalike, "execute").defaultMode()).isEqualTo(Mode.READ_ONLY);
    assertThat(definition(vercelDocs, "buy_domain").defaultMode()).as("Vercel's docs server is another server")
        .isEqualTo(Mode.READ_ONLY);
    assertThat(definition(connector("https://mcp.vercel.com", tool("buy_domain", true)), "buy_domain")
        .defaultMode()).isEqualTo(Mode.APPROVAL);
  }
}
