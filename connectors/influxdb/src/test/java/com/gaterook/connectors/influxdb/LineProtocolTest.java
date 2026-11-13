package com.gaterook.connectors.influxdb;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.connectors.influxdb.LineProtocol.Batch;
import com.gaterook.connectors.influxdb.LineProtocol.Point;
import com.gaterook.connectors.influxdb.LineProtocol.Type;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Line protocol as GateRook reads it before an approval: exactly what every InfluxDB version reads the same way. */
class LineProtocolTest {

  private static Batch read(String text) {
    return LineProtocol.read(text, "ns", true);
  }

  @Test
  void pointsAreReadWithTheirTagsTypedFieldsAndTimestamps() {
    Batch b = read("cpu,host=web-1,region=eu usage=12.5,cores=4i,free=7u,ok=true,note=\"a \\\"quoted\\\" \\\\ note\" "
        + "1730000000000000000\nmy\\ meas,tag\\,key=a\\=b\\ c f=1e3,g=-0.25,h=F\n\n");
    assertThat(b.ok()).as(b.problem()).isTrue();
    assertThat(b.body()).isEqualTo("cpu,host=web-1,region=eu usage=12.5,cores=4i,free=7u,ok=true,note=\"a "
        + "\\\"quoted\\\" \\\\ note\" 1730000000000000000\nmy\\ meas,tag\\,key=a\\=b\\ c f=1e3,g=-0.25,h=F");
    Point cpu = b.points().get(0);
    assertThat(cpu.measurement()).isEqualTo("cpu");
    assertThat(cpu.tags()).containsExactly(java.util.Map.entry("host", "web-1"), java.util.Map.entry("region", "eu"));
    assertThat(cpu.fields()).extracting(LineProtocol.Field::type).containsExactly(Type.FLOAT, Type.INTEGER,
        Type.UNSIGNED, Type.BOOLEAN, Type.STRING);
    assertThat(cpu.fields().get(4).value()).isEqualTo("a \"quoted\" \\ note");
    assertThat(cpu.timestamp()).isEqualTo(1_730_000_000_000_000_000L);
    Point other = b.points().get(1);
    assertThat(other.measurement()).isEqualTo("my meas");
    assertThat(other.tags()).containsEntry("tag,key", "a=b c");
    assertThat(other.timestamp()).isNull();
    assertThat(other.fields().get(2).value()).isEqualTo("false");
    assertThat(LineProtocol.describe(cpu, "ns")).isEqualTo("cpu host=web-1 region=eu · usage=12.5 (float), cores=4 "
        + "(integer), free=7 (unsigned), ok=true (boolean), note=\"a \"quoted\" \\ note\" (string) · "
        + "2024-10-27T03:33:20Z");
  }

  @Test
  void timestampsFollowThePrecisionWithinInfluxDbsRange() {
    assertThat(LineProtocol.instant(1_730_000_000L, "s")).isEqualTo(Instant.parse("2024-10-27T03:33:20Z"));
    assertThat(LineProtocol.instant(1_730_000_000_123L, "ms")).isEqualTo(Instant.parse("2024-10-27T03:33:20.123Z"));
    assertThat(LineProtocol.instant(-1L, "ns")).isEqualTo(Instant.parse("1969-12-31T23:59:59.999999999Z"));
    assertThat(LineProtocol.instant(Long.MAX_VALUE, "ns")).isNull();
    assertThat(LineProtocol.instant(10_000_000_000L, "s")).isNull();
    assertThat(LineProtocol.read("m v=1 10000000000", "s", true).problem()).contains("outside InfluxDB's time range");
    assertThat(LineProtocol.read("m v=1 1730000000", "s", true).ok()).isTrue();
  }

  @Test
  void malformedOrAmbiguousLinesAreRefusedWithTheirLine() {
    List<String> refused = List.of(
        "cpu",                                   // no fields
        "cpu ",                                  // no fields
        "cpu  v=1",                              // two spaces
        "cpu v=1  1730000000",                   // two spaces before the timestamp
        "cpu v=1 ",                              // trailing space
        "cpu\tv=1",                              // a tab
        "cpu v=1\r",                             // a carriage return
        "# comment",                             // a comment
        " cpu v=1",                              // leading space
        "cpu,host=web\\\\1 v=1",                 // \\ in a tag: 3.x reads one backslash, 1.x and 2.x two
        "cpu,host=web\\n v=1",                   // another backslash
        "cpu,host=a=b v=1",                      // = in a tag value
        "cpu,host v=1",                          // a tag without a value
        "cpu,=a v=1",                            // a tag without a key
        "cpu,host=a,host=b v=1",                 // a tag twice
        "cpu v=1,v=2",                           // a field twice
        "cpu,time=1 v=1",                        // time as a tag
        "cpu time=1",                            // time as a field
        "_cpu v=1",                              // reserved measurement
        "cpu,_t=1 v=1",                          // reserved tag key
        "cpu _f=1",                              // reserved field key
        "cpu v=+1", "cpu v=.5", "cpu v=1.", "cpu v=NaN", "cpu v=1e999", "cpu v=-1u", "cpu v=99999999999999999999i",
        "cpu v=18446744073709551616u", "cpu v=tru", "cpu v=abc",
        "cpu s=\"open", "cpu s=\"a\\nb\"", "cpu s=\"a\"b", "cpu v=1 17300000.5", "cpu v=1 abc",
        "cpu v=1 99999999999999999999", "c\"pu v=1", "cpu,ta\"g=1 v=1", "cp=u v=1", "cpu v=1 1 2");
    for (String line : refused) {
      Batch b = read("ok v=1\n" + line);
      assertThat(b.ok()).as(line).isFalse();
      assertThat(b.problem()).as(line).startsWith("Line 2: ");
    }
    assertThat(read("cpu,host=web\\\\1 v=1").problem()).contains("read differently by InfluxDB versions");
  }

  @Test
  void unsignedIntegersDontExistOnInfluxDb1() {
    assertThat(LineProtocol.read("m v=1u", "ns", false).problem()).contains("no unsigned integers");
    assertThat(LineProtocol.read("m v=1u", "ns", true).ok()).isTrue();
  }

  @Test
  void aWriteHasLinesAndAtMostOneMebibyte() {
    assertThat(read("").ok()).isFalse();
    assertThat(read("\n\n").problem()).contains("no lines");
    assertThat(read("m s=\"" + "x".repeat(LineProtocol.MAX_BYTES) + "\"").problem()).contains("1 MiB");
    assertThat(read("m s=\"\uD800\"").problem()).contains("valid Unicode");
    assertThat(read("m s=\"日本語 ✓\",t=\"😀\"").ok()).isTrue();
  }
}
