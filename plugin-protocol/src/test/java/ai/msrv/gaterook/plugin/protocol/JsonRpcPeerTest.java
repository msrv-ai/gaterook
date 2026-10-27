package ai.msrv.gaterook.plugin.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.plugin.protocol.host.WireSecret;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class JsonRpcPeerTest {

  private final MemoryPipe coreToHost = new MemoryPipe();
  private final MemoryPipe hostToCore = new MemoryPipe();
  private JsonRpcPeer core;
  private JsonRpcPeer host;

  @AfterEach
  void close() {
    if (core != null) {
      core.close();
    }
    if (host != null) {
      host.close();
    }
  }

  private void start(JsonRpcPeer.Handler coreHandler, JsonRpcPeer.Handler hostHandler, List<String> strays) {
    core = new JsonRpcPeer(hostToCore.in(), coreToHost.out(), coreHandler,
        JsonRpcPeer.Options.defaults().name("core").strayLine(strays::add)).start();
    host = new JsonRpcPeer(coreToHost.in(), hostToCore.out(), hostHandler,
        JsonRpcPeer.Options.defaults().name("host").hostIds(true)).start();
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static void sleep(Duration d) {
    try {
      Thread.sleep(d);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static ObjectNode obj(String key, String value) {
    ObjectNode o = GppJson.object();
    o.put(key, value);
    return o;
  }

  @Test
  void requestsGoBothWaysAndAnswersMayComeOutOfOrder() throws Exception {
    CountDownLatch slowMayAnswer = new CountDownLatch(1);
    start((m, p, c) -> obj("from", "core"), (m, p, c) -> {
      if (m.equals("slow")) {
        await(slowMayAnswer);
      }
      return obj("echo", p.path("v").asString());
    }, List.of());

    JsonRpcPeer.Call<JsonNode> slow = core.request("slow", obj("v", "1"));
    JsonRpcPeer.Call<JsonNode> fast = core.request("fast", obj("v", "2"));
    assertThat(fast.get(Duration.ofSeconds(5)).path("echo").asString()).isEqualTo("2");
    assertThat(slow.future().isDone()).isFalse();
    slowMayAnswer.countDown();
    assertThat(slow.get(Duration.ofSeconds(5)).path("echo").asString()).isEqualTo("1");

    JsonRpcPeer.Call<JsonNode> back = host.request("hello", null);
    assertThat(back.id()).isEqualTo("h1");
    assertThat(back.get(Duration.ofSeconds(5)).path("from").asString()).isEqualTo("core");
    assertThat(core.request("x", null).id()).isEqualTo(3L);
  }

  @Test
  void aHandlerMayCallBackInTheMiddleOfItsRequest() throws Exception {
    JsonRpcPeer[] hostRef = new JsonRpcPeer[1];
    start((m, p, c) -> obj("secret-ish", "value-for-" + p.path("field").asString()), (m, p, c) -> {
      JsonNode answer;
      try {
        answer = hostRef[0].request("secrets/get", obj("field", "apiToken")).get(Duration.ofSeconds(5));
      } catch (TimeoutException | InterruptedException e) {
        throw new IllegalStateException(e);
      }
      return obj("used", answer.path("secret-ish").asString());
    }, List.of());
    hostRef[0] = host;
    assertThat(core.request("connector/execute", null).get(Duration.ofSeconds(5)).path("used").asString())
        .isEqualTo("value-for-apiToken");
  }

  @Test
  void errorsArriveAsRpcExceptionsWithTheirCodeAndData() {
    start((m, p, c) -> null, (m, p, c) -> {
      if (m.equals("boom")) {
        throw new IllegalArgumentException("bad thing");
      }
      throw new RpcException(Gpp.SECRET_DENIED, "Secret access outside a call", obj("why", "late"));
    }, List.of());
    assertThatThrownBy(() -> core.request("denied", null).get(Duration.ofSeconds(5)))
        .isInstanceOfSatisfying(RpcException.class, e -> {
          assertThat(e.code()).isEqualTo(Gpp.SECRET_DENIED);
          assertThat(e.getMessage()).isEqualTo("Secret access outside a call");
          assertThat(e.data().path("why").asString()).isEqualTo("late");
        });
    assertThatThrownBy(() -> core.request("boom", null).get(Duration.ofSeconds(5)))
        .isInstanceOfSatisfying(RpcException.class, e -> {
          assertThat(e.code()).isEqualTo(Gpp.INTERNAL_ERROR);
          assertThat(e.data().path("type").asString()).isEqualTo(IllegalArgumentException.class.getName());
          assertThat(e.data().path("frames").size()).isBetween(1, 5);
        });
  }

  @Test
  void cancelInterruptsTheRequestAndRunsItsCallbacks() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    AtomicBoolean callback = new AtomicBoolean();
    AtomicBoolean interrupted = new AtomicBoolean();
    start((m, p, c) -> null, (m, p, c) -> {
      c.onCancel(() -> callback.set(true));
      started.countDown();
      try {
        Thread.sleep(Duration.ofMinutes(1));
      } catch (InterruptedException e) {
        interrupted.set(true);
        throw new RpcException(Gpp.CANCELLED, "Cancelled");
      }
      return null;
    }, List.of());
    JsonRpcPeer.Call<JsonNode> call = core.request("hang", null);
    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
    core.cancel(call, "deadline");
    assertThat(call.future().isCancelled()).isTrue();
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (!(interrupted.get() && callback.get()) && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertThat(interrupted).isTrue();
    assertThat(callback).isTrue();
  }

  @Test
  void linesThatArentJsonAreSkippedAndTheConversationGoesOn() throws Exception {
    List<String> strays = new CopyOnWriteArrayList<>();
    start((m, p, c) -> null, (m, p, c) -> obj("ok", "yes"), strays);
    hostToCore.out().write("Hello from native code\n[1,2,3]\n".getBytes(StandardCharsets.UTF_8));
    assertThat(core.request("x", null).get(Duration.ofSeconds(5)).path("ok").asString()).isEqualTo("yes");
    assertThat(strays).containsExactly("Hello from native code", "[1,2,3]");
  }

  @Test
  void theEndOfTheStreamFailsWhatWaits() throws Exception {
    start((m, p, c) -> null, (m, p, c) -> {
      sleep(Duration.ofMinutes(1));
      return null;
    }, List.of());
    JsonRpcPeer.Call<JsonNode> call = core.request("hang", null);
    hostToCore.out().close();
    assertThatThrownBy(() -> call.get(Duration.ofSeconds(5))).isInstanceOf(JsonRpcPeer.PeerClosedException.class);
    assertThat(core.closed().get(5, TimeUnit.SECONDS)).isNull();
    assertThatThrownBy(() -> core.request("after", null).get(Duration.ofSeconds(1)))
        .isInstanceOf(JsonRpcPeer.PeerClosedException.class);
  }

  @Test
  void aRequestReadJustBeforeEofIsCountedSoItsAnswerIsntLost() throws Exception {
    // The race of report 16 §1: the host sees EOF right after a request; it must wait for that request.
    CountDownLatch release = new CountDownLatch(1);
    start((m, p, c) -> null, (m, p, c) -> {
      await(release);
      return obj("done", "yes");
    }, List.of());
    JsonRpcPeer.Call<JsonNode> call = core.request("work", null);
    core.closeOutput();
    assertThat(host.closed().get(5, TimeUnit.SECONDS)).isNull();
    assertThat(host.inFlight()).isEqualTo(1);
    assertThat(host.awaitIdle(Duration.ofMillis(100))).isFalse();
    release.countDown();
    assertThat(host.awaitIdle(Duration.ofSeconds(5))).isTrue();
    assertThat(call.get(Duration.ofSeconds(5)).path("done").asString()).isEqualTo("yes");
  }

  @Test
  void aSecretCrossesAsCharsAndTheSendersCopyIsZeroed() throws Exception {
    char[] value = "s3cr3t-ATATT3xFfGF0".toCharArray();
    start((m, p, c) -> SecretWire.text(value, "v3", null), (m, p, c) -> null, List.of());
    JsonRpcPeer.Call<WireSecret> call = host.request(Gpp.SECRETS_GET, obj("field", "/apiToken"),
        SecretWire.secretDecoder());
    try (WireSecret s = call.get(Duration.ofSeconds(5))) {
      assertThat(new String(s.chars())).isEqualTo("s3cr3t-ATATT3xFfGF0");
      assertThat(s.version()).isEqualTo("v3");
      assertThat(s.toString()).isEqualTo("‹secret›");
    }
    assertThat(new String(value)).isEqualTo("\0".repeat(value.length));
  }

  @Test
  void aBinarySecretTravelsAsBase64() throws Exception {
    byte[] value = {0, 1, 2, (byte) 0xff, (byte) 0xfe};
    start((m, p, c) -> SecretWire.binary(value.clone(), "v1", null), (m, p, c) -> null, List.of());
    try (WireSecret s = host.request(Gpp.SECRETS_GET, null, SecretWire.secretDecoder()).get(Duration.ofSeconds(5))) {
      assertThat(s.bytes()).containsExactly(value);
      assertThat(s.chars()).isEmpty();
    }
  }

  /**
   * The core bounds what a host can make it do at once (review 2026-10-27): a host runs a plugin's code and could
   * flood the core with requests, each a thread of its own. Over the bound, the answer is {@code -32012} at once.
   */
  @Test
  void requestsOverTheBoundAreBusyAtOnce() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    core = new JsonRpcPeer(hostToCore.in(), coreToHost.out(), (m, p, c) -> {
      await(release);
      return obj("ok", "yes");
    }, JsonRpcPeer.Options.defaults().name("core").maxIncoming(2)).start();
    host = new JsonRpcPeer(coreToHost.in(), hostToCore.out(), (m, p, c) -> null,
        JsonRpcPeer.Options.defaults().name("host").hostIds(true)).start();
    JsonRpcPeer.Call<JsonNode> first = host.request("secrets/get", null);
    JsonRpcPeer.Call<JsonNode> second = host.request("secrets/get", null);
    for (int i = 0; i < 50 && core.inFlight() < 2; i++) {
      sleep(Duration.ofMillis(20));
    }
    assertThatThrownBy(() -> host.request("secrets/get", null).get(Duration.ofSeconds(2)))
        .isInstanceOfSatisfying(RpcException.class, e -> assertThat(e.code()).isEqualTo(Gpp.HOST_BUSY));
    release.countDown();
    assertThat(first.get(Duration.ofSeconds(2)).path("ok").asString()).isEqualTo("yes");
    assertThat(second.get(Duration.ofSeconds(2)).path("ok").asString()).isEqualTo("yes");
    assertThat(core.awaitIdle(Duration.ofSeconds(2))).isTrue();
    assertThat(host.request("secrets/get", null).get(Duration.ofSeconds(2)).path("ok").asString()).isEqualTo("yes");
  }

  @Test
  void aFrameOverTheLimitEndsTheConversation() throws IOException, Exception {
    core = new JsonRpcPeer(hostToCore.in(), coreToHost.out(), (m, p, c) -> null,
        JsonRpcPeer.Options.defaults().maxFrameBytes(100)).start();
    JsonRpcPeer.Call<JsonNode> call = core.request("x", null);
    hostToCore.out().write(("{\"x\":\"" + "a".repeat(200) + "\"}\n").getBytes(StandardCharsets.UTF_8));
    assertThat(core.closed().get(5, TimeUnit.SECONDS)).isInstanceOf(FrameReader.FrameTooLarge.class);
    assertThatThrownBy(() -> call.get(Duration.ofSeconds(1))).isInstanceOf(JsonRpcPeer.PeerClosedException.class);
  }

  @Test
  void notificationsAreHandledInOrderBeforeTheAnswerThatFollowsThem() throws Exception {
    List<String> seen = new CopyOnWriteArrayList<>();
    JsonRpcPeer.Handler coreHandler = new JsonRpcPeer.Handler() {
      @Override
      public Object handle(String method, JsonNode params, JsonRpcPeer.Incoming call) {
        return null;
      }

      @Override
      public void notification(String method, JsonNode params) {
        seen.add(params.path("value").asString());
      }
    };
    JsonRpcPeer[] hostRef = new JsonRpcPeer[1];
    start(coreHandler, (m, p, c) -> {
      for (int i = 0; i < 50; i++) {
        hostRef[0].notify(Gpp.TAINT_ADD, obj("value", "t" + i));
      }
      return obj("answer", "now");
    }, List.of());
    hostRef[0] = host;
    core.request("x", null).get(Duration.ofSeconds(5));
    assertThat(seen).hasSize(50).startsWith("t0").endsWith("t49");
  }
}
