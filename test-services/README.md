# Connector test services

Docker Compose supplies disposable servers for connector integration tests: PostgreSQL, MySQL, MariaDB, Oracle Database, MongoDB, Redis, Loki, OpenSearch, moto, Vault, OpenSSH, and k3s. This avoids relying on Linux x86-64 fixture binaries when testing on macOS or another Docker-capable machine.

Services bind to `127.0.0.1`. Credentials are public test fixtures, and service data is disposable. Tests reset databases, users, and server settings; never point this suite at real data.

## Run

From the repository root:

```bash
scripts/test-services.sh up
scripts/test-services.sh run mvn -B -ntp verify -Pservices
scripts/test-services.sh down
```

The default starts everything except Kubernetes and waits for health checks. Profiles are `sql`, `oracle`, `mongo`, `redis`, `logs`, `aws`, `vault`, `ssh`, `k8s`, and `all`:

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

`-Pservices` enables the relevant integration profiles and passes `gateway.it.services`. For IDE runs, set `GW_TEST_SERVICES` to the absolute path of this directory. Without either setting, tests use their own fixture processes; Oracle requires an explicit external test service as described in [Oracle Database](../docs/oracle.md#integration-tests). The wrapper's `run` command serializes access to shared services through a temporary-directory lock; concurrent unwrapped runs would reset each other's data. Remove a stale lock only after verifying its previous process has stopped.

## Services

The committed [.env](.env) contains image pins, ports, and disposable passwords. Both Compose and the test fixtures read it.

| Service | Image / behavior | Loopback port | Profile |
| --- | --- | --- | --- |
| `postgres` | PostgreSQL 16.13 with test TLS | 55432 | sql, vault |
| `mysql`, `mariadb` | MySQL 8.4.10 / MariaDB 11.4.5 | 55306 / 55307 | sql |
| `oracle` | [gvenzl/oracle-free](https://github.com/gvenzl/oci-oracle-free) 23.26.3-slim, service `FREEPDB1` | 55521 | sql, oracle |
| `mongo`, `mongo-open`, `mongo-tls` | MongoDB 8.2.4 with auth / no auth / required TLS | 55017 / 55018 / 55019 | mongo |
| `mongo7`, `mongo9` | MongoDB 7.0.45 / 9.0.4 | 55070 / 55090 | mongo |
| `redis` | Redis 7.0.15, plain and TLS listeners | 55379 / 55380 | redis |
| `redis-replica`, `redis-open`, `redis-cluster` | Replica, no-auth, and cluster fixtures | 55381 / 55382 / 55383 | redis |
| `redis62` | Redis 6.2.11, plain and TLS | 55362 / 55363 | redis |
| `loki` | Loki 3.7.9 with tenants | 55100 | logs |
| `opensearch` | OpenSearch 3.9.0 without security plugin | 55200 | logs |
| `moto` | moto 5.2.4 AWS emulator | 55500 | logs, aws |
| `vault` | Vault 2.2.1 dev mode | 55820 | vault, ssh |
| `openssh` | Ubuntu 24.04 / OpenSSH 9.6p1 | 55022 | ssh |
| `k3s` | k3s v1.37.0-k3s1, privileged | 55443 | k8s |
| `certs` | One-shot Alpine certificate generation | None | sql, mongo, redis, vault |

PostgreSQL uses `postgres` / `postgres-pw-0001`; tests create the `app` database and `ro`, `rw`, `signal`, and `rotator` roles with `<role>-pw-0001`. MySQL/MariaDB use a passwordless fixture root account; tests create their database and users. Oracle uses the scoped `GATEROOK_TEST` / `oracle-pw-0001` account in `FREEPDB1`; the container creates it with session/table privileges and a limited tablespace quota. MongoDB uses `root` / `root-pw-7a1c2e9b`, except the no-auth fixture. Redis ACL users use `<user>-pass` as specified in `redis/users*.acl`. Vault's fixture token is `cnry-root-compose-0001`.

SSH test keys are generated under `run/openssh/`; connect with `ssh -i test-services/run/openssh/user_ed25519 -p 55022 ubuntu@127.0.0.1`. Kubernetes configuration is `test-services/run/k3s/kubeconfig.yaml`.

The ignored `run/` directory holds generated certificates, SSH keys/logs, and kubeconfig. These files must never be committed. Regenerate them by stopping services, removing only this disposable output, and starting again.

## Fixture isolation and platform differences

SQL fixtures recreate their test database and roles. Oracle recreates only its named test tables in the dedicated test user's schema; it never resets other schemas. Oracle data lives in the container's writable layer, survives a restart, and is removed by `down`. MongoDB removes non-system databases/users and resets fail points. Redis reloads ACLs, clears statistics/logs and data, and restores changed passwords. Loki uses per-run tenants; OpenSearch recreates test indexes; moto resets its emulator state. Vault recreates engines and roles, revoking database leases. SSH tests reset logs and inspect child processes. Kubernetes tests recreate a dedicated namespace and workload.

The Compose MongoDB 8.x fixture uses 8.2.4 because the original investigation found a Linux-kernel compatibility problem in 8.0.x; see [SERVER-121912](https://jira.mongodb.org/browse/SERVER-121912). Redis glob matching differs for high bytes between signed-`char` and unsigned-`char` platforms; tests probe the server's behavior rather than assuming the client architecture.

The MongoDB alternate-loopback hostname test is skipped in this mode because it needs `127.0.0.2`, which is not normally provided by Docker port forwarding or macOS. `KubeApiServerIT` remains a separate envtest fixture for Linux amd64; `K3sKubeletIT` uses the Compose k3s service with a real kubelet.

Use Docker Engine or Docker Desktop with Compose v2. Kubernetes requires a privileged container. Oracle uses a container memory limit of 2 GiB; its startup wrapper sets a 768 MiB SGA and a 256 MiB PGA target before the first database startup. The Docker VM also needs memory for the other running services. Run the `oracle` profile alone when the Docker VM cannot accommodate the full suite; actual requirements depend on images and other running containers. OpenSearch is configured with a 512 MB heap.
