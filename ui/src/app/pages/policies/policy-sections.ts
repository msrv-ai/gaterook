import { ConnectionRow } from '../../core/api/connections.api';
import { Env, JsonValue } from '../../core/api/types';
import { Hint, HintPart, KIT_KEYS, MUTATING_VERBS, c, chip, codeList, sectionSpec, t, verbLabel } from './policy-catalog';
import { PoliciesByEnv, PolicySchemas, chain, effectiveValue, fallback, inheritors, isLocked, keySchema, origin, sectionKeys } from './policy-model';
import { KeyInfo, approvalRule, capitalize, joinWords, keyInfo, numberText, valueParts } from './policies-view';

/**
 * The fixed parts of each section of design/pages/policies.html: its description, the rows that no setting opens
 * ("INSERT, UPDATE, DELETE → Approval, always"), what a section says while its environment has no connection of
 * its kind, and the read-only summary it shows then.
 */

export interface SectionCtx {
  readonly policies: PoliciesByEnv;
  readonly schemas: PolicySchemas;
  readonly env: Env;
  readonly section: string;
  /** The section's connections on this environment. */
  readonly connections: readonly ConnectionRow[];
  /** Every connection, for the "is a stage connection" line of an empty section. */
  readonly all: readonly ConnectionRow[];
}

function value(x: SectionCtx, key: string): JsonValue | undefined {
  return effectiveValue(x.policies[x.env], x.section, key);
}

function applies(x: SectionCtx): HintPart[] {
  const names = x.connections.map((r) => r.name);
  const out: HintPart[] = [t('Applies to '), ...codeList(names)];
  if (x.section === 'sql' && x.connections.length === 1 && x.connections[0].badges.includes('Read replica')) out.push(t(', a read replica'));
  out.push(t('.'));
  return out;
}

/** A base environment explains; one that inherits says that an empty field follows its parent. */
function base(x: SectionCtx): boolean {
  return chain(x.policies, x.env).length === 1;
}

/** The paragraph under a section's title. */
export function sectionDescription(x: SectionCtx): Hint {
  const parent = x.policies[x.env]?.inherits ?? null;
  const has = x.connections.length > 0;
  const lead = has ? [...applies(x), t(' ')] : [];
  switch (x.section) {
    case 'sql':
      return has
        ? [...lead, t(parent ? `An empty field follows the ${parent} value shown in it.` : 'The gateway parses every statement: SELECT is a read, anything else is a mutation.')]
        : [t(`Limits and allowlists for ${x.env} databases.`)];
    case 'ssh':
      if (x.connections.some((connection) => connection.type === 'ssh-logs')) {
        return [...lead, t('SSH log files use the authentication and output limits here. They only read selected files and do not run shell commands. SSH connections can run saved runbooks with typed parameters.')];
      }
      return has
        ? [...lead, t('Agents run runbooks: saved commands with typed parameters, so nothing is glued into a shell string.')]
        : [t(`Runbooks and limits for ${x.env} hosts.`)];
    case 'k8s': {
      if (has) return [...lead, t('Anything outside these lists is denied by policy.')];
      const verbs = value(x, 'verbs');
      const readOnly = Array.isArray(verbs) && !verbs.some((v) => MUTATING_VERBS.includes(String(v)));
      return [t(`Verbs, resources and namespaces for ${x.env} clusters.${readOnly ? ` ${capitalize(x.env)} starts read only.` : ''}`)];
    }
    case 'cloud': {
      const types = new Set(x.connections.map((r) => r.type));
      if (!has) return [t('Which AWS and GCP services, regions and resources agents may describe. The IAM role or service account still limits what the gateway itself can read.')];
      const tools = types.size === 1 ? [...types][0] : null;
      const role = tools === 'aws' ? 'The AssumeRole role' : tools === 'gcp' ? 'The service account' : 'The IAM role or service account';
      return [
        ...lead,
        ...(tools ? [t('What '), c(`${tools}_describe`), t(' and '), c(`${tools}_metrics`), t(' may read')] : [t('What the cloud tools may read')]),
        t(`; anything else is denied by policy. ${role} still limits what the gateway itself can read.`),
      ];
    }
    case 'http':
      return has
        ? [...lead, t('This stops SSRF: an agent steered into using the gateway, which sits inside your network, to reach internal addresses.')]
        : [t(`Where HTTP tools may send requests from ${x.env}. This stops SSRF: an agent steered into using the gateway, which sits inside your network, to reach internal addresses.`)];
    case 'logs':
      if (x.connections.some((connection) => connection.type === 'ssh-logs')) {
        return [...lead, t('Longest time range limits the requested event-time period for SSH log files. Their line limit comes from Limits and their authentication and output settings come from SSH. The other settings here apply to log-store connectors.')];
      }
      return has
        ? [...lead, t('How far back and how much '), c('logs_search'), t(' and '), c('logs_tail'), t(' read; Log lines per call in Limits caps every call.')]
        : [t('How far back and how much '), c('logs_search'), t(' and '), c('logs_tail'), t(` read on ${x.env}.`)];
    case 'masking':
      return inheritors(x.policies, x.env).length || (base(x) && x.env === 'dev')
        ? [t('What the gateway replaces with '), chip('‹masked›', 'secret'), t(` in every result, before the agent, Playground or the audit log sees it. Applies to every ${x.env} connection.`)]
        : [t('What the gateway replaces with '), chip('‹masked›', 'secret'), t(` in every result from ${x.env} connections.`)];
    case 'limits':
      return inheritors(x.policies, x.env).length
        ? [
            t(
              "Caps per minute and per call, so a looping agent can't flood a resource or its own context. These are the source: an endpoint cap or a tool's own limit can only be stricter, and the strictest value wins. The bars show use in the last minute.",
            ),
          ]
        : [t("Caps per minute and per call. An endpoint cap or a tool's own limit can only be stricter; the strictest value wins. The bars show use in the last minute.")];
    case 'approvals': {
      const lead2 = 'How long a request waits, who decides and where it is announced.';
      if (value(x, 'comment') !== 'required') return [t(lead2)];
      const longer = Number(value(x, 'timeoutMinutes')) > Number(effectiveValue(x.policies.dev, 'approvals', 'timeoutMinutes'));
      return [t(`${lead2} ${capitalize(x.env)} ${longer ? 'gives you more time and asks' : 'asks'} for a comment with every approval.`)];
    }
    default: {
      const title = x.schemas.sections[x.section]?.title ?? x.section;
      return has ? [...lead, t(`What the ${title} tools may do on ${x.env}.`)] : [t(`What the ${title} tools may do on ${x.env}.`)];
    }
  }
}

/** The approval row that no setting opens, for the sections whose tools can change something. */
export interface LockRow {
  readonly key: string;
  readonly caption: Hint;
}

export function approvalRow(x: SectionCtx): LockRow | null {
  const rule = (): HintPart[] => [t('unless Bypass · rule '), chip(approvalRule(x.env, x.section))];
  const has = x.connections.length > 0;
  switch (x.section) {
    case 'sql':
      return { key: value(x, 'denyDdl') === true ? 'INSERT, UPDATE, DELETE' : 'INSERT, UPDATE, DELETE, DDL', caption: has ? rule() : [t('unless Bypass')] };
    case 'ssh':
      return { key: 'Runbooks that change state', caption: has ? rule() : [t('unless Bypass')] };
    case 'k8s': {
      const verbs = value(x, 'verbs');
      const mutating = (Array.isArray(verbs) ? verbs.map(String) : []).filter((v) => MUTATING_VERBS.includes(v));
      if (!mutating.length) return { key: 'Mutating verbs', caption: [t('unless Bypass, if you allow one')] };
      return { key: mutating.map(verbLabel).join(', '), caption: has ? rule() : [t('unless Bypass')] };
    }
    case 'cloud':
      return { key: 'Changes to cloud resources', caption: has ? rule() : [t('unless Bypass, if a tool can make one')] };
    case 'http':
      return { key: 'POST, PUT, PATCH or DELETE', caption: rule() };
    case 'logs':
    case 'masking':
    case 'limits':
    case 'approvals':
      return null;
    default:
      return { key: 'Changes', caption: has ? rule() : [t('unless Bypass, if a tool can make one')] };
  }
}

/** "No SSH connection on stage", its text and what the Add connection link says to a screen reader. */
export interface EmptySection {
  readonly icon: string;
  readonly title: string;
  readonly text: Hint;
  readonly addLabel: string;
  readonly type: string;
}

const OBJECTS: Readonly<Record<string, string>> = {
  sql: 'database',
  ssh: 'SSH host',
  k8s: 'Kubernetes cluster',
  cloud: 'cloud account',
  http: 'HTTP API',
  logs: 'log source',
};

export function emptySection(x: SectionCtx): EmptySection {
  const spec = sectionSpec(x.section);
  const elsewhere = x.all.filter((r) => r.env !== x.env && spec.types.includes(r.type));
  const text: HintPart[] = [];
  if (elsewhere.length === 1) {
    const r = elsewhere[0];
    text.push(c(r.name), t(` is a ${r.env} connection, so its rules are in the ${r.env} list. `));
  }
  if (x.section === 'http') text.push(t(`HTTP tools can't reach anything from ${x.env} until you add a connection and its base URL.`));
  else if (x.section === 'cloud') text.push(t(`These rules apply as soon as you add a ${x.env} account.`));
  else if (x.section === 'ssh') text.push(t('These rules apply as soon as you add one. Runbooks are listed here per host.'));
  else text.push(t('These rules apply as soon as you add one.'));
  return {
    icon: spec.icon,
    title: `No ${spec.noun} connection on ${x.env}`,
    text,
    addLabel: `: ${OBJECTS[x.section] ?? `${spec.noun} connection`} on ${x.env}`,
    type: spec.types[0],
  };
}

/** One read-only row of an empty section: "Command timeout · 30 s · inherited from dev". */
export interface SummaryRow {
  readonly key: string;
  readonly value: Hint;
  /** Mono value (lists of literals, numbers set `gw-tabular`). */
  readonly mono: boolean;
  readonly tabular: boolean;
  readonly suffix: string;
}

function provenanceSuffix(x: SectionCtx, info: KeyInfo, v: JsonValue | undefined): string {
  const o = origin(x.policies, x.schemas, x.env, info.section, info.key);
  const inherit = keySchema(x.schemas, info.section, info.key)['x-inherit'] !== false;
  switch (o.kind) {
    case 'set':
      return `set on ${x.env}`;
    case 'inherited':
      return `inherited from ${o.from}`;
    case 'env-default': {
      let s = `${x.env} default`;
      if (x.env !== 'dev' && info.kind === 'list' && Array.isArray(v)) {
        const dev = effectiveValue(x.policies.dev, info.section, info.key);
        const extra = Array.isArray(dev) ? dev.filter((d) => !v.some((e) => String(e) === String(d))) : [];
        if (extra.length) s += `, dev also allows ${joinWords(extra.map((e) => (info.key === 'verbs' ? verbLabel(e) : String(e))))}`;
      }
      return inherit ? s : `${s}, per environment`;
    }
    case 'own':
      return info.kind === 'boolean' ? 'per environment, never inherited' : 'per environment';
    case 'default':
      return inherit ? '' : 'per environment';
  }
}

function summaryValue(info: KeyInfo, v: JsonValue | undefined): { parts: Hint; mono: boolean; tabular: boolean } {
  if (info.kind === 'list') {
    const items = Array.isArray(v) ? v : [];
    if (!items.length) return { parts: [t(info.spec.tags && ['namespaces', 'allowedServices'].includes(info.key) ? 'None yet' : 'None')], mono: false, tabular: false };
    const labels = items.map((i) => (info.key === 'verbs' ? verbLabel(i) : (info.spec.tags?.label?.(i) ?? String(i))));
    return { parts: [t(labels.join(', '))], mono: !info.ks.items?.enum || info.key === 'verbs', tabular: false };
  }
  if (info.kind === 'boolean') {
    if (info.key === 'allowExec') return { parts: [t(v === true ? 'Allowed' : 'Not allowed')], mono: false, tabular: false };
    return { parts: [t(v === true ? 'On' : 'Off')], mono: false, tabular: false };
  }
  if (info.kind === 'number') return { parts: [t(numberText(info, v))], mono: false, tabular: true };
  return { parts: valueParts(info, v), mono: false, tabular: false };
}

/**
 * The rules an empty section still holds, read only (kit: stage SSH, prod Kubernetes, prod HTTP egress): the kit's
 * settings of the section with where each value comes from. Cloud with nothing chosen collapses to one row.
 */
export function summaryRows(x: SectionCtx): SummaryRow[] {
  const keys = (KIT_KEYS[x.section] ?? sectionKeys(x.schemas, x.section)).filter((k) => !isLocked(keySchema(x.schemas, x.section, k)));
  const rows: SummaryRow[] = [];
  if (x.section === 'cloud' && keys.every((k) => !(value(x, k) as JsonValue[] | undefined)?.length)) {
    return [{ key: 'Services, regions, resources', value: [t('None yet')], mono: false, tabular: false, suffix: 'per environment' }];
  }
  for (const key of keys) {
    const info = keyInfo(x.schemas, x.section, key);
    if (!info) continue;
    const v = value(x, key) ?? fallback(x.policies, x.schemas, x.env, x.section, key).value;
    const s = summaryValue(info, v);
    rows.push({ key: info.spec.label + (info.spec.labelCode ? ` ${info.spec.labelCode}` : ''), value: s.parts, mono: s.mono, tabular: s.tabular, suffix: provenanceSuffix(x, info, v) });
    if (x.section === 'http' && key === 'allowedBaseUrls') {
      rows.push({ key: 'Cloud metadata', value: [t('Always blocked: '), c('169.254.169.254'), t(', '), c('fd00:ec2::254')], mono: false, tabular: false, suffix: '' });
      rows.push({ key: 'Private networks', value: [t('Blocked')], mono: false, tabular: false, suffix: 'unless allowed below, per environment' });
    }
  }
  if (x.section === 'ssh') {
    // The kit names the arbitrary-commands permission by what it allows.
    const i = rows.findIndex((r) => r.key.startsWith('Allow arbitrary commands'));
    if (i >= 0) rows[i] = { ...rows[i], key: 'Arbitrary commands' };
  }
  return rows;
}

/** The keys a section shows beyond the kit's layout ("More SQL settings"). */
export function extraKeys(schemas: PolicySchemas, section: string): string[] {
  const kit = KIT_KEYS[section];
  return sectionKeys(schemas, section).filter((k) => {
    const ks = keySchema(schemas, section, k);
    return !isLocked(ks) && (!kit || !kit.includes(k)) && keyInfo(schemas, section, k) !== null;
  });
}

/** Rows "Always on" for the locks of a section the kit doesn't lay out (a plugin's `const` keys). */
export function lockRows(schemas: PolicySchemas, section: string): { key: string; text: string }[] {
  return sectionKeys(schemas, section)
    .map((k) => ({ k, ks: keySchema(schemas, section, k) }))
    .filter((e) => e.ks.const !== undefined)
    .map((e) => ({ key: e.ks.title ?? e.k, text: e.ks.description ?? 'Always on.' }));
}
