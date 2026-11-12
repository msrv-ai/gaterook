# Connector delivery

Every connector module under `connectors/` reaches the application in one of two ways. [`connectors/delivery.json`](../connectors/delivery.json) records which, with the connector types each module adds.

| Delivery | Meaning |
| --- | --- |
| `bundled` | Compiled into the native core. Part of every installer; needs no download and no Java runtime. |
| `module` | Not part of the installer and not compiled into the native core. Its code runs in a separate JVM host, as plugins do (see [architecture §7.5](ARCHITECTURE.md#75-plugins-catalog-and-external-mcp-servers)). |

The source of a connector is the same in both cases: its Maven module, its schemas, and its tests. Delivery is a packaging decision.

## The rule

A connector is compiled in unless one of these holds:

- **It can't be.** The native image builder refuses its driver, or the driver needs native-image work that the vendor does not maintain. The Cassandra driver is the current case: its own GraalVM substitutions require JNR, which this build excludes.
- **It is too heavy for everybody to carry.** A bundled connector may add at most the budget in `delivery.json` (3 MB of native image) unless `budget.exempt` lists it with the reason it stays compiled in.

Connectors that speak HTTP through the shared egress code cost between 0.5 and 3 MB of image each, which is why they are compiled in. Vendor driver stacks cost far more: the Oracle JDBC driver alone is about 30 MB.

Kubernetes, SSH, MongoDB, the log services and the SQL family stay compiled in by decision, although they are over the budget ([decision 18](DECISIONS.md)).

## What follows the file

- `app/pom.xml` depends on the bundled connectors. The native core is built from that classpath.
- The Maven profile `all-bundled` of `app/pom.xml` adds the module-delivered connectors for a JVM run that wants every connector in one process. `npm run build` and the development launchers use it; a native build must not.
- `node scripts/delivery.mjs --check` compares the file with `app/pom.xml`, the root reactor and the connector directories. It runs with `npm test`.
- The tests of the assembly read the file too: `ConnectorDelivery` in `app/src/test` gives them the types the running core must have.

To change a connector's delivery, edit `delivery.json`, move its dependency in `app/pom.xml` between the main dependencies and the `all-bundled` profile, and run `npm test`.

## Connector modules

A connector delivered as a module is GateRook's own connector code with another delivery. It is the same Maven module, written against the same [connector SPI](connector-spi.md), and its connector types are built-in types: plain tool names, the default modes the connector declares, its own classification, exact action and prepared state. A plugin is foreign code and gets none of that ([architecture §7.5](ARCHITECTURE.md#75-plugins-catalog-and-external-mcp-servers)).

### The package

A module is one JAR with an `mcp-gateway-plugin.json` of `manifestVersion` 2. The manifest names a `moduleClass` that implements `ConnectorModule` (connector API 1.2) and lists one or more connector types, each with its schemas, tools, template kinds, concurrency and capabilities. `ModuleManifests` writes it from the module's own connectors, so the manifest and the code can't disagree by hand. The host refuses a module whose code gives other connector types or other tool definitions than its manifest declares.

A module may ship its own classes under `ai.msrv.gaterook.connectors.` and its driver stack. It may not ship the packages the host provides: the connector API, the plugin protocol, the host itself, Jackson, PF4J and SLF4J.

Each module has a folder under `modules/`:

| File | What |
| --- | --- |
| `module.json` | The module's descriptor: its id, version, name, the application and connector API versions it runs with, what it may reach, and its host's limits. |
| `pom.xml` | Builds the package: the connector module of `connectors/`, its libraries and the shared connector code in one JAR, without what the host provides. |
| `src/main/java/…/…Module.java` | The entry point: it gives the host the module's connectors. |
| `src/main/resources/META-INF/LICENSE-…-module.txt` | Which of the bundled libraries is under which license. The libraries' own license and notice files stay in the package. |

```bash
mvn -B -ntp -pl modules/cassandra -am package -DskipTests
```

The package is `modules/cassandra/target/cassandra-<version>.jar`. `modules/cassandra` is the first one: Apache Cassandra with its Java driver, about 12 MB.

### Versions

A module's version is its own and has nothing to do with the application's. It changes only when the package does. Every archive of the reactor is built with a fixed timestamp (`project.build.outputTimestamp` in the root `pom.xml`), so the same sources give the same bytes, and [`modules/modules.lock.json`](../modules/modules.lock.json) records the SHA-256 of every version that was released.

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

Modules come from the module channel: a signed index with its own Ed25519 keys (`gateway.modules.channel-keys`) and address (`gateway.modules.channel-url`), kept apart from the plugin catalog because it vouches for something else. A release reads GateRook's official list, `modules/index.json` of [gaterook/gaterook-plugins](https://github.com/gaterook/gaterook-plugins) on GitHub, under the key built into the application (`OfficialLists`); the packages are the assets of that repository's releases. A development build reads no list unless it is given one or started with `-Dgateway.lists=official`, so tests and the development loop don't depend on the network. The index is verified as the catalog is: the signature covers the raw bytes, the sequence never goes back, an expired index installs nothing, and a revoked package is refused. Its state lives in the signed settings `modules.last-good` and `modules.sequence`.

An entry lists one version of one module: the application and connector API versions it needs, the package's address, size and SHA-256, and the connector types it adds with their tool names. Several versions of a module may be listed. A build takes the highest version whose requirements it satisfies, so an application that isn't updated keeps a module it can run.

`catalog/sign-index.mjs` signs a list of modules as it signs the catalog, and `node scripts/modules.mjs index` writes the list's source from the built packages and the list before it. A version that is listed keeps the package it was released with: a list that would pin other bytes to it is refused.

Only the channel installs a module. A module package that arrives as a file, or through the plugin catalog, is refused. The tool names of every listed module are reserved: no new plugin, MCP server tool or template may publish one. A plugin that was installed before a module was listed keeps its tools until the module itself is installed.

### Installing

Nobody reviews or confirms a module. It installs when something needs one of its connector types:

- a person opens the type's form in Add connection;
- a connection of the type is created, tested, shared or imported;
- a connection of the type exists and the type isn't registered, as after an update that turned a compiled-in connector into a module;
- a call reaches a connection whose module is installed but whose package isn't on the disk, as after a backup restored on another computer. The start notices too and downloads the package in the background.

A request that is about to work with the type waits for the installation, up to 90 seconds. A call waits up to 20 seconds and otherwise answers that the connector is being downloaded. A type that something merely looked up, such as a label in an old audit row, installs nothing unless a connection uses it.

The steps are: the Java runtime (downloaded once when the application doesn't carry one), the package, its checks (the pinned bytes, the manifest, the versions it needs, the connector types the index promised), a first load in a fresh host, and a signed row. One installation runs per module; whoever asks meanwhile joins it. Each step is published as a `module.state` event.

`GET /api/modules` lists the installed and the listed modules. `POST /api/modules/{id}/install` starts an installation by module id or connector type id and answers at once. `POST /api/modules/refresh` checks the list again and starts the updates it calls for. `DELETE /api/modules/{id}` removes a module that no connection uses. The cards of `GET /api/connector-types` carry `module` for a type that a module adds.

### Updates

An installed module follows the list. When the list has a higher version that the build can run, the module updates by itself: soon after the start, every six hours, and whenever the list is checked again. Nobody is asked. `gateway.modules.auto-update=false` turns the checks off.

- The new version's host starts beside the old one and takes over; the old one finishes the calls it has and stops.
- What waits for approval on the module's connections is rejected with "Connector updated to 1.0.1, ask again": it was prepared and described by the old code.
- The version before is kept for a week. `POST /api/modules/{id}/rollback` returns to it, and an update then installs only a version past the one that was rolled back from.
- A package the list revokes is switched off: its host stops and its connector types leave the endpoints. The best listed version that isn't revoked replaces it, at once.
- An update that fails leaves the installed version as it is and is tried again after six hours.

Each change is a Setup change ("Connector Apache Cassandra: 1.0.0 → 1.0.1").

### In the interface

A type that a module adds is a card among the built-in types in Add connection. Until the module is installed, its line says what choosing it costs: `Built-in · 3 tools · 9.4 MB download`. Continue goes straight to Configure, with no review and no dialog. While the form waits for the module, Configure says which step the installation is at, from the `module.state` events: the Java runtime, the download, its check, the installation. A module that doesn't arrive is reported in the core's own words, and Try again asks for it again; a download that is still under way is joined, not restarted.

### At run time

An installed module's row stores its manifest, and the row's MAC covers it. Its connector types are therefore registered at start without a host and without the package; a host starts on the first call. All types of a module share one host process. A compiled-in connector of the same type id comes first: a build that compiles a type in serves it itself, whatever is installed.

A module's host runs in the same OS sandbox as a plugin's and reaches only the hosts its manifest declares. Its calls get what a compiled-in connector has from the core: the shared remote quotas, the masked view of a result, progress and client log messages.

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
