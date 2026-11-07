/**
 * Redis connector ({@code redis}), report 05.
 *
 * <p>{@code redis_get} and {@code redis_scan} use a read-only command allowlist. Typed write tools use a separate,
 * bounded allowlist, default to approval and never retry a write after losing its reply. All tools enforce the
 * connection's database and key scopes. Raw commands, scripts and administrative writes are not exposed.
 */
package ai.msrv.gaterook.connectors.redis;
