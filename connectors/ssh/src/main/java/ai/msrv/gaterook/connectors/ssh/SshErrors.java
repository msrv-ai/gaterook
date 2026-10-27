package ai.msrv.gaterook.connectors.ssh;

import ai.msrv.gaterook.connector.api.ConnectorException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.nio.channels.InterruptedByTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.util.Locale;

/**
 * What went wrong, in the kit's words: what happened and what to do (report 06 §5). Host key problems are
 * {@code DENIED} (nothing is wrong with the host, the person must look), an unreachable host is
 * {@code RESOURCE_UNAVAILABLE} (it counts for the circuit breaker), a refused sign-in {@code RESOURCE_ERROR}.
 */
final class SshErrors {

  private SshErrors() {}

  static ConnectorException connect(SshSettings.Target t, IOException e) {
    Throwable root = root(e);
    String text = String.valueOf(root.getMessage()).toLowerCase(Locale.ROOT);
    if (root instanceof ConnectException || text.contains("connection refused")) {
      return ConnectorException.unavailable("Connection refused on port " + t.port() + " of " + t.host()
          + ". Is sshd running?", e);
    }
    if (root instanceof UnknownHostException || root instanceof UnresolvedAddressException
        || text.contains("unresolved")) {
      return ConnectorException.unavailable("Can't resolve " + t.host() + ". Check the name, the VPN or the hosts "
          + "file, then test again.", e);
    }
    if (root instanceof NoRouteToHostException) {
      return ConnectorException.unavailable("No route to " + t.address() + ". Check the VPN, then test again.", e);
    }
    return ConnectorException.unavailable("Can't reach " + t.address() + ". Check the VPN or the host, then test "
        + "again.", e);
  }

  /** The sign-in failed: maybe because the host key wasn't trusted (MINA reports both the same way). */
  static ConnectorException auth(SshSettings.Target t, HostKeys.Check check, SshLogins.Login login, IOException e) {
    ConnectorException hostKey = hostKey(t, check);
    if (hostKey != null) {
      return hostKey;
    }
    String text = String.valueOf(root(e).getMessage()).toLowerCase(Locale.ROOT);
    if (text.contains("negotiate") || text.contains("no common")) {
      return ConnectorException.resourceError("GateRook and " + t.address() + " have no algorithms in common. "
          + "For an old server, turn on Allow old algorithms (Advanced).", e);
    }
    if (root(e) instanceof InterruptedByTimeoutException || text.contains("timeout") || text.contains("timed out")) {
      return ConnectorException.timeout(t.address() + " didn't finish the sign-in in time.");
    }
    if (text.contains("no more authentication methods") || text.contains("authentication")) {
      String what = switch (login.method()) {
        case PASSWORD -> "the password";
        case PRIVATE_KEY -> login.notAfter() == null ? "the key" : "the key or its certificate";
        case VAULT_SSH_CA -> "the certificate. Check TrustedUserCAKeys and the principals on the host";
      };
      return ConnectorException.resourceError(t.label() + " rejected " + what + ".", e);
    }
    return ConnectorException.unavailable("The connection to " + t.address() + " closed during the sign-in.", e);
  }

  /** A host key that isn't trusted, or {@code null} when the verifier accepted it. */
  static ConnectorException hostKey(SshSettings.Target t, HostKeys.Check check) {
    if (check == null) {
      return null;
    }
    return switch (check.verdict()) {
      case UNKNOWN -> ConnectorException.denied("The host key of " + t.address() + " isn't trusted yet. Test the "
          + "connection in GateRook and compare the fingerprint before you trust it.");
      case CHANGED -> ConnectorException.denied("The host key of " + t.address() + " changed. GateRook sent "
          + "nothing to it. Replace the pinned key only if you know why the host was rebuilt.");
      case REVOKED -> ConnectorException.denied("The host key of " + t.address() + " is revoked in known_hosts.");
      default -> null;
    };
  }

  static ConnectorException forwarding(SshSettings.Target bastion, IOException e) {
    return ConnectorException.resourceError("The bastion " + bastion.address() + " refused port forwarding. Its "
        + "certificate needs permit-port-forwarding, and sshd needs AllowTcpForwarding.", e);
  }

  static Throwable root(Throwable e) {
    Throwable t = e;
    while (t.getCause() != null && t.getCause() != t) {
      t = t.getCause();
    }
    return t;
  }
}
