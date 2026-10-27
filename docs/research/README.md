# Architecture research

These reports preserve the technical research behind GateRook's desktop application. They cover protocol contracts, connector safety, credentials, the interface, and the runtime. They are curated reference documents, not an implementation progress log.

Most evidence was collected on **2026-10-23**. Version tables, benchmark figures, API observations, and unresolved questions are dated evidence. A report's description of a prototype is not a claim that the current application or every supported operating system has passed the same checks. Consult the [architecture](../ARCHITECTURE.md), [decisions](../DECISIONS.md), current source, and build manifests for the integrated implementation.

| Report | Subject |
| --- | --- |
| [01 · MCP protocol](01-mcp-protocol.md) | Tool contracts, transports, approval state, and client compatibility |
| [02 · MCP authentication and remote access](02-mcp-auth-claudeai.md) | OAuth, consent, tunnel ingress, and remote-client boundaries |
| [03 · Client connection](03-clients-oneclick.md) | Local bridges, credential brokering, and client configuration |
| [04 · SQL](04-sql.md) | Classification, read-only enforcement, pools, and exact statements |
| [05 · NoSQL](05-nosql.md) | MongoDB and Redis access, policy, and bounded results |
| [06 · SSH](06-ssh.md) | Reviewed runbooks, quoting, credentials, and remote execution |
| [07 · Logs](07-logs.md) | Log providers, query bounds, pagination, and masking |
| [08 · Kubernetes](08-kubernetes.md) | API scope, manifests, diffs, and approved changes |
| [09 · Cloud](09-cloud.md) | AWS/GCP inventory, metrics, IAM, and read boundaries |
| [10 · HTTP and templates](10-http-openapi-templates.md) | SSRF defenses, OpenAPI import, and SQL templates |
| [11 · Extensibility](11-extensibility.md) | Catalog trust, external MCP servers, and plugin provenance |
| [12 · Secrets](12-secrets.md) | Envelope encryption, master-key lifecycle, stores, and masking |
| [13 · Notifications](13-notifications.md) | Desktop, Slack, and Telegram approval delivery |
| [14 · Desktop stack](14-platform-stack.md) | Spring Boot, SQLite, Angular, Electron, and local boundaries |
| [15 · Native core](15-graalvm-native.md) | GraalVM, metadata, JPA, startup measurements, and packaging |
| [16 · Plugin host](16-plugin-host.md) | Separate JVM generations, GPP/1, scoped secrets, and lifecycle |

Read the reports as a sequence of decisions. Report 16 replaces report 11's initial proposal to load Java plugins in the core. Report 15 replaces report 14's JVM-only shipping-core proposal while keeping JVM development, and refines its JPA/native mapping requirements. Other historical discrepancies are called out where they affect trust or compatibility.

The [design kit](../../design/README.md), [design rules](../../design/DESIGN.md), and [screen blueprint](../../design/BLUEPRINT.md) provide the interface contracts used alongside this research. Temporary investigation files and working notes belong in the ignored root `ai/` directory and must never be committed.
