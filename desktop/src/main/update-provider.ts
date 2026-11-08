import type { CancellationToken, CustomPublishOptions } from 'builder-util-runtime';
import type { OutgoingHttpHeaders } from 'node:http';
import { URL } from 'node:url';
import type { AppUpdater } from 'electron-updater';
import { GenericProvider } from 'electron-updater/out/providers/GenericProvider.js';
import type { ProviderRuntimeOptions } from 'electron-updater/out/providers/Provider.js';
import { INSTALL_ID_PATTERN } from './install-id.ts';

export const UPDATE_ORIGIN = 'https://gaterook.com';

export function updateFeedUrl(platform: NodeJS.Platform, arch: string): string {
  if (!['darwin', 'win32'].includes(platform) || !['arm64', 'x64'].includes(arch)) {
    throw new Error('Updates are not available for this platform and architecture.');
  }
  return `${UPDATE_ORIGIN}/api/updates/${platform}/${arch}/`;
}

interface SiteUpdateOptions extends CustomPublishOptions {
  readonly url: string;
  readonly installId?: string;
}

/** Keep the generic feed query-free: electron-updater 6.x also copies its query to artifact URLs. */
export class SiteUpdateProvider extends GenericProvider {
  private readonly installId: string | null;

  constructor(options: CustomPublishOptions, updater: AppUpdater, runtime: ProviderRuntimeOptions) {
    if (typeof options.url !== 'string' || (options.installId != null && typeof options.installId !== 'string')) {
      throw new Error('Invalid update source configuration.');
    }
    const url = new URL(options.url);
    if (url.origin !== UPDATE_ORIGIN || url.search || url.hash
        || (options.installId != null && !INSTALL_ID_PATTERN.test(options.installId))) {
      throw new Error('Invalid update source configuration.');
    }
    super({ provider: 'generic', url: url.href }, updater, { ...runtime, isUseMultipleRangeRequest: false });
    this.installId = options.installId ?? null;
  }

  protected override httpRequest(url: URL, headers?: OutgoingHttpHeaders | null,
    cancellationToken?: CancellationToken): Promise<string | null> {
    if (url.origin !== UPDATE_ORIGIN) throw new Error('Invalid update metadata origin.');
    const metadataUrl = new URL(url.href);
    if (this.installId) metadataUrl.searchParams.set('install_id', this.installId);
    return super.httpRequest(metadataUrl, headers, cancellationToken);
  }
}

export function siteUpdateOptions(platform: NodeJS.Platform, arch: string, installId: string | null): SiteUpdateOptions {
  return { provider: 'custom', updateProvider: SiteUpdateProvider, url: updateFeedUrl(platform, arch),
    ...(installId ? { installId } : {}) };
}

/** Store owns installation; this request only reads the site metadata and records an update check. */
export async function checkStoreMetadata(url: string): Promise<void> {
  const response = await fetch(url, { redirect: 'error', credentials: 'omit', signal: AbortSignal.timeout(10_000) });
  await response.body?.cancel();
  if (!response.ok) throw new Error('The Store update information is currently unavailable.');
}
