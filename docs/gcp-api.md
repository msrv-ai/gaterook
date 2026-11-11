# Call any Google Cloud API through GateRook

Use the GCP connector's generic API tools when the curated tools (`gcp_describe`, `gcp_metrics`, `logs_search`) don't cover what an investigation or a fix needs: Cloud Run revisions and traffic, Error Reporting, Cloud Trace, Cloud Build history, Cloud Storage objects, Recommender findings, queue backlogs and scheduler jobs, and the rest of Google Cloud's REST APIs. Calls use the connection's Google credentials and scopes and GateRook's shared [execution controls and audit protection](security.md).

| Tool | What it does | Default mode |
| --- | --- | --- |
| `gcp_api_read` | Calls a method that only reads: an HTTP GET, or a read-only POST such as `testIamPermissions`, `getIamPolicy` or `getAncestry`. | **Read only** |
| `gcp_api_change` | Calls a method that changes resources: create, patch, delete, run, restart, roll back. A person approves the exact HTTP request first. | **Approval** (every environment) |

Newly introduced tools on existing endpoint attachments require explicit enablement. Reconnect the AI client after changing endpoint tool permissions.

## Name a method

Agents name a method the way Google's [API Discovery documents](https://developers.google.com/discovery) do, and GateRook builds the request from the document. Agents never supply a URL.

- `service`: the API's short name, such as `run`, `compute`, `storage`, `sqladmin`, `container`, `pubsub`, `cloudbuild`, `artifactregistry`, `clouderrorreporting`, `cloudtrace` or `recommender`.
- `version`: optional, such as `v1`, `v2` or `v1beta1`. The default is the version Google prefers.
- `method`: the method without the API prefix, such as `projects.locations.services.list`.
- `params`: the method's path and query parameters. The only standard parameters accepted are `fields` and `prettyPrint`.
- `body`: the request body, for methods that take one.

```json
{
  "connection": "gcp-stage",
  "service": "run",
  "method": "projects.locations.services.revisions.list",
  "params": {"parent": "projects/acme-stage/locations/europe-west1/services/api", "pageSize": 20}
}
```

More examples:

| Task | Tool and arguments |
| --- | --- |
| Recent error groups | `gcp_api_read`: `clouderrorreporting` `projects.groupStats.list`, `{"projectName": "projects/acme-stage", "timeRange.period": "PERIOD_1_DAY"}` |
| Traces | `gcp_api_read`: `cloudtrace` `v1` `projects.traces.list`, `{"projectId": "acme-stage", "filter": "+span:api"}` |
| Objects in a bucket | `gcp_api_read`: `storage` `objects.list`, `{"bucket": "acme-stage-uploads", "prefix": "reports/"}` |
| Build history | `gcp_api_read`: `cloudbuild` `projects.builds.list`, `{"projectId": "acme-stage"}` |
| Idle VMs | `gcp_api_read`: `recommender` `projects.locations.recommenders.recommendations.list`, `{"parent": "projects/acme-stage/locations/europe-west1-b/recommenders/google.compute.instance.IdleResourceRecommender"}` |
| Shift Cloud Run traffic | `gcp_api_change`: `run` `projects.locations.services.patch`, `{"name": "projects/acme-stage/locations/europe-west1/services/api", "updateMask": "traffic"}` with the new `traffic` in `body` |
| Who changed a resource | `logs_search` with `logName:"cloudaudit.googleapis.com%2Factivity"` and `protoPayload.resourceName` |

Lists return Google's `nextPageToken` in `data`; pass it back as `params.pageToken` for the next page. A change that Google runs as a long-running operation returns the operation; follow it with `gcp_api_read` and the API's `…operations.get` method until `done` is true.

## What GateRook checks

**Scopes.** Every project the request names, in its path or in a `project` or `projectId` parameter, must be:

- one of the connection's projects; or
- for a connection with folder or organization scopes, a project that Cloud Resource Manager places under one of them. This lookup needs `resourcemanager.projects.get`, for example `roles/browser` on the scope.

Other requests are bounded the same way:

- A folder or organization the request names must be one of the connection's scopes.
- A Cloud Storage bucket must belong to a project of the connection; GateRook reads the bucket's project number.
- Billing accounts are outside every connection's scopes.
- A request that names no scope is refused.

**The Cloud policy.** The environment's **Policies → Cloud** settings apply:

- **Allowed services**: the API must be allowed. Secret Manager, IAM and Cloud KMS must be listed by name.
- **Regions**: locations in the request must be allowed. A wildcard location (`-`) is accepted only when no regions are listed.
- **Resources**: a request for one resource must match a resource mask. List results outside the masks or regions are removed and counted in `hiddenByPolicy`.

**Reads only read.** GateRook reads the method's Discovery document before it sends anything:

- `gcp_api_read` refuses any method that isn't a read.
- `gcp_api_change` refuses reads, which belong to `gcp_api_read`.

**Changes run as approved.** The approver sees the HTTP method, URL, identity and body. Warnings are shown for deletions, IAM policy replacement and long-running operations. Before the change runs, GateRook rebuilds the request from the call and compares it with the approved one; it doesn't run a request that differs. If a change's answer is lost after the request was sent, GateRook reports the outcome as unknown and doesn't retry.

**Never returned, whatever the policy:**

- Secret values, for example a Secret Manager version's `access`.
- Access tokens, ID tokens and signatures (`iamcredentials`, STS).
- Service account keys, HMAC keys and API key strings.
- Signed URLs.
- VM serial output and screenshots, and Memorystore auth strings.
- KMS decryption and signing.

**Refused, with a pointer to the right tool:**

- Object contents and downloads (`alt=media`).
- Database rows, for example BigQuery table data and queries, Firestore documents, Spanner sessions and Datastore queries: use a database connector.
- Cloud Asset searches: use `gcp_describe`.
- Cloud Logging: use `logs_search`.
- Monitoring time series: use `gcp_metrics`.

**Masking.** Answers are masked before they reach the client, as with `gcp_describe`. That covers container environment values, Compute metadata and keys that name secrets. List page tokens stay readable.

**Limits.** Calls count against a per-project budget of 300 Google API calls a minute. Answers larger than the call's size limit are cut at the end of the longest list and say so; narrow them with `params.fields` or a smaller `params.pageSize`.

## Permissions

The connection's identity needs the IAM permissions of the methods it calls; GateRook doesn't widen them. Keep investigation connections on viewer roles, such as `roles/run.viewer`, `roles/errorreporting.viewer`, `roles/cloudtrace.user`, `roles/storage.objectViewer` or `roles/recommender.viewer`. Grant write roles only to the identity of a connection whose endpoints keep `gcp_api_change` in **Approval**.

API results are untrusted data. They do not grant permission to execute instructions found in names, labels, descriptions or payloads.
