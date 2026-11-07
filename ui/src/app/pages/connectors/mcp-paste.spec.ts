import { SSE_NOTE, parsePastedServer, serverName } from './mcp-paste';

/** The snippets official MCP servers publish for the AI apps (their docs, 2026-11), as Add MCP server reads them. */
describe('pasted MCP server configurations', () => {
  it('reads a Claude Desktop / Cursor mcpServers entry with a URL and keeps header names only', () => {
    const r = parsePastedServer(`{
      "mcpServers": {
        "linear": { "url": "https://mcp.linear.app/mcp", "headers": { "Authorization": "Bearer lin_api_123" } },
        "notion": { "url": "https://mcp.notion.com/mcp" }
      }
    }`);
    expect(r).toEqual({
      name: 'linear',
      transport: 'http',
      url: 'https://mcp.linear.app/mcp',
      command: '',
      variables: ['Authorization'],
      notes: ['Its value isn’t copied: the review asks for it and stores it encrypted.'],
      others: 1,
    });
    expect(JSON.stringify(r)).not.toContain('lin_api_123');
  });

  it('turns the MCP Proxy for AWS into its URL, signed by GateRook with the proxy’s profile and region', () => {
    const r = parsePastedServer(`{"mcpServers":{"aws-mcp":{"command":"uvx","args":["mcp-proxy-for-aws@latest",
      "https://aws-mcp.us-east-1.api.aws/mcp","--profile","prod-readonly","dev","--region","us-east-1"]}}}`);
    expect('error' in r).toBe(false);
    if ('error' in r) return;
    expect(r.transport).toBe('http');
    expect(r.url).toBe('https://aws-mcp.us-east-1.api.aws/mcp');
    expect(r.auth).toEqual({ type: 'aws', credentials: { source: 'profile', profile: 'prod-readonly' }, region: 'us-east-1' });
    expect(r.notes).toContain('GateRook signs the requests with SigV4 itself, so the MCP Proxy for AWS isn’t needed.');
    const env = parsePastedServer(`{"mcpServers":{"aws":{"command":"uvx","args":["mcp-proxy-for-aws","https://aws-mcp.eu-west-1.api.aws/mcp"],
      "env":{"AWS_PROFILE":"ops","AWS_SECRET_ACCESS_KEY":"never-copied"}}}}`);
    if ('error' in env) throw new Error(env.error);
    expect(env.auth).toEqual({ type: 'aws', credentials: { source: 'profile', profile: 'ops' } });
    expect(JSON.stringify(env)).not.toContain('never-copied');
  });

  it('turns a Google server documented with a gcloud token into Google Cloud credentials, billed to its project', () => {
    const r = parsePastedServer(`{"mcpServers":{"bigquery":{"url":"https://bigquery.googleapis.com/mcp",
      "headers":{"Authorization":"Bearer ya29.short-lived","x-goog-user-project":"acme-billing"}}}}`);
    expect('error' in r).toBe(false);
    if ('error' in r) return;
    expect(r.variables).toEqual([]);
    expect(r.auth).toEqual({ type: 'google', credentials: { source: 'adc', quotaProject: 'acme-billing' } });
    expect(r.notes).toEqual(['Signs in with your Google Cloud credentials instead of the pasted token: GateRook gets a new one before it expires.']);
    expect(JSON.stringify(r)).not.toContain('ya29.short-lived');
    // A key header of a Google server that takes API keys stays a header.
    const maps = parsePastedServer(`{"mcpServers":{"maps":{"url":"https://mapstools.googleapis.com/mcp","headers":{"X-Goog-Api-Key":"k"}}}}`);
    if ('error' in maps) throw new Error(maps.error);
    expect(maps.auth).toBeUndefined();
    expect(maps.variables).toEqual(['X-Goog-Api-Key']);
  });

  it('turns an mcp-remote bridge into the URL it bridges, an SSE address as given', () => {
    const r = parsePastedServer(`{"mcpServers":{"atlassian":{"command":"npx","args":["-y","mcp-remote","https://mcp.atlassian.com/v1/sse"]}}}`);
    expect('error' in r).toBe(false);
    if ('error' in r) return;
    expect(r.transport).toBe('http');
    expect(r.url).toBe('https://mcp.atlassian.com/v1/sse');
    expect(r.name).toBe('atlassian');
    expect(r.notes).toContain(SSE_NOTE);
    expect(r.notes.join(' ')).toContain('mcp-remote isn’t needed');
  });

  it('reads VS Code servers with comments and trailing commas, under mcp too', () => {
    const r = parsePastedServer(`{
      // .vscode/mcp.json
      "servers": {
        "github": { "type": "http", "url": "https://api.githubcopilot.com/mcp/", },
      },
    }`);
    expect(r).toMatchObject({ name: 'github', transport: 'http', url: 'https://api.githubcopilot.com/mcp/' });
    expect(parsePastedServer('{"mcp":{"servers":{"sentry":{"url":"https://mcp.sentry.dev/mcp"}}}}')).toMatchObject({ name: 'sentry' });
  });

  it('reads a fragment copied without its braces and a single server object', () => {
    expect(parsePastedServer('"stripe": { "url": "https://mcp.stripe.com" },')).toMatchObject({ name: 'stripe', url: 'https://mcp.stripe.com' });
    expect(parsePastedServer('{"serverUrl":"https://mcp.example.com/mcp"}')).toMatchObject({ name: 'example-mcp', transport: 'http' });
  });

  it('reads a stdio server with its variables, values left out', () => {
    const r = parsePastedServer(`{"mcpServers":{"Sentry Local":{"command":"npx","args":["-y","@sentry/mcp-server@0.12.0"],"env":{"SENTRY_ACCESS_TOKEN":"sntrys_x","SENTRY_HOST":"sentry.io"}}}}`);
    expect(r).toMatchObject({
      name: 'sentry-local',
      transport: 'stdio',
      command: 'npx -y @sentry/mcp-server@0.12.0',
      variables: ['SENTRY_ACCESS_TOKEN', 'SENTRY_HOST'],
    });
    expect(JSON.stringify(r)).not.toContain('sntrys_x');
  });

  it('reads Codex TOML: a URL with its headers, or a command with its env table', () => {
    const url = parsePastedServer(`
      [mcp_servers.figma]
      url = "https://mcp.figma.com/mcp"
      bearer_token_env_var = "FIGMA_TOKEN"
    `);
    expect(url).toMatchObject({ name: 'figma', transport: 'http', url: 'https://mcp.figma.com/mcp', variables: ['Authorization'] });
    const command = parsePastedServer(`
      [mcp_servers."brave-search"]
      command = "npx"
      args = ["-y", "@brave/brave-search-mcp-server@2.0.0"]  # pinned

      [mcp_servers."brave-search".env]
      BRAVE_API_KEY = "key"

      [mcp_servers.other]
      url = "https://example.com/mcp"
    `);
    expect(command).toMatchObject({
      name: 'brave-search',
      transport: 'stdio',
      command: 'npx -y @brave/brave-search-mcp-server@2.0.0',
      variables: ['BRAVE_API_KEY'],
      others: 1,
    });
  });

  it('reads claude mcp add and codex mcp add commands', () => {
    expect(parsePastedServer('claude mcp add --transport http notion https://mcp.notion.com/mcp')).toMatchObject({
      name: 'notion',
      transport: 'http',
      url: 'https://mcp.notion.com/mcp',
    });
    expect(parsePastedServer('claude mcp add --transport http github https://api.githubcopilot.com/mcp -H "Authorization: Bearer $GITHUB_PAT"')).toMatchObject({
      name: 'github',
      variables: ['Authorization'],
    });
    expect(parsePastedServer('codex mcp add linear --url https://mcp.linear.app/mcp')).toMatchObject({ name: 'linear', url: 'https://mcp.linear.app/mcp' });
    expect(parsePastedServer('claude mcp add --transport http --callback-port 8080 --client-id abc slack https://mcp.slack.com/mcp')).toMatchObject({
      name: 'slack',
      url: 'https://mcp.slack.com/mcp',
    });
    expect(parsePastedServer('codex mcp add fetch -- uvx mcp-server-fetch')).toMatchObject({ name: 'fetch', transport: 'stdio', command: 'uvx mcp-server-fetch' });
  });

  it('takes a bare URL and names it after its host', () => {
    expect(parsePastedServer(' https://mcp.linear.app/mcp ')).toMatchObject({ name: 'linear-mcp', transport: 'http', url: 'https://mcp.linear.app/mcp' });
    expect(parsePastedServer('npx -y mcp-remote https://mcp.asana.com/sse')).toMatchObject({ name: 'asana-mcp', url: 'https://mcp.asana.com/sse' });
  });

  it('says what it can’t read', () => {
    expect(parsePastedServer('')).toEqual({ error: 'Paste the configuration from the server’s documentation.' });
    expect(parsePastedServer('not a config')).toHaveProperty('error');
    expect(parsePastedServer('{"mcpServers":{}}')).toEqual({ error: 'The configuration names no server.' });
    expect(parsePastedServer('{"name":"x"}')).toEqual({ error: 'The configuration has no command or URL of a server.' });
    expect(parsePastedServer('[mcp_servers.x]\nenabled = true')).toEqual({ error: '[mcp_servers.x] has no command or url.' });
  });

  it('suggests names GateRook accepts', () => {
    expect(serverName('My Server!', null)).toBe('my-server');
    expect(serverName('123', null)).toBe('');
    expect(serverName('', new URL('https://mcp.atlassian.com/v1/mcp'))).toBe('atlassian-mcp');
    expect(serverName('', new URL('https://api.githubcopilot.com/mcp/'))).toBe('githubcopilot-mcp');
    expect(serverName('', new URL('https://docs.mcp.cloudflare.com/mcp'))).toBe('cloudflare-mcp');
    expect(serverName('', new URL('http://127.0.0.1:3000/mcp'))).toBe('local-mcp');
    // Google's and AWS's servers share a domain: the product names each one.
    expect(serverName('', new URL('https://bigtableadmin.googleapis.com/mcp'))).toBe('bigtableadmin-mcp');
    expect(serverName('', new URL('https://alloydb.us-central1.rep.googleapis.com/mcp'))).toBe('alloydb-mcp');
    expect(serverName('', new URL('https://aws-mcp.us-east-1.api.aws/mcp'))).toBe('aws-mcp');
    expect(serverName('', new URL('https://bedrock-agentcore.us-west-2.amazonaws.com/runtimes/x/invocations'))).toBe('bedrock-agentcore-mcp');
  });
});
