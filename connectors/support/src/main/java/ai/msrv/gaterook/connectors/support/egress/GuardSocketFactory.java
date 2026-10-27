package ai.msrv.gaterook.connectors.support.egress;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.Objects;
import javax.net.SocketFactory;

/**
 * The authoritative layer of the egress guard (report 10 §1, §7.2): every socket it makes judges the address it is
 * about to connect to, right before {@code connect}. Whatever the name resolved to earlier, whichever hop of a redirect
 * this is, whether HTTP/2 wants to coalesce onto an existing connection or the URL held an IP literal that never went
 * through {@link GuardDns}: the TCP connection goes only where the {@link AddressPolicy} allows.
 *
 * <p>TLS is layered on top of these sockets by OkHttp ({@code SSLSocketFactory.createSocket(socket, …)}), so the check
 * covers HTTPS too. Unresolved addresses are refused: resolution must have gone through the guard.
 */
public final class GuardSocketFactory extends SocketFactory {

  private final AddressPolicy policy;

  public GuardSocketFactory(AddressPolicy policy) {
    this.policy = Objects.requireNonNull(policy, "policy");
  }

  @Override
  public Socket createSocket() {
    return new GuardedSocket(policy);
  }

  @Override
  public Socket createSocket(String host, int port) throws IOException {
    Socket socket = createSocket();
    connectGuarded(socket, host, port);
    return socket;
  }

  @Override
  public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
    Socket socket = createSocket();
    socket.bind(new InetSocketAddress(localHost, localPort));
    connectGuarded(socket, host, port);
    return socket;
  }

  @Override
  public Socket createSocket(InetAddress host, int port) throws IOException {
    Socket socket = createSocket();
    socket.connect(new InetSocketAddress(host, port));
    return socket;
  }

  @Override
  public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
      throws IOException {
    Socket socket = createSocket();
    socket.bind(new InetSocketAddress(localAddress, localPort));
    socket.connect(new InetSocketAddress(address, port));
    return socket;
  }

  /** A host name in these overloads would bypass {@link GuardDns}: only literals are accepted here. */
  private void connectGuarded(Socket socket, String host, int port) throws IOException {
    InetAddress literal = AddressPolicy.canonicalIp(host).orElse(null);
    if (literal == null) {
      socket.close();
      throw new ConnectDeniedException(host, new AddressPolicy.Verdict(AddressPolicy.Reason.UNRESOLVED, null,
          "Connections by name must be resolved through the egress guard: " + host));
    }
    socket.connect(new InetSocketAddress(literal, port));
  }

  /** A plain socket whose {@code connect} asks the policy first; no proxy, ever. */
  private static final class GuardedSocket extends Socket {

    private final AddressPolicy policy;

    GuardedSocket(AddressPolicy policy) {
      super(Proxy.NO_PROXY);
      this.policy = policy;
    }

    @Override
    public void connect(SocketAddress endpoint, int timeout) throws IOException {
      if (!(endpoint instanceof InetSocketAddress target) || target.isUnresolved()) {
        throw new ConnectDeniedException(String.valueOf(endpoint), new AddressPolicy.Verdict(
            AddressPolicy.Reason.UNRESOLVED, null, "An unresolved address can't be checked: " + endpoint));
      }
      AddressPolicy.Verdict verdict = policy.check(target.getHostString(), target.getAddress(), target.getPort());
      if (!verdict.allowed()) {
        throw new ConnectDeniedException(target.getHostString(), verdict);
      }
      super.connect(endpoint, timeout);
    }
  }
}
