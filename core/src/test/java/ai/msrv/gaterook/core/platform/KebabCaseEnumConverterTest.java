package ai.msrv.gaterook.core.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.Env;
import org.junit.jupiter.api.Test;

class KebabCaseEnumConverterTest {

  enum Sample { GCP_SM, READ_ONLY, PROD }

  static final class SampleConverter extends KebabCaseEnumConverter<Sample> {
    SampleConverter() {
      super(Sample.values());
    }
  }

  @Test
  void constantsBecomeTheIdsOfTheSchema() {
    SampleConverter converter = new SampleConverter();

    assertThat(converter.convertToDatabaseColumn(Sample.GCP_SM)).isEqualTo("gcp-sm");
    assertThat(converter.convertToDatabaseColumn(Sample.READ_ONLY)).isEqualTo("read-only");
    assertThat(converter.convertToEntityAttribute("read-only")).isEqualTo(Sample.READ_ONLY);
    assertThat(converter.convertToDatabaseColumn(null)).isNull();
    assertThat(converter.convertToEntityAttribute(null)).isNull();
  }

  @Test
  void unknownStoredValuesFailLoudly() {
    assertThatThrownBy(() -> new SampleConverter().convertToEntityAttribute("READ_ONLY"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void envMatchesConnectorApiIds() {
    EnvConverter converter = new EnvConverter();
    for (Env env : Env.values()) {
      assertThat(converter.convertToDatabaseColumn(env)).isEqualTo(env.id());
      assertThat(converter.convertToEntityAttribute(env.id())).isEqualTo(env);
    }
  }

  @Test
  void setupChangeKindsMatchTheirIds() {
    for (SetupChangeRecorder.What what : SetupChangeRecorder.What.values()) {
      assertThat(KebabCaseEnumConverter.idOf(what)).isEqualTo(what.id());
    }
  }
}
