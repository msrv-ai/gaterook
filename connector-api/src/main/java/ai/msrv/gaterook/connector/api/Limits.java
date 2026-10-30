package ai.msrv.gaterook.connector.api;

import java.time.Duration;

/**
 * Effective limits of one call = min(environment policy, connection, endpoint, tool override), computed by the
 * core.
 *
 * @param maxRows          dev 1,000 · prod 100
 * @param maxLines         logs: 1,000 · 200
 * @param maxResponseBytes 512 KB
 * @param maxOutputBytes   ssh stdout 256 KB
 * @param maxStderrBytes   64 KB
 * @param maxCellChars     8 KB
 * @param statementTimeout 5 s · 3 s
 * @param commandTimeout   30 s
 * @param toolTimeout      dev/stage 30 s · prod 5 s; connector-specific limits may be lower
 * @param maxRange         maximum log query duration; {@code null} means unrestricted (the default)
 */
public record Limits(int maxRows, int maxLines, long maxResponseBytes, long maxRequestBytes, long maxOutputBytes,
    long maxStderrBytes, int maxCellChars, Duration statementTimeout, Duration commandTimeout,
    Duration toolTimeout, Duration maxRange) {

  /** Time a command needs past its own timeout to stop: TERM, two seconds, KILL and the channel's close. */
  public static final Duration COMMAND_STOP_MARGIN = Duration.ofMillis(2_500);

  /** A call answered within the client's request ends before the client gives up: Codex waits 60 s for a tool. */
  public static final Duration MAX_COMMAND_CALL = Duration.ofSeconds(55);

  /** An approved call runs on the gateway's own time after the decision, and at most this long. */
  public static final Duration MAX_APPROVED_COMMAND_CALL = Duration.ofMinutes(10);

  /**
   * The deadline of a call to a tool that runs a command bounded by {@link #commandTimeout()}
   * ({@link Connector#runsCommand}): long enough for the command's own timeout and its stop
   * ({@link #COMMAND_STOP_MARGIN}), at most {@link #MAX_COMMAND_CALL} while the client waits for the answer and
   * {@link #MAX_APPROVED_COMMAND_CALL} for a call the gateway runs after a person approved it, and never shorter
   * than the tool timeout.
   *
   * @param approved the call runs after a decision, not within the client's request
   */
  public Duration commandCallTimeout(boolean approved) {
    if (commandTimeout == null) {
      return toolTimeout;
    }
    Duration wanted = commandTimeout.plus(COMMAND_STOP_MARGIN);
    Duration cap = approved ? MAX_APPROVED_COMMAND_CALL : MAX_COMMAND_CALL;
    Duration capped = wanted.compareTo(cap) <= 0 ? wanted : cap;
    return toolTimeout != null && toolTimeout.compareTo(capped) > 0 ? toolTimeout : capped;
  }
}
