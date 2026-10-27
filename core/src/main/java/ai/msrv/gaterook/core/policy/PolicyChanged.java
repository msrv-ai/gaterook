package ai.msrv.gaterook.core.policy;

import ai.msrv.gaterook.connector.api.Env;

/**
 * The effective policy of {@code env} changed (an edit of it or of the environment it inherits from, a reset, a
 * confirmed review). Endpoint snapshots and connector pools of that environment are rebuilt; connections of the
 * environment get a new {@code ConnectionConfig.version}. Published inside the transaction of the change.
 */
public record PolicyChanged(Env env) {}
