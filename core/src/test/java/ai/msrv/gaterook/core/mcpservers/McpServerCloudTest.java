package ai.msrv.gaterook.core.mcpservers;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;

/**
 * Servers on Google Cloud and AWS (ARCHITECTURE.md §7.6): the review offers their cloud's credentials in place of an
 * OAuth sign-in Google gives only to an app the person registers, and AWS for at most 12 hours.
 */
class McpServerCloudTest {

  @Test
  void aServerOnGoogleCloudOrAwsCanSignInWithItsCloudsCredentials() {
    assertThat(McpServerSignIns.cloud(URI.create("https://bigtableadmin.googleapis.com/mcp"), null)).isEqualTo("google");
    assertThat(McpServerSignIns.cloud(URI.create("https://aws-mcp.us-east-1.api.aws/mcp"), null)).isEqualTo("aws");
    assertThat(McpServerSignIns.cloud(URI.create("https://bedrock-agentcore.us-west-2.amazonaws.com/runtimes/x/mcp"),
        null)).isEqualTo("aws");
    assertThat(McpServerSignIns.cloud(URI.create("https://mcp.linear.app/mcp"), null)).isNull();
  }
}
