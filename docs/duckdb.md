# Control AI access to a DuckDB database

GateRook's MCP Firewall lets AI apps read a DuckDB database file on this computer through bounded, read-only queries, and holds every change for your review. The [security model](security.md) explains execution controls, explicit Bypass, and the append-only, tamper-evident audit trail.

## Get the DuckDB connector

DuckDB is one of GateRook's own connectors, delivered as a [connector module](connector-delivery.md#connector-modules): it isn't part of the installer. The first time you choose DuckDB in **Add connection**, GateRook downloads its package (about 34 MB), checks it against its signed list of modules and starts using it; there is nothing to review or confirm, and later versions arrive the same way. The connector's code runs in a module host, a separate Java process with its own data folder, started by GateRook. The package holds the DuckDB JDBC driver 2.0.0.1 and DuckDB's native libraries for **macOS on Apple Silicon** and **Windows x64**, the systems GateRook ships for: on another system the connector says it has no DuckDB library for it and runs nothing.

To build the package yourself, `mvn -B -ntp -pl modules/duckdb -am package -DskipTests` writes `modules/duckdb/target/duckdb-1.1.0.jar`; the build is reproducible, so the same sources give the same SHA-256. **Connectors → Add connector → Install plugin or module** takes the file: a package that GateRook's signed list has installs as GateRook's own connector, and any other build is [your own module](connector-delivery.md#a-module-from-a-file), reviewed as unsigned code, whose tools all start in **Approval**.

Java support for modules downloads on first use and is cached ([optional components](development.md#optional-components-and-offline-use)). On an endpoint the three reads start in **Read only** and `duckdb_execute` in **Approval**.

## Choose the database file

A DuckDB connection is one database file. In the desktop app, **Choose** opens the system's file dialog; or enter an absolute path or `~/…`, such as `~/projects/analytics/warehouse.duckdb`. Choosing returns only the path: GateRook doesn't open the file until a call does. There is no host, user or password: what GateRook may do with the file is what the connector's process may do with it.

Only you choose the file. The setting is marked person-only, so an [AI connection import](ai-connection-import.md) cannot propose a path. GateRook refuses:

- a relative path; a path with a question mark, a semicolon (the DuckDB driver reads what follows it as settings), control characters or, outside Windows, a backslash (DuckDB reads it as a folder separator, so it would open another file); and a network share or device path;
- a missing file, a folder, an empty file, and a file whose header isn't DuckDB's;
- GateRook's own folder and its database, also when reached through a link.

GateRook checks the file whenever a call opens it, not only at **Test connection**, and gives DuckDB the real path it checked. It never creates a file.

## Let queries read data files

By default queries read the database file only. **Data folders** (person-only too) name folders whose Parquet, CSV and JSON files queries may read with `read_parquet`, `read_csv`, `read_json`, `read_text`, `read_blob` and `glob`, or by naming a file where a table goes (`FROM '/data/sales/2026/*.parquet'`). Queries name files by their absolute path, without `~`; `current_setting('allowed_directories')` lists the folders as DuckDB resolved them.

GateRook refuses a data folder that is GateRook's own folder, lies in it or holds it, your home folder or a folder above it, the root of a disk, and a folder that holds the database file. That last rule matters because DuckDB's allowed folders admit writes as well as reads: a statement could otherwise overwrite the database through its own folder.

Once the connector's host runs, the form offers the DuckDB files of the folder you type and, for the database file you chose, the subfolders next to it that hold Parquet, CSV or JSON files as data folders, saying how many of each; data files beside the database itself get a note to move them into a subfolder. Nothing is filled in: both fields stay yours to choose.

## Bound reads and review exact SQL

| Tool | What it does |
| --- | --- |
| `duckdb_query` | One read-only statement: SELECT, WITH … SELECT, FROM …, VALUES, TABLE, PIVOT, UNPIVOT, DESCRIBE, SHOW, SUMMARIZE or EXPLAIN |
| `duckdb_list_tables` | The tables and views by schema, with estimated rows, column counts and comments |
| `duckdb_describe_table` | One table's or view's columns, primary key, constraints, indexes and `CREATE` statement |
| `duckdb_execute` | One INSERT, UPDATE, DELETE or DDL statement (CREATE, ALTER, DROP, TRUNCATE, COMMENT ON), after approval |

Every call runs on a DuckDB instance of its own, opened for that call and closed after it, so no setting, temporary table or variable outlives it. Before DuckDB opens the file, the instance gets `access_mode=READ_ONLY` (reads), `enable_external_access=false` with `allowed_directories` limited to the data folders, `autoinstall_known_extensions=false`, `autoload_known_extensions=false`, community extensions and persistent secrets off, no temporary directory, the connection's **Memory limit** (512 MB by default) and **Threads** (2), and `lock_configuration=true`, after which no statement can change a setting. GateRook reads the settings back from the open instance and refuses the call if one differs.

A query that needs more memory than the limit fails: nothing spills to disk. Results follow the environment's row, response-size and cell limits; GateRook bounds the rows in DuckDB itself by running the statement inside an outer `LIMIT`, so a query over a large table returns its first rows instead of materializing them all. GateRook stops a statement at its deadline, the environment's statement timeout or the connection's stricter one, with DuckDB's interrupt, which also stops a runaway recursive query, and when the call is cancelled.

The classifier reads DuckDB's tokens: `'…'` strings with `''`, `E'…'` and dollar-quoted strings, `"…"` names, line comments and nested block comments. It allows one statement per call and refuses `ATTACH`, `DETACH`, `COPY`, `EXPORT DATABASE`, `IMPORT DATABASE`, `INSTALL`, `LOAD`, `SET`, `RESET`, `PRAGMA`, `CALL`, `CHECKPOINT`, `USE`, transaction control, prepared statements, secrets, a statement that changes data inside parentheses, functions that run SQL from text (`query`, `query_table`) or reach other systems, and file reads of anything but an absolute path written as text inside a data folder. It refuses whatever it can't read.

`duckdb_execute` requires approval by default and is never retried. The approval shows the statement exactly as it will run, the file it runs on and the limits it runs under. The connector runs only an approved call, only its approved statement, on a separate read-write instance with the same external-access and configuration locks. DuckDB runs the statement as one transaction, DDL included, and rolls it back if it fails or reaches its deadline. Calls on one file wait for each other: a change waits until running reads of the file end. A change keeps the file's storage version, so a file written by an older DuckDB stays readable by it.

## What is enforced where

| Layer | What it enforces |
| --- | --- |
| GateRook core | Endpoint access, tool modes, approval of the exact call, policy limits, masking and the audit trail |
| Classifier | One read or one change, the refused statements and functions, file paths inside the data folders |
| DuckDB | Read-only file handles for reads, external access off except the data folders (links resolved, `..` refused), no extension installed or loaded, locked settings, the memory and thread limits |
| Connector's process | One instance per call on its own thread, the deadline and cancellation, the file rules at every open |
| OS sandbox | On macOS the connector's process writes only its own data folder and can't read GateRook's home or credential folders |

Known limits:

- On macOS the sandbox of the connector's process lets it write only its own data folder, so `duckdb_execute` can't change a database file elsewhere there: the change fails and nothing is written. **Test connection** says the file can't be written. Reads work.
- On Windows the connector's process runs at low integrity, can't write your files, and refuses DLLs with a low label, which is where the DuckDB driver unpacks its library. DuckDB is unverified there.
- DuckDB itself would let a statement write files in a data folder. The classifier refuses `COPY` and `EXPORT`, and the macOS sandbox refuses the write.
- A file that another program has open for writing can't be read: DuckDB lets other programs in only while none writes it. Close that program, or point the connection at a copy.

**Test connection** reports the DuckDB version, the file's size and storage version, the number of tables and views, and whether GateRook may write the file and its folder. On stage and prod a writable file is a warning: use a read-only copy, or keep `duckdb_execute` off.

## Verify with a local file

DuckDB needs no test service. The module's tests create database files in a temporary folder and check the classifier and the file rules, and they show DuckDB's own enforcement with the classifier bypassed: writes, `ATTACH`, `COPY TO`, reading outside the data folders, `INSTALL` and `LOAD` refused, locked settings, the memory limit, timeouts, cancellation and approved changes. `DuckDbModuleHostIT` loads the built package in a real module host, sandboxed on macOS as GateRook sandboxes it, and `DuckDbModuleCoreProcessIT` (in `app`) has the assembled core install the package from a list of modules and read a database file through it:

```bash
mvn -B -ntp -pl modules/duckdb -am verify -Dtest='ai.msrv.gaterook.connectors.duckdb.*Test' -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=DuckDbModuleHostIT -Dfailsafe.failIfNoSpecifiedTests=false
```

JVM checks do not establish compatibility with the bundled Java runtime, a published package or every operating system.

DuckDB documents [securing DuckDB](https://duckdb.org/docs/current/operations_manual/securing_duckdb/overview) and [concurrency](https://duckdb.org/docs/current/connect/concurrency).
