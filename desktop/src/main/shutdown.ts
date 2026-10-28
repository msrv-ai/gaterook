export interface ShutdownStep {
  readonly name: string;
  readonly run: () => void | Promise<void>;
}

/** One cleanup sequence shared by ordinary quit and update installation. */
export class ShutdownSequence {
  private pending: Promise<void> | null = null;
  private finished = false;
  private readonly steps: readonly ShutdownStep[];
  private readonly report: (step: string, error: Error) => void;

  constructor(steps: readonly ShutdownStep[], report: (step: string, error: Error) => void) {
    this.steps = steps;
    this.report = report;
  }

  /** Every cleanup has been attempted, including after an earlier failure; another quit may now proceed. */
  get completed(): boolean { return this.finished; }

  run(): Promise<void> {
    // Defer the first step until pending is assigned: a synchronous callback may itself request quit.
    this.pending ??= Promise.resolve().then(() => this.runSteps());
    return this.pending;
  }

  private async runSteps(): Promise<void> {
    let failure: Error | null = null;
    try {
      for (const step of this.steps) {
        try {
          const pending = step.run();
          // Keep synchronous teardown together: closing an unlock prompt releases startup's promise.
          // The supervisor must enter shutdown before that promise can resume and spawn a core.
          if (pending) await pending;
        } catch (error) {
          const cause = error instanceof Error ? error : new Error(String(error));
          failure ??= cause;
          try { this.report(step.name, cause); } catch { /* Logging must not prevent remaining cleanup. */ }
        }
      }
      // Quit can finish after failed cleanup, but an installer must not replace files whose release failed.
      if (failure) throw failure;
    } finally {
      this.finished = true;
    }
  }
}
