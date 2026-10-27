package ai.msrv.gaterook.connector.api;

import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Secrets of one connection for one call, closed by the core.
 *
 * <p>Only {@code writeOnly} fields of this connection resolve. Every value handed out is tainted: raw, Base64,
 * URL-encoded and JSON-escaped echoes of it get masked. Plugins get the same API over GPP/1.
 */
public interface SecretResolver {

  /** A value, a PEM or a binary. */
  Secret secret(String field) throws SecretUnavailableException;

  /** A static login, a reference or Vault dynamic credentials. */
  Login login(String field) throws SecretUnavailableException;

  boolean isSet(String field);

  /** No value: pool keys, UI hints. */
  Meta meta(String field);

  /** Vault SSH CA: a certificate for {@code key}, valid for {@code request.ttl()}. */
  SshCertificate sshCertificate(String field, PublicKey key, SshCertRequest request)
      throws SecretUnavailableException;

  /** Live views zeroed on close(); toString() is "‹secret›"; Jackson refuses to serialize it. */
  interface Secret extends AutoCloseable {
    char[] chars();

    byte[] bytes();

    /** Only for APIs that demand a String (Hikari, MINA, AWS SDK); still tainted. */
    String unsafeString();

    String version();

    Optional<Instant> notAfter();

    @Override
    void close();
  }

  record Login(String username, Secret password, String version, Instant notAfter) implements AutoCloseable {
    @Override
    public void close() {
      password.close();
    }
  }

  /** {@code source}: local | reference | dynamic. */
  record Meta(String source, String version, Instant notAfter) {}

  record SshCertRequest(String principal, Duration ttl, String keyId, Map<String, String> extensions) {}

  record SshCertificate(String openSshText, String serial, Instant notAfter) {}

  /** Optional connector capability: Rotate at the source (ALTER ROLE … PASSWORD); a person's action, no approval. */
  interface CredentialRotator {
    boolean canRotate(ConnectionConfig cfg, String field);

    void rotate(ConnectionConfig cfg, SecretResolver current, String field, char[] next, ExecContext ctx)
        throws ConnectorException;
  }
}
