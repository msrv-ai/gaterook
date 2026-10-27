# 04 · PostgreSQL, MySQL, and MariaDB connectors

> Research snapshot: 2026-10-23. Recorded experiments used PostgreSQL 16.15, MySQL 8.4.10, MariaDB 11.4.5, Temurin 25.0.5, pgjdbc 42.7.14, Connector/J 9.7.0, JSqlParser 5.4, and HikariCP 7.0.2. This document preserves their findings and limitations; it does not assert a new test run. **UNVERIFIED** identifies unresolved behavior. See the [architecture](../ARCHITECTURE.md) and [MCP report](01-mcp-protocol.md).

## 1. Decision and guarantees

Use a Spring-independent JDBC connector with PostgreSQL and MySQL dialects. Expose `postgres` and `mysql` connection types and the same four tools. The MySQL dialect recognizes MariaDB by its version string: the reviewed Connector/J returned product name `MySQL` for MariaDB too.

Use a lazy Hikari pool per connection, normally two connections, zero minimum idle, and a 60-second idle timeout. Close pools on configuration/secret changes, removal, and extended inactivity. Direct JDBC permits explicit transaction control, row limits, metadata, and cancellation.

Three layers work together:

1. The database enforces READ ONLY transactions for every read tool, with server timeouts and unconditional rollback afterward.
2. The SQL parser classifies requests, identifies DDL and dangerous functions, checks referenced objects, and routes mutations through human approval.
3. Database account privileges and replica deployment provide the actual authorization boundary.

The parser cannot inspect function bodies, view definitions, triggers, or remote connections opened by functions. A parser-only read guarantee is insufficient. Conversely, READ ONLY does not stop every administrative or external side effect, so dangerous-function and statement restrictions remain necessary.

Execute the agent's SQL exactly as approved, with `Statement` and `setEscapeProcessing(false)`. Do not append LIMIT, wrap queries, or deparse/reformat the statement. `PreparedStatement` is inappropriate for arbitrary submitted PostgreSQL SQL because its `?` placeholder handling conflicts with JSONB operators; use bound parameters for the connector's own introspection queries.

Rejected approaches included Spring JDBC wrappers that obscure required control, SQL rewriting, a parser as the only security layer, heavier grammar stacks without a demonstrated need, and a fresh Vault credential for every logical call.

## 2. Evaluated dependencies

| Dependency | Version | Reason / constraint |
| --- | --- | --- |
| `org.postgresql:postgresql` | 42.7.14 | Boot-managed JDBC driver; tested on Java 25. |
| `com.mysql:mysql-connector-j` | 9.7.0 | Boot-managed; tested with MySQL 8.4.10 and MariaDB 11.4.5. Exclude protobuf when only classic JDBC is used. |
| `com.github.jsqlparser:jsqlparser` | 5.4 | Explicit pin; no runtime dependencies; Apache-2.0 or LGPL-2.1 licensing. |
| `com.zaxxer:HikariCP` | 7.0.2 | Boot-managed connection pool. |
| MariaDB JDBC driver | 3.5.10 considered | Alternative for MariaDB-specific authentication or distribution requirements; not selected in the original decision. |
| Embedded PostgreSQL | 2.2.2 considered | Optional non-root test setup. |
| MariaDB4j binary artifact | 11.4.5 | Recorded test-server source; wrapper not required. |

Connector/J 26.7.0 and 26.10.0 had a newer version scheme and documented MySQL 8.4+ support, while 9.7.0 documented 8.0+. Actual compatibility with MySQL 8.0/9.x and MariaDB 10.6 was not established by the recorded experiments. Connector/J lacks MariaDB ed25519/PARSEC plugins. Preserve its GPLv2 plus Universal FOSS Exception notice and review distribution obligations before selecting a product license; this report does not determine legal compatibility.

## 3. Tool contracts

| Tool | Behavior | Default mode |
| --- | --- | --- |
| `sql_list_schemas` | Visible schemas/tables, filtered by policy; estimates are labeled as estimates. | Read only |
| `sql_describe_table` | Columns, keys, indexes, references, checks, and comments for one permitted table. | Read only |
| `sql_query` | One analyzed read statement in a READ ONLY transaction. | Read only |
| `sql_execute` | Approved DML or permitted DDL; never a read-only mode. | Approval |

The core supplies each endpoint's eligible `connection` enum. Advertise final output schemas only for tools whose results satisfy them, following the approval-result rules in report 01.

### Query output and limits

A query returns column names/types, typed rows, row count, truncation information, elapsed time, and masked fields. Research defaults were 50 requested rows, capped by 1,000 in dev or 100 in prod. Use `setMaxRows(limit + 1)` to detect truncation, discard the extra row, cancel promptly, and enforce a response-byte budget while reading. Bound individual cells (research proposal: 8 KiB) and binary previews (256 bytes). Do not claim cursor pagination: the original SQL proposal returns no cursor and asks the agent to narrow/filter/page its SQL explicitly.

Represent decimals and integers outside JavaScript's exact range as strings. Preserve booleans, ISO dates/times, arrays, UUIDs, and non-finite numbers deliberately. JSON/JSONB can remain text. Supply column provenance for masking: PostgreSQL base-schema/table/column metadata and MySQL catalog/table/column metadata resolve aliases, but computed expressions may lose provenance and need pattern-based redaction.

### Mutation execution

`sql_execute` requires an approved context in addition to the core's approval check. PostgreSQL may execute up to twenty statements in one transaction, preserving the original SQL string and collecting each update count/result. Roll back on any failure. MySQL accepts one statement because `allowMultiQueries=false`; DDL can implicitly commit and must be described as such.

Do not retry an uncertain commit. A lost connection during COMMIT returns “Outcome unknown; check the data before retrying.” Pure reads submitted to `sql_execute` are rejected with guidance to use `sql_query`.

### Classification

Bound input length to 100,000 characters, nesting depth to 64, and parser time. Use a shared executor rather than creating a parser thread pool per call. Parse with dialect-aware backslash behavior, but assume parser/server lexer differences are possible.

| Construct | Classification / rule |
| --- | --- |
| SELECT, VALUES, TABLE, supported SHOW/DESCRIBE | READ if all nested expressions are safe. |
| EXPLAIN without execution | READ, subject to safe planning constraints. |
| EXPLAIN ANALYZE over DML | MUTATION: it executes the statement. |
| Data-changing CTE | MUTATION even when the outer query is SELECT. |
| SELECT FOR UPDATE/SHARE, including nested forms | MUTATION because it acquires locks. |
| PostgreSQL SELECT INTO | DDL; deny where DDL is prohibited. |
| MySQL INTO OUTFILE/DUMPFILE | Always forbidden. |
| INSERT/UPDATE/DELETE/MERGE/REPLACE/CALL | Mutation; an unknown target cannot satisfy a configured allowlist. |
| CREATE/ALTER/DROP/TRUNCATE/GRANT and related schema changes | DDL; deny on stage/prod under the proposed defaults. |
| Transaction/session control, prepared execution, file/program operations, database/server administration | Forbidden; the gateway owns the session and transaction. |
| Dangerous function anywhere in the AST | Forbidden. |
| Multiple statements in a read tool | Forbidden. |
| Parser failure or `UnsupportedStatement` | Fail closed for reads. The original research proposed explicitly warned approval for unparsed dev mutations only when no DDL prohibition or object allowlist requires analysis; current policy must decide whether to allow that path. |

Explicitly handle parsed transaction classes such as `RollbackStatement`, `SavepointStatement`, `AlterSystemStatement`, and prepared `EXECUTE`; a generic DDL fallback would accidentally make them approvable. `UnsupportedStatement` can be returned without throwing. `SHOW TABLES` and `SHOW COLUMNS` use separate parser classes. Collect real tables with `TablesNamesFinder.getTables`, not a raw `visit(Table)` override that also sees CTE aliases. Handle unsupported visitor operations as analysis failure. Table-valued functions need function checks, not just table checks.

## 4. Approval presentation

The exact SQL text is the approval payload; do not normalize it. Show connection/environment, targets, statement count, effective timeout, transaction behavior, and analysis limitations. Render invisible characters and confusables visibly. The agent's note is explicitly unverified.

Impact estimation is best effort and must not execute the mutation. PostgreSQL permits EXPLAIN of a write inside READ ONLY; the row estimate may be below the top `ModifyTable` plan node. MySQL READ ONLY rejects EXPLAIN UPDATE, so any proposed normal-transaction planning probe must roll back and exclude statements whose functions could run while planning. Never interpret an estimate as a guaranteed affected-row count.

Bind approval to the canonical tool, connection, and exact SQL digest. A production comment is enforced by the core, not a connector UI convention.

## 5. Connection and policy settings

Use structured host, port, database, TLS mode, optional CA, login source, timeouts, pool size, default schema, and schema/table allowlists. Build JDBC URLs from validated fields and a fixed property allowlist; never accept arbitrary JDBC parameters. Secrets come from the core's resolver and are write-only in the UI/API.

PostgreSQL defaults to port 5432, database required, `verify-full`, and default schema `public`. MySQL defaults to 3306; database can be optional. Map TLS modes explicitly to driver values. Public-key retrieval defaults off and requires a warning because it weakens password protection without verified TLS.

Research policy defaults:

| Setting | Dev | Stage / prod | Merge rule |
| --- | --- | --- | --- |
| READ ONLY for reads | Always | Always | Cannot be disabled |
| Statement timeout | 5 seconds | Prod 3 seconds | Minimum |
| Row limit | 1,000 | Prod 100 | Minimum |
| Deny DDL | False; approval still required | True | Logical OR |
| Allowed objects | Explicit configured set | More restricted set | Intersection |
| Denied functions | Built-in plus configured | Inherited/additional | Union |
| Response size | 512 KiB | Policy may reduce | Minimum |

Normalize PostgreSQL unquoted names to lowercase, preserve quoted names, and set a matching transaction-local `search_path`. Normalize MySQL names according to `lower_case_table_names`. Reject `USE` and unapproved system catalogs. An allowed view can expose underlying data; allowlists supplement database grants, not replace them.

## 6. Database enforcement and resource safety

### READ ONLY and rollback

Recorded PostgreSQL experiments rejected writes inside functions, nextval, large-object mutation, data-changing CTEs, SELECT INTO, row locks, and temporary-table creation in READ ONLY. MySQL rejected writes and row locks too. But PostgreSQL administrative/file functions and advisory locks, and MySQL GET_LOCK/user-variable behavior, can still have effects. `dblink` can open a different connection outside the current read-only transaction.

Always roll reads back. This resets transactional settings and prevents notifications or other transaction-bound effects from escaping, but it does not undo every nontransactional action. Maintain a built-in, extensible denylist covering:

- PostgreSQL backend cancellation/termination, reload/promotion/backup/replication controls, advisory locks, notification/config changes, server-file access, large objects, dblink, XML helpers that hide queries, and reset/admin functions.
- MySQL/MariaDB lock functions, file access, benchmark/server execution UDFs, and blocking replication/GTID waits.

Keep historical aliases where older supported servers use them. Ordinary sleep functions can be bounded by the server deadline rather than treated as harmless without limits.

### Driver and server controls

Set read-only before the transaction's first statement. PostgreSQL uses transaction-local statement/lock/idle timeouts and search path; verify `transaction_read_only`. MySQL uses `max_execution_time` for SELECT, `innodb_lock_wait_timeout` for relevant writes, and a client query timeout. MariaDB uses `max_statement_time` in seconds, not MySQL's variable. Driver socket timeouts are a final bound, not a substitute for cancellation.

On PostgreSQL use fetch sizing with autocommit off. Connector/J may implement row caps through `sql_select_limit`, which an explicit SQL LIMIT can override; large-result memory behavior and cursor-fetch alternatives require further measurement. Cancel after the allowed extra row rather than reading an unbounded result into memory.

Disable multi-query and local-file import properties on MySQL. Do not expose pgjdbc socket factories, SSL factories, logging paths, or arbitrary plugin parameters. For pgjdbc 42.7.14, `sslrootcert=system` is not supported: it is interpreted as a filename. Use the fixed `DefaultJavaSSLFactory` for JVM trust, or a controlled PEM path with the appropriate driver factory. Full TLS handshakes for both paths were still unverified in the original research.

### Dynamic credentials

A resolved login includes username/password, version, and expiry. Use a credential-aware DataSource when opening each physical connection; refresh before expiry and softly evict pooled connections when the credential generation changes. One lease can serve a pool generation.

Clamp Hikari lifetimes explicitly. In 7.0.2, a value below thirty seconds is replaced with the thirty-minute default, not rounded up to thirty seconds. An idle timeout near maxLifetime may be disabled. Never allow these defaults to extend a database session beyond the intended credential lifetime. The original Vault lease-revocation experiment remained unverified.

## 7. Test connection and error handling

Connect directly through the driver, not the pool, so the UI receives the actual connection error instead of a pool-acquisition timeout. Within a bounded budget, inspect version, TLS, latency, read replica status, visible objects, effective privileges, and allowlist existence.

PostgreSQL permission checks should identify superuser, BYPASSRLS, file/program capabilities, write privileges, and replica state. Check version-dependent predefined roles safely. MySQL roles may require `SHOW GRANTS ... USING ...` to expose granted role privileges; nested-role handling was unresolved. MariaDB lacks `@@super_read_only`, so do not query it blindly.

Distinguish name resolution, refused port, timeout, credentials, missing database, and TLS errors. Mask server messages because errors can contain data. Preserve useful SQLState, position, and hint information only after sanitization. Never put passwords in URLs, pool names, configuration strings, or logs.

## 8. Verification strategy

Use a classifier corpus covering nested locks, DML CTEs, unparsed statements, lexer discrepancies, Unicode/quoted identifiers, functions in FROM, and database/session commands. Verify the submitted and executed SQL strings remain identical.

Run real PostgreSQL, MySQL, and MariaDB servers with read-only, write-capable, and administrative accounts. Assert server-side rejection, rollback after reads, cancellation/deadlines, row/byte caps, column provenance, privilege diagnostics, and commit-uncertainty handling. Tests must distinguish parser rejection from database enforcement.

Recorded tests inspected driver traffic/general logs, exercised PostgreSQL 16.15 and MySQL 8.4.10/MariaDB 11.4.5, and verified Hikari eviction using a changing DataSource. Do not promote those observations into claims about all advertised server versions. Rootless PostgreSQL launch, huge BLOB memory usage, full TLS, nested MySQL roles, real Vault expiry, and performance budgets remained additional work.

## 9. Evidence and open decisions

The research identified design inconsistencies to resolve in the application contract: `limit` versus `max_rows`, default row caps, one effective SQL timeout, PostgreSQL multi-statement support, and accurate Vault wording. Read-only unparsed SQL was intentionally not proposed as a shortcut. Query resources can reuse the same guarded introspection path.

- [PostgreSQL JDBC source](https://github.com/pgjdbc/pgjdbc) and [42.7.14 SSL implementation](https://raw.githubusercontent.com/pgjdbc/pgjdbc/REL42.7.14/pgjdbc/src/main/java/org/postgresql/ssl/LibPQFactory.java)
- [JSqlParser artifact metadata](https://repo1.maven.org/maven2/com/github/jsqlparser/jsqlparser/maven-metadata.xml)
- [HikariCP 7.0.2 configuration validation](https://raw.githubusercontent.com/brettwooldridge/HikariCP/HikariCP-7.0.2/src/main/java/com/zaxxer/hikari/HikariConfig.java)
- [Connector/J 9.7.0 release notes](https://dev.mysql.com/doc/relnotes/connector-j/en/news-9-7-0.html), [26.7.0](https://dev.mysql.com/doc/relnotes/connector-j/en/news-26-7-0.html), and [26.10.0](https://dev.mysql.com/doc/relnotes/connector-j/en/news-26-10-0.html)
- [MariaDB system variables](https://mariadb.com/kb/en/server-system-variables/)
- [PostgreSQL 14 pg_class](https://www.postgresql.org/docs/14/catalog-pg-class.html) and [13 pg_class](https://www.postgresql.org/docs/13/catalog-pg-class.html), relevant to unknown row estimates
- [PostgreSQL 15 release notes](https://www.postgresql.org/docs/release/15.0/), relevant to renamed backup functions
- [Spring Boot 4.1.2 dependency BOM](https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.2/spring-boot-dependencies-4.1.2.pom)
