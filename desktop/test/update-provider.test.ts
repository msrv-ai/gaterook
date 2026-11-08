import assert from 'node:assert/strict';
import type { RequestOptions } from 'node:http';
import { describe, it } from 'node:test';
import type { AppUpdater } from 'electron-updater';
import type { ProviderRuntimeOptions } from 'electron-updater/out/providers/Provider.js';
import { SiteUpdateProvider, siteUpdateOptions, updateFeedUrl } from '../src/main/update-provider.ts';

const ID = '0123456789AbCdEf';
const ZIP = 'https://github.com/msrv-ai/gaterook/releases/download/v0.7.0/GateRook-0.7.0-arm64.zip';

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
  });
});
