import { OAuthConsent, OAuthGrant, TunnelState } from '../../../core/api/tunnel.api';
import { Iso } from '../../../core/api/types';
import { clockTime, shortDate } from '../../../core/time/time-format';

/**
 * What the claude.ai card says about the tunnel (ARCHITECTURE.md §6 tunnel / OAuth, step K3): the published paths,
 * the three gates, when it was public, and the sign-ins you allowed. Pure functions of the API's state and "now".
 */

export type TunnelLook = { readonly tone: 'neutral' | 'success' | 'info' | 'danger'; readonly text: string };

export function tunnelLook(state: TunnelState | null): TunnelLook {
  switch (state?.state) {
    case 'on':
      return { tone: 'success', text: 'Tunnel on' };
    case 'starting':
      return { tone: 'info', text: 'Starting tunnel' };
    case 'error':
      return { tone: 'danger', text: 'Tunnel error' };
    case 'off':
      return { tone: 'neutral', text: 'Tunnel off' };
    default:
      return { tone: 'neutral', text: 'Not available yet' };
  }
}

/** "Off since 12:10 · public today 08:40 to 12:10", "Off · never published", "Public since just now". */
export function tunnelHistory(t: TunnelState, now: number, relative: (at: Iso) => string): string {
  if (t.state === 'on') return t.since ? `Public since ${relative(t.since)}` : 'Public';
  const last = [...t.history].filter((h) => h.to).sort((a, b) => Date.parse(b.to!) - Date.parse(a.to!))[0] ?? null;
  const since = t.since ? `Off since ${clockOrDate(t.since, now)}` : 'Off';
  if (!last) return `${since} · never published`;
  const day = shortDate(last.from, now);
  const when = day === 'Today' ? 'today' : day;
  return `${since} · public ${when} ${clockTime(last.from, false)} to ${clockTime(last.to!, false)}`;
}

function clockOrDate(at: Iso, now: number): string {
  const day = shortDate(at, now);
  return day === 'Today' ? clockTime(at, false) : `${day} ${clockTime(at, false)}`;
}

/** The calls claude.ai made in the last public period, and the latest one through the tunnel. */
export function whilePublic(t: TunnelState, grants: readonly OAuthGrant[]): { readonly calls: number; readonly lastAt: Iso | null } | null {
  const last = [...t.history].sort((a, b) => Date.parse(b.from) - Date.parse(a.from))[0] ?? null;
  if (!last) return null;
  let lastAt: Iso | null = null;
  for (const g of grants) {
    if (g.ingress === 'TUNNEL' && g.lastUsedAt && (!lastAt || Date.parse(g.lastUsedAt) > Date.parse(lastAt))) lastAt = g.lastUsedAt;
  }
  return { calls: last.calls, lastAt };
}

/**
 * The endpoints a publish preview keeps local: its `/mcp/<slug>` lines. The core words the app's own line itself
 * ("The app and its admin API (127.0.0.1:7465)"); the dialogs say that part in their own words.
 */
export function localEndpoints(local: readonly string[]): string[] {
  return local.map((l) => /^\/mcp\/(.+)$/.exec(l)?.[1] ?? null).filter((s): s is string => !!s);
}

/** Sign-ins that still work (Signed in). */
export function activeGrants(grants: readonly OAuthGrant[]): OAuthGrant[] {
  return grants.filter((g) => g.state === 'active' || g.state === 'address-changed');
}

/** "Read access to stage-readonly", "Access to dev: 14 read only, 3 with approval". */
export function consentWants(c: OAuthConsent): string {
  if (!c.tools.approval && !c.tools.bypass) return 'Read access to';
  return 'Access to';
}

export function consentTools(c: OAuthConsent): string | null {
  if (!c.tools.approval && !c.tools.bypass) return null;
  const parts = [c.tools.read ? `${c.tools.read} read only` : null, c.tools.approval ? `${c.tools.approval} with approval` : null, c.tools.bypass ? `${c.tools.bypass} bypass approval` : null].filter(Boolean);
  return parts.join(', ');
}

/** "5 min", "48 s": how long the consent waits before it denies by itself. */
export function answerWithin(expiresAt: Iso, now: number): string {
  const s = Math.max(0, Math.round((Date.parse(expiresAt) - now) / 1000));
  return s >= 60 ? `${Math.ceil(s / 60)} min` : `${s} s`;
}
