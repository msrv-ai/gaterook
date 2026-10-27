import { JsonObject, JsonValue } from '../../core/api/types';

/**
 * The SQL query template editor's model (kit Connectors › Templates › "Edit template orders_by_status"), as pure
 * functions: the `:name` placeholders of the SQL become the Parameters table, the table and the fields become the
 * definition the core compiles (`sql-query`, connectors/sql `sql-query.definition.schema.json`), and "What the agent
 * sees" is the input schema the core will publish (the same rules as `TemplateParams.inputSchema`).
 *
 * The parse here only drives the preview. The core parses the SQL again when the template is saved and decides
 * what is read only; what it says wins (its errors and warnings are shown with the form).
 */

export type ParamType = 'string' | 'enum' | 'integer' | 'number' | 'boolean' | 'timestamp' | 'date';

export const PARAM_TYPES: readonly { readonly value: ParamType; readonly label: string }[] = [
  { value: 'string', label: 'String' },
  { value: 'enum', label: 'Enum' },
  { value: 'integer', label: 'Integer' },
  { value: 'number', label: 'Number' },
  { value: 'boolean', label: 'Boolean' },
  { value: 'timestamp', label: 'Timestamp' },
  { value: 'date', label: 'Date' },
];

/** One row of the Parameters table, as typed (values comma-separated, the default as text). */
export interface ParamDraft {
  readonly name: string;
  readonly type: ParamType;
  readonly required: boolean;
  readonly values: string;
  readonly dflt: string;
  readonly description: string;
}

export interface TemplateDraft {
  readonly name: string;
  readonly description: string;
  readonly sql: string;
  readonly params: readonly ParamDraft[];
}

/** What a scan of the SQL found: its placeholders in order of first use, the first keyword, and what stops it. */
export interface SqlScan {
  readonly placeholders: string[];
  readonly statement: string | null;
  /** Data-changing words, locking reads or a second statement: the tool would need approval. */
  readonly changes: boolean;
  readonly error: string | null;
}

const READ_STATEMENTS = new Set(['SELECT', 'WITH', 'SHOW', 'EXPLAIN', 'VALUES', 'TABLE']);

/**
 * Scans SQL as the connector's lexer does for placeholders (connectors/sql `NamedSql`): literals ('…', with ''),
 * quoted names ("…", `…`), comments (-- and nested /* *\/) and dollar quotes ($tag$…$tag$) are skipped; in code
 * `:name` is a placeholder, `::` (a cast) and `:=` are not.
 */
export function scanSql(sql: string): SqlScan {
  const placeholders: string[] = [];
  const code: string[] = [];
  let i = 0;
  let error: string | null = null;
  let current = '';
  const flush = (): void => {
    if (current) code.push(current);
    current = '';
  };
  while (i < sql.length) {
    const c = sql[i];
    const next = sql[i + 1] ?? '';
    if (c === "'" || c === '"' || c === '`') {
      flush();
      const end = closeQuote(sql, i, c);
      if (end < 0) {
        error = 'A quote or comment in the SQL isn’t closed.';
        break;
      }
      i = end;
      current += ' ';
    } else if (c === '-' && next === '-') {
      const nl = sql.indexOf('\n', i);
      i = nl < 0 ? sql.length : nl;
    } else if (c === '/' && next === '*') {
      const end = closeBlockComment(sql, i);
      if (end < 0) {
        error = 'A quote or comment in the SQL isn’t closed.';
        break;
      }
      i = end;
      current += ' ';
    } else if (c === '$' && /[A-Za-z_$]/.test(next) && /^\$[A-Za-z_]*\$/.test(sql.slice(i))) {
      const tag = /^\$[A-Za-z_]*\$/.exec(sql.slice(i))![0];
      const end = sql.indexOf(tag, i + tag.length);
      if (end < 0) {
        error = 'A quote or comment in the SQL isn’t closed.';
        break;
      }
      i = end + tag.length;
      current += ' ';
    } else if (c === ':' && (next === ':' || next === '=')) {
      current += c + next;
      i += 2;
    } else if (c === ':' && /[A-Za-z_]/.test(next) && !/[A-Za-z0-9_$]/.test(sql[i - 1] ?? '')) {
      let j = i + 1;
      while (j < sql.length && /[A-Za-z0-9_]/.test(sql[j])) j++;
      const name = sql.slice(i + 1, j);
      if (!placeholders.includes(name)) placeholders.push(name);
      current += ' ? ';
      i = j;
    } else {
      current += c;
      i++;
    }
  }
  flush();
  const text = code.join(' ');
  const words = text.toUpperCase().match(/[A-Z_]+/g) ?? [];
  const statement = words[0] ?? null;
  // A statement after a `;` (other than trailing whitespace) is a second one: a template runs one statement.
  const semi = text.indexOf(';');
  const second = semi >= 0 && text.slice(semi + 1).trim().length > 0;
  if (!error && second) error = 'A template runs one statement. Remove the second one.';
  // Only a read keeps Read only: a statement that isn't one, a data-changing CTE (WITH … DELETE), SELECT … INTO,
  // a locking read (FOR UPDATE / SHARE) and EXPLAIN ANALYZE (it runs the statement) need approval.
  const upper = ` ${text.toUpperCase().replace(/[\s(),]+/g, ' ')} `;
  const changes =
    !statement ||
    !READ_STATEMENTS.has(statement) ||
    / (INSERT|UPDATE|DELETE|MERGE) /.test(upper) ||
    / FOR (NO KEY )?SHARE /.test(upper) ||
    / INTO /.test(upper) ||
    (statement === 'EXPLAIN' && / ANALYZE /.test(upper));
  return { placeholders, statement, changes: changes || second, error };
}

function closeQuote(sql: string, start: number, q: string): number {
  let i = start + 1;
  while (i < sql.length) {
    if (sql[i] === q) {
      if (sql[i + 1] === q) {
        i += 2; // a doubled quote is the quote itself
        continue;
      }
      return i + 1;
    }
    i++;
  }
  return -1;
}

function closeBlockComment(sql: string, start: number): number {
  let depth = 0;
  let i = start;
  while (i < sql.length) {
    if (sql[i] === '/' && sql[i + 1] === '*') {
      depth++;
      i += 2;
    } else if (sql[i] === '*' && sql[i + 1] === '/') {
      depth--;
      i += 2;
      if (depth === 0) return i;
    } else {
      i++;
    }
  }
  return -1;
}

/**
 * The status line under the SQL (kit): "Parsed as SELECT, read only · 2 parameters: :status, :since"; a statement
 * that changes data needs approval; what keeps it from being saved, in danger.
 */
export function scanStatus(scan: SqlScan, sql: string): { readonly tone: 'success' | 'warning' | 'danger'; readonly text: string; readonly params: string[] } | null {
  if (!sql.trim()) return null;
  if (scan.error) return { tone: 'danger', text: scan.error, params: [] };
  const n = scan.placeholders.length;
  const what = scan.changes ? `Parsed as ${scan.statement ?? 'a statement'}, needs approval` : `Parsed as ${scan.statement}, read only`;
  if (!n) return { tone: scan.changes ? 'warning' : 'success', text: `${what} · no parameters`, params: [] };
  return {
    tone: scan.changes ? 'warning' : 'success',
    text: `${what} · ${n} ${n === 1 ? 'parameter' : 'parameters'}: `,
    params: scan.placeholders.map((p) => `:${p}`),
  };
}

export function newParam(name: string): ParamDraft {
  return { name, type: 'string', required: true, values: '', dflt: '', description: '' };
}

/** The Parameters rows for these placeholders, in their order, keeping what was typed for names that stay. */
export function syncParams(placeholders: readonly string[], current: readonly ParamDraft[]): ParamDraft[] {
  const byName = new Map(current.map((p) => [p.name, p]));
  return placeholders.map((n) => byName.get(n) ?? newParam(n));
}

function listOf(values: string): string[] {
  return values
    .split(',')
    .map((v) => v.trim())
    .filter(Boolean);
}

/** A default typed by its parameter: numbers and booleans as JSON values, times and text as strings. */
export function typedDefault(p: Pick<ParamDraft, 'type' | 'dflt'>): JsonValue | undefined {
  const text = p.dflt.trim();
  if (!text) return undefined;
  if (p.type === 'integer' && /^-?\d+$/.test(text)) return Number(text);
  if (p.type === 'number' && /^-?\d+(\.\d+)?([eE][-+]?\d+)?$/.test(text)) return Number(text);
  if (p.type === 'boolean' && (text === 'true' || text === 'false')) return text === 'true';
  return text;
}

/** The definition `POST`/`PUT /api/templates` takes for the `sql-query` kind. */
export function toDefinition(d: TemplateDraft): JsonObject {
  const def: JsonObject = { name: d.name.trim(), sql: d.sql };
  if (d.description.trim()) def['description'] = d.description.trim();
  def['params'] = d.params.map((p) => {
    const out: JsonObject = { name: p.name, type: p.type, required: p.required };
    const values = listOf(p.values);
    if (values.length) out['values'] = values;
    const dflt = typedDefault(p);
    if (dflt !== undefined) out['default'] = dflt;
    if (p.description.trim()) out['description'] = p.description.trim();
    return out;
  });
  return def;
}

/** A stored definition back into the editor's fields (a core that sends `definition` with the template). */
export function fromDefinition(def: JsonObject): TemplateDraft {
  const params = Array.isArray(def['params']) ? def['params'] : [];
  return {
    name: typeof def['name'] === 'string' ? def['name'] : '',
    description: typeof def['description'] === 'string' ? def['description'] : '',
    sql: typeof def['sql'] === 'string' ? def['sql'] : '',
    params: params.flatMap((p) => {
      if (!p || typeof p !== 'object' || Array.isArray(p) || typeof p['name'] !== 'string') return [];
      const type = PARAM_TYPES.some((t) => t.value === p['type']) ? (p['type'] as ParamType) : 'string';
      const values = Array.isArray(p['values']) ? p['values'].filter((x) => typeof x === 'string').join(', ') : '';
      const dflt = p['default'] === undefined || p['default'] === null ? '' : String(p['default']);
      return [{ name: p['name'], type, required: p['required'] !== false, values, dflt, description: typeof p['description'] === 'string' ? p['description'] : '' }];
    }),
  };
}

/** `-PT24H` → "24 hours ago", `P2D` → "2 days from now" (connectors/sql `TemplateParams.defaultText`); null if not one. */
export function humanDuration(text: string): string | null {
  const m = /^(-)?P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?)?$/.exec(text.trim());
  if (!m || (!m[2] && !m[3] && !m[4] && !m[5])) return null;
  const seconds = Number(m[2] ?? 0) * 86400 + Number(m[3] ?? 0) * 3600 + Number(m[4] ?? 0) * 60 + Number(m[5] ?? 0);
  const hours = Math.floor(seconds / 3600);
  const rest = seconds - hours * 3600;
  let human: string;
  if (rest === 0 && hours > 0) {
    human = hours % 24 === 0 && hours >= 48 ? `${hours / 24} days` : `${hours} ${hours === 1 ? 'hour' : 'hours'}`;
  } else {
    const minutes = Math.floor(seconds / 60);
    human = `${minutes} ${minutes === 1 ? 'minute' : 'minutes'}`;
  }
  return human + (m[1] ? ' ago' : ' from now');
}

/** A default that is a duration from now (a time or a date parameter whose default starts with P or -P). */
function isDuration(p: Pick<ParamDraft, 'type' | 'dflt'>): boolean {
  const t = p.dflt.trim();
  return (p.type === 'timestamp' || p.type === 'date') && (t.startsWith('P') || t.startsWith('-P'));
}

/** The tool's input schema as the core publishes it ("What the agent sees", `TemplateParams.inputSchema`). */
export function inputSchemaOf(params: readonly ParamDraft[]): JsonObject {
  const properties: JsonObject = {};
  const required: string[] = [];
  for (const p of params) {
    const prop: JsonObject = {};
    switch (p.type) {
      case 'string':
        prop['type'] = 'string';
        prop['maxLength'] = 256;
        break;
      case 'enum':
        prop['type'] = 'string';
        prop['enum'] = listOf(p.values);
        break;
      case 'integer':
      case 'number':
      case 'boolean':
        prop['type'] = p.type;
        break;
      case 'timestamp':
        prop['type'] = 'string';
        prop['format'] = 'date-time';
        break;
      case 'date':
        prop['type'] = 'string';
        prop['format'] = 'date';
        break;
    }
    let description = p.description.trim();
    const dflt = typedDefault(p);
    const dfltText = dflt === undefined ? null : isDuration(p) ? (humanDuration(p.dflt) ?? `${p.dflt.trim()} from now`) : String(dflt);
    if (dfltText !== null) {
      description = description ? `${description}${description.endsWith('.') ? ' ' : '. '}Default: ${dfltText}` : `Default: ${dfltText}`;
    }
    if (description) prop['description'] = description;
    if (dflt !== undefined && !isDuration(p)) prop['default'] = dflt;
    if (p.required && dflt === undefined) required.push(p.name);
    properties[p.name] = prop;
  }
  const schema: JsonObject = { type: 'object', properties };
  if (required.length) schema['required'] = required;
  return schema;
}

/**
 * JSON as the kit prints an input schema: objects one member per line, arrays of plain values on one line
 * (`"enum": ["pending", "paid"]`), two-space indent.
 */
export function formatSchema(value: JsonValue, indent = ''): string {
  if (Array.isArray(value)) {
    if (value.every((v) => v === null || typeof v !== 'object')) return `[${value.map((v) => JSON.stringify(v)).join(', ')}]`;
    const inner = indent + '  ';
    return `[\n${value.map((v) => inner + formatSchema(v, inner)).join(',\n')}\n${indent}]`;
  }
  if (value && typeof value === 'object') {
    const entries = Object.entries(value);
    if (!entries.length) return '{}';
    const inner = indent + '  ';
    return `{\n${entries.map(([k, v]) => `${inner}${JSON.stringify(k)}: ${formatSchema(v, inner)}`).join(',\n')}\n${indent}}`;
  }
  return JSON.stringify(value);
}

/** The tool name rule of the core (`^[a-z][a-z0-9_]{0,63}$`). */
export const TOOL_NAME = /^[a-z][a-z0-9_]{0,63}$/;
