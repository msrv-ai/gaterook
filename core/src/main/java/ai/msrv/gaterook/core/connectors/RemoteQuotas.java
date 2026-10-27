package ai.msrv.gaterook.core.connectors;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Quotas of remote APIs that every connection of a project or account shares ({@code ExecContext.acquire}):
 * {@code gcp:<project>}, {@code aws:<account>:<region>:<api>}. One rate limiter per key, sized by the first caller's
 * permits per minute; a call that finds no permit is told so at once and the connector answers "try again in a minute"
 * instead of waiting.
 */
public final class RemoteQuotas {

  private static final Pattern KEY = Pattern.compile("[a-z0-9][a-z0-9:._/-]{0,199}");

  private final Map<String, RateLimiter> limiters = new ConcurrentHashMap<>();

  public boolean acquire(String key, int permitsPerMinute) {
    if (key == null || !KEY.matcher(key).matches()) {
      throw new IllegalArgumentException("Invalid quota key");
    }
    if (permitsPerMinute <= 0) {
      return false;
    }
    RateLimiter limiter = limiters.computeIfAbsent(key, k -> RateLimiter.of(k, RateLimiterConfig.custom()
        .limitForPeriod(permitsPerMinute).limitRefreshPeriod(Duration.ofMinutes(1)).timeoutDuration(Duration.ZERO)
        .build()));
    return limiter.acquirePermission();
  }
}
