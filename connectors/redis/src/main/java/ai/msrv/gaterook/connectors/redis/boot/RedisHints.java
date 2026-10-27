package ai.msrv.gaterook.connectors.redis.boot;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Native-image hints of the Redis connector; they live with the module, not in the core (ARCHITECTURE.md §2.3).
 */
class RedisHints implements RuntimeHintsRegistrar {

  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    // Tool, connection and policy schemas are read with getResourceAsStream: null in native without this hint.
    hints.resources().registerPattern("connectors/redis/*.schema.json");
    // Jedis reads its version from this file and sends it with CLIENT SETINFO; without it every pooled
    // connection fails in native (report 15 §4).
    hints.resources().registerPattern("redis/clients/jedis/pom.properties");
  }
}
