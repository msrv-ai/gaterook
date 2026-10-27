package ai.msrv.gaterook.connectors.gcp;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What a person sees when they put a GCP tool in Approval mode themselves (report 09 §3.6): the requests exactly as
 * they go to Google — the search with its scope, types, query and page size, the live read of one resource, the time
 * series query with its filter, interval and aggregation — with times in absolute form. The approved call runs the
 * stored payload, not "-1h" evaluated later.
 */
final class Actions {

  private Actions() {}

  /** @param testBase the test endpoint of a live route by key ({@code null}: the real service) */
  static ActionPreview describe(DescribeRequest r, Settings s, ConnectionConfig cfg,
      java.util.function.Function<String, java.net.URI> testBase) {
    List<String> lines = new ArrayList<>();
    lines.add("gcp_describe · " + cfg.name() + " (GCP · " + s.target() + " · " + cfg.env().id() + ")");
    if (r.get()) {
      List<String> scopes = r.scope() != null ? List.of(r.scope()) : AssetDescribe.candidateScopes(r.name(), s);
      String last = r.name().substring(r.name().lastIndexOf('/') + 1);
      lines.add("searchAllResources " + String.join(", ", scopes) + " query=name:\"" + last + "\" (finds "
          + r.name() + ")");
      var live = r.live() ? LiveRoutes.of(r.name(), testBase) : java.util.Optional.<LiveRoutes.Target>empty();
      if (live.isPresent()) {
        lines.add("GET " + live.get().uri());
      } else {
        lines.add("no live read: the inventory's snapshot of the resource");
      }
    } else {
      StringBuilder q = new StringBuilder("searchAllResources ").append(r.scope());
      if (!r.assetTypes().isEmpty()) {
        q.append(" assetTypes=[").append(String.join(", ", r.assetTypes())).append(']');
      }
      if (r.query() != null) {
        q.append(" query=\"").append(r.query()).append('"');
      }
      if (r.orderBy() != null) {
        q.append(" orderBy=\"").append(r.orderBy()).append('"');
      }
      q.append(" pageSize=").append(r.limit());
      lines.add(q.toString());
      if (r.cursor() != null) {
        lines.add("continues from the cursor of the previous call");
      }
    }
    String what = r.get() ? r.name() : r.query() != null ? r.query() : "every resource";
    return preview("gcp_describe", lines, cfg, s, what, r.payload(),
        List.of(new ActionPreview.Field("Scope", r.scope() != null ? r.scope() : String.join(", ", s.scopes())),
            new ActionPreview.Field("Limit", r.limit() + " resources")));
  }

  static ActionPreview metrics(MetricsRequest r, Settings s, ConnectionConfig cfg) {
    List<String> lines = new ArrayList<>();
    lines.add("gcp_metrics · " + cfg.name() + " (GCP · " + r.project() + " · " + cfg.env().id() + ")");
    List<ActionPreview.Field> fields = new ArrayList<>();
    fields.add(new ActionPreview.Field("Project", r.project()));
    switch (r.action()) {
      case QUERY -> {
        lines.add("timeSeries.list projects/" + r.project());
        lines.add("filter: " + r.monitoringFilter());
        lines.add("interval: " + r.from() + " → " + r.to() + " (" + human(Duration.between(r.from(), r.to()))
            + ") · period " + r.period() + " s");
        lines.add("aligner: " + (r.aligner() != null ? r.aligner()
            : "by the metric's kind (ALIGN_MEAN for gauges, ALIGN_RATE for counters)") + " · reducer " + r.reducer()
            + (r.groupBy().isEmpty() ? "" : " by " + String.join(", ", r.groupBy())));
        lines.add("series: up to " + r.limit());
        fields.add(new ActionPreview.Field("Range", r.from() + " → " + r.to()));
      }
      case LIST_METRICS -> lines.add("metricDescriptors.list projects/" + r.project() + (r.prefix() == null ? ""
          : " filter=metric.type = starts_with(\"" + r.prefix() + "\")") + " activeOnly=true pageSize=" + r.limit());
      case ALERTS -> lines.add("alerts.list projects/" + r.project() + " state=" + r.state() + " pageSize="
          + r.limit());
    }
    if (r.cursor() != null) {
      lines.add("continues from the cursor of the previous call");
    }
    String what = r.action() == MetricsRequest.Action.QUERY ? r.metric() : r.action().id;
    return preview("gcp_metrics", lines, cfg, s, what, r.payload(), fields);
  }

  private static ActionPreview preview(String tool, List<String> lines, ConnectionConfig cfg, Settings s, String what,
      tools.jackson.databind.JsonNode payload, List<ActionPreview.Field> fields) {
    String summary = tool + " on " + cfg.name() + ": " + what;
    if (summary.length() > 120) {
      summary = summary.substring(0, 119) + "…";
    }
    return ActionPreview.of(ActionPreview.Kind.TEXT, "text", String.join("\n", lines), cfg.name() + " · "
        + s.target(), summary)
        .withFields(fields)
        .withFacets(Map.of("provider", "gcp", "tool", tool))
        .withPayload(payload);
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
