// End-to-end test of the Total Chaos Dashboard against the desktop simulator, through a real
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
import { FrameDecoder } from '../../dashboard/web/js/frames.js';

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
  // Add a chart of our own from the telemetry the robot is sending.
  await b.eval(`document.querySelector('#add-chart').click()`);
  await b.waitFor(`document.querySelector('#chart-dialog').open && document.querySelectorAll('#chart-keys input').length > 5`);
  const offered = await b.eval(`[...document.querySelectorAll('#chart-keys input')].map((i) => i.value)`);
  check(offered.includes('Shots') && offered.includes('Pollen power') && !offered.includes('Intake'),
    `Add chart offers the numbers the robot sends (${offered.length} of them), not text`);
  await b.eval(`(() => {
    for (const key of ['Pollen power', 'Nectar power']) document.querySelector('#chart-keys input[value="' + key + '"]').checked = true;
    document.querySelector('#chart-title').value = 'Motor power';
    document.querySelector('#chart-create').click();
  })()`);
  await b.waitFor(`[...document.querySelectorAll('.chart h3')].some((h) => h.textContent.includes('Motor power'))`);
  await sleep(1200);
  const custom = await b.eval(`[...document.querySelectorAll('.chart')].find((c) => c.querySelector('h3').textContent.includes('Motor power')).querySelector('.legend').innerText`);
  check(/Pollen power\s+0\.\d+/.test(custom) && /Nectar power\s+0\.\d+/.test(custom), `the new chart plots live values (${custom.replace(/\s+/g, ' ')})`);
  await b.goto(URL);
  await b.waitFor(`[...document.querySelectorAll('.chart h3')].some((h) => h.textContent.includes('Motor power'))`);
  check(true, 'the added chart is still there after reloading the page');
  const scrolls = await b.eval(`(() => { const t = document.querySelector('.side .grow'); return getComputedStyle(t).overflowY === 'auto' && t.scrollHeight > t.clientHeight && t.getBoundingClientRect().bottom <= innerHeight + 1; })()`);
  check(scrolls, 'the telemetry list scrolls inside the page instead of running off the bottom');
  await b.screenshot(join(OUT, '1-live.png'));
  await b.eval(`[...document.querySelectorAll('.chart')].find((c) => c.querySelector('h3').textContent.includes('Motor power')).querySelector('.remove').click()`);
  check(await b.eval(`![...document.querySelectorAll('.chart h3')].some((h) => h.textContent.includes('Motor power'))`), 'and ✕ removes it');

  // Compact stream: names once, then numbers; text only when it changes.
  await sleep(2500);
  const liveLoad = (await api('/api/state')).data.load;
  const perLoop = liveLoad.kbPerSecond * 1024 / liveLoad.loopsPerSecond;
  check(liveLoad.liveViewers === 1 && perLoop < 250,
    `on the Live tab the browser gets every loop, compactly: ${Math.round(perLoop)} bytes per loop (${liveLoad.kbPerSecond} KB/s)`);
  const toolbar = await b.eval(`document.getElementById('live-load').textContent`);
  check(/^Loop [\d.]+ ms · dashboard \+[\d.]+ ms · [\d.]+ KB\/s$/.test(toolbar),
    `the Live toolbar shows the loop time and the dashboard's share of it (${toolbar})`);
  check(liveLoad.dashboardMs > 0 && liveLoad.dashboardMs < 0.5,
    `each loop's time in the dashboard's own code is measured: ${liveLoad.dashboardMs} ms`);
  const runningState = (await api('/api/state')).data;
  check(runningState.active && runningState.battery > 10,
    `while an OpMode runs the battery comes from its Battery V, not from reading the hubs (${runningState.battery} V)`);
  const liveText = await b.eval(`document.getElementById('tab-live').innerText`);
  check(/Flywheel compensated F\s+11\b/.test(liveText),
    'the flywheel F follows the battery: 11 with the simulator\'s full battery');

  // Stopping the OpMode clears its telemetry from the table (charts keep their history).
  await b.eval(`document.querySelector('#op-stop').click()`);
  await b.waitFor(`!document.getElementById('tab-live').innerText.includes('Flywheel compensated F')`, 5000);
  check(true, 'after Stop the telemetry table no longer shows the stopped OpMode\'s values');
  await api('/api/opmode', { method: 'POST', body: { action: 'init', name: 'Field Centric (Java)' } });
  await api('/api/opmode', { method: 'POST', body: { action: 'start' } });
  await b.waitFor(`document.getElementById('tab-live').innerText.includes('Flywheel compensated F')`, 5000);

  // ---- Gamepad drawer: drive the simulated robot from the dashboard ---------------------
  console.log('Gamepad drawer');
  check(await b.eval(`!!document.querySelector('#pad-handle') && document.querySelector('#pad-body').hidden`),
    'every tab has the Gamepad handle at the bottom, closed at first');
  await b.eval(`document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'g', bubbles: true }))`);
  await b.waitFor(`!document.querySelector('#pad-body').hidden && document.querySelector('#pad-svg')`);
  check(true, 'G opens the drawer with the controller picture');
  await b.waitFor(`document.querySelector('#pad-status').textContent.includes('as the robot sees it')`);
  check(await b.eval(`document.querySelector('#pad-legend').textContent.includes('reset forward')`),
    'it labels what gamepad 1 does in the teleop');
  await b.waitFor(`[...document.querySelectorAll('#telemetry tr')].some((r) => r.textContent === 'DriverDemo (nobody driving)')`, 3000);
  check(true, 'nobody is driving yet: the simulator\'s demo driver is (text the OpMode did not change since the restart still shows)');
  await b.eval(`document.querySelector('#pad-take').click()`);
  await b.waitFor(`document.querySelector('#pad-take').textContent === 'Release' && !document.querySelector('#pad-pill').hidden`);
  check(true, 'Take control: the button becomes Release and the top bar shows a Driving pill');
  await b.waitFor(`[...document.querySelectorAll('#telemetry tr')].some((r) => r.textContent.includes('Dashboard gamepad 1'))`, 3000);
  check(true, 'the simulated teleop now takes gamepad 1 from the dashboard (the demo driver stops)');
  await sleep(1500);   // the demo's motion dies down
  // Push the left stick straight up with the mouse and hold it.
  const knob = await b.eval(`(() => { const r = document.querySelector('[data-stick="left"] .knob').getBoundingClientRect(); return { x: r.x + r.width / 2, y: r.y + r.height / 2, h: r.height }; })()`);
  const mouse = (type, x, y) => b.send('Input.dispatchMouseEvent', { type, x, y, button: 'left', buttons: type === 'mouseReleased' ? 0 : 1, clickCount: 1 });
  const poseBefore = await b.eval(`document.getElementById('pose-text').textContent`);
  await mouse('mousePressed', knob.x, knob.y);
  for (let i = 1; i <= 5; i++) await mouse('mouseMoved', knob.x, knob.y - knob.h * 0.3 * i);
  await sleep(300);
  const readout = await b.eval(`document.querySelector('#pad-readout').textContent`);
  check(/left 0\.00, -1\.00/.test(readout), `dragging the left stick up gives y = -1, like an FTC gamepad (${readout.trim()})`);
  await sleep(1200);
  const poseMoving = await b.eval(`document.getElementById('pose-text').textContent`);
  await mouse('mouseReleased', knob.x, knob.y - knob.h * 1.5);
  const parsePose = (text) => (text.match(/-?\d+(\.\d+)?/g) || []).map(Number);
  const [x0, y0] = parsePose(poseBefore), [x1, y1] = parsePose(poseMoving);
  check(Math.hypot(x1 - x0, y1 - y0) > 10, `the robot drives forward on the field: (${poseBefore}) → (${poseMoving})`);
  await sleep(200);
  check(await b.eval(`/left 0\\.00, 0\\.00/.test(document.querySelector('#pad-readout').textContent)`), 'letting go springs the stick back to the middle');
  await sleep(800);
  const stopped1 = parsePose(await b.eval(`document.getElementById('pose-text').textContent`));
  await sleep(700);
  const stopped2 = parsePose(await b.eval(`document.getElementById('pose-text').textContent`));
  check(Math.hypot(stopped2[0] - stopped1[0], stopped2[1] - stopped1[1]) < 0.5, 'and the robot stops');
  // Operator buttons: gamepad 2, A turns the flywheels off.
  await b.eval(`document.querySelector('#pad-seg [data-pad="2"]').click()`);
  await b.waitFor(`document.querySelector('#pad-take').textContent === 'Take control' && document.querySelector('#pad-pill').hidden`);
  check(true, 'switching to gamepad 2 releases gamepad 1');
  await b.eval(`document.querySelector('#pad-take').click()`);
  await b.waitFor(`document.querySelector('#pad-take').textContent === 'Release'`);
  const aButton = await b.eval(`(() => { const r = document.querySelector('[data-btn="a"]').getBoundingClientRect(); return { x: r.x + r.width / 2, y: r.y + r.height / 2 }; })()`);
  await mouse('mousePressed', aButton.x, aButton.y);
  await sleep(250);
  await mouse('mouseReleased', aButton.x, aButton.y);
  const flywheelsOn = async () => (await api('/api/settings')).data.items.find((i) => i.group === 'Flywheels' && i.name === 'FLYWHEELS_ON').value;
  for (let i = 0; i < 30 && await flywheelsOn(); i++) await sleep(100);
  check(await flywheelsOn() === false,
    'operator A from the drawer toggles the flywheels off, like on the Driver Station');
  await mouse('mousePressed', aButton.x, aButton.y);
  await sleep(250);
  await mouse('mouseReleased', aButton.x, aButton.y);
  await b.eval(`document.querySelector('#pad-take').click()`);   // Release
  await b.waitFor(`document.querySelector('#pad-take').textContent === 'Take control'`);

  // Safety: one driver per gamepad, and inputs that stop count as nothing.
  const other = new WebSocket(`ws://localhost:${PORT}/ws`);
  const replies = [];
  other.onmessage = (event) => { const m = JSON.parse(event.data); if (m.type === 'control') replies.push(m); };
  await new Promise((resolve) => { other.onopen = resolve; });
  other.send(JSON.stringify({ type: 'control', pad: 2, take: true }));
  for (let i = 0; i < 50 && !replies.length; i++) await sleep(50);
  check(replies[0] && replies[0].owner > 0 && !replies[0].error, 'another browser can take gamepad 2 once it is free');
  const push = setInterval(() => other.send(JSON.stringify({ type: 'gamepad', pad: 2, axes: [0, 0, 0, 0, 0, 0], buttons: 0 })), 50);
  await b.waitFor(`document.querySelector('#pad-take').disabled && document.querySelector('#pad-status').textContent.includes('Another browser')`, 3000);
  check(true, 'while it drives, this browser cannot take gamepad 2 and says who has it');
  await b.waitFor(`[...document.querySelectorAll('#telemetry tr')].some((r) => r.textContent.includes('Dashboard gamepad 2'))`, 3000);
  clearInterval(push);   // its inputs stop, the connection stays open
  await b.waitFor(`[...document.querySelectorAll('#telemetry tr')].some((r) => r.textContent.includes('Demo (nobody driving)'))`, 3000);
  check(true, 'when its inputs stop arriving the robot ignores that gamepad (all zero) within a moment');
  other.close();
  await b.waitFor(`!document.querySelector('#pad-take').disabled`, 3000);
  check(true, 'when that browser leaves, its gamepad is free again');

  // Control ends with the OpMode.
  await b.eval(`document.querySelector('#pad-seg [data-pad="1"]').click()`);
  await b.eval(`document.querySelector('#pad-take').click()`);
  await b.waitFor(`document.querySelector('#pad-take').textContent === 'Release'`);
  await api('/api/opmode', { method: 'POST', body: { action: 'stop' } });
  await b.waitFor(`document.querySelector('#pad-pill').hidden && document.querySelector('#toast').textContent.includes('OpMode stopped')`, 4000);
  check((await api('/api/state')).data.gamepads.every((owner) => owner === 0), 'stopping the OpMode ends gamepad control (and says so)');
  check(await b.eval(`document.querySelector('#pad-take').disabled`), 'and Take control waits for an OpMode');
  await b.screenshot(join(OUT, '1-gamepad.png'));
  await b.eval(`document.querySelector('#pad-handle').click()`);
  check(await b.eval(`document.querySelector('#pad-body').hidden`), 'the handle closes the drawer');
  // Live switches turned off for a test go back on at the next INIT (never saved, never stuck).
  await api('/api/settings', { method: 'POST', body: { group: 'Flywheels', name: 'FLYWHEELS_ON', value: false } });
  await api('/api/settings', { method: 'POST', body: { group: 'Intake', name: 'INTAKE_ENABLED', value: false } });
  await api('/api/opmode', { method: 'POST', body: { action: 'init', name: 'Field Centric (Java)' } });
  const switches = (await api('/api/settings')).data.items.filter((i) => ['FLYWHEELS_ON', 'INTAKE_ENABLED'].includes(i.name));
  check(switches.length === 2 && switches.every((i) => i.value === true), 'live switches (FLYWHEELS_ON, INTAKE_ENABLED) turned off go back on at the next INIT');
  await api('/api/opmode', { method: 'POST', body: { action: 'start' } });
  await b.waitFor(`document.getElementById('tab-live').innerText.includes('Flywheel compensated F')`, 5000);

  // Full size: a chart fills the Live area under the toolbar, and Esc puts it back.
  await b.eval(`document.querySelector('.chart .expand').click()`);
  await sleep(400);
  const big = await b.eval(`(() => {
    const card = document.querySelector('.chart.expanded');
    const bar = document.querySelector('#tab-live .toolbar').getBoundingClientRect();
    const box = card.getBoundingClientRect();
    return { top: box.top > bar.bottom, wide: box.width > innerWidth * 0.9, tall: box.height > (innerHeight - bar.bottom) * 0.85,
             plot: card.querySelector('canvas').getBoundingClientRect().height,
             closeHidden: getComputedStyle(card.querySelector('.remove')).display === 'none',
             label: card.querySelector('.expand').textContent.trim() };
  })()`);
  check(big.closeHidden && big.label === 'Exit full size', 'at full size the ✕ is hidden and the button says "Exit full size"');
  check(big.top && big.wide && big.tall && big.plot > 400, `the expand button makes a chart fill the Live area below the toolbar (plot ${Math.round(big.plot)} px tall)`);
  await b.send('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 });
  await sleep(300);
  check(await b.eval(`!document.querySelector('.chart.expanded')`), 'Esc puts it back');

  // Pause on a chart freezes all charts; hovering then reads exact values and their time.
  await b.eval(`document.querySelector('.chart .pause-chart').click()`);
  check(await b.eval(`document.getElementById('charts').classList.contains('paused') && document.getElementById('pause').textContent === 'Resume'`),
    'the pause button on a chart pauses the charts');
  const box = await b.eval(`(() => { const r = document.querySelector('.chart canvas').getBoundingClientRect(); return { x: r.left + r.width * 0.6, y: r.top + r.height / 2 }; })()`);
  await b.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: box.x, y: box.y });
  await sleep(200);
  const hover = await b.eval(`document.querySelector('.chart .legend .at').textContent`);
  check(/^at -\d+\.\d\d s$/.test(hover), `hovering a paused chart shows the exact values and when: "${hover}"`);
  await b.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: 5, y: 5 });
  await b.send('Input.dispatchKeyEvent', { type: 'keyDown', key: ' ', code: 'Space', windowsVirtualKeyCode: 32 });
  await sleep(200);
  check(await b.eval(`!document.getElementById('charts').classList.contains('paused')`), 'Space resumes them');

  // Standard charts can be closed and moved too; each browser remembers its own layout.
  const titles = () => b.eval(`[...document.querySelectorAll('#charts .chart h3')].map((h) => h.textContent.replace('⠿', '').trim())`);
  await b.eval(`[...document.querySelectorAll('.chart')].find((c) => c.querySelector('h3').textContent.includes('Battery & loop')).querySelector('.remove').click()`);
  await b.eval(`(() => {
    const cards = [...document.querySelectorAll('#charts .chart')];
    const moving = cards.find((c) => c.querySelector('h3').textContent.includes('Intake'));
    const target = cards[0];
    const data = new DataTransfer();
    moving.querySelector('.grip').dispatchEvent(new DragEvent('dragstart', { bubbles: true, dataTransfer: data }));
    const box = target.getBoundingClientRect();
    target.dispatchEvent(new DragEvent('dragover', { bubbles: true, cancelable: true, dataTransfer: data, clientX: box.left + 5, clientY: box.top + 5 }));
    target.dispatchEvent(new DragEvent('drop', { bubbles: true, cancelable: true, dataTransfer: data }));
  })()`);
  const arranged = await titles();
  check(JSON.stringify(arranged) === JSON.stringify(['Intake', 'Flywheels', 'Flywheel error']),
    `closing and dragging standard charts works: ${arranged.join(', ')}`);
  await b.goto(URL);
  await b.waitFor(`document.querySelectorAll('#charts .chart').length === 3`);
  check(JSON.stringify(await titles()) === JSON.stringify(arranged), 'and this browser remembers the layout');
  await b.eval(`(() => { window.confirm = () => true; document.querySelector('#reset-charts').click(); })()`);
  check(JSON.stringify(await titles()) === JSON.stringify(['Flywheels', 'Flywheel error', 'Intake', 'Battery & loop']),
    'Reset charts brings back the standard ones');

  // ---- 2. Tune a setting, save it, sync it into Git ---------------------------------------------
  console.log('Tune tab');
  // A robot.json saved before the settings moved into mechanisms/ still loads.
  await api('/api/file?path=settings/robot.json', { method: 'PUT', headers: { 'X-Base-Hash': '*' },
    body: JSON.stringify({ version: 1, FieldCentricJava: { STINGER_DOWN: 0.4 }, MecanumDrive: { INITIAL_SPEED: 0.6 } }, null, 2) });
  await api('/api/settings/reload', { method: 'POST' });
  const moved = (await api('/api/settings')).data.items.find((i) => i.group === 'Stinger' && i.name === 'STINGER_DOWN');
  check(moved.value === 0.4 && moved.inFile === 0.4,
    'an older robot.json (stinger under FieldCentricJava) still loads into the Stinger mechanism\'s settings');
  const driveSpeed = (await api('/api/settings')).data.items.find((i) => i.group === 'MecanumDrive' && i.name === 'DRIVE_SPEED');
  check(driveSpeed.value === 0.6 && driveSpeed.inFile === 0.6, 'and its INITIAL_SPEED becomes DRIVE_SPEED (renamed; it now applies live)');
  check((await api('/api/settings')).data.items.some((i) => i.group === 'Intake' && i.name === 'INTAKE_I'), 'the intake has an I to tune (INTAKE_I)');
  await b.eval(`document.querySelector('[data-tab="tune"]').click()`);
  await b.waitFor(`document.querySelector('[data-key="Flywheels.POLLEN_FLYWHEEL_RPM"]')`);
  check(await b.eval(`document.querySelectorAll('#settings .dot.unsaved').length === 0 && document.querySelectorAll('#settings .dot.synced').length > 20`),
    'freshly loaded settings all show a green (in sync) dot');
  const routeOptions = await b.eval(`[...document.querySelectorAll('[data-key="PedroAutonomous.ROUTE"] option')].map((o) => o.value)`);
  check(routeOptions.includes('saturday'), `PedroAutonomous.ROUTE is a dropdown of the autons on the robot: ${routeOptions.join(', ')}`);
  const tunerShown = () => b.eval(`!!document.querySelector('#settings [data-group="FlywheelTuner"]')`);
  check(!(await tunerShown()) && (await b.eval(`document.getElementById('settings-hidden').innerText`)).includes('FlywheelTuner'),
    '@Configurable.Hidden: FlywheelTuner is listed under Hidden, not as a group');
  await b.eval(`document.querySelector('#settings-hidden [data-show="FlywheelTuner"]').click()`);
  check(await tunerShown(), 'Show puts it back on the Tune tab');
  await b.eval(`document.querySelector('#settings [data-group="FlywheelTuner"] .hide-group').click()`);
  check(!(await tunerShown()), 'and Hide hides it again (remembered in this browser)');
  await b.eval(`(() => {
    const input = document.querySelector('[data-key="Flywheels.POLLEN_FLYWHEEL_RPM"]');
    input.value = '3000';
    input.dispatchEvent(new Event('change', { bubbles: true }));
  })()`);
  await b.waitFor(`document.querySelector('[data-key="Flywheels.POLLEN_FLYWHEEL_RPM"]').value === '3000'
    && document.querySelectorAll('#settings .dot.unsaved').length > 0`);
  let settings = (await api('/api/settings')).data;
  const rpm = settings.items.find((i) => i.name === 'POLLEN_FLYWHEEL_RPM');
  check(rpm.value === 3000 && rpm.default === 3400, 'editing the box changes the setting on the robot right away');
  check(await b.eval(`!!document.querySelector('[data-key="Flywheels.POLLEN_FLYWHEEL_RPM"]').closest('.setting-row').querySelector('.dot.unsaved')`)
    && await b.eval(`document.querySelectorAll('#settings .dot.unsaved').length === 1`),
    `only the changed setting turns yellow (changed, not saved): ${await b.eval(`[...document.querySelectorAll('#settings .dot.unsaved')].map((d) => d.closest('.setting-row').querySelector('.setting-name').innerText.split('\\n')[0]).join(', ')`)}`);
  await sleep(1500);
  const target = await b.eval(`(() => { document.querySelector('[data-tab="live"]').click(); return true; })()`);
  await sleep(800);
  const legend2 = await b.eval(`[...document.querySelectorAll('.chart')][0].querySelector('.legend').innerText`);
  check(target && /Pollen target\s+3000/.test(legend2), 'the running teleop uses the new target at once');
  await b.eval(`document.querySelector('[data-tab="tune"]').click()`);
  await b.waitFor(`document.querySelector('#settings-save')`);
  await b.eval(`document.querySelector('#settings-save').click()`);
  await b.waitFor(`document.querySelector('#settings-status').textContent.startsWith('all saved')`);
  const saved = (await api('/api/file?path=settings/robot.json')).data;
  check(saved.Flywheels.POLLEN_FLYWHEEL_RPM === 3000 && saved.version === 1, 'Save writes robot.json on the robot');
  await b.waitFor(`document.querySelectorAll('#settings .dot.unsaved').length === 0`);
  check(true, 'after Save to robot every dot is green again');
  check(!('FLYWHEELS_ON' in saved.Flywheels) && !('INTAKE_ENABLED' in saved.Intake) && !('Simulator' in saved), 'live switches are not saved');
  check(saved.MecanumDrive.DRIVE_SPEED === 0.6 && !('INITIAL_SPEED' in saved.MecanumDrive), 'Save writes DRIVE_SPEED, not the old INITIAL_SPEED');
  check(!('FieldCentricJava' in saved) && saved.Stinger.STINGER_DOWN === 0.4, 'Save writes the moved settings under their mechanisms (Stinger.STINGER_DOWN 0.4), not FieldCentricJava');
  await b.screenshot(join(OUT, '2-tune.png'));

  // Undo (↶) on a yellow value puts the saved one back.
  await b.eval(`(() => { const i = document.querySelector('[data-key="Flywheels.POLLEN_FLYWHEEL_RPM"]');
    i.value = '1234'; i.dispatchEvent(new Event('change', { bubbles: true })); })()`);
  await b.waitFor(`document.querySelectorAll('#settings .dot.unsaved').length === 1 && document.querySelector('[data-undo="Flywheels.POLLEN_FLYWHEEL_RPM"]')`);
  check((await b.eval(`document.querySelector('[data-key="Flywheels.POLLEN_FLYWHEEL_RPM"]').closest('.setting-row').querySelector('small').textContent`)) === 'code default 3400 · robot.json: 3000',
    'each setting shows its code default and what robot.json sets instead');
  await b.eval(`document.querySelector('[data-undo="Flywheels.POLLEN_FLYWHEEL_RPM"]').click()`);
  await b.waitFor(`document.querySelector('[data-key="Flywheels.POLLEN_FLYWHEEL_RPM"]').value === '3000' && document.querySelectorAll('#settings .dot.unsaved').length === 0`);
  check(true, 'the undo button next to a yellow value puts back the saved value (3000)');

  // History: a second save, then the Tune tab's History button shows both and restores the first.
  await b.eval(`(() => { const i = document.querySelector('[data-key="Flywheels.POLLEN_FLYWHEEL_RPM"]');
    i.value = '9000'; i.dispatchEvent(new Event('change', { bubbles: true })); })()`);
  await b.waitFor(`document.querySelectorAll('#settings .dot.unsaved').length === 1`);
  await b.eval(`document.querySelector('#settings-save').click()`);
  await b.waitFor(`document.querySelector('#settings-status').textContent.startsWith('all saved')`);
  await b.eval(`document.querySelector('#settings-history').click()`);
  await b.waitFor(`document.querySelector('#tab-robot').classList.contains('active') && document.querySelectorAll('#history tr[data-id]').length === 3`);
  check(true, 'Tune → History opens the history of robot.json with both saves (and the older file before them)');
  await b.eval(`document.querySelector('#history tr[data-id] [data-changes]').click()`);
  await b.waitFor(`document.querySelector('#history tr.changes:not([hidden])')`);
  const changes = await b.eval(`document.querySelector('#history tr.changes:not([hidden])').innerText`);
  check(/POLLEN_FLYWHEEL_RPM\s+3000\s+→\s+9000/.test(changes), `it shows what the newest save changed: ${changes.trim()}`);
  await b.eval(`(() => { window.confirm = () => true; [...document.querySelectorAll('#history tr[data-id]')][1].querySelector('[data-restore]').click(); })()`);
  await b.waitFor(`document.querySelectorAll('#history tr[data-id]').length === 4`);
  const restoredRpm = (await api('/api/settings')).data.items.find((i) => i.name === 'POLLEN_FLYWHEEL_RPM').value;
  check(restoredRpm === 3000, 'Restore puts the older version back on the robot (and keeps it in history)');

  const scratch = mkdtempSync(join(tmpdir(), 'biobuzz-repo-'));
  for (const folder of ['autos', 'robot-config']) cpSync(join(REPO, folder), join(scratch, folder), { recursive: true });
  const sync = (...args) => execFileSync(join(REPO, 'robot-sync'), [...args, '--url', URL],
    { env: { ...process.env, ROBOT_SYNC_ROOT: scratch }, encoding: 'utf8' });
  check(sync('status').includes('only on the robot'), 'robot-sync status sees the new robot.json');
  sync('pull');
  const pulled = JSON.parse(readFileSync(join(scratch, 'settings/robot.json'), 'utf8'));
  check(pulled.Flywheels.POLLEN_FLYWHEEL_RPM === 3000, 'robot-sync pull copies it into the repo');
  check(!sync('status').includes('->'), 'after the pull everything is in sync');
  const historyFiles = execFileSync('find', [join(scratch, 'history'), '-type', 'f'], { encoding: 'utf8' }).trim().split('\n');
  check(historyFiles.length >= 3, `robot-sync pull also copies the robot's history into the repo (${historyFiles.length} versions)`);

  // ---- 3. Hardware map editor ---------------------------------------------------------------
  console.log('Robot tab');
  await b.eval(`document.querySelector('[data-tab="robot"]').click()`);
  await b.waitFor(`document.querySelectorAll('table.devices tr[data-m]').length > 10`);
  const devices = await b.eval(`document.querySelectorAll('table.devices tr[data-m]').length`);
  const inFile = (readFileSync(join(REPO, 'robot-config/24621-Biobuzz.xml'), 'utf8').match(/<(?!Lynx)\w+ name="[^"]+" port=/g) || []).length;
  check(devices === inFile, `hardware map shows all ${devices} devices on both hubs`);
  check(await b.eval(`document.querySelector('#hw-problems').children.length === 0`), 'the real map has no problems');
  await b.waitFor(`document.querySelector('#load').innerText.includes('Viewers')`);
  await sleep(2500);
  const load = (await api('/api/state')).data.load;
  check(load.viewers >= 1 && load.dashboardCpu >= 0 && load.loopMs > 0,
    `the Load card measures the dashboard: ${load.viewers} viewer, ${load.dashboardCpu} % CPU, ${load.kbPerSecond} KB/s, loop ${load.loopMs} ms`);
  check(load.liveViewers === 0 && load.kbPerSecond < 4,
    `off the Live tab the browser gets status only, no loop data: ${load.kbPerSecond} KB/s`);
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

  // Several hardware maps: Control Hub first, new map with an Expansion Hub, webcams, Use this map.
  check((await b.eval(`document.querySelector('.hub-title').innerText`)).startsWith('Control Hub'),
    'the Control Hub is listed first, though the file has the Expansion Hub first');
  check((await b.eval(`document.querySelector('#hw-map').selectedOptions[0].textContent`)) === '24621-Biobuzz (in use)',
    'the map picker opens the map the robot uses, marked (in use)');
  await b.eval(`document.querySelector('#hw-new').click()`);
  await b.eval(`(() => { document.querySelector('#hw-new-name').value = 'TestBot'; document.querySelector('#hw-new-create').click(); })()`);
  await b.waitFor(`document.querySelector('#hw-map') && document.querySelector('#hw-map').value === 'TestBot.xml' && document.querySelectorAll('.hub-title').length >= 2`);
  const testBot = (await api('/api/file?path=TestBot.xml')).data;
  check(/<LynxModule name="Expansion Hub 2" port="2"/.test(testBot) && /<LynxModule name="Control Hub" port="173">/.test(testBot),
    'New map makes a map with the Control Hub and an Expansion Hub at address 2');
  check((await b.eval(`document.querySelector('.hw-note').innerText`)).includes('Not in use'), 'a map that is not in use says so');
  await b.eval(`(() => { window.confirm = () => true; document.querySelector('[data-remove-hub]').click(); })()`);
  check(await b.eval(`!!document.querySelector('#hw-add-hub')`), 'removing the Expansion Hub offers + Expansion Hub');
  await b.eval(`(() => { window.prompt = () => '3'; document.querySelector('#hw-add-hub').click(); })()`);
  check((await b.eval(`[...document.querySelectorAll('.hub-title')].map((h) => h.innerText).join('|')`)).includes('Expansion Hub 3'),
    '+ Expansion Hub adds one at the address you give');
  await b.screenshot(join(OUT, '3b-new-map.png'));
  // Delete: autons and maps not in use (robot.json and the map in use cannot be deleted).
  check((await api('/api/file?path=24621-Biobuzz.xml', { method: 'DELETE' })).status === 400, 'the hardware map in use cannot be deleted');
  check((await api('/api/file?path=settings/robot.json', { method: 'DELETE' })).status === 400, 'nor can robot.json');
  await b.eval(`document.querySelector('#files-refresh').click()`);
  await b.waitFor(`document.querySelector('[data-delete="TestBot.xml"]')`);
  check(await b.eval(`!document.querySelector('[data-delete="24621-Biobuzz.xml"]') && !document.querySelector('[data-delete="settings/robot.json"]')`),
    'the Files list offers Delete only where it is allowed');
  await b.eval(`(() => { window.confirm = () => true; document.querySelector('[data-delete="TestBot.xml"]').click(); })()`);
  await b.waitFor(`!document.querySelector('[data-delete="TestBot.xml"]') && document.querySelector('#toast').textContent.includes('Deleted')`);
  check((await api('/api/file?path=TestBot.xml')).status === 400 || (await api('/api/file?path=TestBot.xml')).status === 404, 'Delete removes a map that is not in use (TestBot)');
  await api('/api/file?path=CamBot.xml', { method: 'PUT', body: testBot.replace('</Robot>',
    '    <Webcam name="Webcam 1" serialNumber="UVC:046d:0825:A1B2C3" />\n</Robot>') });
  await b.eval(`document.querySelector('#files-refresh').click()`);
  await b.waitFor(`[...document.querySelectorAll('#hw-map option')].some((o) => o.value === 'CamBot.xml')`);
  await b.eval(`(() => { const s = document.querySelector('#hw-map'); s.value = 'CamBot.xml'; s.dispatchEvent(new Event('change')); })()`);
  await b.waitFor(`document.querySelector('tr[data-u]')`);
  check((await b.eval(`document.querySelector('tr[data-u]').innerText`)).includes('UVC:046d'), 'a webcam added on the Driver Station shows under USB devices');
  await b.eval(`document.querySelector('#hw-use').click()`);
  await b.waitFor(`document.querySelector('#hw-map').selectedOptions[0].textContent === 'CamBot (in use)'`);
  check((await api('/api/state')).data.hardwareMap === 'CamBot.xml', 'Use this map switches the map the (simulated) robot uses');
  await b.waitFor(`document.getElementById('adb-status').textContent.includes('simulator has no adb')`);
  check(await b.eval(`document.getElementById('adb-restart').disabled`), 'the adb card says the simulator has no adb, and Restart adb is off');
  await api('/api/hardware/active', { method: 'POST', body: { path: '24621-Biobuzz.xml' } });

  // ---- 4. Path editor: robot actions on the route --------------------------------------------------
  console.log('Paths tab');
  // The functions come from the mechanisms' @AutoFunction methods: the same list as before.
  const functionNames = (await api('/api/actions')).data.map((a) => a.name).sort();
  check(JSON.stringify(functionNames) === JSON.stringify(['deployStinger', 'flywheelsOff', 'flywheelsOn', 'intakeOff', 'intakeOn',
    'shoot', 'stowStinger', 'turnTo', 'wait', 'waitForFlywheels']),
    `the Auton Sequence offers the mechanisms' functions plus the steps, nothing hidden (${functionNames.join(', ')})`);
  await b.eval(`document.querySelector('[data-tab="paths"]').click()`);
  const frame = `document.getElementById('visualizer').contentWindow.document`;
  await b.waitFor(`${frame}.querySelector('[data-testid=robot-panel]') && ${frame}.querySelector('[data-testid=robot-panel]').innerText.includes('connected')`, 20000);
  check(true, 'the path editor loads inside the dashboard and finds the robot');
  const shown = (id) => `${frame}.querySelector('[data-testid=${id}]').offsetParent !== null`;
  check(await b.eval(`${shown('panel-paths')} && !(${shown('panel-auton')})`),
    'the side panel has tabs and opens on Paths (start point and the selected path)');
  await b.eval(`${frame}.querySelector('[data-testid=panel-tab-auton]').click()`);
  await sleep(200);
  check(await b.eval(`${shown('panel-auton')} && !(${shown('panel-paths')})`),
    'the Auton Sequence tab shows the sequence on its own');
  await b.waitFor(`[...${frame}.querySelectorAll('[data-testid=route-load] option')].some((o) => o.value === 'autos/saturday.json')`);
  await b.eval(`(() => { const s = ${frame}.querySelector('[data-testid=route-load]'); s.value = 'autos/saturday.json'; s.dispatchEvent(new Event('change', { bubbles: true })); })()`);
  await b.waitFor(`${frame}.querySelector('[data-testid=route-message]') && ${frame}.querySelector('[data-testid=route-message]').innerText.includes('Loaded')`);
  const steps = await b.eval(`${frame}.querySelector('[data-testid=route-steps]').innerText`);
  check(/follow\s+path1/.test(steps) && /shoot/.test(steps) && /follow\s+path2/.test(steps), 'Saturday route opens with its steps');

  // "Function on a path": choose path2 from the list, stinger down at 40%, then flywheels on at 20%.
  const setIn = (testid, value, type = 'change') => b.eval(`(() => {
    const e = ${frame}.querySelector('[data-testid=${testid}]'); e.value = ${JSON.stringify(String(value))};
    e.dispatchEvent(new Event('${type}', { bubbles: true })); })()`);
  await b.eval(`${frame}.querySelector('[data-testid=add-mode-path]').click()`);
  await b.waitFor(`${frame}.querySelector('[data-testid=onpath-path]')`);
  const path2Id = await b.eval(`[...${frame}.querySelectorAll('[data-testid=onpath-path] option')].find((o) => o.textContent.trim() === 'path2').value`);
  await setIn('onpath-path', path2Id);
  await setIn('onpath-action', 'deployStinger');
  await setIn('onpath-at', 40, 'input');
  await b.eval(`${frame}.querySelector('[data-testid=onpath-add]').click()`);
  await setIn('onpath-action', 'flywheelsOn');
  await setIn('onpath-at', 20, 'input');
  await b.eval(`${frame}.querySelector('[data-testid=onpath-add]').click()`);
  await b.waitFor(`${frame}.querySelectorAll('[data-testid=event-row]').length === 2`);
  // "Function": stinger up, after the last step.
  await b.eval(`${frame}.querySelector('[data-testid=add-mode-function]').click()`);
  await b.waitFor(`${frame}.querySelector('[data-testid=fn-action]')`);
  // Its settings are filled in before adding: a 1.25 s wait at the end (then removed again).
  await setIn('fn-action', 'wait');
  await b.waitFor(`${frame}.querySelector('[data-testid=fn-param]')`);
  check(await b.eval(`${frame}.querySelector('[data-testid=fn-param]').value === '0.5'`), 'Add shows the function\'s settings with their defaults (wait: 0.5 s)');
  await setIn('fn-param', 1.25, 'input');
  await b.eval(`${frame}.querySelector('[data-testid=fn-add]').click()`);
  await sleep(300);
  const added = await b.eval(`[...${frame}.querySelectorAll('[data-testid=route-steps] > li')].pop().innerText`);
  check(/wait/.test(added) && /1\.25 s/.test(added), `a wait set to 1.25 s before adding is added as 1.25 s (${added.replace(/\s+/g, ' ').trim()})`);
  await b.eval(`[...${frame}.querySelectorAll('[data-testid=route-steps] > li')].pop().querySelector('[title=Remove]').click()`);
  await sleep(200);
  await setIn('fn-action', 'stowStinger');
  await b.eval(`${frame}.querySelector('[data-testid=fn-add]').click()`);
  await sleep(300);
  const sequenceText = (await b.eval(`${frame}.querySelector('[data-testid=route-steps]').innerText`)).replace(/\s+/g, ' ');
  check(/Sequence/.test(await b.eval(`${frame}.querySelector('[data-testid=robot-panel]').innerText`)), 'the list is called Sequence');
  const rows = await b.eval(`[...${frame}.querySelectorAll('[data-testid=event-row]')].map((r) =>
    [r.dataset.at, r.dataset.action])`);
  check(JSON.stringify(rows) === JSON.stringify([['20', 'flywheelsOn'], ['40', 'deployStinger']]),
    `on-path functions are listed under their path, sorted by when they fire: ${JSON.stringify(rows)}`);
  const underPath2 = await b.eval(`(() => {
    const row = ${frame}.querySelector('[data-testid=event-row]').closest('li');
    return row.innerText.includes('follow') && row.innerText.includes('path2');
  })()`);
  check(underPath2, 'they sit under the "follow path2" step');
  const last = await b.eval(`[...${frame}.querySelectorAll('[data-testid=route-steps] > li')].pop().innerText`);
  check(/stinger up/.test(last), 'the Function was added at the end of the sequence');

  // Rows are read-only until the pencil is clicked.
  check(await b.eval(`${frame}.querySelectorAll('[data-testid=route-steps] input').length === 0`),
    'sequence rows show text, not boxes, until you click the pencil');
  // Edit the first step (wait 1 s): 0.5 s.
  await b.eval(`${frame}.querySelector('[data-testid=route-steps] > li [data-testid=step-edit]').click()`);
  await b.waitFor(`${frame}.querySelector('[data-testid=step-edit-param]')`);
  await setIn('step-edit-param', 0.5);
  await b.eval(`${frame}.querySelector('[data-testid=edit-done]').click()`);
  // Change the last step's function and back: stinger up -> intake off -> stinger up.
  const lastEdit = `[...${frame}.querySelectorAll('[data-testid=route-steps] > li')].pop()`;
  await b.eval(`${lastEdit}.querySelector('[data-testid=step-edit]').click()`);
  await b.waitFor(`${frame}.querySelector('[data-testid=step-edit-action]')`);
  await setIn('step-edit-action', 'intakeOff');
  check(await b.eval(`${lastEdit}.dataset.action === 'intakeOff'`), 'the pencil lets you change which function a step runs');
  await setIn('step-edit-action', 'stowStinger');
  await b.eval(`${frame}.querySelector('[data-testid=edit-done]').click()`);
  // Edit the 40% stinger-down function on path2 to 45%.
  await b.eval(`[...${frame}.querySelectorAll('[data-testid=event-row]')].find((r) => r.dataset.at === '40').querySelector('[data-testid=event-edit]').click()`);
  await b.waitFor(`${frame}.querySelector('[data-testid=event-edit-at]')`);
  await setIn('event-edit-at', 45);
  await b.eval(`${frame}.querySelector('[data-testid=edit-done]').click()`);
  const edited = await b.eval(`[...${frame}.querySelectorAll('[data-testid=event-row]')].map((r) => r.dataset.at + ' ' + r.dataset.action).join(', ')`);
  check(edited === '20 flywheelsOn, 45 deployStinger', `editing a function on a path changes its %: ${edited}`);
  const time = await b.eval(`${frame}.querySelector('[data-testid=route-time]').innerText`);
  check(/of 30 s/.test(time), `timeline estimate includes the waits: ${time}`);
  const markers = await b.eval(`${frame}.querySelectorAll('[id^="robot-event-"]').length`);
  check(markers === 2, 'both on-path functions show as markers on the field');
  // A path's top speed, as a % of the robot's: path2 at 40% (pencil on its "follow" row).
  const path2Row = `[...${frame}.querySelectorAll('[data-testid=route-steps] > li')].find((li) => /follow\\s+path2/.test(li.innerText))`;
  check(await b.eval(`${path2Row}.querySelector('[data-testid=path-speed-text]').textContent === 'full speed'`), 'paths go at full speed unless you set one');
  await b.eval(`${path2Row}.querySelector('[data-testid=path-speed-edit]').click()`);
  await b.waitFor(`${frame}.querySelector('[data-testid=path-speed]')`);
  await setIn('path-speed', 40);
  await b.eval(`${frame}.querySelector('[data-testid=path-speed-done]').click()`);
  check(await b.eval(`${path2Row}.querySelector('[data-testid=path-speed-text]').textContent === '40% speed'`), 'the pencil on a path sets its top speed: path2 at 40%');
  await b.screenshot(join(OUT, '4-paths.png'));
  await b.eval(`${frame}.querySelector('[data-testid=route-save]').click()`);
  await b.waitFor(`${frame}.querySelector('[data-testid=route-message]').innerText.includes('Saved autos/saturday.json')`);
  const route = (await api('/api/file?path=autos/saturday.json')).data;
  const path2 = route.paths.find((p) => p.name === 'path2');
  check(path2.events.length === 2 && path2.events[0].action === 'flywheelsOn' && path2.events[0].at === 0.2
    && path2.events[1].action === 'deployStinger' && path2.events[1].at === 0.45,
    'the saved route has flywheels on at 20% and stinger down at 45% of path2, in order');
  check(route.steps[0].action === 'wait' && route.steps[0].params.seconds === 0.5, 'and the edited wait of 0.5 s');
  check(path2.speedPercent === 40 && route.paths[0].speedPercent === undefined, 'and path2\'s speed (40%; path1 has none, full speed)');
  check(route.steps[route.steps.length - 1].action === 'stowStinger', 'and "stinger up" as the last step');
  check(route.paths[1].points.length === 3 && route.start.heading === 270, 'geometry and start pose survive the round trip');
  check(route.robot && route.robot.width > 0 && route.robot.length > 0 && route.robot.safetyMargin >= 0,
    `the route saves the robot size and safety margin: ${JSON.stringify(route.robot)}`);

  // Save as new: a second auton file, leaving the first one as it was.
  check((await b.eval(`${frame}.querySelector('[data-testid=robot-panel]').innerText`)).includes('Auton Sequence'),
    'the panel is called Auton Sequence');
  const before = (await api('/api/files')).data.find((f) => f.path === 'autos/saturday.json').hash;
  await b.eval(`(() => { document.getElementById('visualizer').contentWindow.prompt = () => 'Latest'; })()`);
  await b.eval(`${frame}.querySelector('[data-testid=route-save-as]').click()`);
  await b.waitFor(`${frame}.querySelector('[data-testid=route-message]').innerText.includes('Saved autos/Latest.json')`);
  const latest = (await api('/api/file?path=autos/Latest.json')).data;
  check(latest.name === 'Latest' && JSON.stringify(latest.paths) === JSON.stringify(route.paths)
    && JSON.stringify(latest.steps) === JSON.stringify(route.steps), 'Save as new… creates autos/Latest.json with the same auton');
  const after = (await api('/api/files')).data.find((f) => f.path === 'autos/saturday.json').hash;
  check(before === after, 'and leaves autos/saturday.json unchanged');
  check((await b.eval(`${frame}.querySelector('[data-testid=route-file]').innerText`)).includes('Editing autos/Latest.json'),
    'the editor now shows it is editing the new file');
  await b.eval(`(() => { const i = ${frame}.querySelector('[data-testid=route-name]'); i.value = 'Sunday'; i.dispatchEvent(new Event('input', { bubbles: true })); })()`);
  check((await b.eval(`${frame}.querySelector('[data-testid=route-file]').innerText`)).includes('will write a new file, autos/Sunday.json'),
    'renaming warns that Save will write a new file');

  // The robot refuses a broken route instead of keeping it.
  const bad = await api('/api/file?path=autos/broken.json', { method: 'PUT', body: JSON.stringify({ ...route, steps: [{ action: 'doABackflip' }] }) });
  check(bad.status === 400 && /Unknown action/.test(bad.data.error), 'the robot rejects a route with an unknown action');

  // ---- Known good snapshot, then break things, then restore ---------------------------------
  console.log('Snapshots and lock');
  await b.eval(`document.querySelector('[data-tab="robot"]').click()`);
  await b.waitFor(`document.querySelector('#snapshot-name')`);
  await b.eval(`(() => { document.querySelector('#snapshot-name').value = 'Before the kids'; document.querySelector('#snapshot-save').click(); })()`);
  await b.waitFor(`document.querySelector('#snapshots').innerText.includes('Before the kids')`);
  check(true, 'Save snapshot makes a known good copy');
  const goodRoute = (await api('/api/file?path=autos/saturday.json')).data;
  await api('/api/file?path=autos/saturday.json', { method: 'PUT', body: JSON.stringify({ ...goodRoute, steps: goodRoute.steps.slice(0, 1) }) });
  await api('/api/settings', { method: 'POST', body: { group: 'Intake', name: 'INTAKE_RPM', value: 1 } });
  await api('/api/settings/save', { method: 'POST' });
  await b.eval(`(() => { window.confirm = () => true; document.querySelector('#snapshots [data-snapshot]').click(); })()`);
  await b.waitFor(`document.querySelector('#toast').textContent.startsWith('Restored')`);
  const back = (await api('/api/file?path=autos/saturday.json')).data;
  const intake = (await api('/api/settings')).data.items.find((i) => i.name === 'INTAKE_RPM').value;
  check(back.steps.length === goodRoute.steps.length && intake === 700, 'Restore puts the auton and the settings back');

  // Simple lock: watch, but no changes, until the PIN.
  check(await b.eval(`document.querySelector('#lock').innerText.trim() === 'Unlocked' && !!document.querySelector('#lock svg')`),
    'the lock button shows an open lock and says Unlocked');
  const enterPin = (pin) => b.eval(`(() => {
    document.querySelector('#lock').click();
    document.querySelector('#lock-pin').value = '${pin}';
    document.querySelector('#lock-ok').click();
  })()`);
  await enterPin('2462');
  await b.waitFor(`document.body.classList.contains('locked') && !document.querySelector('#locked-banner').hidden`);
  check(await b.eval(`document.querySelector('#lock').innerText.trim() === 'Locked'`), 'Lock asks for the PIN once (shown as typed), then says Locked and is view only');
  const refused = await api('/api/settings/save', { method: 'POST' });
  check(refused.status === 423, 'while locked the robot refuses changes, even straight to the API');
  check((await api('/api/opmode', { method: 'POST', body: { action: 'stop' } })).status === 423, 'and OpMode control');
  check((await api('/api/settings')).status === 200, 'but watching still works');
  await enterPin('1111');
  await b.waitFor(`document.querySelector('#toast').textContent.includes('Wrong PIN')`, 8000);
  check(await b.eval(`document.body.classList.contains('locked')`), 'a wrong PIN keeps it locked');
  await enterPin('2462');
  await b.waitFor(`!document.body.classList.contains('locked')`, 8000);
  check((await api('/api/settings/save', { method: 'POST' })).status === 200, 'the right PIN unlocks it');

  // ---- 5. Run it as the autonomous ----------------------------------------------------------------
  console.log('Autonomous run');
  await api('/api/opmode', { method: 'POST', body: { action: 'init', name: 'Pedro Pathing Autonomous' } });
  await api('/api/opmode', { method: 'POST', body: { action: 'start' } });
  const { WebSocket: _ } = globalThis;
  const ws = new WebSocket(`ws://localhost:${PORT}/ws`);
  const seen = { steps: new Set(), stinger: new Set(), last: null, path2Peak: 0, mark: null };
  const decoder = new FrameDecoder();
  ws.onmessage = (event) => {
    const telemetry = decoder.handle(JSON.parse(event.data));
    if (!telemetry) return;
    for (const f of telemetry.frames) {
      if (f.s && f.s.Step) seen.steps.add(f.s.Step.replace(/^\d+\/\d+\s+/, ''));
      if (f.n.Stinger !== undefined) seen.stinger.add(f.n.Stinger);
      if (f.n.X !== undefined) seen.last = f;
      // Speed on path2, over 0.25 s steps.
      const onPath2 = f.s && f.s.Step ? /follow path2/.test(f.s.Step) : seen.onPath2;
      seen.onPath2 = onPath2;
      if (onPath2 && f.n.X !== undefined) {
        if (!seen.mark) seen.mark = f;
        else if (f.t - seen.mark.t >= 0.25) {
          seen.path2Peak = Math.max(seen.path2Peak, Math.hypot(f.n.X - seen.mark.n.X, f.n.Y - seen.mark.n.Y) / (f.t - seen.mark.t));
          seen.mark = f;
        }
      } else seen.mark = null;
    }
  };
  const deadline = Date.now() + 25000;
  while (Date.now() < deadline && !(seen.last && seen.last.n['Route done'] === 1)) await sleep(250);
  ws.close();
  check(seen.last && seen.last.n['Route done'] === 1, 'the autonomous finishes the whole route');
  check(seen.steps.has('follow path1') && seen.steps.has('feed on') && seen.steps.has('stinger up'),
    `it ran the steps from the file: ${[...seen.steps].join(' → ')}`);
  check(seen.stinger.has(saved.Stinger.STINGER_DOWN), 'the stinger went down part way along path2');
  const top40 = 68.47 * 0.4;   // ForesightConstants maxAchievableForwardVelocity
  check(seen.path2Peak > 5 && seen.path2Peak <= top40 * 1.15, `on path2 the robot keeps to 40% of its top speed, ${top40.toFixed(1)} in/s (fastest ${seen.path2Peak.toFixed(1)} in/s)`);
  const { X, Y, Heading } = seen.last.n;
  check(Math.abs(X - 13.2) < 1.5 && Math.abs(Y - 96.07) < 1.5 && Math.abs(Heading - 90) < 5,
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
