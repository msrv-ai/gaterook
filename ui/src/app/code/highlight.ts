import { CODE, COMMENT, NAME, SQL_DIALECTS, SQL_START, STRING, SqlDialect, SqlLexState, agree, scanSql } from './sql-lexer';

/**
 * Syntax colour for code blocks (`gw-tok-*`): decoration only. Every character of the input comes out once, in order,
 * in some token — the text a person approves is never changed, dropped or reordered (tests check that). Unknown
 * languages come out as plain text.
 *
 * The colours must never make text that runs look like text that doesn't: a character is painted as a string or a
 * comment only where the language's own reading says so. SQL follows the dialect of the action's connection
 * (`sql-lexer.ts`; every dialect GateRook reads must agree when it isn't known), with literals and comments carried
 * from line to line. Shell and YAML stop colouring (plain text from there on) where they could no longer tell what the
 * shell or the YAML reader does with the text.
 */

export type TokenClass =
  | 'keyword'
  | 'string'
  | 'number'
  | 'comment'
  | 'function'
  | 'property'
  | 'punctuation'
  | 'secret';

export interface Token {
  readonly cls: TokenClass | null;
  readonly text: string;
}

/** A here-document whose body follows: the line that ends it, and whether `<<-` strips leading tabs. */
interface HereDoc {
  readonly delimiter: string;
  readonly tabs: boolean;
}

interface ShellState {
  /** The colouring can't follow the text any more: everything from here on is plain. */
  readonly lost: boolean;
  /** Here-documents opened on earlier lines, in order; `body`: the next line belongs to the first one. */
  readonly docs: readonly HereDoc[];
  readonly body: boolean;
  /** The line before ended with a backslash: this one goes on with its command, not at the start of a word. */
  readonly continued: boolean;
  readonly commandStart: boolean;
}

interface YamlState {
  /** Inside a quoted scalar (TOML: a multi-line string) that goes on from the line before: what closes it. */
  readonly quote: string | null;
  /** Inside a block scalar (`|`, `>`): lines indented deeper than this column belong to it. */
  readonly block: number | null;
}

/** Carried from one line to the next: what an earlier line left open. */
export interface HighlightState {
  /** SQL: the dialects the colour must agree with (one when the connection's is known) and where each one is. */
  readonly sql: { readonly dialects: readonly SqlDialect[]; readonly states: readonly SqlLexState[] };
  readonly shell: ShellState;
  readonly yaml: YamlState;
}

const SHELL_START: ShellState = { lost: false, docs: [], body: false, continued: false, commandStart: true };
const YAML_START: YamlState = { quote: null, block: null };

/** The state a text starts in; `dialect`: the SQL dialect of the action's connection, null when not known. */
export function highlightState(dialect: SqlDialect | null = null): HighlightState {
  const dialects = dialect ? [dialect] : SQL_DIALECTS;
  return { sql: { dialects, states: dialects.map(() => SQL_START) }, shell: SHELL_START, yaml: YAML_START };
}

export const INITIAL_STATE: HighlightState = highlightState(null);

const SQL_KEYWORDS = new Set(
  (
    'select from where and or not in is null like ilike between join left right inner outer full cross on using group by ' +
    'order asc desc limit offset having distinct as case when then else end insert into values update set delete ' +
    'returning create alter drop truncate table index view schema database grant revoke begin commit rollback with ' +
    'union all except intersect exists interval true false default primary key foreign references constraint unique ' +
    'explain analyze show describe cascade restrict if replace temporary temp sequence function trigger procedure ' +
    'call do language returns return declare cast over partition window filter lateral natural only'
  ).split(' '),
);

/** `‹masked›` (a value the core removed) and `‹token›` (where a snippet wants the person's token). */
const SECRET = /‹(?:masked[^›]*|token)›/;

export function isSqlLanguage(language: string | null): boolean {
  return (language ?? '').toLowerCase() === 'sql';
}

/**
 * One line of a text and the state the next line starts in. A line is a whole source line: what a rule sees past its
 * end is the line break.
 */
export function highlightLine(text: string, language: string | null, state: HighlightState = INITIAL_STATE): {
  readonly tokens: Token[];
  readonly state: HighlightState;
} {
  const lang = (language ?? '').toLowerCase();
  if (lang === 'sql') return sqlLine(text, state);
  if (lang === 'json') return { tokens: json(text), state };
  if (lang === 'sh' || lang === 'bash' || lang === 'shell' || lang === 'command' || lang === 'runbook') {
    const line = shell(text, state.shell);
    return { tokens: line.tokens, state: { ...state, shell: line.state } };
  }
  if (lang === 'yaml' || lang === 'yml' || lang === 'toml') {
    const line = yaml(text, lang === 'toml', state.yaml);
    return { tokens: line.tokens, state: { ...state, yaml: line.state } };
  }
  if (lang === 'http') return { tokens: http(text), state };
  return { tokens: secrets(text, null), state };
}

/**
 * The tokens of a SQL text whose characters' roles are known (`sqlRoles` over the whole action): strings and comments
 * exactly where the roles say, keywords, numbers and functions only in code.
 */
export function sqlTokens(text: string, roles: ArrayLike<number>): Token[] {
  const out: Token[] = [];
  let i = 0;
  while (i < text.length) {
    const role = roles[i];
    let j = i + 1;
    while (j < text.length && roles[j] === role) j++;
    const run = text.slice(i, j);
    if (role === STRING) out.push(...secrets(run, 'string'));
    else if (role === COMMENT) push(out, 'comment', run);
    else if (role === NAME) push(out, null, run);
    else sqlCode(out, run);
    i = j;
  }
  return out;
}

/** Splits `‹masked›` / `‹token›` placeholders out of a run as `secret` tokens. */
function secrets(text: string, cls: TokenClass | null): Token[] {
  const out: Token[] = [];
  let rest = text;
  for (;;) {
    const m = SECRET.exec(rest);
    if (!m) break;
    if (m.index > 0) out.push({ cls, text: rest.slice(0, m.index) });
    out.push({ cls: 'secret', text: m[0] });
    rest = rest.slice(m.index + m[0].length);
  }
  if (rest) out.push({ cls, text: rest });
  return out;
}

function push(out: Token[], cls: TokenClass | null, text: string): void {
  if (!text) return;
  const last = out[out.length - 1];
  if (last && last.cls === cls && cls !== 'secret') out[out.length - 1] = { cls, text: last.text + text };
  else out.push({ cls, text });
}

// ── SQL ──────────────────────────────────────────────────────────────────────────────────────────────────────

function sqlLine(text: string, state: HighlightState): { tokens: Token[]; state: HighlightState } {
  const { dialects, states } = state.sql;
  // The line with its break: a rule that looks one character ahead ("-- ", "q'") sees what the server sees.
  const scans = dialects.map((d, k) => scanSql(d, text + '\n', states[k]));
  const roles = agree(scans.map((s) => s.roles)).subarray(0, text.length);
  return { tokens: sqlTokens(text, roles), state: { ...state, sql: { dialects, states: scans.map((s) => s.state) } } };
}

/** A run the server reads as code: keywords, functions, numbers and punctuation; nothing in it is a literal. */
function sqlCode(out: Token[], text: string): void {
  let i = 0;
  while (i < text.length) {
    const rest = text.slice(i);
    let m: RegExpExecArray | null;
    if ((m = /^‹masked[^›]*›/.exec(rest))) {
      out.push({ cls: 'secret', text: m[0] });
      i += m[0].length;
      continue;
    }
    if ((m = /^\d+(?:\.\d+)?\b/.exec(rest))) {
      push(out, 'number', m[0]);
      i += m[0].length;
      continue;
    }
    if ((m = /^[A-Za-z_][A-Za-z0-9_$]*/.exec(rest))) {
      const word = m[0];
      const after = rest.slice(word.length);
      if (SQL_KEYWORDS.has(word.toLowerCase())) push(out, 'keyword', word);
      else if (/^\s*\(/.test(after)) push(out, 'function', word);
      else push(out, null, word);
      i += word.length;
      continue;
    }
    if ((m = /^[(),;=<>!+\-*/%|.:[\]]+/.exec(rest))) {
      push(out, 'punctuation', m[0]);
      i += m[0].length;
      continue;
    }
    push(out, null, text[i]);
    i += 1;
  }
}

// ── JSON and HTTP ────────────────────────────────────────────────────────────────────────────────────────────

function json(text: string): Token[] {
  const out: Token[] = [];
  let i = 0;
  while (i < text.length) {
    const rest = text.slice(i);
    let m: RegExpExecArray | null;
    if ((m = /^"(?:[^"\\]|\\.)*"?/.exec(rest)) && rest[0] === '"') {
      const isKey = /^\s*:/.test(rest.slice(m[0].length));
      // A string that is only a placeholder is the secret; inside a longer string only the placeholder is
      // ("Bearer ‹token›", as the kit draws it).
      if (SECRET.test(m[0]) && !isKey) {
        if (/^"‹[^›]*›"$/.test(m[0])) out.push({ cls: 'secret', text: m[0] });
        else out.push(...secrets(m[0], 'string'));
      } else push(out, isKey ? 'property' : 'string', m[0]);
      i += m[0].length;
      continue;
    }
    if ((m = /^-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?/.exec(rest))) {
      push(out, 'number', m[0]);
      i += m[0].length;
      continue;
    }
    if ((m = /^(?:true|false|null)\b/.exec(rest))) {
      push(out, 'keyword', m[0]);
      i += m[0].length;
      continue;
    }
    if ((m = /^[{}[\],:]+/.exec(rest))) {
      push(out, 'punctuation', m[0]);
      i += m[0].length;
      continue;
    }
    push(out, null, text[i]);
    i += 1;
  }
  return out;
}

function http(text: string): Token[] {
  const req = /^(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)(\s.*)$/.exec(text);
  if (req) {
    // The target (path or URL) reads as a string, the protocol version as plain text: "POST /v1/refunds HTTP/1.1".
    const target = /^(\s+)(\S+)(.*)$/.exec(req[2]);
    if (!target) return [{ cls: 'keyword', text: req[1] }, ...secrets(req[2], null)];
    return [{ cls: 'keyword', text: req[1] }, { cls: null, text: target[1] }, ...secrets(target[2], 'string'), ...secrets(target[3], null)];
  }
  const header = /^([A-Za-z0-9-]+)(:)(.*)$/.exec(text);
  if (header) return [{ cls: 'property', text: header[1] }, { cls: 'punctuation', text: header[2] }, ...secrets(header[3], null)];
  return json(text);
}

// ── Shell ────────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * A POSIX shell line. Quotes and comments are painted only where the line alone says what they are: a quote still
 * open at the end of the line, an expansion inside double quotes (`$(…)`, `${…}`, backquotes, which can hold quotes
 * of their own) or a here-document that can't be followed make the rest of the text plain; a here-document's body is
 * plain (its expansions run, and `#` in it is no comment).
 */
function shell(text: string, state: ShellState): { tokens: Token[]; state: ShellState } {
  if (state.lost) return { tokens: secrets(text, null), state };
  if (state.body) {
    const [doc, ...rest] = state.docs;
    const ends = (doc.tabs ? text.replace(/^\t+/, '') : text) === doc.delimiter;
    return { tokens: secrets(text, null), state: ends ? { ...state, docs: rest, body: rest.length > 0 } : state };
  }
  const out: Token[] = [];
  const docs: HereDoc[] = [];
  let i = 0;
  let lost = false;
  let docsLost = false;
  let comment = false;
  /** Open `((` of arithmetic on this line: `<<` in it is a shift, not a here-document. */
  let arithmetic = 0;
  let wordStart = !state.continued;
  // The command word (first of the line, or after | ; && ||) reads as a function: `kubectl`, `sudo`, `docker`.
  let commandStart = state.continued ? state.commandStart : true;
  const loseFrom = (at: number) => {
    out.push(...secrets(text.slice(at), null));
    lost = true;
    i = text.length;
  };
  while (i < text.length) {
    const rest = text.slice(i);
    let m: RegExpExecArray | null;
    if (rest[0] === '#' && wordStart) {
      push(out, 'comment', rest);
      comment = true;
      break;
    }
    if (rest[0] === '\\') {
      // An escaped character is a character: \' opens no string, \# starts no comment.
      push(out, null, rest.slice(0, 2));
      i += 2;
      wordStart = false;
      continue;
    }
    if (rest.startsWith('$$')) {
      // The shell's process id: a quote right after it is an ordinary one, not $'…'.
      push(out, null, '$$');
      i += 2;
      wordStart = false;
      continue;
    }
    if (rest.startsWith('((') || rest.startsWith('$((')) {
      arithmetic++;
      push(out, 'punctuation', rest[0] === '$' ? '$((' : '((');
      i += rest[0] === '$' ? 3 : 2;
      wordStart = false;
      continue;
    }
    if (rest.startsWith('))') && arithmetic > 0) {
      arithmetic--;
      push(out, 'punctuation', '))');
      i += 2;
      wordStart = false;
      continue;
    }
    if (rest[0] === '`') {
      // Command substitution: the shell finds the closing backquote first, so a # inside it comments out nothing
      // after it. Plain, as code.
      const end = closingQuote(text, i, '`', true);
      if (end < 0) {
        loseFrom(i);
        break;
      }
      out.push(...secrets(text.slice(i, end), null));
      i = end;
      wordStart = false;
      continue;
    }
    if (rest[0] === "'" || rest.startsWith("$'")) {
      // '…' takes no escapes; $'…' (ANSI-C quoting) takes backslash escapes, \' included.
      const ansi = rest[0] === '$';
      const end = closingQuote(text, ansi ? i + 1 : i, "'", ansi);
      if (end < 0) {
        out.push(...secrets(rest, 'string'));
        lost = true;
        break;
      }
      out.push(...secrets(text.slice(i, end), 'string'));
      i = end;
      wordStart = false;
      continue;
    }
    if (rest[0] === '"') {
      const end = closingQuote(text, i, '"', true);
      // An expansion inside runs (and may hold quotes of its own, so the end may not be where it looks).
      if (expands(text.slice(i + 1, end < 0 ? text.length : end - 1))) {
        loseFrom(i);
        break;
      }
      if (end < 0) {
        out.push(...secrets(rest, 'string'));
        lost = true;
        break;
      }
      out.push(...secrets(text.slice(i, end), 'string'));
      i = end;
      wordStart = false;
      continue;
    }
    if ((m = /^‹masked[^›]*›/.exec(rest))) {
      out.push({ cls: 'secret', text: m[0] });
      i += m[0].length;
      continue;
    }
    if (rest.startsWith('<<<')) {
      push(out, 'punctuation', '<<<');
      i += 3;
      wordStart = true;
      continue;
    }
    if (rest.startsWith('<<') && arithmetic > 0) {
      push(out, 'punctuation', '<<');
      i += 2;
      continue;
    }
    if (rest.startsWith('<<')) {
      // A here-document: its body starts on the next line and ends at the line that is its delimiter.
      const doc = /^<<(-?)([ \t]*)(?:'([^']+)'|"([^"]+)"|\\?([A-Za-z0-9_.+%@:/-]+))(?=$|[\s;|&<>()])/.exec(rest);
      if (!doc) {
        docsLost = true;
        push(out, 'punctuation', '<<');
        i += 2;
        wordStart = true;
        continue;
      }
      docs.push({ delimiter: doc[3] ?? doc[4] ?? doc[5], tabs: doc[1] === '-' });
      push(out, 'punctuation', '<<' + doc[1]);
      push(out, null, doc[2]);
      out.push({ cls: doc[3] || doc[4] ? 'string' : null, text: doc[0].slice(2 + doc[1].length + doc[2].length) });
      i += doc[0].length;
      wordStart = false;
      continue;
    }
    if ((m = /^(?:\|\||&&|[|;&<>()])/.exec(rest))) {
      push(out, 'punctuation', m[0]);
      i += m[0].length;
      wordStart = true;
      commandStart = /^(?:\|\||&&|[|;&])$/.test(m[0]);
      continue;
    }
    if ((m = /^[A-Za-z0-9_./][\w./-]*/.exec(rest)) && wordStart && commandStart) {
      push(out, 'function', m[0]);
      i += m[0].length;
      wordStart = false;
      commandStart = false;
      continue;
    }
    if ((m = /^--?[A-Za-z][\w-]*/.exec(rest)) && wordStart) {
      push(out, 'property', m[0]);
      i += m[0].length;
      wordStart = false;
      continue;
    }
    const ch = text[i];
    push(out, null, ch);
    wordStart = /\s/.test(ch);
    i += 1;
  }
  if (lost || docsLost) return { tokens: out, state: { ...SHELL_START, lost: true } };
  const continued = !comment && /(?:^|[^\\])(?:\\\\)*\\$/.test(text);
  return {
    tokens: out,
    state: { lost: false, docs, body: docs.length > 0, continued, commandStart: continued ? commandStart : true },
  };
}

/** The index after the quote that closes the one at `from`, or -1 when the line ends first. */
function closingQuote(text: string, from: number, quote: string, escapes: boolean): number {
  for (let j = from + 1; j < text.length; j++) {
    if (escapes && text[j] === '\\') {
      j++;
      continue;
    }
    if (text[j] === quote) return j + 1;
  }
  return -1;
}

/** Whether the inside of a double-quoted string holds an expansion that can hold quotes of its own. */
function expands(inside: string): boolean {
  for (let j = 0; j < inside.length; j++) {
    const c = inside[j];
    if (c === '\\') {
      j++;
      continue;
    }
    if (c === '`' || (c === '$' && (inside[j + 1] === '(' || inside[j + 1] === '{' || inside[j + 1] === '['))) return true;
  }
  return false;
}

// ── YAML and TOML ────────────────────────────────────────────────────────────────────────────────────────────

/**
 * A YAML (or TOML) line. `#` starts a comment only outside quotes, after white space or at the start of the line, and
 * never inside a block scalar or a quoted value that goes on over several lines: those are the value's text. A line
 * that ends with a block indicator (`|`, `>-`…) starts a block scalar whatever its key looks like (`example.com/x: |`):
 * the lines below it that are indented deeper than it are the scalar's text.
 */
function yaml(text: string, toml: boolean, state: YamlState): { tokens: Token[]; state: YamlState } {
  const out: Token[] = [];
  let i = 0;
  if (state.block !== null && (!text.trim() || indent(text) > state.block)) return { tokens: secrets(text, 'string'), state };
  if (state.quote !== null) {
    const end = scalarEnd(text, 0, state.quote);
    if (end < 0) return { tokens: secrets(text, 'string'), state };
    out.push(...secrets(text.slice(0, end), 'string'));
    i = end;
  } else {
    const key = /^(\s*-?\s*)([A-Za-z0-9_.\-"[\]]+)(\s*[:=])/.exec(text);
    if (key) {
      push(out, null, key[1]);
      push(out, 'property', key[2]);
      push(out, 'punctuation', key[3]);
      i = key[0].length;
    }
  }
  // The value: quoted parts are strings, `#` after white space starts the comment.
  let plain = '';
  /** The line outside quotes and its comment: whether it ends with a block indicator. */
  let code = text.slice(0, i);
  const flush = () => {
    if (!plain) return;
    const trimmed = plain.trim();
    if (/^-?\d+(?:\.\d+)?$/.test(trimmed)) push(out, 'number', plain);
    else if (/^(?:true|false|null|yes|no)$/i.test(trimmed)) push(out, 'keyword', plain);
    else out.push(...secrets(plain, null));
    code += plain;
    plain = '';
  };
  const done = (quote: string | null): { tokens: Token[]; state: YamlState } => {
    const block = !toml && quote === null && /(?:^|[\s:])[|>][0-9+-]*\s*$/.test(code) ? indent(text) : null;
    return { tokens: out, state: { quote, block } };
  };
  let scalarStart = true;
  while (i < text.length) {
    const c = text[i];
    const prev = i > 0 ? text[i - 1] : ' ';
    if (c === '#' && /\s/.test(prev)) {
      flush();
      push(out, 'comment', text.slice(i));
      return done(null);
    }
    if ((c === '"' || c === "'") && (scalarStart || /[\s[{,:]/.test(prev))) {
      flush();
      const close = toml && text.startsWith(c.repeat(3), i) ? c.repeat(3) : c;
      const end = scalarEnd(text, i + close.length, close);
      if (end < 0) {
        out.push(...secrets(text.slice(i), 'string'));
        return done(close);
      }
      out.push(...secrets(text.slice(i, end), 'string'));
      code += '""';
      i = end;
      scalarStart = false;
      continue;
    }
    plain += c;
    scalarStart = /[\s[{,]/.test(c);
    i++;
  }
  flush();
  return done(null);
}

/**
 * The index after the quote that closes a quoted scalar from `from` on, or -1 when the line ends first: `"` takes
 * backslash escapes, `'` a doubled quote, TOML's `"""` and `'''` the triple.
 */
function scalarEnd(text: string, from: number, close: string): number {
  for (let j = from; j < text.length; j++) {
    if (close.startsWith('"') && text[j] === '\\') {
      j++;
      continue;
    }
    if (!text.startsWith(close, j)) continue;
    if (close === "'" && text[j + 1] === "'") {
      j++;
      continue;
    }
    return j + close.length;
  }
  return -1;
}

function indent(text: string): number {
  return text.length - text.trimStart().length;
}
