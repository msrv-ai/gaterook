package ai.msrv.gaterook.core.audit;

/**
 * Calls sent from Activity didn't reach the destination (Send to SIEM): what the destination answered, masked. The
 * admin API answers {@code 502}: the request was fine, the destination wasn't.
 */
public class AuditExportFailedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public AuditExportFailedException(String message, Throwable cause) {
    super(message, cause);
  }
}
