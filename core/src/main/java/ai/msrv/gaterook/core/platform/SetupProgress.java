package ai.msrv.gaterook.core.platform;

/**
 * SPI: how far one area of the setup is, for Overview's getting started and setup map (ARCHITECTURE.md §6 overview).
 * Implemented by the module that owns the area once it exists: {@code clients} ("Connect a client", the Clients node,
 * K1) and {@code notifications} ("Set up approval notifications", K2). Without an implementation the admin API falls
 * back to what other modules show (grants and named tokens for clients; nothing set up for notifications).
 */
public interface SetupProgress {

  /** {@code clients} or {@code notifications}. */
  String area();

  Progress progress();

  /**
   * @param count     what the setup map counts (connected clients, notification channels)
   * @param done      the getting-started step is done
   * @param attention something needs the person (a client that needs a restart, a failing channel)
   * @param status    one line for the setup map ("Claude Desktop, Codex"), or {@code null}
   */
  record Progress(int count, boolean done, boolean attention, String status) {

    public Progress {
      if (count < 0) {
        throw new IllegalArgumentException("count " + count);
      }
    }

    public static Progress none() {
      return new Progress(0, false, false, null);
    }
  }
}
