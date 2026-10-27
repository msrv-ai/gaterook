package ai.msrv.gaterook.core.secrets.store;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * One use of a store on behalf of a call (or a test): where derived credentials go and how long it may take. The
 * store hands every token it uses for the call — the Vault token, the GCP access token, the STS session key — to
 * {@link #taint}, so an echo of one is masked like the secret itself (ARCHITECTURE.md §9, ctx.taint).
 *
 * @param deadline requests to the store end by then; a store's own limits are shorter
 */
public record StoreCall(Consumer<CharSequence> taint, Instant deadline, Clock clock) {

  /** No store request takes longer, whatever the call allows. */
  public static final Duration MAX_REQUEST = Duration.ofSeconds(10);

  public StoreCall {
    Objects.requireNonNull(taint, "taint");
    Objects.requireNonNull(deadline, "deadline");
    Objects.requireNonNull(clock, "clock");
  }

  /** A call with {@code budget} from now. */
  public static StoreCall within(Consumer<CharSequence> taint, Duration budget, Clock clock) {
    return new StoreCall(taint, clock.instant().plus(budget), clock);
  }

  /** The timeout of the next request: what is left of the deadline, at most {@link #MAX_REQUEST}. */
  public Duration requestTimeout() {
    Duration left = Duration.between(clock.instant(), deadline);
    if (left.isNegative() || left.isZero()) {
      throw StoreException.unavailable("The call ran out of time before the store answered.", null);
    }
    return left.compareTo(MAX_REQUEST) > 0 ? MAX_REQUEST : left;
  }
}
