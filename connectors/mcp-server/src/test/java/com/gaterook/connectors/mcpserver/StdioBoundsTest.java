package com.gaterook.connectors.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ObjectNode;

/**
 * A stdio server that stops reading its input (audit M-A5): the messages waiting for it are bounded, and the server is
 * stopped with the reason instead of the queue growing with every answer the gateway owes it.
 */
class StdioBoundsTest {

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void aServerThatReadsNoInputIsStoppedOnceItsQueueIsFull(@TempDir Path dir) throws Exception {
    // A process that never reads its stdin: the pipe fills, then the queue.
    GatewayStdioTransport t = new GatewayStdioTransport(List.of("/bin/sleep", "30"), Map.of(), dir,
        Session.MAX_MESSAGE, line -> {}, s -> s);
    Process p = t.start();
    try {
      ObjectNode big = Rpc.notification("notifications/message", Fixtures.object("{\"data\":\"\"}"));
      ((ObjectNode) big.get("params")).put("data", "z".repeat(256 * 1024));
      int accepted = 0;
      Channel.Exchange last = null;
      for (int i = 0; i < GatewayStdioTransport.MAX_WAITING + 50; i++) {
        last = t.send(big, Map.of());
        if (last.done().isCompletedExceptionally()) {
          break;
        }
        accepted++;
      }

      assertThat(accepted).isLessThanOrEqualTo(GatewayStdioTransport.MAX_WAITING + 2);
      assertThat(last.done().isCompletedExceptionally()).isTrue();
      assertThat(t.failure()).isEqualTo("The server stopped reading its input: " + GatewayStdioTransport.MAX_WAITING
          + " messages waited for it");
      assertThat(p.waitFor(10, TimeUnit.SECONDS)).as("the process is stopped").isTrue();
    } finally {
      p.destroyForcibly();
    }
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void theFirstReasonAServerIsStoppedForStays(@TempDir Path dir) throws Exception {
    // Stopping the process breaks its pipe, so the writer that waited on it fails next with a reason that says less.
    GatewayStdioTransport t = new GatewayStdioTransport(List.of("/bin/sleep", "30"), Map.of(), dir,
        Session.MAX_MESSAGE, line -> {}, s -> s);
    Process p = t.start();
    try {
      t.abandon("fake-server sent too much");
      t.abandon("The server stopped reading its input");

      assertThat(p.waitFor(10, TimeUnit.SECONDS)).isTrue();
      assertThat(t.failure()).isEqualTo("fake-server sent too much");
    } finally {
      p.destroyForcibly();
    }
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void aSessionGivenUpStopsItsProcessWithTheReason(@TempDir Path dir) throws Exception {
    GatewayStdioTransport t = new GatewayStdioTransport(List.of("/bin/sleep", "30"), Map.of(), dir,
        Session.MAX_MESSAGE, line -> {}, s -> s);
    Process p = t.start();
    try {
      t.abandon("fake-server sent too much");

      assertThat(p.waitFor(10, TimeUnit.SECONDS)).isTrue();
      assertThat(t.failure()).isEqualTo("fake-server sent too much");
    } finally {
      p.destroyForcibly();
    }
  }
}
