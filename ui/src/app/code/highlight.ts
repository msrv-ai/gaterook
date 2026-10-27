/**
 * Syntax colour for code blocks (`gw-tok-*`): decoration only. Every character of the input comes out once, in order,
 * in some token — the text a person approves is never changed, dropped or reordered (tests check that). Unknown
 * languages come out as plain text.
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

/** Carried from one line to the next: an open SQL block comment. */
export interface HighlightState {
  readonly inBlockComment: boolean;
}

export const INITIAL_STATE: HighlightState = { inBlockComment: false };

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

export function highlightLine(text: string, language: string | null, state: HighlightState = INITIAL_STATE): {
  readonly tokens: Token[];
  readonly state: HighlightState;
} {
  const lang = (language ?? '').toLowerCase();
  if (lang === 'sql') return sql(text, state);
  if (lang === 'json') return { tokens: json(text), state };
  if (lang === 'sh' || lang === 'bash' || lang === 'shell' || lang === 'command' || lang === 'runbook') return { tokens: shell(text), state };
  if (lang === 'yaml' || lang === 'yml' || lang === 'toml') return { tokens: yaml(text), state };
  if (lang === 'http') return { tokens: http(text), state };
  return { tokens: secrets(text, null), state };
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

function sql(text: string, state: HighlightState): { tokens: Token[]; state: HighlightState } {
  const out: Token[] = [];
  let i = 0;
  let inBlock = state.inBlockComment;
  while (i < text.length) {
    if (inBlock) {
      const end = text.indexOf('*/', i);
      const stop = end === -1 ? text.length : end + 2;
      push(out, 'comment', text.slice(i, stop));
      i = stop;
      inBlock = end === -1;
      continue;
    }
    const rest = text.slice(i);
    let m: RegExpExecArray | null;
    if (rest.startsWith('--') || rest.startsWith('#')) {
      push(out, 'comment', rest);
      break;
    }
    if (rest.startsWith('/*')) {
      inBlock = true;
      push(out, 'comment', '/*');
      i += 2;
      continue;
    }
    if ((m = /^'(?:[^']|'')*'?/.exec(rest)) && rest[0] === "'") {
      out.push(...secrets(m[0], 'string'));
      i += m[0].length;
      continue;
    }
    if ((m = /^"(?:[^"]|"")*"?/.exec(rest)) && rest[0] === '"') {
      push(out, null, m[0]);
      i += m[0].length;
      continue;
    }
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
  return { tokens: out, state: { inBlockComment: inBlock } };
}

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

function shell(text: string): Token[] {
  const out: Token[] = [];
  let i = 0;
  let wordStart = true;
  // The command word (first of the line, or after | ; && ||) reads as a function: `kubectl`, `sudo`, `docker`.
  let commandStart = true;
  while (i < text.length) {
    const rest = text.slice(i);
    let m: RegExpExecArray | null;
    if (rest[0] === '#' && wordStart) {
      push(out, 'comment', rest);
      break;
    }
    if ((m = /^'[^']*'?/.exec(rest)) && rest[0] === "'") {
      out.push(...secrets(m[0], 'string'));
      i += m[0].length;
      wordStart = false;
      continue;
    }
    if ((m = /^"(?:[^"\\]|\\.)*"?/.exec(rest)) && rest[0] === '"') {
      out.push(...secrets(m[0], 'string'));
      i += m[0].length;
      wordStart = false;
      continue;
    }
    if ((m = /^‹masked[^›]*›/.exec(rest))) {
      out.push({ cls: 'secret', text: m[0] });
      i += m[0].length;
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
  return out;
}

function yaml(text: string): Token[] {
  const out: Token[] = [];
  const comment = /(^|\s)#.*$/.exec(text);
  const body = comment ? text.slice(0, comment.index + comment[1].length) : text;
  const key = /^(\s*-?\s*)([A-Za-z0-9_.\-"[\]]+)(\s*[:=])/.exec(body);
  let rest = body;
  if (key) {
    push(out, null, key[1]);
    push(out, 'property', key[2]);
    push(out, 'punctuation', key[3]);
    rest = body.slice(key[0].length);
  }
  const value = rest;
  const trimmed = value.trim();
  if (/^(['"]).*\1$/.test(trimmed)) out.push(...secrets(value, 'string'));
  else if (/^-?\d+(?:\.\d+)?$/.test(trimmed)) push(out, 'number', value);
  else if (/^(?:true|false|null|yes|no)$/i.test(trimmed)) push(out, 'keyword', value);
  else out.push(...secrets(value, null));
  if (comment) push(out, 'comment', text.slice(comment.index + comment[1].length));
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
