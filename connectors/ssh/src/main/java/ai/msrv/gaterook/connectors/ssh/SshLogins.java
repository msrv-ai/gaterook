package ai.msrv.gaterook.connectors.ssh;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.SecretResolver;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Map;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.OpenSshCertificate;
import org.apache.sshd.common.keyprovider.KeyPairProvider;

/**
 * Signing in (report 06 §1, §7.3): a password, a private key (with its optional certificate), or a fresh Vault SSH
 * CA certificate for an ephemeral Ed25519 key that never leaves memory. Credentials are added to the one session,
 * never to the client, so one connection's credentials can't be offered to another host.
 */
final class SshLogins {

  /**
   * What one session signs in with.
   *
   * @param notAfter the certificate's end, or {@code null}: a pooled session must not outlive it
   * @param serial   of a Vault certificate, hex, for Test connection and the audit; {@code null} otherwise
   */
  record Login(SshSettings.Method method, String password, KeyPair keys, Instant notAfter, String serial,
      long signMs) {

    void addTo(ClientSession session) {
      if (password != null) {
        session.addPasswordIdentity(password);
      }
      if (keys != null) {
        session.addPublicKeyIdentity(keys);
      }
    }

    /**
     * Puts the password into the taint of a call that uses this login's session: a pooled session signed in for an
     * earlier call, so this call resolved nothing, and an echo of the password must still be masked.
     */
    void taint(ExecContext ctx) {
      if (password != null) {
        ctx.taint(password);
      }
    }

    /** Never the password: a record would print it. */
    @Override
    public String toString() {
      return "Login[" + method.id + (serial == null ? "" : ", serial " + serial) + "]";
    }

    /** "deploy · Vault SSH CA certificate, valid 5 min": the "Runs as" line approvers read. */
    static String describe(SshSettings settings) {
      SshSettings.Credentials c = settings.credentials();
      String how = switch (c.method()) {
        case VAULT_SSH_CA -> "Vault SSH CA certificate, valid " + c.vault().ttl().toMinutes() + " min";
        case PRIVATE_KEY -> c.certificate() == null ? "private key" : "private key and certificate";
        case PASSWORD -> "password";
      };
      return settings.target().username() + " · " + how;
    }
  }

  private SshLogins() {}

  /**
   * @param keyId     the certificate's key id ({@code gw:<connection>:<approval or call>}), which sshd writes to its
   *                  auth log next to the login
   * @param forBastion a Vault certificate for a bastion needs {@code permit-port-forwarding}
   */
  static Login login(SshSettings.Credentials c, SshSettings.Target target, SecretResolver secrets, String keyId,
      boolean forBastion) throws ConnectorException {
    return switch (c.method()) {
      case PASSWORD -> {
        try (SecretResolver.Secret password = secrets.secret(c.password())) {
          // MINA's API takes a String; it stays tainted for the masker (report 06 §6).
          yield new Login(c.method(), password.unsafeString(), null, null, null, 0);
        }
      }
      case PRIVATE_KEY -> {
        char[] passphrase = null;
        try (SecretResolver.Secret key = secrets.secret(c.privateKey())) {
          SecretResolver.Secret p = secrets.isSet(c.passphrase()) ? secrets.secret(c.passphrase()) : null;
          try {
            passphrase = p == null ? null : p.chars();
            KeyPair pair = PrivateKeys.parse(key.chars(), passphrase, c.privateKey());
            Instant notAfter = null;
            if (c.certificate() != null) {
              pair = PrivateKeys.withCertificate(pair, c.certificate());
              notAfter = validBefore((OpenSshCertificate) pair.getPublic());
            }
            yield new Login(c.method(), null, pair, notAfter, null, 0);
          } finally {
            if (p != null) {
              p.close();
            }
          }
        }
      }
      case VAULT_SSH_CA -> vault(c, target, secrets, keyId, forBastion);
    };
  }

  private static Login vault(SshSettings.Credentials c, SshSettings.Target target, SecretResolver secrets,
      String keyId, boolean forBastion) throws ConnectorException {
    SshSettings.Vault v = c.vault();
    if (v == null || v.store() == null || v.store().isBlank()) {
      throw ConnectorException.badInput("Choose the Vault that signs the certificates (credentials.vault.store).");
    }
    KeyPair ephemeral;
    try {
      ephemeral = KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, 256);
    } catch (GeneralSecurityException e) {
      throw new ConnectorException(ConnectorException.Kind.INTERNAL, "No Ed25519 key could be made.", e);
    }
    String principal = v.principal() == null ? target.username() : v.principal();
    long t0 = System.nanoTime();
    SecretResolver.SshCertificate signed = secrets.sshCertificate(c.vaultField(), ephemeral.getPublic(),
        new SecretResolver.SshCertRequest(principal, v.ttl(), keyId,
            forBastion ? Map.of("permit-port-forwarding", "") : Map.of()));
    long signMs = (System.nanoTime() - t0) / 1_000_000;
    PublicKey cert;
    try {
      cert = HostKeys.parse(signed.openSshText().strip());
    } catch (IOException | GeneralSecurityException | RuntimeException e) {
      throw ConnectorException.resourceError("Vault returned something that isn't an OpenSSH certificate.", e);
    }
    if (!(cert instanceof OpenSshCertificate oc) || !KeyUtils.compareKeys(oc.getCertPubKey(), ephemeral.getPublic())) {
      throw ConnectorException.resourceError("Vault returned a certificate for another key.", null);
    }
    String serial = signed.serial() != null ? signed.serial() : Long.toUnsignedString(oc.getSerial(), 16);
    Instant notAfter = signed.notAfter() != null ? signed.notAfter() : validBefore(oc);
    return new Login(c.method(), null, new KeyPair(cert, ephemeral.getPrivate()), notAfter, serial, signMs);
  }

  private static Instant validBefore(OpenSshCertificate c) {
    long before = c.getValidBefore();
    // 0xFFFF_FFFF_FFFF_FFFF means forever in OpenSSH certificates.
    return before == -1L ? null : Instant.ofEpochSecond(before);
  }
}
