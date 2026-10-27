// A server-sent events parser (the core's `GET /api/events`, ARCHITECTURE.md §6): text in, messages out, by the
// rules of the HTML standard — lines end in CR, LF or CRLF; `event`, `data` (joined with LF), `id`, `retry`; a line
// starting with `:` is a comment (the core's heartbeat); a blank line dispatches.

export interface SseMessage {
  readonly event: string;
  readonly data: string;
  readonly id: string | null;
}

export class SseParser {
  private buffer = '';
  private event = '';
  private data: string[] = [];
  private hasData = false;
  private pendingCr = false;
  /** The last `id:` seen: what a reconnect sends as Last-Event-ID. */
  lastEventId: string | null = null;
  private readonly onMessage: (m: SseMessage) => void;
  private readonly onRetry: (ms: number) => void;

  constructor(onMessage: (m: SseMessage) => void, onRetry: (ms: number) => void = () => undefined) {
    this.onMessage = onMessage;
    this.onRetry = onRetry;
  }

  push(chunk: string): void {
    let text = chunk;
    // A CRLF split across chunks: the LF belongs to the CR already handled.
    if (this.pendingCr && text.startsWith('\n')) text = text.slice(1);
    this.pendingCr = false;
    this.buffer += text;
    for (;;) {
      const m = /\r\n|\r|\n/.exec(this.buffer);
      if (!m) break;
      if (m[0] === '\r' && m.index === this.buffer.length - 1) {
        // Maybe the first half of a CRLF: take the line now, skip a following LF.
        this.pendingCr = true;
      }
      const line = this.buffer.slice(0, m.index);
      this.buffer = this.buffer.slice(m.index + m[0].length);
      this.line(line);
    }
    // A runaway line without an end is a broken stream, not a message to keep growing.
    if (this.buffer.length > 1024 * 1024) this.buffer = '';
  }

  private line(line: string): void {
    if (line === '') {
      this.dispatch();
      return;
    }
    if (line.startsWith(':')) return;
    const colon = line.indexOf(':');
    const field = colon < 0 ? line : line.slice(0, colon);
    let value = colon < 0 ? '' : line.slice(colon + 1);
    if (value.startsWith(' ')) value = value.slice(1);
    switch (field) {
      case 'event':
        this.event = value;
        break;
      case 'data':
        this.data.push(value);
        this.hasData = true;
        break;
      case 'id':
        if (!value.includes('\0')) this.lastEventId = value;
        break;
      case 'retry':
        if (/^[0-9]+$/.test(value)) this.onRetry(Number(value));
        break;
      default:
        break;
    }
  }

  private dispatch(): void {
    if (this.hasData) {
      this.onMessage({ event: this.event || 'message', data: this.data.join('\n'), id: this.lastEventId });
    }
    this.event = '';
    this.data = [];
    this.hasData = false;
  }
}
