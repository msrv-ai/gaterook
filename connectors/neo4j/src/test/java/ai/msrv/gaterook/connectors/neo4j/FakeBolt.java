package ai.msrv.gaterook.connectors.neo4j;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ExecContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import org.neo4j.driver.AccessMode;
import org.neo4j.driver.Record;
import org.neo4j.driver.Value;
import org.neo4j.driver.internal.InternalRecord;

/**
 * A Neo4j server behind the {@link Bolt} seam: it answers statements by scripted rules (the first rule whose test
 * matches the statement wins) and records every transaction, statement, commit and lease, so a test can check what
 * reached the server, in which access mode, and what never did.
 */
final class FakeBolt implements Bolt.Source {

  /** One scripted answer: records and a summary, or a failure of the statement or of its commit. */
  record Answer(List<String> keys, List<List<Value>> rows, Bolt.Summary summary, RuntimeException failure,
      RuntimeException commitFailure) {

    static Answer rows(List<String> keys, List<List<Value>> rows) {
      return new Answer(keys, rows, FakeBolt.summary(null), null, null);
    }

    static Answer plan(String type) {
      return new Answer(List.of(), List.of(), new Bolt.Summary(type, Map.of(), new Bolt.Plan("ProduceResults", 3.0,
          null, List.of(new Bolt.Plan("Create", 3.0, "(n:Person)", List.of()))), List.of(), AGENT, "5.8"), null,
          null);
    }

    static Answer fails(RuntimeException e) {
      return new Answer(List.of(), List.of(), FakeBolt.summary(null), e, null);
    }

    Answer withSummary(Bolt.Summary s) {
      return new Answer(keys, rows, s, failure, commitFailure);
    }

    Answer failingCommit(RuntimeException e) {
      return new Answer(keys, rows, summary, failure, e);
    }
  }

  static final String AGENT = "Neo4j/5.26.31";

  static Bolt.Summary summary(String type) {
    return new Bolt.Summary(type, Map.of(), null, List.of(), AGENT, "5.8");
  }

  static Bolt.Summary counters(String type, Map<String, Integer> counters) {
    return new Bolt.Summary(type, new LinkedHashMap<>(counters), null, List.of(), AGENT, "5.8");
  }

  /** One transaction as the server saw it. */
  static final class Tx {
    final AccessMode mode;
    final String database;
    final Duration timeout;
    final Map<String, Object> metadata;
    final int fetchSize;
    final List<String> statements = new CopyOnWriteArrayList<>();
    final List<Map<String, Object>> parameters = new CopyOnWriteArrayList<>();
    volatile boolean committed;
    volatile boolean closed;

    Tx(AccessMode mode, String database, Duration timeout, Map<String, Object> metadata, int fetchSize) {
      this.mode = mode;
      this.database = database;
      this.timeout = timeout;
      this.metadata = metadata;
      this.fetchSize = fetchSize;
    }
  }

  private record Rule(Predicate<String> test, Answer answer) {}

  final List<Tx> transactions = new CopyOnWriteArrayList<>();
  final List<String> events = new CopyOnWriteArrayList<>();
  private final List<Rule> rules = new CopyOnWriteArrayList<>();
  volatile RuntimeException beginFailure;
  volatile int leases;
  volatile int released;
  volatile int fresh;
  volatile boolean closed;

  /** Answers statements that start with {@code prefix}. */
  FakeBolt on(String prefix, Answer answer) {
    rules.add(new Rule(s -> s.startsWith(prefix), answer));
    return this;
  }

  FakeBolt when(Predicate<String> test, Answer answer) {
    rules.add(new Rule(test, answer));
    return this;
  }

  /** Every statement that reached the server, in order. */
  List<String> statements() {
    List<String> out = new ArrayList<>();
    transactions.forEach(t -> out.addAll(t.statements));
    return out;
  }

  Tx last() {
    return transactions.getLast();
  }

  @Override
  public Bolt.Lease lease(ConnectionConfig cfg, Neo4jSettings settings, Neo4jAuth auth, ExecContext ctx) {
    leases++;
    events.add("lease " + cfg.id() + " " + auth.method() + " " + auth.user());
    return lease();
  }

  @Override
  public Bolt.Lease fresh(Neo4jSettings settings, Neo4jAuth auth, ExecContext ctx) {
    fresh++;
    events.add("fresh " + auth.method() + " " + auth.user());
    return lease();
  }

  private Bolt.Lease lease() {
    return new Bolt.Lease() {
      @Override
      public Bolt.Client client() {
        return FakeBolt.this::begin;
      }

      @Override
      public void close() {
        released++;
      }
    };
  }

  private Bolt.Tx begin(AccessMode mode, String database, Duration timeout, Map<String, Object> metadata,
      int fetchSize) {
    if (beginFailure != null) {
      throw beginFailure;
    }
    Tx tx = new Tx(mode, database, timeout, metadata, fetchSize);
    transactions.add(tx);
    return new Bolt.Tx() {
      private Answer lastAnswer;

      @Override
      public Bolt.Run run(String cypher, Map<String, Object> parameters) {
        tx.statements.add(cypher);
        tx.parameters.add(parameters);
        Answer a = rules.stream().filter(r -> r.test().test(cypher)).map(Rule::answer).findFirst()
            .orElse(Answer.rows(List.of(), List.of()));
        lastAnswer = a;
        if (a.failure() != null) {
          throw a.failure();
        }
        Iterator<List<Value>> rows = a.rows().iterator();
        return new Bolt.Run() {
          @Override
          public List<String> keys() {
            return a.keys();
          }

          @Override
          public boolean hasNext() {
            return rows.hasNext();
          }

          @Override
          public Record next() {
            return new InternalRecord(a.keys(), rows.next());
          }

          @Override
          public Bolt.Summary consume() {
            return a.summary();
          }
        };
      }

      @Override
      public void commit() {
        events.add("commit");
        if (lastAnswer != null && lastAnswer.commitFailure() != null) {
          throw lastAnswer.commitFailure();
        }
        tx.committed = true;
      }

      @Override
      public void close() {
        tx.closed = true;
      }
    };
  }

  @Override
  public void evict(String connectionId) {
    events.add("evict " + connectionId);
  }

  @Override
  public void close() {
    closed = true;
  }
}
