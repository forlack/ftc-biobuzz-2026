// Tune tab: every setting, live editing, Save to robot.
import { api, escapeHtml, toast } from './api.js';

/** Text settings that take one of a few values: shown as a dropdown. Keyed "Group.NAME". */
const CHOICES = {
  'Alliance.ALLIANCE': ['RED', 'BLUE'],
  'Vision.HIVE_TARGET': ['ANY', 'SCORING', 'AUDIENCE'],
  'Simulator.RED_HIVE_UP': ['AUDIENCE', 'SCORING'],
  'Simulator.BLUE_HIVE_UP': ['SCORING', 'AUDIENCE'],
};

// Groups hidden or shown in this browser, overriding the code's @Configurable.Hidden.
const HIDDEN_KEY = 'biobuzz.hiddenGroups';
function hiddenOverrides() {
  try { return JSON.parse(localStorage.getItem(HIDDEN_KEY)) || {}; } catch { return {}; }
}
function setHidden(group, hidden) {
  const overrides = hiddenOverrides();
  overrides[group] = hidden;
  try { localStorage.setItem(HIDDEN_KEY, JSON.stringify(overrides)); } catch { /* private window */ }
}

// The order of the cards, as dragged in this browser. Groups not in it keep the code's order, after these.
const ORDER_KEY = 'biobuzz.groupOrder';
function groupOrder() {
  try { return JSON.parse(localStorage.getItem(ORDER_KEY)) || []; } catch { return []; }
}
function saveGroupOrder(order) {
  try { localStorage.setItem(ORDER_KEY, JSON.stringify(order)); } catch { /* private window */ }
}
function sortGroups(groups) {
  const order = groupOrder();
  const rank = (group) => {
    const at = order.indexOf(group);
    return at < 0 ? order.length : at;
  };
  return [...groups].sort(([a], [b]) => rank(a) - rank(b));
}

export class TuneTab {
  constructor() {
    this.items = [];
    document.getElementById('settings-save').addEventListener('click', () => this.save());
    document.getElementById('settings-reload').addEventListener('click', () => this.reload());
    document.getElementById('settings-download').addEventListener('click', () => this.download());
    document.getElementById('settings-history').addEventListener('click', () => {
      window.dispatchEvent(new CustomEvent('biobuzz:history', { detail: 'settings/robot.json' }));
    });
    this.bindDragging();
  }

  // Drag a card by its ⠿ grip to move it. The cards flow down columns, so the top or bottom
  // half of the card under the pointer says before or after it.
  bindDragging() {
    const holder = document.getElementById('settings');
    holder.addEventListener('dragover', (event) => {
      if (!this.dragging) {
        return;
      }
      event.preventDefault();
      const target = event.target.closest('.settings > .card');
      if (!target || target === this.dragging) {
        return;
      }
      const box = target.getBoundingClientRect();
      const after = event.clientY > box.top + box.height / 2;
      holder.insertBefore(this.dragging, after ? target.nextSibling : target);
    });
    holder.addEventListener('drop', (event) => {
      event.preventDefault();
      this.finishDrag();
    });
  }

  finishDrag() {
    if (!this.dragging) {
      return;
    }
    this.dragging.classList.remove('dragging');
    this.dragging = null;
    // Shown cards in their new order; hidden groups keep their old place after them.
    const shown = [...document.querySelectorAll('#settings > .card')].map((card) => card.dataset.group);
    saveGroupOrder([...shown, ...groupOrder().filter((group) => !shown.includes(group))]);
    if (this.waiting) {
      const data = this.waiting;
      this.waiting = null;
      this.show(data);
    }
  }

  async refresh() {
    // Only the newest request draws: an older answer arriving late would show old values.
    const mine = this.request = (this.request || 0) + 1;
    try {
      // The autons on the robot, for PedroAutonomous.ROUTE's dropdown.
      const files = await api('/api/files').catch(() => []);
      this.routes = files.map((f) => f.path).filter((p) => /^autos\/.+\.json$/.test(p))
        .map((p) => p.slice('autos/'.length, -'.json'.length));
      const data = await api('/api/settings');
      if (mine === this.request) this.show(data);
    } catch (error) {
      this.status(`Could not read settings: ${error.message}`, 'bad');
    }
  }

  show(data) {
    // Drawing again would drop the card being dragged: draw when it is let go.
    if (this.dragging) {
      this.waiting = data;
      return;
    }
    this.items = data.items;
    this.fingerprint = data.fingerprint;   // what is on screen, compared with the robot's in app.js
    const unsaved = this.items.filter((i) => i.saved && !inSync(i)).length;
    const status = unsaved ? `${unsaved} change${unsaved === 1 ? '' : 's'} not saved · ${data.status}` : `all saved · ${data.status}`;
    this.status(status, data.status.startsWith('SAVE FAILED') ? 'bad' : unsaved ? 'warn' : 'ok');
    document.getElementById('settings-save').classList.toggle('pulse', unsaved > 0);
    document.getElementById('settings-warnings').innerHTML = (data.warnings || [])
      .map((w) => `<div class="warning">${escapeHtml(w)}</div>`).join('');
    const groups = new Map();
    for (const item of this.items) {
      if (!groups.has(item.group)) groups.set(item.group, []);
      groups.get(item.group).push(item);
    }
    const holder = document.getElementById('settings');
    const focused = document.activeElement && document.activeElement.dataset.key;
    holder.innerHTML = '';
    const overrides = hiddenOverrides();
    const hidden = [];
    for (const [group, items] of sortGroups(groups)) {
      if (overrides[group] ?? items[0].hidden) {
        hidden.push([group, items]);
        continue;
      }
      const card = document.createElement('div');
      card.className = 'card';
      card.dataset.group = group;
      card.innerHTML = `<div class="card-head"><h3><span class="grip" draggable="true" title="Drag to move this card (remembered in this browser)">⠿</span>${escapeHtml(group)}</h3>
        <span class="muted">${items.length} <button class="btn small hide-group" title="Hide this group on the Tune tab (this browser). It still loads and saves.">Hide</button></span></div>`;
      card.querySelector('.hide-group').addEventListener('click', () => { setHidden(group, true); this.show(data); });
      card.querySelector('.grip').addEventListener('dragstart', (event) => {
        this.dragging = card;
        card.classList.add('dragging');
        event.dataTransfer.effectAllowed = 'move';
        event.dataTransfer.setData('text/plain', group);
        event.dataTransfer.setDragImage(card, 20, 20);
      });
      card.querySelector('.grip').addEventListener('dragend', () => this.finishDrag());
      for (const item of items) card.appendChild(this.row(item));
      holder.appendChild(card);
    }
    const shelf = document.getElementById('settings-hidden');
    shelf.hidden = hidden.length === 0;
    shelf.innerHTML = hidden.length ? `<span class="muted">Hidden:</span> ` + hidden.map(([group, items]) => {
      const changed = items.filter((i) => i.saved && !inSync(i)).length;
      return `<span class="hidden-group">${escapeHtml(group)} <span class="muted">(${items.length}${changed ? `, <b class="warn-text">${changed} not saved</b>` : ''})</span>
        <button class="btn small" data-show="${escapeHtml(group)}">Show</button></span>`;
    }).join(' ') : '';
    shelf.querySelectorAll('[data-show]').forEach((button) => button.addEventListener('click', () => {
      setHidden(button.dataset.show, false);
      this.show(data);
    }));
    if (focused) {
      const again = holder.querySelector(`[data-key="${CSS.escape(focused)}"]`);
      if (again) again.focus();
    }
  }

  row(item) {
    const row = document.createElement('div');
    row.className = 'setting-row';
    const key = `${item.group}.${item.name}`;
    let tag = '';
    if (!item.saved) {
      tag = '<span class="tag live" title="A live switch: changes now, never saved">live</span>';
    } else if (inSync(item)) {
      tag = `<span class="dot synced" title="${item.inFile === null ? 'Same as the code: nothing to save' : 'Same as robot.json on the robot'}"></span>`;
    } else {
      const savedValue = item.inFile !== null ? item.inFile : item.default;
      tag = `<span class="unsaved-cell"><button class="undo" data-undo="${escapeHtml(key)}"
          title="Undo: back to the saved value (${escapeHtml(format(savedValue))})">↶</button>
        <span class="dot unsaved" title="Changed on the robot but not saved: press Save to robot to keep it"></span></span>`;
    }
    // The code's value, and what robot.json sets instead (robot.json wins at every INIT).
    const inFileDiffers = item.saved && item.inFile !== null && !same(item.inFile, item.default);
    row.innerHTML = `<div class="setting-name" title="${escapeHtml(key)}">${escapeHtml(item.name)}
        <small>code default ${escapeHtml(format(item.default))}${inFileDiffers ? ` · robot.json: ${escapeHtml(format(item.inFile))}` : ''}</small></div>
      <div class="control"></div>${tag}`;
    const undo = row.querySelector('[data-undo]');
    if (undo) undo.addEventListener('click', () => this.set(item, item.inFile !== null ? item.inFile : item.default));
    const control = row.querySelector('.control');
    if (item.group === 'PedroAutonomous' && item.name === 'ROUTE') {
      // Which auton file runs at INIT: pick from the autons on the robot.
      const routes = [...new Set([...(this.routes || []), item.value])];
      control.innerHTML = `<select data-key="${escapeHtml(key)}" class="route-select">${routes.map((r) =>
        `<option value="${escapeHtml(r)}" ${r === item.value ? 'selected' : ''}>${escapeHtml(r)}${
          (this.routes || []).includes(r) ? '' : ' (missing)'}</option>`).join('')}</select>`;
      control.querySelector('select').addEventListener('change', (event) => this.set(item, event.target.value));
      row.querySelector('.setting-name small').textContent = 'the auton that runs at INIT (autos/' + item.value + '.json)';
    } else if (CHOICES[key]) {
      // A setting with a few fixed values (Alliance RED / BLUE, ...): a dropdown.
      const choices = [...new Set([...CHOICES[key], item.value])];
      control.innerHTML = `<select data-key="${escapeHtml(key)}" class="route-select">${choices.map((c) =>
        `<option value="${escapeHtml(c)}" ${c === item.value ? 'selected' : ''}>${escapeHtml(c)}</option>`).join('')}</select>`;
      control.querySelector('select').addEventListener('change', (event) => this.set(item, event.target.value));
    } else if (item.type === 'boolean') {
      control.innerHTML = `<label class="toggle"><input type="checkbox" data-key="${escapeHtml(key)}" ${item.value ? 'checked' : ''}><span></span></label>`;
      control.querySelector('input').addEventListener('change', (event) => this.set(item, event.target.checked));
    } else {
      const input = document.createElement('input');
      input.type = item.type === 'string' ? 'text' : 'number';
      if (item.type !== 'string') input.step = 'any';
      input.value = item.value;
      input.dataset.key = key;
      const commit = () => {
        if (String(input.value) === String(item.value)) return;
        if (item.type === 'string') this.set(item, input.value);
        else if (input.value.trim() === '' || !Number.isFinite(Number(input.value))) {
          toast(`${item.name} needs a number`, true);
          input.value = item.value;
        } else this.set(item, Number(input.value));
      };
      input.addEventListener('change', commit);
      input.addEventListener('keydown', (event) => { if (event.key === 'Enter') input.blur(); });
      control.appendChild(input);
    }
    return row;
  }

  async set(item, value) {
    const mine = this.request = (this.request || 0) + 1;
    try {
      const data = await api('/api/settings', { method: 'POST', body: { group: item.group, name: item.name, value } });
      if (mine === this.request) this.show(data);
    } catch (error) {
      toast(error.message, true);
      this.refresh();
    }
  }

  async save() {
    try {
      const data = await api('/api/settings/save', { method: 'POST' });
      this.show(data);
      if (data.status.startsWith('SAVE FAILED')) toast(data.status, true);
      else toast('Saved robot.json on the robot');
    } catch (error) {
      toast(error.message, true);
    }
  }

  async reload() {
    try {
      this.show(await api('/api/settings/reload', { method: 'POST' }));
      toast('Reloaded robot.json');
    } catch (error) {
      toast(error.message, true);
    }
  }

  async download() {
    try {
      const response = await fetch('/api/file?path=settings/robot.json');
      if (!response.ok) throw new Error('No robot.json on the robot yet. Save first.');
      const link = document.createElement('a');
      link.href = URL.createObjectURL(await response.blob());
      link.download = 'robot.json';
      link.click();
    } catch (error) {
      toast(error.message, true);
    }
  }

  status(text, kind = '') {
    const element = document.getElementById('settings-status');
    element.textContent = text;
    element.className = `status ${kind}`;
  }
}

/**
 * In sync: the robot is running what robot.json says. With no robot.json yet (or a setting
 * missing from it) the robot runs the code's value, so matching the default counts as in sync.
 */
function inSync(item) {
  return same(item.value, item.inFile !== null ? item.inFile : item.default);
}

function same(a, b) {
  if (typeof a === 'number' && typeof b === 'number') return Math.abs(a - b) < 1e-9;
  return String(a) === String(b);
}

function format(value) {
  return value === null || value === undefined ? '—' : String(value);
}
