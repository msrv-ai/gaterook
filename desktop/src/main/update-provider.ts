import type { CancellationToken, CustomPublishOptions, UpdateInfo } from 'builder-util-runtime';
import type { OutgoingHttpHeaders } from 'node:http';
import { URL } from 'node:url';
import type { AppUpdater } from 'electron-updater';
import { GenericProvider } from 'electron-updater/out/providers/GenericProvider.js';
import { parseUpdateInfo, type ProviderRuntimeOptions } from 'electron-updater/out/providers/Provider.js';
import { getChannelFilename, newUrlFromBase } from 'electron-updater/out/util.js';
import { INSTALL_ID_PATTERN } from './install-id.ts';
import { compareVersions } from './updates.ts';

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
  /** Read the beta channel next to the stable one (Settings › General "Get beta versions"). */
  readonly beta?: boolean;
}

/** The update a check offers; `beta` marks a version that only the beta channel has. */
export type OfferedUpdate = UpdateInfo & { readonly beta?: boolean };

/** Whether a channel's answer says that nothing is published there, rather than that it couldn't be read. */
function notPublished(error: unknown): boolean {
  const e = error as { statusCode?: unknown; code?: unknown } | null;
  return e?.statusCode === 404 || e?.code === 'ERR_UPDATER_CHANNEL_FILE_NOT_FOUND';
}

/** Keep the generic feed query-free: electron-updater 6.x also copies its query to artifact URLs. */
export class SiteUpdateProvider extends GenericProvider {
  private readonly installId: string | null;
  private readonly feed: URL;
  private readonly beta: boolean;
  private readonly warn: (message: string) => void;

  constructor(options: CustomPublishOptions, updater: AppUpdater, runtime: ProviderRuntimeOptions) {
    if (typeof options.url !== 'string' || (options.installId != null && typeof options.installId !== 'string')
        || (options.beta != null && typeof options.beta !== 'boolean')) {
      throw new Error('Invalid update source configuration.');
    }
    const url = new URL(options.url);
    if (url.origin !== UPDATE_ORIGIN || url.search || url.hash
        || (options.installId != null && !INSTALL_ID_PATTERN.test(options.installId))) {
      throw new Error('Invalid update source configuration.');
    }
    super({ provider: 'generic', url: url.href }, updater, { ...runtime, isUseMultipleRangeRequest: false });
    this.installId = options.installId ?? null;
    this.beta = options.beta === true;
    // A channel file is resolved against the feed's folder, as the stable one is.
    if (!url.pathname.endsWith('/')) url.pathname += '/';
    this.feed = url;
    this.warn = (message) => updater.logger?.warn(message);
  }

  /**
   * The version a check offers. With beta versions on, the beta channel is read next to the stable one and the newer
   * of the two wins: a stable release reaches an installation that follows betas whatever the beta channel holds, and
   * a pre-release that became the stable release is offered as that. The beta channel is an addition: with nothing
   * published there, or an answer that can't be used, the check is the stable one.
   */
  override async getLatestVersion(): Promise<OfferedUpdate> {
    if (!this.beta) return super.getLatestVersion();
    const [stable, beta] = await Promise.allSettled([super.getLatestVersion(), this.betaVersion()]);
    if (beta.status === 'rejected') {
      if (!notPublished(beta.reason)) {
        const reason = String((beta.reason as Error | null)?.message ?? beta.reason).split('\n')[0];
        this.warn(`Beta update information is unavailable; checking the stable release only: ${reason}`);
      }
      if (stable.status === 'rejected') throw stable.reason;
      return stable.value;
    }
    const offered: OfferedUpdate = { ...beta.value, beta: true };
    if (stable.status === 'rejected') {
      // A target whose first release is still a pre-release has no stable channel yet; any other failure is the check's.
      if (!notPublished(stable.reason)) throw stable.reason;
      return offered;
    }
    return (compareVersions(beta.value.version, stable.value.version) ?? 0) > 0 ? offered : stable.value;
  }

  private async betaVersion(): Promise<UpdateInfo> {
    const file = getChannelFilename(this.getCustomChannelName('beta'));
    const url = newUrlFromBase(file, this.feed, true);
    const info = parseUpdateInfo(await this.httpRequest(url), file, url) as UpdateInfo | null | undefined;
    if (typeof info?.version !== 'string') throw new Error(`${file} names no version.`);
    return info;
  }

  protected override httpRequest(url: URL, headers?: OutgoingHttpHeaders | null,
    cancellationToken?: CancellationToken): Promise<string | null> {
    if (url.origin !== UPDATE_ORIGIN) throw new Error('Invalid update metadata origin.');
    const metadataUrl = new URL(url.href);
    if (this.installId) metadataUrl.searchParams.set('install_id', this.installId);
    return super.httpRequest(metadataUrl, headers, cancellationToken);
  }
}

export function siteUpdateOptions(platform: NodeJS.Platform, arch: string, installId: string | null,
  beta = false): SiteUpdateOptions {
  return { provider: 'custom', updateProvider: SiteUpdateProvider, url: updateFeedUrl(platform, arch),
    ...(installId ? { installId } : {}), ...(beta ? { beta: true } : {}) };
}

/** Store owns installation; this request only reads the site metadata and records an update check. */
export async function checkStoreMetadata(url: string): Promise<void> {
  const response = await fetch(url, { redirect: 'error', credentials: 'omit', signal: AbortSignal.timeout(10_000) });
  await response.body?.cancel();
  if (!response.ok) throw new Error('The Store update information is currently unavailable.');
}
