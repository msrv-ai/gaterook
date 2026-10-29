# Oracle Database

The built-in **Oracle Database** connector uses JDBC Thin to connect to Oracle Database 19c and later. It exposes the same tools as MySQL: `sql_list_schemas`, `sql_describe_table`, `sql_query` and `sql_execute`, plus parameterized SQL query templates.

## Connect

Choose **Oracle Database** in **Connections → Add connection**. Enter the listener host and port, the service name and the database user and password. For example, use `oracle.example.test`, port `1521` and service `orders.example.test`. The service name is stored in `connection.database`; one connection reaches one service. SID connections, TNS aliases and Oracle wallets are not supported.

Under **Advanced**, select the transport that matches the listener. The default, **Verify the CA and host name**, uses TCPS and requires a TLS listener on the chosen port. **Verify the CA** also uses TCPS. Supply a private CA certificate in PEM format when needed; otherwise the driver uses the system trust store. **Off** uses unencrypted TCP.

The default schema is the authenticated user's schema. Set **Default schema** to use another permitted schema. Unquoted Oracle identifiers use uppercase; double-quote case-sensitive names. Restrict accessible schemas with the connection's schema allowlist and the endpoint's SQL policy. When an external credential provider supplies the username, set an explicit default schema or qualify table names for schema introspection.

## Query and change data

`sql_query` runs one analyzed read in an explicit read-only transaction and rolls it back when finished. Row, response-size and timeout limits apply. Oracle statement timeouts use JDBC cancellation rounded up to whole seconds. `sql_list_schemas` lists accessible tables and views within the allowlist; `sql_describe_table` returns their columns and constraints. SQL query templates bind parameter values through JDBC; boolean parameters use `1` and `0` for compatibility with Oracle 19c.

`sql_execute` requires approval by default and follows the shared SQL policy. Oracle schema changes commit implicitly and cannot be rolled back; the approval preview identifies this behavior. Oracle plan estimates are unavailable because `EXPLAIN PLAN` writes to a plan table.

Use a restricted database user and trusted schemas and views. The connector blocks explicit unknown function calls, procedural PL/SQL and database links. A read-only transaction does not prevent an Oracle autonomous routine from producing side effects, including when a view or an implicit function reference invokes it. Restrict database grants accordingly.

## Integration tests

`OracleIT` runs only when `GW_ORACLE_HOST` is set. Use a disposable Oracle service and a separate test user with `CREATE SESSION`, `CREATE TABLE` and a small quota on its default tablespace. The tests create and remove `GW_ORACLE_IT_CUSTOMERS`, `GW_ORACLE_IT_ORDERS` and `"GateRook mixed table"` in that user's schema. They use TCP without TLS and must not run against production data.

From the repository root, with the normal Java toolchain configured:

```bash
export GW_ORACLE_HOST=127.0.0.1
export GW_ORACLE_PORT=1521
export GW_ORACLE_SERVICE=FREEPDB1
export GW_ORACLE_USER=GATEROOK_TEST
export GW_ORACLE_PASSWORD='replace-with-disposable-password'
mvn -B -ntp -pl connectors/sql -am -Dit.test=OracleIT \
  -Dfailsafe.failIfNoSpecifiedTests=false verify
```

The suite exercises real connection checks, queries, approved writes, read-only enforcement, schema metadata, parameter binding and cancellation. TCPS certificate verification is not covered by this suite.
