package ai.msrv.gaterook.connector.api;

/** A secret can't be resolved: "…can't be resolved: AWS Secrets Manager isn't connected." */
public final class SecretUnavailableException extends ConnectorException {
  private static final long serialVersionUID = 1L;

  public SecretUnavailableException(String message, Throwable cause) {
    super(Kind.RESOURCE_UNAVAILABLE, message, cause);
  }
}
