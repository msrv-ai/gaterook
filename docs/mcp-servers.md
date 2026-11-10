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

The **MCP servers** page includes **Popular for developers**: 20 cards with local logos, categories and short descriptions. The selection balances public repository interest, package downloads and common development workflows; it is a curated selection, not an installation ranking. The cards remain available alongside connected servers, and **Add MCP server** opens an empty form for any other server.

Use **Search MCP Registry** directly on the page to search beyond these picks. Results appear after at least three characters; Enter searches immediately. **Show more** loads the next page, and **Use** opens the add dialog with the selected result filled in. Clearing the search restores the popular cards. Searches use the same configured registry and official-publisher identification as the dialog.

Choose a card to open the add dialog and fill **Manual entry** from that exact server's current entry in the Official MCP Registry. GateRook checks the registry source and exact publisher name before filling the form. An unavailable, deprecated or unsupported entry shows an error with retry and manual-entry options. Nothing connects or starts until you continue through the existing review.

Open the **Official MCP Registry** tab and type at least three characters of a server's name, such as `linear`. GateRook searches the [Official MCP Registry](https://registry.modelcontextprotocol.io), where publishers list their public servers, and shows the latest version of each match. General results load 20 at a time; known official matches are also looked up by their exact names so they can appear on the first page. **Use** fills **Manual entry** as a pasted configuration does and returns to it, where **Review and connect** or **Review and start** continues:

- A server at a URL gets its Streamable HTTP address, else its SSE address, and the names of the headers it needs. An `Authorization` header that the entry doesn't mark required is left out: it is a token the server takes besides its sign-in (GitHub's entry names one for a personal access token), and the review finds the sign-in. To send a token instead, choose **Bearer token** under **Sign-in options**.
- An npm package gets `npx -y <package>@<version>`, a PyPI package `uvx --from <package>==<version> <package>`, both pinned to the version the registry names, and the names of the variables the server needs. A package that the registry lists only as a Docker image, an MCP bundle or a NuGet package can't be added from the search; paste the configuration from its docs instead.
- Parts that only you know stay visible: `{tenant_id}` in a URL, `<workspace>` in a command. **Review and connect** waits until you replace them.

The **Official** badge identifies a known vendor-published server and puts it before other results. GateRook maintains a conservative list of exact registry names with vendor documentation as evidence, including Stripe, Linear, Sentry, GitHub, MongoDB, Notion, Atlassian, and PayPal. For example, `com.stripe/mcp` is Stripe's own entry; a third-party integration with “Stripe” in its title does not receive this badge. An unmarked server may still be official if it has not been added to this list. These badges apply only to results from the Official MCP Registry.

The registry checks who published a name: a domain such as `app.linear` belongs to the publisher of `linear.app`, and `io.github.<owner>` to that GitHub account. It doesn't check what a server does, and anyone can publish one. The search only fills the form; nothing runs or connects before the review, and the review shows the exact command or URL. The registry gets your search text and any supplementary exact-name lookups, through the same guarded connection as the catalog. A build can search another registry with the same API by setting `gateway.mcp-registry.url`; an empty value turns the search off.

**Review and connect** sends one request to the server without a sign-in and shows what it found:

- **Sign-in with OAuth.** You sign in in your browser, as in Claude Desktop, Cursor or Codex. Signing in confirms the server: GateRook connects it as soon as you allow access, with no other step.
- **Optional sign-in.** The server also answers without one, with less access. Sign in to connect it with your sign-in, or confirm the review and connect without one. Without a sign-in, you can sign in later from the server's row.
- **No sign-in.** Connect directly.
- **It wants a key.** It refused the request without offering a sign-in. Select **Use a Bearer token** to enter the token in the review, or select **Back** and add the custom header the server requires.

Some servers take their key in the URL, as their docs give it: `https://mcp.example.com/<key>/mcp` or `…/mcp?apiKey=<key>`. Such a server still connects, but the review warns you. GateRook stores the URL with the server's settings, not encrypted as a secret, and the provider or a proxy on the way may log it. If the server also accepts the key in a header, add that header instead. The review shows the URL with `‹masked›` in place of the key, and the lists show it without the key. Agents never see the URL.

For a server on Google Cloud or AWS, the review also offers to sign in with that cloud's credentials. See [Servers on Google Cloud](#servers-on-google-cloud) and [Servers on AWS](#servers-on-aws).

Adding a server ends as adding a connection does: GateRook offers the endpoints its tools may join, the usual one chosen. Tools declared read only (`readOnlyHint: true`, without `destructiveHint: true`) start as **Read only**. Other tools start as **Approval**, or **Off** where the endpoint only permits reads. **Not now** leaves the tools Off until you add the server to an endpoint later.

To change all of a server's tools together, open its connection and use **Set all to…** in **Used by**. Choose an endpoint, or all endpoints, and select Off, Read only, Approval, Bypass or Restore defaults. On an endpoint's page the same menu of a server's group is **Set shown to…** while a search or a mode filter is active, and it changes only the tools the list shows. Either way only the tools you see change: a tool the server adds after the list was loaded keeps its mode, and if the server changes a tool you see while you choose Read only, Bypass or Restore defaults, GateRook changes nothing and asks you to review the tools again. Before Bypass, or before Read only or Restore defaults lets more than one of a server's declared reads run without approval, GateRook asks you to confirm and names how many tools. Read only skips tools that are not declared read only; endpoint restrictions still apply. Off and Approval also reach a tool the server isn't listing at the moment, as far as they lower its mode. Restore defaults applies the read-only/approval defaults to existing tools. Refreshing a server keeps the modes you saved, with one exception: **Read only** and **Bypass** are for the definition the tool had when you set them. A tool whose definition changed (its title, description, parameters, annotations or the app it shows) goes back to **Approval** on every endpoint where it was in either mode, or to **Off** on an endpoint that only permits reads. A temporary bypass is for the tool, the server and the endpoint as they were when you granted it: it applies while the tool keeps that definition and the server's tool list and version, the connection's settings and the endpoint's settings stay as they were. Any change to them ends it, even for a tool whose definition is unchanged: a refresh in which the server lists any change or reports another version, an edit of the connection or of the endpoint, or a change a reconcile writes to the endpoint, such as moving one of its tools back to Approval. You can grant one to a tool stored as Read only that waits in Approval while GateRook hasn't stored Approval for it yet (a tool stored as Bypass cannot be given one); when a refresh or another reconcile of the endpoint stores it, the grant ends with the mode change and **Activity** says so. The start that stores Approval for tools left without a recorded definition (see the upgrade below) keeps their temporary bypasses: each still applies until it expires or its binding changes. A tool the server lists under a new name is a new tool and starts like one. **Activity** records each change with the tool, the endpoint and the new mode, and you can set the mode again for what the tool is now. If the server changes the tool while you choose **Read only** or **Bypass** for it, GateRook refuses the change and asks you to review the tool again. Saving a tool's limits keeps its stored mode and reviews nothing, but it ends a temporary bypass of the tool: a tool that waits in Approval keeps waiting until you choose its mode. A tool that changes back is not restored: its mode is set again like any other. **Off** and **Approval** stay as you chose them. A tool the server stops listing and lists again keeps the mode you gave it, if its definition is the same, so a server can neither reset nor raise a choice by unlisting a tool; if the definition differs, the rule above applies. Newly discovered read-only tools start as Read only, while other newly discovered tools stay Off until enabled. A tool that stops declaring read-only behavior can no longer run in Read only. When GateRook upgrades from a version that did not record definitions, it records the ones in force then, so modes you set before the upgrade hold until a server changes a tool; tools it could not record (for example because the vault stayed locked during the upgrade) wait in Approval until you set them again, and the first start that can verify them stores Approval for them (Off on an endpoint that only permits reads), with one **Activity** entry that says why. A temporary bypass that an earlier version granted to a tool that shows an app (an MCP App) stops applying after the upgrade, because a grant now covers the app as well: grant it again if you still need it.

Read-only annotations come from the server and remain labeled **Declared by …, not verified**. GateRook does not infer read-only behavior from a tool's name. Plugin tools keep their separate per-tool read-only confirmation.

The server is then listed in **Connections** with the other connections, where you test, edit or delete it. In **Connectors › MCP servers** you can also restart it, sign in to it again or sign out.

Editing a server checks its sign-in as adding it does. Its tool prefix stays the one it was added with, because endpoints and agents know its tools by it: to use another prefix, add the server again. A cloud key (a service account key or a secret access key) can be replaced there but not added: to switch to a key, add the server again with it.

A server can be renamed in the same form. Its tools keep their prefix and their modes on every endpoint, so agents call them as before. A server on this computer stops, and its folder (its installed package and home) and its log move to the new name; the next call starts it there. Its resources are then listed under `mcpgw://connections/<new name>/mcp/…`. **Activity** keeps the old name for calls made before the rename.

### Connect with a Bearer token

For an HTTP server, **Sign-in options** appears directly below the URL, with the authentication choices always visible. Choose **Bearer token**, then **Review and connect**. Enter only the token in the review: GateRook adds the `Authorization` header and the `Bearer ` prefix automatically. An existing `Bearer ` prefix is accepted without adding it twice. The token is stored encrypted when you connect and never shown again.

You can still add other headers. If you already named an `Authorization` header, **Bearer token** uses that header rather than adding a duplicate. For another authentication scheme, leave **Bearer token** unselected, keep the custom header and enter its complete value in the review. **Back** preserves the setup fields; secret values must be entered again.

## What a server shares besides tools

The review says what a server offers besides tools: resources, prompts, and the MCP Apps its tools show. Its tools are published as described above. The rest reaches agents as follows:

- **Resources and prompts.** In the server's settings, **Share with agents** lets it share its resources and resource templates, its prompts and their completions, or both. Both are off by default. AI apps then list them next to GateRook's own: resources under `mcpgw://connections/<server>/mcp/…`, prompts with the server's prefix. Reading one runs without approval, through the endpoint's policy, masked like a result and recorded in **Activity**. A paused server or endpoint shares nothing.
- **MCP Apps.** When a tool that is on shows an app, AI apps that render MCP Apps show it through GateRook, whether or not the server shares its resources. The tool's review pins which app it shows. The app may call its own server's tools as the server declares; GateRook tells the AI app that every other tool of the endpoint is for the model only, so the app can't call another connection's tools (unless that connection also shows an app on the same endpoint) or GateRook's own, apart from the buttons of GateRook's approval and import cards. The AI app enforces this, and every call still needs the tool's mode or your approval. The app gets the sites its server lists for it to connect to and load from, where each is a plain web origin, but no camera, microphone, location or clipboard access and no web origin of its own: no review shows them.
- **Images, audio and files.** Results keep them. GateRook can't mask pixels: it withholds a block whose bytes contain a secret it handed to the server, and one that doesn't fit the result's size limit, with a note in its place.
- **Progress, log messages and cancelling.** When the AI app asks for them, it hears a call's progress and the server's log messages while the call runs, masked. Stopping a call in the AI app stops it at the server.
- **Changes and updates.** AI apps that listen hear when the server's resources or prompts change, and get updates of the resources they subscribed to.
- **Tasks.** A tool that the server runs as a task runs to its end within the call's timeout. AI apps that use tasks can run a call through GateRook as a task; one that waits for approval then waits for your decision.

GateRook speaks the MCP revisions `2026-07-28` and `2025-11-25` back to `2024-11-05`, the newest the server speaks, over stdio, Streamable HTTP or the older SSE transport. It gives a server no access to the AI app: a server that asks for its model, its files or your input gets an error.

What a server sends is held within limits real servers don't reach. A list (of tools, resources or prompts) keeps at most 1,000 entries and 4 MB: GateRook keeps the first ones, and the server's log says so. A server that sends notifications faster than GateRook can take them in, or a server on this computer that stops reading what GateRook sends it, loses its session, and its log says why. A server on this computer is then treated as if it had crashed; a remote server's next call opens a new session.

## Sign in

Select **Sign in** in the review. GateRook opens the server's sign-in page in your default browser and listens on this computer, at `http://localhost:<free port>/callback`, for the browser to come back. After you allow access, the page says you are signed in and GateRook returns to the front. It connects the server right away, and the endpoint step opens with a note that the server is signed in. Values the server needs besides the sign-in, such as a header, are entered in the review first: **Sign in** waits for them. If the server doesn't connect, the review says why. A sign-in is used once, so sign in again to try once more.

For GitHub's server (`https://api.githubcopilot.com/mcp/`), the sign-in uses a one-time code instead, because GitHub admits only apps registered with it. GateRook has its own GitHub app for this, so there is nothing to set up. Select **Sign in with github.com**: the review shows a code such as `WDJB-MJHT`. Select **Copy code and open github.com**, paste the code on GitHub's page and allow access to GateRook there. GateRook finishes the sign-in by itself and connects the server. Nothing comes back to this computer, so you can also enter the code in a browser on another device. The code works once, and the sign-in waits for it for 10 minutes. GitHub renews this sign-in for GateRook without a client secret. If an organization restricts OAuth apps, its repositories stay out of reach until an owner approves GateRook; GitHub's authorization page lets you request that.

The provider's consent page names GateRook. Where the provider accepts client metadata documents (in November 2026, Linear, Notion and Sentry among others), GateRook doesn't register with it. The provider reads the details GateRook publishes at `https://gaterook.com/oauth/gaterook-client.json`: its name, that it has no client secret, and that the browser comes back to this computer. Other servers get a registration that GateRook makes under its own name.

The sign-in belongs to GateRook, not to the AI app. GateRook keeps it encrypted as a secret of the server and renews it before it expires. Every call of the server carries it, and no agent, AI app, log or answer receives it. Any of your AI apps can use the server's tools through an endpoint, under the endpoint's modes and approvals.

If the browser can't reach this computer, for example because it runs on another device, open **The browser can’t reach GateRook?** while the sign-in waits. Paste the whole address the browser ended on. It must belong to the same sign-in.

The server's row shows **Signed in**, **Needs sign-in** or **Signed out**. A server needs a new sign-in when its provider refuses to renew the sign-in (it was revoked, or it expired) or asks for more permissions. Its calls don't run meanwhile, and they say where to sign in. Use **Sign in** in the row, or **Sign in again** in its menu. **Sign out** revokes the sign-in where the provider allows that and forgets it. The tools and their modes stay either way.

## Servers that need your own OAuth app

Some servers neither accept GateRook's published details nor let apps register themselves. At the time of writing (November 2026) these include Slack's and Asana's v2 server. GitHub's is one too, but it signs in with GateRook's own GitHub app, as [Sign in](#sign-in) describes. The review then says so and shows the redirect URI to register, `http://127.0.0.1:7471/callback`:

1. Create an OAuth app for GateRook in the provider's developer settings, with that redirect URI.
2. In **Add MCP server › Sign-in options › OAuth app options**, enter its client ID, or enter it in the review.
3. If the app has a client secret, enter it in the review. It is stored as a write-only secret of the server.

GateRook sends the client secret only to the authorization server it was entered for: the review shows the address it goes to, and the sign-in goes there only. Later sign-ins go to the authorization server the secret was first used with. If the server names another one, or its authorization server takes tokens at another address, GateRook doesn't send the secret and says so. If you trust the new one, enter the client secret again, in **Secrets** or by editing the server in **Connections**, then sign in. Signing out keeps this record; removing the server deletes it.

**OAuth app options** also set the **Scopes** to ask for, and a **Callback port** for a server or an app that accepts only one redirect address. A client ID entered there replaces GateRook's own GitHub app.

## Servers on Google Cloud

Google's MCP servers, such as `https://bigtableadmin.googleapis.com/mcp` or `https://bigquery.googleapis.com/mcp`, don't let apps register themselves or read client metadata documents. Instead of an OAuth app, choose **Sign-in options › Sign in with › Google Cloud**, or **Use Google Cloud credentials** in the review. GateRook then gets a Google access token itself and sends it with every request. It renews the token before it expires, and no agent or log sees it. It sends that token only to Google's own servers, HTTPS addresses under `googleapis.com`: a server at any other address gets none, even one whose sign-in is Google's, and the review offers Google Cloud credentials only for those addresses.

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
| Access wasn't allowed on github.com | The sign-in was cancelled on GitHub's page. Sign in again for a new code. |
| The code expired before it was entered | Sign in again for a new code, and enter it within 10 minutes. |
| GitHub's tools don't see an organization's repositories | The organization restricts OAuth apps. Request approval for GateRook on GitHub's authorization page, or ask an organization owner to approve it. |
| Refused its renewed sign-in | The provider took a new token back at once. Sign in again if it keeps happening. |
| Refused GateRook's Google Cloud credentials | The account lacks `roles/mcp.toolUser` or the product's roles, or the API isn't enabled. Google's message names what is missing. |
| No Google sign-in on this computer | Run `gcloud auth application-default login`, or use a service account key. |
| Refused GateRook's AWS credentials | The principal or role may not call the server, or this computer's clock is off by more than 5 minutes. AWS's message says which. |
| Name the AWS service and region to sign for | The URL's host doesn't name them. Enter them in **Sign-in options**, such as `bedrock-agentcore` and `us-west-2`. |
