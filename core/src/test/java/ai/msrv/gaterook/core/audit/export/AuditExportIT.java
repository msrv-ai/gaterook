package ai.msrv.gaterook.core.audit.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.audit.AuditBackfill;
import ai.msrv.gaterook.core.audit.AuditExports;
import ai.msrv.gaterook.core.audit.AuditQueries;
import ai.msrv.gaterook.core.audit.AuditWriter;
import ai.msrv.gaterook.core.audit.internal.AuditRetentionJob;
import ai.msrv.gaterook.core.platform.Caller;
import ai.msrv.gaterook.core.platform.CanonicalJson;
import ai.msrv.gaterook.core.platform.HmacKeys;
import ai.msrv.gaterook.core.platform.Ids;
import ai.msrv.gaterook.core.platform.Ingress;
import ai.msrv.gaterook.core.platform.IntegrityAlerts;
import ai.msrv.gaterook.core.platform.ProblemException;
import ai.msrv.gaterook.core.platform.SettingStore;
import ai.msrv.gaterook.testfixtures.TestCore;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.http.SdkHttpMethod;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Audit export on a whole core (ARCHITECTURE.md §10 K5) against fakes of S3 and BigQuery (MockWebServer) and a SIEM
 * (syslog over TLS in the test): every row reaches the destination once under a name that a retry repeats — also
 * across a stop between the delivery and the cursor —, the signed head goes with every batch, a destination fails
 * with the destination's own words and recovers on Retry, the scheduler streams by itself, retention never deletes
 * what a destination hasn't taken, secrets are write-only and every change is a Setup change.
 */
class AuditExportIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String ACCESS_KEY = "AKIAIOSFODNN7EXAMPLE";
  private static final String SECRET_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
  private static final Caller CODEX = new Caller(Caller.Kind.LOCAL_TOKEN, "tok_test", "codex-laptop", "codex",
      Ingress.LOCAL, "127.0.0.1");

  @Test
  void s3GetsEveryRowOnceWithItsHeadAndARetryWritesTheSameObject() throws Exception {
    try (FakeCloud cloud = new FakeCloud()) {
      Path home;
      String id;
      List<Long> all = new ArrayList<>();
      try (TestCore core = core("export-s3", cloud)) {
        home = core.home();
        all.addAll(calls(core, 3, "sql_query"));
        AuditExports exports = core.bean(AuditExports.class);
        id = exports.create(s3(cloud, "jsonl"), "SOC wants a copy").id();

        AuditExports.Destination d = exports.run(id);

        assertThat(d.state()).isEqualTo("ok");
        assertThat(d.lastExportedId()).isEqualTo(all.getLast());
        assertThat(d.pending()).isFalse();
        FakeCloud.Request put = cloud.requests("PUT", "/acme-audit/").getFirst();
        String day = DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneOffset.UTC).format(Instant.now());
        assertThat(put.path()).isEqualTo("/acme-audit/mcp-gateway/" + day + "/tool-calls-000000000001-"
            + String.format("%012d", all.getLast()) + ".jsonl");
        assertThat(put.header("Content-Type")).isEqualTo("application/x-ndjson");
        assertSignedLikeAws(put);
        List<JsonNode> records = lines(put.body());
        assertThat(records.subList(0, records.size() - 1)).extracting(r -> r.path("id").asLong())
            .containsExactlyElementsOf(all);
        assertChained(records.subList(0, records.size() - 1));
        JsonNode head = records.getLast();
        assertThat(head.path("type").asString()).isEqualTo("audit-head");
        assertThat(head.path("id").asLong()).isEqualTo(all.getLast());
        assertThat(head.path("rows").asInt()).isEqualTo(all.size());
        assertThat(head.path("rowSha256").asString()).isEqualTo(records.get(records.size() - 2).path("rowSha256")
            .asString());
        assertThat(ExportRecords.verify(core.bean(HmacKeys.class), head)).as("the head's MAC").isTrue();
        assertThat(records.getFirst().path("args").path("sql").asString()).startsWith("SELECT");

        // The core stops after the delivery, before the cursor moves: the batch stays pending.
        all.addAll(calls(core, 3, "sql_execute"));
        ExportRunner runner = core.bean(ExportRunner.class);
        runner.afterDelivery = b -> {
          throw new IllegalStateException("The core stopped here");
        };
        AuditExports.Destination stopped = exports.run(id);
        assertThat(stopped.pending()).isTrue();
        assertThat(stopped.lastExportedId()).as("the cursor didn't move").isEqualTo(d.lastExportedId());
        assertThat(cloud.puts()).isEqualTo(2);
        runner.afterDelivery = b -> {};

        AuditExports.Destination retried = exports.run(id);
        assertThat(retried.state()).isEqualTo("ok");
        assertThat(retried.lastExportedId()).isEqualTo(all.getLast());
        assertThat(cloud.puts()).as("the same batch again").isEqualTo(3);
        assertThat(cloud.objects()).as("written over, not beside").hasSize(2);

        // Once more, and this time the core really stops.
        all.addAll(calls(core, 2, "logs_search"));
        runner.afterDelivery = b -> {
          throw new IllegalStateException("The core stopped here");
        };
        assertThat(exports.run(id).pending()).isTrue();
      }
      try (TestCore again = TestCore.reopen(home, args(cloud))) {
        AuditExports.Destination resumed = again.bean(AuditExports.class).run(id);
        assertThat(resumed.state()).isEqualTo("ok");
        assertThat(resumed.pending()).isFalse();
        assertThat(resumed.lastExportedId()).isEqualTo(all.getLast());
      }
      assertThat(cloud.objects()).hasSize(3);
      // Every row once in the bucket: nothing lost, nothing twice.
      List<Long> exported = new ArrayList<>();
      for (byte[] object : cloud.objects().values()) {
        List<JsonNode> records = lines(object);
        records.subList(0, records.size() - 1).forEach(r -> exported.add(r.path("id").asLong()));
      }
      assertThat(exported).containsExactlyInAnyOrderElementsOf(all);
    }
  }

  @Test
  void csvGoesToS3WithTheSameRows() throws Exception {
    try (FakeCloud cloud = new FakeCloud(); TestCore core = core("export-s3-csv", cloud)) {
      List<Long> ids = calls(core, 2, "sql_query");
      AuditExports exports = core.bean(AuditExports.class);
      String id = exports.create(s3(cloud, "csv"), null).id();

      exports.run(id);

      FakeCloud.Request put = cloud.requests("PUT", "/acme-audit/").getFirst();
      assertThat(put.path()).endsWith(".csv");
      List<String> lines = put.text().lines().toList();
      assertThat(lines.getFirst()).startsWith("type,id,callId");
      assertThat(lines).hasSize(ids.size() + 2);
      assertThat(lines.getLast()).startsWith("\"audit-head\",\"" + ids.getLast() + "\"");
    }
  }

  @Test
  void bigQueryFailsInGooglesWordsAndRetryInsertsWithInsertIds() throws Exception {
    try (FakeCloud cloud = new FakeCloud(); TestCore core = core("export-bigquery", cloud)) {
      List<Long> ids = calls(core, 3, "sql_query");
      AuditExports exports = core.bean(AuditExports.class);
      String id = exports.create(new AuditExports.Draft("bigquery", "warehouse", true, JSON.readTree("""
          {"table":"acme-prod.audit.mcp_calls","credentials":{"source":"serviceAccountKey"}}"""), "hourly",
          Map.of(ExportSettings.BIGQUERY_KEY, new AuditExports.SecretInput("local",
              FakeCloud.serviceAccountKey().toCharArray(), null, null))), null).id();
      cloud.bigQueryFails(403, "Access Denied: Table acme-prod:audit.mcp_calls: Permission "
          + "bigquery.tables.updateData denied for gw-audit@acme-prod.iam.gserviceaccount.com");

      AuditExports.Destination failing = exports.run(id);

      assertThat(failing.state()).isEqualTo("failing");
      assertThat(failing.lastError()).startsWith("403 Forbidden: Access Denied: Table acme-prod:audit.mcp_calls");
      assertThat(failing.failingSince()).isNotNull();
      assertThat(failing.lastExportedId()).isNull();
      assertThat(failing.pending()).isTrue();
      assertThat(JSON.writeValueAsString(failing)).doesNotContain(FakeCloud.TOKEN);

      cloud.bigQueryFails(0, null);
      AuditExports.Destination retried = exports.run(id);

      assertThat(retried.state()).isEqualTo("ok");
      assertThat(retried.failingSince()).isNull();
      assertThat(retried.lastError()).isNull();
      assertThat(retried.lastExportedId()).isEqualTo(ids.getLast());
      List<FakeCloud.Request> inserts = cloud.requests("POST", "/bigquery/v2/projects/acme-prod/datasets/audit/"
          + "tables/mcp_calls/insertAll");
      assertThat(inserts).hasSize(2);
      FakeCloud.Request insert = inserts.getLast();
      assertThat(insert.header("Authorization")).isEqualTo("Bearer " + FakeCloud.TOKEN);
      JsonNode body = JSON.readTree(insert.text());
      assertThat(body.path("ignoreUnknownValues").asBoolean(true)).isFalse();
      List<String> insertIds = new ArrayList<>();
      body.path("rows").forEach(r -> insertIds.add(r.path("insertId").asString()));
      assertThat(insertIds.subList(0, ids.size())).containsExactlyElementsOf(ids.stream().map(String::valueOf)
          .toList());
      assertThat(insertIds.getLast()).startsWith("head-");
      assertThat(body.path("rows").get(0).path("json").path("args").isString()).as("JSON as text").isTrue();
      // The retry sends the refused batch again: the same insert ids, the same rows.
      JsonNode first = JSON.readTree(inserts.getFirst().text());
      List<String> firstIds = new ArrayList<>();
      first.path("rows").forEach(r -> firstIds.add(r.path("insertId").asString()));
      assertThat(firstIds).isEqualTo(insertIds);
      assertThat(first.path("rows").get(0)).isEqualTo(body.path("rows").get(0));

      calls(core, 1, "sql_query");
      cloud.bigQueryRefusesRow(0, "invalid", "Cannot convert value to integer");
      AuditExports.Destination refused = exports.run(id);
      assertThat(refused.lastError()).isEqualTo("BigQuery refused row 0 of the batch: invalid at durationMs: Cannot "
          + "convert value to integer");

      cloud.tableColumns(List.of("type", "id", "callId"));
      AuditExports.Tested missing = exports.test(id);
      assertThat(missing.ok()).isFalse();
      assertThat(missing.message()).contains("lacks the columns").contains("rowSha256").contains("mac");
      List<String> columns = new ArrayList<>(ExportRecords.FIELDS);
      columns.addAll(ExportRecords.HEAD_FIELDS);
      cloud.tableColumns(columns);
      assertThat(exports.test(id).ok()).isTrue();
      assertThat(exports.get(id).state()).as("a test changes no state").isEqualTo("failing");
    }
  }

  @Test
  void aStreamingSiemGetsNewRowsByItself() throws Exception {
    try (FakeSyslog siem = new FakeSyslog(false); FakeCloud cloud = new FakeCloud();
        TestCore core = core("export-siem", cloud, "--gateway.audit.export.tick=200ms")) {
      AuditExports exports = core.bean(AuditExports.class);
      String id = exports.create(siem(siem, "stream"), null).id();

      List<Long> ids = calls(core, 5, "sql_query");
      SiemSenderTest.await(() -> siem.json("tool-call").size() == ids.size(), "the first rows");
      ids.addAll(calls(core, 2, "k8s_get"));
      SiemSenderTest.await(() -> siem.json("tool-call").size() == ids.size(), "the new rows");

      assertThat(siem.problems()).isEmpty();
      assertThat(siem.json("tool-call")).extracting(r -> r.path("id").asLong()).containsExactlyElementsOf(ids);
      assertThat(siem.json("audit-head")).hasSizeGreaterThanOrEqualTo(2).allSatisfy(h ->
          assertThat(ExportRecords.verify(core.bean(HmacKeys.class), h)).isTrue());
      assertThat(siem.json("audit-head").getLast().path("id").asLong()).isEqualTo(ids.getLast());
      SiemSenderTest.await(() -> ids.getLast().equals(exports.get(id).lastExportedId()), "the cursor");
      assertThat(exports.get(id).state()).isEqualTo("ok");
    }
  }

  @Test
  void retentionNeverDeletesWhatADestinationHasntTaken() throws Exception {
    try (FakeCloud cloud = new FakeCloud();
        TestCore core = core("export-retention", cloud, "--gateway.audit.export.batch.s3=4")) {
      // Three calls of 400 days ago (rows 1 to 6), then two of today.
      Instant old = Instant.now().minus(Duration.ofDays(400));
      List<AuditBackfill.PastCall> past = new ArrayList<>();
      for (int i = 0; i < 3; i++) {
        AuditWriter.Call call = call("sql_query");
        past.add(new AuditBackfill.PastCall(call, List.of(
            AuditBackfill.PastRow.received(old.plusSeconds(i), AuditWriter.Received.of(AuditWriter.Verdict.ALLOWED,
                Classification.Kind.READ, null)),
            AuditBackfill.PastRow.completed(old.plusSeconds(i).plusMillis(40), completion()))));
      }
      assertThat(core.bean(AuditBackfill.class).write(past)).isEqualTo(6);
      calls(core, 2, "sql_query");
      AuditExports exports = core.bean(AuditExports.class);
      AuditRetentionJob retention = core.bean(AuditRetentionJob.class);
      core.bean(SettingStore.class).put(AuditRetentionJob.SETTING, JsonNodeFactory.instance.objectNode()
          .put("retentionYears", 1), null);

      // A paused destination that never exported holds everything back.
      AuditExports.Draft paused = s3(cloud, "jsonl");
      String pausedId = exports.create(new AuditExports.Draft(paused.kind(), "paused", false, paused.config(),
          paused.schedule(), secret()), null).id();
      String id = exports.create(s3(cloud, "jsonl"), null).id();
      // Batches of four: the first one (rows 1 to 4) arrives and the cursor moves; the core stops after the second.
      AtomicInteger delivered = new AtomicInteger();
      core.bean(ExportRunner.class).afterDelivery = b -> {
        if (delivered.incrementAndGet() == 2) {
          throw new IllegalStateException("The core stopped here");
        }
      };
      AuditExports.Destination half = exports.run(id);
      assertThat(half.lastExportedId()).isEqualTo(4);
      assertThat(half.pending()).isTrue();

      assertThat(retention.run().toolCalls()).as("the paused destination took nothing").isZero();
      exports.delete(pausedId, "not needed");
      AuditRetentionJob.Run run = retention.run();

      assertThat(run.toolCalls()).as("old and taken: rows 1 to 4").isEqualTo(4);
      assertThat(core.jdbc().queryForList("select id from tool_call order by id", Long.class))
          .as("rows 5 and 6 are old but on their way; 7 to 10 are young").containsExactly(5L, 6L, 7L, 8L, 9L, 10L);
      assertThat(core.bean(IntegrityAlerts.class).has("audit", "chain")).isFalse();
    }
  }

  @Test
  void aPositionMovedOutsideTheCoreSendsNothingHoldsRetentionAndASaveStartsOver() throws Exception {
    try (FakeCloud cloud = new FakeCloud();
        TestCore core = core("export-position", cloud, "--gateway.audit.export.batch.s3=4")) {
      // Three calls of 400 days ago (rows 1 to 6), then two of today (rows 7 to 10).
      Instant old = Instant.now().minus(Duration.ofDays(400));
      List<AuditBackfill.PastCall> past = new ArrayList<>();
      for (int i = 0; i < 3; i++) {
        AuditWriter.Call call = call("sql_query");
        past.add(new AuditBackfill.PastCall(call, List.of(
            AuditBackfill.PastRow.received(old.plusSeconds(i), AuditWriter.Received.of(AuditWriter.Verdict.ALLOWED,
                Classification.Kind.READ, null)),
            AuditBackfill.PastRow.completed(old.plusSeconds(i).plusMillis(40), completion()))));
      }
      core.bean(AuditBackfill.class).write(past);
      List<Long> today = calls(core, 2, "sql_query");
      core.bean(SettingStore.class).put(AuditRetentionJob.SETTING, JsonNodeFactory.instance.objectNode()
          .put("retentionYears", 1), null);
      AuditExports exports = core.bean(AuditExports.class);
      String id = exports.create(s3(cloud, "jsonl"), null).id();
      // The first batch (rows 1 to 4) arrives; the core stops before the cursor moves past the second.
      AtomicInteger delivered = new AtomicInteger();
      core.bean(ExportRunner.class).afterDelivery = b -> {
        if (delivered.incrementAndGet() == 2) {
          throw new IllegalStateException("The core stopped here");
        }
      };
      assertThat(exports.run(id).lastExportedId()).isEqualTo(4);
      core.bean(ExportRunner.class).afterDelivery = b -> {};
      assertThat(exports.get(id).verified()).as("the core's own moves keep the row signed").isTrue();

      // An agent's shell (the same OS user) moves the position past everything, in gateway.db.
      core.jdbc().update("update audit_export set last_exported_id = 1000000, pending_to_id = null where id = ?", id);

      AuditExports.Destination moved = exports.get(id);
      assertThat(moved.verified()).isFalse();
      assertThat(moved.state()).isEqualTo("failing");
      assertThat(moved.lastError()).contains("Changed outside MCP Gateway");
      assertThat(core.bean(IntegrityAlerts.class).has("audit-export", id)).isTrue();
      assertThatThrownBy(() -> exports.run(id)).isInstanceOf(ProblemException.class)
          .hasMessageContaining("changed outside");
      AuditRetentionJob.Run run = core.bean(AuditRetentionJob.class).run();
      assertThat(run.toolCalls()).as("a position nobody vouches for holds everything").isZero();
      assertThat(core.jdbc().queryForObject("select count(*) from tool_call", Integer.class)).isEqualTo(10);

      // Saved again: nothing says what the destination got, so it gets everything again from the first row.
      int putsBefore = cloud.puts();
      AuditExports.Destination saved = exports.update(id, new AuditExports.Patch(null, null, null, null, null, null),
          "checked after the alert");
      assertThat(saved.verified()).isTrue();
      assertThat(saved.lastExportedId()).isNull();
      assertThat(saved.pending()).isFalse();
      assertThat(core.bean(IntegrityAlerts.class).has("audit-export", id)).isFalse();
      assertThat(core.bean(AuditQueries.class).changes("Audit export", null, 5)).extracting(AuditQueries.Change::to)
          .anySatisfy(to -> assertThat(to).contains("export starts again from the first row"));
      AuditExports.Destination again = exports.run(id);
      assertThat(again.state()).isEqualTo("ok");
      assertThat(again.lastExportedId()).isEqualTo(today.getLast());
      assertThat(cloud.puts() - putsBefore).as("rows 1 to 10 in batches of four").isEqualTo(3);

      // The batch in flight is the position too.
      core.jdbc().update("update audit_export set pending_to_id = 1000000 where id = ?", id);
      assertThat(exports.get(id).verified()).isFalse();
    }
  }

  @Test
  void activitySendsTheSelectedCallsOnceWithTheChainsHead() throws Exception {
    try (FakeSyslog siem = new FakeSyslog(false); FakeCloud cloud = new FakeCloud();
        TestCore core = core("export-activity", cloud)) {
      calls(core, 3, "sql_query");
      List<Long> executes = calls(core, 2, "sql_execute");
      AuditExports exports = core.bean(AuditExports.class);
      String id = exports.create(siem(siem, "daily@03:00"), null).id();

      AuditExports.Sent sent = exports.send(id, "tool:sql_execute", null, null);

      assertThat(sent.rows()).isEqualTo(2);
      assertThat(sent.target()).isEqualTo("127.0.0.1:" + siem.port());
      assertThat(sent.format()).isEqualTo("jsonl");
      SiemSenderTest.await(() -> siem.messages().size() == 3, "two calls and the head");
      // The latest row of each selected call (its completed row).
      assertThat(siem.json("tool-call")).extracting(r -> r.path("id").asLong()).containsExactly(executes.get(1),
          executes.get(3));
      JsonNode head = siem.json("audit-head").getFirst();
      assertThat(head.path("rows").asInt()).isEqualTo(2);
      assertThat(head.path("from").isNull()).isTrue();
      assertThat(ExportRecords.verify(core.bean(HmacKeys.class), head)).isTrue();
      assertThat(exports.get(id).lastExportedId()).as("its own cursor didn't move").isNull();
      assertThatThrownBy(() -> exports.send(id, "nonsense:1", null, null)).isInstanceOf(ProblemException.class);
    }
  }

  @Test
  void secretsAreWriteOnlyChecksRefuseWhatCantWorkAndEveryChangeIsASetupChange() throws Exception {
    try (FakeCloud cloud = new FakeCloud(); TestCore core = core("export-settings", cloud)) {
      AuditExports exports = core.bean(AuditExports.class);
      AuditExports.Destination d = exports.create(s3(cloud, "jsonl"), "SOC wants a copy");
      assertThat(d.secrets()).extracting(s -> s.field() + " " + s.source() + " v" + s.version() + " " + s.state())
          .containsExactly("/credentials/secretAccessKey local v1 stored");
      assertThat(JSON.writeValueAsString(exports.list())).doesNotContain(SECRET_KEY);
      assertThat(d.config().path("credentials").has("secretAccessKey")).isFalse();
      assertThat(d.verified()).isTrue();

      AuditExports.Destination replaced = exports.update(d.id(), new AuditExports.Patch(null, null, null, "hourly",
          secret(), d.version()), "rotated");
      assertThat(replaced.secrets().getFirst().version()).isEqualTo(2);
      assertThat(replaced.schedule()).isEqualTo("hourly");
      assertThatThrownBy(() -> exports.update(d.id(), new AuditExports.Patch("x", null, null, null, null,
          d.version()), null)).isInstanceOf(ProblemException.class).hasMessageContaining("meantime");
      ObjectNode profile = d.config().deepCopy();
      profile.putObject("credentials").put("source", "profile").put("profile", "acme-audit");
      AuditExports.Destination noKey = exports.update(d.id(), new AuditExports.Patch(null, false, profile, null, null,
          null), null);
      assertThat(noKey.secrets()).as("a profile needs no stored key").isEmpty();
      assertThat(noKey.enabled()).isFalse();

      assertThat(problem(() -> exports.create(new AuditExports.Draft("s3", "no-key", true, s3(cloud, "jsonl")
          .config(), "daily@02:00", null), null))).isEqualTo("/secrets/~1credentials~1secretAccessKey");
      assertThat(problem(() -> exports.create(new AuditExports.Draft("s3", "stream", true, s3(cloud, "jsonl")
          .config(), "stream", secret()), null))).isEqualTo("/schedule");
      ObjectNode metadata = (ObjectNode) s3(cloud, "jsonl").config().deepCopy();
      metadata.put("endpoint", "http://169.254.169.254");
      assertThat(problem(() -> exports.create(new AuditExports.Draft("s3", "imds", true, metadata, "hourly",
          secret()), null))).isEqualTo("/config/endpoint");
      ObjectNode plain = (ObjectNode) s3(cloud, "jsonl").config().deepCopy();
      plain.put("endpoint", "http://minio.acme.internal:9000");
      assertThat(problem(() -> exports.create(new AuditExports.Draft("s3", "plain", true, plain, "hourly",
          secret()), null))).isEqualTo("/config/endpoint");
      assertThat(problem(() -> exports.create(new AuditExports.Draft("s3", d.name(), true, s3(cloud, "jsonl")
          .config(), "hourly", secret()), null))).isEqualTo("/name");
      assertThat(problem(() -> exports.create(new AuditExports.Draft("syslog", "x", true, JSON.createObjectNode(),
          "hourly", null), null))).isEqualTo("/kind");

      exports.delete(d.id(), "moved to BigQuery");
      assertThat(exports.list()).isEmpty();
      List<AuditQueries.Change> changes = core.bean(AuditQueries.class).changes("Audit export", null, 20);
      assertThat(changes).extracting(c -> c.subject() + ": " + c.to()).contains(
          "Audit export audit-s3: Amazon S3 · s3://acme-audit/mcp-gateway/ · Daily at 02:00",
          "Audit export audit-s3: Hourly; new credentials", "Audit export audit-s3: Removed");
      assertThat(changes).extracting(AuditQueries.Change::reason).contains("SOC wants a copy", "rotated",
          "moved to BigQuery");
      assertThat(core.jdbc().queryForObject("select count(*) from secret where owner_kind = 'audit-export'",
          Integer.class)).isZero();
    }
  }

  @Test
  void aDestinationChangedOutsideTheCoreSendsNothingUntilItIsSavedAgain() throws Exception {
    try (FakeSyslog siem = new FakeSyslog(false); FakeCloud cloud = new FakeCloud();
        TestCore core = core("export-outside", cloud)) {
      calls(core, 2, "sql_query");
      AuditExports exports = core.bean(AuditExports.class);
      AuditExports.Destination d = exports.create(siem(siem, "hourly"), null);
      core.jdbc().update("update audit_export set config = ? where id = ?",
          "{\"host\":\"collector.evil.example\",\"port\":6514,\"facility\":13,\"appName\":\"x\"}", d.id());

      AuditExports.Destination changed = exports.get(d.id());
      assertThat(changed.verified()).isFalse();
      assertThat(changed.state()).isEqualTo("failing");
      assertThat(changed.lastError()).contains("Changed outside MCP Gateway");
      assertThatThrownBy(() -> exports.run(d.id())).isInstanceOf(ProblemException.class)
          .hasMessageContaining("changed outside");
      assertThatThrownBy(() -> exports.send(d.id(), null, null, null)).isInstanceOf(ProblemException.class);
      assertThat(core.bean(IntegrityAlerts.class).has("audit-export", d.id())).isTrue();
      assertThat(siem.connections()).isZero();

      // Saving it again (the person reviewed it) signs what they saved.
      AuditExports.Destination saved = exports.update(d.id(), new AuditExports.Patch(null, null,
          siem(siem, "hourly").config(), null, null, null), "checked");
      assertThat(saved.verified()).isTrue();
      assertThat(core.bean(IntegrityAlerts.class).has("audit-export", d.id())).isFalse();
      assertThat(exports.run(d.id()).state()).isEqualTo("ok");
    }
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

  /** The fakes' addresses; the scheduler only runs what the test runs, unless {@code extra} sets its tick. */
  static String[] args(FakeCloud cloud, String... extra) {
    List<String> args = new ArrayList<>(List.of("--gateway.cloud.gcp.token-uri=" + cloud.url() + "/token",
        "--gateway.cloud.endpoints=gcp.bigquery=" + cloud.url()));
    if (List.of(extra).stream().noneMatch(a -> a.startsWith("--gateway.audit.export.tick="))) {
      args.add("--gateway.audit.export.tick=1h");
    }
    args.addAll(List.of(extra));
    return args.toArray(String[]::new);
  }

  static TestCore core(String name, FakeCloud cloud, String... extra) {
    return TestCore.start(name, args(cloud, extra));
  }

  static Map<String, AuditExports.SecretInput> secret() {
    return Map.of(ExportSettings.S3_SECRET_KEY, new AuditExports.SecretInput("local", SECRET_KEY.toCharArray(),
        null, null));
  }

  static AuditExports.Draft s3(FakeCloud cloud, String format) {
    return new AuditExports.Draft("s3", "audit-s3", true, JSON.readTree("""
        {"bucket":"acme-audit","prefix":"mcp-gateway","region":"eu-central-1","endpoint":"%s","format":"%s",
         "credentials":{"source":"accessKey","accessKeyId":"%s"}}""".formatted(cloud.url(), format, ACCESS_KEY)),
        "daily@02:00", secret());
  }

  static AuditExports.Draft siem(FakeSyslog siem, String schedule) {
    ObjectNode config = JsonNodeFactory.instance.objectNode();
    config.put("host", "127.0.0.1");
    config.put("port", siem.port());
    config.put("caCertificate", siem.caPem());
    return new AuditExports.Draft("siem", "soc", true, config, schedule, null);
  }

  /** {@code n} calls of {@code tool} written as the call path writes them; the ids of their rows. */
  static List<Long> calls(TestCore core, int n, String tool) {
    AuditWriter writer = core.bean(AuditWriter.class);
    for (int i = 0; i < n; i++) {
      AuditWriter.Call call = call(tool);
      writer.received(call, AuditWriter.Received.of(AuditWriter.Verdict.ALLOWED, Classification.Kind.READ, null));
      writer.completed(call, completion());
    }
    return new ArrayList<>(core.jdbc().queryForList("select id from tool_call order by id desc limit ?", Long.class,
        n * 2).reversed());
  }

  private static AuditWriter.Call call(String tool) {
    ObjectNode args = JsonNodeFactory.instance.objectNode();
    args.put("sql", "SELECT id FROM orders LIMIT 5");
    args.put("connection", "dev-db");
    return new AuditWriter.Call(Ids.next("tc_"), "dev", tool, tool, "dev-db", Env.DEV, CODEX, "codex", null,
        "2025-11-25", HexFormat.of().formatHex(new byte[16]), "00f067aa0ba902b7", false, args,
        CanonicalJson.sha256Hex(args), null);
  }

  private static AuditWriter.Completion completion() {
    return new AuditWriter.Completion(AuditWriter.Verdict.ALLOWED, null, null, null, null, 41L, 3L, 38L, null, 7L,
        "0".repeat(64), "5 rows", 0, false, null, null, null, null, null);
  }

  private static List<JsonNode> lines(byte[] body) {
    List<JsonNode> out = new ArrayList<>();
    new String(body, StandardCharsets.UTF_8).lines().forEach(l -> out.add(JSON.readTree(l)));
    return out;
  }

  /** Every row links to the one before it in the batch. */
  private static void assertChained(List<JsonNode> rows) {
    for (int i = 1; i < rows.size(); i++) {
      assertThat(rows.get(i).path("prevSha256").asString()).isEqualTo(rows.get(i - 1).path("rowSha256").asString());
    }
  }

  /**
   * The request went out signed with SigV4 as S3 checks it: the signature recomputed here over what arrived — method,
   * URL, the signed headers' values, the body — with the access key matches the one sent.
   */
  private static void assertSignedLikeAws(FakeCloud.Request put) throws Exception {
    String amzDate = put.header("x-amz-date");
    Instant at = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).parse(amzDate,
        Instant::from);
    assertThat(put.header("x-amz-content-sha256")).isEqualTo(HexFormat.of().formatHex(MessageDigest
        .getInstance("SHA-256").digest(put.body())));
    String authorization = put.header("Authorization");
    assertThat(authorization).startsWith("AWS4-HMAC-SHA256 Credential=" + ACCESS_KEY + "/" + amzDate.substring(0, 8)
        + "/eu-central-1/s3/aws4_request");
    Map<String, String> signed = new LinkedHashMap<>();
    String names = authorization.replaceAll(".*SignedHeaders=([^,]+),.*", "$1");
    Set<String> skip = new LinkedHashSet<>(List.of("host", "x-amz-date", "x-amz-content-sha256"));
    for (String name : names.split(";")) {
      if (!skip.contains(name)) {
        signed.put(name, put.header(name));
      }
    }
    assertThat(names).contains("content-md5").contains("content-type");
    var again = S3Signing.sign(SdkHttpMethod.PUT, URI.create(put.url()), signed, put.body(),
        AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY), "eu-central-1", Clock.fixed(at, ZoneOffset.UTC));
    String expected = again.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase("Authorization"))
        .findFirst().orElseThrow().getValue().getFirst();
    assertThat(authorization).isEqualTo(expected);
  }

  private static String problem(Runnable call) {
    try {
      call.run();
    } catch (ProblemException e) {
      return e.field();
    }
    throw new AssertionError("no 422");
  }
}
