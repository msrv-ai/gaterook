package gaterook.plugins.fixtures;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code oom_fill} keeps allocating until the heap its manifest asked for (64 MB) runs out: the host runs with
 * {@code -XX:+ExitOnOutOfMemoryError}, so the process ends (exit code 3) instead of limping on with a broken heap.
 * {@code oom_heap} answers with the heap the process got.
 */
public final class OomConnector extends Fixture {

  /** Held so that nothing can be collected. */
  private static final List<byte[]> HELD = new ArrayList<>();

  @Override
  public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    if (call.tool().equals("oom_fill")) {
      while (true) {
        HELD.add(new byte[1024 * 1024]);
      }
    }
    return ToolResult.text("heap " + Runtime.getRuntime().maxMemory() / (1024 * 1024) + " MB");
  }
}
