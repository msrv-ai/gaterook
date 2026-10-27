package ai.msrv.gaterook.core.platform;

import java.util.Optional;

/**
 * Whether the core runs locked (ARCHITECTURE.md §7.1, case b): the gateway holds local secrets, but the master keyset is
 * missing or doesn't open them. Then only {@code /api/health}, {@code /api/events}, {@code /api/master-key} and its
 * {@code restore}/{@code create}, and {@code /internal/files/grant} answer; {@code /mcp/*} gets 503 with JSON-RPC
 * {@code -32603}. Written by the {@code secrets} module (which owns the key), read by the ready line and the local
 * listener; Restore unlocks without a restart.
 */
public final class MasterKeyLock {

  /** The {@code locked} value of the ready line and of {@code /api/health}. */
  public enum Reason {
    /** No keyset file. */
    MASTER_KEY_MISSING,
    /** A keyset that can't be read or doesn't hold the keys the stored secrets need. */
    MASTER_KEY_INVALID
  }

  private volatile Reason reason;

  public Optional<Reason> reason() {
    return Optional.ofNullable(reason);
  }

  public boolean locked() {
    return reason != null;
  }

  public void lock(Reason value) {
    this.reason = java.util.Objects.requireNonNull(value, "value");
  }

  public void unlock() {
    this.reason = null;
  }

  /** What the agent and the API are told while locked. */
  public String message() {
    Reason current = reason;
    if (current == null) {
      return "MCP Gateway is not locked";
    }
    return current == Reason.MASTER_KEY_MISSING ? "MCP Gateway is locked: the master key was not found"
        : "MCP Gateway is locked: the master key can't open the stored secrets";
  }
}
