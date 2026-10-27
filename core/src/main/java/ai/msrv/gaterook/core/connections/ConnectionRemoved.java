package ai.msrv.gaterook.core.connections;

import ai.msrv.gaterook.connector.api.Env;

/** A connection was deleted. Published inside the deletion's transaction. */
public record ConnectionRemoved(String connectionId, String name, String type, Env env) {}
