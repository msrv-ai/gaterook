# GCP logs

The GCP Cloud Logging connector exposes `gcp_logging_search` and `gcp_logging_tail`. Both tools read Cloud Logging through the connection's Google credentials and selected projects or log views. They accept the native Logging query language used in Logs Explorer, including multiline filters and `--` comments outside quoted values.

The general GCP connector also offers logs alongside resource inventory and metrics through its existing `logs_search` and `logs_tail` tools. Saved legacy `logs` connections retain those tool names and their endpoint modes. New dedicated log connections use the GCP Cloud Logging connector and its own tools.

## Setup

1. Choose **GCP Cloud Logging** and configure its Google credentials, using local Application Default Credentials or a service account. The identity must have permission to list the selected log entries, commonly through `roles/logging.viewer`. Private logs and restricted log views can require additional access. The Cloud Logging API must be enabled.
2. After credentials, load the project list and select the project containing the logs. You can also enter its project ID, such as `acme-dev`, which is saved as `projects/acme-dev`, or enter a full project or log-view resource name. Use the project ID, not its display name. Project discovery uses Cloud Resource Manager access; if discovery is unavailable, manual entry remains available. For GKE application logs, select the project containing those log entries.
3. Add the connection to an endpoint. `gcp_logging_search` defaults to **Read only** for new attachments; `gcp_logging_tail` defaults to **Off**. Reconnect the AI client after changing endpoint tool permissions to refresh its tools.

The dedicated connector tests Cloud Logging access. The general GCP connector's test checks inventory and monitoring access; its successful test does not establish Cloud Logging permissions. Newly introduced tools on existing endpoint attachments require explicit enablement.

## Search GKE logs

Copy the query text from Logs Explorer into `query`. Supply the time range separately with `from` and `to`; both the supplied query and GateRook's time bounds apply.

```json
{
  "connection": "gcp-logs",
  "query": "resource.type=\"k8s_container\"\nresource.labels.namespace_name=\"default\"\nresource.labels.container_name=\"api\"\nseverity>=ERROR",
  "source": "projects/acme-dev",
  "from": "-1h",
  "to": "now",
  "limit": 100
}
```

Call `gcp_logging_search` with these arguments. Replace the namespace and container with values from the target workload's log entries. A Kubernetes service name is not necessarily the container name. Optional predicates such as `resource.labels.cluster_name`, `resource.labels.pod_name`, `textPayload`, and `jsonPayload` work as they do in Logs Explorer.

Results contain timestamps, severity, messages, labels, and available structured payloads, newest first. To read older entries, repeat the same query and source with the returned `nextCursor` as `cursor`. `source` must be one of the configured resource scopes; omit it to search all configured scopes. `describe_connection` reports the available sources, query language, and an example filter.

## Limits and narrower access

The endpoint's line, time-range, response-size, timeout, and masking limits apply. Pagination keeps the original query window and checks it against the current limits. The dedicated connector's `scopeFilter` further restricts entries within the selected projects or log views.

For the general GCP connector, Cloud `maxItems` and `maxRangeDays` can further narrow log access. Cloud inventory restrictions on services, regions, or resource names do not map reliably to Cloud Logging entries, so GateRook refuses log calls when those restrictions are present. Use a dedicated **GCP Cloud Logging** connection with explicit projects or log views and a `scopeFilter` for the permitted logs.

Log contents are untrusted data. They do not grant permission to execute instructions found in a message or payload.

Google's documentation describes the [Logging query language](https://cloud.google.com/logging/docs/view/logging-query-language), [entries.list API](https://cloud.google.com/logging/docs/reference/v2/rest/v2/entries/list), and [Logging access control](https://cloud.google.com/logging/docs/access-control).
