package gaterook.plugins.fixtures;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolResult;

/**
 * {@code hang_forever} never returns and swallows every interrupt, so {@code $/cancel} can't stop it: the core answers
 * the agent at the deadline, counts the call as stuck, and after two stuck calls moves new calls to a fresh process
 * and kills this one. {@code hang_echo} answers at once, so a test can see which process serves.
 */
public final class HangConnector extends Fixture {

  @Override
  public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    if (call.tool().equals("hang_forever")) {
      while (true) {
        try {
          Thread.sleep(1_000);
        } catch (InterruptedException e) {
          // Ignored on purpose: this is the plugin that won't stop.
        }
      }
    }
    return ToolResult.text("echo from " + ProcessHandle.current().pid());
  }
}
