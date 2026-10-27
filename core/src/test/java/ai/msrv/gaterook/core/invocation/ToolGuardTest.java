package ai.msrv.gaterook.core.invocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.core.connectors.CallContext;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Rate limits, bulkhead, circuit breaker and deadline of tool calls (ARCHITECTURE.md §5 steps 5 and 9). */
class ToolGuardTest {

  private static final Limits LIMITS = new Limits(1000, 1000, 512 * 1024, 256 * 1024, 256 * 1024, 64 * 1024, 8192,
      Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofDays(7));

  private final ToolGuard guard = new ToolGuard(Duration.ofMillis(300));

  @AfterEach
  void stop() {
    guard.destroy();
  }

  /** The guard never touches handles; the context only insists on having a codec. */
  private static final ExecContext.HandleCodec NO_HANDLES = new ExecContext.HandleCodec() {
    @Override
    public String seal(String purpose, JsonNode payload, Duration ttl) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<JsonNode> open(String purpose, String handle) {
      return Optional.empty();
    }
  };

  private static CallContext ctx() {
    return CallContext.builder("tc_1", Env.DEV, LIMITS, Clock.systemUTC()).handles(NO_HANDLES).build();
  }

  @Test
  void ratesArePerPrincipalAndPerPrincipalAndTool() {
    for (int i = 0; i < 3; i++) {
      assertThat(guard.admit("tok_a", "sql_query", 5, 3).allowed()).isTrue();
    }
    ToolGuard.Admission perTool = guard.admit("tok_a", "sql_query", 5, 3);
    assertThat(perTool.allowed()).isFalse();
    assertThat(perTool.scope()).isEqualTo("tool");
    assertThat(perTool.retryAfterMs()).isPositive().isLessThanOrEqualTo(60_000);
    assertThat(guard.admit("tok_a", "sql_execute", 5, 3).allowed()).as("another tool").isTrue();
    // The principal's own permits ran out on the refused per-tool call too: 5 taken.
    ToolGuard.Admission perPrincipal = guard.admit("tok_a", "logs_query", 5, 3);
    assertThat(perPrincipal.allowed()).isFalse();
    assertThat(perPrincipal.scope()).isEqualTo("principal");
    assertThat(guard.admit("tok_b", "sql_query", 5, 3).allowed()).as("another principal").isTrue();

    guard.forgetPrincipal("tok_a");
    assertThat(guard.admit("tok_a", "sql_query", 5, 3).allowed()).as("a fresh start after revocation").isTrue();
  }

  @Test
  void aCallPastItsDeadlineIsCancelled() {
    CallContext ctx = ctx();
    AtomicBoolean interrupted = new AtomicBoolean();
    assertThatThrownBy(() -> guard.run("cn_slow", "slow-db", 4, Duration.ofMillis(200), ctx, () -> {
      try {
        Thread.sleep(5_000);
      } catch (InterruptedException e) {
        interrupted.set(true);
      }
      return "late";
    })).isInstanceOfSatisfying(ConnectorException.class, e -> {
      assertThat(e.kind()).isEqualTo(ConnectorException.Kind.TIMEOUT);
      assertThat(e.getMessage()).isEqualTo("No answer from slow-db within 0.2 s.");
    });
    assertThat(ctx.cancelled()).isTrue();
  }

  @Test
  void aTimeoutCountsOnceWhateverTheCancelledCallDoesThen() throws Exception {
    // One connector maps the interrupt to TIMEOUT (a second failure, if both sides recorded: the breaker would open at
    // the second timeout), one ignores it and answers (a success each): 4 successes and 3 timeouts are 43 % failures,
    // below the 50 % that opens the breaker.
    for (boolean mapsInterrupt : List.of(true, false)) {
      String id = mapsInterrupt ? "cn_maps" : "cn_ignores";
      for (int i = 0; i < 4; i++) {
        assertThat(guard.run(id, id, 4, Duration.ofSeconds(5), ctx(), () -> "ok")).isEqualTo("ok");
      }
      CountDownLatch ended = new CountDownLatch(3);
      for (int i = 0; i < 3; i++) {
        assertThatThrownBy(() -> guard.run(id, id, 4, Duration.ofMillis(50), ctx(), () -> {
          try {
            Thread.sleep(5_000);
            return "late";
          } catch (InterruptedException e) {
            if (mapsInterrupt) {
              throw ConnectorException.timeout("interrupted");
            }
            return "answered anyway";
          } finally {
            ended.countDown();
          }
        })).isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.TIMEOUT));
      }
      assertThat(ended.await(5, TimeUnit.SECONDS)).as("every cancelled call ended").isTrue();
      Thread.sleep(50);
      CircuitBreaker.Metrics metrics = guard.metrics(id);
      assertThat(metrics.getNumberOfFailedCalls()).as(id).isEqualTo(3);
      assertThat(metrics.getNumberOfSuccessfulCalls()).as(id).isEqualTo(4);
      assertThat(guard.state(id)).as(id).isEqualTo(CircuitBreaker.State.CLOSED);
    }
  }

  @Test
  void theBulkheadRefusesCallsBeyondTheConnectionsLimit() throws Exception {
    CountDownLatch running = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread first = Thread.ofVirtual().start(() -> {
      try {
        guard.run("cn_busy", "busy-db", 1, Duration.ofSeconds(5), ctx(), () -> {
          running.countDown();
          try {
            release.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return "ok";
        });
      } catch (ConnectorException e) {
        throw new AssertionError(e);
      }
    });
    assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
    assertThatThrownBy(() -> guard.run("cn_busy", "busy-db", 1, Duration.ofSeconds(5), ctx(), () -> "second"))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_UNAVAILABLE);
          assertThat(e.getMessage()).contains("busy-db is busy: 1 calls are running");
        });
    release.countDown();
    first.join();
    assertThat(guard.run("cn_busy", "busy-db", 1, Duration.ofSeconds(5), ctx(), () -> "third")).isEqualTo("third");
  }

  @Test
  void onlyUnavailabilityAndTimeoutsTripTheBreaker() throws Exception {
    for (int i = 0; i < 10; i++) {
      assertThatThrownBy(() -> guard.run("cn_bad", "bad-input-db", 4, Duration.ofSeconds(5), ctx(), () -> {
        throw ConnectorException.badInput("syntax error");
      })).isInstanceOfSatisfying(ConnectorException.class,
          e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.BAD_INPUT));
    }
    assertThat(guard.state("cn_bad")).as("an agent's bad SQL is not the database failing")
        .isEqualTo(CircuitBreaker.State.CLOSED);

    for (int i = 0; i < 5; i++) {
      assertThatThrownBy(() -> guard.run("cn_down", "down-db", 4, Duration.ofSeconds(5), ctx(), () -> {
        throw ConnectorException.unavailable("connection refused", null);
      })).isInstanceOf(ConnectorException.class);
    }
    assertThat(guard.state("cn_down")).isEqualTo(CircuitBreaker.State.OPEN);
    assertThatThrownBy(() -> guard.run("cn_down", "down-db", 4, Duration.ofSeconds(5), ctx(), () -> "never"))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_UNAVAILABLE);
          assertThat(e.getMessage()).contains("down-db keeps failing");
        });

    // After the open period one call goes through; its success closes the breaker.
    Thread.sleep(400);
    assertThat(guard.run("cn_down", "down-db", 4, Duration.ofSeconds(5), ctx(), () -> "back")).isEqualTo("back");
    assertThat(guard.state("cn_down")).isEqualTo(CircuitBreaker.State.CLOSED);

    guard.forget("cn_down");
    assertThat(guard.state("cn_down")).isEqualTo(CircuitBreaker.State.CLOSED);
  }

  @Test
  void anUnexpectedExceptionIsAnInternalError() {
    assertThatThrownBy(() -> guard.run("cn_x", "x", 4, Duration.ofSeconds(5), ctx(), () -> {
      throw new IllegalStateException("bug");
    })).isInstanceOfSatisfying(ConnectorException.class, e -> {
      assertThat(e.kind()).isEqualTo(ConnectorException.Kind.INTERNAL);
      assertThat(e.getMessage()).isEqualTo("Internal error in the connector");
    });
    assertThat(ToolGuard.countsForBreaker(ConnectorException.timeout("t"))).isTrue();
    assertThat(ToolGuard.countsForBreaker(ConnectorException.denied("d"))).isFalse();
    assertThat(ToolGuard.countsForBreaker(new java.util.concurrent.TimeoutException())).isTrue();
  }
}
