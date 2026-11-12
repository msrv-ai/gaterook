# Control AI access to a SQLite database

GateRook's MCP Firewall lets AI apps read a SQLite database file on this computer through bounded, read-only queries, and holds every change for your review. The [security model](security.md) explains execution controls, explicit Bypass, and the append-only, tamper-evident audit trail.

## Choose the database file

The built-in **SQLite** connector (`sqlite`) opens one database file with the SQLite library bundled with GateRook. Choose the file with **Choose file…** in the desktop app, or enter an absolute path or `~/…`, such as `~/projects/shop/db/development.sqlite3`; while you type, the form offers the SQLite files of that folder. There is no host, user or password: what GateRook may do with the file is what your account may do with it.

Only you choose the file. The setting is marked person-only, so an [AI connection import](ai-connection-import.md) cannot propose a path: an imported SQLite connection waits for you to choose its file. GateRook refuses:

- a relative path, a path with a question mark or control characters, and a network share or device path;
- a missing file, a folder, and a file that does not start with SQLite's header (an empty file is an empty database);
- GateRook's own folder and its database, also when reached through a link.

GateRook checks the file whenever a connection to it opens, not only at **Test connection**. It never creates a file.

**Test connection** reports the SQLite version, the size and journal mode of the file, and whether your account may write the file and its folder. On stage and prod a writable file is a warning: use a read-only copy, or keep `sql_execute` off.

## Bound reads and review exact SQL

The connector provides the same tools as the other SQL connectors: `sql_list_schemas`, `sql_describe_table`, `sql_query` and `sql_execute`. It also supports SQL query templates with bound parameters, row and response limits, statement timeouts and cancellation. Every table is in the `main` schema; SQLite's own `sqlite_*` tables are not listed. `sql_describe_table` returns the columns, primary key, indexes, foreign keys, and the table's `CREATE` statement, which holds its checks and defaults.

Reads use their own connections, which open the file read-only (`SQLITE_OPEN_READONLY`) and turn on `query_only`, so SQLite itself refuses writes and temporary tables. Approved changes use separate read-write connections. No connection may attach another database, load an extension, or build a value larger than 64 MiB. Every call turns `trusted_schema` off, so views, triggers, defaults and generated columns stored in the file cannot call functions that have side effects. The SQLite JDBC driver does not enforce statement timeouts itself; GateRook interrupts a statement at its deadline and when the call is cancelled.

The classifier reads SQLite's tokens: `"…"`, `` `…` `` and `[…]` names, `''` inside strings, no backslash escapes, and block comments that do not nest. `INSERT OR IGNORE`, `UPDATE OR REPLACE`, `GLOB`, `MATCH` and `EXPLAIN QUERY PLAN` are analyzed like their standard forms. These statements are refused for every tool: `PRAGMA`, `ATTACH`, `DETACH`, `VACUUM`, `REINDEX`, `ANALYZE`, transaction control, and objects in the `temp` schema. These functions are refused too: `load_extension`, `fts3_tokenizer`, raw page access (`sqlite_dbpage`), and the file and SQL-text functions of the SQLite shell and its extensions.

Writes use `sql_execute`, require approval by default, and run one statement in a transaction that commits after it succeeds. SQLite schema changes are transactional, so a failed one leaves the file as it was. **Enforce foreign keys** is on by default: SQLite checks the file's foreign keys and runs their `ON DELETE` and `ON UPDATE` actions on approved changes, as most apps that use foreign keys do. Turn it off only for a file whose application does not use them. **Lock wait** sets how long a call waits while another program writes to the file.

When a table allowlist restricts which tables agents may use, SQLite's catalog tables and the `pragma_*` table-valued functions are refused, because they describe any table by name. Name a catalog table exactly to open it. Name comparisons follow SQLite: they ignore ASCII case, quoted or not.

Template parameters bind as SQLite stores values: times are UTC text in the format of SQLite's `datetime()` (`2026-11-01 10:30:00`, with milliseconds when present), dates are `YYYY-MM-DD` text, and booleans are `1` or `0`. Compare a column stored in another format with `datetime(column)`.

## Verify with a local file

SQLite needs no test service: the connector's tests create database files in a temporary folder and check read-only enforcement, approved changes, timeouts, cancellation and the file rules. JVM checks do not establish native-image or installer compatibility.

SQLite documents [open flags](https://www.sqlite.org/c3ref/open.html), [`query_only`](https://www.sqlite.org/pragma.html#pragma_query_only), [`trusted_schema`](https://www.sqlite.org/pragma.html#pragma_trusted_schema) and [run-time limits](https://www.sqlite.org/c3ref/c_limit_attached.html).
