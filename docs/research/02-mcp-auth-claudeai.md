# 02 · OAuth for claude.ai and the public tunnel

> Research snapshot: 2026-10-23. Scope: the single-user desktop application and SQLite. This is an English adaptation of the recorded research, not a fresh audit of external services. Versions, limits, provider addresses, and client behavior below are dated findings. **UNVERIFIED** identifies unresolved observations. See [01](01-mcp-protocol.md) for MCP behavior and the [architecture](../ARCHITECTURE.md) for the application contract.

## 1. Decision and alternatives

Implement a small authorization server inside the core, using Spring MVC and opaque tokens. Put public traffic on a separate loopback listener, `127.0.0.1:7466`, while the application and administration API remain on 7465. A supervised `cloudflared` process forwards public HTTPS to the restricted listener.

Use the same origin for the authorization server and resource server, with an issuer containing no path. Publish protected-resource metadata at `/.well-known/oauth-protected-resource/mcp/<slug>` and authorization-server metadata at `/.well-known/oauth-authorization-server`. This avoids the cross-host and issuer-path discovery problems reported for claude.ai during the research.

Client registration defaults to Client ID Metadata Documents (CIMD). The reviewed claude.ai client ID was `https://claude.ai/oauth/mcp-oauth-client-metadata`. Advertise both `client_id_metadata_document_supported: true` and `none` in token endpoint authentication methods; the reviewed Claude documentation required both to select CIMD. Dynamic registration (DCR), deprecated in the reviewed modern MCP revision, is an optional explicit setting.

The original research proposed one-hour access tokens and seven-day grants. The current application contract issues access tokens (`gwo_`), refresh tokens (`gwr_`), and grants for up to 184 days from consent, covering at least six calendar months. Refresh tokens rotate on every exchange; reuse revokes the entire grant, and refresh does not extend its absolute expiry. Store token hashes, bind every token to a canonical endpoint audience, and check the grant on every request for immediate revocation. Existing issued expiry dates remain unchanged. See [the architecture](../ARCHITECTURE.md#73-tunnel-and-oauth) for the current contract.

Consent happens on the user's computer. The browser displays a comparison code and waits; an application dialog displays the same code, client host, redirect host, endpoint, permissions, and expiry. Deny receives initial focus. Closing, Escape, or five minutes without a decision denies access.

| Alternative | Reason not selected |
| --- | --- |
| Spring Authorization Server 7.1.2 / 7.2.0-RC1 | The reviewed artifacts lacked the required CIMD/RFC 8707 authorization-code/RFC 9207 behavior and assumed a broader authenticated-user login flow. |
| `spring-ai-community/mcp-security` 0.1.14 | It did support CIMD, but its JWT-oriented model, resource-identifier handling, and Spring AI coupling did not fit this gateway. |
| External IdP | Adds an account/process while consent still needs to occur locally. |
| JWT access tokens | The AS and RS share a process; a database lookup gives simpler immediate revocation. |
| ngrok as the default | Requires an account and token; its recorded free-tier limits and HTML interstitial complicate the initial experience. |
| A hosted relay | A separate service architecture, outside this desktop research. |

## 2. Evaluated components

| Component | Research version | Notes |
| --- | --- | --- |
| Spring Boot Web MVC | 4.1.2 | Tomcat 11.0.27; additional connector through `TomcatServletWebServerFactory`. |
| Jackson | 3.1.7 | Managed by Boot; `JsonNode.asString()` is available. |
| JDK | 25 | HTTP client, cryptography, literal IP parsing, process supervision. |
| Caffeine | 3.2.4 | Optional bounded metadata cache; a local map can suffice. |
| cloudflared | 2026.10.0 | Apache-2.0; pin and verify each platform asset. |
| MockWebServer and okhttp-tls | 5.5.0 | HTTPS CIMD fixtures with a test CA. |
| MCP TypeScript SDK, tests | 1.32.1 | Reference OAuth client, using legacy MCP on the wire. |
| MCP conformance, tests | 0.1.16 / 0.2.0-alpha.12 | The alpha has a separate authorization-server command. |
| Optional ngrok Node SDK | 1.7.0 | Alternative integration investigated, not required. |

The reviewed cloudflared Linux amd64 asset had SHA-256 `d33ff2d14475178d2012c2c56beba87389ac5ded27649519f198a7d3134a99db`. Release assets, licenses, and hashes must be supplied through the current build configuration; this report's historical pin is not a substitute for that configuration.

## 3. Public ingress and MCP

The public listener must reject everything except the currently published endpoint, OAuth routes, and OAuth metadata. The administration API is never exposed. Enforce the allowlist before the dispatcher, and independently reject public-port access in administration handlers/interceptors.

| Route family | Purpose |
| --- | --- |
| `/mcp/<published-slug>` | The same MCP dispatcher, authenticated only by an OAuth access token for this audience. |
| `/.well-known/oauth-protected-resource...` | Resource metadata. |
| `/.well-known/oauth-authorization-server` | AS metadata. |
| `/oauth/authorize`, `/oauth/authorize/status` | Browser consent initiation and polling. |
| `/oauth/token`, `/oauth/register`, `/oauth/revoke` | Token exchange, optional DCR, and revocation. |
| Explicit OAuth page assets | Only if required by the page's CSP; never a general static-file prefix. |

A local `gwt_` token or broker token must be rejected through the tunnel. Without valid OAuth credentials, every MCP method returns HTTP 401 before JSON-RPC dispatch. The response includes `WWW-Authenticate` with protected-resource metadata. No public tools or lazy-auth bypass are needed.

Use JSON responses only. The reviewed quick-tunnel service did not support SSE and imposed a limit of 200 in-flight requests. Human approval waits use MRTR or handle polling, not an unbounded HTTP connection. The gateway's tool modes and mutation approval rules remain unchanged for public callers.

When the tunnel is off, the public listener returns 404 for everything. Require the current public Host and reject ambiguous raw paths containing encoded separators, percent escapes, semicolon parameters, or traversal syntax. A public Host sent to the local listener must not bypass local-origin checks.

## 4. OAuth protocol

### 4.1 Discovery

Use one canonical origin, for example `https://gateway.example.com`, and resource `https://gateway.example.com/mcp/stage-readonly`:

```json
{
  "resource": "https://gateway.example.com/mcp/stage-readonly",
  "authorization_servers": ["https://gateway.example.com"],
  "scopes_supported": ["mcp"],
  "bearer_methods_supported": ["header"]
}
```

AS metadata declares code/query responses, authorization-code and refresh-token grants, public-client authentication (`none`), PKCE S256, CIMD, and `authorization_response_iss_parameter_supported`. Advertise `offline_access` in AS scopes so the reviewed Claude client requests refresh tokens; do not advertise it as a protected-resource scope. Include `registration_endpoint` only while DCR is enabled.

### 4.2 Authorization and local consent

Validate the client and exact redirect URI first. Invalid client/redirect combinations get an HTML error without redirecting. Then require `response_type=code`, S256 PKCE, exactly one matching resource, and permitted scope. Only after redirect validation may protocol errors redirect with `error`, `state`, and `iss`.

Canonicalize the resource consistently: lowercase scheme/host, remove a default port, reject fragments, and apply the selected trailing-slash policy. Never widen one endpoint grant to another.

Generate a six-character comparison code from `23456789ABCDEFGHJKLMNPQRSTUVWXYZ`, shown as `XXX-XXX` (30 bits). Limit pending consents to three per endpoint and authorization attempts to ten per minute per browser IP. The browser receives a random HttpOnly, Secure, SameSite=Lax cookie scoped to `/oauth/authorize`; only that cookie can poll its request. Return a redirect location once after consent resolves.

A new Allow replaces the previous active grant for the same client/endpoint pair. Consent is not a server-side authentication of the browser owner: the user must recognize the sign-in and match the code.

### 4.3 Token exchange and revocation

`POST /oauth/token` accepts form-encoded input. Bind authorization codes to client ID, redirect URI, resource, and S256 challenge. Codes are single use and expire after 60 seconds. Tokens contain 256 random bits; store only SHA-256 hashes. Return `Cache-Control: no-store`.

```json
{
  "access_token": "<new opaque access token>",
  "token_type": "Bearer",
  "expires_in": 15897599,
  "refresh_token": "<new rotating refresh token>",
  "scope": "mcp"
}
```

`expires_in` reports the remaining seconds before the grant expires; the example is a newly issued 184-day sign-in.

Refresh consumes the old token and creates a new one atomically. Reuse of a consumed refresh token revokes the grant. Replayed authorization codes also trigger defensive grant revocation. Invalid refresh returns `invalid_grant`, as expected by the reviewed Claude client. Reject query-string access tokens and mismatched audiences. Revocation follows RFC 7009's non-disclosing successful response for unknown tokens.

Store clients, grants, and hashed access/refresh tokens in SQLite. Keep pending consents and short-lived authorization codes in memory so a restart invalidates incomplete flows. Batch `last_used_at` updates to avoid a write-lock on every call. Changing a quick-tunnel hostname invalidates grants bound to the previous resource.

## 5. CIMD, registration, and network controls

Only fetch CIMD URLs on explicitly allowed hosts (default `claude.ai`), with HTTPS, a path, and no userinfo, query, fragment, or dot segments. Disable redirects; require HTTP 200 and JSON; cap the body at 5 KiB and total request time at five seconds. Reject special-use addresses. The hostname allowlist is essential alongside DNS checks; a single DNS check alone does not prevent rebinding.

Require the document's `client_id` to equal the requested URL exactly, public-client authentication method `none`, and an exact redirect match. Loopback-port exceptions are only for explicitly supported local clients. Cache successful documents according to Cache-Control within 60 seconds to 24 hours; the reviewed Claude document used 300 seconds. Do not cache failures. Revoke grants when a client's permitted redirect set changes.

Optional DCR accepts only permitted HTTPS redirects, returns public-client authentication, audits registrations, and does not bypass consent. Remove unused registrations after the selected retention window (research proposal: 30 days).

The research recorded Anthropic outbound IPv4 `160.79.104.0/21`; inbound API ranges are unrelated. Apply the outbound allowlist to discovery, MCP, token, registration, and revocation routes, not browser authorization routes. Keep the verification date visible because provider ranges change.

For Cloudflare, read `CF-Connecting-IP` only from the loopback tunnel connection. Do not trust a user-supplied `X-Forwarded-For` chain. A local process can forge forwarding headers, so OAuth authentication remains mandatory. Whether Cloudflare overwrites every malicious `CF-Connecting-IP` variant was **UNVERIFIED** in this research. Aggregate blocked requests rather than flooding audit storage.

## 6. Tunnel lifecycle and checks

Start quick tunnels with an explicit fallback-capable protocol selector:

```bash
cloudflared tunnel --url http://127.0.0.1:7466 --metrics 127.0.0.1:<metrics-port> --protocol auto
```

Read the assigned hostname from the loopback `/quicktunnel` metrics endpoint. Publish it only after `/ready` returns 200. Obtaining a hostname is not proof of an established edge connection. Without `--protocol auto`, the reviewed quick-tunnel code selected QUIC with fallback disabled; auto permits HTTP/2 over TCP 7844 if UDP 7844 is blocked.

Named tunnels use a stable hostname and `TUNNEL_TOKEN` through the environment, never command-line arguments. Verify a pinned binary hash before launch, preserve its license/notice, supervise it as a child, and terminate the process tree when stopped. Keep metrics loopback-only. Avoid inheriting unrelated cloudflared configuration or secret-bearing environment variables.

“Check public URL” must verify readiness, public IPv4 resolution, protected-resource metadata, AS issuer, and unauthenticated MCP 401 through the actual public HTTPS route. Use a short-lived, one-use self-test nonce to bypass the provider IP filter for this bounded probe. Do not follow redirects into a different origin. Report reachability separately from a successful client sign-in.

Warn that a quick-tunnel address changes on restart, that callers may request approved mutations, and when broad caller access or DCR is enabled. Production endpoint publishing was an open product decision in the original research; its recommendation was to deny it by default.

## 7. Security and failure modes

- Consent phishing remains possible: an attacker can begin a sign-in from their own claude.ai account, which uses the same provider IP range. Code comparison, “only if you started this sign-in,” rate limits, limited endpoint scope, and separate mutation approvals address this risk.
- A browser authorization page is public even when server calls are restricted by provider IP. Do not claim that other callers cannot reach any public page.
- Protect the consent page with a restrictive CSP, no framing, and `Referrer-Policy: no-referrer`. Any externalized page assets need an explicit matching ingress allowlist.
- Never log access tokens, refresh tokens, authorization codes, verifiers, or tunnel credentials. Audit setup changes and grant identifiers.
- Configure Tomcat's body limit with `Connector.setMaxPostSize(65536)`; the reviewed `setProperty("maxPostSize", ...)` call silently failed because that property did not belong to the protocol handler.
- Keep token exchanges fast and SQLite transactions short. The reviewed Claude deadlines were ten seconds for discovery/registration/token and thirty for refresh.
- A quick tunnel is a development service with no SLA. Corporate networks blocking both UDP and TCP 7844 cannot be repaired merely by changing the protocol selector.

## 8. Verification plan and recorded experiment

Unit tests cover RFC 7636's PKCE vector, URI canonicalization, code generation, CIMD URL validation, cache policy, and CIDR boundaries. Use real local Tomcat connectors, fake tunnel state, and HTTPS CIMD fixtures for deterministic integration tests.

The full local flow is 401 → resource metadata → AS metadata → authorize → local Allow → browser status → code exchange → tools/list → refresh → replay detection → 401. Repeat for Deny, expiry, revocation, hostname changes, malformed/foreign resources, redirect mismatch, plain PKCE, DCR disabled, and caller-rate limits. Enumerate every MVC handler against the public port to prove the admin API stays inaccessible.

Use the MCP SDK 1.32.1 OAuth client as a legacy reference. Conformance 0.1.16 does not have `--requirements` or modern support. In 0.2.0-alpha.12, AS scenarios use the separate `authorization` command, a registered test client, a resource URL, and a loopback callback. Test-only callbacks and HTTP issuers must not escape into release behavior.

The original live cloudflared experiment obtained a quick-tunnel hostname but could not establish an edge connection because SRV resolution/port 7844 were blocked; `/ready` stayed 503. That result established the failure path, not successful end-to-end publication. A real claude.ai sign-in, revoked grant, tunnel restart, and changed-hostname test remain separate manual checks.

## 9. Open questions and evidence

Unverified findings include Claude's exact DCR body, `iss` checking, MRTR support, fallback from enterprise JWT-bearer registration, some cross-origin discovery issue fixes, loopback authorization endpoint acceptance, and Windows Unix-domain socket feasibility. The proposal to use a non-HTTPS loopback authorization endpoint conflicts with the reviewed AS HTTPS requirement and must not be treated as an approved implementation.

Original evidence included Maven artifacts inspected with `javap`, npm packages, cloudflared source commit `8cc81c7`, the downloaded Claude CIMD, and provider documentation:

- [MCP authorization](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization) and [revision changelog](https://modelcontextprotocol.io/specification/2026-07-28/changelog)
- [OAuth 2.1 draft-13](https://www.ietf.org/archive/id/draft-ietf-oauth-v2-1-13.txt)
- [Client ID Metadata Document draft](https://datatracker.ietf.org/doc/draft-ietf-oauth-client-id-metadata-document/)
- [Claude authentication](https://claude.com/docs/connectors/building/authentication), [lazy authentication](https://claude.com/docs/connectors/building/lazy-authentication), [testing](https://claude.com/docs/connectors/building/testing), and [troubleshooting](https://claude.com/docs/connectors/building/troubleshooting)
- [Claude client metadata](https://claude.ai/oauth/mcp-oauth-client-metadata)
- [Anthropic IP ranges](https://platform.claude.com/docs/en/api/ip-addresses)
- [Claude discovery issue 82](https://github.com/anthropics/claude-ai-mcp/issues/82), [214](https://github.com/anthropics/claude-ai-mcp/issues/214), and [401/header discussion 219](https://github.com/anthropics/claude-ai-mcp/issues/219)
- [cloudflared source](https://github.com/cloudflare/cloudflared)
- [Cloudflare quick tunnels](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/), [forwarded headers](https://developers.cloudflare.com/fundamentals/reference/http-headers/), and [connection limits](https://developers.cloudflare.com/fundamentals/reference/connection-limits/)
- [ngrok free-plan limits](https://ngrok.com/docs/pricing-limits/free-plan-limits)
- [JDK HTTP client configuration](https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/module-summary.html)
