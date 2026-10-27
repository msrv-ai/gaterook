package ai.msrv.gaterook.connectors.ssh;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.SecretResolver;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.client.session.forward.ExplicitPortForwardingTracker;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.apache.sshd.core.CoreModuleProperties;

/**
 * Opens sessions and keeps those of reads (report 06 §7.4). A read reuses a ready session of its connection — one
 * exec on it costs tens of milliseconds against hundreds for connect, key exchange and sign-in — at most
 * {@code maxChannelsPerSession} channels at a time (OpenSSH allows 10 by default). A session lives while it is used
 * and at most two idle minutes, ten minutes in all and never past its certificate's end minus 30 s. A change always
 * opens a fresh session that ends with the call: its certificate names the approval ({@code key_id}).
 *
 * <p>Pools are keyed by connection id and config version: an edit, a secret rotation or a policy change gives a new
 * version, and {@link #forget} closes what the old one opened.
 */
final class SshSessions implements AutoCloseable {

  static final Duration IDLE = Duration.ofSeconds(120);
  static final Duration MAX_AGE = Duration.ofMinutes(10);
  static final Duration CERTIFICATE_MARGIN = Duration.ofSeconds(30);

  /** A session and what must close with it: the bastion's session, if any. */
  record Opened(ClientSession session, ClientSession jump, SshLogins.Login login, SshLogins.Login jumpLogin,
      String banner, HostKeys.Check hostCheck, HostKeys.Check jumpCheck) implements AutoCloseable {

    /** The passwords this session signed in with, into the taint of a call that uses it (pooled: an earlier call's). */
    void taint(ExecContext ctx) {
      login.taint(ctx);
      if (jumpLogin != null) {
        jumpLogin.taint(ctx);
      }
    }

    @Override
    public void close() {
      session.close(true);
      if (jump != null) {
        jump.close(true);
      }
    }
  }

  /** A session for one call; {@link #close} gives it back (pooled) or closes it (fresh). */
  final class Lease implements AutoCloseable {
    final Opened opened;
    private final Pooled pooled;

    private Lease(Opened opened, Pooled pooled) {
      this.opened = opened;
      this.pooled = pooled;
    }

    ClientSession session() {
      return opened.session();
    }

    /** One channel at a time per permit; a pooled session caps them at {@code maxChannelsPerSession}. */
    boolean acquireChannel(Duration wait) throws InterruptedException {
      return pooled == null || pooled.channels.tryAcquire(wait.toMillis(), TimeUnit.MILLISECONDS);
    }

    void releaseChannel() {
      if (pooled != null) {
        pooled.channels.release();
      }
    }

    /** The session broke (or the host refused a channel): don't hand it out again. */
    void discard() {
      if (pooled != null) {
        pooled.broken = true;
      }
    }

    @Override
    public void close() {
      if (pooled == null) {
        opened.close();
        return;
      }
      synchronized (pooled) {
        pooled.users--;
        pooled.lastUsed = Instant.now();
        if (pooled.broken && pooled.users == 0) {
          remove(pooled);
        }
      }
    }
  }

  private static final class Pooled {
    final String key;
    final String connectionId;
    final Opened opened;
    final Semaphore channels;
    final Instant expires;
    Instant lastUsed = Instant.now();
    int users;
    volatile boolean broken;

    Pooled(String key, String connectionId, Opened opened, int maxChannels, Instant expires) {
      this.key = key;
      this.connectionId = connectionId;
      this.opened = opened;
      this.channels = new Semaphore(maxChannels, true);
      this.expires = expires;
    }

    boolean usable(Instant now) {
      return !broken && opened.session().isOpen() && now.isBefore(expires);
    }
  }

  private final SshClients clients;
  private final Map<String, Pooled> pool = new ConcurrentHashMap<>();
  private final Map<String, Object> creating = new ConcurrentHashMap<>();
  private ScheduledFuture<?> sweeper;

  SshSessions(SshClients clients) {
    this.clients = clients;
  }

  /**
   * A session for a call: pooled for a read when the connection allows reuse, otherwise fresh.
   *
   * @param keyId the key id of a Vault certificate, {@code gw:<connection>:<approval or call>}
   */
  Lease acquire(ConnectionConfig cfg, SshSettings s, SecretResolver secrets, Duration timeout, boolean fresh,
      String keyId) throws ConnectorException {
    if (fresh || !s.advanced().reuseSessions()) {
      return new Lease(open(s, secrets, timeout, keyId), null);
    }
    String key = cfg.id() + "@" + cfg.version();
    Object lock = creating.computeIfAbsent(key, k -> new Object());
    synchronized (lock) {
      Instant now = Instant.now();
      Pooled p = pool.get(key);
      if (p != null) {
        synchronized (p) {
          if (p.usable(now)) {
            p.users++;
            return new Lease(p.opened, p);
          }
        }
        removeIfIdle(p);
      }
      Opened opened = open(s, secrets, timeout, keyId);
      Instant expires = now.plus(MAX_AGE);
      if (opened.login().notAfter() != null && opened.login().notAfter().minus(CERTIFICATE_MARGIN).isBefore(expires)) {
        expires = opened.login().notAfter().minus(CERTIFICATE_MARGIN);
      }
      Pooled fresh2 = new Pooled(key, cfg.id(), opened, s.advanced().maxChannels(), expires);
      fresh2.users = 1;
      pool.put(key, fresh2);
      startSweeper();
      return new Lease(opened, fresh2);
    }
  }

  /** Connect, check the host key, sign in; through the bastion when there is one. */
  Opened open(SshSettings s, SecretResolver secrets, Duration timeout, String keyId) throws ConnectorException {
    long deadline = System.nanoTime() + timeout.toNanos();
    HostKeys.Check hostCheck = HostKeys.check(s.target(), s.hostKeys());
    if (s.jump() == null) {
      ClientSession session = connect(s, s.target(), s.target().host(), s.target().port(), hostCheck, left(deadline));
      return signIn(session, null, null, s, secrets, deadline, keyId, hostCheck, null);
    }
    SshSettings.Jump jump = s.jump();
    HostKeys.Check jumpCheck = HostKeys.check(jump.target(), jump.hostKeys());
    ClientSession bastion = connect(s, jump.target(), jump.target().host(), jump.target().port(), jumpCheck,
        left(deadline));
    try {
      SshLogins.Login jumpLogin = SshLogins.login(jump.credentials(), jump.target(), secrets, keyId, true);
      authenticate(bastion, jumpLogin, left(deadline), jump.target(), jumpCheck);
      ClientSession session = throughBastion(bastion, s, hostCheck, left(deadline));
      return signIn(session, bastion, jumpLogin, s, secrets, deadline, keyId, hostCheck, jumpCheck);
    } catch (ConnectorException | RuntimeException e) {
      bastion.close(true);
      throw e;
    }
  }

  /** The target over a local forward of the signed-in bastion; the local port is closed as soon as TCP is through. */
  ClientSession throughBastion(ClientSession bastion, SshSettings s, HostKeys.Check hostCheck, Duration timeout)
      throws ConnectorException {
    // The local port lives only until the target's TCP connection is through (report 06 §9.9).
    try (ExplicitPortForwardingTracker tracker = bastion.createLocalPortForwardingTracker(
        new SshdSocketAddress("127.0.0.1", 0), new SshdSocketAddress(s.target().host(), s.target().port()))) {
      SshdSocketAddress local = tracker.getBoundAddress();
      return connect(s, s.target(), local.getHostName(), local.getPort(), hostCheck, timeout);
    } catch (IOException e) {
      throw SshErrors.forwarding(s.jump().target(), e);
    } catch (ConnectorException e) {
      if (e.kind() == ConnectorException.Kind.RESOURCE_UNAVAILABLE) {
        // The bastion took the forward but closed it: AllowTcpForwarding or permit-port-forwarding is missing.
        throw SshErrors.forwarding(s.jump().target(), new IOException(e.getMessage(), e));
      }
      throw e;
    }
  }

  private Opened signIn(ClientSession session, ClientSession bastion, SshLogins.Login jumpLogin, SshSettings s,
      SecretResolver secrets, long deadline, String keyId, HostKeys.Check hostCheck, HostKeys.Check jumpCheck)
      throws ConnectorException {
    try {
      SshLogins.Login login = SshLogins.login(s.credentials(), s.target(), secrets, keyId, false);
      CoreModuleProperties.HEARTBEAT_INTERVAL.set(session, s.advanced().keepalive());
      authenticate(session, login, left(deadline), s.target(), hostCheck);
      Opened opened = new Opened(session, bastion, login, jumpLogin, session.getServerVersion(), hostCheck,
          jumpCheck);
      if (bastion != null) {
        session.addCloseFutureListener(f -> bastion.close(true));
      }
      return opened;
    } catch (ConnectorException | RuntimeException e) {
      session.close(true);
      throw e;
    }
  }

  /**
   * TCP and key exchange: the session comes back only once the host key is trusted, before any credential is even
   * resolved (a Vault certificate is signed only for a trusted host).
   */
  ClientSession connect(SshSettings s, SshSettings.Target target, String host, int port, HostKeys.Check check,
      Duration timeout) throws ConnectorException {
    SshClient client = clients.client(s.advanced().legacyAlgorithms());
    long deadline = System.nanoTime() + timeout.toNanos();
    ClientSession session;
    try {
      session = client.connect(target.username(), host, port, HostKeys.context(check))
          .verify(min(s.advanced().connectTimeout(), timeout)).getSession();
    } catch (IOException e) {
      throw SshErrors.connect(target, e);
    }
    Set<ClientSession.ClientSessionEvent> state = session.waitFor(
        EnumSet.of(ClientSession.ClientSessionEvent.WAIT_AUTH, ClientSession.ClientSessionEvent.CLOSED),
        left(deadline));
    if (!state.contains(ClientSession.ClientSessionEvent.WAIT_AUTH)) {
      session.close(true);
      ConnectorException hostKey = SshErrors.hostKey(target, check);
      if (hostKey != null) {
        throw hostKey;
      }
      if (state.contains(ClientSession.ClientSessionEvent.TIMEOUT)) {
        throw ConnectorException.timeout(target.address() + " didn't finish the key exchange in time.");
      }
      Throwable failure = check.failure();
      String why = failure == null ? "" : String.valueOf(failure.getMessage()).toLowerCase(Locale.ROOT);
      if (why.contains("negotiat") || why.contains("no common")) {
        throw ConnectorException.resourceError("MCP Gateway and " + target.address() + " have no algorithms in "
            + "common. For an old server, turn on Allow old algorithms (Advanced).", failure);
      }
      throw ConnectorException.unavailable(target.address() + " closed the connection during the key exchange.",
          failure);
    }
    return session;
  }

  void authenticate(ClientSession session, SshLogins.Login login, Duration timeout, SshSettings.Target target,
      HostKeys.Check check) throws ConnectorException {
    login.addTo(session);
    try {
      session.auth().verify(timeout);
    } catch (IOException e) {
      throw SshErrors.auth(target, check, login, e);
    }
  }

  private static Duration left(long deadline) {
    long nanos = deadline - System.nanoTime();
    return Duration.ofNanos(Math.max(nanos, 1_000_000));
  }

  /** Closes every session of a connection: its config, a secret or its policy changed, or it was removed. */
  void forget(String connectionId) {
    creating.keySet().removeIf(k -> k.startsWith(connectionId + "@"));
    for (Pooled p : new ArrayList<>(pool.values())) {
      if (p.connectionId.equals(connectionId)) {
        synchronized (p) {
          p.broken = true;
          if (p.users == 0) {
            remove(p);
          }
        }
      }
    }
  }

  private synchronized void startSweeper() {
    if (sweeper == null) {
      // MINA's own scheduler: the connector starts no threads of its own.
      sweeper = clients.client(false).getScheduledExecutorService().scheduleWithFixedDelay(this::sweep, 30, 30,
          TimeUnit.SECONDS);
    }
  }

  void sweep() {
    Instant now = Instant.now();
    for (Pooled p : new ArrayList<>(pool.values())) {
      synchronized (p) {
        boolean idle = p.users == 0 && p.lastUsed.plus(IDLE).isBefore(now);
        if (p.users == 0 && (idle || !p.usable(now))) {
          remove(p);
        }
      }
    }
  }

  private void removeIfIdle(Pooled p) {
    synchronized (p) {
      if (p.users == 0) {
        remove(p);
      } else {
        p.broken = true; // the last user closes it
      }
    }
  }

  private void remove(Pooled p) {
    pool.remove(p.key, p);
    p.opened.close();
  }

  int pooled() {
    return pool.size();
  }

  @Override
  public synchronized void close() {
    if (sweeper != null) {
      sweeper.cancel(false);
    }
    List<Pooled> all = new ArrayList<>(pool.values());
    pool.clear();
    all.forEach(p -> p.opened.close());
  }

  private static Duration min(Duration a, Duration b) {
    return a.compareTo(b) <= 0 ? a : b;
  }
}
