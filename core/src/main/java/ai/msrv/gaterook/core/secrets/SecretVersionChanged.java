package ai.msrv.gaterook.core.secrets;

/**
 * A secret got a new current version (Replace, Rotate): connectors drop pools and clients keyed by the old one
 * ({@code Connector.onConnectionChanged}). Published inside the transaction that makes the version current, so that
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} listeners (ARCHITECTURE.md §2.2) hear of it once it has
 * committed and never when it rolls back.
 */
public record SecretVersionChanged(String secretId, SecretOwner owner, String field, Integer version) {}
