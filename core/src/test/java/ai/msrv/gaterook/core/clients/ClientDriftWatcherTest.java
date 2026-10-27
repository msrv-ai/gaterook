package ai.msrv.gaterook.core.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.msrv.gaterook.core.clients.internal.ClientEnvironment;
import ai.msrv.gaterook.core.platform.EventBus;
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
        dir, dir.resolve(".mcp-gateway"), null, Map.of(), true, "laptop"), events, "");
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
        dir, dir.resolve(".mcp-gateway"), null, Map.of(), true, "laptop"), events, "");
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

  private static ClientViews.Card card(String client, String status) {
    return new ClientViews.Card(client, client, status, status, "", "", true, List.of(), null, List.of(), List.of(),
        List.of());
  }
}
