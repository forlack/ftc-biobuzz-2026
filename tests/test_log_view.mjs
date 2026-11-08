import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
const source = await readFile(new URL('../dashboard/web/js/logs.js', import.meta.url), 'utf8');
const { diagnosticLine, problemRows, formatProblems } = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));
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

const snapshot = { events: [
  {kind:'event',timeMs:0,level:'INFO',source:'Aim lights',message:'GREEN'},
  {kind:'event',timeMs:1,level:'ERROR',source:'Camera',message:'Stream failed'},
  {kind:'event',timeMs:2,level:'ERROR',source:'Camera',message:'Stream failed'},
].map(JSON.stringify).join('\n'), android: [
  '11-07 12:48:32.620 986 1171 D UvcStream: frame=6864: previous frame failed to send EOF',
  '11-07 12:48:32.720 986 1171 D UvcStream: frame=6865: previous frame failed to send EOF',
  '11-07 12:47:34.965 986 1122 E UvcDeviceHandle: Failed to get min zoom : error -9',
  '11-07 12:48:33.000 986 1171 F libc: Fatal signal 6',
].join('\n'), context:{}, collectedAt:100 };
const problems = problemRows(snapshot);
assert.equal(problems.length, 3);
assert.equal(problems.find(p=>p.source==='Camera').count,2);
assert.equal(problems.find(p=>p.source==='UvcStream').count,2);
assert.ok(!formatProblems(snapshot).includes('GREEN'));
assert.ok(formatProblems(snapshot).includes('Fatal signal 6'));
assert.equal(problemRows({context:{timeMs:0,opModeState:'running',camera:{state:'ERROR',reportAgeMs:0}},collectedAt:100000}).length,0);
assert.equal(problemRows({context:{timeMs:10,opModeState:'running',camera:{state:'ERROR',reportAgeMs:0}},collectedAt:100}).length,1);
console.log('PASS: combined problems, EOF debug retained, deduplication, harmless probe exclusion, stale context ignored');

assert.equal(formatProblems({}), 'No problems found.');
assert.ok(!formatProblems(snapshot).includes('does not prove'));
const ordered = problemRows({ events: [
  {level:'WARN',source:'Camera',message:'Repeated fault',timeMs:10},
  {level:'ERROR',source:'Robot',message:'Older fault',timeMs:5},
  {level:'WARN',source:'Camera',message:'Repeated fault',timeMs:15},
].map(JSON.stringify).join('\n') });
assert.equal(ordered[0].message, 'Repeated fault');
assert.equal(ordered[0].lastStamp, 15);
assert.equal(ordered[0].firstStamp, 10);
assert.equal(ordered[0].count, 2);
assert.equal(ordered[1].message, 'Older fault');
console.log('PASS: newest problem first, grouped latest/earliest timestamps');
