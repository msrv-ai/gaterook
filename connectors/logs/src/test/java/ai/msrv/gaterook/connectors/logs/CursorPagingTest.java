package ai.msrv.gaterook.connectors.logs;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudEndpoints;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * The cursor of both tools against a Loki that answers like the real one (step C5 "cursor without omissions or duplicates"):
 * entries of several streams, many of them at the same nanosecond, ties returned in a different order on every
 * request, pages cut by the limit and by the size budget. Every entry comes exactly once, in order.
 */
class CursorPagingTest {

  private final MockWebServer server = new MockWebServer();
  private final CloudCredentials cloud = new CloudCredentials(CloudEndpoints.PRODUCTION, Clock.systemUTC());

  @AfterEach
  void stop() throws IOException {
    server.close();
    cloud.close();
  }

  @ParameterizedTest(name = "seed {0}")
  @ValueSource(longs = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12})
  void searchHandsOutEveryEntryOnceNewestFirst(long seed) throws Exception {
    Random random = new Random(seed);
    FakeLoki loki = new FakeLoki(seed, () -> Long.MAX_VALUE);
    server.setDispatcher(loki);
    server.start();
    Instant base = Instant.parse("2026-10-22T12:00:00Z");
    long baseNs = base.getEpochSecond() * 1_000_000_000L;
    int n = 150 + random.nextInt(350);
    List<String> streams = List.of("api", "worker", "db");
    for (int i = 0; i < n; i++) {
      // A few hundred distinct instants for hundreds of entries: ties everywhere, within and across streams.
      long ns = baseNs + random.nextInt(60) * 1_000_000_000L + (random.nextInt(4) == 0 ? random.nextInt(3) : 0);
      loki.add(streams.get(random.nextInt(3)), ns, "line-" + i + (i % 7 == 0 ? " ERROR boom" : " ok"));
    }
    LogsConnector connector = new LogsConnector(cloud, Clock.systemUTC());
    ConnectionConfig cfg = Configs.loki(server.url("/").toString());
    TestSecrets secrets = new TestSecrets().with("/auth/secret", "loki-token-0001");

    List<String> seen = new ArrayList<>();
    List<Long> times = new ArrayList<>();
    String cursor = null;
    int pages = 0;
    do {
      int limit = 1 + random.nextInt(40);
      // Some pages are cut by size before the limit: the cursor must follow the entries really given.
      Limits limits = random.nextInt(3) == 0 ? withResponseBytes(TestCtx.dev(), 6_000) : TestCtx.dev();
      String args = "{\"query\":\"{app=~\\\".+\\\"}\",\"from\":\"" + base + "\",\"to\":\"" + base.plusSeconds(3600)
          + "\",\"limit\":" + limit + (cursor == null ? "" : ",\"cursor\":\"" + cursor + "\"") + "}";
      ToolResult r = connector.execute(Configs.call("logs_search", args), cfg, secrets, TestCtx.of().with(limits));
      JsonNode s = r.structuredContent();
      for (JsonNode e : s.path("entries")) {
        seen.add(e.path("message").asString());
        times.add(Instant.parse(e.path("timestamp").asString()).getEpochSecond() * 1_000_000_000L
            + Instant.parse(e.path("timestamp").asString()).getNano());
      }
      assertThat(s.path("gapPossible").asBoolean()).isFalse();
      cursor = s.path("nextCursor").isNull() ? null : s.path("nextCursor").asString();
      assertThat(++pages).as("pages").isLessThan(2_000);
    } while (cursor != null);

    List<String> expected = loki.lines.stream().map(FakeLoki.Line::text).sorted().toList();
    assertThat(seen.stream().sorted().toList()).as("every entry exactly once").isEqualTo(expected);
    for (int i = 1; i < times.size(); i++) {
      assertThat(times.get(i)).as("newest first, entry " + i).isLessThanOrEqualTo(times.get(i - 1));
    }
  }

  @Test
  void moreEntriesAtOneInstantThanACursorHoldsAreSteppedOverAndSaidSo() throws Exception {
    FakeLoki loki = new FakeLoki(42, () -> Long.MAX_VALUE);
    server.setDispatcher(loki);
    server.start();
    Instant base = Instant.parse("2026-10-22T12:00:00Z");
    long at = base.getEpochSecond() * 1_000_000_000L + 30_000_000_000L;
    for (int i = 0; i < 400; i++) {
      loki.add("api", at, "burst-" + i);
    }
    loki.add("api", at - 1_000, "older");
    LogsConnector connector = new LogsConnector(cloud, Clock.systemUTC());
    ConnectionConfig cfg = Configs.loki(server.url("/").toString());
    TestSecrets secrets = new TestSecrets().with("/auth/secret", "t");
    List<String> got = new ArrayList<>();
    boolean gap = false;
    String cursor = null;
    do {
      String args = "{\"query\":\"{app=\\\"api\\\"}\",\"from\":\"" + base + "\",\"to\":\"" + base.plusSeconds(60)
          + "\",\"limit\":100" + (cursor == null ? "" : ",\"cursor\":\"" + cursor + "\"") + "}";
      JsonNode s = connector.execute(Configs.call("logs_search", args), cfg, secrets, TestCtx.of()).structuredContent();
      s.path("entries").forEach(e -> got.add(e.path("message").asString()));
      gap |= s.path("gapPossible").asBoolean();
      cursor = s.path("nextCursor").isNull() ? null : s.path("nextCursor").asString();
    } while (cursor != null);

    assertThat(gap).as("the answer says entries may be missing").isTrue();
    assertThat(got).doesNotHaveDuplicates().contains("older");
    // Pages of 100: after the third the boundary holds 300 entries, more than a cursor keeps, and is stepped over.
    assertThat(got.stream().filter(m -> m.startsWith("burst-"))).hasSize(300);
  }

  @ParameterizedTest(name = "seed {0}")
  @ValueSource(longs = {21, 22, 23, 24, 25, 26, 27, 28})
  void tailHandsOutEveryEntryThatArrivesWithinTheDelayOnce(long seed) throws Exception {
    Random random = new Random(seed);
    TestCtx.MovingClock clock = new TestCtx.MovingClock(Instant.parse("2026-10-22T12:00:00Z"));
    FakeLoki loki = new FakeLoki(seed, () -> nanos(clock.instant()));
    server.setDispatcher(loki);
    server.start();
    long startNs = nanos(clock.instant());
    long arrival = startNs;
    Map<String, Integer> expected = new HashMap<>();
    for (int i = 0; i < 400; i++) {
      arrival += random.nextInt(4) == 0 ? 0 : random.nextInt(300) * 1_000_000L;
      // Entries reach Loki up to 1.5 s after their timestamp; bursts share one nanosecond.
      long lag = random.nextInt(1_500) * 1_000_000L;
      String text = "event-" + i;
      String stream = random.nextBoolean() ? "api" : "worker";
      if (random.nextInt(5) == 0 && !loki.lines.isEmpty()) {
        FakeLoki.Line last = loki.lines.getLast();
        loki.add(stream, last.ns(), text, last.arrivesAtNs());
      } else {
        long ns = Math.max(arrival - lag, startNs);
        loki.add(stream, ns, text, Math.max(arrival, ns));
      }
      expected.put(text, 1);
    }
    LogsConnector connector = new LogsConnector(cloud, clock);
    ConnectionConfig cfg = Configs.loki(server.url("/").toString());
    TestSecrets secrets = new TestSecrets().with("/auth/secret", "t");
    TestCtx base = TestCtx.of().clock(clock);
    Map<String, Integer> got = new HashMap<>();
    String cursor = null;
    long endNs = arrival + 10_000_000_000L;
    int calls = 0;
    while (nanos(clock.instant()) < endNs) {
      String args = "{\"query\":\"{app=~\\\".+\\\"}\",\"wait\":0,\"limit\":" + (1 + random.nextInt(25))
          + (cursor == null ? ",\"since\":\"" + clock.instant() + "\"" : ",\"cursor\":\"" + cursor + "\"") + "}";
      JsonNode s = connector.execute(Configs.call("logs_tail", args), cfg, secrets, base.next()).structuredContent();
      s.path("entries").forEach(e -> got.merge(e.path("message").asString(), 1, Integer::sum));
      cursor = s.path("nextCursor").asString();
      assertThat(s.path("gapPossible").asBoolean()).isFalse();
      clock.advance(Duration.ofMillis(random.nextInt(1_200)));
      assertThat(++calls).isLessThan(5_000);
    }
    assertThat(got).as("every entry once, none twice").isEqualTo(expected);
  }

  @Test
  void tailWaitsForAnEntryOnItsWayAndReturnsAtOnceWhenItCame() throws Exception {
    long now = nanos(Instant.now());
    FakeLoki loki = new FakeLoki(7, () -> nanos(Instant.now()));
    server.setDispatcher(loki);
    server.start();
    // Written a second ago, reaches Loki in half a second: the second poll (1 s later, window 2 s behind) has it.
    loki.add("api", now - 1_000_000_000L, "on its way", now + 500_000_000L);
    LogsConnector connector = new LogsConnector(cloud, Clock.systemUTC());

    JsonNode s = connector.execute(Configs.call("logs_tail", "{\"query\":\"{app=\\\"api\\\"}\",\"since\":\"-10s\","
        + "\"wait\":5}"), Configs.loki(server.url("/").toString()), new TestSecrets().with("/auth/secret", "t"),
        TestCtx.of()).structuredContent();

    assertThat(s.at("/entries/0/message").asString()).isEqualTo("on its way");
    assertThat(s.path("waitedMs").asLong()).isBetween(800L, 3_000L);
    assertThat(loki.requests).hasSizeBetween(2, 4);
  }

  @Test
  void tailWithNothingNewWaitsAndKeepsItsCursor() throws Exception {
    FakeLoki loki = new FakeLoki(8, () -> nanos(Instant.now()));
    server.setDispatcher(loki);
    server.start();
    LogsConnector connector = new LogsConnector(cloud, Clock.systemUTC());
    ConnectionConfig cfg = Configs.loki(server.url("/").toString());

    long started = System.nanoTime();
    var r = connector.execute(Configs.call("logs_tail", "{\"query\":\"{app=\\\"api\\\"}\",\"since\":\"-1m\","
        + "\"wait\":2}"), cfg, new TestSecrets().with("/auth/secret", "t"), TestCtx.of());

    assertThat((System.nanoTime() - started) / 1_000_000).isBetween(1_500L, 4_000L);
    JsonNode s = OutputSchema.valid(r).structuredContent();
    assertThat(s.path("count").asInt()).isZero();
    assertThat(r.content().getFirst().text()).startsWith("No new entries on app-logs (Loki) in ")
        .contains("Call logs_tail again with cursor lc1.");
    assertThat(s.path("nextCursor").asString()).startsWith("lc1.");
  }

  @Test
  void anAgentCantForgeOrMoveACursor() throws Exception {
    FakeLoki loki = new FakeLoki(9, () -> Long.MAX_VALUE);
    server.setDispatcher(loki);
    server.start();
    Instant base = Instant.parse("2026-10-22T12:00:00Z");
    for (int i = 0; i < 5; i++) {
      loki.add("api", base.getEpochSecond() * 1_000_000_000L + i, "l" + i);
    }
    LogsConnector connector = new LogsConnector(cloud, Clock.systemUTC());
    ConnectionConfig cfg = Configs.loki(server.url("/").toString());
    TestSecrets secrets = new TestSecrets().with("/auth/secret", "t");
    String range = ",\"from\":\"" + base + "\",\"to\":\"" + base.plusSeconds(60) + "\",\"limit\":2";
    String cursor = connector.execute(Configs.call("logs_search", "{\"query\":\"{app=\\\"api\\\"}\"" + range + "}"),
        cfg, secrets, TestCtx.of()).structuredContent().path("nextCursor").asString();

    String forged = cursor.substring(0, cursor.length() - 3) + (cursor.endsWith("AAA") ? "BBB" : "AAA");
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> connector.execute(Configs.call("logs_search",
        "{\"query\":\"{app=\\\"api\\\"}\",\"cursor\":\"" + forged + "\"}"), cfg, secrets, TestCtx.of()))
        .hasMessageContaining("invalid or has expired");
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> connector.execute(Configs.call("logs_search",
        "{\"query\":\"{app=\\\"db\\\"}\",\"cursor\":\"" + cursor + "\"}"), cfg, secrets, TestCtx.of()))
        .hasMessageContaining("belongs to another query");
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> connector.execute(Configs.call("logs_search",
        "{\"query\":\"{app=\\\"api\\\"}\",\"cursor\":\"" + cursor + "\"}"),
        Configs.of("other-logs", ai.msrv.gaterook.connector.api.Env.DEV, cfg.config().toString(), "/auth/secret"),
        secrets, TestCtx.of())).hasMessageContaining("belongs to another query or connection");
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> connector.execute(Configs.call("logs_tail",
        "{\"query\":\"{app=\\\"api\\\"}\",\"cursor\":\"" + cursor + "\",\"wait\":0}"), cfg, secrets,
        TestCtx.of())).as("a search cursor isn't a tail cursor").hasMessageContaining("invalid or has expired");
  }

  @Test
  void anHourOldTailCursorStartsAnHourBackAndSaysEntriesMayBeMissing() throws Exception {
    TestCtx.MovingClock clock = new TestCtx.MovingClock(Instant.parse("2026-10-22T12:00:00Z"));
    FakeLoki loki = new FakeLoki(10, () -> nanos(clock.instant()));
    server.setDispatcher(loki);
    server.start();
    LogsConnector connector = new LogsConnector(cloud, clock);
    ConnectionConfig cfg = Configs.loki(server.url("/").toString());
    TestSecrets secrets = new TestSecrets().with("/auth/secret", "t");
    TestCtx ctx = TestCtx.of().clock(clock);
    String cursor = connector.execute(Configs.call("logs_tail", "{\"query\":\"{app=\\\"api\\\"}\",\"wait\":0}"),
        cfg, secrets, ctx.next()).structuredContent().path("nextCursor").asString();
    clock.advance(Duration.ofHours(3));
    loki.add("api", nanos(clock.instant()) - 1_800_000_000_000L, "half an hour ago");

    JsonNode s = connector.execute(Configs.call("logs_tail", "{\"query\":\"{app=\\\"api\\\"}\",\"wait\":0,"
        + "\"cursor\":\"" + cursor + "\"}"), cfg, secrets, ctx.next()).structuredContent();

    assertThat(s.path("gapPossible").asBoolean()).isTrue();
    assertThat(s.at("/entries/0/message").asString()).isEqualTo("half an hour ago");
    long start = Long.parseLong(FakeLoki.query(loki.requests.getLast().getUrl().encodedQuery()).get("start"));
    assertThat(nanos(clock.instant()) - start).isEqualTo(Duration.ofHours(1).toNanos());
  }

  private static long nanos(Instant i) {
    return i.getEpochSecond() * 1_000_000_000L + i.getNano();
  }

  private static Limits withResponseBytes(Limits l, long bytes) {
    return new Limits(l.maxRows(), l.maxLines(), bytes, l.maxRequestBytes(), l.maxOutputBytes(), l.maxStderrBytes(),
        l.maxCellChars(), l.statementTimeout(), l.commandTimeout(), l.toolTimeout(), l.maxRange());
  }
}
