# 03 · Connecting desktop clients

> Research snapshot: 2026-10-23. Scope: one local user and the core on `127.0.0.1:7465`. This report records source inspection and experiments from that date; it is not a claim that every current client/platform combination has been tested. **UNVERIFIED** preserves unresolved compatibility questions. Related reports: [MCP runtime](01-mcp-protocol.md), [OAuth](02-mcp-auth-claudeai.md), and [desktop stack](14-platform-stack.md).

## 1. Decision

Use a revocable client grant, a local token broker, and thin bridges. A grant authorizes one client to one endpoint. Client configuration stores its non-secret ID, such as `gr_Cx7q2LmA`; obtaining a token additionally requires access to the broker's OS-protected IPC endpoint.

Electron main hosts the broker through Node `net`: a Unix-domain socket under the application home on macOS/Linux, or a per-user named pipe on Windows. On Unix, use a 0700 parent directory and 0600 socket. The broker asks the core for 15-minute credentials using the launch-only internal authentication channel. Revoking a grant immediately invalidates all its tokens.

Claude Desktop gets one `.mcpb` extension per endpoint, containing a small dependency-free Node bridge. Codex gets an HTTP server entry with `http_headers_helper`, which runs the Java/native `gw-bridge headers` command. Cursor and Antigravity support an explicit token file, OAuth, or a proposed stdio bridge alternative. The app rewrites a stable bridge launcher in its home directory on startup so client configurations survive application moves and updates.

Do not enable Electron's `RunAsNode` fuse to implement the helper: that would allow another process to execute code under the signed application's identity. Node SEA adds a large runtime per platform. A secret file plus loopback broker HTTP exposes a reusable disk secret. Serializing an entire JSON/TOML client configuration unnecessarily alters unrelated entries and comments. One MCPB containing all endpoints would require a different tool-namespacing model.

## 2. Client contracts and detection

All paths below are application conventions or documented user-level configuration paths; actual discovery must handle platform variants and overrides. Do not edit project-scoped configuration.

| Client | User configuration / integration | Apply and verification |
| --- | --- | --- |
| Claude Desktop | `.mcpb` extension; fallback `claude_desktop_config.json` under the platform's Claude data directory | Open the extension install dialog. For the JSON fallback, ask the user to quit and reopen Claude. Mark connected after the bridge first reaches the broker. |
| Codex | `$CODEX_HOME/config.toml`, default `~/.codex/config.toml` | Requires helper support, first found in 0.148.0. Desktop MCP-server Restart or a new CLI session applies changes. |
| Cursor | `~/.cursor/mcp.json` | HTTP uses `url` and `headers`; stdio uses command/args. Current automatic reload and initial enablement need verification. |
| Antigravity | `~/.gemini/config/mcp_config.json`; recognize the older `~/.gemini/antigravity/mcp_config.json` when appropriate | `serverUrl` or `url`, plus headers. Use the client's refresh/reload action. The App's exact config convention remained unverified. |
| MCP Inspector | User-provided protected config file | Do not mutate its global configuration. |

For Claude on Windows, MSIX installations may use `%LOCALAPPDATA%\Packages\Claude_*\LocalCache\Roaming\Claude\`; legacy installations may use `%APPDATA%\Claude\`. Some MSIX findings came from a secondary troubleshooting report and require real-platform verification. Linux extension support, install associations, exact paths, embedded Node version, and minimum manifest-compatible Claude version were unresolved.

The reviewed Codex helper is a shell command **string**, not an argv array. Codex executes it with a cleaned environment, a ten-second deadline, and a 64 KiB output limit. It expects a JSON object of HTTP headers and rejects reserved transport headers. After a qualifying same-origin POST 401/403, it invokes the helper once and retries only if the header values changed. The broker must therefore issue a new token on each helper call. Helper operation is unavailable in remote execution environments.

Explicit settings avoid discrepancies between documentation and source defaults:

```toml
# >>> GateRook managed entry
[mcp_servers.gateway-dev]
url = "http://127.0.0.1:7465/mcp/dev"
http_headers_helper = "~/.mcp-gateway/bin/gw-bridge headers --endpoint dev --grant gr_Cx7q2LmA"
default_tools_approval_mode = "writes"
startup_timeout_sec = 20
tool_timeout_sec = 60
# <<< GateRook managed entry
```

`writes` prompts for tools without `readOnlyHint: true`; this client prompt does not replace gateway approval. Older Codex versions may silently ignore the helper key, or reject it under strict configuration, so version detection is required.

For MCPB, use manifest 0.3 fields supported by the evaluated CLI, `tools_generated: true`, a per-endpoint display name, `server.type: "node"`, and `compatibility.runtimes.node`. Claude ships a Node environment on the evaluated macOS/Windows platforms. Generate the bundle with the Node bridge and required icon; do not embed a token.

On Windows, Node spawning a `.cmd` without a shell is unsupported after the relevant Node security fix. For stdio clients that launch argv directly, use the executable Java/native bridge path, not a `.cmd` launcher. Quote Windows TOML and shell commands using the target parser's rules, not JSON escaping.

Cursor install links encode a single server entry with standard base64 and URI encoding. Never include a bearer token in the link. Token mode creates the actual credential only when the user confirms; previews and audit records show a placeholder.

## 3. Broker and bridge protocol

One IPC connection carries one newline-terminated JSON request, at most 4 KiB. Bound the server wait to three seconds and simultaneous connections to 32.

```json
{"v":1,"op":"token","client":"codex","endpoint":"dev","grant":"gr_Cx7q2LmA","pid":48211,"bridge":"1.0.0"}
```

```json
{"v":1,"ok":true,"token":"<short-lived bearer token>","expiresAt":"2026-10-22T12:44:00Z","url":"http://127.0.0.1:7465/mcp/dev"}
```

Errors distinguish bad request, unknown/revoked grant, endpoint mismatch/off, unavailable core, and rate limiting. Research limits were twenty token issues per minute per grant and at most four live tokens. A bridge accepts only a loopback HTTP URL with its own endpoint path; a changed local port can be obtained from the broker without rewriting stdio configurations.

`gw-bridge headers` writes only the header object to stdout. Exit codes distinguish unavailable app (1), revoked/unknown grant (2), and arguments (3). Codex discards helper stderr, so write a sanitized diagnostic to the application's bridge log and client status. Never write the token there.

The stdio bridges preserve JSON-RPC messages, send the negotiated protocol version plus required method/name headers, and include both JSON and event-stream Accept types. Notifications that receive HTTP 202 produce no stdout response. Refresh once on 401. Keep stdout exclusively for protocol JSON and serialize concurrent responses safely. Report gateway errors without exiting unnecessarily, so a transient unavailable core does not permanently disable the client integration.

Java and Node bridge implementations must share the same conformance fixtures. The bridge makes no policy decisions and never resolves connector secrets. Audit identity comes from the grant, not from self-reported PID or client name.

## 4. Minimal, reviewable configuration edits

The Connect flow first previews the exact affected file, resolved path, diff, permission change, backup name, and client reload instructions. Confirm applies that exact preview only if the file's hash still matches. A stale hash returns a conflict and requires a fresh preview.

For JSON, use a strict parser with token positions to locate the managed member. Splice only that member and its necessary comma/indentation. For TOML, append or update a clearly delimited table block; parse before and after to verify semantic equivalence outside the intended entry. Reject unsupported inline `mcp_servers` tables with a manual insertion snippet instead of rewriting the file.

The invariant is:

```text
tree(after) == tree(before) with only the requested managed entry changed
bytes outside the edited span remain unchanged
```

Preserve indentation, CRLF/LF, BOM behavior, final newline, unrelated keys, and comments where the format allows them. Detect collisions: an unrelated `gateway-dev` entry gets a new suffixed name instead of being overwritten. Reject malformed input, JSONC where strict JSON is required, non-regular files, and files over 1 MiB. Show parse line/column information.

Before writing, make a collision-safe sibling backup (`.bak-YYYY-MM-DD`, then numbered suffixes) and an app-managed backup, retaining the selected history (research proposal: twenty). Write a temporary sibling file, set appropriate permissions, flush it, and atomically replace the original. Resolve and display symlinks consistently; never conceal the true target. A new file or any file containing a token is 0600 on Unix.

Disconnect reverses only the managed entry and revokes its grant/token. It does not blindly restore an old file and discard later user edits. Restore is a separate current-to-backup preview and atomic write. Track managed fragment hashes and handle missing/orphaned TOML markers safely.

## 5. Connection form and health checks

The form selects client, endpoint, method, entry name, grant label, optional explicit-token lifetime, and supported client-specific settings. The token value is never returned to the renderer/API. A config-path override must still meet file safety checks.

Research defaults were seven-day explicit tokens and 15-minute broker tokens. For endpoints containing production connections, the proposal was to disable token-in-file mode, prefer bridge/OAuth, shorten lifetimes, and show the production scope. This is a product-policy recommendation; current behavior is defined by the application.

“Check” should validate the config, managed entry, path and executable, broker reachability, and grant status. Run the exact configured helper/bridge using the client's invocation semantics. For stdio, initialize and list tools. Distinguish that self-test from evidence that the actual client has called: use last grant activity and recorded protocol revision. A generated MCPB is not proof that Claude installed it.

A configuration watcher detects deletion or external modification. Show “Removed outside the app” or “Needs repair”; Repair is another preview/confirm operation. Do not silently restart another application or overwrite its changes.

## 6. Trust boundaries

The broker isolates OS users, not arbitrary processes running under the same account. On Unix, a different-UID connection to the protected socket returned `EACCES` in the recorded experiment. On Windows, reviewed libuv used `FILE_FLAG_FIRST_PIPE_INSTANCE`, a default security descriptor, and no `PIPE_REJECT_REMOTE_CLIENTS`. Explicit per-user DACL/peer verification was identified as possible hardening, not claimed implemented protection.

Avoid port/pipe squatting, stale socket takeover, and accidental disclosure: detect an already-live broker, reject unexpected URLs, keep credentials short-lived, and validate grants on every core call. Do not put secrets into deep links, argv, previews, logs, screenshots, or audit entries. Explicit token-file mode must be clearly labeled and access-restricted.

The scope is user configuration only. Project `.cursor`, `.codex`, or `.agents` configuration can be committed by a user and must never receive a generated secret. Application/source identifiers and compatibility paths should not be casually renamed during a migration.

## 7. Dependencies and implementation areas

The evaluated implementation used Jackson 3.1.7 (JSON tree/streaming parser and TOML parser), java-diff-utils 4.17, JDK 25 IPC/ZIP/HTTP/file APIs, and a dependency-free Node bridge requiring Node 18 or newer. Test clients included MCPB 2.1.2, Codex 0.162.1, and Inspector 2.12.0. JNA was only a possible Windows broker-hardening option.

Core responsibilities include client adapters, JSON/TOML editors, atomic writes/backups, diff models, bundle building, bridge launchers, drift tracking, and internal token issuance. Electron owns local IPC and opening installer dialogs. Persist grants separately from client configuration links; a link records the entry, path, written hash, backup, method, and associated grant/token.

## 8. Verification and recorded results

Golden tests must cover absent/empty files, missing objects, indentation, CRLF, Unicode, BOM, no final newline, collisions, reconnect, strict JSON rejection, inline/dotted TOML, lost markers, stale hashes, symlinks, and file modes. Check both semantic equivalence and byte preservation outside the edit span.

Broker tests cover size/time limits, grant revocation, rate limits, stale sockets, a competing instance, and another UID. Test both bridges against identical initialize/list/call/notification/401 fixtures. Validate and unpack the generated MCPB with the evaluated CLI, then run the unpacked Node bridge.

The original experiment ran Codex CLI 0.162.1 against a fake MCP server without using a model: `codex mcp get --json` accepted and redacted the helper; `codex app-server` plus `mcpServerStatus/list` invoked it; a 401 caused a fresh token and successful retry. This does not verify every Codex desktop release. Cursor and Antigravity were covered by configuration goldens, with interactive platform testing still required.

Use disposable client homes for experiments, never the user's live config. Scan all test logs, preview snapshots, and audit output for complete canary tokens. Platform checks include named pipes, command quoting, executable selection, MSIX discovery, and macOS socket-path length.

## 9. Open questions and sources

Unresolved items included Claude extension upgrades/uninstall locations, Linux support, embedded Node version, `.mcpb` file associations, Cursor current enablement/reload and DCR redirects, Antigravity App Origin behavior and Windows paths, and sandbox access to Unix sockets. The original bridge startup/RSS measurements were not independently repeated. Keep “connected,” “configuration written,” and “self-test passed” distinct.

- [Codex MCP documentation used by the research](https://learn.chatgpt.com/docs/extend/mcp?surface=app)
- [Codex helper implementation PR](https://github.com/openai/codex/pull/38245) and [0.148.0 configuration types](https://raw.githubusercontent.com/openai/codex/rust-v0.148.0/codex-rs/config/src/mcp_types.rs)
- [MCPB repository](https://github.com/modelcontextprotocol/mcpb), [manifest 0.3 schema](https://github.com/modelcontextprotocol/mcpb/blob/main/src/schemas/0.3.ts), and [Claude MCPB guide](https://claude.com/docs/connectors/building/mcpb)
- [Claude local MCP setup](https://support.claude.com/en/articles/10949351-getting-started-with-local-mcp-servers-on-claude-desktop)
- [Cursor MCP](https://cursor.com/docs/context/mcp) and [install links](https://cursor.com/docs/context/mcp/install-links)
- [Antigravity MCP](https://antigravity.google/docs/mcp)
- [Node IPC API](https://nodejs.org/api/net.html), [libuv Windows pipe implementation](https://github.com/libuv/libuv/blob/v1.x/src/win/pipe.c), and [Windows named-pipe security](https://learn.microsoft.com/en-us/windows/win32/ipc/named-pipe-security-and-access-rights)
- [MCP Streamable HTTP](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http)
