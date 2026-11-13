package com.gaterook.core.mcpservers;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;

/**
 * Servers on Google Cloud and AWS (ARCHITECTURE.md §7.6): the review offers their cloud's credentials in place of an
 * OAuth sign-in Google gives only to an app the person registers, and AWS for at most 12 hours. Only the server's own
 * address decides: the authorization server its metadata names is the server's say.
 */
class McpServerCloudTest {

  @Test
  void aServerOnGoogleCloudOrAwsCanSignInWithItsCloudsCredentials() {
    assertThat(McpServerSignIns.cloud(URI.create("https://bigtableadmin.googleapis.com/mcp"))).isEqualTo("google");
    assertThat(McpServerSignIns.cloud(URI.create("https://BigQuery.GoogleAPIs.com:443/mcp"))).isEqualTo("google");
    assertThat(McpServerSignIns.cloud(URI.create("https://aws-mcp.us-east-1.api.aws/mcp"))).isEqualTo("aws");
    assertThat(McpServerSignIns.cloud(URI.create("https://bedrock-agentcore.us-west-2.amazonaws.com/runtimes/x/mcp")))
        .isEqualTo("aws");
    assertThat(McpServerSignIns.cloud(URI.create("https://mcp.linear.app/mcp"))).isNull();
  }

  @Test
  void googlesCredentialsAreOfferedOnlyForGooglesOwnApiHostsOverHttps() {
    // Code that other people deploy runs elsewhere, and a token with the cloud-platform scope would reach them.
    for (String url : new String[] {"http://bigquery.googleapis.com/mcp", "https://bigquery.googleapis.com:8443/mcp",
        "https://someone@bigquery.googleapis.com/mcp", "https://googleapis.com.example.test/mcp",
        "https://example-googleapis.com/mcp", "https://my-service-abc123.a.run.app/mcp",
        "https://mcp.example.test/mcp"}) {
      assertThat(McpServerSignIns.cloud(URI.create(url))).as(url).isNull();
    }
  }
}
