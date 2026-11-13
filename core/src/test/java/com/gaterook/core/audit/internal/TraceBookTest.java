package com.gaterook.core.audit.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.connector.api.Classification;
import com.gaterook.connector.api.Env;
import com.gaterook.core.audit.TraceSummarizer;
import com.gaterook.core.audit.internal.persistence.CallDecision;
import com.gaterook.core.audit.internal.persistence.CallPhase;
import com.gaterook.core.audit.internal.persistence.ToolCallEntity;
import com.gaterook.core.audit.internal.persistence.ToolCallRepository;
import com.gaterook.core.audit.internal.persistence.TraceEntity;
import com.gaterook.core.audit.internal.persistence.TraceRepository;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.json.JsonMapper;

/**
 * The trace id is the client's and has no bound, so one trace can collect any number of rows: the work each new row
 * causes, and the summary, must stay bounded. A store in memory counts the rows every run reads.
 */
class TraceBookTest {

  private static final String TRACE = "4f2a9c00000000000000000000000000";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final List<ToolCallEntity> table = new ArrayList<>();
  private final Map<String, TraceEntity> stored = new HashMap<>();
  private long rowsRead;
  private long nextId = 1;
  private int calls;
  private Instant clock = Instant.parse("2026-11-10T09:00:00Z");
  private final TraceBook book = new TraceBook(callRepository(), traceRepository(), JSON, new NoTransactions());

  @BeforeEach
  void runsOnlyWhenTheTestSaysSo() throws InterruptedException {
    // No background worker: each test runs the summarizer itself, as many times as the worker would.
    book.destroy();
  }

  @Test
  void aShortTraceIsSummarizedFromAllItsRowsAsBefore() {
    read();
    read();
    String change = pending();
    approve(change, 47_000);
    read();
    book.summarize(TRACE);
    TraceEntity t = stored.get(TRACE);
    assertThat(t.getCalls()).isEqualTo(4);
    assertThat(t.getSummary()).doesNotContainKeys(TraceBook.TIMELINE_CALLS, TraceBook.TIMELINE_END)
        .containsEntry("decisions", Map.of("allowed", 3, "approved", 1)).containsEntry("waiting", false)
        .containsEntry("waitedForPeopleMs", 47_000);
    assertThat(((Number) t.getSummary().get(TraceBook.THROUGH)).longValue()).isEqualTo(nextId - 1);
    assertThat(phases(t)).extracting(p -> p.get("name")).containsExactly("Explore", "Change", "Verify");
  }

  @Test
  void theWorkForEachNewRowStaysBoundedHoweverLongTheTraceGrows() {
    List<Long> perRun = new ArrayList<>();
    for (int run = 0; run < 150; run++) {
      for (int i = 0; i < 10; i++) {
        read();
      }
      long before = rowsRead;
      book.summarize(TRACE);
      perRun.add(rowsRead - before);
    }
    // 1,500 calls, 3,000 rows: re-reading every row on every run would read about 450,000.
    assertThat(stored.get(TRACE).getCalls()).isEqualTo(1_500);
    assertThat(perRun.getLast()).as("a run after the cut reads the new rows only").isLessThanOrEqualTo(20);
    assertThat(perRun.stream().skip(110).allMatch(n -> n <= 20)).isTrue();
    assertThat(perRun.stream().allMatch(n -> n <= TraceBook.ROWS_PER_READ + 1)).as("and no run more than a read")
        .isTrue();
    assertThat(rowsRead).isLessThan(150_000);
  }

  @Test
  void aCutTraceCountsEveryCallAndSaysHowManyItsPhasesDescribe() {
    for (int i = 0; i < TraceSummarizer.MAX_CALLS + 250; i++) {
      read();
    }
    String late = pending();
    drain();
    TraceEntity t = stored.get(TRACE);
    assertThat(t.getCalls()).isEqualTo(TraceSummarizer.MAX_CALLS + 251);
    assertThat(t.getSummary()).containsEntry(TraceBook.TIMELINE_CALLS, TraceSummarizer.MAX_CALLS)
        .containsEntry("decisions", Map.of("allowed", TraceSummarizer.MAX_CALLS + 250, "pending", 1))
        .containsEntry("waiting", true);
    assertThat(phases(t).stream().mapToInt(p -> ((Number) p.get("calls")).intValue()).sum())
        .isEqualTo(TraceSummarizer.MAX_CALLS);
    assertThat(t.getLastAt()).isEqualTo(clock);

    approve(late, 5_000);
    drain();
    t = stored.get(TRACE);
    assertThat(t.getCalls()).as("a later row of a counted call is no new call").isEqualTo(TraceSummarizer.MAX_CALLS + 251);
    assertThat(t.getSummary()).containsEntry("decisions", Map.of("allowed", TraceSummarizer.MAX_CALLS + 250,
        "approved", 1)).containsEntry("waiting", false).containsEntry("waitedForPeopleMs", 5_000);
    assertThat(JSON.writeValueAsString(t.getSummary()).length()).as("the stored summary is bounded")
        .isLessThan(64 * 1024);
  }

  @Test
  void aCallThePhasesDescribeThatIsDecidedLaterUpdatesThemAndTheCounts() {
    read();
    String early = pending();
    // More rows than one read holds: the decision lands after them, outside the first rows.
    for (int i = 0; i < TraceBook.ROWS_PER_READ / 2 + 10; i++) {
      read();
    }
    drain();
    TraceEntity t = stored.get(TRACE);
    assertThat(t.getSummary()).containsEntry("waiting", true);
    assertThat(phases(t)).anySatisfy(p -> assertThat(String.valueOf(p.get("summary"))).contains("waiting for you"));

    approve(early, 30_000);
    drain();
    t = stored.get(TRACE);
    assertThat(t.getSummary()).containsEntry("waiting", false).containsEntry("waitedForPeopleMs", 30_000);
    assertThat(phases(t)).noneSatisfy(p -> assertThat(String.valueOf(p.get("summary"))).contains("waiting for you"));
    assertThat(phases(t)).anySatisfy(p -> assertThat(String.valueOf(p.get("summary"))).contains("approved by you"));
  }

  @Test
  void aTimelineOfManyPhasesKeepsTheFirstAndSaysHowManyCallsTheyDescribe() {
    for (int i = 0; i < 120; i++) {
      read();
      approve(pending(), 1_000);
    }
    book.summarize(TRACE);
    TraceEntity t = stored.get(TRACE);
    assertThat(t.getCalls()).isEqualTo(240);
    assertThat(phases(t)).hasSize(TraceSummarizer.MAX_PHASES);
    int described = phases(t).stream().mapToInt(p -> ((Number) p.get("calls")).intValue()).sum();
    assertThat(t.getSummary()).containsEntry(TraceBook.TIMELINE_CALLS, described);
    assertThat(described).isLessThan(240);
  }

  // ── The audit rows, as the gateway writes them ─────────────────────────────────────────────────────────────

  /** A read of the logs (another connection than the changes, so that every change starts a phase of its own). */
  private void read() {
    String id = "tc_" + (++calls);
    add(row(id, CallPhase.RECEIVED, CallDecision.ALLOWED, "logs_search", "app-logs", Classification.Kind.READ,
        "read"));
    add(row(id, CallPhase.COMPLETED, CallDecision.ALLOWED, "logs_search", "app-logs", Classification.Kind.READ,
        "read"));
  }

  private String pending() {
    String id = "tc_" + (++calls);
    add(row(id, CallPhase.RECEIVED, CallDecision.PENDING, "sql_execute", "dev-db", Classification.Kind.MUTATION,
        "write").approval("ap_" + id, null, null, null, null, null));
    return id;
  }

  private void approve(String id, long waitMs) {
    add(row(id, CallPhase.RESOLVED, CallDecision.APPROVED, "sql_execute", "dev-db", Classification.Kind.MUTATION,
        "write").approval("ap_" + id, null, "app", null, null, null).timing(null, null, null, waitMs));
    add(row(id, CallPhase.COMPLETED, CallDecision.APPROVED, "sql_execute", "dev-db", Classification.Kind.MUTATION,
        "write").approval("ap_" + id, null, "app", null, null, null).timing(waitMs + 40, 8L, 32L, waitMs));
  }

  private ToolCallEntity.Builder row(String callId, CallPhase phase, CallDecision decision, String tool,
      String connection, Classification.Kind kind, String nature) {
    clock = clock.plusMillis(100);
    return ToolCallEntity.builder(callId, phase, clock, "dev", tool, decision)
        .tool("fake/" + tool, connection, Env.DEV)
        .client("codex", Map.of("name", "codex-mcp-client", "version", "1"), "2026-07-28")
        .arguments(Map.of("connection", connection, "query", "order sync"), "0".repeat(64))
        .facets(Map.of("nature", nature))
        .policy(kind, null)
        .trace(TRACE, "00f067aa0ba902b7")
        .chain("0".repeat(64), "0".repeat(64));
  }

  private void add(ToolCallEntity.Builder builder) {
    ToolCallEntity row = builder.build();
    ReflectionTestUtils.setField(row, "id", nextId++);
    table.add(row);
  }

  /** Runs the summarizer until it has read every row, as the worker does across its runs. */
  private void drain() {
    for (int i = 0; i < 10; i++) {
      book.summarize(TRACE);
    }
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> phases(TraceEntity t) {
    return (List<Map<String, Object>>) t.getSummary().get("phases");
  }

  // ── Stores in memory ───────────────────────────────────────────────────────────────────────────────────────

  private ToolCallRepository callRepository() {
    return (ToolCallRepository) Proxy.newProxyInstance(getClass().getClassLoader(),
        new Class<?>[] {ToolCallRepository.class}, (proxy, method, args) -> {
          List<ToolCallEntity> out = switch (method.getName()) {
            case "findByTraceIdOrderByIdAsc" -> limit(table.stream().filter(r -> args[0].equals(r.getTraceId()))
                .toList(), args.length > 1 ? (Pageable) args[1] : null);
            case "findByTraceIdAndIdGreaterThanOrderByIdAsc" -> limit(table.stream()
                .filter(r -> args[0].equals(r.getTraceId()) && r.getId() > (Long) args[1]).toList(), (Pageable) args[2]);
            case "findByCallIdOrderByIdAsc" -> table.stream().filter(r -> args[0].equals(r.getCallId())).toList();
            default -> throw new UnsupportedOperationException(method.getName());
          };
          rowsRead += out.size();
          return out;
        });
  }

  private static List<ToolCallEntity> limit(List<ToolCallEntity> rows, Pageable page) {
    return page == null ? rows : rows.subList(0, Math.min(rows.size(), page.getPageSize()));
  }

  @SuppressWarnings("unchecked")
  private TraceRepository traceRepository() {
    return (TraceRepository) Proxy.newProxyInstance(getClass().getClassLoader(),
        new Class<?>[] {TraceRepository.class}, (proxy, method, args) -> switch (method.getName()) {
          case "findById" -> Optional.ofNullable(stored.get((String) args[0]));
          case "existsById" -> stored.containsKey((String) args[0]);
          case "save" -> {
            TraceEntity t = (TraceEntity) args[0];
            // As the JSON column gives it back: numbers and maps as a parser reads them.
            ReflectionTestUtils.setField(t, "summary", JSON.readValue(JSON.writeValueAsString(t.getSummary()),
                Map.class));
            stored.put(t.getTraceId(), t);
            yield t;
          }
          default -> throw new UnsupportedOperationException(method.getName());
        });
  }

  private static final class NoTransactions implements PlatformTransactionManager {

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {}

    @Override
    public void rollback(TransactionStatus status) {}
  }
}
