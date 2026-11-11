package ai.msrv.gaterook.connectors.azure;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * What a person sees before an Azure call runs: for {@code azure_call} the HTTP request exactly as it goes to
 * Resource Manager (method, URL with every parameter, the masked authorization, the JSON body), for the read tools
 * the Resource Graph query or the Azure Monitor request with absolute times. The approved call runs the stored
 * payload, not the arguments evaluated again later.
 */
final class Actions {

  private Actions() {}

  /** @param base the Resource Manager base URL of the connection's cloud (or its test endpoint) */
  static ActionPreview call(CallRequest r, Settings s, ConnectionConfig cfg, URI base) {
    StringBuilder text = new StringBuilder();
    String root = base.toString().endsWith("/") ? base.toString().substring(0, base.toString().length() - 1)
        : base.toString();
    StringBuilder q = new StringBuilder();
    for (Map.Entry<String, String> e : r.parameters().entrySet()) {
      q.append(q.isEmpty() ? '?' : '&').append(Arm.encode(e.getKey())).append('=').append(Arm.encode(e.getValue()));
    }
    text.append(r.method()).append(' ').append(root).append(r.encodedPath()).append(q).append('\n');
    text.append("Authorization: Bearer ‹masked›\n");
    if (r.body() != null) {
      text.append("Content-Type: application/json\n\n").append(ResourceGraph.pretty(r.body()));
    }
    List<ActionPreview.Field> fields = new ArrayList<>();
    fields.add(new ActionPreview.Field("Subscription", r.path().subscription()));
    if (r.path().resourceGroup() != null) {
      fields.add(new ActionPreview.Field("Resource group", r.path().resourceGroup()));
    }
    if (r.path().isResource()) {
      fields.add(new ActionPreview.Field("Resource", r.path().type() + " " + r.path().name()));
    }
    fields.add(new ActionPreview.Field("API version", r.apiVersion()));
    List<String> warnings = new ArrayList<>();
    if (r.method().equals("DELETE")) {
      warnings.add("DELETE removes the resource; Azure can't undo it.");
    }
    if (r.method().equals("PUT")) {
      warnings.add("PUT replaces the whole resource with the body: properties left out are reset.");
    }
    String summary = "azure_call on " + cfg.name() + ": " + r.method() + " " + r.rawPath();
    return ActionPreview.of(ActionPreview.Kind.HTTP, "http", text.toString(), cfg.name() + " · " + s.target(),
        cut(summary))
        .withFields(fields)
        .withWarnings(warnings)
        .withFacets(Map.of("provider", "azure", "tool", "azure_call", "method", r.method()))
        .withPayload(r.payload());
  }

  static ActionPreview describe(DescribeRequest r, Settings s, ConnectionConfig cfg) {
    List<String> lines = new ArrayList<>();
    lines.add("azure_describe · " + cfg.name() + " (Azure · " + s.target() + " · " + cfg.env().id() + ")");
    lines.add("POST /providers/Microsoft.ResourceGraph/resources?api-version=" + ResourceGraph.API_VERSION);
    lines.add("subscriptions: " + String.join(", ", r.subscriptions()));
    lines.add("query: " + r.effectiveQuery());
    lines.add("$top=" + r.limit() + " resultFormat=objectArray");
    if (r.cursor() != null) {
      lines.add("continues from the cursor of the previous call");
    }
    return preview("azure_describe", lines, cfg, s, r.get() ? r.id() : r.query(), r.payload(),
        List.of(new ActionPreview.Field("Subscriptions", String.join(", ", r.subscriptions())),
            new ActionPreview.Field("Limit", r.limit() + " rows")));
  }

  static ActionPreview monitor(MonitorRequest r, Settings s, ConnectionConfig cfg) {
    List<String> lines = new ArrayList<>();
    lines.add("azure_monitor · " + cfg.name() + " (Azure · " + r.subscription() + " · " + cfg.env().id() + ")");
    List<ActionPreview.Field> fields = new ArrayList<>();
    fields.add(new ActionPreview.Field("Subscription", r.subscription()));
    switch (r.action()) {
      case QUERY -> {
        lines.add("GET " + r.rawResource() + "/providers/Microsoft.Insights/metrics?api-version="
            + Monitor.METRICS_API);
        lines.add("metricnames: " + String.join(", ", r.metrics()) + " · aggregation " + String.join(", ",
            r.aggregations()) + " · interval " + r.interval());
        lines.add("timespan: " + r.from() + "/" + r.to());
        if (r.filter() != null) {
          lines.add("$filter: " + r.filter() + (r.top() > 0 ? " · top " + r.top() : ""));
        }
        fields.add(new ActionPreview.Field("Range", r.from() + " → " + r.to()));
      }
      case LIST_METRICS -> lines.add("GET " + r.rawResource() + "/providers/Microsoft.Insights/metricDefinitions"
          + "?api-version=" + Monitor.METRICS_API);
      case ALERTS -> {
        lines.add("GET /subscriptions/" + r.subscription() + "/providers/Microsoft.AlertsManagement/alerts"
            + "?api-version=" + Monitor.ALERTS_API);
        lines.add("monitorCondition=" + r.condition() + (r.timeRange() != null ? " timeRange=" + r.timeRange()
            : " customTimeRange=" + r.from() + "/" + r.to()) + (r.state() == null ? "" : " alertState=" + r.state())
            + (r.severity() == null ? "" : " severity=" + r.severity()));
      }
      case ACTIVITY_LOG -> {
        lines.add("GET /subscriptions/" + r.subscription() + "/providers/Microsoft.Insights/eventtypes/management/"
            + "values?api-version=" + Monitor.ACTIVITY_API);
        lines.add("eventTimestamp " + r.from() + " → " + r.to() + (r.resourceGroup() == null ? ""
            : " · resourceGroupName " + r.resourceGroup()) + (r.rawResource() == null ? ""
            : " · resourceUri " + r.rawResource()));
        fields.add(new ActionPreview.Field("Range", r.from() + " → " + r.to()));
      }
      case HEALTH -> lines.add("GET " + (r.rawResource() != null ? r.rawResource()
          + "/providers/Microsoft.ResourceHealth/availabilityStatuses/current" : "/subscriptions/" + r.subscription()
          + "/providers/Microsoft.ResourceHealth/availabilityStatuses") + "?api-version=" + Monitor.HEALTH_API);
    }
    lines.add("items: up to " + r.limit());
    if (r.cursor() != null) {
      lines.add("continues from the cursor of the previous call");
    }
    return preview("azure_monitor", lines, cfg, s, r.action().id + (r.rawResource() == null ? ""
        : " " + r.rawResource()), r.payload(), fields);
  }

  private static ActionPreview preview(String tool, List<String> lines, ConnectionConfig cfg, Settings s, String what,
      JsonNode payload, List<ActionPreview.Field> fields) {
    return ActionPreview.of(ActionPreview.Kind.TEXT, "text", String.join("\n", lines), cfg.name() + " · "
        + s.target(), cut(tool + " on " + cfg.name() + ": " + what))
        .withFields(fields)
        .withFacets(Map.of("provider", "azure", "tool", tool))
        .withPayload(payload);
  }

  private static String cut(String summary) {
    return summary.length() > 120 ? summary.substring(0, 119) + "…" : summary;
  }
}
