# Redis

The Redis connector reads keys with `redis_get` and lists keys with `redis_scan`. It also exposes six tools for writing values. Write tools start in **Approval** on every environment; review the exact command, key and database before approving. An explicitly configured **Bypass** mode runs the same validated operation without approval.

## Connect

**Connection type** **Auto**, the default for new dev and stage connections, finds out before signing in whether the server speaks TLS. GateRook first tries a TLS handshake, verified as the connection says: its private CA or the system's, and the host name unless **Verify the CA only** is chosen. A server that completes it gets TLS. A certificate or name that fails verification is reported as such and is never a reason to drop encryption. Only a server that doesn't speak TLS at all gets a connection without TLS, after it answered `PING` without credentials on a connection of its own (`+PONG`, or an error such as `-NOAUTH`). redis-server reads a TLS hello on its plain port as the start of a command and waits, so a short silence with no byte back also leads to that `PING`; a server that then doesn't answer as Redis is tried again as a slow TLS server. With a CA certificate in the form, the server must speak TLS. Auto runs once per version of a connection and on every Test connection, whose first check names the transport (`TLS (Auto)` or `Without TLS (Auto)`). New prod connections use **TLS**, and connections saved without a connection type keep their transport: TLS on prod, none elsewhere. As with any automatic choice, someone who controls the network path could make a TLS server look plain; choose **TLS** where that matters.

While you fill in the form, GateRook offers `localhost` for **Host** when a server answers on port 6379, and once the form can sign in it lists the server's logical databases with their keys under **Database**.

| Tool | Required value arguments | Behavior and result |
| --- | --- | --- |
| `redis_set` | `value`: string | Creates or replaces a key with a string, replacing any previous type. Returns `applied`; `false` means its condition was not met. |
| `redis_hset` | `fields`: object of string values | Creates or updates hash fields; preserves other fields. Returns `added`, the number of new fields, excluding overwritten fields. |
| `redis_lpush` | `values`: array of strings | Prepends list values. `["a", "b"]` puts `b, a` at the head. Returns the resulting `length`. |
| `redis_rpush` | `values`: array of strings | Appends list values in the supplied order. Returns the resulting `length`. |
| `redis_sadd` | `values`: array of strings | Adds set members, ignoring duplicates. Returns `added`, the number of new members. |
| `redis_zadd` | `members`: array of `{ "score": number, "value": string }` | Adds sorted set members or updates their scores. Returns `added`, the number of new members, excluding updated members. Scores must be finite. |

Every write requires `connection` and `key`. As with the read tools, `database` selects an allowed logical database and is required when the connection selects more than one. `encoding: "base64"` decodes a binary key; the default is UTF-8. `redis_set` separately accepts `valueEncoding: "base64"` for binary values. Collection fields and values use UTF-8 strings, including empty strings.

`redis_set` accepts `condition: "NX"` to create only an absent key, `condition: "XX"` to replace only an existing key, or `"always"` (the default). Use `ttlMs` for an expiration of 1–2,147,483,647,000 milliseconds or `keepTtl: true` to preserve an existing expiration. These options are mutually exclusive. Without either option, a successful SET removes the existing expiration. The collection tools preserve the key's expiration. SET conditions and expiration are applied in the same Redis command. See the [Redis SET reference](https://redis.io/docs/latest/commands/set/).

For example, create a short-lived cache entry only if it is absent:

```json
{
  "connection": "cache-dev",
  "key": "cache:example",
  "value": "ready",
  "condition": "NX",
  "ttlMs": 60000
}
```

The connection's key allowlist, key denylist and database selection apply to both reads and writes. Redis ACL permissions still apply: a read-only Redis account cannot execute these writes even after GateRook approval. Use an account scoped to the required commands and keys. Raw commands, Lua scripts, deletion and administrative operations are not exposed.

A call writes one key with one command, up to 1,000 elements and 1 MiB total key, field and value bytes. Each value and hash field must fit the connection's **Max value size**. The effective call limits on elements (`maxRows`) and request size apply as well. GateRook rejects an oversized write in full; it never silently truncates the supplied values. Collection writes fail without changing the key if it holds an incompatible type.

Approved execution uses the stored request and rechecks the current connection scope and limits. Writes are never automatically retried after a connection failure: Redis may have applied a command before its reply was lost. A write whose connection fails after the command was sent is reported as **outcome unknown**, and the request is not run again; a pooled connection that served an earlier call is checked with `PING` before the write, so a connection that went stale while idle fails before anything is written. If the result is uncertain, read the key before deciding whether to retry, especially for list pushes.

After installing a build with these tools, restart GateRook and refresh or reconnect the AI client's MCP session if it still shows the previous tool list.
