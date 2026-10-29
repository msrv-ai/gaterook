// The core as main's child process (ARCHITECTURE.md §7.1 "Starting the core", A20): spawn, bootstrap line on stdin (kept
// open — EOF is how the core learns the app is gone), progress and ready lines on stdout turned into the status
// screen's DesktopCoreState, the ready line awaited 60 s, automatic restarts after a crash (3, with backoff, then the
// "stopped" screen), a clean stop on quit. Electron-free, so the unit tests drive it with a fake core.
import { spawn as nodeSpawn, type ChildProcess } from 'node:child_process';
import { closeSync, openSync, readSync, fstatSync } from 'node:fs';
import { join } from 'node:path';
import { createInterface } from 'node:readline';
import type { CoreLock, DesktopCoreState } from '../shared/contract.ts';
import { bootstrapLine, newLaunch, type Launch } from './bootstrap.ts';
import { failedState, parseCoreLine, readyState, StartProgress, type CoreLine, type StartMeta } from './core-lines.ts';
import type { CoreEndpoint } from './proxy.ts';

/** The core main started, once it said ready (locked or not). */
export interface ReadyCore extends CoreEndpoint {
  readonly publicPort: number;
  readonly pid: number | null;
  readonly locked: CoreLock | null;
  readonly version: string;
  readonly runtime: 'native' | 'jvm';
  readonly firstRun: boolean;
}

export interface CoreLaunchSpec {
  readonly file: string;
  readonly args: readonly string[];
  readonly env: Record<string, string>;
}

export interface SupervisorOptions {
  /** The bundled core's version (the app's): the status screen's meta line before the core answers. */
  readonly version: string;
  readonly home: string;
  /** desktop.json, read at every start: "Use another port" changes it between two starts. */
  readonly ports: () => { readonly port: number; readonly publicPort: number };
  readonly command: () => CoreLaunchSpec;
  /** The OS-protection file key of this start and the bootstrap's `protection`. */
  readonly fileKey: () => Promise<{ readonly fileKey: Buffer | null; readonly protection: string }>;
  readonly log: (line: string) => void;
  /** Copies of the core's stderr lines (development: the terminal). */
  readonly stderrLine?: (line: string) => void;
  readonly now?: () => number;
  readonly monotonic?: () => number;
  readonly readyTimeoutMs?: number;
  /** After stdin EOF the core halts itself within 20 s; main waits a little longer before it kills. */
  readonly stopTimeoutMs?: number;
  readonly restartDelaysMs?: readonly number[];
  /** A core that ran this long after ready starts the crash count over. */
  readonly stableAfterMs?: number;
  readonly spawn?: typeof nodeSpawn;
  readonly launch?: () => Launch;
}

type Listener<T> = (value: T) => void;

/** One spawned core. */
interface Run {
  readonly id: number;
  readonly child: ChildProcess;
  readonly launch: Launch;
  readonly meta: StartMeta;
  readonly progress: StartProgress;
  readonly spawnedAt: number;
  readonly stderr: string[];
  readonly exited: Promise<{ code: number | null; signal: NodeJS.Signals | null }>;
  ready: Extract<CoreLine, { kind: 'ready' }> | null;
  readyAt: number | null;
  failure: Extract<CoreLine, { kind: 'failed' }> | null;
  timedOut: boolean;
  spawnError: Error | null;
  stopping: boolean;
  readyTimer: NodeJS.Timeout | null;
}

const STDERR_LINES = 200;
const LOG_TAIL_LINES = 8;

export class CoreSupervisor {
  private readonly o: Required<Omit<SupervisorOptions, 'stderrLine'>> & Pick<SupervisorOptions, 'stderrLine'>;
  private run: Run | null = null;
  private runs = 0;
  private current: DesktopCoreState;
  private lastStarting: DesktopCoreState | null = null;
  private crashes = 0;
  private restartTimer: NodeJS.Timeout | null = null;
  private startPromise: Promise<void> | null = null;
  private shuttingDown = false;
  private paused = false;
  private readonly stateListeners: Listener<DesktopCoreState>[] = [];
  private readonly readyListeners: Listener<ReadyCore>[] = [];
  private readonly downListeners: Listener<void>[] = [];

  constructor(options: SupervisorOptions) {
    this.o = {
      now: Date.now,
      monotonic: () => performance.now(),
      readyTimeoutMs: 60_000,
      stopTimeoutMs: 25_000,
      restartDelaysMs: [1000, 3000, 9000],
      stableAfterMs: 60_000,
      spawn: nodeSpawn,
      launch: () => newLaunch(),
      ...options,
    };
    const ports = this.o.ports();
    this.current = { kind: 'starting', steps: [], port: ports.port, publicPort: ports.publicPort, version: options.version };
  }

  onState(cb: Listener<DesktopCoreState>): void {
    this.stateListeners.push(cb);
  }

  /** The core said ready (also locked): main may proxy and listen to its events. */
  onReady(cb: Listener<ReadyCore>): void {
    this.readyListeners.push(cb);
  }

  /** The ready core went away (exit, stop, restart): stop proxying and listening. */
  onDown(cb: Listener<void>): void {
    this.downListeners.push(cb);
  }

  state(): DesktopCoreState {
    return this.current;
  }

  /** What a renderer that subscribes now should see, oldest first: the steps of the last start, then the state. */
  replay(): DesktopCoreState[] {
    if (this.current.kind === 'starting' || !this.lastStarting) return [this.current];
    return [this.lastStarting, this.current];
  }

  /** The running, ready core, or null. */
  endpoint(): ReadyCore | null {
    const run = this.run;
    if (!run || !run.ready || run.stopping || run.child.exitCode !== null || run.child.signalCode !== null) return null;
    const r = run.ready;
    return {
      port: r.ports.local, publicPort: r.ports.public, launchSecret: run.launch.launchSecret,
      instanceNonce: run.launch.instanceNonce, pid: r.pid ?? run.child.pid ?? null, locked: r.locked, version: r.version,
      runtime: r.runtime, firstRun: r.firstRun,
    };
  }

  /** The core's process id, while one runs. */
  pid(): number | null {
    return this.run?.child.pid ?? null;
  }

  /**
   * The running core of this launch said it isn't locked any more: Restore or Create of the master key unlock it
   * without a restart (ARCHITECTURE.md §7.1 "clears the locked state without restarting"). The ready state follows — the screen, the
   * tray and a page that subscribes later see it — and the core is returned, for what main skipped while it was locked.
   * Null when there is nothing to change: another launch, not ready, not locked, going away.
   */
  unlocked(instanceNonce: string): ReadyCore | null {
    const run = this.run;
    if (!run || !run.ready || run.ready.locked === null || run.launch.instanceNonce !== instanceNonce || run.stopping
        || this.hasExited(run)) {
      return null;
    }
    run.ready = { ...run.ready, locked: null };
    this.o.log(`core #${run.id} unlocked`);
    this.emit(readyState(run.ready));
    return this.endpoint();
  }

  /** Starts a core unless one runs or is starting. */
  start(): Promise<void> {
    if (this.shuttingDown || this.paused) return Promise.resolve();
    if (this.run && !this.hasExited(this.run)) return Promise.resolve();
    this.startPromise ??= this.spawnRun().finally(() => (this.startPromise = null));
    return this.startPromise;
  }

  /** Restart core (the status screen, "Use another port", core.restart-required): a fresh start, crash count reset. */
  async restart(): Promise<void> {
    if (this.shuttingDown || this.paused) return;
    this.cancelRestart();
    await this.startPromise;
    // The screen leaves `ready` at once: while the old core stops, nothing answers for it.
    const ports = this.o.ports();
    this.emit({ kind: 'starting', steps: [], port: ports.port, publicPort: ports.publicPort, version: this.o.version });
    await this.stopRun();
    this.crashes = 0;
    await this.start();
  }

  /** Stops the core for good (quit): stdin EOF, then a kill if it doesn't go. */
  async stop(): Promise<void> {
    this.shuttingDown = true;
    this.cancelRestart();
    await this.startPromise;
    await this.stopRun();
  }

  /** Stop all core writes during a full backup without enabling automatic restarts. */
  async pause(): Promise<void> {
    this.paused = true;
    this.cancelRestart();
    await this.startPromise;
    await this.stopRun();
  }

  async resume(start = true): Promise<void> {
    this.paused = false;
    this.crashes = 0;
    if (start) await this.start();
  }

  private hasExited(run: Run): boolean {
    return run.child.exitCode !== null || run.child.signalCode !== null || run.spawnError !== null;
  }

  private cancelRestart(): void {
    if (this.restartTimer) clearTimeout(this.restartTimer);
    this.restartTimer = null;
  }

  private emit(state: DesktopCoreState): void {
    if (state.kind === 'starting') this.lastStarting = state;
    this.current = state;
    for (const cb of this.stateListeners) {
      try {
        cb(state);
      } catch (e) {
        this.o.log(`core state listener failed: ${(e as Error).message}`);
      }
    }
  }

  private async spawnRun(): Promise<void> {
    const ports = this.o.ports();
    const meta: StartMeta = { port: ports.port, publicPort: ports.publicPort, version: this.o.version };
    let key: { fileKey: Buffer | null; protection: string };
    try {
      key = await this.o.fileKey();
    } catch (e) {
      this.o.log(`could not prepare the OS-protection key: ${(e as Error).message}`);
      // A provider failure is never permission to start without a key. Explicitly disabled or unavailable
      // protection is represented by a successful plan; errors must leave both the core and its data untouched.
      if (!this.shuttingDown) this.emit({ kind: 'failed', reason: 'STARTUP_FAILED',
        detail: 'The encryption key could not be prepared. Check the native unlock helper and restart GateRook.',
        portOwner: null, log: null, ...meta });
      return;
    }
    if (this.shuttingDown || this.paused) {
      key.fileKey?.fill(0);
      return;
    }
    const spec = this.o.command();
    const launch = this.o.launch();
    const child = this.o.spawn(spec.file, [...spec.args], {
      env: spec.env,
      stdio: ['pipe', 'pipe', 'pipe'],
      windowsHide: true,
    });
    // The last protocol lines (a `ready:false` right before exit 2) may still be in the pipe when 'exit' comes: the
    // run ends once stdout is read to its end too (bounded, in case a grandchild kept the pipe open).
    const stdoutRead = new Promise<void>((resolve) => {
      if (!child.stdout) resolve();
      else child.stdout.once('close', () => resolve());
    });
    const exited = new Promise<{ code: number | null; signal: NodeJS.Signals | null }>((resolve) => {
      child.once('exit', (code, signal) => {
        void Promise.race([stdoutRead, new Promise((r) => setTimeout(r, 2000).unref())]).then(() => resolve({ code, signal }));
      });
      child.once('error', () => {
        // A process that never started emits no exit.
        if (child.pid === undefined) resolve({ code: null, signal: null });
      });
    });
    const run: Run = {
      id: ++this.runs, child, launch, meta, progress: new StartProgress(meta, this.o.monotonic()),
      spawnedAt: this.o.now(), stderr: [], exited, ready: null, readyAt: null, failure: null, timedOut: false,
      spawnError: null, stopping: false, readyTimer: null,
    };
    this.run = run;
    this.o.log(`core #${run.id} starting: ${spec.file} on 127.0.0.1:${meta.port} (public ${meta.publicPort}), `
      + `protection ${key.protection}`);
    this.emit(run.progress.state());

    child.on('error', (e) => {
      run.spawnError = e;
      this.o.log(`core #${run.id} could not start: ${e.message}`);
      key.fileKey?.fill(0);
    });
    child.stdin?.on('error', () => undefined); // EPIPE when the core is already gone
    if (child.pid !== undefined) {
      try {
        child.stdin?.write(bootstrapLine({ ...launch, fileKey: key.fileKey, protection: key.protection, port: meta.port,
          publicPort: meta.publicPort }));
      } catch (e) {
        this.o.log(`core #${run.id}: invalid bootstrap (${(e as Error).message})`);
        child.stdin?.end();
      } finally {
        key.fileKey?.fill(0);
      }
    }
    if (child.stdout) {
      createInterface({ input: child.stdout, crlfDelay: Infinity }).on('line', (line) => this.onLine(run, line));
    }
    if (child.stderr) {
      createInterface({ input: child.stderr, crlfDelay: Infinity }).on('line', (line) => {
        run.stderr.push(line.slice(0, 2000));
        if (run.stderr.length > STDERR_LINES) run.stderr.shift();
        this.o.stderrLine?.(line);
      });
    }
    run.readyTimer = setTimeout(() => this.onReadyTimeout(run), this.o.readyTimeoutMs);
    run.readyTimer.unref?.();
    void exited.then((exit) => this.onExit(run, exit.code, exit.signal));
  }

  private onLine(run: Run, text: string): void {
    // A run being stopped (restart, quit) says nothing more to the screen.
    if (run !== this.run || run.stopping || text.length > 64 * 1024) return;
    const line = parseCoreLine(text);
    if (!line) {
      this.o.log(`core #${run.id}: not a protocol line on stdout (${text.length} characters)`);
      return;
    }
    const at = this.o.monotonic();
    switch (line.kind) {
      case 'progress':
        if (!run.ready && !run.failure && !run.timedOut) this.emit(run.progress.progress(line, at));
        break;
      case 'ready':
        if (run.ready || run.failure || run.timedOut) return;
        run.progress.firstLine(at);
        run.ready = line;
        run.readyAt = this.o.now();
        this.clearReadyTimer(run);
        this.o.log(`core #${run.id} ready on 127.0.0.1:${line.ports.local} (${line.runtime} ${line.version}`
          + `${line.locked ? `, locked: ${line.locked}` : ''}${line.firstRun ? ', first run' : ''})`);
        this.emit(readyState(line));
        {
          const endpoint = this.endpoint();
          if (endpoint) for (const cb of this.readyListeners) cb(endpoint);
        }
        break;
      case 'failed':
        if (run.ready || run.failure || run.timedOut) return;
        run.progress.firstLine(at);
        run.failure = line;
        this.clearReadyTimer(run);
        this.o.log(`core #${run.id} did not start: ${line.reason}${line.detail ? ` (${line.detail})` : ''}`);
        // The core exits right after this line (exit code 2); the state waits for the exit code.
        setTimeout(() => {
          if (!this.hasExited(run)) run.child.kill('SIGKILL');
        }, 5000).unref?.();
        break;
    }
  }

  private onReadyTimeout(run: Run): void {
    if (run !== this.run || run.ready || run.failure || this.hasExited(run)) return;
    run.timedOut = true;
    this.o.log(`core #${run.id}: no ready line within ${Math.round(this.o.readyTimeoutMs / 1000)} s, stopping it`);
    run.child.stdin?.end();
    setTimeout(() => {
      if (!this.hasExited(run)) run.child.kill('SIGKILL');
    }, 3000).unref?.();
  }

  private clearReadyTimer(run: Run): void {
    if (run.readyTimer) clearTimeout(run.readyTimer);
    run.readyTimer = null;
  }

  private onExit(run: Run, code: number | null, signal: NodeJS.Signals | null): void {
    this.clearReadyTimer(run);
    const wasReady = run.ready !== null;
    if (run !== this.run) return;
    this.o.log(`core #${run.id} exited (${signal ? `signal ${signal}` : `code ${code}`})`);
    if (wasReady && !run.stopping) for (const cb of this.downListeners) cb();
    if (run.stopping) return;
    const logFile = run.failure?.log ?? join(this.o.home, 'logs', 'core.log');
    const tail = logTail(logFile, run.stderr);
    if (run.spawnError) {
      this.emit({
        kind: 'failed', reason: 'STARTUP_FAILED', detail: `Can't run the core: ${run.spawnError.message}`, portOwner: null,
        log: null, logTail: tail, exitCode: null, port: run.meta.port, publicPort: run.meta.publicPort,
        version: run.meta.version,
      });
      return;
    }
    if (run.failure) {
      this.emit(failedState(run.failure, run.meta, { exitCode: code, logTail: tail }));
      return;
    }
    if (run.timedOut) {
      this.emit({
        kind: 'failed', reason: 'READY_TIMEOUT', detail: `No ready line within ${Math.round(this.o.readyTimeoutMs / 1000)} s`,
        portOwner: null, log: logFile, logTail: tail, exitCode: code, port: run.meta.port,
        publicPort: run.meta.publicPort, version: run.meta.version,
      });
      return;
    }
    if (!wasReady) {
      this.emit({
        kind: 'failed', reason: 'STARTUP_FAILED',
        detail: `The core exited (${signal ? `signal ${signal}` : `code ${code}`}) before it was ready`, portOwner: null,
        log: logFile, logTail: tail, exitCode: code, port: run.meta.port, publicPort: run.meta.publicPort,
        version: run.meta.version,
      });
      return;
    }
    // A crash after ready: start again, a few times.
    const ranFor = run.readyAt === null ? 0 : this.o.now() - run.readyAt;
    if (ranFor >= this.o.stableAfterMs) this.crashes = 0;
    const delays = this.o.restartDelaysMs;
    if (this.crashes < delays.length && !this.shuttingDown && !this.paused) {
      const delay = delays[this.crashes] ?? 1000;
      this.crashes++;
      this.o.log(`core crashed; restart ${this.crashes} of ${delays.length} in ${delay} ms`);
      this.emit({ kind: 'starting', steps: [], port: run.meta.port, publicPort: run.meta.publicPort, version: run.meta.version });
      this.restartTimer = setTimeout(() => {
        this.restartTimer = null;
        void this.start();
      }, delay);
      this.restartTimer.unref?.();
      return;
    }
    const lastError = [...tail?.split('\n') ?? []].reverse().find((l) => /\bERROR\b/.test(l)) ?? null;
    this.emit({
      kind: 'stopped',
      exitCode: code,
      reason: signal ? `Terminated by ${signal}` : lastError ? lastError.trim().slice(0, 300) : null,
      uptimeMs: Math.max(0, this.o.now() - run.spawnedAt),
      at: new Date(this.o.now()).toISOString(),
      log: logFile,
      logTail: tail,
      port: run.meta.port,
    });
  }

  private async stopRun(): Promise<void> {
    const run = this.run;
    if (!run || this.hasExited(run)) return;
    const wasReady = run.ready !== null;
    run.stopping = true;
    this.clearReadyTimer(run);
    if (wasReady) for (const cb of this.downListeners) cb();
    this.o.log(`core #${run.id} stopping`);
    run.child.stdin?.end();
    const killer = setTimeout(() => {
      if (!this.hasExited(run)) {
        this.o.log(`core #${run.id} still running ${Math.round(this.o.stopTimeoutMs / 1000)} s after stdin closed: killing it`);
        run.child.kill('SIGKILL');
      }
    }, this.o.stopTimeoutMs);
    killer.unref?.();
    await run.exited;
    clearTimeout(killer);
  }
}

/**
 * The last lines of the core's log (main reads at most 64 KiB from its end), else of its stderr: the status screen's
 * "core.log · last lines".
 */
export function logTail(file: string | null, stderr: readonly string[], lines = LOG_TAIL_LINES): string | null {
  if (file) {
    try {
      const fd = openSync(file, 'r');
      try {
        const size = fstatSync(fd).size;
        const length = Math.min(size, 64 * 1024);
        const buffer = Buffer.alloc(length);
        readSync(fd, buffer, 0, length, size - length);
        const text = buffer.toString('utf8').split(/\r?\n/).filter((l) => l.trim() !== '');
        if (text.length > 0) return text.slice(-lines).join('\n');
      } finally {
        closeSync(fd);
      }
    } catch {
      // No log yet (the core stopped before logging started): stderr says why.
    }
  }
  const err = stderr.filter((l) => l.trim() !== '');
  return err.length > 0 ? err.slice(-lines).join('\n') : null;
}
