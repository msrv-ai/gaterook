# 14 · Desktop stack: Spring Boot, SQLite, Angular, and Electron

Research date: **2026-10-23**. Scope: one local user, SQLite, and loopback-only application listeners. This records the platform choice and original prototype evidence. **The JVM-only shipping-core proposal is superseded by [native-image research](15-graalvm-native.md) and [plugin-host research](16-plugin-host.md).** The JVM remains supported for local development. The [architecture](../ARCHITECTURE.md) and current manifests describe the integrated application.

## 1. Selected stack

| Layer | Investigated version | Reason |
| --- | --- | --- |
| Java | 25 | Virtual threads and native-image target |
| Spring Boot | 4.1.2 | MVC, validation, data access, lifecycle, observability |
| Spring Framework / Tomcat | 7.0.10 / 11.0.27 | Boot-managed server stack |
| Jackson | 3.1.7 | Shared JSON representation across core/connectors |
| SQLite JDBC | 3.53.4.0 | Embedded local database |
| Hibernate / Flyway | 7.4.13 / 12.4.0 | JPA persistence and versioned schema migrations |
| Spring Modulith | 2.1.2 | Explicit module APIs and boundary verification |
| Resilience4j | 2.4.0 | Programmatic circuit breakers, bulkheads, rate limiting |
| Angular / CDK | 22.2 / 22.2.4 | Standalone, signals, zoneless UI and accessibility utilities |
| Electron | 45.0.0 | Desktop main/preload, native window and OS integration |
| electron-builder / updater | 26.17.0 / 6.8.10 | Packaging and update compatibility |

These versions were inspected in the dated research; do not interpret the table as a request to update dependencies. Use the checked-in manifests and Node pin for local work.

Use Jackson 3 consistently. The `com.fasterxml.jackson.annotation` namespace remains shared annotations and is not itself an unwanted Jackson 2 databind dependency. Enforce the distinction in Maven. MCP JSON integration must select the Jackson 3 module.

Use Spring Modulith API in runtime and its verification tooling in tests, avoiding unnecessary runtime analysis dependencies. The connector SPI is an explicit shared API; internal core packages are not plugin APIs.

## 2. Loopback server and lifecycle

Bind the local API/MCP listener to `127.0.0.1:7465`. A second loopback listener on 7466 is reserved for tunnel ingress and has a deny-by-default route filter. It must not expose admin/internal endpoints. Desktop settings cannot turn these listeners into network-wide services.

A local guard checks Host, Origin, and fetch metadata. Admin/internal calls need the launch secret, compared without timing-sensitive string equality. Reject foreign origins, inappropriate cross-site admin requests, and unexpected request content types. Do not enable permissive CORS. Admin responses are no-store and nosniff.

Electron main creates a fresh random launch secret, passes it through a bounded stdin bootstrap, and keeps stdin open as the core's ownership signal. The renderer never receives it. Release startup without a valid bootstrap times out. Development has a separate explicit secret path.

The core emits a single machine-readable readiness/failure message. Useful failure reasons include occupied port, missing/invalid master key, database lock/migration failure, and bootstrap timeout. Electron displays the matching status screen and verifies health after readiness. Do not silently choose a different port: client configurations refer to the configured one.

Use a minimal process environment, bounded masked logs, explicit shutdown deadlines, disabled attach/heap dumps, and OS process hardening where supported. EOF from the parent shuts down the child. Native and JVM launch paths must preserve the same observable protocol.

## 3. SQLite concurrency and persistence

Use WAL, foreign keys, a bounded busy timeout, and `synchronous=FULL` for durable audit writes. Use **one writer connection** with `transaction_mode=IMMEDIATE`; short write transactions queue in Java rather than multiple virtual-thread carriers blocking inside native SQLite. Route read-only queries through a small separate pool (the prototype used three connections).

A read-only JDBC flag was insufficient in the recorded driver: without an extra setting it could fail pool startup; with that setting inserts could still succeed. Apply `PRAGMA query_only=1` to reader connections and test actual write refusal. `LazyConnectionDataSourceProxy.setReadOnlyDataSource` exists, but JPA routing needs an integration test, not just API inspection.

The original prototype reported 171 failures in 400 read-then-write DEFERRED transactions, versus none with IMMEDIATE. Two blocked native writers on two carriers delayed unrelated virtual-thread tasks by roughly ten seconds; one writer avoided that effect. These measurements were not independently rerun in the fact review and are design evidence, not performance guarantees. JEP 491's monitor improvements do not eliminate blocking inside JNI.

Flyway SQLite support is inside `flyway-core`; no separate SQLite database artifact is needed. Use the Hibernate community SQLite dialect. The initial recommendation to disable schema validation after an INTEGER/Long mismatch was refined in report 15: explicit SQLite column definitions and native-compatible JPA mapping permit validation. Preserve that later correction.

Before schema migration, create a consistent database backup, for example using VACUUM INTO. Keep master-key material out of the database archive. Store timestamps consistently and remember that SQLite native queries compare the chosen numeric representation, not arbitrary timestamp strings.

The audit is append-only at both service and database layers. Triggers reject updates and premature deletions; only the retention job removes sufficiently old records. Long Activity/Insights queries use the read pool. Batch nonessential usage counters to reduce writer contention.

## 4. Invocation resilience

Connectors use blocking APIs compatible with virtual threads and honor `ExecContext.deadline`, cancellation, and resource-native timeouts. They must not create uncontrolled platform-thread pools or change global SSL, proxy, authentication, or system-property state.

Apply rate limits per token/tool, bulkheads per connection, and circuit breakers per connection. The proposed default was 60 calls/minute and four concurrent calls per connection. Return actionable MCP tool errors with retry metadata for rate limits, busy resources, open circuits, and deadlines. Do not create an approval request for a call that cannot pass admission limits.

Count only resource-unavailable and timeout failures toward a circuit breaker. Bad input, normal resource errors such as SQL syntax, policy denial, and human rejection must not mark a healthy resource down. Resilience4j's `recordException=false` can count an exception as success; use explicit ignore semantics and test the accounting, especially when a timeout interrupts work that later completes.

Close pools/sessions and reset associated admission state when configuration changes. Mutations are not automatically retried. Uncertain delivery remains visible to the caller and reviewer.

## 5. Angular and the canonical design kit

Use standalone components, signals, the built-in control flow, and zoneless change detection. Connect the canonical kit as the local `@gaterook/design-kit` package; reuse tokens, components, fonts, and icons. The researched icon package was `@lucide/angular`, with explicit aliases for renamed kit icons.

Port behavior through thin Angular directives while preserving the kit's keyboard/ARIA contract. Native dialog and Popover API behavior can avoid overlay conflicts inside a modal top layer. Use CDK utilities such as key managers, Clipboard, and LiveAnnouncer where useful; do not introduce a second visual component system.

Render connector configuration with a bounded JSON Schema renderer on Signal Forms. The agreed extension vocabulary includes:

| Extension | Purpose |
| --- | --- |
| `x-gateway-secret` | Value/login/PEM/binary secret field and source picker |
| `x-widget`, `x-language` | Textarea, code, segmented, choice cards, tags, file, environment controls |
| `x-enumTitles`, `x-unit` | Human-readable enum labels and units |
| `x-collapsed` | Advanced sections |
| `x-env-default` | Environment-specific defaults |
| `x-stricter` | Policy tightening semantics |
| `x-order`, `x-confirm` | Field order and explicit risky-change confirmation |

Use fieldsets/legends for sections and a safe JSON fallback for unknown schema features rather than silently dropping fields. Earlier reports used inconsistent aliases; the integrated renderer/connector schemas must share one vocabulary.

Load CodeMirror 6 lazily and preserve exact action text. Server-produced diffs render through the kit's diff presentation. Maintain parity tests for dialogs, menus, focus return, disabled reasons, announcements, and schema-generated forms.

## 6. Electron renderer boundary and CSP

Serve the UI from `app://gw/` through a secure registered custom protocol. The main process proxies only the permitted `/api/` surface and injects the launch secret. The renderer cannot call `/internal/` or `/mcp/` through that proxy or fetch the loopback core directly under CSP.

Enable sandbox and context isolation; disable Node integration, webviews, unwanted permissions, arbitrary window creation, and navigation outside the app origin. Validate IPC against a fixed method list, the actual sender frame, and payload schemas; a missing sender frame is denied. External links are explicitly validated HTTPS destinations.

Use a fresh style nonce for Angular and CodeMirror. A policy containing only `style-src 'self'` blocks their dynamic style elements. Keep `base-uri 'self'` for the application base element. Disable Angular critical-style inlining if its generated loader script conflicts with the chosen script policy. Static inline style attributes and runtime CSS bindings require different treatment under CSP and should be tested in the real bundle.

Release fuses disable RunAsNode, NODE_OPTIONS, CLI inspect switches, and file-protocol extra privileges, while enabling ASAR integrity and appropriate cookie protection. A sandboxed preload in Electron 45 cannot assume arbitrary Node globals or shims.

Deep links only navigate to a strict route set. Updates require platform signature checks, and database migration/rollback must preserve compatibility. A source configuration is not evidence that signing, notarization, installers, or update delivery have passed validation.

## 7. Observability and packaging

Insights are derived from application audit data, not transient telemetry. OTLP export is opt-in. In the investigated Boot stack, traces needed an explicit endpoint but OTLP metrics defaulted to periodic localhost export, so disable metric export explicitly until configured. Keep secret-bearing configuration and diagnostic endpoints unavailable.

The original JVM distribution used an extracted Boot application plus a jlink runtime; later research selected a native core and retained jlink for plugin hosts. jdeps output alone does not capture reflection/service-loader requirements. Stage platform-specific SQLite native libraries deliberately, rather than rely on an executable temporary directory; signed macOS bundles need matching native-library treatment.

Runtime data paths, client configuration keys, deep-link schemes, and updater identity are compatibility interfaces. A product-name change must not blindly rewrite them. Use current scripts for reproducible local builds; report 15 explains native packaging tradeoffs.

## 8. Validation and unresolved platform work

Useful checks include Spring Modulith boundaries; both listener route maps; missing/foreign admin credentials; PRAGMA values; real read-pool write denial; concurrent writer behavior; append-only triggers; startup failure/readiness/EOF; and the built JVM/native core as a separate process.

UI tests cover design behavior and schema fixtures. Electron smoke tests cover JSON/POST/SSE proxy behavior, direct-fetch and script blocking, nonce styles, missing require, keyboard navigation, and fonts. Native OS tests remain necessary for Keychain, signing/notarization, Windows installer identity, tray/notifications, login startup, and auto-update.

The fact review confirmed published APIs and several isolated SQLite behaviors but did not rerun the original full skeleton. Startup/RSS/installer-size numbers, some concurrency claims, SSE/CSP demonstrations, and cross-platform packaging assumptions remain historical or unverified. The reported JVM startup of 4.4–5.3 seconds and optional 1.6-second AOT cache motivated the subsequent native investigation; they are not current application benchmarks.

## 9. Primary evidence

- [Spring Boot dependency BOM](https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.2/spring-boot-dependencies-4.1.2.pom).
- [SQLite pragmas](https://www.sqlite.org/pragma.html) and [sqlite-jdbc](https://github.com/xerial/sqlite-jdbc).
- [Resilience4j circuit breaker semantics](https://resilience4j.readme.io/docs/circuitbreaker).
- [Angular Aria](https://angular.dev/guide/aria/overview) and [Angular security/CSP](https://angular.dev/best-practices/security).
- [Electron releases](https://releases.electronjs.org/releases.json), [security guidance](https://www.electronjs.org/docs/latest/tutorial/security), [autoUpdater](https://raw.githubusercontent.com/electron/electron/main/docs/api/auto-updater.md), and [breaking changes](https://raw.githubusercontent.com/electron/electron/main/docs/breaking-changes.md).
- [JEP 491](https://openjdk.org/jeps/491) and [GitHub runner images](https://github.com/actions/runner-images).

## Appendix: recorded configuration and health examples

These examples illustrate the dated proposal. Current application configuration and health DTOs are authoritative.
```json
{"type":"object","properties":{
 "general":{"type":"object","title":"General","properties":{
   "openAtLogin":{"type":"boolean","title":"Start MCP Gateway when you sign in","default":true},
   "startHidden":{"type":"boolean","title":"Start in the tray","default":true},
   "updates":{"enum":["auto","notify","off"],"x-enumTitles":["Download and install","Tell me","Don't check"],"default":"auto","x-widget":"segmented"},
   "singleKeyShortcuts":{"type":"boolean","title":"Single-key shortcuts","default":true}}},
 "localApi":{"type":"object","title":"Local API","properties":{
   "port":{"type":"integer","minimum":1024,"maximum":65535,"default":7465,"x-confirm":"warning",
           "description":"Clients' configs contain this port. Changing it reconnects every client."},
   "allowedOrigins":{"type":"array","items":{"type":"string","format":"uri"},"uniqueItems":true,"maxItems":20,"default":[],"x-widget":"tags","x-confirm":"warning",
           "description":"Browser origins allowed to call /mcp. Empty: only non-browser clients."}}}}}
```

```json
{"status":"UP","version":"0.4.0","uptimeMs":5120,"ports":{"local":7465,"public":7466},
 "db":{"path":"~/.mcp-gateway/gateway.db","sqlite":"3.53.4","journal":"wal","schema":"V7","sizeBytes":4194304},
 "jre":"25.0.5+8","masterKey":{"protection":"os"},"tunnel":"off","warnings":[]}
```
