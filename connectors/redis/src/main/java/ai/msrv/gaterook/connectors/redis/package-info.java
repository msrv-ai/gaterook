/**
 * Redis connector ({@code redis}), report 05.
 *
 * <p>Read only by construction: {@code redis_get} and {@code redis_scan} send O(limit) commands through a read-only
 * command executor; {@code KEYS}, {@code FLUSHALL} and every write are blocked before the network. Step C3
 * implements execution; this package has the final manifest.
 */
package ai.msrv.gaterook.connectors.redis;
