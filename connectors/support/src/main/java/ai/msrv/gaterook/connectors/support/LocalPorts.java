package ai.msrv.gaterook.connectors.support;

import ai.msrv.gaterook.connector.api.Suggestions;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Servers listening on this computer, for the setup forms' local suggestions ({@code Connector.suggest}): a TCP
 * connection to the loopback address and nothing sent, so a database in Docker on its usual port shows up as
 * {@code localhost} under Host. Never a value filled in: a person setting up a remote server picks it only on purpose.
 */
public final class LocalPorts {

  /** Long enough for a loopback connect, short enough for a form that asks while the person types. */
  public static final Duration TIMEOUT = Duration.ofMillis(150);

  private LocalPorts() {}

  /** Whether something accepts a TCP connection on {@code 127.0.0.1:port}. */
  public static boolean listening(int port) {
    if (port < 1 || port > 65535) {
      return false;
    }
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), (int) TIMEOUT.toMillis());
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  /**
   * {@code localhost} for {@code pointer} when a server answers on one of {@code ports}, with the port in its detail
   * ("A server answers on port 5432"); the ports that answered, for a form with a Port field.
   */
  public static List<Integer> offer(Suggestions.Builder out, String pointer, String what, int... ports) {
    List<Integer> found = new ArrayList<>();
    for (int port : ports) {
      if (listening(port)) {
        found.add(port);
      }
    }
    if (!found.isEmpty()) {
      out.options(pointer, "this computer", List.of(new Suggestions.Option("localhost", null,
          what + " answers on port " + found.get(0))));
    }
    return found;
  }
}
