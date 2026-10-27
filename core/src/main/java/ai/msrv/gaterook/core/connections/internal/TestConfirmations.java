package ai.msrv.gaterook.core.connections.internal;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.platform.Ids;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.JsonNode;

/**
 * Tests that stopped at {@code needsConfirmation} (an SSH host key seen for the first time, a self-signed
 * certificate) and wait for the person's "Trust" ({@code POST …/test/confirm {confirmationId}}). Kept in memory for
 * {@link #TTL}; they hold the connector's patch and the non-secret draft, never a secret value: after Trust the form
 * tests again with the values it still has.
 */
public final class TestConfirmations {

  public static final Duration TTL = Duration.ofMinutes(10);
  private static final int MAX = 64;

  /**
   * One pending confirmation.
   *
   * @param connectionId the stored connection a test of {@code …/{name}/test} was about, or {@code null} for a draft
   * @param draftConfig  the non-secret config of a draft test, or {@code null}
   */
  public record Pending(String id, String kind, String text, JsonNode data, JsonNode configPatch, String type, Env env,
      String connectionId, JsonNode draftConfig, Instant expiresAt) {}

  private final Map<String, Pending> pending = new ConcurrentHashMap<>();
  private final Clock clock;

  public TestConfirmations(Clock clock) {
    this.clock = clock;
  }

  public Pending add(String kind, String text, JsonNode data, JsonNode configPatch, String type, Env env,
      String connectionId, JsonNode draftConfig) {
    purge();
    if (pending.size() >= MAX) {
      Iterator<String> oldest = pending.keySet().iterator();
      if (oldest.hasNext()) {
        pending.remove(oldest.next());
      }
    }
    Pending p = new Pending(Ids.next("cf_"), kind, text, data, configPatch, type, env, connectionId,
        draftConfig == null ? null : draftConfig.deepCopy(), clock.instant().plus(TTL));
    pending.put(p.id(), p);
    return p;
  }

  /** Takes a confirmation once; empty when unknown or expired. */
  public Optional<Pending> take(String id) {
    purge();
    return Optional.ofNullable(id == null ? null : pending.remove(id));
  }

  private void purge() {
    Instant now = clock.instant();
    pending.values().removeIf(p -> !p.expiresAt().isAfter(now));
  }
}
