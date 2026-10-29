import { Env, JsonValue } from '../../core/api/types';
import { Display, KeySchema, PLAIN } from './policy-model';

/**
 * What the kit's Policies page (design/pages/policies.html) says about each section and setting: labels, hints,
 * placeholders, the names of the tag buttons, the words of the summaries. The values, defaults and limits come from
 * the core's schemas (`GET /api/policies/schema`); a section or key the kit doesn't know (a plugin's) is shown with
 * the schema's own title and description.
 */

/** A run of a hint: text, a literal set in mono, or a link inside the app. */
export interface HintPart {
  readonly text: string;
  readonly code?: boolean;
  /** A literal as the kit's `gw-code` chip (a rule id), `secret` for the ‹masked› stand-in. */
  readonly chip?: 'code' | 'secret';
  readonly link?: { readonly route: string; readonly query?: Readonly<Record<string, string>>; readonly fragment?: string };
}
export type Hint = readonly HintPart[];

export const t = (text: string): HintPart => ({ text });
export const c = (text: string): HintPart => ({ text, code: true });
export const chip = (text: string, kind: 'code' | 'secret' = 'code'): HintPart => ({ text, chip: kind });

/** Literals as "`a`, `b` and `c`". */
export function codeList(values: readonly string[], last = ' and '): HintPart[] {
  const out: HintPart[] = [];
  values.forEach((v, i) => {
    if (i > 0) out.push(t(i === values.length - 1 ? last : ', '));
    out.push(c(v));
  });
  return out;
}

/** What a hint knows: the environment, the value it shows and the connections the section applies to there. */
export interface FieldCtx {
  readonly env: Env;
  readonly value: JsonValue | undefined;
  readonly connections: readonly string[];
}

/** How a list's tags are named and checked (`gw-tag` A11y: the remove button names the value). */
export interface TagSpec {
  /** `aria-label` of the list: "Allowed verbs on dev". */
  readonly list: (env: Env) => string;
  /** The remove button: "Remove verb get on dev". */
  readonly remove: (value: string, env: Env) => string;
  /** The add button: "Add verb on dev". */
  readonly add: (env: Env) => string;
  readonly placeholder: (env: Env) => string;
  /** What an empty list means, in place of the tags ("None: every private address is blocked."). */
  readonly empty?: (ctx: FieldCtx) => Hint;
  /** Turns typed text into an item, or says what's wrong with it (on Add, at the field). */
  readonly parse?: (text: string, ks: KeySchema) => { readonly value: JsonValue } | { readonly error: string };
  /** The text of an item (objects of allowed private ranges). */
  readonly label?: (item: JsonValue) => string;
  /** An item that needs approval: an amber tag with `user-check` ("rollout restart, needs approval"). */
  readonly approval?: (item: JsonValue) => boolean;
  readonly inputType?: 'url' | 'text';
}

export interface FieldSpec {
  readonly label: string;
  /** A literal that ends the label, set in mono ("Allow arbitrary commands with `ssh_exec`"). */
  readonly labelCode?: string;
  /** The key in a sentence: "approval timeout", "row limit". */
  readonly noun: string;
  readonly display?: Display;
  /** The hint on the base environment (dev, and prod, which inherits nothing): what the setting does. */
  readonly describe?: (ctx: FieldCtx) => Hint;
  /** Said after where the value comes from on the other environments ("A DROP TABLE on prod is denied …"). */
  readonly note?: (ctx: FieldCtx) => Hint;
  /** Booleans in sentences: denyDdl true → "refused", false → "allowed with approval unless Bypass". */
  readonly words?: { readonly on: string; readonly off: string };
  /** Options of a select, or of a group of checkboxes, in the kit's words. */
  readonly options?: Readonly<Record<string, string>>;
  readonly tags?: TagSpec;
  /** The field is marked "Optional" (kit: allowed private ranges). */
  readonly optional?: boolean;
}

const has = (v: JsonValue | undefined, item: string): boolean => Array.isArray(v) && v.includes(item);

// ── Tags: what may be typed ─────────────────────────────────────────────────────────────────────────────────────

/** Cloud metadata addresses hand out credentials: no allowlist may name them (ARCHITECTURE §9 SSRF). */
const METADATA_HOSTS = /^(169\.254\.\d{1,3}\.\d{1,3}|\[?fd00:ec2::254\]?|\[?fd20:ce::254\]?|metadata(\.google\.internal)?|metadata\.goog)$/i;

function patternOf(ks: KeySchema): RegExp | null {
  try {
    return ks.items?.pattern ? new RegExp(ks.items.pattern) : null;
  } catch {
    return null;
  }
}

function plainItem(what: string, example: string) {
  return (text: string, ks: KeySchema): { value: JsonValue } | { error: string } => {
    const v = text.trim();
    const max = ks.items?.maxLength;
    if (max && v.length > max) return { error: `Use at most ${max} characters.` };
    const re = patternOf(ks);
    if (re && !re.test(v)) return { error: `${v} isn't ${what}. ${example}` };
    return { value: v };
  };
}

function baseUrl(text: string, ks: KeySchema): { value: JsonValue } | { error: string } {
  const v = text.trim();
  let url: URL;
  try {
    url = new URL(v);
  } catch {
    return { error: 'Enter a base URL that starts with https://, for example https://api.example.com.' };
  }
  if (METADATA_HOSTS.test(url.hostname)) {
    return { error: `${url.hostname.replace(/^\[|\]$/g, '')} is a cloud metadata address and is always blocked. Add the public base URL of the API instead.` };
  }
  const re = patternOf(ks);
  if ((url.protocol !== 'https:' && url.protocol !== 'http:') || (re && !re.test(v))) {
    return { error: 'Enter a base URL without a query or user name, for example https://api.example.com/v1.' };
  }
  return { value: v };
}

const IPV4 = /^(25[0-5]|2[0-4]\d|1?\d?\d)(\.(25[0-5]|2[0-4]\d|1?\d?\d)){3}$/;

function privateRange(text: string): { value: JsonValue } | { error: string } {
  const v = text.trim();
  const [address, bits, extra] = v.split('/');
  const v4 = IPV4.test(address ?? '');
  const v6 = !v4 && /^[0-9a-f:]+$/i.test(address ?? '') && (address ?? '').includes(':');
  const max = v4 ? 32 : 128;
  if (extra !== undefined || (!v4 && !v6) || (bits !== undefined && !(/^\d{1,3}$/.test(bits) && Number(bits) <= max))) {
    return { error: 'Enter an address or a CIDR range, for example 10.20.0.0/16.' };
  }
  if (v4 && address!.startsWith('169.254.')) return { error: `${v} holds the cloud metadata address and is always blocked.` };
  return { value: { cidr: v } };
}

const K8S_VERBS: Readonly<Record<string, string>> = {
  get: 'get',
  list: 'list',
  describe: 'describe',
  logs: 'logs',
  'rollout-restart': 'rollout restart',
  apply: 'apply',
};

/** The verbs of the Kubernetes section as the kit writes them: `rollout-restart` → "rollout restart". */
export function verbLabel(v: JsonValue): string {
  return typeof v === 'string' ? (K8S_VERBS[v] ?? v) : String(v);
}

export const MUTATING_VERBS: readonly string[] = ['rollout-restart', 'apply'];

function k8sVerb(text: string, ks: KeySchema): { value: JsonValue } | { error: string } {
  const v = text.trim().toLowerCase().replace(/\s+/g, '-');
  const allowed = (ks.items?.enum ?? Object.keys(K8S_VERBS)).map(String);
  if (allowed.includes(v)) return { value: v };
  return { error: `${text.trim()} isn't a verb agents can be allowed. Use ${allowed.map(verbLabel).join(', ')}.` };
}

// ── Sections ────────────────────────────────────────────────────────────────────────────────────────────────────

export interface SectionSpec {
  /** Connector types whose connections follow this section ("Applies to"). */
  readonly types: readonly string[];
  /** The icon of its "No … connection" state. */
  readonly icon: string;
  /** "No SSH connection on stage". */
  readonly noun: string;
}

/** Connector sections in the kit's order, then the ones the kit doesn't have (Logs), then any plugin's. */
export const CONNECTOR_SECTIONS: Readonly<Record<string, SectionSpec>> = {
  sql: { types: ['postgres', 'mysql', 'oracle'], icon: 'database', noun: 'SQL' },
  ssh: { types: ['ssh', 'ssh-logs'], icon: 'terminal', noun: 'SSH' },
  k8s: { types: ['kubernetes'], icon: 'ship-wheel', noun: 'Kubernetes' },
  cloud: { types: ['aws', 'gcp'], icon: 'cloud', noun: 'cloud' },
  http: { types: ['http'], icon: 'globe', noun: 'HTTP' },
  logs: { types: ['gcp-logging', 'cloudwatch', 'loki', 'logs', 'ssh-logs'], icon: 'scroll-text', noun: 'logs' },
};
export const CORE_SECTIONS: readonly string[] = ['masking', 'limits', 'approvals'];

/** The connector type order of the catalog (Databases, Remote access, Logs, Kubernetes, Cloud, HTTP and MCP). */
const TYPE_ORDER = ['postgres', 'mysql', 'oracle', 'mongodb', 'redis', 'opensearch', 'ssh', 'ssh-logs', 'gcp-logging', 'cloudwatch', 'loki', 'logs', 'kubernetes', 'gcp', 'aws', 'http', 'mcp-server'];

export function typeRank(type: string): number {
  const i = TYPE_ORDER.indexOf(type);
  return i < 0 ? TYPE_ORDER.length : i;
}

/** A section the kit doesn't know: its own type (a plugin's `x-policy-section` defaults to its manifest id). */
export function sectionSpec(section: string): SectionSpec {
  return CONNECTOR_SECTIONS[section] ?? { types: [section], icon: 'puzzle', noun: section };
}

/** The sections in the order of the page: the kit's connector sections, others, Logs, then the core's. */
export function sectionOrder(names: readonly string[]): string[] {
  const kit = ['sql', 'ssh', 'k8s', 'cloud', 'http'].filter((s) => names.includes(s));
  const others = names.filter((s) => !kit.includes(s) && s !== 'logs' && !CORE_SECTIONS.includes(s)).sort();
  const logs = names.includes('logs') ? ['logs'] : [];
  return [...kit, ...others, ...logs, ...CORE_SECTIONS.filter((s) => names.includes(s))];
}

/** The settings each kit section lays out itself; the rest of a section's keys are "More … settings". */
export const KIT_KEYS: Readonly<Record<string, readonly string[]>> = {
  sql: ['statementTimeoutMs', 'rowLimit', 'allowlist', 'denyDdl'],
  ssh: ['allowExec', 'commandTimeoutSeconds', 'maxOutputKb'],
  k8s: ['verbs', 'resources', 'namespaces'],
  cloud: ['allowedServices', 'regions', 'resources'],
  http: ['allowedBaseUrls', 'allowedPrivateRanges'],
  masking: ['pii', 'fields'],
  limits: ['rateLimitPerToken', 'rateLimitPerTool', 'maxResponseKb', 'toolTimeoutSeconds', 'logLines'],
  approvals: ['timeoutMinutes', 'comment', 'announceIn'],
};

const SECONDS: Display = { unit: 's', scale: 1000 };

/** Per `"<section>.<key>"`: the kit's words for it. */
export const FIELDS: Readonly<Record<string, FieldSpec>> = {
  'sql.statementTimeoutMs': {
    label: 'Statement timeout',
    noun: 'statement timeout',
    display: SECONDS,
    describe: () => [t('The database cancels slower statements.')],
  },
  'sql.rowLimit': {
    label: 'Row limit',
    noun: 'row limit',
    describe: () => [t('Longer results are cut and marked truncated for the agent.')],
  },
  'sql.allowlist': {
    label: 'Schema and table allowlist',
    noun: 'table allowlist',
    describe: () => [t('Agents see and query only these. '), c('schema.*'), t(' allows a whole schema.')],
    note: (x) => (Array.isArray(x.value) && x.value.length && x.connections.length ? [t(`Only these are readable on `), ...codeList(x.connections), t('.')] : []),
    tags: {
      list: (env) => `Allowed schemas and tables on ${env}`,
      remove: (v, env) => `Remove ${v} from the ${env} allowlist`,
      add: (env) => `Add to the ${env} allowlist`,
      placeholder: (env) => (env === 'dev' ? 'Add a schema or table, for example billing.invoices' : env === 'prod' ? 'Add a table, for example public.refunds' : 'Add a schema or table'),
      empty: () => [t('None: agents see every table the database user can read.')],
      parse: plainItem('a schema or a table', 'Use schema.table or schema.*.'),
    },
  },
  'sql.denyDdl': {
    label: 'Refuse DDL: CREATE, ALTER, DROP, TRUNCATE',
    noun: 'DDL',
    words: { on: 'refused', off: 'allowed with approval unless Bypass' },
    describe: (x) =>
      x.value === true
        ? [t(`On ${x.env}: a DROP TABLE is denied before anyone is asked.`)]
        : [t(`Off on ${x.env}: DDL waits for approval unless its tool uses Bypass. When on, it is denied before anyone is asked.`)],
    note: (x) => (x.value === true ? [t(`A DROP TABLE on ${x.env} is denied before anyone is asked.`)] : [t('DDL waits for approval unless its tool uses Bypass.')]),
  },
  'sql.deniedFunctions': {
    label: 'More denied functions',
    noun: 'denied functions',
    tags: {
      list: (env) => `Denied functions on ${env}`,
      remove: (v, env) => `Remove function ${v} on ${env}`,
      add: (env) => `Add denied function on ${env}`,
      placeholder: () => 'Add a function, for example pg_sleep',
      empty: () => [t('None beyond the built-in denylist.')],
      parse: plainItem('a function name', ''),
    },
  },
  'ssh.allowExec': {
    label: 'Allow arbitrary commands with',
    labelCode: 'ssh_exec',
    noun: 'ssh_exec',
    words: { on: 'allowed', off: 'not allowed' },
    describe: (x) =>
      x.env === 'prod'
        ? [t('Set on prod only, never inherited; off by default on prod. Allowing it asks you to confirm. Commands need approval unless ssh_exec uses Bypass.')]
        : [t('A separate permission, off by default and never inherited. Commands need approval unless ssh_exec uses Bypass.')],
  },
  'ssh.commandTimeoutSeconds': { label: 'Command timeout', noun: 'command timeout', describe: () => [t('The session is closed after this.')] },
  'ssh.maxOutputKb': { label: 'Max output', noun: 'max output', describe: () => [t('Longer output is cut from the start.')] },
  'ssh.maxStderrKb': { label: 'Max stderr', noun: 'max stderr', describe: () => [t('Longer error output is cut from the start.')] },
  'ssh.maxConcurrentCalls': { label: 'Parallel calls per connection', noun: 'parallel calls', describe: () => [t('More calls on one host wait for a free session.')] },
  'ssh.allowedCredentialMethods': {
    label: 'Sign-in methods',
    noun: 'sign-in methods',
    options: { vaultSshCa: 'Certificate from Vault SSH CA', privateKey: 'Private key', password: 'Password' },
    describe: () => [t('A connection that signs in another way is refused on this environment.')],
  },
  'k8s.verbs': {
    label: 'Allowed verbs',
    noun: 'allowed verbs',
    describe: () => [c('rollout restart'), t(' and '), c('apply'), t(' change the cluster and need approval unless their tool uses Bypass. '), c('delete'), t(', '), c('scale'), t(' and '), c('exec'), t(' are denied.')],
    tags: {
      list: (env) => `Allowed verbs on ${env}`,
      remove: (v, env) => `Remove verb ${v} on ${env}`,
      add: (env) => `Add verb on ${env}`,
      placeholder: () => 'Add a verb, for example describe',
      empty: () => [t('None: agents can call nothing on the cluster.')],
      parse: k8sVerb,
      label: verbLabel,
      approval: (v) => typeof v === 'string' && MUTATING_VERBS.includes(v),
    },
  },
  'k8s.resources': {
    label: 'Allowed resources',
    noun: 'allowed resources',
    describe: (x) =>
      has(x.value, 'secrets')
        ? [c('secrets'), t(' is on the list: agents can read Kubernetes Secrets, with their values masked.')]
        : [c('secrets'), t(" is not on the list, so agents can't read Kubernetes Secrets.")],
    tags: {
      list: (env) => `Allowed resources on ${env}`,
      remove: (v, env) => `Remove resource ${v} on ${env}`,
      add: (env) => `Add resource on ${env}`,
      placeholder: () => 'Add a resource, for example cronjobs',
      empty: () => [t('None: agents can read no resource.')],
      parse: plainItem('a resource name', 'Use the plural RBAC name, for example cronjobs or certificates.cert-manager.io.'),
    },
  },
  'k8s.namespaces': {
    label: 'Namespaces',
    noun: 'namespaces',
    describe: () => [t('Per environment, never inherited. Agents see nothing outside these namespaces.')],
    tags: {
      list: (env) => `Namespaces on ${env}`,
      remove: (v) => `Remove namespace ${v}`,
      add: (env) => `Add namespace on ${env}`,
      placeholder: () => 'Add a namespace',
      empty: () => [t("None: agents see only the namespace of each connection's context.")],
      parse: plainItem('a namespace', 'Use lowercase letters, digits and dashes.'),
    },
  },
  'k8s.denyPrivileged': {
    label: 'Deny privileged pods in',
    labelCode: 'k8s_apply',
    noun: 'privileged pods',
    words: { on: 'denied', off: 'allowed with approval unless Bypass' },
    describe: () => [t('Privileged containers, host network and host paths in a manifest are denied before anyone is asked.')],
  },
  'k8s.applyMaxObjects': { label: 'Objects per apply', noun: 'objects per apply', describe: () => [t('A larger manifest is denied.')] },
  'k8s.logsTailMax': { label: 'Log lines per k8s_logs call', noun: 'log lines per k8s_logs call', describe: () => [t('A larger tail is denied.')] },
  'k8s.listMax': { label: 'Objects per list', noun: 'objects per list', describe: () => [t('A larger page is denied.')] },
  'cloud.allowedServices': {
    label: 'Allowed services',
    noun: 'allowed services',
    describe: (x) =>
      ['secretsmanager', 'iam', 'kms'].some((s) => has(x.value, s))
        ? [t('Per environment, never inherited.')]
        : [t('Per environment, never inherited. '), c('secretsmanager'), t(', '), c('iam'), t(' and '), c('kms'), t(" are not on the list, so agents can't read secrets, roles or keys.")],
    tags: {
      list: (env) => `Allowed services on ${env}`,
      remove: (v, env) => `Remove service ${v} on ${env}`,
      add: (env) => `Add service on ${env}`,
      placeholder: () => 'Add a service, for example s3',
      empty: () => [t('None: agents can describe nothing.')],
      parse: plainItem('a service name', 'Use the short name, for example ec2 or sqladmin.'),
    },
  },
  'cloud.regions': {
    label: 'Regions',
    noun: 'regions',
    describe: () => [t('Per environment, never inherited.')],
    tags: {
      list: (env) => `Regions on ${env}`,
      remove: (v, env) => `Remove region ${v} on ${env}`,
      add: (env) => `Add region on ${env}`,
      placeholder: () => 'Add a region, for example eu-west-1',
      empty: () => [t('None: every region.')],
      parse: plainItem('a region', ''),
    },
  },
  'cloud.resources': {
    label: 'Resources',
    noun: 'resource patterns',
    describe: () => [t('ARN patterns; '), c('*'), t(' matches any text. Agents see nothing outside them.')],
    tags: {
      list: (env) => `Resources on ${env}`,
      remove: (v, env) => `Remove resource pattern ${v} on ${env}`,
      add: (env) => `Add resource pattern on ${env}`,
      placeholder: () => 'Add an ARN pattern',
      empty: () => [t('None: every resource of the allowed services.')],
      parse: plainItem('a resource pattern', ''),
    },
  },
  'cloud.maxRangeDays': { label: 'Longest time range', noun: 'longest time range', describe: () => [t('Metrics further back are denied.')] },
  'cloud.maxPoints': { label: 'Data points per call', noun: 'data points per call', describe: () => [t('Longer series are cut and marked truncated.')] },
  'cloud.maxItems': { label: 'Items per call', noun: 'items per call', describe: () => [t('Longer lists are cut and marked truncated.')] },
  'http.allowedBaseUrls': {
    label: 'Allowed base URLs',
    noun: 'allowed base URLs',
    describe: () => [t('Per environment, never inherited. A request must start with one of these.')],
    tags: {
      list: (env) => `Allowed base URLs on ${env}`,
      remove: (v, env) => `Remove ${v} from ${env}`,
      add: (env) => `Add base URL on ${env}`,
      placeholder: () => 'https://api.example.com',
      empty: (x) => [t(`None on ${x.env}: HTTP tools can't send a request anywhere until you add its base URL.`)],
      parse: baseUrl,
      inputType: 'url',
    },
  },
  'http.allowedPrivateRanges': {
    label: 'Allowed private ranges',
    noun: 'allowed private ranges',
    optional: true,
    describe: () => [t('Per environment, never inherited. Add a range only for an internal API you trust.')],
    tags: {
      list: (env) => `Allowed private ranges on ${env}`,
      remove: (v, env) => `Remove private range ${v} on ${env}`,
      add: (env) => `Add private range on ${env}`,
      placeholder: (env) => (env === 'stage' ? '10.30.0.0/16' : '10.20.0.0/16'),
      empty: () => [t('None: every private address is blocked.')],
      parse: privateRange,
      label: (item) => {
        if (item && typeof item === 'object' && !Array.isArray(item)) {
          const conns = Array.isArray(item['connections']) ? item['connections'].map(String) : [];
          return `${String(item['cidr'] ?? '')}${conns.length ? ` · ${conns.join(', ')}` : ''}`;
        }
        return String(item);
      },
    },
  },
  'http.maxResponseKb': { label: 'Max response size', noun: 'max response size', describe: () => [t('Bigger responses are cut and marked truncated.')] },
  'http.toolTimeoutSeconds': { label: 'Request timeout', noun: 'request timeout', describe: () => [t('The request is cancelled after this.')] },
  'http.maxRequestKb': { label: 'Max request body', noun: 'max request body', describe: () => [t('A bigger body is denied before it is sent.')] },
  'logs.maxRangeHours': { label: 'Longest time range', noun: 'longest time range', describe: () => [t('A search further back is cut to this range.')] },
  'logs.defaultLimit': { label: 'Entries per call by default', noun: 'entries per call', describe: () => [t('When the agent asks for no limit. Log lines per call in Limits caps every call.')] },
  'logs.maxTailWaitSeconds': { label: 'Longest tail wait', noun: 'tail wait', describe: () => [t('How long one call waits for new lines.')] },
  'masking.pii': {
    label: 'Personal data, found by pattern',
    noun: 'personal data masking',
    options: { email: 'Email addresses', phone: 'Phone numbers', card: 'Card numbers', iban: 'IBANs' },
    describe: () => [t('Checked in every value of every column and field, whatever its name.')],
  },
  'masking.fields': {
    label: 'Always mask these fields',
    noun: 'masked fields',
    describe: () => [c('table.column'), t(' for SQL, '), c('collection.field'), t(' for MongoDB; '), c('*'), t(' matches any table. Masked even when the value looks harmless.')],
    tags: {
      list: (env) => `Masked fields on ${env}`,
      remove: (v, env) => `Remove ${v} from masked fields on ${env}`,
      add: (env) => `Add masked field on ${env}`,
      placeholder: (env) => (env === 'dev' ? 'Add a field, for example customers.birth_date' : 'Add a field'),
      empty: () => [t('None beyond secrets and personal data.')],
      parse: plainItem('a field', ''),
    },
  },
  'masking.entropy': {
    label: 'Strings that look random',
    noun: 'random-looking strings',
    words: { on: 'masked', off: 'shown' },
    describe: () => [t('Long random-looking strings are masked as unknown secrets.')],
  },
  'limits.rateLimitPerToken': { label: 'Rate limit per token', noun: 'rate limit per token', describe: (x) => [t(`Across all ${x.env} connections.`)] },
  'limits.rateLimitPerTool': { label: 'Rate limit per tool', noun: 'rate limit per tool', describe: () => [t('Each tool on each connection.')] },
  'limits.maxResponseKb': { label: 'Max response size', noun: 'max response', describe: () => [t('Bigger results are cut and marked truncated.')] },
  'limits.toolTimeoutSeconds': { label: 'Tool timeout', noun: 'tool timeout', describe: () => [t('Log tools, Kubernetes, NoSQL and HTTP. SQL statements and SSH commands have separate timeouts above.')] },
  'limits.logLines': { label: 'Log lines per call', noun: 'log lines per call', describe: () => [t('Applies to log search and tail tools.')] },
  'approvals.timeoutMinutes': { label: 'Approval timeout', noun: 'approval timeout', describe: () => [t('The countdown approvers see on a request.')] },
  'approvals.comment': {
    label: 'Comment on a decision',
    noun: 'comment',
    describe: () => [
      t('Sent to the agent and kept in the audit log. Required means Approve and run waits for a comment, and Slack and Telegram offer only Deny and Open in app.'),
    ],
    // Prod never gets Approve in a channel, comment or not (notifications.Eligibility, kit after KIT): the note says so
    // there whatever the value; elsewhere it explains what "required" does to the channels.
    note: (x) =>
      x.env === 'prod'
        ? [
            t(
              `${x.value === 'required' ? 'Approve and run waits for a comment. ' : ''}Slack and Telegram never offer Approve on prod, with or without a comment: they show Deny and Open in app. `,
            ),
            { text: 'Require a comment on prod', link: { route: '/settings/approval-rules' } },
            t(' in Settings'),
          ]
        : x.value === 'required'
          ? [t('Approve and run waits for a comment; Slack and Telegram show Deny and Open in app.')]
          : [],
  },
  'approvals.announceIn': {
    label: 'Announce requests in',
    noun: 'announcements',
    options: { desktop: 'Desktop notification', slack: 'Slack', telegram: 'Telegram' },
    describe: () => [t("Channels are connected in Settings. The agent's note is never sent to a channel.")],
  },
};

/** A key without kit words: the schema's title and description, with its unit. */
export function fieldSpec(section: string, key: string, ks: KeySchema): FieldSpec {
  const known = FIELDS[`${section}.${key}`];
  if (known) return known;
  const label = ks.title ?? key;
  return { label, noun: label.charAt(0).toLowerCase() + label.slice(1), describe: () => (ks.description ? [t(ks.description)] : []) };
}

/** How a key is typed and shown: the kit's override, else the schema's `x-unit`. */
export function displayOf(section: string, key: string, ks: KeySchema): Display {
  return FIELDS[`${section}.${key}`]?.display ?? (ks['x-unit'] ? { unit: ks['x-unit'], scale: 1 } : PLAIN);
}

/** Option words: the kit's, else the schema's `x-enumTitles`, else the value. */
export function optionLabel(spec: FieldSpec, ks: KeySchema, value: JsonValue, items = false): string {
  const fromKit = spec.options?.[String(value)];
  if (fromKit) return fromKit;
  const values = items ? ks.items?.enum : ks.enum;
  const i = values?.findIndex((v) => v === value) ?? -1;
  const titles = ks['x-enumTitles'];
  return i >= 0 && titles?.[i] ? titles[i] : String(value);
}
