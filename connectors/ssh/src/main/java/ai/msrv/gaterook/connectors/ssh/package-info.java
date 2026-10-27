/**
 * SSH connector ({@code ssh}), report 06, ARCHITECTURE.md A23.
 *
 * <p>{@code ssh_run} runs saved runbooks with typed parameters (a read-only runbook runs at once, any other always
 * waits for approval); {@code ssh_exec} runs one shell command line, is always a mutation and is off by default.
 * Exec channels only: no shell, PTY, agent or X11 forwarding. Step C4 implements execution and makes MINA work in
 * the native image; this package has the final manifest.
 */
package ai.msrv.gaterook.connectors.ssh;
