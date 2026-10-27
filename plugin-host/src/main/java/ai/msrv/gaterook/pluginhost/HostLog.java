package ai.msrv.gaterook.pluginhost;

import ai.msrv.gaterook.plugin.protocol.JsonRpcPeer;
import ai.msrv.gaterook.plugin.protocol.host.PeerLogger;
import java.util.Locale;

/**
 * Where the host's and the plugin's log lines go (report 16 §5): {@code log} notifications to the core once the peer
 * is up, stderr before that (the core keeps both, masked). The level comes with {@code host/hello}; until then INFO.
 */
final class HostLog {

  private static volatile JsonRpcPeer peer;
  private static volatile System.Logger.Level level = System.Logger.Level.INFO;

  private HostLog() {}

  static void attach(JsonRpcPeer p) {
    peer = p;
  }

  static JsonRpcPeer peer() {
    return peer;
  }

  static System.Logger.Level level() {
    return level;
  }

  /** {@code host/hello} {@code logLevel}: trace, debug, info, warn(ing), error, off. */
  static void level(String name) {
    level = parse(name);
  }

  static System.Logger.Level parse(String name) {
    if (name == null) {
      return System.Logger.Level.INFO;
    }
    return switch (name.toLowerCase(Locale.ROOT)) {
      case "trace", "all" -> System.Logger.Level.TRACE;
      case "debug" -> System.Logger.Level.DEBUG;
      case "warn", "warning" -> System.Logger.Level.WARNING;
      case "error" -> System.Logger.Level.ERROR;
      case "off" -> System.Logger.Level.OFF;
      default -> System.Logger.Level.INFO;
    };
  }

  static boolean enabled(System.Logger.Level l) {
    System.Logger.Level threshold = level;
    return threshold != System.Logger.Level.OFF && l.getSeverity() >= threshold.getSeverity();
  }

  /** One line from {@code logger} at {@code l}, outside any call. */
  static void line(String logger, System.Logger.Level l, String text) {
    if (enabled(l)) {
      new PeerLogger(HostLog::peer, logger, null, System.Logger.Level.TRACE).send(l, text);
    }
  }
}
