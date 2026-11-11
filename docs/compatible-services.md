# Compatible services

Many managed and alternative databases speak the protocol of a database GateRook already supports. **Add connection**
shows them as cards of their own, so you can pick **Supabase** or **Valkey** directly. The card opens the base
connector's form with the service's starting values: TLS settings, a default port, or the functions agents may call.
The connection you save is an ordinary connection of the base type, with the same MCP Firewall controls: bounded
reads, policies, approvals for changes, and the activity record.

A compatible service implements its protocol to a different degree. GateRook still classifies every statement itself
and runs reads the way the base connector does, but a service may refuse a command, a catalog query or a function that
the original database accepts. **Test connection** reports what the service answered.

## PostgreSQL protocol

Through the PostgreSQL connector:

| Service | Notes |
|---|---|
| Supabase | Use the direct connection or the session pooler, and paste Supabase's CA certificate for **verify-full**. |
| Neon | Use the direct host: the pooled host runs PgBouncer in transaction mode. |
| Amazon Aurora and RDS for PostgreSQL | A database user and password; IAM database authentication is not supported. Paste the Amazon RDS CA bundle for **verify-full**. |
| Cloud SQL for PostgreSQL, AlloyDB for PostgreSQL | A database user and password, through the instance's IP and its CA certificate, or the Cloud SQL or AlloyDB Auth Proxy on this computer. IAM authentication is not supported. |
| Azure Database for PostgreSQL | Flexible server with a database user and password over **verify-full**. Microsoft Entra authentication is not supported. |
| TimescaleDB | Hypertables read as tables and continuous aggregates as views. |
| PostGIS | The card lists common read-only spatial functions under **Advanced › Allowed functions**; any other function agents call waits for approval. |
| [CockroachDB](cockroachdb.md) | Port 26257 with the cluster's CA certificate. CockroachDB implements only part of PostgreSQL's catalogs and functions. |
| YugabyteDB | The YSQL API, on port 5433 by default. |
| EDB Postgres, Greenplum | EDB Postgres Advanced Server and EDB Postgres AI; Greenplum 7 builds on PostgreSQL 12. |

Connection poolers in transaction mode (Supavisor's port 6543, PgBouncer) have not been verified: GateRook's schema
queries use bound parameters. Prefer a direct connection or a session-mode pooler.

## MySQL protocol

Through the MySQL connector: MariaDB; Amazon Aurora and RDS for MySQL; Cloud SQL for MySQL; Azure Database for MySQL;
PlanetScale and Vitess; TiDB (port 4000); SingleStore; Percona Server for MySQL; OceanBase in MySQL mode (port 2881);
PolarDB for MySQL; StarRocks and Apache Doris through the frontend's MySQL port (9030). Read-only transactions differ
between these engines: check what **Test connection** reports for the user before relying on it.

## SQL Server and Oracle

Through the SQL Server connector: Azure SQL Database, Azure SQL Managed Instance and Amazon RDS for SQL Server, with SQL
authentication. Through the [Oracle](oracle.md) connector: Amazon RDS for Oracle, and Oracle Autonomous Database over
TLS without a wallet (allow TLS connections without mutual TLS on the database first).

## MongoDB protocol

Through the MongoDB connector: MongoDB Atlas (its `mongodb+srv://` connection string), Amazon DocumentDB (TLS with the
Amazon RDS CA bundle), Azure Cosmos DB for MongoDB (RU and vCore), FerretDB, Percona Server for MongoDB, and Firestore
with MongoDB compatibility. DocumentDB and Cosmos DB support only part of MongoDB's aggregation stages and operators.

## Redis protocol (RESP)

Through the [Redis](redis.md) connector: Valkey; Amazon ElastiCache and MemoryDB; Memorystore for Redis and Valkey;
Azure Cache for Redis and Azure Managed Redis; Upstash; Dragonfly; KeyDB; and Microsoft Garnet. Managed caches sign in
with an AUTH token, an access key or an ACL user: IAM and Microsoft Entra authentication are not supported. Garnet
implements part of Redis's commands.

## Cassandra protocol (CQL)

Through the [Apache Cassandra](cassandra.md) connector: ScyllaDB, Amazon Keyspaces (TLS on port 9142 with
service-specific credentials) and Azure Managed Instance for Apache Cassandra.

## Prometheus API

Through the [Prometheus](prometheus.md) connector: Thanos Query, Grafana Mimir and Cortex (set the tenant when a
cluster serves several), VictoriaMetrics, and Grafana Cloud Metrics with basic sign-in.

## Next to their base type

Starburst (Trino), Azure Databricks (Databricks SQL), Amazon OpenSearch Service (OpenSearch), Elastic Cloud
(Elasticsearch), Neo4j AuraDB (Neo4j), InfluxDB Cloud (InfluxDB), Grafana Cloud Logs (Loki), and Amazon EKS, Google
Kubernetes Engine, Azure Kubernetes Service, Red Hat OpenShift and k3s (Kubernetes, from your kubeconfig; a cloud's
sign-in command runs only once you trust it in **Test connection**).

## Plugins from the catalog

A plugin that the signed catalog offers but that isn't installed yet, such as [DuckDB](duckdb.md) or ClickHouse,
appears in **Add connection** too. Choosing it and **Continue** shows the plugin's review (its signature, package and
tools; an unsigned plugin needs your explicit acknowledgement) and installs it, then opens its form.
