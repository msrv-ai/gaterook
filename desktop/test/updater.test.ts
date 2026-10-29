import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, it, type TestContext } from 'node:test';
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
    packaged: true, resourcesPath: temp.dir, platform: 'win32', log: () => undefined,
    autoUpdate: async () => true, push: (state) => states.push(state),
    loadUpdater: async () => { loads++; return driver; },
    prepareInstall: async () => { prepares++; updates.stop(); },
    installFailed: (message) => failures.push(message),
    ...overrides,
  });
  t.after(() => updates.stop());
  return { updates, driver, states, failures, prepares: () => prepares, loads: () => loads };
}

describe('explicit desktop updates', () => {
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
