# Control AI access to Google BigQuery

GateRook's MCP Firewall lets AI apps explore BigQuery datasets and run GoogleSQL reads within a cost limit per statement, and holds every change for your review. BigQuery's own dry run decides what a statement is before anything runs. The [security model](security.md) explains execution controls, explicit Bypass, and the append-only, tamper-evident audit trail.

## Connect a project

The built-in **Google BigQuery** connector (`bigquery`) calls BigQuery's REST API v2 at `bigquery.googleapis.com`. There is no BigQuery client library in GateRook; requests go only to Google's APIs, and Google tokens never go anywhere else. Choose:

- the **project** that runs, and pays for, the queries, and optionally the **location** of the jobs (`US`, `EU`, `europe-west1`);
- the **credentials**: your Google sign-in on this computer (Application Default Credentials, from `gcloud auth application-default login`) or a service account key, stored encrypted in GateRook. Either can **impersonate** a service account; outside dev, **Test connection** warns when calls use your own sign-in;
- the **datasets** agents may use: `dataset` in the project, or `project.dataset`, with `*` matching any characters. Empty, every dataset the credentials may read.

Give readers `roles/bigquery.dataViewer` on the datasets and `roles/bigquery.jobUser` on the project. BigQuery has no read-only session: the credentials' roles stay part of the boundary, so a reader should not hold `dataEditor`. Test connection lists the datasets agents may use and runs a dry run of `SELECT 1` to check that the credentials can run jobs.

## Bound reads and review exact changes

| Tool | What it does | Default mode |
| --- | --- | --- |
| `bigquery_list_datasets` | Datasets agents may use in the project | Read only |
| `bigquery_list_tables` | Tables, views and their kinds in one dataset | Read only |
| `bigquery_describe_table` | Columns with nested fields, partitioning, clustering, row count, size, and a view's query | Read only |
| `bigquery_query` | One GoogleSQL `SELECT`, with named parameters | Read only |
| `bigquery_execute` | One DML or DDL statement | Approval |

Before a statement runs, BigQuery dry-runs it: free, nothing runs, and BigQuery names the statement type, the tables it reads and the bytes it would process. `bigquery_query` runs only what BigQuery calls a `SELECT`, of tables in the connection's datasets, within **Data per statement** under **Advanced** (10 GiB by default), and runs it with that limit as BigQuery's `maximumBytesBilled`, so BigQuery itself stops a query that would bill more. Tables read through a view count as the tables BigQuery reports.

Name tables as `dataset.table` or `project.dataset.table`, and pass values as named parameters (`@since`) of a scalar type: `STRING`, `INT64`, `FLOAT64`, `NUMERIC`, `BIGNUMERIC`, `BOOL`, `DATE`, `DATETIME`, `TIME` or `TIMESTAMP`. Answers keep BigQuery's types: an `INT64` is a JSON number only where JavaScript keeps it exact, `NUMERIC` stays text, a `TIMESTAMP` is ISO 8601 in UTC, `BYTES` show their first 256 bytes in hex, records are objects and repeated fields arrays. A large result returns `nextCursor`, which reads the next rows of the same job without running the query again. A query that outlives its call is cancelled.

GateRook first reads each statement by GoogleSQL's tokens: strings with their escapes, triple quotes, raw and bytes prefixes, backtick names, and `#`, `--` and block comments. It refuses what it can't read with certainty (an open quote, a backslash in a raw string or a quoted name), more than one statement, scripting and transactions, `EXPORT DATA`, `LOAD DATA`, `CALL`, `GRANT` and `REVOKE`, row access policies, reservations and project options, and functions that call another service or database from BigQuery (`EXTERNAL_QUERY`, `ML.GENERATE_TEXT`, `AI.GENERATE` and their kin), as the byte limit doesn't cover what those cost or change.

`bigquery_execute` runs one `INSERT`, `UPDATE`, `DELETE`, `MERGE`, `TRUNCATE`, `CREATE`, `ALTER`, `DROP` or `UNDROP`. While the connection lists datasets, the statement must name its table as `dataset.table` in one of them. The approval shows the exact statement, its parameters, BigQuery's statement type, the bytes it would process and the tables it reads; a statement BigQuery refuses, a read, or one over the limit is never put to a person. What runs is the approved statement, once, with a request id derived from the approval. A change is never retried: an answer lost after the request was sent reports the outcome as unknown, and the job's id tells you where to look.

## Verify

The connector's tests run against a fake of Google's token server and BigQuery's REST API in every build; there is no BigQuery emulator. JVM checks do not establish native-image or installer compatibility, nor behaviour against BigQuery itself.

Google documents [jobs.query](https://cloud.google.com/bigquery/docs/reference/rest/v2/jobs/query), [dry runs](https://cloud.google.com/bigquery/docs/running-queries#dry-run), [maximum bytes billed](https://cloud.google.com/bigquery/docs/best-practices-costs#limit_query_costs_by_restricting_the_number_of_bytes_billed), [query parameters](https://cloud.google.com/bigquery/docs/parameterized-queries) and [BigQuery roles](https://cloud.google.com/bigquery/docs/access-control).
