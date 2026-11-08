import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, it, type TestContext } from 'node:test';
import { setImmediate } from 'node:timers/promises';
import type { UpdateState } from '../src/shared/contract.ts';
import { Updates, type UpdateDriver, type UpdatesOptions } from '../src/main/updater.ts';
import { tempDir } from './helpers.ts';

function deferred<T = void>() {
  let resolve!: (value: T | PromiseLike<T>) => void;
  let reject!: (reason: Error) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

class FakeUpdater extends EventEmitter implements UpdateDriver {
  logger: UpdateDriver['logger'] = null;
  autoDownload = true;
  autoInstallOnAppQuit = true;
  allowPrerelease = true;
  allowDowngrade = true;
  feed: Parameters<UpdateDriver['setFeedURL']>[0] | null = null;
  setFeedURL(options: Parameters<UpdateDriver['setFeedURL']>[0]): void { this.feed = options; }
  checks = 0;
  downloads = 0;
  installs = 0;
  check = async (): Promise<null> => {
    this.emit('checking-for-update');
    this.emit('update-available', { version: '0.5.0' });
    return null;
  };
  download = async (): Promise<string[]> => ['installer.exe'];
  install = (): void => undefined;

  async checkForUpdates(): Promise<null> {
    this.checks++;
    return this.check();
  }
  async downloadUpdate(): Promise<string[]> {
    this.downloads++;
    return this.download();
  }
  quitAndInstall(silent?: boolean, restart?: boolean): void {
    assert.equal(silent, true);
    assert.equal(restart, true);
    this.installs++;
    this.install();
  }
}

function fixture(t: TestContext, overrides: Partial<UpdatesOptions> = {}) {
  const temp = tempDir('updater');
  t.after(() => temp.remove());
  writeFileSync(join(temp.dir, 'app-update.yml'), 'provider: github\nowner: msrv-ai\nrepo: gaterook\npublisherName: GateRook\n');
  const driver = new FakeUpdater();
  const states: UpdateState[] = [];
  const failures: string[] = [];
  let prepares = 0;
  let loads = 0;
  const updates = new Updates({
    packaged: true, resourcesPath: temp.dir, userDataPath: join(temp.dir, 'profile'), platform: 'win32', arch: 'x64', log: () => undefined,
    storeMetadataCheck: async () => undefined,
    autoUpdate: async () => true, push: (state) => states.push(state),
    loadUpdater: async () => { loads++; return driver; },
    prepareInstall: async () => { prepares++; updates.stop(); },
    installFailed: (message) => failures.push(message),
    ...overrides,
  });
  t.after(() => updates.stop());
  return { updates, driver, states, failures, identityPath: join(temp.dir, 'profile', 'install-id'), prepares: () => prepares, loads: () => loads };
}

describe('explicit desktop updates', () => {
  it('never loads or installs an external updater for MSIX, even with an embedded signed feed', async (t) => {
    const f = fixture(t, { windowsStore: true });
    f.updates.schedule();
    await f.updates.check();
    await f.updates.install();
    assert.equal(f.loads(), 0);
    assert.equal(f.driver.checks, 0);
    assert.equal(f.driver.downloads, 0);
    assert.equal(f.driver.installs, 0);
    assert.equal(f.prepares(), 0);
    assert.match((f.updates.current() as { message: string }).message, /Microsoft Store manages updates/);
  });

  it('checks Store metadata with a persistent identity without loading an external installer', async (t) => {
    const requests: string[] = [];
    const f = fixture(t, { windowsStore: true, storeMetadataCheck: async (url) => { requests.push(url); } });
    await f.updates.check();
    await f.updates.check();
    assert.equal(requests.length, 2);
    assert.equal(requests[0], requests[1]);
    const url = new URL(requests[0]!);
    assert.equal(url.origin, 'https://gaterook.com');
    assert.equal(url.pathname, '/api/updates/win32/x64/store.json');
    assert.equal(url.searchParams.get('install_id'), readFileSync(f.identityPath, 'utf8').trim());
    assert.equal(f.loads(), 0);
    await f.updates.install();
    assert.equal(f.driver.installs, 0);
  });

  it('checks updates without counting when the identity is corrupt or cannot be persisted', async (t) => {
    for (const invalid of ['corrupt', 'unwritable']) {
      const temp = tempDir(`identity-${invalid}`);
      t.after(() => temp.remove());
      const path = invalid === 'corrupt' ? join(temp.dir, 'install-id') : join(temp.dir, 'profile-is-a-file');
      writeFileSync(path, 'preserve this file');
      const logs: string[] = [];
      const f = fixture(t, { userDataPath: invalid === 'corrupt' ? temp.dir : path, log: (message) => logs.push(message) });
      await f.updates.check();
      assert.equal(f.driver.checks, 1);
      assert.equal(f.updates.current()?.state, 'available');
      assert.equal((f.driver.feed as unknown as { installId?: string }).installId, undefined);
      assert.equal(readFileSync(path, 'utf8'), 'preserve this file');
      assert.match(logs.join('\n'), /continue without activity counting/);
    }
  });

  it('still checks Store metadata without a valid local installation identity', async (t) => {
    const temp = tempDir('store-invalid-identity');
    t.after(() => temp.remove());
    writeFileSync(join(temp.dir, 'install-id'), 'corrupt');
    const requests: string[] = [];
    const f = fixture(t, { windowsStore: true, userDataPath: temp.dir,
      storeMetadataCheck: async (url) => { requests.push(url); } });
    await f.updates.check();
    assert.equal(requests.length, 1);
    assert.equal(new URL(requests[0]!).searchParams.has('install_id'), false);
    assert.equal(f.loads(), 0);
    assert.match((f.updates.current() as { message: string }).message, /Microsoft Store manages/);
  });

  it('redacts installation IDs from updater logs and user-facing errors', async (t) => {
    const logs: string[] = [];
    const f = fixture(t, { log: (message) => logs.push(message) });
    await f.updates.check();
    const id = readFileSync(f.identityPath, 'utf8').trim();
    const message = `Cannot request https://gaterook.com/api/updates/win32/x64/latest.yml?install_id=${id}`;
    f.driver.logger?.error(message);
    f.driver.emit('error', new Error(message));
    assert.equal(logs.join('\n').includes(id), false);
    assert.equal(JSON.stringify(f.updates.current()).includes(id), false);
  });

  it('checks metadata without downloading or installing and retains signed stable release restrictions', async (t) => {
    const f = fixture(t);
    await Promise.all([f.updates.check(), f.updates.check()]);
    assert.equal(f.loads(), 1);
    assert.equal(f.driver.checks, 1);
    assert.equal(f.updates.current()?.state, 'available');
    assert.equal(f.driver.downloads, 0);
    assert.equal(f.prepares(), 0);
    assert.equal(f.driver.installs, 0);
    assert.equal(f.driver.autoDownload, false);
    assert.equal(f.driver.autoInstallOnAppQuit, false);
    assert.equal(f.driver.allowPrerelease, false);
    assert.equal(f.driver.allowDowngrade, false);
    assert.equal(typeof f.driver.feed, 'object');
    assert.equal((f.driver.feed as { url: string }).url, 'https://gaterook.com/api/updates/win32/x64/');
    assert.equal((f.driver.feed as unknown as { installId: string }).installId, readFileSync(f.identityPath, 'utf8').trim());
  });

  it('downloads once per click sequence, reports progress, and waits for shutdown before installing', async (t) => {
    const downloaded = deferred<string[]>();
    const downloading = deferred();
    const stopping = deferred();
    const stopped = deferred();
    const f = fixture(t, { prepareInstall: async () => { stopping.resolve(); await stopped.promise; f.updates.stop(); } });
    f.driver.download = () => { downloading.resolve(); return downloaded.promise; };
    await f.updates.check();
    const first = f.updates.install();
    const second = f.updates.install();
    assert.equal(first, second);
    await downloading.promise;
    assert.equal(f.updates.current()?.state, 'downloading');
    f.driver.emit('download-progress', { percent: 42.7 });
    assert.equal((f.updates.current() as { percent: number }).percent, 42);
    await f.updates.check();
    assert.equal(f.driver.checks, 1, 'checking must not replace the in-flight download');
    assert.equal(f.driver.installs, 0);
    downloaded.resolve(['installer.exe']);
    await stopping.promise;
    assert.equal(f.updates.current()?.state, 'installing');
    assert.equal(f.driver.installs, 0, 'the installer must not replace a running core');
    stopped.resolve();
    await Promise.all([first, second]);
    assert.equal(f.driver.downloads, 1);
    assert.equal(f.driver.installs, 1);
    assert.deepEqual(f.states.map((s) => s.state), ['checking', 'available', 'downloading', 'downloading', 'downloaded', 'installing']);
  });

  it('keeps the app running after download/signature failure and allows a fresh retry', async (t) => {
    const f = fixture(t);
    f.driver.download = async () => {
      const error = new Error('Invalid code signature');
      f.driver.emit('error', error);
      throw error;
    };
    await f.updates.check();
    await f.updates.install();
    assert.equal(f.updates.current()?.state, 'error');
    assert.equal(f.updates.current()?.version, '0.5.0');
    assert.equal(f.prepares(), 0);
    assert.equal(f.driver.installs, 0);
    assert.deepEqual(f.failures, []);
    f.driver.download = async () => ['verified-installer.exe'];
    await f.updates.install();
    assert.equal(f.driver.downloads, 2);
    assert.equal(f.prepares(), 1);
    assert.equal(f.driver.installs, 1);
  });

  it('does not install after an error event even when the download promise resolves', async (t) => {
    const f = fixture(t);
    f.driver.download = async () => { f.driver.emit('error', new Error('signature failed')); return []; };
    await f.updates.check();
    await f.updates.install();
    assert.equal(f.updates.current()?.state, 'error');
    assert.equal(f.prepares(), 0);
    assert.equal(f.driver.installs, 0);
  });

  it('ignores completion of a download after the user quits', async (t) => {
    const download = deferred<string[]>();
    const started = deferred();
    const f = fixture(t);
    f.driver.download = () => { started.resolve(); return download.promise; };
    await f.updates.check();
    const install = f.updates.install();
    await started.promise;
    f.updates.stop();
    download.resolve(['installer.exe']);
    await install;
    assert.equal(f.prepares(), 0);
    assert.equal(f.driver.installs, 0);
  });

  it('recovers once if installer launch fails after shutdown', async (t) => {
    const f = fixture(t);
    const error = new Error('Installer could not start');
    f.driver.install = () => { f.driver.emit('error', error); throw error; };
    await f.updates.check();
    await f.updates.install();
    assert.equal(f.prepares(), 1);
    assert.equal(f.driver.installs, 1);
    assert.deepEqual(f.failures, ['Installer could not start']);
  });

  it('recovers if shutdown fails after closing the updater, without starting the installer', async (t) => {
    const f = fixture(t, { prepareInstall: async () => {
      f.updates.stop();
      throw new Error('The core could not stop');
    } });
    await f.updates.check();
    await f.updates.install();
    assert.equal(f.driver.installs, 0);
    assert.deepEqual(f.failures, ['The core could not stop']);
    assert.equal(f.updates.current()?.state, 'error');
  });

  it('does not load an updater for a development app or install without an available release', async (t) => {
    const dev = fixture(t, { packaged: false });
    await dev.updates.check();
    await dev.updates.install();
    assert.equal(dev.loads(), 0);
    assert.equal(existsSync(dev.identityPath), false);
    assert.equal(dev.updates.current()?.state, 'error');
    const installed = fixture(t);
    await installed.updates.install();
    assert.equal(installed.loads(), 0);
    assert.equal(installed.driver.downloads, 0);
    assert.equal(installed.prepares(), 0);
  });

  it('reports a missing packaged update source before loading the updater', async (t) => {
    const resources = tempDir('updater-without-source');
    t.after(() => resources.remove());
    const f = fixture(t, { platform: 'darwin', resourcesPath: resources.dir });
    await f.updates.check();
    await f.updates.install();
    assert.equal(f.loads(), 0);
    assert.equal(f.driver.checks, 0);
    assert.equal(f.driver.downloads, 0);
    assert.equal(f.updates.current()?.state, 'error');
    assert.equal((f.updates.current() as { message: string }).message, 'This build has no update source.');
  });

  it('reports loader errors and allows a later check', async (t) => {
    let attempts = 0;
    const driver = new FakeUpdater();
    const f = fixture(t, { loadUpdater: async () => {
      if (++attempts === 1) throw new Error('Updater could not load');
      return driver;
    } });
    await f.updates.check();
    assert.equal(f.updates.current()?.state, 'error');
    await f.updates.check();
    assert.equal(attempts, 2);
    assert.equal(f.updates.current()?.state, 'available');
  });
});

describe('automatic update checks', () => {
  /** Fires the schedule's next minute ticks on time, letting each one finish. */
  async function ticks(t: TestContext, count = 1): Promise<void> {
    for (let i = 0; i < count; i++) {
      t.mock.timers.runAll();
      await setImmediate();
    }
  }

  it('checks a minute after start, then every hour on macOS, counting from the tick', async (t) => {
    t.mock.timers.enable({ apis: ['setTimeout', 'Date'], now: 0 });
    let asked = 0;
    const f = fixture(t, { platform: 'darwin', autoUpdate: async () => {
      asked++;
      await Promise.resolve();
      t.mock.timers.setTime(Date.now() + 5_000); // the core answers after the next tick was set
      return true;
    } });
    f.updates.schedule();
    await ticks(t);
    assert.equal(f.driver.checks, 1);
    await ticks(t, 59);
    assert.equal(f.driver.checks, 1);
    await ticks(t);
    assert.equal(f.driver.checks, 2, 'a slow answer must not move the check to the next tick');
    assert.equal(asked, 2, 'the core is asked only when a check is due');
  });

  it('counts the hour from Check now and checks on the first tick after a Mac wakes', async (t) => {
    t.mock.timers.enable({ apis: ['setTimeout', 'Date'], now: 0 });
    const f = fixture(t, { platform: 'darwin' });
    f.updates.schedule();
    await ticks(t, 30);
    assert.equal(f.driver.checks, 1);
    await f.updates.check();
    assert.equal(f.driver.checks, 2);
    await ticks(t, 59);
    assert.equal(f.driver.checks, 2);
    await ticks(t);
    assert.equal(f.driver.checks, 3);
    // Asleep for eight hours: the tick due during the sleep runs on waking and finds the check overdue.
    t.mock.timers.setTime(Date.now() + 8 * 60 * 60_000);
    t.mock.timers.tick(0);
    await setImmediate();
    assert.equal(f.driver.checks, 4);
  });

  it('does not repeat a Check now made while the core answered', async (t) => {
    t.mock.timers.enable({ apis: ['setTimeout', 'Date'], now: 0 });
    const answer = deferred<boolean>();
    const f = fixture(t, { platform: 'darwin', autoUpdate: () => answer.promise });
    f.updates.schedule();
    await ticks(t);
    await f.updates.check();
    answer.resolve(true);
    await setImmediate();
    assert.equal(f.driver.checks, 1);
  });

  it('checks Store metadata daily and respects the automatic update preference', async (t) => {
    t.mock.timers.enable({ apis: ['setTimeout', 'Date'], now: 0 });
    let wanted = false;
    let checks = 0;
    const f = fixture(t, { windowsStore: true, autoUpdate: async () => wanted,
      storeMetadataCheck: async () => { checks++; } });
    f.updates.schedule();
    await ticks(t, 60);
    assert.equal(checks, 0);
    wanted = true;
    await ticks(t);
    assert.equal(checks, 1);
    await ticks(t, 24 * 60 - 1);
    assert.equal(checks, 1);
    await ticks(t);
    assert.equal(checks, 2);
    assert.equal(f.loads(), 0);
    assert.deepEqual(f.states, [], 'background Store metadata checks must stay silent');
    await f.updates.check();
    assert.match((f.updates.current() as { message: string }).message, /Microsoft Store manages/);
  });

  it('explains Store ownership for a manual check sharing an in-flight background check', async (t) => {
    t.mock.timers.enable({ apis: ['setTimeout', 'Date'], now: 0 });
    const result = deferred();
    let checks = 0;
    const f = fixture(t, { windowsStore: true, storeMetadataCheck: async () => { checks++; await result.promise; } });
    f.updates.schedule();
    await ticks(t);
    const manual = f.updates.check();
    assert.deepEqual(f.states, []);
    result.resolve();
    await manual;
    assert.equal(checks, 1);
    assert.match((f.updates.current() as { message: string }).message, /Microsoft Store manages/);
  });

  it('checks once a day on Windows and asks hourly while the setting is off', async (t) => {
    t.mock.timers.enable({ apis: ['setTimeout', 'Date'], now: 0 });
    let wanted = false;
    let asked = 0;
    const f = fixture(t, { autoUpdate: async () => { asked++; return wanted; } });
    f.updates.schedule();
    await ticks(t, 180);
    assert.equal(asked, 3);
    assert.equal(f.driver.checks, 0);
    wanted = true;
    await ticks(t);
    assert.equal(f.driver.checks, 1);
    await ticks(t, 24 * 60 - 1);
    assert.equal(f.driver.checks, 1);
    await ticks(t);
    assert.equal(f.driver.checks, 2);
    assert.equal(asked, 5, 'while the setting is on, the core is asked only when a check is due');
  });
});
