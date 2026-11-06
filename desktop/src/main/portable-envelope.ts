// Portable file encryption. The fixed header is authenticated together with every payload byte.
// Keep version 1's KDF costs fixed: an imported file must not select an expensive or weakened derivation.
import { createCipheriv, createDecipheriv, randomBytes, scrypt } from 'node:crypto';

export interface PortableEnvelope {
  readonly magic: string;
  readonly maxBytes: number;
  readonly invalid: string;
  readonly cannotOpen: string;
  readonly invalidSize: string;
}


const VERSION = 1;
const HEADER_BYTES = 68;
const TAG_BYTES = 16;
export const ENVELOPE_OVERHEAD = HEADER_BYTES + TAG_BYTES;
const SALT_BYTES = 32;
const NONCE_BYTES = 12;
const COST = 2 ** 17;
const BLOCK_SIZE = 8;
const PARALLELISM = 1;
const KEY_BYTES = 32;
const MAX_MEMORY = 256 * 1024 * 1024;

/** Preserve all characters exactly, including leading and trailing spaces; never normalize a passphrase. */
export function validatePortablePassphrase(passphrase: unknown): asserts passphrase is string {
  if (typeof passphrase !== 'string' || passphrase.length < 12 || passphrase.length > 1024
      || !passphrase.isWellFormed()) {
    throw new Error('Use a passphrase of 12 to 1,024 characters.');
  }
}

async function deriveKey(passphrase: string, salt: Buffer): Promise<Buffer> {
  const password = Buffer.from(passphrase, 'utf8');
  try {
    return await new Promise<Buffer>((resolve, reject) => {
      scrypt(password, salt, KEY_BYTES,
        { N: COST, r: BLOCK_SIZE, p: PARALLELISM, maxmem: MAX_MEMORY },
        (error, key) => error ? reject(error) : resolve(key));
    });
  } finally {
    password.fill(0);
  }
}

function header(format: PortableEnvelope): Buffer {
  const value = Buffer.alloc(HEADER_BYTES);
  if (Buffer.byteLength(format.magic, 'ascii') !== 8) throw new Error('Invalid portable file format.');
  Buffer.from(format.magic, 'ascii').copy(value);
  value[8] = VERSION;
  value[9] = 1; // scrypt
  value[10] = 1; // AES-256-GCM
  value[11] = 0; // reserved
  value.writeUInt32BE(COST, 12);
  value.writeUInt32BE(BLOCK_SIZE, 16);
  value.writeUInt32BE(PARALLELISM, 20);
  randomBytes(SALT_BYTES).copy(value, 24);
  randomBytes(NONCE_BYTES).copy(value, 56);
  return value;
}

/** No plaintext temporary file is needed. The caller owns and must erase the input payload after use. */
export async function encryptPortable(payload: Buffer, passphrase: unknown, format: PortableEnvelope): Promise<Buffer> {
  validatePortablePassphrase(passphrase);
  if (!Buffer.isBuffer(payload) || payload.length === 0
      || payload.length > format.maxBytes - ENVELOPE_OVERHEAD) {
    throw new Error(format.invalidSize);
  }
  const metadata = header(format);
  const key = await deriveKey(passphrase, metadata.subarray(24, 56));
  try {
    const cipher = createCipheriv('aes-256-gcm', key, metadata.subarray(56, 68), { authTagLength: TAG_BYTES });
    cipher.setAAD(metadata);
    return Buffer.concat([metadata, cipher.update(payload), cipher.final(), cipher.getAuthTag()]);
  } finally {
    key.fill(0);
  }
}

/** Return plaintext only after authentication succeeds. Reject unsupported KDF costs before running scrypt. */
export async function decryptPortable(file: Buffer, passphrase: unknown, format: PortableEnvelope): Promise<Buffer> {
  validatePortablePassphrase(passphrase);
  if (!Buffer.isBuffer(file) || file.length <= HEADER_BYTES + TAG_BYTES || file.length > format.maxBytes) {
    throw new Error(format.invalid);
  }
  // Snapshot the small authenticated header before the asynchronous KDF; never trust mutable file views as metadata.
  const metadata = Buffer.from(file.subarray(0, HEADER_BYTES));
  if (!metadata.subarray(0, 8).equals(Buffer.from(format.magic, 'ascii')) || metadata[8] !== VERSION
      || metadata[9] !== 1 || metadata[10] !== 1 || metadata[11] !== 0
      || metadata.readUInt32BE(12) !== COST || metadata.readUInt32BE(16) !== BLOCK_SIZE
      || metadata.readUInt32BE(20) !== PARALLELISM) {
    throw new Error(format.invalid);
  }
  const key = await deriveKey(passphrase, metadata.subarray(24, 56));
  let plaintext: Buffer | null = null;
  try {
    const decipher = createDecipheriv('aes-256-gcm', key, metadata.subarray(56, 68), { authTagLength: TAG_BYTES });
    decipher.setAAD(metadata);
    decipher.setAuthTag(file.subarray(file.length - TAG_BYTES));
    plaintext = decipher.update(file.subarray(HEADER_BYTES, file.length - TAG_BYTES));
    const tail = decipher.final();
    const result = tail.length === 0 ? plaintext : Buffer.concat([plaintext, tail]);
    if (result !== plaintext) plaintext.fill(0);
    tail.fill(0);
    plaintext = null; // The authenticated buffer belongs to the caller now.
    return result;
  } catch {
    throw new Error(format.cannotOpen);
  } finally {
    plaintext?.fill(0);
    key.fill(0);
  }
}
