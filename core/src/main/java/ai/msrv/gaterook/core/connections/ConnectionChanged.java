package ai.msrv.gaterook.core.connections;

import ai.msrv.gaterook.connector.api.Env;

/**
 * A connection was created or got a new {@code version}: its config, metadata, env or a secret changed, or the policy
 * of its environment did. Connectors drop pools keyed by the old version, endpoint snapshots are rebuilt. Published
 * inside the transaction of the change (ARCHITECTURE.md §2.2).
 */
public record ConnectionChanged(String connectionId, String name, Env env, long version) {}
