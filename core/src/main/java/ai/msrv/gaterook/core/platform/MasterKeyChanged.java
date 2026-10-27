package ai.msrv.gaterook.core.platform;

/**
 * The master key changed (published by {@code secrets}). Listeners use {@code @TransactionalEventListener(phase =
 * AFTER_COMMIT)} as ARCHITECTURE.md §2.2 says: the event goes out in a transaction once the change has committed and
 * the new keys are in use, never for a change that rolled back, and never before the application is ready (a rotation
 * resumed at start may finish earlier: its event waits).
 *
 * <p>What a listener does depends on {@link Kind}. The keys derived from the master key ({@link HmacKeys}) stay the
 * same across {@link Kind#ROTATED}, {@link Kind#RESTORED} and {@link Kind#PROTECTION_CHANGED}: nothing MAC'd before
 * needs attention. {@link Kind#CREATED} and {@link Kind#REPLACED_LOST} mean new derived keys: handles and request
 * states made before stop verifying (they expire anyway), and no stored {@code mac} verifies any more. A new key can't
 * vouch for rows written while it didn't exist — a process of the same OS account may have deleted the key file and
 * edited {@code gateway.db} (§9) — so {@code RowMac} never re-signs rows on these kinds: every MAC-protected row reads
 * "Changed outside MCP Gateway" (fail closed) until the person reviews and confirms it. The audit re-anchors its head
 * with a Setup change that marks the chain before it as unverified.
 *
 * <p>Declared next to {@link HmacKeys}, whose keys it announces, rather than in {@code secrets}: its listeners
 * ({@code platform}, {@code audit} and the modules that own MAC'd rows) may not depend on {@code secrets}.
 */
public record MasterKeyChanged(Kind kind) {

  public enum Kind {
    /** The person created a new key in locked mode (danger dialog, Setup change); new derived keys. */
    CREATED,
    /**
     * At start the key was missing, no stored secret needed it, and the database had a key before: a new key was made
     * without asking (warning {@code new-key-created}, Setup change); new derived keys.
     */
    REPLACED_LOST,
    /** The key came back from a backup; derived keys as before. */
    RESTORED,
    /** A rotation finished: every data key is wrapped by the new primary; derived keys as before. */
    ROTATED,
    /** The key file was rewritten with or without OS protection; nothing else changed. */
    PROTECTION_CHANGED
  }
}
