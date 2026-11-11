# Control AI access to Azure

GateRook's **Azure** connector gives AI clients bounded access to Azure subscriptions: inventory through Azure Resource Graph, any Azure Resource Manager request with reads and changes told apart, Azure Monitor metrics, alerts, the Activity Log and Resource Health, and the logs of Log Analytics workspaces and Application Insights. The **Azure Monitor Logs** connector offers the log tools on their own. Calls use GateRook's shared [execution controls and audit protection](security.md).

## Tools

| Tool | What it does | Default mode |
| --- | --- | --- |
| `azure_describe` | KQL over Resource Graph tables (`resources`, `resourcecontainers`, `healthresources`, …) in the connection's subscriptions, or the snapshot of one resource by `id` | Read only |
| `azure_api_read` | One Resource Manager read: a GET of a `path` under a subscription of the connection with its `apiVersion` and optional `query` parameters, or a read-only POST query | Read only |
| `azure_api_change` | One Resource Manager change: POST an action, PUT, PATCH or DELETE, with the same arguments and a JSON `body` | Approval |
| `azure_monitor` | `query` (metrics of one resource), `list_metrics`, `alerts` (fired alerts), `activity_log` (who changed what) and `health` (Resource Health) | Read only |
| `logs_search`, `logs_tail` | KQL over the connection's Log Analytics workspaces and Application Insights components | Read only; tail Off |

The Azure Monitor Logs connector publishes the same log tools as `azure_logs_search` and `azure_logs_tail`.

## How the API tools tell reads from changes

Agents never pass a URL. GateRook builds the request on the cloud's Resource Manager host from a checked path, percent-encodes every segment, and refuses paths outside the connection's subscriptions.

1. An action that hands out keys, secrets or credentials is refused whatever the method: `listKeys`, `listSecrets`, `listCredentials`, `listConnectionStrings`, `listClusterUserCredential`, `listAccountSas`, `listCallbackUrl`, `regenerate…`, App Service `config/<name>/list` and `publishxml`, among others. Azure's API contract keeps secrets out of GET answers and returns them from such actions.
2. GET is a read, for `azure_api_read`.
3. A POST is a read only for queries that change nothing: Cost Management `query` and `forecast`, the subscription's metrics batch (`/subscriptions/<id>/providers/Microsoft.Insights/metrics`) and `checkNameAvailability`.
4. Every other POST, and every PUT, PATCH and DELETE, is a change for `azure_api_change`, which waits for a person to approve the exact request. The approval shows the method, the full URL with every parameter, and the body. An approved call runs exactly what was approved.

Each tool refuses the other's requests and names the right one, so an endpoint can keep `azure_api_change` Off while agents read.

Answers are data: a 403 or 404 from Azure comes back to the agent with its status and error. Paging and long-running operations come back as arguments for the next call (`next.query` with the `$skipToken`, `operation` with the path and API version to GET), never as URLs to follow. Values that look like secrets are masked before the core's own masking: app settings and connection strings, container environment values and secrets, fields named like passwords, keys or SAS tokens, and connection strings or SAS URLs anywhere.

## Sign-in

Choose the credentials under **Credentials**:

- **Azure CLI sign-in on this computer.** GateRook runs `az account get-access-token` for each audience it needs. Test connection shows the exact command and runs nothing until you choose **Allow the command and test again**; a changed command, program or tenant asks again. Run `az login` first. Without a tenant ID the CLI's default tenant is used.
- **Sign in with a code in the browser.** Test connection shows a code; open the sign-in page, enter it and sign in, then choose **I signed in, test again**. The sign-in uses Azure CLI's public client unless you enter your organization's own application (client) ID. The sign-in lasts until GateRook restarts; then test the connection again.
- **Service principal with a certificate** (recommended for production) or **with a client secret.** Paste the PEM with the RSA private key and its certificate, as `az ad sp create-for-rbac --create-cert` writes it, or the client secret's value.

GateRook never uses managed identity, environment variables or other programs' token caches. It asks Entra ID for one token per audience (Resource Manager, Log Analytics) and keeps tokens in memory only.

**Tenant and subscriptions.** Enter the subscription IDs agents may read. Test connection checks that each one is visible to the identity, enabled and in the token's tenant, and records the tenant. Every later token must belong to that tenant (or to the configured tenant ID); a sign-in of another tenant is refused until you test again.

**Clouds.** Choose **Azure**, **Azure Government** or **Azure China (21Vianet)**. Sign-in, Resource Manager and Log Analytics then use that cloud's own endpoints, and tokens are sent only there.

## Permissions

Assign **Reader** on the subscriptions (or resource groups) and **Log Analytics Reader** on the workspaces. Test connection lists what the identity may do on each subscription and warns, on production with emphasis, when it can change resources: Reader is enough, because GateRook's changes wait for approval anyway.

## Cloud policy

The environment's **Cloud** policy applies to Azure as to AWS and GCP:

- **Allowed services** are resource provider namespaces without `Microsoft.`: `compute`, `web` (also `appservice`, `functions`), `sql`, `storage`, `containerservice` (also `aks`), `insights` (also `monitor`), `resources` for resource groups. Key Vault (`keyvault`) and role assignments (`authorization`) are hidden unless the list names them.
- **Regions** are Azure locations such as `westeurope` or `eastus2`; `global` only when listed.
- **Resources** are resource ID masks such as `/subscriptions/*/resourceGroups/stage-*`, compared without case; a mask that matches a resource also covers its configuration and actions.

Resource Graph rows and Resource Manager answers are checked against the policy and hidden when they don't match. When the policy restricts services, regions or resources, every Resource Graph row must carry `id` and `type` (and `location` when regions are restricted): project them, because an aggregate can't be checked. Alerts, Activity Log events and health statuses carry no location, so only services and resource masks narrow them. The log tools of the Azure connector are refused under such a policy; use an Azure Monitor Logs connection whose workspaces hold only the permitted logs.

## Logs

Queries are KQL and start with a table: `AppExceptions | where SeverityLevel >= 3`. A workspace's rows need `TimeGenerated`, an Application Insights component's rows `timestamp`; GateRook adds the time range and `top` to return the newest entries first. A query that aggregates (`summarize`, `count`, …) answers as a table. Several workspaces of one connection are queried together; components are queried by their resource ID.

A query may read only the connection's sources. GateRook refuses `workspace()`, `app()`, `resource()`, `adx()`, `arg()`, Data Explorer's `cluster()` and `database()`, `externaldata`, plugins that call other services, and control commands. Pick one configured source with `source` instead. A tail reads entries about a minute behind now; records that arrive later appear in searches.

## Limits

`azure_monitor` caps the time range at the Cloud policy's **Longest time range** (and at what Azure keeps: 30 days of alerts, 90 days of Activity Log), the metric data points at **Data points per call** (a coarser interval is chosen when needed), and items at **Items per call**. GateRook spends only a share of Resource Manager, Resource Graph, Azure Monitor and Log Analytics request limits, which people and other tools share.
