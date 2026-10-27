package ai.msrv.gaterook.connectors.logs;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * What a person sees when they put a Logs tool in Approval mode themselves (report 07 §3.6): the query exactly as it
 * goes to the backend — for Logs Insights with what the gateway adds, for Cloud Logging with the scope filter and the
 * time range — and the range in absolute time. The approved call runs with these values, not with "-1h" evaluated
 * later.
 */
final class Actions {

  private Actions() {}

  static ActionPreview preview(LogsRequest r, Settings s, ConnectionConfig cfg) {
    List<String> lines = new ArrayList<>();
    lines.add(r.tool().id + " · " + cfg.name() + " (" + s.title() + " · " + s.target() + " · " + cfg.env().id() + ")");
    long from = Search.nanos(r.from());
    switch (s) {
      case Settings.Loki l -> lines.add("query: " + r.query());
      case Settings.Gcp g -> {
        lines.add("resources: " + (r.source() != null ? r.source() : String.join(", ", g.resourceNames())));
        long to = r.to() == null ? from : Search.nanos(r.to());
        lines.add("filter: " + (r.tool() == LogsRequest.Tool.SEARCH ? GcpLoggingBackend.filter(g, r.query(), from, to)
            : GcpLoggingBackend.filter(g, r.query(), from, from).replaceFirst("\nAND timestamp<=\"[^\"]*\"$", "")));
      }
      case Settings.CloudWatch c -> {
        if (r.tool() == LogsRequest.Tool.SEARCH && c.insights()) {
          lines.add("log groups: " + (r.source() != null ? r.source() : String.join(", ", c.logGroups())));
          lines.add("query: " + CloudWatchBackend.insightsQuery(r.query()));
        } else {
          lines.add("log group: " + (r.source() != null ? r.source() : c.logGroups().get(0)));
          lines.add("filter pattern: " + (r.query().isBlank() ? "(every event)" : r.query()));
        }
      }
      case Settings.OpenSearch o -> {
        lines.add("indices: " + (r.source() != null ? r.source() : String.join(",", o.indices())));
        lines.add("query_string: " + (r.query().isBlank() ? "(every document)" : r.query()));
        if (o.scopeQuery() != null) {
          lines.add("always: " + o.scopeQuery());
        }
      }
    }
    if (r.tool() == LogsRequest.Tool.SEARCH) {
      lines.add("range: " + r.from() + " → " + r.to() + " (" + human(Duration.between(r.from(), r.to())) + ") · limit "
          + r.limit());
    } else {
      lines.add("since: " + r.from() + " · waits up to " + r.waitFor().toSeconds() + " s · limit " + r.limit());
    }
    if (r.cursor() != null) {
      lines.add("continues from the cursor of the previous call");
    }
    String text = String.join("\n", lines);
    String query = r.query().isBlank() ? "every entry" : r.query();
    String summary = r.tool().id + " on " + cfg.name() + ": " + query;
    if (summary.length() > 120) {
      summary = summary.substring(0, 119) + "…";
    }
    List<ActionPreview.Field> fields = new ArrayList<>();
    fields.add(new ActionPreview.Field("Backend", s.title()));
    fields.add(new ActionPreview.Field("Where", s.target()));
    if (r.tool() == LogsRequest.Tool.SEARCH) {
      fields.add(new ActionPreview.Field("Range", r.from() + " → " + r.to()));
    }
    fields.add(new ActionPreview.Field("Limit", r.limit() + " entries"));
    return ActionPreview.of(ActionPreview.Kind.TEXT, "text", text, cfg.name() + " · " + s.target(), summary)
        .withFields(fields)
        .withPayload(r.payload());
  }

  static String human(Duration d) {
    long minutes = d.toMinutes();
    if (minutes < 60) {
      return Math.max(1, minutes) + " min";
    }
    long hours = d.toHours();
    return hours < 48 ? hours + " h" : d.toDays() + " days";
  }
}
