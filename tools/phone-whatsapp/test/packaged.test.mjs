import test from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, rmSync, symlinkSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';

test('the actual Android helper archive starts its real isolated runtime without WhatsApp', t => {
  const source = dirname(dirname(fileURLToPath(import.meta.url)));
  const directory = mkdtempSync(join(tmpdir(), 'raad-wa-archive-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const archive = join(directory, 'helper.tgz');
  execFileSync('python3', [join(source, 'build-asset.py'), '--output', archive]);
  execFileSync('python3', ['-c', 'import tarfile,sys; tarfile.open(sys.argv[1]).extractall(sys.argv[2],filter="data")', archive, directory]);
  assert.equal(readFileSync(join(directory, 'package-lock.json'), 'utf8'), readFileSync(join(source, 'package-lock.json'), 'utf8'));
  symlinkSync(join(source, 'node_modules'), join(directory, 'node_modules'), 'dir');
  const output = execFileSync(process.execPath, [join(directory, 'verify-runtime.mjs')], { cwd: directory, timeout: 20_000, encoding: 'utf8' });
  assert.match(output, /Real socket construction and isolated runtime verified locally/);
});
