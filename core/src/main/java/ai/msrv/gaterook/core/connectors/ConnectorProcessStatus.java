package ai.msrv.gaterook.core.connectors;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * SPI: the state of the process behind a connector, for {@code GET /api/connector-types} ("Running · 54 MB",
 * "Sleeping"). Implemented by the modules that run processes: {@code connectors.plugins} (one plugin host per plugin)
 * and {@code mcpservers} (one process per stdio server). Built-in connectors have no process of their own.
 */
public interface ConnectorProcessStatus {

  /**
   * The process of a plugin's connector type or of an MCP server connection, or empty when this implementation
   * doesn't own {@code id}.
   *
   * @param id a connector type id ({@code jira-service-desk}) or an MCP server connection id
   */
  Optional<ProcessInfo> processOf(String id);

  /**
   * A snapshot of one process.
   *
   * @param pid    operating system pid while a process exists, else {@code null}
   * @param rssMb  resident memory in MB when known, else {@code null}
   * @param since  when the process entered {@code state}
   * @param detail one line for the UI: exit code and reason of a crash, next retry of a backoff
   */
  record ProcessInfo(State state, Long pid, Integer rssMb, Instant since, String detail) {

    public ProcessInfo {
      Objects.requireNonNull(state, "state");
    }
  }

  /**
   * Life cycle of a supervised process (ARCHITECTURE.md §7.5): {@code STARTING → RUNNING → DRAINING → STOPPED};
   * {@code CRASHED → BACKOFF → STARTING}; five crashes in ten minutes → {@code FAILED} until a person restarts
   * it.
   */
  enum State {
    STARTING, RUNNING, DRAINING,
    /** Not running: never started, or stopped after the idle period ("Sleeping" in the UI). */
    STOPPED,
    CRASHED, BACKOFF, FAILED
  }
}
