package ai.msrv.gaterook.core.mcpservers.internal;

import ai.msrv.gaterook.core.connections.ConnectionConfigs;
import ai.msrv.gaterook.core.secrets.SecretOwner;
import ai.msrv.gaterook.core.secrets.SecretOwners;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The Secrets screen's view of what MCP servers own themselves ({@code secret.owner_kind = 'mcp-server'}): the
 * OAuth sign-in GateRook keeps and renews for a server added by its URL. Its owner is the server's connection; a
 * person replaces it by signing in again, never by typing a value or rotating it here.
 */
@Component
class McpServerSecretOwners implements SecretOwners {

  private final ConnectionConfigs configs;

  McpServerSecretOwners(ConnectionConfigs configs) {
    this.configs = configs;
  }

  @Override
  public SecretOwner.Kind kind() {
    return SecretOwner.Kind.MCP_SERVER;
  }

  @Override
  public Optional<Owner> describe(String ownerId) {
    return configs.peek(ownerId).map(c -> new Owner(c.name(), c.env(), List.of(c.name())));
  }

  @Override
  public Optional<String> rotateUnavailable(String ownerId, String field) {
    return Optional.of("GateRook renews this sign-in itself. To replace it, sign in to the server again in "
        + "Connectors › MCP servers.");
  }
}
