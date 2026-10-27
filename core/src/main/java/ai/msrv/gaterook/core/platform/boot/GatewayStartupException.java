package ai.msrv.gaterook.core.platform.boot;

import java.util.Objects;

/** A start-up failure the core can name: it becomes the {@code reason} of the ready line. */
public class GatewayStartupException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final StartupReason reason;

  public GatewayStartupException(StartupReason reason, String message, Throwable cause) {
    super(message, cause);
    this.reason = Objects.requireNonNull(reason, "reason");
  }

  public GatewayStartupException(StartupReason reason, String message) {
    this(reason, message, null);
  }

  public StartupReason reason() {
    return reason;
  }
}
