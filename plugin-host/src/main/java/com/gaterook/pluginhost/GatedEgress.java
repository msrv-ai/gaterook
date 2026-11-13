package com.gaterook.pluginhost;

import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.ExecContext;
import com.gaterook.plugin.protocol.host.ScopedEgress;
import java.util.Optional;

/**
 * The egress of one call of a plugin that declares TCP destinations: HTTP through {@link EgressHttpClient} as for
 * every plugin, and the call's {@link TcpGrant} bound while the plugin's code runs ({@link #run}), so the sockets its
 * driver opens meanwhile reach what the grant allows. Around the call the gate makes sure it is still the JVM's proxy
 * selector and authenticator; a plugin that replaced them is told in its log.
 */
final class GatedEgress implements ScopedEgress {

  private final ExecContext.Egress http;
  private final TcpGate gate;
  private final TcpGrant grant;

  GatedEgress(ExecContext.Egress http, TcpGate gate, TcpGrant grant) {
    this.http = http;
    this.gate = gate;
    this.grant = grant;
  }

  @Override
  public ExecContext.EgressResponse send(ExecContext.EgressRequest request, long maxBytes) throws ConnectorException {
    return http.send(request, maxBytes);
  }

  @Override
  public <T, X extends Throwable> T run(ScopedValue.CallableOp<T, X> body) throws X {
    TcpGrant before = TcpGate.INHERITED.get();
    TcpGate.INHERITED.set(grant);
    checkDefaults("before");
    try {
      return ScopedValue.where(TcpGate.CURRENT, grant).call(body);
    } finally {
      if (before == null) {
        TcpGate.INHERITED.remove();
      } else {
        TcpGate.INHERITED.set(before);
      }
      checkDefaults("during");
    }
  }

  @Override
  public Runnable wrap(Runnable action) {
    return () -> {
      TcpGrant before = TcpGate.INHERITED.get();
      TcpGate.INHERITED.set(grant);
      try {
        ScopedValue.where(TcpGate.CURRENT, grant).run(action);
      } finally {
        if (before == null) {
          TcpGate.INHERITED.remove();
        } else {
          TcpGate.INHERITED.set(before);
        }
      }
    };
  }

  @Override
  public Optional<Denial> lastDenial() {
    return grant.lastDenial();
  }

  @Override
  public void close() {
    gate.ended(grant);
  }

  TcpGrant grant() {
    return grant;
  }

  private void checkDefaults(String when) {
    if (!gate.ensureDefaults()) {
      HostLog.line("tcp-gate", System.Logger.Level.WARNING, "The plugin replaced the JVM's proxy selector or "
          + "authenticator " + when + " a call; GateRook restored them. Connections opened meanwhile may have gone "
          + "past the TCP gate.");
    }
  }
}
