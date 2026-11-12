# Control AI access to Apache Cassandra

GateRook's MCP Firewall lets AI apps read Apache Cassandra and CQL-compatible services with bounded queries and holds every change for your review. Cassandra has no read-only session, so the grants of the role GateRook signs in as remain the boundary: GateRook's reading of each statement is defence in depth. The [security model](security.md) explains execution controls, explicit Bypass, and the append-only, tamper-evident audit trail.

## Connect a cluster

The built-in **Apache Cassandra** connector (`cassandra`) uses the Apache Cassandra Java driver 4.19 with plain CQL, TLS and password sign-in. It covers Apache Cassandra 4 and 5, ScyllaDB, and CQL services that take a user name and password, such as Amazon Keyspaces with service-specific credentials and Azure Managed Instance for Apache Cassandra. Vendor sign-in plugins (SigV4, Kerberos, LDAP plugins of the driver) are not supported. Choose:

- **Contact points**: `host:port` of one or more nodes (`9042` without a port; Amazon Keyspaces listens on `9142`). The driver finds the other nodes itself;
- **Local data center**: the data center whose nodes serve every request, as `nodetool status` names it (Amazon Keyspaces: the region). Nodes of other data centers are never used. Once the form can sign in, GateRook fills an empty field with the contact points' data center and offers the cluster's others;
- **Keyspace**: the keyspace of statements that don't name one. GateRook sets it for the session and refuses `USE`;
- **Keyspaces**: the keyspaces agents may use, with `*` matching any characters (`shop_*`). Empty, every keyspace but Cassandra's own. Names are as Cassandra stores them: lower case unless created with quotes;
- the **User** and **Password** of a role, stored encrypted in GateRook, or both read from an external store;
- **Connection type**: **Auto**, the default for new dev and stage connections, finds out on the first contact point that answers, before signing in, whether the nodes speak TLS. A TLS handshake comes first, verified as below; a node that completes it gets TLS, also one with `client_encryption_options.optional` that speaks both. A certificate or name that fails verification is reported and is never a reason to drop encryption, and neither is a handshake that times out. Only a node that closes the connection or answers bytes that aren't TLS (Cassandra reads a TLS hello as a frame of an unknown protocol version) gets CQL without TLS, after it answered `OPTIONS` without credentials on a connection of its own. With a CA certificate in the form, the nodes must speak TLS. Test connection's connect check says `(Auto)`. New prod connections use **TLS**, and connections saved without a connection type keep TLS. **Verify the CA and host name** checks each node's certificate against its address as GateRook connects to it (a contact point's name, or the IP address the cluster announces for a peer; never a reverse DNS lookup), or against **TLS server name** when set, which is also sent as SNI. Amazon Keyspaces needs its endpoint's name there, as every node presents that certificate. **Verify the CA only** needs the cluster's own CA certificate: with the system's CAs, any server with a public certificate would pass. A private CA goes in **CA certificate (PEM)**;
- **Read consistency**: `LOCAL_ONE` (default) or `LOCAL_QUORUM`. Changes always write at `LOCAL_QUORUM`, and lightweight transactions use `LOCAL_SERIAL`.

While you fill in the form, GateRook offers the host, port and user of cqlsh's `~/.cassandra/cqlshrc` (never its password) and `localhost:9042` when a node answers there. Once the form can sign in, it opens a session of its own, as Test connection does, and lists the cluster's data centers with their nodes and its keyspaces for **Keyspace** and **Keyspaces**.

GateRook resolves the contact points once when it connects and refuses a name with a link-local, cloud metadata or reserved address. A node the cluster announces at such an address is ignored, never connected to. Writes carry the coordinator's timestamp, not the desktop's clock, so a computer whose clock is off can't make its writes win or lose against other clients.

## The role is the boundary

Nothing in a Cassandra session keeps a role from writing. For reads alone, sign in with a role that may only `SELECT`:

```sql
CREATE ROLE gw_reader WITH PASSWORD = 'choose-one' AND LOGIN = true;
GRANT SELECT ON KEYSPACE shop TO gw_reader;
```

`cassandra_execute` needs `MODIFY` on the keyspaces it changes, and `CREATE`, `ALTER` or `DROP` for schema changes. **Test connection** opens a session of its own, reports the cluster's name, its release and its data centers with their nodes from `system.local` and `system.peers`, checks that the local data center has nodes, and lists the keyspaces agents may use. Where the cluster lets the role list its own grants, it runs `LIST ROLES OF` and `LIST ALL PERMISSIONS OF` and reports the role as reading, writing or administering: a superuser or a role that may grant permissions is a danger outside dev, a role that may write is a warning outside dev. When the cluster doesn't say (no authorizer, or a service without these statements), the test says so and passes. Its hints always show the CQL of a role that only reads. Health runs `SELECT release_version FROM system.local`.

## Bound reads and review exact changes

| Tool | What it does | Default mode |
| --- | --- | --- |
| `cassandra_list_keyspaces` | Keyspaces agents may use, with replication, tables and materialized views, from `system_schema` | Read only |
| `cassandra_describe_table` | Columns with their kinds (partition key, clustering, static, regular), types and clustering order, secondary indexes, views, default TTL, compaction | Read only |
| `cassandra_query` | One CQL `SELECT` with `?` or `:name` values | Read only |
| `cassandra_execute` | One `INSERT`, `UPDATE`, `DELETE` or `TRUNCATE`, or `CREATE`, `ALTER` or `DROP` of a `TABLE`, `INDEX`, `TYPE` or `MATERIALIZED VIEW` | Approval |

GateRook reads each statement by the tokens of Cassandra's own lexer: `'…'` strings with doubled quotes, `$$…$$` strings, `"…"` names with doubled quotes, `--` and `//` comments up to a line break, and `/* … */` comments. What it can't read the same way is refused: an open quote or comment, a comment inside a comment, a line comment at the very end of the text (Cassandra reads `1 --2` there as `1 - -2`), a number that runs into letters, a `$` outside a string, unbalanced brackets, control characters, invisible characters in comments, and characters outside ASCII outside strings, quoted names and comments. Both tools refuse more than one statement, `BATCH`, `USE`, `GRANT`, `REVOKE`, `LIST`, roles, users and identities, `CREATE`, `ALTER` and `DROP KEYSPACE` (replication stays with people), functions, aggregates and triggers (code on the server), and `DESCRIBE`.

A statement names its table in fixed places (`FROM`, `INTO`, `UPDATE`, `ON`, after the object keywords of DDL); that table's keyspace, a view's base table and the keyspace of a function called as `keyspace.function(…)` must be on the connection's list. A statement without a keyspace uses the connection's, and is refused without one. `system_auth`, `dse_security` and ScyllaDB's role tables are never available. Cassandra's other own keyspaces (`system`, `system_*`, `dse_*`) are readable only when the list names them from their first character (`system_views`, `system*`; `*` doesn't open them), and are never changed.

`cassandra_query` refuses `ALLOW FILTERING`, which makes Cassandra read through partitions, possibly every one in the cluster. **Allow ALLOW FILTERING** under **Advanced** permits it on a dev connection used by a dev endpoint, never on stage or prod. Values are bound to the types Cassandra declares for the markers: an array for `?` in order, or an object for `:name`; strings for `text`, `uuid`, `inet` (an IP address; names are not looked up), dates, times and durations; numbers, with `bigint`, `varint` and `decimal` also as strings to stay exact; ISO-8601 or epoch milliseconds for `timestamp`; `0x…` for `blob`; arrays for lists, sets, tuples and vectors; objects for maps and UDTs. A value that doesn't fit its column is refused before the statement runs.

Answers keep Cassandra's types: integers within ±2^53 are numbers and larger ones exact text, `decimal` is its exact text, `timestamp`, `date` and `time` are ISO-8601, `blob` shows its first 256 bytes as `0x…` with its length, collections and tuples are arrays, UDTs objects, and text is cut to the endpoint's cell size. A page holds at most the endpoint's row limit and a share of its response size; `nextCursor` goes on where the answer stopped, also inside a page that the response size cut. Cursors are sealed by GateRook and belong to one connection and one request. A read runs at the connection's consistency within the stricter of the connection's and the policy's statement timeout, and never past the call's deadline.

The approval of `cassandra_execute` shows the exact statement, its values (with a warning when they are longer than the 2,000 characters shown), the connection's keyspace for names without one, and the consistency; `TRUNCATE`, dropping a table, view or type, and `USING TIMESTAMP` carry warnings. Before a person is asked, Cassandra prepares an `INSERT`, `UPDATE` or `DELETE` and GateRook binds its values, so a statement the cluster refuses, a table that doesn't exist or a value of the wrong type is never put to a person. What runs is the approved statement with its approved values, in the keyspace the person saw. It is sent to one node once: the statement is not idempotent, its profile never retries and has no speculative executions, and a timeout or a connection lost after it was sent is reported as an unknown outcome. A lightweight transaction (`IF NOT EXISTS`, `IF EXISTS`, `IF …`) reports `applied`, and the current values when it didn't apply. A schema change waits up to five seconds for the nodes to agree on the schema and says whether they did.

GateRook runs one statement per approval, so batches are refused: a logged batch's atomicity across partitions, its batchlog and its size limits are not something an approver can judge from the text. Send the statements one by one, or run the batch yourself.

## Driver details

The driver gets its options in code and from nothing else: no `application.conf`, no system properties, no schema metadata of its own, no metrics and no logging of server warnings, which go to the answer instead. Jackson 2, JNR and Dropwizard Metrics are not on GateRook's classpath. The driver's STARTUP message therefore carries its name and version, a client id and the application (GateRook and its version, shown in `system_views.clients` on Cassandra 4 and later), but not the driver's "baggage" JSON. Each connection's session has its own Netty threads (two for I/O, one for administration); it closes when the connection changes, and at the next call of any connection once it has been idle for ten minutes.

## Verify

The `cassandra` profile of the [Docker test services](../test-services/README.md#cassandra) runs one Apache Cassandra 5.0 node with password sign-in, permissions and TLS for the connector's integration test. ScyllaDB, Amazon Keyspaces, Azure Managed Instance, DataStax Enterprise, multi-node and multi-data-center clusters were not tested. JVM checks do not establish native-image or installer compatibility.

The Apache Cassandra project documents [CQL](https://cassandra.apache.org/doc/latest/cassandra/developing/cql/index.html), [roles and permissions](https://cassandra.apache.org/doc/latest/cassandra/developing/cql/security.html), [lightweight transactions](https://cassandra.apache.org/doc/latest/cassandra/developing/cql/dml.html) and the [Java driver](https://github.com/apache/cassandra-java-driver).
