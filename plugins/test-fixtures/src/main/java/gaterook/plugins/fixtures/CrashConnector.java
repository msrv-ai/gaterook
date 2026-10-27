package gaterook.plugins.fixtures;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolResult;

/**
 * {@code crash_now} ends the whole plugin process with exit code 3, as a plugin calling {@code System.exit} would; the
 * core must fail the calls in flight, start the host again on the next call after a backoff, and give up after five
 * crashes in ten minutes. {@code crash_echo} answers, so a test can see the process work before and after.
 */
public final class CrashConnector extends Fixture {

  @Override
  public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    if (call.tool().equals("crash_now")) {
      System.exit(3);
    }
    return ToolResult.text("echo " + call.arguments().path("text").asString("") + " from "
        + ProcessHandle.current().pid());
  }
}
