import { JsonObject } from '../../../core/api/types';

/** Older MongoDB connections keep their URI and its effective TLS default when opened for editing or duplication. */
export function mongodbFormConfig(type: string, current: JsonObject | null, env: string): JsonObject | null {
  if (type !== 'mongodb' || !current) return current;
  const connection = current['connection'];
  if (!connection || typeof connection !== 'object' || Array.isArray(connection)
    || (connection['mode'] !== undefined && connection['mode'] !== 'uri')
    || typeof connection['uri'] !== 'string' || !connection['uri'].trim()) return current;
  return { ...current, connection: {
    ...connection,
    mode: connection['mode'] ?? 'uri',
    tls: connection['tls'] !== undefined ? connection['tls'] : env === 'prod' || uriEnablesTls(connection['uri']),
  } };
}

/** Match the connector's TLS aliases and last-value precedence without rewriting or resolving the URI. */
function uriEnablesTls(uri: string): boolean {
  const options = new Map<string, string>();
  const question = uri.indexOf('?');
  if (question >= 0) {
    for (const part of uri.slice(question + 1).split(/[&;]/)) {
      const equal = part.indexOf('=');
      if (equal < 0) continue;
      const key = part.slice(0, equal).toLowerCase();
      if (key !== 'tls' && key !== 'ssl') continue;
      try {
        options.set(key, decodeURIComponent(part.slice(equal + 1)).toLowerCase());
      } catch {
        // Invalid escapes remain in the URI for the connector to report as a field error.
        return false;
      }
    }
  }
  const tls = options.get('tls') ?? options.get('ssl');
  return tls === 'true' || (uri.startsWith('mongodb+srv://') && tls === undefined);
}
