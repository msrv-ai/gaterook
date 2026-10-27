package ai.msrv.gaterook.connectors.support.egress;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import okhttp3.OkHttpClient;

/**
 * The core's {@code ExecContext.egress()}: the neutral interface every connector and plugin sees, plus, for the
 * built-in connectors that run inside the core, the guard it stands for — the {@link EgressSpec} the core built for
 * this connection (the environment's address policy with its private ranges for this connection, the core's own
 * ports, one connection pool per connection) and the {@link EgressClients} that serve it.
 *
 * <p>The HTTP connector makes its own OkHttp requests (TLS of the connection, timings, the handshake for Test
 * connection) and must do so under exactly this guard: {@link #withTransport} may change TLS trust and timeouts, never
 * the address policy or the pool, so a request of a connection can't reach anything its environment refuses.
 */
public record GuardedEgress(EgressClients clients, EgressSpec spec) implements ExecContext.Egress {

  public GuardedEgress {
    Objects.requireNonNull(clients, "clients");
    Objects.requireNonNull(spec, "spec");
  }

  /** The guard behind {@code ctx}, or empty when the call has no guarded network access of the core. */
  public static Optional<GuardedEgress> of(ExecContext ctx) {
    return ctx != null && ctx.egress() instanceof GuardedEgress guarded ? Optional.of(guarded) : Optional.empty();
  }

  @Override
  public ExecContext.EgressResponse send(ExecContext.EgressRequest request, long maxBytes) throws ConnectorException {
    return clients.send(spec, request, maxBytes);
  }

  /**
   * The same guard with the connection's own transport: TLS trust and pins, connect and call timeouts. The address
   * policy and the pool key are the core's and stay as they are.
   */
  public GuardedEgress withTransport(EgressSpec.Tls tls, Duration connectTimeout, Duration callTimeout) {
    return new GuardedEgress(clients, spec.withTls(tls).withTimeouts(connectTimeout, callTimeout));
  }

  /** The guarded OkHttp client of {@link #spec}; callers add no interceptors and keep its DNS and socket factory. */
  public OkHttpClient client() {
    return clients.client(spec);
  }

  /**
   * The address policy connections of this guard are judged by, the core's ports included: a caller that pins
   * addresses itself (plain HTTP only to allowed private addresses) judges them by the same rules.
   */
  public AddressPolicy policy() {
    return spec.policy().withCorePorts(clients.settings().corePorts().get());
  }

  /** The resolver behind the guard's DNS. */
  public HostResolver resolver() {
    return clients.settings().resolver();
  }
}
