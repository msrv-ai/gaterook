import { SecretStore } from '../../core/api/secrets.api';

/** Vault namespaces are case-sensitive; URL origins and default ports follow URL normalization. */
export function vaultIdentity(address: string, namespace = ''): string | null {
  try {
    const url = new URL(address.trim());
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash) return null;
    return `${url.origin}${url.pathname.replace(/\/+$/, '')}|${namespace.trim().replace(/^\/+|\/+$/g, '')}`;
  } catch { return null; }
}

export function matchingVault(stores: readonly SecretStore[], address: string, namespace = ''): SecretStore | null {
  const identity = vaultIdentity(address, namespace);
  return identity ? stores.find(store => store.kind === 'vault' && typeof store.config['address'] === 'string'
    && vaultIdentity(store.config['address'], typeof store.config['namespace'] === 'string' ? store.config['namespace'] : '') === identity) ?? null : null;
}
