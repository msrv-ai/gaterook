package ai.msrv.gaterook.connectors.prometheus;

import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolDefinition.Annotations;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.connector.api.ToolDefinition.Nature;
import ai.msrv.gaterook.connector.api.ToolDefinition.ResultShape;
import ai.msrv.gaterook.connectors.support.SchemaResources;
import java.util.List;

/** The tools of the Prometheus connector (docs/prometheus.md): all of them read, on every environment. */
final class PrometheusTools {

  static final String QUERY = "prometheus_query";
  static final String QUERY_RANGE = "prometheus_query_range";
  static final String SERIES = "prometheus_series";
  static final String LABELS = "prometheus_labels";
  static final String LABEL_VALUES = "prometheus_label_values";
  static final String METADATA = "prometheus_metadata";
  static final String TARGETS = "prometheus_targets";
  static final String RULES = "prometheus_rules";
  static final String ALERTS = "prometheus_alerts";

  private PrometheusTools() {}

  static List<ToolDefinition> definitions(SchemaResources schemas) {
    return List.of(
        read(schemas, QUERY, "Run an instant PromQL query",
            "Evaluates one PromQL expression at one time (default now): an instant vector, a range vector, a scalar or "
                + "a string. Values stay as the server wrote them (NaN, +Inf), times are ISO 8601 in UTC."),
        read(schemas, QUERY_RANGE, "Run a PromQL range query",
            "Evaluates one PromQL expression at every step from start to end (default the last hour, about 120 "
                + "points per series). Each series has its labels and [time, value] points, oldest first."),
        read(schemas, SERIES, "Find series",
            "Label sets of the series that match series selectors (match[]) within a time window (default the last "
                + "hour)."),
        read(schemas, LABELS, "List label names",
            "Label names of the series within a time window (default the last hour), optionally only of series "
                + "matching selectors."),
        read(schemas, LABEL_VALUES, "List label values",
            "Values of one label within a time window (default the last hour); __name__ lists metric names."),
        read(schemas, METADATA, "Describe metrics",
            "Type (counter, gauge, histogram, summary), help text and unit of metrics, as their targets expose them."),
        read(schemas, TARGETS, "List scrape targets",
            "Targets the server scrapes, with their health, last scrape and last error; or the discovered targets "
                + "relabeling dropped."),
        read(schemas, RULES, "List alerting and recording rules",
            "Rule groups with each rule's PromQL expression, health, last error and, for alerting rules, its state."),
        read(schemas, ALERTS, "List active alerts",
            "Pending and firing alerts with their labels, annotations, value and since when they are active."));
  }

  private static ToolDefinition read(SchemaResources schemas, String name, String title, String description) {
    return ToolDefinition.builder(name)
        .title(title)
        .description(description)
        .inputSchema(schemas.load(name + ".input"))
        .annotations(Annotations.read())
        .nature(Nature.READ)
        .defaultMode(Mode.READ_ONLY, Mode.READ_ONLY)
        .resultShape(ResultShape.JSON)
        .build();
  }
}
