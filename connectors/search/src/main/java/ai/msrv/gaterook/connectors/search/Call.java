package ai.msrv.gaterook.connectors.search;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.SecretResolver;

/** One call's way to the store: the connection, its secrets and the call's context. */
record Call(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {}
