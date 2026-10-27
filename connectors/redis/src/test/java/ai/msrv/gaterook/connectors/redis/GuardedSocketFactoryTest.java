package ai.msrv.gaterook.connectors.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Connection;
import redis.clients.jedis.JedisSocketFactory;
import redis.clients.jedis.exceptions.JedisConnectionException;

/** The sockets of the connector's Jedis connections. */
class GuardedSocketFactoryTest {

  @Test
  void aCutConnectionIsNeverOpenedAgainWithoutItsSignIn() throws Exception {
    try (ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
      AtomicInteger opened = new AtomicInteger();
      JedisSocketFactory sockets = () -> {
        opened.incrementAndGet();
        try {
          return new Socket(server.getInetAddress(), server.getLocalPort());
        } catch (IOException e) {
          throw new JedisConnectionException(e);
        }
      };
      Connection c = new Connection(GuardedSocketFactory.oneSocket(sockets));
      c.connect();
      // What a cancelled call does to its socket (RedisSession.abort); a command racing it must not go out on a new
      // socket that Jedis would open without HELLO … AUTH and SELECT.
      c.forceDisconnect();

      assertThatThrownBy(() -> c.sendCommand(Cmd.PING)).isInstanceOf(JedisConnectionException.class)
          .hasMessageContaining("isn't reopened");
      assertThat(opened).hasValue(1);
    }
  }
}
