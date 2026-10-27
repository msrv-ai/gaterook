package ai.msrv.gaterook.core.platform.db;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code gateway.db.*}: the two SQLite pools (ARCHITECTURE.md A10, report 14 §7.1).
 *
 * @param readerPoolSize    connections of the read-only pool, at most {@link SqlitePools#MAX_READER_POOL_SIZE}; 0 is
 *                          the one-pool fallback of the A10 gate: every transaction then uses the single writer
 * @param busyTimeout       how long SQLite waits for a lock inside native code ({@code busy_timeout})
 * @param connectionTimeout how long a transaction waits for a pooled connection (in Java, unmounted)
 */
@ConfigurationProperties("gateway.db")
public record GatewayDatabaseProperties(
    @DefaultValue("3") int readerPoolSize,
    @DefaultValue("5s") Duration busyTimeout,
    @DefaultValue("10s") Duration connectionTimeout) {

  public GatewayDatabaseProperties {
    if (readerPoolSize < 0 || readerPoolSize > SqlitePools.MAX_READER_POOL_SIZE) {
      // The virtual-thread scheduler is sized for at most this many pinned carriers (VirtualThreadScheduler).
      throw new IllegalArgumentException("gateway.db.reader-pool-size must be 0-" + SqlitePools.MAX_READER_POOL_SIZE);
    }
  }
}
