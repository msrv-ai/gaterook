# Control AI access to a Microsoft Access database

GateRook's MCP Firewall lets AI apps read a Microsoft Access database file (`.accdb` or `.mdb`) on this computer through bounded, read-only queries, and holds every change for your review. The [security model](security.md) explains execution controls, explicit Bypass, and the append-only, tamper-evident audit trail.

## Get the Microsoft Access connector

Microsoft Access is one of GateRook's own connectors, delivered as a [connector module](connector-delivery.md#connector-modules): it isn't part of the installer. The first time you choose Microsoft Access in **Add connection**, GateRook downloads its package (about 4 MB), checks it against its signed list of modules and starts using it; there is nothing to review or confirm, and later versions arrive the same way. The connector's code runs in a module host, a separate Java process with its own data folder, started by GateRook. The package holds UCanAccess 5.3.0, Jackcess 5.2.0 and HSQLDB 2.7.4 unmodified, under the Apache License 2.0 and HSQLDB's BSD-style license (`META-INF/LICENSE-Access-module.txt` says which file is whose). It needs no Microsoft software and runs on every system GateRook ships for.

To build the package yourself, `mvn -B -ntp -pl modules/msaccess -am package -DskipTests` writes `modules/msaccess/target/msaccess-1.1.0.jar`. **Connectors → Add connector → Install plugin or module** takes the file: a package that GateRook's signed list has installs as GateRook's own connector, and any other build is [your own module](connector-delivery.md#a-module-from-a-file), reviewed as unsigned code, whose tools all start in **Approval**.

On an endpoint the three reads start in **Read only** and `access_execute` in **Approval**.

## Choose the database file

A connection is one Access file. In the desktop app, **Choose** opens the system's file dialog with `.accdb` and `.mdb` files; or enter an absolute path or `~/…`, such as `~/Documents/orders.accdb`. Only you choose the file: the setting is person-only, so an [AI connection import](ai-connection-import.md) cannot propose a path. GateRook refuses:

- a relative path, a network share, and a path with a question mark, a semicolon (UCanAccess reads what follows it as settings), control characters or, outside Windows, a backslash;
- a missing file, a folder, an empty file, a file whose header isn't Access's (`Standard Jet DB` or `Standard ACE DB`), and a file larger than 1 GB;
- GateRook's own folder and its database, also when reached through a link;
- a password-protected file: GateRook can't open encrypted Access files, so remove the password in Access first (**File → Info → Decrypt Database**).

GateRook checks the file whenever a call opens it, and gives UCanAccess the real path it checked. It never creates a file. Linked tables to other files aren't loaded.

## Bound reads and review exact SQL

| Tool | What it does |
| --- | --- |
| `access_list_tables` | The tables and saved select queries of the file |
| `access_describe_table` | One table's or saved query's columns with their types, sizes, nullability, defaults and AutoNumber, its primary key and indexes |
| `access_query` | One SELECT or WITH … SELECT in Access SQL |
| `access_execute` | One INSERT, UPDATE or DELETE, or CREATE TABLE, ALTER TABLE, DROP TABLE or CREATE INDEX, after approval |

UCanAccess reads Access SQL: names in brackets (`[Order Details]`), strings in single or double quotes, dates as `#2026-11-12#`, `&` to join text, `*` and `?` in `LIKE`, `TOP n`, and Access functions such as `IIf`, `Nz`, `Format`, `DateAdd` and `DatePart`. It copies the file into an HSQLDB database, in memory for files up to 128 MB and in the connector's data folder above that, keeps the copy for a while after a call, and loads it again when the file changes. A change GateRook makes is seen by the next read at once; a change Access makes is seen once the file is two seconds newer than the copy.

Four things keep `access_query` from changing anything. GateRook admits one SELECT and refuses `SELECT … INTO` (Access's make-table query), `LOAD_FILE` (HSQLDB reads a file with it), the domain functions (`DLookup`, `DSum`, `DCount` and the others run SQL built from their string arguments), `NEXT VALUE FOR`, `FOR UPDATE`, JDBC escapes, more than one statement, and statements that act on the copy rather than the file (`SHUTDOWN`, `SET`, `SCRIPT`, `CHECKPOINT`, `CALL`, transactions, rights, routines, triggers). Jackcess opens the file read-only. The query runs in an HSQLDB transaction that is read-only and rolled back. And on macOS the sandbox of the connector's process lets it write its own folder only. A saved query of the file that calls `LOAD_FILE`, which no query Access writes does, keeps every read of that file from running. Answers keep the types: integers are JSON numbers where JavaScript keeps them exact, Currency and Decimal values are text, dates are ISO-8601 and OLE objects show their first 256 bytes in hex.

`access_execute` runs one statement exactly as approved on a writable copy of its own and writes it to the file once, when it commits; GateRook first checks that the file opens for writing, so a change the file's permissions or the sandbox refuse says so and changes nothing. Jackcess writes rows one by one, without a transaction of its own: if writing the change to the file fails part way, GateRook says the outcome is unknown, and never retries it. On macOS the sandbox lets the module host write only its own folder, so changes to a file elsewhere fail there; keep a copy of a file you let agents change.

## Verify

The module's tests run against real `.accdb` files written by Jackcess: the tools, Access SQL and types, the refusals, HSQLDB's own refusal of a change on a read connection, an approved change that the next read and the file both show, and the file rules. `AccessModuleHostIT` loads the built package in a real module host: on macOS under the core's `sandbox-exec` rules it reads the person's file and an approved change can't open it for writing, and without the sandbox the change applies and the next read sees it. `AccessModuleCoreProcessIT` (in `app`) has the assembled core install the package from a list of modules and read a file through it. These tests don't cover files Access itself wrote (saved queries, attachments, multi-value fields), password-protected files, or Windows and Linux hosts.
