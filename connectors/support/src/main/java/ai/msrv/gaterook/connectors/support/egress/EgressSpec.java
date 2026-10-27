package ai.msrv.gaterook.connectors.support.egress;

import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * What one guarded client may do: its connection pool, the address policy, timeouts and TLS. A connector builds one per
 * connection version ({@code connection id} as {@link #poolKey}); the core builds its own for downloads
 * ({@code catalog}, {@code plugin-download}, {@code openapi-url}).
 *
 * @param poolKey        keep-alive sockets are shared only under the same key; {@link EgressClients#evict} drops them
 *                       when the connection or its policy changes, so a removed range can't live on in a pooled socket
 * @param policy         the environment's address policy; the core's own ports are always added on top
 * @param connectTimeout TCP and TLS handshake
 * @param callTimeout    the whole call, unless a request sets its own
 * @param tls            trust and pins for https
 */
public record EgressSpec(String poolKey, AddressPolicy policy, Duration connectTimeout, Duration callTimeout,
    Tls tls) {

  public EgressSpec {
    if (poolKey == null || poolKey.isBlank()) {
      throw new IllegalArgumentException("A pool key is required");
    }
    Objects.requireNonNull(policy, "policy");
    Objects.requireNonNull(connectTimeout, "connectTimeout");
    Objects.requireNonNull(callTimeout, "callTimeout");
    tls = tls == null ? Tls.PLATFORM : tls;
  }

  /** Defaults: 5 s to connect, 10 s per call, the platform's CAs (report 10 §4, kit "Tool timeout 10 s"). */
  public static EgressSpec of(String poolKey, AddressPolicy policy) {
    return new EgressSpec(poolKey, policy, Duration.ofSeconds(5), Duration.ofSeconds(10), Tls.PLATFORM);
  }

  public EgressSpec withTimeouts(Duration connect, Duration call) {
    return new EgressSpec(poolKey, policy, connect, call, tls);
  }

  public EgressSpec withTls(Tls value) {
    return new EgressSpec(poolKey, policy, connectTimeout, callTimeout, value);
  }

  /**
   * Trust of https connections.
   *
   * @param extraCertificates private CAs or a trusted certificate of the connection
   * @param platformTrust     whether the platform's CAs count too
   * @param pins              {@code sha256/…} public-key pins per host pattern (OkHttp {@code CertificatePinner})
   * @param tls13Only         {@code minVersion: TLSv1.3}; otherwise TLS 1.2 and 1.3
   */
  public record Tls(List<X509Certificate> extraCertificates, boolean platformTrust, List<Pin> pins,
      boolean tls13Only) {

    /** The platform's CAs, no pins, TLS 1.2+. */
    public static final Tls PLATFORM = new Tls(List.of(), true, List.of(), false);

    public Tls {
      extraCertificates = List.copyOf(extraCertificates);
      pins = List.copyOf(pins);
      if (extraCertificates.isEmpty() && !platformTrust) {
        throw new IllegalArgumentException("TLS must trust something: the platform's CAs or a certificate");
      }
    }
  }

  /** One pin: {@code hostPattern} as OkHttp's {@code CertificatePinner} reads it, {@code sha256/<base64>}. */
  public record Pin(String hostPattern, String sha256) {
    public Pin {
      Objects.requireNonNull(hostPattern, "hostPattern");
      if (sha256 == null || !sha256.matches("sha256/[A-Za-z0-9+/=]{44}")) {
        throw new IllegalArgumentException("A pin is sha256/ followed by 44 base64 characters");
      }
    }
  }
}
