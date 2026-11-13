package gaterook.plugins.fixtures;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.ExecContext;
import com.gaterook.connector.api.SecretResolver;
import com.gaterook.connector.api.TestResult;
import com.gaterook.connector.api.ToolCall;
import com.gaterook.connector.api.ToolResult;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.node.ObjectNode;

/**
 * A plugin with a TCP client of its own, as a JDBC driver has one: {@code java.net.Socket}s that neither the egress
 * client nor the resolver hook sees. It declares {@code {connection.host}:{connection.port}} in {@code access.tcp},
 * so the host's TCP gate lets those sockets reach that destination and nothing else. {@code tcp_probe} reports for
 * each attempt the line the server answered or the error the socket ran into.
 *
 * <ul>
 *   <li>{@code granted}: the connection's host and port, from the call's thread;</li>
 *   <li>{@code fromThread}: the same from a thread the call starts;</li>
 *   <li>{@code otherPort}: another port of the same host, which the grant doesn't name;</li>
 *   <li>{@code corePort}: a port of the core on this computer;</li>
 *   <li>{@code selector}: the JVM's proxy selector, the gate's own.</li>
 * </ul>
 * A failed connection test is the TCP error, which the host explains by what the gate refused.
 */
public final class TcpClientConnector extends Fixture {

  @Override
  public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    String host = cfg.string("/host");
    int port = cfg.integer("/port", 5000);
    ObjectNode out = NODES.objectNode();
    out.put("granted", exchange(host, port));
    AtomicReference<String> fromThread = new AtomicReference<>();
    Thread t = Thread.ofPlatform().start(() -> fromThread.set(exchange(host, port)));
    try {
      t.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw ConnectorException.timeout("Cancelled");
    }
    out.put("fromThread", fromThread.get());
    int other = call.arguments().path("otherPort").asInt(0);
    if (other > 0) {
      out.put("otherPort", exchange(host, other));
    }
    int core = call.arguments().path("corePort").asInt(0);
    if (core > 0) {
      out.put("corePort", exchange("127.0.0.1", core));
    }
    ProxySelector selector = ProxySelector.getDefault();
    out.put("selector", selector == null ? "none" : selector.getClass().getName());
    return ToolResult.json(out, out.toString());
  }

  @Override
  public TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    String answer = exchange(cfg.string("/host"), cfg.integer("/port", 5000));
    return answer.startsWith("answered ") ? TestResult.success(1, "1.0.0", "tcp", null)
        : TestResult.failure("tcp.unreachable", "Can't reach the server: " + answer, null);
  }

  /** One line out and one back over a fresh socket, as a driver's handshake would go. */
  static String exchange(String host, int port) {
    try (Socket s = new Socket()) {
      s.connect(new InetSocketAddress(host, port), 5_000);
      s.setSoTimeout(5_000);
      OutputStream out = s.getOutputStream();
      out.write("hello\n".getBytes(StandardCharsets.UTF_8));
      out.flush();
      String line = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)).readLine();
      return "answered " + line;
    } catch (IOException | RuntimeException e) {
      return e.getClass().getSimpleName() + ": " + e.getMessage();
    }
  }
}
