package ai.msrv.gaterook.plugin.protocol.host;

import ai.msrv.gaterook.plugin.protocol.Gpp;
import ai.msrv.gaterook.plugin.protocol.JsonRpcPeer;
import ai.msrv.gaterook.plugin.protocol.Messages;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.function.Supplier;

/**
 * A {@link System.Logger} whose lines become {@code log} notifications (report 16 §4.3): the core masks them with the
 * taint of the process's calls before they reach {@code logs/plugins/<id>.log}. Rate-limited per process, so a plugin
 * stuck in a loop can't flood the channel; what doesn't fit goes to stderr, which the core masks and keeps as well.
 */
public final class PeerLogger implements System.Logger {

  /** Lines per second through the channel (report 16 §9). */
  static final int PER_SECOND = 200;

  private static final Object BUCKET = new Object();
  private static long second;
  private static int sent;

  private final Supplier<JsonRpcPeer> peer;
  private final String name;
  private final String callId;
  private final Level threshold;

  public PeerLogger(Supplier<JsonRpcPeer> peer, String name, String callId, Level threshold) {
    this.peer = peer;
    this.name = name;
    this.callId = callId;
    this.threshold = threshold;
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public boolean isLoggable(Level level) {
    return level != Level.OFF && level.getSeverity() >= threshold.getSeverity();
  }

  @Override
  public void log(Level level, ResourceBundle bundle, String msg, Throwable thrown) {
    if (!isLoggable(level)) {
      return;
    }
    String text = msg == null ? "" : msg;
    if (thrown != null) {
      text = text + " " + thrown;
    }
    send(level, text);
  }

  @Override
  public void log(Level level, ResourceBundle bundle, String format, Object... params) {
    if (!isLoggable(level)) {
      return;
    }
    String text = format == null ? "" : params == null || params.length == 0 ? format
        : new MessageFormat(format, Locale.ROOT).format(params);
    send(level, text);
  }

  /** Sends one line, or prints it to stderr when the rate limit is spent or the core is gone. */
  public void send(Level level, String text) {
    JsonRpcPeer p = peer.get();
    if (p != null && take()) {
      p.notify(Gpp.LOG, Messages.log(level.getName().toLowerCase(Locale.ROOT), name, callId, text));
      return;
    }
    System.err.println(level.getName() + " " + name + (callId == null ? "" : " " + callId) + ": " + text);
  }

  private static boolean take() {
    long now = System.nanoTime() / 1_000_000_000L;
    synchronized (BUCKET) {
      if (now != second) {
        second = now;
        sent = 0;
      }
      return ++sent <= PER_SECOND;
    }
  }
}
