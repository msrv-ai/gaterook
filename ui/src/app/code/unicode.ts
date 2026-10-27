import { ActionSegment } from '../core/api/approvals.api';

/**
 * Names for the `title` of `gw-tok-invisible` / `gw-tok-confusable` ("U+202E right-to-left override",
 * "U+043E Cyrillic small letter o"). Where the server segmented a text (approvals.ActionInspector) it decides what is
 * invisible or confusable and the UI only names it; `inspectPlain` below applies the same rules to text it did not.
 */
const NAMES: Record<number, string> = {
  0x0009: 'tab',
  0x000d: 'carriage return',
  0x0020: 'trailing space',
  0x00a0: 'no-break space',
  0x00ad: 'soft hyphen',
  0x061c: 'Arabic letter mark',
  0x115f: 'Hangul choseong filler',
  0x1160: 'Hangul jungseong filler',
  0x1680: 'Ogham space mark',
  0x180e: 'Mongolian vowel separator',
  0x2000: 'en quad',
  0x2001: 'em quad',
  0x2002: 'en space',
  0x2003: 'em space',
  0x2004: 'three-per-em space',
  0x2005: 'four-per-em space',
  0x2006: 'six-per-em space',
  0x2007: 'figure space',
  0x2008: 'punctuation space',
  0x2009: 'thin space',
  0x200a: 'hair space',
  0x200b: 'zero width space',
  0x200c: 'zero width non-joiner',
  0x200d: 'zero width joiner',
  0x200e: 'left-to-right mark',
  0x200f: 'right-to-left mark',
  0x2028: 'line separator',
  0x2029: 'paragraph separator',
  0x202a: 'left-to-right embedding',
  0x202b: 'right-to-left embedding',
  0x202c: 'pop directional formatting',
  0x202d: 'left-to-right override',
  0x202e: 'right-to-left override',
  0x202f: 'narrow no-break space',
  0x205f: 'medium mathematical space',
  0x2060: 'word joiner',
  0x2061: 'function application',
  0x2062: 'invisible times',
  0x2063: 'invisible separator',
  0x2064: 'invisible plus',
  0x2066: 'left-to-right isolate',
  0x2067: 'right-to-left isolate',
  0x2068: 'first strong isolate',
  0x2069: 'pop directional isolate',
  0x3000: 'ideographic space',
  0x3164: 'Hangul filler',
  0xfeff: 'zero width no-break space',
  0xffa0: 'halfwidth Hangul filler',
  0xe0001: 'language tag',
  0xe007f: 'cancel tag',
};

/** Latin look-alikes of other scripts: the letter is boxed as itself, the title says what it really is. */
const CONFUSABLE_SCRIPTS: readonly [number, number, string][] = [
  [0x0370, 0x03ff, 'Greek'],
  [0x0400, 0x04ff, 'Cyrillic'],
  [0x0500, 0x052f, 'Cyrillic'],
  [0x0530, 0x058f, 'Armenian'],
  [0xff00, 0xffef, 'Fullwidth'],
];

/** "U+202E right-to-left override" for an escape `‹U+202E›` (as the server writes it). */
export function invisibleTitle(escape: string): string {
  const m = /U\+([0-9A-Fa-f]{4,6})/.exec(escape);
  if (!m) return escape;
  const cp = parseInt(m[1], 16);
  return `U+${m[1].toUpperCase()} ${invisibleName(cp)}`;
}

function invisibleName(cp: number): string {
  const known = NAMES[cp];
  if (known) return known;
  // Tag characters spell ASCII nobody sees ("ASCII smuggling"): the title says which letter each one hides.
  if (cp >= 0xe0020 && cp <= 0xe007e) return `tag character ${cp === 0xe0020 ? 'space' : String.fromCodePoint(cp - 0xe0000)}`;
  const ch = String.fromCodePoint(cp);
  if (/\p{Cc}/u.test(ch)) return 'control character';
  if (/\p{Zs}/u.test(ch)) return 'space';
  if (/\p{Cf}/u.test(ch)) return 'format character';
  return 'invisible character';
}

/** "U+043E Cyrillic small letter o" (best effort: script and case; the code point is always right). */
export function confusableTitle(ch: string): string {
  const cp = ch.codePointAt(0) ?? 0;
  const hex = cp.toString(16).toUpperCase().padStart(4, '0');
  const script = CONFUSABLE_SCRIPTS.find(([from, to]) => cp >= from && cp <= to)?.[2] ?? 'Non-ASCII';
  const lower = ch.toLowerCase() === ch && ch.toUpperCase() !== ch;
  const latin = LATIN_LOOKALIKE[cp];
  return `U+${hex} ${script} ${lower ? 'small' : 'capital'} letter${latin ? ` ${latin}` : ''}`.trim();
}

/** The Latin letter a common confusable passes for (Cyrillic and Greek). */
const LATIN_LOOKALIKE: Record<number, string> = {
  0x0430: 'a', 0x0435: 'ie', 0x043e: 'o', 0x0440: 'er', 0x0441: 'es', 0x0443: 'u', 0x0445: 'ha', 0x0456: 'i',
  0x0410: 'A', 0x0412: 'VE', 0x0415: 'IE', 0x041a: 'KA', 0x041c: 'EM', 0x041d: 'EN', 0x041e: 'O', 0x0420: 'ER',
  0x0421: 'ES', 0x0422: 'TE', 0x0425: 'HA', 0x03bf: 'omicron', 0x039f: 'OMICRON', 0x03b1: 'alpha', 0x0391: 'ALPHA',
};

/** `‹U+202E›`, as `ActionInspector` writes an escape (`%04X`: at least four hex digits, six for a tag character). */
export function escapeOf(cp: number): string {
  return `‹U+${cp.toString(16).toUpperCase().padStart(4, '0')}›`;
}

const INVISIBLE_CATEGORY = /[\p{Cc}\p{Cf}\p{Zl}\p{Zp}]/u;
const SPACE_SEPARATOR = /\p{Zs}/u;
const LETTER = /\p{L}/u;

/**
 * Whether a person can't see `cp`: the exact mirror of the server's `ActionInspector.invisible()` — control, format,
 * line and paragraph separator characters (tab, bidi controls, zero-width, BOM, tag characters), every space but
 * U+0020 (no-break and narrow spaces), the soft hyphen, the invisible operators U+2060–2064 and the Hangul fillers
 * (letters by category, blank on screen). Only `\n` is a line break, never escaped.
 */
export function isInvisible(cp: number): boolean {
  if (cp === 0x0a) return false;
  const ch = String.fromCodePoint(cp);
  return (
    INVISIBLE_CATEGORY.test(ch) ||
    (SPACE_SEPARATOR.test(ch) && cp !== 0x20) ||
    cp === 0x00ad ||
    (cp >= 0x2060 && cp <= 0x2064) ||
    cp === 0xfeff ||
    cp === 0x115f ||
    cp === 0x1160 ||
    cp === 0x3164 ||
    cp === 0xffa0
  );
}

/** A non-ASCII letter (`Character.isLetter(cp) && cp > 0x7f` on the server): it can pass for a Latin one. */
export function isConfusable(cp: number): boolean {
  return cp > 0x7f && LETTER.test(String.fromCodePoint(cp));
}

/**
 * Segments a text the server didn't segment (a diff line of a client config, a request's one-line summary) with the
 * inspector's rules, so nothing hides there either: invisible characters and trailing spaces become `‹U+XXXX›`
 * escapes, every non-ASCII letter is a `confusable`, the rest is text. The server doesn't box a letter inside a string
 * literal; without the whole action the UI can't tell a literal, so it boxes every one — which can only add marks,
 * never hide one. `\r` right before `\n` is part of the line break, as on the server.
 */
export function inspectPlain(text: string): ActionSegment[] {
  const out: ActionSegment[] = [];
  let run = '';
  const flush = () => {
    if (run) out.push({ t: 'text', v: run });
    run = '';
  };
  const chars = Array.from(text);
  for (let i = 0; i < chars.length; i++) {
    const ch = chars[i];
    const cp = ch.codePointAt(0) ?? 0;
    const lineBreak = cp === 0x0a || (cp === 0x0d && chars[i + 1] === '\n');
    if (!lineBreak && (isInvisible(cp) || (cp === 0x20 && trailingSpace(chars, i)))) {
      flush();
      out.push({ t: 'invisible', v: escapeOf(cp) });
    } else if (!lineBreak && isConfusable(cp)) {
      flush();
      out.push({ t: 'confusable', v: ch });
    } else {
      run += ch;
    }
  }
  flush();
  return out;
}

/** A space is trailing when only spaces follow it before a line break or the end (the server's rule). */
function trailingSpace(chars: readonly string[], at: number): boolean {
  let j = at;
  while (j < chars.length && chars[j] === ' ') j++;
  return j >= chars.length || chars[j] === '\n' || chars[j] === '\r';
}
