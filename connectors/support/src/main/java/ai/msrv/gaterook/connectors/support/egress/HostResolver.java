package ai.msrv.gaterook.connectors.support.egress;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Name resolution behind {@link GuardDns}. The system resolver in production; tests substitute a map of names, which
 * is how DNS rebinding (an allowed answer first, a private one next) is reproduced.
 */
@FunctionalInterface
public interface HostResolver {

  /** The system resolver ({@link InetAddress#getAllByName}). */
  HostResolver SYSTEM = host -> List.of(InetAddress.getAllByName(host));

  /** Every address of {@code host}; never empty. */
  List<InetAddress> resolve(String host) throws UnknownHostException;
}
