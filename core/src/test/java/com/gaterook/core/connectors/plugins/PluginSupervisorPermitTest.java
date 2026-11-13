package com.gaterook.core.connectors.plugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gaterook.connector.api.ConnectorException;
import com.gaterook.plugin.protocol.Gpp;
import com.gaterook.plugin.protocol.GppJson;
import com.gaterook.plugin.protocol.JsonRpcPeer;
import com.gaterook.plugin.protocol.MemoryPipe;
import com.gaterook.plugin.protocol.RpcException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What counts against a plugin host (report PL-2): only a call that got no answer by its deadline is a stuck call; an
 * error answer is an answer, so calls that fail inside the plugin don't replace its host. And a start that fails in
 * an unexpected way gives its bulkhead permit back, so eight of them don't make the plugin busy for good.
 */
class PluginSupervisorPermitTest {

  @TempDir
  Path tmp;

  @Test
  void anErrorAnswerIsAnAnswerAndNoStuckCall() throws Exception {
    MemoryPipe coreToHost = new MemoryPipe();
    MemoryPipe hostToCore = new MemoryPipe();
    JsonRpcPeer plugin = new JsonRpcPeer(coreToHost.in(), hostToCore.out(), (method, params, call) -> {
      throw new RpcException(Gpp.CONNECTOR_ERROR, "The plugin refused the call", null);
    }, JsonRpcPeer.Options.defaults().name("plugin").hostIds(true)).start();
    JsonRpcPeer core = new JsonRpcPeer(hostToCore.in(), coreToHost.out(), (method, params, call) -> null,
        JsonRpcPeer.Options.defaults().name("core")).start();
    Process process = mock(Process.class);
    when(process.isAlive()).thenReturn(true);
    when(process.descendants()).thenAnswer(invocation -> Stream.empty());
    HostProcess host = new HostProcess("fixture@1.0.0", 1, process, core,
        new PluginLog(tmp.resolve("plugin.log"), Clock.systemUTC()), Instant.now());
    host.state(HostProcess.State.RUNNING);
    PluginSupervisor supervisor = new PluginSupervisor("fixture", null, Clock.systemUTC());
    try {
      for (int call = 0; call < 3; call++) {
        try (PluginSupervisor.Permit permit = supervisor.new Permit(host)) {
          host.enter();
          assertThatThrownBy(() -> RemoteConnector.answer(permit, host.request(Gpp.CONNECTOR_EXECUTE,
              GppJson.object()), Duration.ofSeconds(5))).isInstanceOf(RpcException.class)
              .hasMessageContaining("refused");
        }
      }

      assertThat(host.stuck()).as("calls the host answered, with an error").isZero();
      assertThat(host.state()).as("the host is kept").isEqualTo(HostProcess.State.RUNNING);
    } finally {
      core.close();
      plugin.close();
    }
  }

  @Test
  void aStartThatFailsUnexpectedlyGivesItsPermitBack() throws Exception {
    HostLauncher launcher = mock(HostLauncher.class);
    when(launcher.start(any(), any())).thenThrow(new IllegalStateException("a launcher failure of no known phase"));
    SteppedClock clock = new SteppedClock(Instant.parse("2026-11-10T10:00:00Z"));
    PluginSupervisor supervisor = new PluginSupervisor("fixture", launcher, clock);
    supervisor.spec(new HostLauncher.Spec("fixture", "1.0.0", tmp.resolve("fixture.jar"), "00", 64, Map.of(),
        List.of()));

    // More attempts than the bulkhead has permits, each one past the previous failure's backoff and crash window.
    for (int attempt = 0; attempt <= PluginSupervisor.BULKHEAD; attempt++) {
      clock.advance(Duration.ofMinutes(11));
      assertThatThrownBy(() -> supervisor.permit(clock.instant().plusSeconds(5)))
          .as("attempt %d", attempt).isInstanceOf(ConnectorException.class)
          .hasMessageContaining("couldn't start").hasMessageNotContaining("busy");
    }
  }

  /** A clock the test moves forward. */
  private static final class SteppedClock extends Clock {
    private volatile Instant now;

    SteppedClock(Instant start) {
      now = start;
    }

    void advance(Duration by) {
      now = now.plus(by);
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
