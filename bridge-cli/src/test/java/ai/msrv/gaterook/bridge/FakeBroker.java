package ai.msrv.gaterook.bridge;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** A broker on a Unix socket in a test home: records requests, answers with {@code answer}. */
final class FakeBroker implements AutoCloseable {

  final Path home;
  final Path socket;
  final List<Object> requests = new CopyOnWriteArrayList<>();
  private final ServerSocketChannel server;
  private final Thread thread;

  FakeBroker(Path home, Function<Object, String> answer) throws IOException {
    this.home = home;
    Files.createDirectories(home.resolve("run"));
    this.socket = home.resolve("run").resolve("test.sock");
    Files.deleteIfExists(socket);
    server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    server.bind(UnixDomainSocketAddress.of(socket));
    Files.writeString(home.resolve("run").resolve("broker.json"), "{\"v\":1,\"path\":" + Json.quote(socket.toString())
        + "}");
    thread = Thread.ofVirtual().start(() -> {
      while (server.isOpen()) {
        try (SocketChannel ch = server.accept()) {
          ByteBuffer buf = ByteBuffer.allocate(8192);
          StringBuilder line = new StringBuilder();
          while (line.indexOf("\n") < 0 && ch.read(buf) > 0) {
            buf.flip();
            line.append(StandardCharsets.UTF_8.decode(buf));
            buf.clear();
          }
          Object request = Json.parse(line.substring(0, line.indexOf("\n")));
          requests.add(request);
          ch.write(ByteBuffer.wrap((answer.apply(request) + "\n").getBytes(StandardCharsets.UTF_8)));
        } catch (IOException | RuntimeException e) {
          if (!server.isOpen()) {
            return;
          }
        }
      }
    });
  }

  @Override
  public void close() throws IOException {
    server.close();
    thread.interrupt();
    Files.deleteIfExists(socket);
  }
}
