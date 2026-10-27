package ai.msrv.gaterook.core.platform;

import java.util.function.IntSupplier;

/**
 * The local listener as clients reach it: {@code http://127.0.0.1:<port>} with the port the web server really bound
 * (the bootstrap may ask for any free one). Approval links and endpoint URLs are built from it.
 */
public final class LocalListener {

  private final IntSupplier port;

  public LocalListener(IntSupplier port) {
    this.port = port;
  }

  public int port() {
    return port.getAsInt();
  }

  /** {@code http://127.0.0.1:7465}; always the IPv4 loopback the core binds (never {@code localhost}). */
  public String origin() {
    return "http://127.0.0.1:" + port();
  }
}
