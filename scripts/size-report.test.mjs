import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import test from 'node:test';
import { attribute, checkBudget, classIndex, codeByOrigin, jarOwners, originOfClass, parseDependencyList, PROXIES,
  render, RUNTIME, zipEntries } from './size-report.mjs';

/** A ZIP archive with every entry stored, as Spring Boot stores the jars of BOOT-INF/lib. */
function storedZip(files) {
  const locals = [];
  const central = [];
  let offset = 0;
  for (const [name, content] of Object.entries(files)) {
    const nameBytes = Buffer.from(name, 'utf8');
    const data = Buffer.isBuffer(content) ? content : Buffer.from(content);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt32LE(data.length, 18);
    local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(nameBytes.length, 26);
    locals.push(local, nameBytes, data);
    const entry = Buffer.alloc(46);
    entry.writeUInt32LE(0x02014b50, 0);
    entry.writeUInt32LE(data.length, 20);
    entry.writeUInt32LE(data.length, 24);
    entry.writeUInt16LE(nameBytes.length, 28);
    entry.writeUInt32LE(offset, 42);
    central.push(entry, nameBytes);
    offset += 30 + nameBytes.length + data.length;
  }
  const directory = Buffer.concat(central);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(Object.keys(files).length, 8);
  end.writeUInt16LE(Object.keys(files).length, 10);
  end.writeUInt32LE(directory.length, 12);
  end.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, directory, end]);
}

function appJar(t) {
  const scratch = new URL('../ai/', import.meta.url);
  mkdirSync(scratch, { recursive: true });
  const root = mkdtempSync(new URL('size-test-', scratch));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const file = join(root, 'app.jar');
  writeFileSync(file, storedZip({
    'BOOT-INF/classes/application.yml': 'x',
    'BOOT-INF/lib/core-1.0.0.jar': storedZip({ 'ai/msrv/gaterook/core/Gateway.class': 'c', 'db/migration/V1.sql': 's' }),
    'BOOT-INF/lib/connectors-support-1.0.0.jar': storedZip({ 'ai/msrv/gaterook/connectors/support/Glob.class': 'c' }),
    'BOOT-INF/lib/connectors-logs-1.0.0.jar': storedZip({ 'ai/msrv/gaterook/connectors/logs/LogsConnector.class': 'c' }),
    'BOOT-INF/lib/connectors-azure-1.0.0.jar': storedZip({ 'ai/msrv/gaterook/connectors/azure/AzureConnector.class': 'c' }),
    'BOOT-INF/lib/connectors-sql-1.0.0.jar': storedZip({ 'ai/msrv/gaterook/connectors/sql/SqlConnector.class': 'c' }),
    'BOOT-INF/lib/driver-2.1.jar': storedZip({ 'org/driver/Driver.class': 'c', 'org/driver/Driver$Inner.class': 'c',
      'META-INF/versions/21/org/driver/Fast.class': 'c' }),
    'BOOT-INF/lib/logs-client-3.0.jar': storedZip({ 'com/cloud/logs/Client.class': 'c' }),
    'BOOT-INF/lib/netty-4.2.jar': storedZip({ 'io/netty/Channel.class': 'c' }),
    'BOOT-INF/lib/spring-core-7.0.jar': storedZip({ 'org/springframework/core/Env.class': 'c' }),
  }));
  return file;
}

test('a stored ZIP lists its entries with the place of their data', () => {
  const zip = storedZip({ 'a.txt': 'hello', 'dir/b.bin': Buffer.from([1, 2, 3]) });
  const entries = zipEntries(zip);
  assert.deepEqual(entries.map((e) => [e.name, e.size, e.method]), [['a.txt', 5, 0], ['dir/b.bin', 3, 0]]);
  assert.equal(zip.toString('utf8', entries[0].dataStart, entries[0].dataStart + 5), 'hello');
  assert.throws(() => zipEntries(Buffer.from('not a zip archive at all, only text')), /Not a ZIP archive/);
});

test('the classes of the jars inside app.jar are indexed, multi-release classes by their own name', (t) => {
  const index = classIndex(appJar(t));
  assert.equal(index.classes.get('org.driver.Driver'), 'driver-2.1.jar');
  assert.equal(index.classes.get('org.driver.Fast'), 'driver-2.1.jar');
  assert.equal(index.classes.get('ai.msrv.gaterook.core.Gateway'), 'core-1.0.0.jar');
  assert.deepEqual([...index.packages.get('org.driver')], ['driver-2.1.jar']);
  assert.equal(index.jars.size, 9);
});

test('a class is traced to its jar through inner classes, lambdas and generated code', (t) => {
  const index = classIndex(appJar(t));
  assert.equal(originOfClass('org.driver.Driver$Inner$Deeper', index), 'driver-2.1.jar');
  assert.equal(originOfClass('org.driver.Driver$$Lambda.0x0001', index), 'driver-2.1.jar');
  assert.equal(originOfClass('org.driver.Generated__Hints', index), 'driver-2.1.jar');
  assert.equal(originOfClass('ai.msrv.gaterook.connectors.sql.boot.SqlConfig__BeanDefinitions', index), 'module:sql');
  assert.equal(originOfClass('ai.msrv.gaterook.connectors.cloudauth.Generated', index), 'module:cloud-auth');
  assert.equal(originOfClass('ai.msrv.gaterook.core.GatewayApplication__Init', index), 'module:core');
  assert.equal(originOfClass('java.util.HashMap', index), RUNTIME);
  assert.equal(originOfClass('jdk.proxy4.$Proxy12', index), PROXIES);
  assert.equal(originOfClass('com.unknown.Thing', index), '(other: com)');
});

test('Maven dependency lists become jar names, with classifiers and scopes', () => {
  const jars = parseDependencyList(`
The following files have been resolved:
   org.driver:driver:jar:2.1 -- module org.driver
   io.netty:netty-transport-native:jar:osx-aarch_64:4.2.19.Final:runtime
   ai.msrv.gaterook:connector-api:jar:1.0.0:compile -- module connector.api (auto)
   org.example:bom:pom:1.0
`);
  assert.deepEqual([...jars], ['driver-2.1.jar', 'netty-transport-native-4.2.19.Final-osx-aarch_64.jar',
    'connector-api-1.0.0.jar']);
});

test('code is attributed to the core, the runtime and the connector that brings each jar', (t) => {
  const index = classIndex(appJar(t));
  const lists = new Map([
    ['core', new Set(['spring-core-7.0.jar', 'connectors-support-1.0.0.jar'])],
    ['connectors-support', new Set()],
    ['connectors-logs', new Set(['logs-client-3.0.jar', 'spring-core-7.0.jar'])],
    ['connectors-azure', new Set(['connectors-logs-1.0.0.jar', 'logs-client-3.0.jar', 'netty-4.2.jar'])],
    ['connectors-sql', new Set(['driver-2.1.jar', 'netty-4.2.jar'])],
    ['connectors-absent', new Set(['netty-4.2.jar'])],
  ]);
  const owners = jarOwners(lists, index.jars, ['logs', 'azure', 'sql', 'absent']);
  assert.deepEqual(owners.get('logs-client-3.0.jar'), ['logs'], 'azure builds on logs, so the client is logs\'');
  assert.deepEqual(owners.get('connectors-logs-1.0.0.jar'), ['logs']);
  assert.deepEqual(owners.get('netty-4.2.jar'), ['azure', 'sql'], 'a connector that is not in the build owns nothing');
  assert.deepEqual(owners.get('spring-core-7.0.jar'), ['core']);
  assert.deepEqual(owners.get('connectors-support-1.0.0.jar'), ['core']);

  const dump = { 'code-breakdown': { 'code-size': [
    { name: 'org.driver.Driver.connect(String) Connection', size: 600 },
    { name: 'io.netty.Channel.write(Object) void', size: 200 },
    { name: 'com.cloud.logs.Client.get() String', size: 100 },
    { name: 'ai.msrv.gaterook.connectors.sql.boot.SqlConfig__BeanDefinitions.get() Object', size: 50 },
    { name: 'ai.msrv.gaterook.core.Gateway.main(String[]) void', size: 300 },
    { name: 'java.lang.String.length() int', size: 700 },
    { name: 'jdk.proxy4.$Proxy1.value() String', size: 50 },
  ] } };
  const attributed = attribute(codeByOrigin(dump, index), owners, 4_000);
  const by = Object.fromEntries(attributed.rows.map((row) => [row.owner, row]));
  assert.equal(attributed.codeBytes, 2_000);
  assert.equal(attributed.factor, 2);
  assert.deepEqual(by.sql, { owner: 'sql', codeBytes: 750, imageBytes: 1_500 });
  assert.deepEqual(by.azure, { owner: 'azure', codeBytes: 100, imageBytes: 200 });
  assert.deepEqual(by.logs, { owner: 'logs', codeBytes: 100, imageBytes: 200 });
  assert.deepEqual(by.core, { owner: 'core', codeBytes: 300, imageBytes: 600 });
  assert.deepEqual(by.runtime, { owner: 'runtime', codeBytes: 750, imageBytes: 1_500 });
  assert.throws(() => codeByOrigin({}, index), /no code breakdown/);
});

test('the budget names bundled connectors over it, modules compiled in, and exemptions no longer needed', () => {
  const delivery = { budget: { bundledImageMegabytes: 3, exempt: { sql: 'first connections', redis: 'popular' } },
    connectors: { sql: { delivery: 'bundled', types: ['postgres'] }, redis: { delivery: 'bundled', types: ['redis'] },
      neo4j: { delivery: 'bundled', types: ['neo4j'] }, trino: { delivery: 'bundled', types: ['trino'] },
      cassandra: { delivery: 'module', types: ['cassandra'], why: 'heavy' } } };
  const attributed = { imageBytes: 100_000_000, codeBytes: 50_000_000, factor: 2, rows: [
    { owner: 'core', codeBytes: 30_000_000, imageBytes: 60_000_000 },
    { owner: 'sql', codeBytes: 10_000_000, imageBytes: 20_000_000 },
    { owner: 'neo4j', codeBytes: 3_300_000, imageBytes: 6_600_000 },
    { owner: 'cassandra', codeBytes: 5_000_000, imageBytes: 10_000_000 },
    { owner: 'redis', codeBytes: 800_000, imageBytes: 1_600_000 },
    { owner: 'trino', codeBytes: 500_000, imageBytes: 1_000_000 } ] };
  const { problems, notes } = checkBudget(attributed, delivery);
  assert.deepEqual(problems, [
    'neo4j adds 6.6 MB to the image, over the 3 MB budget of a bundled connector: deliver it as a module or list it in '
      + 'budget.exempt with the reason',
    'cassandra is delivered as a module but is compiled into this image (10.0 MB)']);
  assert.deepEqual(notes, ['redis is listed in budget.exempt but adds only 1.6 MB']);
  const text = render(attributed, delivery);
  assert.match(text, /Native image 100\.0 MB, code area 50\.0 MB/);
  assert.match(text, /sql\s+10\.0\s+20\.0 {2}bundled \(exempt\)/);
  assert.match(text, /connectors\s+19\.6\s+39\.2/);
});
