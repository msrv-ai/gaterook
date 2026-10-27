# 09 · Cloud connectors: GCP and AWS

Research date: **2026-10-23**. This is a design and evidence record for the single-user desktop gateway. Historical prototype measurements below are not fresh migration test results. The current implementation and build manifests take precedence where they have evolved. See the [architecture](../ARCHITECTURE.md), [logging research](07-logs.md), and [secrets research](12-secrets.md).

## 1. Selected approach

Provide two built-in connectors with two tools each: `gcp_describe`, `gcp_metrics`, `aws_describe`, and `aws_metrics`. Share time parsing, resource globs, sensitive-field redaction, cursors, and the Cloud policy section. Cloud credential support is shared with logs and external secret stores.

| Area | GCP | AWS |
| --- | --- | --- |
| Transport | `java.net.http`, Jackson 3, google-auth for tokens | AWS SDK v2 with URLConnection transport |
| Inventory | Cloud Asset v1 `searchAllResources` | Cloud Control `ListResources` / `GetResource` |
| Current resource state | Allowlisted service-specific GET routes | Cloud Control type handlers |
| Metrics | Monitoring v3 time series, metric descriptors, alerts | CloudWatch metric data, metric list, alarms |
| Credentials | ADC or service-account key, preferably impersonation | Access key or profile, preferably AssumeRole with ExternalId |
| Token lifetime | 900 seconds by default | 900 seconds by default |

Classification returns `READ` or `DENY`; these tools never classify as mutations. The default mode is Read only, with explicit `readOnlyHint:true`, `destructiveHint:false`, `idempotentHint:true`, and `openWorldHint:false`. A person can still require approval for a read. The approval must display the exact operation, account/project, region, range, metric expressions, and parameters.

Four independent controls enforce the read boundary: an operation allowlist in code; URLs constructed by the gateway; an AWS session policy; and remote IAM permissions. An HTTP GET is not inherently safe: Google GET endpoints can return Redis passwords, serial output, storage object contents, or secret values. Those routes are never admitted by the live-resource route table.

## 2. Dependencies and alternatives

The recorded prototypes used Temurin 25.0.5, AWS SDK **2.57.3** (`cloudcontrol`, `cloudwatch`, `sts`, `url-connection-client`), google-auth-library **1.54.0**, Jackson **3.1.7**, MockWebServer **5.5.1**, and moto **5.2.4**. Spring Boot 4.1.2 managed Jackson but not AWS SDK, google-auth, or OkHttp. Consult the root Maven manifest for current pins.

Exclude the SDK's Apache and Netty HTTP transports. The measured AWS dependency set was 6.95 MiB; google-auth with its required dependencies was 4.65 MiB. Optional AWS SSO support adds `sso` and `ssooidc` and requires a valid `aws sso login` session.

Rejected alternatives:

- Google Cloud Asset/Monitoring Java clients pulled in gRPC, gax, and protobuf: 62 jars totaling approximately 54 MB in the recorded comparison.
- Separate AWS service SDKs substantially increased size; Cloud Control was approximately 0.46 MB versus approximately 26 MB for EC2 alone.
- Resource Explorer lacks detailed state and requires an account index. AWS Config adds recorder setup and cost.
- Handwritten SigV4, STS XML, and CloudWatch CBOR duplicate SDK functionality already shared with logging.
- Arbitrary agent-supplied URLs would bypass the resource-access boundary.

## 3. Tool contracts

### AWS inventory

`aws_describe` accepts connection, configured region, `AWS::Service::Type`, optional identifier/parent properties, bounded filters, projected properties, limit, and cursor. Without an identifier it uses `ListResources`, with at most five pages to satisfy filters. With an identifier it uses `GetResource`.

Cloud Control's `Properties` is JSON encoded inside a JSON string. Parse it twice. Normalize tags, derive status only from documented type fields, and derive an ARN only from a known field or verified type-specific template. A direct request outside the resource masks is denied; a list hides such resources and reports `hiddenByPolicy`. An unknown ARN under a restrictive mask must fail closed.

List results can be incomplete; return `complete:false` and explain that an identifier lookup is required. In the recorded schema corpus, 1,552 of 1,741 types supported both list and read. ECS service identifiers combine `ServiceArn|Cluster`. Cloud Control does not expose all useful runtime states: Lambda state, ECS task counts, and alarm state are not generally available through its resource model. Use metrics/alarms for these MVP cases.

### AWS metrics

`aws_metrics` has `query`, `list_metrics`, and `alarms` actions. Query accepts up to ten metric requests and five math expressions. Call `GetMetricData` with ascending timestamps and a bounded point budget; preserve partial-data and forbidden statuses. Default period targets no more than 360 points and increases to at least 300 seconds after 15 days and 3,600 seconds after 63 days. List uses `RecentlyActive=PT3H`; alarms use the requested state and name prefix.

Do not expose cross-account `AccountId` or Metrics Insights. Parse metric math with an allowlist: query IDs, numbers, arithmetic, comparisons, boolean operators, and the approved local transformation functions. Strings are accepted only in `METRICS("...")`. Reject `LAMBDA`, `SEARCH`, `SELECT`, `SCHEMA`, `INSIGHT_RULE_METRIC`, `DB_PERF_INSIGHTS`, and `SERVICE_QUOTA`. These can execute a function or fetch data beyond the requested metrics.

**Critical finding:** denying `lambda:InvokeFunction` in the caller's session policy does not block CloudWatch `LAMBDA()`. CloudWatch uses its own service principal and the function resource policy. The expression parser is essential.

### GCP inventory

Search Cloud Asset with configured scopes, allowlisted asset types, bounded query/order fields, and a narrow read mask. The agent cannot supply regular-expression metacharacters in asset types. For a named resource, first establish membership in the configured scope with an exact name comparison; then use an approved live GET or the inventory snapshot.

The live table covers Compute, Cloud Run, Cloud SQL, GKE, Pub/Sub, Storage bucket metadata, Cloud Functions, and Redis instance metadata. Match complete resource-name patterns, reject percent escapes, traversal, query fragments, and extra path segments, and construct the API URL internally. Never permit `authString`, `serialPort`, object-content, or `:access` routes.

Cloud Asset is eventually consistent, usually by minutes and potentially much longer for some types. Include inventory timestamps and distinguish live state from snapshots. Searching for a newly created resource may not yet find it.

### GCP metrics

`gcp_metrics` supports time-series queries, metric descriptors, and alerts. Resolve a metric descriptor to its kind/unit/aligner and cache it for one hour. Require exactly one metric type. Construct the Monitoring filter as the selected type plus the bounded user filter; reject attempts to inject another `metric.type` or `project` selector.

Monitoring FULL-view page size counts **points**, not series. Normalize returned points to ascending time, convert integer string values carefully, and summarize distributions as count and mean. Alignment is at least 60 seconds. Alert filter semantics were not established in the research, so filter alert state locally rather than claim unsupported syntax.

## 4. Configuration and limits

GCP configuration includes scopes, metrics projects, ADC or service-account-key credentials, impersonated service account, optional delegates, token lifetime, quota project, and live-GET preference. Accept only explicitly supported credential kinds; do not fall back to compute metadata credentials. Service-account `token_uri` must be the expected Google endpoint.

AWS configuration includes regions, pinned account ID, display alias, access key or named profile, role ARN, generated ExternalId, session duration, and generated read-only session policy. Do not persist expiring `ASIA...` credentials. Warn before using a profile with `credential_process`: it executes a local command.

The original region and ARN patterns covered the standard, China, and GovCloud partitions but excluded `aws-eusc` and `aws-iso*`. Supporting another partition requires coordinated validation, endpoint, ARN, policy, and test changes; do not merely loosen a regex.

| Limit | Development/staging | Production |
| --- | --- | --- |
| Tool timeout | 10 seconds | 5 seconds |
| Default / maximum items | 25 / 100 | 10 / 50 |
| Maximum points | 2,000 | 1,000 |
| Maximum range | 14 days | 7 days |
| AWS session policy | Generated by default; disabling warns | Generated required |

Cloud policy does not inherit across environments. Intersect service/region/resource sets and take the minimum numeric limits. Lower an excessive requested count with an explicit note; deny an invalid range or unauthorized scope. Resource masks are strict and may hide otherwise allowed services. Metrics are scoped by namespace rather than ARN masks; document this distinction.

## 5. Credentials, redaction, and network boundary

An AWS execution interceptor permits only `ListResources`, `GetResource`, `GetMetricData`, `ListMetrics`, `DescribeAlarms`, and the required STS operations. In the recorded experiment, DeleteResource and PutMetricData threw before credential resolution/network activity.

Generate an inline session policy from allowed services, plus Cloud Control/CloudWatch reads. Add explicit denials for secret reads, SSM parameters, KMS decrypt, S3 object reads, DynamoDB data reads, SQS ReceiveMessage, and Lambda invocation. Keep the inline/managed policy input within the STS 2,048-character limit. Session policy intersection does not replace the operation guard.

Redact before returning data to the core, then apply the core's masking again. Sensitive examples include EC2 user data, Lambda environment variables, ECS environment values and repository credentials, ElastiCache auth tokens, Compute metadata, Cloud Run/Functions environment variables, and GKE master credentials. Also mask keys matching password, secret, token, private key, credential, connection string, user data, or auth string patterns. Reject Secrets Manager and SSM Parameter resource types entirely.

Keep temporary credentials in memory and refresh before expiry. Pin the expected account after Test connection. Disable redirects and cap response bodies at 8 MB. Build Google hosts and AWS region endpoints from known tables. AWS environment/profile endpoint settings can redirect signed traffic; explicit endpoint selection and override handling must prevent unexpected destinations. Test overrides are never exposed as production connector fields.

Treat tags, labels, descriptions, and alarm reasons as untrusted data. Audit provider, operation, type, region/scope, range, filters, page counts, masking counts, request IDs, and requested metric counts; retain only a cursor hash.

## 6. Test connection

GCP: obtain/refresh the supported credential; impersonate where configured; inspect the project/folder; read one Cloud Asset item and one metric descriptor; probe a bounded list with `testIamPermissions`. Report missing viewer roles, API enablement, project state, and dangerous permissions such as secret access or resource mutation. Testing permissions must not perform mutations.

AWS: get the base caller identity; reject or warn on root credentials; AssumeRole with ExternalId and session policy; verify the session identity/account; try a bounded selection of resource types; call ListMetrics and DescribeAlarms. Explain missing permissions by type because Cloud Control handlers may need cross-service reads. A generated session policy is evidence of a constrained session, not a complete remote-role audit.

## 7. Recorded experiments and reproducible test strategy

The 2026-10-23 prototypes compiled and exercised MockWebServer and moto. The protocol distinctions are necessary to reproduce them:

- STS uses Query/XML. Return future expiration times or the SDK refreshes continuously.
- Cloud Control uses JSON 1.0 with `X-Amz-Target: CloudApiService.ListResources`.
- CloudWatch SDK 2.57.3 uses Smithy RPCv2 CBOR at `/service/GraniteServiceVersion20100801/operation/GetMetricData`, with `Content-Type: application/cbor`, `smithy-protocol: rpc-v2-cbor`, and timestamp tag 1. moto 5.2.4 returned 404 for this protocol and did not provide Cloud Control; use a CBOR-aware mock.
- GCP tests override token/IAM endpoints and verify token reuse, request metadata, URL encoding, and quota-project headers. One 900-second impersonated token serviced five recorded calls.
- Unit tests cover expressions, scope classification, resource masks, sensitive routes, type redaction, period adjustment, cursor bounds, and session policy size/partitions.
- Guard tests assert zero additional server requests for forbidden operations. Contract tests cover throttling, partial data, response schemas, filtered pagination, deadlines, and masked values.
- Opt-in `cloud-it` exercises moto STS. Live-cloud validation remains a separate explicitly configured smoke test.

## 8. Remaining uncertainties

The research did not establish whether Cloud Control's internally created temporary handler session retains the caller's session-policy restrictions; compatibility of AWS-managed policies in `PolicyArns` on a live account; Cloud Asset consumer/quota-project attribution for impersonated tokens; CRM behavior for irrelevant permissions; Monitoring alert-filter grammar/read quotas/pricing; Cloud Control TPS limits and large-account latency; or the proposed IAM simulation path. Preserve these as open questions, not verified guarantees.

Handlers can need surprising permissions: RDS reads can require EC2 reads; Lambda reads can require KMS decrypt and therefore fail safely under the chosen policy. The dated price observation was $0.01 per 1,000 requested CloudWatch metrics in us-east-1; it is evidence for auditing `metricsRequested`, not a current price promise.

## 9. Primary evidence

- [AWS ListResources API](https://docs.aws.amazon.com/cloudcontrolapi/latest/APIReference/API_ListResources.html) and [listing behavior](https://docs.aws.amazon.com/cloudcontrolapi/latest/userguide/resource-operations-list.html).
- [CloudFormation schema corpus](https://schema.cloudformation.eu-central-1.amazonaws.com/CloudformationSchema.zip).
- [CloudWatch GetMetricData](https://docs.aws.amazon.com/AmazonCloudWatch/latest/APIReference/API_GetMetricData.html), [metric math](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/using-metric-math.html), and [custom data-source invocation](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/CloudWatch_MultiDataSources-Connect-Custom.html).
- [STS AssumeRole](https://docs.aws.amazon.com/STS/latest/APIReference/API_AssumeRole.html) and [SDK endpoint settings](https://docs.aws.amazon.com/sdkref/latest/guide/feature-ss-endpoints.html).
- [Cloud Asset discovery](https://cloudasset.googleapis.com/$discovery/rest?version=v1), [search syntax](https://docs.cloud.google.com/asset-inventory/docs/searching-resources), [resource names](https://docs.cloud.google.com/asset-inventory/docs/resource-name-format), [quotas](https://docs.cloud.google.com/asset-inventory/docs/quota), and [asset freshness](https://docs.cloud.google.com/asset-inventory/docs/asset-types).
- [Monitoring quotas and retention](https://docs.cloud.google.com/monitoring/quotas) and [MQL deprecation](https://docs.cloud.google.com/stackdriver/docs/deprecations/mql).
- [Smithy RPCv2 CBOR](https://smithy.io/2.0/additional-specs/protocols/smithy-rpc-v2-cbor.html).

## Appendix: recorded schema and response examples

These prototype examples preserve the investigated contracts. Current generated connector schemas are authoritative for executable behavior.
```json
{"type":"object","additionalProperties":false,"required":["connection","type"],"properties":{
 "connection":{"type":"string","enum":["aws-stage"]},"region":{"type":"string","enum":["eu-central-1"],"description":"Default: the connection's first region"},
 "type":{"type":"string","pattern":"^AWS::[A-Za-z0-9]+::[A-Za-z0-9]+$","examples":["AWS::EC2::Instance","AWS::RDS::DBInstance","AWS::ECS::Service","AWS::SQS::Queue"]},
 "identifier":{"type":"string","maxLength":2048,"description":"Primary identifier from a list result (i-0abc…, stage-orders, ServiceArn|Cluster). Given: full current state of one resource"},
 "parent":{"type":"object","maxProperties":6,"additionalProperties":{"type":"string","maxLength":2048},"description":"Only for types whose list needs a parent, e.g. {\"LoadBalancerArn\":\"…\"}"},
 "where":{"type":"object","maxProperties":5,"additionalProperties":{"type":"string","maxLength":256},"description":"Filter after listing: {\"State.Name\":\"running\",\"tags.env\":\"stage\"}; * matches any text"},
 "properties":{"type":"array","maxItems":30,"items":{"type":"string","maxLength":128},"description":"Return only these top-level properties"},
 "limit":{"type":"integer","minimum":1,"maximum":100,"default":25},"cursor":{"type":"string","maxLength":4096}}}
```

```json
{"provider":"aws","account":"412398765012","region":"eu-central-1","type":"AWS::RDS::DBInstance","mode":"list",
 "resources":[{"identifier":"stage-orders","arn":"arn:aws:rds:eu-central-1:412398765012:db:stage-orders","status":"available",
   "tags":{"env":"stage"},"properties":{"Engine":"postgres","DBInstanceClass":"db.t4g.medium"},"complete":false}],
 "count":1,"hiddenByPolicy":0,"truncated":false,"nextCursor":null,"masked":0,"note":"ListResources may return only some properties; pass identifier for the full state"}
```

```json
{"type":"object","additionalProperties":false,"required":["connection"],"properties":{
 "connection":{"type":"string","enum":["aws-stage"]},"region":{"type":"string","enum":["eu-central-1"]},"action":{"enum":["query","list_metrics","alarms"],"default":"query"},
 "metrics":{"type":"array","minItems":1,"maxItems":10,"items":{"type":"object","additionalProperties":false,"required":["namespace","metric"],"properties":{
   "id":{"type":"string","pattern":"^[a-z][a-zA-Z0-9_]{0,31}$"},"namespace":{"type":"string","maxLength":255,"examples":["AWS/RDS"]},"metric":{"type":"string","maxLength":255},
   "dimensions":{"type":"object","maxProperties":30,"additionalProperties":{"type":"string","maxLength":1024}},
   "stat":{"type":"string","maxLength":32,"default":"Average","examples":["Average","Sum","Maximum","p99"]},"label":{"type":"string","maxLength":256}}}},
 "expressions":{"type":"array","maxItems":5,"items":{"type":"object","additionalProperties":false,"required":["id","expression"],"properties":{
   "id":{"type":"string","pattern":"^[a-z][a-zA-Z0-9_]{0,31}$"},"expression":{"type":"string","maxLength":512,"examples":["100 * m2 / m1"]},"label":{"type":"string"}}}},
 "from":{"type":"string","default":"-1h"},"to":{"type":"string","default":"now"},
 "period":{"type":"integer","enum":[60,300,900,3600,21600,86400],"description":"Default: smallest giving ≤ 360 points that fits retention"},
 "namespace":{"type":"string"},"metric":{"type":"string"},"dimensions":{"type":"object","additionalProperties":{"type":"string"}},
 "state":{"enum":["ALARM","OK","INSUFFICIENT_DATA"],"description":"alarms; default ALARM"},"alarmNamePrefix":{"type":"string","maxLength":255},
 "limit":{"type":"integer","minimum":1,"maximum":100,"default":50},"cursor":{"type":"string","maxLength":4096}}}
```

```json
{"provider":"aws","region":"eu-central-1","action":"query","from":"2026-10-22T11:30:00Z","to":"2026-10-22T12:30:00Z","period":300,"messages":[],"truncated":false,"nextCursor":null,
 "series":[{"id":"m1","label":"CPUUtilization","namespace":"AWS/RDS","metric":"CPUUtilization","dimensions":{"DBInstanceIdentifier":"stage-orders"},"stat":"Average","status":"Complete",
   "points":[{"t":"2026-10-22T12:25:00Z","v":12.5}],"summary":{"min":10.0,"max":40.0,"avg":18.2,"last":12.5}}],
 "metrics":null,"alarms":null}
```

```json
{"type":"object","additionalProperties":false,"required":["connection"],"properties":{
 "connection":{"type":"string","enum":["gcp-stage"]},"scope":{"type":"string","enum":["projects/acme-stage"]},
 "name":{"type":"string","maxLength":1024,"pattern":"^//[a-z0-9-]+\\.googleapis\\.com/[A-Za-z0-9._~@:/-]+$","description":"Full resource name from a search result. Given: one resource with its full state"},
 "assetTypes":{"type":"array","maxItems":10,"items":{"type":"string","pattern":"^[a-z0-9-]+\\.googleapis\\.com/[A-Za-z]+$"},"examples":[["run.googleapis.com/Service","compute.googleapis.com/Instance"]]},
 "query":{"type":"string","maxLength":2000,"description":"Cloud Asset query: state=RUNNING, labels.env:stage, location:europe-west1*, name:api"},
 "orderBy":{"type":"string","pattern":"^(name|assetType|location|createTime|updateTime|state)( DESC)?$"},
 "live":{"type":"boolean","default":true,"description":"With name: read from the service's own API, not the inventory snapshot (minutes old), when supported"},
 "limit":{"type":"integer","minimum":1,"maximum":100,"default":25},"cursor":{"type":"string","maxLength":4096}}}
```

```json
{"provider":"gcp","scope":"projects/acme-stage","mode":"get","resources":null,"count":1,"hiddenByPolicy":0,"truncated":false,"nextCursor":null,"masked":1,
 "resource":{"name":"//run.googleapis.com/projects/acme-stage/locations/europe-west1/services/api","assetType":"run.googleapis.com/Service","project":"projects/123456789012",
   "location":"europe-west1","state":"ACTIVE","source":"live","inventoryUpdateTime":"2026-10-22T11:58:02Z",
   "data":{"terminalCondition":{"type":"Ready","state":"CONDITION_SUCCEEDED"},"template":{"containers":[{"env":[{"name":"DB_PASS","value":"‹masked›"}]}]}}}}
```

```json
{"type":"object","additionalProperties":false,"required":["connection"],"properties":{
 "connection":{"type":"string","enum":["gcp-stage"]},"project":{"type":"string","enum":["acme-stage"]},"action":{"enum":["query","list_metrics","alerts"],"default":"query"},
 "metric":{"type":"string","maxLength":512,"pattern":"^[a-z0-9.-]+\\.(googleapis\\.com|io)/[A-Za-z0-9_/.-]+$","examples":["run.googleapis.com/request_count","kubernetes.io/container/cpu/core_usage_time"]},
 "filter":{"type":"string","maxLength":2000,"examples":["resource.labels.service_name = \"api\" AND metric.labels.response_code_class = \"5xx\""],"description":"ANDed with the metric; no metric.type or project here"},
 "from":{"type":"string","default":"-1h"},"to":{"type":"string","default":"now"},"period":{"type":"integer","enum":[60,300,900,3600,21600,86400]},
 "aligner":{"enum":["ALIGN_MEAN","ALIGN_MAX","ALIGN_MIN","ALIGN_SUM","ALIGN_RATE","ALIGN_DELTA","ALIGN_COUNT","ALIGN_PERCENTILE_99","ALIGN_PERCENTILE_95","ALIGN_PERCENTILE_50"],"description":"Default: ALIGN_MEAN for GAUGE, ALIGN_RATE for DELTA and CUMULATIVE"},
 "reducer":{"enum":["REDUCE_NONE","REDUCE_SUM","REDUCE_MEAN","REDUCE_MAX","REDUCE_MIN","REDUCE_COUNT","REDUCE_PERCENTILE_99","REDUCE_PERCENTILE_95","REDUCE_PERCENTILE_50"],"default":"REDUCE_NONE"},
 "groupBy":{"type":"array","maxItems":5,"items":{"type":"string","pattern":"^(metric|resource)\\.labels\\.[a-z0-9_]+$"}},
 "prefix":{"type":"string","maxLength":256,"description":"list_metrics"},"state":{"enum":["OPEN","CLOSED"],"default":"OPEN","description":"alerts"},
 "limit":{"type":"integer","minimum":1,"maximum":100,"default":20,"description":"query: max series"},"cursor":{"type":"string","maxLength":4096}}}
```

```json
{"title":"GCP","type":"object","required":["scopes","credentials"],"properties":{
 "scopes":{"type":"array","minItems":1,"maxItems":20,"uniqueItems":true,"title":"Projects, folders or organizations",
   "items":{"type":"string","pattern":"^(projects/([a-z][a-z0-9-]{4,28}[a-z0-9]|\\d{1,19})|folders/\\d{1,19}|organizations/\\d{1,19})$"},"examples":[["projects/acme-stage"]]},
 "metricsProjects":{"type":"array","maxItems":20,"items":{"type":"string","pattern":"^[a-z][a-z0-9-]{4,28}[a-z0-9]$"},"description":"Default: the projects in scopes"},
 "credentials":{"type":"object","x-gateway-credential":"gcp","required":["source"],"properties":{
   "source":{"enum":["adc","serviceAccountKey"],"default":"adc","x-enumTitles":["Google sign-in on this computer (gcloud auth application-default login)","Service account key (JSON)"]},
   "serviceAccountKey":{"type":"string","writeOnly":true,"x-widget":"textarea","maxLength":10000},
   "impersonate":{"type":"string","pattern":"^[a-z0-9-]+@[a-z0-9-]+\\.iam\\.gserviceaccount\\.com$","examples":["gw-reader@acme-stage.iam.gserviceaccount.com"]},
   "delegates":{"type":"array","maxItems":5,"items":{"type":"string"}},"lifetimeSeconds":{"type":"integer","minimum":300,"maximum":3600,"default":900},
   "quotaProject":{"type":"string","description":"★ Billed project (x-goog-user-project); needs serviceusage.services.use"}}},
 "liveGet":{"type":"boolean","default":true,"title":"★ Read current state from service APIs"}}}
```

```json
{"title":"AWS","type":"object","required":["regions","credentials"],"properties":{
 "regions":{"type":"array","minItems":1,"maxItems":10,"uniqueItems":true,"items":{"type":"string","pattern":"^[a-z]{2}(-[a-z]+)+-\\d$"},"examples":[["eu-central-1"]]},
 "accountId":{"type":"string","pattern":"^\\d{12}$","description":"★ Filled by Test connection; calls fail if the account changes"},
 "accountAlias":{"type":"string","maxLength":63,"examples":["acme-stage"],"description":"★ Display only"},
 "credentials":{"type":"object","x-gateway-credential":"aws","required":["source"],"properties":{
   "source":{"enum":["accessKey","profile"],"default":"accessKey"},"accessKeyId":{"type":"string","pattern":"^AKIA[A-Z0-9]{16}$"},
   "secretAccessKey":{"type":"string","writeOnly":true,"format":"password"},"profile":{"type":"string","maxLength":128},
   "roleArn":{"type":"string","pattern":"^arn:aws(-cn|-us-gov)?:iam::\\d{12}:role/[\\w+=,.@/-]{1,512}$","examples":["arn:aws:iam::412398765012:role/gw-readonly"]},
   "externalId":{"type":"string","pattern":"^[\\w+=,.@:/-]{2,1224}$","x-generated":"gw-ext-<12 hex>","description":"Not a secret. Add it to the role's trust policy as sts:ExternalId"},
   "sessionSeconds":{"type":"integer","minimum":900,"maximum":3600,"default":900},
   "sessionPolicy":{"enum":["generated","none"],"default":"generated","x-stricter":"prod:generated","x-enumTitles":["★ Read only, enforced by AWS (recommended)","The role's permissions as they are"]}}}}}
```

```json
{"allowedServices":["ec2","ecs","rds","sqs","cloudwatch"],"regions":["eu-central-1"],
 "resources":["arn:aws:ecs:eu-central-1:*:service/stage/*","arn:aws:rds:eu-central-1:*:db:stage-*","arn:aws:sqs:eu-central-1:*:stage-*"],
 "maxRangeDays":14,"maxPoints":2000,"maxItems":100}
```
