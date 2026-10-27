import { addressProblem, connectRequest, emptyConnectModel, externalId, fieldProblem, storeName } from './store-connect';

describe('connect a store', () => {
  const m = emptyConnectModel();

  it('checks each kind’s fields in the core’s words, only those on its form', () => {
    expect(fieldProblem('aws-sm', 'roleArn', m)).toMatch(/^Enter the role/);
    expect(fieldProblem('aws-sm', 'roleArn', { ...m, roleArn: 'gw-readonly' })).toBe("gw-readonly isn't a role ARN. It looks like arn:aws:iam::412398765012:role/gw-secrets-reader.");
    expect(fieldProblem('aws-sm', 'roleArn', { ...m, roleArn: 'arn:aws:iam::412398765012:role/gw-secrets-reader' })).toBeNull();
    expect(fieldProblem('aws-sm', 'region', { ...m, region: 'Frankfurt' })).toBe('Choose the region of the secrets.');
    // The access key ID counts only with the access-key sign-in, the profile only with the profile one.
    expect(fieldProblem('aws-sm', 'accessKeyId', m)).toBeNull();
    expect(fieldProblem('aws-sm', 'accessKeyId', { ...m, awsSource: 'accessKey' })).toBe('Enter the access key ID.');
    expect(fieldProblem('aws-sm', 'accessKeyId', { ...m, awsSource: 'accessKey', accessKeyId: 'AKIA123' })).toMatch(/AKIA or ASIA/);
    expect(fieldProblem('aws-sm', 'profile', { ...m, profile: ' ' })).toMatch(/^Name the profile/);
    expect(fieldProblem('aws-sm', 'address', m)).toBeNull();

    expect(fieldProblem('vault', 'address', m)).toMatch(/^Enter the address/);
    expect(fieldProblem('vault', 'roleId', m)).toBe('Enter the AppRole role ID.');
    expect(fieldProblem('vault', 'roleId', { ...m, method: 'token' })).toBeNull();
    expect(fieldProblem('vault', 'caCert', { ...m, caCert: 'not a pem' })).toMatch(/PEM/);
    expect(fieldProblem('gcp-sm', 'project', { ...m, project: 'Acme' })).toBe("Acme isn't a project ID: 6–30 lowercase letters, digits and dashes.");
    expect(fieldProblem('gcp-sm', 'impersonate', { ...m, impersonate: 'someone@gmail.com' })).toMatch(/isn't a service account/);
    expect(fieldProblem('gcp-sm', 'impersonate', { ...m, impersonate: 'gw-secrets@acme-stage.iam.gserviceaccount.com' })).toBeNull();
  });

  it('allows https anywhere and plain http only to this computer (S1 StoreAddress)', () => {
    expect(addressProblem('https://vault.acme.internal')).toBeNull();
    expect(addressProblem('http://127.0.0.1:8200')).toBeNull();
    expect(addressProblem('http://localhost:8200')).toBeNull();
    expect(addressProblem('http://vault.acme.internal')).toBe('Use https://. Plain http is allowed only to this computer (127.0.0.1).');
    expect(addressProblem('vault')).toMatch(/isn't an address/);
  });

  it('makes an ExternalId as the core does: gw-ext- and 12 hex digits', () => {
    expect(externalId(new Uint8Array([0x3f, 0x9a, 0x0e, 0x01, 0xab, 0xff]))).toBe('gw-ext-3f9a0e01abff');
    expect(externalId()).toMatch(/^gw-ext-[0-9a-f]{12}$/);
  });

  it('names the store after what it reads, without taking a name in use', () => {
    expect(storeName('vault', m, [])).toBe('vault');
    expect(storeName('vault', m, ['vault', 'vault-2'])).toBe('vault-3');
    expect(storeName('gcp-sm', { ...m, project: 'acme-stage' }, [])).toBe('gcp-acme-stage');
    expect(storeName('aws-sm', { ...m, region: 'eu-central-1' }, [])).toBe('aws-eu-central-1');
  });

  it('sends only the fields of the kind and its sign-in; sign-in values go apart, by pointer', () => {
    const aws = connectRequest('aws-sm', 'aws-eu-central-1', { ...m, region: 'eu-central-1', profile: 'acme-stage', roleArn: ' arn:aws:iam::412398765012:role/r ', externalId: 'gw-ext-3f9a0e01abff' }, {});
    expect(aws).toEqual({
      kind: 'aws-sm',
      name: 'aws-eu-central-1',
      config: { region: 'eu-central-1', credentials: { source: 'profile', profile: 'acme-stage', roleArn: 'arn:aws:iam::412398765012:role/r', externalId: 'gw-ext-3f9a0e01abff' } },
    });
    const key = connectRequest('aws-sm', 'a', { ...m, awsSource: 'accessKey', accessKeyId: 'AKIAABCDEFGHIJKLMNOP' }, { '/credentials/secretAccessKey': 's3cr3t' });
    expect(key.config).toEqual({ region: 'us-east-1', credentials: { source: 'accessKey', accessKeyId: 'AKIAABCDEFGHIJKLMNOP' } });
    expect(key.secrets).toEqual({ '/credentials/secretAccessKey': 's3cr3t' });

    const vault = connectRequest('vault', 'vault', { ...m, address: 'https://vault.acme.internal/', roleId: 'mcp-gateway', namespace: 'team' }, { '/auth/secretId': 'x' });
    expect(vault).toEqual({
      kind: 'vault',
      name: 'vault',
      config: { address: 'https://vault.acme.internal', auth: { method: 'approle', roleId: 'mcp-gateway' }, namespace: 'team' },
      secrets: { '/auth/secretId': 'x' },
    });
    expect(connectRequest('vault', 'vault', { ...m, address: 'https://v', method: 'cli-token' }, {}).config).toEqual({ address: 'https://v', auth: { method: 'cli-token' } });

    const gcp = connectRequest('gcp-sm', 'gcp-acme-stage', { ...m, project: 'acme-stage', impersonate: 'gw-secrets@acme-stage.iam.gserviceaccount.com' }, {});
    expect(gcp.config).toEqual({ project: 'acme-stage', credentials: { source: 'adc', impersonate: 'gw-secrets@acme-stage.iam.gserviceaccount.com' } });
    expect('secrets' in gcp).toBe(false);
  });
});
