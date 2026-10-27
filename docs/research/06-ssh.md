# 06 · SSH and runbooks

> Research snapshot: 2026-10-23. Recorded experiments used Java 25, Apache MINA SSHD 2.20.0, Bouncy Castle 1.86, in-process SSH servers, OpenSSH 9.6p1, and Vault 2.2.1. The independent review repeated key SSH behavior but not every Vault chain or timing measurement. **UNVERIFIED** retains those limits. Related: [secrets](12-secrets.md), [MCP approvals](01-mcp-protocol.md), and the [architecture](../ARCHITECTURE.md).

## 1. Decision

Use Apache MINA SSHD with explicit client hardening, exec channels, typed runbooks, and a fail-closed host-key verifier. Provide `ssh_run` and `ssh_exec`. A mutating runbook always requires approval; arbitrary shell commands always classify as MUTATION, even when they look harmless. Do not attempt to prove a shell command read-only by parsing it.

MINA provides both the production client and an in-process server for tests. Alternatives considered were sshj, JSch, a system ssh process, and MINA 3 milestones. The selected stable line avoids a second test-server library and keeps credentials in process. Do not use a shell channel, PTY, agent forwarding, X11 forwarding, SFTP, or SCP.

Replace MINA defaults explicitly:

- Use a strict per-connection server-key verifier instead of `AcceptAllServerKeyVerifier`.
- Set `HostConfigEntryResolver.EMPTY` instead of reading the user's SSH config.
- Use `KeyIdentityProvider.EMPTY_KEYS_PROVIDER` instead of loading ambient private keys.
- Keep the agent factory null and add identities only to the intended session.

A test must assert these settings; merely constructing a default MINA client is unsafe for this application.

## 2. Dependencies and credentials

| Component | Evaluated version | Purpose |
| --- | --- | --- |
| `sshd-core` / `sshd-common` | 2.20.0 | SSH client and test server; explicit parent-POM pin. |
| `bcprov-jdk18on` / `bcpkix-jdk18on` | 1.86 | Ed25519, encrypted PKCS#8, and supported hybrid key exchange in MINA 2.x. |
| SLF4J | Boot-managed | Exclude conflicting `jcl-over-slf4j` transitive bridge. |
| JUnit / MockWebServer | Test dependencies | SSH behavior and Vault signing fixtures. |

Bouncy Castle was required for the reviewed MINA 2.x Ed25519 and encrypted-key paths. Avoid the older optional EdDSA implementation. Static jdeps results do not prove a reduced runtime contains every reflective dependency; run SSH integration tests against the final runtime/native distribution.

Supported authentication proposals are password, private key with optional passphrase/certificate, and Vault SSH CA. Recorded key tests covered OpenSSH encrypted/unencrypted keys, PEM PKCS#1/PKCS#8, Ed25519, ECDSA, and RSA. Private material is resolved only inside the connector and never copied to ordinary configuration or logs.

Vault CA creates an ephemeral key in memory and signs its public key for a short TTL, normally five minutes. A fresh mutation session carries a certificate key ID such as `gw:<connection>:<approvalId>` for correlation with sshd authentication logs. The reviewed role needs `allow_user_certificates`, allowed principals, bounded TTL, and `allow_user_key_ids`; bastions additionally need port-forwarding permission. The exact end-to-end signing performance was not independently remeasured.

## 3. Tools and results

`ssh_run` accepts a saved runbook name and typed parameters selected for the endpoint/connection. Describe parameter signatures in tool text because some clients ignore conditional JSON Schema. A missing/disabled runbook or invalid parameter is DENY. A read-only runbook is READ, but endpoint Approval mode still requires a decision. A mutating runbook is MUTATION regardless of a read-oriented tool mode.

`ssh_exec` accepts an exact shell command up to the configured length (research limit: 8,000 characters), rejects NUL, and is unavailable under runbook-only or `allowExec=false` policy. It defaults Off, and has no Read only mode. Enabling arbitrary commands on production is an explicit policy decision.

Both tools can return pending approvals, so do not advertise a final-execution-only output schema. Set conservative annotations explicitly rather than relying on MCP defaults. The manifest's egress classification is separate from `openWorldHint`: unrestricted string parameters or arbitrary execution can form an external channel.

A result records command/runbook, exit code/signal, separate stdout/stderr, byte counts, truncation, timeout state, and duration. A completed nonzero exit is ordinarily data (`systemctl status` can return 3 for a stopped service); connection/authentication failures, deadlines, and exit signals are errors. Escape ANSI CSI/OSC and control characters before presentation; final masking belongs to the core.

Research limits were thirty seconds, 256 KiB stdout, 64 KiB stderr, and bounded concurrent channels. Retain the beginning of output and clearly identify truncation. Never allocate an unbounded buffer before enforcing the limit.

## 4. Typed runbook compiler

A runbook stores a stable name, description, version, read-only declaration, one-line command template, optional working directory, deadline, and parameter definitions:

```json
{
  "name": "compose-logs",
  "description": "Show recent logs for one service",
  "readOnly": true,
  "version": 3,
  "command": "docker compose logs --tail {lines} {service}",
  "workdir": "/srv/example-app",
  "timeoutSeconds": 20,
  "params": [
    {"name": "service", "type": "enum", "values": ["api", "worker"], "required": true},
    {"name": "lines", "type": "int", "min": 1, "max": 500, "default": 100}
  ]
}
```

Every placeholder must be declared and used. Placeholders must appear outside quoted strings and heredocs; a small lexer tracks quotes and escapes before substitution. Quoting a value inside an already quoted template can reopen shell syntax and defeat escaping.

Validate each value first, then POSIX-single-quote it. Strings require an explicit pattern and length bound (proposal: at most 256); enums have bounded control-free values; integers require min/max; booleans map only to permitted fixed literals. Reject control characters and leading dashes by default. Shell quoting prevents shell expansion, but not option injection, so templates should also use `--` where the command supports it.

A read-only declaration is the template author's assertion, not a proof. Warn about commands containing restart/stop/remove/kill/sudo/redirection, and audit a change from mutating to read-only. Warn about follow/watch commands that will run until the deadline.

The approver sees the runbook, parameters, exact wire command, connection, identity, deadline, and any sudo/analysis warnings. Include `cd '<workdir>' &&` and a remote timeout wrapper if used. Bind approval to tool, connection configuration version, runbook version, and wire-command digest. If the runbook changes after approval, do not execute the new command under the old decision.

## 5. Host trust and bastions

Trust on first use is available only through Test connection. Capture the presented host key before authentication, display its fingerprint for independent comparison, and send no credential until the user explicitly trusts it. A changed pinned key gets a distinct danger state and replacement flow, not an automatic first-use prompt.

Match known_hosts entries with the correct host and port, including hashed entries where supported. A port-22 entry must not silently authorize a different port. The original MVP proposal did not implement host certificates and revoked-entry handling; these need explicit semantics before claiming support. Never interpret an unsupported trust marker as a successful match.

A bastion has its own credentials and pinned identity. The reviewed design used a tightly scoped loopback forward, connected to the target, and immediately closed the listening forward. The target host key remains independently verified. A local process might connect during the short listener window, but receives no target credentials and cannot bypass target authentication.

MINA's built-in ProxyJump path was rejected for this design because its shared client identity and host-key context complicated separation. A Vault bastion certificate needs `permit-port-forwarding`, and sshd must permit forwarding; translate refusal into a clear diagnostic.

## 6. Execution, cancellation, and session lifetime

Create an exec channel with no PTY, separate bounded output streams, and close stdin immediately. The recorded `cat` experiment otherwise waited indefinitely for input. Wait only until the effective deadline.

On timeout, cancellation, or output exhaustion, send the SSH `signal` request for TERM, wait a short grace period (proposal: two seconds), then KILL and close the channel. The reviewed MINA API needed an explicit RFC 4254 signal message. OpenSSH 9.6 delivered TERM to the session process group, including a child sleep. Closing the channel or TCP session alone left the remote command alive.

This mechanism is not universal: OpenSSH forced-command/subsystem sessions reject signal requests. If the laptop crashes or sleeps before signaling, a remote command can survive. Optional `timeout -k ... sh -c ...` on the host supplies an independent deadline, but changes the exact approved command and requires a supported remote utility. Whether to enable it by default for production was open.

Reuse sessions only for reads, with bounded idle and credential lifetimes. Open fresh sessions for mutations. SSH certificate expiry is checked at authentication, so the pool must close sessions by the certificate's expiry; otherwise a five-minute credential can leave a much longer-lived session. Handle clock skew between Vault, client, and server explicitly.

Limit channels below the server's maximum; the reviewed OpenSSH default was ten, and the proposal used eight. Retry a failed channel open on a new session only when execution has not begun. Treat CLOSED with no exit status as an execution-channel failure when the server rejected exec; do not call it successful merely because no exception was raised.

## 7. Settings, diagnostics, and policy

Connection configuration includes host/port/user, pinned keys or supplied known_hosts, credential method, optional bastion, timeouts, keepalive, reuse/concurrency, legacy algorithm opt-in, remote deadline, and whether to probe sudo. Private keys, passphrases, and passwords are write-only; host keys and public certificates are not secret values.

Disable SHA-1 `ssh-rsa`, CBC ciphers, and SHA-1 MACs by default. A legacy-algorithm exception must be explicit. Do not inherit SSH config, local key files, agent identities, arbitrary environment requests, or forwarding capabilities from the machine.

Test connection proceeds through TCP, KEX/host-key trust, authentication, and a bounded diagnostic exec. Show root, Docker group, sudo behavior, noninteractive PATH, and required commands. `sudo -n true` can generate audit/SIEM events and should be configurable. Do not claim it proves unrestricted sudo: it proves the tested command's noninteractive permission, while exact runbook sudo policy still requires inspection.

The supported remote shell contract is POSIX on Unix. Fish, csh, Windows cmd/PowerShell, restricted shells, and ForceCommand can invalidate quoting or cancellation assumptions. Test and explain these limits. Do not send a sudo password or enable PTY to bypass a failed precondition.

Audit masked parameters, wire-command digest, exit/signal, output counts, truncation, host-key fingerprint, and certificate serial/key ID. Never audit the private key or secret values. A lightweight health probe should not mint certificates or execute privileged commands unnecessarily.

## 8. Verification strategy

Property-test quoting through a real POSIX shell with hostile strings, substitutions, backticks, quotes, newlines, wildcard characters, empty input, and Unicode. Run the JVM with a UTF-8 native encoding so ProcessBuilder does not corrupt the test arguments. Reject placeholders inside quotes and heredocs.

Use an in-process MINA server for password/key/certificate auth, unknown and changed keys, port-sensitive known_hosts, output limits, EOF, concurrent channels, and bastions. Use real OpenSSH for behavior the fixture cannot establish: exit signals, process-group termination, MaxSessions, forwarding restrictions, and auth-log correlation. Run separate tests for host disconnect and a surviving remote process.

Vault fixtures validate sign requests and certificate use without an external service. Optional real Vault/OpenSSH tests cover TTL/principal/key-ID restrictions, clock skew, forwarding extensions, and certificate expiry. The original review confirmed API/source behavior and many SSH runs, while the full Vault-to-OpenSSH chain and latency figures were not independently repeated.

## 9. Remaining questions and sources

Open items included production remote-deadline defaults, host certificates/revoked entries, FIDO keys, per-call passphrase prompts, and the precise design wording for read-only runbooks under endpoint Approval mode. Full terminal recording and SFTP were outside the proposed scope; sanitized stdout/stderr and audit records serve the exec use case.

- [Apache MINA SSHD](https://github.com/apache/mina-sshd)
- [MINA dependencies](https://github.com/apache/mina-sshd/blob/master/docs/dependencies.md)
- [RFC 4254, SSH connection protocol](https://www.rfc-editor.org/rfc/rfc4254.txt)
- [OpenSSH release notes](https://www.openssh.com/releasenotes.html)
- [OpenSSH portable source](https://github.com/openssh/openssh-portable)
- [Vault SSH secrets engine](https://developer.hashicorp.com/vault/docs/secrets/ssh)
- [Vault 2.2.1 source](https://github.com/hashicorp/vault/tree/v2.2.1/builtin/logical/ssh)
- [MCP schema and annotation defaults](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/schema/2026-07-28/schema.ts)
- [Maven Central](https://repo.maven.apache.org/maven2/)
