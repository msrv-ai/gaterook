import { ApiError } from '../../../core/api/api-error';
import { clickhouseCheck, sentryReview } from '../connectors.fixtures';
import { failureDetails, failureText } from './failure';
import { mcpRequest } from './mcp-add.dialog';
import { registryResult } from '../mcp-registry-results';
import { connectedToast } from './mcp-endpoint.dialog';
import { McpServerAuthConfig } from '../../../core/api/plugins.api';
import { mcpReviewView, mcpSecretValues, signInConfig } from './mcp-review.dialog';
import { accessText, connectionFormText, pluginFacts } from './plugin-facts';

describe('plugin review facts', () => {
  it('words the kit’s ClickHouse review', () => {
    expect(pluginFacts(clickhouseCheck(true), null)).toEqual({
      id: 'clickhouse',
      name: 'ClickHouse',
      version: '0.9.1',
      signed: true,
      file: 'gw-clickhouse-0.9.1.jar',
      size: '1.4 MB',
      sha256: clickhouseCheck().report.sha256,
      toolNames: ['ch_query', 'ch_tables', 'ch_describe'],
      allReadOnly: true,
      connectionForm: 'Host, port, user and password, from its config schema',
      access: 'The host of each connection, port 8443',
      unsandboxed: true,
    });
  });

  it('says a plugin runs without a sandbox only when the core reports none (report.sandbox null)', () => {
    const check = clickhouseCheck();
    const withSandbox = (sandbox: string | null | undefined) => pluginFacts({ ...check, report: { ...check.report, sandbox } }, null);
    expect(withSandbox(null).unsandboxed).toBe(true);
    expect(withSandbox('sandbox-exec').unsandboxed).toBe(false);
    expect(withSandbox('landlock').unsandboxed).toBe(false);
    // A core before the K4 review doesn't say: nothing is claimed either way.
    expect(withSandbox(undefined).unsandboxed).toBe(false);
  });

  it('names the file as chosen when the core doesn’t, and doesn’t trust a partial read-only claim', () => {
    const check = clickhouseCheck();
    const facts = pluginFacts(
      { ...check, report: { ...check.report, file: null, name: null, tools: [{ name: 'a_b', declared: { readOnlyHint: true } }, { name: 'c_d', declared: {} }] } },
      'C:\\Downloads\\gw-clickhouse.jar',
    );
    expect(facts.file).toBe('gw-clickhouse.jar');
    expect(facts.name).toBe('clickhouse');
    expect(facts.allReadOnly).toBe(false);
    expect(pluginFacts({ ...check, report: { ...check.report, file: null } }, null).file).toBe('clickhouse-0.9.1.jar');
  });

  it('words the access a manifest asks for', () => {
    expect(accessText({ hosts: ['{connection.host}:15672'] })).toBe('The host of each connection, port 15672');
    expect(accessText({ hosts: ['{connection.host}:5672', '{connection.host}:15672'] })).toBe('The host of each connection, ports 5672 and 15672');
    expect(accessText({ hosts: ['{connection.host}', 'api.example.com'], commands: ['kubectl'] })).toBe(
      'The host of each connection; api.example.com; the commands kubectl',
    );
    expect(accessText({})).toBe('Nothing beyond its connections');
    expect(accessText(null)).toBe('Nothing beyond its connections');
    expect(connectionFormText('Host, port, virtual host, user and password')).toBe('Host, port, virtual host, user and password');
    expect(connectionFormText(['Host', 'Virtual host'])).toBe('Host and virtual host, from its config schema');
    expect(connectionFormText([])).toBeNull();
  });
});

describe('Add MCP server request', () => {
  it('refuses what can’t be sent, field by field', () => {
    expect(mcpRequest('', 'stdio', '', '', []).errors).toEqual({
      name: 'Name the server: lowercase letters, digits and -.',
      command: 'Enter the command that starts the server.',
    });
    expect(mcpRequest('Sentry', 'stdio', 'node "x', '', []).errors).toEqual({
      name: 'Use lowercase letters, digits and -, starting with a letter.',
      command: 'A quote in the command isn’t closed.',
    });
    expect(mcpRequest('linear', 'http', '', 'http://example.com/mcp', []).errors).toEqual({
      url: 'Use an HTTPS URL, starting with https://.',
    });
    expect(mcpRequest('local', 'http', '', 'http://127.0.0.1:3000/mcp', []).errors).toBeNull();
    expect(mcpRequest('a', 'stdio', 'run', '', [{ name: '1BAD' }]).errors?.variables).toBe(
      '1BAD isn\'t a variable name: letters, digits and _, not starting with a digit.',
    );
    expect(mcpRequest('a', 'stdio', 'run', '', [{ name: 'TOKEN' }, { name: 'TOKEN' }]).errors?.variables).toBe('TOKEN is named twice. Give each variable once.');
    expect(mcpRequest('a', 'http', '', 'https://x.dev', [{ name: 'Authorization' }, { name: 'authorization' }]).errors?.variables).toBe(
      'authorization is named twice. Give each header once.',
    );
  });

  it('sends argv and the names only: the core takes no existing secret, and values go with the start, not the review', () => {
    // Review 2026-10-26: `secretRef: <existing secret>` passed the review and then failed every start with 422 "Secret
    // missing" (C10 drops it); a new value travelled to /review, which doesn't need it.
    const r = mcpRequest(' sentry-mcp ', 'stdio', 'npx -y "@sentry/mcp-server@0.12.0"', '', [{ name: 'SENTRY_AUTH_TOKEN' }, { name: ' ' }, { name: 'SENTRY_ORG' }]);
    expect(r.source).toEqual({
      config: {
        name: 'sentry-mcp',
        transport: 'stdio',
        command: ['npx', '-y', '@sentry/mcp-server@0.12.0'],
        env: [{ name: 'SENTRY_AUTH_TOKEN' }, { name: 'SENTRY_ORG' }],
      },
    });
    const http = mcpRequest('linear', 'http', 'ignored', ' https://mcp.linear.app/mcp ', [{ name: 'Authorization' }]);
    expect(http.source).toEqual({ config: { name: 'linear', transport: 'http', url: 'https://mcp.linear.app/mcp', headers: [{ name: 'Authorization' }] } });
  });

  it('turns sign-in options into an OAuth sign-in of the URL, and leaves it out when they are empty', () => {
    const app = mcpRequest('github-mcp', 'http', '', 'https://api.githubcopilot.com/mcp/', [], { clientId: ' Iv23li0abc ', scopes: ' repo  read:org ', callbackPort: '7471' });
    expect(app.source).toEqual({
      config: {
        name: 'github-mcp',
        transport: 'http',
        url: 'https://api.githubcopilot.com/mcp/',
        headers: [],
        auth: { type: 'oauth', clientId: 'Iv23li0abc', scopes: 'repo read:org', callbackPort: 7471 },
      },
    });
    expect(mcpRequest('linear-mcp', 'http', '', 'https://mcp.linear.app/mcp', []).source).toEqual({
      config: { name: 'linear-mcp', transport: 'http', url: 'https://mcp.linear.app/mcp', headers: [] },
    });
    // A command never gets them.
    expect(mcpRequest('a', 'stdio', 'run', '', [], { clientId: 'x', scopes: '', callbackPort: '' }).source).toEqual({
      config: { name: 'a', transport: 'stdio', command: ['run'], env: [] },
    });
  });

  it('adds a Bearer authorization header without sending a token or stale OAuth settings to review', () => {
    const options = { with: 'bearer' as const, clientId: 'invalid client id', scopes: 'invalid "scope"', callbackPort: '80' };
    const headers = [{ name: 'X-Workspace' }];
    expect(mcpRequest('private-mcp', 'http', '', 'https://mcp.example.com/mcp', headers, options).source).toEqual({
      config: { name: 'private-mcp', transport: 'http', url: 'https://mcp.example.com/mcp', headers: [{ name: 'X-Workspace' }, { name: 'Authorization' }] },
    });
    expect(headers).toEqual([{ name: 'X-Workspace' }]);
    // Selecting another mode or transport must not retain the generated header.
    expect(mcpRequest('private-mcp', 'http', '', 'https://mcp.example.com/mcp', headers).source).toEqual({
      config: { name: 'private-mcp', transport: 'http', url: 'https://mcp.example.com/mcp', headers },
    });
    expect(mcpRequest('private-mcp', 'stdio', 'node server.js', '', [], options).source).toEqual({
      config: { name: 'private-mcp', transport: 'stdio', command: ['node', 'server.js'], env: [] },
    });
  });

  it('sends a verified profile’s switch as a header with a fixed value, never a credential or a secret’s name', () => {
    const fixed = [{ name: 'X-MCP-Readonly', text: 'true' }];
    expect(mcpRequest('devops-mcp', 'http', '', 'https://mcp.dev.azure.com/contoso', [], undefined, fixed).source).toEqual({
      config: { name: 'devops-mcp', transport: 'http', url: 'https://mcp.dev.azure.com/contoso', headers: [], fixedHeaders: fixed },
    });
    expect(mcpRequest('devops-mcp', 'stdio', 'npx -y @azure-devops/mcp contoso', '', [], undefined, fixed).source).toEqual({
      config: { name: 'devops-mcp', transport: 'stdio', command: ['npx', '-y', '@azure-devops/mcp', 'contoso'], env: [] },
    });
    expect(mcpRequest('devops-mcp', 'http', '', 'https://mcp.dev.azure.com/contoso', [], undefined, [{ name: 'Authorization', text: 'Bearer x' }]).errors)
      .toEqual({ fixedHeaders: 'Authorization carries a credential: add it as a header whose value the review asks for.' });
    expect(mcpRequest('devops-mcp', 'http', '', 'https://mcp.dev.azure.com/contoso', [{ name: 'x-mcp-readonly' }], undefined, fixed).errors)
      .toEqual({ fixedHeaders: 'X-MCP-Readonly is also a header whose value the review asks for. Keep one.' });
  });

  it('reuses an existing Authorization header for Bearer tokens and still rejects duplicate header names', () => {
    const options = { with: 'bearer' as const, clientId: '', scopes: '', callbackPort: '' };
    const headers = [{ name: 'X-Workspace' }, { name: 'authorization' }];
    expect(mcpRequest('private-mcp', 'http', '', 'https://mcp.example.com/mcp', headers, options).source).toEqual({
      config: { name: 'private-mcp', transport: 'http', url: 'https://mcp.example.com/mcp', headers },
    });
    expect(mcpRequest('private-mcp', 'http', '', 'https://mcp.example.com/mcp', [...headers, { name: 'Authorization' }], options).errors?.variables).toBe(
      'Authorization is named twice. Give each header once.',
    );
  });

  it('refuses sign-in options HTTP or the server can’t take', () => {
    const errors = (o: { clientId?: string; scopes?: string; callbackPort?: string }, headers: { name: string }[] = []) =>
      mcpRequest('x', 'http', '', 'https://x.dev/mcp', headers, { clientId: '', scopes: '', callbackPort: '', ...o }).errors;
    expect(errors({ callbackPort: '80' })?.callbackPort).toBe('Use a port from 1024 to 65535.');
    expect(errors({ callbackPort: 'abc' })?.callbackPort).toBe('Use a port from 1024 to 65535.');
    expect(errors({ clientId: 'a b' })?.clientId).toBe('A client ID has no spaces or control characters.');
    expect(errors({ scopes: 'a "b"' })?.scopes).toBe('Separate scopes with spaces; a scope has no quotes or backslashes.');
    expect(errors({ clientId: 'abc' }, [{ name: 'Authorization' }])?.variables).toBe(
      'The OAuth sign-in sends the Authorization header. Remove that header, or the client ID.',
    );
  });

  it('signs in with Google Cloud credentials: this computer’s sign-in or a key, a service account, a billed project', () => {
    const google = (o: Record<string, string>, url = 'https://bigtableadmin.googleapis.com/mcp', headers: { name: string }[] = []) =>
      mcpRequest('bigtable-admin', 'http', '', url, headers, { with: 'google', clientId: 'ignored', scopes: '', callbackPort: '', ...o });
    expect(google({}).source).toEqual({
      config: { name: 'bigtable-admin', transport: 'http', url: 'https://bigtableadmin.googleapis.com/mcp', headers: [],
        auth: { type: 'google', credentials: { source: 'adc' } } },
    });
    expect(google({ source: 'serviceAccountKey', impersonate: 'gw-mcp@acme-stage.iam.gserviceaccount.com', quotaProject: 'acme-billing',
      scopes: ' https://www.googleapis.com/auth/bigtable.admin ' }).source).toEqual({
      config: { name: 'bigtable-admin', transport: 'http', url: 'https://bigtableadmin.googleapis.com/mcp', headers: [],
        auth: { type: 'google', credentials: { source: 'serviceAccountKey', impersonate: 'gw-mcp@acme-stage.iam.gserviceaccount.com',
          quotaProject: 'acme-billing' }, scopes: 'https://www.googleapis.com/auth/bigtable.admin' } },
    });
    expect(google({ impersonate: 'me@gmail.com' }).errors?.impersonate).toBe('A service account looks like name@project.iam.gserviceaccount.com.');
    expect(google({ quotaProject: 'X' }).errors?.quotaProject).toBe('A project ID has 6 to 30 lowercase letters, digits and -.');
    expect(google({}, undefined, [{ name: 'Authorization' }]).errors?.variables).toBe(
      'Google Cloud credentials send the Authorization header. Remove that header, or sign in another way.',
    );
    // A token with the cloud-platform scope goes to Google's own servers only, whatever else a server says.
    for (const url of ['https://mcp.example.com/mcp', 'https://my-service-abc123.a.run.app/mcp']) {
      expect(google({}, url).errors?.url).toBe(
        'Google Cloud credentials go only to Google’s own servers (https://<service>.googleapis.com). Sign in to this one another way.',
      );
    }
  });

  it('signs requests with AWS credentials for the service and region the URL names, or the ones given', () => {
    const aws = (o: Record<string, string>, url = 'https://aws-mcp.us-east-1.api.aws/mcp') =>
      mcpRequest('aws-mcp', 'http', '', url, [], { with: 'aws', clientId: '', scopes: '', callbackPort: '', ...o });
    expect(aws({}).source).toEqual({
      config: { name: 'aws-mcp', transport: 'http', url: 'https://aws-mcp.us-east-1.api.aws/mcp', headers: [],
        auth: { type: 'aws', credentials: { source: 'profile', profile: 'default' } } },
    });
    expect(aws({ source: 'accessKey', accessKeyId: 'AKIAIOSFODNN7EXAMPLE', roleArn: 'arn:aws:iam::123456789012:role/gw-mcp',
      externalId: 'gw-ext-1a2b3c', region: 'eu-west-1' }).source).toEqual({
      config: { name: 'aws-mcp', transport: 'http', url: 'https://aws-mcp.us-east-1.api.aws/mcp', headers: [],
        auth: { type: 'aws', credentials: { source: 'accessKey', accessKeyId: 'AKIAIOSFODNN7EXAMPLE', roleArn: 'arn:aws:iam::123456789012:role/gw-mcp',
          externalId: 'gw-ext-1a2b3c' }, region: 'eu-west-1' } },
    });
    expect(aws({ source: 'accessKey' }).errors?.accessKeyId).toBe('Enter the access key ID.');
    expect(aws({ source: 'accessKey', accessKeyId: 'AKIA-short' }).errors?.accessKeyId).toBe('An access key ID looks like AKIA… (20 characters).');
    expect(aws({ roleArn: 'gw-mcp' }).errors?.roleArn).toBe('A role ARN looks like arn:aws:iam::123456789012:role/name.');
    expect(aws({ region: 'Mars' }).errors?.region).toBe('A region looks like us-east-1.');
    // A host that names no service and region needs both said.
    expect(aws({}, 'https://mcp.example.com/mcp').errors?.service).toBe(
      'Name the service and region to sign for: the URL doesn’t say, as aws-mcp.us-east-1.api.aws does.',
    );
    expect(aws({ service: 'bedrock-agentcore', region: 'us-west-2' }, 'https://mcp.example.com/mcp').errors).toBeNull();
  });

  it('refuses to review a registry template until its parts are replaced', () => {
    expect(mcpRequest('teams', 'http', '', 'https://agent365.svc.cloud.microsoft/agents/tenants/{tenant_id}/servers/mcp_TeamsServer', []).errors).toEqual({
      url: 'Replace {tenant_id} in the URL with your own value.',
    });
    expect(mcpRequest('acme', 'http', '', 'https://{tenant}.acme.dev/{region}/mcp', []).errors?.url).toBe(
      'Replace {tenant} and {region} in the URL with your own values.',
    );
    expect(mcpRequest('linear-mcp', 'stdio', 'npx -y @someone/linear-mcp@0.3.2 --workspace "<workspace>"', '', []).errors).toEqual({
      command: 'Replace <workspace> in the command with your own value.',
    });
    expect(mcpRequest('gh', 'stdio', 'docker run -i --rm -e GITHUB_TOKEN={token} ghcr.io/github/github-mcp-server', '', []).errors?.command).toBe(
      'Replace {token} in the command with your own value.',
    );
    // JSON in an argument isn't a placeholder.
    expect(mcpRequest('x', 'stdio', `node server.js '{"debug":true}'`, '', []).errors).toBeNull();
  });
});

describe('MCP registry results', () => {
  it('shows the host of a remote, the command of a package, and nothing for what can’t be added', () => {
    const linear = registryResult({ name: 'app.linear/linear', title: 'Linear', status: 'active', packages: [],
      remotes: [{ type: 'streamable-http', url: 'https://mcp.linear.app/mcp', headers: [], variables: [] }] });
    expect(linear).toMatchObject({ title: 'Linear', target: 'mcp.linear.app', use: { kind: 'remote' } });
    const sentry = registryResult({ name: 'io.github.getsentry/sentry-mcp', status: 'active', remotes: [],
      packages: [{ registryType: 'npm', identifier: '@sentry/mcp-server', version: '0.42.0', transport: 'stdio',
        environmentVariables: [], runtimeArguments: [], packageArguments: [] }] });
    expect(sentry).toMatchObject({ title: 'sentry-mcp', target: 'npx @sentry/mcp-server@0.42.0', use: { kind: 'package', runtime: 'npx' } });
    const tenant = registryResult({ name: 'com.example/tenant', status: 'active', packages: [],
      remotes: [{ type: 'streamable-http', url: 'https://{tenant}.example.com/mcp', headers: [], variables: [] }] });
    expect(tenant.target).toBe('{tenant}.example.com');
    expect(registryResult({ name: 'com.example/none', status: 'active', remotes: [], packages: [] })).toMatchObject({ target: '', use: { kind: 'none' } });
  });
});

describe('MCP server values', () => {
  it('sends each typed value at its pointer, as typed, and names the empty ones', () => {
    const secrets = [
      { name: 'A', pointer: '/env/0/secretRef' },
      { name: 'B', pointer: '/env/1/secretRef' },
    ];
    expect(mcpSecretValues(secrets, [' tok en ', 'x'])).toEqual({
      secrets: { '/env/0/secretRef': { source: 'local', value: ' tok en ' }, '/env/1/secretRef': { source: 'local', value: 'x' } },
      missing: [],
    });
    expect(mcpSecretValues(secrets, ['', 'x']).missing).toEqual([0]);
    expect(mcpSecretValues([], [])).toEqual({ secrets: {}, missing: [] });
  });

  it('prefixes only the dedicated Bearer token, accepts an existing prefix and refuses an empty token', () => {
    const secrets = [
      { name: 'X-Workspace', pointer: '/headers/0/secretRef' },
      { name: 'authorization', pointer: '/headers/1/secretRef', label: 'Bearer token', bearer: true },
    ];
    for (const token of [' example-token ', 'Bearer example-token', ' bearer\texample-token ']) {
      expect(mcpSecretValues(secrets, [' workspace ', token])).toEqual({
        secrets: {
          '/headers/0/secretRef': { source: 'local', value: ' workspace ' },
          '/headers/1/secretRef': { source: 'local', value: 'Bearer example-token' },
        },
        missing: [],
      });
    }
    for (const token of ['', '  ', 'Bearer', ' Bearer\t ']) {
      expect(mcpSecretValues(secrets, ['workspace', token])).toEqual({
        secrets: { '/headers/0/secretRef': { source: 'local', value: 'workspace' } },
        missing: [1],
      });
    }
    expect(mcpSecretValues([{ name: 'Authorization', pointer: '/headers/0/secretRef' }], [' Basic example '])).toEqual({
      secrets: { '/headers/0/secretRef': { source: 'local', value: ' Basic example ' } },
      missing: [],
    });
  });
});

describe('MCP server review', () => {
  const typed = (command: string[], review = sentryReview()) => ({
    review,
    source: { config: { name: 'sentry-mcp', transport: 'stdio' as const, command } },
    name: 'sentry-mcp',
    transport: 'stdio' as const,
    target: command.join(' '),
    variables: [{ name: 'SENTRY_AUTH_TOKEN' }],
    opener: null,
  });

  it('shows the command as the core resolved it, its pin and where it runs', () => {
    const w = mcpReviewView(typed(['npx', '-y', '@sentry/mcp-server@0.12.0']));
    expect(w).toMatchObject({
      name: 'sentry-mcp',
      verb: 'Start',
      targetKey: 'Command',
      target: 'npx -y @sentry/mcp-server@0.12.0',
      version: { pinned: true, version: '0.12.0' },
      workdir: '~/.gaterook/mcp/sentry-mcp',
      sandbox: null,
      secrets: [{ name: 'SENTRY_AUTH_TOKEN', pointer: '/env/0/secretRef' }],
    });
    expect(mcpReviewView(typed(['npx', '-y', '@sentry/mcp-server'], sentryReview({ notPinned: true }))).version).toEqual({ pinned: false });
    expect(mcpReviewView(typed(['./server'])).version).toBeNull();
  });

  it('shows the verified profile the core matched, with how its pins stand and the tools it starts', () => {
    const profile = {
      id: 'supabase', name: 'Supabase', publisher: 'Supabase', category: 'Cloud platform', description: '',
      documentation: 'https://supabase.com/docs/guides/getting-started/mcp', registryName: 'com.supabase/mcp', checked: '2026-11-11',
      transport: 'http' as const, url: 'https://mcp.supabase.com/mcp?project_ref={project_ref}&read_only=true', command: [], headers: [],
      placeholders: [], signIn: 'OAuth.', notes: [],
      pins: [{ in: 'query' as const, name: 'read_only', setTo: 'true', purpose: 'Supabase lists only its read tools.', missing: 'Without read_only=true …', set: false }],
      tools: [{ tool: 'execute_sql', start: 'approval' as const, kind: 'write' as const, reason: 'It runs any SQL.' }],
    };
    const url = 'https://mcp.supabase.com/mcp?project_ref=abc';
    const w = mcpReviewView({
      review: sentryReview({ transport: 'http', url, argv: [], env: [], warnings: ['Without read_only=true …'], profile }),
      source: { config: { name: 'supabase-mcp', transport: 'http', url } },
      name: 'supabase-mcp',
      transport: 'http',
      target: url,
      variables: [],
      opener: null,
    });
    expect(w.profile).toBe(profile);
    expect(w.profileStarts).toBe('execute_sql in Approval');
    expect(w.warnings).toEqual(['Without read_only=true …']);
    expect(mcpReviewView(typed(['npx', '-y', '@sentry/mcp-server@0.12.0']))).toMatchObject({ profile: null, profileStarts: '' });
  });

  it('reviews a catalog server by the core’s name, pin and resolved command', () => {
    const w = mcpReviewView({
      review: sentryReview({ name: 'github-mcp', catalogEntry: 'github@0.6.2', argv: ['node', '/Users/me/.gaterook/mcp/github-mcp/dist/index.js'], env: ['GITHUB_PERSONAL_ACCESS_TOKEN'], sandbox: 'landlock' }),
      source: { catalog: 'github@0.6.2' },
      name: 'github',
      transport: 'stdio',
      target: '',
      variables: [],
      opener: null,
    });
    expect(w).toMatchObject({
      name: 'github-mcp',
      target: 'node /Users/me/.gaterook/mcp/github-mcp/dist/index.js',
      version: { pinned: true, version: '0.6.2' },
      sandbox: 'landlock',
      // The entry's variable, named by the core's review: its value goes to /env/0/secretRef with the start.
      secrets: [{ name: 'GITHUB_PERSONAL_ACCESS_TOKEN', pointer: '/env/0/secretRef' }],
    });
  });

  it('connects to an HTTP server and says every call sends the secrets there', () => {
    const w = mcpReviewView({
      review: sentryReview({ transport: 'http', url: 'https://mcp.linear.app/mcp', argv: [] }),
      source: { config: { name: 'linear', transport: 'http', url: 'https://mcp.linear.app/mcp' } },
      name: 'linear',
      transport: 'http',
      target: 'https://mcp.linear.app/mcp',
      variables: [{ name: 'Authorization' }],
      opener: null,
    });
    // sentryReview's env names SENTRY_AUTH_TOKEN; an HTTP review names headers, at /headers/n.
    expect(w).toMatchObject({ verb: 'Connect', targetKey: 'URL', target: 'https://mcp.linear.app/mcp', version: null, secrets: [{ pointer: '/headers/0/secretRef' }] });
    expect(mcpReviewView({ ...typed([]), review: sentryReview({ env: [] }), variables: [{ name: 'X_TOKEN' }] }).secrets).toEqual([
      { name: 'X_TOKEN', pointer: '/env/0/secretRef' },
    ]);
    expect(w.risk).toBe('Every call sends these secrets to this URL. Nobody has verified the server behind it.');
    expect(w.signIn).toBeNull();
  });

  it('shows the core’s URL and warnings for a server at a URL, and a command’s warnings as its rows', () => {
    const keyed = mcpReviewView({
      review: sentryReview({
        transport: 'http',
        url: 'https://mcp.example.com/‹masked›/mcp',
        argv: [],
        env: [],
        warnings: ['The URL carries a key. GateRook stores it with the server’s settings, not as an encrypted secret.'],
      }),
      source: { config: { name: 'search-mcp', transport: 'http', url: 'https://mcp.example.com/k-AbCdEf/mcp' } },
      name: 'search-mcp',
      transport: 'http',
      target: 'https://mcp.example.com/k-AbCdEf/mcp',
      variables: [],
      opener: null,
    });
    // The key the URL carries stays as the core masked it: the review shows the core's URL, not the typed one.
    expect(keyed.target).toBe('https://mcp.example.com/‹masked›/mcp');
    expect(keyed.warnings).toEqual(['The URL carries a key. GateRook stores it with the server’s settings, not as an encrypted secret.']);
    const command = mcpReviewView(typed(['npx', '-y', '@sentry/mcp-server'], sentryReview({ notPinned: true, warnings: ['Not pinned: it can fetch new code on every start.'] })));
    expect(command.warnings).toEqual([]);
    expect(command.version).toEqual({ pinned: false });
  });

  it('labels only the chosen Authorization header as a Bearer token at its existing secret index', () => {
    const reviewed = {
      review: sentryReview({ transport: 'http', url: 'https://mcp.example.com/mcp', argv: [], env: ['X-Workspace', 'authorization'], auth: { kind: 'oauth' as const } }),
      source: { config: { name: 'private-mcp', transport: 'http' as const, url: 'https://mcp.example.com/mcp', headers: [{ name: 'X-Workspace' }, { name: 'authorization' }] } },
      name: 'private-mcp',
      transport: 'http' as const,
      target: 'https://mcp.example.com/mcp',
      variables: [{ name: 'X-Workspace' }, { name: 'authorization' }],
      opener: null,
      bearer: true,
    };
    const w = mcpReviewView(reviewed);
    expect(w.secrets).toEqual([
      { name: 'X-Workspace', pointer: '/headers/0/secretRef' },
      { name: 'authorization', pointer: '/headers/1/secretRef', label: 'Bearer token', bearer: true },
    ]);
    expect(w.signIn).toBeNull();
    expect(mcpReviewView({ ...reviewed, bearer: false }).secrets).toEqual([
      { name: 'X-Workspace', pointer: '/headers/0/secretRef' },
      { name: 'authorization', pointer: '/headers/1/secretRef' },
    ]);
  });

  const remote = (auth: NonNullable<Parameters<typeof sentryReview>[0]>['auth'], headers: string[] = [], clientId?: string) =>
    mcpReviewView({
      review: sentryReview({ transport: 'http', url: 'https://mcp.linear.app/mcp', argv: [], env: headers, auth }),
      source: {
        config: { name: 'linear-mcp', transport: 'http', url: 'https://mcp.linear.app/mcp', ...(clientId ? { auth: { type: 'oauth' as const, clientId } } : {}) },
      },
      name: 'linear-mcp',
      transport: 'http',
      target: 'https://mcp.linear.app/mcp',
      variables: headers.map((name) => ({ name })),
      opener: null,
    });

  it('asks for a sign-in where the server wants one, and says the sign-in goes to the URL', () => {
    const w = remote({ kind: 'oauth', issuer: 'https://mcp.linear.app', authorizationHost: 'mcp.linear.app', registration: 'dynamic' });
    expect(w.signIn).toBe('required');
    expect(w.risk).toBe('Every call sends your sign-in to this URL. Nobody has verified the server behind it.');
    expect(w.trust).toBe('I trust this server with my sign-in');
    const withHeader = remote({ kind: 'oauth', registration: 'dynamic' }, ['X-Workspace']);
    expect(withHeader.risk).toBe('Every call sends your sign-in and these secrets to this URL. Nobody has verified the server behind it.');
    expect(withHeader.trust).toBe('I trust this server with my sign-in and these secrets');
    expect(remote({ kind: 'optional', registration: 'dynamic' }).signIn).toBe('optional');
    // With an optional sign-in, the checkbox is for connecting without one: a sign-in confirms the server by itself.
    expect(remote({ kind: 'optional', registration: 'dynamic' }).trust).toBe('I trust this server with the calls of my AI apps');
    expect(remote({ kind: 'optional', registration: 'dynamic' }, ['X-Workspace']).trust).toBe('I trust this server with these secrets');
    expect(remote({ kind: 'none' }).signIn).toBeNull();
    // Nothing secret goes with the calls of a server without a sign-in or headers: the review doesn't say otherwise.
    expect(remote({ kind: 'none' }).risk).toBe('Every call goes to this URL. Nobody has verified the server behind it.');
    expect(remote({ kind: 'none' }).trust).toBe('I trust this server with the calls of my AI apps');
    const bare = mcpReviewView({ ...typed(['./server']), review: sentryReview({ env: [] }), variables: [] });
    expect(bare.risk).toBe('This code runs on your computer. Nobody has verified it: it can reach anything your user account can.');
    expect(bare.trust).toBe('I trust this code on this computer');
    expect(remote({ kind: 'refused', message: 'It refused the request.' }).signIn).toBeNull();
    // A key the person passes as the Authorization header is their choice instead of the sign-in.
    expect(remote({ kind: 'oauth', registration: 'dynamic' }, ['Authorization']).signIn).toBeNull();
    expect(remote({ kind: 'oauth', registration: 'app' }, [], 'Iv23li0abc').appClient).toBe('Iv23li0abc');
    // GateRook's own app (GitHub): a sign-in with a one-time code, no client of the person's.
    const github = remote({ kind: 'oauth', issuer: 'https://github.com/login/oauth', authorizationHost: 'github.com', registration: 'gaterook-app' });
    expect(github.signIn).toBe('required');
    expect(github.appClient).toBeNull();
    expect(github.secrets).toEqual([]);
  });

  const cloudy = (auth: NonNullable<Parameters<typeof sentryReview>[0]>['auth'], chosen?: McpServerAuthConfig, url = 'https://bigtableadmin.googleapis.com/mcp') =>
    mcpReviewView({
      review: sentryReview({ transport: 'http', url, argv: [], env: [], auth }),
      source: { config: { name: 'bigtable-admin', transport: 'http', url, ...(chosen ? { auth: chosen } : {}) } },
      name: 'bigtable-admin',
      transport: 'http',
      target: url,
      variables: [],
      opener: null,
    });

  it('offers a Google or AWS server its cloud’s credentials, and reviews them as the sign-in', () => {
    // Google gives OAuth only to an app the person registers: its own credentials instead.
    const offered = cloudy({ kind: 'optional', registration: 'app-required', cloud: 'google', secretRequired: true });
    expect(offered.suggest).toBe('google');
    expect(offered.signIn).toBe('optional');

    const adc = cloudy({ kind: 'optional', registration: 'app-required', cloud: 'google' }, { type: 'google', credentials: { source: 'adc', quotaProject: 'acme-billing' } });
    expect(adc.suggest).toBeNull();
    expect(adc.signIn).toBeNull();
    expect(adc.cloud).toBe('google');
    expect(adc.cloudText).toBe('A Google token from the Google sign-in of this computer, billed to acme-billing. GateRook gets a new one before it expires.');
    expect(adc.trust).toBe('I trust this server with my Google Cloud credentials');
    expect(adc.secrets).toEqual([]);

    const keyed = cloudy({ kind: 'optional', cloud: 'google' }, { type: 'google', credentials: { source: 'serviceAccountKey' } });
    expect(keyed.secrets).toEqual([{ name: 'Service account key (JSON)', pointer: '/auth/credentials/serviceAccountKey', label: 'Service account key (JSON)', multiline: true }]);
    expect(keyed.named).toEqual([]);

    const url = 'https://aws-mcp.us-east-1.api.aws/mcp';
    expect(cloudy({ kind: 'optional', registration: 'dynamic', cloud: 'aws' }, undefined, url).suggest).toBe('aws');
    const signed = cloudy({ kind: 'optional', cloud: 'aws' }, { type: 'aws', credentials: { source: 'accessKey', accessKeyId: 'AKIAIOSFODNN7EXAMPLE' } }, url);
    expect(signed.cloudText).toBe('Each request signed with SigV4 for aws-mcp in us-east-1, with the access key AKIAIOSFODNN7EXAMPLE.');
    expect(signed.secrets.map((x) => x.pointer)).toEqual(['/auth/credentials/secretAccessKey']);
    expect(signed.risk).toBe('Every call carries a signature of your AWS credentials to this URL. Nobody has verified the server behind it.');
    // A server elsewhere is offered nothing.
    expect(remote({ kind: 'optional', registration: 'dynamic' }).suggest).toBeNull();
  });

  it('signs in and adds with the same config: the reviewed one with the sign-in and the typed client ID', () => {
    const config = { name: 'github-mcp', transport: 'http' as const, url: 'https://api.githubcopilot.com/mcp/', headers: [] };
    expect(signInConfig(config, null)).toEqual({ ...config, auth: { type: 'oauth' } });
    expect(signInConfig(config, ' Iv23li0abc ')).toEqual({ ...config, auth: { type: 'oauth', clientId: 'Iv23li0abc' } });
    const app = { ...config, auth: { type: 'oauth' as const, clientId: 'from-options', callbackPort: 7471 } };
    expect(signInConfig(app, '')).toEqual(app);
  });
});

describe('MCP server endpoint step', () => {
  const linear = { name: 'linear-mcp', http: true, tools: 23 };

  it('says where the tools went, their default modes, or that they are Off', () => {
    expect(connectedToast(linear, { added: ['dev'], notAdded: [] })).toEqual({
      tone: 'success',
      title: 'MCP server linear-mcp connected',
      body: 'Its 23 tools are on dev. Declared reads start as Read only; other tools start as Approval. Change their modes on the endpoint.',
    });
    expect(connectedToast({ ...linear, tools: 1 }, { added: ['dev', 'stage'], notAdded: [] }).body).toBe(
      'Its tool is on dev and stage. Declared reads start as Read only; other tools start as Approval. Change their modes on the endpoint.',
    );
    expect(connectedToast({ name: 'notes-mcp', http: false, tools: null }, { added: [], notAdded: [] })).toEqual({
      tone: 'success',
      title: 'MCP server notes-mcp started',
      body: 'Its tools are Off until you add them to an endpoint. Declared reads start as Read only; other tools start as Approval.',
    });
  });

  it('names the endpoints the core refused, with its reasons', () => {
    expect(connectedToast(linear, { added: ['dev'], notAdded: [{ slug: 'shared', why: 'Live tokens would cross the prod line.' }] })).toEqual({
      tone: 'warning',
      title: 'MCP server linear-mcp connected, not added to shared',
      body: 'Its 23 tools are on dev with their default modes. Live tokens would cross the prod line.',
    });
  });
});

describe('failure text', () => {
  it('uses the core’s words, a plain line for a missing route and for no answer', () => {
    expect(failureText(new ApiError('conflict', 409, { detail: 'Someone changed it.' }))).toBe('Someone changed it.');
    expect(failureText(new ApiError('not-available', 404, {}), 'Not here yet.')).toBe('Not here yet.');
    expect(failureText(new ApiError('network', 0, {}))).toBe('The core didn’t answer. Check that GateRook is running, then try again.');
    expect(failureText(new Error('boom'))).toBe('boom');
    expect(failureText('x')).toBe('Something went wrong. Try again.');
    expect(failureDetails('GET /api/x', new ApiError('server', 500, { title: 'Server error', detail: 'stack' }))).toBe('GET /api/x → 500\nServer error: stack');
  });
});
