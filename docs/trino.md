# Control AI access to Trino and Presto

GateRook's MCP Firewall lets AI apps explore Trino catalogs and run SQL reads that Trino's own plan confirms, and holds every change for your review. The [security model](security.md) explains execution controls, explicit Bypass, and the append-only, tamper-evident audit trail.

## Connect a coordinator

The built-in **Trino / Presto** connector (`trino`) speaks Trino's client protocol to one coordinator: it sends each statement to `/v1/statement` and follows the coordinator's `nextUri` until the query ends. There is no Trino client library in GateRook. Choose:

- the **coordinator URL**, such as `https://trino.example.com:8443`, without a path. Every request goes to that scheme, host and port: when the coordinator names another address for the rest of a query, GateRook stops there and sends nothing to it. Behind a proxy, let Trino see the client's address (`http-server.process-forwarded=true`). A private CA goes under **CA certificate**;
- the **engine**: Trino, or Presto (prestodb), which uses `X-Presto-*` headers;
- **authentication**: a user and password (HTTP Basic), or a JWT sent as a bearer token. Both travel only over TLS, unless the coordinator runs on this computer. Without authentication Trino believes any user name, so that mode is limited to dev connections and to a coordinator on this computer;
- the **user** statements run as, and optionally a **default catalog** and **default schema** for names without one;
- the **catalogs** agents may use, with `*` matching any characters. Empty, every catalog the user may read.

Trino has no read-only session, so the user's access control in Trino stays part of the boundary: give it read access only wherever you can. **Test connection** reads the coordinator's version, runs `SELECT current_user` to show who the statements run as, and lists the catalogs agents may use.

## Bound reads and review exact changes

| Tool | What it does | Default mode |
| --- | --- | --- |
| `trino_list_catalogs` | Catalogs agents may use | Read only |
| `trino_list_schemas` | Schemas of one catalog | Read only |
| `trino_list_tables` | Tables and views of one schema, optionally matching a `LIKE` pattern | Read only |
| `trino_describe_table` | Columns with their types and comments, and the table's comment | Read only |
| `trino_query` | One `SELECT`, `WITH`, `VALUES`, `SHOW`, `DESCRIBE` or `EXPLAIN` | Read only |
| `trino_execute` | One `INSERT`, `UPDATE`, `DELETE`, `MERGE`, `CREATE`, `ALTER`, `DROP`, `COMMENT`, `TRUNCATE`, `ANALYZE` or `REFRESH` | Approval |

GateRook first reads each statement by Trino's tokens: `'…'` strings with `''`, `"…"` names with `""`, `--` comments to the end of the line and block comments, which don't nest. It refuses what it can't read with certainty (an open quote or comment, a comment opened inside another or closed outside one, a backquoted name, a control character, a character outside ASCII in the code) and more than one statement; a single final semicolon is left out of what is sent, as Trino refuses it. Only the read statements above run through `trino_query`, and `EXPLAIN ANALYZE`, which runs its statement, is refused.

Before a read runs, Trino plans it: GateRook sends `EXPLAIN (TYPE IO, FORMAT JSON)` with the statement (with the query an `EXPLAIN` explains), and runs the statement only when Trino's answer names no table it writes and every table it reads is in a catalog of the connection. Tables read through a view count as the tables Trino reports. For forms whose plan names no table, such as `SHOW CREATE TABLE`, `SHOW STATS` and `SHOW FUNCTIONS`, GateRook checks the catalog of the name they show; `SHOW CATALOGS` is refused while the connection lists catalogs. Functions of another catalog (`catalog.schema.function`) must be in a listed catalog too.

GateRook never runs session and role statements (`SET SESSION`, `WITH SESSION`, `SET ROLE`, `SET SESSION AUTHORIZATION`, `SET PATH`, `USE`), access control (`GRANT`, `REVOKE`, `DENY`, `CREATE ROLE`, `DROP ROLE`, owners set with `AUTHORIZATION`), transactions, prepared statements (`PREPARE`, `EXECUTE`, `EXECUTE IMMEDIATE`, `DEALLOCATE`), procedures (`CALL`) or catalogs (`CREATE CATALOG`, `DROP CATALOG`). It refuses table functions other than Trino's built-in `sequence` and `exclude_columns`, as a pass-through query of a JDBC catalog runs any SQL in that database whatever Trino's plan shows, and AI functions (`ai_*`), which send data to a model provider.

Each statement runs in a session GateRook sets for the call: `query_max_run_time` and `query_max_execution_time` end it a second before the call's deadline, and with **Data scanned per statement** under **Advanced**, `query_max_scan_physical_bytes` stops a Trino statement that reads more from storage. GateRook cancels a query that is still running at the deadline. Answers keep Trino's types: an integer is a JSON number only where JavaScript keeps it exact, decimals stay text, timestamps are ISO 8601 with `Z` or their offset, binary values show their first 256 bytes in hex, arrays and maps are JSON, and rows with named fields are objects. A read stops at the endpoint's row limit or response size and cancels the query; Trino can't resume a query, so the answer has no cursor and says so: narrow the query, or page it with `ORDER BY`, `LIMIT` and `OFFSET`, to see further rows.

`trino_execute` runs one change. While the connection lists catalogs, its target must be in one of them, named as `catalog.schema.table` or in the default catalog. The approval shows the exact statement and Trino's plan: the table it writes and the tables it reads. A data definition statement has no plan: Trino checks it when it runs. A statement Trino refuses, a read, or one that reads or writes outside the listed catalogs is never put to a person. What runs is the approved statement, once, after Trino's plan is checked again. A change is never retried: an answer lost after the statement was sent, or a change still running at the deadline, which GateRook asks Trino to cancel, reports the outcome as unknown. Trino's connectors write a change when its query finishes; a connector without transactions may keep part of a failed one, so check the table before retrying.

## Boundaries

GateRook decides what it sends: the statement kinds above, Trino's plan of each read, the listed catalogs, the deadline and the row and size limits. Trino decides what the user may do: its access control, resource groups and the coordinator's own limits. Trino's plan is the authority for what a read touches; GateRook does not prove that a function the coordinator offers without a catalog, such as a plugin's, has no effect beyond the plan.

## Verify

The connector's tests run against a fake coordinator in every build. With Docker, start Trino 484 with its `memory` and `tpch` catalogs from the [test services](../test-services/README.md) and run the integration test:

```bash
scripts/test-services.sh up trino
scripts/test-services.sh run mvn -B -ntp -pl connectors/trino -am verify -Pservices,trino-it -Dit.test=TrinoIT -Dfailsafe.failIfNoSpecifiedTests=false
```

It reads `tpch` through every tool, shows Trino's plan of a write refused by the read check, runs an approved `CREATE TABLE` and `INSERT` into `memory`, and checks in the coordinator's own records that a query stopped at the row limit, the response size or the deadline was cancelled. Presto mode was checked against the protocol's documented headers only, not against a Presto server. JVM checks do not establish native-image or installer compatibility.

Trino documents its [client protocol](https://trino.io/docs/current/develop/client-protocol.html), [EXPLAIN](https://trino.io/docs/current/sql/explain.html), [session properties](https://trino.io/docs/current/admin/properties-query-management.html), [table functions](https://trino.io/docs/current/functions/table.html) and [access control](https://trino.io/docs/current/security/overview.html).
