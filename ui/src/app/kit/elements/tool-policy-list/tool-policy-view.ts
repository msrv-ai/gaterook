import { ToolGroup, ToolRow, WiredConnection } from '../../../core/api/endpoints.api';
import { Env, JsonObject, JsonValue, ToolMode } from '../../../core/api/types';
import { formatCount } from '../../../core/time/time-format';

/**
 * What the tool-policy-list element shows, from the admin API's tool groups (ARCHITECTURE.md §6 endpoint-detail,
 * `GET /api/endpoints/{slug}/tools`). Pure functions, tested against the kit's wording
 * (design/elements/tool-policy-list.html).
 */

/** One group as the element draws it: the API's group plus what the page knows about its connection. */
export interface ToolPolicyGroup {
  readonly group: ToolGroup;
  /** The connector type of the group's connection (`postgres`, `ssh`, `mcp-server`): icon and limit fields. */
  readonly type: string | null;
  /** What the connection is, before the counts: "PostgreSQL 16", "SSH · 10.20.0.14", "Kubernetes · namespace app-dev". */
  readonly detail: string | null;
  /**
   * The group's heading when the page names it itself: Playground calls the core's own tools "General" (kit
   * playground.html), endpoint detail "Built-in tools" (the default without a connection).
   */
  readonly title?: string | null;
}

/**
 * The groups of `GET /api/endpoints/{slug}/tools` with what the endpoint detail knows of each connection: its connector
 * type (icon, limit fields) and its type name as the detail line. The core group (no connection) has neither.
 */
export function toolPolicyGroups(groups: readonly ToolGroup[], connections: readonly WiredConnection[]): ToolPolicyGroup[] {
  return groups.map((group) => {
    // The core's own tools answer from the gateway itself (kit endpoint-detail: "Built-in tools").
    if (group.connection === null) return { group, type: null, detail: BUILT_IN_DETAIL };
    const c = connections.find((x) => x.name === group.connection);
    return { group, type: c?.type ?? null, detail: c?.typeName ?? null };
  });
}

/** The kit's line under "Built-in tools": the core's own tools never reach a resource, so they have no limits. */
export const BUILT_IN_DETAIL = 'Answer from the gateway, never from a resource';

/** The radio values of the picker (the kit's `value`s) and the API's modes. */
export const MODE_VALUE: Record<ToolMode, 'off' | 'read' | 'approval' | 'bypass'> = { OFF: 'off', READ_ONLY: 'read', APPROVAL: 'approval', BYPASS: 'bypass' };

export const MODE_FILTERS = [
  { value: 'ALL', label: 'All', icon: null },
  { value: 'OFF', label: 'Off', icon: 'circle-off' },
  { value: 'READ_ONLY', label: 'Read only', icon: 'eye' },
  { value: 'APPROVAL', label: 'Approval', icon: 'user-check' },
  { value: 'BYPASS', label: 'Bypass', icon: 'shield-off' },
] as const;

export type ModeFilter = (typeof MODE_FILTERS)[number]['value'];

/** "6 read only", "3 approval", "5 off": the summary chips and group descriptions, in this order. */
export function modeCounts(tools: readonly { readonly mode: ToolMode }[]): { readonly mode: ToolMode; readonly count: number; readonly text: string }[] {
  const order: ToolMode[] = ['READ_ONLY', 'APPROVAL', 'BYPASS', 'OFF'];
  const words: Record<ToolMode, string> = { READ_ONLY: 'read only', APPROVAL: 'approval', BYPASS: 'bypass', OFF: 'off' };
  return order
    .map((mode) => ({ mode, count: tools.filter((t) => t.mode === mode).length }))
    .filter((c) => c.count > 0)
    .map((c) => ({ ...c, text: `${c.count} ${words[c.mode]}` }));
}

export function toolCount(n: number): string {
  return `${formatCount(n)} ${n === 1 ? 'tool' : 'tools'}`;
}

/** Search over the tool's name and description; the mode filter over its current mode. */
export function matches(tool: ToolRow, description: string | null, query: string, mode: ModeFilter): boolean {
  if (mode !== 'ALL' && tool.mode !== mode) return false;
  const q = query.trim().toLowerCase();
  if (!q) return true;
  return `${tool.name} ${description ?? ''}`.toLowerCase().includes(q);
}

/** Why a mode option is unavailable, or what a mode means for this tool (`__mode-note`). */
export interface ModeNote {
  readonly icon: 'lock' | 'info' | 'shield-off';
  readonly text: string;
  /** The whole picker is disabled (the endpoint is read only). */
  readonly locked: boolean;
  /** Read only is natively disabled (it would remove the approval). */
  readonly readOnlyDisabled: boolean;
  /** Read only is unavailable until the person confirms it for this tool (external tools). */
  readonly needsConfirmation: boolean;
}

/** Built-in tools whose Read only is a safe subset worth naming (kit: ssh_run). */
const SUBSET_NOTES: Record<string, string> = {
  ssh_run: 'Read only runs runbooks marked read only',
};

export function mutates(t: ToolRow): boolean {
  return !!t.annotations.destructiveHint || (!t.readOnlyAllowed && !t.declaredBy);
}

export function modeNote(t: ToolRow, endpoint: { readonly slug: string; readonly readOnly: boolean }): ModeNote | null {
  if (endpoint.readOnly && mutates(t)) {
    return {
      icon: 'lock',
      text: `Locked: endpoint ${endpoint.slug} is read only, so tools that change data stay off`,
      locked: true,
      readOnlyDisabled: false,
      needsConfirmation: false,
    };
  }
  if (t.mode === 'BYPASS') {
    const readOnlyDisabled = t.declaredBy ? !!t.annotations.destructiveHint || !t.annotations.readOnlyHint : !t.readOnlyAllowed;
    return {
      icon: 'shield-off',
      text: 'Bypass runs without approval, including changes to data. Policy limits still apply.',
      locked: false,
      readOnlyDisabled,
      needsConfirmation: !!t.declaredBy && !readOnlyDisabled && !t.readOnlyConfirmed,
    };
  }
  if (t.declaredBy) {
    if (t.annotations.destructiveHint) return lock('Declared destructive: approval, bypass or off');
    if (!t.annotations.readOnlyHint) return lock('Not declared read only: approval, bypass or off');
    if (!t.readOnlyConfirmed) {
      return { icon: 'lock', text: 'Read only needs your confirmation for this tool', locked: false, readOnlyDisabled: false, needsConfirmation: true };
    }
    return null;
  }
  if (!t.readOnlyAllowed) return lock('Mutates data: approval, bypass or off');
  const subset = SUBSET_NOTES[t.name];
  return subset ? { icon: 'info', text: subset, locked: false, readOnlyDisabled: false, needsConfirmation: false } : null;
}

function lock(text: string): ModeNote {
  return { icon: 'lock', text, locked: false, readOnlyDisabled: true, needsConfirmation: false };
}

/** The hints of "Set all to…": what each choice does to this group, and what stays as it is. */
export function bulkHints(
  connection: string,
  tools: readonly ToolRow[],
  endpoint: { readonly readOnly: boolean },
): { readonly off: string; readonly read: string; readonly approval: string; readonly bypass: string } {
  const changing = tools.filter((t) => mutates(t)).map((t) => t.name);
  const unconfirmed = tools.filter((t) => t.declaredBy && !mutates(t) && !t.readOnlyConfirmed).map((t) => t.name);
  const names = (list: readonly string[]) =>
    list.length <= 1 ? list.join('') : `${list.slice(0, -1).join(', ')} and ${list.at(-1)}`;
  let read = 'Calls run at once and can\'t change data';
  let approval = 'Every call waits for your decision';
  if (changing.length && endpoint.readOnly) {
    read = `${names(changing)} ${changing.length === 1 ? 'stays' : 'stay'} off: the endpoint is read only`;
    approval = `Every call waits for you; ${names(changing)} ${changing.length === 1 ? 'stays' : 'stay'} off`;
  } else if (changing.length) {
    read = `${names(changing)} ${changing.length === 1 ? 'stays as it is: it changes' : 'stay as they are: they change'} data`;
  }
  if (unconfirmed.length) read += `; ${names(unconfirmed)} ${unconfirmed.length === 1 ? 'needs' : 'need'} your confirmation first`;
  return { off: `Agents can't call any ${connection} tool`, read, approval, bypass: endpoint.readOnly ? 'Unavailable: the endpoint is read only' : 'Calls run without approval, including changes to data; policy limits still apply' };
}

/** One per-tool limit field: a key of the environment policy that a pair may narrow (`overrides`). */
export interface LimitField {
  readonly key: string;
  readonly label: string;
  readonly unit: string;
  /** The policy section whose value the field inherits. */
  readonly section: string;
  /** The policy stores ms, the field shows seconds. */
  readonly scale: number;
}

const field = (key: string, label: string, unit: string, section: string, scale = 1): LimitField => ({ key, label, unit, section, scale });

/** The fields of the limits row by connector family (the policy sections of §6 policies). */
export function limitFields(type: string | null): LimitField[] {
  switch (type) {
    case 'postgres':
    case 'mysql':
      return [field('rowLimit', 'Row limit', 'rows', 'sql'), field('statementTimeoutMs', 'Timeout', 's', 'sql', 1000), field('maxResponseKb', 'Max response size', 'KB', 'sql')];
    case 'ssh':
      return [field('commandTimeoutSeconds', 'Timeout', 's', 'ssh'), field('maxOutputKb', 'Max output size', 'KB', 'ssh')];
    case 'logs':
      return [field('logLines', 'Line limit', 'lines', 'limits'), field('toolTimeoutSeconds', 'Timeout', 's', 'limits'), field('maxResponseKb', 'Max response size', 'KB', 'limits')];
    case 'http':
      return [field('toolTimeoutSeconds', 'Timeout', 's', 'http'), field('maxResponseKb', 'Max response size', 'KB', 'http')];
    default:
      return [field('toolTimeoutSeconds', 'Timeout', 's', 'limits'), field('maxResponseKb', 'Max response size', 'KB', 'limits')];
  }
}

/** The value the environment policy gives a field (shown in the field's unit), or null when unknown. */
export function inherited(f: LimitField, sections: JsonObject | null | undefined): number | null {
  const section = sections?.[f.section];
  const v = section && typeof section === 'object' && !Array.isArray(section) ? (section as JsonObject)[f.key] : undefined;
  return typeof v === 'number' ? v / f.scale : null;
}

/** The pair's own value for a field (in the field's unit), or null when it inherits. */
export function overridden(f: LimitField, overrides: JsonObject | null): number | null {
  const v: JsonValue | undefined = overrides?.[f.key];
  return typeof v === 'number' ? v / f.scale : null;
}

export function overrideCount(fields: readonly LimitField[], overrides: JsonObject | null): number {
  return fields.filter((f) => overridden(f, overrides) !== null).length;
}

/** "1,000 rows", "5 s", "512 KB". */
export function withUnit(n: number, unit: string): string {
  return `${formatCount(n)} ${unit}`;
}

/** The environment policy a pair inherits from: its connection's (dev-db → dev, billing-api → stage). */
export function policyEnv(group: ToolGroup, fallback: Env): Env {
  return group.env ?? fallback;
}
