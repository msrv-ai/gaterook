// scripts/stage.mjs: what the installers carry, laid out as desktop/electron-builder.yml packs it.
import assert from 'node:assert/strict';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { after, describe, it } from 'node:test';
import { parseArgs, platformKey, sqliteLibName, stage } from '../../scripts/stage.mjs';

const dir = realpathSync(mkdtempSync(join(tmpdir(), 'gw-stage-')));
after(() => rmSync(dir, { recursive: true, force: true }));

function fakes() {
  const f = join(dir, `src-${Math.random().toString(36).slice(2)}`);
  mkdirSync(join(f, 'ui', 'assets'), { recursive: true });
  writeFileSync(join(f, 'ui', 'index.html'), '<gw-root></gw-root>');
  writeFileSync(join(f, 'ui', 'assets', 'theme-boot.js'), '');
  mkdirSync(join(f, 'target', 'native-libs'), { recursive: true });
  writeFileSync(join(f, 'target', 'mcp-gateway-core'), 'native core');
  writeFileSync(join(f, 'target', 'native-libs', 'libsqlitejdbc.dylib'), 'sqlite');
  writeFileSync(join(f, 'target', 'app.jar'), 'jar');
  writeFileSync(join(f, 'gw-bridge.mjs'), '// bridge');
  return {
    f,
    sources: { uiDist: join(f, 'ui'), nativeCore: join(f, 'target', 'mcp-gateway-core'), jar: join(f, 'target', 'app.jar'),
      jre: join(f, 'missing', 'jre'), pluginHost: join(f, 'missing', 'plugin-host.jar'), bridgeMjs: join(f, 'gw-bridge.mjs'), bridgeNative: join(f, 'missing', 'gw-bridge'),
      bridgeJar: join(f, 'missing', 'gw-bridge.jar') },
  };
}

describe('stage.mjs', () => {
  it('takes the plugin host\'s JRE from scripts/jlink.sh\'s folder when --jre names none', async () => {
    const { f, sources } = fakes();
    mkdirSync(join(f, 'jre', 'bin'), { recursive: true });
    writeFileSync(join(f, 'jre', 'release'), 'JAVA_RUNTIME_VERSION="25.0.2+10-LTS"\n');
    const out = join(dir, 'out-jre');
    const report = await stage({ out, platform: 'darwin-arm64', core: null, jar: false, jre: null, skipCloudflared: true },
      { ...sources, jre: join(f, 'jre') });
    assert.ok(existsSync(join(out, 'jre', 'release')));
    assert.ok(!existsSync(join(out, 'jre', 'README.txt')));
    assert.ok(report.includes(`jre ← ${join(f, 'jre')}`));
  });

  it('stages the UI, the native core with its SQLite library, the bridge, placeholders and the icon', async () => {
    const { sources } = fakes();
    const out = join(dir, 'out-native');
    const report = await stage({ out, platform: 'darwin-arm64', core: null, jar: false, jre: null, skipCloudflared: true }, sources);
    assert.ok(existsSync(join(out, 'ui', 'index.html')));
    assert.equal(readFileSync(join(out, 'core', 'mcp-gateway-core'), 'utf8'), 'native core');
    if (process.platform !== 'win32') assert.equal(statSync(join(out, 'core', 'mcp-gateway-core')).mode & 0o111, 0o111);
    assert.equal(readFileSync(join(out, 'core', 'libsqlitejdbc.dylib'), 'utf8'), 'sqlite');
    assert.ok(existsSync(join(out, 'bridge', 'gw-bridge.mjs')));
    assert.ok(existsSync(join(out, 'jre', 'README.txt')));
    assert.ok(existsSync(join(out, 'plugin-host', 'README.txt')));
    assert.ok(existsSync(join(out, 'cloudflared', 'darwin-arm64', 'README.txt')));
    const png = readFileSync(join(out, 'icons', 'icon.png'));
    assert.equal(png.readUInt32BE(16), 1024);
    assert.deepEqual(png, readFileSync(new URL('../../design/assets/gaterook/gaterook-icon.png', import.meta.url)));
    assert.ok(report.some((l) => l.startsWith('core/mcp-gateway-core')));
  });

  it('stages the JVM fallback only when asked', async () => {
    const { sources } = fakes();
    const out = join(dir, 'out-jar');
    await stage({ out, platform: 'linux-x64', core: null, jar: true, jre: null, skipCloudflared: true }, sources);
    assert.equal(readFileSync(join(out, 'core', 'app.jar'), 'utf8'), 'jar');
    assert.ok(!existsSync(join(out, 'core', 'mcp-gateway-core')));
  });

  it('refuses to stage without a UI or a core', async () => {
    const { sources } = fakes();
    await assert.rejects(stage({ out: join(dir, 'x1'), platform: 'darwin-arm64', core: null, jar: false, jre: null, skipCloudflared: true },
      { ...sources, uiDist: join(dir, 'nothing') }), /npm run build -w ui/);
    await assert.rejects(stage({ out: join(dir, 'x2'), platform: 'darwin-arm64', core: null, jar: false, jre: null, skipCloudflared: true },
      { ...sources, nativeCore: join(dir, 'nothing', 'core') }), /no native core/);
    await assert.rejects(stage({ out: join(dir, 'x3'), platform: 'linux-x64', core: null, jar: false, jre: null, skipCloudflared: true },
      sources), /libsqlitejdbc\.so/);
  });

  it('never clears a folder it didn\'t make', async () => {
    const { sources } = fakes();
    const precious = join(dir, 'precious');
    mkdirSync(precious, { recursive: true });
    writeFileSync(join(precious, 'notes.txt'), 'keep me');
    await assert.rejects(stage({ out: precious, platform: 'darwin-arm64', core: null, jar: false, jre: null, skipCloudflared: true },
      sources), /isn't a stage folder/);
    assert.equal(readFileSync(join(precious, 'notes.txt'), 'utf8'), 'keep me');
    const out = join(dir, 'out-again');
    await stage({ out, platform: 'darwin-arm64', core: null, jar: false, jre: null, skipCloudflared: true }, sources);
    await stage({ out, platform: 'darwin-arm64', core: null, jar: false, jre: null, skipCloudflared: true }, sources);
    assert.ok(existsSync(join(out, '.mcp-gateway-stage')));
  });

  it('knows the platform keys and libraries', () => {
    assert.equal(platformKey('darwin', 'arm64'), 'darwin-arm64');
    assert.equal(platformKey('win32', 'x64'), 'windows-x64');
    assert.equal(platformKey('linux', 'x64'), 'linux-x64');
    assert.equal(sqliteLibName('windows-x64'), 'sqlitejdbc.dll');
    assert.deepEqual(parseArgs(['--jar', '--skip-cloudflared', '--platform', 'linux-arm64']).platform, 'linux-arm64');
    assert.throws(() => parseArgs(['--nope']), /unknown argument/);
  });
});
