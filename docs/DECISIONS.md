# Product decisions

Accepted product decisions take precedence over exploratory research. Dates identify when a decision was made; they do not imply that every resulting change has shipped.

| # | Date | Decision | Consequences |
| --- | --- | --- | --- |
| 1 | 2026-10-23 | Build the desktop application first: Electron, Angular, and a loopback core. | One local user, with decisions attributed to “you.” Server-mode identity, OIDC, roles, a hosted gateway database, and Docker/Helm deployment are outside the desktop scope. Disposable Docker test services remain useful. |
| 2 | 2026-10-23 | Use a GraalVM native core for fast API startup. | The same source runs on JVM 25 for development and tests. Dynamic Java plugins run in a separate JVM host; core dependencies must support native image. See [native-image research](research/15-graalvm-native.md) and [plugin-host research](research/16-plugin-host.md). |
| 3 | 2026-10-23 | Use Spring Data JPA and Hibernate for the gateway database. | SQLite with the community dialect, Flyway migrations, and schema validation. Spring AOT must retain the same behavior. The SQL connector still uses JDBC directly for user databases. |
| 4 | 2026-10-24 | Resolve the documented implementation choices using the accepted architecture. | The resulting product choices are recorded below; this historical decision does not grant blanket authorization for unrelated future actions. |
| 5 | 2026-10-24 | Defer a separate OS account for the core. | The OS user account remains the trust boundary. A process with the same account's privileges retains substantial access. |
| 6 | 2026-10-24 | Defer hardware-backed user-presence confirmation for approval and consent. | Content protection, a 700 ms activation delay, and no Enter-to-approve reduce accidental activation but do not establish a trusted human-presence boundary. |
| 7 | 2026-10-24 | Defer upstream OAuth for external MCP servers. | Configure upstream authentication with a secret passed through the server environment or HTTP headers. |
| 8 | 2026-10-24 | Bundle cloudflared; download ngrok only when selected. | Verify pinned SHA-256 hashes before execution. |
| 9 | 2026-10-24 | Keep SSH inside the native core. | Adapt MINA SSHD for native use first; consider sshj only if needed, and a separate JVM process only as a last resort. |
| 10 | 2026-10-24 | Application behavior follows accepted decisions when kit copy differs. | Keep the kit authoritative for reusable styles and components. Update kit copy deliberately and run its verifier. |
| 11 | 2026-10-27 | The product name is GateRook and the target slug is `gaterook`. | The intended new home is `~/.gaterook`, with no import, reading, or modification of `~/.mcp-gateway`. Target deep links, bridge bundles, package names, and branding follow the new name. Java/Maven and MCP metadata use `ai.msrv.gaterook`; first-party plugin packages use `gaterook.plugins.*`. Keep `GW_*` and `gateway.*` configuration names. |
| 12 | 2026-10-27 | Keep the complete application and local build in this public repository. | Include source, design kit, build manifests, locks, local tools, architecture, and curated research. README must document build and launch commands. All documentation is English. Internal AI files and progress logs are excluded; root `ai/` is ignored and must never be committed. |

**Naming implementation status:** the Java namespace has been updated, but the imported runtime still contains legacy `mcp-gateway` names and paths. This source migration preserves behavior. Decision 11 describes the intended remaining rename, not an automatic migration of existing installations. The documented development launcher uses a repository-local home, independent of either production home.
