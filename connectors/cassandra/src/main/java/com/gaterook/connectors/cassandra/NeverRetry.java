package com.gaterook.connectors.cassandra;

import com.datastax.oss.driver.api.core.ConsistencyLevel;
import com.datastax.oss.driver.api.core.context.DriverContext;
import com.datastax.oss.driver.api.core.retry.RetryDecision;
import com.datastax.oss.driver.api.core.retry.RetryPolicy;
import com.datastax.oss.driver.api.core.retry.RetryVerdict;
import com.datastax.oss.driver.api.core.servererrors.CoordinatorException;
import com.datastax.oss.driver.api.core.servererrors.WriteType;
import com.datastax.oss.driver.api.core.session.Request;

/**
 * The retry policy of approved changes (execution profile {@code change}): every failure is the answer, on the node
 * that gave it. The statements are also marked non-idempotent and the profile has no speculative executions, so a
 * change is sent to one node once. The driver builds it by name, with this constructor.
 */
public final class NeverRetry implements RetryPolicy {

  public NeverRetry(DriverContext context, String profileName) {
    // Nothing to configure.
  }

  @Override
  public RetryVerdict onReadTimeoutVerdict(Request request, ConsistencyLevel cl, int blockFor, int received,
      boolean dataPresent, int retryCount) {
    return RetryVerdict.RETHROW;
  }

  @Override
  public RetryVerdict onWriteTimeoutVerdict(Request request, ConsistencyLevel cl, WriteType writeType, int blockFor,
      int received, int retryCount) {
    return RetryVerdict.RETHROW;
  }

  @Override
  public RetryVerdict onUnavailableVerdict(Request request, ConsistencyLevel cl, int required, int alive,
      int retryCount) {
    return RetryVerdict.RETHROW;
  }

  @Override
  public RetryVerdict onRequestAbortedVerdict(Request request, Throwable error, int retryCount) {
    return RetryVerdict.RETHROW;
  }

  @Override
  public RetryVerdict onErrorResponseVerdict(Request request, CoordinatorException error, int retryCount) {
    return RetryVerdict.RETHROW;
  }

  @Override
  @Deprecated
  public RetryDecision onReadTimeout(Request request, ConsistencyLevel cl, int blockFor, int received,
      boolean dataPresent, int retryCount) {
    return RetryDecision.RETHROW;
  }

  @Override
  @Deprecated
  public RetryDecision onWriteTimeout(Request request, ConsistencyLevel cl, WriteType writeType, int blockFor,
      int received, int retryCount) {
    return RetryDecision.RETHROW;
  }

  @Override
  @Deprecated
  public RetryDecision onUnavailable(Request request, ConsistencyLevel cl, int required, int alive,
      int retryCount) {
    return RetryDecision.RETHROW;
  }

  @Override
  @Deprecated
  public RetryDecision onRequestAborted(Request request, Throwable error, int retryCount) {
    return RetryDecision.RETHROW;
  }

  @Override
  @Deprecated
  public RetryDecision onErrorResponse(Request request, CoordinatorException error, int retryCount) {
    return RetryDecision.RETHROW;
  }

  @Override
  public void close() {
    // Nothing held.
  }
}
