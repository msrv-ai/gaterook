# 07 · Logs: Loki, Cloud Logging, CloudWatch, and OpenSearch

> Research snapshot: 2026-10-23. Recorded prototypes used Loki 3.7.9, OpenSearch 3.9.0 without its security plugin, moto 5.2.4, MockWebServer, google-auth 1.54.0, AWS SDK 2.57.3, Jackson 3.1.7, and Java 25. The fact review inspected primary sources but did not repeat every backend experiment. **UNVERIFIED** identifies remaining findings. Related: [cloud access](09-cloud.md), [NoSQL](05-nosql.md), and the [architecture](../ARCHITECTURE.md).

## 1. Decision

Implement one `logs` connector with four backend adapters and two shared log tools: `logs_search` and `logs_tail`. Share time parsing, limits, normalization, cursor handling, polling, masking metadata, and truncation. Both log tools classify only as READ or DENY; they have no write path. OpenSearch also exposes index discovery and explicitly enabled full REST tools, including approved administration; see [OpenSearch queries and administration](opensearch-api.md).

Use JDK HTTP plus Jackson for Loki, GCP REST, and the scoped OpenSearch log adapter. Full OpenSearch REST tools use Apache HttpClient 5 with automatic retries, redirects, authentication negotiation, and cookies disabled. This preserves GET bodies and prevents a generic approved GET operation from being replayed after a lost response. Google authentication supplies tokens without the full Cloud Logging gRPC stack. Use the synchronous AWS SDK for CloudWatch/STS and its SigV4 signer for Amazon OpenSearch, with a lightweight URLConnection transport. This avoids duplicate Jackson generations and reactive/network stacks for a small number of calls.

Tail is a bounded poll, not an indefinitely open stream. Each call waits for data or its deadline, returns a page and cursor, and can be continued by the next call. The research rejected Loki WebSocket tail, GCP bidirectional gRPC tail, and AWS event-stream Live Tail for the initial desktop tool contract. They carry additional lifecycle, transport, and cost complexity.

## 2. Backend requests

| Backend | Search | Tail | Scope boundary |
| --- | --- | --- | --- |
| Loki | `GET /loki/api/v1/query_range`, newest first | Same API, oldest first | Fixed configured tenant and base URL. |
| GCP Cloud Logging | `POST /v2/entries:list`, timestamp descending | Ascending timestamp queries | Fixed resourceNames plus an always-applied scope filter. |
| CloudWatch | Logs Insights StartQuery/GetQueryResults, or FilterLogEvents mode | FilterLogEvents | Configured log groups, region, and credentials. |
| OpenSearch | `POST /<allowed-indices>/_search` with query_string | Same search API, ascending | Index patterns in the path plus an always-applied query filter. |

Loki start is inclusive and end exclusive. Responses are grouped by streams and require a merge sort. Request `categorize-labels` to receive structured metadata distinctly; `detected_level` can be there rather than in stream labels. Errors may be plain text. Metric LogQL results are matrices, not log lines, and need an explicit aggregate representation.

GCP pages can be empty while still returning a nextPageToken. Continue within the call's page/deadline budget rather than treating the first empty page as completion. Keep the same filter/range parameters when using a native page token. Its precise lifetime and maximum accepted page size were not established by the report.

CloudWatch search fixes `queryLanguage=CWLI`. Deny the `SOURCE` command and SQL/PPL alternatives because they can choose log groups inside the query string, bypassing the configured scope. For Insights, poll with backoff, stop the query at the deadline, and label partial results. The final query shown for review includes any inserted fields/sort clauses. Filter mode uses the filter-pattern language for both search and tail; the reviewed API supported newest-first `startFromHead=false` only with sufficiently recent start times, while moto ignored that option.

OpenSearch log tools accept Lucene `query_string`, not arbitrary JSON DSL. Full Query DSL is available separately through `opensearch_query` when the connection enables full API access and relies on server-side permissions. A recorded terms-lookup experiment read a document from an index outside the requested search path; this makes naive DSL pass-through unsafe. Disable leading wildcards, apply a server timeout, and validate configured index patterns against cross-cluster/path syntax. `_resolve/index` is more useful than a wildcard HEAD request for determining whether indices actually exist. The evaluated server supported sort `numeric_type: date_nanos`, not the proposed sort `format` field.

## 3. Tools, normalized results, and time

`logs_search` accepts connection, backend-language query, from/to, limit, optional source, and continuation. Accept offset-aware ISO timestamps and explicit relative forms such as `-15m`, `now-1h`, or `now`; reject ambiguous bare numeric timestamps. Resolve relative times once and keep that range stable across pagination.

`logs_tail` accepts connection, query, optional source, continuation, first-call `since`, wait, and limit. The effective wait is bounded by both a small poll window and remaining tool time. Research defaults were eight seconds in dev and three in prod, with tail initially Off on endpoints. Search is idempotent for the same stable range; tail is not presented as an immutable snapshot.

A normalized entry contains timestamp, severity, message, labels, structured fields, and a stable backend-derived identity. Results include entries/count, bounds, truncation reason, continuation, waited time, possible gaps, request/scanned-byte statistics, and masked count. Optional aggregate rows represent LogQL metrics or Insights stats.

| Field | Loki | GCP | CloudWatch | OpenSearch |
| --- | --- | --- | --- | --- |
| Timestamp | Nanosecond timestamp | Entry timestamp | Event milliseconds / Insights timestamp | Nanosecond sort value |
| Identity | Timestamp + labels + line digest | logName + insertId | eventId / pointer | Index + document ID |
| Message | Log line | Text, message field, or structured payload summary | message / `@message` | Configured message field |
| Severity | Detected level, then level label | Native severity | Structured level or recognized prefix | Configured severity field |
| Scope labels | Stream + structured metadata | Resource/log labels | Log group/stream | Index + configured labels |

Bound each message and structured payload, then enforce the total response budget on complete entries. Preserve a continuation that does not skip entries removed by byte truncation. Mark limit, size, timeout, and partial-query truncation separately. The core masks messages, labels, fields, errors, and query arguments before returning or auditing them.

## 4. Loss-aware continuation

A tail cursor binds connection, query, source, direction, and position with an HMAC. Store the last timestamp plus identities already emitted at that timestamp. Resume inclusively at the boundary and remove only already-seen entries there. Moving directly to `timestamp > last` would lose distinct entries sharing a timestamp.

Do not rely on a backend returning equal-timestamp entries in the same order. Keep boundary identities or buffered overflow sufficient to avoid loss/repetition across pages, and expose `gapPossible` if a hard bound makes a guarantee impossible. The original prototype proposed a bounded boundary-ID set (256); reaching that bound must be handled explicitly rather than silently advancing.

Use an ingestion delay before the tail window's upper bound: research proposals were two seconds for Loki/OpenSearch, ten for GCP, and five for CloudWatch. This reduces but cannot eliminate late-arrival loss. Entries arriving later than the delay may be missed; tail is best effort, and a historical search is needed for completeness. The recorded Loki experiment covered equal timestamps and a short delayed arrival, not arbitrary lateness.

Search uses backend-native page tokens where suitable. Otherwise retain boundary state and the original range. The original Insights implementation proposal had no continuation and asked callers to narrow the range; the reviewed API had since added GetQueryResults pagination, which could be adopted deliberately with tests.

## 5. Configuration and classification

Configure the backend explicitly:

- Loki: base URL, fixed tenant, bearer/basic/none auth, and optional CA.
- GCP: fixed projects/log views, explicit ADC or service-account source, optional impersonation/delegates, always-applied filter, and quota share.
- CloudWatch: region, allowed groups, explicit profile/key source, optional AssumeRole/ExternalId, and Insights/filter search mode.
- OpenSearch: base URL, log index patterns, timestamp/message/severity/label fields, fixed scope query, explicit authentication, optional CA/SigV4 settings, and API access (`logs` by default; explicit `full` for REST queries and approved administration).

Secrets are write-only core-managed values. Short-lived impersonation/AssumeRole credentials stay in memory and are refreshed; do not describe cached fifteen-minute tokens as “one token per call.” AWS SSO profiles need their supported SDK modules. Do not silently use credentials from an unselected ambient source.

Classification denies sources outside configuration, invalid/reversed/oversized ranges, excessive query length, CloudWatch source selection inside Insights, and OpenSearch JSON DSL in the log tools. Clamp requested output and wait limits to stricter policy and disclose the effective limits. Let the backend validate its query language, but sanitize syntax/error messages.

Research policy proposals were 1,000 dev/200 prod lines, ten/five-second tool deadlines, a seven-day dev/one-day prod maximum range, and smaller production default page sizes. Current source schemas and policy code define the actual values.

## 6. Permission checks and security

Test connection uses bounded reads and reports each stage separately. Loki checks readiness/version/labels and may evaluate an empty push probe on known supported implementations; a parsing response is not universal proof of effective write permission. GCP reads one entry and tests relevant IAM permissions with a token whose scopes support the Resource Manager method. CloudWatch checks identity, groups, bounded reads, and Insights capability. OpenSearch checks version, index resolution, a bounded search, and optional security-account metadata.

Do not enable the proposed CloudWatch DeleteLogStream-on-a-random-name permission probe: whether IAM checks precede resource existence was **UNVERIFIED**. If permissions cannot be safely established, report unknown rather than guessing. An API key may not be allowed to call OpenSearch's security API even when reads work.

Treat log contents as untrusted data and a likely secret source. No unmask option is exposed; CloudWatch `unmask` is always false and the connector does not request `logs:Unmask`. Read privileges should be limited at IAM/backend level, independently of the query filter.

Disable HTTP redirects so credentials cannot follow a changed origin. Use explicit TLS trust and hostname validation, with no general insecure override. Proxy behavior and internal/VPN destinations must follow the selected connection policy. The agent cannot change auth headers, tenant, region, or resource parents.

Provider quotas are shared with other users and systems. The reviewed GCP entries.list quota was sixty requests/minute/project; the proposal reserved at most thirty for the gateway. The reviewed eu-central-1 FilterLogEvents quota was five TPS/account/region; the proposal used two. Rate-limit by provider scope, not merely connection name, and do not amplify throttling with retries. Limits may change and need revalidation when updating the integration.

Insights charges by scanned volume. Expose bytesScanned, bound time ranges, and avoid using Insights for repeated tail polling. Do not promise a price from this historical report.

## 7. Dependencies and implementation boundary

Evaluated versions were google-auth 1.54.0 and AWS SDK BOM 2.57.3. Use explicit `ServiceAccountCredentials.fromStream` for service-account input; generic Google credential loading was marked obsolete and can accept broader credential types. Share explicit authentication providers with the cloud connector rather than implementing a second credential chain.

Exclude AWS SDK's `apache5-client` and `netty-nio-client` when using URLConnection; the older `apache-client` exclusion alone no longer removed the default dependency in the reviewed release. Google auth transitive Gson may be mediated by Boot. Verify the resolved tree and reduced runtime, including JDK modules used indirectly by XML/STS/auth libraries.

Backend adapters should return normalized pages and request statistics. A common tail loop owns polling, continuation, delay, and deadlines. Query construction and the exact review text must derive from the same prepared request to avoid a mismatch between approved and executed scope.

## 8. Verification strategy and limits

Use fixture-based tests for relative-time parsing, severity normalization, HMAC tampering, cross-connection/query cursors, source restrictions, Insights SOURCE detection, DSL rejection, output-schema validation, and size truncation. Property tests should randomize same-timestamp order and page boundaries to detect lost or duplicated entries.

MockWebServer tests assert exact paths/headers/bodies, credential exchange and impersonation, retry/deadline behavior, plain-text errors, empty GCP pages, partial Insights queries and StopQuery, SigV4 headers, and OpenSearch sort options. Test canaries must be absent from agent output and audit logs.

Optional real services validate Loki query/tail/metadata semantics and OpenSearch index/query behavior. The recorded OpenSearch distribution had no security plugin, so Basic/API-key behavior was only mocked. Moto handled STS and simple log operations but did not accurately implement Insights filtering or newest-first behavior. GCP had no local logging emulator in this research. Real-provider permission/authentication checks remain separate from these fixtures.

## 9. Open questions and sources

Outstanding questions included GCP token expiry, receiveTimestamp-based tail alternatives, exact Insights timestamp formatting, current pricing/quotas, optional aggregate output, and a separate Elasticsearch integration. Do not imply Elasticsearch is supported merely because some APIs look similar. Describe the log tools as bounded polling and Lucene queries. Full REST tools have a separate contract, scope, and approval behavior documented in [OpenSearch queries and administration](opensearch-api.md).

- [Loki HTTP API](https://grafana.com/docs/loki/latest/reference/loki-http-api/)
- [Cloud Logging entries.list](https://docs.cloud.google.com/logging/docs/reference/v2/rest/v2/entries/list) and [quotas](https://docs.cloud.google.com/logging/quotas)
- [GCP Logging roles and permissions](https://docs.cloud.google.com/iam/docs/roles-permissions/logging)
- [CloudWatch FilterLogEvents](https://docs.aws.amazon.com/AmazonCloudWatchLogs/latest/APIReference/API_FilterLogEvents.html)
- [CloudWatch StartQuery](https://docs.aws.amazon.com/AmazonCloudWatchLogs/latest/APIReference/API_StartQuery.html), [GetQueryResults](https://docs.aws.amazon.com/AmazonCloudWatchLogs/latest/APIReference/API_GetQueryResults.html), and [SOURCE syntax](https://docs.aws.amazon.com/AmazonCloudWatch/latest/logs/CWL_QuerySyntax-Source.html)
- [CloudWatch Logs quotas](https://docs.aws.amazon.com/AmazonCloudWatch/latest/logs/cloudwatch_limits_cwl.html)
- [AWS temporary credentials](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/credentials-temporary.html)
- [OpenSearch API keys](https://docs.opensearch.org/latest/security/access-control/api-keys/)
