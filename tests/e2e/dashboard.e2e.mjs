// End-to-end test of the BioBuzz dashboard against the desktop simulator, through a real
// (headless) browser. Run with:  node tests/e2e/dashboard.e2e.mjs
//
// It starts its own simulator on port 8687 with a scratch SD card, so it never touches your
// files, then: watches live charts, tunes and saves a setting, syncs it into a scratch repo,
// checks the hardware map editor, builds a route with robot actions in the path editor, saves it
// to the "robot", runs it as the autonomous and checks the robot really did what the route said.
import { spawn, execFileSync } from 'node:child_process';
import { cpSync, mkdirSync, mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Browser, sleep } from './browser.mjs';

const REPO = join(dirname(fileURLToPath(import.meta.url)), '../..');
const PORT = 8687;
const URL = `http://localhost:${PORT}`;
const OUT = join(REPO, 'tests/e2e/out');
mkdirSync(OUT, { recursive: true });

let passed = 0;
function check(condition, message) {
  if (!condition) throw new Error(`FAILED: ${message}`);
  passed++;
  console.log(`  ✓ ${message}`);
}
const api = async (path, options = {}) => {
  const response = await fetch(URL + path, {
    ...options,
    headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
    body: options.body && typeof options.body !== 'string' ? JSON.stringify(options.body) : options.body,
  });
  const text = await response.text();
  return { status: response.status, data: (() => { try { return JSON.parse(text); } catch { return text; } })() };
};

// ---- Start a fresh simulator ------------------------------------------------------
console.log('Building and starting the simulator…');
execFileSync(join(REPO, 'gradlew'), ['-q', '-p', 'sim', 'installDist'], { cwd: REPO, stdio: 'inherit' });
const storage = mkdtempSync(join(tmpdir(), 'biobuzz-sd-'));
const sim = spawn(join(REPO, 'sim/build/install/biobuzz-sim/bin/biobuzz-sim'),
  ['--port', String(PORT), '--storage', storage, '--fresh'], { cwd: join(REPO, 'sim'), stdio: 'ignore' });
let browser;
const cleanup = () => {
  try { sim.kill('SIGKILL'); } catch { /* gone */ }
  if (browser) browser.close();
  rmSync(storage, { recursive: true, force: true });
};
process.on('exit', cleanup);

try {
  for (let i = 0; i < 100; i++) {
    try { if ((await fetch(`${URL}/api/state`)).ok) break; } catch { /* starting */ }
    await sleep(200);
  }
  browser = await Browser.launch({ width: 1600, height: 1000 });
  const b = browser;
  await b.goto(URL);
  await b.waitFor('document.querySelector("#conn").classList.contains("up")');
  check(true, 'dashboard loads and its websocket connects');
  await b.waitFor('document.querySelector("#sim-badge") && !document.querySelector("#sim-badge").hidden');
  check(true, 'it knows it is talking to the simulator');

  // ---- 1. OpMode control and live charts -----------------------------------------------
  console.log('Live tab');
  await b.eval(`document.querySelector('[data-tab="live"]').click()`);
  await b.eval(`(() => { const s = document.querySelector('#op-select'); s.value = 'Field Centric (Java)'; })()`);
  await b.eval(`document.querySelector('#op-init').click()`);
  await b.waitFor(`document.querySelector('#op-state').textContent.startsWith('init')`);
  check(true, 'Init from the dashboard puts the OpMode in INIT');
  await b.waitFor(`!document.querySelector('#op-start').disabled`);
  await b.eval(`document.querySelector('#op-start').click()`);
  await b.waitFor(`document.querySelector('#op-state').textContent.startsWith('running')`);
  check(true, 'Start runs it');
  await sleep(3000);
  const legend = await b.eval(`[...document.querySelectorAll('.chart')][0].querySelector('.legend').innerText`);
  check(/Pollen\s+3[0-9]{3}/.test(legend), `flywheel chart shows a live Pollen RPM (${legend.replace(/\s+/g, ' ')})`);
  const rate = await b.eval(`parseInt(document.querySelector('#rate').textContent) || 0`);
  check(rate >= 50, `charts receive every loop: ${rate} samples/s`);
  const table = await b.eval(`document.querySelector('#telemetry').innerText`);
  check(table.includes('Battery V') && table.includes('Shots'), 'telemetry table lists the robot values');
  await b.screenshot(join(OUT, '1-live.png'));

  // ---- 2. Tune a setting, save it, sync it into Git ---------------------------------------------
  console.log('Tune tab');
  await b.eval(`document.querySelector('[data-tab="tune"]').click()`);
  await b.waitFor(`document.querySelector('[data-key="FieldCentricJava.POLLEN_FLYWHEEL_RPM"]')`);
  await b.eval(`(() => {
    const input = document.querySelector('[data-key="FieldCentricJava.POLLEN_FLYWHEEL_RPM"]');
    input.value = '3000';
    input.dispatchEvent(new Event('change', { bubbles: true }));
  })()`);
  await b.waitFor(`document.querySelector('[data-key="FieldCentricJava.POLLEN_FLYWHEEL_RPM"]').value === '3000'`);
  let settings = (await api('/api/settings')).data;
  const rpm = settings.items.find((i) => i.name === 'POLLEN_FLYWHEEL_RPM');
  check(rpm.value === 3000 && rpm.default === 3400, 'editing the box changes the setting on the robot right away');
  const row = await b.eval(`document.querySelector('[data-key="FieldCentricJava.POLLEN_FLYWHEEL_RPM"]').closest('.setting-row').innerText`);
  check(row.includes('unsaved'), 'it is marked unsaved');
  await sleep(1500);
  const target = await b.eval(`(() => { document.querySelector('[data-tab="live"]').click(); return true; })()`);
  await sleep(800);
  const legend2 = await b.eval(`[...document.querySelectorAll('.chart')][0].querySelector('.legend').innerText`);
  check(target && /Pollen target\s+3000/.test(legend2), 'the running teleop uses the new target at once');
  await b.eval(`document.querySelector('[data-tab="tune"]').click()`);
  await b.waitFor(`document.querySelector('#settings-save')`);
  await b.eval(`document.querySelector('#settings-save').click()`);
  await b.waitFor(`document.querySelector('#settings-status').textContent.startsWith('saved')`);
  const saved = (await api('/api/file?path=settings/robot.json')).data;
  check(saved.FieldCentricJava.POLLEN_FLYWHEEL_RPM === 3000 && saved.version === 1, 'Save writes robot.json on the robot');
  check(!('FLYWHEELS_ON' in saved.FieldCentricJava) && !('Simulator' in saved), 'live switches are not saved');
  await b.screenshot(join(OUT, '2-tune.png'));

  const scratch = mkdtempSync(join(tmpdir(), 'biobuzz-repo-'));
  for (const folder of ['autos', 'paths', 'robot-config']) cpSync(join(REPO, folder), join(scratch, folder), { recursive: true });
  const sync = (...args) => execFileSync(join(REPO, 'robot-sync'), [...args, '--url', URL],
    { env: { ...process.env, ROBOT_SYNC_ROOT: scratch }, encoding: 'utf8' });
  check(sync('status').includes('only on the robot'), 'robot-sync status sees the new robot.json');
  sync('pull');
  const pulled = JSON.parse(readFileSync(join(scratch, 'settings/robot.json'), 'utf8'));
  check(pulled.FieldCentricJava.POLLEN_FLYWHEEL_RPM === 3000, 'robot-sync pull copies it into the repo');
  check(!sync('status').includes('->'), 'after the pull everything is in sync');

  // ---- 3. Hardware map editor ---------------------------------------------------------------
  console.log('Robot tab');
  await b.eval(`document.querySelector('[data-tab="robot"]').click()`);
  await b.waitFor(`document.querySelectorAll('table.devices tr[data-m]').length > 10`);
  const devices = await b.eval(`document.querySelectorAll('table.devices tr[data-m]').length`);
  const inFile = (readFileSync(join(REPO, 'robot-config/2222-Config.xml'), 'utf8').match(/<(?!Lynx)\w+ name="[^"]+" port=/g) || []).length;
  check(devices === inFile, `hardware map shows all ${devices} devices on both hubs`);
  check(await b.eval(`document.querySelector('#hw-problems').children.length === 0`), 'the real map has no problems');
  await b.eval(`(() => {
    const input = [...document.querySelectorAll('table.devices input[data-field="name"]')].find((i) => i.value === 'stinger');
    input.value = 'stingr';
    input.dispatchEvent(new Event('change', { bubbles: true }));
  })()`);
  const problems = await b.eval(`document.querySelector('#hw-problems').innerText`);
  check(problems.includes('called "stinger"'), 'a misspelled device name is caught before saving');
  await b.eval(`document.querySelector('#hw-revert').click()`);
  await sleep(500);
  await b.screenshot(join(OUT, '3-robot.png'));

  // ---- 4. Path editor: robot actions on the route --------------------------------------------------
  console.log('Paths tab');
  await b.eval(`document.querySelector('[data-tab="paths"]').click()`);
  const frame = `document.getElementById('visualizer').contentWindow.document`;
  await b.waitFor(`${frame}.querySelector('[data-testid=robot-panel]') && ${frame}.querySelector('[data-testid=robot-panel]').innerText.includes('connected')`, 20000);
  check(true, 'the path editor loads inside the dashboard and finds the robot');
  await b.waitFor(`[...${frame}.querySelectorAll('[data-testid=route-load] option')].some((o) => o.value === 'autos/saturday.json')`);
  await b.eval(`(() => { const s = ${frame}.querySelector('[data-testid=route-load]'); s.value = 'autos/saturday.json'; s.dispatchEvent(new Event('change', { bubbles: true })); })()`);
  await b.waitFor(`${frame}.querySelector('[data-testid=route-message]') && ${frame}.querySelector('[data-testid=route-message]').innerText.includes('Loaded')`);
  const steps = await b.eval(`${frame}.querySelector('[data-testid=route-steps]').innerText`);
  check(/follow\s+path1/.test(steps) && /shoot/.test(steps) && /follow\s+path2/.test(steps), 'Saturday route opens with its steps');

  // Select path2 by clicking it in the path list, then put "stinger down" half way along it.
  await b.eval(`(() => {
    const item = [...${frame}.querySelectorAll('button, li, div')].find((e) => e.children.length < 4 && /^\\s*path2\\b/.test(e.innerText || ''));
    item.click();
  })()`);
  await b.waitFor(`${frame}.querySelector('[data-testid=robot-panel]').innerText.includes('On the path: path2')`);
  await b.eval(`(() => { const s = ${frame}.querySelector('[data-testid=event-action]'); s.value = 'deployStinger'; s.dispatchEvent(new Event('change', { bubbles: true })); })()`);
  await b.eval(`${frame}.querySelector('[data-testid=event-add]').click()`);
  await b.waitFor(`${frame}.querySelector('[data-testid=event-at]')`);
  await b.eval(`(() => { const r = ${frame}.querySelector('[data-testid=event-at]'); r.value = '40'; r.dispatchEvent(new Event('input', { bubbles: true })); })()`);
  // And raise it again at the end, as a step after path2.
  await b.eval(`(() => { const s = ${frame}.querySelector('[data-testid=step-action]'); s.value = 'stowStinger'; s.dispatchEvent(new Event('change', { bubbles: true })); })()`);
  await b.eval(`${frame}.querySelector('[data-testid=step-add]').click()`);
  await sleep(500);
  const time = await b.eval(`${frame}.querySelector('[data-testid=route-time]').innerText`);
  check(/of 30 s/.test(time), `timeline estimate includes the waits: ${time}`);
  const markers = await b.eval(`${frame}.querySelectorAll('[id^="robot-event-"]').length`);
  check(markers === 1, 'the action shows as a marker on the field');
  await b.screenshot(join(OUT, '4-paths.png'));
  await b.eval(`${frame}.querySelector('[data-testid=route-save]').click()`);
  await b.waitFor(`${frame}.querySelector('[data-testid=route-message]').innerText.includes('Saved autos/saturday.json')`);
  const route = (await api('/api/file?path=autos/saturday.json')).data;
  const path2 = route.paths.find((p) => p.name === 'path2');
  check(path2.events.length === 1 && path2.events[0].action === 'deployStinger' && path2.events[0].at === 0.4,
    'the saved route has "stinger down" at 40% of path2');
  check(route.steps[route.steps.length - 1].action === 'stowStinger', 'and "stinger up" as the last step');
  check(route.paths[1].points.length === 3 && route.start.heading === 270, 'geometry and start pose survive the round trip');

  // The robot refuses a broken route instead of keeping it.
  const bad = await api('/api/file?path=autos/broken.json', { method: 'PUT', body: JSON.stringify({ ...route, steps: [{ action: 'doABackflip' }] }) });
  check(bad.status === 400 && /Unknown action/.test(bad.data.error), 'the robot rejects a route with an unknown action');

  // ---- 5. Run it as the autonomous ----------------------------------------------------------------
  console.log('Autonomous run');
  await api('/api/opmode', { method: 'POST', body: { action: 'init', name: 'Pedro Pathing Autonomous' } });
  await api('/api/opmode', { method: 'POST', body: { action: 'start' } });
  const { WebSocket: _ } = globalThis;
  const ws = new WebSocket(`ws://localhost:${PORT}/ws`);
  const seen = { steps: new Set(), stinger: new Set(), last: null };
  ws.onmessage = (event) => {
    const message = JSON.parse(event.data);
    if (message.type !== 'frames') return;
    for (const f of message.frames) {
      if (f.s && f.s.Step) seen.steps.add(f.s.Step.replace(/^\d+\/\d+\s+/, ''));
      if (f.n.Stinger !== undefined) seen.stinger.add(f.n.Stinger);
      if (f.n.X !== undefined) seen.last = f;
    }
  };
  const deadline = Date.now() + 25000;
  while (Date.now() < deadline && !(seen.last && seen.last.n['Route done'] === 1)) await sleep(250);
  ws.close();
  check(seen.last && seen.last.n['Route done'] === 1, 'the autonomous finishes the whole route');
  check(seen.steps.has('follow path1') && seen.steps.has('feed on') && seen.steps.has('stinger up'),
    `it ran the steps from the file: ${[...seen.steps].join(' → ')}`);
  check(seen.stinger.has(saved.FieldCentricJava.STINGER_DOWN), 'the stinger went down part way along path2');
  const { X, Y, Heading } = seen.last.n;
  check(Math.abs(X - 13.2) < 1.5 && Math.abs(Y - 96.07) < 1.5 && Math.abs(Math.abs(Heading) - 180) < 5,
    `Pedro drove the simulated robot to the park pose: (${X.toFixed(2)}, ${Y.toFixed(2)}, ${Heading.toFixed(0)}°)`);
  await b.eval(`document.querySelector('[data-tab="live"]').click()`);
  await sleep(1000);
  await b.screenshot(join(OUT, '5-auto.png'));

  const errors = b.errors.filter((e) => !/favicon/.test(e));
  check(errors.length === 0, `no errors in the browser console${errors.length ? `: ${errors.join(' | ')}` : ''}`);
  rmSync(scratch, { recursive: true, force: true });
  console.log(`\nAll ${passed} checks passed. Screenshots in tests/e2e/out/.`);
  cleanup();
  process.exit(0);
} catch (error) {
  console.error(`\n${error.stack || error}`);
  if (browser) await browser.screenshot(join(OUT, 'failure.png')).catch(() => {});
  cleanup();
  process.exit(1);
}
