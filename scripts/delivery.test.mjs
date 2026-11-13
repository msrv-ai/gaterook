import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import test from 'node:test';
import { appAssembly, checkDelivery, readDelivery, typesByDelivery } from './delivery.mjs';

const dependency = (name, scope = '') => `<dependency><groupId>ai.msrv.gaterook</groupId>
  <artifactId>connectors-${name}</artifactId>${scope ? `<scope>${scope}</scope>` : ''}</dependency>`;

/** A checkout with the connectors `alpha` (bundled) and `beta` (module), as the given overrides change it. */
function fixture(t, { map, always = ['alpha'], profile = ['beta'], bridge = [],
  directories = ['alpha', 'beta', 'support'], modules = ['support', 'alpha', 'beta'] } = {}) {
  const scratch = new URL('../ai/', import.meta.url);
  mkdirSync(scratch, { recursive: true });
  const root = mkdtempSync(new URL('delivery-test-', scratch));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  for (const name of directories) {
    mkdirSync(join(root, 'connectors', name), { recursive: true });
    writeFileSync(join(root, 'connectors', name, 'pom.xml'), '<project/>');
  }
  mkdirSync(join(root, 'app'), { recursive: true });
  writeFileSync(join(root, 'pom.xml'), `<project><modules>
    <!-- <module>connectors/ghost</module> -->
    ${modules.map((m) => `<module>connectors/${m}</module>`).join('\n')}</modules></project>`);
  writeFileSync(join(root, 'app', 'pom.xml'), `<project><dependencies>
    <dependency><groupId>ai.msrv.gaterook</groupId><artifactId>core</artifactId></dependency>
    ${always.map((n) => dependency(n)).join('\n')}
    ${dependency('gamma', 'test')}
    </dependencies><profiles>
    <profile><id>bridge</id><dependencies>${bridge.map((n) => dependency(n)).join('\n')}</dependencies></profile>
    <profile><id>all-bundled</id><dependencies>${profile.map((n) => dependency(n)).join('\n')}</dependencies></profile>
    <profile><id>native</id><dependencies>${dependency('delta')}</dependencies></profile>
    </profiles></project>`);
  writeFileSync(join(root, 'connectors', 'delivery.json'), JSON.stringify(map ?? {
    schema: 1,
    budget: { bundledImageMegabytes: 3, exempt: {} },
    connectors: {
      alpha: { types: ['alpha', 'alpha-logs'], delivery: 'bundled' },
      beta: { types: ['beta'], delivery: 'module', why: 'Its driver is heavy.' },
    },
  }));
  return root;
}

test('this checkout: every connector has a delivery and the app assembly follows it', () => {
  assert.deepEqual(checkDelivery(), []);
  const map = readDelivery();
  assert.equal(map.connectors.cassandra.delivery, 'module');
  assert.equal(map.connectors.kubernetes.delivery, 'bundled');
  assert.ok(typesByDelivery().bundled.includes('postgres'));
  assert.ok(!typesByDelivery().bundled.includes('oracle'), 'Oracle is its own unit of delivery');
  assert.ok(!appAssembly().always.includes('cassandra'));
});

test('a map and a build that agree have no problems', (t) => {
  const root = fixture(t);
  assert.deepEqual(checkDelivery(root), []);
  assert.deepEqual(appAssembly(root), { always: ['alpha'], allBundled: ['beta'], bridge: [] });
  assert.deepEqual(typesByDelivery(root), { bundled: ['alpha', 'alpha-logs'], module: ['beta'], bridge: [] });
});

test('a bridge connector is compiled in through the bridge profile and nowhere else', (t) => {
  const map = {
    schema: 1,
    budget: { bundledImageMegabytes: 3, exempt: {} },
    connectors: {
      alpha: { types: ['alpha'], delivery: 'bundled' },
      beta: { types: ['beta'], delivery: 'bridge', why: 'Its driver is heavy.' },
    },
  };
  const agreed = fixture(t, { map, profile: [], bridge: ['beta'] });
  assert.deepEqual(checkDelivery(agreed), []);
  assert.deepEqual(typesByDelivery(agreed), { bundled: ['alpha'], module: [], bridge: ['beta'] });

  assert.deepEqual(checkDelivery(fixture(t, { map, always: ['alpha', 'beta'], profile: ['beta'] })), [
    'beta is a bridge connector but app/pom.xml always depends on connectors-beta',
    'beta is a bridge connector but the bridge profile of app/pom.xml lacks connectors-beta',
    'beta is a bridge connector, so it does not belong in the all-bundled profile',
  ]);
  assert.deepEqual(checkDelivery(fixture(t, { bridge: ['alpha'] })),
    ['alpha is not a bridge connector, so it does not belong in the bridge profile']);
});

test('a module connector that is always compiled in is reported', (t) => {
  const root = fixture(t, { always: ['alpha', 'beta'], profile: [] });
  assert.deepEqual(checkDelivery(root), [
    'beta is a module but app/pom.xml always depends on connectors-beta',
    'beta is a module but the all-bundled profile of app/pom.xml lacks connectors-beta',
  ]);
});

test('a bundled connector must be a dependency of the assembly and not of the profile', (t) => {
  const root = fixture(t, { always: [], profile: ['alpha', 'beta'] });
  assert.deepEqual(checkDelivery(root), [
    'alpha is bundled but app/pom.xml does not depend on connectors-alpha',
    'alpha is bundled, so it does not belong in the all-bundled profile',
  ]);
});

test('a connector directory without an entry, an entry without a directory and a missing reactor module are reported', (t) => {
  const root = fixture(t, { directories: ['alpha', 'omega', 'support'], modules: ['support', 'alpha'] });
  assert.deepEqual(checkDelivery(root), [
    'connectors/omega has no entry in connectors/delivery.json',
    'connectors/omega is not a module of the root pom',
    'connectors/delivery.json lists beta, but connectors/beta is not a connector module',
  ]);
});

test('a dependency of the assembly that the map does not know is reported', (t) => {
  const root = fixture(t, { always: ['alpha', 'omega'] });
  assert.deepEqual(checkDelivery(root),
    ['app/pom.xml depends on connectors-omega, which connectors/delivery.json does not list']);
});

test('the map is refused when its own shape is wrong', (t) => {
  const bad = (connectors, budget = { bundledImageMegabytes: 3, exempt: {} }) =>
    () => readDelivery(fixture(t, { map: { schema: 1, budget, connectors } }));
  assert.throws(bad({ alpha: { types: ['alpha'], delivery: 'shipped' } }), /delivery must be one of bundled, module, bridge/);
  assert.throws(bad({ alpha: { types: [], delivery: 'bundled' } }), /types must list/);
  assert.throws(bad({ alpha: { types: ['Alpha'], delivery: 'bundled' } }), /not a connector type id/);
  assert.throws(bad({ alpha: { types: ['x'], delivery: 'bundled' }, beta: { types: ['x'], delivery: 'bundled' } }),
    /x is listed by both alpha and beta/);
  assert.throws(bad({ beta: { types: ['beta'], delivery: 'module' } }), /says why it is not compiled in/);
  assert.throws(bad({ beta: { types: ['beta'], delivery: 'bridge' } }), /says why it is not compiled in/);
  assert.throws(bad({ alpha: { types: ['alpha'], delivery: 'bundled' } }, { bundledImageMegabytes: 0, exempt: {} }),
    /positive number/);
  assert.throws(bad({ beta: { types: ['beta'], delivery: 'module', why: 'heavy' } },
    { bundledImageMegabytes: 3, exempt: { beta: 'no' } }), /beta, which is not bundled/);
  assert.throws(bad({ alpha: { types: ['alpha'], delivery: 'bundled' } },
    { bundledImageMegabytes: 3, exempt: { ghost: 'no' } }), /ghost, which is not a connector/);
});
