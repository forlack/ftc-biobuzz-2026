// Tune tab: every setting, live editing, Save to robot.
import { api, escapeHtml, toast } from './api.js';

export class TuneTab {
  constructor() {
    this.items = [];
    document.getElementById('settings-save').addEventListener('click', () => this.save());
    document.getElementById('settings-reload').addEventListener('click', () => this.reload());
    document.getElementById('settings-download').addEventListener('click', () => this.download());
  }

  async refresh() {
    try {
      this.show(await api('/api/settings'));
    } catch (error) {
      this.status(`Could not read settings: ${error.message}`, 'bad');
    }
  }

  show(data) {
    this.items = data.items;
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
    for (const [group, items] of groups) {
      const card = document.createElement('div');
      card.className = 'card';
      card.innerHTML = `<div class="card-head"><h3>${escapeHtml(group)}</h3><span class="muted">${items.length}</span></div>`;
      for (const item of items) card.appendChild(this.row(item));
      holder.appendChild(card);
    }
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
      tag = '<span class="dot unsaved" title="Changed on the robot but not saved: press Save to robot to keep it"></span>';
    }
    const notDefault = item.default !== null && !same(item.value, item.default);
    row.innerHTML = `<div class="setting-name" title="${escapeHtml(key)}">${escapeHtml(item.name)}
        <small>default ${escapeHtml(format(item.default))}${notDefault ? ' · changed' : ''}</small></div>
      <div class="control"></div>${tag}`;
    const control = row.querySelector('.control');
    if (item.type === 'boolean') {
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
    try {
      this.show(await api('/api/settings', { method: 'POST', body: { group: item.group, name: item.name, value } }));
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
