package ai.msrv.gaterook.connectors.mcpserver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code ~/.mcp-gateway/run/mcp-pids.json}: the root process of every running stdio server with its start time, so
 * that a core that was killed (or crashed) stops what it left behind at its next start (ARCHITECTURE.md §7.1 "Orphaned processes from the
 * previous run are terminated using PID files"). A pid is only trusted together with its start time: after a
 * reboot the number may belong to someone else's process.
 */
final class PidFile {

  private final Path file;
  private final Map<String, long[]> entries = new LinkedHashMap<>();
  /** What the previous run recorded, read before this run records anything. */
  private List<long[]> previous;

  /**
   * Reads what the previous run left right away (a small file): the first server of this run rewrites the file, and
   * the clean-up, which runs in the background, would otherwise see this run's processes instead of the orphans.
   */
  PidFile(Path file) {
    this.file = file;
    this.previous = read(file);
  }

  /** Stops what a previous core left running, then forgets it. Returns how many trees were stopped. */
  synchronized int reapOrphans() {
    int stopped = 0;
    for (long[] e : previous) {
      if (e[0] > 0 && ProcessTree.same(e[0], e[1])) {
        ProcessHandle.of(e[0]).ifPresent(p -> ProcessTree.stop(p, Duration.ofSeconds(3)));
        stopped++;
      }
    }
    previous = List.of();
    write();
    return stopped;
  }

  private static List<long[]> read(Path file) {
    List<long[]> out = new ArrayList<>();
    if (!Files.isRegularFile(file)) {
      return out;
    }
    try {
      JsonNode root = Texts.JSON.readTree(Files.readString(file));
      for (Map.Entry<String, JsonNode> e : root.properties()) {
        out.add(new long[] {e.getValue().path("pid").asLong(-1), e.getValue().path("start").asLong(-1)});
      }
    } catch (IOException | RuntimeException e) {
      // An unreadable record can't name processes to stop; the next write replaces it.
    }
    return out;
  }

  synchronized void record(String key, ProcessHandle process) {
    long start = process.info().startInstant().map(java.time.Instant::toEpochMilli).orElse(0L);
    entries.put(key, new long[] {process.pid(), start});
    write();
  }

  synchronized void forget(String key) {
    if (entries.remove(key) != null) {
      write();
    }
  }

  private void write() {
    ObjectNode root = Texts.JSON.createObjectNode();
    entries.forEach((k, v) -> root.putObject(k).put("pid", v[0]).put("start", v[1]));
    try {
      Files.createDirectories(file.getParent());
      Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
      Files.writeString(tmp, Texts.JSON.writeValueAsString(root));
      try {
        Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
      } catch (UnsupportedOperationException e) {
        // Windows: the folder's ACL protects it.
      }
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      // Losing the record only means an orphan after a crash; the running servers are unaffected.
    }
  }
}
