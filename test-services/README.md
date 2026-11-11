# Connector test services

Docker Compose supplies disposable servers for connector integration tests: PostgreSQL, MySQL, MariaDB, Oracle Database, SQL Server, CockroachDB, Trino, the Azure Cosmos DB emulator, MongoDB, Neo4j, Redis, Loki, OpenSearch, Elasticsearch, moto, Prometheus, InfluxDB, Vault, OpenSSH, and k3s. This avoids relying on Linux x86-64 fixture binaries when testing on macOS or another Docker-capable machine.

Services bind to `127.0.0.1`. Credentials are public test fixtures, and service data is disposable. Tests reset databases, users, and server settings; never point this suite at real data.

## Run

From the repository root:

```bash
scripts/test-services.sh up
scripts/test-services.sh run mvn -B -ntp verify -Pservices
scripts/test-services.sh down
```

The default starts everything except Kubernetes, SQL Server, CockroachDB, Trino, the Cosmos DB emulator, Neo4j, Prometheus and InfluxDB and waits for health checks. Profiles are `sql`, `oracle`, `sqlserver`, `cockroach`, `trino`, `cosmosdb`, `mongo`, `neo4j`, `redis`, `logs`, `aws`, `dynamodb`, `prometheus`, `influxdb`, `vault`, `ssh`, `k8s`, and `all`:

```bash
scripts/test-services.sh up sql redis
scripts/test-services.sh run mvn -B -ntp -pl connectors/sql,connectors/redis -am verify -Pservices
```

Oracle is part of `sql` and can also run on its own:

```bash
scripts/test-services.sh up oracle
scripts/test-services.sh run mvn -B -ntp -pl connectors/sql -am verify -Pservices \
  -Dit.test=OracleIT -Dfailsafe.failIfNoSpecifiedTests=false
```

The Oracle health check connects over TCP as the test user and runs a query, so readiness includes the listener,
service registration and initialized credentials. Its first startup takes longer than the smaller SQL fixtures.

Direct Compose use is also possible: `docker compose -f test-services/docker-compose.yml --profile sql up -d --wait`.

`-Pservices` enables the relevant integration profiles and passes `gateway.it.services`. For IDE runs, set `GW_TEST_SERVICES` to the absolute path of this directory. Without either setting, tests use their own fixture processes; `ElasticsearchIT` requires Compose and is skipped. Oracle requires an explicit external test service as described in [Oracle Database](../docs/oracle.md#integration-tests). The wrapper's `run` command serializes access to shared services through a temporary-directory lock; concurrent unwrapped runs would reset each other's data. Remove a stale lock only after verifying its previous process has stopped.

## Services

The committed [.env](.env) contains image pins, ports, and disposable passwords. Both Compose and the test fixtures read it.

| Service | Image / behavior | Loopback port | Profile |
| --- | --- | --- | --- |
| `postgres` | PostgreSQL 16.13 with test TLS | 55432 | sql, vault |
| `mysql`, `mariadb` | MySQL 8.4.10 / MariaDB 11.4.5 | 55306 / 55307 | sql |
| `oracle` | [gvenzl/oracle-free](https://github.com/gvenzl/oci-oracle-free) 23.26.3-slim, service `FREEPDB1` | 55521 | sql, oracle |
| `sqlserver` | SQL Server 2022 CU23 Developer, x86-64 image | 55433 | sqlserver |
| `cockroach` | CockroachDB v26.3.5, one in-memory node with TLS | 55257 | cockroach |
| `trino` | Trino 484 with the `memory` and `tpch` catalogs, no authentication | 55808 | trino |
| `cosmosdb` | Azure Cosmos DB Linux emulator vNext (EN20261019, pinned by digest), plain HTTP | 55081 | cosmosdb |
| `mongo`, `mongo-open`, `mongo-tls` | MongoDB 8.2.4 with auth / no auth / required TLS | 55017 / 55018 / 55019 | mongo |
| `mongo7`, `mongo9` | MongoDB 7.0.45 / 9.0.4 | 55070 / 55090 | mongo |
| `neo4j` | Neo4j 5.26.31 Community, Bolt with optional TLS on one port | 55687 | neo4j |
| `redis` | Redis 7.0.15, plain and TLS listeners | 55379 / 55380 | redis |
| `redis-replica`, `redis-open`, `redis-cluster` | Replica, no-auth, and cluster fixtures | 55381 / 55382 / 55383 | redis |
| `redis62` | Redis 6.2.11, plain and TLS | 55362 / 55363 | redis |
| `loki` | Loki 3.7.9 with tenants | 55100 | logs |
| `opensearch` | OpenSearch 3.9.0 without security plugin | 55200 | logs |
| `elasticsearch` | Elasticsearch 9.5.5 with security disabled | 55201 | logs |
| `moto` | moto 5.2.4 AWS emulator (STS, CloudWatch, DynamoDB) | 55500 | logs, aws, dynamodb |
| `prometheus` | Prometheus v3.15.1 scraping itself, with a down target, a dropped target and rules | 55909 | prometheus |
| `influxdb3` | InfluxDB 3 Core 3.12.1 in memory, admin token from `.env` | 55086 | influxdb |
| `influxdb2` | InfluxDB 2.9.1 set up with organization `acme` and an operator token | 55087 | influxdb |
| `vault` | Vault 2.2.1 dev mode | 55820 | vault, ssh |
| `openssh` | Ubuntu 24.04 / OpenSSH 9.6p1 | 55022 | ssh |
| `k3s` | k3s v1.37.0-k3s1, privileged | 55443 | k8s |
| `certs` | One-shot Alpine certificate generation | None | sql, mongo, redis, vault |
| `neo4j-certs` | The same generation into `run/neo4j-certs/` | None | neo4j |

PostgreSQL uses `postgres` / `postgres-pw-0001`; tests create the `app` database and `ro`, `rw`, `signal`, and `rotator` roles with `<role>-pw-0001`. MySQL/MariaDB use a passwordless fixture root account; tests create their database and users. Oracle uses the scoped `GATEROOK_TEST` / `oracle-pw-0001` account in `FREEPDB1`; the container creates it with session/table privileges and a limited tablespace quota. MongoDB uses `root` / `root-pw-7a1c2e9b`, except the no-auth fixture. Neo4j uses `neo4j` / `neo4j-pw-0001`. Redis ACL users use `<user>-pass` as specified in `redis/users*.acl`. Vault's fixture token is `cnry-root-compose-0001`.

SSH test keys are generated under `run/openssh/`; connect with `ssh -i test-services/run/openssh/user_ed25519 -p 55022 ubuntu@127.0.0.1`. Kubernetes configuration is `test-services/run/k3s/kubeconfig.yaml`.

The ignored `run/` directory holds generated certificates, SSH keys/logs, and kubeconfig. These files must never be committed. Regenerate them by stopping services, removing only this disposable output, and starting again.

## Fixture isolation and platform differences

SQL fixtures recreate their test database and roles. Oracle recreates only its named test tables in the dedicated test user's schema; it never resets other schemas. Oracle data lives in the container's writable layer, survives a restart, and is removed by `down`. MongoDB removes non-system databases/users and resets fail points. Neo4j deletes every node of `neo4j` and recreates its test index and constraint. Redis reloads ACLs, clears statistics/logs and data, and restores changed passwords. Loki uses per-run tenants; Prometheus is only read; OpenSearch recreates test indexes; Elasticsearch creates and deletes uniquely named test indexes; moto resets its emulator state. Vault recreates engines and roles, revoking database leases. SSH tests reset logs and inspect child processes. Kubernetes tests recreate a dedicated namespace and workload.

The Compose MongoDB 8.x fixture uses 8.2.4 because the original investigation found a Linux-kernel compatibility problem in 8.0.x; see [SERVER-121912](https://jira.mongodb.org/browse/SERVER-121912). Redis glob matching differs for high bytes between signed-`char` and unsigned-`char` platforms; tests probe the server's behavior rather than assuming the client architecture.

The MongoDB alternate-loopback hostname test is skipped in this mode because it needs `127.0.0.2`, which is not normally provided by Docker port forwarding or macOS. `KubeApiServerIT` remains a separate envtest fixture for Linux amd64; `K3sKubeletIT` uses the Compose k3s service with a real kubelet.

Use Docker Engine or Docker Desktop with Compose v2. Kubernetes requires a privileged container. Oracle uses a container memory limit of 2 GiB; its startup wrapper sets a 768 MiB SGA and a 256 MiB PGA target before the first database startup. The Docker VM also needs memory for the other running services. Run the `oracle` profile alone when the Docker VM cannot accommodate the full suite; actual requirements depend on images and other running containers. OpenSearch and Elasticsearch each use a 512 MB heap. The Elasticsearch image is pinned to the multi-architecture release in [Elastic's official registry](https://www.docker.elastic.co/r/elasticsearch/elasticsearch).

## SQL Server

SQL Server is opt-in because its image requires x86-64 and at least 2 GB of available memory in addition to
other running services. The container has a 2.5 GB ceiling and the SQL Server process a 2 GB memory limit. On ARM Docker Desktop,
Linux/amd64 emulation must be available; Microsoft supports these container images on x86-64 Linux hosts.
The fixture uses SQL authentication, a disposable `sa` password from `.env`, and a self-signed TLS certificate.
The tests create only their dedicated `gaterook_sqlserver_*_it` databases and login.

```bash
scripts/test-services.sh up sqlserver
scripts/test-services.sh run mvn -B -ntp -pl connectors/sql -am verify -Pservices,sqlserver-it -Dit.test=SqlServerIT -Dfailsafe.failIfNoSpecifiedTests=false
```

The ordinary `services` Maven profile does not enable SQL Server tests. See the [connector guide](../docs/sql-server.md)
for supported authentication and T-SQL behavior, and Microsoft's [container deployment guide](https://learn.microsoft.com/en-us/sql/linux/sql-server-linux-docker-container-deployment?view=sql-server-ver17)
for platform requirements.

## CockroachDB

CockroachDB is opt-in: one node keeps its data in 1 GiB of memory, within a 2 GiB container limit. The image makes
its own CA and node certificate (for `127.0.0.1`) in `run/cockroach-certs/` and the admin user `gaterook` with the
password from `.env`; the test adds a reader and its database `gaterook_crdb_it`. The PostgreSQL connector verifies the
node with that CA.

```bash
scripts/test-services.sh up cockroach
scripts/test-services.sh run mvn -B -ntp -pl connectors/sql -am verify -Pservices,cockroach-it -Dit.test=CockroachIT -Dfailsafe.failIfNoSpecifiedTests=false
```

The ordinary `services` Maven profile does not enable CockroachDB tests. See [CockroachDB](../docs/cockroachdb.md).

## Prometheus

Prometheus is opt-in: one server keeps a day of its own metrics in memory, within a 256 MiB container limit. Its
configuration in `prometheus/` scrapes the server itself every 5 seconds, keeps a target that is down and one that
relabeling drops, and evaluates a recording rule and an alert that always fires. It needs no credentials, and no admin,
lifecycle or remote write endpoint is enabled.

```bash
scripts/test-services.sh up prometheus
scripts/test-services.sh run mvn -B -ntp -pl connectors/prometheus -am verify -Pservices,prometheus-it -Dit.test=PrometheusIT -Dfailsafe.failIfNoSpecifiedTests=false
```

The ordinary `services` Maven profile does not enable Prometheus tests. See [Prometheus](../docs/prometheus.md).

## InfluxDB

InfluxDB is opt-in: two small servers, each with a 512 MiB container limit. `influxdb3` runs InfluxDB 3 Core with an
in-memory object store; its admin token is the fixture value `GW_INFLUXDB3_TOKEN` of `.env`, which the container writes
to a token file at start-up and loads with `--admin-token-file`, so no setup call or network access is needed. Only
`/health` answers without the token, and telemetry upload and package management are off. `influxdb2` runs InfluxDB
2.9 set up on every start with the user `gaterook`, the organization `acme`, the bucket `metrics` and the operator token
`GW_INFLUXDB2_TOKEN`; its data lives in tmpfs. The test creates its own databases, a bucket and a token that may only
read it, and removes them afterwards.

```bash
scripts/test-services.sh up influxdb
scripts/test-services.sh run mvn -B -ntp -pl connectors/influxdb -am verify -Pservices,influxdb-it -Dit.test=InfluxDbIT -Dfailsafe.failIfNoSpecifiedTests=false
```

The ordinary `services` Maven profile does not enable InfluxDB tests. See [InfluxDB](../docs/influxdb.md).

## Trino

Trino is opt-in: one coordinator that is also its own worker, with a 768 MiB heap ([trino/jvm.config](trino/jvm.config))
within a 1,280 MiB container limit. [trino/catalog](trino/catalog) replaces the image's catalogs with the built-in
`memory` catalog (64 MB per node) and `tpch`, so nothing reaches another system, and the container loads only these
two plugins. [trino/config.properties](trino/config.properties) keeps query memory, threads and the query history
small; repeated test runs peak near 1.05 GiB. The coordinator has no authentication: the test names its user on
loopback. Tables it creates in `memory` disappear with the container.

```bash
scripts/test-services.sh up trino
scripts/test-services.sh run mvn -B -ntp -pl connectors/trino -am verify -Pservices,trino-it -Dit.test=TrinoIT -Dfailsafe.failIfNoSpecifiedTests=false
```

The ordinary `services` Maven profile does not enable Trino tests. See [Trino and Presto](../docs/trino.md).

## Azure Cosmos DB

The Azure Cosmos DB emulator is opt-in: the Linux emulator vNext runs the API for NoSQL in gateway mode on x86-64 and
ARM64, with PostgreSQL inside, in about 700 MB within a 2 GiB container limit. It listens on plain HTTP; its data lives
in the container and is removed with it. It enforces no authentication: the test signs with Microsoft's published,
well-known emulator key from `.env`, and the connector's signatures are checked against a fake instead. The test makes
and drops its own database `gaterook_cosmos_it`.

```bash
scripts/test-services.sh up cosmosdb
scripts/test-services.sh run mvn -B -ntp -pl connectors/cosmosdb -am verify -Pservices,cosmosdb-it -Dit.test=CosmosEmulatorIT -Dfailsafe.failIfNoSpecifiedTests=false
```

The ordinary `services` Maven profile does not enable Cosmos DB tests. See [Azure Cosmos DB](../docs/cosmosdb.md) for what
the emulator does differently from an Azure account.

## Neo4j

Neo4j is opt-in: one Community Edition server with a 512 MB heap and a 128 MB page cache, within a 1 GiB container
limit, without plugins and without usage reports. Bolt listens on 55687 for plain and TLS connections alike
(`server.bolt.tls_level=OPTIONAL`), with a certificate for `localhost` and `127.0.0.1` signed by the test CA. The
one-shot `neo4j-certs` makes them in `run/neo4j-certs/` with the same script as `certs`, apart from the other
profiles' files. The routing table names `127.0.0.1:55687`, so `neo4j://` works through Docker's port mapping. The IT
signs in as `neo4j` with the password from `.env`.

```bash
scripts/test-services.sh up neo4j
scripts/test-services.sh run mvn -B -ntp -pl connectors/neo4j -am verify -Pservices,neo4j-it -Dit.test=Neo4jIT -Dfailsafe.failIfNoSpecifiedTests=false
```

The ordinary `services` Maven profile does not enable Neo4j tests. See [Neo4j](../docs/neo4j.md).
