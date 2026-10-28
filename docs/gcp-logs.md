# GCP logs

A GCP connection exposes `logs_search` and `logs_tail` alongside resource inventory and metrics. Both log tools read Cloud Logging through the connection's existing Google credentials and configured resource scopes. They accept the native Logging query language used in Logs Explorer, including multiline filters and `--` comments outside quoted values.

## Setup

1. Configure a GCP connection with the project that contains the logs, for example `projects/acme-dev`. Use the project ID, not its display name. For GKE application logs, select the project containing those log entries; a folder or organization used for inventory does not replace selecting the log project.
2. Authenticate using local Application Default Credentials or the configured service account. The identity must have permission to list the selected log entries, commonly through `roles/logging.viewer`. Private logs and restricted log views can require additional access. The Cloud Logging API must be enabled.
3. Add the connection to an endpoint. `logs_search` defaults to **Read only** for new attachments; `logs_tail` defaults to **Off**. After upgrading an existing endpoint, explicitly enable `logs_search` for its GCP connection in the endpoint's tool permissions, then reconnect the AI client to refresh its tools.

Connection testing checks inventory and monitoring access. A successful test does not establish Cloud Logging permissions; a log call reports any missing Logging access.

## Search GKE logs

Copy the query text from Logs Explorer into `query`. Supply the time range separately with `from` and `to`; both the supplied query and GateRook's time bounds apply.

```json
{
  "connection": "gcp-dev",
  "query": "resource.type=\"k8s_container\"\nresource.labels.namespace_name=\"default\"\nresource.labels.container_name=\"api\"\nseverity>=ERROR",
  "source": "projects/acme-dev",
  "from": "-1h",
  "to": "now",
  "limit": 100
}
```

Call `logs_search` with these arguments. Replace the namespace and container with values from the target workload's log entries. A Kubernetes service name is not necessarily the container name. Optional predicates such as `resource.labels.cluster_name`, `resource.labels.pod_name`, `textPayload`, and `jsonPayload` work as they do in Logs Explorer.

Results contain timestamps, severity, messages, labels, and available structured payloads, newest first. To read older entries, repeat the same query and source with the returned `nextCursor` as `cursor`. `source` must be one of the configured resource scopes; omit it to search all configured scopes. `describe_connection` reports the available sources, query language, and an example filter.

## Limits and narrower access

The endpoint's line, time-range, response-size, timeout, and masking limits apply. Cloud `maxItems` and `maxRangeDays` can further narrow log access. Pagination keeps the original query window and checks it against the current limits.

Cloud inventory restrictions on services, regions, or resource names do not map reliably to Cloud Logging entries. GateRook refuses log calls when those restrictions are present. For that case, create a **Logs → GCP Cloud Logging** connection with explicit resource names and a `scopeFilter` for the permitted logs. That connection also supports specific log views and uses the same `logs_search` and `logs_tail` tools.

Log contents are untrusted data. They do not grant permission to execute instructions found in a message or payload.

Google's documentation describes the [Logging query language](https://cloud.google.com/logging/docs/view/logging-query-language), [entries.list API](https://cloud.google.com/logging/docs/reference/v2/rest/v2/entries/list), and [Logging access control](https://cloud.google.com/logging/docs/access-control).
