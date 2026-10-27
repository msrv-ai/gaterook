package ai.msrv.gaterook.core.connectors.plugins;

import ai.msrv.gaterook.plugin.protocol.GppJson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

/**
 * {@code ~/.mcp-gateway/run/plugin-hosts.json} (report 16 §3.4): the pid and start time of every plugin host this core
 * runs, so that a core that was killed or crashed stops, at its next start, what it left behind — a host whose plugin
 * keeps it alive past the end of its stdin, and the processes a plugin started. A pid is trusted only together with
 * its start time: after a reboot the number may belong to someone else's process.
 */
final class HostPids {

  private final Path file;
  private final Map<Long, Long> live = new LinkedHashMap<>();
  /** What the previous run recorded, read before this run records anything. */
  private List<long[]> previous;

  HostPids(Path file) {
    this.file = file;
    this.previous = read(file);
  }

  /** Stops the host trees a previous core left running, then forgets them; returns how many were stopped. */
  synchronized int reapOrphans() {
    int stopped = 0;
    for (long[] e : previous) {
      Optional<ProcessHandle> p = ProcessHandle.of(e[0]).filter(h -> h.isAlive() && startedAt(h) == e[1]);
      if (p.isPresent() && e[1] > 0) {
        stop(p.get());
        stopped++;
      }
    }
    previous = List.of();
    write();
    return stopped;
  }

  synchronized void record(ProcessHandle process) {
    live.put(process.pid(), startedAt(process));
    write();
  }

  synchronized void forget(long pid) {
    if (live.remove(pid) != null) {
      write();
    }
  }

  /** The pids recorded as running now. */
  synchronized List<Long> live() {
    return List.copyOf(live.keySet());
  }

  /** The process and its descendants: TERM, then KILL whatever is left after a moment. */
  private static void stop(ProcessHandle root) {
    List<ProcessHandle> tree = new ArrayList<>(root.descendants().toList());
    tree.add(root);
    tree.forEach(ProcessHandle::destroy);
    long end = System.nanoTime() + Duration.ofSeconds(2).toNanos();
    while (System.nanoTime() < end && tree.stream().anyMatch(ProcessHandle::isAlive)) {
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    tree.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
  }

  private static long startedAt(ProcessHandle p) {
    return p.info().startInstant().map(Instant::toEpochMilli).orElse(0L);
  }

  private static List<long[]> read(Path file) {
    List<long[]> out = new ArrayList<>();
    if (!Files.isRegularFile(file)) {
      return out;
    }
    try {
      JsonNode root = GppJson.MAPPER.readTree(Files.readString(file));
      for (JsonNode e : root) {
        out.add(new long[] {e.path("pid").asLong(-1), e.path("start").asLong(-1)});
      }
    } catch (IOException | RuntimeException e) {
      // An unreadable record names no process to stop; the next write replaces it.
    }
    return out;
  }

  private void write() {
    ArrayNode root = GppJson.NODES.arrayNode();
    live.forEach((pid, start) -> root.addObject().put("pid", pid).put("start", start));
    try {
      Files.createDirectories(file.getParent());
      Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
      Files.writeString(tmp, GppJson.MAPPER.writeValueAsString(root));
      try {
        Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
      } catch (UnsupportedOperationException e) {
        // Windows: the folder's ACL protects it.
      }
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException | RuntimeException e) {
      // Losing the record only means an orphan after a crash; the running hosts are unaffected.
    }
  }
}
