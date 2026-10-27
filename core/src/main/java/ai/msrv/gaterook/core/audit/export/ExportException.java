package ai.msrv.gaterook.core.audit.export;

/**
 * An export or a test that didn't reach its destination, with what the person should know: what the destination
 * answered ("403 Forbidden: Access Denied …"), or what this computer couldn't do. The text is masked with the run's
 * secrets before it is stored as the destination's {@code last_error}.
 */
public class ExportException extends Exception {

  private static final long serialVersionUID = 1L;

  public ExportException(String message) {
    super(message);
  }

  public ExportException(String message, Throwable cause) {
    super(message, cause);
  }
}
