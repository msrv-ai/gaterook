# GateRook MCP Firewall product messaging

GateRook's product category is **MCP Firewall**. The central promise is controlled AI access to production infrastructure through encrypted credentials, scoped connector permissions, exact-action approvals, and append-only, tamper-evident audit logs. The [security model](security.md) defines the scope of these claims.

This document provides reusable English copy for the repository and [public website](https://gaterook.com/). Website templates and publishing remain outside the application design kit. Page URLs, protocol identifiers, configuration keys, and existing product names stay stable.

## Core positioning and promotional copy

| Placement | Copy |
| --- | --- |
| Product category | GateRook MCP Firewall |
| Main headline | The MCP Firewall for AI access to production. |
| Supporting headline | Put every AI action through a security checkpoint. |
| Hero description | Protect connected databases, APIs, logs, cloud services, and MCP servers with encrypted credentials, scoped tool permissions, exact-action approvals, and tamper-evident audit logs. |
| Supporting line | Give AI the access its task needs. Keep control of what can run. |
| Short promotion | Your AI proposes. Your MCP Firewall enforces. |
| Primary CTA | Download GateRook |
| Secondary CTA | Explore security controls |
| Footer | The MCP Firewall for controlled AI access. |
| Page title | GateRook — MCP Firewall for AI Access to Production |
| Meta description | Control AI access to production with GateRook MCP Firewall: encrypted credentials, scoped connectors, exact-action approvals, and tamper-evident audit logs. |

**Short product description:** GateRook is a desktop MCP Firewall that controls how AI clients access connected infrastructure. Keep credentials encrypted, scope each tool, review changes by default, and trace execution in an append-only, tamper-evident audit trail.

**Launch promotion:** Put production access behind GateRook. Connect your services, choose what each AI client can use, and review the exact action before a change runs by default. GateRook combines encrypted credentials, connector policies, and tamper-evident audit logs in one desktop MCP Firewall.

## Homepage sections

### Put a firewall between AI and production

Route MCP calls through a local enforcement point for databases, logs, APIs, cloud services, Kubernetes, SSH, and external MCP servers. Select the connections and tools each endpoint exposes. GateRook checks the client, scope, and policy before allowing execution.

### Keep credentials encrypted and under your control

Locally stored secrets use AES-256-GCM envelope encryption. GateRook resolves credentials for connectors and manages upstream OAuth sign-ins, so AI clients can use scoped tools without connection passwords in their configuration. OS credential protection secures master-key material when enabled with a supported secure backend.

### Review the exact action before execution

Inspect the stored action and target before allowing a change. GateRook binds approval to the prepared payload and rechecks policy and integrity before execution. Changes require review by default; explicit Bypass removes the human review step while retaining policy denials and execution limits.

### Control every connected tool

Choose Off, Read only, Approval, or explicit Bypass per tool. Separate production access from other environments and constrain requests with connector policies. Pause an endpoint or a shared connection to stop new access; actions already executing are not cancelled.

### Trace actions with tamper-evident audit logs

Follow requests, decisions, and execution outcomes in Activity. Append-only records, hash chains, and authenticated heads make changes detectable. GateRook exposes no MCP tool for agents to rewrite audit history. Retention and export settings govern how long eligible records remain.

### Bring existing MCP servers behind the firewall

Find a server in the Official MCP Registry or add its URL or configuration. Review the server, keep stored authentication secrets encrypted in GateRook, and choose which tools your AI clients may call. Registry discovery does not certify a server's behavior; local server processes still require your trust.

### Protect production access with clear boundaries

Production connections require production endpoints. Built-in public tunnel publication and Slack or Telegram approval are unavailable for production. GateRook controls the calls routed through it; infrastructure permissions, verified transport, and host security remain essential. Allowed results reach your AI client and may reach its provider.

**Closing headline:** Put production access behind your MCP Firewall.

**Closing copy:** Start with one scoped connection. Protect its credentials, choose its tool policies, and inspect its activity.

**Closing CTA:** Download GateRook.

## Website page headlines and descriptions

Use the following copy for the corresponding routes. Connector descriptions name their actual access boundary; read-only integrations are not advertised as write tools. Keep setup instructions, screenshots, and exact application labels aligned with the current product.

| Page | Headline | Intro or listing description |
| --- | --- | --- |
| [Home](https://gaterook.com/) | The MCP Firewall for AI access to production. | Protect connected services with encrypted credentials, scoped tools, exact-action approvals, and tamper-evident audit logs. |
| [Integrations](https://gaterook.com/integrations/) | One MCP Firewall. Controlled access across your stack. | Bring databases, APIs, cloud services, logs, SSH, Kubernetes, and external MCP servers under endpoint policies and audit. |
| [Documentation](https://gaterook.com/docs/) | Configure your MCP Firewall for production access. | Set up credentials, connector scope, tool policies, approvals, and activity review. |
| [Getting started](https://gaterook.com/docs/getting-started/) | Put your first connection behind the MCP Firewall. | Connect a service, select its tools, choose an AI endpoint, and inspect the first request in Activity. |
| [AWS](https://gaterook.com/docs/aws/) | Control AI access to AWS inventory and metrics. | Scope regions and IAM permissions for bounded resource inspection through GateRook. |
| [Google Cloud Logging](https://gaterook.com/docs/cloud-logging/) | Keep AI log searches within your Google Cloud scope. | Select projects or log views and constrain queries and returned data. |
| [CloudWatch](https://gaterook.com/docs/cloudwatch/) | Investigate CloudWatch logs with controlled AI access. | Select permitted log groups and bound searches with endpoint and log policies. |
| [Elasticsearch](https://gaterook.com/docs/elasticsearch/) | Control Elasticsearch reads. Review API changes. | Scope indices and REST operations, bound results, and review changes by default. |
| [GCP](https://gaterook.com/docs/gcp/) | Scope AI access to Google Cloud resources and logs. | Select projects and permitted resources while keeping service permissions under IAM control. |
| [HTTP APIs](https://gaterook.com/docs/http/) | Protect API credentials. Control every exposed operation. | Import selected operations, restrict destinations, and apply tool modes before granting AI access. |
| [Kubernetes](https://gaterook.com/docs/kubernetes/) | Scope cluster access. Review Kubernetes changes. | Limit namespaces and resources, inspect prepared changes, and audit execution. |
| [Loki](https://gaterook.com/docs/loki/) | Give AI scoped access to Loki logs. | Configure log scope and query limits for investigations through controlled read tools. |
| [MariaDB](https://gaterook.com/docs/mariadb/) | Put MariaDB queries and changes behind your MCP Firewall. | Restrict database access, bound reads, and require approval for changes by default. |
| [External MCP servers](https://gaterook.com/docs/mcp-server/) | Put existing MCP servers behind your MCP Firewall. | Review server definitions, protect stored credentials, and expose only selected tools under explicit modes. |
| [MongoDB](https://gaterook.com/docs/mongodb/) | Scope MongoDB reads. Review exact updates. | Restrict databases and collections, bound queries, and review prepared changes by default. |
| [MySQL](https://gaterook.com/docs/mysql/) | Protect MySQL access with scoped reads and reviewed writes. | Combine restricted database grants with tool policies, query limits, and change approval by default. |
| [OpenSearch](https://gaterook.com/docs/opensearch/) | Keep OpenSearch API actions under your control. | Limit index access, inspect REST requests, and review administrative changes by default. |
| [Oracle](https://gaterook.com/docs/oracle/) | Control AI access to Oracle schemas and SQL changes. | Use restricted grants and verified transport with bounded reads and reviewed writes by default. |
| [PostgreSQL](https://gaterook.com/docs/postgresql/) | Protect PostgreSQL access with an MCP Firewall. | Scope schemas and tables, bound queries, and review exact changes before execution by default. |
| [Redis](https://gaterook.com/docs/redis/) | Limit which Redis keys AI can read. | Configure allowed key patterns and bounded read tools with credential protection and audit. |
| [SQL Server](https://gaterook.com/docs/sql-server/) | Scope SQL Server reads. Review permitted writes. | Use SELECT-only grants for read access, verified TLS, and exact-action approval for writes by default. |
| [SSH log files](https://gaterook.com/docs/ssh-logs/) | Expose selected SSH log files with controlled reads. | Select individual files, verify the host key, and bound the records returned to AI clients. |
| [SSH](https://gaterook.com/docs/ssh/) | Keep AI server operations under explicit control. | Verify the host, restrict runbooks and commands, and review changes by default. |
| [Articles](https://gaterook.com/articles/) | Practical security for AI access to infrastructure. | Explore connector trust, production boundaries, and reviewed execution through an MCP Firewall. |
| [PostgreSQL comparison](https://gaterook.com/articles/gaterook-vs-postgres-mcp-pro/) | GateRook vs Postgres MCP Pro: PostgreSQL access controls. | Compare the access model and documented controls; retain dated evidence and avoid unsupported superiority claims. |
| [MCP security article](https://gaterook.com/articles/why-gaterook-is-safer-than-raw-mcp-servers/) | How GateRook adds an MCP Firewall to AI workflows. | Explain credential isolation, scoped tools, exact-action approvals, and audit, alongside the remaining trust boundaries. |
| [Release notes](https://gaterook.com/release-notes/) | GateRook MCP Firewall release notes. | Follow changes to connector controls, approvals, credential protection, and the desktop application. |
| [Privacy](https://gaterook.com/privacy/) | Privacy Policy | Use “desktop MCP Firewall” in the factual product description. Preserve the policy's data-handling statements. |
| [Terms](https://gaterook.com/terms/) | Terms of Use | Preserve the legal terms; product-category wording does not add a security warranty. |
| [FairVerdict](https://gaterook.com/fairverdict/) | A fairer verdict. A clearer choice. | Preserve this separate product's existing positioning. |

## Shared page copy and metadata

Connector pages use **“[Connector] · MCP Firewall integration”** as an eyebrow and **“[Connector] MCP Firewall: [specific access boundary] | GateRook”** as a title pattern. Their section headings should describe the task: **“Set the access boundary”**, **“Configure credentials and tool policies”**, **“Review actions and inspect Activity”**, and **“Security boundaries and limitations.”** The closing CTA is **“Put [Connector] access behind GateRook.”** For read-only connectors, review means inspecting activity or choosing Approval mode, not a promise of write capability.

Use the same product category in navigation descriptions, integration cards, diagram captions and alt text, page titles, meta descriptions, Open Graph and Twitter descriptions, and structured-data descriptions. Keep the application name **GateRook** and all canonical URLs stable. The homepage's **Explore security controls** link should lead to its security sections or a published security guide.

## Precise security language

| Use | Scope to retain alongside the claim |
| --- | --- |
| **MCP Firewall for AI access to production** | Enforces calls routed through GateRook; alternate access paths and the local OS account remain outside its control. |
| **Encrypted credentials** | AES-256-GCM protects locally stored secret values. This is not a claim that the entire database or every transport is encrypted. |
| **Exact-action approvals** | Changes require approval by default. Permanent Bypass is explicit and can be enabled on standard production endpoints; temporary bypass is DEV/STAGE only. |
| **Append-only, tamper-evident audit logs** | Retention can prune eligible records. Integrity checks detect changes within the documented trust model; the local store is not universally immutable or WORM storage. |
| **Control every connected tool** | Scope is the tools exposed through GateRook. Upstream permissions and connector-specific limitations still apply. |
| **Credential masking** | Defense in depth, with known transformation and short-value limits. Allowed business data can still reach an AI provider. |
| **Pause AI access** | Stops new access; operations already executing are not cancelled. |

Avoid unsupported ranking, absolute security guarantees, “every action needs approval,” “all data stays local,” and unqualified “immutable logs.” Keep historical research, third-party terminology, Java packages, `gateway.*` settings, `GW_*` variables, persisted paths, and protocol identifiers intact.
