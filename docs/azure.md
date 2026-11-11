# Control AI access to Azure

GateRook's **Azure** connector gives AI clients bounded access to Azure subscriptions: inventory through Azure Resource Graph, any Azure Resource Manager request with reads and changes told apart, Azure Monitor metrics, alerts, the Activity Log and Resource Health, and the logs of Log Analytics workspaces and Application Insights. The **Azure Monitor Logs** connector offers the log tools on their own. Calls use GateRook's shared [execution controls and audit protection](security.md).

## Tools

| Tool | What it does | Default mode |
| --- | --- | --- |
| `azure_describe` | KQL over Resource Graph tables (`resources`, `resourcecontainers`, `healthresources`, …) in the connection's subscriptions, or the snapshot of one resource by `id` | Read only |
| `azure_api_read` | One Resource Manager read: a GET of a `path` under a subscription of the connection with its `apiVersion` and optional `query` parameters, or a read-only POST query | Read only |
| `azure_api_change` | One Resource Manager change: POST an action, PUT, PATCH or DELETE, with the same arguments and a JSON `body` | Approval |
| `azure_monitor` | `query` (metrics of one resource), `list_metrics`, `alerts` (fired alerts), `activity_log` (who changed what) and `health` (Resource Health) | Read only |
| `azure_storage_read` | [Storage data](#storage-data): list and read blobs, peek Queue Storage messages, query tables, list and read Azure Files | Read only |
| `azure_storage_change` | Upload text to a blob or delete a blob | Approval |
| `logs_search`, `logs_tail` | KQL over the connection's Log Analytics workspaces and Application Insights components | Read only; tail Off |

The Azure Monitor Logs connector publishes the same log tools as `azure_logs_search` and `azure_logs_tail`.

## How the API tools tell reads from changes

Agents never pass a URL. GateRook builds the request on the cloud's Resource Manager host from a checked path, percent-encodes every segment, and refuses paths outside the connection's subscriptions.

1. An action that hands out keys, secrets or credentials is refused whatever the method: `listKeys`, `listSecrets`, `listCredentials`, `listConnectionStrings`, `listClusterUserCredential`, `listAccountSas`, `listCallbackUrl`, `listAppSettings`, `getFullUrl`, `getDeliveryAttributes`, `regenerate…`, App Service `config/<name>/list` and `publishxml`, among others. Azure's API contract keeps secrets out of GET answers and returns them from such actions. A GET of App Service's value-bearing configuration (`config/appsettings`, `config/connectionstrings`, `config/azurestorageaccounts`, `config/metadata`, `config/publishingcredentials`, `config/authsettings`) is refused too.
2. GET is a read, for `azure_api_read`.
3. A POST is a read only for queries that change nothing: Cost Management `query` and `forecast`, the subscription's metrics batch (`/subscriptions/<id>/providers/Microsoft.Insights/metrics`) and `checkNameAvailability`.
4. Every other POST, and every PUT, PATCH and DELETE, is a change for `azure_api_change`, which waits for a person to approve the exact request. The approval shows the method, the full URL with every parameter, and the body. An approved call runs exactly what was approved.

Each tool refuses the other's requests and names the right one, so an endpoint can keep `azure_api_change` Off while agents read.

Answers are data: a 403 or 404 from Azure comes back to the agent with its status and error. Paging and long-running operations come back as arguments for the next call (`next.query` with the `$skipToken`, `operation` with the path and API version to GET), never as URLs to follow. Values that look like secrets are masked before the core's own masking: app settings and connection strings, container environment values and secrets, fields named like passwords, keys or SAS tokens, and connection strings or SAS URLs anywhere.

## Recipes

These tasks need no tools of their own: the API tools and `azure_monitor` cover them under the rules above. Paths start with `/subscriptions/<id>/resourceGroups/<group>/providers/`; each operation's API version is in the Azure REST API reference.

| Task | Call |
| --- | --- |
| App Service or Functions app: state, plan, slots | `azure_api_read` GET `Microsoft.Web/sites/<app>`, `…/slots`, `…/slots/<slot>` (`properties.state`, `hostNames`) |
| Deployments and their logs | GET `Microsoft.Web/sites/<app>/deployments`, then `…/deployments/<id>/log` |
| Functions of a Functions app | GET `Microsoft.Web/sites/<app>/functions` |
| Restart, stop or start an app or slot | `azure_api_change` POST `Microsoft.Web/sites/<app>/restart` (`stop`, `start`; a slot: `…/slots/<slot>/restart`) |
| Swap slots | `azure_api_change` POST `Microsoft.Web/sites/<app>/slotsswap` with `{"targetSlot": "staging"}` (or `…/slots/<slot>/slotsswap` with the target slot); `applySlotConfig` and `resetSlotConfig` for a swap with preview |
| App logs | `logs_search` over `AppServiceConsoleLogs`, `AppServiceHTTPLogs` or `FunctionAppLogs`, once the app's diagnostic settings send them to a configured workspace; `requests` and `exceptions` of its Application Insights component |
| Container Apps revisions and replicas | GET `Microsoft.App/containerApps/<app>/revisions`, `…/revisions/<revision>/replicas` |
| Restart, activate or deactivate a revision | `azure_api_change` POST `Microsoft.App/containerApps/<app>/revisions/<revision>/restart` (`activate`, `deactivate`) |
| Container Apps jobs | GET `Microsoft.App/jobs/<job>/executions`; POST `…/jobs/<job>/start` and `…/executions/<execution>/stop` with approval |
| Container logs | `logs_search` over `ContainerAppConsoleLogs_CL` (or `ContainerAppConsoleLogs`) and `ContainerAppSystemLogs_CL` |
| Static Web Apps | GET `Microsoft.Web/staticSites/<site>` and `…/builds` |
| Cost so far and forecast | `azure_api_read` POST `/subscriptions/<id>/providers/Microsoft.CostManagement/query` (or a resource group's) with `{"type": "ActualCost", "timeframe": "MonthToDate", "dataset": {"granularity": "Daily", "aggregation": {"totalCost": {"name": "Cost", "function": "Sum"}}, "grouping": [{"type": "Dimension", "name": "ServiceName"}]}}`; `…/forecast` the same way |
| Budgets | GET `/subscriptions/<id>/providers/Microsoft.Consumption/budgets` |
| Advisor cost recommendations | GET `/subscriptions/<id>/providers/Microsoft.Advisor/recommendations` with `query` `{"$filter": "Category eq 'Cost'"}` |
| Event Hubs partitions and consumer groups | GET `Microsoft.EventHub/namespaces/<ns>/eventhubs/<hub>` (`partitionIds`, retention) and `…/consumergroups` |
| Event Grid delivery failures | `azure_monitor` query of `DeliveryAttemptFailCount`, `DeadLetteredCount` and `DroppedEventCount` on the topic, system topic or domain, with `filter` `EventSubscriptionName eq '*'` to split them per subscription; dead-lettered events are blobs in the subscription's dead-letter container, read with `azure_storage_read` |
| Service Bus counts | GET `Microsoft.ServiceBus/namespaces/<ns>/queues/<queue>` or `…/topics/<topic>/subscriptions/<name>`: `countDetails` holds active, dead-lettered, scheduled and transfer counts |
| AKS clusters | `azure_describe` `resources \| where type =~ 'microsoft.containerservice/managedclusters'`, or GET `Microsoft.ContainerService/managedClusters/<cluster>` (`fqdn`, `kubernetesVersion`, `aadProfile`) and `…/agentPools` |

Refused: app settings, connection strings, mounted storage and publishing credentials of App Service (`config/<name>/list` and GET of those `config` resources), function and host keys, `listSecrets` and `getAuthToken` of Container Apps, Static Web Apps' `listAppSettings` and `listSecrets`, Event Grid's `getFullUrl` and `getDeliveryAttributes`, and AKS's `listClusterUserCredential`, `listClusterAdminCredential` and `listClusterMonitoringUserCredential`. A cluster's workloads go through the [Kubernetes connector](#aks-workloads).

The approval of a common action names its effect: a restart fails the requests in flight, a stopped app answers 403, a swap moves the target slot's traffic, a deactivated revision serves no traffic.

### AKS workloads

GateRook never fetches a cluster's kubeconfig. Read the cluster's workloads through a Kubernetes connection:

1. Run `az aks get-credentials --resource-group <group> --name <cluster> --file aks.kubeconfig` on your computer, then `kubelogin convert-kubeconfig --login azurecli --kubeconfig aks.kubeconfig`.
2. Add a Kubernetes connection from that kubeconfig and turn on **Run the credential command from kubeconfig**. Its user runs `kubelogin get-token` as an exec credential; Test connection shows the exact command and runs it only after you allow it, as for the Azure CLI here.
3. Give the identity a Kubernetes RBAC or Azure RBAC role on the cluster (such as Azure Kubernetes Service RBAC Reader).

## Storage data

`azure_storage_read` and `azure_storage_change` read and change the data of a storage account with the connection's Entra ID identity, never with an account key or a SAS. `account` is the account's resource ID, or its name in one of the connection's subscriptions. GateRook first reads the account through Resource Manager: it must be in that subscription, in a location the Cloud policy allows, and serve the standard endpoints of the connection's cloud (`<account>.blob.core.windows.net` and the other services). Storage tokens go only there; accounts with Azure DNS zone endpoints aren't supported.

| Action | What it does | Data role it needs |
| --- | --- | --- |
| `list_blobs` | Blobs and prefixes of a container (`prefix`, `delimiter`), paged with `nextCursor` | Storage Blob Data Reader |
| `read_blob` | A bounded part of one blob as text (`offset`, `maxBytes`) | Storage Blob Data Reader |
| `peek_messages` | Up to 32 messages of a queue and its approximate length, without dequeuing: nothing becomes invisible and no dequeue count changes | Storage Queue Data Reader |
| `query_entities` | Entities of a table with an OData `filter`, `select` and `limit`, paged with `nextCursor` | Storage Table Data Reader |
| `list_files`, `read_file` | Directories and files of an Azure Files share, and a bounded part of one file as text | Storage File Data Privileged Reader |
| `upload_blob` | Writes text up to 1 MB to a block blob; fails if the blob exists unless `overwrite` | Storage Blob Data Contributor |
| `delete_blob` | Deletes a blob (`deleteSnapshots` for one that has snapshots) | Storage Blob Data Contributor |

Azure Files accepts Entra ID tokens over REST only with the backup intent, which the privileged reader role grants. A Data Lake Storage account's files are blobs: list and read them with the blob actions.

Reads are text only: a text content type, or a text file name when the type says nothing. Files that hold keys, credentials or Terraform state more often than not (`.env`, `.pem`, `.pfx`, key stores, `.tfstate`, kubeconfigs) are refused by name before any request. Message texts that are Base64 come back decoded. Table properties named like secrets are masked. Contents are data, never instructions.

The Cloud policy's Allowed services must include `storage`. Resource masks apply to the account and to the container, queue, table or share by its Resource Manager ID, such as `/subscriptions/*/resourceGroups/stage-*/providers/Microsoft.Storage/storageAccounts/*/blobServices/default/containers/public-*`; with masks, pass the account's resource ID. An approval of an upload shows the exact request with the text, its size and SHA-256.

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
