package ai.msrv.gaterook.connectors.redis.boot;

import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connectors.redis.RedisConnector;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * Registers the Redis connector (ARCHITECTURE.md §2.3, A13): an explicit bean listed in this module's own {@code
 * AutoConfiguration.imports}, resolved by Spring AOT at build time. No {@code @Conditional*}: the type is switched
 * on and off at run time by {@code connector_type.enabled}.
 */
@AutoConfiguration
@ImportRuntimeHints(RedisHints.class)
public class RedisConnectorsAutoConfiguration {

  @Bean
  Connector redisConnector() {
    return new RedisConnector();
  }
}
