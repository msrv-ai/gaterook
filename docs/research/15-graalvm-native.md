# 15 · GraalVM native core: startup, compatibility, and build cost

Research date: **2026-10-23**. Measurements were taken on Linux x64 with four vCPUs and approximately 15.7 GiB RAM. Some comparisons ran under substantial concurrent load; the JPA comparison was repeated on an otherwise idle machine. This is historical research, not a claim that the migrated application has just passed all native release tests. See the [architecture](../ARCHITECTURE.md), [desktop stack](14-platform-stack.md), and [plugin host](16-plugin-host.md).

## 1. Decision and measurement method

Ship a GraalVM native core while keeping the same application source runnable on Java 25 for development, tests, and fallback. Run dynamic Java plugins in separate JVM processes. Native image does not need to load arbitrary plugin classes.

The prototypes used Spring Boot 4.1.2 and the connector dependency set from reports 01–13. The toolchain was GraalVM CE **25.4.4.1.1** (25i4, based on OpenJDK 25.0.4.1), compared with Temurin **25.0.5+8**. The JPA variant added Spring Data JPA, Hibernate **7.4.13**, six entities, five repositories, Flyway, and schema validation.

Readiness was measured from process launch to the first successful health response, alternating modes and taking five-run medians. This is more representative than the framework's own Started message. Under load, the same non-JPA prototype recorded:

| Mode | Median readiness | Observed RSS |
| --- | --- | --- |
| JVM fat jar | 13.4 seconds | 224–263 MB |
| JVM with JDK AOT cache | 10.5 seconds | 217–257 MB |
| JVM with AOT cache and Spring AOT | 8.6 seconds | 204–233 MB |
| Native | 0.49 seconds | 109 MB |

The same-app improvement was approximately 17–27 times in that experiment. A lower eight-times figure in the original comparison mixed different prototypes and should not be used as a controlled result. These measurements do not predict every platform's application startup.

On an idle machine, the JPA comparison was:

| Metric | Without JPA | With JPA |
| --- | --- | --- |
| Native readiness, migrated database | 0.23 seconds | 0.32 seconds |
| Native RSS | 108–112 MB | 169 MB |
| JVM readiness | 5.0 seconds | 8.6 seconds |
| Native build duration | 5 min 48 sec | 9–11 minutes |
| Peak build RSS | 7.27 GiB | 8.45–8.79 GiB |
| Native executable | 142.9 MiB | 192.2–192.4 MiB |
| Compressed executable, gzip / xz | 54 / 38 MB | 72 / 51 MB |

JPA added roughly 0.1–0.15 seconds to native startup, 60 MB runtime memory, 49 MiB executable size, and up to 1.5 GiB build memory. It remained compatible with the startup goal; replacing JPA with JDBC was not the selected decision.

## 2. Distribution choice and compatibility targets

The dated selection was **Liberica NIK 25.0.4.1+1** for release builds on Linux x64/arm64, macOS x64/arm64, and Windows x64, with GraalVM CE as a canary. NIK retained macOS Intel support that CE dropped after 25.0.1. The full prototype was built on CE; the NIK experiment covered a smaller SQLite native smoke, so full release equivalence remained a required test.

Evaluate distribution licenses and retain notices rather than inferring redistribution rights from a product name. The research preferred the open-source distribution line over Oracle-specific licensing and rejected Mandrel as an ill-fitting target set for this desktop application. Vendor release cadence and security-update lag must be reviewed when pins change.

Target conservative CPU baselines: `-march=x86-64-v2` on x64 and `-march=compatibility` on arm64. The investigated defaults could require AVX2 or armv8.1 and fail on older supported desktop hardware. Build against a deliberately chosen Linux libc baseline; do not claim static/musl compatibility while relying on dynamically loaded SQLite and FFM default lookup.

## 3. Spring AOT and metadata

The Boot native profile runs Spring AOT and adds reachability metadata before native compilation. In the investigated stack, Native Build Tools **1.1.14** and its pinned metadata repository were used. A successful image build proves only that compilation completed; missing runtime metadata can fail much later.

**Keep manual metadata in a distinct directory**, such as a `*-manual` artifact under `META-INF/native-image`. Spring AOT overwrote files placed in its generated group/artifact directory in the experiment, silently removing manual FFM registration.

Use Spring binding hints for records/DTOs serialized manually; explicit runtime hint registrars for controlled type graphs/resources; and narrowly scoped JSON metadata for foreign calls or third-party proxies. Prefer Jackson's tree model for internal wire protocols. Register nested types recursively where the library requires them: the MCP client prototype needed 164 named nested schema types plus its anonymous helper.

Common failure cases included unregistered record components, missing JSON schema resources, buried MCP serialization errors that appeared as initialization timeouts, and missing FFM downcalls. Test runtime paths, not merely generated metadata file existence.

Do not initialize application packages at image-build time: keys, SecureRandom state, environment-dependent paths, and user configuration must not enter the image heap. Build-time conditional beans/profiles are fixed; runtime enablement of connectors or channels belongs in data/configuration rather than pruning their beans from the image.

## 4. Dependency compatibility findings

| Dependency/feature | Historical result and required treatment |
| --- | --- |
| Spring MVC/Tomcat and virtual threads | Worked with Spring AOT |
| Jackson manual record mapping | Requires binding metadata; tree-model protocol avoids it |
| Spring Data JPA/Hibernate | Worked with build-time enhancement and mapping rules below |
| sqlite-jdbc | Worked; export and stage its platform native library explicitly |
| Flyway | Worked, including SQLite triggers; preserve resource filesystem support |
| MCP Java SDK | Client operations worked after recursive schema-type hints |
| PostgreSQL | SCRAM, JSONB, arrays, metadata, and read-only transactions exercised |
| MySQL | Driver/error path exercised; live-server native validation remained open |
| MongoDB | BSON/EJSON and timeout paths exercised; live server/SRV remained open |
| Jedis 8 | Needed `redis/clients/jedis/pom.properties` resource registration |
| Fabric8 8 | Needed updated model metadata beyond the older repository entry |
| AWS STS / Google auth / OkHttp | Representative authentication/network paths exercised |
| Tink | AES-GCM and JSON keyset paths exercised |
| JDK WebSocket / process pipes / Linux UDS | Representative paths exercised |
| FFM process hardening | Required manual downcall signatures; platform support differs |
| MINA SSHD with Bouncy Castle | Image compilation fixed, but the recorded SSH handshake was still failing |
| PF4J | Excluded from the native core; runs in the JVM host |

Jedis's older metadata referenced a class in a package that moved in version 8, so its conditional resource hint no longer fired. Fabric8's newer model likewise exceeded older metadata. A repository fallback to a nearby version is not proof of correctness.

The Hibernate metadata repository had no exact 7.4.13 entry and selected a later version. The pinned combination passed the prototype, but upgrading Boot/Hibernate/metadata can change this fallback. Re-run database native tests after such changes.

## 5. SQLite and MINA packaging details

sqlite-jdbc normally extracts its native library at runtime. That can fail with a non-executable temporary directory or signed application library constraints. Set `org.sqlite.lib.exportPath` at build time, stage the exported library beside the core, and set `org.sqlite.lib.path` at runtime. The NIK smoke failed without the runtime path and worked with it. Sign the staged library with the application where required.

MINA's service declaration registered a rooted filesystem provider that GraalVM instantiated during image construction. Removing all filesystem providers fixed compilation but broke Flyway's resource filesystem. Initializing the provider at build time pulled in logging state. The successful compilation workaround was the `third-party/sshd-nofs` module, shading sshd-core/common without relocation and excluding only `META-INF/services/java.nio.file.spi.FileSystemProvider`.

An alternative runtime-filesystem-provider initialization flag compiled a smaller independent experiment, but was not validated with the full Spring/Flyway application. Do not remove the workaround on that evidence alone.

The SSH handshake remained unresolved in this dated report: Bouncy Castle provider verification failed in native; disabling BC advanced to missing reflection registration on JDK cryptography factories. Caller-based tracing was the next route. This is a **historical unresolved finding**, not an assertion that later application source necessarily retains the same failure. Any native release must establish its current SSH status with a real smoke test.

## 6. JPA rules that passed the prototype

- Enhance all entities at build time with the matching Hibernate Maven plugin. Runtime ByteBuddy generation is unavailable when Spring ORM selects the native bytecode provider `none`.
- Verify enhancement in tests, including lazy to-one relationships. An ordinary JVM run can hide a missing enhancement step by generating proxies dynamically.
- Prefer dialect discovery from JDBC metadata. A string class name in `spring.jpa.database-platform` caused an unregistered class lookup; an explicit hint is needed if retaining such configuration.
- Use Flyway for schema creation and Hibernate for validation. SQLite IDENTITY requires `INTEGER PRIMARY KEY` and the corresponding integer column definition on a Long ID. This fixes the earlier report 14 validation concern.
- JSON Map/List columns used CLOB, with an explicit Jackson 3 format mapper instance. Byte arrays use BLOB; enums use strings; timestamp representation must match native SQL queries.
- Entity callbacks, converters, optimistic versioning, UUID IDs, derived queries, JPQL constructor projections, interface projections, specifications, entity graphs, paging, and streams were exercised. External entity-listener callback methods needed to be public for the generated hints.
- Keep lazy access inside transactions and disable open-in-view. Use constructor-injected service classes so Spring AOT generates transactional proxies.
- Protect audit with immutable entities, read/append-only repository APIs, and SQLite triggers; each layer covers a different route.

**AOT repository errors can coexist with Maven BUILD SUCCESS.** A deliberate `findBySlugg` typo produced `Failed to contribute Repository method`, omitted generated method code, and failed only at application startup. Treat that AOT diagnostic as a failed verification and run the AOT application on the JVM before paying for a native build.

Native SQL queries, Querydsl, static metamodel generation, Envers, second-level cache, and arbitrary POJO JSON columns were not all validated. Add a targeted native test before adopting them.

## 7. Development and verification strategy

Keep the normal loop on the JVM. Build through Maven so entity enhancement occurs. Validate Flyway on an empty database, repository behavior, and generated AOT startup. Then use the tracing agent on meaningful connector integration paths, retaining only reviewed metadata.

Filter tracing by the **calling library**, not only the reflected target package. MINA uses reflection on JDK MessageDigest/Signature/KeyFactory/etc.; a target-only MINA filter discarded exactly those registrations. Preserve necessary proxy and resource entries and compare fresh agent output with versioned metadata when dependencies change.

Native smoke must exercise readiness/bootstrap, authenticated admin API, MCP initialization/tool listing/read/mutation-approval, SSE, representative connector calls, JPA queries/lazy relationships, audit write restrictions, and clean shutdown. Scan logs for missing-registration and unsupported-feature errors; some are swallowed by asynchronous frameworks and emerge only as timeouts.

Run native tests per supported OS/architecture before release. The memory measurements rule out assuming a 7 GB macOS runner can build the JPA image: a 6 GB builder heap thrashed and failed, while the successful 8 GB heap builds peaked above 8 GiB RSS. Set an explicit builder heap and choose sufficient runner memory. Published runner capacities and availability are dated observations, not permanent constants.

## 8. Application packaging and fallback

Stage the native executable and SQLite library in the desktop resources. Set a bounded runtime heap (the proposal used 384 MB), application-owned temporary directory, library path, and the existing stdin bootstrap. Do not pass host JVM `-XX:` options through the native executable: native images parse their own runtime flags.

Bundle a jlink runtime for Java plugin hosts so a plugin installed from a local file can work offline. The broad host runtime measured approximately 68 MB uncompressed / 44 MB compressed; a smaller module set saved space but could break legitimate plugins. Preserve its `legal/` notices.

The normal installer need not contain a second JVM copy of the full core. Retain a reproducible JVM artifact/fallback path for platforms where native validation fails. AOT caches may improve those JVM processes, but they are bound to JVM build/classpath and do not replace native correctness testing.

## 9. Evidence limits and sources

The report's detailed build logs supported the main CE/JPA size/memory comparisons. Some lightweight timings, plugin-host first-response figures, and alternate low-memory builds were not preserved or independently repeated. NIK full-application behavior, macOS/Windows signing/FFM/UDS, native GC latency under load, and the SSH fix remained open. Never describe these as completed release validation.

- [Spring Boot native-image guide](https://docs.spring.io/spring-boot/reference/packaging/native-image/introducing-graalvm-native-images.html), [native testing](https://docs.spring.io/spring-boot/how-to/native-image/testing-native-applications.html), and [AOT cache](https://docs.spring.io/spring-boot/reference/packaging/aot-cache.html).
- [GraalVM 25.1 notes](https://www.graalvm.org/release-notes/25.1/), [25.4 notes](https://www.graalvm.org/release-notes/25.4/), and [JDK 25 platform notes](https://www.graalvm.org/release-notes/JDK_25/).
- [Native Build Tools Maven documentation](https://graalvm.github.io/native-build-tools/latest/maven-plugin.html), [reachability metadata](https://github.com/oracle/graalvm-reachability-metadata), and [FFM support](https://www.graalvm.org/latest/reference-manual/native-image/native-code-interoperability/ffm-api/).
- [Liberica NIK installation](https://docs.bell-sw.com/liberica-nik/latest/general/install-guide/) and [distribution license](https://bell-sw.com/liberica_nik_eula/).
- [JEP 483](https://openjdk.org/jeps/483), [JEP 514](https://openjdk.org/jeps/514), and [JEP 515](https://openjdk.org/jeps/515).
- [Spring AI MCP hints](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-helpers.html).
