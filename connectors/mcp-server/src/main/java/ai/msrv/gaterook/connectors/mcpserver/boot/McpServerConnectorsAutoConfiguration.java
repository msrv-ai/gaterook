package ai.msrv.gaterook.connectors.mcpserver.boot;

import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudEndpoints;
import ai.msrv.gaterook.connectors.mcpserver.McpServerConnector;
import ai.msrv.gaterook.connectors.mcpserver.McpServers;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.core.env.Environment;

/**
 * Registers the External MCP server connector type and the runtime of its servers (ARCHITECTURE.md §2.3, §7.5, A13):
 * explicit beans listed in this module's own {@code AutoConfiguration.imports}, resolved by Spring AOT at build time.
 * No {@code @Conditional*}: the type is switched on and off at run time by {@code connector_type.enabled}. The core
 * finds the runtime by its SPI type ({@code McpServerRuntime}) and attaches to it; nothing starts before that.
 */
@AutoConfiguration
@ImportRuntimeHints(McpServerHints.class)
public class McpServerConnectorsAutoConfiguration {

  /**
   * @param env   {@code gateway.mcp-servers.login-shell-path=false} skips asking the login shell for the PATH (tests);
   *              {@code gateway.mcp-servers.tick} sets how often idle stops and pings are looked at
   * @param cloud the core's cloud credentials (a server's Google token or AWS signature); a context without the core
   *              gets credentials for the real endpoints
   */
  @Bean
  McpServers mcpServerRuntime(Environment env, ObjectProvider<CloudCredentials> cloud) {
    boolean loginShell = env.getProperty("gateway.mcp-servers.login-shell-path", Boolean.class, Boolean.TRUE);
    Duration tick = env.getProperty("gateway.mcp-servers.tick", Duration.class, Duration.ofSeconds(5));
    Clock clock = Clock.systemUTC();
    return new McpServers(System.getenv(), System.getProperty("os.name", ""), loginShell, clock, tick,
        McpServerConnector.typeManifest(), cloud.getIfAvailable(() -> new CloudCredentials(CloudEndpoints.PRODUCTION,
            clock)));
  }

  @Bean
  Connector mcpServerConnector(McpServers mcpServerRuntime) {
    return new McpServerConnector(mcpServerRuntime);
  }
}
