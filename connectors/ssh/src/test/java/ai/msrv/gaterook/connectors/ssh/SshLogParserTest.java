package ai.msrv.gaterook.connectors.ssh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ConnectorException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class SshLogParserTest {
  private SshLogParser parser(String config) throws ConnectorException {
    return SshLogParser.of(TestConfigs.json(config));
  }

  @Test
  void recognizesIsoAndBracketedTextWithOffsetsAndFractions() throws Exception {
    var parser = parser("{}");
    var iso = parser.parse("2026-10-28T14:22:03.123Z INFO request accepted");
    assertThat(iso.timestamp()).isEqualTo(Instant.parse("2026-10-28T14:22:03.123Z"));
    assertThat(iso.level()).isEqualTo("INFO");
    var bracket = parser.parse("[2026-10-28 16:22:03,123456+0200] [worker] WARNING request delayed");
    assertThat(bracket.timestamp()).isEqualTo(Instant.parse("2026-10-28T14:22:03.123456Z"));
    assertThat(bracket.level()).isEqualTo("WARN");
  }

  @Test
  void readsSeverityFromTheHeaderRatherThanFromMessageProse() throws Exception {
    var parser = parser("{}");
    assertThat(parser.parse("2026-10-28T10:00:00Z request failed after a timeout error").level()).isNull();
    assertThat(parser.parse("2026-10-28T10:00:00Z INFO 42 --- [main] service: ERROR recovered").level()).isEqualTo("INFO");
    assertThat(parser.parse("2026-10-28T10:00:00Z [worker] [service] WARN retry").level()).isEqualTo("WARN");
    assertThat(parser.parse("[ERROR] plain error header").level()).isEqualTo("ERROR");
    assertThat(parser.parse("request failed with ERROR text").level()).isNull();
  }

  @Test
  void usesConfiguredTimeZoneAndNeverTheMachineTimeZone() throws Exception {
    var parser = parser("{\"timezone\":\"Europe/Madrid\"}");
    assertThat(parser.parse("2026-01-10 12:00:00 INFO winter").timestamp())
        .isEqualTo(Instant.parse("2026-01-10T11:00:00Z"));
    assertThat(parser.parse("2026-07-10 12:00:00 INFO summer").timestamp())
        .isEqualTo(Instant.parse("2026-07-10T10:00:00Z"));
    assertThat(parser("{}").parse("2026-07-10 12:00:00 INFO default").timestamp())
        .isEqualTo(Instant.parse("2026-07-10T12:00:00Z"));
  }

  @Test
  void refusesAmbiguousOrNonexistentDstLocalTimesUnlessAnOffsetDisambiguatesThem() throws Exception {
    var parser = parser("{\"timezone\":\"Europe/Madrid\"}");
    for (String time : new String[] {"2026-03-29 02:30:00", "2026-10-25 02:30:00"}) {
      var entry = parser.parse(time + " ERROR ambiguous");
      assertThat(entry.timestamp()).isNull();
      assertThat(entry.timestampStatus()).isEqualTo(SshLogParser.TimestampStatus.INVALID);
    }
    assertThat(parser.parse("2026-10-25T02:30:00+02:00 INFO earlier").timestamp())
        .isEqualTo(Instant.parse("2026-10-25T00:30:00Z"));
    assertThat(parser.parse("2026-10-25T02:30:00+01:00 INFO later").timestamp())
        .isEqualTo(Instant.parse("2026-10-25T01:30:00Z"));
  }

  @Test
  void rejectsInvalidDatesAndReportsStackTracesWithoutTimestamps() throws Exception {
    var parser = parser("{}");
    assertThat(parser.parse("2026-02-30T12:00:00Z ERROR impossible").timestampStatus())
        .isEqualTo(SshLogParser.TimestampStatus.INVALID);
    var trace = parser.parse("    at example.Service.run(Service.java:42)");
    assertThat(trace.timestamp()).isNull();
    assertThat(trace.timestampStatus()).isEqualTo(SshLogParser.TimestampStatus.MISSING);
    assertThat(trace.text()).contains("Service.java:42");
  }

  @Test
  void autoHandlesMixedTextAndJsonAndUsesKnownJsonFields() throws Exception {
    var parser = parser("{}");
    var json = parser.parse("{\"@timestamp\":\"2026-10-28T14:00:00Z\",\"log\":{\"level\":\"critical\"},\"message\":\"disk full\"}");
    assertThat(json.timestamp()).isEqualTo(Instant.parse("2026-10-28T14:00:00Z"));
    assertThat(json.level()).isEqualTo("FATAL");
    assertThat(json.malformed()).isFalse();
    assertThat(parser.parse("2026-10-28 14:00:00 DEBUG plain").level()).isEqualTo("DEBUG");
    assertThat(parser.parse("{\"timestamp\":\"2026-10-28T14:00:00Z\",\"log.level\":\"err\",\"log\":{\"level\":\"info\"}}").level())
        .isEqualTo("ERROR");
  }

  @Test
  void parsesExplicitNumericUnitsWithoutGuessingByNumberLength() throws Exception {
    var millis = parser("{\"format\":\"jsonl\"}");
    assertThat(millis.parse("{\"timestamp\":1700000000123}").timestamp()).isEqualTo(Instant.ofEpochMilli(1700000000123L));
    assertThat(millis.parse("{\"timestamp\":1700000000}").timestamp()).isEqualTo(Instant.ofEpochMilli(1700000000L));
    var seconds = parser("{\"timestampUnit\":\"seconds\"}");
    assertThat(seconds.parse("{\"ts\":1700000000.125}").timestamp()).isEqualTo(Instant.ofEpochSecond(1700000000, 125000000));
    assertThat(seconds.parse("{\"ts\":-0.25}").timestamp()).isEqualTo(Instant.ofEpochSecond(-1, 750000000));
    assertThat(millis.parse("{\"timestamp\":\"1700000000123\"}").timestamp()).isNull();
    assertThat(seconds.parse("{\"timestamp\":1e1000}").timestamp()).isNull();
  }

  @Test
  void supportsCustomJsonFieldsPointersAndStrictTimestampPatterns() throws Exception {
    var parser = parser("""
        {"timestampField":"event.created","levelField":"/attributes/severity",
         "timestampPattern":"dd/MM/yyyy HH:mm:ss","timezone":"Europe/Madrid"}
        """);
    var entry = parser.parse("{\"event\":{\"created\":\"28/10/2026 15:00:00\"},\"attributes\":{\"severity\":\"severe\"}}");
    assertThat(entry.timestamp()).isEqualTo(Instant.parse("2026-10-28T14:00:00Z"));
    assertThat(entry.level()).isEqualTo("ERROR");
    assertThat(parser.parse("{\"event\":{\"created\":\"30/02/2026 15:00:00\"}}").timestamp()).isNull();
    assertThat(parser.parse("{\"timestamp\":\"2026-10-28T14:00:00Z\"}").timestampStatus())
        .isEqualTo(SshLogParser.TimestampStatus.MISSING);
  }

  @Test
  void customTextPatternParsesThePrefixWithoutConsumingTheMessage() throws Exception {
    var parser = parser("{\"format\":\"text\",\"timestampPattern\":\"dd/MM/uuuu HH:mm:ss.SSS\"}");
    assertThat(parser.parse("28/10/2026 14:01:02.345 ERROR failed").timestamp())
        .isEqualTo(Instant.parse("2026-10-28T14:01:02.345Z"));
  }

  @Test
  void malformedJsonIsRetainedAndNeverReceivesATimestampFromItsMessage() throws Exception {
    var parser = parser("{\"format\":\"jsonl\"}");
    for (String line : new String[] {"{broken", "2026-10-28T14:00:00Z ERROR plain", "[]", "{} trailing"}) {
      var parsed = parser.parse(line);
      assertThat(parsed.malformed()).as(line).isTrue();
      assertThat(parsed.timestamp()).isNull();
      assertThat(parsed.text()).isEqualTo(line);
    }
    var noTime = parser.parse("{\"message\":\"2026-10-28T14:00:00Z ERROR plain\"}");
    assertThat(noTime.timestampStatus()).isEqualTo(SshLogParser.TimestampStatus.MISSING);
    assertThat(noTime.level()).isNull();
  }

  @Test
  void validatesConfigWithoutIo() {
    for (String config : new String[] {"{\"timezone\":\"Moon/Base\"}", "{\"format\":\"csv\"}",
        "{\"timestampUnit\":\"guess\"}", "{\"timestampPattern\":\"uuuu-MM-dd'\"}"}) {
      assertThatThrownBy(() -> parser(config)).as(config).isInstanceOf(ConnectorException.class);
    }
  }

  @Test
  void masksJsonObjectsBeforeExtractingSensitiveFieldsOrMatching() throws Exception {
    var parser = parser("{\"timestampField\":\"password\",\"levelField\":\"token\"}");
    var context = TestCtx.read().withMasker(tree -> {
        if (tree.isObject()) {
          ObjectNode copy = (ObjectNode) tree.deepCopy();
          copy.put("password", "[masked]").put("token", "[masked]");
          return copy;
        }
        return tree;
    });
    String masked = parser.mask("{\"password\":\"2026-10-28T14:00:00Z\",\"token\":\"ERROR\",\"message\":\"kept\"}", context);
    assertThat(masked).doesNotContain("2026-10-28", "ERROR").contains("kept");
    assertThat(parser.parse(masked).timestamp()).isNull();
    assertThat(parser.parse(masked).level()).isNull();
  }
}
