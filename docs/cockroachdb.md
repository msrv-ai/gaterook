# Control AI access to CockroachDB

CockroachDB speaks PostgreSQL's wire protocol, so GateRook connects to it with the built-in **PostgreSQL** connection type and applies the same MCP Firewall controls: bounded reads in read-only transactions, and every change held for your review. The [security model](security.md) explains execution controls, explicit Bypass, and the append-only, tamper-evident audit trail.

## Connect

Add a **PostgreSQL** connection with the cluster's host, port (`26257` by default) and database, and a SQL user with a password. Keep TLS on: choose **verify-full** and paste the cluster's CA certificate (CockroachDB Cloud lets you download it; a self-hosted cluster has it as `ca.crt` in its certificates directory). GateRook never sends a client certificate, so sign in with a password. **Test connection** reports the server as CockroachDB with its version, the TLS state and what the user may do.

For a read-only user:

```sql
CREATE USER gw_reader WITH PASSWORD 'choose-one';
GRANT CONNECT ON DATABASE app TO gw_reader;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO gw_reader;
REVOKE CREATE ON SCHEMA public FROM public;
```

Like PostgreSQL before version 15, CockroachDB lets every user create tables in `public` unless that grant is revoked; Test connection reports such a user as one that can write. Members of CockroachDB's `admin` role count as administrators.

## What works as on PostgreSQL

`sql_list_schemas`, `sql_describe_table`, `sql_query` and `sql_execute` behave as on PostgreSQL. Every read runs in a `READ ONLY` transaction that is rolled back, with the policy's statement timeout set for that transaction only; CockroachDB refuses a write, a sequence's `nextval` and `SELECT … FOR UPDATE` in it, whatever the user's grants. Before a change is put to a person, CockroachDB's `EXPLAIN` checks that the statement can run and estimates its rows. Results keep CockroachDB's types: `INT8`, `DECIMAL`, `BOOL`, `TIMESTAMPTZ`, `BYTES`, `JSONB`, arrays and `UUID`.

The differences GateRook accounts for: CockroachDB names itself in a session parameter rather than in `server_version` (which reads `13.0.0`), has no `EXPLAIN (FORMAT JSON)`, and refuses privilege checks of its virtual `crdb_internal` tables, which listings and Test connection leave out, as they hold cluster internals rather than data.

## Verify

The `cockroach` profile of the [Docker test services](../test-services/README.md#cockroachdb) runs one CockroachDB node with TLS for the connector's integration test. JVM checks do not establish native-image or installer compatibility, nor behaviour against multi-node clusters or CockroachDB Cloud.

Cockroach Labs documents [PostgreSQL compatibility](https://www.cockroachlabs.com/docs/stable/postgresql-compatibility), [authentication](https://www.cockroachlabs.com/docs/stable/authentication), [`EXPLAIN`](https://www.cockroachlabs.com/docs/stable/explain) and [privileges](https://www.cockroachlabs.com/docs/stable/security-reference/authorization).
