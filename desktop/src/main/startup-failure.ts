// When GateRook can't start, the person is told before it closes: the app used to log the reason and exit, and a
// start at login or from the Dock ended without a word. The modal box is fine here: nothing runs yet that it could
// hold (the core and its clients start only after this point).

/** A start-up failure whose message is written for the person: what is wrong and what to do. */
export class StartupProblem extends Error {
  constructor(message: string, options?: { cause?: unknown }) {
    super(message, options);
    this.name = 'StartupProblem';
  }
}

export interface StartupFailureHost {
  log(line: string): void;
  /** Electron's `dialog.showErrorBox`: synchronous, works once the app is ready. */
  showErrorBox(title: string, content: string): void;
  exit(code: number): void;
  /** Where `log` writes, for the person to look at or send. */
  readonly logFile: string;
}

/** What the person reads: a {@link StartupProblem}'s own words, else where the details are. */
export function startupFailureText(error: unknown, logFile: string): { readonly title: string; readonly message: string } {
  const title = "GateRook couldn't start";
  if (error instanceof StartupProblem) return { title, message: `${error.message}\n\nThe details are in ${logFile}.` };
  return { title, message: `Something went wrong while GateRook started. The details are in ${logFile}.` };
}

/** Logs the failure, shows it to the person and closes the app. Never throws: the exit must happen. */
export function reportStartupFailure(error: unknown, host: StartupFailureHost): void {
  const e = error instanceof Error ? error : new Error(String(error));
  host.log(`could not start: ${e.stack ?? e.message}`);
  try {
    const { title, message } = startupFailureText(error, host.logFile);
    host.showErrorBox(title, message);
  } catch (shown) {
    host.log(`could not show why: ${shown instanceof Error ? shown.message : String(shown)}`);
  }
  host.exit(1);
}
