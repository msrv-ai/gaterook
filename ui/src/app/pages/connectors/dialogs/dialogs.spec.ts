import { ApiError } from '../../../core/api/api-error';
import { clickhouseCheck, sentryReview } from '../connectors.fixtures';
import { failureDetails, failureText } from './failure';
import { mcpRequest } from './mcp-add.dialog';
import { mcpReviewView, mcpSecretValues } from './mcp-review.dialog';
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
    });
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
      url: 'Use an HTTPS URL, or http://127.0.0.1 for a server on this computer.',
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
      workdir: '~/.mcp-gateway/mcp/sentry-mcp',
      sandbox: null,
      secrets: [{ name: 'SENTRY_AUTH_TOKEN', pointer: '/env/0/secretRef' }],
    });
    expect(mcpReviewView(typed(['npx', '-y', '@sentry/mcp-server'], sentryReview({ notPinned: true }))).version).toEqual({ pinned: false });
    expect(mcpReviewView(typed(['./server'])).version).toBeNull();
  });

  it('reviews a catalog server by the core’s name, pin and resolved command', () => {
    const w = mcpReviewView({
      review: sentryReview({ name: 'github-mcp', catalogEntry: 'github@0.6.2', argv: ['node', '/Users/me/.mcp-gateway/mcp/github-mcp/dist/index.js'], env: ['GITHUB_PERSONAL_ACCESS_TOKEN'], sandbox: 'landlock' }),
      source: { catalog: 'github@0.6.2' },
      name: 'github',
      transport: 'stdio',
      target: '',
      variables: [],
      opener: null,
    });
    expect(w).toMatchObject({
      name: 'github-mcp',
      target: 'node /Users/me/.mcp-gateway/mcp/github-mcp/dist/index.js',
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
  });
});

describe('failure text', () => {
  it('uses the core’s words, a plain line for a missing route and for no answer', () => {
    expect(failureText(new ApiError('conflict', 409, { detail: 'Someone changed it.' }))).toBe('Someone changed it.');
    expect(failureText(new ApiError('not-available', 404, {}), 'Not here yet.')).toBe('Not here yet.');
    expect(failureText(new ApiError('network', 0, {}))).toBe('The core didn’t answer. Check that MCP Gateway is running, then try again.');
    expect(failureText(new Error('boom'))).toBe('boom');
    expect(failureText('x')).toBe('Something went wrong. Try again.');
    expect(failureDetails('GET /api/x', new ApiError('server', 500, { title: 'Server error', detail: 'stack' }))).toBe('GET /api/x → 500\nServer error: stack');
  });
});
