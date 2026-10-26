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
// Its SD card starts with only the hardware map and the saturday auton from the repo: no
// robot.json (code defaults) and none of the other autons robot-sync may have pulled.
const storage = mkdtempSync(join(tmpdir(), 'biobuzz-sd-'));
cpSync(join(REPO, 'robot-config/24621-Biobuzz.xml'), join(storage, '24621-Biobuzz.xml'));
mkdirSync(join(storage, 'autos'));
cpSync(join(REPO, 'autos/saturday.json'), join(storage, 'autos/saturday.json'));
const sim = spawn(join(REPO, 'sim/build/install/biobuzz-sim/bin/biobuzz-sim'),
  ['--port', String(PORT), '--storage', storage, '--no-seed'], { cwd: join(REPO, 'sim'), stdio: 'ignore' });
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
  await sleep(450);   // a few robot loops and gamepad messages, so the press is seen
  await mouse('mouseReleased', aButton.x, aButton.y);
  const flywheelsOn = async () => (await api('/api/settings')).data.items.find((i) => i.group === 'Flywheels' && i.name === 'FLYWHEELS_ON').value;
  for (let i = 0; i < 30 && await flywheelsOn(); i++) await sleep(100);
  check(await flywheelsOn() === false,
    'operator A from the drawer toggles the flywheels off, like on the Driver Station');
  await mouse('mousePressed', aButton.x, aButton.y);
  await sleep(250);
  await mouse('mouseReleased', aButton.x, aButton.y);
  // X flips the intake's switch the same way.
  const xButton = await b.eval(`(() => { const r = document.querySelector('[data-btn="x"]').getBoundingClientRect(); return { x: r.x + r.width / 2, y: r.y + r.height / 2 }; })()`);
  const intakeSwitch = async () => (await api('/api/settings')).data.items.find((i) => i.group === 'Intake' && i.name === 'INTAKE_ON').value;
  await mouse('mousePressed', xButton.x, xButton.y);
  await sleep(250);
  await mouse('mouseReleased', xButton.x, xButton.y);
  for (let i = 0; i < 30 && await intakeSwitch(); i++) await sleep(100);
  check(await intakeSwitch() === false, 'operator X flips INTAKE_ON, the switch the Tune tab shows');
  await mouse('mousePressed', xButton.x, xButton.y);
  await sleep(250);
  await mouse('mouseReleased', xButton.x, xButton.y);
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
  await api('/api/settings', { method: 'POST', body: { group: 'Intake', name: 'INTAKE_ON', value: false } });
  await api('/api/opmode', { method: 'POST', body: { action: 'init', name: 'Field Centric (Java)' } });
  const switches = (await api('/api/settings')).data.items.filter((i) => ['FLYWHEELS_ON', 'INTAKE_ON'].includes(i.name));
  check(switches.length === 2 && switches.every((i) => i.value === true), 'live switches (FLYWHEELS_ON, INTAKE_ON) turned off go back on at the next INIT');
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
    `freshly loaded settings all show a green (in sync) dot ${await b.eval(`[...document.querySelectorAll('#settings .dot.unsaved')].map((d) => d.closest('.setting-row').querySelector('.setting-name').innerText.split('\\n')[0]).join(', ')`)}`);
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
  check(!('FLYWHEELS_ON' in saved.Flywheels) && !('INTAKE_ON' in saved.Intake) && !('Simulator' in saved), 'live switches are not saved');
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
  // INIT puts a live switch back on: the Tune tab shows it without a refresh.
  await b.eval(`(() => { const t = document.querySelector('[data-key="Intake.INTAKE_ON"]'); t.checked = false; t.dispatchEvent(new Event('change', { bubbles: true })); t.blur(); })()`);
  await b.waitFor(`!document.querySelector('[data-key="Intake.INTAKE_ON"]').checked && (document.activeElement.blur(), true)`);
  const intakeOnNow = async () => (await api('/api/settings')).data.items.find((i) => i.name === 'INTAKE_ON').value;
  for (let i = 0; i < 30 && await intakeOnNow(); i++) await sleep(100);   // the robot has it off
  await api('/api/opmode', { method: 'POST', body: { action: 'init', name: 'Field Centric (Java)' } });
  await api('/api/opmode', { method: 'POST', body: { action: 'start' } });
  await b.waitFor(`document.querySelector('[data-key="Intake.INTAKE_ON"]').checked`, 4000);
  check(true, 'when INIT turns a live switch back on, the Tune tab shows it on by itself');

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
  // A scratch repo with the same files the test robot started with.
  for (const file of ['autos/saturday.json', 'robot-config/24621-Biobuzz.xml']) cpSync(join(REPO, file), join(scratch, file));
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
  console.log('Functions');
  // The functions come from the mechanisms' @AutoFunction methods: the same list as before.
  const functionNames = (await api('/api/actions')).data.map((a) => a.name).sort();
  check(JSON.stringify(functionNames) === JSON.stringify(['deployStinger', 'flywheelsOff', 'flywheelsOn', 'intakeOff', 'intakeOn',
    'shoot', 'stowStinger', 'turnTo', 'wait', 'waitForFlywheels']),
    `the Auton Sequence offers the mechanisms' functions plus the steps, nothing hidden (${functionNames.join(', ')})`);
  check(await b.eval(`!document.querySelector('[data-tab="paths"]') && !document.getElementById('visualizer')`),
    'the old Pedro tab is gone (the Auton tab is the path editor)');
  check((await fetch(`${URL}/visualizer/index.html`)).status === 404, 'and so is its built copy');
  // The auton the rest of the test runs: saturday with functions on path2, its speed and a last step.
  const saturday = (await api('/api/file?path=autos/saturday.json')).data;
  const route = JSON.parse(JSON.stringify(saturday));
  route.steps[0].params.seconds = 0.5;
  const path2 = route.steps.find((st) => st.drive && st.drive.name === 'path2').drive;
  path2.events = [{ at: 0.2, action: 'flywheelsOn' }, { at: 0.45, action: 'deployStinger' }];
  path2.speedPercent = 40;
  route.steps.push({ action: 'stowStinger' });
  const savedRoute = await api('/api/file?path=autos/saturday.json', { method: 'PUT', body: JSON.stringify(route, null, 2) });
  check(savedRoute.status === 200, 'the robot accepts saturday with flywheels on at 20% and stinger down at 45% of path2, at 40% speed, then stinger up');

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
  const lockedTries = [
    await api('/api/file?path=autos/saturday.json', { method: 'PUT', body: '{}' }),
    await api('/api/snapshots', { method: 'POST', body: { name: 'locked' } }),
    await api('/api/hardware/active', { method: 'POST', body: { name: 'CamBot.xml' } }),
    await api('/api/adb/restart', { method: 'POST' }),
  ];
  check(lockedTries.every((r) => r.status === 423), 'and saving files, snapshots, the hardware map and Restart adb');
  await b.eval(`document.querySelector('[data-tab="auton"]').click()`);
  await b.waitFor(`document.getElementById('auton-add-drive')`);
  const lockedLook = await b.eval(`(() => {
    const off = (el) => !!el && getComputedStyle(el).pointerEvents === 'none';
    return { add: off(document.getElementById('auton-add-drive')), save: off(document.getElementById('auton-save')),
      preview: off(document.getElementById('auton-preview')), file: off(document.getElementById('auton-file')) };
  })()`);
  const stepsLocked = await b.eval(`document.querySelectorAll('.auton-step').length`);
  await b.eval(`document.dispatchEvent(new KeyboardEvent('keydown', { key: 'z', ctrlKey: true }))`);
  check(lockedLook.add && lockedLook.save && !lockedLook.preview && !lockedLook.file
    && await b.eval(`document.querySelectorAll('.auton-step').length`) === stepsLocked,
    'the Auton tab is view only while locked (Preview and picking a file still work)');
  await b.eval(`document.querySelector('[data-tab="robot"]').click()`);
  await b.waitFor(`document.getElementById('hw-save')`);
  check(await b.eval(`getComputedStyle(document.querySelector('#hardware [data-field], #hw-new')).pointerEvents === 'none'`),
    'and so is the hardware map editor');
  await enterPin('1111');
  await b.waitFor(`document.querySelector('#toast').textContent.includes('Wrong PIN')`, 8000);
  check(await b.eval(`document.body.classList.contains('locked')`), 'a wrong PIN keeps it locked');
  await enterPin('2462');
  await b.waitFor(`!document.body.classList.contains('locked')`, 8000);
  check((await api('/api/settings/save', { method: 'POST' })).status === 200, 'the right PIN unlocks it');

  // ---- 5. Run it as the autonomous ----------------------------------------------------------------
  console.log('Autonomous run');
  await b.eval(`document.querySelector('[data-tab="live"]').click()`);
  await b.waitFor(`Number(document.getElementById('field').dataset.trail) > 1`);
  await api('/api/opmode', { method: 'POST', body: { action: 'init', name: 'Pedro Pathing Autonomous' } });
  await b.waitFor(`document.getElementById('field').dataset.trail === '0'`);
  check(true, 'INIT clears the line the robot drew on the Live field');
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

  // ---- 6. Version 3 auton files: waypoints, face a point, functions part way --------------------
  console.log('Auton files v3');
  const v2 = await api('/api/file?path=autos/old.json', { method: 'PUT', headers: { 'X-Base-Hash': '*' },
    body: JSON.stringify({ version: 2, name: 'old', start: { x: 0, y: 0, heading: 0 }, paths: [], steps: [] }) });
  check(v2.status === 400 && /old version 2/.test(v2.data.error) && /autos_v2_to_v3/.test(v2.data.error), `an old version 2 file is refused with a clear message (${v2.data.error})`);
  const waypointRoute = { version: 3, name: 'waypoints', start: { x: 56, y: 9, heading: 90 }, steps: [
    { drive: { name: 'through', segments: [{ to: [56, 50] }, { via: [[56, 80]], to: [90, 90] }],
      heading: { type: 'face', x: 72, y: 140 }, events: [{ at: 0.5, action: 'deployStinger' }] } },
    { drive: { name: 'back', segments: [{ to: [60, 40] }], heading: { type: 'turn', degrees: 0 }, speedPercent: 40,
      events: [{ at: 0.1, action: 'shoot', params: { seconds: 1 } }] } }] };
  check((await api('/api/file?path=autos/waypoints.json', { method: 'PUT', headers: { 'X-Base-Hash': '*' },
    body: JSON.stringify(waypointRoute, null, 2) })).status === 200, 'a version 3 auton with a waypoint and "face a point" is accepted');
  await api('/api/settings', { method: 'POST', body: { group: 'PedroAutonomous', name: 'ROUTE', value: 'waypoints' } });
  await api('/api/opmode', { method: 'POST', body: { action: 'init', name: 'Pedro Pathing Autonomous' } });
  await api('/api/opmode', { method: 'POST', body: { action: 'start' } });
  const ws2 = new WebSocket(`ws://localhost:${PORT}/ws`);
  const decoder2 = new FrameDecoder();
  const trace = [];
  let traceStep = '';
  ws2.onmessage = (event) => {
    const t = decoder2.handle(JSON.parse(event.data));
    if (t) for (const f of t.frames) {
      if (f.s && f.s.Step) traceStep = f.s.Step;
      if (f.n.X !== undefined) trace.push({ t: f.t, x: f.n.X, y: f.n.Y, stinger: f.n.Stinger, feed: f.n.Feeding, step: traceStep, done: f.n['Route done'] });
    }
  };
  const deadline2 = Date.now() + 15000;
  while (Date.now() < deadline2 && !(trace.length && trace.at(-1).done === 1)) await sleep(250);
  ws2.close();
  check(trace.length && trace.at(-1).done === 1, 'the waypoint auton finishes');
  const through = trace.filter((r) => /follow through/.test(r.step));
  const speedNear = (() => {
    let best = 0;
    for (let i = 1; i < through.length; i++) {
      const a = through[i - 1], z = through[i];
      if (z.t - a.t > 0 && Math.hypot(z.x - 56, z.y - 50) < 8) best = Math.max(best, Math.hypot(z.x - a.x, z.y - a.y) / (z.t - a.t));
    }
    return best;
  })();
  check(speedNear > 25, `it drives through the waypoint without stopping (${speedNear.toFixed(0)} in/s there)`);
  const firstDown = through.find((r) => r.stinger > 0.2);
  check(through[0].stinger < 0.2 && firstDown && firstDown.y > 45,
    `a function at 50% fires part way along the whole drive, not at its start (stinger down at y ${firstDown ? firstDown.y.toFixed(0) : '—'})`);
  const backDrive = trace.filter((r) => /follow back/.test(r.step));
  const feeding = backDrive.filter((r) => r.feed === 1);
  const feedSpeed = feeding.length > 1 ? Math.hypot(feeding.at(-1).x - feeding[0].x, feeding.at(-1).y - feeding[0].y) / (feeding.at(-1).t - feeding[0].t) : 0;
  check(feeding.length > 3 && feedSpeed > 10, `shoot placed on a drive feeds while the robot keeps driving (${feedSpeed.toFixed(0)} in/s while feeding)`);
  await api('/api/settings', { method: 'POST', body: { group: 'PedroAutonomous', name: 'ROUTE', value: 'saturday' } });

  // ---- 7. The Auton tab: our own editor ------------------------------------------------------------------
  console.log('Auton tab');
  await b.eval(`document.querySelector('[data-tab="auton"]').click()`);
  await b.waitFor(`document.querySelectorAll('#auton-file option').length >= 2`);
  await b.eval(`(() => { const s = document.getElementById('auton-file'); s.value = 'autos/saturday.json'; s.dispatchEvent(new Event('change')); })()`);
  await b.waitFor(`document.getElementById('auton-name').value === 'Saturday' || document.getElementById('auton-steps').innerText.includes('path2')`);
  const stepRows = await b.eval(`[...document.querySelectorAll('.auton-step:not(.start) .what')].map((e) => e.innerText)`);
  check(stepRows.length >= 4 && /Drive path1/.test(stepRows.join('|')) && /shoot/.test(stepRows.join('|')),
    `the Auton tab lists the start and every step, drives and functions in one list (${stepRows.length} steps)`);
  check(await b.eval(`document.querySelector('.auton-step.start').innerText.includes('Start')`) && await b.eval(`document.querySelector('[data-start="heading"]').value === '270'`),
    'the start pose is the first row, editable');
  const fieldClick = async (x, y, type = 'click', button = 'left') => {
    const r = await b.eval(`(() => { const r = document.getElementById('auton-field').getBoundingClientRect(); return { l: r.left, t: r.top, w: r.width, h: r.height }; })()`);
    const cx = r.l + (x / 144) * r.w, cy = r.t + ((144 - y) / 144) * r.h;
    const send = (t, extra = {}) => b.send('Input.dispatchMouseEvent', { type: t, x: cx, y: cy, button, buttons: t === 'mouseReleased' ? 0 : 1, clickCount: 1, ...extra });
    if (type === 'click') { await send('mousePressed'); await send('mouseReleased'); }
    if (type === 'dblclick') { await send('mousePressed'); await send('mouseReleased'); await send('mousePressed', { clickCount: 2 }); await send('mouseReleased', { clickCount: 2 }); }
    return { cx, cy, send };
  };
  // + Drive, then click the field: a drive from where the selected step ends.
  const lastStep = stepRows.length - 1;
  await b.eval(`document.querySelector('.auton-step[data-step="${lastStep}"]').click()`);
  await b.eval(`document.getElementById('auton-add-drive').click()`);
  await fieldClick(100, 100);
  await b.waitFor(`document.querySelectorAll('.auton-step:not(.start)').length === ${stepRows.length + 1}`);
  const newDrive = await b.eval(`document.querySelector('.auton-step.selected .what').innerText`);
  check(/Drive path3 to 100, 100 · keep facing/.test(newDrive), `+ Drive then a click on the field adds a drive there, keeping the heading it starts with (${newDrive})`);
  // Drag its end point.
  const { send } = await fieldClick(100, 100, 'none');
  await send('mousePressed');
  const r2 = await b.eval(`(() => { const r = document.getElementById('auton-field').getBoundingClientRect(); return { l: r.left, t: r.top, w: r.width, h: r.height }; })()`);
  await b.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: r2.l + (110 / 144) * r2.w, y: r2.t + ((144 - 104) / 144) * r2.h, button: 'left', buttons: 1 });
  await b.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: r2.l + (110 / 144) * r2.w, y: r2.t + ((144 - 104) / 144) * r2.h, button: 'left', buttons: 0 });
  await b.waitFor(`document.querySelector('.auton-step.selected .what').innerText.includes('to 110, 104')`);
  check(true, 'dragging the yellow end point moves the drive (snapped to half inches)');
  // Double-click the drive half way: a waypoint, then Smooth lines the bends up through it.
  const fromPose = await b.eval(`(() => { const t = document.querySelector('.auton-detail .muted').innerText.match(/at (-?[\\d.]+), (-?[\\d.]+)/); return [Number(t[1]), Number(t[2])]; })()`);
  await fieldClick((fromPose[0] + 110) / 2, (fromPose[1] + 104) / 2, 'dblclick');
  await b.waitFor(`document.querySelector('.auton-step.selected .what').innerText.includes('1 waypoint')`);
  check(true, 'double-clicking a drive adds a waypoint on it');
  await b.eval(`document.querySelector('[data-smooth="0"]').click()`);
  await b.waitFor(`document.querySelector('.auton-points').innerText.includes('1 bend')`);
  check(true, 'Smooth gives the waypoint lined-up bends on both sides');
  // Heading: face a point. It appears just ahead of the robot's end (16 in up) and follows the
  // mouse until a click places it.
  await b.eval(`(() => { const s = document.getElementById('drive-heading'); s.value = 'face'; s.dispatchEvent(new Event('change')); })()`);
  await b.waitFor(`document.getElementById('auton-hint').innerText.includes('Click the point to face')`);
  check(await b.eval(`document.getElementById('drive-face-x').value === '110' && document.getElementById('drive-face-y').value === '120'`),
    'Face a point: the point appears just above the robot at the end of the drive');
  const faceBox = await b.eval(`(() => { const r = document.getElementById('auton-field').getBoundingClientRect(); return { l: r.left, t: r.top, w: r.width, h: r.height }; })()`);
  await b.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: faceBox.l + (90 / 144) * faceBox.w, y: faceBox.t + ((144 - 130) / 144) * faceBox.h });
  await b.waitFor(`document.getElementById('drive-face-x').value === '90' && document.getElementById('drive-face-y').value === '130'`);
  check(true, 'and follows the mouse over the field without holding a button');
  await fieldClick(72, 140);
  await b.waitFor(`document.getElementById('drive-face-x') && document.getElementById('drive-face-x').value === '72' && document.getElementById('drive-face-y').value === '140'`);
  await b.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: faceBox.l + (60 / 144) * faceBox.w, y: faceBox.t + ((144 - 60) / 144) * faceBox.h });
  await sleep(200);
  check(await b.eval(`document.getElementById('drive-face-x').value === '72'`), 'a click places it; after that it stays put');
  // Face away from a point: placed the same way; the robot keeps its back to it.
  await b.eval(`(() => { const s = document.getElementById('drive-heading'); s.value = 'away'; s.dispatchEvent(new Event('change')); })()`);
  await b.waitFor(`document.getElementById('auton-hint').innerText.includes('Click the point')`);
  await fieldClick(72, 140);
  await b.waitFor(`document.getElementById('drive-heading') && document.getElementById('drive-heading').value === 'away' && document.getElementById('drive-face-y').value === '140'`);
  check(await b.eval(`document.querySelector('.auton-step.selected .what').innerText.includes('face away from a point')`),
    'Face away from a point: placed like face, and the step says so');
  // Robot size (in the Auton file card): the outlines on the field use it.
  const widthBefore = await b.eval(`document.getElementById('auton-robot-width').value`);
  await b.eval(`(() => { const i = document.getElementById('auton-robot-length'); i.value = '17.5'; i.dispatchEvent(new Event('change')); })()`);
  await b.waitFor(`document.getElementById('auton-robot-length').value === '17.5'`);
  await b.eval(`(() => { const i = document.getElementById('auton-robot-width'); i.value = '40'; i.dispatchEvent(new Event('change')); })()`);
  await sleep(150);
  check(await b.eval(`document.getElementById('auton-robot-width').value === '${widthBefore}' && document.getElementById('auton-robot-length').value === '17.5'`),
    'robot size can be set (front to back 17.5 in); an impossible size (40 in) is refused');
  // A function on the drive, then undo / redo.
  await b.eval(`document.getElementById('event-add').click()`);
  await b.waitFor(`document.querySelector('.auton-menu [data-pick="deployStinger"]')`);
  check(await b.eval(`!!document.querySelector('.auton-menu [data-pick="shoot"]') && !document.querySelector('.auton-menu [data-pick="turnTo"]')`),
    '+ Function on this drive opens a list: timed ones like shoot too, not turn to');
  await b.eval(`document.querySelector('.auton-menu [data-pick="deployStinger"]').click()`);
  await b.waitFor(`document.querySelector('[data-event-at="0"]') && document.querySelector('[data-event-at="0"]').value === '50'`);
  check(true, 'a function on the drive starts at 50% (a ◆ to drag along it)');
  await b.eval(`document.getElementById('auton-undo').click()`);
  await b.waitFor(`!document.querySelector('[data-event-at="0"]')`);
  await b.eval(`document.getElementById('auton-redo').click()`);
  await b.waitFor(`!!document.querySelector('[data-event-at="0"]')`);
  check(true, 'undo and redo');
  // A timed function on the drive: it starts at its % and the robot keeps driving.
  await b.eval(`document.getElementById('event-add').click()`);
  await b.waitFor(`document.querySelector('.auton-menu [data-pick="shoot"]')`);
  await b.eval(`document.querySelector('.auton-menu [data-pick="shoot"]').click()`);
  await b.waitFor(`document.querySelectorAll('.event-row').length === 2`);
  check(await b.eval(`document.querySelectorAll('.event-row')[1].innerText.includes('The robot keeps driving')`), 'shoot on a drive says the robot keeps driving, and where it is done');
  await b.eval(`document.getElementById('auton-add-function').click()`);
  await b.waitFor(`document.querySelector('.auton-menu [data-pick="stowStinger"]')`);
  await b.eval(`document.querySelector('.auton-menu [data-pick="stowStinger"]').click()`);
  await b.waitFor(`document.querySelector('.auton-step.selected .what').innerText.includes('stinger up')`);
  check(true, '+ Function opens a list of functions, and adds the one picked after the selected step');
  // Preview: the robot drives the auton on the field.
  await b.eval(`document.getElementById('auton-preview').click()`);
  await b.waitFor(`document.getElementById('auton-preview-time') && parseFloat(document.getElementById('auton-preview-time').textContent) > 0.5`);
  check(await b.eval(`document.getElementById('auton-preview').textContent.includes('Stop')`) && await b.eval(`!!document.querySelector('.auton-step.playing')`),
    'Preview plays the auton on the field and marks the step it is on');
  const bar = await b.eval(`document.getElementById('auton-scrub').style.background`);
  check(/linear-gradient/.test(bar) && /245, 179, 1|f5b301/i.test(bar) && /139, 152, 168|8b98a8/i.test(bar),
    'the preview bar is coloured by step: drives in their field colour, functions in grey');
  check(/rgba\(\d+, \d+, \d+, 0\.4\d*\)/.test(bar),
    'a drive that must face another way first shows a paler stretch: the robot turns in place, then drives (as on the robot)');
  await b.eval(`document.getElementById('auton-preview').click()`);
  await b.eval(`document.getElementById('auton-preview-close').click()`);
  // Unsaved changes survive a reload (kept in this browser).
  const stepsBefore = await b.eval(`document.querySelectorAll('.auton-step:not(.start)').length`);
  await b.goto(URL);
  await b.eval(`document.querySelector('[data-tab="auton"]').click()`);
  await b.waitFor(`document.getElementById('auton-discard')`);
  check(await b.eval(`document.querySelectorAll('.auton-step:not(.start)').length`) === stepsBefore, 'unsaved changes are still there after reloading the page, with a Discard button');
  await b.screenshot(join(OUT, '6-auton-editor.png'));
  // Save as a new auton: the robot accepts it, and it runs.
  await b.eval(`(() => { window.prompt = () => 'editor-test'; document.getElementById('auton-save-as').click(); })()`);
  await b.waitFor(`document.querySelector('#toast').textContent.includes('Saved autos/editor-test.json')`);
  const made = (await api('/api/file?path=autos/editor-test.json')).data;
  const madeDrive = made.steps.filter((st) => st.drive).at(-1).drive;
  check(made.version === 3 && madeDrive.segments.length === 2 && madeDrive.segments[0].via && madeDrive.segments[1].via
    && madeDrive.heading.type === 'face' && madeDrive.heading.away === true && made.robot.length === 17.5 && madeDrive.events[0].action === 'deployStinger' && madeDrive.events[1].action === 'shoot' && made.steps.at(-1).action === 'stowStinger',
    'Save as… writes a version 3 auton the robot accepts: the waypoint, its bends, face away from a point, the robot size, the function');
  await api('/api/settings', { method: 'POST', body: { group: 'PedroAutonomous', name: 'ROUTE', value: 'editor-test' } });
  const editorSince = (await api('/api/state')).data.time;   // a new connection first gets the last seconds of frames
  const editorInit = await api('/api/opmode', { method: 'POST', body: { action: 'init', name: 'Pedro Pathing Autonomous' } });
  check(editorInit.status === 200, `the edited auton passes INIT${editorInit.data && editorInit.data.error ? `: ${editorInit.data.error}` : ''}`);
  await api('/api/opmode', { method: 'POST', body: { action: 'start' } });
  const ws3 = new WebSocket(`ws://localhost:${PORT}/ws`);
  const decoder3 = new FrameDecoder();
  let editorDone = null, editorRunning = false;   // ignore the last run's frames until this one starts
  ws3.onmessage = (event) => { const t = decoder3.handle(JSON.parse(event.data)); if (t) for (const f of t.frames) {
    if (f.t < editorSince) continue;
    if (f.n['Route done'] === 0) editorRunning = true;
    else if (editorRunning && f.n['Route done'] === 1) editorDone = f;
  } };
  const deadline3 = Date.now() + 25000;
  while (Date.now() < deadline3 && !editorDone) await sleep(250);
  ws3.close();
  check(editorDone && Math.hypot(editorDone.n.X - 110, editorDone.n.Y - 104) < 3,
    `the simulator runs the edited auton to its end (${editorDone ? `${editorDone.n.X.toFixed(1)}, ${editorDone.n.Y.toFixed(1)}` : 'not finished'})`);
  await api('/api/settings', { method: 'POST', body: { group: 'PedroAutonomous', name: 'ROUTE', value: 'saturday' } });

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
