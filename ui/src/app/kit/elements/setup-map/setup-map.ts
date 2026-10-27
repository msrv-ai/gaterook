import { ChangeDetectionStrategy, Component, booleanAttribute, computed, input, numberAttribute } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { RouterLink } from '@angular/router';
import { EndpointDetail } from '../../../core/api/endpoints.api';
import { SetupNode, SetupNodeId } from '../../../core/api/overview.api';
import { Env } from '../../../core/api/types';
import { GwCopy } from '../../copy';
import { GwIcon } from '../../icons';
import { GwClientMark, clientName } from '../shared/client-mark';
import { connectorIcon } from '../shared/connector-icons';
import { Look, clientStatusLook, healthLook } from '../shared/dictionaries';

/** The kit's copy of each node (elements/setup-map.html). */
const NODES: Record<SetupNodeId, { readonly icon: string; readonly title: string; readonly desc: string; readonly route: string; readonly what: string }> = {
  secrets: { icon: 'key-round', title: 'Secrets & resources', desc: 'Credentials the agent never sees', route: '/secrets', what: 'secrets' },
  connections: { icon: 'database', title: 'Connections', desc: 'Where tools run: databases, hosts, APIs', route: '/connections', what: 'connections' },
  policies: { icon: 'shield', title: 'Policies', desc: 'Decides each call: mode, limits, approval', route: '/policies', what: 'policies' },
  endpoints: { icon: 'waypoints', title: 'Endpoints', desc: 'One MCP URL, with a token or OAuth', route: '/endpoints', what: 'endpoints' },
  clients: { icon: 'app-window', title: 'AI apps', desc: 'Apps your agent works in', route: '/clients', what: 'AI apps' },
};

const STATE: Record<SetupNode['state'], { readonly tile: string; readonly badge: string; readonly icon: string; readonly text: string }> = {
  done: { tile: 'brand', badge: 'success', icon: 'circle-check', text: 'Done' },
  todo: { tile: 'neutral', badge: 'neutral', icon: 'circle-dashed', text: 'Not set up' },
  attention: { tile: 'warning', badge: 'warning', icon: 'triangle-alert', text: 'Needs attention' },
};

let nextId = 0;

/**
 * `<gw-setup-map [nodes]="overview.setupMap.nodes">` (elements/setup-map.html): what to configure and how it connects,
 * in setup order — secrets and resources, connections, policies, endpoints, clients. Each node says its state in words
 * (the core's status line) and links to its page; done nodes are joined by brand arrows, the rest are dashed. An
 * agent's call travels the other way, and the secret is injected at the connection: the visible caption and the
 * chain's description say so. From lg the chain runs across; `vertical` keeps it stacked (onboarding, side panels).
 */
@Component({
  selector: 'gw-setup-map',
  imports: [GwIcon, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './setup-map.html',
})
export class GwSetupMap {
  readonly nodes = input.required<readonly SetupNode[]>();
  readonly vertical = input(false, { transform: booleanAttribute });
  /** The visible "which way a call travels" line under the chain (a page with two maps shows it once). */
  readonly caption = input(true, { transform: booleanAttribute });
  /** The node titles' heading level. */
  readonly level = input(3, { transform: numberAttribute });
  /**
   * A page that titles the chain itself (onboarding: "Where this leads") names it by that heading instead of the
   * generic "Setup path".
   */
  readonly labelledBy = input<string | null>(null);
  /**
   * The chain's description for screen readers, when the page tells it in its own terms (onboarding ties each node to
   * its step: "step 1 protects secrets and resources, …"); otherwise the element's sentence.
   */
  readonly description = input<string | null>(null);

  protected readonly uid = `sm-${++nextId}`;
  protected readonly copy = NODES;
  protected readonly look = STATE;
  /** The core's order is setup order; keep only nodes the kit knows. */
  protected readonly ordered = computed(() => this.nodes().filter((n) => n.id in NODES));
}

/** A client of an endpoint on the wiring card: its id and state (K1 `/api/clients`). */
export interface WiringClient {
  readonly client: string;
  readonly status: string;
}

/** A connection an endpoint's tools run on, with its health. */
export interface WiringConnection {
  readonly name: string;
  readonly type: string;
  readonly env: Env;
  readonly health: string;
  readonly latencyMs: number | null;
}

/** Everything the wiring needs about one endpoint (endpoint detail: summary, tools, connections, clients). */
export interface SetupWiring {
  readonly slug: string;
  readonly url: string;
  readonly tools: { readonly read: number; readonly approval: number; readonly off: number; readonly bypass?: number };
  readonly tokens: number;
  readonly oauth: boolean;
  /** "dev rules · mutations need approval". */
  readonly policy: string;
  readonly connections: readonly WiringConnection[];
  readonly clients: readonly WiringClient[];
  /**
   * On the endpoint's own page (`inPage`): the environments whose rules its pairs follow (each a link to its policy),
   * the limits line of the endpoint node ("120 calls per min per token · 512 KB per result") with the policy it comes
   * from, and how its clients get their tokens ("Tokens issued on the fly, none stored").
   */
  readonly policyEnvs?: readonly Env[];
  readonly limits?: { readonly text: string; readonly env: Env } | null;
  readonly clientsNote?: string | null;
  /** The wiring in words for screen readers, when the page knows more than the counts (who is detected, the rules). */
  readonly flowText?: string | null;
}

/**
 * The wiring of one endpoint from `GET /api/endpoints/{slug}` and the clients that use it (K1 `/api/clients` links;
 * none until K1 is merged). The core measures no latency per endpoint row, so health stands alone.
 */
export function setupWiring(detail: EndpointDetail, clients: readonly WiringClient[] = []): SetupWiring {
  const e = detail.endpoint;
  return {
    slug: e.slug,
    url: e.url,
    tools: e.tools,
    tokens: e.tokens.active,
    oauth: detail.settings.oauthEnabled,
    policy: `${e.env} rules · mutations need approval`,
    connections: detail.connections.map((c) => ({ name: c.name, type: c.type, env: c.env, health: c.health, latencyMs: null })),
    clients,
  };
}

/**
 * `<gw-setup-wiring [wiring]="…">` (elements/setup-map.html, "Wiring on endpoint-detail", gw-setup-map--wiring): the
 * endpoint in the middle, the clients that call it on the left, the connections its tools run on, with environment
 * and health, on the right.
 *
 * `inPage` is the wiring as pages/endpoint-detail.html draws it under the endpoint's own header: the URL and sign-in
 * are in the header, so the endpoint node lists Tools, Policy (links to each environment's rules) and Limits, and the
 * footers lead to the page's own tabs (`?tab=clients|tools|connections`) instead of other pages.
 */
@Component({
  selector: 'gw-setup-wiring',
  imports: [GwIcon, RouterLink, GwClientMark, GwCopy, NgTemplateOutlet],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './setup-wiring.html',
})
export class GwSetupWiring {
  readonly wiring = input.required<SetupWiring>();
  readonly level = input(3, { transform: numberAttribute });
  readonly inPage = input(false, { transform: booleanAttribute });

  protected readonly uid = `smw-${++nextId}`;
  protected readonly clientName = clientName;
  protected readonly connectorIcon = connectorIcon;

  protected readonly toolCount = computed(() => {
    const t = this.wiring().tools;
    return t.read + t.approval + t.off + (t.bypass ?? 0);
  });
  /** "22 tools · 8 connections · 1 token": counts with the right number of the noun (live data has ones). */
  protected readonly counts = computed(() => {
    const w = this.wiring();
    const n = (k: number, one: string) => `${k} ${k === 1 ? one : `${one}s`}`;
    // The endpoint's own page counts what can call it now (kit: "2 active tokens").
    return `${n(this.toolCount(), 'tool')} · ${n(w.connections.length, 'connection')} · ${n(w.tokens, this.inPage() ? 'active token' : 'token')}`;
  });
  protected readonly connected = computed(() => this.wiring().clients.filter((c) => c.status === 'connected').length);
  protected readonly detected = computed(() => this.wiring().clients.filter((c) => c.status === 'detected').length);
  protected readonly unhealthy = computed(() => {
    const looks = this.wiring().connections.map((c) => healthLook(c.health).tone);
    return { down: looks.filter((t) => t === 'danger').length, slow: looks.filter((t) => t === 'warning').length };
  });
  protected readonly attention = computed(() => this.unhealthy().down + this.unhealthy().slow > 0);
  protected readonly flow = computed(() => {
    const w = this.wiring();
    if (w.flowText) return w.flowText;
    const names = w.clients.filter((c) => c.status === 'connected').map((c) => clientName(c.client));
    const who = names.length ? `${names.join(' and ')} call` : 'AI apps call';
    const n = w.connections.length;
    return `${who} endpoint ${w.slug} with their tokens; ${w.slug} runs its tools on ${n} ${n === 1 ? 'connection' : 'connections'}, which inject the secrets.`;
  });
  protected readonly connectionsSummary = computed(() => {
    const n = this.wiring().connections.length;
    const parts = [`${n} ${n === 1 ? 'connection' : 'connections'}`];
    if (this.unhealthy().down) parts.push(`${this.unhealthy().down} unreachable`);
    if (this.unhealthy().slow) parts.push(`${this.unhealthy().slow} slow`);
    return parts.join(' · ');
  });

  protected health(c: WiringConnection): Look {
    return healthLook(c.health, c.health === 'healthy' || c.health === 'degraded' ? c.latencyMs : null);
  }

  protected clientLook(c: WiringClient): Look {
    const look = clientStatusLook(c.status);
    // On the wiring card a detected client reads "Detected", in neutral: it isn't wired to this endpoint. The
    // endpoint's own page draws it in info, as its Clients tab does (pages/endpoint-detail.html: Cursor).
    return c.status === 'detected' ? { tone: this.inPage() ? 'info' : 'neutral', icon: look.icon, text: 'Detected' } : look;
  }
}
