# 05 · MongoDB and Redis connectors

> Research snapshot: 2026-10-23. Recorded experiments used MongoDB 7.0.45, 8.0.23, and 9.0.4; Redis 6.2.11 and 7.0.15; Java 25; MongoDB driver 5.13.0; Jedis 8.0.2; and Jackson 3.1.7. Some independent checks repeated a narrower matrix. This adaptation preserves those findings without claiming new compatibility verification. **UNVERIFIED** marks outstanding evidence. Related: [SQL safety](04-sql.md), [logs/OpenSearch](07-logs.md), and the [architecture](../ARCHITECTURE.md).

## 1. Decision and scope

**Current extension (2026-10-28):** MongoDB also exposes `mongo_update` (one matching document) and
`mongo_update_many` (all matching documents), classified as MUTATION and defaulting to Approval in every
environment. Both require an explicit filter and accept update operators or an update pipeline, optional
`upsert`, and `arrayFilters` for operator updates. Preparation performs no writes; execution uses the canonical
approved payload, primary routing, acknowledged writes, and a bounded operation timeout without automatic write
retries. Read document limits do not cap updates. Results report matched and modified counts and an upserted ID.
The namespace and server-side JavaScript restrictions still apply, and aggregate write stages remain refused.
Credentials need update privileges for these tools. The original read-only research decision and experiments
below describe the earlier scope.

Provide two built-in read-only connectors: MongoDB with `mongo_find` and `mongo_aggregate`, and Redis with `redis_get` and `redis_scan`. Do not expose arbitrary MongoDB or Redis commands. Classification is READ or DENY, never MUTATION. A user may still require approval for a read tool through endpoint policy.

Use the synchronous MongoDB driver directly, with one bounded client per connection, and Jedis with a small pooled provider and a command-executor allowlist. Avoid entity frameworks, reactive stacks, and generic command tools that expand the reachable operation surface. OpenSearch is a backend of the Logs connector; an additional general-purpose search-data connector was deferred.

Read safety has three layers: fixed command construction, recursive argument/namespace validation, and least-privilege server credentials. MongoDB has no SQL-style read-only session. The recorded experiment showed `$out` writing under a `readWrite` account even when the client only requested `.first()`. Therefore a validator failure under broad credentials is a real write risk. Recommend a `read` account and restrictive Redis ACLs.

## 2. Dependency findings

| Dependency | Evaluated version | Notes |
| --- | --- | --- |
| `mongodb-driver-sync` | 5.13.0 | Overrides Boot 4.1.2's 5.8.1; uses bson/core/record-codec. |
| Jedis | 8.0.2 | Overrides Boot's 7.4.1; RESP3 with fallback; use `RedisClient`, not removed `JedisPooled`. |
| Jackson | 3.1.7 | SPI JSON representation and schema handling. |
| Flapdoodle Mongo, tests | 5.0.0 | Known server versions stopped at 8.3.2; a binary override is needed for newer matrix entries. |
| embedded-redis, tests | 1.4.4 | Used as a Redis 6.2.11 binary source despite the bundled filename's older version string. |

MongoDB driver's client-side operation timeout API was still annotated alpha; the proposal used server `maxTimeMS` plus socket deadlines. Jedis's optional beta `redis-authx-core` dependency was excluded for these paths. Account for Boot's Gson override: the effective dependency can differ from Jedis's POM. Run dependency convergence and actual tests rather than assuming compatible mediation.

A reduced JRE must explicitly include `jdk.naming.dns` for MongoDB SRV/TXT resolution. Static `jdeps` did not discover this reflectively loaded provider, and the recorded reduced-runtime experiment failed without it. Avoid unrelated Spring auto-configuration modules that could construct an ambient localhost client.

## 3. MongoDB contracts

`mongo_find` accepts endpoint connection, optional database, collection, filter, projection, sort, limit, and skip. Parse object/string arguments once into BSON; validate and execute the same BSON value. The evaluated parser accepts canonical/relaxed Extended JSON and supported shell literals such as `ObjectId`, `ISODate`, and `NumberLong`.

Use bounded find operations with projection/sort/skip, `limit(n + 1)`, small batch size, server time limit, `allowDiskUse(false)`, and an audit correlation comment. Read `RawBsonDocument` size before conversion, close cursors on budget exhaustion, and distinguish document-count and byte-size truncation. An empty result can come from a nonexistent collection; a bounded listCollections check may provide a useful warning.

`mongo_aggregate` accepts one to fifty single-key stages. Validate recursively, append a final `$limit: n + 1`, disable disk spill, and use the same deadlines/output budget. Database-wide aggregate operations and persistent change streams are outside the tool contract.

### Recursive validation

| Condition | Decision |
| --- | --- |
| `$out` or `$merge` anywhere | Deny writes, including nested facet/lookup pipelines. |
| `$where`, `$function`, or `$accumulator` anywhere | Deny server-side JavaScript. |
| Unknown stage or malformed stage object | Deny, rather than assuming a future stage is read-only. |
| Lookup/graphLookup/unionWith references outside the namespace policy | Deny. |
| System databases/collections without explicit permission | Deny. |
| Invalid BSON/EJSON | Deny with sanitized argument diagnostics. |
| Valid bounded read | READ. |

The reviewed stage allowlist was `$addFields`, `$set`, `$bucket`, `$bucketAuto`, `$count`, `$densify`, `$documents`, `$facet`, `$fill`, `$geoNear`, `$graphLookup`, `$group`, `$limit`, `$lookup`, `$match`, `$project`, `$unset`, `$redact`, `$replaceRoot`, `$replaceWith`, `$sample`, `$setWindowFields`, `$skip`, `$sort`, `$sortByCount`, `$unionWith`, `$unwind`, `$rankFusion`, `$score`, `$scoreFusion`, `$search`, `$searchMeta`, `$vectorSearch`, `$collStats`, `$indexStats`, and `$listSearchIndexes`. Their availability varies by server/product; an allowed syntax is not proof that every server supports it.

Deny cluster/session/query introspection stages, change streams, and write stages. Validate referenced collections in nested pipelines, not just the top-level collection. `admin.*`, `local.*`, `config.*`, and `*.system.*` are closed by default; some contain credentials or broad operational data. Views can expose underlying collections, so namespace filtering supplements real database permissions.

### BSON output

Return database, collection, documents, returned count, truncation reason, bytes, elapsed time, read preference, and warnings. Use relaxed Extended JSON, but represent Int64 outside JavaScript's exact integer range as `$numberLong` strings. Preserve Decimal128, pre-1970 dates, non-finite numbers, binary subtype, and UUID semantics. The default relaxed writer emitted an unsafe bare large integer in the experiment.

Bound strings and binary previews before conversion; use standard UUID representation. One server document may still be large even when the response budget is small, so a 16 MiB document remains a memory test case. The core masks both documents and query arguments using namespace/field provenance and value detectors.

## 4. Redis contracts

Use binary APIs and strict UTF-8 decoding. Non-UTF-8 values become explicit base64 data, rather than replacement characters that lose information. `redis_get` first inspects TYPE, PTTL, and optional memory usage, then issues a bounded command appropriate to the type:

| Type | Bounded read |
| --- | --- |
| String | STRLEN; GET for a small value, otherwise GETRANGE. |
| Hash | HLEN and HSCAN. |
| List | LLEN and a bounded LRANGE. |
| Set | SCARD and SSCAN. |
| Sorted set | ZCARD and bounded ZRANGE WITHSCORES. |
| Stream | XLEN and bounded XREVRANGE with exclusive continuation. |
| Missing | `exists: false`. |
| Unsupported module type | Explicit unsupported result, not a guessed command. |

RedisJSON support through JSON.GET remained unverified and must not be treated as established by this report. Retry one TYPE/read race when WRONGTYPE indicates a changed key. Bind continuation to key hash and type; reject use on another key. Distinguish persistent TTL from missing keys and protect optional NOPERM diagnostics.

`redis_scan` accepts a Redis glob, optional type, approximate count, opaque cursor, and optional details. Iterate SCAN within a bounded time/iteration budget. COUNT is a hint; a Redis batch can exceed it and cannot be resumed from its middle. A lossless implementation must preserve or buffer overflow rather than discard keys while advancing the server cursor. SCAN can repeat keys under its documented semantics; callers must not assume a snapshot.

Filter every returned key through the gateway's allowlist/denylist. Redis ACL key patterns do **not** hide key names from SCAN: the recorded `~cache:*` account could enumerate other names but received NOPERM when reading them. Mask sensitive key names too.

Bind scan cursors to pattern/type and reapply current policy on each request. A cryptographic signature is unnecessary for a harmless opaque Redis scan position, provided no cursor field grants permissions. Property-test the local glob matcher against real SCAN MATCH, including bracket classes, negation, escapes, and Unicode bytes.

The read executor must restrict exact commands and argument shapes, including subcommands. A broad `CLIENT` or `CONFIG` allowance is unsafe. Jedis pipelines bypassed the command executor in the recorded test; keep any pipeline in one tightly controlled read helper and verify the actual commands on the wire.

## 5. Connection settings

MongoDB uses a credential-free URI, explicit authentication fields, TLS/CA settings, read preference, namespace restrictions, timeouts, and pool size. Reject URI userinfo so passwords cannot become ordinary configuration. Reject unsafe TLS URI switches and conflicting duplicate configuration. Do not allow URI `authMechanism` or `authMechanismProperties` to invoke ambient AWS/OIDC credentials or metadata endpoints; use only explicit supported credential modes. Cap URI pool settings to the configured pool size.

Redis uses host, port, fixed database, explicit login, TLS verification/CA, key globs, command/connect timeouts, maximum value size, and pool size. Pasting a URL must move its password into the secret field, not preserve it in configuration. Production defaults should enable TLS and full hostname verification; any weaker mode must be explicit and policy-controlled.

Research defaults were two connections, 1,000 dev/100 prod returned items, MongoDB five-second dev/three-second prod queries, Redis two-second command/scan budgets, and 512 KiB responses. “Stricter wins” means minimum limits, intersected allowlists, and unioned denylists. `CLIENT NO-TOUCH` is available only from Redis 7.2; older reads can affect LRU/LFU state.

## 6. Test connection and permissions

Use a disposable direct client and a bounded budget. Do not save a connection merely because its probe succeeded.

MongoDB checks ping, buildInfo, hello/topology, `connectionStatus {showPrivileges: true}`, authorized databases/collections, and configured namespaces. Distinguish read, write, administrative, and unauthenticated servers. Inspect the underlying server exception when a top-level selection timeout hides DNS, TLS, or authentication failure. Recommend read credentials, verified TLS, and secondary reads where appropriate, without claiming that secondaryPreferred guarantees a secondary.

Redis HELLO reports version, protocol, mode, role, and modules even when INFO is unavailable. Reject unsupported Cluster/Sentinel operation rather than silently mishandling MOVED or multi-node scans. Permission inspection may use ACL GETUSER/DRYRUN when allowed. For known compatible Redis/Valkey servers, the research evaluated `SET <random nonexistent probe> 1 NX XX`: ACL is checked before mutually exclusive options reject the command, and the key stayed absent on Redis 6.2/7.0. Unknown implementations must not receive an unverified probe. Report uncertainty instead of claiming read-only permissions from an unavailable inspection command.

The tested Redis 7+ ACL pattern removed dangerous commands after adding read/connection categories:

```text
ACL SETUSER gw_reader on >REPLACE_WITH_SECRET ~* resetchannels -@all +@read +@connection -@dangerous -dump -xread -lolwut
```

Redis 6.2 required selectively re-adding `client|setname` and `client|id`. Omitting `-@dangerous` allowed CLIENT PAUSE/KILL through `+@connection`; adding unknown commands such as `-sort_ro` on older servers can fail the entire ACL change. These are version-specific provisioning examples, not commands the connector should execute. The password placeholder must never become a committed credential.

## 7. Runtime safety

MongoDB uses server timeouts and `allowDiskUse(false)`; server memory errors should suggest earlier match/limit stages. Redis avoids unbounded KEYS/HGETALL/SMEMBERS and validates every reachable command. Both connectors enforce concurrency and total output budgets in addition to per-command timeouts.

Rotate MongoDB credentials by replacing the client generation. Redis credential providers refresh physical connections and clear the pool after a secret-version change. Never place credentials in URIs, client names, or logs. Dynamic Vault support through the core's secret resolver was a design integration, not a completed experiment in this report.

Use explicit TLS trust stores and hostname checks. Treat every returned document, value, error, and key name as untrusted content; the core handles final masking. Reads can still expose confidential data or carry prompt injection, so egress composition and endpoint scope remain relevant.

## 8. Verification strategy

Tests cover every allowed/denied Mongo stage, nested writes/JavaScript, foreign namespaces, duplicate-key parsing, EJSON fidelity, precision, truncation, and output-schema validation. With real Mongo servers, demonstrate that a broad account can write with an unsafe pipeline and that the connector blocks it **before** network execution. Exercise timeouts, missing collections, role inspection, authentication, TLS, and secret rotation.

Redis tests cover all core data types, large values, missing/persistent TTL, binary data, cursor mismatch, scan completeness, ACL-hidden keys, replicas, exact wire commands, wrong certificates/SANs, pool rotation, and a property corpus for glob semantics. Verify SET/FLUSHALL/EVAL/KEYS/administrative subcommands fail before network access, including raw-command and pipeline escape paths.

The recorded prototype compiled and exercised core paths. Independent rechecks covered Redis 6.2/7.0 and MongoDB 8.0/8.3 more extensively than other matrix entries. Redis 8, managed services, Valkey/Dragonfly/KeyDB/Upstash, RedisJSON, full-size documents, and Vault integration remained unverified. A passing local server test does not establish those products' behavior.

## 9. Open decisions and sources

Open product questions included requiring a proven read-only Mongo account on production, adding Redis database selection, exposing Mongo hint/collation, separate OpenSearch data tools, and cloud-native authentication. Keep unknown stages denied until their semantics and tests are reviewed.

- [MongoDB aggregation stages](https://www.mongodb.com/docs/manual/reference/mql/aggregation-stages/)
- [MongoDB server-side JavaScript](https://www.mongodb.com/docs/manual/reference/operator/query/where/)
- [MongoDB 9.0 release notes](https://www.mongodb.com/docs/manual/release-notes/9.0/)
- [MongoDB download/version manifest](https://downloads.mongodb.org/full.json)
- [Jedis releases](https://github.com/redis/jedis/releases) and [v7 to v8 migration](https://github.com/redis/jedis/blob/master/docs/migration-guides/v7-to-v8.md)
- [Redis CLIENT NO-TOUCH](https://redis.io/docs/latest/commands/client-no-touch/)
- [Vault MongoDB plugin](https://developer.hashicorp.com/vault/docs/secrets/databases/mongodb) and [Redis plugin](https://developer.hashicorp.com/vault/docs/secrets/databases/redis)
- [Maven Central artifact registry](https://repo1.maven.org/maven2/)
