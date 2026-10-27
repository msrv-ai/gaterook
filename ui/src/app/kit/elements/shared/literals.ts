/** A run of text, or a literal (tool, connection, slug) the kit sets in `gw-code` / mono. */
export interface TextPart {
  readonly text: string;
  readonly code: boolean;
}

/**
 * Splits a sentence of the core ("sql_query reads dev-db") into text and the literals it names, so the template can
 * set the literals in code without parsing markup: `literalParts("sql_query reads dev-db", ["sql_query", "dev-db"])`.
 * Longer literals win where they overlap; matches are whole words of [A-Za-z0-9_.-].
 */
export function literalParts(text: string, literals: readonly (string | null | undefined)[]): TextPart[] {
  const names = [...new Set(literals.filter((l): l is string => !!l))].sort((a, b) => b.length - a.length);
  if (!names.length) return [{ text, code: false }];
  const escaped = names.map((n) => n.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'));
  const re = new RegExp(`(?<![A-Za-z0-9_.-])(${escaped.join('|')})(?![A-Za-z0-9_-]|\\.[A-Za-z0-9])`, 'g');
  const parts: TextPart[] = [];
  let last = 0;
  for (const m of text.matchAll(re)) {
    const at = m.index ?? 0;
    if (at > last) parts.push({ text: text.slice(last, at), code: false });
    parts.push({ text: m[0], code: true });
    last = at + m[0].length;
  }
  if (last < text.length) parts.push({ text: text.slice(last), code: false });
  return parts;
}

/**
 * A value of the core in which the literals are not named (an impact fact: "deployment/api in app-dev",
 * "registry.acme.dev/api:1.42.0, unchanged"): words that can only be identifiers — a path, a host, a version, a
 * snake_case or kebab-case name, an address — are set in code, prose stays prose. A value that is one lowercase word
 * ("orders") is a name too. Never changes the text: the parts concatenate to it.
 */
export function literalWords(text: string): TextPart[] {
  if (/^[a-z][a-z0-9_]*$/.test(text)) return [{ text, code: true }];
  const parts: TextPart[] = [];
  const push = (t: string, code: boolean): void => {
    const prev = parts.at(-1);
    if (prev && prev.code === code && !code) parts[parts.length - 1] = { text: prev.text + t, code };
    else parts.push({ text: t, code });
  };
  // Words are runs of non-space; trailing punctuation of prose (",", ";", ")") stays outside the literal.
  for (const m of text.matchAll(/(\s+)|([^\s]+?)([,;:)]*)(?=\s|$)/g)) {
    if (m[1]) {
      push(m[1], false);
      continue;
    }
    const word = m[2] ?? '';
    if (LITERAL_WORD.test(word) && !PROSE_HYPHEN.test(word)) push(word, true);
    else push(word, false);
    if (m[3]) push(m[3], false);
  }
  return parts;
}

/**
 * Whether one word (without the punctuation that ends a sentence) can only be an identifier — the test
 * {@link literalWords} applies to each word, for a page that marks words of a sentence it assembles itself.
 */
export function isLiteralWord(word: string): boolean {
  return LITERAL_WORD.test(word) && !PROSE_HYPHEN.test(word);
}

/** A word only an identifier has: inner `/ _ : .` (not a decimal number) or a hyphen between letters or digits. */
/** English compounds that look like kebab-case names ("read-only", "built-in", "short-lived"). */
const PROSE_HYPHEN = /^(?:read|write|built|one|two|short|long|up|non|re|self|well|opt|pre|post|co|multi|all|per)-[a-z]+$/i;

const LITERAL_WORD = /^(?:[A-Za-z0-9]*[A-Za-z][A-Za-z0-9]*(?:[-_./:@][A-Za-z0-9]+)+|[a-z0-9]+(?:_[a-z0-9]+)+|\d{1,3}(?:\.\d{1,3}){3}(?::\d+)?)$/;
