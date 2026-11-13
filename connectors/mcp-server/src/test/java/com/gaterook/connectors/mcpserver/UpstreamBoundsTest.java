package com.gaterook.connectors.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * What a server can make the gateway's client hold (ARCHITECTURE.md §5.4, audit M-A5): the entries of a list over all
 * its pages, the notifications that wait for the session's thread, the server's own requests and their answers. Each
 * has a bound real servers never reach, and a server that keeps sending past one loses its session with the reason in
 * its log instead of filling the core's memory.
 */
class UpstreamBoundsTest {

  /** A channel in memory: what the client sends is kept, and {@code answer} may answer it at once. */
  private static final class FakeChannel implements Channel {
    volatile Sink sink;
    final List<ObjectNode> sent = new CopyOnWriteArrayList<>();
    final List<CompletableFuture<Void>> exchanges = new CopyOnWriteArrayList<>();
    volatile Function<ObjectNode, ObjectNode> answer = m -> null;
    volatile boolean slowExchanges;
    volatile String abandoned;

    @Override
    public void open(Sink sink) {
      this.sink = sink;
    }

    @Override
    public Exchange send(ObjectNode message, Map<String, String> headers) {
      sent.add(message);
      CompletableFuture<Void> done = slowExchanges ? new CompletableFuture<>() : CompletableFuture.completedFuture(null);
      exchanges.add(done);
      ObjectNode reply = Rpc.isRequest(message) ? answer.apply(message) : null;
      if (reply != null) {
        sink.message(reply, null);
      }
      return new Exchange() {
        @Override
        public CompletableFuture<Void> done() {
          return done;
        }

        @Override
        public void abort() {}
      };
    }

    @Override
    public boolean answersInExchange() {
      return false;
    }

    @Override
    public boolean alive() {
      return abandoned == null;
    }

    @Override
    public void close() {}

    @Override
    public void abandon(String reason) {
      abandoned = reason;
    }

    List<ObjectNode> sent(String method) {
      return sent.stream().filter(m -> method.equals(Texts.str(m.get("method"), null))).toList();
    }

    List<ObjectNode> replies() {
      return sent.stream().filter(Rpc::isResponse).toList();
    }
  }

  /** What the session hears, and a gate the session's thread waits at while a test fills its queue. */
  private static final class Heard implements UpstreamClient.Events {
    final List<ObjectNode> logs = new CopyOnWriteArrayList<>();
    final List<String> notes = new CopyOnWriteArrayList<>();
    final AtomicInteger toolsChanged = new AtomicInteger();
    final AtomicInteger resourcesChanged = new AtomicInteger();
    final List<String> updated = new CopyOnWriteArrayList<>();
    final CountDownLatch busy = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);

    @Override
    public void toolsChanged() {
      if (toolsChanged.incrementAndGet() == 1) {
        busy.countDown();
        try {
          release.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }

    @Override
    public void resourcesChanged() {
      resourcesChanged.incrementAndGet();
    }

    @Override
    public void resourceUpdated(String uri) {
      updated.add(uri);
    }

    @Override
    public void log(ObjectNode params) {
      logs.add(params);
    }

    @Override
    public void note(String text) {
      notes.add(text);
    }
  }

  private static UpstreamClient client(FakeChannel channel, Heard heard) throws Exception {
    channel.answer = m -> "initialize".equals(Texts.str(m.get("method"), null))
        ? Rpc.result(m.get("id"), Fixtures.object("{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{}}"))
        : null;
    UpstreamClient client = new UpstreamClient(channel, JsonNodeFactory.instance.objectNode().put("name", "gaterook"),
        "fake-server", heard);
    client.connect(UpstreamClient.Era.LEGACY, Duration.ofSeconds(5), Duration.ofSeconds(5));
    return client;
  }

  private static ObjectNode notification(String method, ObjectNode params) {
    return Rpc.notification(method, params);
  }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > end) {
        throw new AssertionError("Condition not met in 10 s");
      }
      Thread.sleep(20);
    }
  }

  @Test
  void aListKeepsNoMoreOverAllItsPagesThanOneMessageMayCarry() throws Exception {
    FakeChannel channel = new FakeChannel();
    Heard heard = new Heard();
    UpstreamClient client = client(channel, heard);
    String description = "d".repeat(64 * 1024);
    AtomicInteger pages = new AtomicInteger();
    channel.answer = m -> {
      if (!"tools/list".equals(Texts.str(m.get("method"), null))) {
        return null;
      }
      // Ten tools of 64 KB a page, and always another page: 100 pages would be over 60 MB.
      int page = pages.getAndIncrement();
      ObjectNode result = JsonNodeFactory.instance.objectNode();
      ArrayNode tools = result.putArray("tools");
      for (int i = 0; i < 10; i++) {
        tools.addObject().put("name", "tool_" + page + "_" + i).put("description", description)
            .putObject("inputSchema").put("type", "object");
      }
      result.put("nextCursor", "page-" + (page + 1));
      return Rpc.result(m.get("id"), result);
    };

    List<ObjectNode> listed = client.list("tools/list", "tools", Duration.ofSeconds(20), Session.MAX_TOOLS);

    long bytes = 0;
    for (ObjectNode t : listed) {
      bytes += Texts.JSON.writeValueAsBytes(t).length;
    }
    assertThat(bytes).isLessThanOrEqualTo(UpstreamClient.MAX_LIST_BYTES);
    assertThat(listed).hasSizeGreaterThan(50).hasSizeLessThan(70);
    assertThat(pages.get()).as("no page is asked for once the bound is reached").isLessThan(10);
    assertThat(heard.notes).singleElement().satisfies(n -> assertThat(n)
        .isEqualTo("tools/list: fake-server listed more than 4 MB; GateRook keeps the first " + listed.size()
            + " entries."));
  }

  @Test
  void aLogMessageIsWrittenWhereItIsReadAndCutToWhatTheLogKeeps() throws Exception {
    FakeChannel channel = new FakeChannel();
    Heard heard = new Heard();
    client(channel, heard);
    ObjectNode params = JsonNodeFactory.instance.objectNode().put("level", "info").put("logger", "big");
    params.put("data", "x".repeat(1024 * 1024));

    channel.sink.message(notification("notifications/message", params), null);

    // Heard before the message was handed over: nothing of it waits for the session's thread.
    assertThat(heard.logs).singleElement().satisfies(l -> {
      assertThat(l.path("level").asString()).isEqualTo("info");
      assertThat(l.path("logger").asString()).isEqualTo("big");
      assertThat(l.path("data").asString()).hasSize(UpstreamClient.MAX_LOG + 1).endsWith("…");
    });
    ObjectNode structured = JsonNodeFactory.instance.objectNode().put("level", "warning");
    structured.putObject("data").put("detail", "y".repeat(10_000));
    channel.sink.message(notification("notifications/message", structured), null);
    assertThat(heard.logs.get(1).path("data").asString()).startsWith("{\"detail\":\"yyy")
        .hasSize(UpstreamClient.MAX_LOG + 1);
  }

  @Test
  void listChangesAndUpdatesThatComeWhileOneWaitsAddNothing() throws Exception {
    FakeChannel channel = new FakeChannel();
    Heard heard = new Heard();
    client(channel, heard);
    channel.sink.message(notification("notifications/tools/list_changed", null), null);
    assertThat(heard.busy.await(10, TimeUnit.SECONDS)).isTrue();

    // While the session's thread lists the tools again, the server keeps saying what changed.
    for (int i = 0; i < 5_000; i++) {
      channel.sink.message(notification("notifications/tools/list_changed", null), null);
      channel.sink.message(notification("notifications/resources/list_changed", null), null);
      channel.sink.message(notification("notifications/resources/updated", JsonNodeFactory.instance.objectNode()
          .put("uri", "notes://welcome")), null);
    }
    heard.release.countDown();

    await(() -> heard.toolsChanged.get() == 2 && heard.resourcesChanged.get() == 1 && heard.updated.size() == 1);
    Thread.sleep(200);
    assertThat(heard.toolsChanged.get()).isEqualTo(2);
    assertThat(heard.resourcesChanged.get()).isEqualTo(1);
    assertThat(heard.updated).containsExactly("notes://welcome");
    assertThat(channel.abandoned).isNull();

    // Once handled, the next change is heard again.
    channel.sink.message(notification("notifications/resources/list_changed", null), null);
    await(() -> heard.resourcesChanged.get() == 2);
  }

  @Test
  void aServerThatSendsUpdatesFasterThanTheyAreTakenInLosesItsSession() throws Exception {
    FakeChannel channel = new FakeChannel();
    Heard heard = new Heard();
    UpstreamClient client = client(channel, heard);
    channel.sink.message(notification("notifications/tools/list_changed", null), null);
    assertThat(heard.busy.await(10, TimeUnit.SECONDS)).isTrue();

    for (int i = 0; i <= UpstreamClient.MAX_QUEUED + 10; i++) {
      channel.sink.message(notification("notifications/resources/updated", JsonNodeFactory.instance.objectNode()
          .put("uri", "notes://" + i)), null);
    }
    heard.release.countDown();

    String reason = "fake-server sent more than " + UpstreamClient.MAX_QUEUED + " list changes and resource updates "
        + "faster than GateRook could take them in.";
    await(() -> channel.abandoned != null);
    assertThat(channel.abandoned).isEqualTo(reason);
    assertThat(client.closed()).isTrue();
    assertThat(heard.notes).contains("GateRook ended the session: " + reason);
    // A session given up hears nothing more.
    int heardBefore = heard.updated.size();
    channel.sink.message(notification("notifications/resources/updated", JsonNodeFactory.instance.objectNode()
        .put("uri", "notes://late")), null);
    Thread.sleep(200);
    assertThat(heard.updated).hasSizeLessThanOrEqualTo(heardBefore).doesNotContain("notes://late");
  }

  @Test
  void anUpdateOfAUriLongerThanAResourceMayHaveIsNotPassedOn() throws Exception {
    FakeChannel channel = new FakeChannel();
    Heard heard = new Heard();
    client(channel, heard);

    channel.sink.message(notification("notifications/resources/updated", JsonNodeFactory.instance.objectNode()
        .put("uri", "notes://" + "n".repeat(UpstreamClient.MAX_URI))), null);
    channel.sink.message(notification("notifications/resources/updated", JsonNodeFactory.instance.objectNode()
        .put("uri", "notes://short")), null);

    await(() -> heard.updated.size() == 1);
    assertThat(heard.updated).containsExactly("notes://short");
  }

  @Test
  void theServersRequestsAreAnsweredSixteenAtATime() throws Exception {
    FakeChannel channel = new FakeChannel();
    Heard heard = new Heard();
    client(channel, heard);
    // Over HTTP an answer is a POST, which takes until the server has read it.
    channel.slowExchanges = true;
    int before = channel.exchanges.size();

    for (int i = 0; i < 40; i++) {
      channel.sink.message(Rpc.request(1_000 + i, "ping", null), null);
    }

    assertThat(channel.replies()).hasSize(UpstreamClient.MAX_ANSWERS);
    assertThat(heard.notes).singleElement().asString().contains("sent more than " + UpstreamClient.MAX_ANSWERS
        + " requests at once");
    // Once the server took the answers, its next request is answered again.
    new ArrayList<>(channel.exchanges.subList(before, channel.exchanges.size())).forEach(f -> f.complete(null));
    channel.slowExchanges = false;
    channel.sink.message(Rpc.request(2_000, "ping", null), null);
    assertThat(channel.replies()).hasSize(UpstreamClient.MAX_ANSWERS + 1);
    assertThat(channel.replies().getLast().path("id").asInt()).isEqualTo(2_000);
  }

  @Test
  void aRequestWhoseIdWouldMakeTheAnswerLargeGetsNone() throws Exception {
    FakeChannel channel = new FakeChannel();
    Heard heard = new Heard();
    client(channel, heard);
    ObjectNode longId = Rpc.request(0, "ping", null);
    longId.put("id", "i".repeat(UpstreamClient.MAX_ID + 1));
    ObjectNode objectId = Rpc.request(0, "ping", null);
    objectId.putObject("id").put("anything", "x");

    channel.sink.message(longId, null);
    channel.sink.message(objectId, null);
    assertThat(channel.replies()).isEmpty();

    ObjectNode shortId = Rpc.request(0, "sampling/createMessage", null);
    shortId.put("id", "i".repeat(UpstreamClient.MAX_ID));
    channel.sink.message(shortId, null);
    assertThat(channel.replies()).singleElement().satisfies(r -> {
      assertThat(r.path("id").asString()).hasSize(UpstreamClient.MAX_ID);
      assertThat(r.path("error").path("code").asInt()).isEqualTo(Rpc.METHOD_NOT_FOUND);
    });
  }
}
