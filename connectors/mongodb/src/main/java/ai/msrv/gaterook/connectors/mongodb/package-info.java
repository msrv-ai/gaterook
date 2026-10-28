/**
 * MongoDB connector ({@code mongodb}), report 05.
 *
 * <p>{@code mongo_find} and {@code mongo_aggregate} remain read-only. {@code mongo_update} and
 * {@code mongo_update_many} are mutations requiring approval by default. Namespace checks, recursive rejection of
 * {@code $out}/{@code $merge} and server-side JavaScript, and explicit update operator/stage allowlists run before
 * execution. Approved calls execute their canonical payload.
 */
package ai.msrv.gaterook.connectors.mongodb;
