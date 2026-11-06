// Endpoint shares have a distinct authenticated type; a full backup can never be imported as a share.
import { encryptPortable, decryptPortable, validatePortablePassphrase } from './portable-envelope.ts';

export const MAX_SHARE_BYTES = 8 * 1024 * 1024;
export const MAX_SHARE_BUNDLE_BYTES = 6 * 1024 * 1024;
const FORMAT = {
  magic: 'GTRKSHR\0', maxBytes: MAX_SHARE_BYTES,
  invalid: 'This file is not a supported encrypted GateRook endpoint share.',
  cannotOpen: 'The passphrase is incorrect, or the endpoint share is damaged.',
  invalidSize: 'The endpoint share is empty or exceeds the 8 MiB size limit.',
};
export function validateSharePassphrase(passphrase: unknown): asserts passphrase is string {
  validatePortablePassphrase(passphrase);
}
export function encryptEndpointShare(payload: Buffer, passphrase: unknown): Promise<Buffer> {
  return encryptPortable(payload, passphrase, FORMAT);
}
export function decryptEndpointShare(file: Buffer, passphrase: unknown): Promise<Buffer> {
  return decryptPortable(file, passphrase, FORMAT);
}
