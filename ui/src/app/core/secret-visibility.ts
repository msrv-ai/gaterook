import { ShortLivedCredential } from './api/secrets.api';

/** Vault database credentials remain supported by the core but are hidden from the current UI. */
function isVaultDynamicReference(ref: string | null | undefined): boolean {
  return /^vault:\/\/[^?#]+\/creds\/[^/?#]+$/i.test(ref ?? '');
}

export function isVaultDynamicCredential(credential: ShortLivedCredential): boolean {
  return /^vault dynamic\b/i.test(credential.mechanism) || isVaultDynamicReference(credential.identity);
}

export function isVaultDynamicSecret(secret: { readonly source: string; readonly state: string; readonly ref?: string | null }): boolean {
  return secret.source === 'vault-dynamic' || secret.source === 'dynamic'
    || (secret.source === 'vault' && secret.state === 'short-lived')
    || isVaultDynamicReference(secret.ref);
}
