import { Injectable, inject } from '@angular/core';
import { SecretStore, SecretsApi, VaultReferences } from '../core/api/secrets.api';

interface Cached<T> { readonly at: number; readonly promise: Promise<T> }

/** Share short-lived metadata loads across secret fields; nothing is persisted and no values enter this cache. */
@Injectable({ providedIn: 'root' })
export class VaultReferenceCatalog {
  private readonly api = inject(SecretsApi);
  private storesCache: Cached<readonly SecretStore[]> | null = null;
  private readonly indexes = new Map<string, Cached<VaultReferences>>();
  private readonly ttl = 30_000;

  stores(refresh = false): Promise<readonly SecretStore[]> {
    if (!refresh && this.storesCache && Date.now() - this.storesCache.at < this.ttl) return this.storesCache.promise;
    const promise = this.api.stores().then((stores) => stores.filter((store) => store.kind === 'vault'));
    const cached = { at: Date.now(), promise };
    this.storesCache = cached;
    void promise.catch(() => { if (this.storesCache === cached) this.storesCache = null; });
    return promise;
  }

  references(store: SecretStore, refresh = false): Promise<VaultReferences> {
    const key = JSON.stringify([store.id, store.version ?? null, store.verified ?? null]);
    const previous = this.indexes.get(key);
    if (!refresh && previous && Date.now() - previous.at < this.ttl) return previous.promise;
    const promise = this.api.vaultReferences(store.id);
    const cached = { at: Date.now(), promise };
    this.indexes.set(key, cached);
    void promise.catch(() => { if (this.indexes.get(key) === cached) this.indexes.delete(key); });
    return promise;
  }
}
