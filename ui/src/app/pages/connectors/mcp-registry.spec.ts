import { McpRegistryServer } from '../../core/api/plugins.api';
import { SSE_NOTE } from './mcp-paste';
import { fromRegistry, registryName, registryUse } from './mcp-registry';

/** Entries of the Official MCP Registry (2026-11-02) as the core passes them on, and what they fill in Add MCP server. */
function entry(e: Partial<McpRegistryServer> & { readonly name: string }): McpRegistryServer {
  return { status: 'active', remotes: [], packages: [], ...e };
}

const LINEAR = entry({
  name: 'app.linear/linear',
  title: 'Linear',
  version: '1.0.2',
  remotes: [
    { type: 'streamable-http', url: 'https://mcp.linear.app/mcp', headers: [], variables: [] },
    { type: 'streamable-http', url: 'https://mcp.linear.app/mcp/readonly', headers: [], variables: [] },
  ],
});

describe('MCP registry entries in Add MCP server', () => {
  it('connects to the first Streamable HTTP remote, named after its host as a pasted URL is', () => {
    expect(fromRegistry(LINEAR)).toEqual({
      name: 'linear-mcp',
      transport: 'http',
      url: 'https://mcp.linear.app/mcp',
      command: '',
      variables: [],
      notes: [],
      others: 0,
    });
  });

  it('leaves out an optional Authorization header, so the review finds the sign-in, and prefers a remote to a package', () => {
    // GitHub's entry (2026-11-09): a PAT or App token besides the OAuth sign-in its server offers.
    const github = entry({
      name: 'io.github.github/github-mcp-server',
      remotes: [{ type: 'streamable-http', url: 'https://api.githubcopilot.com/mcp/', variables: [], headers: [
        { name: 'Authorization', description: 'Authorization header with authentication token (PAT or App token)', required: false, secret: true },
        { name: 'X-MCP-Toolsets', description: 'Toolsets to enable', required: false, secret: false },
      ] }],
      packages: [{ registryType: 'oci', identifier: 'ghcr.io/github/github-mcp-server:2.1.0', transport: 'stdio',
        environmentVariables: [], runtimeArguments: [], packageArguments: [] }],
    });
    const r = fromRegistry(github);
    if ('error' in r) throw new Error(r.error);
    expect(r.transport).toBe('http');
    expect(r.url).toBe('https://api.githubcopilot.com/mcp/');
    expect(r.variables).toEqual([]);
    expect(r.notes).toEqual(['It also takes a token in Authorization: to send one instead of signing in, choose Bearer token under Sign-in options.']);
  });

  it('asks for the secret and required headers by name only, a required Authorization header too', () => {
    const keyed = entry({
      name: 'com.example/search',
      remotes: [{ type: 'streamable-http', url: 'https://mcp.example.com/mcp', variables: [], headers: [
        { name: 'Authorization', description: 'Bearer <api key>', required: true, secret: true },
        { name: 'X-Workspace', description: 'Workspace', required: false, secret: true },
        { name: 'X-Debug', description: 'Debug output', required: false, secret: false },
      ] }],
    });
    const r = fromRegistry(keyed);
    if ('error' in r) throw new Error(r.error);
    expect(r.variables).toEqual(['Authorization', 'X-Workspace']);
    expect(r.notes).toEqual(['Their values aren’t copied: the review asks for each and stores it encrypted.']);
  });

  it('keeps a URL template visible and names the parts to replace', () => {
    const teams = entry({
      name: 'com.microsoft/workiq-teamsserver',
      remotes: [{ type: 'streamable-http', headers: [],
        url: 'https://agent365.svc.cloud.microsoft/agents/tenants/{tenant_id}/servers/mcp_TeamsServer',
        variables: [{ name: 'tenant_id', description: 'Microsoft Entra tenant ID', required: true, secret: false }] }],
    });
    const r = fromRegistry(teams);
    if ('error' in r) throw new Error(r.error);
    expect(r.name).toBe('workiq-teamsserver');
    expect(r.url).toBe('https://agent365.svc.cloud.microsoft/agents/tenants/{tenant_id}/servers/mcp_TeamsServer');
    expect(r.notes[0]).toBe('Replace {tenant_id} in the URL with your own value. {tenant_id}: Microsoft Entra tenant ID.');
  });

  it('keeps an SSE address as given, as a pasted one', () => {
    const r = fromRegistry(entry({ name: 'com.example/tenant',
      remotes: [{ type: 'sse', url: 'https://{tenant}.example.com/sse', headers: [], variables: [] }] }));
    if ('error' in r) throw new Error(r.error);
    expect(r.url).toBe('https://{tenant}.example.com/sse');
    expect(r.name).toBe('tenant');
    expect(r.notes).toContain(SSE_NOTE);
  });

  it('starts an npm package with npx and its pinned version, its secret variables by name', () => {
    const sentry = entry({
      name: 'io.github.getsentry/sentry-mcp',
      packages: [{ registryType: 'npm', identifier: '@sentry/mcp-server', version: '0.42.0', transport: 'stdio',
        environmentVariables: [
          { name: 'SENTRY_ACCESS_TOKEN', description: 'User auth token', required: true, secret: true },
          { name: 'SENTRY_HOST', description: 'Self-hosted Sentry', required: false, secret: false },
        ],
        runtimeArguments: [],
        packageArguments: [] }],
    });
    expect(fromRegistry(sentry)).toEqual({
      name: 'sentry-mcp',
      transport: 'stdio',
      url: '',
      command: 'npx -y @sentry/mcp-server@0.42.0',
      variables: ['SENTRY_ACCESS_TOKEN'],
      notes: [
        'Its value isn’t copied: the review asks for it and stores it encrypted.',
        'It also reads SENTRY_HOST; add it under Variables if you need it.',
      ],
      others: 0,
    });
  });

  it('names at most three optional variables, as the MongoDB server documents dozens', () => {
    const optional = (names: readonly string[]) => names.map((name) => ({ name, required: false, secret: false }));
    const mongo = (names: readonly string[]) => fromRegistry(entry({
      name: 'io.github.mongodb-js/mongodb-mcp-server',
      packages: [{ registryType: 'npm', identifier: 'mongodb-mcp-server', version: '3.2.1', transport: 'stdio',
        environmentVariables: [{ name: 'MDB_MCP_CONNECTION_STRING', required: false, secret: true }, ...optional(names)],
        runtimeArguments: [], packageArguments: [] }],
    }));
    const many = mongo(['MDB_MCP_LOG_PATH', 'MDB_MCP_READ_ONLY', 'MDB_MCP_HTTP_PORT', 'MDB_MCP_HTTP_HOST', 'MDB_MCP_INDEX_CHECK']);
    if ('error' in many) throw new Error(many.error);
    expect(many.variables).toEqual(['MDB_MCP_CONNECTION_STRING']);
    expect(many.notes).toContain('It also reads MDB_MCP_LOG_PATH, MDB_MCP_READ_ONLY, MDB_MCP_HTTP_PORT and 2 more variables; add the ones you need under Variables.');
    const two = mongo(['MDB_MCP_LOG_PATH', 'MDB_MCP_READ_ONLY']);
    if ('error' in two) throw new Error(two.error);
    expect(two.notes).toContain('It also reads MDB_MCP_LOG_PATH and MDB_MCP_READ_ONLY; add the ones you need under Variables.');
  });

  it('leaves optional options off and marks a required one without a value for the person', () => {
    const r = fromRegistry(entry({
      name: 'io.github.someone/linear-mcp',
      packages: [{ registryType: 'npm', identifier: '@someone/linear-mcp', version: '0.3.2', runtimeHint: 'npx', transport: 'stdio',
        environmentVariables: [],
        runtimeArguments: [{ type: 'named', name: '--yes', required: false }, { type: 'named', name: '--read-only', required: false }],
        packageArguments: [
          { type: 'named', name: '--workspace', valueHint: 'workspace', required: true },
          { type: 'named', name: '--aggregationCountTimeoutMs', required: false },
          { type: 'positional', given: 'stdio', required: false },
        ] }],
    }));
    if ('error' in r) throw new Error(r.error);
    // npx gets -y of its own: an optional flag without a value stays off like any other option.
    expect(r.command).toBe('npx -y @someone/linear-mcp@0.3.2 --workspace "<workspace>" stdio');
    expect(r.notes).toContain('Replace <workspace> in the command with your own value.');
  });

  it('starts a PyPI package with uvx, pinned the way the review counts a pin', () => {
    const r = fromRegistry(entry({
      name: 'io.github.someone/fetch',
      packages: [{ registryType: 'pypi', identifier: 'mcp-server-fetch', version: '2025.4.7', runtimeHint: 'uvx', transport: 'stdio',
        environmentVariables: [], runtimeArguments: [], packageArguments: [] }],
    }));
    if ('error' in r) throw new Error(r.error);
    expect(r.name).toBe('fetch');
    expect(r.command).toBe('uvx --from mcp-server-fetch==2025.4.7 mcp-server-fetch');
  });

  it('runs the MCP Proxy for AWS from PyPI as its URL, signed by GateRook', () => {
    const aws = entry({
      name: 'io.github.aws/aws-mcp',
      packages: [{ registryType: 'pypi', identifier: 'mcp-proxy-for-aws', version: '1.1.6', runtimeHint: 'uvx', transport: 'stdio',
        environmentVariables: [], runtimeArguments: [],
        packageArguments: [{ type: 'positional', given: 'https://aws-mcp.us-east-1.api.aws/mcp', valueHint: 'mcp_endpoint_url', required: false }] }],
    });
    const r = fromRegistry(aws);
    if ('error' in r) throw new Error(r.error);
    expect(r.transport).toBe('http');
    expect(r.url).toBe('https://aws-mcp.us-east-1.api.aws/mcp');
    expect(r.auth).toEqual({ type: 'aws', credentials: { source: 'profile', profile: 'default' } });
  });

  it('says why an entry can’t be added, and notes a deprecated one', () => {
    const image = entry({ name: 'io.github.someone/image-only', packages: [
      { registryType: 'oci', identifier: 'ghcr.io/someone/mcp:1', transport: 'stdio', environmentVariables: [], runtimeArguments: [], packageArguments: [] },
      { registryType: 'mcpb', identifier: 'https://example.com/x.mcpb', transport: 'stdio', environmentVariables: [], runtimeArguments: [], packageArguments: [] },
    ] });
    const reason = 'It runs as a Docker image or an MCP bundle, which GateRook doesn’t start from the registry yet. Paste the configuration from its docs instead.';
    expect(registryUse(image)).toEqual({ kind: 'none', reason });
    expect(fromRegistry(image)).toEqual({ error: reason });
    expect(registryUse(entry({ name: 'com.example/nothing' }))).toEqual({ kind: 'none', reason: 'Its entry names no URL or package to run.' });

    const old = fromRegistry({ ...LINEAR, status: 'deprecated' });
    if ('error' in old) throw new Error(old.error);
    expect(old.notes).toEqual(['Its publisher marked this server deprecated.']);
  });

  it('names a server after the entry, or after its publisher when the entry’s own name is generic', () => {
    expect(registryName('io.github.someone/linear-mcp')).toBe('linear-mcp');
    expect(registryName('com.stripe/mcp')).toBe('stripe-mcp');
    expect(registryName('io.github.acme/mcp-server')).toBe('acme-mcp');
    expect(registryName('com.microsoft/Azure_DevOps')).toBe('azure-devops');
  });
});
