package ai.msrv.gaterook.connectors.logs;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.TestResult;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The steps of Test connection (report 07 §5): each one timed and shown, the first failure stops the test with what
 * happened and what to do, and the checks done so far stay visible.
 */
final class Probe {

  /** One step's work. */
  @FunctionalInterface
  interface Step<T> {
    T run() throws ConnectorException;
  }

  /** What a finished step shows. */
  @FunctionalInterface
  interface Detail<T> {
    String of(T value) throws ConnectorException;
  }

  private final ExecContext ctx;
  private final List<TestResult.Check> checks = new ArrayList<>();

  Probe(ExecContext ctx) {
    this.ctx = ctx;
  }

  <T> T step(String name, Step<T> step) throws ConnectorException {
    return step(name, step, String::valueOf);
  }

  <T> T step(String name, Step<T> step, Detail<T> detail) throws ConnectorException {
    long started = System.nanoTime();
    try {
      T value = step.run();
      checks.add(new TestResult.Check(name, true, (System.nanoTime() - started) / 1_000_000, detail.of(value)));
      return value;
    } catch (ConnectorException e) {
      checks.add(new TestResult.Check(name, false, (System.nanoTime() - started) / 1_000_000, e.getMessage()));
      throw e;
    }
  }

  /** A step that may fail without failing the test (an optional permission check). */
  <T> T optional(String name, Step<T> step, Detail<T> detail) {
    try {
      return step(name, step, detail);
    } catch (ConnectorException e) {
      return null;
    }
  }

  List<TestResult.Check> checks() {
    return List.copyOf(checks);
  }

  /** The test's answer after a failed step. */
  TestResult failure(ConnectorException e) {
    String code = switch (e.kind()) {
      case TIMEOUT -> "logs.timeout";
      case RESOURCE_UNAVAILABLE -> "logs.unreachable";
      case BAD_INPUT -> "logs.invalid";
      default -> "logs.refused";
    };
    Throwable cause = e.getCause();
    return TestResult.failure(code, e.getMessage(), cause == null ? null : cause.getClass().getSimpleName() + ": "
        + cause.getMessage()).withChecks(checks());
  }

  ExecContext ctx() {
    return ctx;
  }

  /** Hints about how a connection travels, the same for every HTTP backend. */
  static List<TestResult.Hint> transport(URI base, Settings.HttpAuth auth, Env env) {
    List<TestResult.Hint> out = new ArrayList<>();
    String host = base.getHost().toLowerCase(Locale.ROOT);
    boolean loopback = host.equals("localhost") || host.startsWith("127.") || host.equals("[::1]")
        || host.equals("::1");
    if ("http".equals(base.getScheme()) && auth.mode() != Settings.AuthMode.NONE && !loopback) {
      out.add(new TestResult.Hint(env == Env.PROD ? TestResult.Severity.DANGER : TestResult.Severity.WARNING,
          "logs.plain-http", "/url", "The token travels unencrypted to " + host + ". Use https://."));
    }
    return out;
  }
}
