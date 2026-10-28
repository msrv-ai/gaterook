import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { setImmediate } from 'node:timers/promises';
import { describe, it } from 'node:test';
import { downloadMacUpdate, type MacUpdateDownloader } from '../src/main/mac-update.ts';

function fixture() {
  const native = new EventEmitter();
  const existingDownloaded = (): void => undefined;
  const existingError = (): void => undefined;
  native.on('update-downloaded', existingDownloaded);
  native.on('error', existingError);
  const download = Promise.withResolvers<string[]>();
  let calls = 0;
  const updater: MacUpdateDownloader = {
    autoInstallOnAppQuit: false,
    downloadUpdate: () => {
      calls++;
      assert.equal(updater.autoInstallOnAppQuit, true, 'Squirrel staging must be enabled during the download');
      assert.equal(native.listenerCount('update-downloaded'), 2, 'subscribe before starting the download');
      assert.equal(native.listenerCount('error'), 2);
      return download.promise;
    },
  };
  const assertClean = (installOnQuit = false): void => {
    assert.equal(updater.autoInstallOnAppQuit, installOnQuit);
    assert.deepEqual(native.listeners('update-downloaded'), [existingDownloaded]);
    assert.deepEqual(native.listeners('error'), [existingError]);
  };
  return { native, download, updater, assertClean, calls: () => calls };
}

describe('macOS update staging', () => {
  it('waits for native verification after the ZIP download resolves', async () => {
    const f = fixture();
    let completed = false;
    const operation = downloadMacUpdate(f.updater, f.native).then(() => { completed = true; });
    f.download.resolve(['update.zip']);
    await setImmediate();
    assert.equal(completed, false);
    assert.equal(f.updater.autoInstallOnAppQuit, true);
    f.native.emit('update-downloaded');
    await operation;
    assert.equal(completed, true);
    assert.equal(f.calls(), 1);
    f.assertClean();
  });

  it('also waits for the download promise when native verification finishes first', async () => {
    const f = fixture();
    let completed = false;
    const operation = downloadMacUpdate(f.updater, f.native).then(() => { completed = true; });
    f.native.emit('update-downloaded');
    await setImmediate();
    assert.equal(completed, false);
    f.download.resolve([]);
    await operation;
    f.assertClean();
  });

  it('catches native events emitted synchronously while starting a cached download', async () => {
    const f = fixture();
    f.updater.downloadUpdate = () => {
      f.native.emit('update-downloaded');
      return Promise.resolve([]);
    };
    await downloadMacUpdate(f.updater, f.native);
    f.assertClean();
  });

  it('waits for a pending download to reject before exposing a native error for retry', async () => {
    const f = fixture();
    const error = new Error('The application signature is invalid');
    let completed = false;
    const operation = downloadMacUpdate(f.updater, f.native).finally(() => { completed = true; });
    f.native.emit('error', error);
    await setImmediate();
    assert.equal(completed, false, 'retry must not coalesce with the previous download');
    assert.equal(f.updater.autoInstallOnAppQuit, true);
    f.download.reject(new Error('Squirrel rejected the ZIP'));
    await assert.rejects(operation, (actual) => actual === error);
    f.assertClean();
  });

  it('observes a synchronous native error before downloadUpdate returns', async () => {
    const f = fixture();
    const error = new Error('Squirrel could not start');
    f.updater.downloadUpdate = () => {
      f.native.emit('error', error);
      return Promise.reject(new Error('Download failed'));
    };
    await assert.rejects(downloadMacUpdate(f.updater, f.native), (actual) => actual === error);
    f.assertClean();
    await setImmediate();
  });

  it('rejects if a native error follows readiness while the download is still pending', async () => {
    const f = fixture();
    const operation = downloadMacUpdate(f.updater, f.native);
    f.native.emit('update-downloaded');
    f.native.emit('error', new Error('Native staging failed'));
    f.download.resolve([]);
    await assert.rejects(operation, /Native staging failed/);
    f.assertClean();
  });

  it('rejects a native error immediately when the download has already resolved', async () => {
    const f = fixture();
    const operation = downloadMacUpdate(f.updater, f.native);
    f.download.resolve([]);
    await setImmediate();
    f.native.emit('error', new Error('Native signature verification failed'));
    await assert.rejects(operation, /Native signature verification failed/);
    f.assertClean();
  });

  it('cleans up after a rejected download without waiting for a native event', async () => {
    const f = fixture();
    const operation = downloadMacUpdate(f.updater, f.native);
    f.download.reject(new Error('Network unavailable'));
    await assert.rejects(operation, /Network unavailable/);
    f.assertClean();
  });

  it('cleans up when starting the download throws', async () => {
    const f = fixture();
    f.updater.downloadUpdate = () => { throw new Error('No update metadata'); };
    await assert.rejects(downloadMacUpdate(f.updater, f.native), /No update metadata/);
    f.assertClean();
  });

  for (const fail of [false, true]) {
    it(`restores an enabled install-on-quit setting after ${fail ? 'failure' : 'success'}`, async () => {
      const f = fixture();
      f.updater.autoInstallOnAppQuit = true;
      const operation = downloadMacUpdate(f.updater, f.native);
      if (fail) {
        f.download.reject(new Error('Download failed'));
        await assert.rejects(operation, /Download failed/);
      } else {
        f.download.resolve([]);
        f.native.emit('update-downloaded');
        await operation;
      }
      f.assertClean(true);
    });
  }

  it('requires a new native verification event when retrying a failed download', async () => {
    const f = fixture();
    const first = downloadMacUpdate(f.updater, f.native);
    f.download.reject(new Error('Network unavailable'));
    await assert.rejects(first, /Network unavailable/);
    f.assertClean();

    f.updater.downloadUpdate = () => Promise.resolve([]);
    let completed = false;
    const retry = downloadMacUpdate(f.updater, f.native).then(() => { completed = true; });
    await setImmediate();
    assert.equal(completed, false, 'a cached ZIP does not prove native verification succeeded');
    f.native.emit('update-downloaded');
    await retry;
    f.assertClean();
  });
});
