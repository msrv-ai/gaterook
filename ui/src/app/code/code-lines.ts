import { ActionSegment } from '../core/api/approvals.api';
import { confusableTitle, invisibleTitle } from './unicode';
import { HighlightState, TokenClass, highlightLine, highlightState } from './highlight';
import { SqlDialect } from './sql-lexer';

/** One painted run of a code line: a syntax token, an invisible character's escape, or a look-alike letter. */
export interface CodePart {
  /** The CSS class: `gw-tok-keyword`… `gw-tok-invisible`, `gw-tok-confusable`, or none. */
  readonly cls: string | null;
  readonly text: string;
  readonly title: string | null;
}

export type CodeLine = readonly CodePart[];

/** One run of a source line as the server cut it, and where it sits in the line's text as the server reads it. */
interface Piece {
  readonly seg: ActionSegment;
  readonly from: number;
  readonly to: number;
}

/**
 * Cuts server segments (or plain text) into source lines — one source line = one `gw-code-block__line`, so a wrapped
 * line hangs under its own start — and colours the text runs. Invisible and confusable segments are kept as the
 * server sent them, with a `title` naming the character. The concatenated text of all parts, lines joined by `\n`,
 * is exactly the segments' text (copy copies what is seen).
 *
 * The colours come from each whole line as the server reads it: an invisible character is the character it stands
 * for there (a carriage return ends a SQL line comment, a zero-width space sits inside a string), and what a line
 * leaves open (a string, a comment, a here-document) goes on in the next one. `dialect` is the SQL dialect of the
 * action's connection; without it a SQL text is coloured as every dialect GateRook reads agrees.
 */
export function toLines(segments: readonly ActionSegment[], language: string | null, dialect: SqlDialect | null = null): CodeLine[] {
  const raw: ActionSegment[][] = [[]];
  for (const s of segments) {
    if (s.t !== 'text') {
      raw[raw.length - 1].push(s);
      continue;
    }
    const pieces = s.v.split(/\r?\n/);
    pieces.forEach((p, i) => {
      if (i > 0) raw.push([]);
      if (p) raw[raw.length - 1].push({ t: 'text', v: p });
    });
  }
  let state: HighlightState = highlightState(dialect);
  return raw.map((parts) => {
    let source = '';
    const pieces: Piece[] = parts.map((seg) => {
      const from = source.length;
      source += seg.t === 'invisible' ? decodeEscape(seg.v) : seg.v;
      return { seg, from, to: source.length };
    });
    const h = highlightLine(source, language, state);
    state = h.state;
    const tokens: { readonly cls: TokenClass | null; readonly from: number; readonly to: number }[] = [];
    let at = 0;
    for (const t of h.tokens) {
      tokens.push({ cls: t.cls, from: at, to: at + t.text.length });
      at += t.text.length;
    }
    const out: CodePart[] = [];
    for (const p of pieces) {
      if (p.seg.t === 'invisible') out.push({ cls: 'gw-tok-invisible', text: p.seg.v, title: invisibleTitle(p.seg.v) });
      else if (p.seg.t === 'confusable') out.push({ cls: 'gw-tok-confusable', text: p.seg.v, title: confusableTitle(p.seg.v) });
      else {
        for (const t of tokens) {
          const from = Math.max(t.from, p.from);
          const to = Math.min(t.to, p.to);
          if (from < to) out.push({ cls: tokClass(t.cls), text: source.slice(from, to), title: null });
        }
      }
    }
    return out;
  });
}

export function textSegments(text: string): ActionSegment[] {
  return text ? [{ t: 'text', v: text }] : [];
}

function tokClass(cls: TokenClass | null): string | null {
  return cls ? `gw-tok-${cls}` : null;
}

/** `‹U+202E›` → the character it escapes (as ActionInspector writes it); U+FFFD when it isn't one. */
function decodeEscape(escape: string): string {
  const m = /^‹U\+([0-9A-F]{4,6})›$/.exec(escape);
  const cp = m ? parseInt(m[1], 16) : NaN;
  return Number.isInteger(cp) && cp <= 0x10ffff ? String.fromCodePoint(cp) : '�';
}
