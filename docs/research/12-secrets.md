# 12 · Secrets: envelope encryption, external stores, and masking

Research date: **2026-10-23**. Scope: a single-user desktop gateway with a local SQLite database. The experiments described here are historical evidence, not a new migration verification run. See the [architecture](../ARCHITECTURE.md), [cloud credentials](09-cloud.md), [SQL connections](04-sql.md), and [SSH](06-ssh.md).

## 1. Security boundary and selected design

**Agents have no secret-management tools and cannot name a secret or secret reference in tool arguments.** A person binds secret fields in connection settings. `describe_connection` exposes only whether credentials are stored, referenced, or short-lived. Admin responses never return secret values, including through a Show action or duplicate-connection operation.

Separate four responsibilities: local envelope encryption; master-key lifecycle and backup; external store resolution; and invocation-scoped secret handling/masking. A connector receives a resolver limited to one connection and one invocation. The resolver registers every released value with the call's taint set. Neither plugin code nor connector context should obtain the master key, a general secret-store client, or another connection's resolver.

The recorded implementation experiments used Tink **1.24.0**, protobuf **4.36.2**, Jackson **3.1.7**, Vault **2.2.1**, AWS SDK **2.57.3**, google-auth **1.54.0**, MockWebServer **5.5.1**, Temurin **25.0.5**, and Electron **45.0.0**. Current dependency manifests remain authoritative.

## 2. Local envelope encryption

Generate a fresh Tink AES256_GCM data-encryption key for each secret **version**. Encrypt the value with associated data binding its identity and version:

```text
mcp-gateway/secret/v1\0<secret_uuid>\0<version>
mcp-gateway/dek/v1\0<secret_uuid>\0<version>
```

The first domain binds the value; the second binds the wrapped DEK. Store ciphertext, wrapped DEK, wrapping key ID, and version separately. Row/version substitution must fail authentication. The recorded Tink format added 33 bytes to ciphertext and produced a 144-byte wrapped DEK. Warm-JIT open/seal measurements were approximately 15/16 microseconds, making a decrypted-local-value cache unnecessary.

Use `AeadConfig2026.get()` and the explicit Configuration overloads. `KeysetHandle.Builder.build()` without `setConfiguration` can fall back to the global registry and fail with no registered key creator. Tink 1.23 is not a drop-in fallback: the new configuration APIs differ and would require an adapter.

A separate wrapped DEK allows master rotation without rewriting secret ciphertext. Tink's built-in KmsEnvelopeAead was rejected because its embedded DEK format would make this lifecycle harder to control. Bare JCA encryption would require maintaining nonce, serialization, and key-status logic already supplied by Tink.

## 3. Master-key file and OS protection

The master keyset lives under the application's data home. The directory is owner-only (`0700`) and the Unix file is `0400`; Windows needs equivalent owner-only ACLs. Write atomically through a `0600` temporary file, flush it, set final permissions, and rename. Detect loose permissions and offer repair. Warn when the key lives in a synced folder. Database backups exclude the master key and its OS-protection blob.

Without OS protection, the keyset file is cleartext key material protected by filesystem permissions. With OS protection, Electron safeStorage protects a separate random 32-byte **file key**. Electron decrypts that small blob at startup and passes the file key to the core over stdin; the core reads and writes the encrypted Tink keyset itself. This keeps master rotation and backup inside the core.

Use Electron's asynchronous safeStorage API. The recorded Electron 45 API returned `{result, shouldReEncrypt}` when decrypting; refresh the protected blob when requested. Linux `basic_text` is not real OS protection and must display unavailable. DPAPI protects against other users but not all applications in the same user session. Keychain behavior and signing changes need platform validation.

The launch bootstrap is one bounded stdin line carrying the launch secret and optional file key. It is not an argument or environment variable. Keep stdin open and terminate the core on EOF so it does not outlive its desktop owner. The key must never appear on stdout or in logs.

## 4. Rotation, replacement, backup, and recovery

Master rotation is resumable:

1. Add a new primary key and durably write the keyset before using it.
2. Rewrap DEKs in the background, preserving each ciphertext and recording a rotation target.
3. Resume after interruption until every required DEK uses the target key.
4. Disable old keys only after rewrapping completes; remove them only after a backup of the new keyset is confirmed.

Serialize keyset writes and backup with one lock. Restore must verify that the supplied keyset covers all referenced key IDs before replacing the file. Missing-key mode exposes recovery actions while refusing operations that need inaccessible values. Losing a key without backup means re-entering affected local secrets; external references have a separate lifecycle.

A password-protected backup uses PBKDF2-HMAC-SHA256 with a random salt and 600,000 iterations to derive an AES-GCM key around an encrypted Tink keyset. The recorded derivation took about 331 ms. A passwordless backup contains cleartext key material and must be clearly identified as such. This is the investigated format, not a recommendation to weaken OS or file protection.

Secret **Replace** creates a pending version. If requested, test the connector against that version before switching. **Rotate** is an optional connector capability that changes the credential at its source, tests it, and then activates the new version. A provider that cannot issue its own replacement token must show a reason and use Replace instead. Production changes require an explanatory setup-audit entry.

The proposal retains only the immediately preceding secret version for seven days, then deletes its wrapped DEK. Set SQLite `secure_delete=ON` on **every connection**; it is not a persistent file option. Truncate/checkpoint the WAL after cryptographic deletion. This does not promise physical erasure from arbitrary backups or storage media.

## 5. External stores and reference syntax

| Store | Reference form | Resolution |
| --- | --- | --- |
| Vault/OpenBao | `vault://path?version=n#key` | Discover mount and KV version, then read the selected field |
| Vault dynamic credentials | `vault://database/creds/role` | Return a scoped login plus lease |
| GCP Secret Manager | `gcp-sm://projects/p/secrets/s/versions/n#jsonKey` | Access selected version; regional references also supported by the API |
| AWS Secrets Manager | `aws-sm://region/name?versionStage=AWSCURRENT#jsonKey` or an ARN form | GetSecretValue, optionally pinned by version ID |

Use an explicit parser that canonicalizes query before fragment and can recognize the legacy fragment/query order. Bind every reference to a configured store ID; do not infer a new external host from a tool call. An unconnected store produces an actionable resolution error.

Vault mount preflight uses `sys/internal/ui/mounts/<path>` to identify nested mounts and KV v1/v2. It still requires capability on a path within the mount; a 403 means access is missing, not that the mount does not exist. Cache the verified mount metadata with the reference.

GCP credentials come from the shared cloud service, preferably ADC impersonation with short-lived tokens. Global and regional `versions:access` endpoints differ. Verify `dataCrc32c` when supplied. AWS uses AssumeRole, ExternalId, and a session policy permitting GetSecretValue only for the configured reference ARNs, plus KMS decrypt when a customer-managed key requires it. Cross-account references require complete ARNs.

Cache external values briefly with zeroing on eviction: up to 60 seconds by default in the proposal, no more than 15 seconds in production. Warn on mutable `latest`/`AWSCURRENT` references in production. Local secrets remain permitted; a reference does not automatically make a configuration safer.

## 6. Vault token and lease lifecycle

Use a small HTTP client with explicit endpoints, TLS verification, optional configured CA, no implicit proxy, and **no redirects**. Send `X-Vault-Request` and namespace where configured. A standby normally forwards internally; a 307 can appear when forwarding is unavailable. Do not follow it with a token to another host—ask for the active address or load balancer.

Keep Vault tokens in memory. Renew near two-thirds of TTL; reauthenticate before maximum TTL or after renewal failure. Invalid/expired tokens and missing authorization both produce 403. Check `lookup-self` to distinguish them; only an invalid session triggers one re-login/retry.

Connection pools own dynamic database leases, renewing while in use and revoking when closed/idle, at exit, and when cleaning up recorded orphan leases after restart. A lease is not necessarily revoked after each query. Lease IDs are identifiers, while issued credentials are secrets.

A narrowly scoped revocation path works better than broad body-form revoke permission. The researched policy example was:

```hcl
path "kv/data/stage/*" { capabilities = ["read"] }
path "database/creds/prod-replica-readonly" { capabilities = ["read"] }
path "sys/leases/revoke/database/creds/prod-replica-readonly/*" { capabilities = ["update"] }
path "ssh-client-signer/sign/gw-runbooks" { capabilities = ["update"] }
```

AppRole settings must account for token TTL/max TTL and secret-ID TTL/use counts. Health should warn before sign-in becomes unusable. OpenBao compatibility was supported by documentation; its binary was not tested in the recorded environment.

## 7. Testing references without disclosing values

Test store health, authentication, identity, and relevant capabilities. Test reference resolves the value internally but reports only existence, version, source, type, selected JSON key names, and latency. **Do not display length, prefix, or hash of the secret value.** Distinguish deleted/destroyed versions, access denial, absent keys, missing stores, and decryption failure.

A GCP identity with `roles/secretmanager.secretAccessor` need not have `secrets.list`; a denied informational list step must not fail a valid access-only setup. Vault capabilities can reveal create/update/delete/sudo access. AWS lacks an equivalent simple self-inspection path, so the generated session policy is the concrete restriction; role-name heuristics are warnings, not proof.

The UI/Admin API deals in secret metadata and opaque IDs. Explicit write-only DTOs and serialization tests prevent accidental value return. A replacement-version resolver allows Test connection to check new credentials while the old version remains active.

## 8. Memory, masking, and audit

Prefer `char[]`/`byte[]`, scope lifetimes, and zero buffers on close. Use streaming extraction for store responses where possible. Some APIs require immutable strings (AWS SDK, JDBC pools, SSH libraries, JavaScript); this is best-effort memory hygiene, not a guarantee that no secret copy ever exists.

Secret wrapper types need a serializer that refuses serialization. A class with no getters can serialize as `{}` in Jackson 3, so an empty-bean assumption is not sufficient. `toString()` must return a fixed marker.

Harden process diagnostics: disable JVM attach and heap dumps, configure bounded error/log locations, disable sensitive actuator endpoints, and apply Linux non-dumpable process settings when supported. These controls do not protect against a fully compromised same-user OS environment.

Mask after connector execution and before all external surfaces: structured content, text, audit arguments, UI previews, notifications, and logs. The proposed chain is invocation taint, sensitive key names, prefixed pattern rules, entropy, and optional PII. Replace matches with `‹masked›` and report counts. Emails/phones default to masking in production; SQL column masking adds its own context.

If approval text contains a stored secret, mask it and clearly warn that a stored value was present. If notification text was masked, channel approval is unavailable because the recipient has not seen the complete action; offer Deny and Open in app. Audit keeps masked arguments and an exact-action digest.

Residual risks include partial/hashed secret echoes, values from other connections appearing in remote logs, false positives on high-entropy non-secret data, and same-user access to external-server environment variables. A masked marker is not evidence that every possible encoding or transformation was detected.

## 9. Validation evidence and remaining limits

Recorded tests covered envelope round trips, swapped identity/version associated data, wrapping rotation with unchanged ciphertext, disabled keys, wrong backup password, atomic file replacement, interrupted/resumed rewrapping, Vault KV v1/v2/AppRole/leases/SSH CA, fake GCP/AWS APIs, and Linux bootstrap/non-dumpable behavior.

Maintain canary leak tests across MCP/Admin responses, SQLite/WAL bytes, audit, logs, stdout/stderr, and secret wrapper serialization. Masker fixtures need both positive secrets and negative UUID/hash/pod-name/base64 examples; the recorded performance target was 40 ms per MB, with a deliberate opt-out on unsuitable machines.

Platform safeStorage and Windows ACL behavior require native OS tests. OpenBao was not run. Actual GCP latest-version numbering and missing-checksum behavior were not confirmed against a live account. Master-key backup exclusion across atomic replacements needs macOS verification. Old benchmark figures do not establish current performance.

## 10. Primary evidence

- [Tink Java releases](https://github.com/tink-crypto/tink-java/releases).
- [Vault HA behavior](https://developer.hashicorp.com/vault/docs/concepts/ha), [OpenBao server](https://openbao.org/docs/commands/server/), and [OpenBao namespaces](https://openbao.org/docs/concepts/namespaces/).
- [AWS GetSecretValue](https://docs.aws.amazon.com/secretsmanager/latest/apireference/API_GetSecretValue.html).
- [GCP Secret Manager discovery](https://secretmanager.googleapis.com/$discovery/rest?version=v1) and [access control](https://docs.cloud.google.com/secret-manager/docs/access-control).
- [Electron safeStorage](https://github.com/electron/electron/blob/main/docs/api/safe-storage.md) and [breaking changes](https://github.com/electron/electron/blob/main/docs/breaking-changes.md).
- [OWASP password-storage guidance](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html), [SQLite pragmas](https://www.sqlite.org/pragma.html), and [gitleaks rule provenance](https://github.com/gitleaks/gitleaks).

## Appendix: recorded configuration and API examples

These examples preserve the investigated shapes; current API DTOs and connector schemas determine executable behavior.
```json
{"x-gateway-secret": "value|login|pem|binary", "oneOf": [
  {"title":"Encrypted here","properties":{"source":{"const":"local"},"value":{"type":"string","writeOnly":true,"format":"password"}}},
  {"title":"Vault / GCP SM / AWS SM","properties":{"source":{"const":"reference"},"ref":{"type":"string","pattern":"^(vault|gcp-sm|aws-sm)://"}}},
  {"title":"Short-lived (Vault dynamic credentials)","x-only-for":"login","properties":{"source":{"const":"vault-dynamic"},"ref":{"type":"string","pattern":"^vault://[^?#]+/creds/[^?#]+$"}}}]}
```

```json
{"$id":"store/vault","required":["address","auth"],"properties":{
 "address":{"type":"string","format":"uri","examples":["https://vault.acme.internal"]},
 "namespace":{"type":"string","description":"X-Vault-Namespace (Enterprise/HCP, OpenBao namespaces)"},
 "caCert":{"type":"string","x-format":"pem"}, "tlsServerName":{"type":"string"},
 "auth":{"oneOf":[
  {"properties":{"method":{"const":"approle"},"mount":{"default":"approle"},"roleId":{"type":"string"},
                 "secretId":{"x-gateway-secret":"value","writeOnly":true,"x-secret-name":"vault/approle-secret-id"}}},
  {"properties":{"method":{"const":"token"},"token":{"x-gateway-secret":"value","writeOnly":true}}},
  {"properties":{"method":{"const":"cli-token"},"file":{"default":"~/.vault-token"},"description":"Use your vault login, like gcloud ADC"}}]},
 "renewIncrement":{"type":"string","default":"1h"},
 "cacheTtlSeconds":{"type":"integer","default":60,"minimum":0,"maximum":300,"x-env-default":{"prod":15},"x-stricter":"min"},
 "useSystemProxy":{"type":"boolean","default":false}}}
```

```json
{"ok":true,"latencyMs":18,"server":{"version":"2.2.1","sealed":false,"standby":false},
 "identity":{"auth":"approle","role":"mcp-gateway","policies":["default","gw-read"],"ttl":"1h","renewable":true},
 "permissions":[{"ref":"vault://kv/stage/db#password","capabilities":["read"]}],
 "hints":[{"severity":"warning","code":"store.can-write","text":"This token can write kv/data/stage/db. Give the gateway a read-only policy."}]}
```
