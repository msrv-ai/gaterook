import { addressProblem, connectRequest, emptyConnectModel, externalId, fieldProblem, isPlainHttp, storeName, storeNameProblem } from './store-connect';

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

  it('accepts HTTPS and HTTP addresses, leaving HTTP consent to the connection dialog', () => {
    expect(addressProblem('https://vault.acme.internal')).toBeNull();
    expect(addressProblem('http://127.0.0.1:8200')).toBeNull();
    expect(addressProblem('http://localhost:8200')).toBeNull();
    expect(addressProblem('http://vault.acme.internal')).toBeNull();
    expect(addressProblem('http://192.0.2.10:8200/')).toBeNull();
    expect(addressProblem('ftp://vault.acme.internal')).toBe('Use https:// or http:// for the Vault address.');
    expect(addressProblem('vault')).toMatch(/isn't an address/);
  });

  it('detects HTTP regardless of host, surrounding whitespace or scheme case', () => {
    for (const address of ['http://vault.acme.internal', ' HTTP://192.0.2.10:8200/ ', 'http://localhost:8200', 'http://[::1]:8200']) {
      expect(isPlainHttp(address)).toBe(true);
    }
    for (const address of ['', 'vault', 'https://vault.acme.internal', 'ftp://vault.acme.internal']) {
      expect(isPlainHttp(address)).toBe(false);
    }
  });

  it('includes the HTTP opt-in only for a confirmed HTTP Vault request', () => {
    const http = { ...m, address: 'http://vault.acme.internal:8200/', method: 'token' as const };
    expect(connectRequest('vault', 'vault', http, {}).config['allowPlainHttp']).toBeUndefined();
    expect(connectRequest('vault', 'vault', http, {}, true).config).toEqual({
      address: 'http://vault.acme.internal:8200', auth: { method: 'token' }, allowPlainHttp: true,
    });
    expect(connectRequest('vault', 'vault', { ...http, address: 'https://vault.acme.internal' }, {}, true).config['allowPlainHttp']).toBeUndefined();
    expect(connectRequest('aws-sm', 'aws', http, {}, true).config['allowPlainHttp']).toBeUndefined();
    expect(connectRequest('gcp-sm', 'gcp', http, {}, true).config['allowPlainHttp']).toBeUndefined();
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
    const key = connectRequest('aws-sm', 'a', { ...m, awsSource: 'accessKey', accessKeyId: 'AKIAIOSFODNN7EXAMPLE' }, { '/credentials/secretAccessKey': 's3cr3t' });
    expect(key.config).toEqual({ region: 'us-east-1', credentials: { source: 'accessKey', accessKeyId: 'AKIAIOSFODNN7EXAMPLE' } });
    expect(key.secrets).toEqual({ '/credentials/secretAccessKey': 's3cr3t' });

    const vault = connectRequest('vault', 'vault', { ...m, address: 'https://vault.acme.internal/', roleId: 'gaterook', namespace: 'team' }, { '/auth/secretId': 'x' });
    expect(vault).toEqual({
      kind: 'vault',
      name: 'vault',
      config: { address: 'https://vault.acme.internal', auth: { method: 'approle', roleId: 'gaterook' }, namespace: 'team' },
      secrets: { '/auth/secretId': 'x' },
    });
    expect(connectRequest('vault', 'vault', { ...m, address: 'https://v', method: 'cli-token' }, {}).config).toEqual({ address: 'https://v', auth: { method: 'cli-token' } });

    const gcp = connectRequest('gcp-sm', 'gcp-acme-stage', { ...m, project: 'acme-stage', impersonate: 'gw-secrets@acme-stage.iam.gserviceaccount.com' }, {});
    expect(gcp.config).toEqual({ project: 'acme-stage', credentials: { source: 'adc', impersonate: 'gw-secrets@acme-stage.iam.gserviceaccount.com' } });
    expect('secrets' in gcp).toBe(false);
  });
});

describe('Vault names and token files', () => {
  it('a name follows the core rule and is not taken', () => {
    expect(storeNameProblem('', [])).toBe('Name the store, for example vault-prod.');
    expect(storeNameProblem('Vault Prod', [])).toContain("isn't a store name");
    expect(storeNameProblem('vault--prod', [])).toContain("isn't a store name");
    expect(storeNameProblem('vault', ['vault'])).toBe('A store named vault already exists. Choose another name.');
    expect(storeNameProblem('vault-prod', ['vault'])).toBeNull();
    expect(fieldProblem('vault', 'name', { ...emptyConnectModel(), name: 'vault' }, ['vault'])).toMatch(/already exists/);
    expect(fieldProblem('aws-sm', 'name', emptyConnectModel(), ['vault'])).toBeNull();
  });

  it('a new Vault starts as vault, then vault-2; the name the person typed wins', () => {
    expect(storeName('vault', emptyConnectModel(), [])).toBe('vault');
    expect(storeName('vault', emptyConnectModel(), ['vault'])).toBe('vault-2');
    expect(storeName('vault', { ...emptyConnectModel(), name: ' vault-stage ' }, ['vault'])).toBe('vault-stage');
  });

  it('a token read from a file is the token-file method with the grant, and no stored token', () => {
    const m = { ...emptyConnectModel(), address: 'https://vault.acme.internal/', method: 'token' as const, tokenSource: 'file' as const };
    expect(connectRequest('vault', 'vault', m, {}, false, { token: 'ft_1', path: '/Users/you/.vault-agent/token' })).toEqual({
      kind: 'vault', name: 'vault', config: { address: 'https://vault.acme.internal', auth: { method: 'token-file' } }, files: { '/auth/file': 'ft_1' },
    });
    expect(connectRequest('vault', 'vault', { ...m, tokenSource: 'stored' }, { '/auth/token': 't' })).toEqual({
      kind: 'vault', name: 'vault', config: { address: 'https://vault.acme.internal', auth: { method: 'token' } }, secrets: { '/auth/token': 't' },
    });
  });
});

describe('Azure Key Vault', () => {
  const m = { ...emptyConnectModel(), vault: 'acme-kv', tenantId: 'contoso.onmicrosoft.com', clientId: '0a1b2c3d-1111-4222-8333-444455556666' };

  it('checks the vault, the tenant and the application in the core’s words', () => {
    expect(fieldProblem('azure-kv', 'vault', m)).toBeNull();
    expect(fieldProblem('azure-kv', 'vault', { ...m, vault: '' })).toBe('Enter the name of the key vault, for example acme-kv.');
    expect(fieldProblem('azure-kv', 'vault', { ...m, vault: 'acme-kv.vault.azure.net' })).toMatch(/not its address/);
    for (const vault of ['kv', 'Acme-kv', '1acme', 'acme-', 'ac--me', 'acme_kv', 'a'.repeat(25)]) {
      expect(fieldProblem('azure-kv', 'vault', { ...m, vault })).toMatch(/isn't a key vault name/);
    }
    expect(fieldProblem('azure-kv', 'vault', { ...m, vault: 'a'.repeat(24) })).toBeNull();
    expect(fieldProblem('azure-kv', 'tenantId', { ...m, tenantId: '' })).toMatch(/^Enter the tenant/);
    expect(fieldProblem('azure-kv', 'tenantId', { ...m, tenantId: 'common' })).toMatch(/aren’t tenants/);
    expect(fieldProblem('azure-kv', 'tenantId', { ...m, tenantId: 'not a tenant' })).toMatch(/isn't a tenant/);
    expect(fieldProblem('azure-kv', 'tenantId', { ...m, tenantId: '6f1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d' })).toBeNull();
    expect(fieldProblem('azure-kv', 'clientId', { ...m, clientId: '' })).toMatch(/^Enter the application/);
    expect(fieldProblem('azure-kv', 'clientId', { ...m, clientId: 'my-app' })).toMatch(/isn't an application ID/);
    // Other kinds' fields aren't on its form, nor are its fields on theirs.
    expect(fieldProblem('azure-kv', 'region', { ...m, region: 'Frankfurt' })).toBeNull();
    expect(fieldProblem('gcp-sm', 'vault', { ...m, vault: '' })).toBeNull();
  });

  it('names the store after its vault', () => {
    expect(storeName('azure-kv', m, [])).toBe('azure-acme-kv');
    expect(storeName('azure-kv', m, ['azure-acme-kv'])).toBe('azure-acme-kv-2');
    expect(storeName('azure-kv', { ...m, vault: '' }, [])).toBe('azure-key-vault');
  });

  it('sends the vault, the cloud, the tenant and the service principal; its secret or certificate goes apart', () => {
    const secret = connectRequest('azure-kv', 'azure-acme-kv', { ...m, vault: ' acme-kv ' }, { '/credentials/clientSecret': 'cs' });
    expect(secret).toEqual({
      kind: 'azure-kv',
      name: 'azure-acme-kv',
      config: { vault: 'acme-kv', cloud: 'AzureCloud', tenantId: 'contoso.onmicrosoft.com', credentials: { source: 'clientSecret', clientId: '0a1b2c3d-1111-4222-8333-444455556666' } },
      secrets: { '/credentials/clientSecret': 'cs' },
    });
    const certificate = connectRequest('azure-kv', 'azure-acme-kv', { ...m, azureCloud: 'AzureUSGovernment', azureSource: 'clientCertificate' }, { '/credentials/certificate': 'pem' });
    expect(certificate.config).toEqual({ vault: 'acme-kv', cloud: 'AzureUSGovernment', tenantId: 'contoso.onmicrosoft.com', credentials: { source: 'clientCertificate', clientId: '0a1b2c3d-1111-4222-8333-444455556666' } });
    expect(certificate.secrets).toEqual({ '/credentials/certificate': 'pem' });
    expect(JSON.stringify(certificate.config)).not.toContain('pem');
  });
});
