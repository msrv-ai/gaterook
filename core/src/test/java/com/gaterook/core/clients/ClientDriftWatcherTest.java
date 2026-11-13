package com.gaterook.core.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gaterook.core.clients.internal.ClientEnvironment;
import com.gaterook.core.platform.EventBus;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Drift (report 03 §6): a look that fails — a busy database — doesn't end the watching (review K1-7). */
class ClientDriftWatcherTest {

  @TempDir
  Path dir;

  @Test
  void aLookThatFailsWaitsForTheNextOne() throws InterruptedException {
    ClientService clients = mock(ClientService.class);
    when(clients.watchedFiles()).thenReturn(List.of());
    when(clients.statuses())
        .thenReturn(Map.of("cl_1", "codex connected"))
        .thenThrow(new IllegalStateException("database is busy"))
        .thenReturn(Map.of("cl_1", "codex removed-outside"));
    EventBus events = new EventBus(Clock.systemUTC());
    ClientDriftWatcher watcher = new ClientDriftWatcher(clients, new ClientEnvironment(ClientEnvironment.Os.LINUX,
        dir, dir.resolve(".gaterook"), null, Map.of(), true, "laptop"), events, "");
    try (EventBus.Subscription s = events.subscribe(OptionalLong.empty())) {
      Set<Path> watched = new HashSet<>();
      watcher.pass(watched, true);
      watcher.pass(watched, false);
      watcher.pass(watched, false);
      Optional<EventBus.GatewayEvent> drift = s.next(Duration.ofSeconds(1));
      assertThat(drift).hasValueSatisfying(e -> {
        assertThat(e.type()).isEqualTo("clients.drift");
        assertThat(e.data().path("status").asString()).isEqualTo("removed-outside");
      });
    }
  }

  @Test
  void aClientWhoseConfigAppearsIsScannedAgainAndAnnounced() throws InterruptedException {
    ClientService clients = mock(ClientService.class);
    when(clients.watchedFiles()).thenReturn(List.of());
    when(clients.statuses()).thenReturn(Map.of());
    when(clients.configsPresent())
        .thenReturn(Map.of("cursor", false, "codex", true))
        .thenReturn(Map.of("cursor", false, "codex", true))
        .thenReturn(Map.of("cursor", true, "codex", true));
    when(clients.scan()).thenReturn(new ClientViews.Clients(Instant.now(), List.of(card("cursor", "detected"),
        card("codex", "connected"))));
    EventBus events = new EventBus(Clock.systemUTC());
    ClientDriftWatcher watcher = new ClientDriftWatcher(clients, new ClientEnvironment(ClientEnvironment.Os.LINUX,
        dir, dir.resolve(".gaterook"), null, Map.of(), true, "laptop"), events, "");
    try (EventBus.Subscription s = events.subscribe(OptionalLong.empty())) {
      Set<Path> watched = new HashSet<>();
      watcher.pass(watched, true);
      watcher.pass(watched, false);
      assertThat(s.next(Duration.ofMillis(200))).as("nothing appeared yet").isEmpty();
      watcher.pass(watched, false);
      assertThat(s.next(Duration.ofSeconds(1))).hasValueSatisfying(e -> {
        assertThat(e.type()).isEqualTo("clients.drift");
        assertThat(e.data().path("client").asString()).isEqualTo("cursor");
        assertThat(e.data().path("status").asString()).isEqualTo("detected");
      });
      assertThat(s.next(Duration.ofMillis(200))).as("codex didn't change").isEmpty();
      verify(clients, times(2)).scan();
    }
  }

  @Test
  void aReinstalledExtensionThatConnectsIsAnnouncedWithItsPlainStatus() throws InterruptedException {
    ClientService clients = mock(ClientService.class);
    when(clients.watchedFiles()).thenReturn(List.of());
    when(clients.configsPresent()).thenReturn(Map.of());
    when(clients.statuses())
        .thenReturn(Map.of("cl_cd", "claude-desktop connected bridge-update"))
        .thenReturn(Map.of("cl_cd", "claude-desktop connected bridge-update"))
        .thenReturn(Map.of("cl_cd", "claude-desktop connected"));
    EventBus events = new EventBus(Clock.systemUTC());
    ClientDriftWatcher watcher = new ClientDriftWatcher(clients, new ClientEnvironment(ClientEnvironment.Os.LINUX,
        dir, dir.resolve(".gaterook"), null, Map.of(), true, "laptop"), events, "");
    try (EventBus.Subscription s = events.subscribe(OptionalLong.empty())) {
      Set<Path> watched = new HashSet<>();
      watcher.pass(watched, true);
      watcher.pass(watched, false);
      assertThat(s.next(Duration.ofMillis(200))).as("the old bridge still asks for tokens").isEmpty();
      watcher.pass(watched, false);
      assertThat(s.next(Duration.ofSeconds(1))).hasValueSatisfying(e -> {
        assertThat(e.type()).isEqualTo("clients.drift");
        assertThat(e.data().path("link").asString()).isEqualTo("cl_cd");
        assertThat(e.data().path("client").asString()).isEqualTo("claude-desktop");
        assertThat(e.data().path("status").asString()).isEqualTo("connected");
        assertThat(e.data().path("bridgeUpdate").asBoolean()).isFalse();
      });
    }
  }

  /**
   * The bridge launcher is written when the MCP server runtime asks for it, before it first probes the sandbox, which
   * can be before this watcher's first pass: whoever asks first writes it, once per run, and hears whether this build
   * has a bridge. A launcher that couldn't be written is written at the next call.
   */
  @Test
  void theLauncherIsWrittenWhenTheRuntimeAsksOncePerRun() throws Exception {
    ClientService clients = mock(ClientService.class);
    Path jar = java.nio.file.Files.writeString(dir.resolve("gw-bridge.jar"), "jar");
    ClientEnvironment env = new ClientEnvironment(ClientEnvironment.Os.LINUX, dir, dir.resolve(".gaterook"), null,
        Map.of(), true, "laptop");
    java.nio.file.Files.createDirectories(dir.resolve(".gaterook"));
    // Something in the way of the bin folder: not written, and asked again later.
    Path blocker = java.nio.file.Files.writeString(dir.resolve(".gaterook/bin"), "not a folder");
    ClientDriftWatcher watcher = new ClientDriftWatcher(clients, env, new EventBus(Clock.systemUTC()), jar.toString());

    assertThat(watcher.prepare()).as("this build has a bridge (the development jar)").isTrue();
    assertThat(env.shim()).doesNotExist();

    java.nio.file.Files.delete(blocker);
    assertThat(watcher.prepare()).isTrue();
    assertThat(java.nio.file.Files.readString(env.shim())).contains(jar.toString());
    // Once per run: a second call doesn't write it again.
    java.nio.file.Files.writeString(env.shim(), "# as this run wrote it\n");
    assertThat(watcher.prepare()).isTrue();
    assertThat(java.nio.file.Files.readString(env.shim())).isEqualTo("# as this run wrote it\n");

    // A build without the bridge: the launcher says so to clients, and the runtime hears there is no sandbox.
    Path other = java.nio.file.Files.createDirectories(dir.resolve("other"));
    ClientEnvironment none = new ClientEnvironment(ClientEnvironment.Os.LINUX, other, other.resolve(".gaterook"),
        null, Map.of(), true, "laptop");
    ClientDriftWatcher without = new ClientDriftWatcher(clients, none, new EventBus(Clock.systemUTC()), "");
    assertThat(without.prepare()).isFalse();
    assertThat(java.nio.file.Files.readString(none.shim())).contains("isn't installed with this build");
  }

  private static ClientViews.Card card(String client, String status) {
    return new ClientViews.Card(client, client, status, status, "", "", true, List.of(), null, List.of(), List.of(),
        List.of());
  }
}
