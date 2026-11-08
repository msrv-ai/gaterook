# Put external MCP servers behind the MCP Firewall

GateRook's MCP Firewall applies per-tool modes, approvals, and audit to an MCP server you already use: a command it starts on this computer, or a server at a URL. Its tools are published under the server's prefix (`linear_list_issues`) and stay Off until you choose their endpoints. On selected endpoints, declared read-only tools start in Read only; other tools start in Approval, or Off on endpoints that only permit reads. Stored header secrets and upstream OAuth tokens are encrypted, and GateRook supplies them to the server without handing them to the AI client. See [the security model](security.md) for protection boundaries and [the architecture](ARCHITECTURE.md#76-remote-mcp-server-sign-in) for protocol details.

You choose the endpoints and tools an AI client can use. Approval mode holds a call for review; explicit Bypass runs it without human approval under the effective policy limits. MCP tools declared read only can use **Read only** without separate per-tool confirmation; their annotations remain labeled **Declared by …, not verified**. A server's description or `readOnlyHint` is not proof of safe behavior. Plugin tools retain their separate confirmation tied to the current definition and version. Review the server and its permissions as well as the exposed tools; GateRook's controls govern calls routed through it.

## Add a server

Open **Connectors › MCP servers › Add MCP server**; **Connections › Add connection › External MCP server** leads there too. The dialog has two tabs: **Manual entry** and [**Official MCP Registry**](#find-a-server-in-the-mcp-registry). On **Manual entry**, fill in the fields, or open **Paste a configuration from the server’s docs** and paste what the server's documentation gives for an AI app:

| What you paste | Example |
| --- | --- |
| Claude Desktop, Cursor or Windsurf JSON | `{"mcpServers": {"linear": {"url": "https://mcp.linear.app/mcp"}}}` |
| VS Code JSON | `{"servers": {"github": {"type": "http", "url": "https://api.githubcopilot.com/mcp/"}}}` |
| Codex TOML | `[mcp_servers.notion]` with `url = "https://mcp.notion.com/mcp"` |
| A command | `claude mcp add --transport http sentry https://mcp.sentry.dev/mcp`, `codex mcp add linear --url …` |
| The URL | `https://mcp.stripe.com` |

Only names of headers and variables are taken from a paste. The review asks for each value and stores it encrypted as a secret of the server. A command that runs `mcp-remote` for a URL becomes that URL, because GateRook signs in itself. A command that runs the MCP Proxy for AWS becomes its URL signed with AWS credentials, with the proxy's profile and region. A Google server pasted with a token in `Authorization` signs in with Google Cloud credentials instead, billed to its `x-goog-user-project`. An address ending in `/sse` stays as it is: GateRook connects there over Streamable HTTP when the server takes it, else over the older SSE transport. If the server also lists an address ending in `/mcp`, use that one. With a URL and no name, the name follows the host: `mcp.linear.app` becomes `linear-mcp`, and a Google or AWS server is named after its product, such as `bigtableadmin-mcp`.

### Find a server in the MCP registry

Open the **Official MCP Registry** tab and type at least three characters of a server's name, such as `linear`. GateRook searches the [Official MCP Registry](https://registry.modelcontextprotocol.io), where publishers list their public servers, and shows the latest version of each match. General results load 20 at a time; known official matches are also looked up by their exact names so they can appear on the first page. **Use** fills **Manual entry** as a pasted configuration does and returns to it, where **Review and connect** or **Review and start** continues:

- A server at a URL gets its Streamable HTTP address, else its SSE address, and the names of the headers it needs.
- An npm package gets `npx -y <package>@<version>`, a PyPI package `uvx --from <package>==<version> <package>`, both pinned to the version the registry names, and the names of the variables the server needs. A package that the registry lists only as a Docker image, an MCP bundle or a NuGet package can't be added from the search; paste the configuration from its docs instead.
- Parts that only you know stay visible: `{tenant_id}` in a URL, `<workspace>` in a command. **Review and connect** waits until you replace them.

The **Official** badge identifies a known vendor-published server and puts it before other results. GateRook maintains a conservative list of exact registry names with vendor documentation as evidence, including Stripe, Linear, Sentry, GitHub, MongoDB, Notion, Atlassian, and PayPal. For example, `com.stripe/mcp` is Stripe's own entry; a third-party integration with “Stripe” in its title does not receive this badge. An unmarked server may still be official if it has not been added to this list. These badges apply only to results from the Official MCP Registry.

The registry checks who published a name: a domain such as `app.linear` belongs to the publisher of `linear.app`, and `io.github.<owner>` to that GitHub account. It doesn't check what a server does, and anyone can publish one. The search only fills the form; nothing runs or connects before the review, and the review shows the exact command or URL. The registry gets your search text and any supplementary exact-name lookups, through the same guarded connection as the catalog. A build can search another registry with the same API by setting `gateway.mcp-registry.url`; an empty value turns the search off.

**Review and connect** sends one request to the server without a sign-in and shows what it found:

- **Sign-in with OAuth.** You sign in in your browser, as in Claude Desktop, Cursor or Codex.
- **Optional sign-in.** The server also answers without one, with less access. If you connect without signing in, you can sign in later from the server's row.
- **No sign-in.** Connect directly.
- **It wants a key.** It refused the request without offering a sign-in. Select **Back**, then choose **Bearer token** in **Sign-in options**, or add the custom header the server requires.

Some servers take their key in the URL, as their docs give it: `https://mcp.example.com/<key>/mcp` or `…/mcp?apiKey=<key>`. Such a server still connects, but the review warns you. GateRook stores the URL with the server's settings, not encrypted as a secret, and the provider or a proxy on the way may log it. If the server also accepts the key in a header, add that header instead. The review shows the URL with `‹masked›` in place of the key, and the lists show it without the key. Agents never see the URL.

For a server on Google Cloud or AWS, the review also offers to sign in with that cloud's credentials. See [Servers on Google Cloud](#servers-on-google-cloud) and [Servers on AWS](#servers-on-aws).

Adding a server ends as adding a connection does: GateRook offers the endpoints its tools may join, the usual one chosen. Tools declared read only (`readOnlyHint: true`, without `destructiveHint: true`) start as **Read only**. Other tools start as **Approval**, or **Off** where the endpoint only permits reads. **Not now** leaves the tools Off until you add the server to an endpoint later.

To change all of a server's tools together, open its connection and use **Set all to…** in **Used by**. Choose an endpoint, or all endpoints, and select Off, Read only, Approval, Bypass or Restore defaults. Read only skips tools that are not declared read only; endpoint restrictions still apply. Restore defaults applies the read-only/approval defaults to existing tools. Refreshing a server preserves saved modes; newly discovered read-only tools start as Read only, while other newly discovered tools stay Off until enabled. A tool that stops declaring read-only behavior can no longer run in Read only.

Read-only annotations come from the server and remain labeled **Declared by …, not verified**. GateRook does not infer read-only behavior from a tool's name. Plugin tools keep their separate per-tool read-only confirmation.

The server is then listed in **Connections** with the other connections, where you test, edit or delete it. In **Connectors › MCP servers** you can also restart it, sign in to it again or sign out.

Editing a server checks its sign-in as adding it does. Its tool prefix stays the one it was added with, because endpoints and agents know its tools by it: to use another prefix, add the server again. A cloud key (a service account key or a secret access key) can be replaced there but not added: to switch to a key, add the server again with it.

### Connect with a Bearer token

For an HTTP server, **Sign-in options** appears directly below the URL, with the authentication choices always visible. Choose **Bearer token**, then **Review and connect**. Enter only the token in the review: GateRook adds the `Authorization` header and the `Bearer ` prefix automatically. An existing `Bearer ` prefix is accepted without adding it twice. The token is stored encrypted when you connect and never shown again.

You can still add other headers. If you already named an `Authorization` header, **Bearer token** uses that header rather than adding a duplicate. For another authentication scheme, leave **Bearer token** unselected, keep the custom header and enter its complete value in the review. **Back** preserves the setup fields; secret values must be entered again.

## What a server shares besides tools

The review says what a server offers besides tools: resources, prompts, and the MCP Apps its tools show. Its tools are published as described above. The rest reaches agents as follows:

- **Resources and prompts.** In the server's settings, **Share with agents** lets it share its resources and resource templates, its prompts and their completions, or both. Both are off by default. AI apps then list them next to GateRook's own: resources under `mcpgw://connections/<server>/mcp/…`, prompts with the server's prefix. Reading one runs without approval, through the endpoint's policy, masked like a result and recorded in **Activity**. A paused server or endpoint shares nothing.
- **MCP Apps.** When a tool that is on shows an app, AI apps that render MCP Apps show it through GateRook, whether or not the server shares its resources. The tool's review pins which app it shows.
- **Images, audio and files.** Results keep them. GateRook can't mask pixels: it withholds a block whose bytes contain a secret it handed to the server, and one that doesn't fit the result's size limit, with a note in its place.
- **Progress, log messages and cancelling.** When the AI app asks for them, it hears a call's progress and the server's log messages while the call runs, masked. Stopping a call in the AI app stops it at the server.
- **Changes and updates.** AI apps that listen hear when the server's resources or prompts change, and get updates of the resources they subscribed to.
- **Tasks.** A tool that the server runs as a task runs to its end within the call's timeout. AI apps that use tasks can run a call through GateRook as a task; one that waits for approval then waits for your decision.

GateRook speaks the MCP revisions `2026-07-28` and `2025-11-25` back to `2024-11-05`, the newest the server speaks, over stdio, Streamable HTTP or the older SSE transport. It gives a server no access to the AI app: a server that asks for its model, its files or your input gets an error.

## Sign in

Select **Sign in** in the review. GateRook opens the server's sign-in page in your default browser and listens on this computer, at `http://localhost:<free port>/callback`, for the browser to come back. After you allow access, the page says you are signed in and GateRook returns to the front. Then select **Connect MCP server**.

The provider's consent page names GateRook. Where the provider accepts client metadata documents (in November 2026, Linear, Notion and Sentry among others), GateRook doesn't register with it. The provider reads the details GateRook publishes at `https://msrv.dev/oauth/gaterook-client.json`: its name, that it has no client secret, and that the browser comes back to this computer. Other servers get a registration that GateRook makes under its own name.

The sign-in belongs to GateRook, not to the AI app. GateRook keeps it encrypted as a secret of the server and renews it before it expires. Every call of the server carries it, and no agent, AI app, log or answer receives it. Any of your AI apps can use the server's tools through an endpoint, under the endpoint's modes and approvals.

If the browser can't reach this computer, for example because it runs on another device, open **The browser can’t reach GateRook?** while the sign-in waits. Paste the whole address the browser ended on. It must belong to the same sign-in.

The server's row shows **Signed in**, **Needs sign-in** or **Signed out**. A server needs a new sign-in when its provider refuses to renew the sign-in (it was revoked, or it expired) or asks for more permissions. Its calls don't run meanwhile, and they say where to sign in. Use **Sign in** in the row, or **Sign in again** in its menu. **Sign out** revokes the sign-in where the provider allows that and forgets it. The tools and their modes stay either way.

## Servers that need your own OAuth app

Some servers neither accept GateRook's published details nor let apps register themselves. At the time of writing (November 2026) these include GitHub's, Slack's and Asana's v2 server. The review then says so and shows the redirect URI to register, `http://127.0.0.1:7471/callback`:

1. Create an OAuth app for GateRook in the provider's developer settings, with that redirect URI.
2. In **Add MCP server › Sign-in options › OAuth app options**, enter its client ID, or enter it in the review.
3. If the app has a client secret, enter it in the review. It is stored as a write-only secret of the server.

**OAuth app options** also set the **Scopes** to ask for, and a **Callback port** for a server or an app that accepts only one redirect address.

## Servers on Google Cloud

Google's MCP servers, such as `https://bigtableadmin.googleapis.com/mcp` or `https://bigquery.googleapis.com/mcp`, don't let apps register themselves or read client metadata documents. Instead of an OAuth app, choose **Sign-in options › Sign in with › Google Cloud**, or **Use Google Cloud credentials** in the review. GateRook then gets a Google access token itself and sends it with every request. It renews the token before it expires, and no agent or log sees it.

- **Google sign-in on this computer**: what `gcloud auth application-default login` set up. The server's tools act as you.
- **Service account key (JSON)**: the review asks for it. It is stored encrypted as a secret of the server.
- **Act as a service account**: a token of that account for 15 minutes, with only its roles. Google recommends a separate identity for agents.
- **Billed project**: sent as `x-goog-user-project`.
- **Scopes**: `cloud-platform` by default, which every Google Cloud MCP server takes.

The account needs the role `roles/mcp.toolUser` on the project and the roles of the product, and the product's API must be enabled. Google's servers list their tools without a sign-in, so a missing role shows only when a tool is called. The call then says that the server refused GateRook's Google Cloud credentials, and quotes Google's message.

To sign in in the browser instead, create an OAuth client of the **Desktop app** type in Google Cloud Console (**APIs & Services › Credentials**). Enter its client ID and client secret: Google requires the secret. A Desktop app client takes any loopback address, so there is no redirect URI to register.

## Servers on AWS

The AWS MCP Server (`https://aws-mcp.<region>.api.aws/mcp`) offers a browser sign-in that GateRook can make: the review finds it, and GateRook registers itself. AWS renews that sign-in for at most 12 hours, and your IAM principal needs the managed policy `AWSMCPSignInOAuthAccessPolicy`.

Choose **Sign-in options › Sign in with › AWS**, or **Use AWS credentials** in the review, to sign every request with SigV4 instead, as the MCP Proxy for AWS does. Nothing expires after 12 hours, and nothing runs besides GateRook.

- **A profile on this computer**: from `~/.aws/credentials` and `~/.aws/config`. Profiles that use AWS IAM Identity Center (SSO) can't be used yet.
- **An access key**: the review asks for its secret. It is stored encrypted as a secret of the server.
- **Assume a role**: short-lived credentials of that role, with an optional external ID, renewed by GateRook. Its policy decides what the server may do in AWS.
- **Service** and **Region**: what requests are signed for. By default they come from the URL: `aws-mcp.us-east-1.api.aws` is `aws-mcp` in `us-east-1`. For another host, such as a Bedrock AgentCore gateway, enter both.

## Servers that admit only approved apps

A few providers admit only clients on their own allowlist, by name or by redirect address. GateRook signs in under its own name and never presents another application's client ID, name or redirect address. Such a server refuses the registration, and the review says so. Use the provider's API key in a header if it offers one, or ask the provider to admit GateRook.

## Troubleshooting

| Message | What to do |
| --- | --- |
| A private address | A server on this computer or your network needs its range in **Policies › HTTP: allowed private ranges** for the environment. |
| Can't listen on port … | Another program uses the callback port. Choose another one in **Sign-in options**, or leave it empty for any free port. |
| It answered HTTP 404 or 405 | Check the URL. Streamable HTTP addresses usually end in `/mcp`, SSE addresses in `/sse`. |
| The provider's page says the client or its redirect address is invalid | The provider couldn't use the details GateRook publishes. Cancel the sign-in in GateRook and try again later. If it keeps happening and the provider lets you create an OAuth app, enter that app's client ID in **Sign-in options**. |
| Needs you to sign in again | Sign in from the server's row. |
| Refused its renewed sign-in | The provider took a new token back at once. Sign in again if it keeps happening. |
| Refused GateRook's Google Cloud credentials | The account lacks `roles/mcp.toolUser` or the product's roles, or the API isn't enabled. Google's message names what is missing. |
| No Google sign-in on this computer | Run `gcloud auth application-default login`, or use a service account key. |
| Refused GateRook's AWS credentials | The principal or role may not call the server, or this computer's clock is off by more than 5 minutes. AWS's message says which. |
| Name the AWS service and region to sign for | The URL's host doesn't name them. Enter them in **Sign-in options**, such as `bedrock-agentcore` and `us-west-2`. |
