package ai.msrv.gaterook.core.audit;

/**
 * An audit row couldn't be written (ARCHITECTURE.md §5 step 8, §9 "Fail-closed audit"): the call must not run, or its
 * result must not reach the agent. The MCP layer answers {@code -32603}.
 */
public class AuditUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public AuditUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
