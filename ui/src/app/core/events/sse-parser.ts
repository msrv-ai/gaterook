/** One server-sent event (WHATWG HTML §9.2 "event stream interpretation"). */
export interface SseMessage {
  readonly id: string | null;
  readonly event: string;
  readonly data: string;
}

/**
 * An incremental parser of `text/event-stream`: feed it decoded chunks as they arrive, it calls `onMessage` for every
 * complete event and `onRetry` for a `retry:` field. Comments (`: heartbeat`) are ignored; `\r\n`, `\r` and `\n`
 * all end a line; a missing `event:` means `message`; `data:` lines join with `\n`; an `id` containing NUL is
 * ignored, as the spec says.
 */
export class SseParser {
  private buffer = '';
  private data: string[] = [];
  private event = '';
  private id: string | null = null;
  private sawData = false;
  /** The last event id the stream set: what a reconnect sends as `Last-Event-ID`. */
  lastEventId: string | null = null;

  constructor(
    private readonly onMessage: (message: SseMessage) => void,
    private readonly onRetry: (ms: number) => void = () => undefined,
  ) {}

  push(chunk: string): void {
    this.buffer += chunk;
    for (;;) {
      const match = /\r\n|\r|\n/.exec(this.buffer);
      if (!match) break;
      // A lone \r at the very end may be the first half of \r\n: wait for the next chunk.
      if (match[0] === '\r' && match.index === this.buffer.length - 1) break;
      const line = this.buffer.slice(0, match.index);
      this.buffer = this.buffer.slice(match.index + match[0].length);
      this.line(line);
    }
  }

  private line(line: string): void {
    if (line === '') {
      this.dispatch();
      return;
    }
    if (line.startsWith(':')) return;
    const colon = line.indexOf(':');
    const field = colon === -1 ? line : line.slice(0, colon);
    let value = colon === -1 ? '' : line.slice(colon + 1);
    if (value.startsWith(' ')) value = value.slice(1);
    switch (field) {
      case 'data':
        this.data.push(value);
        this.sawData = true;
        break;
      case 'event':
        this.event = value;
        break;
      case 'id':
        if (!value.includes('\u0000')) this.id = value;
        break;
      case 'retry':
        if (/^\d+$/.test(value)) this.onRetry(Number(value));
        break;
      default:
        break;
    }
  }

  private dispatch(): void {
    if (this.id !== null) this.lastEventId = this.id;
    if (this.sawData) {
      this.onMessage({ id: this.lastEventId, event: this.event || 'message', data: this.data.join('\n') });
    }
    this.data = [];
    this.event = '';
    this.id = null;
    this.sawData = false;
  }
}
