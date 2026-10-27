package ai.msrv.gaterook.connectors.support.egress;

/**
 * An exception that carries the {@link AddressPolicy.Verdict} that stopped a connection. Thrown as
 * {@link java.net.UnknownHostException} by {@link GuardDns} (OkHttp's {@code Dns} contract) and as
 * {@link java.net.SocketException} by {@link GuardSocketFactory}; {@link EgressClients} finds either in the cause chain.
 */
public interface EgressDenial {

  AddressPolicy.Verdict verdict();

  /** The host as the caller wrote it (a name or a literal). */
  String host();

  /** The first denial in the cause chain of {@code failure}, or {@code null}. */
  static EgressDenial find(Throwable failure) {
    for (Throwable t = failure; t != null; t = t.getCause()) {
      if (t instanceof EgressDenial denial) {
        return denial;
      }
      for (Throwable suppressed : t.getSuppressed()) {
        if (suppressed instanceof EgressDenial denial) {
          return denial;
        }
      }
    }
    return null;
  }
}
