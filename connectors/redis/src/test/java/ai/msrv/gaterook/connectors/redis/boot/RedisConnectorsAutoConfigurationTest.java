package ai.msrv.gaterook.connectors.redis.boot;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Connector;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class RedisConnectorsAutoConfigurationTest {

  @Test
  void isListedInTheModulesAutoConfigurationImports() {
    assertThat(ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()).getCandidates())
        .contains(RedisConnectorsAutoConfiguration.class.getName());
  }

  @Test
  void registersTheConnector() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(RedisConnectorsAutoConfiguration.class))
        .run(ctx -> assertThat(ctx.getBeansOfType(Connector.class).values())
            .extracting(c -> c.manifest().id())
            .containsExactly("redis"));
  }

  @Test
  void schemasAreNativeImageResources() {
    RuntimeHints hints = new RuntimeHints();
    new RedisHints().registerHints(hints, getClass().getClassLoader());

    assertThat(RuntimeHintsPredicates.resource().forResource("connectors/redis/redis_get.input.schema.json"))
        .accepts(hints);
    assertThat(RuntimeHintsPredicates.resource().forResource("connectors/redis/config.schema.json"))
        .accepts(hints);
  }

  @Test
  void jedisFindsItsVersionInNative() {
    RuntimeHints hints = new RuntimeHints();
    new RedisHints().registerHints(hints, getClass().getClassLoader());

    assertThat(RuntimeHintsPredicates.resource().forResource("redis/clients/jedis/pom.properties")).accepts(hints);
    // The hint names a path in the Jedis jar: a Jedis that moved the file would leave the hint pointing at nothing.
    assertThat(getClass().getClassLoader().getResource("redis/clients/jedis/pom.properties")).isNotNull();
  }
}
