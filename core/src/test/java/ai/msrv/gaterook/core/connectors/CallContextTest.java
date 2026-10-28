package ai.msrv.gaterook.core.connectors;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.core.platform.MaskPolicy;
import ai.msrv.gaterook.core.platform.Masker;
import ai.msrv.gaterook.core.platform.Taint;
import ai.msrv.gaterook.core.invocation.internal.ApprovedContext;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code ExecContext.masked}: the answer's masking ahead of time, for a connector that filters by values the agent
 * chose (C8 review, {@code aws_describe}'s {@code where}). It masks as the answer will be masked — key names, values
 * that look like credentials, this call's taint, the environment's personal data — and leaves the tree it was given
 * alone.
 */
class CallContextTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Limits LIMITS = new Limits(1000, 1000, 512 * 1024, 256 * 1024, 256 * 1024, 64 * 1024, 8192,
      Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofDays(7));
  private static final ExecContext.HandleCodec NO_HANDLES = new ExecContext.HandleCodec() {
    @Override
    public String seal(String purpose, JsonNode payload, Duration ttl) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<JsonNode> open(String purpose, String handle) {
      return Optional.empty();
    }
  };

  @Test
  void masksWhatTheAnswerWillMaskAndLeavesTheOriginal() {
    Taint taint = new Taint();
    taint.add("cnry-session-secret-0042");
    CallContext ctx = CallContext.builder("tc_1", Env.STAGE, LIMITS, Clock.systemUTC()).handles(NO_HANDLES)
        .taint(taint).mask(MaskPolicy.SECRETS_ONLY).build();
    JsonNode tree = JSON.readTree("""
        {"Variables":{"apiKey":"kAAA1","stage":"blue"},"tags":{"db_password":"hunter2","env":"stage"},
         "Description":"rotated: cnry-session-secret-0042","Engine":"postgres","owner":"ravi@acme.dev"}""");

    JsonNode masked = ctx.masked(tree);

    assertThat(masked.at("/Variables/apiKey").asString()).isEqualTo("‹masked›");
    assertThat(masked.at("/tags/db_password").asString()).isEqualTo("‹masked›");
    assertThat(masked.at("/Description").asString()).doesNotContain("cnry-session-secret-0042");
    assertThat(masked.at("/Variables/stage").asString()).isEqualTo("blue");
    assertThat(masked.at("/Engine").asString()).isEqualTo("postgres");
    assertThat(masked.at("/owner").asString()).as("stage: personal data shows").isEqualTo("ravi@acme.dev");
    assertThat(tree.at("/Variables/apiKey").asString()).as("the tree given stays as it was").isEqualTo("kAAA1");
  }

  @Test
  void theEnvironmentsPolicyAppliesWhenNoneIsSet() {
    CallContext prod = CallContext.builder("tc_2", Env.PROD, LIMITS, Clock.systemUTC()).handles(NO_HANDLES).build();

    assertThat(prod.masked(JSON.readTree("{\"owner\":\"ravi@acme.dev\"}")).path("owner").asString())
        .as("prod masks personal data by default").doesNotContain("ravi@acme.dev");
  }

  @Test
  void sourcePrefiltersCanRelyOnlyOnTheFixedTextReplacementContract() {
    Taint taint = new Taint();
    taint.add("cnry-prefilter-secret-0042");
    CallContext call = CallContext.builder("tc_filter", Env.DEV, LIMITS, Clock.systemUTC()).handles(NO_HANDLES)
        .taint(taint).build();
    ExecContext approved = new ApprovedContext(call, "apr_filter", null, null);
    for (ExecContext context : new ExecContext[] {call, approved}) {
      assertThat(context.textMaskMarker()).contains(Masker.MASK);
      assertThat(context.masked(JSON.getNodeFactory().textNode("before cnry-prefilter-secret-0042 after")).asString())
          .isEqualTo("before " + context.textMaskMarker().orElseThrow() + " after");
    }
  }
}
