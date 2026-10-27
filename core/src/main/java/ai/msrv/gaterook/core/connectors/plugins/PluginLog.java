package ai.msrv.gaterook.core.connectors.plugins;

import ai.msrv.gaterook.core.platform.MaskPolicy;
import ai.msrv.gaterook.core.platform.Masker;
import ai.msrv.gaterook.core.platform.Taint;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The log of one plugin and its host (report 16 §5): lines of the plugin's {@code log} notifications and the host's
 * stderr, each masked in the core with the secret rules and the taint of this process's calls, written to
 * {@code ~/.mcp-gateway/logs/plugins/<id>.log} (0600, rotated at 5 MB, three kept). The last lines are kept in memory
 * for the UI when a host fails to start or crashes. A plugin that logs its own secret leaves none on disk.
 */
final class PluginLog implements AutoCloseable {

  static final long MAX_BYTES = 5L * 1024 * 1024;
  static final int KEEP = 3;
  static final int TAIL = 20;
  /** What of one line is read and masked; the rest of a longer one is dropped (a line is kept to 4,000). */
  static final int MAX_READ = 16 * 1024;
  /** What of one line is kept. */
  static final int MAX_LINE = 4_000;

  private final Path file;
  private final Clock clock;
  private final Masker masker = Masker.standard();
  /** The values this process's calls handed out; masked out of every line, cleared when the host stops. */
  private final Taint taint = new Taint();
  private final Deque<String> tail = new ArrayDeque<>();
  private Writer out;
  private long size;

  PluginLog(Path file, Clock clock) {
    this.file = file;
    this.clock = clock;
  }

  /** Records a value a call handed out, so an echo of it is masked in this host's log too. */
  synchronized void taint(CharSequence value) {
    taint.add(value);
  }

  /** One line from the plugin's {@code log}, masked and kept. */
  synchronized void line(String logger, String level, String message) {
    String text = mask((logger == null ? "" : logger + " ") + (message == null ? "" : message));
    append("[" + level + "] " + text);
  }

  /** One line of the host's stderr, masked and kept. */
  synchronized void stderr(String raw) {
    append("[stderr] " + mask(raw));
  }

  /** A note of the gateway itself in the log ("Started …", "Stopped after 15 min idle"). */
  synchronized void note(String text) {
    append("[mcp-gateway] " + mask(text));
  }

  /** The last lines, masked, for the UI. */
  synchronized List<String> tail() {
    return new ArrayList<>(tail);
  }

  /** Masks a line read only up to {@link #MAX_READ}: a plugin's line can be megabytes, masking it costs that much. */
  private String mask(String line) {
    String bounded = line.length() <= MAX_READ ? line : line.substring(0, MAX_READ);
    String rules = masker.log(bounded);
    return masker.text(rules, taint, MaskPolicy.SECRETS_ONLY).text();
  }

  /**
   * Splits {@code in} into lines of at most {@code max} bytes each (a longer line comes in pieces), UTF-8 decoded,
   * handing each to {@code line}; returns at the end of the stream. Never holds more than one piece.
   */
  static void lines(java.io.InputStream in, int max, java.util.function.Consumer<String> line)
      throws java.io.IOException {
    byte[] buffer = new byte[max];
    int length = 0;
    int b;
    while ((b = in.read()) >= 0) {
      if (b == '\n') {
        line.accept(decode(buffer, length));
        length = 0;
        continue;
      }
      buffer[length++] = (byte) b;
      if (length == max) {
        line.accept(decode(buffer, length));
        length = 0;
      }
    }
    if (length > 0) {
      line.accept(decode(buffer, length));
    }
  }

  private static String decode(byte[] bytes, int length) {
    int end = length > 0 && bytes[length - 1] == '\r' ? length - 1 : length;
    return new String(bytes, 0, end, StandardCharsets.UTF_8);
  }

  private void append(String body) {
    String line = clip(body);
    tail.addLast(line);
    while (tail.size() > TAIL) {
      tail.removeFirst();
    }
    write(clock.instant() + " " + line + "\n");
  }

  private static String clip(String s) {
    String line = s.replace('\n', ' ').replace('\r', ' ');
    return line.length() <= MAX_LINE ? line : line.substring(0, MAX_LINE) + "…";
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
      Path from = i == 1 ? file : file.resolveSibling(file.getFileName() + "." + (i - 1));
      if (Files.exists(from)) {
        Files.move(from, file.resolveSibling(file.getFileName() + "." + i), StandardCopyOption.REPLACE_EXISTING);
      }
    }
    open();
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
    taint.clear();
  }
}
