// Self-approval through the GUI (ARCHITECTURE.md §9 "Self-approval through the GUI", DECISIONS 6): an agent with computer
// use, or UI automation of the same account, can click "Approve and run" or "Allow access" the instant the screen
// shows, and a deep link brings the window forward. The decision buttons in the UI ignore activation for 700 ms after
// they appear, after the window gets focus and after a deep link (ui/src/app/kit/activation-guard.ts). Main holds the
// same line one level lower, on the input events themselves: while a decision screen shows, clicks and Enter/Space
// that arrive within 700 ms of the window coming forward, of a deep link or of the screen appearing never reach the
// page. Main's own decision dialog ("Codex wants to use incident-prod") ignores an answer given that fast.

export const ACTIVATION_DELAY_MS = 700;

/** An input event as `before-mouse-event` / `before-input-event` report it. */
export interface GateInput {
  readonly kind: 'mouse' | 'key';
  /** mouseDown, mouseUp, mouseMove, …; keyDown, keyUp, char, rawKeyDown. */
  readonly type: string;
  /** The key, for keyboard events (`Enter`, ` `, `Space`). */
  readonly key?: string;
}

/** Events that can press a button: a click, or the keys that activate a focused one. */
export function activates(input: GateInput): boolean {
  if (input.kind === 'mouse') return input.type === 'mouseDown' || input.type === 'mouseUp' || input.type === 'contextMenu';
  const key = input.key ?? '';
  return (input.type === 'keyDown' || input.type === 'keyUp' || input.type === 'rawKeyDown' || input.type === 'char')
    && (key === 'Enter' || key === ' ' || key === 'Space' || key === 'Spacebar' || key === 'NumpadEnter');
}

/** Routes of the window that show decision buttons: the approval queue and a request in it. */
export function isDecisionRoute(url: string): boolean {
  let u: URL;
  try {
    u = new URL(url);
  } catch {
    return false;
  }
  return u.protocol === 'app:' && u.host === 'gw' && (u.pathname === '/approvals' || u.pathname.startsWith('/approvals/'));
}

export class ActivationGate {
  private readonly now: () => number;
  private armedAt: number;

  /** `now` is monotonic (performance.now): changing the system clock can't shorten the delay. */
  constructor(now: () => number = () => performance.now()) {
    this.now = now;
    this.armedAt = now();
  }

  /** The window came forward, a deep link arrived, a decision screen appeared: the delay starts again. */
  rearm(): void {
    this.armedAt = this.now();
  }

  /** Whether the delay has passed since the last rearm. */
  ready(): boolean {
    return this.now() - this.armedAt >= ACTIVATION_DELAY_MS;
  }

  /** Milliseconds left until {@link ready}, 0 once it is. */
  remaining(): number {
    return Math.max(0, ACTIVATION_DELAY_MS - (this.now() - this.armedAt));
  }

  /**
   * Whether an input event may reach the page. Only events that can press a button are held back, only while a
   * decision screen shows, and only during the delay: typing a comment or scrolling is never slowed down.
   */
  admits(input: GateInput, decisionScreen: boolean): boolean {
    return !decisionScreen || !activates(input) || this.ready();
  }
}

/** Whether an answer that came `elapsedMs` after a dialog appeared can be a person's. */
export function answeredByAPerson(elapsedMs: number): boolean {
  return elapsedMs >= ACTIVATION_DELAY_MS;
}
