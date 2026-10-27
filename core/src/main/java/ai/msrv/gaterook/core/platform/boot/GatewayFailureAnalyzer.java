package ai.msrv.gaterook.core.platform.boot;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Human-readable start-up failures in {@code core.log} for the failures the core names itself. */
public final class GatewayFailureAnalyzer extends AbstractFailureAnalyzer<GatewayStartupException> {

  @Override
  protected FailureAnalysis analyze(Throwable rootFailure, GatewayStartupException cause) {
    String action = switch (cause.reason()) {
      case DB_LOCKED -> "Another GateRook core uses this gateway home. Quit it (or wait for it to exit) and start "
          + "GateRook again.";
      case DB_MIGRATION_FAILED -> "The copy taken before the migration is in the backups folder of the gateway home. "
          + "Report the log, then restore the copy or start the previous version.";
      case DB_CHECK_FAILED -> "The SQLite driver didn't apply the settings the core relies on. Report the log.";
      case BOOTSTRAP_TIMEOUT, BOOTSTRAP_INVALID -> "Start the core through GateRook. For development on the JVM, "
          + "use -Dgateway.dev=true and GW_DEV_INTERNAL_SECRET.";
      case PORT_IN_USE -> "Stop the process that listens on the port, or choose another port in GateRook.";
      case STARTUP_FAILED -> "Report the log.";
    };
    return new FailureAnalysis(cause.getMessage(), action, cause);
  }
}
