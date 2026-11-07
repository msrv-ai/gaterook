// Full backups keep their version 1 authenticated envelope and fixed KDF parameters.
import { encryptPortable, decryptPortable, validatePortablePassphrase, ENVELOPE_OVERHEAD } from './portable-envelope.ts';

/** Maximum complete encrypted file, including its fixed header and authentication tag. */
export const MAX_BACKUP_BYTES = 256 * 1024 * 1024;
export const MAX_BACKUP_PAYLOAD_BYTES = MAX_BACKUP_BYTES - ENVELOPE_OVERHEAD;
const FORMAT = {
  magic: 'GTRKFBK\0', maxBytes: MAX_BACKUP_BYTES,
  invalid: 'This file is not a supported encrypted GateRook full backup.',
  cannotOpen: 'The passphrase is incorrect, or the backup is damaged.',
  invalidSize: 'The backup is empty or exceeds the 256 MiB size limit.',
};
export function validateBackupPassphrase(passphrase: unknown): asserts passphrase is string {
  validatePortablePassphrase(passphrase);
}
export function encryptFullBackup(payload: Buffer, passphrase: unknown): Promise<Buffer> {
  return encryptPortable(payload, passphrase, FORMAT);
}
export function decryptFullBackup(file: Buffer, passphrase: unknown): Promise<Buffer> {
  return decryptPortable(file, passphrase, FORMAT);
}
