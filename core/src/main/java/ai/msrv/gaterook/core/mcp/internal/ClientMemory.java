package ai.msrv.gaterook.core.mcp.internal;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * What a legacy client said about itself in its last {@code initialize}, by principal: without sessions the later
 * requests don't repeat it, and Activity still wants "reports itself as codex-mcp-client 0.164.0". Display only, never
 * a decision; bounded, in memory.
 */
@Component
public class ClientMemory {

  private static final int MAX = 1_024;

  private final Map<String, JsonNode> byPrincipal = new LinkedHashMap<>(64, 0.75f, true) {
    private static final long serialVersionUID = 1L;

    @Override
    protected boolean removeEldestEntry(Map.Entry<String, JsonNode> eldest) {
      return size() > MAX;
    }
  };

  public synchronized void remember(String principalId, JsonNode clientInfo) {
    if (clientInfo != null && clientInfo.isObject()) {
      byPrincipal.put(principalId, clientInfo.deepCopy());
    }
  }

  public synchronized JsonNode recall(String principalId) {
    return byPrincipal.get(principalId);
  }
}
