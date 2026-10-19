import { readFile, writeFile, rename, mkdir, open, unlink } from 'node:fs/promises';
import { readFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { performance } from 'node:perf_hooks';

const projectRoot = fileURLToPath(new URL('../../', import.meta.url));
let localStorage;
try {
  localStorage = readFileSync(path.join(projectRoot, 'local.properties'), 'utf8')
    .match(/^\s*miaStorageRoot\s*=\s*(.*?)\s*$/m)?.[1];
} catch (error) {
  if (error.code !== 'ENOENT') throw error;
}
const storage = path.resolve(projectRoot, process.env.MIA_STORAGE_ROOT || localStorage || '.local');
const instance = process.env.MIA_CLIENT_INSTANCE ?? '';
if (instance && !/^[a-z0-9][a-z0-9-]{0,31}$/.test(instance)) {
  throw new Error('MIA_CLIENT_INSTANCE must be a lowercase slug of 1-32 letters, digits or hyphens');
}
export const root = path.join(storage, 'run', instance ? `client-${instance}` : 'client', 'automation');

export async function action(action, params = {}, timeout = 15000) {
  const ready = JSON.parse(await readFile(path.join(root, 'ready.json'), 'utf8'));
  try { process.kill(ready.pid, 0); }
  catch { throw new Error('The isolated client from ready.json is no longer running. Launch runClient.'); }
  const id = randomUUID();
  const request = path.join(root, 'requests', id + '.json');
  const response = path.join(root, 'responses', id + '.json');
  const started = performance.now();
  await mkdir(path.dirname(request), { recursive: true });
  await writeFile(request + '.tmp', JSON.stringify({ session: ready.session, action, params }));
  await rename(request + '.tmp', request);
  while (performance.now() - started < timeout) {
    let raw;
    try { raw = await readFile(response, 'utf8'); }
    catch (error) { if (error.code !== 'ENOENT') throw error; }
    if (raw) {
      const reply = JSON.parse(raw);
      // Consumed responses are deleted; leaving them filled the disk (110k files, 35 GB, 2026-10-14).
      await unlink(response).catch(() => {});
      if (!reply.ok) throw new Error(reply.error);
      return { ...reply.result, latencyMs: Math.round(performance.now() - started) };
    }
    await new Promise(resolve => setTimeout(resolve, 10));
  }
  throw new Error(`Client control timeout: ${action}. Check the current isolated client/session.`);
}

export async function screenshot() {
  const reply = await action('screenshot');
  const deadline = performance.now() + 10000;
  while (performance.now() < deadline) {
    let handle;
    try {
      handle=await open(reply.path,'r');
      const {size}=await handle.stat();
      if(size>=45){
        const head=Buffer.alloc(24),tail=Buffer.alloc(12);
        await handle.read(head,0,head.length,0);await handle.read(tail,0,tail.length,size-tail.length);
        if(head.subarray(0,8).equals(Buffer.from([137,80,78,71,13,10,26,10]))
           &&tail.equals(Buffer.from([0,0,0,0,73,69,78,68,174,66,96,130])))
          return {...reply,width:head.readUInt32BE(16),height:head.readUInt32BE(20),bytes:size};
      }
    } catch(error) {if(error.code!=='ENOENT')throw error;}
    finally {await handle?.close();}
    await new Promise(resolve => setTimeout(resolve, 20));
  }
  throw new Error('Native screenshot did not finish writing');
}
