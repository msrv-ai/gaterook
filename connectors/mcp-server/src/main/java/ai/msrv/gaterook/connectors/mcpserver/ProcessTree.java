package ai.msrv.gaterook.connectors.mcpserver;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Stops a server's process and everything it started (ARCHITECTURE.md §7.5 "terminating the entire descendant process tree"):
 * {@code npx} runs {@code sh}, which runs {@code node}, and a server may start helpers of its own. The tree is read
 * before the first signal — a killed parent's children are re-parented to init and can no longer be found from it —
 * then every process gets TERM, and whatever still runs after the grace period gets KILL.
 */
final class ProcessTree {

  private ProcessTree() {}

  /** TERM to the tree, KILL after {@code grace}; returns when every process is gone or KILLed. */
  static void stop(ProcessHandle root, Duration grace) {
    stop(root, List.of(), grace);
  }

  /**
   * The same, with the descendants last seen while the root ran: after a root crashed its children belong to init
   * and only such a record still names them. A handle remembers its process's start time, so a recycled pid is
   * neither signalled nor waited for.
   */
  static void stop(ProcessHandle root, List<ProcessHandle> known, Duration grace) {
    List<ProcessHandle> tree = new ArrayList<>();
    if (root != null) {
      tree.add(root);
      tree.addAll(root.descendants().toList());
    }
    for (ProcessHandle p : known) {
      if (p.isAlive() && !tree.contains(p)) {
        tree.add(p);
        tree.addAll(p.descendants().filter(d -> !tree.contains(d)).toList());
      }
    }
    if (tree.isEmpty()) {
      return;
    }
    for (ProcessHandle p : tree) {
      p.destroy();
    }
    long deadline = System.nanoTime() + grace.toNanos();
    for (ProcessHandle p : tree) {
      await(p, Math.max(0, deadline - System.nanoTime()));
    }
    // Children that appeared while the root was dying (a shell that forked before it got TERM).
    List<ProcessHandle> late = new ArrayList<>();
    for (ProcessHandle p : tree) {
      if (p.isAlive()) {
        late.addAll(p.descendants().toList());
      }
    }
    tree.addAll(late);
    for (ProcessHandle p : tree) {
      if (p.isAlive()) {
        p.destroyForcibly();
      }
    }
    for (ProcessHandle p : tree) {
      await(p, TimeUnit.SECONDS.toNanos(2));
    }
  }

  /** Whether {@code pid} still runs and started at {@code startMillis} (a recycled pid is someone else's process). */
  static boolean same(long pid, long startMillis) {
    return ProcessHandle.of(pid).filter(ProcessHandle::isAlive)
        .flatMap(p -> p.info().startInstant())
        .map(i -> Math.abs(i.toEpochMilli() - startMillis) < 1_000)
        .orElse(false);
  }

  private static void await(ProcessHandle p, long nanos) {
    CompletableFuture<ProcessHandle> exit = p.onExit();
    try {
      exit.get(nanos, TimeUnit.NANOSECONDS);
    } catch (TimeoutException | ExecutionException e) {
      // Still running, or no longer observable: the next step decides.
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
