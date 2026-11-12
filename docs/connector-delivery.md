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
