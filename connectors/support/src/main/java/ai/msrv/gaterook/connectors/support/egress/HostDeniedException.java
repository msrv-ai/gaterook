package ai.msrv.gaterook.connectors.support.egress;

import java.io.Serial;
import java.net.UnknownHostException;
import java.util.Objects;

/** {@link GuardDns} refused a name: blocked, or one of its addresses is not allowed. */
public final class HostDeniedException extends UnknownHostException implements EgressDenial {

  @Serial
  private static final long serialVersionUID = 1L;

  private final transient AddressPolicy.Verdict verdict;
  private final String host;

  public HostDeniedException(String host, AddressPolicy.Verdict verdict) {
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
