# OpenSearch: queries and administration

Reviewed against the official REST documentation on 2026-10-27. This document describes the API coverage and authorization contract of GateRook's OpenSearch backend, including the distinction between scoped log reads and full REST access. Installed plugins, the server version, managed-service restrictions, and the connection user's permissions determine which server operations are available.

## Enable the tools

An OpenSearch connection uses the `logs` connector with `backend: "opensearch"`. Full REST access is the default: an omitted `apiAccess` value means `"full"`, including in existing configurations. The connection offers `logs_search`, `logs_tail`, `opensearch_indices`, `opensearch_query`, and `opensearch_request`, subject to endpoint tool modes.

**OpenSearch API access** starts at **Full OpenSearch REST API**. Full API connections can omit `indices`, which then defaults to `["*"]`. To restrict a connection to log tools and scoped index discovery, explicitly select **Scoped log search** (`"apiAccess":"logs"`) and configure its log index patterns. Existing explicit `"logs"` selections remain scoped. Authentication, TLS, and AWS signing settings remain those of the connection.

Enable the desired tools on the endpoint as well. Existing endpoints have no configuration rows for newly added tools, so those tools remain Off until explicitly enabled. A connection's full API setting does not by itself grant an AI client endpoint access.

| Tool | Purpose | Authorization |
| --- | --- | --- |
| `opensearch_indices` | Resolve concrete indices, aliases, and data streams | READ. In log mode, restricted to configured patterns; in full mode, accepts an index selector and defaults to all indices. |
| `opensearch_query` | Known read REST APIs with full Query DSL, aggregations, metadata, and diagnostics | READ, subject to endpoint policy. Requires full API access. Unrecognized operations are denied with a direction to use `opensearch_request`. |
| `opensearch_request` | Arbitrary supported OpenSearch REST operations, including settings, writes, SQL/PPL, and plugins | MUTATION classification with Approval by default, including for GET. Explicit endpoint Bypass also authorizes the exact prepared request. Requires full API access. |

Bypass is an explicit per-tool endpoint choice, never a default. It executes the same prepared request without creating a human approval; configuration checks, path validation, server permissions, and output limits still apply.

The configured `indices` and `scopeQuery` continue to govern `logs_search` and `logs_tail`. They do **not** restrict full REST tools. Use OpenSearch roles, document/field permissions, and managed-service policies to scope full REST credentials. GateRook deliberately does not present a URL index check as a complete boundary for arbitrary DSL.

## Request and response contract

Query and request tools accept `method`, a server-relative `path`, optional `query` parameters, optional `body`, and `contentType`. Supported methods include GET, HEAD, POST, PUT, PATCH, DELETE, and OPTIONS. GET and DELETE can carry bodies. Query values can be strings, numbers, booleans, or arrays of those values. Structured query values are URL-encoded; arrays produce repeated parameters.

Use `application/json` for JSON objects, arrays, or JSON text. Use `application/x-ndjson` with a text body for bulk and multi-search. Each nonempty line must be valid JSON. GateRook adds a final newline when absent, and the preview and executed body use those same bytes. Request bodies are never silently truncated.

Responses retain the server's JSON value or text in `body`, with `status`, `contentType`, and `truncated`. HTTP error statuses and bulk item errors remain visible. They are server answers, not proof that a requested operation succeeded. Response budgets may truncate output; check `truncated` before treating results as complete. Native cursors and task IDs remain in untruncated server responses and can be supplied in subsequent calls.

The stored connection fixes the origin and authentication. Agents cannot replace Authorization or Host headers, redirect credentials to another host, or use traversal paths. A prepared request freezes its method, path, query, content type, and body. Approved execution uses the reviewed payload even if the later tool arguments differ; Bypass executes the freshly prepared payload. Credentials remain managed by GateRook. Full REST calls use Apache HttpClient 5 with retries and redirects disabled, including for GET requests. A connection failure after an authorized request was dispatched is reported as an unknown outcome. After an uncertain timeout or lost response, inspect server state before submitting another mutation.

## Examples

List available indices and aliases:

```json
{"connection":"search-dev","index":"products-*","expandWildcards":"all"}
```

Use `opensearch_query` for an aggregation without imposing the log tool's time range or source fields:

```json
{
  "connection": "search-dev",
  "method": "POST",
  "path": "/products/_search",
  "body": {
    "size": 0,
    "query": {"match_all": {}},
    "aggs": {"categories": {"terms": {"field": "category"}}}
  }
}
```

Inspect index configuration with `opensearch_query`:

```json
{
  "connection": "search-dev",
  "path": "/products/_settings",
  "query": {"include_defaults": true, "flat_settings": true}
}
```

Change an index setting with `opensearch_request`:

```json
{
  "connection": "search-dev",
  "method": "PUT",
  "path": "/products/_settings",
  "body": {"index": {"number_of_replicas": 1}}
}
```

Change a dynamic cluster setting with `opensearch_request`:

```json
{
  "connection": "search-dev",
  "method": "PUT",
  "path": "/_cluster/settings",
  "body": {"persistent": {"cluster.max_shards_per_node": 500}}
}
```

Submit SQL through `opensearch_request` when the SQL plugin is installed:

```json
{
  "connection": "search-dev",
  "method": "POST",
  "path": "/_plugins/_sql",
  "body": {"query": "SELECT category, COUNT(*) FROM products GROUP BY category"}
}
```

For PPL, use `/_plugins/_ppl` with a query such as `source=products | stats count() by category`. Both language endpoints use Approval by default because the generic connector does not assume every supported version, plugin engine, or statement is read-only.

## Index settings and server settings

`GET /products/_settings?include_defaults=true` inspects index settings, and `PUT /products/_settings` changes them. Dynamic settings change online. Some static settings require closing the index first; final settings such as the primary shard count cannot simply be changed afterward. A null value resets a setting. The tool accepts any setting supported by the server. [Update Index Settings](https://docs.opensearch.org/latest/api-reference/index-apis/update-settings/)

`GET /_cluster/settings?include_defaults=true` and `PUT /_cluster/settings` manage dynamic cluster settings. Updates use `persistent` and/or `transient`; null removes an override. Persistent settings survive restart; transient settings take precedence but do not survive restart. Static node configuration belongs in `opensearch.yml` or startup settings. Full REST access cannot change static node settings, install plugins, or restart the host by itself. [Cluster Settings](https://docs.opensearch.org/latest/api-reference/cluster-api/cluster-settings/), [Configuring OpenSearch](https://docs.opensearch.org/latest/install-and-configure/configuring-opensearch/index/)

## Core API coverage

The treatment column distinguishes retrieval from changes. `opensearch_query` recognizes a conservative subset of reads; use `opensearch_request` for every other supported operation, including read operations outside that subset. WRITE below means MUTATION in GateRook and uses Approval by default, with execution also available through explicit Bypass.

| Family | Representative coverage | Operation treatment |
| --- | --- | --- |
| Discovery | `GET /`, `/_resolve/index/{pattern}`; aliases, concrete indices, and data streams | READ. Resolve is useful for actual names and object kinds. [Resolve Index](https://docs.opensearch.org/latest/api-reference/index-apis/resolve-index/) |
| Index inventory | `GET /_cat/indices/{pattern}?format=json`; `/_list/indices/{pattern}` | READ. CAT adds health/storage/shards; its document counts include nested Lucene documents. List APIs add pagination in 2.18+, with `next_token` and `size`. [CAT Indices](https://docs.opensearch.org/latest/api-reference/cat/cat-indices/), [List Indices](https://docs.opensearch.org/latest/api-reference/list/list-indices/) |
| Schema/metadata | `GET /{index}`, `/{index}/_mapping`, `/{index}/_settings` | READ. Get Index returns mappings, aliases, and settings. [Get Index](https://docs.opensearch.org/latest/api-reference/index-apis/get-index/) |
| Search | `_search`, `_msearch`, `_count`, `_explain`, `_field_caps`, `_validate/query`, `_search_shards`, `_rank_eval`; aggregations, highlighting, scripts, profiling, vector queries in DSL; templates, scroll, PIT | Known query endpoints READ; lifecycle commands conservatively WRITE unless intentionally accepted as query context management. Preserve full JSON and query parameters. [Search APIs](https://docs.opensearch.org/latest/api-reference/search-apis/index/) |
| Individual/batch documents | `_doc`, `_create`, `_update`, `_mget`, `_termvectors`, `_mtermvectors`, `_bulk`, `_update_by_query`, `_delete_by_query`, `_reindex` | Retrieval READ; writes and copy/delete/update jobs WRITE. Preserve optimistic concurrency parameters and routing. [Document APIs](https://docs.opensearch.org/latest/api-reference/document-apis/index/) |
| Index administration | Creation/deletion/open/close, aliases, data streams, mapping updates, templates, settings, blocks/allocation, cloning/shrinking/splitting, dangling indices, maintenance | Retrieval READ; all alterations and maintenance WRITE. [Index APIs](https://docs.opensearch.org/latest/api-reference/index-apis/index/) |
| Cluster | Health/state/stats/pending tasks/settings/remote info; reroute/allocation/voting exclusions/decommission | Known status reads READ; configuration and lifecycle WRITE. [Cluster APIs](https://docs.opensearch.org/latest/api-reference/cluster-api/index/) |
| Nodes | Info/stats/usage/hot threads; reload secure settings | Known status reads READ; reload WRITE. Hot threads can be text. [Nodes APIs](https://docs.opensearch.org/latest/api-reference/nodes-apis/index/) |
| Ingest | `/_ingest/pipeline`, processors, `/_simulate` | Pipeline reads READ; mutations WRITE. Simulation runs processors (possibly plugin processors); conservatively require review until its side effects are assessed. [Ingest APIs](https://docs.opensearch.org/latest/api-reference/ingest-apis/index/) |
| Scripts | Stored scripts, inline execution, supported languages/contexts | Metadata retrieval READ; script storage/deletion/execution conservatively WRITE. [Script APIs](https://docs.opensearch.org/latest/api-reference/script-apis/index/) |
| Snapshot/repository | Repository definitions, verification/cleanup, snapshots/status/create/clone/delete/restore | Retrieval READ; all lifecycle work WRITE, including verification that may touch storage. [Snapshot APIs](https://docs.opensearch.org/latest/api-reference/snapshots/index/) |
| Tasks | List/get/delete/cancel/rethrottle | Status READ; delete/cancel/rethrottle WRITE. Async mutations return a task; polling success does not substitute for observing task result/failures. [Tasks APIs](https://docs.opensearch.org/latest/api-reference/tasks/tasks/) |
| Security | `/_plugins/_security/`: accounts, users, roles, mappings, action groups, tenants, API keys, configuration, certificates, caches | Conservative review for arbitrary security endpoints. Availability depends on explicit REST-admin privileges or supported admin certificate roles; general cluster admin does not automatically grant every security API. [Security APIs](https://docs.opensearch.org/latest/security/api/index/), [API permissions](https://docs.opensearch.org/latest/security/access-control/api/) |

## SQL, PPL, and plugins

`POST /_plugins/_sql` accepts SQL in a JSON `query`; `POST /_plugins/_ppl` accepts PPL. Both have explain routes. SQL JDBC output contains schema and rows and supports `fetch_size` and opaque cursor continuation; CSV/raw are also available. This is OpenSearch SQL, not MySQL wire protocol or full MySQL language compatibility. [SQL and PPL API](https://docs.opensearch.org/latest/sql-and-ppl/sql-and-ppl-api/index/)

GateRook sends SQL and PPL through `opensearch_request`, which defaults to Approval: legacy SQL supports DELETE behind a disabled-by-default setting, and OpenSearch 3.0 removed DELETE support. The current documentation still contains the legacy DELETE page, so versionless assumptions are unsafe. [SQL DELETE](https://docs.opensearch.org/latest/sql-and-ppl/sql/delete/), [3.0 breaking changes](https://docs.opensearch.org/latest/breaking-changes/)

This connector supports its configured Basic, bearer, API-key, or AWS SigV4 credentials; it does not support TLS client certificates. Security APIs that require an admin certificate cannot be reached with that authentication requirement through this connector. Role-based access works only where the installed Security plugin explicitly permits it.

A generic REST tool also carries installed plugin APIs: asynchronous search and search pipelines; search relevance; k-NN/ML Commons/workflow; ISM/rollups/transforms/analyzer refresh; CCR/remote store/backpressure/workload rules; snapshot management; alerting/anomaly detection/forecasting/job scheduler/notifications/query insights/performance analysis; resource sharing/security analytics. New plugin endpoints remain reachable without adding a new SDK method, with conservative review by default. The official API specification is expressly incomplete; it cannot be used as an exhaustive allowlist. Dashboards saved objects/workspaces/reports use a separate service, usually port 5601; experimental gRPC is a separate transport. [API reference](https://docs.opensearch.org/latest/api-reference/)

Examples of plugin-specific effects make a blanket plugin READ rule unsuitable: ML agent execution runs configured tools, and resource-sharing PATCH changes permissions. [Execute Agent](https://docs.opensearch.org/latest/ml-commons-plugin/api/agent-apis/execute-agent/), [Resource sharing APIs](https://docs.opensearch.org/latest/security/access-control/resource-sharing-api/)

## Payloads and authorization boundaries

1. Bulk requires alternating NDJSON action/document records where appropriate, with the final newline preserved. It can combine create/index/update/delete and target different indices. HTTP 200 can contain failed items; preserve `errors` and individual results. [Bulk API](https://docs.opensearch.org/latest/api-reference/document-apis/bulk/)
2. Multi-search also uses NDJSON. The index in the path is a default; each metadata line may override it. A request with no target can search all indices. Preserve per-search failures and order. [Multi-search](https://docs.opensearch.org/latest/api-reference/search-apis/multi-search/)
3. API bodies are not limited to POST; search with GET and JSON is supported. JSON and NDJSON suffice for a practical connector, although the server also supports other encodings. Keep content type explicit. [Common parameters](https://docs.opensearch.org/latest/api-reference/common-parameters/)
4. An index in the URL is not a complete authorization boundary. Terms lookup loads values from a separately named index. Existing GateRook logs research already records this as the reason arbitrary DSL is rejected. [Terms query](https://docs.opensearch.org/latest/query-dsl/term/terms/)
5. SQL/PPL targets are inside the query body. The SQL documentation explicitly gives them the same access-policy concern as bulk/mget/msearch. Setting `rest.action.multi.allow_explicit_index=false` disables SQL/PPL. [SQL settings](https://docs.opensearch.org/latest/sql-and-ppl/settings/)
6. Reindex names source and destination in its body and can read from a remote host, whose credentials may also be in the body. Asynchronous execution can outlive the gateway request deadline. The original mappings/settings are not copied automatically. [Reindex](https://docs.opensearch.org/latest/api-reference/document-apis/reindex/)
7. The Security plugin authorizes underlying actions, not merely URL/method pairs. A single endpoint can need multiple index and cluster permissions. Therefore full REST access should rely on server credentials/roles for data scope; the existing gateway index filter and fixed scope query must not be advertised as enforcing arbitrary REST scope. [Security permissions](https://docs.opensearch.org/latest/security/access-control/permissions/)

## Verification and practical limits

The unit suite uses MockWebServer to verify full API defaults, explicit scoped mode, index discovery scope, unmodified DSL and aggregation results, query parameter encoding, approval replay, JSON Patch, NDJSON, Basic authentication, SigV4 writes, path rejection, HTTP error bodies, text output, and response/request budgets. `OpenSearchIT` exercises index creation, discovery, bulk ingestion, GET-body aggregation, index settings, dynamic cluster settings with restoration, deletion, and existing log behavior against a disposable server.

Run the module checks using the JDK and Maven versions in [development](../development.md):

```bash
mvn -B -ntp -pl connectors/logs -am verify
```

Real-service tests need the documented fixture or [test services](../../test-services/README.md). The standalone minimal distribution has no Security or SQL plugin. Full distribution fixtures can exercise SQL/PPL when the SQL plugin is installed; the test explicitly skips that case when it is absent. Security-disabled fixtures cannot establish Security-plugin permissions or Amazon-managed service coverage. Basic authentication, SigV4 signing, private-CA trust, and hostname verification use mocks. Disconnect tests verify that approved GET and POST requests are not replayed, and deadline tests cover stalled headers and bodies. A listed API family indicates REST reachability through the generic request tool, not proof that every operation was tested on every deployment.

Long-running asynchronous tasks may continue after the gateway deadline. Query size, shard fan-out, scripts, and administrative operations can consume server resources even when gateway output is bounded. Choose server-side limits and permissions appropriate to the connection; use returned task IDs and native continuation protocols for follow-up requests.
