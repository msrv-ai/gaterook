# SQL Server

The built-in **SQL Server** connector (`sqlserver`) uses Microsoft's JDBC driver over TCP with SQL authentication.
Create a connection with its host, TCP port (default **1433**), SQL login and password, then load and select the
databases it may expose. A named instance must have a known TCP port. Windows integrated authentication,
Microsoft Entra authentication and in-app password rotation are not supported.

TLS defaults to **verify-full**, which validates the certificate and host name. Add a PEM CA certificate for a
private CA. **require** encrypts without validating the certificate; it is suitable for the disposable test fixture.
**disable** does not request encryption, although the server may require it. Connection testing reports effective
permissions and TLS status; if a restricted login cannot inspect TLS metadata, the UI reports what the connection
settings establish or leaves the status unknown. It does not require granting server-state permissions to a reader.

The connector provides the same tools as MySQL: `sql_list_schemas`, `sql_describe_table`, `sql_query` and `sql_execute`.
It also supports SQL query templates with bound parameters, response limits, cancellation and pooled connections.
Calls must provide `database` when several databases are selected; approvals retain the selected database and exact
SQL text. Three- and four-part object names are refused: select the database explicitly and use `schema.table`.

SQL Server does not provide JDBC read-only transactions. GateRook therefore admits only one analyzed `SELECT`
(or `WITH ... SELECT`) with known built-in functions for reads, and always rolls the transaction back. Use a dedicated
login with only the required `SELECT` grants for server-enforced protection. Reads calling unknown functions remain
refused even with approval. Transactions, session commands, temporary objects, stored procedures, sequence advancement,
`OUTPUT` clauses and external rowsets cannot run through this connector. Views and computed expressions retain
the permissions and behavior configured on the server; gateway table rules are not a replacement for database grants.

Writes use `sql_execute`, require approval by default, and run one analyzed statement in a transaction. Existing
DDL and table policies still apply. Before requesting approval, GateRook estimates DML impact using
`SHOWPLAN_XML`, which does not execute the change and requires `SHOWPLAN` permission. If the login cannot obtain
a plan, the approval states that the estimate is unavailable. Statements with `OUTPUT` are currently refused
because their additional write targets cannot be verified.

Set **Default schema** to the user's actual database default (normally `dbo`). GateRook verifies this when borrowing
a connection. With a restricted table allowlist and a different default schema, qualify table names explicitly to
avoid SQL Server's fallback lookup in `dbo`. Identifier comparisons in gateway allowlists are exact, even when the
server uses a case-insensitive collation.

For local verification, use the opt-in [SQL Server test service](../test-services/README.md#sql-server).
JVM checks and database integration tests do not establish native-image or installer compatibility.

Microsoft documents the [JDBC read-only limitation](https://learn.microsoft.com/en-us/sql/connect/jdbc/reference/setreadonly-method-sqlserverconnection?view=sql-server-ver17),
[connection properties](https://learn.microsoft.com/en-us/sql/connect/jdbc/setting-the-connection-properties?view=sql-server-ver17)
and [SHOWPLAN_XML](https://learn.microsoft.com/en-us/sql/t-sql/statements/set-showplan-xml-transact-sql?view=sql-server-ver17).
