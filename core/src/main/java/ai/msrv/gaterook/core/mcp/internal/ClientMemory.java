package ai.msrv.gaterook.core.mcp.internal;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * What a legacy client said about itself in its last {@code initialize}, by principal: without sessions the later
 * requests don't repeat it. Client info labels Activity; capabilities select the presentation and delivery flow.
 * Neither authenticates a person or authorizes a decision. Bounded, in memory.
 */
@Component
public class ClientMemory {

  private static final int MAX = 1_024;

  private record Remembered(JsonNode info, JsonNode capabilities) {}

  private final Map<String, Remembered> byPrincipal = new LinkedHashMap<>(64, 0.75f, true) {
    private static final long serialVersionUID = 1L;

    @Override
    protected boolean removeEldestEntry(Map.Entry<String, Remembered> eldest) {
      return size() > MAX;
    }
  };

  public synchronized void remember(String principalId, JsonNode clientInfo) {
    remember(principalId, clientInfo, null);
  }

  public synchronized void remember(String principalId, JsonNode clientInfo, JsonNode capabilities) {
    byPrincipal.put(principalId, new Remembered(
        clientInfo != null && clientInfo.isObject() ? clientInfo.deepCopy() : null,
        capabilities != null && capabilities.isObject() ? capabilities.deepCopy() : null));
  }

  public synchronized JsonNode recall(String principalId) {
    Remembered remembered = byPrincipal.get(principalId);
    return remembered == null ? null : remembered.info();
  }

  public synchronized JsonNode capabilities(String principalId) {
    Remembered remembered = byPrincipal.get(principalId);
    return remembered == null ? null : remembered.capabilities();
  }
}
