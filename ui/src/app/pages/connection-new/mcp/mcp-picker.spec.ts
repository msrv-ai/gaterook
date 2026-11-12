import { ApiError } from '../../../core/api/api-error';
import { ConnectorCard } from '../../../core/api/connector-types.api';
import { McpProfile, McpRegistryPage, McpRegistryServer } from '../../../core/api/plugins.api';
import { FEATURED_MCP_SERVERS } from '../../connectors/mcp-featured-servers';
import { registryResult } from '../../connectors/mcp-registry-results';
import {
  EXTERNAL_CARD,
  EXTERNAL_KEY,
  MCP_SERVER_TYPE,
  OFFICIAL_REGISTRY,
  PASTE_KEY,
  RegistryFinder,
  featuredCard,
  featuredKey,
  isMcpKey,
  looksLikePaste,
  mcpGroups,
  officialEntry,
  registryEntry,
  registryLabel,
  parseDatabaseUrl,
  pasteGroup,
  profileCard,
  profileKey,
  readPaste,
  registryCard,
  registryKey,
} from './mcp-picker';

/** The MCP section of Add connection's picker: its cards, a paste in its search, and the registry it searches. */

function profile(p: Partial<McpProfile> & { readonly id: string; readonly name: string }): McpProfile {
  return {
    publisher: p.name, category: 'Cloud platform', description: '', documentation: `https://docs.example/${p.id}`, registryName: null,
    checked: '2026-11-11', transport: 'http', url: `https://mcp.${p.id}.example/mcp`, headers: [], command: [], placeholders: [], pins: [],
    signIn: 'OAuth in your browser.', notes: [], tools: [], ...p,
  };
}

const SUPABASE = profile({ id: 'supabase', name: 'Supabase', registryName: 'com.supabase/mcp', url: 'https://mcp.supabase.com/mcp?project_ref={project_ref}&read_only=true' });
const CLOUDFLARE_API = profile({ id: 'cloudflare-api', name: 'Cloudflare API', publisher: 'Cloudflare', url: 'https://mcp.cloudflare.com/mcp' });

/** Entries shaped like the Official MCP Registry's on 2026-11-02. */
const LINEAR: McpRegistryServer = {
  name: 'app.linear/linear', title: 'Linear', version: '1.0.2', description: 'Issues, projects and cycles.', status: 'active', packages: [],
  remotes: [{ type: 'streamable-http', url: 'https://mcp.linear.app/mcp', headers: [], variables: [] }],
};
const IMAGE_ONLY: McpRegistryServer = {
  name: 'io.github.someone/image-only', status: 'deprecated', remotes: [],
  packages: [{ registryType: 'oci', identifier: 'ghcr.io/someone/linear:1', transport: 'stdio', environmentVariables: [], runtimeArguments: [], packageArguments: [] }],
};
const NPM_SERVER: McpRegistryServer = {
  name: 'io.github.someone/linear-mcp', description: 'linear from the command line.', status: 'active', remotes: [],
  packages: [{ registryType: 'npm', identifier: '@someone/linear-mcp', version: '0.3.2', transport: 'stdio',
    environmentVariables: [{ name: 'LINEAR_API_KEY', required: true, secret: true }], runtimeArguments: [], packageArguments: [] }],
};
const FIGMA: McpRegistryServer = {
  name: 'com.figma.mcp/mcp', title: 'Figma', status: 'active', packages: [],
  remotes: [{ type: 'streamable-http', url: 'https://mcp.figma.com/mcp', headers: [], variables: [] }],
};
/** Similarly named integrations first, as the registry orders them, then Stripe's own server. */
const STRIPE_OTHERS: McpRegistryServer[] = [
  { name: 'app.vercel.stripecheckup/stripecheckup', title: 'stripecheckup', status: 'active', packages: [],
    remotes: [{ type: 'streamable-http', url: 'https://stripecheckup.vercel.app', headers: [], variables: [] }] },
  { name: 'com.insightfulpipe/stripe', title: 'Official Stripe integration', status: 'active', packages: [],
    remotes: [{ type: 'streamable-http', url: 'https://stripe.insightfulmcp.com', headers: [], variables: [] }] },
];
const STRIPE: McpRegistryServer = {
  name: 'com.stripe/mcp', title: 'mcp', version: '0.2.4', status: 'active', packages: [],
  remotes: [{ type: 'streamable-http', url: 'https://mcp.stripe.com', headers: [], variables: [] }],
};

const linearPage = (): McpRegistryPage => ({ registry: OFFICIAL_REGISTRY, nextCursor: 'app.linear/linear:1.0.2', servers: [LINEAR, IMAGE_ONLY] });
/** The next page repeats a server of the first one. */
const nextPage = (): McpRegistryPage => ({ registry: OFFICIAL_REGISTRY, nextCursor: null, servers: [LINEAR, NPM_SERVER] });

const POSTGRES: ConnectorCard = {
  type: 'postgres', variant: null, name: 'PostgreSQL', icon: 'database', description: 'Read-only SQL by default.', kind: 'built-in', version: null,
  signed: null, tools: 7, toolsFrom: 'manifest', connections: 0, available: true, reason: null, presetConfig: null, catalogRef: null,
};

const LINEAR_PASTE = '{"mcpServers":{"linear":{"url":"https://mcp.linear.app/mcp","headers":{"X-Api-Key":"lin_api_example_123"}}}}';

describe('MCP section of the picker', () => {
  it('starts with External MCP server, then the verified profiles, then the Popular picks without a profile', () => {
    expect(mcpGroups([]).map((g) => [g.name, g.cards.length])).toEqual([['Any MCP server', 1], ['Popular MCP servers', FEATURED_MCP_SERVERS.length]]);
    const groups = mcpGroups([SUPABASE, CLOUDFLARE_API]);
    expect(groups.map((g) => g.name)).toEqual(['Any MCP server', 'Verified MCP servers', 'Popular MCP servers']);
    expect(groups[0].cards).toEqual([EXTERNAL_CARD]);
    expect(groups[1].cards.map((c) => c.name)).toEqual(['Supabase', 'Cloudflare API']);
    // Supabase's own entry shows once, as its verified card; Cloudflare's documentation server has no profile and stays.
    const popular = groups[2].cards.map((c) => c.name);
    expect(popular).not.toContain('Supabase');
    expect(popular).toContain('Cloudflare');
    expect(popular).toHaveLength(FEATURED_MCP_SERVERS.length - 1);
    expect(groups.flatMap((g) => g.cards).every((c) => c.type === MCP_SERVER_TYPE && c.kind === 'mcp-server')).toBe(true);
  });

  it('enters any server by hand with External MCP server', () => {
    expect(EXTERNAL_CARD).toMatchObject({ type: 'mcp-server', variant: null, name: 'External MCP server', markSrc: 'assets/integrations/mcp.svg',
      metaText: 'MCP server · stdio or HTTP', available: true });
    expect(EXTERNAL_CARD.description).toContain('Paste it into the search above.');
  });

  it('keys each card under the MCP server type', () => {
    expect(EXTERNAL_KEY).toBe('mcp-server');
    expect([EXTERNAL_KEY, PASTE_KEY, profileKey('supabase'), registryKey('app.linear/linear')].every(isMcpKey)).toBe(true);
    expect([null, 'postgres', 'mcp-serverless'].some(isMcpKey)).toBe(false);
    expect(profileKey('supabase')).toBe('mcp-server:profile.supabase');
    expect(featuredKey({ registryName: 'io.github.microsoft/playwright-mcp' })).toBe('mcp-server:featured.io-github-microsoft-playwright-mcp');
    expect(registryKey('app.linear/linear')).toBe('mcp-server:registry.app-linear-linear');
    // A card's variant is its key after the type.
    expect(`${MCP_SERVER_TYPE}:${profileCard(SUPABASE).variant}`).toBe(profileKey('supabase'));
    expect(`${MCP_SERVER_TYPE}:${featuredCard(FEATURED_MCP_SERVERS[0]).variant}`).toBe(featuredKey(FEATURED_MCP_SERVERS[0]));
    expect(`${MCP_SERVER_TYPE}:${registryCard(registryResult(LINEAR, OFFICIAL_REGISTRY)).variant}`).toBe(registryKey('app.linear/linear'));
  });

  const signIns: [string, Partial<McpProfile>, string][] = [
    ['an OAuth sign-in', { signIn: 'OAuth in your browser.' }, 'MCP server · HTTP · Sign-in in your browser'],
    ['no sign-in', { signIn: 'None: it answers without one.' }, 'MCP server · HTTP · No sign-in'],
    ['Microsoft Entra ID', { signIn: 'Microsoft Entra ID in your browser (OAuth).' }, 'MCP server · HTTP · Microsoft Entra ID'],
    ['a token', { signIn: 'A personal access token in a header.' }, 'MCP server · HTTP'],
    ['a command', { transport: 'stdio', url: null, command: ['npx', '-y', '@acme/mcp@2.2.0'] }, 'MCP server · stdio · Runs on this computer'],
  ];
  it.each(signIns)('words a verified profile with %s, badged as verified', (_, over, meta) => {
    const card = profileCard(profile({ id: 'acme', name: 'Acme', ...over }));
    expect(card.metaText).toBe(meta);
    expect(card.badge).toBe('verified');
  });

  it('shows a profile’s vendor mark, on a light tile where the mark turns white in dark themes', () => {
    expect(profileCard(SUPABASE).markSrc).toBe('mcp-logos/supabase.png');
    expect(profileCard(profile({ id: 'digitalocean', name: 'DigitalOcean' })).markSrc).toBe('mcp-logos/digitalocean-on-light.svg');
    expect(profileCard(profile({ id: 'acme', name: 'Acme' })).markSrc).toBe('assets/integrations/mcp.svg');
    // The names a developer types for the vendor's service find its card.
    expect(profileCard(SUPABASE).searchWords).toContain('postgres');
  });

  it('words a Popular pick by its category, with its vendor mark and the names developers type for it', () => {
    const linear = featuredCard(FEATURED_MCP_SERVERS.find((f) => f.name === 'Linear')!);
    expect(linear).toMatchObject({ type: 'mcp-server', variant: 'featured.app-linear-linear', name: 'Linear', markSrc: 'mcp-logos/linear.png',
      metaText: 'MCP server · Project management' });
    expect(linear.badge ?? null).toBeNull();
    expect(featuredCard(FEATURED_MCP_SERVERS.find((f) => f.name === 'GitHub')!).searchWords).toContain('pull requests');
  });

  it('words a registry result by how it runs and its version, badged official only in the Official MCP Registry', () => {
    expect(registryCard(registryResult(LINEAR, OFFICIAL_REGISTRY))).toMatchObject({ name: 'Linear', description: 'Issues, projects and cycles.',
      metaText: 'MCP server · HTTP · v1.0.2', badge: 'official', available: true, reason: null });
    expect(registryCard(registryResult(LINEAR, 'registry.example.com')).badge).toBeNull();
    expect(registryCard(registryResult(NPM_SERVER, OFFICIAL_REGISTRY)).metaText).toBe('MCP server · stdio');
    expect(registryCard(registryResult({ ...NPM_SERVER, version: 'v0.3.2' }, OFFICIAL_REGISTRY)).metaText).toBe('MCP server · stdio · v0.3.2');
    const image = registryCard(registryResult(IMAGE_ONLY, OFFICIAL_REGISTRY));
    expect(image).toMatchObject({ name: 'image-only', metaText: 'MCP server', badge: 'deprecated', available: false });
    expect(image.reason).toContain('It runs as a Docker image, which GateRook doesn’t start from the registry yet.');
  });
});

describe('A paste in the picker’s search', () => {
  it.each([
    'https://mcp.linear.app/mcp',
    'postgres://reader@db.example.com/orders',
    '{"mcpServers":{}}',
    '"linear": {"url": "https://mcp.linear.app/mcp"}',
    'npx -y @playwright/mcp@1.2.3',
    'claude mcp add --transport http linear https://mcp.linear.app/mcp',
    'line one\nline two',
  ])('takes %j for a paste, never a name to search for', (text) => {
    expect(looksLikePaste(text)).toBe(true);
  });

  it.each(['linear', 'post', 'git hub', 'stripe payments', 'mcp-server'])('searches for %j', (text) => {
    expect(looksLikePaste(text)).toBe(false);
    expect(readPaste(text)).toBeNull();
  });

  it('reads the parts of a PostgreSQL connection string, never its password', () => {
    expect(parseDatabaseUrl('postgres://reader:example-pass@db.example.com:6543/orders')).toEqual({
      type: 'postgres', host: 'db.example.com', port: 6543, database: 'orders', user: 'reader', hadPassword: true,
    });
    expect(parseDatabaseUrl('postgresql://db.example.com')).toEqual({
      type: 'postgres', host: 'db.example.com', port: 5432, database: null, user: null, hadPassword: false,
    });
    expect(parseDatabaseUrl('postgres://app%40corp@[::1]/sales%20eu')).toEqual({
      type: 'postgres', host: '::1', port: 5432, database: 'sales eu', user: 'app@corp', hadPassword: false,
    });
    expect(parseDatabaseUrl('mysql://reader@db.example.com/orders')).toBeNull();
    expect(parseDatabaseUrl('https://db.example.com/orders')).toBeNull();
  });

  it('reads a paste as an MCP server, a database, or the reason it can’t', () => {
    expect(readPaste('postgres://reader@db.example.com/orders')).toEqual({ kind: 'db', db: expect.objectContaining({ host: 'db.example.com', database: 'orders' }) });
    expect(readPaste('mysql://reader:example-pass@db.example.com/orders')).toEqual({ kind: 'error',
      message: 'GateRook reads PostgreSQL connection strings and MCP server URLs. Clear the search, then choose the type and fill in where it is.' });
    const mcp = readPaste(LINEAR_PASTE);
    expect(mcp).toEqual({ kind: 'mcp', server: expect.objectContaining({ name: 'linear', transport: 'http', url: 'https://mcp.linear.app/mcp', variables: ['X-Api-Key'] }) });
    expect(JSON.stringify(mcp)).not.toContain('lin_api_example_123');
    expect(readPaste('{"mcpServers": {}}')).toEqual({ kind: 'error', message: 'The configuration names no server.' });
  });

  it('makes a PostgreSQL connection string the PostgreSQL card, filled in where its form has them', () => {
    const group = pasteGroup(readPaste('postgres://reader:example-pass@db.example.com:6543/orders')!, POSTGRES)!;
    expect(group.name).toBe('From your paste');
    expect(group.description).toBe('Host, port, database and user fill Configure. The password in it isn’t copied: you enter it there.');
    expect(group.cards).toEqual([{
      ...POSTGRES,
      variant: 'paste',
      description: 'db.example.com:6543/orders as reader.',
      // The PostgreSQL form's own places: Connection (host, port, the databases agents may use) and Credentials.
      presetConfig: { connection: { host: 'db.example.com', port: 6543, databases: ['orders'] }, credentials: { user: 'reader' } },
      metaText: 'Built-in · 7 tools · from a connection string',
      searchWords: '',
    }]);
    expect(JSON.stringify(group)).not.toContain('example-pass');

    const bare = pasteGroup(readPaste('postgresql://db.example.com')!, { ...POSTGRES, tools: null })!;
    expect(bare.cards[0].description).toBe('db.example.com:5432.');
    expect(bare.cards[0].presetConfig).toEqual({ connection: { host: 'db.example.com', port: 5432 } });
    expect(bare.cards[0].metaText).toBe('Built-in · 4 tools · from a connection string');
    // No PostgreSQL connector on this core, or nothing GateRook can read: no group.
    expect(pasteGroup(readPaste('postgres://db.example.com/orders')!, null)).toBeNull();
    expect(pasteGroup(readPaste('mysql://db.example.com/orders')!, POSTGRES)).toBeNull();
  });

  it('makes a pasted MCP server one card that says which values aren’t copied', () => {
    const group = pasteGroup(readPaste(LINEAR_PASTE)!, POSTGRES)!;
    expect(group.name).toBe('From your paste');
    expect(group.description).toBe('Values of X-Api-Key aren’t copied: the review asks for each one, and GateRook stores it encrypted.');
    expect(group.cards).toEqual([expect.objectContaining({
      type: 'mcp-server', variant: 'paste', name: 'linear', markSrc: 'mcp-logos/linear.png', metaText: 'MCP server · HTTP · Read from your paste',
      description: 'At https://mcp.linear.app/mcp. GateRook signs in to it for you after the review.',
    })]);
    expect(JSON.stringify(group)).not.toContain('lin_api_example_123');
  });

  it('says what a pasted command runs and that only the first of its servers is taken', () => {
    const read = readPaste('{"mcpServers":{"playwright":{"command":"npx","args":["-y","@playwright/mcp@1.2.3"]},"other":{"url":"https://mcp.example.com/mcp"}}}')!;
    const group = pasteGroup(read, null)!;
    expect(group.description).toBe(
      'Values of keys and tokens in it aren’t copied: the review asks for each one, and GateRook stores it encrypted. It named 2 servers; this is the first.');
    expect(group.cards[0]).toMatchObject({ name: 'playwright', markSrc: 'mcp-logos/playwright.svg', metaText: 'MCP server · stdio · Read from your paste',
      description: 'Runs npx -y @playwright/mcp@1.2.3 on this computer, after the review.' });
    expect(pasteGroup(readPaste('{"mcpServers":{"acme":{"url":"https://mcp.acme.example/mcp"}}}')!, null)!.cards[0].markSrc).toBe('assets/integrations/mcp.svg');
  });
});

describe('The registry search of the picker', () => {
  afterEach(() => vi.useRealTimers());

  const flush = async () => {
    for (let i = 0; i < 20; i++) await Promise.resolve();
  };

  function deferred<T>() {
    let resolve!: (v: T) => void;
    const promise = new Promise<T>((res) => (resolve = res));
    return { promise, resolve };
  }

  /** The core's registry search (`GET /api/mcp-registry/servers`): what it was asked, and its answers. */
  function registry(answer: (q: string, cursor: string | null) => McpRegistryPage | Promise<McpRegistryPage>) {
    const asked: string[] = [];
    const search = vi.fn(async (q: string, cursor?: string | null) => {
      asked.push(cursor ? `${q} after ${cursor}` : q);
      return answer(q, cursor ?? null);
    });
    return { finder: new RegistryFinder(search), asked };
  }
  const names = (finder: RegistryFinder) => finder.results().map((r) => r.server.name);

  it('asks from three characters, 350 ms after the last key, and never for a paste', async () => {
    vi.useFakeTimers();
    const { finder, asked } = registry(() => linearPage());
    finder.find('li');
    await vi.advanceTimersByTimeAsync(1000);
    expect(asked).toEqual([]);
    expect(finder.searching()).toBe(false);

    finder.find('lin');
    expect(finder.searching()).toBe(true);
    await vi.advanceTimersByTimeAsync(300);
    finder.find('linear');
    await vi.advanceTimersByTimeAsync(349);
    expect(asked).toEqual([]);
    await vi.advanceTimersByTimeAsync(1);
    await flush();
    expect(asked).toEqual(['linear']);
    expect(finder.searched()).toBe('linear');
    expect(names(finder)).toEqual(['app.linear/linear', 'io.github.someone/image-only']);
    expect(finder.nextCursor()).toBe('app.linear/linear:1.0.2');
    expect(finder.registry()).toBe(OFFICIAL_REGISTRY);
    expect(finder.searching()).toBe(false);

    // The same query isn't asked again as it is typed; Enter asks at once.
    finder.find('linear ');
    await vi.advanceTimersByTimeAsync(1000);
    expect(asked).toEqual(['linear']);
    finder.find('linear', true);
    await flush();
    expect(asked).toEqual(['linear', 'linear']);

    // A paste is never sent to the registry.
    finder.find('https://mcp.linear.app/mcp');
    expect(finder.results()).toEqual([]);
    expect(finder.searched()).toBeNull();
    finder.find('npx -y @playwright/mcp@1.2.3', true);
    await vi.advanceTimersByTimeAsync(1000);
    expect(asked).toEqual(['linear', 'linear']);
  });

  it('drops an answer for an older query, and a search stopped before it ran', async () => {
    const older = deferred<McpRegistryPage>();
    const { finder, asked } = registry((q) => (q === 'linear' ? older.promise : { registry: OFFICIAL_REGISTRY, servers: [FIGMA] }));
    finder.find('linear', true);
    finder.find('figma', true);
    await flush();
    expect(names(finder)).toEqual(['com.figma.mcp/mcp']);
    older.resolve(linearPage());
    await flush();
    expect(finder.searched()).toBe('figma');
    expect(names(finder)).toEqual(['com.figma.mcp/mcp']);
    expect(finder.nextCursor()).toBeNull();

    vi.useFakeTimers();
    finder.find('notion');
    finder.stop();
    await vi.advanceTimersByTimeAsync(1000);
    expect(asked).toEqual(['linear', 'figma']);
    expect(finder.searching()).toBe(false);
  });

  it('drops an official lookup of an older query that ends after a newer query’s answer', async () => {
    const lookup = deferred<McpRegistryPage>();
    const { finder } = registry((q) => (q === 'com.stripe/mcp' ? lookup.promise : q === 'stripe' ? { registry: OFFICIAL_REGISTRY, servers: STRIPE_OTHERS } : linearPage()));
    finder.find('stripe', true);
    await flush();
    finder.find('linear', true);
    await flush();
    lookup.resolve({ registry: OFFICIAL_REGISTRY, servers: [STRIPE] });
    await flush();
    expect(finder.searched()).toBe('linear');
    expect(names(finder)).toEqual(['app.linear/linear', 'io.github.someone/image-only']);
  });

  it('puts an official publisher first, looking up its exact name when the page doesn’t have it', async () => {
    const { finder, asked } = registry((q) => (q === 'com.stripe/mcp'
      ? { registry: OFFICIAL_REGISTRY, servers: [STRIPE_OTHERS[0], STRIPE] }
      : { registry: OFFICIAL_REGISTRY, servers: STRIPE_OTHERS }));
    finder.find('stripe', true);
    await flush();
    expect(asked).toEqual(['stripe', 'com.stripe/mcp']);
    expect(finder.results().map((r) => [r.server.name, r.officialPublisher?.name ?? null])).toEqual([
      ['com.stripe/mcp', 'Stripe'],
      ['app.vercel.stripecheckup/stripecheckup', null],
      ['com.insightfulpipe/stripe', null],
    ]);
  });

  it('trusts no publisher of another registry and looks none up there', async () => {
    const { finder, asked } = registry(() => ({ registry: 'registry.example.com', servers: [...STRIPE_OTHERS, STRIPE] }));
    finder.find('stripe', true);
    await flush();
    expect(asked).toEqual(['stripe']);
    expect(names(finder)).toEqual(['app.vercel.stripecheckup/stripecheckup', 'com.insightfulpipe/stripe', 'com.stripe/mcp']);
    expect(finder.results().every((r) => r.officialPublisher === null)).toBe(true);
    expect(finder.registry()).toBe('registry.example.com');
  });

  it('keeps the ordinary results when the official lookup fails', async () => {
    const { finder } = registry((q) => {
      if (q === 'com.stripe/mcp') throw new Error('Lookup unavailable');
      return { registry: OFFICIAL_REGISTRY, servers: STRIPE_OTHERS };
    });
    finder.find('stripe', true);
    await flush();
    expect(finder.error()).toBeNull();
    expect(names(finder)).toEqual(['app.vercel.stripecheckup/stripecheckup', 'com.insightfulpipe/stripe']);
  });

  it('adds the next page without repeating a server, and names the first one it added', async () => {
    const { finder, asked } = registry((_, cursor) => (cursor ? nextPage() : linearPage()));
    finder.find('linear', true);
    await flush();
    const first = await finder.more();
    expect(asked).toEqual(['linear', 'linear after app.linear/linear:1.0.2']);
    expect(names(finder)).toEqual(['app.linear/linear', 'io.github.someone/image-only', 'io.github.someone/linear-mcp']);
    expect(first?.server.name).toBe('io.github.someone/linear-mcp');
    expect(finder.nextCursor()).toBeNull();
    expect(await finder.more()).toBeNull();
    expect(asked).toHaveLength(2);
  });

  it('ranks an official server of a later page first, and names it as the first one added', async () => {
    const { finder } = registry((q, cursor) => (cursor
      ? { registry: OFFICIAL_REGISTRY, nextCursor: null, servers: [STRIPE_OTHERS[0], STRIPE] }
      : q === 'com.stripe/mcp' ? Promise.reject(new Error('Lookup unavailable'))
        : { registry: OFFICIAL_REGISTRY, nextCursor: 'next-stripe-page', servers: STRIPE_OTHERS }));
    finder.find('stripe', true);
    await flush();
    expect(names(finder)).toHaveLength(2);
    const first = await finder.more();
    expect(names(finder)).toEqual(['com.stripe/mcp', 'app.vercel.stripecheckup/stripecheckup', 'com.insightfulpipe/stripe']);
    expect(first?.server.name).toBe('com.stripe/mcp');
  });

  it('adds no page of the previous search while a new one is on its way', async () => {
    const figma = deferred<McpRegistryPage>();
    const { finder, asked } = registry((q, cursor) => (q === 'figma' ? figma.promise : cursor ? nextPage() : linearPage()));
    finder.find('linear', true);
    await flush();
    finder.find('figma', true);
    expect(await finder.more()).toBeNull();
    expect(asked).toEqual(['linear', 'figma']);
    figma.resolve({ registry: OFFICIAL_REGISTRY, servers: [FIGMA] });
    await flush();
    expect(names(finder)).toEqual(['com.figma.mcp/mcp']);
  });

  it('says why the registry didn’t answer, and asks again on retry', async () => {
    let fail = true;
    const { finder, asked } = registry(() => {
      if (fail) throw new ApiError('server', 502, { detail: 'registry.modelcontextprotocol.io answered HTTP 503.' });
      return linearPage();
    });
    finder.find('linear', true);
    await flush();
    expect(finder.error()).toBe('registry.modelcontextprotocol.io answered HTTP 503.');
    expect(finder.results()).toEqual([]);
    expect(finder.searched()).toBeNull();
    expect(finder.searching()).toBe(false);
    fail = false;
    finder.retry();
    await flush();
    expect(finder.error()).toBeNull();
    expect(names(finder)).toEqual(['app.linear/linear', 'io.github.someone/image-only']);
    expect(asked).toEqual(['linear', 'linear']);
  });

  it('says a core without the registry route can’t search it', async () => {
    const { finder } = registry(() => {
      throw new ApiError('not-available', 404, {});
    });
    finder.find('linear', true);
    await flush();
    expect(finder.error()).toBe('This build of the gateway core can’t search the MCP registry.');
  });

  it('keeps the results when the next page fails, and adds it when asked again', async () => {
    let fail = true;
    const { finder } = registry((_, cursor) => {
      if (cursor && fail) throw new Error('Next page unavailable');
      return cursor ? nextPage() : linearPage();
    });
    finder.find('linear', true);
    await flush();
    expect(await finder.more()).toBeNull();
    expect(finder.error()).toBe('Next page unavailable');
    expect(names(finder)).toHaveLength(2);
    expect(finder.moreBusy()).toBe(false);
    fail = false;
    expect((await finder.more())?.server.name).toBe('io.github.someone/linear-mcp');
    expect(names(finder)).toHaveLength(3);
  });
});

describe('A shortcut to an entry of the Official MCP Registry', () => {
  const answering = (page: McpRegistryPage) => vi.fn(async (_: string) => page);

  it('takes the exact active entry by its name, never a similarly named one', async () => {
    const search = answering({ registry: OFFICIAL_REGISTRY, servers: [{ ...LINEAR, name: 'app.linear/linear-beta' }, LINEAR] });
    const result = await officialEntry(search, 'app.linear/linear');
    expect(search).toHaveBeenCalledWith('app.linear/linear');
    expect(result.server).toBe(LINEAR);
    expect(result.officialPublisher?.name).toBe('Linear');
    expect(result.use.kind).toBe('remote');
  });

  const refusals: [string, McpRegistryPage, string][] = [
    ['an entry of another registry', { registry: 'registry.example.com', servers: [LINEAR] },
      'This shortcut requires the Official MCP Registry. Search your configured registry or enter the server by hand.'],
    ['a missing entry', { registry: OFFICIAL_REGISTRY, servers: [{ ...LINEAR, name: 'io.github.someone/linear' }] },
      'This server is no longer available in the Official MCP Registry. Search for another server or enter it by hand.'],
    ['a deprecated entry', { registry: OFFICIAL_REGISTRY, servers: [{ ...LINEAR, status: 'deprecated' }] },
      'This server is no longer available in the Official MCP Registry. Search for another server or enter it by hand.'],
    ['an entry GateRook can’t run', { registry: OFFICIAL_REGISTRY, servers: [{ ...LINEAR, remotes: [] }] },
      'Its entry names no URL or package to run.'],
  ];
  it.each(refusals)('refuses %s with the reason', async (_, page, reason) => {
    await expect(officialEntry(answering(page), 'app.linear/linear')).rejects.toThrow(reason);
  });
});

describe('A search result opened by its name (?registryServer=)', () => {
  const answering = (page: McpRegistryPage) => vi.fn(async (_: string) => page);

  it('takes the exact entry from the registry the core searches, a configured one or a deprecated entry too', async () => {
    const deprecated = { ...LINEAR, status: 'deprecated' as const };
    const search = answering({ registry: 'registry.example.com', servers: [{ ...LINEAR, name: 'app.linear/linear-beta' }, deprecated] });
    const { result, registry } = await registryEntry(search, 'app.linear/linear');
    expect(search).toHaveBeenCalledWith('app.linear/linear');
    expect(result.server).toBe(deprecated);
    expect(registry).toBe('registry.example.com');
  });

  it.each([
    ['a missing entry', { registry: 'registry.example.com', servers: [{ ...LINEAR, name: 'io.github.someone/linear' }] },
      'The MCP registry at registry.example.com no longer lists this server. Search for another server or enter it by hand.'],
    ['a missing official entry', { registry: OFFICIAL_REGISTRY, servers: [] },
      'The Official MCP Registry no longer lists this server. Search for another server or enter it by hand.'],
    ['an entry GateRook can’t run', { registry: 'registry.example.com', servers: [{ ...LINEAR, remotes: [] }] },
      'Its entry names no URL or package to run.'],
  ] as [string, McpRegistryPage, string][])('refuses %s with the reason', async (_, page, reason) => {
    await expect(registryEntry(answering(page), 'app.linear/linear')).rejects.toThrow(reason);
  });

  it('names the registry: the official one by default, another by its host', () => {
    expect(registryLabel(null)).toBe('the Official MCP Registry');
    expect(registryLabel(OFFICIAL_REGISTRY)).toBe('the Official MCP Registry');
    expect(registryLabel('registry.example.com')).toBe('the MCP registry at registry.example.com');
  });
});
