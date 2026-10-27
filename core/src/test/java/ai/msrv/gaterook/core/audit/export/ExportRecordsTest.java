package ai.msrv.gaterook.core.audit.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.core.platform.HmacKeys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The formats of an export, the head's MAC, and the schedules. */
class ExportRecordsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void jsonLinesCarryEveryRecordAndTheHeadLast() {
    Batch batch = SiemSenderTest.batch();

    String text = new String(ExportRecords.jsonl(batch.records()), StandardCharsets.UTF_8);

    List<String> lines = text.lines().toList();
    assertThat(lines).hasSize(4);
    assertThat(text).endsWith("\n");
    assertThat(JSON.readTree(lines.get(1)).path("args").path("sql").asString()).isEqualTo("DROP TABLE orders_archive");
    JsonNode head = JSON.readTree(lines.get(3));
    assertThat(head.path("type").asString()).isEqualTo("audit-head");
    assertThat(head.path("from").asLong()).isEqualTo(41);
    assertThat(head.path("id").asLong()).isEqualTo(43);
    assertThat(head.path("rows").asInt()).isEqualTo(3);
    assertThat(ExportRecords.verify(SiemSenderTest.KEYS, head)).isTrue();
  }

  @Test
  void csvHasTheHeaderAQuotedRowPerRecordAndDefusesFormulas() {
    Batch batch = SiemSenderTest.batch();
    ObjectNode sneaky = batch.rows().getFirst().deepCopy();
    sneaky.put("errorMessage", "=HYPERLINK(\"http://evil\")");

    String text = new String(ExportRecords.csv(List.of(sneaky, batch.head())), StandardCharsets.UTF_8);

    List<String> lines = text.lines().toList();
    assertThat(lines.getFirst()).startsWith("type,id,callId,phase,at,").endsWith(",prevSha256,rowSha256,from,rows,mac");
    assertThat(lines.get(1)).startsWith("\"tool-call\",\"41\",\"tc_41\"").contains("\"'=HYPERLINK(\"\"http://evil\"\")\"");
    assertThat(lines.get(2)).startsWith("\"audit-head\",\"43\"");
  }

  @Test
  void aHeadSignedUnderAnotherKeyOrEditedDoesntVerify() {
    ObjectNode head = ExportRecords.head(SiemSenderTest.KEYS, null, 7, 2, null, "f".repeat(64),
        Instant.parse("2026-10-22T12:30:00Z"));
    assertThat(ExportRecords.verify(SiemSenderTest.KEYS, head)).isTrue();

    HmacKeys other = (purpose, data) -> SiemSenderTest.KEYS.mac(HmacKeys.Purpose.ROW_MAC, data);
    assertThat(ExportRecords.verify(other, head)).as("another purpose's key").isFalse();
    ObjectNode moved = head.deepCopy();
    moved.put("id", 8);
    assertThat(ExportRecords.verify(SiemSenderTest.KEYS, moved)).isFalse();
    HmacKeys locked = (purpose, data) -> {
      throw new IllegalStateException("locked");
    };
    assertThat(ExportRecords.verify(locked, head)).isFalse();
    assertThatThrownBy(() -> ExportRecords.head(locked, null, 1, 0, null, null, Instant.now()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void bigQueryRowsCarryTheJsonFieldsAsText() {
    ObjectNode denied = SiemSenderTest.batch().rows().get(1);
    ObjectNode flat = ExportRecords.flat(denied);
    assertThat(flat.path("args").isString()).isTrue();
    assertThat(JSON.readTree(flat.path("args").asString()).path("sql").asString())
        .isEqualTo("DROP TABLE orders_archive");
    assertThat(BigQuerySender.insertId(SiemSenderTest.batch(), denied)).isEqualTo("42");
    assertThat(BigQuerySender.insertId(SiemSenderTest.batch(), SiemSenderTest.batch().head()))
        .isEqualTo("head-000000000041-000000000043");
  }

  @Test
  void schedulesParseAndFindTheirNextTime() {
    ZoneId berlin = ZoneId.of("Europe/Berlin");
    assertThat(Schedule.parse("stream")).isEqualTo(Schedule.STREAM);
    assertThat(Schedule.parse("daily").at()).isEqualTo(LocalTime.of(2, 0));
    assertThat(Schedule.parse("daily@14:30").id()).isEqualTo("daily@14:30");
    assertThatThrownBy(() -> Schedule.parse("weekly")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Schedule.parse("daily@25:00")).isInstanceOf(IllegalArgumentException.class);

    Instant at = Instant.parse("2026-10-22T12:30:00Z");
    assertThat(Schedule.HOURLY.nextAfter(at, berlin)).isEqualTo(Instant.parse("2026-10-22T13:00:00Z"));
    assertThat(Schedule.DAILY.nextAfter(at, berlin)).as("02:00 CEST tomorrow")
        .isEqualTo(Instant.parse("2026-10-23T00:00:00Z"));
    assertThat(Schedule.DAILY.nextAfter(Instant.parse("2026-10-22T23:59:00Z"), berlin))
        .as("already 01:59 there: tonight").isEqualTo(Instant.parse("2026-10-23T00:00:00Z"));
    assertThat(Schedule.parse("daily@02:30").nextAfter(Instant.parse("2026-10-24T23:00:00Z"), berlin))
        .as("02:30 on the night the clocks go back happens twice; the first one")
        .isEqualTo(Instant.parse("2026-10-25T00:30:00Z"));
  }
}
