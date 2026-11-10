import { choiceReason, credentialSources } from './connection-import-review';

describe('credential sources of an import candidate', () => {
  const prepared = [
    { field: '/credentials/password', source: 'local' as const },
    { field: '/credentials/token', source: 'file' as const, path: '/private/token.json', jsonPointer: '/token' },
    { field: '/credentials/key', source: 'reference' as const, reference: 'vault://kv/dev/app#key', store: 'team-vault' },
    { field: '/credentials/lease', source: 'vault-dynamic' as const, reference: 'vault://database/creds/reader' },
  ];

  it('describes each prepared source by its kind and origin, never by a value', () => {
    expect(credentialSources(prepared, {}).map(source => [source.field, source.kind, source.details])).toEqual([
      ['/credentials/password', 'literal', []],
      ['/credentials/token', 'file', ['/private/token.json', 'key /token']],
      ['/credentials/key', 'reference', ['vault://kv/dev/app#key', 'store team-vault']],
      ['/credentials/lease', 'dynamic', ['vault://database/creds/reader', 'store chosen when you import']],
    ]);
  });

  it('lets what the person entered or chose replace the prepared source of the same field', () => {
    const sources = credentialSources(prepared, {
      '/credentials/token': { source: 'local', value: 'canary-value' },
      '/credentials/key': { source: 'reference', ref: 'vault://kv/dev/mine#key', store: 'my-vault' },
      '/credentials/extra': { source: 'vault-dynamic', ref: 'vault://database/creds/other' },
    });
    expect(sources.map(source => [source.field, source.kind])).toEqual([
      ['/credentials/password', 'literal'], ['/credentials/token', 'entered'], ['/credentials/key', 'chosen'],
      ['/credentials/lease', 'dynamic'], ['/credentials/extra', 'chosen'],
    ]);
    expect(JSON.stringify(sources)).not.toContain('canary-value');
  });

  it('asks the person to choose a destination only for a credential read from a file or an external store', () => {
    expect(choiceReason(credentialSources([], {}))).toBeNull();
    expect(choiceReason(credentialSources([prepared[0]], {}))).toBeNull();
    expect(choiceReason(credentialSources([prepared[1]], {}))).toContain('a local file. Check');
    expect(choiceReason(credentialSources([prepared[2]], {}))).toContain('an external secret store. Check');
    expect(choiceReason(credentialSources([prepared[3]], {}))).toContain('an external secret store. Check');
    expect(choiceReason(credentialSources(prepared, {}))).toContain('a local file and an external secret store');
    // The person's own replacement is not a source the AI chose.
    expect(choiceReason(credentialSources([prepared[1]], { '/credentials/token': { source: 'local', value: 'x' } }))).toBeNull();
    expect(choiceReason(credentialSources([prepared[1]], { '/credentials/token': { source: 'reference', ref: 'vault://a/b#c' } }))).toBeNull();
  });
});
