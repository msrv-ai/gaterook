package ai.msrv.gaterook.core.notifications;

import ai.msrv.gaterook.connector.api.TestResult;
import java.util.Objects;

/**
 * A channel's tokens didn't pass the test that runs before they are saved ({@code PUT /api/notifications/{channel}},
 * ARCHITECTURE.md §6): nothing was saved; the admin API answers 422 with the test.
 */
public final class NotifyTestFailedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final transient TestResult result;

  public NotifyTestFailedException(TestResult result) {
    super(result.error() != null ? result.error().message() : result.needsConfirmation() != null
        ? result.needsConfirmation().text() : "The test failed");
    this.result = Objects.requireNonNull(result, "result");
  }

  public TestResult result() {
    return result;
  }
}
