# 16 · Plugin host: Java plugins outside the native core

Research date: **2026-10-23**. This design supersedes the in-core PF4J proposal in [report 11](11-extensibility.md) and complements the [native-core decision](15-graalvm-native.md). It records selected contracts and historical prototype evidence; current source and the [architecture](../ARCHITECTURE.md) determine actual behavior.

## 1. Decision

Run Java plugins in separate JVM processes, **one process per plugin generation (`id@version`)**. The native core talks to each host over GPP/1: JSON-RPC 2.0 objects framed as newline-delimited UTF-8 JSON over stdin/stdout. GPP mirrors Connector SPI operations rather than pretending to be a general MCP server.

The core starts without plugin hosts. Tool listing comes from verified stored metadata. Start a host only when execution, connection testing, or dynamic classification needs it; stop idle hosts. A crash, out-of-memory error, stuck thread, or System.exit then affects one plugin generation. Updating starts a new generation, switches future calls, and drains existing calls on the old one.

The host contains PF4J 3.16.0, Jackson 3.1.7, the connector API/protocol, and a small logging adapter. It needs neither Spring nor Reactor nor MCP SDK. Use a bundled jlink JRE, not whichever Java installation happens to be on the user's PATH.

Rejected alternatives:

- One shared host saves memory but shares secret-bearing memory and failure state across plugins.
- Experimental native runtime class loading still crashed the whole prototype when plugin code reached an AOT method omitted from analysis. It also provides no plugin security boundary.
- MCP lacks the SPI's classify, exact-action, configuration-per-call, and scoped secret callbacks; vendor extensions would recreate GPP with more dependencies.
- gRPC adds a larger protocol/runtime stack. A separate socket would need authentication and lose the useful parent-pipe ownership signal.
- Downloading the JRE on demand breaks offline installation from a local plugin file.

## 2. Trust model

Process separation isolates normal API access and failure state; **it is not a complete sandbox for arbitrary same-user code**. The plugin sees values belonging to its own connections during calls and can copy or exfiltrate them. JDK SecurityManager is permanently disabled. A declared host allowlist constrains the gateway-provided HTTP helper, not arbitrary sockets the plugin creates.

The core never supplies its master key, general store client, or another plugin's resolver over GPP. OS hardening and actual sandbox capabilities may further restrict the process, but UI claims must match what is enforced on that platform. A catalog signature establishes artifact identity, not trustworthy behavior.

Review text should state that the plugin runs in its own Java process and receives its connections' secrets while needed. Do not promise that separate heaps alone defeat a malicious process with unrestricted same-user filesystem/debugging access.

## 3. Runtime, launch, and AOT cache

Bundle `java.se` plus the required JDK modules for networking, DNS, cryptography, ZIP files, character sets, locales, management, and common plugin libraries. Verify `requires.javaModules` from each plugin against the bundled runtime. Preserve the JRE legal notices. The investigated broad runtime was about 68 MB on disk / 44 MB compressed.

Build a stable host jar/classpath. Launch with an argv array, never a shell. Clear inherited environment, especially `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`, `_JAVA_OPTIONS`, and CLASSPATH, which can inject agents or flags. Add only required OS/runtime values. Give each plugin a dedicated owner-only data/tmp directory and bounded heap/metaspace/code-cache settings. The proposal defaulted to a 256 MB heap, adjustable within a reviewed 64–1,024 MB range.

Redirect JVM logging to stderr with `-Xlog:disable -Xlog:all=warning:stderr`. An incompatible AOT cache otherwise printed warnings/errors to stdout in the experiment and corrupted the protocol channel. Expand application paths explicitly; an argv launch does not expand `~`.

A JDK AOT cache can reduce host startup but depends on JVM build, classpath, jar contents, timestamps, and layout. Generate it locally in the background with a built-in training plugin, write it atomically, and key it by host hash/version and complete runtime identity. jlink's default release metadata lacked the full build identifier; add it or hash the runtime modules. An incompatible/missing cache falls back to ordinary startup rather than preventing plugin use.

PF4J's custom-loaded plugin classes do not become part of the built-in-loader AOT cache. Cache training accelerates the host framework; it does not eliminate plugin-specific loading cost.

## 4. GPP/1 framing and methods

Use one JSON object per UTF-8 line, capped at **8 MiB**. Read bytes into bounded reusable buffers rather than materializing every frame as an immutable String, especially for secret callbacks. Enforce the tighter invocation response limit before writing a result.

Core request IDs are integers; host callback IDs use a distinct string namespace. Responses may arrive out of order. Register a request as in-flight **before** dispatching its virtual thread so EOF cannot race the final response. Retain the original stdout descriptor for protocol output, redirect `System.out` to stderr before loading plugin code, and bound/recover from direct native stdout pollution. The researched policy restarted after more than 20 non-JSON lines per minute.

Hello checks protocol and connector API compatibility. Core and host ship together; a mismatch is a packaging/runtime error and must stop the host.

| Direction | Method | Contract |
| --- | --- | --- |
| Core to host | `host/hello` | Protocol/runtime identity and capabilities |
| Core to host | `plugin/load`, `plugin/unload` | Verified generation lifecycle |
| Core to host | `connector/tools` | Dynamic tool list, only if declared |
| Core to host | `connector/test` | Connection identity/permissions/health |
| Core to host | `connector/classify` | MIXED tools only; no secret resolver |
| Core to host | `connector/describeAction` | Secondary exact-action description; no secrets |
| Core to host | `connector/execute` | Bounded invocation and approved payload |
| Core to host | `host/ping`, `host/shutdown` | Liveness and graceful termination |
| Core to host | `$/cancel`, `connection/invalidate` | Cancellation or pool/config/secret invalidation |
| Host to core | `secrets/get`, `secrets/login`, `secrets/meta`, `secrets/sshCertificate` | Scoped invocation callbacks |
| Host to core | `log`, `connector/toolsChanged` | Bounded events |

Manifest READ/MUTATION classification can avoid an RPC; MIXED classification has a short deadline and fails closed. A describeAction timeout removes the plugin's secondary explanation, not the core's canonical primary payload. Use ordinary result errors for domain failures and structured protocol errors for load, busy, cancellation, deadline, or secret-denial failures. Error text and limited stack frames still pass core masking.

Default researched deadlines were hello 15 seconds (30 without cache), load 30, test/tools 10, classify two, description five, ping five, and shutdown three. Execution follows the effective policy deadline rather than a separate unlimited host timeout.

## 5. Scoped secret callbacks

Create a random 128-bit call token for each invocation and bind it to host instance, generation, connection, and call ID. Its lifetime ends at result, timeout, or cancellation. Issue it only for operations permitted to resolve secrets; classify and describeAction receive none.

Permit only fields marked writeOnly in that plugin's connection schema, belonging to that connection. Bound callback count (the proposal used 16 per invocation). Reject expired/replayed tokens and cross-host/cross-connection fields with a secret-denied error and a setup audit. The agent never chooses a secret reference.

Resolve lazily: a plugin receives only the fields it requests, dynamic Vault leases are issued only when needed, and the core's taint set is accurate. SSH certificate callbacks take a public key; the private key stays in the host.

Write secret characters directly into a bounded frame and zero temporary buffers after flush. Extract them through a streaming parser in the host, then zero the frame. `RemoteSecretResolver` closes every issued wrapper in a finally block even if plugin code forgets. Immutable strings required by downstream libraries remain a best-effort memory limitation.

Connection pools may retain credentials while active; `connection/invalidate` must close/refresh them after rotation, edits, or removal. The host framework does not persist secret values. Core masking is applied to raw host results, audit, and logs. Keep a bounded recent per-process taint set for stderr/log messages that arrive after a call.

## 6. Installation, update, and shutdown

Inspect staging artifacts without executing code: ZIP structure, size/hash, signed catalog status/revocation, manifest, namespaces, schemas, gateway/API/module requirements, and forbidden bundled API packages. Preserve the existing catalog checks from report 11. Execute plugin code only after the user's install trust decision.

A trial load uses a fresh host with no connections or secrets: hello, load, verify reported tools against the manifest, then shutdown. Only then activate the installed generation. A phase-specific error allows rollback without leaving a half-installed plugin active.

The host state machine is `Starting → Running → Draining → Stopped`, with failures through `Crashed → Backoff → Starting`. Five crashes in ten minutes produce Failed until explicit recovery. Startup is single-flight so concurrent callers do not spawn duplicate hosts.

On update, verify the new artifact, start/load its host, validate tool changes, atomically update the current generation, keep new tools Off, invalidate affected waiting approvals, and route future calls to the new process. Old in-flight calls finish within the drain budget (proposed 60 seconds), then the old process exits. Delete old jars only after process termination, especially on Windows. Rollback is another reviewed generation switch.

The original update example preserved read-only confirmations when definitions were unchanged, while report 11 bound them to version as well. This is a historical inconsistency: the integrated implementation must choose and test one trust rule. The conservative rule is to require fresh confirmation when either version or definition changes.

For disable/remove/revocation, deny new work, invalidate pending approvals, drain, shut down, and kill remaining descendants after the deadline. A plugin may spawn children; killing only the host PID is insufficient. Orphan cleanup validates PID **and start time** to avoid killing an unrelated reused PID.

Keep the pipe's writing end owned by the core. Parent death produces EOF and host exit; also apply bounded forced cleanup. Linux prototype behavior does not establish Windows anonymous-pipe/Job Object correctness. Idle stop was proposed at 15 minutes, or five in production; sleeping hosts are not repeatedly awakened just for scheduled health checks.

## 7. Failure, timeout, and admission limits

Detect process exit, stdout EOF, protocol violations, and missed pings. In-flight calls fail with bounded masked diagnostics. **Never retry a mutation after a host crash:** its outcome may be unknown.

A deadline sends cancellation and interrupts the host's virtual thread. If plugin code ignores cancellation, track stuck calls. Multiple stuck calls or failed liveness moves the generation to Draining; route new work to a replacement and terminate the old process after other calls finish or their deadline expires. Thread.stop is not a recovery mechanism.

The proposed core bulkhead admitted eight running calls, a queue of 16, and up to two seconds queue wait; the host had a defensive limit of 32. These are explicit configurable policy limits, not permission to exceed invocation deadlines. Record restart count, state, process identity, recent errors, and memory where the OS supplies it.

## 8. Shared connector API

One connector API jar serves built-ins and hosted plugins. Keep dependencies to JDK types and Jackson tree types. Do not expose Spring, Reactor, MCP SDK, Jakarta, SLF4J, or OpenTelemetry objects through the API. A traceparent string and a narrow logger interface cross the boundary more reliably.

Wire values must be explicitly serializable: primitives, strings, tree nodes, lists/maps, ISO times/durations, Base64 bytes, and encoded public keys. No InputStream, arbitrary Object, or process-local callbacks cross the boundary. Use cursors instead of streaming results in GPP/1.

Use a handwritten tree codec with golden tests, avoiding reflection in the native protocol path. Admin DTO binding is separate and still requires native metadata. Configuration access stays tree-based; a generic `config.as(MyClass.class)` would create hidden reflection requirements.

Offer stable factories/builders for plugin-created result, test, tool, and action types. Adding a record field changes its canonical constructor and can break existing plugin bytecode. Evolve through default methods/new factories and declared connectorApi semver ranges. `RemoteConnector` implements the same interface as built-ins so policy, approval, audit, and masking remain centralized.

## 9. Historical measurements and validation

The prototype used GraalVM CE 25.4.4.1.1, Temurin 25.0.5+8, PF4J 3.16.0, and Jackson 3.1.7 on four shared vCPUs. Reported native-core-to-jlink-host startup with local AOT cache was **198–273 ms**, versus **779–993 ms** without cache. A synthetic call with two secret callbacks reported p50 **0.10–0.23 ms**, p95 **0.41–0.75 ms**, and host RSS **53–57 MB**. Separate-process private memory was estimated around 28 MB per idle host.

The later fact review did **not** rerun the full host benchmark. It reproduced runtime size, cache invalidation/stdout contamination, and hello-world trends, and inspected saved build logs. Treat detailed latency/throughput/EOF numbers as historical prototype observations, not current gates already passed.

Test the protocol with out-of-order responses, oversized/partial frames, malformed output, cancellation races, and forged secret tokens. Process fixtures should include crash, OOM, ignored interrupt, stdout pollution, secret theft, slow class initialization, and two generations with a call held open during update. Verify no inherited Java agent options, fallback after a bad AOT cache, masked logs, and exit after parent death.

Native integration should launch the real core and bundled runtime, install a fixture through admin API, invoke it over MCP, and exercise a fake remote service. Test each admin DTO's native serialization separately from the reflection-free GPP codec. macOS/Windows signing, process-tree containment, library loading, and jar deletion remain OS-specific tests.

## 10. Open questions and primary evidence

Unresolved choices included full-JIT UI controls, previous-generation retention, prefetch on tool listing, host cache packaging details, and Windows Job Object coverage. The fat-jar AOT path and preserved-timestamp relocation were not independently rerun. Experimental native class loading should not replace this architecture without a fresh compatibility and trust review.

- [PF4J classloader source](https://github.com/pf4j/pf4j/blob/release-3.16.0/pf4j/src/main/java/org/pf4j/PluginClassLoader.java).
- [GraalVM 25.1 runtime-class-loading notes](https://www.graalvm.org/release-notes/25.1/), [25.4 notes](https://www.graalvm.org/release-notes/25.4/), and [implementation source](https://github.com/oracle/graal/tree/vm-25.4.4.1.1).
- [JEP 483](https://openjdk.org/jeps/483), [JEP 514](https://openjdk.org/jeps/514), [JEP 515](https://openjdk.org/jeps/515), and [JEP 486](https://openjdk.org/jeps/486).
- [JDK 25 Thread API](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/Thread.html).
- [Native Build Tools Maven guide](https://graalvm.github.io/native-build-tools/latest/maven-plugin.html) and [reachability metadata repository](https://github.com/oracle/graalvm-reachability-metadata).
- [MCP Java stdio transport source](https://github.com/modelcontextprotocol/java-sdk/blob/v2.1.0/mcp-core/src/main/java/io/modelcontextprotocol/client/transport/StdioClientTransport.java).

## Appendix: recorded protocol examples

These examples retain the investigated GPP shapes. Current protocol codecs and golden tests are authoritative.
```json
→ {"jsonrpc":"2.0","id":1,"method":"host/hello","params":{"protocol":1,"core":{"version":"0.4.0","connectorApi":"1.0.0","native":true},
   "limits":{"maxFrameBytes":8388608,"maxInFlight":8},"logLevel":"info"}}
← {"jsonrpc":"2.0","id":1,"result":{"protocol":1,"host":{"version":"0.4.0","connectorApi":"1.0.0"},"pid":21691,
   "java":{"version":"25.0.5+8-LTS","aotCache":true},"capabilities":["cancel","secrets.callback","tools.dynamic"],"readyMs":31}}

→ {"jsonrpc":"2.0","id":2,"method":"plugin/load","params":{"generation":"jira-service-desk@1.3.0","instance":1,
   "jar":"/home/ann/.mcp-gateway/plugins/jira-service-desk/1.3.0/plugin.jar","sha256":"c4e19a07…f52d","manifestSha256":"9b1f…",
   "dataDir":"/home/ann/.mcp-gateway/plugin-data/jira-service-desk","egress":{"hosts":["acme.atlassian.net:443"],"denyPrivate":true}}}
← {"jsonrpc":"2.0","id":2,"result":{"generation":"jira-service-desk@1.3.0","type":"jira-service-desk","connectorApi":"1.0.0","loadMs":47,
   "tools":[{"name":"jira_request_sla","title":"Request SLA","description":"…","inputSchema":{…},"outputSchema":{…},
     "annotations":{"readOnlyHint":true,"destructiveHint":false,"idempotentHint":true,"openWorldHint":false},"nature":"READ","resultShape":"JSON"}, …]}}
```

```json
→ {"jsonrpc":"2.0","id":41,"method":"connector/execute","params":{"generation":"jira-service-desk@1.3.0","callId":"tc_01JBA7Q2","callToken":"ct_9Qm2…(22 b64url)",
   "tool":"jira_request_sla","arguments":{"issueKey":"SD-123"},
   "connection":{"id":"acme-jsm","type":"jira-service-desk","env":"dev","configHash":"sha256:3f0c…",
     "config":{"baseUrl":"https://acme.atlassian.net","deployment":"cloud","email":"svc-gateway@acme.com","serviceDeskIds":[],"timeoutMs":10000},
     "secretFields":["apiToken"]},
   "timeoutMs":10000,"approved":false,"limits":{"maxBytes":1048576,"maxRows":1000},"traceparent":"00-4f2a9c71e8d04b5a9c3e2b1f0a6d8e81-7a1c2e9b4d3f6a10-01"}}
← {"jsonrpc":"2.0","id":"h17","method":"secrets/get","params":{"callToken":"ct_9Qm2…","field":"apiToken"}}
→ {"jsonrpc":"2.0","id":"h17","result":{"value":"ATATT3xFfGF0…","encoding":"utf8","version":"v3"}}
← {"jsonrpc":"2.0","method":"log","params":{"level":"debug","logger":"io.acme.jsm.Http","callId":"tc_01JBA7Q2","message":"GET /rest/servicedeskapi/request/SD-123/sla 200 212 ms"}}
← {"jsonrpc":"2.0","id":41,"result":{"content":[{"type":"text","text":"SD-123 · Time to first response: ongoing, 2h 30m left (goal 4h)"}],
   "structuredContent":{"issueKey":"SD-123","slas":[{"name":"Time to first response","state":"ongoing","breached":false,"goalMillis":14400000,
     "elapsedMillis":5400000,"remainingMillis":9000000,"breachTime":"2026-10-22T18:30:00+02:00"}],"truncated":false},
   "isError":false,"stats":{"durationMs":214,"secretReads":1}}}
```
