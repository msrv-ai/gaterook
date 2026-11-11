package ai.msrv.gaterook.plugin.protocol.host;

import ai.msrv.gaterook.connector.api.ExecContext;
import java.util.Optional;

/**
 * The egress of one call when the host gates the plugin's own TCP connections (the plugin host's TCP gate,
 * ARCHITECTURE.md §7.5): {@link ConnectorEndpoint} runs the plugin's code inside {@link #run}, which lets through only
 * the connections the call's grant allows, and explains a failed call or test by what the gate refused during it.
 */
public interface ScopedEgress extends ExecContext.Egress, AutoCloseable {

  /** What the gate refused, in the words a person reads, and how the environment's policy could allow it. */
  record Denial(Reason reason, String destination, String text, String suggestedCidr, String connection) {}

  /** Why a connection was refused. */
  enum Reason {
    /** Not one of the connection's destinations. */
    NOT_GRANTED,
    /** A private address the environment's policy doesn't allow for the connection. */
    PRIVATE_ADDRESS,
    /** Cloud metadata, link-local, reserved: never reachable. */
    ALWAYS_BLOCKED,
    /** One of GateRook's own ports on this computer. */
    CORE_PORT,
    /** Opened after the call that could open it ended. */
    OUTSIDE_CALL,
    /** Too many open connections. */
    LIMIT
  }

  /** Runs {@code body} with this call's grant bound to the calling thread and to the threads it starts. */
  <T, X extends Throwable> T run(ScopedValue.CallableOp<T, X> body) throws X;

  /** {@code action} run with this call's grant bound: a cancel that opens a connection of its own. */
  Runnable wrap(Runnable action);

  /** The last refusal of this call, if any. */
  Optional<Denial> lastDenial();

  /** The call ended: its grant opens nothing more (connections it opened stay open). */
  @Override
  void close();
}
