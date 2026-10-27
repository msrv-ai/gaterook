# 11 · Extensibility: Java plugins and external MCP servers

Research date: **2026-10-23**. This report records the initial extensibility design and its evidence. **The in-core PF4J proposal is superseded by [research 16](16-plugin-host.md): Java plugins run in separate JVM hosts so the core can be a native executable.** Catalog verification, tool provenance, approval, snapshots, and lifecycle requirements remain relevant. Use the [architecture](../ARCHITECTURE.md) for the integrated design.

## 1. Decisions and alternatives

Use PF4J **3.16.0** without pf4j-spring for plugin lifecycle and compatibility metadata. The initial prototype used one manager per plugin generation (`id@version`), because a PF4J manager cannot load two versions with the same plugin ID simultaneously. The later host design preserves generation isolation at the process boundary.

Read `mcp-gateway-plugin.json` directly with `ZipFile` before loading classes. The manifest contains the connector class, compatibility requirements, schemas, tools, and declared access. This makes Check plugin a metadata inspection rather than code execution and avoids reliance on annotation processors, which javac stopped enabling implicitly in JDK 23.

Use a signed catalog index: Ed25519 over the **raw bytes** of `index.json`, with artifact SHA-256 and byte size in each entry. The application pins trusted public keys. Signature verification establishes artifact provenance, not a security audit of the code.

External servers use MCP Java SDK **2.1.0**, stdio or Streamable HTTP, with gateway-owned process supervision, schema snapshots, tool name mapping, and approval policy. The recorded client worked with Jackson 3.1.7 and Reactor 3.8.8 under Spring Boot 4.1.2. Dependency versions and protocol support are historical observations; current manifests define executable behavior.

Rejected choices included pf4j-spring's unnecessary child Spring contexts and older Spring dependency line; handwritten plugin loading that duplicates lifecycle/semver handling; JAR-signing verification with partially unsigned entries and certificate-chain complexity; minisign's extra hash dependency; and Sigstore's larger trust/bootstrap machinery for the initial catalog. Sigstore bundle verification can be offline, but signing and trust-root distribution still require their own design.

## 2. Trust and tool publication

A plugin classloader is a compatibility boundary, **not a sandbox**. JDK 24 permanently disabled SecurityManager. Neither a signature nor declared `access.hosts` prevents arbitrary Java code from accessing the user's system. The separate-process design in report 16 limits accidental coupling and enables additional OS restrictions, but must also state its actual protection level honestly.

External tools begin **Off** on endpoints. Adding one selects **Approval**. Read only requires both an upstream read-only declaration without a destructive declaration and explicit confirmation for that particular tool. Do not provide a bulk action that silently trusts every upstream annotation.

Bind read-only confirmation to the endpoint, plugin/server version, and SHA-256 of canonical `{name, description, inputSchema, outputSchema, annotations}`. A changed definition or version invalidates confirmation and returns the tool to Approval. A connector can further restrict a call to MUTATION or DENY; it cannot override the gateway's stricter mode.

Publish effective annotations to agents, while the UI separately labels upstream annotations as declared and unverified. In Approval mode, omit outputSchema because a pending-approval result cannot conform to the upstream execution schema.

The core constructs the primary approval text from the exact canonical arguments that will be sent. A plugin's `describeAction` is secondary, explicitly attributed text. Store and execute the approved body, with its digest in the audit. Escape invisible/control characters so the reviewer sees meaningful differences.

Do not forward upstream server instructions as gateway instructions. Sanitize and bound descriptions. Do not advertise roots, sampling, or elicitation client capabilities; an upstream server must not gain a path to ask the user's model or filesystem through this gateway. Resources and prompts were outside the initial proxy scope.

## 3. Names, schemas, snapshots, and results

Derive a stable configurable prefix, such as `github_` from `github-mcp`, and maintain the exposed-to-original name mapping. Reserve built-in groups and gateway control tools. Reject collisions after normalization/truncation. The design uses a conservative 64-character name limit; when truncating, include a deterministic hash suffix.

The initial prefix proposal replaced hyphens with underscores while its regex allowed no internal underscores. This inconsistency must be resolved in validation/tests rather than copied as an authoritative rule.

Persist tool snapshots in SQLite. Agents can list stored tools without starting the upstream process. On startup or `tools/list_changed`, compare definitions: new tools remain Off; removed tools disappear from endpoints with a setup audit; changed tools lose read-only confirmation and display a changed marker.

Normalize missing object-schema basics before registration, bound schemas to 64 KB, and explicitly reject unsupported conversions. Removing a draft-07 `$schema` does not automatically convert draft-07 constructs to 2020-12: tuple-style `items:[...]`, for example, can then fail validation. The SDK examined accepted explicit non-2020-12 schemas without validating them, so registration success alone was not proof of schema compatibility.

Preserve upstream `isError` and structured content through the connector result model, then apply core masking and response limits. The initial policy allowed text and resource links, bounded images to 1 MB, and replaced unsupported audio/blob resource content with explanatory notes. Translate upstream JSON-RPC failures into bounded tool errors. Default concurrency was four calls per server; queue within the invocation deadline. Server timeout overrides were bounded to 300 seconds.

## 4. Catalog and installation

The signed index records schema version, monotonically increasing sequence, generation/expiry times, artifact metadata, compatibility, and revocations. Reject bad signatures, rollback sequences, and expired indexes. Catalog unavailability must not prevent the local application from operating. Refresh checks were proposed on Catalog access after six hours and daily when updates are enabled.

Check a plugin before installation: signature status, hash/size, ID/version, compatibility, tool annotations, connection schema, and declared hosts/commands. An arbitrary local or URL artifact without a matching trusted index is Unsigned. Installing or re-enabling risky code requires an explicit danger confirmation with Cancel as the initial focus and a setup audit. Revocation disables the affected artifact and explains why.

Store versioned plugin artifacts with owner-only permissions. Recompute hashes at load time; refuse files changed on disk. Updates receive the same checks plus manifest/tool diffs. New tools remain Off. Invalidate waiting approvals when code changes because those approvals described the prior generation; let already executing calls drain according to the lifecycle policy.

For catalog npm servers, install an exact version once using a catalog lockfile and `npm ci`, with lifecycle scripts disabled by default. Generate the matching package manifest as well: `npm ci` does not operate from a lockfile alone. Verify integrity against the signed entry before launch, and launch the resolved Node binary directly. This provides reproducible, offline starts. A reviewed exception is needed for packages that require install scripts.

A user-supplied command is executed as an argv array without a shell and is visibly marked Not pinned when the package/version is not fixed. Agent arguments never become process argv or environment variables.

## 5. Process supervision and credentials

Clear inherited environment variables and add only the reviewed minimal environment plus server-specific secrets. Use a dedicated working directory. A secret passed through environment remains present for the process lifetime and may be visible to other processes under the same OS user; disclose this limitation.

Start lazily on first call, stop after 15 minutes idle, and use bounded exponential restart backoff (one to 60 seconds). Five crashes in ten minutes moves the server to Failed. Stop the entire descendant tree: a generic npm command may create npm, shell, and Node descendants. Record enough process identity to avoid killing an unrelated reused PID.

Capture and mask stderr into bounded rotating logs. Non-JSON stdout is a protocol failure, not a line to silently ignore. SDK 2.1.0 logged the offending line and stopped its read loop, and graceful close alone did not provide sufficient process-tree supervision. Wrap or replace the transport where required to retain process ownership and enforce deadlines.

Find Node explicitly, check its version against the selected package, and show the resolved executable in review. GUI applications may not inherit a shell's PATH. Do not use Electron's RunAsNode mode as a hidden runtime; the release fuse disables it. OS-specific discovery and process termination behavior require platform tests.

## 6. Test connection and plugin examples

For an external server, Test connection performs a cold start, initialize, and tools/list. **It never calls an upstream tool.** Report protocol/server version, startup latency, tool count, declared annotations, pinning, and masked diagnostic output. Explain timeout, malformed stdout, unsupported protocol, missing runtime, or authentication failure. Save a successful tool snapshot.

The recorded deprecated `@modelcontextprotocol/server-github@2025.4.8` reported server version `0.6.2`, protocol `2024-11-05`, and 26 tools with **no annotations**. Package version, server version, and protocol version are separate identities. Its tools therefore could not be treated as verified reads. The older npm `0.6.2` package instead exposed nine tools and incomplete schemas. The official GitHub server was a different implementation with declared read-only hints.

`server-everything@2.0.0` required Node 24, exposed 16 annotated tools, and exercised environment and list-change scenarios. The Java client revision studied supported legacy initialize through 2025-11-25; a server that exclusively implements the incompatible 2026-07-28 initialization model needs an explicit compatibility error.

For the Jira Service Desk example, Test connection reads service-desk information, current identity, and selected permissions. Cloud uses `/rest/api/3/`; Data Center uses `/rest/api/2/`. Mutating comment/transition capabilities must be shown as warnings. SLA fields were checked against OpenAPI, not a live Jira instance.

## 7. Validation and open questions

Useful tests include manifest parsing without code execution; signed index tampering/expiry/rollback; artifact changes on disk; staged update diffs; tool-name collisions; read-only confirmation invalidation; canonical approval payloads; malformed stdout; descendant cleanup; idle shutdown; crash budgets; pinned npm integrity; and secret echo masking.

The original PF4J dual-manager/GC experiment was not preserved for the fact-review rerun. Source inspection supported the design, but this report must not present classloader collection as independently reproduced. macOS/Windows lifecycle details, JUnit-extension compatibility, live Jira behavior, some timing numbers, and all-client name limits were likewise unverified.

Remaining product decisions included catalog-key rotation and third-party publisher keys; production read-only trust policy for external tools; remote MCP OAuth; bundling Node; and compatibility policy for plugin API evolution. Report 16 resolves the major native-core/process-host question.

## 8. Primary evidence

- [PF4J 3.16.0 source](https://github.com/pf4j/pf4j/tree/release-3.16.0).
- [MCP Java SDK 2.1.0 source](https://github.com/modelcontextprotocol/java-sdk/tree/v2.1.0) and [MCP versioning](https://modelcontextprotocol.io/specification/2026-07-28/basic/versioning).
- [JEP 486: permanently disable SecurityManager](https://openjdk.org/jeps/486) and [javac annotation-processing change](https://bugs.openjdk.org/browse/JDK-8321314).
- [Official GitHub MCP server](https://github.com/github/github-mcp-server).
- [Jira Service Management OpenAPI](https://developer.atlassian.com/cloud/jira/service-desk/swagger.v3.json), [Jira Cloud platform OpenAPI](https://developer.atlassian.com/cloud/jira/platform/swagger-v3.v3.json), and [Data Center REST documentation](https://developer.atlassian.com/server/jira/platform/rest/v11000/intro/).
- [Electron fuses](https://github.com/electron/electron/blob/main/docs/tutorial/fuses.md).

## Appendix: recorded manifest and configuration examples

These examples belong to the dated proposal; the current schemas and report 16 determine the executable contract.
```json
{"type":"object","additionalProperties":false,"required":["connection","issueKey"],
 "properties":{"connection":{"type":"string","enum":["acme-jsm"]},
  "issueKey":{"type":"string","pattern":"^[A-Z][A-Z0-9_]{0,9}-[0-9]{1,9}$","description":"Request key, for example SD-123"}}}
```

```json
{"type":"object","required":["name","transport"],
 "properties":{
  "name":{"type":"string","pattern":"^[a-z][a-z0-9-]{1,31}$","description":"Also the default tool prefix"},
  "toolPrefix":{"type":"string","pattern":"^[a-z][a-z0-9]{0,14}_$"},
  "environment":{"enum":["dev","stage","prod"],"default":"dev"},
  "transport":{"enum":["stdio","http"],"default":"stdio"},
  "command":{"type":"array","items":{"type":"string","maxLength":1024},"minItems":1,"maxItems":64,
             "description":"argv; the UI line is tokenized (quotes only), never run by a shell"},
  "env":{"type":"array","maxItems":32,"items":{"type":"object","required":["name","secretRef"],
         "properties":{"name":{"type":"string","pattern":"^[A-Za-z_][A-Za-z0-9_]{0,63}$"},
                       "secretRef":{"type":"string","writeOnly":true,"description":"secret id or vault:// | gcp-sm:// | aws-sm:// reference"}}}},
  "inheritEnv":{"type":"array","items":{"enum":["HTTPS_PROXY","HTTP_PROXY","NO_PROXY","NODE_EXTRA_CA_CERTS","SSL_CERT_FILE","LANG"]},"default":[]},
  "url":{"type":"string","format":"uri","pattern":"^(https://|http://(127\\.0\\.0\\.1|localhost)[:/])"},
  "headers":{"type":"array","items":{"type":"object","required":["name","secretRef"],
             "properties":{"name":{"type":"string","pattern":"^[A-Za-z0-9-]{1,64}$"},"secretRef":{"type":"string","writeOnly":true}}}},
  "start":{"enum":["on-first-call","with-core"],"default":"on-first-call"},
  "idleStopMinutes":{"type":"integer","minimum":0,"maximum":1440,"default":15},
  "startTimeoutSeconds":{"type":"integer","minimum":5,"maximum":300,"default":30},
  "callTimeoutSeconds":{"type":"integer","minimum":1,"maximum":300,"default":60},
  "maxInFlight":{"type":"integer","minimum":1,"maximum":16,"default":4},
  "catalogEntry":{"type":"string","readOnly":true,"description":"github@0.6.2 when installed from the catalog"}},
 "allOf":[{"if":{"properties":{"transport":{"const":"stdio"}}},"then":{"required":["command"]}},
          {"if":{"properties":{"transport":{"const":"http"}}},"then":{"required":["url"]}}]}
```

```json
{"manifestVersion":1,"id":"jira-service-desk","version":"1.3.0","name":"Jira Service Desk",
 "provider":"Acme","license":"Apache-2.0","description":"Search requests and read queues. Comments and transitions wait for approval.",
 "requires":{"gateway":">=0.4.0 & <1.0.0","connectorApi":">=1.0.0 & <2.0.0"},
 "connectorClass":"io.acme.jsm.JiraServiceDeskConnector","type":"jira-service-desk","toolNamespace":"jira_","icon":"puzzle",
 "configSchema":{"$ref":"#/see 4.3"},
 "tools":[{"name":"jira_request_sla","title":"Request SLA","description":"…","inputSchema":{},"outputSchema":{},
           "annotations":{"readOnlyHint":true,"destructiveHint":false,"idempotentHint":true,"openWorldHint":false},"nature":"READ"}],
 "access":{"hosts":["{connection.host}:443"],"commands":[]}}
```

```json
{"type":"object","required":["baseUrl","deployment","apiToken"],
 "properties":{"baseUrl":{"type":"string","format":"uri","pattern":"^https://"},
  "deployment":{"enum":["cloud","datacenter"],"default":"cloud"},
  "email":{"type":"string","format":"email","description":"Cloud only: the account of the API token"},
  "apiToken":{"type":"string","writeOnly":true,"format":"password"},
  "serviceDeskIds":{"type":"array","items":{"type":"integer"},"description":"Allowlist; empty = all visible"},
  "timeoutMs":{"type":"integer","minimum":1000,"maximum":30000,"default":10000}}}
```

```json
{"schema":1,"sequence":412,"generatedAt":"2026-10-22T09:30:00Z","expiresAt":"2026-11-21T09:30:00Z",
 "entries":[
  {"kind":"plugin","id":"jira-service-desk","version":"1.3.0","requires":{"gateway":">=0.4.0 & <1.0.0"},
   "artifact":{"url":"https://…/gw-jira-service-desk-1.3.0.jar","sha256":"c4e19a07…f52d","size":1677721},"tools":6,
   "access":{"hosts":["{connection.host}:443"],"commands":[]}},
  {"kind":"mcp-server","id":"github","displayVersion":"0.6.2","transport":"stdio",
   "npm":{"package":"@modelcontextprotocol/server-github","version":"2025.4.8",
          "integrity":"sha512-8N43bQw9MlUB0piTZHK2JMh8kYPKxH57d4Z7Wb8PS4by2MkZ0FzI5xPImg3xumpev82VZw2VWHQJJJYp+WkwEw==",
          "lockfile":{"url":"https://…/github-2025.4.8.package-lock.json","sha256":"…"},"bin":"mcp-server-github",
          "node":">=18","ignoreScripts":true},
   "env":[{"name":"GITHUB_PERSONAL_ACCESS_TOKEN","secret":true}],"toolPrefix":"github_","tools":26}],
 "revoked":[{"sha256":"…","reason":"CVE-2026-…"}]}
```
