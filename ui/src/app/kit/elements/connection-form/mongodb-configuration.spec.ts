import { JsonObject } from '../../../core/api/types';
import { mongodbFormConfig } from './mongodb-configuration';

describe('MongoDB form compatibility', () => {
  it.each([
    { uri: 'mongodb://mongo.example.test/orders', env: 'dev', tls: false },
    { uri: 'mongodb://mongo.example.test/orders', env: 'prod', tls: true },
    { uri: 'mongodb+srv://cluster.example.test/orders', env: 'dev', tls: true },
    { uri: 'mongodb+srv://cluster.example.test/orders?tls=false', env: 'dev', tls: false },
    { uri: 'mongodb+srv://cluster.example.test/orders?ssl=false', env: 'dev', tls: false },
    { uri: 'mongodb://mongo.example.test/orders?ssl=true', env: 'dev', tls: true },
    { uri: 'mongodb://mongo.example.test/orders?tls=false&ssl=true', env: 'dev', tls: false },
    { uri: 'mongodb://mongo.example.test/orders?ssl=false&tls=true', env: 'dev', tls: true },
    { uri: 'mongodb://mongo.example.test/orders?tls=false;TLS=TrUe', env: 'dev', tls: true },
    { uri: 'mongodb://mongo.example.test/orders?tls=%74rue', env: 'dev', tls: true },
    { uri: 'mongodb+srv://cluster.example.test/orders?tls=false', env: 'prod', tls: true },
  ])('preserves the effective TLS default of $uri on $env', ({ uri, env, tls }) => {
    const current = { connection: { uri }, credentials: { authSource: 'users' } };
    expect(mongodbFormConfig('mongodb', current, env)).toEqual({
      connection: { mode: 'uri', uri, tls }, credentials: { authSource: 'users' },
    });
    expect(current).toEqual({ connection: { uri }, credentials: { authSource: 'users' } });
  });

  it.each([true, false, 'auto'])('keeps an explicit TLS setting of %s', (tls) => {
    const current = { connection: { uri: 'mongodb+srv://cluster.example.test/orders', tls } };
    expect(mongodbFormConfig('mongodb', current, 'prod')!['connection']).toEqual({ ...current.connection, mode: 'uri' });
  });

  it('leaves an invalid URI intact so the connector can report it', () => {
    const uri = 'mongodb://mongo.example.test/orders?tls=%broken';
    expect(mongodbFormConfig('mongodb', { connection: { uri } }, 'dev')).toEqual({
      connection: { uri, mode: 'uri', tls: false },
    });
  });

  it('keeps explicit host mode and unrelated connector configurations unchanged', () => {
    const config: JsonObject = { connection: { mode: 'host', host: 'mongo.example.test', uri: 'mongodb://stale.example.test' } };
    expect(mongodbFormConfig('mongodb', config, 'dev')).toBe(config);
    expect(mongodbFormConfig('mysql', config, 'dev')).toBe(config);
    expect(mongodbFormConfig('mongodb', null, 'dev')).toBeNull();
  });
});
