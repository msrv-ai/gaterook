package ai.msrv.gaterook.connectors.mcpserver;

import java.io.IOException;
import java.io.Writer;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What a server writes to stderr and its {@code logging} notifications (report 11 §6.5): to
 * {@code ~/.gaterook/logs/mcp/<name>.log} (0600, rotated at 5 MB, three files kept) and to a short tail the test
 * shows when the server doesn't start. The values of the server's own secrets are masked in every form a program
 * would print them in (as they are, Base64, URL-encoded, JSON-escaped) before anything is written: a server that logs
 * its token at start-up leaves no token on disk.
 */
final class ServerLog implements AutoCloseable {

  static final long MAX_BYTES = 5L * 1024 * 1024;
  static final int KEEP = 3;
  static final int TAIL = 20;
  static final String MASK = "‹masked›";
  /** How many masked forms are kept across starts (six forms a value). */
  static final int KEPT = 256;

  private final Path file;
  private final Clock clock;
  private final Set<String> secrets = new LinkedHashSet<>();
  private final Deque<String> tail = new ArrayDeque<>();
  private Writer out;
  private long size;

  ServerLog(Path file, Clock clock) {
    this.file = file;
    this.clock = clock;
  }

  /**
   * The secret values of the process being logged, in every form they may be printed in. Earlier values stay masked
   * too: after a rotation the old process finishes its calls with the old value (the newest {@value #KEPT} forms kept).
   */
  synchronized void secrets(List<String> values) {
    for (String v : values) {
      if (v == null || v.length() < 4) {
        continue;
      }
      secrets.add(v);
      byte[] bytes = v.getBytes(StandardCharsets.UTF_8);
      secrets.add(Base64.getEncoder().encodeToString(bytes));
      secrets.add(Base64.getEncoder().withoutPadding().encodeToString(bytes));
      secrets.add(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
      secrets.add(URLEncoder.encode(v, StandardCharsets.UTF_8));
      secrets.add(Texts.JSON.writeValueAsString(v).replaceAll("^\"|\"$", ""));
    }
    secrets.removeIf(s -> s.length() < 4);
    while (secrets.size() > KEPT) {
      secrets.remove(secrets.iterator().next());
    }
  }

  /** {@code line} with every known secret replaced. */
  synchronized String mask(String line) {
    String out = line;
    // Longest first: an earlier value that starts a later one must not leave the rest of it.
    for (String s : secrets.stream().sorted(java.util.Comparator.comparingInt(String::length).reversed()).toList()) {
      if (out.contains(s)) {
        out = out.replace(s, MASK);
      }
    }
    return out;
  }

  /**
   * One line of the server: masked, cleaned of control characters, masked again (the cleaning joins a value split by
   * an invisible character), kept in the tail and appended to the file.
   */
  synchronized void line(String raw) {
    String line = mask(Texts.clean(mask(raw), 4_000, false));
    tail.addLast(line);
    while (tail.size() > TAIL) {
      tail.removeFirst();
    }
    write(clock.instant() + " " + line + "\n");
  }

  /** A note of the gateway itself in the server's log ("Started: …", "Stopped after 15 min idle"). */
  synchronized void note(String text) {
    write(clock.instant() + " [gaterook] " + mask(text) + "\n");
  }

  /** The last lines of stderr, masked. */
  synchronized List<String> tail() {
    return new ArrayList<>(tail);
  }

  synchronized void clearTail() {
    tail.clear();
  }

  Path file() {
    return file;
  }

  private void write(String text) {
    try {
      if (out == null) {
        open();
      }
      byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
      if (size + bytes.length > MAX_BYTES) {
        rotate();
      }
      out.write(text);
      out.flush();
      size += bytes.length;
    } catch (IOException e) {
      // A full disk or a removed folder must not stop the server; the tail still has the lines.
      closeQuietly();
    }
  }

  private void open() throws IOException {
    Files.createDirectories(file.getParent());
    if (!Files.exists(file)) {
      Files.createFile(file);
      try {
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
      } catch (UnsupportedOperationException e) {
        // Windows: the folder's ACL protects it.
      }
    }
    size = Files.size(file);
    out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
  }

  private void rotate() throws IOException {
    closeQuietly();
    for (int i = KEEP - 1; i >= 1; i--) {
      Path from = i == 1 ? file : sibling(i - 1);
      if (Files.exists(from)) {
        Files.move(from, sibling(i), StandardCopyOption.REPLACE_EXISTING);
      }
    }
    open();
  }

  private Path sibling(int n) {
    return file.resolveSibling(file.getFileName() + "." + n);
  }

  private void closeQuietly() {
    if (out != null) {
      try {
        out.close();
      } catch (IOException e) {
        // Nothing to do.
      }
      out = null;
    }
  }

  @Override
  public synchronized void close() {
    closeQuietly();
  }
}
