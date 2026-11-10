# GateRook MCP Firewall security model

GateRook is a desktop MCP Firewall for AI access to production infrastructure. It puts encrypted credentials, scoped permissions, exact-action approvals, and append-only, tamper-evident audit logs between AI clients and the services you connect. These controls apply to requests routed through GateRook.

## Control the path from an AI request to execution

An endpoint exposes only the connections and tools you select. GateRook authenticates the client, checks scope and policy, classifies the request, and records its receipt before execution. The shared invoker applies connection-scoped credentials, deadlines, rate limits, result limits, and masking. Connector policies add resource-specific restrictions, such as permitted schemas, HTTP destinations, or SSH runbooks.

| Tool mode | Execution behavior |
| --- | --- |
| **Off** | The connection/tool pair is not exposed or callable. |
| **Read only** | Allowed reads execute within policy limits; an action classified as a mutation requires approval. A read-only endpoint separately prohibits mutations and Bypass. |
| **Approval** | Both reads and changes require a review decision before execution. |
| **Bypass** | Explicitly enabled calls execute without human approval. Classification denials, integrity checks, production isolation, masking, and execution limits remain in force. |

Changes require approval by default. Permanent Bypass can be selected on standard endpoints, including production. Temporary bypass is narrower: it applies to one endpoint, connection, and tool for a fixed duration, and is available only when both environments are DEV or STAGE. Existing pending approvals still require their own decision.

Pausing a connection blocks new access across its endpoints; pausing an endpoint blocks new access through that endpoint. These switches preserve configuration and do not cancel operations already executing. Revoking a client grant or token prevents subsequent authorized requests.

## Keep credentials encrypted and scoped to the connector

Locally stored secrets use **AES-256-GCM envelope encryption**, with a separate data key for each secret version. Authenticated identity and version fields bind ciphertext to the intended secret. The master key is kept outside the application database. With OS credential protection enabled and a secure backend available, platform key storage protects the master-key file; the application shows the active protection status.

GateRook resolves configured credentials for the selected connector. Remote MCP OAuth sign-ins are stored as encrypted secrets and renewed by GateRook. The client secret of your own OAuth app goes only to the authorization server it was entered for, and Google Cloud credentials only to Google's own API hosts, whatever a server says about itself. AI clients use endpoint credentials instead of receiving the connected service's password or upstream OAuth token. External secret stores can supply values at call time; see [secret stores and key protection](../README.md#keep-credentials-encrypted-and-under-your-control).

This encryption protects secret values, not the entire SQLite database, all activity records, or every network connection. Use the connector's verified TLS or SSH host-key settings for transport protection. Portable full backups are separately passphrase-encrypted; see [backup and restore](development.md#full-backup-and-restore).

Masking reduces credential exposure in results, audit views, and notifications. It is not complete data-loss prevention: unknown secrets, sufficiently short values, or secrets transformed by an upstream service can escape recognition. Allowed query results still reach the chosen AI client and may reach its provider. The work of masking one value is bounded: text built to make the rules rescan themselves is masked around its credential keywords instead of being searched without limit, and a number with an extreme exponent is never written out in full.

## Review the exact action before it runs

For a change that requires approval, GateRook prepares and stores the canonical action, target, payload, and digest. The review shows the exact action, with connector-specific impact information where available. An agent's explanation is untrusted context; the stored action is what the decision authorizes.

Before execution, GateRook rechecks approval expiry, policy, relevant definition versions, integrity, and the action digest. Changed conditions invalidate approval rather than silently changing its meaning. The core executes the authorized payload itself; the client does not resubmit a replacement command. Failed or timed-out changes are not retried automatically, because the external outcome can be uncertain.

An MCP approval card or deep link opens review; it cannot grant approval through MCP. Desktop approval remains a control within the local OS account, not hardware-backed proof of human presence. See the [execution flow](ARCHITECTURE.md#5-call-flow) and [policy truth table](ARCHITECTURE.md#51-policy-truth-table).

## Preserve an append-only, tamper-evident audit trail

Activity connects requests, decisions, execution outcomes, and configuration changes. Audit records use append-only database constraints, hash chains, and authenticated heads. GateRook exposes no MCP tool that lets an agent edit or delete audit history.

The precise guarantee is **append-only, tamper-evident audit logs**, not universally immutable or tamper-proof storage. Retention intentionally removes eligible older records: the available periods are 1, 2, 3, or 5 years, with 1 year as the default. Call-history retention respects configured export progress. A detected integrity break is reported and explicitly re-anchored; this does not restore lost history or guarantee that all execution stops.

An accepted execution must have its receipt recorded first. If audit completion cannot be persisted, further calls are blocked until recovery; an external action that already completed cannot be undone by that failure. Local administrators or code with sufficient OS privileges remain outside an absolute immutability guarantee.

See [persistence and integrity](ARCHITECTURE.md#4-persistence-and-integrity), the [audit writer](../core/src/main/java/ai/msrv/gaterook/core/audit/AuditWriter.java), and the [retention implementation](../core/src/main/java/ai/msrv/gaterook/core/audit/internal/AuditRetentionJob.java).

## Apply stricter controls to production

Production connections require production endpoints. GateRook prevents mixing them across the production boundary, publishing production endpoints through its built-in tunnel, or approving production actions through Slack or Telegram. Issuing the first production bridge token for each grant requires desktop consent; that consent persists for the specific client and endpoint until revoked, while tool approval rules apply independently.

Local OAuth access, including VS Code, requires OAuth to be enabled on the endpoint and an explicit desktop consent for that sign-in. Its tokens remain bound to the local ingress and endpoint resource; revocation stops access. The same production tools and action approval rules apply. OAuth does not allow publishing production endpoints through the tunnel.

For a production connection, select a restricted upstream identity, verify transport, expose only the tools needed for the task, and keep changes in Approval mode when human review is required. Database grants, cloud IAM permissions, SSH account privileges, and the selected tool modes determine the available authority together.

## Understand connector and host trust

An external MCP server's description or `readOnlyHint` is not proof of safe behavior. MCP tools declared read only can use **Read only** without separate per-tool confirmation and remain labeled **Declared by …, not verified**. A tool that stops declaring read-only behavior cannot continue in that mode. Neither **Read only** nor **Bypass** outlives a change of the tool's definition (its title, description, parameters, annotations or the app it shows): the tool goes back to **Approval** until you choose the mode again for the definition it has then, and unlisting a tool and listing it again changes no choice you made. A temporary bypass lasts only while the tool keeps the definition it was granted for. Plugin tools require confirmation tied to the reviewed definition and version; changes reset that confirmation. See the [tool-mode rules](../core/src/main/java/ai/msrv/gaterook/core/endpoints/internal/ToolPairs.java) and [MCP setup guide](mcp-servers.md). A registry listing identifies a publisher, not a security certification. Local stdio servers and extension code need their own trust assessment: approval of calls through GateRook is not a general-purpose sandbox for everything that process can do.

The local **OS account is the trust boundary**. Touch ID, Windows Hello, protected key storage, signed internal requests, and UI safeguards reduce specific risks; they do not protect against arbitrary code with the same account's privileges. GateRook does not govern direct database connections, other MCP servers, or credentials used outside its execution path. It does not promise to prevent every prompt injection or every disclosure in allowed results.

An AI app's [connection import](ai-connection-import.md) is a proposal, not a configuration. It can name hosts and prepare credential sources, but it cannot make a decision that is yours: a proposal that contains a setting recording a **Trust** decision made after **Test connection** (an SSH host key or known-host lines, an HTTP certificate or pin trusted there, a pinned Kubernetes cluster, an allowed kubeconfig credential command), an AWS account pin or an option that runs a local command is refused. You set those through **Test connection** and **Trust**, or the connection form. Other TLS settings, such as a private CA certificate or a switch that relaxes verification outside production, are not refused: they appear on the candidate's card, and you should check them before you import. The import review shows each candidate's target, its non-default settings and every prepared credential source before anything is saved, and a candidate that reads a credential from a local file or an external reference is not selected until you choose its destination. Nothing is saved until you confirm the import.

For implementation details and the verification map, see [security invariants](ARCHITECTURE.md#9-security-invariants-and-verification). For connector-specific restrictions, start with [HTTP APIs](http-api-import.md), [external MCP servers](mcp-servers.md), [Oracle](oracle.md), [SQL Server](sql-server.md), [GCP logs](gcp-logs.md), and [SSH log files](ssh-log-files.md).
