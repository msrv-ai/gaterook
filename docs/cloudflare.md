# Control AI access to Cloudflare

GateRook's MCP Firewall lets AI apps read a Cloudflare account — zones and DNS records, Workers with their logs and KV, Pages, security and traffic analytics, tunnels and Logpush jobs — and holds every change for your review: a DNS record, a cache purge, a Worker deployment, a Pages retry or rollback. The API token's permissions remain the boundary; GateRook's checks narrow what agents may ask for within them. The [security model](security.md) explains execution controls, explicit Bypass, and the append-only, tamper-evident audit trail.

## Connect with an API token

The built-in **Cloudflare** connector (`cloudflare`) calls Cloudflare's v4 API and its GraphQL Analytics API at `https://api.cloudflare.com/client/v4` over HTTPS. There is no Cloudflare SDK in GateRook. Agents never pass a URL, GraphQL text or code: GateRook builds every request from checked arguments, redirects are never followed and no proxy is used.

- **Account ID**: the 32-character ID of the account. Workers, Workers Logs, KV, Pages, tunnels and account Logpush jobs are read in this account, and every zone a call reaches must belong to it.
- **Zones**: the zone names agents may use, such as `example.com`. Empty: every zone of the account that the token can see. Agents name a zone by name or by its ID; a zone outside the list is refused before any request about it.
- **API token**: a user or account API token, stored encrypted in GateRook. Create it under **My Profile → API Tokens** or **Manage Account → Account API Tokens**, limited to this account and the zones you list. The Global API Key is not accepted.

Give the token read permissions for the families agents should read, and add an Edit permission only for the changes you want agents to be able to request:

| Tools | Permission |
| --- | --- |
| `cloudflare_zones` | Zone · Zone · Read |
| `cloudflare_dns_records`, `cloudflare_dns_change` | Zone · DNS · Read; DNS · Edit to change records |
| `cloudflare_cache_purge` | Zone · Cache Purge · Purge |
| `cloudflare_analytics` | Zone · Analytics · Read |
| `cloudflare_logpush_jobs` | Account or Zone · Logs · Edit: Cloudflare requires it to list jobs, and GateRook only reads them |
| `cloudflare_workers`, `cloudflare_workers_deploy` | Account · Workers Scripts · Read; Edit to deploy. Routes need Zone · Workers Routes · Read |
| `cloudflare_workers_logs` | Account · Workers Observability · Edit: Cloudflare requires it to run log queries |
| `cloudflare_kv` | Account · Workers KV Storage · Read |
| `cloudflare_pages`, `cloudflare_pages_deployment` | Account · Cloudflare Pages · Read; Edit to retry or roll back |
| `cloudflare_tunnels` | Account · Cloudflare Tunnel · Read |

**Test connection** verifies the token (`/user/tokens/verify`, or `/accounts/{id}/tokens/verify` for an account token) and lists the accounts and the zones of the account it can see. It then reads once per family — DNS, Workers, KV, Pages, tunnels, Logpush, analytics — and reports which tools will work. When the token may read its own policies, it reports the Edit permissions it holds: a token that can change resources is a warning on prod, and one that can create API tokens is a danger. A token that expires within two weeks is a warning. Health verifies the token. The zones the test found are recorded, so a zone given by ID is checked against the list before any request.

## Read the account

| Tool | What it reads | Default mode |
| --- | --- | --- |
| `cloudflare_zones` | The account's zones with status, plan and name servers; or one zone with its DNSSEC status | Read only |
| `cloudflare_dns_records` | A zone's records, filtered by type, name, content, search or proxied; or one record | Read only |
| `cloudflare_workers` | Workers; one Worker's settings and bindings; its versions and deployments; a zone's Worker routes; custom domains | Read only |
| `cloudflare_workers_logs` | Workers Logs: events of one Worker or all, by time, text and field filters, or their count by fields | Read only |
| `cloudflare_kv` | KV namespaces, the keys of one, and the value of one key (text, up to 64 KB) | Read only |
| `cloudflare_pages` | Pages projects, one project, its deployments, one deployment, and a deployment's build log | Read only |
| `cloudflare_analytics` | Security events (WAF, rate limiting and the rest), their count by dimension, and HTTP requests by dimension, through the GraphQL Analytics API | Read only |
| `cloudflare_tunnels` | Cloudflare Tunnels with their status and connections, one tunnel's connectors, a remotely managed tunnel's ingress rules | Read only |
| `cloudflare_logpush_jobs` | The Logpush jobs of the account or a zone: dataset, destination, last success and last error | Read only |

Record names may be written as Cloudflare reads them: `www.example.com`, `www` in the zone, or `@` for the zone itself. Lists page with `page` (KV keys and Workers Logs with `cursor`), and an answer larger than the call may return is cut and says so.

- **Workers Logs** queries are GateRook's own: `from` (default `-1h`) and `to`, `search` (text or an RE2 expression), up to ten field filters such as `{"key": "$metadata.statusCode", "operation": "gte", "value": 500}`, and `view: count` with `groupBy`. They are sent as dry runs, so they aren't saved among the account's queries. Workers Logs keeps events for at most seven days; the environment's longest log range applies too.
- **Analytics** queries are GateRook's own too, one fixed template per dataset (`firewallEventsAdaptive`, `firewallEventsAdaptiveGroups`, `httpRequestsAdaptiveGroups`), with dimensions from a fixed list and filter values written as GraphQL string literals. Counts of the adaptive datasets are sampled estimates on busy zones, and how far back a zone can be read depends on its plan.
- **Build logs** of Pages show the last lines within the environment's line limit.

## Review exact changes

| Tool | What it changes | Default mode |
| --- | --- | --- |
| `cloudflare_dns_change` | Creates a record, updates only the fields given, or deletes a record | Approval |
| `cloudflare_cache_purge` | Purges up to 30 files (full URLs), Cache-Tag values, prefixes (host and path) or host names of a zone, or everything | Approval |
| `cloudflare_workers_deploy` | Deploys versions that already exist: one at 100 percent to roll back or promote it, or two that add up to 100 for a gradual rollout | Approval |
| `cloudflare_pages_deployment` | Retries a deployment, or rolls production back to an earlier successful production deployment | Approval |

The approval shows the exact HTTP request — method, URL with the zone's ID, and the JSON body — with the token as `‹masked›`, and what it changes, read from Cloudflare when the request is prepared:

- a DNS change shows the record before and after, and for a new record the records already at that name, with a warning when a CNAME would share its name;
- a purge of everything warns that the origin serves every request until the cache fills again;
- a deployment shows the deployment that serves traffic now and each version's number, date and message;
- a Pages rollback shows the production deployment that is live now and the one that becomes live.

What runs is the approved request, built again from the call and compared with what was approved first. An update or deletion of a record runs only if the record's `modified_on` is still the one the approval showed, a Worker deployment only if no other deployment happened since, and a Pages rollback only while production is still the deployment the approval showed; otherwise nothing is sent and the agent is asked to read again. Every URL, prefix and host name of a purge must be in the zone. A change is never retried: a lost answer is reported as an unknown outcome, to check before trying again.

## What GateRook masks and refuses

Before the core's masker sees an answer, the connector masks values that Cloudflare returns as plain configuration but that are usually secrets:

- Worker bindings: the text of `secret_text`, the key of `secret_key`, and the text of `plain_text` or the value of `json` whose name looks like a credential (`API_KEY`, `DATABASE_URL`, `SESSION_SECRET`);
- Pages environment variables: every `secret_text` value and `plain_text` values named like a credential, and the Web Analytics token;
- Logpush destinations: credentials in their query (`secret-access-key`, `header_Authorization`, a SAS `sig`) and long opaque path segments, keeping the scheme, bucket or host and harmless options;
- credentials in the query of request URLs in logs and security events, such as `?token=`;
- everywhere, text under keys that name a secret, such as `token`, `tunnel_secret` or `password`.

GateRook never reads Worker script code, Worker secrets, tunnel or management tokens, Pages upload tokens, or values of KV keys named like credentials (`*token*`, `*secret*`, `.env`, `*.pem` and similar). R2 objects and D1 databases are outside this connector. Cloudflare's own remote MCP server takes generated code in its `execute` tool, so GateRook can't classify those calls one by one: if you add it as an external MCP server, keep `execute` in Approval.

GateRook takes a share of the account's API budget per minute (Cloudflare allows 1,200 requests per five minutes for each user, shared with the dashboard and other tools) and refuses a call when it is used up.

## Verification limits

No Cloudflare account was used to build or test this connector. Against api.cloudflare.com only the answers to a token it doesn't know were checked: the v4 error envelope, 401 with code 1000 from both token verification endpoints, 403 with code 9109 from a zone list, and 401 with code 10000 from the GraphQL endpoint. The tests run against a fake of the v4 API and the GraphQL endpoint and check the requests, the zone checks, masking, paging, approvals, the checks before a change runs, refusals and error mapping. Request shapes follow Cloudflare's published OpenAPI schemas (`github.com/cloudflare/api-schemas`, November 2026). How Cloudflare itself behaves is not verified: the GraphQL field and filter names of each dataset and which plans may read how far back, the answers of Workers Logs queries, the order of a Worker's deployments, the permission group names a token's policies use, how the two token verification endpoints treat each other's tokens, and Cloudflare's error codes. JVM checks do not establish native-image or installer compatibility.

Cloudflare documents its [API](https://developers.cloudflare.com/api/), [API tokens](https://developers.cloudflare.com/fundamentals/api/get-started/create-token/), [the GraphQL Analytics API](https://developers.cloudflare.com/analytics/graphql-api/), [Workers Logs](https://developers.cloudflare.com/workers/observability/logs/workers-logs/), [Worker deployments](https://developers.cloudflare.com/workers/configuration/versions-and-deployments/), [purging the cache](https://developers.cloudflare.com/cache/how-to/purge-cache/) and [rate limits](https://developers.cloudflare.com/fundamentals/api/reference/limits/).
