package ai.msrv.gaterook.core.secrets;

import java.util.Objects;

/**
 * Whose config a secret field belongs to ({@code secret.owner_kind}, {@code secret.owner_id}): a connection, an
 * external store's sign-in, an MCP server, a notification channel, the tunnel, an audit export.
 */
public record SecretOwner(Kind kind, String id) {

  public enum Kind { CONNECTION, STORE, MCP_SERVER, NOTIFICATION, TUNNEL, AUDIT_EXPORT }

  public SecretOwner {
    Objects.requireNonNull(kind, "kind");
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("An owner needs an id");
    }
  }

  public static SecretOwner connection(String id) {
    return new SecretOwner(Kind.CONNECTION, id);
  }
}
