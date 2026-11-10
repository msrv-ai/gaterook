package ai.msrv.gaterook.core.platform;

/**
 * SPI: the bridge launcher this core writes in GateRook's home ({@code bin/gw-bridge}, on Windows also
 * {@code bin/gw-bridge.cmd} and {@code bin/gw-bridge.argv}; ARCHITECTURE.md §7.2), which client configs point at and
 * through which the core starts the MCP servers it sandboxes (§7.5). The clients module writes it; the MCP servers
 * module asks for it before it first asks whether the sandbox works, so that the answer is about this build's bridge,
 * never about a launcher an earlier install left there or one not written yet. Declared here, so neither module needs
 * the other.
 */
public interface BridgeLauncher {

  /**
   * Writes the launcher for this run unless that was done already (once per run, by whichever asks first; one that
   * couldn't be written is tried again at the next call), and says whether this build has a bridge for it to run:
   * {@code false} means there is no sandbox, and servers run as the person's user.
   */
  boolean prepare();
}
