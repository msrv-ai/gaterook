# Control AI access to Google Cloud Spanner

GateRook's MCP Firewall lets AI apps explore a Spanner database and run GoogleSQL or PostgreSQL-dialect reads in read-only transactions, in which Spanner itself refuses every change, and holds every change for your review. The [security model](security.md) explains execution controls, explicit Bypass, and the append-only, tamper-evident audit trail.

## Connect a database

The built-in **Google Cloud Spanner** connector (`spanner`) calls Spanner's REST API v1 at `spanner.googleapis.com`. There is no Spanner client library in GateRook; requests go only to Google's APIs, and Google tokens never go anywhere else. One connection is one database. Choose:

- the **project**, the **instance** and the **database**. GoogleSQL and PostgreSQL-dialect databases both work; GateRook learns the dialect from the database;
- the **credentials**: your Google sign-in on this computer (Application Default Credentials, from `gcloud auth application-default login`) or a service account key, stored encrypted in GateRook. Either can **impersonate** a service account; outside dev, **Test connection** warns when calls use your own sign-in;
- optionally, under **Advanced**, a **database role** of Spanner's fine-grained access control. Every session GateRook opens then takes that role (`creatorRole`), so agents get the role's privileges on tables, columns and views instead of the credentials' access to the whole database. The credentials need `roles/spanner.fineGrainedAccessUser` and access to the role.

While you fill in the form, GateRook fills in the project of a pasted key, or the one gcloud works in on this computer. Once the form can sign in, it lists the project's instances, the instance's databases with their dialects and the database's roles, and says when the instance or database typed isn't there. Listing instances and databases needs `roles/spanner.viewer`; without it, type them.

Give readers `roles/spanner.databaseReader` on the database. Approved changes need `roles/spanner.databaseUser` for DML and `roles/spanner.databaseAdmin` for schema changes; a database role narrows what any of them may touch. Test connection opens a session in the database role, reads in a read-only transaction, reports the dialect, and lists the tables agents may read.

## Bound reads and review exact changes

| Tool | What it does | Default mode |
| --- | --- | --- |
| `spanner_list_tables` | Tables and views, with their schema, kind and the parent an interleaved table belongs to | Read only |
| `spanner_describe_table` | Columns with their types, defaults and generated expressions, the primary key, indexes with their stored columns, the interleaving parent and, where the credentials may read it, the table's DDL | Read only |
| `spanner_query` | One query in the database's dialect, with parameters | Read only |
| `spanner_execute` | One DML statement or one schema change | Approval |

`spanner_query` runs one query in a single-use read-only transaction. Spanner refuses DML in that transaction, and schema changes never go through a query at all, so a statement GateRook misreads still can't change anything. GoogleSQL's pipe syntax (`FROM … |> …`) and Spanner Graph queries (`GRAPH … MATCH …`) are queries too. Pass values as parameters of a scalar type (`STRING`, `INT64`, `FLOAT64`, `NUMERIC`, `BOOL`, `DATE`, `TIMESTAMP`, `BYTES` as base64, or `JSON`): `@since` in GoogleSQL, or `$1`, `$2` named `p1`, `p2` in a PostgreSQL-dialect database. Answers keep Spanner's types, named in the database's dialect: an `INT64` is a JSON number only where JavaScript keeps it exact, `NUMERIC` stays text, `BYTES` show their first 256 bytes in hex, arrays stay arrays and structs become objects.

Results stream: GateRook stops reading once the page is full, so a large table costs only the rows it shows. The answer's `nextCursor` reads the next page at the first page's read timestamp, so pages come from one snapshot even while the data changes; add `ORDER BY` for stable pages. A cursor lasts an hour, the default time Spanner keeps old versions, and reaches at most 100,000 rows into a result: past that, narrow the query by key instead.

GateRook first reads each statement by the tokens of both dialects: strings with doubled quotes or backslash escapes, PostgreSQL's dollar quotes, backtick and double-quoted names, `--`, `#` and block comments, and statement hints. It refuses what it can't read with certainty (an open quote or comment, a nested comment), more than one statement, transactions and session settings, `CALL`, `GRANT` and `REVOKE`, roles, `ALTER DATABASE`, placements, models, and functions that call Vertex AI from Spanner (`ML.PREDICT`, `spanner.ML_PREDICT_ROW`), as what they cost and send isn't the database's.

`spanner_execute` runs one `INSERT`, `UPDATE` or `DELETE`, or one `CREATE`, `ALTER`, `DROP`, `RENAME` or `ANALYZE` schema change. The approval shows the exact statement, its parameters and the table it names. A DML statement runs in its own read-write transaction and is committed once; rows of `THEN RETURN` (GoogleSQL) or `RETURNING` (PostgreSQL) come back with the answer. A commit that Spanner aborts because another transaction changed the same data changed nothing; run it again. A schema change runs as one Spanner operation whose id derives from the approval, so the same approval can't apply it twice; GateRook follows it until the call's time is up, and a longer one (an index backfill) goes on in Spanner and is reported as still running. A change is never retried: an answer lost after the commit was sent reports the outcome as unknown.

## Verify

The connector's tests run against a fake of Google's token server and Spanner's REST API in every build. `SpannerEmulatorIT` runs against Google's Spanner emulator from the [Docker test services](../test-services/README.md) (profile `spanner`), with a GoogleSQL and a PostgreSQL-dialect database: snapshot paging while rows change, Spanner's refusal of DML in a read-only transaction, approved DML and schema changes, and dialect-specific parameters and types. The emulator doesn't enforce IAM, so these tests don't cover IAM permissions or database roles; nor do they establish behaviour against Spanner itself, native-image or installer compatibility.

Google documents [ExecuteStreamingSql](https://cloud.google.com/spanner/docs/reference/rest/v1/projects.instances.databases.sessions/executeStreamingSql), [read-only transactions](https://cloud.google.com/spanner/docs/transactions#read-only_transactions), [UpdateDatabaseDdl](https://cloud.google.com/spanner/docs/reference/rest/v1/projects.instances.databases/updateDdl), [fine-grained access control](https://cloud.google.com/spanner/docs/fgac-about) and [Spanner roles](https://cloud.google.com/spanner/docs/iam).
