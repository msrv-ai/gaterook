# Repository layout

GateRook is a single application source tree with Maven modules and npm workspaces. A local build does not require another repository.

| Path | Responsibility |
| --- | --- |
| `pom.xml` | Application version, Java dependency management, reactor modules, build profiles, and test configuration |
| `package.json`, `package-lock.json`, `.nvmrc` | Node toolchain, workspace dependencies, and local commands |
| `.gitattributes` | Portable source line endings and exact-byte preservation for golden fixtures, vendored schemas, and migrations |
| `app/` | Executable Spring Boot application, native image configuration, process and end-to-end tests |
| `core/` | Gateway modules: policy, approvals, audit, secrets, endpoints, MCP, OAuth, clients, notifications, and admin API |
| `connector-api/` | Connector SPI and shared types |
| `connectors/` | SQL, MongoDB, Redis, SSH, logs, Kubernetes, GCP, AWS, HTTP, external MCP, and shared support |
| `plugin-protocol/` | GPP/1 messages, framing, and protocol tests |
| `plugin-host/`, `plugin-it/` | Isolated JVM plugin runtime and integration tests |
| `plugins/` | First-party plugins and test fixtures |
| `bridge/`, `bridge-cli/` | Electron token broker, Node stdio bridge, and Java/native helper |
| `desktop/` | Electron main/preload, lifecycle, security, development runner, and packaging configuration |
| `ui/` | Angular renderer, component tests, development proxy, and visual comparison tools |
| `design/` | Canonical design tokens, SCSS, components, assets, examples, generators, and design documentation |
| `catalog/` | Extension catalog format, signing tools, and pinned metadata |
| `third-party/` | Build-time adaptation of third-party dependencies |
| `scripts/` | Asset synchronization, staging, native/AOT checks, and test helpers |
| `test-services/` | Disposable Docker Compose integration-test services and public test credentials |
| `docs/` | Architecture, product decisions, development guide, and curated research |
| `ai/` (ignored) | Internal scratch files, temporary research, experiments, and logs; never commit |

Installed dependencies, runtime homes, build outputs, generated test keys, and editor state are ignored. Generated design assets needed by consumers remain versioned and are regenerated from canonical sources. The `/ai/` ignore rule is anchored at the root so Java packages under `src/main/java/ai/` and `src/test/java/ai/` remain application source.
