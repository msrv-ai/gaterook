package ai.msrv.gaterook.plugin.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Limits;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class WireCodecTest {

  @Test
  void anUnrestrictedLogRangeRoundTripsThroughNumericZero() {
    Limits original = limits(null);
    var wire = WireCodec.limits(original);
    assertThat(wire.path("maxRangeMs").asLong()).isZero();
    assertThat(WireCodec.limits(wire)).isEqualTo(original);
  }

  @Test
  void missingOrNullLogRangesUseTheUnrestrictedDefault() {
    var wire = WireCodec.limits(limits(Duration.ofHours(24)));
    wire.remove("maxRangeMs");
    assertThat(WireCodec.limits(wire).maxRange()).isNull();
    wire.putNull("maxRangeMs");
    assertThat(WireCodec.limits(wire).maxRange()).isNull();
  }

  @Test
  void explicitLogRangesPreserveTheirExistingWireValues() {
    for (long hours : new long[] {24, 168, 17_520, 2_562_047_788_015L}) {
      Limits original = limits(Duration.ofHours(hours));
      var wire = WireCodec.limits(original);
      assertThat(wire.path("maxRangeMs").asLong()).isEqualTo(hours * 3_600_000);
      assertThat(WireCodec.limits(wire)).isEqualTo(original);
    }
  }

  @Test
  void suggestionsFromAPluginAreBoundedAsTheyAreRead() {
    tools.jackson.databind.node.ObjectNode wire = GppJson.object();
    tools.jackson.databind.node.ObjectNode fields = wire.putObject("fields");
    for (int i = 0; i < WireCodec.MAX_SUGGESTED_FIELDS + 5; i++) {
      fields.putObject("/f" + i).putArray("options").addObject().put("value", "v" + i);
    }
    tools.jackson.databind.node.ArrayNode many = fields.putObject("/f0").putArray("options");
    for (int i = 0; i < ai.msrv.gaterook.connector.api.Suggestions.MAX_OPTIONS + 3; i++) {
      many.addObject().put("value", "o" + i);
    }
    many.addObject().put("label", "no value");
    tools.jackson.databind.node.ArrayNode notes = wire.putArray("notes");
    for (int i = 0; i < WireCodec.MAX_SUGGESTION_NOTES + 5; i++) {
      notes.add("note " + i);
    }
    notes.add(42);

    var s = WireCodec.suggestions(wire);

    assertThat(s.fields()).hasSize(WireCodec.MAX_SUGGESTED_FIELDS);
    assertThat(s.fields().get("/f0").options()).hasSize(ai.msrv.gaterook.connector.api.Suggestions.MAX_OPTIONS);
    assertThat(s.fields().get("/f0").complete()).isFalse();
    assertThat(s.fields().get("/f1").complete()).isTrue();
    assertThat(s.notes()).hasSize(WireCodec.MAX_SUGGESTION_NOTES);
    assertThat(WireCodec.suggestions(GppJson.object()).isEmpty()).isTrue();
    assertThat(WireCodec.suggestions((tools.jackson.databind.JsonNode) null).isEmpty()).isTrue();
  }

  private static Limits limits(Duration range) {
    return new Limits(1000, 1000, 524_288, 1_048_576, 262_144, 65_536, 8192, Duration.ofSeconds(5),
        Duration.ofSeconds(30), Duration.ofSeconds(10), range);
  }
}
