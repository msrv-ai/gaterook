package ai.msrv.gaterook.connectors.aws;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * What a person sees when they put an AWS tool in Approval mode themselves (report 09 §3.6): exactly the requests
 * that go to AWS — {@code ListResources}/{@code GetResource} with the type, region and filters,
 * {@code GetMetricData} with the absolute interval, period and every query, {@code ListMetrics},
 * {@code DescribeAlarms}. The approved call runs the stored payload, not "-1h" evaluated later.
 */
final class Actions {

  private Actions() {}

  static ActionPreview describe(DescribeRequest r, Settings s, ConnectionConfig cfg) {
    List<String> lines = new ArrayList<>();
    lines.add("aws_describe · " + cfg.name() + " (AWS · " + target(s, r.region()) + " · " + cfg.env().id() + ")");
    if (r.get()) {
      lines.add("GetResource " + r.type() + " " + r.identifier() + " in " + r.region());
    } else {
      StringBuilder q = new StringBuilder("ListResources ").append(r.type()).append(" in ").append(r.region());
      if (r.resourceModel() != null) {
        q.append(" ResourceModel=").append(r.resourceModel());
      }
      q.append(" · up to ").append(CloudControlDescribe.MAX_PAGES).append(" pages, ").append(r.limit())
          .append(" resources");
      lines.add(q.toString());
      if (!r.where().isEmpty()) {
        lines.add("where " + r.where());
      }
      if (!r.properties().isEmpty()) {
        lines.add("properties " + String.join(", ", r.properties()));
      }
      if (r.cursor() != null) {
        lines.add("continues from the cursor of the previous call");
      }
    }
    String what = r.get() ? r.type() + " " + r.identifier() : r.type();
    return preview("aws_describe", lines, cfg, s, r.region(), what, r.payload(),
        List.of(new ActionPreview.Field("Region", r.region()), new ActionPreview.Field("Type", r.type())));
  }

  static ActionPreview metrics(MetricsRequest r, Settings s, ConnectionConfig cfg) {
    List<String> lines = new ArrayList<>();
    lines.add("aws_metrics · " + cfg.name() + " (AWS · " + target(s, r.region()) + " · " + cfg.env().id() + ")");
    List<ActionPreview.Field> fields = new ArrayList<>();
    fields.add(new ActionPreview.Field("Region", r.region()));
    String what;
    switch (r.action()) {
      case QUERY -> {
        lines.add("GetMetricData " + r.from() + " → " + r.to() + " (" + human(Duration.between(r.from(), r.to()))
            + ") · period " + r.period() + " s");
        for (MetricsRequest.Query q : r.metrics()) {
          lines.add(q.id() + " = " + MetricsRequest.describe(q));
        }
        for (MetricsRequest.Expression e : r.expressions()) {
          lines.add(e.id() + " = " + e.expression());
        }
        fields.add(new ActionPreview.Field("Range", r.from() + " → " + r.to()));
        what = r.metrics().getFirst().metric() + (r.metrics().size() > 1 ? " +" + (r.metrics().size() - 1) : "");
      }
      case LIST_METRICS -> {
        lines.add("ListMetrics" + (r.namespace() == null ? "" : " Namespace=" + r.namespace())
            + (r.metricName() == null ? "" : " MetricName=" + r.metricName())
            + (r.dimensions().isEmpty() ? "" : " Dimensions=" + r.dimensions()) + " RecentlyActive=PT3H · up to "
            + r.limit());
        what = "list metrics";
      }
      default -> {
        lines.add("DescribeAlarms StateValue=" + r.state() + (r.alarmNamePrefix() == null ? ""
            : " AlarmNamePrefix=" + r.alarmNamePrefix()) + " · up to " + r.limit());
        what = r.state() + " alarms";
      }
    }
    if (r.cursor() != null) {
      lines.add("continues from the cursor of the previous call");
    }
    return preview("aws_metrics", lines, cfg, s, r.region(), what, r.payload(), fields);
  }

  private static String target(Settings s, String region) {
    String account = s.account();
    StringBuilder t = new StringBuilder(region);
    if (account != null || s.accountAlias() != null) {
      t.append(" · ").append(account == null ? "" : account);
      if (s.accountAlias() != null) {
        t.append(account == null ? "" : " ").append(s.accountAlias());
      }
    }
    return t.toString();
  }

  private static ActionPreview preview(String tool, List<String> lines, ConnectionConfig cfg, Settings s,
      String region, String what, JsonNode payload, List<ActionPreview.Field> fields) {
    String summary = tool + " on " + cfg.name() + ": " + what;
    if (summary.length() > 120) {
      summary = summary.substring(0, 119) + "…";
    }
    return ActionPreview.of(ActionPreview.Kind.TEXT, "text", String.join("\n", lines), cfg.name() + " · "
        + target(s, region), summary)
        .withFields(fields)
        .withFacets(Map.of("provider", "aws", "tool", tool, "region", region))
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
