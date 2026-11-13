/**
 * What each character of a SQL text is to the server that runs it: code, a string literal, a quoted name or a
 * comment. The colouring of an exact action (`highlight.ts`) paints a character as a string or a comment only where
 * this says so: text the server executes must never look like text it ignores.
 *
 * One model per dialect GateRook connects to, read the way the gateway's own lexer reads the text it checks
 * (connectors/sql `SqlLexer`, and the ClickHouse plugin's `ReadOnlySql` scanner):
 *
 * - PostgreSQL: `'…'` with `''` (standard_conforming_strings), `E'…'` with backslash escapes, `"…"` names,
 *   `$tag$…$tag$`, `--` line comments and nested `/* … *\/`; a `/*+ … *\/` hint is code (pg_hint_plan acts on it).
 * - MySQL and MariaDB: `'…'` and `"…"` with backslash escapes and doubled quotes, `` `…` `` names, `#` and `-- `
 *   (a space or control character after the dashes) line comments, `/* … *\/` that does not nest; `/*!…*\/`,
 *   `/*M!…*\/` and `/*+…*\/` are code the server runs.
 * - Oracle: `'…'`, `q'[…]'` / `nq'…'` alternative quotes, `"…"` names, `--` and `/* … *\/` (not nested); the hints
 *   `--+…` and `/*+…*\/` are code.
 * - SQL Server: `'…'`, `"…"` and `[…]` names, `--` and nested `/* … *\/`.
 * - SQLite: `'…'` with `''`, no backslash escapes; `"…"` and `` `…` `` names with doubled quotes, `[…]` names that end
 *   at the first `]`; `--` and `/* … *\/` that does not nest.
 * - ClickHouse: `'…'` with backslash escapes, `"…"` and `` `…` `` names, `--`, `#` and `//` line comments, nested
 *   `/* … *\/` and `$tag$…$tag$` heredocs. Where the model can't follow the server (a `$` that opens no heredoc, a
 *   typographic quote ‘ ’ “ ”), everything after it is code.
 *
 * A text whose dialect is not known is read with every model, and a character counts as a string, a name or a comment
 * only when they all agree ({@link sqlRoles}).
 */

export type SqlDialect = 'postgres' | 'mysql' | 'oracle' | 'sqlserver' | 'sqlite' | 'clickhouse';

/** Every dialect GateRook reads: what a SQL text of an unknown connection is checked against. */
export const SQL_DIALECTS: readonly SqlDialect[] = ['postgres', 'mysql', 'oracle', 'sqlserver', 'sqlite', 'clickhouse'];

/** What one character is to the server. */
export type SqlRole = typeof CODE | typeof STRING | typeof NAME | typeof COMMENT;
export const CODE = 0;
export const STRING = 1;
export const NAME = 2;
export const COMMENT = 3;

/**
 * The dialect of a connection type (`ConnectionRow.type`), or null for a type whose SQL GateRook has no model of:
 * MariaDB connections use the MySQL type.
 */
export function sqlDialectOf(connectionType: string | null | undefined): SqlDialect | null {
  switch ((connectionType ?? '').toLowerCase()) {
    case 'postgres':
    case 'postgresql':
      return 'postgres';
    case 'mysql':
    case 'mariadb':
      return 'mysql';
    case 'oracle':
      return 'oracle';
    case 'sqlserver':
    case 'mssql':
      return 'sqlserver';
    case 'sqlite':
      return 'sqlite';
    case 'clickhouse':
      return 'clickhouse';
    default:
      return null;
  }
}

/** A construct that is still open where a text ends: its characters go on in the next text. */
type Open =
  | { readonly k: 'quote'; readonly close: string; readonly backslash: boolean; readonly role: SqlRole; readonly escaped: boolean;
      /** False where a doubled closing character ends the name (SQLite's `[…]`). */
      readonly doubled?: boolean }
  | { readonly k: 'line'; readonly role: SqlRole }
  | { readonly k: 'block'; readonly nested: boolean; readonly depth: number; readonly role: SqlRole }
  | { readonly k: 'tag'; readonly tag: string }
  /** Oracle's `q'X…X'`; `close` null: an opener the server can't close (white space or a quote after `q'`). */
  | { readonly k: 'alt'; readonly close: string | null };

/** Where one model is at the end of a text: carried to the next line. */
export interface SqlLexState {
  readonly open: Open | null;
  /** The last two characters before the next text, for the rules that look back (`E'…'`, `$tag$`, `q'…'`). */
  readonly before: string;
  /** The model can't follow the text any more: everything from here on is code. */
  readonly lost: boolean;
}

export const SQL_START: SqlLexState = { open: null, before: '', lost: false };

/** How a construct opens at one position: what it is, and how many characters its opening takes. */
interface Opening {
  readonly open: Open;
  readonly length: number;
}

/**
 * The role of every UTF-16 unit of `text` for one dialect, continuing from `state` (the end of the previous text);
 * the returned state is where the next text continues.
 */
export function scanSql(dialect: SqlDialect, text: string, state: SqlLexState = SQL_START): { readonly roles: Uint8Array; readonly state: SqlLexState } {
  const n = text.length;
  const roles = new Uint8Array(n);
  const before = (state.before + text).slice(-2);
  if (state.lost) return { roles, state: { open: null, before, lost: true } };
  const at = (k: number): string | undefined => (k >= 0 ? text[k] : state.before[state.before.length + k]);
  let open = state.open;
  let i = 0;
  while (i < n) {
    if (open) {
      const { end, rest } = closeAt(open, text, i);
      const stop = end < 0 ? n : end;
      roles.fill(roleOf(open), i, stop);
      i = stop;
      open = end < 0 ? rest : null;
      continue;
    }
    const opening = openAt(dialect, text, i, at);
    if (opening === null) {
      i++;
      continue;
    }
    if (opening === 'lost') return { roles, state: { open: null, before, lost: true } };
    roles.fill(roleOf(opening.open), i, Math.min(n, i + opening.length));
    i += opening.length;
    open = opening.open;
  }
  return { roles, state: { open, before, lost: false } };
}

/**
 * The roles of a whole text: with one dialect its model's; without one (null), the roles every model agrees on and
 * code wherever they differ.
 */
export function sqlRoles(text: string, dialect: SqlDialect | null): Uint8Array {
  return agree((dialect ? [dialect] : SQL_DIALECTS).map((d) => scanSql(d, text).roles));
}

/** Per character, the role all the models give it; code where any two differ. */
export function agree(all: readonly Uint8Array[]): Uint8Array {
  if (all.length === 1) return all[0];
  const out = new Uint8Array(all[0].length);
  for (let i = 0; i < out.length; i++) {
    const role = all[0][i];
    out[i] = all.every((r) => r[i] === role) ? role : CODE;
  }
  return out;
}

function roleOf(open: Open): SqlRole {
  switch (open.k) {
    case 'quote':
    case 'line':
    case 'block':
      return open.role;
    case 'tag':
    case 'alt':
      return STRING;
  }
}

// ── Where a construct ends ─────────────────────────────────────────────────────────────────────────────────

/** The index after the construct's last character, or -1 and its state when the text ends inside it. */
function closeAt(open: Open, text: string, from: number): { readonly end: number; readonly rest: Open } {
  switch (open.k) {
    case 'quote': {
      let j = from;
      if (open.escaped) j++;
      while (j < text.length) {
        const c = text[j];
        if (open.backslash && c === '\\') {
          if (j + 1 >= text.length) return { end: -1, rest: { ...open, escaped: true } };
          j += 2;
          continue;
        }
        if (c === open.close) {
          if (open.doubled !== false && text[j + 1] === open.close) {
            j += 2;
            continue;
          }
          return { end: j + 1, rest: open };
        }
        j++;
      }
      return { end: -1, rest: { ...open, escaped: j > text.length } };
    }
    case 'line': {
      for (let j = from; j < text.length; j++) if (text[j] === '\n' || text[j] === '\r') return { end: j, rest: open };
      return { end: -1, rest: open };
    }
    case 'block': {
      let depth = open.depth;
      let j = from;
      while (j < text.length - 1) {
        if (open.nested && text[j] === '/' && text[j + 1] === '*') {
          depth++;
          j += 2;
        } else if (text[j] === '*' && text[j + 1] === '/') {
          depth--;
          j += 2;
          if (depth === 0) return { end: j, rest: open };
        } else {
          j++;
        }
      }
      return { end: -1, rest: { ...open, depth } };
    }
    case 'tag': {
      const close = text.indexOf(open.tag, from);
      return { end: close < 0 ? -1 : close + open.tag.length, rest: open };
    }
    case 'alt': {
      if (open.close === null) return { end: -1, rest: open };
      const close = text.indexOf(open.close + "'", from);
      return { end: close < 0 ? -1 : close + 2, rest: open };
    }
  }
}

// ── Where a construct starts ───────────────────────────────────────────────────────────────────────────────

function openAt(dialect: SqlDialect, text: string, i: number, at: (k: number) => string | undefined): Opening | 'lost' | null {
  const c = text[i];
  const next = text[i + 1];
  switch (dialect) {
    case 'postgres':
      if (c === "'") return quote("'", escapeString(i, at), STRING);
      if (c === '"') return quote('"', false, NAME);
      if (c === '-' && next === '-') return line(COMMENT);
      if (c === '/' && next === '*') return block(true, text.startsWith('/*+', i) ? CODE : COMMENT);
      if (c === '$' && !identifierPart(at(i - 1))) {
        const end = dollarTag(text, i);
        if (end > 0) return { open: { k: 'tag', tag: text.slice(i, end) }, length: end - i };
      }
      return null;
    case 'mysql':
      if (c === "'" || c === '"') return quote(c, true, STRING);
      if (c === '`') return quote('`', false, NAME);
      if (c === '#') return { open: { k: 'line', role: COMMENT }, length: 1 };
      // "--" is a comment only before white space or a control character ("--1" is minus minus one).
      if (c === '-' && next === '-' && (i + 2 === text.length || text[i + 2] <= ' ')) return line(COMMENT);
      if (c === '/' && next === '*') {
        const executable = ['/*!', '/*M!', '/*m!', '/*+'].some((p) => text.startsWith(p, i));
        return block(false, executable ? CODE : COMMENT);
      }
      return null;
    case 'oracle': {
      const alt = alternativeQuote(text, i, at);
      if (alt !== null) return alt;
      if (c === "'") return quote("'", false, STRING);
      if (c === '"') return quote('"', false, NAME);
      if (c === '-' && next === '-') return line(text.startsWith('--+', i) ? CODE : COMMENT);
      if (c === '/' && next === '*') return block(false, text.startsWith('/*+', i) ? CODE : COMMENT);
      return null;
    }
    case 'sqlserver':
      if (c === "'") return quote("'", false, STRING);
      if (c === '"') return quote('"', false, NAME);
      if (c === '[') return quote(']', false, NAME);
      if (c === '-' && next === '-') return line(COMMENT);
      if (c === '/' && next === '*') return block(true, COMMENT);
      return null;
    case 'sqlite':
      if (c === "'") return quote("'", false, STRING);
      if (c === '"' || c === '`') return quote(c, false, NAME);
      if (c === '[') return { open: { k: 'quote', close: ']', backslash: false, role: NAME, escaped: false, doubled: false }, length: 1 };
      if (c === '-' && next === '-') return line(COMMENT);
      if (c === '/' && next === '*') return block(false, COMMENT);
      return null;
    case 'clickhouse':
      if ((c === '-' && next === '-') || (c === '/' && next === '/')) return line(COMMENT);
      if (c === '#') return { open: { k: 'line', role: COMMENT }, length: 1 };
      if (c === '/' && next === '*') return block(true, COMMENT);
      if (c === "'") return quote("'", true, STRING);
      if (c === '"' || c === '`') return quote(c, true, NAME);
      if (c === '$') {
        const m = /^\$[A-Za-z0-9_]*\$/.exec(text.slice(i, i + 256));
        return m ? { open: { k: 'tag', tag: m[0] }, length: m[0].length } : 'lost';
      }
      if (c === '‘' || c === '’' || c === '“' || c === '”') return 'lost';
      return null;
    default:
      // A dialect without a model (a caller outside the types): nothing in it is taken for a literal or a comment.
      return 'lost';
  }
}

function quote(close: string, backslash: boolean, role: SqlRole): Opening {
  return { open: { k: 'quote', close, backslash, role, escaped: false }, length: 1 };
}

function line(role: SqlRole): Opening {
  return { open: { k: 'line', role }, length: 2 };
}

function block(nested: boolean, role: SqlRole): Opening {
  return { open: { k: 'block', nested, depth: 1, role }, length: 2 };
}

/** PostgreSQL `E'…'`: the quote at `i` opens a string that takes backslash escapes. */
function escapeString(i: number, at: (k: number) => string | undefined): boolean {
  const e = at(i - 1);
  if (e !== 'E' && e !== 'e') return false;
  const b = at(i - 2);
  return b === undefined || !identifierPart(b);
}

/** `$tag$` at `i`: the index after its closing `$`, or -1 when `i` starts no dollar quote. */
function dollarTag(text: string, i: number): number {
  let j = i + 1;
  if (text[j] === '$') return j + 1;
  if (j >= text.length || !(isLetter(text[j]) || text[j] === '_')) return -1;
  while (j < text.length && (isLetterOrDigit(text[j]) || text[j] === '_')) j++;
  return text[j] === '$' ? j + 1 : -1;
}

/** Oracle `q'X…X'` and `nq'X…X'` at `i` (the `n` or the `q`), or null. */
function alternativeQuote(text: string, i: number, at: (k: number) => string | undefined): Opening | null {
  const b = at(i - 1);
  if (b !== undefined && (identifierPart(b) || b === '#')) return null;
  let q = i;
  if (text[q] === 'n' || text[q] === 'N') q++;
  if (!(q + 2 < text.length && (text[q] === 'q' || text[q] === 'Q') && text[q + 1] === "'")) return null;
  const opener = text[q + 2];
  const close = opener === "'" || javaWhitespace(opener) ? null : ({ '[': ']', '{': '}', '(': ')', '<': '>' } as Record<string, string>)[opener] ?? opener;
  return { open: { k: 'alt', close }, length: q + 3 - i };
}

// ── Characters, as Java's Character reads one UTF-16 unit ──────────────────────────────────────────────────

function isLetter(c: string | undefined): boolean {
  return c !== undefined && /\p{L}/u.test(c);
}

function isLetterOrDigit(c: string | undefined): boolean {
  return c !== undefined && /[\p{L}\p{Nd}]/u.test(c);
}

/** `SqlLexer.identifierPart`: a letter, a digit, `_` or `$`. */
function identifierPart(c: string | undefined): boolean {
  return c !== undefined && (isLetterOrDigit(c) || c === '_' || c === '$');
}

/** `Character.isWhitespace`: space separators other than the no-break ones, and the ASCII and separator controls. */
function javaWhitespace(c: string | undefined): boolean {
  if (c === undefined) return false;
  if (/[\t\n\u000B\f\r\u001C-\u001F]/.test(c)) return true;
  return /[\p{Zs}\p{Zl}\p{Zp}]/u.test(c) && c !== ' ' && c !== ' ' && c !== ' ';
}
