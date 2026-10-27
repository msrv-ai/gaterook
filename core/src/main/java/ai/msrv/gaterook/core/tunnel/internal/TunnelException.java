package ai.msrv.gaterook.core.tunnel.internal;

/**
 * The tunnel can't start or went away; the message is for the person (Settings › Tunnel shows it as the error state)
 * and never carries a token: agents get their secrets only through the environment of the process.
 */
public class TunnelException extends Exception {

  private static final long serialVersionUID = 1L;

  public TunnelException(String message) {
    super(message);
  }
}
