/**
 * MongoDB connector ({@code mongodb}), report 05.
 *
 * <p>Read only by construction: {@code mongo_find} and {@code mongo_aggregate}. The validator walks every key of
 * filter, projection, sort and pipeline: {@code $out}/{@code $merge} at any depth and server-side JavaScript are
 * denied before the network. Step C2 implements execution; this package has the final manifest.
 */
package ai.msrv.gaterook.connectors.mongodb;
