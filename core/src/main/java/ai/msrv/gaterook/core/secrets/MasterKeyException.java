package ai.msrv.gaterook.core.secrets;

/** A master key operation that can't be done now; the message is for the person, the problem picks the HTTP status. */
public class MasterKeyException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Why: the admin API answers 409 for state conflicts and 422 for bad input. */
  public enum Problem {
    /** The core is locked: restore or create first. */
    LOCKED,
    /** Restore and create only exist in locked mode. */
    NOT_LOCKED,
    /** A rotation is already running. */
    ROTATION_RUNNING,
    /** The backup can't be read (format, passphrase). */
    BAD_BACKUP,
    /** The backup doesn't hold the keys the stored secrets need. */
    MISSING_KEYS,
    /** The confirmation of a destructive operation is missing. */
    NOT_CONFIRMED,
    /** The file system refused. */
    IO
  }

  private final Problem problem;

  public MasterKeyException(Problem problem, String message) {
    this(problem, message, null);
  }

  public MasterKeyException(Problem problem, String message, Throwable cause) {
    super(message, cause);
    this.problem = problem;
  }

  public Problem problem() {
    return problem;
  }
}
