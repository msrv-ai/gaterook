import assert from 'node:assert/strict';
import type { RequestOptions } from 'node:http';
import { describe, it } from 'node:test';
import { HttpError } from 'builder-util-runtime';
import type { AppUpdater } from 'electron-updater';
import type { ProviderRuntimeOptions } from 'electron-updater/out/providers/Provider.js';
import { SiteUpdateProvider, siteUpdateOptions, updateFeedUrl } from '../src/main/update-provider.ts';

const ID = '0123456789AbCdEf';
const ZIP = 'https://github.com/gaterook/gaterook/releases/download/v0.7.0/GateRook-0.7.0-arm64.zip';

function manifest(version: string): string {
  const zip = ZIP.replaceAll('0.7.0', version);
  return `version: ${version}\nfiles:\n  - url: ${zip}\n    sha512: digest\n    size: 10\npath: ${zip}\nsha512: digest\n`;
}

/** A provider whose feed holds the given channel files; a missing one answers 404, an Error is the request's failure. */
function channels(platform: 'darwin' | 'win32', files: Record<string, string | Error>, beta = true) {
  const requests: URL[] = [];
  const warnings: string[] = [];
  const updater = { channel: null, isAddNoCacheQuery: true,
    logger: { info: () => undefined, warn: (message: string) => warnings.push(message), error: () => undefined } } as unknown as AppUpdater;
  const runtime = { platform, executor: { request: async (options: RequestOptions) => {
    const url = new URL(options.path!, `https://${options.hostname}`);
    requests.push(url);
    const answer = files[url.pathname.split('/').at(-1)!];
    if (answer === undefined) throw new HttpError(404, `404 Not Found\n"method: GET url: ${url.href}"`);
    if (answer instanceof Error) throw answer;
    return answer;
  } } } as ProviderRuntimeOptions;
  const provider = new SiteUpdateProvider(siteUpdateOptions(platform, 'arm64', ID, beta), updater, runtime);
  return { provider, requests, warnings, names: () => requests.map((url) => url.pathname.split('/').at(-1)).sort() };
}

function fixture(platform: 'darwin' | 'win32') {
  const requests: RequestOptions[] = [];
  const updater = { channel: null, isAddNoCacheQuery: true } as unknown as AppUpdater;
  const runtime = { platform, executor: { request: async (options: RequestOptions) => {
    requests.push(options);
    return `version: 0.7.0\nfiles:\n  - url: ${ZIP}\n    sha512: digest\n    size: 10\npath: ${ZIP}\nsha512: digest\n`;
  } } } as ProviderRuntimeOptions;
  const provider = new SiteUpdateProvider(siteUpdateOptions(platform, 'arm64', ID), updater, runtime);
  return { provider, requests };
}

describe('site update provider', () => {
  for (const [platform, filename] of [['darwin', 'latest-mac.yml'], ['win32', 'latest.yml']] as const) {
    it(`includes the identity only on ${platform} metadata, never on GitHub payloads or blockmaps`, async () => {
      const { provider, requests } = fixture(platform);
      const info = await provider.getLatestVersion();
      assert.equal(requests.length, 1);
      assert.equal(requests[0]?.hostname, 'gaterook.com');
      const request = new URL(requests[0]!.path!, 'https://gaterook.com');
      assert.equal(request.pathname, `/api/updates/${platform}/arm64/${filename}`);
      assert.equal(request.searchParams.get('install_id'), ID);
      const files = provider.resolveFiles(info);
      assert.equal(files[0]?.url.href, ZIP);
      const maps = await provider.getBlockMapFiles(files[0]!.url, '0.6.0', '0.7.0');
      assert.deepEqual(maps.map((url) => url.href), [ZIP.replaceAll('0.7.0', '0.6.0') + '.blockmap', ZIP + '.blockmap']);
      assert.equal(provider.isUseMultipleRangeRequest, false);
    });
  }

  it('checks metadata anonymously if the local installation identity is unavailable', async () => {
    const requests: RequestOptions[] = [];
    const provider = new SiteUpdateProvider(siteUpdateOptions('darwin', 'arm64', null),
      { channel: null, isAddNoCacheQuery: true } as unknown as AppUpdater,
      { platform: 'darwin', executor: { request: async (options: RequestOptions) => {
        requests.push(options);
        return `version: 0.7.0\nfiles:\n  - url: ${ZIP}\n    sha512: digest\n    size: 10\n`;
      } } } as ProviderRuntimeOptions);
    const info = await provider.getLatestVersion();
    const request = new URL(requests[0]!.path!, 'https://gaterook.com');
    assert.equal(request.pathname, '/api/updates/darwin/arm64/latest-mac.yml');
    assert.equal(request.searchParams.has('install_id'), false);
    assert.equal(provider.resolveFiles(info)[0]!.url.href, ZIP);
  });

  it('preserves a payload URL query without attaching the identity', async () => {
    const { provider } = fixture('darwin');
    const info = await provider.getLatestVersion();
    info.files[0]!.url += '?download=1';
    assert.equal(provider.resolveFiles(info)[0]!.url.href, `${ZIP}?download=1`);
  });

  it('rejects unsupported architectures and invalid metadata origins', () => {
    assert.throws(() => updateFeedUrl('darwin', '../x64'), /not available/);
    assert.throws(() => updateFeedUrl('linux', 'x64'), /not available/);
    const options = siteUpdateOptions('darwin', 'arm64', ID);
    assert.throws(() => new SiteUpdateProvider({ ...options, url: 'https://example.com/' }, {} as AppUpdater,
      {} as ProviderRuntimeOptions), /Invalid update source/);
    assert.throws(() => new SiteUpdateProvider({ ...options, url: options.url + '?install_id=' + ID }, {} as AppUpdater,
      {} as ProviderRuntimeOptions), /Invalid update source/);
    assert.throws(() => new SiteUpdateProvider({ ...options, beta: 'yes' }, {} as AppUpdater,
      {} as ProviderRuntimeOptions), /Invalid update source/);
  });
});

describe('beta channel of the site update provider', () => {
  it('never reads the beta channel unless beta versions are on', async () => {
    const { provider, names } = channels('darwin', { 'latest-mac.yml': manifest('0.7.0'), 'beta-mac.yml': manifest('0.8.0') }, false);
    const info = await provider.getLatestVersion();
    assert.deepEqual(names(), ['latest-mac.yml']);
    assert.equal(info.version, '0.7.0');
    assert.equal(info.beta, undefined);
    assert.equal(siteUpdateOptions('darwin', 'arm64', ID).beta, undefined);
    assert.equal(siteUpdateOptions('darwin', 'arm64', ID, true).beta, true);
  });

  for (const [platform, stable, beta] of [['darwin', 'latest-mac.yml', 'beta-mac.yml'], ['win32', 'latest.yml', 'beta.yml']] as const) {
    it(`offers a newer pre-release from ${beta}, with the identity on both metadata requests only`, async () => {
      const { provider, requests, names, warnings } = channels(platform, { [stable]: manifest('0.7.0'), [beta]: manifest('0.8.0') });
      const info = await provider.getLatestVersion();
      assert.deepEqual(names(), [beta, stable].sort());
      for (const request of requests) {
        assert.equal(request.origin, 'https://gaterook.com');
        assert.equal(request.pathname.startsWith(`/api/updates/${platform}/arm64/`), true);
        assert.equal(request.searchParams.get('install_id'), ID);
      }
      assert.equal(info.version, '0.8.0');
      assert.equal(info.beta, true);
      const files = provider.resolveFiles(info);
      assert.equal(files[0]?.url.href, ZIP.replaceAll('0.7.0', '0.8.0'));
      assert.equal(files[0]?.url.search, '');
      assert.deepEqual(warnings, []);
    });
  }

  it('offers the stable release when it is as new as the pre-release or newer', async () => {
    for (const [stable, beta] of [['0.8.0', '0.8.0'], ['0.9.0', '0.8.0'], ['0.8.0', '0.8.0-beta.2'], ['0.10.0', '0.9.9']] as const) {
      const { provider } = channels('darwin', { 'latest-mac.yml': manifest(stable), 'beta-mac.yml': manifest(beta) });
      const info = await provider.getLatestVersion();
      assert.equal(info.version, stable, `${stable} against the pre-release ${beta}`);
      assert.equal(info.beta, undefined, 'a release everyone gets is not a beta');
    }
    const { provider } = channels('darwin', { 'latest-mac.yml': manifest('0.8.0'), 'beta-mac.yml': manifest('0.8.1-beta.1') });
    const next = await provider.getLatestVersion();
    assert.deepEqual([next.version, next.beta], ['0.8.1-beta.1', true]);
  });

  it('checks the stable release alone while no pre-release is published', async () => {
    const { provider, names, warnings } = channels('darwin', { 'latest-mac.yml': manifest('0.7.0') });
    const info = await provider.getLatestVersion();
    assert.deepEqual(names(), ['beta-mac.yml', 'latest-mac.yml']);
    assert.deepEqual([info.version, info.beta], ['0.7.0', undefined]);
    assert.deepEqual(warnings, [], 'an empty beta channel is the usual case');
  });

  it('falls back to the stable release when the beta channel cannot be used, and says why without the identity', async () => {
    const failed = new Error(`net::ERR_CONNECTION_RESET\nhttps://gaterook.com/api/updates/darwin/arm64/beta-mac.yml?install_id=${ID}`);
    for (const beta of [failed, '', 'version: [0.8.0]\n', 'not: a manifest\n', ': : :']) {
      const { provider, warnings } = channels('darwin', { 'latest-mac.yml': manifest('0.7.0'), 'beta-mac.yml': beta });
      const info = await provider.getLatestVersion();
      assert.deepEqual([info.version, info.beta], ['0.7.0', undefined]);
      assert.equal(warnings.length, 1);
      assert.match(warnings[0]!, /^Beta update information is unavailable; checking the stable release only: /);
      assert.equal(warnings[0]!.includes(ID), false);
    }
    // A version that isn't one can't be compared: the stable release is offered, as with the beta channel off.
    const { provider } = channels('darwin', { 'latest-mac.yml': manifest('0.7.0'), 'beta-mac.yml': manifest('next') });
    assert.equal((await provider.getLatestVersion()).version, '0.7.0');
  });

  it('offers a pre-release for a target that has no stable release yet', async () => {
    const { provider } = channels('darwin', { 'beta-mac.yml': manifest('0.8.0') });
    const info = await provider.getLatestVersion();
    assert.deepEqual([info.version, info.beta], ['0.8.0', true]);
  });

  it('fails the check when the stable channel fails, whatever the beta channel says', async () => {
    const down = Object.assign(new Error('net::ERR_INTERNET_DISCONNECTED'), { code: 'ERR_INTERNET_DISCONNECTED' });
    await assert.rejects(channels('darwin', { 'latest-mac.yml': down, 'beta-mac.yml': manifest('0.8.0') }).provider.getLatestVersion(),
      /ERR_INTERNET_DISCONNECTED/);
    await assert.rejects(channels('darwin', { 'latest-mac.yml': down, 'beta-mac.yml': down }).provider.getLatestVersion(),
      /ERR_INTERNET_DISCONNECTED/);
    await assert.rejects(channels('darwin', {}).provider.getLatestVersion(),
      (error: Error & { code?: string }) => error.code === 'ERR_UPDATER_CHANNEL_FILE_NOT_FOUND');
    await assert.rejects(channels('darwin', {}, false).provider.getLatestVersion(),
      (error: Error & { code?: string }) => error.code === 'ERR_UPDATER_CHANNEL_FILE_NOT_FOUND');
  });
});
