import { JsonObject, JsonValue } from '../../../core/api/types';
import { SchemaNode, layoutRows } from '../../../schema-form/compile';

export interface FileSelection {
  readonly rootPath: readonly string[];
  readonly rootPointer: string;
  readonly path: readonly string[];
  readonly pointer: string;
}

const pointerPath = (value: JsonValue | undefined): string[] | null => typeof value === 'string' && /^\/(?:[^/]+)(?:\/[^/]+)*$/.test(value)
  ? value.slice(1).split('/').map((part) => part.replace(/~1/g, '/').replace(/~0/g, '~')) : null;

/** Connector-declared locations for the folder and exact relative file allowlist. */
export function fileSelection(schema: JsonObject): FileSelection | null {
  const value = schema['x-file-selection'];
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  const rootPath = pointerPath(value['rootPath']);
  const path = pointerPath(value['path']);
  return rootPath && path ? { rootPath, rootPointer: String(value['rootPath']), path, pointer: String(value['path']) } : null;
}

/** The remote picker owns these fields; empty schema sections must not leave duplicate headings. */
export function withoutFileFields(nodes: readonly SchemaNode[], selection: FileSelection | null): readonly SchemaNode[] {
  if (!selection) return nodes;
  return nodes.filter((node) => node.pointer !== selection.pointer && node.pointer !== selection.rootPointer).flatMap((node) => {
    if (!node.children.length) return [node];
    const children = withoutFileFields(node.children, selection);
    return children.length ? [{ ...node, children, rows: layoutRows(children) }] : [];
  });
}

export function relativeFile(root: string, absolute: string): string | null {
  const prefix = root === '/' ? '/' : root.replace(/\/$/, '') + '/';
  if (!root.startsWith('/') || !absolute.startsWith(prefix)) return null;
  const relative = absolute.slice(prefix.length);
  return relative && !relative.split('/').some((part) => !part || part === '.' || part === '..') ? relative : null;
}

/** Remove selection fields and empty ancestors so choosing the first root does not change connection identity. */
export function withoutSelectionValue(config: JsonObject, parts: readonly string[]): JsonObject {
  const [head, ...tail] = parts;
  const next = { ...config };
  const value = next[head];
  if (!tail.length) delete next[head];
  else if (value && typeof value === 'object' && !Array.isArray(value)) {
    const child = withoutSelectionValue(value, tail);
    if (Object.keys(child).length) next[head] = child;
    else delete next[head];
  }
  return next;
}
