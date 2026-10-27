package ai.msrv.gaterook.connectors.ssh;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.auth.password.PasswordIdentityProvider;
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.NamedFactory;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.io.nio2.Nio2ServiceFactoryFactory;
import org.apache.sshd.common.kex.KexProposalOption;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.apache.sshd.common.random.JceRandomFactory;
import org.apache.sshd.common.random.SingletonRandomFactory;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.session.SessionListener;
import org.apache.sshd.common.signature.Signature;
import org.apache.sshd.core.CoreModuleProperties;

/**
 * The hardened MINA clients of the connector (report 06 §1, §6, §7.2): one with modern algorithms, one that also
 * speaks to old servers ({@code advanced.legacyAlgorithms}). MINA's defaults that would reach outside a connection's
 * config are replaced: every host key is checked by {@link HostKeys}, never accepted blindly; nothing is read from
 * {@code ~/.ssh} (config, keys) or an agent; credentials are added to each session, never to the client. Exec
 * channels only — no shell, PTY, agent or X11 forwarding is ever requested.
 *
 * <p>Each client owns MINA's I/O threads; both start lazily and stop with the connector.
 */
final class SshClients implements AutoCloseable {

  private final String identification;
  private SshClient modern;
  private SshClient legacy;
  private boolean closed;

  SshClients(String version) {
    // "SSH-2.0-" is MINA's; the host logs this in auth.log next to the user.
    this.identification = "GateRook_" + version.replaceAll("[^A-Za-z0-9.]", "_");
  }

  synchronized SshClient client(boolean legacyAlgorithms) {
    if (closed) {
      throw new IllegalStateException("The SSH connector was closed");
    }
    if (legacyAlgorithms) {
      if (legacy == null) {
        legacy = start(true);
      }
      return legacy;
    }
    if (modern == null) {
      modern = start(false);
    }
    return modern;
  }

  private SshClient start(boolean legacyAlgorithms) {
    MinaSecurity.install();
    SshClient c = SshClient.setUpDefaultClient();
    ServerKeyVerifier verifier = HostKeys.VERIFIER;
    c.setServerKeyVerifier(verifier);                                         // default: accept all
    c.setHostConfigEntryResolver(HostConfigEntryResolver.EMPTY);              // default: ~/.ssh/config
    c.setKeyIdentityProvider(KeyIdentityProvider.EMPTY_KEYS_PROVIDER);        // default: ~/.ssh/id_*
    c.setPasswordIdentityProvider(PasswordIdentityProvider.EMPTY_PASSWORDS_PROVIDER);
    c.setAgentFactory(null);
    c.setUserInteraction(null);
    c.setIoServiceFactoryFactory(new Nio2ServiceFactoryFactory());
    c.setRandomFactory(new SingletonRandomFactory(JceRandomFactory.INSTANCE));
    if (!legacyAlgorithms) {
      // SHA-1 signatures and KEX, CBC ciphers and SHA-1 MACs only for hosts that need them (report 06 §6).
      c.setSignatureFactories(new ArrayList<>(c.getSignatureFactories().stream()
          .filter(f -> !"ssh-rsa".equals(f.getName()) && !f.getName().startsWith("ssh-dss")).toList()));
      c.setCipherFactories(new ArrayList<>(c.getCipherFactories().stream()
          .filter(f -> !f.getName().endsWith("-cbc")).toList()));
      c.setMacFactories(new ArrayList<>(c.getMacFactories().stream()
          .filter(f -> !f.getName().startsWith("hmac-sha1") && !f.getName().startsWith("hmac-md5")).toList()));
      c.setKeyExchangeFactories(new ArrayList<>(c.getKeyExchangeFactories().stream()
          .filter(f -> !f.getName().endsWith("-sha1")).toList()));
    }
    CoreModuleProperties.CLIENT_IDENTIFICATION.set(c, identification);
    CoreModuleProperties.HEARTBEAT_INTERVAL.set(c, Duration.ofSeconds(15));
    CoreModuleProperties.HEARTBEAT_NO_REPLY_MAX.set(c, 3);
    CoreModuleProperties.AUTH_TIMEOUT.set(c, Duration.ofSeconds(10));
    c.addSessionListener(new SessionWatch());
    c.start();
    return c;
  }

  @Override
  public synchronized void close() {
    closed = true;
    for (SshClient c : new SshClient[] {modern, legacy}) {
      if (c != null) {
        c.stop();
      }
    }
    modern = null;
    legacy = null;
  }

  /**
   * Proposes the host key algorithms we already trust for the host first, as OpenSSH does with known hosts: a server
   * with an RSA and an Ed25519 key would otherwise offer whichever the client prefers, and a pinned RSA key would look
   * like a changed key. Also keeps why a session failed before sign-in, for the message.
   */
  private static final class SessionWatch implements SessionListener {

    @Override
    public void sessionNegotiationEnd(Session session, Map<KexProposalOption, String> clientProposal,
        Map<KexProposalOption, String> serverProposal, Map<KexProposalOption, String> negotiatedOptions,
        Throwable reason) {
      HostKeys.Check check = check(session);
      if (check != null && reason != null) {
        check.failed(reason);
      }
    }

    @Override
    public void sessionException(Session session, Throwable t) {
      HostKeys.Check check = check(session);
      if (check != null) {
        check.failed(t);
      }
    }

    private static HostKeys.Check check(Session session) {
      if (!(session instanceof ClientSession client) || client.getConnectionContext() == null) {
        return null;
      }
      return client.getConnectionContext().getAttribute(HostKeys.CHECK);
    }

    @Override
    public void sessionCreated(Session session) {
      HostKeys.Check check = check(session);
      if (check == null) {
        return;
      }
      ClientSession client = (ClientSession) session;
      Set<String> trusted = check.trustedKeyTypes();
      if (trusted.isEmpty()) {
        return;
      }
      List<NamedFactory<Signature>> first = new ArrayList<>();
      List<NamedFactory<Signature>> rest = new ArrayList<>();
      for (NamedFactory<Signature> f : client.getSignatureFactories()) {
        (trusted.contains(KeyUtils.getCanonicalKeyType(f.getName())) ? first : rest).add(f);
      }
      first.addAll(rest);
      client.setSignatureFactories(first);
    }
  }
}
