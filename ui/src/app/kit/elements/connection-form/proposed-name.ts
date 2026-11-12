import { JsonValue } from '../../../core/api/types';
import { SchemaNode, getAt, isVisible } from '../../../schema-form/compile';
import { referenceKind } from '../../../schema-form/secret-sources';

/** A name the form proposes for a new connection, and what it came from ("the database file"). */
export interface ProposedName {
  readonly name: string;
  readonly from: string;
}

/** Fields that name the resource itself, most specific first (a single database, not its server). */
const RESOURCE_KEYS = ['database', 'databaseName', 'dbName', 'keyspace', 'index', 'indexName', 'bucket', 'instance',
  'instanceId', 'cluster', 'clusterName', 'workspace', 'project', 'projectId', 'account', 'profile'];
/** Names that say nothing about the resource. */
const MEANINGLESS = new Set(['default', 'default-database']);
/** Fields that say where the resource is: a host, or a URL whose host is used. */
const ADDRESS_KEYS = ['host', 'hostname', 'address', 'endpoint', 'url', 'uri', 'baseUrl', 'server'];
/** First host labels that name a service's door rather than the resource behind it. */
const GENERIC_LABELS = new Set(['www', 'api', 'app', 'mcp', 'rest', 'console', 'portal']);
/** The longest name proposed. */
const MAX_LENGTH = 63;

/**
 * The name a new connection gets until the person types one, from what it points to: the one database chosen, a
 * database file (its name without the extension), a kubeconfig context (the cluster's own name), a project, an
 * instance or a cluster, else the host (`postgres-local` for this computer). Values read from a secret store, values
 * still at their default, IDs that read as numbers or UUIDs, and names already taken (`-2`, `-3` then) are never
 * proposed as they are.
 */
export function proposeName(nodes: readonly SchemaNode[], model: JsonValue, type: string,
  databases: readonly (string | number)[], taken: readonly string[]): ProposedName | null {
  const shown = nodes.filter((n) => n.kind !== 'object' && n.kind !== 'secret' && isVisible(n, model));
  // A value read from a secret store at call time (vault://…) or a placeholder isn't known here.
  const text = (n: SchemaNode): string => {
    const v = getAt(model, n.path);
    return typeof v === 'string' && !referenceKind(v) && !v.includes('{') ? v.trim() : '';
  };
  const byKey = (keys: readonly string[]) => keys.flatMap((k) => shown.filter((n) => n.key === k && text(n)));
  const candidates: { readonly value: string | null; readonly from: string }[] = [];
  if (databases.length === 1 && typeof databases[0] === 'string') {
    candidates.push({ value: databases[0], from: 'the database' });
  }
  // A database file (its picker says so: sqlite-database, duckdb-database); a key or a kubeconfig names nothing.
  const databaseFile = (node: SchemaNode) => String(node.schema['x-file-picker'] ?? '').endsWith('-database');
  for (const n of shown.filter((node) => databaseFile(node) && text(node))) {
    candidates.push({ value: fileStem(text(n)), from: `the ${lowerFirst(n.title)}` });
  }
  for (const n of shown.filter((node) => node.key === 'context' && text(node))) {
    candidates.push({ value: contextName(text(n)), from: 'the context' });
  }
  // A resource field still at its default (Redshift's dev, Neo4j's neo4j) names the product, not this resource.
  for (const n of byKey(RESOURCE_KEYS).filter((node) => text(node) !== node.schema['default'])) {
    candidates.push({ value: text(n), from: `the ${lowerFirst(n.title)}` });
  }
  for (const n of byKey(ADDRESS_KEYS)) {
    const host = hostOf(text(n));
    if (host === null) continue;
    candidates.push(isLocal(host)
      ? { value: `${type}-local`, from: 'this computer' }
      : { value: hostLabel(host), from: `the ${lowerFirst(n.title)}` });
  }
  for (const c of candidates) {
    if (!c.value) continue;
    const slug = toSlug(c.value);
    if (slug && !MEANINGLESS.has(slug)) return { name: unique(slug, taken), from: c.from };
  }
  return null;
}

/** "warehouse" for ~/data/warehouse.duckdb; "data" for C:\db\data.mdb. */
function fileStem(path: string): string {
  const base = path.split(/[\\/]/).filter((s) => s.length > 0).pop() ?? '';
  const dot = base.lastIndexOf('.');
  return dot > 0 ? base.slice(0, dot) : base;
}

/** A context's cluster: gke_project_zone_cluster → cluster, arn:…:cluster/prod → prod, kind-dev as it is. */
function contextName(context: string): string {
  const afterSlash = context.split('/').pop() ?? context;
  return /^gke_/.test(afterSlash) ? afterSlash.split('_').pop() ?? afterSlash : afterSlash;
}

/** The host of a URL or a host[:port], or null for what isn't one. */
function hostOf(value: string): string | null {
  const withScheme = /^[a-z][a-z0-9+.-]*:\/\//i.test(value) ? value : `x://${value}`;
  try {
    const host = new URL(withScheme).hostname.replace(/^\[|\]$/g, '');
    return host || null;
  } catch {
    return null;
  }
}

function isLocal(host: string): boolean {
  return /^(localhost|127(\.\d+){3}|::1|0\.0\.0\.0)$/i.test(host) || host.endsWith('.localhost');
}

/** The label of a host name that names the resource: db1 of db1.internal, acme of api.acme.example. */
function hostLabel(host: string): string | null {
  if (/^[\d.]+$/.test(host) || host.includes(':')) return null; // an IP address names nothing
  const labels = host.split('.');
  return labels.find((label) => !GENERIC_LABELS.has(label.toLowerCase())) ?? labels[0] ?? null;
}

/** A slug, or '' for a value that doesn't make one a person would recognise (a UUID, a number). */
function toSlug(value: string): string {
  if (/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value)) return '';
  const slug = value
    .toLowerCase()
    .normalize('NFKD')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^[^a-z]+/, '')
    .slice(0, MAX_LENGTH)
    .replace(/-+$/g, '');
  return /^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$/.test(slug) ? slug : '';
}

function unique(slug: string, taken: readonly string[]): string {
  if (!taken.includes(slug)) return slug;
  for (let i = 2; ; i++) {
    const suffix = `-${i}`;
    const name = `${slug.slice(0, MAX_LENGTH - suffix.length).replace(/-+$/g, '')}${suffix}`;
    if (!taken.includes(name)) return name;
  }
}

function lowerFirst(text: string): string {
  return /^[A-Z][a-z]/.test(text) ? text.charAt(0).toLowerCase() + text.slice(1) : text;
}
