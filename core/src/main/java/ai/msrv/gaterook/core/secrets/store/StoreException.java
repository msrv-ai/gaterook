package ai.msrv.gaterook.core.secrets.store;

/**
 * Why a store couldn't answer, in words a person (and, masked, an agent) can act on: "Version 1 of kv/stage/db is
 * destroyed", "The gateway's token can't read kv/data/prod/db". Never carries a value, a token or a response body.
 */
public final class StoreException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** What kind of failure, for Test reference and the store's state. */
  public enum Reason {
    /** No such secret, version or key. */
    NOT_FOUND,
    /** The version exists but was deleted, destroyed, disabled or is scheduled for deletion. */
    GONE,
    /** The store's identity may not read it. */
    DENIED,
    /** The store can't be reached, is sealed, answered from a standby, or the sign-in failed. */
    UNAVAILABLE,
    /** The reference or the store's settings can't be used. */
    INVALID
  }

  private final Reason reason;

  public StoreException(Reason reason, String message) {
    this(reason, message, null);
  }

  public StoreException(Reason reason, String message, Throwable cause) {
    super(message, cause, false, false);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }

  public static StoreException notFound(String message) {
    return new StoreException(Reason.NOT_FOUND, message);
  }

  public static StoreException gone(String message) {
    return new StoreException(Reason.GONE, message);
  }

  public static StoreException denied(String message) {
    return new StoreException(Reason.DENIED, message);
  }

  public static StoreException unavailable(String message, Throwable cause) {
    return new StoreException(Reason.UNAVAILABLE, message, cause);
  }

  public static StoreException invalid(String message) {
    return new StoreException(Reason.INVALID, message);
  }
}
