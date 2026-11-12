# Control AI access to Prometheus

GateRook's MCP Firewall lets AI apps query Prometheus, and servers with its HTTP API such as VictoriaMetrics, Thanos Query, Grafana Mimir and Cortex, through read-only tools with bounded answers. The [security model](security.md) explains execution controls, explicit Bypass, and the append-only, tamper-evident audit trail.

## Connect a server

The built-in **Prometheus** connector (`prometheus`) calls the server's HTTP API with `GET` requests to fixed read paths under `/api/v1`, with every parameter encoded: there is no Prometheus client library and no request body. Set:

- the **server URL**, with any path the API sits under: `https://prometheus.example.com`, `https://mimir.example.com/prometheus` for Mimir, Grafana Cloud's URL ending in `/api/prom`, or `https://vmselect.example.com/select/0/prometheus` for a VictoriaMetrics cluster. GateRook adds `/api/v1/…` itself. Use `https://`; plain `http://` reaches only this computer (`localhost`, `127.0.0.1`), for example through `kubectl port-forward`. Redirects are never followed;
- the **credentials**: none, a username and password (basic authentication; for Grafana Cloud, the instance ID and an access policy token with `metrics:read`), or a bearer token, stored encrypted in GateRook;
- the **tenant** of Grafana Mimir or Cortex, sent as `X-Scope-OrgID`, several joined by `|`. Agents can't name another;
- a **CA certificate** for a server whose certificate a private CA signed. GateRook then trusts this CA as well as the system's; an AI app's connection import may propose one, shown on its card for you to check.

**Test connection** reads the server's build information (`/api/v1/status/buildinfo`; a server that reports none still passes), evaluates `vector(1)`, and counts the `up` series to show whether anything is scraped into the server. It warns, outside dev, when a server other than this computer answers without credentials.

While you fill in the form, GateRook offers `http://localhost:9090` when a server answers there, and once the URL is in, it asks the server as Test connection does: it names the server (Prometheus, Grafana Mimir), fills in the sign-in a server asks for in its `WWW-Authenticate` answer, says when Mimir or Cortex needs a tenant, and for a URL without a path that finds nothing, tries `/prometheus`, `/api/prom` and `/select/0/prometheus` and offers the one that answers.

## Read-only tools

| Tool | What it does | Default mode |
| --- | --- | --- |
| `prometheus_query` | One PromQL expression at one time, by default now | Read only |
| `prometheus_query_range` | One PromQL expression at every step from start to end | Read only |
| `prometheus_series` | Label sets of the series that match `match[]` selectors in a time window | Read only |
| `prometheus_labels` | Label names in a time window, optionally of matching series only | Read only |
| `prometheus_label_values` | Values of one label; `__name__` lists metric names | Read only |
| `prometheus_metadata` | Type, help text and unit of metrics | Read only |
| `prometheus_targets` | Scrape targets with their health, last scrape and last error, or the targets relabeling dropped | Read only |
| `prometheus_rules` | Alerting and recording rules with their expressions, health and last error | Read only |
| `prometheus_alerts` | Pending and firing alerts with their labels, annotations and values | Read only |

Every tool reads, on every environment, prod included. GateRook builds no URL of an admin, write or lifecycle endpoint (`/api/v1/admin/…`, remote write, `/-/reload`, `/-/quit`), and PromQL can't change data.

Times are ISO 8601 with an offset or relative to now (`-1h`, `now-30m`, `now`); bare numbers are refused, as seconds and milliseconds would both be plausible. Series lookups, label names and label values cover the last hour unless `start` says otherwise. A range query without a step gets a round one for about 120 points per series (an hour at `30s`). One whose `(end - start) / step + 1` exceeds **Points per series** under **Advanced** (11,000, Prometheus' own limit) is refused before anything is sent, with the smallest step that fits. Each evaluation carries what is left of the call as Prometheus' `timeout`, so the server stops when the call does; its own `--query.timeout` and `--query.max-samples` still apply.

## Answers

Answers keep what the server said exactly: sample values stay as the server wrote them (`0.30000000000000004`, `NaN`, `+Inf`), times are ISO 8601 in UTC, label sets are objects, and native histograms keep their count, sum and buckets. A range query lists each series' points as `[time, value]`, oldest first, and the text adds each series' minimum, maximum and last value.

An answer holds at most **Series per answer** (1,000) and the endpoint's row limit. The server is asked for one more, so the answer says when there are more. Within the endpoint's response size, every series keeps the same number of its newest points, as many as fit; when even one point each doesn't fit, series go from the end. Lists lose whole items from the end. GateRook reads at most **Server answer size** (16 MB) of one server answer, builds only what an answer can show from it, and says when it cut one. Labels, help texts, annotations and errors are untrusted data: control and invisible characters are made visible, and long values are cut to the endpoint's cell size. Scrape URLs of targets are shown without their query string, which may hold a token. Dropped targets show the labels service discovery gave them (`__meta_…`), which can include cloud tags and Kubernetes annotations.

A read that an endpoint puts in **Approval** shows the exact `GET` with absolute times, even when the agent asked for `-1h`, and runs that request after approval. Only the evaluation `timeout` is added then: what is left of the call.

## What it doesn't limit

There is no list of allowed metrics. GateRook can't tell with certainty from PromQL text which series a server reads: Prometheus' grammar lets keywords be metric names, compatible servers extend the language (VictoriaMetrics' MetricsQL), and recording rules copy data under new names. Agents read every metric the credentials can read, so scope access on the server: a tenant, a read-only account, or a proxy that enforces label matchers on every query.

## Verify

The connector's tests run against a fake of the HTTP API in every build. For a real server, start the opt-in [Prometheus service](../test-services/README.md#prometheus) with `scripts/test-services.sh up prometheus` and run `PrometheusIT` with `-Pservices,prometheus-it`. It covers Prometheus 3.15.1 without authentication; VictoriaMetrics, Thanos, Mimir and Cortex are not tested. JVM checks do not establish native-image or installer compatibility.

Prometheus documents its [HTTP API](https://prometheus.io/docs/prometheus/latest/querying/api/), [PromQL](https://prometheus.io/docs/prometheus/latest/querying/basics/) and [query limits](https://prometheus.io/docs/prometheus/latest/command-line/prometheus/).
