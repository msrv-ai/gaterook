package com.gaterook.core.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gaterook.connector.api.ToolResult;
import com.gaterook.core.approvals.ApprovalService;
import com.gaterook.core.platform.CallTracing;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * How a task ends (ARCHITECTURE.md §5.6): a cancel that declines the request the call waits for ends the task
 * cancelled, though the declined request wakes the call at once and its refusal arrives first; a request a person
 * approved meanwhile isn't cancelled, and the task gets what ran.
 */
class CallTasksTest {

  final ApprovalService approvals = mock(ApprovalService.class);
  final CallTasks tasks = new CallTasks(approvals, new CallTracing(null, null), Clock.systemUTC());

  /** A call that waits for request ap_1, and answers what it came to as soon as {@code decided} opens. */
  private CallTasks.Task waiting(CountDownLatch waits, CountDownLatch decided, CountDownLatch answered) {
    return tasks.start(ApprovalDeliveryTest.request(false, "auto", 15), CallTasks.DEFAULT_TTL, own -> {
      own.task().waitsFor("ap_1", "Waiting for a person to approve it in GateRook: request ap_1.");
      waits.countDown();
      try {
        decided.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      ObjectNode refusal = JsonNodeFactory.instance.objectNode();
      refusal.putArray("content").addObject().put("type", "text").put("text", "Rejected. Nothing ran.");
      try {
        return refusal;
      } finally {
        answered.countDown();
      }
    }).orElseThrow();
  }

  @Test
  void aCancelThatDeclinesTheRequestEndsTheTaskCancelledThoughTheRefusalComesFirst() throws Exception {
    CountDownLatch waits = new CountDownLatch(1);
    CountDownLatch decided = new CountDownLatch(1);
    CountDownLatch answered = new CountDownLatch(1);
    CallTasks.Task task = waiting(waits, decided, answered);
    assertThat(waits.await(5, TimeUnit.SECONDS)).isTrue();
    when(approvals.find("ap_1")).thenReturn(Optional.of(ApprovalDeliveryTest.view("pending", null)));
    when(approvals.declineInClient("ap_1")).thenAnswer(i -> {
      // The declined request wakes the call, whose answer comes before the decline returns.
      decided.countDown();
      assertThat(answered.await(5, TimeUnit.SECONDS)).isTrue();
      Thread.sleep(100);
      return ApprovalDeliveryTest.view("rejected", null);
    });

    tasks.cancel(task);

    assertThat(tasks.legacy(task).path("status").asString()).isEqualTo("cancelled");
    assertThat(tasks.legacy(task).path("statusMessage").asString()).isEqualTo("Cancelled by the client.");
    assertThatThrownBy(() -> tasks.cancel(task)).isInstanceOfSatisfying(McpException.class,
        e -> assertThat(e.getMessage()).isEqualTo("Task " + task.id() + " is cancelled already."));
  }

  @Test
  void aRequestApprovedMeanwhileIsNotCancelledAndTheTaskGetsWhatRan() throws Exception {
    CountDownLatch waits = new CountDownLatch(1);
    CountDownLatch decided = new CountDownLatch(1);
    CountDownLatch answered = new CountDownLatch(1);
    CallTasks.Task task = waiting(waits, decided, answered);
    assertThat(waits.await(5, TimeUnit.SECONDS)).isTrue();
    when(approvals.find("ap_1")).thenReturn(Optional.of(ApprovalDeliveryTest.view("pending", null)));
    when(approvals.declineInClient("ap_1")).thenReturn(ApprovalDeliveryTest.view("running", null));

    assertThatThrownBy(() -> tasks.cancel(task)).isInstanceOfSatisfying(McpException.class,
        e -> assertThat(e.getMessage()).contains("was approved in GateRook"));
    decided.countDown();
    assertThat(answered.await(5, TimeUnit.SECONDS)).isTrue();
    long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (task.working() && System.nanoTime() < end) {
      Thread.sleep(20);
    }
    assertThat(tasks.legacy(task).path("status").asString()).isEqualTo("completed");
  }

  /** A call that prepares until {@code prepared} opens, then asks to start a Bypass change and says what it heard. */
  private CallTasks.Task bypass(CountDownLatch preparing, CountDownLatch prepared, CountDownLatch running,
      CountDownLatch finish, java.util.concurrent.atomic.AtomicReference<Boolean> started) {
    return tasks.start(ApprovalDeliveryTest.request(false, "auto", 15), CallTasks.DEFAULT_TTL, own -> {
      preparing.countDown();
      await(prepared);
      started.set(own.task().startsToRunToItsEnd());
      running.countDown();
      await(finish);
      return com.gaterook.core.mcp.internal.ResultWriter.callToolResult(own.env(),
          ToolResult.text(started.get() ? "1 row updated" : "not started"), null, null, null);
    }).orElseThrow();
  }

  private static void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Test
  void aBypassChangeThatStartedRunsToItsEndAndCancellingItIsRefused() throws Exception {
    CountDownLatch preparing = new CountDownLatch(1);
    CountDownLatch prepared = new CountDownLatch(1);
    CountDownLatch running = new CountDownLatch(1);
    CountDownLatch finish = new CountDownLatch(1);
    var started = new java.util.concurrent.atomic.AtomicReference<Boolean>();
    CallTasks.Task task = bypass(preparing, prepared, running, finish, started);
    prepared.countDown();
    assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();

    assertThatThrownBy(() -> tasks.cancel(task)).isInstanceOfSatisfying(McpException.class,
        e -> assertThat(e.getMessage()).isEqualTo("Task " + task.id() + " runs a change that GateRook doesn't stop "
            + "once it started: it runs to its end and the task gets its result."));
    assertThat(task.working()).as("not reported cancelled while the change goes on").isTrue();
    finish.countDown();
    assertThat(tasks.result(task).at("/content/0/text").asString()).isEqualTo("1 row updated");
    assertThat(tasks.legacy(task).path("status").asString()).isEqualTo("completed");
  }

  @Test
  void aCancelBeforeABypassChangeStartsMeansItNeverStarts() throws Exception {
    CountDownLatch preparing = new CountDownLatch(1);
    CountDownLatch prepared = new CountDownLatch(1);
    CountDownLatch running = new CountDownLatch(1);
    CountDownLatch finish = new CountDownLatch(1);
    var started = new java.util.concurrent.atomic.AtomicReference<Boolean>();
    CallTasks.Task task = bypass(preparing, prepared, running, finish, started);
    assertThat(preparing.await(5, TimeUnit.SECONDS)).isTrue();

    tasks.cancel(task);
    prepared.countDown();
    assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
    finish.countDown();

    assertThat(started.get()).as("the change must not start after the cancel").isFalse();
    assertThat(tasks.legacy(task).path("status").asString()).isEqualTo("cancelled");
  }

  @Test
  void aCallThatGetsItsStopAfterTheCancelStopsAtOnce() throws Exception {
    CountDownLatch preparing = new CountDownLatch(1);
    CountDownLatch cancelled = new CountDownLatch(1);
    CountDownLatch stopped = new CountDownLatch(1);
    CallTasks.Task task = tasks.start(ApprovalDeliveryTest.request(false, "auto", 15), CallTasks.DEFAULT_TTL, own -> {
      preparing.countDown();
      await(cancelled);
      // What the invoker hands over when the call reaches the connector.
      own.stream().onClose(stopped::countDown);
      return com.gaterook.core.mcp.internal.ResultWriter.callToolResult(own.env(), ToolResult.text("rows"), null,
          null, null);
    }).orElseThrow();
    assertThat(preparing.await(5, TimeUnit.SECONDS)).isTrue();

    tasks.cancel(task);
    cancelled.countDown();

    assertThat(stopped.await(5, TimeUnit.SECONDS)).as("a read that starts after the cancel is stopped").isTrue();
    assertThat(tasks.legacy(task).path("status").asString()).isEqualTo("cancelled");
  }

  @Test
  void aCallThatAnswersAtOnceIsAnsweredAsUsualAndForgotten() {
    CallTasks.Task task = tasks.start(ApprovalDeliveryTest.request(false, "auto", 15), CallTasks.DEFAULT_TTL,
        own -> com.gaterook.core.mcp.internal.ResultWriter.callToolResult(own.env(), ToolResult.text("done"), null,
            null, null)).orElseThrow();

    assertThat(tasks.quick(task)).hasValueSatisfying(r -> assertThat(r.at("/content/0/text").asString())
        .isEqualTo("done"));
    assertThat(tasks.find(ApprovalDeliveryTest.request(false, "auto", 15), task.id())).isEmpty();
  }
}
