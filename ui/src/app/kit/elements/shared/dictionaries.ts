import { duration } from '../../../core/time/time-format';

/** A status as the kit draws it everywhere (BLUEPRINT §7 "Shared status vocabulary"): tone, icon and words. */
export interface Look {
  readonly tone: 'success' | 'warning' | 'danger' | 'neutral' | 'info' | 'brand';
  readonly icon: string;
  readonly text: string;
}

/** The decision of a call (audit). */
export const DECISIONS: Record<string, Look> = {
  allowed: { tone: 'success', icon: 'check', text: 'Allowed' },
  approved: { tone: 'success', icon: 'user-check', text: 'Approved' },
  pending: { tone: 'warning', icon: 'hourglass', text: 'Waiting for approval' },
  denied: { tone: 'danger', icon: 'shield-x', text: 'Denied by policy' },
  rejected: { tone: 'danger', icon: 'user-x', text: 'Rejected' },
  expired: { tone: 'neutral', icon: 'timer-off', text: 'Expired' },
  error: { tone: 'danger', icon: 'circle-alert', text: 'Error' },
};

export function decisionLook(decision: string | null | undefined): Look {
  return DECISIONS[(decision ?? '').toLowerCase()] ?? { tone: 'neutral', icon: 'circle-dot', text: decision ?? '' };
}

/** A tool's mode; Bypass explicitly runs calls without approval, within policy limits. */
export const MODES: Record<string, Look & { readonly cls: string }> = {
  OFF: { tone: 'neutral', icon: 'circle-off', text: 'Off', cls: 'gw-mode--off' },
  READ_ONLY: { tone: 'info', icon: 'eye', text: 'Read only', cls: 'gw-mode--read' },
  APPROVAL: { tone: 'warning', icon: 'user-check', text: 'Approval', cls: 'gw-mode--approval' },
  BYPASS: { tone: 'danger', icon: 'shield-off', text: 'Bypass', cls: 'gw-mode--bypass' },
};

/** The health of a connection, a client link or a process: "Healthy · 42 ms", "Slow · 2.4 s", "Unreachable". */
export function healthLook(state: string | null | undefined, latencyMs?: number | null): Look {
  const s = (state ?? 'unknown').toLowerCase();
  const ms = latencyMs != null ? ` · ${duration(latencyMs)}` : '';
  switch (s) {
    case 'healthy':
    case 'ok':
    case 'running':
    case 'connected':
      return { tone: 'success', icon: 'circle-check', text: `Healthy${ms}` };
    case 'degraded':
    case 'slow':
      return { tone: 'warning', icon: 'triangle-alert', text: `Slow${ms}` };
    case 'unreachable':
    case 'failed':
    case 'error':
      return { tone: 'danger', icon: 'circle-alert', text: 'Unreachable' };
    case 'checking':
    case 'starting':
      return { tone: 'info', icon: 'loader-circle', text: 'Checking' };
    default:
      return { tone: 'neutral', icon: 'circle-dashed', text: 'Not checked yet' };
  }
}

/** An MCP client's state on this machine. */
export const CLIENT_STATUS: Record<string, Look> = {
  connected: { tone: 'success', icon: 'circle-check', text: 'Connected' },
  detected: { tone: 'info', icon: 'scan-search', text: 'Detected, not connected' },
  'needs-restart': { tone: 'warning', icon: 'rotate-cw', text: 'Needs restart' },
  'not-installed': { tone: 'neutral', icon: 'circle-dashed', text: 'Not installed' },
  error: { tone: 'danger', icon: 'circle-alert', text: 'Error' },
};

export function clientStatusLook(status: string): Look {
  return CLIENT_STATUS[status] ?? { tone: 'neutral', icon: 'circle-dashed', text: status };
}
