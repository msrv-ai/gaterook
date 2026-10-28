import { ActionSegment } from '../core/api/approvals.api';

// Bound both scanning work and indentation expansion before rendering untrusted actions.
const MAX_INPUT_LENGTH = 256 * 1024;
const MAX_OUTPUT_LENGTH = 1024 * 1024;
const MAX_DEPTH = 64;
const MAX_TOKENS = 65_536;
const MAX_SEGMENTS = 16_384;

interface SourceRange {
  readonly start: number;
  readonly end: number;
}

interface Annotation extends SourceRange {
  readonly segment: ActionSegment;
}

/**
 * Adds display-only JSON whitespace without rewriting a token or server annotation.
 * The original segments remain the source of truth. Unsupported, incomplete, or
 * excessively large input returns null so callers can keep the original view.
 */
export function prettyJsonSegments(
  segments: readonly ActionSegment[],
): readonly ActionSegment[] | null {
  if (segments.length > MAX_SEGMENTS) return null;
  let inputLength = 0;
  const annotations: Annotation[] = [];
  for (const segment of segments) {
    const start = inputLength;
    inputLength += segment.v.length;
    if (inputLength > MAX_INPUT_LENGTH) return null;
    if (segment.t !== 'text') {
      if (!segment.v.length) return null;
      annotations.push({ start, end: inputLength, segment });
    }
  }

  const source = segments.map((segment) => segment.v).join('');
  const tokens = scanTokens(source);
  if (!tokens?.length || !'{['.includes(source[tokens[0].start])) return null;
  try {
    // Validate only: serializing a parsed value would lose number precision,
    // duplicate keys, key order, negative zero, and original escape spellings.
    JSON.parse(source);
  } catch {
    return null;
  }

  const result: ActionSegment[] = [];
  let textChunks: string[] = [];
  let outputLength = 0;
  let annotationIndex = 0;
  let depth = 0;

  function appendText(value: string): void {
    outputLength += value.length;
    if (value && outputLength <= MAX_OUTPUT_LENGTH) textChunks.push(value);
  }

  function flushText(): void {
    if (textChunks.length) result.push({ t: 'text', v: textChunks.join('') });
    textChunks = [];
  }

  function appendToken(token: SourceRange): boolean {
    let position = token.start;
    while (annotationIndex < annotations.length && annotations[annotationIndex].start < token.end) {
      const annotation = annotations[annotationIndex];
      // A mark must survive whole. Never discard marked whitespace or split
      // a visible escape across tokens, which could change its meaning/title.
      if (annotation.start < position || annotation.end > token.end) return false;
      appendText(source.slice(position, annotation.start));
      flushText();
      result.push(annotation.segment);
      outputLength += annotation.segment.v.length;
      position = annotation.end;
      annotationIndex++;
    }
    appendText(source.slice(position, token.end));
    return outputLength <= MAX_OUTPUT_LENGTH;
  }

  for (let index = 0; index < tokens.length; index++) {
    const token = tokens[index];
    const symbol = source[token.start];
    if (symbol === '}' || symbol === ']') {
      depth--;
      const previous = source[tokens[index - 1].start];
      if (previous !== '{' && previous !== '[') appendText('\n' + '  '.repeat(depth));
    }
    if (!appendToken(token)) return null;
    if (symbol === '{' || symbol === '[') {
      depth++;
      const next = source[tokens[index + 1].start];
      if (next !== '}' && next !== ']') appendText('\n' + '  '.repeat(depth));
    } else if (symbol === ',') {
      appendText('\n' + '  '.repeat(depth));
    } else if (symbol === ':') {
      appendText(' ');
    }
    if (outputLength > MAX_OUTPUT_LENGTH) return null;
  }
  if (annotationIndex !== annotations.length) return null;
  flushText();
  return result;
}

/** Locate source tokens before validation, limiting nesting without recursion. */
function scanTokens(source: string): SourceRange[] | null {
  const tokens: SourceRange[] = [];
  let position = 0;
  let depth = 0;
  while (position < source.length) {
    const char = source[position];
    if (isWhitespace(char)) {
      position++;
      continue;
    }
    const start = position++;
    if (char === '"') {
      let closed = false;
      while (position < source.length) {
        const next = source[position++];
        if (next === '\\') position++;
        else if (next === '"') {
          closed = true;
          break;
        }
      }
      if (!closed) return null;
    } else if ('{}[],:'.includes(char)) {
      if (char === '{' || char === '[') {
        if (++depth > MAX_DEPTH) return null;
      } else if (char === '}' || char === ']') {
        if (--depth < 0) return null;
      }
    } else {
      while (
        position < source.length &&
        !isWhitespace(source[position]) &&
        !'{}[],:"'.includes(source[position])
      ) {
        position++;
      }
    }
    if (tokens.length >= MAX_TOKENS) return null;
    tokens.push({ start, end: position });
  }
  return depth === 0 ? tokens : null;
}

function isWhitespace(char: string): boolean {
  return char === ' ' || char === '\t' || char === '\r' || char === '\n';
}
