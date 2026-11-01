# External MCP servers

GateRook can put an MCP server you already use behind its modes, approvals and audit: a command it starts on this computer, or a server at a URL. Its tools are published under the server's prefix (`linear_list_issues`) and start Off on every endpoint. See [the architecture](ARCHITECTURE.md#76-remote-mcp-server-sign-in) for the protocol details.

## Add a server

Open **Connectors › MCP servers › Add MCP server**. Either fill in the fields, or open **Paste a configuration from the server’s docs** and paste what the server's documentation gives for an AI app:

| What you paste | Example |
| --- | --- |
| Claude Desktop, Cursor or Windsurf JSON | `{"mcpServers": {"linear": {"url": "https://mcp.linear.app/mcp"}}}` |
| VS Code JSON | `{"servers": {"github": {"type": "http", "url": "https://api.githubcopilot.com/mcp/"}}}` |
| Codex TOML | `[mcp_servers.notion]` with `url = "https://mcp.notion.com/mcp"` |
| A command | `claude mcp add --transport http sentry https://mcp.sentry.dev/mcp`, `codex mcp add linear --url …` |
| The URL | `https://mcp.stripe.com` |

Only names of headers and variables are taken from a paste. The review asks for each value and stores it encrypted as a secret of the server. A command that runs `mcp-remote` for a URL becomes that URL, because GateRook signs in itself. An address ending in `/sse` becomes `/mcp`, because GateRook connects over Streamable HTTP and servers that still list an SSE address usually answer there too. With a URL and no name, the name follows the host: `mcp.linear.app` becomes `linear-mcp`.

**Review and connect** sends one request to the server without a sign-in and shows what it found:

- **Sign-in with OAuth.** You sign in in your browser, as in Claude Desktop, Cursor or Codex.
- **Optional sign-in.** The server also answers without one, with less access.
- **No sign-in.** Connect directly.
- **It wants a key.** It refused the request without offering a sign-in. Add the header that carries its key, such as `Authorization`.

## Sign in

Select **Sign in** in the review. GateRook opens the server's sign-in page in your default browser and listens on this computer, at `http://localhost:<free port>/callback`, for the browser to come back. After you allow access, the page says you are signed in and GateRook returns to the front. Then select **Connect MCP server**.

The sign-in belongs to GateRook, not to the AI app. GateRook keeps it encrypted as a secret of the server and renews it before it expires. Every call of the server carries it, and no agent, AI app, log or answer receives it. Any of your AI apps can use the server's tools through an endpoint, under the endpoint's modes and approvals.

If the browser can't reach this computer, for example because it runs on another device, open **The browser can’t reach GateRook?** while the sign-in waits. Paste the whole address the browser ended on. It must belong to the same sign-in.

The server's row shows **Signed in**, **Needs sign-in** or **Signed out**. A server needs a new sign-in when its provider refuses to renew the sign-in (it was revoked, or it expired) or asks for more permissions. Its calls don't run meanwhile, and they say where to sign in. Use **Sign in** in the row, or **Sign in again** in its menu. **Sign out** revokes the sign-in where the provider allows that and forgets it. The tools and their modes stay either way.

## Servers that need your own OAuth app

Some servers don't let apps register themselves. At the time of writing (November 2026) these include GitHub's, Slack's and Asana's v2 server. The review then says so and shows the redirect URI to register, `http://127.0.0.1:7471/callback`:

1. Create an OAuth app for GateRook in the provider's developer settings, with that redirect URI.
2. In **Add MCP server › Sign-in options**, enter its client ID, or enter it in the review.
3. If the app has a client secret, enter it in the review. It is stored as a write-only secret of the server.

**Sign-in options** also set the **Scopes** to ask for, and a **Callback port** for a server or an app that accepts only one redirect address.

## Servers that admit only approved apps

A few providers register only clients on their own allowlist, by name or by redirect address. GateRook registers under its own name and never presents another application's client ID, name or redirect address. Such a server refuses the registration, and the review says so. Use the provider's API key in a header if it offers one, or ask the provider to admit GateRook.

## Troubleshooting

| Message | What to do |
| --- | --- |
| A private address | A server on this computer or your network needs its range in **Policies › HTTP: allowed private ranges** for the environment. |
| Can't listen on port … | Another program uses the callback port. Choose another one in **Sign-in options**, or leave it empty for any free port. |
| It answered HTTP 404 or 405 | Check the URL. Streamable HTTP addresses usually end in `/mcp`. |
| Needs you to sign in again | Sign in from the server's row. |
| Refused its renewed sign-in | The provider took a new token back at once. Sign in again if it keeps happening. |
