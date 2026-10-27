package ai.msrv.gaterook.connectors.ssh;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.sshd.common.channel.Channel;
import org.apache.sshd.common.channel.ChannelFactory;
import org.apache.sshd.common.channel.ChannelListener;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.OpenSshCertificate;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.session.SessionListener;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.Signal;
import org.apache.sshd.server.SignalListener;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.forward.AcceptAllForwardingFilter;
import org.apache.sshd.server.forward.DirectTcpipFactory;
import org.apache.sshd.server.forward.RejectAllForwardingFilter;

/**
 * An in-process SSH server that runs exec requests with {@code /bin/sh -c} as the test user, like sshd does: no PTY,
 * stdout and stderr apart, the exit status, and {@code signal} requests delivered to the process and its children
 * (OpenSSH ≥ 7.9 signals the process group). It records what it saw, so tests can check what reached the host.
 */
final class TestSshd implements AutoCloseable {

  static final String USER = "deploy";
  static final String PASSWORD = "correct horse battery";

  final SshServer server;
  final KeyPair hostKey;
  final Set<PublicKey> authorizedKeys = ConcurrentHashMap.newKeySet();
  volatile PublicKey userCa;
  final List<OpenSshCertificate> certificates = new CopyOnWriteArrayList<>();
  final List<String> commands = new CopyOnWriteArrayList<>();
  final List<String> signals = new CopyOnWriteArrayList<>();
  final List<Process> processes = new CopyOnWriteArrayList<>();
  final AtomicInteger sessions = new AtomicInteger();
  final AtomicInteger passwordAttempts = new AtomicInteger();
  final AtomicInteger refusedChannels = new AtomicInteger();
  /** Like OpenSSH's MaxSessions: more session channels at a time on one connection are refused. */
  volatile int maxChannelsPerSession = Integer.MAX_VALUE;
  private final Map<Session, AtomicInteger> openChannels = new ConcurrentHashMap<>();

  private TestSshd(SshServer server, KeyPair hostKey) {
    this.server = server;
    this.hostKey = hostKey;
  }

  static TestSshd start() throws Exception {
    return start(false);
  }

  /** @param forwarding a bastion: lets clients open direct-tcpip channels */
  static TestSshd start(boolean forwarding) throws Exception {
    MinaSecurity.install();
    KeyPair hostKey = KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, 256);
    SshServer s = SshServer.setUpDefaultServer();
    TestSshd sshd = new TestSshd(s, hostKey);
    s.setHost("127.0.0.1");
    s.setPort(0);
    s.setKeyPairProvider(KeyPairProvider.wrap(hostKey));
    s.setPasswordAuthenticator((user, password, session) -> {
      sshd.passwordAttempts.incrementAndGet();
      return USER.equals(user) && PASSWORD.equals(password);
    });
    s.setPublickeyAuthenticator((user, key, session) -> {
      if (!USER.equals(user)) {
        return false;
      }
      if (key instanceof OpenSshCertificate cert) {
        // MINA checked the CA signature, the type and the validity; the CA and the principal are ours to check.
        PublicKey ca = sshd.userCa;
        boolean ok = ca != null && KeyUtils.compareKeys(ca, cert.getCaPubKey()) && cert.getPrincipals().contains(user);
        // MINA asks twice per sign-in (may I use it? then: here is the signature): record each certificate once.
        if (ok && sshd.certificates.stream().noneMatch(c -> Arrays.equals(c.getNonce(), cert.getNonce()))) {
          sshd.certificates.add(cert);
        }
        return ok;
      }
      return sshd.authorizedKeys.stream().anyMatch(k -> KeyUtils.compareKeys(k, key));
    });
    s.setCommandFactory((channel, command) -> new Sh(command, sshd));
    s.setChannelFactories(List.of(sshd.new LimitedSessions(), DirectTcpipFactory.INSTANCE));
    s.setForwardingFilter(forwarding ? AcceptAllForwardingFilter.INSTANCE : RejectAllForwardingFilter.INSTANCE);
    s.addSessionListener(new SessionListener() {
      @Override
      public void sessionCreated(Session session) {
        sshd.sessions.incrementAndGet();
      }
    });
    s.start();
    return sshd;
  }

  int port() {
    return server.getPort();
  }

  String hostKeyLine() {
    return HostKeys.line(hostKey.getPublic());
  }

  /** Whether every process the server started has ended. */
  boolean allEnded() {
    return processes.stream().noneMatch(Process::isAlive);
  }

  @Override
  public void close() throws IOException {
    server.stop(true);
    for (Process p : processes) {
      p.descendants().forEach(ProcessHandle::destroyForcibly);
      p.destroyForcibly();
    }
  }

  /** Session channels, at most {@link #maxChannelsPerSession} open at a time per connection. */
  private final class LimitedSessions implements ChannelFactory {
    @Override
    public String getName() {
      return "session";
    }

    @Override
    public Channel createChannel(Session session) {
      AtomicInteger open = openChannels.computeIfAbsent(session, k -> new AtomicInteger());
      if (open.incrementAndGet() > maxChannelsPerSession) {
        open.decrementAndGet();
        refusedChannels.incrementAndGet();
        return null; // the client gets SSH_MSG_CHANNEL_OPEN_FAILURE
      }
      ChannelSession channel = new ChannelSession();
      channel.addChannelListener(new ChannelListener() {
        @Override
        public void channelClosed(Channel c, Throwable reason) {
          open.decrementAndGet();
        }
      });
      return channel;
    }
  }

  /** One exec request: {@code /bin/sh -c <command>}. */
  private static final class Sh implements Command, SignalListener {
    private final String command;
    private final TestSshd sshd;
    private InputStream in;
    private OutputStream out;
    private OutputStream err;
    private ExitCallback exit;
    private Process process;

    Sh(String command, TestSshd sshd) {
      this.command = command;
      this.sshd = sshd;
    }

    @Override
    public void setInputStream(InputStream in) {
      this.in = in;
    }

    @Override
    public void setOutputStream(OutputStream out) {
      this.out = out;
    }

    @Override
    public void setErrorStream(OutputStream err) {
      this.err = err;
    }

    @Override
    public void setExitCallback(ExitCallback callback) {
      this.exit = callback;
    }

    @Override
    public void start(ChannelSession channel, Environment env) throws IOException {
      sshd.commands.add(command);
      env.addSignalListener(this);
      ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", command);
      pb.environment().put("LC_ALL", "C.UTF-8");
      process = pb.start();
      sshd.processes.add(process);
      Thread input = Thread.ofVirtual().start(() -> copy(in, process.getOutputStream(), true));
      Thread stdout = Thread.ofVirtual().start(() -> copy(process.getInputStream(), out, false));
      Thread stderr = Thread.ofVirtual().start(() -> copy(process.getErrorStream(), err, false));
      Thread.ofVirtual().start(() -> {
        try {
          int code = process.waitFor();
          stdout.join();
          stderr.join();
          input.interrupt();
          exit.onExit(code);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });
    }

    private static void copy(InputStream from, OutputStream to, boolean closeAtEof) {
      byte[] buf = new byte[8192];
      try {
        for (int n; (n = from.read(buf)) >= 0; ) {
          to.write(buf, 0, n);
          to.flush();
        }
      } catch (IOException ignored) {
        // The channel or the process went away.
      } finally {
        if (closeAtEof) {
          try {
            to.close();
          } catch (IOException ignored) {
            // Nothing to close any more.
          }
        }
      }
    }

    @Override
    public void signal(Channel channel, Signal signal) {
      sshd.signals.add(signal.name());
      if (process == null) {
        return;
      }
      // sshd delivers it to the process group: the shell and what it started.
      if (signal == Signal.KILL) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
      } else if (signal == Signal.TERM) {
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
      }
    }

    @Override
    public void destroy(ChannelSession channel) {
      // A real sshd leaves the process running when the channel closes; the test server cleans up after itself.
      if (process != null && process.isAlive()) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
      }
    }
  }
}
