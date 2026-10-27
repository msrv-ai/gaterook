import { JsonObject, JsonValue } from '../../../core/api/types';
import { SchemaNode, getAt, layoutRows, setAt } from '../../../schema-form/compile';

export type DatabaseName = string | number;

export interface DatabaseSelection {
  readonly path: readonly string[];
  readonly pointer: string;
  readonly legacyPath: readonly string[] | null;
  readonly legacyPointer: string | null;
}

const object = (value: JsonValue | undefined): value is JsonObject => !!value && typeof value === 'object' && !Array.isArray(value);
const path = (pointer: JsonValue | undefined): string[] | null =>
  typeof pointer === 'string' && /^\/(?:[^/]+)(?:\/[^/]+)*$/.test(pointer)
    ? pointer.slice(1).split('/').map((part) => part.replace(/~1/g, '/').replace(/~0/g, '~'))
    : null;

/** Connector-declared storage paths; database names stay typed (Redis uses integer indexes). */
export function databaseSelection(schema: JsonObject): DatabaseSelection | null {
  const spec = schema['x-database-selection'];
  if (!object(spec)) return null;
  const selected = path(spec['path']);
  if (!selected) return null;
  const legacy = path(spec['legacyPath']);
  return { path: selected, pointer: String(spec['path']), legacyPath: legacy,
    legacyPointer: legacy ? String(spec['legacyPath']) : null };
}

export function databaseNames(value: JsonValue): DatabaseName[] {
  return Array.isArray(value)
    ? [...new Set(value.filter((name): name is DatabaseName =>
      (typeof name === 'string' && name.length > 0) || (typeof name === 'number' && Number.isInteger(name) && name >= 0)))]
    : [];
}

export function initialDatabases(config: JsonObject | null, selection: DatabaseSelection): DatabaseName[] {
  const current = getAt(config, selection.path);
  if (Array.isArray(current)) return databaseNames(current);
  const legacy = selection.legacyPath ? getAt(config, selection.legacyPath) : null;
  return databaseNames(legacy === null ? [] : [legacy]);
}

/** Editing a legacy single-database connection starts with that database selected. */
export function databaseConfig(config: JsonObject | null, selection: DatabaseSelection | null): JsonObject | null {
  if (!config || !selection) return config;
  const names = initialDatabases(config, selection);
  return names.length ? setAt(config, selection.path, names) : config;
}

export function removeConfigPath(config: JsonObject, parts: readonly string[]): JsonObject {
  const [head, ...tail] = parts;
  const next = { ...config };
  if (!tail.length) delete next[head];
  else if (object(next[head])) next[head] = removeConfigPath(next[head], tail);
  return next;
}

/** Selection is presented after credentials, without duplicating the schema's legacy or array fields. */
export function withoutDatabaseFields(nodes: readonly SchemaNode[], selection: DatabaseSelection | null): readonly SchemaNode[] {
  if (!selection) return nodes;
  return nodes.filter((node) => node.pointer !== selection.pointer && node.pointer !== selection.legacyPointer).map((node) => {
    if (!node.children.length) return node;
    const children = withoutDatabaseFields(node.children, selection);
    return { ...node, children, rows: layoutRows(children) };
  });
}
