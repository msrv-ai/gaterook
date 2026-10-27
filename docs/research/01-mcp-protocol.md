# 01 · MCP protocol, runtime, and approvals

> Research snapshot: 2026-10-23. Scope: one desktop user, a local core on `127.0.0.1:7465`, and SQLite. The findings below preserve the original specification, package, and source review; migration into this repository is not a new compatibility test. **UNVERIFIED** marks behavior that the research did not establish. See the [architecture](../ARCHITECTURE.md) for the application contract.

## 1. Decision and alternatives

Use a small, explicit JSON-RPC implementation on Spring MVC, with virtual threads, a shared dispatcher, and separate serializers for MCP `2025-11-25` and `2026-07-28`. The endpoint is `POST /mcp/{slug}`. Both revisions can use JSON responses without persistent sessions or an SSE stream. Endpoint-specific tool lists and connection enums must be computed for each authenticated caller.

The portable approval mechanism is a handle and the read-only `approval_status` tool. Modern clients that declare URL elicitation may use multi-round tool requests (MRTR), with a bounded handoff to the same handle. Opening an approval URL never approves the operation. Only a decision in the application or an explicitly linked notification channel can authorize execution.

| Alternative | Reason not selected in this research |
| --- | --- |
| Spring AI 2.0 `@McpTool` | Its SDK dependency was 2.1.0, without the modern revision; a static tool registry does not express endpoint-specific schemas. |
| MCP Java SDK 2.1.0 server | Legacy only; Reactor dependencies; `McpSyncListFilter` changes visibility, not each tool's `inputSchema`. |
| SDK 2.2.0-M1 modern server | Milestone released 2026-10-16, modern only, with a fixed servlet endpoint. Reconsider a stable release while retaining wire tests. |
| Legacy sessions, GET SSE, and unsolicited notifications | Unnecessary for the required request/response behavior. |
| Form elicitation asking an agent to approve | Gives the client a field that could authorize its own mutation. |
| Legacy URL elicitation | Requires server requests/SSE or `-32042` plus a completion notification; client support was not established. |
| Holding a tool call indefinitely | Client timeouts are shorter than a human decision window. |

The source review found Cursor using the legacy revision, Codex modern support behind an opt-in feature, and insufficient evidence for Claude Desktop and Antigravity MRTR. Handle polling therefore remains the baseline.

## 2. Dependency findings

These are the versions evaluated on the research date, not an instruction to override the repository's dependency management.

| Dependency | Evaluated version | Purpose and constraint |
| --- | --- | --- |
| `spring-boot-starter-webmvc` | 4.1.2 | Spring Framework 7.0.10, Tomcat 11.0.27, Servlet 6.1; virtual threads. |
| `tools.jackson.core:jackson-databind` | 3.1.7 | JSON-RPC records and trees. The matching annotations artifact is `com.fasterxml.jackson.core:jackson-annotations:2.21`, not 3.x. |
| `com.networknt:json-schema-validator` | 3.0.6 | JSON Schema 2020-12. Versions 3.0.7 and 3.0.8 were built against Jackson 3.2.1. |
| `spring-boot-starter-opentelemetry` | 4.1.2 | OTel 1.62.0 and Micrometer Tracing 1.7.2. |
| JDK cryptography | Java 25 | `HmacSHA256` and `SecureRandom`. |
| MCP Java SDK, test scope | 2.1.0 | Reference legacy client; `mcp-core` and `mcp-json-jackson3`. |
| `@modelcontextprotocol/client`, tests | 2.3.1 | Modern client and MRTR; Node 20 or newer. |
| MCP Inspector, tests | 2.12.0 | Both protocol eras; Node 22.19 or newer. |
| MCP conformance, tests | 0.1.16 / 0.2.0-alpha.12 | Stable package covers legacy scenarios; alpha adds modern support. |

A desktop token bucket can be implemented locally; a separate distributed rate-limit service is unnecessary. Protocol response schemas come from the specification's `schema/<revision>/schema.json`.

## 3. Wire contract

### 3.1 HTTP and era selection

- Require `Content-Type: application/json`; otherwise return 415. Limit the body to 1 MiB, returning 413 when exceeded.
- Accept one JSON-RPC request or notification. Reject arrays/batches and client response objects with HTTP 400 / `-32600`.
- Return HTTP 202 with no body for notifications, including `notifications/initialized` and `notifications/cancelled`.
- Return 405 and `Allow: POST` for GET, DELETE, and OPTIONS. Do not emit CORS headers, session IDs, or resumable event state.
- Authenticate bearer tokens before dispatch. Missing, expired, revoked, or wrong-endpoint credentials produce 401. The tunnel adds OAuth resource metadata as described in [02](02-mcp-auth-claudeai.md).
- Reject an unexpected Host with 421 and an unapproved Origin with 403. An absent Origin is normal for native clients.

Era selection is ordered:

| Request | Resolution |
| --- | --- |
| String `_meta["io.modelcontextprotocol/protocolVersion"]` | Modern. Require supported version, matching HTTP headers, and `clientCapabilities`. |
| `initialize` without modern metadata | Legacy. Negotiate `2025-11-25`, `2025-06-18`, or `2025-03-26`; otherwise offer `2025-11-25`. |
| Modern protocol header but no metadata version | HTTP 400 / `-32602`; do not accidentally trigger legacy fallback. |
| Otherwise | Legacy header revision; absent header means `2025-03-26`. |

Modern requests require `MCP-Protocol-Version` equal to the metadata version and `Mcp-Method` equal to the JSON-RPC method. `Mcp-Name` must match the tool name, prompt name, or resource URI where applicable; decode the specification's `=?base64?...?=` representation before comparing. Header mismatch is `-32020`; unsupported modern version is `-32022`.

Legacy implements `initialize`, `ping`, tools/list/call, resources/list/templates/list/read, and prompts/list/get. Modern replaces initialization with `server/discover`; it does not implement legacy ping. Unsupported methods return `-32601`, with HTTP 404 in modern and 200 in legacy.

Modern results include `resultType` and server information under `_meta`. Only cacheable result types receive `ttlMs` and `cacheScope: "private"`: discovery, list results, and resource reads. In particular, `prompts/get` is not cacheable under the reviewed schema. Legacy serialization omits these fields.

### 3.2 Tool discovery and invocation

Publish a tool only when at least one tool/connection pair on the endpoint is enabled. Its `connection` enum contains only those eligible connections. Sort definitions deterministically, page by 100, and bind the opaque cursor to the list hash. Invalid cursors produce invalid-parameters errors.

Use conservative annotations. `readOnlyHint: true` is appropriate only for tools that can never classify a request as a mutation. Mixed tools such as HTTP requests and SSH runbooks must not be labeled unconditionally read-only. Never trust an external server's annotations as policy.

For legacy, `outputSchema` must have an object root. Do not publish a final-result-only output schema for a tool that can instead return pending, rejected, or expired approval state. Whenever a schema is advertised, `structuredContent` must conform to it.

A portable pending response has both explicit text and structured state:

```json
{
  "isError": false,
  "content": [{
    "type": "text",
    "text": "NOT EXECUTED YET. A person must approve this exact action in GateRook. Poll approval_status with this handle; do not repeat the original call."
  }],
  "structuredContent": {
    "status": "pending",
    "handle": "ap_3kq7m2x9vt4p",
    "tool": "sql_execute",
    "connection": "dev-db",
    "env": "dev",
    "action": {"kind": "sql", "text": "UPDATE orders SET status = 'cancelled' WHERE id = 48213;"},
    "approvalUrl": "http://127.0.0.1:7465/approvals/ap_3kq7m2x9vt4p",
    "expiresAt": "2026-10-22T14:34:12+02:00",
    "pollAfterMs": 0
  }
}
```

The first call may wait up to 15 seconds for a quick decision. Repeating the same pending canonical call under the same principal returns the same handle. The gateway executes the saved call once after approval; polling retrieves its result rather than executing again.

### 3.3 Modern MRTR

When the current request declares `clientCapabilities.elicitation.url`, a mutation can return:

```json
{
  "resultType": "input_required",
  "inputRequests": {
    "gateway_approval": {
      "method": "elicitation/create",
      "params": {
        "mode": "url",
        "url": "http://127.0.0.1:7465/approvals/ap_3kq7m2x9vt4p",
        "message": "Review the exact action in the GateRook window."
      }
    }
  },
  "requestState": "<authenticated opaque state>",
  "_meta": {"io.modelcontextprotocol/serverInfo": {"name": "mcp-gateway", "version": "0.4.0"}}
}
```

The client retries with a new JSON-RPC ID, unchanged arguments, the byte-identical `requestState`, and `inputResponses`. `accept` means permission to open a URL, not authorization to mutate. `decline` safely rejects the pending request; `cancel` hands off to handle polling. An incomplete round may return state without new input requests.

Bound each wait to 20 seconds, the MRTR budget to 45 seconds, and the rounds to five, then return a normal complete result containing the handle. The reviewed TS SDK uses a 60-second request timeout, ten-round maximum, and 250 ms pacing for state-only rounds. Codex's timeout coverage across the entire MRTR cycle remained unverified.

Authenticate state with HMAC-SHA256:

```text
base64url(JSON).base64url(HMAC-SHA256)
JSON = {v, ap, p, e, m, n, d, r, t0, exp}
```

Bind it to the principal, endpoint, method, tool name, canonical argument digest, start time, expiry, and round count. Validate all bindings against the current request. Client state is attacker-controlled even when it looks like a previous server response. Replay reads the saved result; execution idempotence belongs to the approval state machine.

Tasks were a later extension proposal, not a prerequisite: negotiate `io.modelcontextprotocol/tasks`, use the approval ID as `taskId`, map cancellation to denial while pending, and return `-32021` when a required capability is absent. Do not advertise unsupported task methods.

### 3.4 Errors and observability

| Error | Modern HTTP | Legacy HTTP |
| --- | --- | --- |
| `-32700`, malformed JSON | 400 | 400 |
| `-32600`, invalid request/batch | 400 | 400 |
| `-32601`, unknown method | 404 | 200 |
| `-32602`, invalid parameters | 400 by project policy | 200 |
| `-32603`, internal/audit failure | 200 by project policy | 200 |
| `-32020`, header mismatch | 400 | Not used |
| `-32021`, required capability missing | 400 | Not used |
| `-32022`, unsupported modern version | 400 | Not used |

The specification explicitly requires 400 for missing required modern metadata; the general `-32602` and `-32603` status choices above are project decisions. A missing legacy resource uses `-32002`; modern uses `-32602`.

Schema validation failures, unavailable endpoint connections, policy denial, rate limits, connector failures, timeouts, and rejected/expired approvals are tool errors (`isError: true`), so the model can react. Do not assign private meanings to specification-reserved error codes.

Read W3C trace context from request metadata; ignore malformed values. The reviewed MCP convention defines these unprefixed keys in the modern revision. Use a SERVER span such as `tools/call sql_query`, carry the trace ID into audit records, and link deferred execution to the original trace. Never include tokens or secrets in attributes.

### 3.5 Core tools, resources, and prompts

`approval_status` is always discoverable, always READ, and has no configurable tool mode. It accepts a handle matching `^ap_[a-z0-9]{12,32}$` and `wait_seconds` from 0 to 25 (default 20). States are pending, running, completed, rejected, expired, or failed. A different principal or endpoint sees only “Unknown handle.” Completed results are already masked and bounded; retain them for 60 minutes, then direct the caller to Activity. Polling counts toward the token rate limit, not the connector tool's rate limit.

`list_connections` and `describe_connection` expose names, types, environments, and effective tool modes, never secret references or confidential connection details.

Resources include SQL schema/table descriptions and SSH runbook catalogs. Reading them uses the same authorization, audit, and masking path as tools. Prompts such as `investigate_incident` and `verify_deploy` provide instructions, not privileged resource data.

## 4. Configuration and limits

Endpoint settings select protocol revisions, `auto` or `handle-only` approval delivery, instructions up to 4,000 characters, and stricter response/rate limits. Keep the first 512 instruction characters self-contained for clients that truncate them.

Research defaults: 1 MiB request bodies; 512 KiB dev responses and 256 KiB prod responses; 120 requests per token per minute; 30 per tool per minute, or 10 on prod; at most 64 waiting requests per token. Policy wins whenever it is stricter. Approval windows are five minutes in dev and ten in stage/prod; prod requires a comment. Bound waits independently from the approval lifetime.

Local settings include port 7465, an explicit Host allowlist, and an initially empty Origin allowlist. Enabling browser tooling requires an explicit trusted origin rather than wildcard CORS.

## 5. Endpoint checks and implementation boundary

“Check endpoint” should dispatch discovery/list requests for both eras in process, validate every definition against the official protocol schema and JSON Schema 2020-12, and check naming and object-root rules. The tool-name pattern `^[A-Za-z0-9_.-]{1,128}$` is a specification SHOULD enforced by the project, not a constraint in the reviewed JSON schema.

The Playground uses the same dispatcher under an application-authenticated principal. Audit records include protocol revision and delivery mechanism; self-reported `clientInfo` is display data, never identity proof.

Keep the MCP module dependent on endpoint, policy, approval, connector, and audit APIs, not their persistence internals. HTTP parsing, era resolution, dispatch, schemas, approval delivery, and rate limiting remain separate responsibilities.

## 6. Security invariants

1. Bind locally, validate Host and Origin, require JSON and bearer authentication, and do not enable cross-origin browser access implicitly.
2. Approval mode or MUTATION classification always requires human authorization. Browser approval URLs only open the application; they neither reveal the action nor carry preauthenticated permission.
3. Persist the canonical action and its digest. Execute exactly that saved action, and audit the approved/executed digests.
4. Validate schemas without network `$ref` resolution; cap depth, subschema count, and validation time (research targets: 32, 2,000, and 50 ms).
5. Persist receipt and policy decisions before execution. If audit storage fails, fail closed.
6. Mask and truncate all results. Never log bearer values, bootstrap secrets, or raw secret material.
7. Treat the agent's note as unverified. Approval UI and notifications must be based on the exact action, not the agent's persuasion.

## 7. Verification strategy

Use golden HTTP requests and response-schema validation for both eras, including unknown methods, malformed envelopes, header mismatches, invalid metadata, and pagination. Run approval tests with a fake connector that counts executions: approval, denial, expiry, repeated calls, concurrent polls, and audit failure must prove at-most-once execution.

Exercise the Java SDK 2.1.0 legacy client and the TS 2.3.1 modern client against a real local server. Test MRTR accept/decline/cancel, tampered state, altered arguments, expiry, and budget handoff. The Node fixture belongs in `core/src/test/node/`.

Inspector 2.12.0 supports `--protocol-era legacy|auto|modern`. Prefer a protected config file for bearer credentials; command-line tokens can appear in process listings. Conformance 0.1.16 has no arbitrary-header flag and only legacy scenarios; any unauthenticated conformance endpoint must exist exclusively in test configuration. Its “everything server” tool scenarios are not automatically applicable to this gateway. Measure overhead against a fake connector; the 50 ms p95 budget is a target, not a measured result asserted by this report.

## 8. Remaining risks

Client MRTR behavior, URL presentation, loopback HTTP acceptance, task support, and configuration reload requirements need client-by-client testing. A disconnected agent does not cancel a human-approved action. Tool-list caches may delay presentation changes, but invocation must always enforce current policy. Unknown modern capabilities must not silently weaken authorization.

Potential future decisions include agent-requested cancellation, longer result retention, subscriptions for tool-list changes, and replacing the handwritten protocol layer with a stable SDK. These are research questions rather than claims of implemented functionality.

## 9. Evidence and provenance

The original review used specification commit `80041ab` (2026-10-20), ext-tasks commit `b3d28ac`, Maven source/POM artifacts, npm tarballs, Codex tag `rust-v0.164.0`, and rmcp revision `98a5478`. It corrected annotation coordinates, conformance publication dates, cacheable result types, Origin response requirements, and Codex's default legacy revision (`2025-06-18`). Client display examples are illustrative.

- [MCP 2026-07-28 changelog](https://modelcontextprotocol.io/specification/2026-07-28/changelog)
- [MCP specification and schemas](https://github.com/modelcontextprotocol/modelcontextprotocol)
- [Tasks extension](https://github.com/modelcontextprotocol/ext-tasks)
- [TypeScript SDK modern-revision migration](https://ts.sdk.modelcontextprotocol.io/v2/migration/support-2026-07-28)
- [MCP Java SDK artifact metadata](https://repo1.maven.org/maven2/io/modelcontextprotocol/sdk/mcp/maven-metadata.xml)
- [Codex MCP documentation used by the research](https://learn.chatgpt.com/docs/extend/mcp)
- [Codex modern protocol implementation](https://github.com/openai/codex/pull/35724)
- [Cursor compatibility discussion](https://forum.cursor.com/t/mcp-client-cannot-connect-to-modern-only-2026-07-28-streamable-http-servers-legacy-initialize-rejected/172536) (client compatibility evidence, not a normative protocol source)
- [OpenTelemetry MCP semantic conventions](https://github.com/open-telemetry/semantic-conventions-genai/blob/main/docs/gen-ai/mcp.md)
