import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
const source = await readFile(new URL('../dashboard/web/js/logs.js', import.meta.url), 'utf8');
const { diagnosticLine } = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));
const row = diagnosticLine(JSON.stringify({ timeMs: 0, session: 'abc', opModeState: 'running', opMode: 'TeleOp',
  javaUsedBytes: 10485760, javaHeapLimitBytes: 104857600, memory: { nativeAllocatedBytes: 20971520, systemLowMemory: true },
  load: { appCpu: 125, loopMs: 50 }, camera: { state: 'STREAMING', fps: 30, previewEnabled: false, reportAgeMs: 1 } }));
assert.ok(row.includes('Java 10.0/100.0 MB'));
assert.ok(row.includes('CPU 125% (one core)'));
assert.ok(row.includes('LOW MEMORY'));
assert.ok(row.includes('STREAMING 30.0 FPS'));
assert.ok(row.includes('preview off'));
assert.equal(diagnosticLine('native crash text'), 'native crash text');
assert.ok(diagnosticLine(JSON.stringify({kind:'event',timeMs:0,level:'ERROR',source:'Camera',message:'Stream failed'})).includes('[ERROR] Camera: Stream failed'));
console.log('PASS: diagnostic display, missing metrics, raw Android log text');
