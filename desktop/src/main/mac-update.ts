/** The part of electron-updater needed to download and stage a macOS update. */
export interface MacUpdateDownloader {
  autoInstallOnAppQuit: boolean;
  downloadUpdate(): Promise<string[]>;
}

/** Native Electron updater events, emitted after Squirrel has checked the downloaded application. */
export interface NativeUpdateEvents {
  on(event: 'update-downloaded', listener: () => void): unknown;
  on(event: 'error', listener: (error: Error) => void): unknown;
  removeListener(event: 'update-downloaded', listener: () => void): unknown;
  removeListener(event: 'error', listener: (error: Error) => void): unknown;
}

/**
 * Download after the person's update request, and wait for Squirrel's verification before stopping the core.
 * electron-updater emits its own `update-downloaded` before Squirrel consumes the ZIP. Its download promise can
 * also resolve before native verification. `autoInstallOnAppQuit` enables that native staging step; this helper
 * does not quit or install. The caller serializes update attempts and performs shutdown only after success.
 */
export async function downloadMacUpdate(updater: MacUpdateDownloader, nativeUpdater: NativeUpdateEvents): Promise<void> {
  const installOnQuit = updater.autoInstallOnAppQuit;
  let onDownloaded = (): void => undefined;
  let onError = (_error: Error): void => undefined;
  try {
    await new Promise<void>((resolve, reject) => {
      let downloadFinished = false;
      let nativeFinished = false;
      let nativeError: Error | null = null;
      const finish = (): void => {
        if (!downloadFinished) return;
        if (nativeError) reject(nativeError);
        else if (nativeFinished) resolve();
      };
      onDownloaded = () => { nativeFinished = true; finish(); };
      // electron-updater coalesces downloads. Keep this attempt pending until its download settles, even when
      // native verification fails first, so an immediate retry cannot reuse the failed attempt's promise.
      onError = (error) => { nativeError ??= error; finish(); };
      nativeUpdater.on('update-downloaded', onDownloaded);
      nativeUpdater.on('error', onError);
      updater.autoInstallOnAppQuit = true;
      // A single promise observes both event errors and download failures, including synchronous emissions and
      // a download rejection arriving after a native error. Neither failure authorizes application shutdown.
      const downloadFailed = (error: unknown): void => reject(nativeError ?? error);
      try {
        void updater.downloadUpdate().then(() => { downloadFinished = true; finish(); }, downloadFailed);
      } catch (error) {
        downloadFailed(error);
      }
    });
  } finally {
    updater.autoInstallOnAppQuit = installOnQuit;
    nativeUpdater.removeListener('update-downloaded', onDownloaded);
    nativeUpdater.removeListener('error', onError);
  }
}
