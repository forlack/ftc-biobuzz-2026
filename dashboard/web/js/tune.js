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
    this.status(data.status, data.status.startsWith('SAVE FAILED') ? 'bad' : '');
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
    const unsaved = item.saved && item.inFile !== null && !same(item.value, item.inFile);
    const neverSaved = item.saved && item.inFile === null;
    let tag = '';
    if (!item.saved) tag = '<span class="tag live" title="A live switch: changes now, never saved">live</span>';
    else if (unsaved || neverSaved) tag = '<span class="tag unsaved" title="Not in robot.json yet: press Save to robot">unsaved</span>';
    else tag = '<span class="tag saved" title="Matches robot.json">saved</span>';
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

function same(a, b) {
  if (typeof a === 'number' && typeof b === 'number') return Math.abs(a - b) < 1e-9;
  return String(a) === String(b);
}

function format(value) {
  return value === null || value === undefined ? '—' : String(value);
}
