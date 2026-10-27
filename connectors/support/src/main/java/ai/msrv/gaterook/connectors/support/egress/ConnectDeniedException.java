package ai.msrv.gaterook.connectors.support.egress;

import java.io.Serial;
import java.net.SocketException;
import java.util.Objects;

/** {@link GuardSocketFactory} refused the address a TCP connection was about to go to. */
public final class ConnectDeniedException extends SocketException implements EgressDenial {

  @Serial
  private static final long serialVersionUID = 1L;

  private final transient AddressPolicy.Verdict verdict;
  private final String host;

  public ConnectDeniedException(String host, AddressPolicy.Verdict verdict) {
    super(Objects.requireNonNull(verdict, "verdict").text());
    this.host = host;
    this.verdict = verdict;
  }

  @Override
  public AddressPolicy.Verdict verdict() {
    return verdict;
  }

  @Override
  public String host() {
    return host;
  }
}
