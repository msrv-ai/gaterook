package ai.msrv.gaterook.connector.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Non-secret config of one connection. Secret fields ({@code writeOnly}) are only named here (JSON pointers in
 * {@code secretFields}); their values live behind {@link SecretResolver}.
 *
 * <p>{@code version} changes on every edit, secret rotation and policy change: {@code (id, version)} is a safe
 * pool key.
 */
public record ConnectionConfig(String id, String name, String type, Env env, long version, JsonNode config,
    Set<String> secretFields, JsonNode metadata) {

  public String string(String ptr) {
    JsonNode n = config.at(ptr);
    return n.isMissingNode() || n.isNull() ? null : n.asString();
  }

  public int integer(String ptr, int dflt) {
    JsonNode n = config.at(ptr);
    return n.isMissingNode() || n.isNull() ? dflt : n.asInt(dflt);
  }

  public boolean bool(String ptr, boolean dflt) {
    JsonNode n = config.at(ptr);
    return n.isMissingNode() || n.isNull() ? dflt : n.asBoolean(dflt);
  }

  public List<String> strings(String ptr) {
    List<String> out = new ArrayList<>();
    for (JsonNode v : config.at(ptr)) {
      out.add(v.asString());
    }
    return out;
  }
}
