import { Component, OutputRef, Type, viewChild } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { ApiError } from '../../../core/api/api-error';
import { ConnectionPreview, EndpointSummary, EndpointsApi } from '../../../core/api/endpoints.api';
import {
  McpProfile,
  McpRegistryPage,
  McpRegistryServer,
  McpServerAuthReview,
  McpServerReview,
  McpServerSource,
  McpServerStarted,
  McpServersApi,
  McpSignIn,
  McpSignInSource,
} from '../../../core/api/plugins.api';
import { Env } from '../../../core/api/types';
import { DesktopBridge } from '../../../core/desktop/desktop-bridge';
import { EventStream } from '../../../core/events/event-stream';
import { AriaDisabledGuard } from '../../../kit/aria-disabled';
import { ClipboardWriter } from '../../../kit/clipboard';
import { provideKitIcons } from '../../../kit/icons';
import { ToastService } from '../../../kit/toast.service';
import { endpoints, sentryReview } from '../../connectors/connectors.fixtures';
import { ConnectorsData } from '../../connectors/connectors.data';
import { parsePastedServer, PastedServer } from '../../connectors/mcp-paste';
import { registryResult } from '../../connectors/mcp-registry-results';
import { GwMcpConfigure, McpReviewed } from './mcp-configure';
import { GwMcpEndpoints, McpServerConnected } from './mcp-endpoints';
import { GwMcpReview } from './mcp-review';

/**
 * The MCP steps of Add connection against a fake core: Configure (what the picker's card filled in, and what the
 * review is asked about), Review (what runs, the values typed there and the sign-in that connects it) and Add to
 * endpoint (where its tools go).
 */

class FakeEvents {
  readonly handlers = new Map<string, Set<(data: unknown) => void>>();
  on(type: string, h: (data: unknown) => void): () => void {
    if (!this.handlers.has(type)) this.handlers.set(type, new Set());
    this.handlers.get(type)!.add(h);
    return () => this.handlers.get(type)!.delete(h);
  }
  emit(type: string, data: unknown): void {
    this.handlers.get(type)?.forEach((h) => h(data));
  }
}

function deferred<T>() {
  let resolve!: (v: T) => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

/** The core's warning for a URL that carries a key (McpServerService.KEY_IN_URL). */
const KEY_IN_URL =
  "The URL carries a key. GateRook stores it with the server's settings, not as an encrypted secret, and the provider or a proxy may log it. If the server accepts the key in a header, add that header instead.";

let calls: string[];
let bodies: Record<string, unknown>;
let events: FakeEvents;
let data: {
  reviewServer: ReturnType<typeof vi.fn<(source: McpServerSource) => Promise<McpServerReview>>>;
  addServer: ReturnType<typeof vi.fn<(source: McpServerSource) => Promise<McpServerStarted>>>;
  startSignIn: ReturnType<typeof vi.fn<(source: McpSignInSource) => Promise<McpSignIn>>>;
  searchRegistry: ReturnType<typeof vi.fn<(search: string, cursor?: string | null) => Promise<McpRegistryPage>>>;
  connections: { refresh: ReturnType<typeof vi.fn<() => Promise<void>>> };
};
let servers: {
  signIn: ReturnType<typeof vi.fn<(id: string) => Promise<McpSignIn>>>;
  cancelSignIn: ReturnType<typeof vi.fn<(id: string) => Promise<void>>>;
  finishSignIn: ReturnType<typeof vi.fn<(id: string, address: string) => Promise<McpSignIn>>>;
};
let endpointsApi: {
  list: ReturnType<typeof vi.fn<() => Promise<EndpointSummary[]>>>;
  previewConnection: ReturnType<typeof vi.fn<(slug: string, connection: string) => Promise<ConnectionPreview>>>;
  addConnection: ReturnType<typeof vi.fn<(slug: string, connection: string) => Promise<unknown>>>;
};

/** The tools a new server lists, as the core would start them on an endpoint. */
function preview(connection: string, over: Partial<ConnectionPreview> = {}): ConnectionPreview {
  return {
    connection,
    env: 'dev',
    tools: [
      { toolKey: `${connection}:find_issues`, tool: 'find_issues', mode: 'READ_ONLY', locked: null, declaredBy: null },
      { toolKey: `${connection}:update_issue`, tool: 'update_issue', mode: 'APPROVAL', locked: null, declaredBy: null },
    ],
    warnings: [],
    refusal: null,
    ...over,
  };
}

function setup(): void {
  calls = [];
  bodies = {};
  events = new FakeEvents();
  const record = (name: string, body?: unknown) => {
    calls.push(name);
    if (body !== undefined) bodies[name] = body;
  };
  data = {
    // As the core reviews (§7.5): over HTTP with the URL's key masked and a warning; a catalog entry with its own name,
    // pin, resolved command and variable (K6); a command with the variables it asks for.
    reviewServer: vi.fn(async (source: McpServerSource) => {
      record('review', source);
      if ('catalog' in source) {
        return sentryReview({ name: 'github-mcp', catalogEntry: source.catalog, argv: ['node', '/Users/me/.gaterook/mcp/github-mcp/dist/index.js'],
          resolvedCommand: 'node /Users/me/.gaterook/mcp/github-mcp/dist/index.js', env: ['GITHUB_PERSONAL_ACCESS_TOKEN'] });
      }
      if (source.config.transport === 'http') {
        const url = source.config.url ?? '';
        return sentryReview({ name: source.config.name, transport: 'http', url: url.replace(/apiKey=[^&#]*/, 'apiKey=‹masked›'), argv: [],
          env: (source.config.headers ?? []).map((h) => h.name), auth: { kind: 'none' }, warnings: /[?&]apiKey=/.test(url) ? [KEY_IN_URL] : [] });
      }
      return sentryReview({ argv: source.config.command ?? [], env: (source.config.env ?? []).map((v) => v.name) });
    }),
    addServer: vi.fn(async (source: McpServerSource) => {
      record('add-server', source);
      return { name: 'catalog' in source ? 'github-mcp' : source.config.name, state: 'running', tools: 12 };
    }),
    startSignIn: vi.fn(async (source: McpSignInSource) => {
      record('start-sign-in', source);
      return { id: 'sign-in-1', server: source.config.name, state: 'waiting', authorizationHost: 'mcp.example.com' } satisfies McpSignIn;
    }),
    searchRegistry: vi.fn(async () => ({ registry: 'registry.modelcontextprotocol.io', servers: [] })),
    connections: { refresh: vi.fn(async () => undefined) },
  };
  servers = {
    signIn: vi.fn(async (id: string) => ({ id, server: 'sentry-mcp', state: 'signed-in', authorizationHost: 'mcp.example.com' }) satisfies McpSignIn),
    cancelSignIn: vi.fn(async (id: string) => void record(`cancel-sign-in ${id}`)),
    finishSignIn: vi.fn(async (id: string) => ({ id, server: 'sentry-mcp', state: 'signed-in' }) satisfies McpSignIn),
  };
  endpointsApi = {
    list: vi.fn(async () => endpoints()),
    previewConnection: vi.fn(async (slug: string, connection: string) => {
      record(`preview ${slug} ${connection}`);
      return preview(connection);
    }),
    addConnection: vi.fn(async (slug: string, connection: string) => {
      record(`add-to-endpoint ${slug} ${connection}`);
      return {};
    }),
  };
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      provideKitIcons(),
      { provide: ConnectorsData, useValue: data },
      { provide: McpServersApi, useValue: servers },
      { provide: EndpointsApi, useValue: endpointsApi },
      { provide: EventStream, useValue: events },
    ],
  });
  TestBed.inject(AriaDisabledGuard).install();
}

afterEach(() => {
  document.body.innerHTML = '';
  TestBed.inject(ToastService).clear();
});

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

async function settle(fixture: ComponentFixture<unknown>): Promise<void> {
  for (let i = 0; i < 5; i++) {
    await fixture.whenStable();
    await sleep(0);
  }
  fixture.detectChanges();
  await fixture.whenStable();
}

/** Each step on its own `<form>`, as the wizard has it: its buttons submit that form. */
@Component({ imports: [GwMcpConfigure], template: '<form gwMcpConfigure aria-label="Configure"></form>' })
class ConfigureHost {
  readonly step = viewChild.required(GwMcpConfigure);
}

@Component({ imports: [GwMcpReview], template: '<form gwMcpReview aria-label="Review"></form>' })
class ReviewHost {
  readonly step = viewChild.required(GwMcpReview);
}

@Component({ imports: [GwMcpEndpoints], template: '<form gwMcpEndpoints aria-label="Add to endpoint"></form>' })
class EndpointsHost {
  readonly step = viewChild.required(GwMcpEndpoints);
}

async function mount<H extends { step: () => unknown }>(host: Type<H>): Promise<{ fixture: ComponentFixture<H>; el: HTMLElement; cmp: ReturnType<H['step']> }> {
  const fixture = TestBed.createComponent(host);
  document.body.appendChild(fixture.nativeElement);
  await settle(fixture);
  const el = (fixture.nativeElement as HTMLElement).querySelector('form')!;
  return { fixture, el, cmp: fixture.componentInstance.step() as ReturnType<H['step']> };
}

/** What a step's output emitted so far. */
function collect<T>(out: OutputRef<T>): T[] {
  const seen: T[] = [];
  out.subscribe((v) => seen.push(v));
  return seen;
}

const text = (el: Element | null | undefined) => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();
const toasts = () => TestBed.inject(ToastService).toasts();

function button(root: Element | null, label: string): HTMLButtonElement {
  const found = Array.from(root?.querySelectorAll<HTMLButtonElement>('button') ?? []).find((b) => text(b).startsWith(label));
  if (!found) throw new Error(`No button "${label}"`);
  return found;
}

/** A step's own field by the end of its id: `name`, `url`, `part-0`, `var-name-0`, `value-0`, `client-secret`. */
function field<E extends HTMLElement = HTMLInputElement>(root: Element, suffix: string): E {
  const found = root.querySelector<E>(`[id$="-${suffix}"]`);
  if (!found) throw new Error(`No field "${suffix}"`);
  return found;
}

function type(input: HTMLInputElement | HTMLTextAreaElement, value: string): void {
  input.value = value;
  input.dispatchEvent(new Event('input', { bubbles: true }));
}

function check(box: HTMLInputElement, checked = true): void {
  box.checked = checked;
  box.dispatchEvent(new Event('change', { bubbles: true }));
}

const radio = (root: Element, group: string, value: string) => root.querySelector<HTMLInputElement>(`input[name$="-${group}"][value="${value}"]`)!;
const rows = (root: Element) =>
  Object.fromEntries(Array.from(root.querySelectorAll('.gw-kv__row')).map((r) => [text(r.querySelector('dt')), text(r.querySelector('dd'))]));
const labelOf = (root: Element, input: Element) => text(root.querySelector(`label[for="${input.id}"]`));

/** GateRook's verified profiles as `GET /api/mcp-profiles` answers (§7.5). */
function profile(p: Partial<McpProfile> & { readonly id: string; readonly name: string }): McpProfile {
  return {
    publisher: p.name, category: 'Cloud platform', description: '', documentation: `https://docs.example/${p.id}`, registryName: null,
    checked: '2026-11-11', transport: 'http', url: null, headers: [], command: [], placeholders: [], pins: [], signIn: 'OAuth in your browser.',
    notes: [], tools: [], ...p,
  };
}

const SUPABASE = profile({
  id: 'supabase', name: 'Supabase', registryName: 'com.supabase/mcp',
  url: 'https://mcp.supabase.com/mcp?project_ref={project_ref}&read_only=true',
  placeholders: [{ name: 'project_ref', description: 'Your project’s reference, from its dashboard URL.' }],
  pins: [{ in: 'query', name: 'read_only', setTo: 'true', purpose: 'Supabase lists only its read tools.', missing: 'Without read_only=true, Supabase lists its writes too.', set: null }],
});

/** A fictional vendor whose profile pins its read-only switch as a header with a fixed value. */
const ACME = profile({
  id: 'acme', name: 'Acme Cloud', publisher: 'Acme', url: 'https://mcp.acme.example/mcp', headers: [{ name: 'X-MCP-Readonly', text: 'true' }],
  pins: [{ in: 'header', name: 'X-MCP-Readonly', setTo: 'true', purpose: 'Acme lists only its read tools.', missing: 'Without it, Acme lists its writes too.', set: null }],
});

const LINEAR: McpRegistryServer = {
  name: 'app.linear/linear', title: 'Linear', version: '1.0.2', description: 'Issues, projects and cycles.', status: 'active', packages: [],
  remotes: [{ type: 'streamable-http', url: 'https://mcp.linear.app/mcp', headers: [], variables: [] }],
};
const PLAYWRIGHT: McpRegistryServer = {
  name: 'io.github.microsoft/playwright-mcp', title: 'Playwright', status: 'active', remotes: [],
  packages: [{ registryType: 'npm', identifier: '@playwright/mcp', version: '1.2.3', transport: 'stdio', environmentVariables: [], runtimeArguments: [], packageArguments: [] }],
};

function pasted(snippet: string): PastedServer {
  const r = parsePastedServer(snippet);
  if ('error' in r) throw new Error(r.error);
  return r;
}

describe('MCP server Configure', () => {
  async function configure() {
    setup();
    const mounted = await mount(ConfigureHost);
    return { ...mounted, reviewed: collect(mounted.cmp.reviewed) };
  }

  it('refuses an empty External MCP server at its first field, then asks the review about the names only, in its environment', async () => {
    const { fixture, el, cmp, reviewed } = await configure();
    cmp.load({ origin: 'external' }, 'stage');
    await settle(fixture);
    expect(radio(el, 'env', 'stage').checked).toBe(true);
    expect(radio(el, 'transport', 'stdio').checked).toBe(true);

    button(el, 'Continue').click();
    await settle(fixture);
    expect(text(el.querySelector('.gw-field__error'))).toBe('Name the server: lowercase letters, digits and -.');
    expect(field(el, 'name').getAttribute('aria-invalid')).toBe('true');
    expect(document.activeElement).toBe(field(el, 'name'));
    expect(calls).not.toContain('review');

    type(field(el, 'name'), 'sentry-mcp');
    type(field(el, 'command'), 'npx -y @sentry/mcp-server@0.12.0');
    button(el, 'Add variable').click();
    await settle(fixture);
    expect(document.activeElement).toBe(field(el, 'var-name-0'));
    type(field(el, 'var-name-0'), 'SENTRY_AUTH_TOKEN');
    // Its value is typed in the review: no value field here, and no secret of another server to pick.
    expect(el.querySelector('input[type="password"]')).toBeNull();
    expect(el.querySelector('select')).toBeNull();
    button(el, 'Continue').click();
    await settle(fixture);
    const config = { name: 'sentry-mcp', transport: 'stdio', command: ['npx', '-y', '@sentry/mcp-server@0.12.0'], env: [{ name: 'SENTRY_AUTH_TOKEN' }] };
    expect(bodies['review']).toEqual({ env: 'stage', config });
    expect(reviewed).toEqual([expect.objectContaining({
      source: { env: 'stage', config }, name: 'sentry-mcp', transport: 'stdio', target: 'npx -y @sentry/mcp-server@0.12.0',
      variables: [{ name: 'SENTRY_AUTH_TOKEN' }], bearer: false,
    })]);
  });

  it('refuses a plain-HTTP URL at its field, and a callback port in the OAuth app options it opens', async () => {
    const { fixture, el, cmp, reviewed } = await configure();
    cmp.load({ origin: 'external', transport: 'http' });
    await settle(fixture);
    expect(radio(el, 'transport', 'http').checked).toBe(true);
    type(field(el, 'url'), 'http://mcp.example.com/mcp');
    await settle(fixture);
    // The URL names the server while the name is empty.
    expect(field(el, 'name').value).toBe('example-mcp');
    button(el, 'Continue').click();
    await settle(fixture);
    expect(text(el.querySelector(`#${field(el, 'url').id}-err`))).toBe('Use an HTTPS URL, starting with https://.');
    expect(document.activeElement).toBe(field(el, 'url'));

    type(field(el, 'url'), 'https://mcp.example.com/mcp');
    const options = el.querySelector<HTMLElement>('[id$="-signin"]')!;
    expect(options.hidden).toBe(true);
    type(field(el, 'port'), '80');
    button(el, 'Continue').click();
    await settle(fixture);
    expect(options.hidden).toBe(false);
    expect(text(el.querySelector(`#${field(el, 'port').id}-err`))).toBe('Use a port from 1024 to 65535.');
    expect(document.activeElement).toBe(field(el, 'port'));
    expect(calls).not.toContain('review');
    expect(reviewed).toEqual([]);
  });

  it('adds the Authorization header for a Bearer token and has the review ask for the token alone', async () => {
    const { fixture, el, cmp, reviewed } = await configure();
    cmp.load({ origin: 'external', transport: 'http' });
    await settle(fixture);
    type(field(el, 'name'), 'private-mcp');
    type(field(el, 'url'), 'https://mcp.example.com/mcp');
    check(radio(el, 'with', 'bearer'));
    await settle(fixture);
    button(el, 'Continue').click();
    await settle(fixture);
    expect(bodies['review']).toEqual({ env: 'dev', config: { name: 'private-mcp', transport: 'http', url: 'https://mcp.example.com/mcp', headers: [{ name: 'Authorization' }] } });
    expect(reviewed).toEqual([expect.objectContaining({ variables: [{ name: 'Authorization' }], bearer: true, transport: 'http', target: 'https://mcp.example.com/mcp' })]);
  });

  it('fills a verified profile with a field for each part of its URL, and refuses the URL until it is filled in', async () => {
    const { fixture, el, cmp, reviewed } = await configure();
    cmp.load({ origin: 'profile', profile: SUPABASE });
    await settle(fixture);
    const origin = el.querySelector('[id$="-origin"]')!;
    expect(origin.classList).toContain('gw-alert--success');
    expect(text(origin.querySelector('.gw-alert__title'))).toBe('GateRook’s verified profile of Supabase’s own server');
    expect(Array.from(origin.querySelectorAll('.gw-alert__body')).map(text)).toEqual([
      'Checked 2026-11-11. It sets the vendor’s read-only switches, and its tools that run code or hand work to another agent start in Approval or Off. That doesn’t verify what the server does.',
      'read_only=true: Supabase lists only its read tools.',
    ]);
    expect(field(el, 'name').value).toBe('supabase-mcp');
    expect(radio(el, 'transport', 'http').checked).toBe(true);
    expect(field(el, 'url').value).toBe('https://mcp.supabase.com/mcp?project_ref={project_ref}&read_only=true');
    const part = field(el, 'part-0');
    expect(labelOf(el, part)).toBe('Project ref');
    expect(text(el.querySelector(`#${part.getAttribute('aria-describedby')}`))).toBe(
      'Goes into {project_ref} of the URL. Your project’s reference, from its dashboard URL.');

    button(el, 'Continue').click();
    await settle(fixture);
    expect(part.getAttribute('aria-invalid')).toBe('true');
    expect(text(el.querySelector('[id$="-part-err-0"]'))).toBe('Enter the project ref.');
    expect(document.activeElement).toBe(part);
    expect(calls).not.toContain('review');

    type(part, 'abcdefghijklmnop');
    await settle(fixture);
    expect(part.hasAttribute('aria-invalid')).toBe(false);
    button(el, 'Continue').click();
    await settle(fixture);
    const url = 'https://mcp.supabase.com/mcp?project_ref=abcdefghijklmnop&read_only=true';
    expect(bodies['review']).toEqual({ env: 'dev', config: { name: 'supabase-mcp', transport: 'http', url, headers: [] } });
    expect(reviewed).toEqual([expect.objectContaining({ name: 'supabase-mcp', target: url })]);
  });

  it('sends a verified profile’s fixed headers as written, and leaves out one the person removed', async () => {
    const { fixture, el, cmp } = await configure();
    cmp.load({ origin: 'profile', profile: ACME });
    await settle(fixture);
    const fixed = el.querySelector(`[aria-describedby$="-fixed-hint"]`)!;
    expect(text(fixed.querySelector('legend'))).toBe('Headers with a fixed value');
    expect(Array.from(fixed.querySelectorAll('code')).map(text)).toEqual(['X-MCP-Readonly: true']);
    button(el, 'Continue').click();
    await settle(fixture);
    expect(bodies['review']).toEqual({ env: 'dev', config: { name: 'acme-mcp', transport: 'http', url: 'https://mcp.acme.example/mcp', headers: [],
      fixedHeaders: [{ name: 'X-MCP-Readonly', text: 'true' }] } });

    button(fixed, 'Remove').click();
    await settle(fixture);
    expect(el.querySelector('[aria-describedby$="-fixed-hint"]')).toBeNull();
    button(el, 'Continue').click();
    await settle(fixture);
    expect(bodies['review']).toEqual({ env: 'dev', config: { name: 'acme-mcp', transport: 'http', url: 'https://mcp.acme.example/mcp', headers: [] } });
  });

  it.each([
    ['a remote', LINEAR, 'linear-mcp', 'http', 'https://mcp.linear.app/mcp', 'app.linear/linear · v1.0.2 · official server of Linear.',
      { name: 'linear-mcp', transport: 'http', url: 'https://mcp.linear.app/mcp', headers: [] }],
    ['an npm package', PLAYWRIGHT, 'playwright-mcp', 'stdio', 'npx -y @playwright/mcp@1.2.3', 'io.github.microsoft/playwright-mcp.',
      { name: 'playwright-mcp', transport: 'stdio', command: ['npx', '-y', '@playwright/mcp@1.2.3'], env: [] }],
  ] as const)('fills %s of the Official MCP Registry and reviews it as filled in', async (_, server, name, transport, target, about, config) => {
    const { fixture, el, cmp, reviewed } = await configure();
    cmp.load({ origin: 'registry', result: registryResult(server, 'registry.modelcontextprotocol.io'), registry: 'registry.modelcontextprotocol.io' });
    await settle(fixture);
    const origin = el.querySelector('[id$="-origin"]')!;
    expect(text(origin.querySelector('.gw-alert__title'))).toBe(`Filled in from the Official MCP Registry: ${server.title}`);
    expect(text(origin.querySelector('.gw-alert__body'))).toBe(`${about} Nothing in it has run or been reviewed yet.`);
    expect(field(el, 'name').value).toBe(name);
    expect(radio(el, 'transport', transport).checked).toBe(true);
    expect(field(el, transport === 'http' ? 'url' : 'command').value).toBe(target);
    // Not External MCP server: no configuration to paste.
    expect(el.querySelector('[id$="-paste-text"]')).toBeNull();
    expect(calls).not.toContain('review');
    button(el, 'Continue').click();
    await settle(fixture);
    expect(bodies['review']).toEqual({ env: 'dev', config });
    expect(reviewed).toEqual([expect.objectContaining({ name, transport, target })]);
  });

  it('fills External MCP server from a pasted configuration without keeping it or its values', async () => {
    const { fixture, el, cmp } = await configure();
    cmp.load({ origin: 'external' });
    await settle(fixture);
    const paste = field<HTMLTextAreaElement>(el, 'paste-text');
    type(paste, '{"mcpServers":{"linear":{"url":"https://mcp.linear.app/mcp","headers":{"X-Api-Key":"lin_api_example_123"}}}}');
    await settle(fixture);
    expect(text(el.querySelector('[id$="-paste-done"]'))).toBe(
      'Filled in the name, the URL and one header from the configuration. Its value isn’t copied: the review asks for it and stores it encrypted.');
    expect(paste.value).toBe('');
    expect(field(el, 'name').value).toBe('linear');
    expect(radio(el, 'transport', 'http').checked).toBe(true);
    expect(field(el, 'url').value).toBe('https://mcp.linear.app/mcp');
    expect(field(el, 'var-name-0').value).toBe('X-Api-Key');
    expect(document.body.innerHTML).not.toContain('lin_api_example_123');
    button(el, 'Continue').click();
    await settle(fixture);
    expect(bodies['review']).toEqual({ env: 'dev', config: { name: 'linear', transport: 'http', url: 'https://mcp.linear.app/mcp', headers: [{ name: 'X-Api-Key' }] } });
  });

  it.each([
    ['Bearer token', 'bearer', '{"url":"https://mcp.example.com/mcp","headers":{"Authorization":"Basic example-credentials"}}'],
    ['Google Cloud credentials', 'google', '{"url":"https://mcp.example.com/mcp"}'],
  ])('does not carry %s chosen for one server to a pasted configuration without them', async (_, way, snippet) => {
    const { fixture, el, cmp } = await configure();
    cmp.load({ origin: 'external', transport: 'http' });
    await settle(fixture);
    check(radio(el, 'with', way));
    await settle(fixture);
    type(field<HTMLTextAreaElement>(el, 'paste-text'), snippet);
    await settle(fixture);
    expect(radio(el, 'with', way).checked).toBe(false);
    expect(radio(el, 'with', 'oauth').checked).toBe(true);
  });

  it('fills the card read from a paste in the search, saying what wasn’t copied', async () => {
    const { fixture, el, cmp } = await configure();
    cmp.load({ origin: 'paste', server: pasted('{"mcpServers":{"linear":{"url":"https://mcp.linear.app/mcp","headers":{"X-Api-Key":"lin_api_example_123"}}}}') });
    await settle(fixture);
    const origin = el.querySelector('[id$="-origin"]')!;
    expect(text(origin.querySelector('.gw-alert__title'))).toBe('Filled in from your paste: the name, the URL and one header');
    expect(text(origin.querySelector('.gw-alert__body'))).toBe(
      'Values of keys and tokens in it weren’t copied: the review asks for each one, and GateRook stores it encrypted.');
    expect(field(el, 'var-name-0').value).toBe('X-Api-Key');
    expect(el.querySelector('[id$="-paste-text"]')).toBeNull();
    expect(document.body.innerHTML).not.toContain('lin_api_example_123');
  });

  it('asks only for the environment of a signed catalog entry, and reviews it by its catalog reference', async () => {
    const { fixture, el, cmp, reviewed } = await configure();
    cmp.load({ origin: 'catalog', id: 'github', version: '0.6.2', name: 'GitHub' }, 'prod');
    await settle(fixture);
    const origin = el.querySelector('[id$="-origin"]')!;
    expect(text(origin.querySelector('.gw-alert__title'))).toBe('GitHub from GateRook’s signed catalog');
    expect(text(origin.querySelector('.gw-alert__body'))).toBe(
      'Version 0.6.2. GateRook installs the package the catalog signed and runs it without a shell: there is no command to type.');
    expect(text(el.querySelector('.gw-form__description'))).toBe('It is named github after its catalog entry, and its name prefixes its tool names.');
    expect(el.querySelector('[id$="-name"]')).toBeNull();
    expect(el.querySelector('input[name$="-transport"]')).toBeNull();
    expect(radio(el, 'env', 'prod').checked).toBe(true);
    button(el, 'Continue').click();
    await settle(fixture);
    expect(bodies['review']).toEqual({ catalog: 'github@0.6.2', env: 'prod' });
    expect(reviewed).toEqual([expect.objectContaining({ name: 'github-mcp', transport: 'stdio', source: { catalog: 'github@0.6.2', env: 'prod' } })]);
  });

  it('says why the core didn’t review the server, and keeps what was typed', async () => {
    const { fixture, el, cmp, reviewed } = await configure();
    data.reviewServer.mockRejectedValueOnce(new ApiError('unprocessable', 422, { detail: 'The command must name the package’s version.' }));
    cmp.load({ origin: 'external' });
    await settle(fixture);
    type(field(el, 'name'), 'sentry-mcp');
    type(field(el, 'command'), 'npx -y @sentry/mcp-server');
    button(el, 'Continue').click();
    await settle(fixture);
    const alert = el.querySelector<HTMLElement>('[role="alert"].gw-alert--danger')!;
    expect(text(alert.querySelector('.gw-alert__title'))).toBe('The server wasn’t reviewed');
    expect(text(alert.querySelector('.gw-alert__body'))).toBe('The command must name the package’s version.');
    expect(document.activeElement).toBe(alert);
    expect(field(el, 'command').value).toBe('npx -y @sentry/mcp-server');
    expect(button(el, 'Continue').hasAttribute('aria-disabled')).toBe(false);
    expect(reviewed).toEqual([]);
  });
});

/** A command's review, as Configure hands it over. */
function stdioReviewed(over: Partial<McpReviewed> = {}): McpReviewed {
  return {
    review: sentryReview(),
    source: { env: 'dev', config: { name: 'sentry-mcp', transport: 'stdio', command: ['npx', '-y', '@sentry/mcp-server@0.12.0'], env: [{ name: 'SENTRY_AUTH_TOKEN' }] } },
    name: 'sentry-mcp', transport: 'stdio', target: 'npx -y @sentry/mcp-server@0.12.0', variables: [{ name: 'SENTRY_AUTH_TOKEN' }], bearer: false, opener: null,
    ...over,
  };
}

/** A server at a URL whose review found `auth`, with the headers whose values the review asks for. */
function httpReviewed(name: string, url: string, auth: McpServerAuthReview, opts: { headers?: string[]; env?: Env; review?: Partial<McpServerReview> } = {}): McpReviewed {
  const headers = opts.headers ?? [];
  return {
    review: sentryReview({ name, transport: 'http', argv: [], env: headers, url, auth, ...opts.review }),
    source: { env: opts.env ?? 'dev', config: { name, transport: 'http', url, headers: headers.map((h) => ({ name: h })) } },
    name, transport: 'http', target: url, variables: headers.map((h) => ({ name: h })), bearer: false, opener: null,
  };
}

/** sentry-mcp, which answers only with a sign-in (or, `optional`, without one too) at `host`. */
function signInReviewed(opts: { kind?: 'oauth' | 'optional'; headers?: string[]; host?: string; env?: Env } = {}): McpReviewed {
  const host = opts.host ?? 'mcp.example.com';
  const url = host === 'mcp.example.com' ? 'https://mcp.example.com/mcp' : `http://${host}:7781/mcp`;
  return httpReviewed('sentry-mcp', url, { kind: opts.kind ?? 'oauth', registration: 'dynamic', authorizationHost: host }, opts);
}

describe('MCP server Review', () => {
  async function review(reviewed: McpReviewed) {
    setup();
    const mounted = await mount(ReviewHost);
    const connected = collect(mounted.cmp.connected);
    const back = collect(mounted.cmp.back);
    mounted.cmp.load(reviewed);
    await settle(mounted.fixture);
    return { ...mounted, connected, back };
  }

  /** The large Sign in of the review's centred call to sign in. */
  const heroSignIn = (el: Element) => el.querySelector<HTMLButtonElement>('[gwMcpSignIn] .gw-empty__actions button')!;
  const actions = (el: Element) => Array.from(el.querySelectorAll('.gw-form__actions button')).map(text);

  it('starts a command only after the acknowledgement, with its value sent once and gone from the page', async () => {
    const { fixture, el, connected } = await review(stdioReviewed());
    const risk = el.querySelector('.gw-alert')!;
    expect(risk.classList).toContain('gw-alert--danger');
    expect(text(risk.querySelector('.gw-alert__title'))).toBe('Start MCP server sentry-mcp?');
    const facts = rows(el);
    expect(facts['Environment']).toBe('dev');
    expect(facts['Command']).toBe('npx -y @sentry/mcp-server@0.12.0');
    expect(facts['Version']).toBe('Pinned to 0.12.0. npm checks the package integrity on every start.');
    expect(facts['Runs as']).toContain('Not sandboxed on this system');
    expect(facts['Secrets passed']).toBe('SENTRY_AUTH_TOKEN, from a new secret of sentry-mcp, encrypted here');
    const value = field(el, 'value-0');
    expect(labelOf(el, value)).toBe('Value of SENTRY_AUTH_TOKEN');
    expect(value.type).toBe('password');
    expect(value.autocomplete).toBe('new-password');

    const start = button(el, 'Start MCP server sentry-mcp');
    expect(start.getAttribute('aria-disabled')).toBe('true');
    start.click();
    await settle(fixture);
    expect(calls).not.toContain('add-server');
    check(field(el, 'ack'));
    await settle(fixture);
    expect(start.hasAttribute('aria-disabled')).toBe(false);
    // Empty: refused at the field, nothing sent.
    start.click();
    await settle(fixture);
    expect(calls).not.toContain('add-server');
    expect(value.getAttribute('aria-invalid')).toBe('true');
    expect(text(el.querySelector('[id$="-value-err-0"]'))).toBe('Enter the value of SENTRY_AUTH_TOKEN.');
    expect(document.activeElement).toBe(value);

    type(value, 'sntrys_example_value');
    await settle(fixture);
    expect(value.hasAttribute('aria-invalid')).toBe(false);
    start.click();
    await settle(fixture);
    expect(bodies['add-server']).toEqual({
      env: 'dev',
      config: { name: 'sentry-mcp', transport: 'stdio', command: ['npx', '-y', '@sentry/mcp-server@0.12.0'], env: [{ name: 'SENTRY_AUTH_TOKEN' }] },
      secrets: { '/env/0/secretRef': { source: 'local', value: 'sntrys_example_value' } },
    });
    expect(calls.filter((c) => c === 'add-server')).toHaveLength(1);
    expect(connected).toEqual([{ name: 'sentry-mcp', env: 'dev', http: false, tools: 12, signedIn: null, opener: null }]);
    // Write-only: the value left the page with the request.
    expect(value.value).toBe('');
    expect(document.body.innerHTML).not.toContain('sntrys_example_value');
  });

  it('starts a catalog entry in its environment with the value of the variable its review names', async () => {
    const reviewed: McpReviewed = {
      review: sentryReview({ name: 'github-mcp', catalogEntry: 'github@0.6.2', argv: ['node', '/Users/me/.gaterook/mcp/github-mcp/dist/index.js'],
        resolvedCommand: 'node /Users/me/.gaterook/mcp/github-mcp/dist/index.js', env: ['GITHUB_PERSONAL_ACCESS_TOKEN'] }),
      source: { catalog: 'github@0.6.2', env: 'stage' }, name: 'github-mcp', transport: 'stdio', target: '', variables: [], bearer: false, opener: null,
    };
    const { fixture, el, connected } = await review(reviewed);
    const facts = rows(el);
    expect(facts['Environment']).toBe('stage');
    expect(facts['Command']).toBe('node /Users/me/.gaterook/mcp/github-mcp/dist/index.js');
    expect(facts['Version']).toBe('Pinned to 0.6.2. npm checks the package integrity on every start.');
    expect(facts['Secrets passed']).toBe('GITHUB_PERSONAL_ACCESS_TOKEN, from a new secret of github-mcp, encrypted here');
    type(field(el, 'value-0'), 'ghp_example_123');
    check(field(el, 'ack'));
    await settle(fixture);
    button(el, 'Start MCP server github-mcp').click();
    await settle(fixture);
    expect(bodies['add-server']).toEqual({ catalog: 'github@0.6.2', env: 'stage', secrets: { '/env/0/secretRef': { source: 'local', value: 'ghp_example_123' } } });
    expect(connected).toEqual([expect.objectContaining({ name: 'github-mcp', env: 'stage', http: false })]);
  });

  it('wipes the typed values on Back, starts the next review empty, and wipes them when the page goes', async () => {
    const { fixture, el, cmp, back } = await review(stdioReviewed());
    const value = field(el, 'value-0');
    type(value, 'sntrys_typed');
    check(field(el, 'ack'));
    await settle(fixture);
    button(el, 'Back').click();
    await settle(fixture);
    expect(back).toHaveLength(1);
    expect(value.value).toBe('');

    cmp.load(stdioReviewed());
    await settle(fixture);
    const again = field(el, 'value-0');
    expect(again.value).toBe('');
    expect(field(el, 'ack').checked).toBe(false);
    expect(button(el, 'Start MCP server sentry-mcp').getAttribute('aria-disabled')).toBe('true');
    type(again, 'sntrys_retyped');
    fixture.destroy();
    expect(again.value).toBe('');
    expect(calls).not.toContain('add-server');
  });

  it('warns about a key in the URL, and still connects the server with the URL as typed', async () => {
    const url = 'https://mcp.example.com/mcp?apiKey=sk-example-123';
    const reviewed = httpReviewed('search-mcp', url, { kind: 'none' },
      { review: { url: 'https://mcp.example.com/mcp?apiKey=‹masked›', warnings: [KEY_IN_URL] } });
    const { fixture, el } = await review(reviewed);
    expect(el.querySelector('.gw-alert')!.classList).toContain('gw-alert--warning');
    expect(text(el.querySelector('.gw-alert__title'))).toBe('Connect MCP server search-mcp?');
    expect(rows(el)['URL']).toBe('https://mcp.example.com/mcp?apiKey=‹masked›');
    expect(Array.from(el.querySelectorAll('.gw-alert--compact.gw-alert--warning')).map(text)).toEqual([KEY_IN_URL]);
    check(field(el, 'ack'));
    await settle(fixture);
    button(el, 'Connect MCP server search-mcp').click();
    await settle(fixture);
    expect(bodies['add-server']).toEqual({ env: 'dev', config: { name: 'search-mcp', transport: 'http', url, headers: [] } });
  });

  it('asks for a required sign-in with one large primary Sign in, without the trust checkbox or Connect', async () => {
    const { el } = await review(signInReviewed());
    const hero = el.querySelector('[gwMcpSignIn] .gw-card.gw-card--muted .gw-empty.gw-empty--compact')!;
    expect(text(hero.querySelector('.gw-empty__title'))).toBe('Sign in to connect sentry-mcp');
    expect(text(hero.querySelector('.gw-empty__text'))).toContain('sentry-mcp answers only with your sign-in.');
    expect(hero.querySelector('.gw-empty__icon')!.classList).toContain('gw-icon-tile--brand');
    const signIn = heroSignIn(el);
    expect(text(signIn)).toBe('Sign in with mcp.example.com');
    expect(Array.from(signIn.classList)).toEqual(expect.arrayContaining(['gw-btn--primary', 'gw-btn--lg']));
    expect(signIn.getAttribute('aria-disabled')).toBeNull();
    expect(text(el.querySelector(`#${signIn.getAttribute('aria-describedby')}`))).toBe('GateRook connects it as soon as you allow access.');
    // Signing in is the confirmation: no checkbox to tick, no Connect to press.
    expect(el.querySelector('input[type="checkbox"]')).toBeNull();
    expect(actions(el)).toEqual(['Back']);
    expect(el.querySelectorAll('.gw-btn--primary')).toHaveLength(1);
  });

  it('connects right after the sign-in, in its environment, and says the server is connecting meanwhile', async () => {
    const { fixture, el, connected } = await review(signInReviewed({ env: 'prod' }));
    const added = deferred<McpServerStarted>();
    data.addServer.mockImplementationOnce((source) => {
      calls.push('add-server');
      bodies['add-server'] = source;
      return added.promise;
    });
    heroSignIn(el).click();
    await settle(fixture);
    expect(data.startSignIn).toHaveBeenCalledWith(expect.objectContaining({ env: 'prod', config: expect.objectContaining({ auth: { type: 'oauth' } }) }));
    expect(text(el.querySelector('[gwMcpSignIn] .gw-empty__title'))).toBe('Allow access in your browser');
    expect(calls).not.toContain('add-server');

    events.emit('mcp-server.sign-in', { id: 'sign-in-1' });
    await settle(fixture);
    const hero = el.querySelector('[gwMcpSignIn] .gw-empty')!;
    expect(hero.querySelector('.gw-empty__icon')!.classList).toContain('gw-icon-tile--success');
    expect(text(hero.querySelector('.gw-empty__title'))).toBe('Signed in at mcp.example.com');
    expect(text(hero.querySelector('.gw-status--checking'))).toBe('Connecting sentry-mcp');
    expect(button(el, 'Back').getAttribute('aria-disabled')).toBe('true');
    expect(bodies['add-server']).toMatchObject({ env: 'prod', signIn: 'sign-in-1', config: { auth: { type: 'oauth' } } });

    added.resolve({ name: 'sentry-mcp', state: 'running', tools: 12 });
    await settle(fixture);
    expect(calls.filter((c) => c === 'add-server')).toHaveLength(1);
    expect(connected).toEqual([{ name: 'sentry-mcp', env: 'prod', http: true, tools: 12, signedIn: { host: 'mcp.example.com' }, opener: null }]);
  });

  it('starts over with a new sign-in when the server doesn’t connect with the one it got', async () => {
    const { fixture, el, connected } = await review(signInReviewed());
    data.addServer.mockRejectedValueOnce(new ApiError('unprocessable', 422, { detail: 'sentry-mcp answered HTTP 500.' }));
    heroSignIn(el).click();
    await settle(fixture);
    events.emit('mcp-server.sign-in', { id: 'sign-in-1' });
    await settle(fixture);
    expect(data.addServer).toHaveBeenCalledTimes(1);
    expect(connected).toEqual([]);
    const failed = el.querySelector('[role="alert"].gw-alert--danger')!;
    expect(text(failed.querySelector('.gw-alert__title'))).toBe("sentry-mcp didn't connect");
    expect(Array.from(failed.querySelectorAll('.gw-alert__body')).map(text)).toEqual([
      'sentry-mcp answered HTTP 500.',
      'A sign-in is used once: sign in again to try once more.',
    ]);
    // The alert sits above the call to sign in, which is back at its start: the large Sign in tries again.
    expect(failed.compareDocumentPosition(el.querySelector('[gwMcpSignIn]')!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(text(heroSignIn(el))).toBe('Sign in with mcp.example.com');
    expect(el.querySelector('input[type="checkbox"]')).toBeNull();
    expect(actions(el)).toEqual(['Back']);
    heroSignIn(el).click();
    await settle(fixture);
    expect(data.startSignIn).toHaveBeenCalledTimes(2);
    expect(el.querySelector('[role="alert"].gw-alert--danger')).toBeNull();
  });

  it('keeps Sign in waiting for the values the server connects with', async () => {
    const { fixture, el } = await review(signInReviewed({ headers: ['X-Workspace'] }));
    const signIn = heroSignIn(el);
    expect(signIn.getAttribute('aria-disabled')).toBe('true');
    expect(text(el.querySelector(`#${signIn.getAttribute('aria-describedby')}`))).toBe('Enter the value of X-Workspace first.');
    signIn.click();
    await settle(fixture);
    expect(data.startSignIn).not.toHaveBeenCalled();
    type(field(el, 'value-0'), 'acme');
    await settle(fixture);
    expect(heroSignIn(el).getAttribute('aria-disabled')).toBeNull();
    expect(text(el.querySelector(`#${heroSignIn(el).getAttribute('aria-describedby')}`))).toBe('GateRook connects it as soon as you allow access.');
  });

  it('names no address in the sign-in of a server on this computer', async () => {
    const { fixture, el, connected } = await review(signInReviewed({ host: '127.0.0.1' }));
    expect(rows(el)['Sign-in']).toBe('OAuth on this computer. GateRook registers itself there as GateRook.');
    expect(text(heroSignIn(el))).toBe('Sign in');
    data.startSignIn.mockResolvedValueOnce({ id: 'sign-in-1', server: 'sentry-mcp', state: 'waiting', authorizationHost: '127.0.0.1' });
    heroSignIn(el).click();
    await settle(fixture);
    expect(text(el.querySelector('[gwMcpSignIn] .gw-empty [role="status"]'))).toBe(
      'Waiting for you to sign in. GateRook connects the server as soon as the browser comes back.');
    expect(text(el.querySelector('[gwMcpSignIn]'))).not.toContain('127.0.0.1');
    servers.signIn.mockResolvedValueOnce({ id: 'sign-in-1', server: 'sentry-mcp', state: 'signed-in', authorizationHost: '127.0.0.1' });
    events.emit('mcp-server.sign-in', { id: 'sign-in-1' });
    await settle(fixture);
    expect(connected).toEqual([expect.objectContaining({ signedIn: { host: null } })]);
  });

  it('offers an optional sign-in beside Connect, and a sign-in connects on its own', async () => {
    const { fixture, el, connected } = await review(signInReviewed({ kind: 'optional' }));
    expect(text(el.querySelector('[gwMcpSignIn] .gw-empty__title'))).toBe('Sign in to sentry-mcp');
    const optional = heroSignIn(el);
    expect(optional.classList).toContain('gw-btn--lg');
    expect(optional.classList).not.toContain('gw-btn--primary');
    expect(text(el.querySelector('.gw-checkbox__label'))).toBe('I trust this server with the calls of my AI apps');
    const connect = button(el, 'Connect MCP server sentry-mcp');
    expect(connect.classList).toContain('gw-btn--danger');
    expect(connect.getAttribute('aria-disabled')).toBe('true');
    optional.click();
    await settle(fixture);
    events.emit('mcp-server.sign-in', { id: 'sign-in-1' });
    await settle(fixture);
    expect(bodies['add-server']).toMatchObject({ signIn: 'sign-in-1', config: { auth: { type: 'oauth' } } });
    expect(connected).toEqual([expect.objectContaining({ signedIn: { host: 'mcp.example.com' } })]);
  });

  it('connects without the optional sign-in after the acknowledgement, keeping the sign-in for later', async () => {
    const { fixture, el, connected } = await review(signInReviewed({ kind: 'optional' }));
    check(field(el, 'ack'));
    await settle(fixture);
    button(el, 'Connect MCP server sentry-mcp').click();
    await settle(fixture);
    expect(data.startSignIn).not.toHaveBeenCalled();
    expect(bodies['add-server']).toEqual({ env: 'dev',
      config: { name: 'sentry-mcp', transport: 'http', url: 'https://mcp.example.com/mcp', headers: [], auth: { type: 'oauth', optional: true } } });
    expect(connected).toEqual([expect.objectContaining({ signedIn: null })]);
  });

  it('switches a refused review to a Bearer token in place, and sends the token with its prefix', async () => {
    const refused: McpServerAuthReview = { kind: 'refused', message: 'It refused the request (HTTP 401) and publishes no OAuth metadata.' };
    const { fixture, el, back } = await review(httpReviewed('private-mcp', 'https://mcp.example.com/mcp', refused));
    const warning = el.querySelector('.gw-alert--compact.gw-alert--warning')!;
    expect(text(warning)).toContain('It wants a key, not a sign-in');
    expect(text(warning)).toContain('go back');
    data.reviewServer.mockResolvedValueOnce(sentryReview({ name: 'private-mcp', transport: 'http', url: 'https://mcp.example.com/mcp', argv: [],
      env: ['Authorization'], auth: refused }));
    button(warning, 'Use a Bearer token').click();
    await settle(fixture);
    const config = { name: 'private-mcp', transport: 'http', url: 'https://mcp.example.com/mcp', headers: [{ name: 'Authorization' }] };
    expect(data.reviewServer).toHaveBeenCalledTimes(1);
    expect(data.reviewServer).toHaveBeenLastCalledWith({ env: 'dev', config });
    expect(back).toEqual([]);
    expect(el.querySelector('.gw-alert--compact.gw-alert--warning')).toBeNull();
    const token = field(el, 'value-0');
    expect(labelOf(el, token)).toBe('Bearer token');
    expect(document.activeElement).toBe(token);

    type(token, ' Bearer ');
    check(field(el, 'ack'));
    await settle(fixture);
    const connect = button(el, 'Connect MCP server private-mcp');
    connect.click();
    await settle(fixture);
    expect(calls).not.toContain('add-server');
    expect(token.getAttribute('aria-invalid')).toBe('true');
    expect(text(el.querySelector('[id$="-value-err-0"]'))).toBe('Enter the bearer token.');
    type(token, ' example-token ');
    connect.click();
    await settle(fixture);
    expect(bodies['add-server']).toEqual({ env: 'dev', config, secrets: { '/headers/0/secretRef': { source: 'local', value: 'Bearer example-token' } } });
    expect(token.value).toBe('');
  });

  it('sends the client secret of an OAuth app only to the authorization server the review showed', async () => {
    const { fixture, el, connected } = await review(httpReviewed('chat-mcp', 'https://mcp.chat.example.com/mcp', {
      kind: 'oauth', issuer: 'https://chat.example.com', authorizationHost: 'chat.example.com', registration: 'app-required',
      redirectUri: 'http://127.0.0.1:7471/callback', secretRequired: true, tokenEndpoint: 'https://api.chat.example.com/oauth/token',
      message: 'chat.example.com takes only OAuth apps registered with it.',
    }));
    expect(text(el)).toContain('This server needs your own OAuth app');
    expect(field(el, 'redirect').value).toBe('http://127.0.0.1:7471/callback');
    const blockedBy = () => text(el.querySelector(`#${heroSignIn(el).getAttribute('aria-describedby')}`));
    expect(blockedBy()).toBe('Enter the client ID of your OAuth app first.');
    type(field(el, 'client'), 'chat-app-1234');
    await settle(fixture);
    expect(blockedBy()).toBe('Enter the client secret of your OAuth app first.');
    const secret = field(el, 'client-secret');
    expect(text(el.querySelector(`#${secret.getAttribute('aria-describedby')}`))).toContain(
      'GateRook sends it only to api.chat.example.com, where this review found the authorization server’s token endpoint.');
    type(secret, 'chat-app-secret-5678');
    await settle(fixture);
    expect(heroSignIn(el).getAttribute('aria-disabled')).toBeNull();
    data.startSignIn.mockResolvedValueOnce({ id: 'msi_app000001', server: 'chat-mcp', state: 'waiting', authorizationHost: 'chat.example.com' });
    heroSignIn(el).click();
    await settle(fixture);
    const config = { name: 'chat-mcp', transport: 'http', url: 'https://mcp.chat.example.com/mcp', headers: [], auth: { type: 'oauth', clientId: 'chat-app-1234' } };
    expect(data.startSignIn).toHaveBeenCalledWith({
      config,
      env: 'dev',
      secrets: { '/auth/clientSecret': { source: 'local', value: 'chat-app-secret-5678' } },
      authorizationServer: { issuer: 'https://chat.example.com', tokenEndpoint: 'https://api.chat.example.com/oauth/token' },
    });
    servers.signIn.mockResolvedValueOnce({ id: 'msi_app000001', server: 'chat-mcp', state: 'signed-in', authorizationHost: 'chat.example.com' });
    events.emit('mcp-server.sign-in', { id: 'msi_app000001' });
    await settle(fixture);
    expect(bodies['add-server']).toEqual({ env: 'dev', config, signIn: 'msi_app000001',
      secrets: { '/auth/clientSecret': { source: 'local', value: 'chat-app-secret-5678' } } });
    expect(connected).toEqual([expect.objectContaining({ name: 'chat-mcp', signedIn: { host: 'chat.example.com' } })]);
    expect(secret.value).toBe('');
  });

  it('signs in with a one-time code where GateRook has its own app, the code first, then the page', async () => {
    const { fixture, el, connected } = await review(httpReviewed('githubcopilot-mcp', 'https://api.githubcopilot.com/mcp/', {
      kind: 'oauth', issuer: 'https://github.com/login/oauth', authorizationHost: 'github.com', registration: 'gaterook-app', scope: 'repo read:org offline_access',
    }));
    // The desktop app opens the sign-in page itself.
    const desktop = TestBed.inject(DesktopBridge);
    vi.spyOn(desktop, 'opensSignIns', 'get').mockReturnValue(true);
    const open = vi.spyOn(desktop, 'openMcpSignIn').mockResolvedValue(undefined);
    const write = vi.spyOn(TestBed.inject(ClipboardWriter), 'write').mockResolvedValue(true);
    expect(text(el)).toContain('GateRook signs in there with its own app');
    expect(text(el)).not.toContain('needs your own OAuth app');
    expect(el.querySelector('input[id$="-client"]')).toBeNull();
    expect(el.querySelector('input[type="password"]')).toBeNull();
    expect(text(el.querySelector('[gwMcpSignIn] .gw-empty__text'))).toContain('You sign in with a one-time code at github.com, in a browser on any device.');
    const start = heroSignIn(el);
    expect(text(start)).toBe('Sign in with github.com');
    data.startSignIn.mockResolvedValueOnce({ id: 'msi_device000001', server: 'githubcopilot-mcp', state: 'waiting', authorizationHost: 'github.com',
      authorizationUrl: 'https://github.com/login/device', userCode: 'WDJB-MJHT' });
    start.click();
    await settle(fixture);
    expect(data.startSignIn).toHaveBeenCalledWith(expect.objectContaining({ config: expect.objectContaining({ auth: { type: 'oauth' } }) }));
    // The code first: the page opens once the person has it.
    expect(open).not.toHaveBeenCalled();
    expect(text(el.querySelector('[gwMcpSignIn] .gw-empty__title'))).toBe('Enter this code at github.com');
    expect(field(el, 'code').value).toBe('WDJB-MJHT');
    const copyAndOpen = button(el, 'Copy code and open github.com');
    expect(copyAndOpen.classList).toContain('gw-btn--primary');
    expect(document.activeElement).toBe(copyAndOpen);
    copyAndOpen.click();
    await settle(fixture);
    expect(write).toHaveBeenCalledWith('WDJB-MJHT');
    expect(open).toHaveBeenCalledWith('msi_device000001');
    expect(calls).not.toContain('add-server');

    // As any finished sign-in in the review, it connects the server.
    servers.signIn.mockResolvedValueOnce({ id: 'msi_device000001', server: 'githubcopilot-mcp', state: 'signed-in', authorizationHost: 'github.com' });
    events.emit('mcp-server.sign-in', { id: 'msi_device000001' });
    await settle(fixture);
    expect(bodies['add-server']).toMatchObject({ signIn: 'msi_device000001', config: { auth: { type: 'oauth' } } });
    expect(connected).toEqual([expect.objectContaining({ name: 'githubcopilot-mcp', signedIn: { host: 'github.com' } })]);
  });

  it('cancels a sign-in still waiting when the page goes', async () => {
    const { fixture, el } = await review(signInReviewed());
    heroSignIn(el).click();
    await settle(fixture);
    fixture.destroy();
    await sleep(0);
    expect(calls).toContain('cancel-sign-in sign-in-1');
    expect(calls).not.toContain('add-server');
  });
});

describe('MCP server Add to endpoint', () => {
  /** The fixture's dev and incident-prod, and a second dev endpoint, team. */
  function threeEndpoints(): EndpointSummary[] {
    const [dev, prod] = endpoints();
    return [dev, { ...dev, slug: 'team', clients: [], connections: { total: 1, byEnv: { dev: 1 } } }, prod];
  }
  const server = (over: Partial<McpServerConnected> = {}): McpServerConnected =>
    ({ name: 'sentry-mcp', env: 'dev', http: false, tools: 12, signedIn: null, opener: null, ...over });

  async function step(connected: McpServerConnected, preferred: string | null = null, list: () => EndpointSummary[] = endpoints) {
    setup();
    endpointsApi.list.mockImplementation(async () => list());
    const mounted = await mount(EndpointsHost);
    const finished = collect(mounted.cmp.finished);
    await mounted.cmp.load(connected, preferred);
    await settle(mounted.fixture);
    return { ...mounted, finished };
  }
  const boxes = (el: Element) => Array.from(el.querySelectorAll<HTMLInputElement>('input[name="endpoints"]'));
  const box = (el: Element, slug: string) => boxes(el).find((b) => b.value === slug)!;

  it('lists the endpoints with the reason one can’t take the server, chooses the usual one and shows its tools there', async () => {
    const { el } = await step(server());
    const saved = el.querySelector('[id$="-saved"]')!;
    expect(text(saved.querySelector('.gw-alert__title'))).toBe('sentry-mcp started');
    expect(text(saved.querySelector('.gw-alert__body'))).toBe('It is saved, and its 12 tools are Off everywhere until you add them here.');
    expect(boxes(el).map((b) => [b.value, b.checked, b.getAttribute('aria-disabled')])).toEqual([
      ['dev', true, null],
      ['incident-prod', false, 'true'],
    ]);
    expect(text(el)).toContain('Accepts prod connections only; sentry-mcp is dev.');
    expect(calls).toEqual(['preview dev sentry-mcp']);
    const tools = el.querySelector('[aria-labelledby$="-pv-dev-t"]')!;
    expect(text(tools.querySelector('.gw-card__description'))).toBe('2 tools · 1 read only · 1 approval');
    expect(Array.from(tools.querySelectorAll('tbody tr')).map((r) => [text(r.querySelector('th')), r.getAttribute('data-mode')])).toEqual([
      ['find_issues', 'read'],
      ['update_issue', 'approval'],
    ]);
    expect(text(button(el, 'Add to endpoint'))).toBe('Add to endpoint');
  });

  it.each([
    [null, ['dev', 'team']],
    ['team', ['team']],
    ['incident-prod', ['dev', 'team']],
  ])('chooses the endpoint the page was opened for (%s) when it may take the server', async (preferred, chosen) => {
    const { el } = await step(server(), preferred, threeEndpoints);
    expect(boxes(el).filter((b) => b.checked).map((b) => b.value)).toEqual(chosen);
    expect(calls).toEqual(chosen.map((slug) => `preview ${slug} sentry-mcp`));
  });

  it('chooses a prod endpoint for a prod server', async () => {
    const { el } = await step(server({ env: 'prod', http: true }));
    expect(boxes(el).map((b) => [b.value, b.checked, b.getAttribute('aria-disabled')])).toEqual([
      ['incident-prod', true, null],
      ['dev', false, 'true'],
    ]);
    expect(text(el)).toContain('Accepts dev and stage connections only; sentry-mcp is prod.');
  });

  it('adds the server to each chosen endpoint after asking the core again, and the toast opens the first', async () => {
    const { fixture, el, finished } = await step(server(), null, threeEndpoints);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const add = button(el, 'Add to 2 endpoints');
    add.click();
    await settle(fixture);
    expect(calls).toEqual([
      'preview dev sentry-mcp', 'preview team sentry-mcp',
      'preview dev sentry-mcp', 'add-to-endpoint dev sentry-mcp', 'preview team sentry-mcp', 'add-to-endpoint team sentry-mcp',
    ]);
    expect(finished).toEqual([{ added: ['dev', 'team'], notAdded: [] }]);
    expect(data.connections.refresh).toHaveBeenCalled();
    const toast = toasts().at(-1)!;
    expect(toast).toMatchObject({ tone: 'success', title: 'MCP server sentry-mcp started',
      body: 'Its 12 tools are on dev and team. Declared reads start as Read only; other tools start as Approval. Change their modes on the endpoint.' });
    expect(toast.actions.map((a) => a.label)).toEqual(['Open endpoint dev']);
    toast.actions[0].onClick?.();
    expect(navigate).toHaveBeenCalledWith(['/endpoints', 'dev']);
  });

  it('says where the tools couldn’t go when the core refuses one of the endpoints', async () => {
    const { fixture, el, finished } = await step(server({ http: true }), null, threeEndpoints);
    endpointsApi.previewConnection.mockImplementation(async (slug, connection) => {
      calls.push(`preview ${slug} ${connection}`);
      return preview(connection, slug === 'team' ? { refusal: 'Tokens of team reach production.' } : {});
    });
    button(el, 'Add to 2 endpoints').click();
    await settle(fixture);
    expect(calls.filter((c) => c.startsWith('add-to-endpoint'))).toEqual(['add-to-endpoint dev sentry-mcp']);
    expect(finished).toEqual([{ added: ['dev'], notAdded: [{ slug: 'team', why: 'Tokens of team reach production.' }] }]);
    expect(toasts().at(-1)).toMatchObject({ tone: 'warning', title: 'MCP server sentry-mcp connected, not added to team',
      body: 'Its 12 tools are on dev with their default modes. Tokens of team reach production.' });
  });

  it('stays with the core’s reasons when no chosen endpoint took the server', async () => {
    const { fixture, el, finished } = await step(server());
    endpointsApi.addConnection.mockRejectedValueOnce(new ApiError('conflict', 409, { detail: 'dev changed outside GateRook.' }));
    button(el, 'Add to endpoint').click();
    await settle(fixture);
    const alert = el.querySelector('[role="alert"][id$="-error"]')!;
    expect(text(alert.querySelector('.gw-alert__title'))).toBe('Not added');
    expect(text(alert.querySelector('.gw-alert__body'))).toBe('dev: dev changed outside GateRook.');
    expect(finished).toEqual([]);
    expect(toasts()).toEqual([]);
  });

  it('leaves the tools Off with Not now', async () => {
    const { fixture, el, finished } = await step(server());
    button(el, 'Not now').click();
    await settle(fixture);
    expect(finished).toEqual([{ added: [], notAdded: [] }]);
    expect(calls.filter((c) => c.startsWith('add-to-endpoint'))).toEqual([]);
    expect(data.connections.refresh).not.toHaveBeenCalled();
    const toast = toasts().at(-1)!;
    expect(toast).toMatchObject({ title: 'MCP server sentry-mcp started',
      body: 'Its 12 tools are Off until you add them to an endpoint. Declared reads start as Read only; other tools start as Approval.' });
    expect(toast.actions).toEqual([]);
  });

  it.each([
    ['mcp.example.com', 'It connected with your sign-in at mcp.example.com; the agent never sees it.'],
    [null, 'It connected with your sign-in; the agent never sees it.'],
  ])('says the server is signed in (at %s)', async (host, words) => {
    const { el } = await step(server({ http: true, signedIn: { host } }));
    const saved = el.querySelector('[id$="-saved"]')!;
    expect(text(saved.querySelector('.gw-alert__title'))).toBe('Signed in to sentry-mcp');
    expect(text(saved.querySelector('.gw-alert__body'))).toBe(`${words} sentry-mcp is saved, and its 12 tools are Off everywhere until you add them here.`);
  });

  it('previews a newly chosen endpoint once, retries a preview that failed and keeps a refused endpoint unchosen', async () => {
    const { fixture, el } = await step(server(), 'dev', threeEndpoints);
    endpointsApi.previewConnection.mockRejectedValueOnce(new ApiError('server', 500, { detail: 'The core restarted.' }));
    check(box(el, 'team'));
    await settle(fixture);
    const team = el.querySelector('[aria-labelledby$="-pv-team-t"]')!;
    const failed = team.querySelector('[role="alert"]')!;
    expect(text(failed.querySelector('.gw-alert__title'))).toBe('Its tools on team didn’t load');
    expect(text(failed.querySelector('.gw-alert__body'))).toBe('The core restarted.');
    button(failed, 'Try again').click();
    await settle(fixture);
    expect(text(el.querySelector('[aria-labelledby$="-pv-team-t"] .gw-card__description'))).toBe('2 tools · 1 read only · 1 approval');
    check(box(el, 'team'), false);
    await settle(fixture);
    expect(el.querySelector('[aria-labelledby$="-pv-team-t"]')).toBeNull();
    check(box(el, 'team'));
    await settle(fixture);
    check(box(el, 'incident-prod'));
    await settle(fixture);
    expect(box(el, 'incident-prod').checked).toBe(false);
    expect(endpointsApi.previewConnection.mock.calls.map(([slug]) => slug)).toEqual(['dev', 'team', 'team']);
    expect(text(button(el, 'Add to 2 endpoints'))).toBe('Add to 2 endpoints');
  });

  it('says why the endpoints didn’t load, and loads them again on Try again', async () => {
    const { fixture, el, cmp } = await step(server());
    expect(boxes(el)).toHaveLength(2);
    endpointsApi.list.mockRejectedValueOnce(new ApiError('server', 503, { detail: 'The core is starting.' }));
    await cmp.load(server());
    await settle(fixture);
    const alert = el.querySelector('[role="alert"]')!;
    expect(text(alert.querySelector('.gw-alert__title'))).toBe('The endpoints didn’t load');
    expect(text(alert.querySelector('.gw-alert__body'))).toBe('The core is starting. You can add sentry-mcp from an endpoint later.');
    expect(button(el, 'Add to endpoint').getAttribute('aria-disabled')).toBe('true');
    button(alert, 'Try again').click();
    await settle(fixture);
    expect(el.querySelector('[role="alert"]')).toBeNull();
    expect(boxes(el).filter((b) => b.checked).map((b) => b.value)).toEqual(['dev']);
  });
});
