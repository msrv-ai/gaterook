package ai.msrv.gaterook.core.policy.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Env;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class LimitsOfTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void logRangesAreUnrestrictedByDefaultInEveryEnvironment() {
    for (Env env : Env.values()) {
      assertThat(LimitsOf.from(JSON.createObjectNode(), env).maxRange()).as(env.id()).isNull();
      assertThat(LimitsOf.from(JSON.readTree("{\"logs\":{\"maxRangeHours\":0}}"), env).maxRange())
          .as(env.id()).isNull();
    }
  }

  /**
   * The SQL and HTTP sections' response sizes cap only their own connectors: a stricter one of them must not cut what
   * OpenSearch (the logs section), a connector without a section or the environment-wide limits return.
   */
  @Test
  void aSectionsResponseSizeCapsOnlyItsOwnConnectors() {
    ObjectNode sections = JSON.createObjectNode();
    ObjectNode limits = sections.putObject("limits").put("maxResponseKb", 2_048);
    sections.putObject("sql").put("maxResponseKb", 1_024);
    sections.putObject("http").put("maxResponseKb", 256);
    sections.putObject("logs").put("maxRangeHours", 0);

    assertThat(LimitsOf.from(sections, Env.PROD).maxResponseBytes()).isEqualTo(2_048 * 1024);
    assertThat(LimitsOf.from(sections, Env.PROD, "logs").maxResponseBytes()).isEqualTo(2_048 * 1024);
    assertThat(LimitsOf.from(sections, Env.PROD, "redis").maxResponseBytes()).isEqualTo(2_048 * 1024);
    assertThat(LimitsOf.from(sections, Env.PROD, "sql").maxResponseBytes()).isEqualTo(1_024 * 1024);
    assertThat(LimitsOf.from(sections, Env.PROD, "http").maxResponseBytes()).isEqualTo(256 * 1024);

    // The environment's size still caps every connector when it is the stricter one.
    limits.put("maxResponseKb", 128);
    for (String section : new String[] {null, "logs", "redis", "sql", "http"}) {
      assertThat(LimitsOf.from(sections, Env.PROD, section).maxResponseBytes()).as(String.valueOf(section))
          .isEqualTo(128 * 1024);
    }
  }

  @Test
  void explicitLogRangesKeepTheirExactDurationIncludingMoreThanOneYear() {
    for (long hours : new long[] {24, 168, 8_760, 17_520, 2_562_047_788_015L}) {
      var sections = JSON.createObjectNode();
      sections.putObject("logs").put("maxRangeHours", hours);
      assertThat(LimitsOf.from(sections, Env.PROD).maxRange()).isEqualTo(Duration.ofHours(hours));
    }
  }
}
