package ai.msrv.gaterook.connectors.cloudflare;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code cloudflare_logpush_jobs}: the Logpush jobs of the account or of a zone ({@code GET …/logpush/jobs}), or one
 * job. Each job's {@code destination_conf} keeps its scheme, bucket or host and harmless options; credentials in it
 * are masked ({@link Redactor#destination}).
 */
final class LogpushRead {

  static final Set<String> ARGS = Set.of("connection", "zone", "job");
  private static final String[] JOB = {"id", "name", "dataset", "enabled", "kind", "frequency", "destination_conf",
      "filter", "output_options", "logpull_options", "last_complete", "last_error", "error_message",
      "max_upload_bytes", "max_upload_records", "max_upload_interval_seconds"};

  private final Zones.Ref zone;
  private final Integer job;

  private LogpushRead(Zones.Ref zone, Integer job) {
    this.zone = zone;
    this.job = job;
  }

  static LogpushRead parse(ObjectNode args, CloudflareSettings s, String connection, JsonNode metadata)
      throws Refusal {
    Args.only(args, CloudflareTools.LOGPUSH, ARGS);
    String z = Args.string(args, "zone", 253);
    Zones.Ref ref = z == null ? null : Zones.Ref.parse(z);
    if (ref != null) {
      Zones.allowed(ref, s, connection, metadata);
    }
    return new LogpushRead(ref, Args.integer(args, "job", 1, Integer.MAX_VALUE));
  }

  ToolResult run(Call c) throws ConnectorException {
    CloudflareApi api = c.api();
    Zones.Zone z = zone == null ? null : c.zones().resolve(c, zone);
    List<String> base = z == null ? List.of("accounts", c.settings().accountId()) : List.of("zones", z.id());
    List<String> path = new ArrayList<>(base);
    path.add("logpush");
    path.add("jobs");
    if (job != null) {
      path.add(String.valueOf(job));
    }
    JsonNode r = api.get(c, api.uri(path, null), "Logpush").result();
    ObjectNode s = Answers.envelope(c, job == null ? "jobs" : "job");
    if (z != null) {
      s.set("zone", z.node());
    }
    if (job == null) {
      ArrayNode items = Answers.F.arrayNode();
      for (JsonNode j : r) {
        items.add(Answers.pick(j, JOB));
      }
      s.set("items", items);
    } else {
      s.set("item", Answers.pick(r, JOB));
    }
    List<String> notes = List.of("A job whose last_error is later than last_complete is failing; error_message says "
        + "why.");
    return Answers.answer(c, "Logpush jobs · " + (z == null ? "account" : z.name()) + " · " + c.cfg().name(), s,
        notes);
  }
}
