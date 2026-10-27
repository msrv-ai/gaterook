import { ActionSegment } from '../core/api/approvals.api';
import { confusableTitle, invisibleTitle } from './unicode';
import { HighlightState, INITIAL_STATE, TokenClass, highlightLine } from './highlight';

/** One painted run of a code line: a syntax token, an invisible character's escape, or a look-alike letter. */
export interface CodePart {
  /** The CSS class: `gw-tok-keyword`… `gw-tok-invisible`, `gw-tok-confusable`, or none. */
  readonly cls: string | null;
  readonly text: string;
  readonly title: string | null;
}

export type CodeLine = readonly CodePart[];

/**
 * Cuts server segments (or plain text) into source lines — one source line = one `gw-code-block__line`, so a wrapped
 * line hangs under its own start — and colours the text runs. Invisible and confusable segments are kept as the
 * server sent them, with a `title` naming the character. The concatenated text of all parts, lines joined by `\n`,
 * is exactly the segments' text (copy copies what is seen).
 */
export function toLines(segments: readonly ActionSegment[], language: string | null): CodeLine[] {
  const raw: { t: ActionSegment['t']; v: string }[][] = [[]];
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
  let state: HighlightState = INITIAL_STATE;
  return raw.map((parts) => {
    const out: CodePart[] = [];
    for (const p of parts) {
      if (p.t === 'invisible') out.push({ cls: 'gw-tok-invisible', text: p.v, title: invisibleTitle(p.v) });
      else if (p.t === 'confusable') out.push({ cls: 'gw-tok-confusable', text: p.v, title: confusableTitle(p.v) });
      else {
        const h = highlightLine(p.v, language, state);
        state = h.state;
        for (const t of h.tokens) out.push({ cls: tokClass(t.cls), text: t.text, title: null });
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
