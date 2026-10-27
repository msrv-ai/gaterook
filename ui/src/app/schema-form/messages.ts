import { JsonValue } from '../core/api/types';
import { SchemaNode, exampleNumber, tagNoun } from './compile';

/**
 * The validation messages of the schema renderer: what is wrong and how to fix it, next to the field (DESIGN.md §10
 * "Errors"). The core validates again on save (networknt, the source of truth); these catch the obvious early.
 */

/** "Host" → "host", but "SSL mode" stays: a word in capitals is a name. */
export function lowerFirst(title: string): string {
  return /^[A-Z][a-z]/.test(title) ? title[0].toLowerCase() + title.slice(1) : title;
}

const num = (v: JsonValue | undefined): number | null => (typeof v === 'number' ? v : null);
const unitOf = (node: SchemaNode) => (node.unit ? ` ${node.unit}` : '');

export function requiredMessage(node: SchemaNode): string {
  if (node.kind === 'tags') return `Add at least one ${tagNoun(node)}.`;
  if (node.kind === 'choice-cards' || node.kind === 'segmented' || node.kind === 'select') return `Choose ${lowerFirst(node.title)}.`;
  return `${node.title} is required.`;
}

/** The first problem of a non-empty value, or `null`. Empty values are `required`'s business. */
export function valueProblem(node: SchemaNode, value: JsonValue): string | null {
  const s = node.schema;
  if (value === null || value === '') return null;
  // Metadata supplies suggestions, including a saved value absent from the latest list. Without a schema enum,
  // the field also accepts typed text before discovery and follows the schema's string constraints.
  const kind = node.kind === 'select' && node.optionsFrom && !node.options.length ? 'text' : node.kind;
  switch (kind) {
    case 'text':
    case 'textarea':
    case 'code': {
      if (typeof value !== 'string') return null;
      const max = num(s['maxLength']);
      const min = num(s['minLength']);
      if (max !== null && value.length > max) return `${node.title} is ${value.length} characters long; the limit is ${max}. Shorten it.`;
      if (min !== null && value.length < min) return `Use at least ${min} characters.`;
      const pattern = typeof s['pattern'] === 'string' ? s['pattern'] : null;
      if (pattern && !safeTest(pattern, value)) return `${value} is not a valid ${lowerFirst(node.title)}.`;
      return null;
    }
    case 'number': {
      const integer = s['type'] === 'integer' || (Array.isArray(s['type']) && s['type'].includes('integer'));
      const example = exampleNumber(node);
      const n = typeof value === 'number' ? value : Number.NaN;
      if (Number.isNaN(n)) return `${String(value)} is not a number. Enter ${integer ? 'a whole number' : 'a number'}, for example ${example}.`;
      if (integer && !Number.isInteger(n)) return `Enter a whole number, for example ${example}.`;
      const min = num(s['minimum']);
      const max = num(s['maximum']);
      if (min !== null && n < min) return `${n}${unitOf(node)} is below the minimum. Use ${min}${unitOf(node)} or more.`;
      if (max !== null && n > max) return `${n}${unitOf(node)} is above the maximum. Use ${max}${unitOf(node)} or less.`;
      return null;
    }
    case 'select':
    case 'choice-cards':
    case 'segmented':
      return node.options.some((o) => o.value === value) ? null : 'Choose one of the listed options.';
    case 'tags': {
      if (!Array.isArray(value)) return null;
      const noun = tagNoun(node);
      const maxItems = num(s['maxItems']);
      if (maxItems !== null && value.length > maxItems) return `Up to ${maxItems} entries. Remove ${value.length - maxItems}.`;
      if (s['uniqueItems'] === true) {
        const dup = value.find((v, i) => value.indexOf(v) !== i);
        if (dup !== undefined) return `${String(dup)} is listed twice. Remove one.`;
      }
      const items = s['items'];
      if (items && typeof items === 'object' && !Array.isArray(items)) {
        const pattern = typeof items['pattern'] === 'string' ? items['pattern'] : null;
        const maxLength = num(items['maxLength']);
        for (const item of value) {
          if (typeof item !== 'string') continue;
          if (maxLength !== null && item.length > maxLength) return `${item.slice(0, 40)}… is longer than ${maxLength} characters.`;
          if (pattern && !safeTest(pattern, item)) return `${item} is not a valid ${noun}.`;
        }
      }
      return null;
    }
    default:
      return null;
  }
}

/** A connector's pattern is data: a broken one must not break the form (the core checks it anyway). */
function safeTest(pattern: string, value: string): boolean {
  try {
    return new RegExp(pattern, 'u').test(value);
  } catch {
    return true;
  }
}

export function jsonProblem(error: unknown): string {
  const detail = error instanceof Error ? error.message.replace(/^JSON\.parse: /, '') : 'unexpected input';
  return `This is not valid JSON (${detail}). Check the brackets, commas and quotes.`;
}
