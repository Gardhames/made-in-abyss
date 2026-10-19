import { action, screenshot } from './control.mjs';
import { readFileSync } from 'node:fs';
try {
  const name = process.argv[2] ?? 'status';
  const params = JSON.parse(process.argv[3] === '--stdin' ? readFileSync(0, 'utf8').replace(/^\uFEFF/, '') : process.argv[3] ?? '{}');
  console.log(JSON.stringify(name === 'screenshot' ? await screenshot() : await action(name, params)));
} catch (error) { console.error(String(error)); process.exitCode = 1; }
