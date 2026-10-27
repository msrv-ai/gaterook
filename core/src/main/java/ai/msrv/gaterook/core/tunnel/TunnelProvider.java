package ai.msrv.gaterook.core.tunnel;

import java.util.Locale;
import java.util.Optional;

/**
 * Who carries the public traffic to {@code 127.0.0.1:7466} (Settings › Tunnel, ARCHITECTURE.md A6): Cloudflare's
 * {@code cloudflared} (a quick tunnel with a new address on every start, or a named tunnel of the person's account
 * with a stable hostname), or the ngrok agent. {@link #TEST} is only the test override of a non-release build.
 */
public enum TunnelProvider {
  CLOUDFLARE_QUICK("cloudflare-quick", "Cloudflare Tunnel"),
  CLOUDFLARE_NAMED("cloudflare-named", "Cloudflare Tunnel (named)"),
  NGROK("ngrok", "ngrok"),
  TEST("test", "Test override");

  private final String id;
  private final String label;

  TunnelProvider(String id, String label) {
    this.id = id;
    this.label = label;
  }

  /** {@code cloudflare-quick}, {@code cloudflare-named}, {@code ngrok}: the id of the API and of Settings. */
  public String id() {
    return id;
  }

  /** "Cloudflare Tunnel", "ngrok": what the UI and the self-test say. */
  public String label() {
    return label;
  }

  public boolean cloudflare() {
    return this == CLOUDFLARE_QUICK || this == CLOUDFLARE_NAMED;
  }

  /** Whether the public address stays the same across starts (a named tunnel, a reserved ngrok domain). */
  public boolean stableAddress(String ngrokDomain) {
    return this == CLOUDFLARE_NAMED || this == NGROK && ngrokDomain != null && !ngrokDomain.isBlank();
  }

  /** The provider a person can choose with this id; the test override isn't one. */
  public static Optional<TunnelProvider> of(String id) {
    if (id == null) {
      return Optional.empty();
    }
    String wanted = id.toLowerCase(Locale.ROOT);
    for (TunnelProvider p : values()) {
      if (p != TEST && p.id.equals(wanted)) {
        return Optional.of(p);
      }
    }
    return Optional.empty();
  }
}
