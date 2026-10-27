package gaterook.plugins.fixtures;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Writes where it shouldn't (report 16 §4.2): {@code System.out.println} in its static initializer (the host pointed
 * {@code System.out} at stderr, so this lands in the plugin's log) and, in {@code polluter_echo}, {@code lines} raw
 * lines straight to file descriptor 1 — the protocol channel — as native code would. The core skips lines that aren't
 * JSON and keeps talking; more than 20 in a minute and it restarts the host.
 */
public final class StdoutPolluterConnector extends Fixture {

  static {
    System.out.println("stdout-polluter: hello from a static initializer");
  }

  @Override
  public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    int lines = call.arguments().path("lines").asInt(1);
    // Never closed: that would close the process's real stdout.
    FileOutputStream raw = new FileOutputStream(FileDescriptor.out);
    try {
      for (int i = 0; i < lines; i++) {
        raw.write(("polluted line " + i + " from native code\n").getBytes(StandardCharsets.UTF_8));
      }
      raw.flush();
    } catch (IOException e) {
      return ToolResult.error("couldn't write: " + e.getMessage());
    }
    return ToolResult.text("wrote " + lines + " raw line(s) and still answered, pid "
        + ProcessHandle.current().pid());
  }
}
