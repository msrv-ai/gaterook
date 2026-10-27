// The TypeScript SDK 1.32.1 as an OAuth client of the core's local authorization server (ARCHITECTURE.md §10 K3):
// StreamableHTTPClientTransport with an OAuthClientProvider, as Cursor-like clients use it. It discovers the
// protected resource metadata from the 401, the authorization server, registers itself (DCR, loopback redirect),
// and hands the authorization URL to the test, which plays the person's browser and their Allow in the app; the
// test writes the redirect it got back on stdin. One JSON line per event on stdout.
//
// Env: MCP_URL (http://127.0.0.1:<port>/mcp/<slug>), MCP_REDIRECT (http://127.0.0.1:<port>/callback).
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';
import { UnauthorizedError } from '@modelcontextprotocol/sdk/client/auth.js';
import readline from 'node:readline';

const url = new URL(process.env.MCP_URL);
const redirect = process.env.MCP_REDIRECT;
const emit = (o) => process.stdout.write(JSON.stringify(o) + '\n');
const input = readline.createInterface({ input: process.stdin });
const nextLine = () => new Promise((resolve) => input.once('line', resolve));

class Provider {
  info = undefined;
  saved = undefined;
  verifier = undefined;
  discovery = undefined;

  get redirectUrl() {
    return redirect;
  }

  get clientMetadata() {
    return {
      client_name: 'MCP TypeScript SDK 1.32.1',
      redirect_uris: [redirect],
      grant_types: ['authorization_code', 'refresh_token'],
      response_types: ['code'],
      token_endpoint_auth_method: 'none',
    };
  }

  state() {
    return 'sdk-state-1';
  }

  clientInformation() {
    return this.info;
  }

  saveClientInformation(info) {
    this.info = info;
    emit({ event: 'registered', client_id: info.client_id, auth: info.token_endpoint_auth_method ?? null });
  }

  tokens() {
    return this.saved;
  }

  saveTokens(tokens) {
    this.saved = tokens;
    // Never the tokens themselves: their kind and what they grant.
    emit({ event: 'tokens', access: tokens.access_token.slice(0, 4), refresh: (tokens.refresh_token ?? '').slice(0, 4),
      scope: tokens.scope ?? null, expiresIn: tokens.expires_in ?? null });
  }

  redirectToAuthorization(authorizationUrl) {
    emit({ event: 'authorize', url: authorizationUrl.toString() });
  }

  saveCodeVerifier(verifier) {
    this.verifier = verifier;
  }

  codeVerifier() {
    return this.verifier;
  }

  saveDiscoveryState(state) {
    this.discovery = state;
  }

  discoveryState() {
    return this.discovery;
  }
}

const provider = new Provider();
try {
  await new Client({ name: 'sdk-oauth', version: '1.0.0' })
    .connect(new StreamableHTTPClientTransport(url, { authProvider: provider }));
  emit({ event: 'error', message: 'connected without signing in' });
  process.exit(2);
} catch (e) {
  if (!(e instanceof UnauthorizedError)) {
    emit({ event: 'error', message: String(e?.stack ?? e) });
    process.exit(3);
  }
}

const back = new URL(await nextLine());
emit({ event: 'callback', state: back.searchParams.get('state'), iss: back.searchParams.get('iss'),
  error: back.searchParams.get('error') });
const transport = new StreamableHTTPClientTransport(url, { authProvider: provider });
await transport.finishAuth(back.searchParams.get('code'));
const client = new Client({ name: 'sdk-oauth', version: '1.0.0' });
await client.connect(transport);
emit({ event: 'connected', server: client.getServerVersion()?.name ?? null, protocol: transport.protocolVersion ?? null });
const tools = await client.listTools();
emit({ event: 'tools', names: tools.tools.map((t) => t.name) });
await client.close();
input.close();
process.exit(0);
