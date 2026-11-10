package ai.msrv.gaterook.core.mcpservers.internal;

import ai.msrv.gaterook.core.connections.ConnectionConfigs;
import ai.msrv.gaterook.core.secrets.SecretOwner;
import ai.msrv.gaterook.core.secrets.SecretOwners;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The Secrets screen's view of what MCP servers own themselves ({@code secret.owner_kind = 'mcp-server'}): the
 * OAuth sign-in GateRook keeps and renews for a server added by its URL, and the record of where the client secret of
 * the person's OAuth app may go ({@code /oauth-app}). Its owner is the server's connection; a person replaces them by
 * signing in again (after entering the client secret again, for the record), never by typing a value or rotating it
 * here.
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
    if ("/oauth-app".equals(field)) {
      return Optional.of("GateRook records here which authorization server the client secret of the server's OAuth "
          + "app goes to. To trust another one, enter the client secret again, then sign in.");
    }
    return Optional.of("GateRook renews this sign-in itself. To replace it, sign in to the server again in "
        + "Connectors › MCP servers.");
  }
}
