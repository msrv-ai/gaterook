package ai.msrv.gaterook.connectors.redis;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The native smoke's fixture touch ({@code smoke/redis.json}, fixture {@code redis}) holds against the fixture it
 * describes: the local redis-server with TLS for localhost, user ro, the list smoke:list.
 */
class SmokeFixtureIT {

  @Test
  void theFixtureTouchHoldsAgainstARealServer() throws Exception {
    JsonNode touch = null;
    for (JsonNode t : SmokeDescriptorTest.descriptor().path("touches")) {
      if (t.path("fixture").asString("").equals("redis")) {
        touch = t;
      }
    }
    assertThat(touch).isNotNull();
    LocalRedis.Certificates certs = LocalRedis.Certificates.create(Path.of("target", "redis-it",
        "smoke-tls-" + ProcessHandle.current().pid()).toAbsolutePath());
    try (LocalRedis redis = LocalRedis.start("smoke", LocalRedis.Options.of(LocalRedis.localBinary())
        .withTls(certs)); RedisConnector connector = new RedisConnector(Clock.systemUTC())) {
      try (Jedis j = redis.admin()) {
        j.rpush("smoke:list", "a", "b", "c");
      }
      Map<String, String> fixture = Map.of("tlsPort", Integer.toString(redis.tlsPort), "caCertificate",
          certs.caPem(), "user", "ro", "password", "ro-pass");
      ConnectionConfig cfg = SmokeDescriptorTest.connection(touch, fixture);
      ToolResult r = OutputSchema.get(connector.execute(new ToolCall("tc_smoke", touch.at("/call/tool").asString(),
          cfg.name(), (ObjectNode) touch.at("/call/arguments"), null), cfg,
          SmokeDescriptorTest.secrets(touch, fixture), TestCtx.of()));
      assertThat(r.isError()).isEqualTo(touch.at("/expect/isError").asBoolean());
      // As JSON text, the way the smoke compares what came over MCP (3 is 3 whether int or long here).
      touch.at("/expect/structuredContent").properties().forEach(e -> assertThat(r.structuredContent()
          .path(e.getKey()).toString()).as(e.getKey()).isEqualTo(e.getValue().toString()));
      assertThat(r.structuredContent().path("nextCursor").asString()).startsWith("rgc.");
    }
  }
}
