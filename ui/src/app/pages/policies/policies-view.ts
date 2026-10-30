import { ConnectionRow } from '../../core/api/connections.api';
import { EndpointSummary } from '../../core/api/endpoints.api';
import { EnvPolicy } from '../../core/api/policies.api';
import { Env, JsonObject, JsonValue } from '../../core/api/types';
import { compactLine } from '../../kit/elements/risk-callout/risk-callout';
import { TextPart } from '../../kit/elements/shared/literals';
import { FieldSpec, Hint, HintPart, c, codeList, displayOf, fieldSpec, optionLabel, sectionSpec, t, typeRank } from './policy-catalog';
import {
  Change,
  Display,
  KeySchema,
  Kind,
  Origin,
  PoliciesByEnv,
  PolicySchemas,
  chain,
  effectiveValue,
  explicitKeys,
  fallback,
  forbidden,
  formatNumber,
  inheritors,
  isLocked,
  keySchema,
  kindOf,
  origin,
  originCounts,
  parseNumber,
} from './policy-model';

/** "Development", "Staging", "Production": the tabs. */
export const ENV_TITLES: Readonly<Record<Env, string>> = { dev: 'Development', stage: 'Staging', prod: 'Production' };

export function capitalize(s: string): string {
  return s ? s.charAt(0).toUpperCase() + s.slice(1) : s;
}

/** An option in a sentence: "Optional" → "optional", but "IBANs" stays an acronym. */
export function lowerFirst(s: string): string {
  return /^[A-Z]{2,}/.test(s) ? s : s.charAt(0).toLowerCase() + s.slice(1);
}

export function plural(n: number, one: string, many = `${one}s`): string {
  return `${formatNumber(n)} ${n === 1 ? one : many}`;
}

/** "a", "a and b", "a, b and c". */
export function joinWords(words: readonly string[]): string {
  if (words.length <= 1) return words[0] ?? '';
  return `${words.slice(0, -1).join(', ')} and ${words[words.length - 1]}`;
}

// ── Values in sentences ─────────────────────────────────────────────────────────────────────────────────────────

/** Everything a setting needs to be shown: its schema, how it is edited and typed, the kit's words. */
export interface KeyInfo {
  readonly section: string;
  readonly key: string;
  readonly ks: KeySchema;
  readonly kind: Kind;
  readonly display: Display;
  readonly spec: FieldSpec;
}

export function keyInfo(schemas: PolicySchemas, section: string, key: string): KeyInfo | null {
  const ks = keySchema(schemas, section, key);
  const kind = kindOf(ks);
  if (!kind) return null;
  return { section, key, ks, kind, display: displayOf(section, key, ks), spec: fieldSpec(section, key, ks) };
}

/** A number with its unit: "5 s", "1,000 rows", "120 calls per min". */
export function numberText(info: KeyInfo, value: JsonValue | undefined): string {
  if (typeof value !== 'number') return 'none';
  if (value === 0 && info.ks['x-stricter'] === 'min-positive') return 'no limit';
  const n = formatNumber(value / info.display.scale);
  return info.display.unit ? `${n} ${info.display.unit}` : n;
}

/** A value as words in a sentence ("dev: 5 s", "dev: off", "dev: optional", "dev: `public.*`, `reporting.*`"). */
export function valueParts(info: KeyInfo, value: JsonValue | undefined): HintPart[] {
  switch (info.kind) {
    case 'number':
      return [t(numberText(info, value))];
    case 'boolean':
      return [t(value === true ? 'on' : 'off')];
    case 'select':
      return [t(value === undefined || value === null ? 'none' : lowerFirst(optionLabel(info.spec, info.ks, value)))];
    case 'list': {
      const items = Array.isArray(value) ? value : [];
      if (!items.length) return [t('none')];
      if (info.ks.items?.enum) return [t(joinWords(items.map((v) => lowerFirst(optionLabel(info.spec, info.ks, v, true)))))];
      const labels = items.map((v) => itemLabel(info, v));
      return labels.length > 4 ? [t(plural(labels.length, 'item'))] : codeList(labels, ', ');
    }
  }
}

export function itemLabel(info: KeyInfo, v: JsonValue): string {
  return info.spec.tags?.label ? info.spec.tags.label(v) : String(v);
}

/** A value as plain text (toasts, the unsaved note, the Reset dialog). */
export function valueText(info: KeyInfo, value: JsonValue | undefined): string {
  return valueParts(info, value)
    .map((p) => p.text)
    .join('');
}

// ── Where a value comes from: the hint under a field ───────────────────────────────────────────────────────────

/** "dev: 5 s", "prod default: 10 min", "default: 30 s": what a value would be without this environment's own. */
function referenceParts(env: Env, o: Origin, info: KeyInfo, value: JsonValue | undefined): HintPart[] {
  const label = o.kind === 'inherited' ? `${o.from}: ` : o.kind === 'env-default' ? `${env} default: ` : 'default: ';
  return [t(label), ...valueParts(info, value)];
}

export interface HintInput {
  readonly policies: PoliciesByEnv;
  readonly schemas: PolicySchemas;
  readonly env: Env;
  readonly info: KeyInfo;
  /** The value the form asks for now (or the stored one). */
  readonly value: JsonValue | undefined;
  /** The person changed it and hasn't saved. */
  readonly dirty: boolean;
  /** The connections of the section on this environment. */
  readonly connections: readonly string[];
}

/**
 * The hint under a field, as the kit words it per environment:
 * - dev, the root the others start from, says what the setting does ("The database cancels slower statements.");
 *   so does a key that is never inherited ("Per environment, never inherited.") and a plain default on prod;
 * - a value set on a child: "Set on stage · dev: 1,000 rows"; inherited: "Inherited from dev: 5 s";
 * - on prod, which inherits nothing, set values and prod defaults compare with dev: "Set on prod · dev: `public.*`",
 *   "Prod default · dev: 5 s";
 * - an unsaved change: "Unsaved · prod default: 10 min".
 */
export function hintFor(x: HintInput): Hint {
  const { policies, schemas, env, info } = x;
  const ctx = { env, value: x.value, connections: x.connections };
  const describe = info.spec.describe?.(ctx) ?? [];
  const note = info.spec.note?.(ctx) ?? [];
  const fb = fallback(policies, schemas, env, info.section, info.key);
  if (x.dirty) return fb.origin.kind === 'own' ? [t('Unsaved. '), ...describe] : [t('Unsaved · '), ...referenceParts(env, fb.origin, info, fb.value)];
  const o = origin(policies, schemas, env, info.section, info.key);
  const parent = policies[env]?.inherits ?? null;
  const root = !parent && (env === 'dev' || inheritors(policies, env).length > 0);
  if (root || fb.origin.kind === 'own') return describe;
  const withNote = (parts: HintPart[]): Hint => (note.length ? [...parts, t('. '), ...note] : parts);
  /** " · dev: 5 s": how dev has it, for an environment that compares with dev rather than following it. */
  const devRef = (): HintPart[] => {
    const dev = policies.dev ? (effectiveValue(policies.dev, info.section, info.key) ?? fallback(policies, schemas, 'dev', info.section, info.key).value) : undefined;
    return env !== 'dev' && dev !== undefined ? [t(' · dev: '), ...valueParts(info, dev)] : [];
  };
  switch (o.kind) {
    case 'set':
      return withNote(parent ? [t(`Set on ${env} · `), ...referenceParts(env, fb.origin, info, fb.value)] : [t(`Set on ${env}`), ...devRef()]);
    case 'inherited': {
      const from = o.from;
      if (info.kind === 'number' || info.kind === 'select') return withNote([t(`Inherited from ${from}: `), ...valueParts(info, fb.value)]);
      if (info.kind === 'list' && info.spec.tags) return [t(`Inherited from ${from}. Add or remove a value to set the list for ${env}.`)];
      return withNote([t(`Inherited from ${from}`)]);
    }
    case 'env-default':
      return withNote([t(`${capitalize(env)} default`), ...devRef()]);
    case 'own':
    case 'default':
      return parent ? withNote([t('Default')]) : describe;
  }
}

/** The empty option of a select on an environment with a parent: "Same as dev: 5 min", "Stage default: 10 min". */
export function followOption(policies: PoliciesByEnv, schemas: PolicySchemas, env: Env, info: KeyInfo): string | null {
  if (chain(policies, env).length === 1) return null;
  const fb = fallback(policies, schemas, env, info.section, info.key);
  const value = valueText(info, fb.value);
  if (fb.origin.kind === 'inherited') return `Same as ${fb.origin.from}: ${value}`;
  if (fb.origin.kind === 'env-default') return `${capitalize(env)} default: ${value}`;
  return `Default: ${value}`;
}

/** What an empty number field shows: the value it follows. */
export function placeholderFor(policies: PoliciesByEnv, schemas: PolicySchemas, env: Env, info: KeyInfo): string {
  const v = fallback(policies, schemas, env, info.section, info.key).value;
  return typeof v === 'number' ? formatNumber(v / info.display.scale) : '';
}

/** Options of a select or of a group of checkboxes; a value the environment refuses is disabled with its reason. */
export interface OptionView {
  readonly value: string;
  readonly label: string;
  readonly disabled: boolean;
}

export function optionsFor(info: KeyInfo, env: Env): OptionView[] {
  const items = info.kind === 'list';
  const values = (items ? info.ks.items?.enum : info.ks.enum) ?? [];
  return values.map((v) => ({
    value: String(v),
    label: optionLabel(info.spec, info.ks, v, items),
    // `x-forbidden-env-values` may name an item of a list or the list holding just it.
    disabled: forbidden(info.ks, env, v) || (items && forbidden(info.ks, env, [v])),
  }));
}

/** Why a typed number can't be saved: not a number, not whole where the schema says integer, out of range. */
export function numberError(info: KeyInfo, text: string): string | null {
  const n = parseNumber(text);
  const unit = info.display.unit ? ` ${info.display.unit}` : '';
  const min = info.ks.minimum !== undefined ? info.ks.minimum / info.display.scale : null;
  const max = info.ks.maximum !== undefined ? info.ks.maximum / info.display.scale : null;
  const range =
    min !== null && max !== null ? ` from ${formatNumber(min)} to ${formatNumber(max)}${unit}` : min !== null ? ` of at least ${formatNumber(min)}${unit}` : max !== null ? ` up to ${formatNumber(max)}${unit}` : '';
  const whole = info.ks.type === 'integer' && info.display.scale === 1;
  const ask = `Enter ${whole ? 'a whole number' : 'a number'}${range}.`;
  if (Number.isNaN(n)) return ask;
  if (whole && !Number.isInteger(n)) return ask;
  const stored = n * info.display.scale;
  if (info.ks.minimum !== undefined && stored < info.ks.minimum) return ask;
  if (info.ks.maximum !== undefined && stored > info.ks.maximum) return ask;
  return null;
}

// ── Sections ────────────────────────────────────────────────────────────────────────────────────────────────────

/** The connections a section applies to on an environment, in the catalog's type order. */
export function sectionConnections(section: string, env: Env, connections: readonly ConnectionRow[]): ConnectionRow[] {
  const types = sectionSpec(section).types;
  return connections
    .filter((r) => r.env === env && types.includes(r.type))
    .sort((a, b) => typeRank(a.type) - typeRank(b.type) || a.name.localeCompare(b.name));
}

/** Every connection of an environment ("Applies to" in the toolbar), in the catalog's type order. */
export function envConnections(env: Env, connections: readonly ConnectionRow[]): ConnectionRow[] {
  return connections.filter((r) => r.env === env).sort((a, b) => typeRank(a.type) - typeRank(b.type) || a.name.localeCompare(b.name));
}

/** The rule an approval of this section is recorded under (`policy.PolicyEngine`: `<env>.<section>.write-approval`). */
export function approvalRule(env: Env, section: string): string {
  return `${env}.${section}.write-approval`;
}

/** The toolbar's "Values": "Base for stage", "3 stage defaults · 2 set on stage · 21 inherited from dev". */
export function valuesSummary(policies: PoliciesByEnv, schemas: PolicySchemas, env: Env): string {
  const n = originCounts(policies, schemas, env);
  const children = inheritors(policies, env);
  const parts: string[] = [];
  if (n.envDefault) parts.push(`${formatNumber(n.envDefault)} ${env} ${n.envDefault === 1 ? 'default' : 'defaults'}`);
  if (n.set) parts.push(`${formatNumber(n.set)} set on ${env}`);
  if (n.inherited && n.from) parts.push(`${formatNumber(n.inherited)} inherited from ${n.from}`);
  if (children.length) parts.push(`${parts.length ? 'base' : 'Base'} for ${joinWords(children)}`);
  if (!parts.length) parts.push(`Defaults of ${env}`);
  return parts.join(' · ');
}

// ── Changes in words ────────────────────────────────────────────────────────────────────────────────────────────

/** One change in a sentence: "approval timeout 10 to 15 min", "DDL allowed with approval unless Bypass", "masked fields to `a`". */
export function changePhrase(info: KeyInfo, from: JsonValue | undefined, to: JsonValue | undefined): string {
  const noun = info.spec.noun;
  switch (info.kind) {
    case 'boolean': {
      const w = info.spec.words;
      return w ? `${noun} ${to === true ? w.on : w.off}` : `${noun} ${to === true ? 'on' : 'off'}`;
    }
    case 'number': {
      if (typeof from === 'number' && typeof to === 'number') {
        if (info.ks['x-stricter'] === 'min-positive' && (from === 0 || to === 0)) {
          return `${noun} ${numberText(info, from)} to ${numberText(info, to)}`;
        }
        const unit = info.display.unit ? ` ${info.display.unit}` : '';
        return `${noun} ${formatNumber(from / info.display.scale)} to ${formatNumber(to / info.display.scale)}${unit}`;
      }
      return `${noun} to ${numberText(info, to)}`;
    }
    case 'select': {
      const unit = info.ks['x-unit'];
      if (unit && typeof from === 'number' && typeof to === 'number') return `${noun} ${formatNumber(from)} to ${formatNumber(to)} ${unit}`;
      return `${noun} ${valueText(info, from)} to ${valueText(info, to)}`;
    }
    case 'list':
      return `${noun} to ${valueText(info, to)}`;
  }
}

/** The status line of the Save panel: "Unsaved changes · approval timeout on prod, 10 to 15 min". */
export function unsavedNote(schemas: PolicySchemas, env: Env, list: readonly Change[]): string {
  if (!list.length) return 'Unsaved changes';
  if (list.length === 1) {
    const ch = list[0];
    const info = keyInfo(schemas, ch.section, ch.key);
    if (!info) return 'Unsaved changes';
    const phrase = changePhrase(info, ch.before, ch.after);
    const noun = info.spec.noun;
    return `Unsaved changes · ${noun} on ${env}, ${phrase.startsWith(noun) ? phrase.slice(noun.length).trim() : phrase}`;
  }
  const nouns = list.map((ch) => keyInfo(schemas, ch.section, ch.key)?.spec.noun ?? ch.key);
  return list.length <= 3 ? `Unsaved changes · ${joinWords(nouns)} on ${env}` : `Unsaved changes · ${plural(list.length, 'value')} on ${env}`;
}

export interface ToastText {
  readonly tone: 'success' | 'neutral' | 'warning' | 'danger';
  readonly title: string;
  readonly body: string;
}

/** After Save: "Prod policy saved · Approval timeout on prod: 15 min. Requests already waiting keep their timer." */
export function savedToast(policies: PoliciesByEnv, schemas: PolicySchemas, env: Env, list: readonly Change[]): ToastText {
  const title = `${capitalize(env)} policy saved`;
  let body: string;
  if (list.length === 1) {
    const ch = list[0];
    const info = keyInfo(schemas, ch.section, ch.key);
    body = info ? `${capitalize(info.spec.noun)} on ${env}: ${valueText(info, ch.after)}.` : `1 value changed on ${env}.`;
  } else {
    body = `${plural(list.length, 'value')} changed on ${env}. They apply from the next call.`;
  }
  if (list.some((ch) => ch.section === 'approvals' && ch.key === 'timeoutMinutes')) body += ' Requests already waiting keep their timer.';
  const children = inheritors(policies, env);
  if (children.length) body += ` ${capitalize(joinWords(children))} follows wherever it sets nothing itself.`;
  return { tone: 'success', title, body };
}

/** The Reset dialog: its title, what goes back where, and the button. */
export interface ResetText {
  readonly title: string;
  readonly description: string;
  readonly button: string;
  readonly toast: ToastText;
  /** The toolbar button: "Reset to dev", "Reset to prod defaults". */
  readonly trigger: string;
}

export function resetText(policies: PoliciesByEnv, schemas: PolicySchemas, env: Env, unsaved: readonly Change[]): ResetText {
  const p = policies[env];
  const parent = p?.inherits ?? null;
  const stored = p ? explicitKeys(p) : [];
  const phrases: string[] = [];
  for (const s of stored) {
    const info = keyInfo(schemas, s.section, s.key);
    if (!info || isLocked(info.ks)) continue;
    const fb = fallback(policies, schemas, env, s.section, s.key);
    phrases.push(changePhrase(info, s.value, fb.value));
  }
  const dest = parent ? parent : 'its defaults';
  const title = parent ? `Reset the ${env} policy to ${parent}?` : `Reset the ${env} policy to its defaults?`;
  const count = phrases.length;
  let description = count
    ? `The ${count === 1 ? 'value' : `${formatNumber(count)} values`} set on ${env} ${count === 1 ? 'goes' : 'go'} back to ${parent ? parent : `${env} defaults`}: ${phrases.join(', ')}.`
    : `Nothing is set on ${env}: it already follows ${dest}.`;
  if (unsaved.length) {
    const nouns = unsaved.map((ch) => keyInfo(schemas, ch.section, ch.key)?.spec.noun ?? ch.key);
    description += ` Your unsaved ${unsaved.length === 1 ? nouns[0] : 'changes'} ${unsaved.length === 1 ? 'is' : 'are'} discarded.`;
  }
  const children = inheritors(policies, env);
  if (children.length) description += ` ${capitalize(joinWords(children))} follows wherever it sets nothing itself.`;
  const toast: ToastText = parent
    ? { tone: 'neutral', title: `${capitalize(env)} policy reset`, body: count ? `${plural(count, 'value')} on ${env} now follow ${parent}.` : `${capitalize(env)} follows ${parent}.` }
    : { tone: 'neutral', title: `${capitalize(env)} policy reset`, body: `${capitalize(env)} follows its defaults again.` };
  return {
    title,
    description,
    button: `Reset ${env} policy`,
    toast,
    trigger: parent ? `Reset to ${parent}` : `Reset to ${env} defaults`,
  };
}

/** What a stored row says, for the person who reviews a policy changed outside GateRook. */
export function storedPhrases(schemas: PolicySchemas, p: EnvPolicy): string[] {
  return explicitKeys(p).map((s) => {
    const info = keyInfo(schemas, s.section, s.key);
    if (!info) return `${s.section}.${s.key}`;
    // A switch in its own words ("ssh_exec allowed", "DDL refused"), the rest as "row limit 500 rows".
    return info.kind === 'boolean' ? changePhrase(info, undefined, s.value) : `${info.spec.noun} ${valueText(info, s.value)}`;
  });
}

// ── Usage in the last minute ────────────────────────────────────────────────────────────────────────────────────

/** The busiest token or tool of an environment in the last minute (one audited call each). */
export interface Busiest {
  readonly name: string;
  /** The connection of a tool (rate limits count each tool on each connection). */
  readonly connection: string | null;
  readonly calls: number;
}

export interface MeterView {
  readonly value: number;
  readonly max: number;
  /** `--gw-meter`: 0..1. */
  readonly fraction: number;
  readonly level: 'ok' | 'warn' | 'critical';
  readonly valueText: string;
  readonly icon: string | null;
  readonly busiest: Busiest | null;
}

/** "14 of 120 calls in the last minute", amber from 75 %, red at the limit (gw-meter: the page picks the level). */
export function meter(limit: JsonValue | undefined, busiest: Busiest | null): MeterView | null {
  if (typeof limit !== 'number' || limit <= 0) return null;
  const value = busiest?.calls ?? 0;
  const fraction = Math.min(1, value / limit);
  const level = value >= limit ? 'critical' : fraction >= 0.75 ? 'warn' : 'ok';
  const judgement = level === 'critical' ? ', at the limit' : level === 'warn' ? ', near the limit' : '';
  return {
    value,
    max: limit,
    fraction: Math.round(fraction * 1000) / 1000,
    level,
    valueText: `${formatNumber(value)} of ${formatNumber(limit)} calls in the last minute${judgement}`,
    icon: level === 'critical' ? 'circle-alert' : level === 'warn' ? 'triangle-alert' : null,
    busiest,
  };
}

/** The busiest token and tool × connection among an environment's calls (`audit.call` rows or `activity/events`). */
export function busiestOf(calls: readonly { readonly token: string | null; readonly tool: string; readonly connection: string | null }[]): {
  token: Busiest | null;
  tool: Busiest | null;
} {
  const tokens = new Map<string, number>();
  const tools = new Map<string, { tool: string; connection: string | null; calls: number }>();
  for (const call of calls) {
    if (call.token) tokens.set(call.token, (tokens.get(call.token) ?? 0) + 1);
    const k = `${call.tool}\u0000${call.connection ?? ''}`;
    const e = tools.get(k) ?? { tool: call.tool, connection: call.connection, calls: 0 };
    e.calls++;
    tools.set(k, e);
  }
  let token: Busiest | null = null;
  for (const [name, n] of [...tokens].sort((a, b) => a[0].localeCompare(b[0]))) if (!token || n > token.calls) token = { name, connection: null, calls: n };
  let tool: Busiest | null = null;
  for (const e of [...tools.values()].sort((a, b) => a.tool.localeCompare(b.tool))) if (!tool || e.calls > tool.calls) tool = { name: e.tool, connection: e.connection, calls: e.calls };
  return { token, tool };
}

// ── Runbooks ────────────────────────────────────────────────────────────────────────────────────────────────────

export interface RunbookView {
  readonly id: string;
  readonly name: string;
  readonly connection: string;
  readonly description: string | null;
  readonly command: string | null;
  readonly params: readonly TextPart[];
  /** Read only: it runs at once in Read only mode; else every run waits for approval; null: the core didn't say. */
  readonly mode: 'read' | 'approval' | null;
  readonly status: string;
  readonly lastRunAt: string | null;
}

/** "`service` optional, one of `api`, `worker` · `lines` integer 1 to 500, default 100", or "None". */
export function paramParts(definition: JsonObject | null | undefined): TextPart[] {
  const params = Array.isArray(definition?.['params']) ? (definition!['params'] as JsonValue[]) : [];
  const out: TextPart[] = [];
  const text = (s: string): void => {
    const last = out.at(-1);
    if (last && !last.code) out[out.length - 1] = { text: last.text + s, code: false };
    else out.push({ text: s, code: false });
  };
  params.forEach((raw, i) => {
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return;
    const p = raw as JsonObject;
    if (i > 0) text(' · ');
    out.push({ text: String(p['name'] ?? ''), code: true });
    if (p['required'] !== true && p['type'] !== 'bool') text(' optional,');
    switch (p['type']) {
      case 'enum': {
        const values = Array.isArray(p['values']) ? p['values'].map(String) : [];
        text(' one of ');
        values.forEach((v, j) => {
          if (j > 0) text(', ');
          out.push({ text: v, code: true });
        });
        break;
      }
      case 'int': {
        const min = typeof p['min'] === 'number' ? p['min'] : null;
        const max = typeof p['max'] === 'number' ? p['max'] : null;
        text(' integer');
        if (min !== null && max !== null) text(` ${formatNumber(min)} to ${formatNumber(max)}`);
        else if (min !== null) text(` from ${formatNumber(min)}`);
        else if (max !== null) text(` up to ${formatNumber(max)}`);
        break;
      }
      case 'bool':
        text(' yes or no');
        break;
      default:
        text(' text');
    }
    if (p['default'] !== undefined && p['default'] !== null) text(`, default ${typeof p['default'] === 'number' ? formatNumber(p['default']) : String(p['default'])}`);
  });
  return out.length ? out : [{ text: 'None', code: false }];
}

/**
 * A runbook's mode: Read only when its definition marks it read only for exactly this command (ARCHITECTURE §3 SSH:
 * `readOnlyConfirmedFor` equals `command`), else Approval; the template's `natures` count compiled tools, which a
 * runbook isn't (`{"read": 0, "mutation": 0}`), so they decide only when they count something. Without either the
 * mode is unknown and the page doesn't guess it.
 */
export function runbookMode(natures: JsonValue | undefined, definition: JsonObject | null | undefined): 'read' | 'approval' | null {
  if (definition && typeof definition['command'] === 'string') {
    return definition['readOnly'] === true && definition['readOnlyConfirmedFor'] === definition['command'] ? 'read' : 'approval';
  }
  if (natures && typeof natures === 'object' && !Array.isArray(natures)) {
    const n = natures as JsonObject;
    if (typeof n['mutation'] === 'number' && n['mutation'] > 0) return 'approval';
    if (typeof n['read'] === 'number' && n['read'] > 0) return 'read';
  }
  return null;
}

// ── HTTP egress and the risky combination ──────────────────────────────────────────────────────────────────────

/** The hosts of an allowlist ("billing.stage.acme.dev"). */
export function allowlistHosts(urls: JsonValue | undefined): string[] {
  const out: string[] = [];
  for (const u of Array.isArray(urls) ? urls : []) {
    try {
      const host = new URL(String(u)).host;
      if (!out.includes(host)) out.push(host);
    } catch {
      /* the core refuses what doesn't parse */
    }
  }
  return out;
}

export interface RiskyEndpoint {
  readonly slug: string;
  readonly line: readonly TextPart[];
}

/**
 * Endpoints that mix private data, untrusted content and an external channel and use an HTTP connection of this
 * environment: through this allowlist they can send data out (the kit's compact risk callout in HTTP egress).
 */
export function riskyEndpoints(env: Env, connections: readonly ConnectionRow[], endpoints: readonly EndpointSummary[]): RiskyEndpoint[] {
  const used = new Set(connections.filter((r) => r.env === env && r.type === 'http').flatMap((r) => r.usedBy));
  return endpoints
    .filter((e) => used.has(e.slug) && e.risk.state === 'present')
    .map((e) => ({ slug: e.slug, line: compactLine(e.risk) }));
}
