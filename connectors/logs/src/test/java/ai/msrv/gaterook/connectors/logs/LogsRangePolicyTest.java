package ai.msrv.gaterook.connectors.logs;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudEndpoints;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Unlimited defaults and optional explicit caps, across every log backend and environment. */
class LogsRangePolicyTest {

  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-30T12:00:00Z"), ZoneOffset.UTC);
  private final CloudCredentials cloud = new CloudCredentials(CloudEndpoints.PRODUCTION, CLOCK);
  private final LogsConnector connector = new LogsConnector(cloud, CLOCK);

  @AfterEach
  void close() {
    connector.close();
    cloud.close();
  }

  @Test
  void schemaDefaultsToUnlimitedAndAcceptsCapsLongerThanAYear() {
    var policy = connector.manifest().policySchema();
    var range = policy.path("properties").path("maxRangeHours");
    assertThat(range.path("default").asInt(-1)).isZero();
    assertThat(range.path("x-env-default").isMissingNode()).isTrue();
    var schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(policy);
    for (long hours : new long[] {0, 24, 8_760, 17_520, 2_562_047_788_015L}) {
      assertThat(schema.validate(TestCtx.JSON.createObjectNode().put("maxRangeHours", hours)))
          .as("range of %s hours", hours).isEmpty();
    }
    assertThat(schema.validate(TestCtx.JSON.createObjectNode().put("maxRangeHours", -1))).isNotEmpty();
    assertThat(schema.validate(TestCtx.JSON.createObjectNode().put("maxRangeHours", 2_562_047_788_016L)))
        .as("durations must fit the wire format's milliseconds").isNotEmpty();
  }

  @ParameterizedTest
  @EnumSource(Env.class)
  void unlimitedSearchesAndTailAcceptMultiYearRangesWhileExplicitCapsStillDeny(Env env) {
    Limits defaults = env == Env.PROD ? TestCtx.prod() : TestCtx.dev();
    Limits unlimited = TestCtx.withRange(defaults, null);
    Limits capped = TestCtx.withRange(defaults, Duration.ofHours(24));
    for (ConnectionConfig backend : List.of(Configs.loki("http://127.0.0.1:3100"), Configs.gcp(),
        Configs.cloudWatch(true), Configs.openSearch("http://127.0.0.1:9200"))) {
      ConnectionConfig cfg = new ConnectionConfig(backend.id(), backend.name(), backend.type(), env,
          backend.version(), backend.config(), backend.secretFields(), backend.metadata());
      for (String from : List.of("-36h", "-365d", "-1095d")) {
        var search = Configs.call("logs_search", "{\"from\":\"" + from + "\"}");
        var tail = Configs.call("logs_tail", "{\"since\":\"" + from + "\",\"wait\":0}");
        for (var call : List.of(search, tail)) {
          assertThat(connector.classify(call, cfg, Configs.policy(env, unlimited)).kind())
              .as("%s %s %s unlimited", env, backend.name(), from).isEqualTo(Classification.Kind.READ);
          assertThat(connector.classify(call, cfg, Configs.policy(env, capped)).ruleId())
              .as("%s %s %s capped", env, backend.name(), from).isEqualTo(env.id() + ".logs.range");
        }
      }
    }
  }

  @Test
  void unlimitedLogQlRangeVectorsStillRespectAnExplicitCap() {
    var cfg = Configs.loki("http://127.0.0.1:3100");
    var call = Configs.call("logs_search", """
        {"query":"count_over_time({app=\\"api\\"}[730d])"}""");
    assertThat(connector.classify(call, cfg, Configs.policy(Env.DEV, TestCtx.withRange(TestCtx.dev(), null))).kind())
        .isEqualTo(Classification.Kind.READ);
    assertThat(connector.classify(call, cfg, Configs.policy(Env.DEV, TestCtx.dev())).ruleId())
        .isEqualTo("dev.logs.range");
  }
}
