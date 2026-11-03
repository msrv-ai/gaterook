package ai.msrv.gaterook.core.connectors;

import tools.jackson.databind.JsonNode;

/**
 * Who hears one call while it runs: its MCP client, when it asked for progress or log messages, and what can cancel
 * it ({@code notifications/cancelled}). What reaches {@link #progress} and {@link #log} is masked and bounded by the
 * {@link CallContext} already; neither may block for long, since they run on the resource's reading thread.
 */
public interface CallListener {

  /** Nobody: no progress, no log messages, nothing to cancel. */
  CallListener NONE = new CallListener() {};

  /** Whether the client asked for progress. */
  default boolean wantsProgress() {
    return false;
  }

  /** The least severe log level the client asked for, or {@code null} for none. */
  default String logLevel() {
    return null;
  }

  default void progress(double progress, Double total, String message) {}

  default void log(String level, String logger, JsonNode data) {}

  /** The call reached the connector: {@code cancel} stops it, as its deadline would. */
  default void started(Runnable cancel) {}
}
