package ai.msrv.gaterook.core.platform.boot;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The core's half of the start-up protocol with Electron (ARCHITECTURE.md §7.1): JSON lines on the original stdout.
 * Progress lines {@code {"v":1,"step":"db-migrations","state":"running|done|failed"}}, then exactly one ready line,
 * {@code {"v":1,"ready":true,…}} or {@code {"v":1,"ready":false,"reason":…}}. Nothing else is ever written there: the
 * launcher points {@code System.out} at stderr before Spring starts.
 *
 * <p>Outside the launcher (tests, {@code @SpringBootTest}) the protocol is disabled and every call is a no-op.
 */
public final class StartupProtocol {

  private static final StartupProtocol DISABLED = new StartupProtocol(null);
  private static volatile StartupProtocol current = DISABLED;

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final PrintStream out;
  private StartupStep running;
  private boolean finished;

  private StartupProtocol(PrintStream out) {
    this.out = out;
  }

  /** Makes {@code out} (the process's original stdout) the protocol channel; called once by the launcher. */
  static StartupProtocol install(PrintStream out) {
    StartupProtocol protocol = writingTo(out);
    current = protocol;
    return protocol;
  }

  /** A protocol on {@code out} that is not the process's own (tests). */
  static StartupProtocol writingTo(PrintStream out) {
    return new StartupProtocol(Objects.requireNonNull(out, "out"));
  }

  /** The active protocol, or a disabled one when the core wasn't started by the launcher. */
  public static StartupProtocol current() {
    return current;
  }

  public boolean enabled() {
    return out != null;
  }

  public synchronized void running(StartupStep step) {
    running = step;
    progress(step, "running");
  }

  public synchronized void done(StartupStep step) {
    if (running == step) {
      running = null;
    }
    progress(step, "done");
  }

  /** The ready line; {@code facts} carries everything but {@code v} and {@code ready}. */
  public synchronized void ready(ObjectNode facts) {
    if (finished) {
      return;
    }
    ObjectNode line = header();
    line.put("ready", true);
    line.setAll(facts);
    write(line);
    finished = true;
  }

  /**
   * Marks the running step failed and writes the {@code ready:false} line. Later calls are ignored: the first
   * failure is the one Electron shows.
   */
  public synchronized void failed(StartupReason reason, String detail, ObjectNode extra) {
    if (finished) {
      return;
    }
    if (running != null) {
      progress(running, "failed");
      running = null;
    }
    ObjectNode line = header();
    line.put("ready", false);
    line.put("reason", reason.name());
    if (detail != null) {
      line.put("detail", detail);
    }
    if (extra != null) {
      line.setAll(extra);
    }
    write(line);
    finished = true;
  }

  /** Whether the ready line, of either kind, was written. */
  public synchronized boolean finished() {
    return finished;
  }

  private void progress(StartupStep step, String state) {
    if (finished) {
      return;
    }
    ObjectNode line = header();
    line.put("step", step.id());
    line.put("state", state);
    write(line);
  }

  private static ObjectNode header() {
    ObjectNode line = JsonNodeFactory.instance.objectNode();
    line.put("v", 1);
    return line;
  }

  private void write(ObjectNode line) {
    if (out == null) {
      return;
    }
    byte[] bytes = (JSON.writeValueAsString(line) + "\n").getBytes(StandardCharsets.UTF_8);
    out.write(bytes, 0, bytes.length);
    out.flush();
  }
}
