package com.gaterook.core.connectors.plugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.gaterook.plugin.protocol.GppJson;
import com.gaterook.plugin.protocol.JsonRpcPeer;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * A plugin host that stops reading its input (report PL-1): a frame larger than the pipe holds then waits inside the
 * stream, and before this the stop closed the pipe first, behind that frame, and never reached the kill. Stopping
 * now ends the process within its grace whatever waits for the host to read.
 */
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "uses sleep(1) as a process that never reads its input")
class HostProcessStopTest {

  /** More than any pipe buffers: the write waits until the process ends. */
  private static final String LARGE = "x".repeat(4 * 1024 * 1024);

  @TempDir
  Path tmp;

  private Process process;

  @AfterEach
  void kill() {
    if (process != null) {
      process.destroyForcibly();
    }
  }

  private HostProcess host(JsonRpcPeer.Options options) throws IOException {
    process = new ProcessBuilder("sleep", "60").start();
    JsonRpcPeer peer = new JsonRpcPeer(process.getInputStream(), process.getOutputStream(),
        (method, params, call) -> null, options.name("host-test")).start();
    return new HostProcess("fixture@1.0.0", 1, process, peer, new PluginLog(tmp.resolve("plugin.log"),
        Clock.systemUTC()), Instant.now());
  }

  @Test
  void aStopEndsTheProcessWithinItsGraceWhileAFrameWaitsForTheHostToRead() throws Exception {
    HostProcess host = host(JsonRpcPeer.Options.defaults());
    // Without a write timeout the sender itself waits in the stream, holding the peer's write lock.
    Thread sender = Thread.ofVirtual().start(() -> host.peer().notify("host/log",
        GppJson.object().put("text", LARGE)));
    sender.join(300);
    assertThat(sender.isAlive()).as("the frame waits for the host to read").isTrue();

    assertTimeoutPreemptively(Duration.ofSeconds(10), () -> host.stopForcibly(Duration.ofMillis(500)));

    assertThat(process.waitFor(5, TimeUnit.SECONDS)).as("the host process ended").isTrue();
    sender.join(5_000);
    assertThat(sender.isAlive()).as("the waiting frame failed with the stream").isFalse();
  }

  @Test
  void aShutdownOfAHostThatStopsReadingEndsWithinTheWriteTimeoutAndTheGrace() throws Exception {
    HostProcess host = host(JsonRpcPeer.Options.defaults().writeTimeout(Duration.ofMillis(500)));
    // As the core's peer is set up: the frame waits no longer than the write timeout, then the peer stops sending.
    assertTimeoutPreemptively(Duration.ofSeconds(5), () -> host.peer().notify("host/log",
        GppJson.object().put("text", LARGE)));

    assertTimeoutPreemptively(Duration.ofSeconds(10), () -> host.shutdown(Duration.ofMillis(500)));

    assertThat(process.waitFor(5, TimeUnit.SECONDS)).as("the host process ended").isTrue();
  }
}
