# External MCP servers

GateRook can put an MCP server you already use behind its modes, approvals and audit: a command it starts on this computer, or a server at a URL. Its tools are published under the server's prefix (`linear_list_issues`) and start Off on every endpoint. See [the architecture](ARCHITECTURE.md#76-remote-mcp-server-sign-in) for the protocol details.

## Add a server

Open **Connectors › MCP servers › Add MCP server**; **Connections › Add connection › External MCP server** leads there too. Either fill in the fields, or open **Paste a configuration from the server’s docs** and paste what the server's documentation gives for an AI app:

| What you paste | Example |
| --- | --- |
| Claude Desktop, Cursor or Windsurf JSON | `{"mcpServers": {"linear": {"url": "https://mcp.linear.app/mcp"}}}` |
| VS Code JSON | `{"servers": {"github": {"type": "http", "url": "https://api.githubcopilot.com/mcp/"}}}` |
| Codex TOML | `[mcp_servers.notion]` with `url = "https://mcp.notion.com/mcp"` |
| A command | `claude mcp add --transport http sentry https://mcp.sentry.dev/mcp`, `codex mcp add linear --url …` |
| The URL | `https://mcp.stripe.com` |

Only names of headers and variables are taken from a paste. The review asks for each value and stores it encrypted as a secret of the server. A command that runs `mcp-remote` for a URL becomes that URL, because GateRook signs in itself. A command that runs the MCP Proxy for AWS becomes its URL signed with AWS credentials, with the proxy's profile and region. A Google server pasted with a token in `Authorization` signs in with Google Cloud credentials instead, billed to its `x-goog-user-project`. An address ending in `/sse` becomes `/mcp`, because GateRook connects over Streamable HTTP and servers that still list an SSE address usually answer there too. With a URL and no name, the name follows the host: `mcp.linear.app` becomes `linear-mcp`, and a Google or AWS server is named after its product, such as `bigtableadmin-mcp`.

**Review and connect** sends one request to the server without a sign-in and shows what it found:

- **Sign-in with OAuth.** You sign in in your browser, as in Claude Desktop, Cursor or Codex.
- **Optional sign-in.** The server also answers without one, with less access. If you connect without signing in, you can sign in later from the server's row.
- **No sign-in.** Connect directly.
- **It wants a key.** It refused the request without offering a sign-in. Add the header that carries its key, such as `Authorization`.

Some servers take their key in the URL, as their docs give it: `https://mcp.example.com/<key>/mcp` or `…/mcp?apiKey=<key>`. Such a server still connects, but the review warns you. GateRook stores the URL with the server's settings, not encrypted as a secret, and the provider or a proxy on the way may log it. If the server also accepts the key in a header, add that header instead. The review shows the URL with `‹masked›` in place of the key, and the lists show it without the key. Agents never see the URL.

For a server on Google Cloud or AWS, the review also offers to sign in with that cloud's credentials. See [Servers on Google Cloud](#servers-on-google-cloud) and [Servers on AWS](#servers-on-aws).

Adding a server ends as adding a connection does: GateRook offers the endpoints its tools may join, the usual one chosen. There each tool starts as Approval, and you change modes on the endpoint. **Not now** leaves the tools Off until you add the server to an endpoint later.

The server is then listed in **Connections** with the other connections, where you test, edit or delete it. In **Connectors › MCP servers** you can also restart it, sign in to it again or sign out.

## Sign in

Select **Sign in** in the review. GateRook opens the server's sign-in page in your default browser and listens on this computer, at `http://localhost:<free port>/callback`, for the browser to come back. After you allow access, the page says you are signed in and GateRook returns to the front. Then select **Connect MCP server**.

The provider's consent page names GateRook. Where the provider accepts client metadata documents (in November 2026, Linear, Notion and Sentry among others), GateRook doesn't register with it. The provider reads the details GateRook publishes at `https://msrv.dev/oauth/gaterook-client.json`: its name, that it has no client secret, and that the browser comes back to this computer. Other servers get a registration that GateRook makes under its own name.

The sign-in belongs to GateRook, not to the AI app. GateRook keeps it encrypted as a secret of the server and renews it before it expires. Every call of the server carries it, and no agent, AI app, log or answer receives it. Any of your AI apps can use the server's tools through an endpoint, under the endpoint's modes and approvals.

If the browser can't reach this computer, for example because it runs on another device, open **The browser can’t reach GateRook?** while the sign-in waits. Paste the whole address the browser ended on. It must belong to the same sign-in.

The server's row shows **Signed in**, **Needs sign-in** or **Signed out**. A server needs a new sign-in when its provider refuses to renew the sign-in (it was revoked, or it expired) or asks for more permissions. Its calls don't run meanwhile, and they say where to sign in. Use **Sign in** in the row, or **Sign in again** in its menu. **Sign out** revokes the sign-in where the provider allows that and forgets it. The tools and their modes stay either way.

## Servers that need your own OAuth app

Some servers neither accept GateRook's published details nor let apps register themselves. At the time of writing (November 2026) these include GitHub's, Slack's and Asana's v2 server. The review then says so and shows the redirect URI to register, `http://127.0.0.1:7471/callback`:

1. Create an OAuth app for GateRook in the provider's developer settings, with that redirect URI.
2. In **Add MCP server › Sign-in options**, enter its client ID, or enter it in the review.
3. If the app has a client secret, enter it in the review. It is stored as a write-only secret of the server.

**Sign-in options** also set the **Scopes** to ask for, and a **Callback port** for a server or an app that accepts only one redirect address.

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
| It answered HTTP 404 or 405 | Check the URL. Streamable HTTP addresses usually end in `/mcp`. |
| The provider's page says the client or its redirect address is invalid | The provider couldn't use the details GateRook publishes. Cancel the sign-in in GateRook and try again later. If it keeps happening and the provider lets you create an OAuth app, enter that app's client ID in **Sign-in options**. |
| Needs you to sign in again | Sign in from the server's row. |
| Refused its renewed sign-in | The provider took a new token back at once. Sign in again if it keeps happening. |
| Refused GateRook's Google Cloud credentials | The account lacks `roles/mcp.toolUser` or the product's roles, or the API isn't enabled. Google's message names what is missing. |
| No Google sign-in on this computer | Run `gcloud auth application-default login`, or use a service account key. |
| Refused GateRook's AWS credentials | The principal or role may not call the server, or this computer's clock is off by more than 5 minutes. AWS's message says which. |
| Name the AWS service and region to sign for | The URL's host doesn't name them. Enter them in **Sign-in options**, such as `bedrock-agentcore` and `us-west-2`. |
