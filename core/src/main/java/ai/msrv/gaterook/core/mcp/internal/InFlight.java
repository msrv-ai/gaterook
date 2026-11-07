package ai.msrv.gaterook.core.mcp.internal;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The calls that run now, by who sent them and the JSON-RPC id they sent, so that a client's
 * {@code notifications/cancelled} stops its own call and nobody else's: the principal, the client of a bridge that
 * several clients share ({@code X-Gateway-Bridge-Client}), the id. A cancel stops the call as its deadline would (the
 * connector's {@code onCancel}: an MCP server hears its own {@code notifications/cancelled}); it never touches an
 * approval, whose request a person decides.
 */
@Component
public class InFlight {

  /** One running call. */
  record Key(String principal, String client, String id) {

    static Key of(McpRequest r) {
      return new Key(r.caller().principalId(), r.client() == null ? "" : r.client(), text(r.env().id()));
    }

    static Key of(String principal, String client, JsonNode requestId) {
      return new Key(principal, client == null ? "" : client, text(requestId));
    }

    private static String text(JsonNode id) {
      return id == null ? "" : id.isString() ? "s:" + id.asString() : "n:" + id.asString();
    }
  }

  private final Map<Key, Runnable> calls = new ConcurrentHashMap<>();

  /** {@code cancel} stops the call of {@code key} until {@link #done}. */
  void started(Key key, Runnable cancel) {
    calls.put(key, cancel);
  }

  void done(Key key) {
    calls.remove(key);
  }

  /** @return whether a call of {@code key} ran and was told to stop */
  boolean cancel(Key key) {
    Runnable cancel = calls.remove(key);
    if (cancel == null) {
      return false;
    }
    cancel.run();
    return true;
  }
}
