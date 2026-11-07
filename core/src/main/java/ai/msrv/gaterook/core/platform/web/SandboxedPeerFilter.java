package ai.msrv.gaterook.core.platform.web;

import ai.msrv.gaterook.core.platform.NativeOs;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongPredicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * On Windows, the core's ports are closed to the processes it sandboxes (ARCHITECTURE.md §7.5). Low integrity doesn't
 * judge sockets, and no rule on a port can be set without an administrator, so the core judges the peer itself: the
 * process at the other end of the loopback connection (the IPv4 TCP table), refused with 403 when it runs below medium
 * integrity, or in an AppContainer, and descends from this core, as its plugin hosts and MCP servers do. The Electron
 * app, a tunnel agent and other programs' sandboxed processes (a browser's) aren't refused: medium, or not the core's.
 * A peer that can't be identified is let through to the usual checks. Elsewhere the filter does nothing: the OS
 * sandboxes close the ports there.
 */
final class SandboxedPeerFilter implements Filter {

  private static final Logger log = LoggerFactory.getLogger(SandboxedPeerFilter.class);
  /** {@code SECURITY_MANDATORY_MEDIUM_RID}. */
  static final int MEDIUM_RID = 0x2000;
  /** How far up the parents of a peer the core is looked for. */
  static final int MAX_ANCESTORS = 32;

  /** The native lookups, as functions so the decision is tested without Windows. */
  interface Peers {
    /** The pid at the client end of 127.0.0.1:{@code clientPort} → 127.0.0.1:{@code serverPort}, or -1. */
    long pid(int serverPort, int clientPort);

    /** The integrity level RID of {@code pid}, 0 for an AppContainer, -1 unknown. */
    int level(long pid);
  }

  private final boolean active;
  private final Peers peers;
  private final LongPredicate descendsFromCore;
  private final Set<Long> reported = ConcurrentHashMap.newKeySet();

  SandboxedPeerFilter() {
    this(NativeOs.isWindows(), new Peers() {
      @Override
      public long pid(int serverPort, int clientPort) {
        return NativeOs.loopbackPeer(serverPort, clientPort);
      }

      @Override
      public int level(long pid) {
        return NativeOs.integrityLevel(pid);
      }
    }, SandboxedPeerFilter::descendsFromThisProcess);
  }

  SandboxedPeerFilter(boolean active, Peers peers, LongPredicate descendsFromCore) {
    this.active = active;
    this.peers = peers;
    this.descendsFromCore = descendsFromCore;
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    if (active && request instanceof HttpServletRequest http && response instanceof HttpServletResponse out
        && refused(http.getRemoteAddr(), http.getLocalPort(), http.getRemotePort())) {
      out.sendError(HttpServletResponse.SC_FORBIDDEN, "A process GateRook sandboxes can't call GateRook.");
      return;
    }
    chain.doFilter(request, response);
  }

  /** Whether a loopback peer is one of this core's sandboxed processes. */
  boolean refused(String remoteAddress, int serverPort, int clientPort) {
    if (!"127.0.0.1".equals(remoteAddress)) {
      return false;
    }
    long pid = peers.pid(serverPort, clientPort);
    if (pid <= 0) {
      return false;
    }
    int level = peers.level(pid);
    // The level first: it is cheap, and a medium peer (the app, a tunnel agent) never needs its parents walked.
    if (level < 0 || level >= MEDIUM_RID || !descendsFromCore.test(pid)) {
      return false;
    }
    if (reported.add(pid)) {
      log.warn("Refused a call to port {} from process {}, which GateRook runs sandboxed", serverPort, pid);
    }
    return true;
  }

  /** Whether {@code pid} has this process among its parents. */
  static boolean descendsFromThisProcess(long pid) {
    long self = ProcessHandle.current().pid();
    Optional<ProcessHandle> p = ProcessHandle.of(pid);
    for (int i = 0; i < MAX_ANCESTORS && p.isPresent(); i++) {
      p = p.get().parent();
      if (p.isPresent() && p.get().pid() == self) {
        return true;
      }
    }
    return false;
  }
}
