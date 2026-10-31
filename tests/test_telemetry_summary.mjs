import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
const source = (await readFile(new URL('../dashboard/web/js/live.js', import.meta.url), 'utf8'))
  .replace("import { escapeHtml } from './api.js';", 'const escapeHtml = String;');
const { LiveTab } = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));
const elements = {
  'telemetry-filter': { value: '' },
  'telemetry-details': { checked: false },
  telemetry: { innerHTML: '' },
};
globalThis.document = { getElementById: id => elements[id] };
const tab = { latest: { 'Pollen RPM': 3650, 'Time drive ms': 2.5, 'Battery V': 12.2 },
  latestText: { Alliance: 'BLUE', 'Hive ignored': 'wrong alliance', 'Pollen hub PIDF': 'diagnostic' } };
const render = () => { LiveTab.prototype.renderTable.call(tab); return elements.telemetry.innerHTML; };
let html = render();
assert.ok(html.includes('Alliance') && html.includes('Pollen RPM') && html.includes('Battery V'));
assert.ok(!html.includes('Time drive ms') && !html.includes('Pollen hub PIDF') && !html.includes('Hive ignored'));
elements['telemetry-details'].checked = true;
html = render();
assert.ok(html.includes('Time drive ms') && html.includes('Pollen hub PIDF'));
assert.ok(!html.includes('Hive ignored'));
elements['telemetry-details'].checked = false;
elements['telemetry-filter'].value = 'time drive';
html = render();
assert.ok(html.includes('Time drive ms') && !html.includes('Pollen RPM'));
assert.equal(tab.latest['Time drive ms'], 2.5); // Filtering leaves chart samples intact.
console.log('PASS: concise telemetry, full diagnostics, search, chart values preserved');
