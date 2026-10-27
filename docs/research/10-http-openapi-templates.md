# 10 · HTTP API connector, OpenAPI import, and templates

Research date: **2026-10-23**. Scope: the single-user desktop gateway. This document preserves the design decisions, historical experiments, and unresolved questions; it does not claim these experiments were rerun during migration. See the [architecture](../ARCHITECTURE.md), [SQL research](04-sql.md), and [MCP research](01-mcp-protocol.md).

## 1. Selected approach

Use a built-in HTTP connector with `http_get` and `http_request`. Share a guarded OkHttp client between HTTP execution, OpenAPI operations, specification downloads, and plugin downloads. Compile OpenAPI with Jackson 3 and SnakeYAML Engine, with local references only. Store templates as a stable identity plus append-only versions. Compile SQL templates through the SQL connector and execute bound parameters through its existing connection pool.

The recorded environment was Temurin 25.0.5, Maven 3.9.11, Spring Boot 4.1.2, OkHttp/MockWebServer/okhttp-tls **5.5.1**, Jackson **3.1.7**, SnakeYAML Engine **3.0.1**, swagger-parser **2.1.48**, JSqlParser **5.4**, pgjdbc **42.7.14**, and PostgreSQL 16. Current build files define the actual pins.

Use `okhttp-jvm` for Maven: the `okhttp` artifact examined in this research was a 754-byte multiplatform stub without JVM classes. Use SnakeYAML directly for bounded alias handling; Jackson YAML did not expand aliases in the prototype. Networknt 3.0.6 validated generated JSON Schema 2020-12 schemas against the Boot-managed Jackson version.

Rejected alternatives included JDK HttpClient without a per-client DNS/socket extension, a global `InetAddressResolverProvider` that would affect unrelated connectors, and swagger-parser's additional dependency model, remote-reference fetching, and expensive full resolution. Apache HttpClient could supply equivalent guards but required a different integration. Handwritten unsafe URL fetching is not an alternative.

## 2. Preventing SSRF

A DNS filter alone is insufficient. Use both:

1. A custom `Dns` implementation checks **every** resolved address and returns only that checked list. Reject a hostname with a mixture of allowed and disallowed addresses.
2. A guarded `SocketFactory` checks the actual `InetAddress` passed to `connect()`. It covers IP literals, noncanonical numeric hosts, mapped IPv6, and the address selected at connection time.

Set `proxy(Proxy.NO_PROXY)`. With a system proxy the socket guard sees the proxy rather than the destination. Do not admit arbitrary interceptors: newer OkHttp APIs can replace DNS, socket, or proxy behavior. Maintain a connection pool per connector and evict it after configuration or policy changes so an existing socket cannot retain access removed by policy.

| Address class | Policy |
| --- | --- |
| Cloud metadata, link-local, unspecified, multicast, reserved/broadcast, Teredo, and the gateway's own loopback ports | Always denied |
| Loopback, RFC1918, CGNAT, benchmark ranges, IPv6 ULA/site-local | Denied unless explicitly allowed by the environment policy |
| IPv4-mapped IPv6, NAT64, 6to4 | Also validate the embedded IPv4 address |

Always-denied examples include `169.254.0.0/16`, `fe80::/10`, `fd00:ec2::/32`, GCP metadata `fd20:ce::254`, and Alibaba metadata `100.100.100.200`. A broad private-range exception must never reopen these. Java's `isSiteLocalAddress()` does not cover ULA or CGNAT, so use a real CIDR matcher.

Accept canonical IP literals or DNS names. Reject ambiguous forms such as `2130706433`, `127.1`, `0x7f.1`, and `0177.0.0.1`; Java and browsers interpret some of these differently. Metadata hostnames are blocked as defense in depth.

## 3. URL, header, and transport rules

A request path starts with exactly one `/`. Reject network-path references, backslashes, userinfo, controls, encoded separators, and traversal segments before URL resolution. Compare parsed scheme, normalized host, effective port, and base path at a segment boundary. A string-prefix check would accept an attacker-controlled hostname that starts with the allowed hostname.

Reject encoded slash/backslash/control bytes and decoded `.`/`..` path segments; downstream servers can normalize them differently. OpenAPI path values must be single safe segments and must be inserted through an encoding builder.

Disable automatic redirects. Return 3xx plus Location as data. An optional same-origin mode is bounded to three hops and allowed only for reads; every hop passes URL/address validation again. Specification downloads use the same guard, HTTPS only, a 20-second budget, and no loopback/link-local exceptions.

**Disable `retryOnConnectionFailure`.** The recorded dropped-connection experiment delivered two POSTs with OkHttp's default retry behavior. With retries disabled it delivered one and returned an I/O failure. Once a mutation may have been sent, report **Outcome unknown** and advise checking before retrying.

Agent-controlled headers cannot override Host, framing/hop-by-hop headers, proxy headers, cookies, authorization, forwarded identity, configured auth headers, or secret headers. Validate header names as RFC 9110 tokens; reject CR/LF/NUL values. Bound name/value lengths, total count, and bytes. Inject credentials after user headers by replacement. Disable cookies and caches. Also prohibit custom `Accept-Encoding` and `Range` where they would bypass the expected decompression/size-limit path.

## 4. Tool semantics and exact approval

| Tool/operation | Default | Classification |
| --- | --- | --- |
| `http_get` GET/HEAD | Read only | READ unless explicitly known to mutate |
| `http_request` | Approval | GET/HEAD may classify READ but remain approval-gated; other supported methods MUTATION |
| SQL read template | Off until assigned, then Read only | READ |
| SQL DML/DDL template | Off until assigned, then Approval | MUTATION, subject to DDL policy |
| OpenAPI GET/HEAD | Off until assigned, then Read only | READ unless marked as state-changing |
| OpenAPI other methods | Off until assigned, then Approval | MUTATION |

Read annotations are explicit; PUT/DELETE can be idempotent without being safe. HTTP and OpenAPI are external channels carrying untrusted content. An endpoint that combines private-data reads with unconstrained HTTP reads can leak data in query parameters even without a mutation. Scope allowed paths and separate endpoints when needed.

`http_request` accepts exactly one body representation: JSON, text with content type, or form fields. Serialize JSON once with stable formatting. Persist the canonical request as the approval payload and send **those exact bytes** after approval; do not rebuild from mutable arguments. Show injected auth values masked, and distinguish transport-added headers. The digest binds connection/configuration version, method, URL, headers, body hash, and auth reference/version. Recheck address policy when executing because DNS can change while approval is pending.

OpenAPI approvals also identify operation and template version. Replacing a template or connection configuration must invalidate incompatible pending work. Agent notes are visibly unverified. A GET known to change state cannot run through `http_get`. A manual read-only override for POST is a development/staging exception requiring explicit review and a setup audit; it is not a production default.

## 5. Responses and secret handling

Any received HTTP status, including 4xx/5xx, is response data. Network errors, policy refusal, timeout, and uncertain delivery are tool errors. Preserve status, content type, elapsed time, truncation, safe response headers, and a readable summary.

Parse JSON only if the complete body fits the limit. Otherwise return a text prefix and an explicit truncation reason. Read at most cap-plus-one **decompressed** bytes. Binary bodies produce size/hash metadata rather than arbitrary binary output. Sanitize control, ANSI, and bidirectional characters in text. Permit a narrow response-header allowlist; drop Set-Cookie.

Register each resolved secret with the invocation's taint set, including common encodings such as raw, Base64, URL-encoded, and JSON-escaped values. Mask structured output, text, audit, Playground, and URL query credentials. Hashes and partial echoes are a residual risk and must not be described as fully covered.

## 6. OpenAPI compilation

Support OpenAPI 3.0 and 3.1; reject Swagger 2.0 with conversion guidance. Permit only local `$ref` values and reject external references before resolution. Convert 3.0 nullable/exclusive-bound/example semantics to JSON Schema 2020-12. Detect recursive references and aliases; bound both tree depth and output schema size.

The research limits were: specification file up to 20 MB; parser document/code-point budget 25 MB; nesting 200; string 5 MB; at most 50 YAML aliases; duplicate YAML keys forbidden. Convert YAML through a cycle-aware walker rather than blindly calling `valueToTree` on recursive structures.

Compile path/query/header parameters and request bodies into tool inputs. Handle simple/form/deepObject serialization, including form encoding used by Stripe. Skip unsupported multipart, cookie parameters, parameter `content`, or unsupported styles with a clear reason. Header parameters for Authorization, Accept, and Content-Type follow OpenAPI's special rules. Server defaults must resolve inside the selected connection's base URLs.

Enable at most 200 operations per template. Select all initially only for small specifications (up to 50 operations); otherwise require explicit selection. Keep each input schema within 32 KB and tool descriptions/property descriptions within 1,000/400 characters. Tool names use a conservative lowercase 64-character convention and must not collide with built-ins or other endpoint tools. The endpoint warning at 40 tools was a compatibility heuristic, not a verified universal client limit.

Imported descriptions are authored by the specification publisher and may contain prompt injection. Strip controls, show the resulting tool surface before activation, and show description/schema diffs on reimport. Review and versioning reduce this risk; they do not establish that descriptions are trustworthy.

## 7. SQL templates

A template binds one connection; callers do not choose a different connection. Parse/classify the single SQL statement when saving. Reject forbidden/unparsed/multiple statements. Bind values, never interpolate SQL text.

The named-parameter lexer must preserve quoted strings, comments, dollar-quoted bodies, casts such as `::timestamptz`, and dialect-specific quoting. Convert `:name` to JDBC placeholders; escape PostgreSQL JSON operators `?` as `??` for pgjdbc. Reject raw JDBC escape blocks outside literals because the driver could rewrite the approved text. Reject ambiguous bare MySQL placeholders.

Use typed nulls, such as `Types.TIMESTAMP_WITH_TIMEZONE`; the recorded PostgreSQL experiment failed to infer an untyped parameter. Support bounded string/enum/integer/decimal/boolean/timestamp/date values. Evaluate relative-time defaults once per invocation. The proposed limits were 20 parameters and 20,000 SQL characters.

READ templates run in the SQL connector's READ ONLY transaction with rollback, timeout, and row limits. Database enforcement is still required for hidden writes in functions. Production DDL policy remains authoritative for mutation templates.

## 8. Test connection and validation

Test DNS, TCP/TLS, authentication, and a safe configured probe with a bounded deadline. A private endpoint needs an explicit policy exception; an unfamiliar CA requires trust review, not automatic acceptance. Explain missing scopes where the API provides evidence. Do not claim to know write permissions from a successful GET. Health checks use the same safe request with a shorter deadline.

The recorded experiments included:

- Mixed DNS answers, IP literals, IPv4-mapped IPv6, metadata addresses, rebinding, redirect attempts, and system proxy behavior.
- HTTPS with a private test CA, exact request headers/body bytes, token echo masking, dropped-connection POST behavior, large responses, and gzip expansion.
- E2E approval followed by exactly the saved request, plus template-change invalidation.
- SQL named parameters, typed nulls, JSONB operators, read-only rejection (`25006`), timeout, and row caps.
- OAI petstore-expanded 3.0 and tictactoe 3.1 fixtures; generated schemas checked against the 2020-12 metaschema.
- Optional pinned/hash-checked Stripe (8.3 MB, 612 operations) and GitHub (13.6 MB, 1,242 operations) corpora. Recorded parse/compile times were approximately 274/200 ms, but the subsequent fact review did not rerun those benchmarks.

Preserve fixture attribution: the OAI examples do not share a single blanket Apache license; the repository uses CC BY 4.0 and the petstore example also declares its own license.

## 9. Limits and unresolved questions

Corporate proxies need a separate design because remote proxy DNS defeats the direct socket boundary. GraphQL, OAuth client credentials, mTLS, HMAC signing, multipart, SQL array expansion, image content, and streaming output were outside the MVP described here. `text/event-stream` is bounded text, not a streaming session.

Client support for nullable unions, allOf/oneOf, long tool sets, and generated schema complexity was not established for every MCP client. The optional swagger-parser `resolveRequestBody` flag was identified but not experimentally evaluated. Performance figures and some original MockWebServer/SQL experiments were not independently rerun during fact review. Preserve those distinctions when using this report as evidence.

## 10. Primary evidence

- [OkHttp source and release history](https://github.com/lysine-dev/okhttp/blob/master/CHANGELOG.md) and [Maven artifact metadata](https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp-jvm/maven-metadata.xml).
- [Spring Boot 4.1.2 dependency BOM](https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.2/spring-boot-dependencies-4.1.2.pom).
- [OpenAPI 3.0.3 specification](https://github.com/OAI/OpenAPI-Specification/blob/main/versions/3.0.3.md) and [OAI learning examples](https://github.com/OAI/learn.openapis.org).
- [MCP protocol schemas](https://github.com/modelcontextprotocol/modelcontextprotocol/tree/main/schema).
- [RFC 9110 header syntax](https://www.rfc-editor.org/rfc/rfc9110.html#section-5.6.2).
- [EKS Pod Identity metadata endpoints](https://docs.aws.amazon.com/eks/latest/userguide/pod-identities.html) and [GCP metadata querying](https://docs.cloud.google.com/compute/docs/metadata/querying-metadata).
- [pgjdbc question-mark escaping discussion](https://www.postgresql.org/message-id/CAFxe7BBAhe-wAbA19b2avQYMp_BsEysQuxcYQ6Ye3jwsMk-FrQ%40mail.gmail.com).
- [Slack section text limits](https://docs.slack.dev/reference/block-kit/blocks/section-block).

## Appendix: recorded schema and response examples

These prototype examples preserve the investigated contracts. Current generated connector schemas are authoritative for executable behavior.
```json
{"type":"object","additionalProperties":false,"required":["connection","path"],"properties":{
 "connection":{"type":"string","enum":["billing-api"]},
 "path":{"type":"string","minLength":1,"maxLength":2048,"description":"Path under the base URL with an optional query, e.g. /v1/invoices?limit=10. A full URL works only if it starts with an allowed base URL."},
 "query":{"type":"object","maxProperties":50,"additionalProperties":{"type":["string","number","boolean","array"],"items":{"type":["string","number","boolean"]}},"description":"Extra query parameters; an array repeats the key."},
 "headers":{"type":"object","maxProperties":20,"additionalProperties":{"type":"string","maxLength":1024},"description":"Extra request headers. Authorization is added by the gateway."},
 "method":{"type":"string","enum":["GET","HEAD"],"default":"GET"}}}
```

```json
{"status":201,"ok":true,"method":"POST","url":"https://billing.stage.acme.dev/v1/refunds",
 "headers":{"content-type":"application/json","x-request-id":"7f3k…","retry-after":null},
 "contentType":"application/json","json":{"id":"re_1","status":"pending"},"text":null,"binary":null,
 "bytes":43,"truncated":false,"truncatedReason":null,"redirect":null,"elapsedMs":84}
```

```json
{"kind":"http","target":"billing-api · billing.stage.acme.dev","summary":"POST /v1/refunds · billing-api (stage)",
 "text":"POST https://billing.stage.acme.dev/v1/refunds\nContent-Type: application/json\nIdempotency-Key: refund-48213\nAuthorization: ‹masked›\n\n{\n  \"order_id\": 48213,\n  \"amount\": 1999,\n  \"currency\": \"EUR\",\n  \"reason\": \"duplicate charge on order 48213\"\n}",
 "fields":[["Host","billing.stage.acme.dev, in the stage allowlist"],["Retries","Safe: idempotency key set"],["Timeout","10 s"],
   ["Connection","Unreachable · checked 3 min ago"],["Also sent by the gateway","Host, Content-Length: 112, Connection: Keep-Alive, User-Agent: mcp-gateway/0.4.0, Accept-Encoding: gzip"]],
 "warnings":[],"payload":{"method":"POST","url":"…","headers":[["Content-Type","application/json"],["Idempotency-Key","refund-48213"]],"bodyB64":"…","auth":"bearer:billing-api/token@v4"}}
```

```json
{"key":"POST /refunds","method":"POST","pathTemplate":"/refunds","args":[{"arg":"Idempotency-Key","in":"header","name":"Idempotency-Key"}],"body":{"arg":"body","mediaType":"application/json","required":true}}
```
