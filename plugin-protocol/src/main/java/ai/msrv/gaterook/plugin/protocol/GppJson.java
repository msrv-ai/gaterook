package ai.msrv.gaterook.plugin.protocol;

import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.util.JsonRecyclerPools;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The JSON both processes speak: tree model only (no data binding, so the native core needs no metadata for it) and
 * no recycled parser or generator buffers — a buffer that once held a secret is never handed to the next message.
 */
public final class GppJson {

  /** Parsers and generators with buffers of their own, dropped after use. */
  public static final JsonFactory FACTORY = JsonFactory.builder()
      .recyclerPool(JsonRecyclerPools.nonRecyclingPool())
      .build();

  /**
   * Reads and writes trees; never binds a class. Values are read out of a frame one member at a time, so what follows
   * a value in the same frame is not "trailing" here.
   */
  public static final JsonMapper MAPPER = JsonMapper.builder(FACTORY)
      .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
      .build();

  public static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private GppJson() {}

  public static ObjectNode object() {
    return NODES.objectNode();
  }
}
