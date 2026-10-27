import { SseMessage, SseParser } from './sse-parser';

function parse(chunks: readonly string[]): { messages: SseMessage[]; retries: number[]; parser: SseParser } {
  const messages: SseMessage[] = [];
  const retries: number[] = [];
  const parser = new SseParser((m) => messages.push(m), (ms) => retries.push(ms));
  for (const c of chunks) parser.push(c);
  return { messages, retries, parser };
}

describe('SseParser (WHATWG event stream interpretation)', () => {
  it('dispatches an event on the blank line, with its type, id and data', () => {
    const { messages, parser } = parse(['id: 41\nevent: approval.requested\ndata: {"id":"ap_1"}\n\n']);
    expect(messages).toEqual([{ id: '41', event: 'approval.requested', data: '{"id":"ap_1"}' }]);
    expect(parser.lastEventId).toBe('41');
  });

  it('defaults the type to message and joins data lines with \\n', () => {
    const { messages } = parse(['data: a\ndata: b\n\n']);
    expect(messages).toEqual([{ id: null, event: 'message', data: 'a\nb' }]);
  });

  it('ignores comments (heartbeats) and the stream-open line', () => {
    const { messages } = parse([': stream open\n\n: heartbeat\n\ndata: x\n\n']);
    expect(messages.map((m) => m.data)).toEqual(['x']);
  });

  it('takes \\r\\n, \\r and \\n as line ends, also split across chunks', () => {
    const { messages } = parse(['data: one\r', '\n\r\ndata: two\r\rdata: three\n', '\n']);
    expect(messages.map((m) => m.data)).toEqual(['one', 'two', 'three']);
  });

  it('assembles an event from arbitrary chunk boundaries', () => {
    const text = 'id: 7\nevent: tunnel.state\ndata: {"state":"on"}\n\n';
    const { messages } = parse(text.split(''));
    expect(messages).toEqual([{ id: '7', event: 'tunnel.state', data: '{"state":"on"}' }]);
  });

  it('removes one leading space only, and keeps a field without a colon as empty', () => {
    const { messages } = parse(['data:  two spaces\ndata\n\n']);
    expect(messages[0].data).toBe(' two spaces\n');
  });

  it('reports retry: only for digits', () => {
    const { retries } = parse(['retry: 2000\nretry: soon\n\n']);
    expect(retries).toEqual([2000]);
  });

  it('keeps the last id for Last-Event-ID, ignores ids with NUL, and does not dispatch without data', () => {
    const { messages, parser } = parse(['id: 5\n\nid: bad\u0000id\ndata: x\n\n']);
    expect(messages).toEqual([{ id: '5', event: 'message', data: 'x' }]);
    expect(parser.lastEventId).toBe('5');
  });

  it('resets the type between events', () => {
    const { messages } = parse(['event: a\ndata: 1\n\ndata: 2\n\n']);
    expect(messages.map((m) => m.event)).toEqual(['a', 'message']);
  });
});
