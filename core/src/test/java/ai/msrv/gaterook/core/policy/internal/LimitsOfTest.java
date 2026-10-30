package ai.msrv.gaterook.core.policy.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Env;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

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

  @Test
  void explicitLogRangesKeepTheirExactDurationIncludingMoreThanOneYear() {
    for (long hours : new long[] {24, 168, 8_760, 17_520, 2_562_047_788_015L}) {
      var sections = JSON.createObjectNode();
      sections.putObject("logs").put("maxRangeHours", hours);
      assertThat(LimitsOf.from(sections, Env.PROD).maxRange()).isEqualTo(Duration.ofHours(hours));
    }
  }
}
