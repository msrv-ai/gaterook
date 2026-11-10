package ai.msrv.gaterook.plugin.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * A peer whose other side stops reading (a plugin host that no longer reads its input): with a write timeout no
 * sender waits longer than it, later frames fail at once, and closing doesn't wait for the stuck frame. Frames still
 * keep their order and conversations work as without a writer.
 */
class JsonRpcPeerWriteTimeoutTest {

  private static final Duration TIMEOUT = Duration.ofMillis(300);

  /** The input of a process that stopped reading: a write waits until the process ends, then fails. */
  private static final class Unread extends OutputStream {
    private final CountDownLatch ended = new CountDownLatch(1);
    private final AtomicInteger writes = new AtomicInteger();

    @Override
    public void write(int b) throws IOException {
      write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      writes.incrementAndGet();
      try {
        ended.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      throw new IOException("Broken pipe");
    }

    void end() {
      ended.countDown();
    }
  }

  private static JsonRpcPeer peer(OutputStream out) {
    return new JsonRpcPeer(new ByteArrayInputStream(new byte[0]), out, (method, params, call) -> null,
        JsonRpcPeer.Options.defaults().name("core").writeTimeout(TIMEOUT));
  }

  @Test
  void aSenderWaitsNoLongerThanTheWriteTimeoutWhenTheOtherSideStopsReading() {
    Unread out = new Unread();
    JsonRpcPeer peer = peer(out);
    try {
      JsonRpcPeer.Call<JsonNode> first = assertTimeoutPreemptively(Duration.ofSeconds(5),
          () -> peer.request(Gpp.HOST_PING, GppJson.object()));
      assertThatThrownBy(() -> first.get(Duration.ofSeconds(1))).isInstanceOf(JsonRpcPeer.PeerClosedException.class)
          .hasMessageContaining("no longer listens");

      // Nothing more reaches the stream: the other side isn't listening, and a sender learns it at once.
      JsonRpcPeer.Call<JsonNode> second = assertTimeoutPreemptively(Duration.ofSeconds(1),
          () -> peer.request(Gpp.HOST_PING, GppJson.object()));
      assertThatThrownBy(() -> second.get(Duration.ofMillis(100)))
          .isInstanceOf(JsonRpcPeer.PeerClosedException.class);
      assertTimeoutPreemptively(Duration.ofSeconds(1), () -> peer.notify("host/log", GppJson.object()));
      assertThat(out.writes.get()).as("frames handed to the stream").isEqualTo(1);

      assertTimeoutPreemptively(Duration.ofSeconds(5), peer::close);
    } finally {
      out.end();
    }
  }

  @Test
  void anInterruptDoesNotCutAWriteShort() throws Exception {
    Unread out = new Unread();
    JsonRpcPeer peer = peer(out);
    try {
      Thread.currentThread().interrupt();
      long started = System.nanoTime();
      JsonRpcPeer.Call<JsonNode> call = peer.request(Gpp.HOST_PING, GppJson.object());
      long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

      assertThat(Thread.interrupted()).as("the interrupt is kept for the caller").isTrue();
      assertThat(waited).as("the frame was given its time").isGreaterThanOrEqualTo(TIMEOUT.toMillis() - 50);
      assertThatThrownBy(() -> call.get(Duration.ofMillis(100))).isInstanceOf(JsonRpcPeer.PeerClosedException.class);
    } finally {
      Thread.interrupted();
      out.end();
      peer.close();
    }
  }

  @Test
  void withAWriterFramesKeepTheirOrderAndAnswersArrive() throws Exception {
    MemoryPipe coreToHost = new MemoryPipe();
    MemoryPipe hostToCore = new MemoryPipe();
    List<String> seen = new CopyOnWriteArrayList<>();
    JsonRpcPeer host = new JsonRpcPeer(coreToHost.in(), hostToCore.out(), new JsonRpcPeer.Handler() {
      @Override
      public Object handle(String method, JsonNode params, JsonRpcPeer.Incoming call) {
        return GppJson.object().put("echo", params.path("n").asInt());
      }

      @Override
      public void notification(String method, JsonNode params) {
        seen.add(params.path("n").asString());
      }
    }, JsonRpcPeer.Options.defaults().name("host").hostIds(true)).start();
    JsonRpcPeer core = new JsonRpcPeer(hostToCore.in(), coreToHost.out(), (method, params, call) -> null,
        JsonRpcPeer.Options.defaults().name("core").writeTimeout(Duration.ofSeconds(5))).start();
    try {
      List<String> sent = new ArrayList<>();
      for (int i = 0; i < 200; i++) {
        core.notify("host/log", GppJson.object().put("n", String.valueOf(i)));
        sent.add(String.valueOf(i));
      }
      JsonNode answer = core.request("connector/test", GppJson.object().put("n", 7)).get(Duration.ofSeconds(5));

      assertThat(answer.path("echo").asInt()).isEqualTo(7);
      // Notifications are handled on the host's reader thread in order, and before the answer that follows them.
      assertThat(seen).containsExactlyElementsOf(sent);
    } finally {
      core.close();
      host.close();
    }
  }
}
