import { ClientCard, ClientId } from '../../core/api/clients.api';
import { ConnectionHealth, ConnectionRow, nextHealth } from '../../core/api/connections.api';
import { EndpointSummary } from '../../core/api/endpoints.api';
import { HealthRow } from '../../core/api/overview.api';
import { connectorIcon, connectorName } from '../../kit/elements/shared/connector-icons';
import { TileState } from '../../kit/elements/status-tile/status-tile';
import { appAnchor, tileHealth } from './overview-view';

export interface MapConnection {
  /** The saved row, with the newest observed health; retains the version for reviewed changes. */
  readonly row: ConnectionRow;
  readonly name: string;
  readonly typeLabel: string;
  readonly icon: string;
  readonly mcp: boolean;
  readonly assigned: boolean;
  /** The connection's global switch, independent of the selected endpoint's switch. */
  readonly enabled: boolean;
  readonly effectiveOn: boolean;
  readonly blockedReason: string | null;
  readonly state: TileState;
  readonly unreachable: boolean;
  readonly route: readonly string[];
  readonly fragment: string | null;
}

/** An older overview snapshot must not replace a newer connection check or SSE update. */
function latestHealth(row: ConnectionRow, health: readonly HealthRow[]): ConnectionHealth {
  const observed = health.find((item) => item.name === row.name);
  if (!observed) return row.health;
  const currentAt = row.health.checkedAt ? Date.parse(row.health.checkedAt) : Number.NaN;
  const observedAt = observed.checkedAt ? Date.parse(observed.checkedAt) : Number.NaN;
  if (!Number.isFinite(observedAt) || (Number.isFinite(currentAt) && currentAt > observedAt)) return row.health;
  return nextHealth(row.health, {
    state: observed.state, latencyMs: observed.latencyMs, message: observed.message, checkedAt: observed.checkedAt,
  });
}

/** Native services on this endpoint, plus real installed MCP servers that can be added to it. */
export function mapConnections(
  rows: readonly ConnectionRow[] | null,
  health: readonly HealthRow[],
  endpoint: EndpointSummary | null,
): MapConnection[] | null {
  if (rows === null) return null;
  return rows.filter((row) => row.type === 'mcp-server' || (endpoint !== null && row.usedBy.includes(endpoint.slug))).map((original) => {
    const row = { ...original, health: latestHealth(original, health) };
    const mcp = row.type === 'mcp-server';
    const assigned = endpoint !== null && row.usedBy.includes(endpoint.slug);
    const enabled = row.enabled !== false;
    const blockedReason = !row.verified ? 'Review this connection before enabling access.'
      : row.setupRequired ? 'Finish setting up this connection before enabling access.'
      : endpoint?.blockedReason ?? null;
    const effectiveOn = assigned && enabled && endpoint?.status === 'running' && blockedReason === null;
    const state: TileState = !assigned ? { tone: 'neutral', label: `Not connected to ${endpoint?.slug ?? 'an endpoint'}` }
      : !row.verified ? { tone: 'warning', label: 'Review required' }
      : row.setupRequired ? { tone: 'warning', label: 'Setup required' }
      : endpoint?.blockedReason ? { tone: 'warning', label: 'Access blocked' }
      : endpoint?.status === 'disabled' ? { tone: 'neutral', label: 'Endpoint off' }
      : !enabled ? { tone: 'neutral', label: 'Connection off' }
      : mcp && row.health.state.toLowerCase() === 'healthy' ? { tone: 'success', label: 'Connected' }
      : tileHealth(row.health);
    return {
      row, name: row.name, typeLabel: row.typeLabel || row.typeName || connectorName(row.type), icon: connectorIcon(row.type),
      mcp, assigned, enabled, effectiveOn, blockedReason, state,
      unreachable: effectiveOn && row.health.state.toLowerCase() === 'unreachable',
      route: mcp ? ['/connectors'] : ['/connections', row.name], fragment: mcp ? 'mcp-servers' : null,
    };
  });
}

/** Only enabled, usable connections count as unreachable; an unavailable list is never zero. */
export function endpointUnreachable(
  endpoint: EndpointSummary,
  rows: readonly ConnectionRow[] | null,
  health: readonly HealthRow[],
): number | null {
  const connections = mapConnections(rows, health, endpoint);
  return connections === null ? null : connections.filter((connection) => connection.unreachable).length;
}

export interface MapApp {
  readonly card: ClientCard;
  readonly client: ClientId;
  readonly name: string;
  readonly mark: string;
  readonly tone: string;
  readonly state: TileState;
  /** A saved access path on this endpoint; revoked and removed entries do not count. */
  readonly assigned: boolean;
  readonly connected: boolean;
  /** A saved path that is paused, needs a restart, or needs repair. */
  readonly pending: boolean;
  readonly action: 'connect' | 'install' | 'manage' | null;
  readonly actionReason: string | null;
  readonly fragment: string;
}

const APP_MARKS: Record<ClientId, { readonly mark: string; readonly tone: string }> = {
  'claude-desktop': { mark: 'CD', tone: 'claude' },
  codex: { mark: 'Cx', tone: 'codex' },
  cursor: { mark: 'Cu', tone: 'cursor' },
  antigravity: { mark: 'Ag', tone: 'antigravity' },
};

function connectReason(card: ClientCard, endpoint: EndpointSummary | null): string | null {
  if (!endpoint) return 'Create or select an endpoint first.';
  if (endpoint.blockedReason) return endpoint.blockedReason;
  if (endpoint.status === 'disabled') return 'Turn on this endpoint before connecting an AI app.';
  if (!card.methods.some((method) => endpoint.env !== 'prod' || method.prodAllowed)) {
    return 'No supported connection method is available for this endpoint.';
  }
  return null;
}

/** The selected endpoint's links are authoritative; a global Connected badge can belong to a different endpoint. */
export function mapApps(cards: readonly ClientCard[] | null, endpoint: EndpointSummary | null): MapApp[] | null {
  if (cards === null) return null;
  return cards.map((card) => {
    const links = endpoint ? card.links.filter((link) => link.endpoint === endpoint.slug) : [];
    const active = links.filter((link) => link.status !== 'revoked' && link.status !== 'removed-outside');
    const assigned = active.length > 0;
    const connected = assigned && active.some((link) => link.status === 'connected') && endpoint?.status === 'running'
      && !endpoint.blockedReason && card.status !== 'not-installed';
    let state: TileState;
    let action: MapApp['action'];
    if (card.status === 'not-installed') {
      state = { tone: 'neutral', label: 'Not installed' };
      action = 'install';
    } else if (assigned && endpoint?.blockedReason) {
      state = { tone: 'warning', label: 'Access blocked' };
      action = 'manage';
    } else if (assigned && endpoint?.status === 'disabled') {
      state = { tone: 'neutral', label: 'Endpoint off' };
      action = 'manage';
    } else if (connected) {
      state = { tone: 'success', label: 'Connected' };
      action = null;
    } else if (active.some((link) => link.status === 'needs-restart')) {
      state = { tone: 'warning', label: 'Needs restart' };
      action = 'manage';
    } else if (active.length || links.some((link) => link.status === 'removed-outside')) {
      state = { tone: 'danger', label: 'Needs repair' };
      action = 'manage';
    } else {
      state = { tone: 'neutral', label: 'Not connected' };
      action = 'connect';
    }
    return {
      card, client: card.client, name: card.name, ...APP_MARKS[card.client], state,
      assigned, connected, pending: assigned && !connected, action,
      actionReason: action === 'connect' ? connectReason(card, endpoint) : null, fragment: appAnchor(card.client),
    };
  });
}
