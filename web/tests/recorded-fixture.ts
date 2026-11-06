import { readFileSync } from 'node:fs';
import { gunzipSync } from 'node:zlib';
import { createHash } from 'node:crypto';
import { resolve } from 'node:path';
export function recordedTar(path: string, expectedSha256: string): Map<string, string> {
  const archive = readFileSync(resolve(path));
  if (createHash('sha256').update(archive).digest('hex') !== expectedSha256) throw Error('Recorded fixture archive hash mismatch');
  const tar = gunzipSync(archive); const files = new Map<string, string>(); let offset = 0;
  while (offset + 512 <= tar.length && tar[offset] !== 0) {
    const header = tar.subarray(offset, offset + 512);
    const name = header.subarray(0, 100).toString().replace(/\0.*$/s, '');
    const size = parseInt(header.subarray(124, 136).toString().replace(/\0.*$/s, '').trim(), 8);
    if (name.startsWith('/') || name.split('/').includes('..') || !Number.isSafeInteger(size) || size < 0 || size > 32 * 1024 * 1024) throw Error('Unsafe recorded fixture');
    files.set(name, tar.subarray(offset + 512, offset + 512 + size).toString('utf8'));
    offset += 512 + Math.ceil(size / 512) * 512;
  }
  return files;
}
