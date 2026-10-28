import { ActionSegment } from '../core/api/approvals.api';
import { textSegments } from './code-lines';
import { prettyJsonSegments } from './pretty-json';

const format = (source: string) => prettyJsonSegments(textSegments(source));
const text = (segments: readonly ActionSegment[] | null) =>
  segments?.map((segment) => segment.v).join('');

describe('display-only JSON formatting', () => {
  it('indents nested MongoDB actions with two spaces', () => {
    expect(
      text(
        format('{"update":"orders","updates":[{"q":{"id":42},"u":{"$set":{"status":"done"}}}]}'),
      ),
    ).toBe(
      '{\n' +
        '  "update": "orders",\n' +
        '  "updates": [\n' +
        '    {\n' +
        '      "q": {\n' +
        '        "id": 42\n' +
        '      },\n' +
        '      "u": {\n' +
        '        "$set": {\n' +
        '          "status": "done"\n' +
        '        }\n' +
        '      }\n' +
        '    }\n' +
        '  ]\n' +
        '}',
    );
  });

  it('handles root arrays, empty containers, and scalar members', () => {
    expect(text(format('[{},[],true,false,null,"value",0]'))).toBe(
      '[\n  {},\n  [],\n  true,\n  false,\n  null,\n  "value",\n  0\n]',
    );
    expect(text(format(' { } '))).toBe('{}');
    expect(text(format('[ ]'))).toBe('[]');
  });

  it('preserves large integers, negative zero, fractions, and exponent spellings', () => {
    const numbers = [
      '900719925474099312345678901234567890',
      '-0',
      '1.2300',
      '1E-007',
      '1e+300',
      '1e400',
    ];
    expect(text(format(`[${numbers.join(',')}]`))).toBe(`[\n  ${numbers.join(',\n  ')}\n]`);
  });

  it('preserves duplicate keys, numeric key order, and special property names', () => {
    expect(text(format('{"10":1,"2":2,"x":3,"x":4,"__proto__":{"safe":true}}'))).toBe(
      '{\n  "10": 1,\n  "2": 2,\n  "x": 3,\n  "x": 4,\n  "__proto__": {\n    "safe": true\n  }\n}',
    );
  });

  it('preserves every string escape and treats punctuation inside strings as data', () => {
    const source = String.raw`{"\u0061":"\/\\\"\n\r\t\b\f","punctuation":"[{,:}]","space":"  keep  "}`;
    expect(text(format(source))).toBe(
      '{\n' +
        String.raw`  "\u0061": "\/\\\"\n\r\t\b\f",` +
        '\n  "punctuation": "[{,:}]",\n  "space": "  keep  "\n}',
    );
  });

  it('does not mutate the original segments or their CRLF whitespace', () => {
    const source = '\r\n{\r\n\t"value" : 1, "items" : [ ]\r\n}\r\n';
    const segments = Object.freeze([Object.freeze({ t: 'text' as const, v: source })]);
    expect(text(prettyJsonSegments(segments))).toBe('{\n  "value": 1,\n  "items": []\n}');
    expect(segments).toEqual([{ t: 'text', v: source }]);
    expect(text(prettyJsonSegments(format(source)!))).toBe(text(format(source)));
  });

  it('preserves exact invisible and confusable segments inside strings', () => {
    const invisible: ActionSegment = Object.freeze({ t: 'invisible', v: '‹U+202E›' });
    const confusable: ActionSegment = Object.freeze({ t: 'confusable', v: 'о' });
    const segments: readonly ActionSegment[] = Object.freeze([
      { t: 'text', v: '{"name":"' },
      invisible,
      confusable,
      { t: 'text', v: 'rders","masked":"‹masked›"}' },
    ]);
    const formatted = prettyJsonSegments(segments)!;
    expect(text(formatted)).toBe('{\n  "name": "‹U+202E›оrders",\n  "masked": "‹masked›"\n}');
    expect(formatted.filter((segment) => segment.t !== 'text')).toEqual([invisible, confusable]);
    expect(formatted).toContain(invisible);
    expect(formatted).toContain(confusable);
    expect(text(segments)).toBe('{"name":"‹U+202E›оrders","masked":"‹masked›"}');
  });

  it('can format tokens split between arbitrary text segment boundaries', () => {
    const source = String.raw`{"escaped":"a\"b","n":9007199254740993}`;
    const segments: ActionSegment[] = Array.from(source, (v) => ({ t: 'text', v }));
    expect(text(prettyJsonSegments(segments))).toBe(text(format(source)));
  });

  it('keeps annotated whitespace inside a string instead of treating it as layout', () => {
    const segments: ActionSegment[] = [
      { t: 'text', v: '{"name":"a' },
      { t: 'invisible', v: ' ' },
      { t: 'text', v: 'b"}' },
    ];
    const formatted = prettyJsonSegments(segments)!;
    expect(text(formatted)).toBe('{\n  "name": "a b"\n}');
    expect(formatted).toContain(segments[1]);
  });

  it('falls back when formatting would discard or split a server annotation', () => {
    const samples: ActionSegment[][] = [
      [
        { t: 'invisible', v: ' ' },
        { t: 'text', v: '{}' },
      ],
      [
        { t: 'text', v: '{}' },
        { t: 'invisible', v: '\n' },
      ],
      [
        { t: 'text', v: '{' },
        { t: 'invisible', v: '\t' },
        { t: 'text', v: '"a":1}' },
      ],
      [
        { t: 'confusable', v: '{"a"' },
        { t: 'text', v: ':1}' },
      ],
      [
        { t: 'text', v: '{"a":1}' },
        { t: 'invisible', v: '' },
      ],
      [
        { t: 'text', v: '{' },
        { t: 'invisible', v: '‹TAB›' },
        { t: 'text', v: '"a":1}' },
      ],
    ];
    for (const segments of samples) expect(prettyJsonSegments(segments)).toBeNull();
  });

  it('rejects scalars, malformed, truncated, and non-JSON inputs', () => {
    const invalid = [
      '',
      ' ',
      'null',
      'true',
      '42',
      '"text"',
      'UPDATE orders SET done = true;',
      '{',
      '{"a":',
      '{"a":1,}',
      '[1,]',
      '[1 2]',
      '{a:1}',
      '{"a":01}',
      '{"a":NaN}',
      '{"a":undefined}',
      '{"a":1}{}',
      '[}',
      '/* comment */ {}',
      '{"a":"unfinished}',
      '{"a":"trailing\\',
      '{"a":"raw\nline"}',
      String.raw`{"a":"\x20"}`,
      '\uFEFF{}',
      '{"a":1}\u00a0',
    ];
    for (const source of invalid) expect(format(source), source).toBeNull();
  });

  it('bounds input size and segment counts', () => {
    expect(format('{"value":"' + 'x'.repeat(256 * 1024) + '"}')).toBeNull();
    const segments: ActionSegment[] = Array.from({ length: 16_384 }, () => ({ t: 'text', v: '' }));
    segments.push({ t: 'text', v: '{}' });
    expect(prettyJsonSegments(segments)).toBeNull();
  });

  it('bounds nesting before validation while allowing brackets inside strings', () => {
    expect(format('['.repeat(65) + '0' + ']'.repeat(65))).toBeNull();
    expect(format('['.repeat(64) + '0' + ']'.repeat(64))).not.toBeNull();
    const source = '{"value":"' + '['.repeat(100) + '"}';
    expect(text(format(source))).toBe('{\n  "value": "' + '['.repeat(100) + '"\n}');
  });

  it('bounds token count and indentation expansion independently of input size', () => {
    expect(format('[' + Array.from({ length: 32_768 }, () => '0').join(',') + ']')).toBeNull();
    const source =
      '['.repeat(64) + Array.from({ length: 9_000 }, () => '0').join(',') + ']'.repeat(64);
    expect(format(source)).toBeNull();
  });
});
