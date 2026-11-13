# Control AI access to InfluxDB

GateRook's MCP Firewall lets AI apps explore InfluxDB databases and run SQL or InfluxQL reads within row and size limits, and holds every write for your review. GateRook reads each statement with its own lexer before anything is sent. The [security model](security.md) explains execution controls, explicit Bypass, and the append-only, tamper-evident audit trail.

## Connect a server

The built-in **InfluxDB** connector (`influxdb`) speaks InfluxDB's HTTP APIs; there is no InfluxDB client library in GateRook. The server you choose decides the API:

| Server | Reads | Writes | Credentials |
| --- | --- | --- | --- |
| **InfluxDB 3 Core or Enterprise** | SQL (`/api/v3/query_sql`) and InfluxQL (`/api/v3/query_influxql`) | `/api/v3/write_lp` | API token, or none for a server started without authentication |
| **InfluxDB 2.x or InfluxDB Cloud** | InfluxQL through the v1-compatible `/query` | `/api/v2/write` | API token |
| **InfluxDB 1.x** | InfluxQL (`/query`) | `/write` | Username and password, or none |

InfluxDB Cloud Serverless and Cloud Dedicated answer SQL only over Arrow Flight, which GateRook doesn't use: connect them as InfluxDB 2.x for InfluxQL reads and writes. **Test connection** reads the version the server reports (`/ping`) and refuses a server of another kind.

The **Server URL** is the server's address with `https://` and its port, optionally with a path prefix; plain `http://` goes only to this computer (`localhost`, `127.0.0.1`, `::1`). Requests go only to that address, with the APIs' fixed paths, and follow no redirect. Before each request GateRook resolves the name and refuses link-local, cloud metadata and reserved addresses and GateRook's own ports; private networks stay reachable. The HTTP client resolves the name again when it connects, so this check is not a guarantee against DNS rebinding. TLS is verified with the system's CAs, or with a private CA you paste under **Advanced**. The token or password is stored encrypted and travels only in the `Authorization` header (`Bearer` on InfluxDB 3, `Token` on 2.x, `Basic` on 1.x), never in a URL. A prod connection must sign in.

**Databases** lists the databases (buckets on 2.x) agents may use; `*` matches any characters, as in `iot_*`. Empty, every database the credentials may read. GateRook checks the list for the database a call names and for every database a statement names itself (`SHOW MEASUREMENTS ON hr`, `FROM "hr"."autogen"."cpu"`), and filters database listings by it. **Organization** is needed by InfluxDB OSS 2.x for writes; InfluxDB Cloud takes it from the token.

While you fill in the form, GateRook offers the servers and organizations of the `influx` CLI's `~/.influxdbv2/configs` (never their tokens) and `http://localhost:8181` or `http://localhost:8086` when a server answers there. For a URL of this computer it reads `/ping` without credentials and sets **Server** to the kind the server reports. Once the form can sign in, it does the same for any server, fills in the organization when the token sees exactly one, and lists the buckets or databases for **Databases**.

Test connection reports the server's version, the databases agents may use, and what the credentials may do as far as the server tells: InfluxDB 3 Core has admin tokens only; on InfluxDB 3 Enterprise, a token that may use the configuration API is an admin token; on 2.x, a token that can read the organization's users is an all-access or operator token; on 1.x, it asks whether the user is an admin (`SHOW USERS`). It never writes to find out. Outside dev it warns about credentials that may also write and delete.

## Bound reads and review exact writes

| Tool | What it does | Default mode |
| --- | --- | --- |
| `influxdb_list_databases` | Databases agents may use (buckets on 2.x) | Read only |
| `influxdb_list_measurements` | Measurements of one database (tables on InfluxDB 3), with `nextCursor` | Read only |
| `influxdb_describe` | Tags, and fields with their types, of one measurement | Read only |
| `influxdb_query` | One SQL (InfluxDB 3 only) or InfluxQL read, with `$name` parameters | Read only |
| `influxdb_write` | Line protocol points for one database | Approval |

Answers are tables with typed cells: an integer is a JSON number only where JavaScript keeps it exact, otherwise its digits as text; the `time` column is ISO 8601 in UTC; long strings are cut to the endpoint's cell size. A read returns at most the call's `limit`, the connection's **Rows per query** and the endpoint's row limit, within **Response size**. GateRook reads the server's answer as it streams (JSON Lines on InfluxDB 3, chunks on 1.x and 2.x), stops at the first limit it reaches and says the answer was cut. Name the database a query reads, except for `SHOW DATABASES`. Cursors of `influxdb_list_measurements` are sealed by GateRook and belong to one connection and one database.

### How GateRook reads a statement

InfluxDB has no read-only session. GateRook runs only what its own reading of a statement calls one read, and refuses whatever it can't read with certainty, before anything is sent:

- **SQL** (InfluxDB 3, DataFusion's dialect): one `SELECT`, `WITH … SELECT`, `VALUES`, `SHOW` or `EXPLAIN` without `ANALYZE`, which would run the statement. Refused: a write anywhere in the text (`INSERT`, `UPDATE`, `DELETE`, `MERGE`, `SELECT … INTO`, `COPY`, `CREATE`, `DROP`, `ALTER`, `TRUNCATE`), any other statement, more than one statement, and InfluxDB's `system` tables, which hold other callers' queries. Strings, quoted names, comments and `$name` placeholders are read as tokens; what two SQL dialects could split differently is refused: a backslash right before a quote, a comment inside a comment, prefixed strings (`E'…'`, `U&'…'`, `X'…'`), dollar quoting, backtick names, `#` and `@`, and characters outside ASCII outside quotes.
- **InfluxQL**: one `SELECT` without `INTO`, or a `SHOW` of data: `DATABASES`, `RETENTION POLICIES`, `MEASUREMENTS`, `SERIES`, `TAG KEYS`, `TAG VALUES`, `FIELD KEYS` and their `CARDINALITY`. Refused: `SELECT … INTO`, `CREATE`, `DROP`, `DELETE`, `ALTER`, `GRANT`, `REVOKE`, `KILL`, `SET PASSWORD`, `EXPLAIN`, the `SHOW` statements of the server's administration (users, grants, queries, subscriptions, shards, diagnostics, stats, continuous queries), and more than one statement. A regular expression `/…/` is read where InfluxQL's grammar takes one: after `=~` and `!~`, and as a field, a source, a dimension or a function's argument. Refused, as InfluxDB's scanners could read them differently: a `/` that is neither a division nor a regular expression with certainty, `\\` or a leading `*` in a regular expression, comments, escapes other than `\\`, `\'` and `\"`, a quote right after a name, a parameter where a database or measurement is named, and characters outside ASCII outside quotes.
- **Flux is not supported.** A Flux script can write and call out (`to()`, `experimental.to()`, `http.post()`, `sql.to()`, socket, CSV and URL sources), and no list of forbidden functions is a guarantee. GateRook never sends Flux.

Quote a measurement, tag or field spelled like a keyword (`"drop"`). The statement's reading is GateRook's boundary; the credentials' permissions are the server's. Give agents credentials that may only read their databases: an InfluxDB 3 Enterprise resource token, a 2.x read token, a 1.x user with `READ` grants. InfluxDB 3 Core has no read-only tokens, so on Core GateRook's reading is the only boundary between a read and a write.

### Writes

`influxdb_write` takes line protocol for one database, at most 1 MiB and **Points per write** under Advanced (1,000 by default), with a `precision` of `ns`, `us`, `ms` or `s`; InfluxDB 3's guessing precision `auto` is never used. GateRook reads the text point by point and refuses a malformed line with its number. Only the form every InfluxDB version reads the same way passes: one space between the parts; the escapes `\,` and `\ ` in a measurement, `\,`, `\=` and `\ ` in tag keys, tag values and field keys, `\"` and `\\` in a string field; floats (`1.5`, `1e3`), integers (`1i`), unsigned integers (`1u`, not on 1.x), strings and booleans; timestamps of digits within InfluxDB's time range. Comments, tabs, carriage returns, other backslashes (InfluxDB 3 reads `\\` in a tag as one backslash, 1.x and 2.x as two), names that begin with `_`, `time` as a key and a key twice in one point are refused. InfluxDB's own databases (`_internal`, `_monitoring`, `_tasks`) aren't written.

The approval shows the exact request: its URL, its headers with the credentials as `‹masked›`, and the body byte for byte, with the points decoded into measurement, tags, typed fields and timestamp. It says how many points have no timestamp (the server stamps them), warns when a timestamp lands before 1980 (most likely a smaller unit than `precision`) and, on InfluxDB 3, when the database doesn't exist yet, as the write would create it. A point with the same measurement, tags and timestamp as an existing one replaces the fields it writes. What runs is the approved payload, once. InfluxDB 3 writes all points or none (`accept_partial=false`); InfluxDB 1.x and 2.x keep the points they accept when they refuse others, which GateRook reports as an unknown outcome, as it does for an answer lost after sending. Read the points back before writing again.

## Verify

The connector's unit tests run against a fake of the three APIs in every build. The integration test runs against InfluxDB 3 Core 3.12.1 and InfluxDB 2.9.1 of the [test services](../test-services/README.md): start them with `scripts/test-services.sh up influxdb` and run `InfluxDbIT` with `-Pservices,influxdb-it`. InfluxDB 1.x is checked against the fake only; InfluxDB 3 Enterprise and the InfluxDB Cloud services are not verified. JVM checks do not establish native-image or installer compatibility.

InfluxData documents the [InfluxDB 3 Core HTTP API](https://docs.influxdata.com/influxdb3/core/api/v3/), [line protocol](https://docs.influxdata.com/influxdb3/core/reference/line-protocol/), the [InfluxDB 2.x v1-compatible API](https://docs.influxdata.com/influxdb/v2/reference/api/influxdb-1x/) and [InfluxQL](https://docs.influxdata.com/influxdb/v1/query_language/).
