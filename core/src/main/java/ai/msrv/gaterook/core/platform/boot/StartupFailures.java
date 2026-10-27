package ai.msrv.gaterook.core.platform.boot;

import java.net.BindException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.flywaydb.core.api.FlywayException;
import org.springframework.boot.tomcat.ConnectorStartFailedException;
import org.springframework.boot.web.server.PortInUseException;

/** Turns whatever stopped the start into a {@link StartupReason} and a short, secret-free detail. */
final class StartupFailures {

  /** SQLite result codes for a database another connection holds: SQLITE_BUSY and SQLITE_LOCKED. */
  private static final Set<Integer> SQLITE_BUSY_CODES = Set.of(5, 6);

  /** The classified failure; {@code port} is set for {@link StartupReason#PORT_IN_USE}. */
  record Failure(StartupReason reason, String detail, Integer port) {}

  private StartupFailures() {}

  static Failure classify(Throwable failure, int configuredPort) {
    Optional<GatewayStartupException> named = find(failure, GatewayStartupException.class);
    if (named.isPresent()) {
      return new Failure(named.get().reason(), named.get().getMessage(), null);
    }
    Optional<PortInUseException> portInUse = find(failure, PortInUseException.class);
    if (portInUse.isPresent()) {
      return portInUse(portInUse.get().getPort());
    }
    Optional<ConnectorStartFailedException> connector = find(failure, ConnectorStartFailedException.class);
    if (connector.isPresent()) {
      return portInUse(connector.get().getPort());
    }
    if (find(failure, BindException.class).isPresent()) {
      return portInUse(configuredPort);
    }
    if (sqliteBusy(failure)) {
      return new Failure(StartupReason.DB_LOCKED,
          "The gateway database is locked by another process; close it and start MCP Gateway again", null);
    }
    if (find(failure, FlywayException.class).isPresent()) {
      return new Failure(StartupReason.DB_MIGRATION_FAILED,
          "A database migration failed; the database was not changed after the last good version", null);
    }
    Throwable root = failure;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    return new Failure(StartupReason.STARTUP_FAILED,
        "The core failed to start (" + root.getClass().getSimpleName() + "); see core.log", null);
  }

  private static Failure portInUse(int port) {
    return new Failure(StartupReason.PORT_IN_USE, "Address already in use: 127.0.0.1:" + port, port);
  }

  private static boolean sqliteBusy(Throwable failure) {
    for (Throwable t : chain(failure)) {
      if (t instanceof SQLException sql && SQLITE_BUSY_CODES.contains(sql.getErrorCode() & 0xFF)) {
        return true;
      }
    }
    return false;
  }

  private static <T extends Throwable> Optional<T> find(Throwable failure, Class<T> type) {
    for (Throwable t : chain(failure)) {
      if (type.isInstance(t)) {
        return Optional.of(type.cast(t));
      }
    }
    return Optional.empty();
  }

  private static Iterable<Throwable> chain(Throwable failure) {
    Set<Throwable> seen = new HashSet<>();
    List<Throwable> out = new ArrayList<>();
    for (Throwable t = failure; t != null && seen.add(t); t = t.getCause()) {
      out.add(t);
      for (Throwable suppressed : t.getSuppressed()) {
        if (seen.add(suppressed)) {
          out.add(suppressed);
        }
      }
    }
    return out;
  }
}
