# Connector delivery

Every connector module under `connectors/` reaches the application in one of three ways. [`connectors/delivery.json`](../connectors/delivery.json) records which, with the connector types each module adds.

| Delivery | Meaning |
| --- | --- |
| `bundled` | Compiled into the native core. Part of every installer; needs no download and no Java runtime. |
| `module` | Not part of the installer and not compiled into the native core. Its code runs in a separate JVM host, as plugins do (see [architecture §7.5](ARCHITECTURE.md#75-plugins-catalog-and-external-mcp-servers)). |
| `bridge` | A module that this release still compiles in. Its package is published already, and the release after makes it `module` (see [the bridge](#the-bridge-between-two-releases)). |

The source of a connector is the same in every case: its Maven module, its schemas, and its tests. Delivery is a packaging decision.

Some of GateRook's connectors are in no build of this repository at all: ClickHouse, DuckDB, H2 and Microsoft Access are written against the connector API alone and exist only as modules. Their source, tests and documents live in the [modules repository](https://github.com/gaterook/gaterook-plugins), which also publishes every module's package and describes [how to build a module of your own](https://github.com/gaterook/gaterook-plugins/blob/main/docs/module-architecture.md).

| Module | Why it is no part of a core |
| --- | --- |
| `clickhouse` | It needs nothing of the core but the host's guarded HTTP client, and few first connections are to ClickHouse. |
| `duckdb` | Its JDBC driver is a native library, which the native core can't load. |
| `h2` | The H2 client opens its own sockets, which only the module host's TCP gate confines. |
| `msaccess` | UCanAccess and HSQLDB are a large stack for a file format few people use. |

## The rule

A connector is compiled in unless one of these holds:

- **It can't be.** The native image builder refuses its driver, or the driver needs native-image work that the vendor does not maintain. The Cassandra driver is the current case: its own GraalVM substitutions require JNR, which this build excludes.
- **It is too heavy for everybody to carry.** A bundled connector may add at most the budget in `delivery.json` (3 MB of native image) unless `budget.exempt` lists it with the reason it stays compiled in. Neo4j is the current case: the Bolt driver brings Netty and Reactor with it.

Connectors that speak HTTP through the shared egress code cost between 0.5 and 3 MB of image each, which is why they are compiled in. Vendor driver stacks cost far more: the Oracle JDBC driver alone is about 30 MB.

Kubernetes, SSH, MongoDB, the log services and the SQL connector with PostgreSQL, MySQL and SQLite stay compiled in by decision, although they are over the budget ([decision 18](DECISIONS.md)).

Oracle Database and SQL Server are dialects of the SQL connector and their own units of delivery: `connectors/sql-oracle` and `connectors/sql-sqlserver` register the type and bring its JDBC driver, while the connector's code stays in `connectors/sql`. Both are `bridge` connectors now.

## What follows the file

- `app/pom.xml` depends on the bundled connectors. The native core is built from that classpath.
- The Maven profile `bridge` of `app/pom.xml` adds the bridge connectors. It is on unless `-Dgateway.withoutBridge` is given, which builds the core as the release after has it.
- The Maven profile `all-bundled` of `app/pom.xml` adds the module-delivered connectors for a JVM run that wants every connector in one process. `npm run build` and the development launchers use it; a native build must not. It doesn't add the modules of the modules repository: the development loop serves those from a signed list of modules on this computer, made of the packages built in a checkout of that repository ([UI development](../ui/README.md#develop-against-the-demo-world)).
- `node scripts/delivery.mjs --check` compares the file with `app/pom.xml`, the root reactor and the connector directories. It runs with `npm test`.
- The tests of the assembly read the file too: `ConnectorDelivery` in `app/src/test` gives them the types the running core must have.

To change a connector's delivery, edit `delivery.json`, move its dependency in `app/pom.xml` between the main dependencies, the `bridge` profile and the `all-bundled` profile, and run `npm test`.

## The bridge between two releases

A connector that a released build compiles in doesn't become a module in one step. A person who has connections of its type would start the next release without the connector, and would wait for a download before the first call.

The release between is the bridge. It still compiles the connector in, and its module is already published.

- With the check for updates, the core installs the module ahead for every such type that a connection uses. It never does so at the start.
- Nothing uses the module meanwhile. A build that compiles a type in serves it itself, and the type's card in Add connection offers no download.
- The release after doesn't compile the connector in. It finds the module in place and serves the same connections from the module's host at once, with nothing to download.

A person who skips the bridge release, or was offline through it, starts the release after without the module. The type is then awaited, as described under [Installing](#a-connector-that-was-compiled-in-before).

To end a bridge, change the connector's delivery to `module` and remove its dependency from the `bridge` profile.

## Connector modules

A connector delivered as a module is GateRook's own connector code with another delivery. It is written against the same [connector SPI](connector-spi.md) as a compiled-in connector, and its connector types are built-in types: plain tool names, the default modes the connector declares, its own classification, exact action and prepared state. A plugin is foreign code and gets none of that ([architecture §7.5](ARCHITECTURE.md#75-plugins-catalog-and-external-mcp-servers)).

A module is built in one of two places. The modules of this repository package a connector of `connectors/` with the shared connector code: Apache Cassandra and Neo4j, and Oracle Database and SQL Server while they are bridge connectors. The modules of the [modules repository](https://github.com/gaterook/gaterook-plugins) use the connector API and nothing else of the application. Both kinds are published in the same list, install the same way and run in the same host.

### The package

A module is one JAR with an `mcp-gateway-plugin.json` of `manifestVersion` 2. The manifest names a `moduleClass` that implements `ConnectorModule` (connector API 1.2) and lists one or more connector types, each with its schemas, tools, template kinds, concurrency and capabilities. `ModuleManifests` writes it from the module's own connectors, so the manifest and the code can't disagree by hand. The host refuses a module whose code gives other connector types or other tool definitions than its manifest declares.

A module may ship its own classes under `ai.msrv.gaterook.connectors.` and its driver stack. It may not ship the packages the host provides: the connector API, the plugin protocol, the host itself, Jackson, PF4J and SLF4J.

Each module of this repository has a folder under `modules/`:

| File | What |
| --- | --- |
| `module.json` | The module's descriptor: its id, version, name, the application and connector API versions it runs with, what it may reach, and its host's limits. |
| `pom.xml` | Builds the package: the connector module of `connectors/`, its libraries and the shared connector code in one JAR, without what the host provides. |
| `src/main/java/…/…Module.java` | The entry point: it gives the host the module's connectors. |
| `src/main/resources/META-INF/LICENSE-…-module.txt` | Which of the bundled libraries is under which license. The libraries' own license and notice files stay in the package. |

A module of the modules repository has the same descriptor and entry point, with the connector itself and its tests in the same folder ([module architecture](https://github.com/gaterook/gaterook-plugins/blob/main/docs/module-architecture.md)).

```bash
mvn -B -ntp -pl modules/cassandra -am package -DskipTests
```

The package is `modules/<id>/target/<id>-<version>.jar`, here `modules/cassandra/target/cassandra-<version>.jar`.

| Module | Connector | Package |
| --- | --- | --- |
| `cassandra` | [Apache Cassandra](cassandra.md) with its Java driver | About 12 MB |
| `neo4j` | [Neo4j](neo4j.md) with its Bolt driver | About 9 MB |
| `sql-oracle` | [Oracle Database](oracle.md): the SQL connector with the Oracle JDBC driver | About 17 MB |
| `sql-sqlserver` | [SQL Server](sql-server.md): the SQL connector with the Microsoft JDBC driver | About 9 MB |

The modules repository builds four more:

| Module | Connector | Package |
| --- | --- | --- |
| `clickhouse` | [ClickHouse](https://github.com/gaterook/gaterook-plugins/blob/main/docs/clickhouse.md) over its HTTP interface | About 60 KB |
| `duckdb` | [DuckDB](https://github.com/gaterook/gaterook-plugins/blob/main/docs/duckdb.md) with its JDBC driver and native libraries for macOS on Apple Silicon and Windows x64 | About 34 MB |
| `h2` | [H2](https://github.com/gaterook/gaterook-plugins/blob/main/docs/h2.md) with the H2 client | About 3 MB |
| `msaccess` | [Microsoft Access](https://github.com/gaterook/gaterook-plugins/blob/main/docs/access.md) with UCanAccess, Jackcess and HSQLDB | About 4 MB |

### Versions

A module's version is its own and has nothing to do with the application's. It changes only when the package does. Every archive of the reactor is built with a fixed timestamp (`project.build.outputTimestamp` in the root `pom.xml`), so the same sources give the same bytes, and [`modules/modules.lock.json`](../modules/modules.lock.json) records the SHA-256 of every released version of this repository's modules. The modules repository keeps the same record of its own modules, with the same commands.

A package carries nothing of the build that made it: the shade plugin of every module leaves out the manifest and the Maven descriptor of each artifact, the module's own included. They would name the application's version, the JDK and the build plugins, and a released package would look changed after the next application release although nothing of the module was.

```bash
node scripts/modules.mjs check
```

```bash
node scripts/modules.mjs status
```

```bash
node scripts/modules.mjs bump cassandra patch
```

`check` compares `modules/` with `connectors/delivery.json` and the poms; it runs with `npm test`. `status` says for each built package whether its version is `new`, `released` with these bytes, or `changed`: built with other bytes than the released ones. A changed package can't be released until `bump` gives it a new version. A package that didn't change is not released again.

### The channel

Modules come from the module channel: a signed index with its own Ed25519 keys (`gateway.modules.channel-keys`) and address (`gateway.modules.channel-url`), kept apart from the plugin catalog because it vouches for something else. Every build reads GateRook's official list, `modules/index.json` of [gaterook/gaterook-plugins](https://github.com/gaterook/gaterook-plugins) on GitHub, under the key built into the application (`OfficialLists`), unless it is given another list; the packages are the assets of that repository's releases. A build from the repository reads it as a release does, so a module GateRook publishes shows up in a development run by itself. Only the tests of this repository say `gateway.lists=none` and read no list unless a test gives them one: they don't depend on the network. A release ignores that setting. The index is verified as the catalog is: the signature covers the raw bytes, the sequence never goes back, an expired index installs nothing, and a revoked package is refused. Its state lives in the signed settings `modules.last-good` and `modules.sequence`.

The list is read in the background, and nothing waits for it: two seconds after the start, or after the unlock when the start was locked; then whenever a page asks for it and the last check is more than six hours old (a failed check is tried again after five minutes); with every check for updates; and at once when a person chooses **Check now** in Connectors › Catalog. When the core accepts another list than the one it had, it publishes a `module.list` event, and the pages that show connector types read them again: a module that was published while the application runs appears with the next check, without a restart. A list that can't be reached changes nothing: the modules of the last list the core verified stay on offer, and the pages say that the check failed. With `gateway.modules.auto-update=false` nothing is read in the background, and the list is read only when a page asks for it.

An entry lists one version of one module: the application and connector API versions it needs, the package's address, size and SHA-256, and the connector types it adds with their tool names. Several versions of a module may be listed. A build takes the highest version whose requirements it satisfies, so an application that isn't updated keeps a module it can run.

`catalog/sign-index.mjs` signs a list of modules as it signs the catalog, and `node scripts/modules.mjs index` writes the list's source from the built packages and the list before it. A version that is listed keeps the package it was released with: a list that would pin other bytes to it is refused.

Only the channel installs a module by itself. A person can install a module's package from a file as well, with a review ([a module from a file](#a-module-from-a-file)); the plugin catalog delivers no module. The tool names of every listed module are reserved: no new plugin, MCP server tool or template may publish one. A plugin that was installed before a module was listed keeps its tools until it is uninstalled; the module of its name installs only then.

What is published can be checked against the application itself. With `-Dgateway.e2e.officialLists=true` the module tests of `app` have their core read GateRook's list on GitHub instead of a list of their own, and `PublishedModulesCoreProcessIT` runs: a core installs every listed module from the list, must then hold exactly the packages that `modules/modules.lock.json` records for this repository's modules, and a core that trusts another key must take nothing from the same address. These tests need the network and are never part of a build.

The tests of the modules whose source lives in the modules repository (`ClickHouseModuleCoreProcessIT`, `DuckDbModuleCoreProcessIT`, `H2ModuleCoreProcessIT`, `AccessModuleCoreProcessIT`) take their packages from a checkout of that repository, built there and named with `-Dgateway.modulesRepo=<folder>`; without one they are skipped.

```bash
mvn -B -ntp -pl app -am verify -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=PublishedModulesCoreProcessIT -Dfailsafe.failIfNoSpecifiedTests=false -Dgateway.e2e.officialLists=true
```

### Installing

Nobody reviews or confirms a module that GateRook's list delivers. It installs when something needs one of its connector types:

- a person opens the type's form in Add connection;
- a connection of the type is created, tested, shared or imported;
- a connection of the type exists and the build doesn't have the type, as after an update that turned a compiled-in connector into a module: the start asks for the module at once;
- a call reaches a connection whose module is installed but whose package isn't on the disk, as after a backup restored on another computer. The start notices too and downloads the package in the background.

A request that is about to work with the type waits for the installation, up to 90 seconds. A call waits up to 20 seconds and otherwise answers that the connector is being downloaded. A type that something merely looked up, such as a label in an old audit row, installs nothing unless a connection uses it.

#### A connector that was compiled in before

An update that turns a compiled-in connector into a module finds connections of a type the new build doesn't carry. Such a type is awaited. It stays registered as its signed row in the database describes it, so its connections keep their tools on every endpoint, in the modes a person chose, and an agent's list of tools is the same after the update as before it.

Until the module is installed, a stand-in answers for the type and runs nothing. A call waits up to 20 seconds for the module and then goes to its connector. Otherwise it is refused with the reason: the connector is being downloaded, or why it couldn't be. A classification the connector didn't give is a deny. When the module arrives, the endpoints rebuild from its own manifest.

A type is awaited only while a connection uses it and its row verifies. A type whose tools this build couldn't hold beside its own, because a tool it shares with a compiled-in type takes other arguments or has another nature, isn't kept either: it is absent until its module is installed. Its templates aren't offered while it is awaited; the modes of their tools stay.

#### Tools that a module shares with the core

A dialect of the SQL connector that comes as a module has the same `sql_*` tools as the dialects the core compiles in, and an endpoint publishes one `sql_query` for all of its SQL connections. A module is built on its own day, so its copy of such a tool may be worded as another build worded it.

The family has one definition, the compiled-in one. A module's tool of the same name is registered and published as the core defines it, as long as everything a call depends on is the same: the arguments, the output, the annotations, the nature, the modes and the risk. Titles, descriptions and examples may differ. A module whose tool differs in more than wording isn't registered, and the start goes on without its types.

#### The steps

An installation takes these steps: the Java runtime (downloaded once when the application doesn't carry one), the package, its checks (the pinned bytes, the manifest, the versions it needs, the connector types the index promised), a first load in a fresh host, and a signed row. One installation runs per module; whoever asks meanwhile joins it. Each step is published as a `module.state` event.

`GET /api/modules` lists the installed and the listed modules. `POST /api/modules/{id}/install` starts an installation by module id or connector type id and answers at once. `POST /api/modules/refresh` checks the list again and starts the updates it calls for. `DELETE /api/modules/{id}` removes a module that no connection uses. The cards of `GET /api/connector-types` carry `module` for a type that a module adds.

### Updates

An installed module follows the list. When the list has a higher version that the build can run, the module updates by itself: soon after the start, every six hours, and whenever the list is checked again. Nobody is asked. `gateway.modules.auto-update=false` turns the checks off.

- The new version's host starts beside the old one and takes over; the old one finishes the calls it has and stops.
- What waits for approval on the module's connections is rejected with "Connector updated to 1.0.1, ask again": it was prepared and described by the old code.
- The version before is kept for a week. `POST /api/modules/{id}/rollback` returns to it, and an update then installs only a version past the one that was rolled back from.
- A package the list revokes is switched off: its host stops and its connector types leave the endpoints. The best listed version that isn't revoked replaces it, at once.
- An update that fails leaves the installed version as it is and is tried again after six hours.

Each change is a Setup change ("Connector Apache Cassandra: 1.0.0 → 1.0.1").

### A module from a file

**Connectors → Add connector → Install plugin or module** takes a module's package from this computer, as it takes a plugin's JAR. The core stages the file and checks it before anything of it loads: the archive, the manifest, the application and connector API versions it needs, and whether the list revoked it. The review then says which of two things the package is.

**A package that GateRook's signed list has**, by version and SHA-256, is GateRook's own module, wherever its bytes came from. It installs without a question about trust, and from then on it is what the channel would have installed: it follows the list. This is how a module reaches a computer that can't download it: get `<id>-<version>.jar` from the release `module-<id>-<version>` of [gaterook/gaterook-plugins](https://github.com/gaterook/gaterook-plugins) elsewhere and choose the file. The core compares it with the list it last verified, so that list must be there and not expired.

**Any other package is your own module**: one you built from GateRook's sources with changes, or wrote against the [connector SPI](connector-spi.md). The modules repository describes [how a module is built](https://github.com/gaterook/gaterook-plugins/blob/main/docs/module-architecture.md) and has the smallest one as an example. GateRook didn't sign it and nobody reviewed its code, so it is reviewed as an unsigned plugin is, and installs only after you confirm that you trust it.

The connector types of your own module are built-in types by name: plain tool names, and a card among the connectors in Add connection that says `Your module, unsigned`. In everything else the core takes the module's word for nothing, as it takes an unsigned plugin's:

| | GateRook's module | Your own module |
| --- | --- | --- |
| Tool modes on an endpoint | The connector's own defaults | Every tool starts in Approval |
| Read only | As the connector declares | Your confirmation per tool, bound to the tool's definition and to this very package |
| What a person approves | The connector's exact action | The call's arguments; the module's own description is shown beside them as not verified |
| Classification | The connector's own | The connector's own, with its rule ids and words confined as a plugin's are |
| Tools that depend on the endpoint, template kinds | Yes | No: only the declared tools |
| Names | A tool may be shared with the compiled-in connectors when its contract is the same | No connector type, tool, or family of tool names (the prefix up to the first underscore) of this build, of another installed package or of another listed module |
| Updates | Follows the list | None from the list, which neither replaces nor restores it; install a newer file |
| Revocation | By SHA-256 | By SHA-256 |

A file never replaces another build of the version that is installed: give a changed build another version in `module.json`. The same build again is taken only when its package is missing on the disk, as after a backup restored on another computer, and brings it back.

A build of a module that GateRook's list has, under the same module id, may keep that module's names and takes the place of GateRook's build; what waits for approval on its connections is rejected, and tools that ran in Read only wait for a person again until you confirm them. `POST /api/modules/{id}/replace-own` returns to the list's version as an update does (**Connectors → Installed**, the card's menu, **Use GateRook's build**). Each tool then keeps the mode it has.

**Uninstall module** in the same menu (`DELETE /api/modules/{id}`) removes a module once no connection uses its connector types.

A file is checked and installed by the routes that take a plugin's: `POST /api/plugins/check` with the file (at most 150 MB for a module) answers a report with `module: true`, `signed`, the connector `types` with their tools and, when a module of that id is installed, `installedVersion`; `POST /api/plugins/install` with the `stagingId` installs it, an unsigned one only with `acknowledgeUnsigned`. `GET /api/modules` and the `module` of a type in `GET /api/connector-types` say `signed: false` for your own module.

### In the interface

A type that a module adds is a card among the built-in types in Add connection. Until the module is installed, its line says what choosing it costs: `Built-in · 3 tools · 9.4 MB download`. A type of your own module says `Your module, unsigned · 2 tools` instead, and Connectors › Installed shows it as a card of its own kind (Module, Unsigned) with the package's version. Continue goes straight to Configure, with no review and no dialog. While the form waits for the module, Configure says which step the installation is at, from the `module.state` events: the Java runtime, the download, its check, the installation. A module that doesn't arrive is reported in the core's own words, and Try again asks for it again; a download that is still under way is joined, not restarted.

Connectors › Catalog lists the same modules, so that a person who looks for a connector there finds it: one card for each connector type of a listed module that isn't installed, before the entries of the plugin catalog, with the module's version and the line `Not part of the installer: adding a connection downloads it (9.4 MB).` Its **Add connection** opens the wizard at that type, where the module installs as above. The tab's count includes these cards. When the list couldn't be checked, the panel says so above the cards of the last list the core verified. Both pages follow `module.list`, so the cards change when the core accepts another list.

**Check now** in the Catalog's toolbar has the core check both signed lists at once, whatever their age: the list of modules (`POST /api/modules/refresh`, which also starts the updates the list calls for) and the plugin catalog (`POST /api/catalog/refresh`). The line beside the search then says when they were checked, and a toast says what the check found: the connectors that are new in the Catalog, that it is up to date, or why a list couldn't be reached, in the core's words. A list that a build doesn't read is not a failure. The button is also there when the Catalog is empty, as after a first start without the network.

### At run time

An installed module's row stores its manifest, and the row's MAC covers it. Its connector types are therefore registered at start without a host and without the package; a host starts on the first call. All types of a module share one host process. A compiled-in connector of the same type id comes first: a build that compiles a type in serves it itself, whatever is installed.

A module's host runs in the same OS sandbox as a plugin's and reaches only the hosts its manifest declares, GateRook's own module and yours alike. Its calls get what a compiled-in connector has from the core: the shared remote quotas, the masked view of a result, progress and client log messages.

A call that can't reach its connector fails closed. A classification that doesn't arrive is a deny. A change whose answer doesn't arrive is `OUTCOME_UNKNOWN` and is never sent twice.

## Measuring

`scripts/size-report.mjs` says what each connector adds to a native core and applies the budget. It needs a native build made with a dashboard dump:

```bash
NATIVE_IMAGE_OPTIONS="-H:+UnlockExperimentalVMOptions -H:DashboardDump=$PWD/app/target/size -H:+DashboardCode -H:+DashboardJson -H:-DashboardBgv" \
  mvn -B -ntp -pl app -am -Pnative -DskipTests package
node scripts/size-report.mjs --resolve --check
```

That build makes three native images in turn, and each writes the dump; the core is the last, so its dump is the one that remains. `--resolve` asks Maven for the dependency list of every connector module.

The builder reports only its ten largest origins; the dump has the size of every compiled method. The script finds each method's class in the jars of `app/target/app.jar` and assigns each jar to the core or to the connector that brings it, from Maven's dependency lists. A connector's share of the image is estimated from its share of the code area, because most of the image heap is code metadata, strings and class objects that follow the code. Treat the image column as an estimate; the code column is measured.

`--check` exits with 1 when a bundled connector is over the budget and not exempt, or when a module-delivered connector was compiled into the image.
