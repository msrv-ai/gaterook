# Control AI access to Neo4j

GateRook's MCP Firewall lets AI apps read a Neo4j graph with bounded Cypher queries and holds every write for your review. Neo4j itself refuses writes in the read transactions GateRook opens, and its own planner says what a statement does before it runs. The [security model](security.md) explains execution controls, explicit Bypass, and the append-only, tamper-evident audit trail.

## Connect

The built-in **Neo4j** connector (`neo4j`) uses the official Neo4j Java driver over Bolt. Choose:

- the **URI**: `neo4j+s://` for a cluster or Aura, where the cluster's routing table picks the server of each call, or `bolt+s://` for one server, with the host and port (7687 by default). Both verify TLS with the system's CAs and the host name. `neo4j://` and `bolt://` without TLS reach only this computer (`localhost`, `127.0.0.0/8`, `[::1]`). The `+ssc` schemes accept any certificate and are refused;
- the **database** every call runs in, `neo4j` by default. The `system` database, which holds users and roles, is refused;
- how GateRook signs in: a **user and password**, or a **bearer token** of single sign-on (OIDC), stored encrypted in GateRook. A token expires: paste a new one when Test connection says so;
- under **Advanced**, the **CA certificate** (PEM) of a private CA for a `+s` URI. GateRook still checks the host name. **Rows per query** and **Transaction timeout** can only tighten the environment policy.

The form helps with the scheme. An address typed without one gets `neo4j+s://` when you leave the field if it names another computer, which GateRook reaches only with TLS. For this computer, GateRook first tries a TLS hello on the port and, only when the port doesn't speak TLS, Bolt's handshake, sending nothing that signs in, and adds `neo4j+s://` or `neo4j://` to match. A scheme that doesn't fit the port, or a URI without TLS to another computer, gets a note with the one that does. With the URI empty, `neo4j://localhost:7687` (or `neo4j+s://`) is offered when a server answers there. Once the form can sign in, GateRook lists the databases the user may use (`SHOW DATABASES`) and fills the home database into **Database**; for `bolt+s://` to one server of a cluster it suggests `neo4j+s://`, which lets the cluster route each call.

Sign in as a user with the `reader` role wherever you can (Enterprise Edition and Aura):

```cypher
CREATE USER gw_reader SET PASSWORD 'choose-one' CHANGE NOT REQUIRED;
GRANT ROLE reader TO gw_reader;
```

**Test connection** reports Neo4j's version and edition, the database's state (`SHOW DATABASE`), the user and its roles (`SHOW CURRENT USER`), and whether the connection uses TLS. A user that can write is named; the `admin` role is a warning outside dev. Community Edition has no roles, so every user may read, write and manage users: Test connection says so, as a warning outside dev. Health runs `RETURN 1`.

## Bound reads and review exact changes

| Tool | What it does | Default mode |
| --- | --- | --- |
| `neo4j_schema` | Labels, relationship types, property keys, indexes and constraints; optionally which labels connect through which types | Read only |
| `neo4j_query` | One Cypher read with `$parameters` | Read only |
| `neo4j_execute` | One Cypher write, or a `CREATE` or `DROP` of an index or constraint | Approval |

A read is held back three times:

1. GateRook first reads the statement by Cypher's own tokens: strings in `'…'` and `"…"` with backslash escapes, names in backticks, `//` and `/* … */` comments, parameters (`$name`). It refuses what it can't read with certainty (an open quote, name or comment, any `\u` escape, a backslash outside a string, control characters and line separators, spaces and characters Cypher doesn't use outside strings, letters that stand for other letters such as `ſ` or fullwidth letters) and what GateRook never runs: administration and security commands (`CREATE`, `ALTER`, `DROP` and `SHOW` of users, roles, databases, aliases and servers, `GRANT`, `DENY`, `REVOKE`, `START` and `STOP DATABASE`, `TERMINATE TRANSACTIONS`), every `SHOW` command, `LOAD CSV`, `USE`, `CALL { … } IN TRANSACTIONS`, more than one statement, and `EXPLAIN`, `PROFILE` and `CYPHER` options. Agents may call only the schema procedures `db.labels`, `db.relationshipTypes`, `db.propertyKeys` and `db.schema.*`; `dbms.*`, APOC's and Graph Data Science's procedures stay with people. Functions are Cypher's built-in ones and APOC's, except those that run Cypher text (`apoc.cypher.*`), read files, URLs or settings, or touch triggers and the system database. A variable named like a keyword (`(load)`, `WITH n AS set`) reads as the keyword: write it in backticks.
2. Each read runs in an explicit transaction with access mode READ, in which Neo4j refuses any write ("Writing in read access mode not allowed").
3. Before the read runs, Neo4j's `EXPLAIN` of it, in that transaction, must call it read only (`r`). A statement Neo4j plans as `rw`, `w` or `s` (a schema command; Neo4j plans DBMS procedures this way too) never runs.

A read transaction is never committed. Answers keep Neo4j's types: a node is `{"elementId", "labels", "properties"}`, a relationship `{"elementId", "type", "startElementId", "endElementId", "properties"}`, a path `{"length", "nodes", "relationships"}`; dates, times, date-times and durations are ISO 8601 (`2026-01-02T03:04:05+01:00[Europe/Berlin]`), a point is `{"crs", "srid", "x", "y"}` with `z` in 3D. An integer is a JSON number only where JavaScript keeps it exact, otherwise its text; NaN and the infinities are their names; strings are cut to the endpoint's cell size, and bytes show their first 256 in hex with their length. A query returns at most the endpoint's rows (and **Rows per query**) within the response size. Each transaction gets a timeout, the shortest of **Transaction timeout**, the policy's statement timeout and what is left of the call: Neo4j stops a slower one.

Parameters are JSON: strings, integers of 64 bits, floats, booleans, null, lists and maps. Build temporal and spatial values in Cypher (`date($day)`, `point({x: $x, y: $y})`). Values never enter the statement's text.

`neo4j_execute` asks Neo4j for its plan before a person is asked, with `EXPLAIN` in a READ transaction, which runs nothing. The approval shows the exact statement, its parameters, Neo4j's query type, the plan's operators and estimated rows, and the planner's warnings. A statement Neo4j can't plan, or plans as read only, is never put to a person. What runs is the approved statement, once, in one explicit WRITE transaction: never through the driver's retrying transactions. The answer reports Neo4j's counters (nodes and relationships created and deleted, properties set, labels added and removed, indexes and constraints) and the rows the statement returns. A failure before the commit changes nothing; an answer lost after the commit was sent reports the outcome as unknown, so check the data before you try again.

## What stays outside these checks

`LOAD CSV` is a read to Neo4j: only GateRook's reading refuses it. Community Edition can't limit what a user may do, so the READ transaction and the plan check are the boundary there; on Enterprise Edition and Aura, the `reader` role is one more. With `neo4j://`, the driver follows the cluster's routing table to the servers it names; GateRook checks the configured host only, refusing link-local, cloud metadata and reserved addresses. A private CA reaches the driver as a file in GateRook's work folder while the driver is built, and is deleted right after.

## Delivery

The connector is a [connector module](connector-delivery.md#connector-modules): it is not part of the installer. GateRook downloads it when the first Neo4j connection needs it, about 9 MB, and runs it in a separate Java process. A development build started with the `all-bundled` profile compiles it in instead.

## Verify

The `neo4j` profile of the [Docker test services](../test-services/README.md#neo4j) runs Neo4j 5.26 Community with Bolt on one port, with or without TLS from the test CA. Start it with `scripts/test-services.sh up neo4j` and run the connector's integration test with `-Pservices,neo4j-it`: it covers Test connection, TLS with the private CA and with the wrong one, the schema, typed results, each of the three read checks on its own, an approved write with its counters, refused administration, `LOAD CSV` and procedures, limits and the transaction timeout. The unit tests stand in for the server behind the connector's Bolt interface in every build. `Neo4jModuleCoreProcessIT` installs the built module through the core and runs it against the same server (`-Pservices,neo4j-it`).

Memgraph speaks Bolt, but GateRook supports Neo4j only. Probed with Memgraph 3.14.0, the driver connects (database `memgraph`, not `neo4j`) and Memgraph refuses writes in READ transactions, but it reports every statement as read and write, `EXPLAIN` and `RETURN 1` included, and returns its plan as rows: `neo4j_query` therefore refuses every read. It has no `db.labels` and allows no `SHOW INDEX INFO` in an explicit transaction, so `neo4j_schema` fails. Test connection names it and warns.

Not verified: Enterprise Edition's roles, clusters and Aura, bearer tokens and Neo4j 2025 versions. JVM checks do not establish native-image or installer compatibility.

Neo4j documents [Cypher](https://neo4j.com/docs/cypher-manual/current/), [query types and EXPLAIN](https://neo4j.com/docs/cypher-manual/current/planning-and-tuning/), [transactions in the Java driver](https://neo4j.com/docs/java-manual/current/transactions/), [access modes](https://neo4j.com/docs/java-manual/current/transactions/#_request_routing), [connection URIs](https://neo4j.com/docs/java-manual/current/connect-advanced/) and [built-in roles](https://neo4j.com/docs/operations-manual/current/authentication-authorization/built-in-roles/).
