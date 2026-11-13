package com.gaterook.connectors.search;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ExecContext;
import com.gaterook.connector.api.SecretResolver;

/** One call's way to the store: the connection, its secrets and the call's context. */
record Call(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {}
